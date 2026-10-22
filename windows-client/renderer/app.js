/**
 * app.js — 渲染页主逻辑：WS 客户端 + 协议处理 + 每帧映射 + UI + Electron 窗口开关
 *
 * 数据流：WebSocket(track) → 按 seq 丢旧帧 → mapping.createMapper().apply(bs, pose)
 *        → stage.setTargetParams(params) → InternalModel.beforeModelUpdate 钩子里写 cubism 参数
 *
 * 对外测试钩子（verifier 用）：
 *   window.__vfc      = { ready, freeze(v), applyTrack({bs,pose}), state(), connect(url), disconnect(), setModel(name), ... }
 *   window.__vfcDebug = { version, ready, model, wsUrl, connected, frames, seq, fps, latencyMs, lastTrack, params, angles, ... }
 */
import { createMapper, BS_INDEX } from "./mapping.js";
import { Live2DStage, checkEnvironment } from "./live2d.js";

const DEFAULT_WS_URL = "ws://127.0.0.1:8765/ws";
const RECONNECT_DELAYS_MS = [500, 1000, 2000, 5000];
const HEARTBEAT_INTERVAL_MS = 5000;
const STALE_TIMEOUT_MS = 20000;
const SMOOTHING = 0.6;

const STORE_KEYS = {
  ws: "vfc.wsUrl",
  model: "vfc.model",
  collapsed: "vfc.uiCollapsed",
};

/** /api/models 不可用时的兜底（仓库里固定这 4 个模型）。 */
const FALLBACK_MODELS = [
  { name: "ATRI", entry: "/models/ATRI/ATRI.model3.json" },
  { name: "sensei", entry: "/models/sensei/sensei.model3.json" },
  { name: "Yuuka", entry: "/models/Yuuka/Yuuka.model3.json" },
  { name: "zzzz", entry: "/models/zzzz/zz.model3.json" },
];

const query = new URLSearchParams(window.location.search);
const native = window.vfcNative ?? null; // Electron preload 注入；纯 Web 下为 null

const ui = {
  root: document.getElementById("ui"),
  dot: document.getElementById("conn-dot"),
  statusText: document.getElementById("status-text"),
  collapse: document.getElementById("btn-collapse"),
  panelBody: document.getElementById("panel-body"),
  wsInput: document.getElementById("ws-url"),
  btnConnect: document.getElementById("btn-connect"),
  device: document.getElementById("meta-device"),
  inFps: document.getElementById("meta-in-fps"),
  renderFps: document.getElementById("meta-render-fps"),
  rtt: document.getElementById("meta-rtt"),
  seq: document.getElementById("meta-seq"),
  dropped: document.getElementById("meta-dropped"),
  modelSelect: document.getElementById("model-select"),
  btnReload: document.getElementById("btn-reload"),
  tgTransparent: document.getElementById("tg-transparent"),
  tgBorderless: document.getElementById("tg-borderless"),
  tgOnTop: document.getElementById("tg-ontop"),
  tgThrough: document.getElementById("tg-through"),
  nativeHint: document.getElementById("native-hint"),
  btnRecalib: document.getElementById("btn-recalib"),
  btnHide: document.getElementById("btn-hide"),
  btnQuit: document.getElementById("btn-quit"),
  debugBox: document.getElementById("debug-box"),
  debugGrid: document.getElementById("debug-grid"),
  errorBox: document.getElementById("error-box"),
};

const state = {
  wsUrl: query.get("ws") || storeGet(STORE_KEYS.ws) || DEFAULT_WS_URL,
  models: FALLBACK_MODELS.slice(),
  modelName: null,
  connected: false,
  statusText: "正在启动…",
  device: "",
  phoneApp: "",
  frames: 0,
  dropped: 0,
  staleFrames: 0,
  badMessages: 0,
  seq: null,
  lastTrack: null,
  lastResult: null,
  rttMs: null,
  inputFps: 0,
  renderFps: 0,
  error: null,
  frozen: false,
  loadingModel: false,
  phoneFps: null,
  fpsWindowFrames: 0,
  fpsWindowStart: performance.now(),
};

const mapper = createMapper();
const stage = new Live2DStage({ canvas: document.getElementById("live2d"), smoothing: SMOOTHING });

/** verifier / 调试读取的稳定对象（原地更新，引用不变）。 */
const debugState = {
  version: 1,
  ready: false,
  model: null,
  wsUrl: state.wsUrl,
  connected: false,
  frames: 0,
  seq: null,
  fps: 0,
  renderFps: 0,
  latencyMs: null,
  lastTrack: null,
  params: null,
  normalized: null,
  raw: null,
  angles: { x: 0, y: 0, z: 0 },
  engine: {},
  dropped: 0,
  frozen: false,
  error: null,
  missingParams: [],
};
window.__vfcDebug = debugState;

// ---------------------------------------------------------------- 小工具

function storeGet(key) {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function storeSet(key, value) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    /* 隐私模式 / file:// 下忽略 */
  }
}

function fmt(value, digits = 2) {
  return typeof value === "number" && Number.isFinite(value) ? value.toFixed(digits) : "-";
}

function setStatus(text, phase = "idle") {
  state.statusText = text;
  ui.statusText.textContent = text;
  ui.statusText.title = text;
  ui.dot.dataset.state = phase;
}

function showError(message) {
  state.error = message;
  debugState.error = message;
  ui.errorBox.hidden = false;
  ui.errorBox.textContent = message;
}

function clearError() {
  state.error = null;
  debugState.error = null;
  ui.errorBox.hidden = true;
  ui.errorBox.textContent = "";
}

function connPhase() {
  return state.connected ? "connected" : "error";
}

/** 透明模式同时作用在 html 与 body（html 背景不透明会让透明窗口露深色底）。 */
function setTransparentMode(value) {
  document.documentElement.classList.toggle("is-transparent", Boolean(value));
  document.body.classList.toggle("is-transparent", Boolean(value));
}

// ---------------------------------------------------------------- WS 客户端

class TrackerClient {
  constructor(handlers) {
    this.handlers = handlers;
    this.url = null;
    this.socket = null;
    this.attempt = 0;
    this.reconnectTimer = null;
    this.heartbeatTimer = null;
    this.lastInboundAt = 0;
    this.lastSeq = -1;
    this.pingSentAt = new Map();
    this.closedByUser = false;
  }

  get connected() {
    return this.socket?.readyState === WebSocket.OPEN;
  }

  connect(url) {
    this.url = url;
    this.closedByUser = false;
    this.attempt = 0;
    this.lastSeq = -1;
    this.clearReconnect();
    this.openSocket();
  }

  disconnect() {
    this.closedByUser = true;
    this.clearReconnect();
    this.clearHeartbeat();
    if (this.socket) {
      this.socket.onclose = null;
      try {
        this.socket.close(1000, "client disconnect");
      } catch {
        /* ignore */
      }
      this.socket = null;
    }
    this.handlers.onClose?.({ manual: true, reason: "主动断开" });
  }

  openSocket() {
    if (this.closedByUser) return;
    if (this.socket) {
      this.socket.onclose = null;
      try {
        this.socket.close();
      } catch {
        /* ignore */
      }
      this.socket = null;
    }
    this.clearHeartbeat();

    let socket;
    try {
      socket = new WebSocket(this.url);
    } catch (err) {
      this.handlers.onStatus?.({ phase: "error", text: "地址无效：" + (err?.message ?? err) });
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;
    this.lastInboundAt = Date.now();
    this.handlers.onStatus?.({ phase: "connecting", attempt: this.attempt });

    socket.onopen = () => {
      this.attempt = 0;
      // 每次建连都当作新会话：手机 App 重启后 seq 会从 0 重新计数，
      // 若沿用断线前的 lastSeq 会把新帧全部当旧帧丢掉（协议 §7.3 的丢帧只针对同一会话）。
      this.lastSeq = -1;
      this.pingSentAt.clear();
      this.lastInboundAt = Date.now();
      this.startHeartbeat();
      this.handlers.onOpen?.();
    };

    socket.onmessage = (event) => {
      this.lastInboundAt = Date.now();
      this.handleMessage(event.data);
    };

    socket.onerror = () => {
      /* 浏览器只给一个笼统事件，真正的重连由 onclose 触发 */
    };

    socket.onclose = (event) => {
      if (this.socket !== socket) return;
      this.socket = null;
      this.clearHeartbeat();
      this.handlers.onClose?.({ manual: this.closedByUser, code: event.code, reason: event.reason });
      if (!this.closedByUser) this.scheduleReconnect();
    };
  }

  scheduleReconnect() {
    this.clearReconnect();
    const delay = RECONNECT_DELAYS_MS[Math.min(this.attempt, RECONNECT_DELAYS_MS.length - 1)];
    this.attempt += 1;
    this.handlers.onStatus?.({ phase: "reconnecting", attempt: this.attempt, delay });
    this.reconnectTimer = setTimeout(() => this.openSocket(), delay);
  }

  clearReconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  clearHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  startHeartbeat() {
    this.clearHeartbeat();
    this.sendPing();
    this.heartbeatTimer = setInterval(() => {
      if (Date.now() - this.lastInboundAt > STALE_TIMEOUT_MS) {
        this.handlers.onStatus?.({ phase: "error", text: "心跳超时（20s 无数据），重连中…" });
        try {
          this.socket?.close();
        } catch {
          /* ignore */
        }
        return;
      }
      this.sendPing();
    }, HEARTBEAT_INTERVAL_MS);
  }

  sendPing() {
    const t = Date.now();
    this.pingSentAt.set(t, performance.now());
    if (this.pingSentAt.size > 32) this.pingSentAt.clear();
    this.send({ type: "ping", t });
  }

  send(object) {
    if (!this.connected) return false;
    try {
      this.socket.send(JSON.stringify(object));
      return true;
    } catch {
      return false;
    }
  }

  handleMessage(raw) {
    let message;
    try {
      message = JSON.parse(raw);
    } catch {
      state.badMessages += 1;
      return;
    }
    if (!message || typeof message !== "object") return;

    switch (message.type) {
      case "hello":
        this.handlers.onHello?.(message);
        break;
      case "track": {
        const seq = Number(message.seq);
        if (Number.isFinite(seq) && seq <= this.lastSeq) {
          state.dropped += 1;
          state.staleFrames += 1;
          return;
        }
        if (Number.isFinite(seq)) this.lastSeq = seq;
        this.handlers.onTrack?.(message);
        break;
      }
      case "calib":
        this.handlers.onCalib?.(message);
        break;
      case "pong": {
        const sentAt = this.pingSentAt.get(message.t);
        if (sentAt !== undefined) {
          this.pingSentAt.delete(message.t);
          this.handlers.onPong?.(performance.now() - sentAt);
        }
        break;
      }
      case "ping":
        // 手机主动 ping 时回 pong（协议 §4.4）
        this.send({ type: "pong", t: message.t ?? Date.now() });
        break;
      case "bye":
        this.handlers.onBye?.(message);
        break;
      case "ack":
        this.handlers.onAck?.(message);
        break;
      default:
        // 未知 type 必须忽略（协议 §3）
        break;
    }
  }
}

const client = new TrackerClient({
  onOpen() {
    state.connected = true;
    setStatus("已连接，等待数据…", "connected");
    ui.btnConnect.textContent = "断开";
    publishDebug();
  },
  onClose(info) {
    const wasConnected = state.connected;
    state.connected = false;
    ui.btnConnect.textContent = "连接";
    // 断线后不要保留最后一帧的表情：清掉归一化窗口 + 平滑回模型默认姿态，再由 idle motion 接管
    mapper.reset();
    state.faceMissingSince = null;
    if (stage.ready) stage.relax();
    if (info?.manual) {
      setStatus("未连接", "idle");
    } else if (wasConnected) {
      setStatus("连接已断开，重连中…", "reconnecting");
    }
    publishDebug();
  },
  onStatus(info) {
    if (info.phase === "connecting") {
      setStatus(info.attempt > 0 ? `重连中（第 ${info.attempt} 次）…` : "连接中…", "connecting");
    } else if (info.phase === "reconnecting") {
      setStatus(`${(info.delay / 1000).toFixed(1)}s 后重连（第 ${info.attempt} 次）`, "reconnecting");
    } else if (info.phase === "error") {
      setStatus(info.text, "error");
    }
    publishDebug();
  },
  onHello(message) {
    // hello = 新会话的起点：兜底重置 seq 跟踪与自适应归一化窗口
    client.lastSeq = -1;
    mapper.reset();
    state.faceMissingSince = null;
    state.device = typeof message.device === "string" ? message.device : "";
    state.phoneApp = typeof message.app === "string" ? message.app : "";
    const version = Number(message.version);
    if (Number.isFinite(version) && version !== 1) {
      showError(`协议版本不一致：手机为 v${version}，本客户端为 v1（仍会尝试按 v1 读取 track）`);
    }
    setStatus(state.device ? `已连接 · ${state.device}` : "已连接", "connected");
    updateMeta();
    publishDebug();
  },
  onTrack(message) {
    if (state.frozen) return; // 冻结时保留 __vfc.applyTrack 手动喂的值
    feedTrack(message);
  },
  onCalib(message) {
    if (message.active) {
      mapper.reset();
      setStatus("手机正在校准（已重置姿态归一化）…", "connecting");
    } else {
      setStatus(state.device ? `已连接 · ${state.device}` : "已连接", "connected");
    }
  },
  onPong(rttMs) {
    state.rttMs = state.rttMs == null ? rttMs : state.rttMs * 0.7 + rttMs * 0.3;
    updateMeta();
  },
  onBye(message) {
    setStatus("手机已停止推流：" + (message.reason ?? "bye"), "error");
  },
  onAck(message) {
    setStatus(`手机已确认命令：${message.name ?? "ack"}`, state.connected ? "connected" : "idle");
  },
});

function connectTo(url) {
  const trimmed = String(url || "").trim();
  if (!trimmed) return;
  state.wsUrl = trimmed;
  state.frames = 0;
  state.dropped = 0;
  state.seq = null;
  state.device = "";
  mapper.reset();
  storeSet(STORE_KEYS.ws, trimmed);
  ui.wsInput.value = trimmed;
  setStatus("连接中…", "connecting");
  client.connect(trimmed);
  publishDebug();
}

// ---------------------------------------------------------------- 每帧映射

function feedTrack(track) {
  if (!track || typeof track !== "object") return false;

  const result = mapper.apply(track.bs ?? [], track.pose ?? [0, 0, 0]);
  state.lastTrack = track;
  state.lastResult = result;
  state.frames += 1;

  if (Number.isFinite(Number(track.seq))) state.seq = Number(track.seq);
  if (track.fps != null && Number.isFinite(Number(track.fps))) state.phoneFps = Number(track.fps);
  if (track.face === false) {
    state.faceLost = (state.faceLost ?? 0) + 1;
    if (state.faceMissingSince == null) state.faceMissingSince = performance.now();
  } else {
    state.faceMissingSince = null;
  }

  stage.setTargetParams(result.params);

  if (Array.isArray(track.bs) && track.bs.length !== 52) {
    state.badFrames = (state.badFrames ?? 0) + 1;
  }

  publishDebug();
  return true;
}

function publishDebug() {
  const result = state.lastResult;
  const params = result?.params ?? null;
  debugState.ready = stage.ready;
  debugState.model = state.modelName;
  debugState.wsUrl = client.url ?? state.wsUrl;
  debugState.connected = state.connected;
  debugState.frames = state.frames;
  debugState.seq = state.seq;
  debugState.fps = Math.round(state.inputFps * 10) / 10;
  debugState.renderFps = Math.round(state.renderFps * 10) / 10;
  debugState.latencyMs = state.rttMs == null ? null : Math.round(state.rttMs * 10) / 10;
  debugState.lastTrack = state.lastTrack;
  debugState.params = params;
  debugState.normalized = result?.normalized ?? null;
  debugState.raw = result?.raw ?? null;
  debugState.angles = {
    x: params?.ParamAngleX ?? 0,
    y: params?.ParamAngleY ?? 0,
    z: params?.ParamAngleZ ?? 0,
  };
  debugState.engine = {
    ParamEyeLOpen: stage.getEngineParam("ParamEyeLOpen"),
    ParamEyeROpen: stage.getEngineParam("ParamEyeROpen"),
    ParamMouthOpenY: stage.getEngineParam("ParamMouthOpenY"),
    ParamMouthForm: stage.getEngineParam("ParamMouthForm"),
    ParamAngleX: stage.getEngineParam("ParamAngleX"),
    ParamAngleY: stage.getEngineParam("ParamAngleY"),
    ParamAngleZ: stage.getEngineParam("ParamAngleZ"),
    ParamBodyAngleX: stage.getEngineParam("ParamBodyAngleX"),
  };
  debugState.dropped = state.dropped;
  debugState.frozen = state.frozen;
  debugState.error = state.error;
  debugState.missingParams = stage.missingParams ?? [];
}

// ---------------------------------------------------------------- 模型

function findModel(name) {
  if (!name) return null;
  const wanted = String(name).toLowerCase();
  return state.models.find((m) => m.name.toLowerCase() === wanted) ?? null;
}

function populateModelSelect() {
  ui.modelSelect.innerHTML = "";
  for (const model of state.models) {
    const option = document.createElement("option");
    option.value = model.name;
    option.textContent = model.name;
    ui.modelSelect.appendChild(option);
  }
  if (state.modelName) ui.modelSelect.value = state.modelName;
}

async function refreshModels() {
  try {
    const response = await fetch("/api/models", { cache: "no-store" });
    if (!response.ok) throw new Error("HTTP " + response.status);
    const data = await response.json();
    const list = Array.isArray(data) ? data : (data.models ?? []);
    const models = list.filter((m) => m && m.name && m.entry).map((m) => ({ name: m.name, entry: m.entry }));
    if (models.length) state.models = models;
  } catch {
    state.models = FALLBACK_MODELS.slice();
  }
  populateModelSelect();
}

async function loadModel(name) {
  if (state.loadingModel) return false;
  const target = findModel(name) ?? state.models[0] ?? null;
  if (!target) {
    showError("没有找到任何模型：请把模型目录放到 windows-client/models/<名字>/ 下（含 *.model3.json）");
    setStatus("没有可用模型", "error");
    return false;
  }

  state.loadingModel = true;
  clearError();
  setStatus(`加载模型 ${target.name}…`, "connecting");
  ui.modelSelect.value = target.name;

  try {
    await stage.loadModel(target.entry, { name: target.name });
    state.modelName = target.name;
    storeSet(STORE_KEYS.model, target.name);
    debugState.model = target.name;
    if (state.lastResult) stage.setTargetParams(state.lastResult.params);
    setStatus(state.connected ? (state.device ? `已连接 · ${state.device}` : "已连接") : "未连接", connPhase());
    publishDebug();
    return true;
  } catch (error) {
    const message = `模型 ${target.name} 加载失败：${error?.message ?? error}`;
    showError(message);
    setStatus("模型加载失败", "error");
    debugState.error = message;
    publishDebug();
    return false;
  } finally {
    state.loadingModel = false;
  }
}

// ---------------------------------------------------------------- UI 更新

function updateMeta() {
  ui.device.textContent = state.device || (state.connected ? "已连接" : "-");
  ui.device.title = state.phoneApp || state.device;
  ui.inFps.textContent = state.inputFps.toFixed(1);
  ui.renderFps.textContent = state.renderFps.toFixed(1);
  ui.rtt.textContent = state.rttMs == null ? "-" : `${state.rttMs.toFixed(1)} ms`;
  ui.seq.textContent = state.seq == null ? "-" : String(state.seq);
  ui.dropped.textContent = String(state.dropped);
}

function renderDebugGrid() {
  if (!ui.debugBox.open) return;
  const track = state.lastTrack;
  const bs = Array.isArray(track?.bs) ? track.bs : [];
  const pose = Array.isArray(track?.pose) ? track.pose : [0, 0, 0];
  const result = state.lastResult;
  const params = result?.params ?? {};
  const rows = [
    ["bs.eyeBlinkL", fmt(bs[BS_INDEX.eyeBlinkLeft], 3)],
    ["bs.eyeBlinkR", fmt(bs[BS_INDEX.eyeBlinkRight], 3)],
    ["bs.jawOpen", fmt(bs[BS_INDEX.jawOpen], 3)],
    ["bs.smile", fmt(((bs[BS_INDEX.mouthSmileLeft] ?? 0) + (bs[BS_INDEX.mouthSmileRight] ?? 0)) / 2, 3)],
    ["pose pitch", fmt(pose[0], 2)],
    ["pose yaw", fmt(pose[1], 2)],
    ["pose roll", fmt(pose[2], 2)],
    ["norm pitch", fmt(result?.normalized?.pitch, 3)],
    ["norm yaw", fmt(result?.normalized?.yaw, 3)],
    ["norm roll", fmt(result?.normalized?.roll, 3)],
    ["EyeLOpen →", fmt(stage.getEngineParam("ParamEyeLOpen"), 3)],
    ["EyeROpen →", fmt(stage.getEngineParam("ParamEyeROpen"), 3)],
    ["MouthOpenY →", fmt(stage.getEngineParam("ParamMouthOpenY"), 3)],
    ["MouthForm →", fmt(stage.getEngineParam("ParamMouthForm"), 3)],
    ["AngleX →", fmt(stage.getEngineParam("ParamAngleX"), 2)],
    ["AngleY →", fmt(stage.getEngineParam("ParamAngleY"), 2)],
    ["AngleZ →", fmt(stage.getEngineParam("ParamAngleZ"), 2)],
    ["BodyAngleX →", fmt(stage.getEngineParam("ParamBodyAngleX"), 2)],
    ["target AngleX", fmt(params.ParamAngleX, 2)],
    ["target AngleY", fmt(params.ParamAngleY, 2)],
    ["frames", String(state.frames)],
    ["dropped", String(state.dropped)],
    ["缺失参数", (stage.missingParams ?? []).length ? (stage.missingParams ?? []).join(",") : "无"],
  ];
  ui.debugGrid.innerHTML = rows
    .map(([key, value]) => `<span class="k">${key}</span><span class="v">${value}</span>`)
    .join("");
}

function startUiLoop() {
  let lastSample = performance.now();
  let lastFrames = state.frames;
  const timer = setInterval(() => {
    const now = performance.now();
    const elapsed = (now - lastSample) / 1000;
    if (elapsed >= 0.5) {
      const instant = (state.frames - lastFrames) / elapsed;
      // EMA 平滑，避免 250ms 窗口的抖动让 fps 数字乱跳
      state.inputFps = state.inputFps > 0 ? state.inputFps * 0.7 + instant * 0.3 : instant;
      lastFrames = state.frames;
      lastSample = now;
    }
    state.renderFps = stage.app ? stage.app.ticker.FPS : 0;

    // 连续丢脸 > 0.8s 时提示，避免用户以为画面卡了
    const faceMissing = state.connected && state.faceMissingSince != null && now - state.faceMissingSince > 800;
    if (faceMissing !== state.faceMissingShown) {
      state.faceMissingShown = faceMissing;
      if (faceMissing) setStatus("未检测到人脸（手机端画面里找不到脸）", "connecting");
      else if (state.connected) setStatus(state.device ? `已连接 · ${state.device}` : "已连接", "connected");
    }

    updateMeta();
    renderDebugGrid();
  }, 250);
  // 页面卸载时释放，避免 Electron 反复重载积累定时器
  window.addEventListener("beforeunload", () => clearInterval(timer));
}

// ---------------------------------------------------------------- Electron 窗口开关

async function initNativeControls() {
  const transparentQuery = query.get("transparent") === "1";
  // 纯 Web 下用 ?transparent=1 模拟透明底；Electron 下由主进程真实窗口状态决定
  setTransparentMode(transparentQuery && !native);

  if (!native) {
    ui.nativeHint.hidden = false;
    for (const input of [ui.tgTransparent, ui.tgBorderless, ui.tgOnTop, ui.tgThrough]) {
      input.disabled = true;
      input.closest("label")?.classList.add("disabled");
    }
    return;
  }

  ui.btnQuit.hidden = false;
  ui.btnQuit.addEventListener("click", () => native.quit());

  const applyState = (windowState) => {
    if (!windowState) return;
    ui.tgTransparent.checked = !!windowState.transparent;
    ui.tgBorderless.checked = !!windowState.borderless;
    ui.tgOnTop.checked = !!windowState.alwaysOnTop;
    ui.tgThrough.checked = !!windowState.mouseThrough;
    setTransparentMode(!!windowState.transparent);
    document.body.classList.toggle("is-borderless", !!windowState.borderless);
  };

  try {
    applyState(await native.getWindowState());
  } catch {
    /* 忽略：接口异常时仍可渲染 */
  }
  native.onWindowState?.(applyState);

  ui.tgTransparent.addEventListener("change", () => {
    // 透明属性只能在创建窗口时指定，主进程会重建窗口并带回 ?transparent=1
    native.setWindowOptions({ transparent: ui.tgTransparent.checked });
  });
  ui.tgBorderless.addEventListener("change", () => {
    native.setWindowOptions({ borderless: ui.tgBorderless.checked });
  });
  ui.tgOnTop.addEventListener("change", () => {
    native.setWindowOptions({ alwaysOnTop: ui.tgOnTop.checked });
  });
  ui.tgThrough.addEventListener("change", () => {
    native.setWindowOptions({ mouseThrough: ui.tgThrough.checked });
  });
}

// ---------------------------------------------------------------- 事件绑定

function wireUi() {
  ui.wsInput.value = state.wsUrl;

  ui.btnConnect.addEventListener("click", () => {
    if (state.connected) {
      client.disconnect();
      setStatus("已断开", "idle");
    } else {
      connectTo(ui.wsInput.value);
    }
  });

  ui.wsInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      connectTo(ui.wsInput.value);
    }
  });

  ui.modelSelect.addEventListener("change", () => {
    loadModel(ui.modelSelect.value);
  });

  ui.btnReload.addEventListener("click", () => {
    loadModel(state.modelName ?? ui.modelSelect.value);
  });

  ui.btnRecalib.addEventListener("click", () => {
    if (!client.send({ type: "command", name: "recalibrate" })) {
      setStatus("未连接，无法发送重新校准命令", "error");
      return;
    }
    mapper.reset();
    setStatus("已发送重新校准命令，等待手机 ack…", "connecting");
  });

  ui.btnHide.addEventListener("click", () => toggleUiHidden());

  ui.collapse.addEventListener("click", () => {
    const collapsed = ui.root.classList.toggle("collapsed");
    storeSet(STORE_KEYS.collapsed, collapsed ? "1" : "0");
    ui.collapse.textContent = collapsed ? "+" : "–";
  });

  if (storeGet(STORE_KEYS.collapsed) === "1") {
    ui.root.classList.add("collapsed");
    ui.collapse.textContent = "+";
  }

  ui.debugBox.addEventListener("toggle", () => renderDebugGrid());

  window.addEventListener("keydown", (event) => {
    if (event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement) return;
    if (event.key === "h" || event.key === "H") {
      toggleUiHidden();
    } else if (event.key === "d" || event.key === "D") {
      ui.debugBox.open = !ui.debugBox.open;
      renderDebugGrid();
    }
  });

  window.addEventListener("beforeunload", () => client.disconnect());
}

function toggleUiHidden() {
  const hidden = document.body.classList.toggle("ui-hidden");
  document.getElementById("hidden-hint").hidden = !hidden;
}

// ---------------------------------------------------------------- 对外测试钩子

window.__vfc = {
  get ready() {
    return stage.ready;
  },
  get frozen() {
    return state.frozen;
  },
  /** 冻结/解冻 WS 驱动（冻结后只有 __vfc.applyTrack 能改参数）。 */
  freeze(value = true) {
    state.frozen = Boolean(value);
    stage.setSmoothing(state.frozen ? 1 : SMOOTHING);
    publishDebug();
    return state.frozen;
  },
  /** 手动喂一条 track（自动冻结 + 立即渲染，截图用）。 */
  applyTrack(track) {
    state.frozen = true;
    stage.setSmoothing(1);
    const payload = { ...(track ?? {}) };
    if (!Number.isFinite(Number(payload.seq))) payload.seq = state.frames + 1;
    feedTrack(payload);
    if (stage.ready) {
      stage.renderNow();
      publishDebug(); // 渲染后再读一次，engine 里就是这一帧真正生效的值
    }
    return state.lastResult;
  },

  /** 把当前 canvas 缩略成小图并算像素统计（自测 / 截图前后对比用，证明画面真的变了）。 */
  pixelStats(size = 192) {
    const canvas = stage.app?.view;
    if (!canvas || !canvas.width) return null;
    const width = Math.max(2, Math.round(size));
    const height = Math.max(2, Math.round((size * canvas.height) / canvas.width));
    const offscreen = document.createElement("canvas");
    offscreen.width = width;
    offscreen.height = height;
    const context = offscreen.getContext("2d", { willReadFrequently: true });
    if (!context) return null;
    context.clearRect(0, 0, width, height);
    context.drawImage(canvas, 0, 0, width, height);
    const data = context.getImageData(0, 0, width, height).data;
    let hash = 2166136261 >>> 0;
    let nonTransparent = 0;
    let alphaSum = 0;
    let rgbSum = 0;
    for (let i = 0; i < data.length; i += 4) {
      hash ^= data[i] + data[i + 1] * 3 + data[i + 2] * 7;
      hash = Math.imul(hash, 16777619) >>> 0;
      alphaSum += data[i + 3];
      rgbSum += data[i] + data[i + 1] + data[i + 2];
      if (data[i + 3] > 8) nonTransparent += 1;
    }
    const pixels = width * height;
    return {
      width,
      height,
      hash: hash >>> 0,
      nonTransparent,
      nonTransparentRatio: Math.round((nonTransparent / pixels) * 10000) / 10000,
      meanAlpha: Math.round((alphaSum / pixels) * 100) / 100,
      meanRgb: Math.round((rgbSum / (pixels * 3)) * 100) / 100,
    };
  },
  state() {
    return {
      ...state,
      params: state.lastResult?.params ?? null,
      normalized: state.lastResult?.normalized ?? null,
      ready: stage.ready,
      modelUrl: stage.modelUrl,
      smoothing: stage.smoothing,
      engineParams: debugState.engine,
    };
  },
  connect(url) {
    connectTo(url ?? state.wsUrl);
  },
  disconnect() {
    client.disconnect();
  },
  setModel(name) {
    return loadModel(name);
  },
  setSmoothing(value) {
    return stage.setSmoothing(value);
  },
  renderNow() {
    stage.renderNow();
  },
  mapper,
  stage,
  client,
};

// ---------------------------------------------------------------- 启动

async function boot() {
  wireUi();
  await initNativeControls();
  updateMeta();

  const problems = checkEnvironment();
  if (problems.length) {
    showError("渲染环境不完整：\n" + problems.join("\n"));
    setStatus("环境不支持", "error");
    return;
  }

  await refreshModels();

  const wantedModel = findModel(query.get("model")) ?? findModel(storeGet(STORE_KEYS.model)) ?? state.models[0] ?? null;
  await loadModel(wantedModel?.name);

  startUiLoop();
  connectTo(state.wsUrl);
}

boot();
