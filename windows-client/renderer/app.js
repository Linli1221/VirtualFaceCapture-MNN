/**
 * app.js — 渲染页主逻辑：WS 客户端 + 协议处理 + 每帧映射 + UI + Electron 窗口开关
 *
 * 数据流：WebSocket(track) → 按 seq 丢旧帧 → mapping.createMapper().apply(bs, pose)
 *        → stage.pushTrackParams(params)（输入慢于渲染时走时间轴插值）
 *        → InternalModel.beforeModelUpdate 钩子里写 cubism 参数
 *
 * 对外测试钩子（verifier 用）：
 *   window.__vfc      = { ready, freeze(v), applyTrack({bs,pose}), state(), connect(url), disconnect(), setModel(name), ... }
 *   window.__vfcDebug = { version, ready, model, wsUrl, connected, frames, seq, fps, latencyMs, lastTrack, params, angles, ... }
 */
import {
  BODY_ARM_MIN_VISIBILITY,
  BODY_BONES,
  BODY_DECAY_PER_FRAME,
  BODY_PARAM_IDS,
  BS_INDEX,
  combineBodyAngleX,
  createMapper,
  mapBody,
  readUpperBodyTrack,
  smoothBodyParams,
} from "./mapping.js";
import {
  ALIGN_DURATION_MS,
  DEFAULT_POSE_OFFSET,
  DEFAULT_POSE_PROFILE,
  POSE_ADAPTIVE_STORAGE_KEY,
  POSE_OFFSET_STORAGE_KEY,
  POSE_PROFILE_STORAGE_KEY,
  AdaptivePoseOffset,
  OffsetRamp,
  PoseOffsetCollector,
  applyPoseOffset,
  decodePoseOffset,
  decodePoseProfileStore,
  encodePoseOffset,
  encodePoseProfileStore,
  formatPoseOffset,
  getProfileFor,
  isNeutralOffset,
  offsetToProfile,
  profileKeyFor,
  profileToOffset,
  sanitizePoseOffset,
  sanitizePoseProfile,
  setProfileFor,
} from "./calibrate.js";
import { isDefaultFilterProfile, sanitizeFilterProfile } from "./filter.js";
import {
  RAW_BODY_RENDER_TAU_MS,
  UpperBodyPostProcessor,
  createBodyState as createBodyStateCore,
  updateBodyStateFromTrack,
} from "./mapping.js";
import { Live2DStage, checkEnvironment } from "./live2d.js";

const DEFAULT_WS_URL = "ws://127.0.0.1:8765/ws";
const RECONNECT_DELAYS_MS = [500, 1000, 2000, 5000];
const HEARTBEAT_INTERVAL_MS = 5000;
const STALE_TIMEOUT_MS = 20000;
const SMOOTHING = 0.6;

/** 调试骨架画布尺寸（画布右下角；D 键 / 调试面板 / ?skeleton=1 显示）。 */
const SKELETON_WIDTH = 176;
const SKELETON_HEIGHT = 234;

/** 一键校准期间进度提示的刷新间隔（ms）。 */
const ALIGN_TICK_MS = 100;

/** 三轴偏移的展示顺序 / 标签（滑块、提示共用）。 */
const POSE_AXES = ["yaw", "pitch", "roll"];
const POSE_AXIS_LABELS = { yaw: "左右", pitch: "上下", roll: "歪头" };

/** PC 端渲染目标帧率（协议 §9：60 FPS @ 1080p）；0 = 不限制，交给 vsync。 */
const DEFAULT_TARGET_FPS = 60;
const MIN_TARGET_FPS = 10;
const MAX_TARGET_FPS = 240;

/** ?fps=<n>：覆盖目标帧率；0 / 负数 = 不限制。非法值回落到默认 60。 */
function parseTargetFps(raw) {
  if (raw == null || String(raw).trim() === "") return DEFAULT_TARGET_FPS;
  const value = Number(raw);
  if (!Number.isFinite(value)) return DEFAULT_TARGET_FPS;
  if (value <= 0) return 0;
  return Math.round(Math.min(Math.max(value, MIN_TARGET_FPS), MAX_TARGET_FPS));
}

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
const TARGET_FPS = parseTargetFps(query.get("fps"));

/**
 * jitterBufferV1 开关（PC 侧自适应抖动缓冲，M1）：默认开启；`?jb=0` 回退旧延迟逻辑。
 * 关闭时**不**在 hello 里声明 jitterBufferV1 —— 手机端就不会把 send.bufferMs 用到 0，
 * 仍保留自己的 500 ms 发送缓冲（回滚路径完整，两步都能退回去）。
 */
const JITTER_BUFFER_ENABLED = query.get("jb") !== "0";
const CLIENT_FEATURES = [...(JITTER_BUFFER_ENABLED ? ["jitterBufferV1"] : []), "upperBodyPostV1"];

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
  btnAlign: document.getElementById("btn-align"),
  btnRecalib: document.getElementById("btn-recalib"),
  btnCalibReset: document.getElementById("btn-calib-reset"),
  calibBox: document.getElementById("calib-box"),
  calibHint: document.getElementById("calib-hint"),
  calibSliders: {
    yaw: document.getElementById("cal-yaw"),
    pitch: document.getElementById("cal-pitch"),
    roll: document.getElementById("cal-roll"),
  },
  calibValues: {
    yaw: document.getElementById("cal-yaw-val"),
    pitch: document.getElementById("cal-pitch-val"),
    roll: document.getElementById("cal-roll-val"),
  },
  tgPoseAdaptive: document.getElementById("tg-pose-adaptive"),
  tgFinalFilter: document.getElementById("tg-final-filter"),
  filterSliders: {
    headSlow: document.getElementById("fl-head-slow"),
    headFast: document.getElementById("fl-head-fast"),
    bodySlow: document.getElementById("fl-body-slow"),
    bodyFast: document.getElementById("fl-body-fast"),
  },
  filterValues: {
    headSlow: document.getElementById("fl-head-slow-val"),
    headFast: document.getElementById("fl-head-fast-val"),
    bodySlow: document.getElementById("fl-body-slow-val"),
    bodyFast: document.getElementById("fl-body-fast-val"),
  },
  btnFilterReset: document.getElementById("btn-filter-reset"),
  btnHide: document.getElementById("btn-hide"),
  btnQuit: document.getElementById("btn-quit"),
  debugBox: document.getElementById("debug-box"),
  debugGrid: document.getElementById("debug-grid"),
  skeleton: document.getElementById("skeleton"),
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
  targetFps: TARGET_FPS,
  error: null,
  frozen: false,
  loadingModel: false,
  phoneFps: null,
  fpsWindowFrames: 0,
  fpsWindowStart: performance.now(),
  /** 本地姿态偏移（镜头没正对你时的校准量）。最终角度 = track.pose + 本偏移。
   *  poseOffset = 当前**应用中**的值（平滑过渡期间可能还没到目标）；
   *  poseOffsetTarget = 目标值（滑块 / 持久化 / UI 显示用）。 */
  poseOffset: decodePoseOffset(storeGet(POSE_OFFSET_STORAGE_KEY)),
  poseOffsetTarget: decodePoseOffset(storeGet(POSE_OFFSET_STORAGE_KEY)),
  /** 按角色保存的 profile store（pitchOffset/yawOffset/rollOffset + 量程偏好）。 */
  poseProfiles: decodePoseProfileStore(storeGet(POSE_PROFILE_STORAGE_KEY)),
  /** 当前角色的量程偏好（映射归一化默认量程的倍率，1 = 与现在一致）。 */
  poseRangeScale: DEFAULT_POSE_PROFILE.rangeScale,
  /** M4 最终观感滤波 profile（含总开关）：按角色随 poseProfiles 保存；默认关闭 = 旧行为。 */
  filterProfile: sanitizeFilterProfile(null),
  /** 可选的缓慢自适应偏移（默认关闭，见 calibrate.js 的 AdaptivePoseOffset）。 */
  poseAdaptive: new AdaptivePoseOffset(),
  adaptiveEnabled: false,
  /** 平滑过渡器：手动重置 / 手机重做基线时偏移限速趋近目标，模型不瞬移。 */
  poseRamp: new OffsetRamp(),
  lastPoseRampAt: null,
  lastAdaptiveSavedAt: 0,
  lastAdaptiveSavedOffset: null,
  /** 正在跑的一键校准：{collector, startedAt, durationMs, timer, promise, resolve}。 */
  align: null,
  /** 最近一次成功校准的报告（调试 / 提示用）。 */
  lastAlign: null,
  /** 最近一帧「叠加偏移后」的 pose（调试面板显示）。 */
  lastAppliedPose: null,
  /** 上半身（协议 §4.2）：{ ok, points, torso, t, … }，见 createBodyState()。 */
  body: createBodyState(),
  /** 头部 + 上半身合并后的参数表（真正写 core 由 live2d.js 叠加；这里给调试 / __vfc 看）。 */
  mergedParams: null,
};

const mapper = createMapper();
const stage = new Live2DStage({
  canvas: document.getElementById("live2d"),
  smoothing: SMOOTHING,
  targetFps: TARGET_FPS,
  jitterBuffer: JITTER_BUFFER_ENABLED,
});

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
  targetFps: TARGET_FPS,
  interp: {
    active: false,
    delayMs: 0,
    inputIntervalMs: 0,
    jitterMs: 0,
    transitJitterMs: 0,
    samples: 0,
    starvedFrames: 0,
    starvedEpisodes: 0,
  },
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
  body: null,
  mergedParams: null,
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
      // 能力协商（M1 jitterBufferV1）：连接一建立就声明本客户端支持哪些能力。
      // 手机端只有看到 jitterBufferV1 才允许 send.bufferMs=0（否则保留 500 ms 发送缓冲）。
      // 老手机把未知 type 当未知消息忽略，完全兼容（协议 §3）。
      this.send({ type: "hello", protocol: "vfc-tracker", version: 1, features: CLIENT_FEATURES, t: Date.now() });
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
    // 新会话：播放时间轴重新预缓冲（jitterBufferV1 的 primed 复位，见 interp.js）
    stage.resetTimeline();
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
    state.poseAdaptive.reset();
    state.faceMissingSince = null;
    resetBodyState();
    // 断线也清时间轴：重连后重新预缓冲，不会把断线前的旧样本接着播
    stage.resetTimeline();
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
    state.poseAdaptive.reset();
    resetBodyState();
    // 收到 hello 也要重新预缓冲（手机可能在同一条连接上重新开始推流，seq 会重计数）
    stage.resetTimeline();
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
      // 手机重做基线后，pose 的零点已经由手机重新定义：本地偏移再叠上去会「双重校准」，
      // 模型反而朝反方向偏。calib 之后基线一定变了，无条件清掉本地偏移（平滑过渡，不瞬移）；
      // 需要更细的对齐再点「一键校准」。
      if (!isNeutralOffset(state.poseOffsetTarget)) {
        applyPoseCalibration(DEFAULT_POSE_OFFSET, { persist: true, smooth: true });
        setCalibHint("手机端基线已更新，本地对齐偏移已清零。如果模型还是歪的，点「一键校准」再对齐一次。", "warn");
      }
      setStatus(state.device ? `已连接 · ${state.device}` : "已连接", "connected");
    }
    publishDebug();
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
  resetBodyState();
  storeSet(STORE_KEYS.ws, trimmed);
  ui.wsInput.value = trimmed;
  setStatus("连接中…", "connecting");
  client.connect(trimmed);
  publishDebug();
}

// ---------------------------------------------------------------- 每帧映射

function feedTrack(track) {
  if (!track || typeof track !== "object") return false;

  const now = performance.now();
  // ---- 本地姿态偏移（PC 端，叠加在手机基线之上）----
  //
  //     最终角度 = track.pose + pcOffset
  //
  // `track.pose` 已经在手机端减过中性基线（首 1s 基线 / extractor.calibrate 都在手机），
  // PC 端的 pcOffset 与手机基线是**叠加**关系：这里只做加法，绝不重复减基线，
  // 也不要求手机发 poseRaw / 478 点。符号约定不变：pitch 上正下负、yaw 本人左转为正、
  // roll 本人左倾为正。
  const rampDtMs = state.lastPoseRampAt == null ? 0 : now - state.lastPoseRampAt;
  state.lastPoseRampAt = now;
  if (!state.poseRamp.settled) state.poseOffset = state.poseRamp.sample(rampDtMs);
  const pose = applyPoseOffset(track.pose, state.poseOffset);
  // 可选缓慢自适应（默认关）：只在低运动 + 低表情变化时极慢地微调偏移
  if (state.poseAdaptive.enabled) maybeAdaptOffset(track);
  const result = mapper.apply(track.bs ?? [], pose);
  // 上半身：解析 ub / ubOk / ubT / body；丢失 / 过期时按帧平滑回 0（不跳变）
  const body = updateBodyState(track, now);
  state.lastTrack = track;
  state.lastResult = result;
  state.mergedParams = mergeBodyParams(result.params, body.current);
  state.lastAppliedPose = { pitch: pose[0], yaw: pose[1], roll: pose[2] };
  state.frames += 1;

  // 一键校准进行中：把这一帧收进采样器（只收 face !== false 的合法 pose）。
  if (state.align) state.align.collector.addTrack(track);

  if (Number.isFinite(Number(track.seq))) state.seq = Number(track.seq);
  if (track.fps != null && Number.isFinite(Number(track.fps))) state.phoneFps = Number(track.fps);
  if (track.face === false) {
    state.faceLost = (state.faceLost ?? 0) + 1;
    if (state.faceMissingSince == null) state.faceMissingSince = performance.now();
  } else {
    state.faceMissingSince = null;
  }

  // 走时间轴：输入帧率低于渲染帧率时，渲染层会在两帧之间插值（见 interp.js）。
  // track.t 是手机采集时间戳：到达时间被 WS 批量/PC 卡顿挤在一起时，用它还原真实帧间隔。
  // track.seq 供时间轴统计跳变（丢帧 / 拥塞信号，jitterBufferV1 分档用；去重在 handleMessage 已做）。
  stage.pushTrackParams(result.params, now, track.t, Number.isFinite(Number(track.seq)) ? Number(track.seq) : null);
  // 上半身参数单独交给 stage：ParamBodyAngleX 与头部**相加后 clamp**，其余写可选参数
  stage.setBodyParams(body.current);

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
  debugState.targetFps = stage.targetFps;
  debugState.interp = stage.getInterpolatorStats();
  debugState.latencyMs = state.rttMs == null ? null : Math.round(state.rttMs * 10) / 10;
  debugState.lastTrack = state.lastTrack;
  debugState.params = params;
  debugState.normalized = result?.normalized ?? null;
  debugState.raw = result?.raw ?? null;
  debugState.poseOffset = { ...state.poseOffset };
  debugState.poseOffsetTarget = { ...state.poseOffsetTarget };
  debugState.calibratedPose = state.lastAppliedPose ? { ...state.lastAppliedPose } : null;
  debugState.calibration = {
    active: !!state.align,
    samples: state.align ? state.align.collector.count : 0,
    remainingMs: state.align
      ? Math.max(0, Math.round(state.align.startedAt + state.align.durationMs - performance.now()))
      : 0,
    stored: !isNeutralOffset(state.poseOffsetTarget),
    lastAlignAt: state.lastAlign ? state.lastAlign.at : null,
    lastAlignSamples: state.lastAlign ? state.lastAlign.samples : null,
    lastAlignSpread: state.lastAlign ? state.lastAlign.spread : null,
  };
  debugState.poseAdaptive = {
    enabled: state.poseAdaptive.enabled,
    rate: state.poseAdaptive.rate,
    quietStreak: state.poseAdaptive.quietStreak,
    updates: state.poseAdaptive.stats.updates,
    skippedMotion: state.poseAdaptive.stats.skippedMotion,
    skippedExpression: state.poseAdaptive.stats.skippedExpression,
    rangeScale: state.poseRangeScale,
  };
  // M4 最终观感滤波：总开关 + 分组参数 + 当前输入速度（验收 / 调试面板用）
  debugState.filter = {
    enabled: state.filterProfile.enabled,
    head: { ...state.filterProfile.head },
    body: { ...state.filterProfile.body },
    normalizer: { ...state.filterProfile.normalizer },
    motionSpeed: Math.round((stage.timeline.motionSpeed ?? 0) * 100) / 100,
  };
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
    ParamBodyAngleY: stage.getEngineParam("ParamBodyAngleY"),
    ParamBodyAngleZ: stage.getEngineParam("ParamBodyAngleZ"),
    ParamArmLA: stage.getEngineParam("ParamArmLA"),
    ParamArmRA: stage.getEngineParam("ParamArmRA"),
    ParamArmLB: stage.getEngineParam("ParamArmLB"),
    ParamArmRB: stage.getEngineParam("ParamArmRB"),
    ParamShoulderL: stage.getEngineParam("ParamShoulderL"),
    ParamShoulderR: stage.getEngineParam("ParamShoulderR"),
    ParamHandL: stage.getEngineParam("ParamHandL"),
    ParamHandR: stage.getEngineParam("ParamHandR"),
    ParamHandAngleL: stage.getEngineParam("ParamHandAngleL"),
    ParamHandAngleR: stage.getEngineParam("ParamHandAngleR"),
  };
  const body = state.body ?? createBodyState();
  // 上半身调试信息（verifier / 调试面板 / 无手机截图都能读到）
  debugState.body = {
    ok: Boolean(body.ok),
    ageMs: body.ageMs,
    stale: Boolean(body.stale),
    present: Boolean(body.present),
    declaredOk: body.ubOk !== false,
    t: body.t,
    torso: { roll: body.torso?.[0] ?? 0, yaw: body.torso?.[1] ?? 0 },
    armAngleL: body.metrics?.armAngleL ?? null,
    armAngleR: body.metrics?.armAngleR ?? null,
    elbowBendL: body.metrics?.elbowBendL ?? null,
    elbowBendR: body.metrics?.elbowBendR ?? null,
    handAngleL: body.metrics?.handAngleL ?? null,
    handAngleR: body.metrics?.handAngleR ?? null,
    spineLean: body.metrics?.spineLean ?? 0,
    clavicleRelTilt: body.metrics?.clavicleRelTilt ?? 0,
    shoulderWidth: body.metrics?.shoulderWidth ?? 0,
    confidence: body.metrics?.confidence ?? 0,
    params: { ...(body.current ?? {}) },
    support: { ...(stage.bodySupport ?? {}) },
    raw: Boolean(body.raw),
    phoneRawTorso: body.phoneRawTorso ? [...body.phoneRawTorso] : null,
    postCalibrated: bodyPost.baseline.isCalibrated,
    postBaseline: bodyPost.serializeBaseline(),
  };
  debugState.mergedParams = state.mergedParams ? { ...state.mergedParams } : null;
  debugState.dropped = state.dropped;
  debugState.frozen = state.frozen;
  debugState.error = state.error;
  debugState.missingParams = stage.missingParams ?? [];
}

// ---------------------------------------------------------------- 上半身（协议 §4.2）


/** 上半身后处理管线（任务 M2 upperBodyPostV1）：原始点 → One-Euro 平滑 → 躯干角 → TorsoBaseline。 */
const bodyPost = new UpperBodyPostProcessor();

/** 基线是否已落盘（只在收齐 10 帧转 calibrated 时写一次 localStorage）。 */
let bodyBaselineWasCalibrated = false;

/** 切换角色 profile 时同步的渲染层低通模式（见 syncBodySmoothingTau）。 */
let lastBodyRawMode = null;

/** 角色 profile 维度的躯干基线持久化 key（按角色各存各的，换角色互不干扰）。 */
function bodyBaselineKey(modelName) {
  return `vfc.bodyBaseline.${profileKeyFor(modelName)}`;
}

/**
 * 切换角色 profile：载入该角色持久化的躯干基线（没有就冷启动重收 10 帧）。
 * 与姿态偏移的 profile（calibrate.js）同一套「按角色 profile 区分」语义。
 */
function activateBodyBaselineProfile(modelName) {
  bodyPost.resetSmoothers();
  bodyBaselineWasCalibrated = false;
  let restored = false;
  try {
    const raw = window.localStorage.getItem(bodyBaselineKey(modelName));
    if (raw) restored = bodyPost.loadBaseline(JSON.parse(raw));
  } catch {
    restored = false;
  }
  return restored;
}

/** 基线收齐（isCalibrated）后落盘；只在状态变化时写一次，不逐帧写盘。 */
function maybeSaveBodyBaseline() {
  const calibrated = bodyPost.baseline.isCalibrated;
  if (!calibrated) {
    bodyBaselineWasCalibrated = false;
    return;
  }
  if (bodyBaselineWasCalibrated) return;
  bodyBaselineWasCalibrated = true;
  try {
    window.localStorage.setItem(bodyBaselineKey(state.modelName ?? ""), JSON.stringify(bodyPost.serializeBaseline()));
  } catch {
    /* 隐私模式 / 配额不足：不落盘不影响运行 */
  }
}

/**
 * 手动重置躯干基线（UI 按钮 / __vfc.resetBodyBaseline）：清掉该角色的持久化基线，
 * 重新收 10 帧渐入（TorsoBaseline 的 collected/10 权重保证不从 0 突跳到基线）。
 */
function resetBodyBaselineForProfile() {
  bodyPost.resetBaseline();
  bodyBaselineWasCalibrated = false;
  try {
    window.localStorage.removeItem(bodyBaselineKey(state.modelName ?? ""));
  } catch {
    /* ignore */
  }
}

/**
 * 上半身状态机的初始值（连接 / 重连 / 断线时复位）——纯函数版在 mapping.js.createBodyState。
 *
 * points / torso 保留最后一帧给调试骨架灰显；ok=false 时所有 body 参数按帧回 0。
 */
function createBodyState() {
  return createBodyStateCore();
}

function resetBodyState() {
  // 新会话：平滑器与 dt 锚点重置（基线是「这个人的中性姿态」，跨会话保留）
  bodyPost.resetSmoothers();
  state.body = createBodyState();
  state.mergedParams = null;
}

/**
 * 解析 track 的 ub / ubOk / ubT / body，并算出这一帧要驱动的上半身参数。
 *
 * 丢失（ubOk=false / 字段缺失 / ubT 过期 / ub 长度不对 / 低可见度侧的参数）时，
 * 由 smoothBodyParams() 按帧向 0 衰减 —— 绝不保留最后一帧不动（三层回退完全不变）。
 *
 * upperBodyPostV1（ubRaw:true）时先走 bodyPost 后处理链（One-Euro 平滑 + 躯干角 +
 * TorsoBaseline，与 Kotlin 原实现对拍 ≤0.5°），mapBody / smoothBodyParams 不变；
 * 未协商（老报文）不进管线，行为与改动前逐值一致。
 */
function updateBodyState(track, now) {
  const prev = state.body ?? createBodyState();
  const next = updateBodyStateFromTrack(track, now, prev, { pipeline: bodyPost });
  maybeSaveBodyBaseline();
  syncBodySmoothingTau(next.raw === true);
  state.body = next;
  return next;
}

/**
 * 滤波责任归属（docs/upper-body.md §3.2）：One-Euro（bodyPost）负责信号调理，
 * 渲染层只留**一层**轻量观感平滑。原始模式把渲染层低通降到 RAW_BODY_RENDER_TAU_MS，
 * 避免「One-Euro + 160ms」双重过度平滑；未协商保持旧 160ms 逐位不变。
 * 可配置低通本体由 M4 的 filter.js 统一实现（存在 setBodySmoothTau 才对接，否则 no-op）。
 */
function syncBodySmoothingTau(raw) {
  if (raw === lastBodyRawMode) return;
  lastBodyRawMode = raw;
  stage.setBodySmoothTau?.(raw ? RAW_BODY_RENDER_TAU_MS : 160);
}

/** 「重置上半身基线」按钮（动态创建，避免与并行任务的 index.html 改动抢同一文件）。 */
function initBodyBaselineUi() {
  try {
    const host = ui.calibHint?.parentElement ?? null;
    if (!host || document.getElementById("btn-body-baseline")) return;
    const button = document.createElement("button");
    button.id = "btn-body-baseline";
    button.type = "button";
    button.textContent = "重置上半身基线";
    button.title = "按角色保存的躯干中性姿态基线：点击清掉并重新收集（约 0.7 秒渐入）";
    button.addEventListener("click", () => {
      resetBodyBaselineForProfile();
      setCalibHint("已重置上半身基线：保持自然坐姿约 0.7 秒，重新收集中性姿态。", "muted");
      publishDebug();
    });
    host.appendChild(button);
    window.addEventListener("pagehide", () => maybeSaveBodyBaseline());
  } catch {
    /* 无 UI（截图 / 自测环境）不影响运行 */
  }
}

/**
 * 头部参数 + 上半身参数 → 合并参数表（调试 / __vfc.state 用）。
 *
 * ParamBodyAngleX 在**这里只做展示**：真正写入 core 时由 live2d.js 用 combineBodyAngleX()
 * 叠加（头部走时间轴插值，上半身按输入帧更新，两者节奏不同，不能提前烧死）。
 */
function mergeBodyParams(headParams, bodyParams) {
  const merged = { ...(headParams ?? {}) };
  const bodyX = bodyParams?.ParamBodyAngleX;
  if (typeof bodyX === "number" && Number.isFinite(bodyX)) {
    merged.ParamBodyAngleX = combineBodyAngleX(headParams?.ParamBodyAngleX, bodyX);
  }
  for (const id of BODY_PARAM_IDS) {
    if (id === "ParamBodyAngleX") continue;
    const value = bodyParams?.[id];
    if (typeof value === "number" && Number.isFinite(value)) merged[id] = value;
  }
  return merged;
}

/** 调试面板 / 骨架角标的上半身状态文本。 */
function bodyStatusText(body) {
  if (!body || !body.present) return "未下发";
  if (body.ok) return "ok";
  if (body.stale) return "过期";
  if (body.ubOk === false) return "无结果";
  return "无效";
}

/** 上半身骨骼参数（臂 A / 肘 B / 肩 / 手）在当前模型上是否存在（自带 4 个模型都没有 → 预期降级）。 */
function bodyParamSupportText() {
  const support = stage.bodySupport ?? {};
  const pick = (id) => (support[id] ? "有" : "无");
  return [
    `臂A L:${pick("ParamArmLA")} R:${pick("ParamArmRA")}`,
    `肘B L:${pick("ParamArmLB")} R:${pick("ParamArmRB")}`,
    `肩 L:${pick("ParamShoulderL")} R:${pick("ParamShoulderR")}`,
    `手 L:${support.ParamHandL || support.ParamHandAngleL ? "有" : "无"} R:${support.ParamHandR || support.ParamHandAngleR ? "有" : "无"}`,
  ].join(" · ");
}

// ---- 调试骨架：画布右下角，13 点 + 15 条边（低可见度灰点），D 键 / ?skeleton=1 显示 ----

const skeletonView = {
  canvas: null,
  ctx: null,
  visible: false,
  raf: 0,
  palette: null,
};

/** 读 CSS 变量取色：骨架只用调色板里已有的 --accent / --ok / --muted，不新增颜色。 */
function initSkeletonView() {
  const canvas = ui.skeleton;
  if (!canvas) return;
  canvas.width = SKELETON_WIDTH;
  canvas.height = SKELETON_HEIGHT;
  skeletonView.canvas = canvas;
  skeletonView.ctx = canvas.getContext("2d");
  const styles = getComputedStyle(document.documentElement);
  const read = (name, fallback) => styles.getPropertyValue(name).trim() || fallback;
  skeletonView.palette = {
    bone: read("--accent", "#5ac8fa"),
    point: read("--ok", "#3ddc97"),
    dim: read("--muted", "#8d97ab"),
  };
}

/** 调试面板打开 / D 键 / ?skeleton=1 → 显示骨架；否则停止 rAF 并清屏。 */
function syncSkeletonVisibility() {
  const canvas = ui.skeleton;
  if (!canvas) return;
  const wanted = Boolean(ui.debugBox?.open) || query.get("skeleton") === "1";
  skeletonView.visible = wanted;
  canvas.hidden = !wanted;
  if (wanted) {
    if (!skeletonView.raf) scheduleSkeletonFrame();
  } else {
    stopSkeletonFrames();
    skeletonView.ctx?.clearRect(0, 0, canvas.width, canvas.height);
  }
}

function scheduleSkeletonFrame() {
  skeletonView.raf = window.requestAnimationFrame(() => {
    skeletonView.raf = 0;
    drawSkeleton();
    if (skeletonView.visible) scheduleSkeletonFrame();
  });
}

function stopSkeletonFrames() {
  if (skeletonView.raf) window.cancelAnimationFrame(skeletonView.raf);
  skeletonView.raf = 0;
}

/**
 * 画 13 点骨架：坐标直接用 ub 的**镜像归一化坐标**（协议 §5.3，本人左 = 画面左），
 * 按画布宽高缩放；低可见度点 / 骨架丢失时灰显。
 */
function drawSkeleton() {
  const ctx = skeletonView.ctx;
  const canvas = skeletonView.canvas;
  if (!ctx || !canvas) return;
  const width = canvas.width;
  const height = canvas.height;
  const palette = skeletonView.palette ?? { bone: "#5ac8fa", point: "#3ddc97", dim: "#8d97ab" };
  const body = state.body;
  const points = Array.isArray(body?.points) ? body.points : null;
  ctx.clearRect(0, 0, width, height);
  ctx.save();
  if (!points) {
    ctx.fillStyle = palette.dim;
    ctx.font = '11px "Cascadia Mono", Consolas, monospace';
    ctx.textAlign = "center";
    ctx.fillText("无上半身数据", width / 2, height / 2);
    ctx.restore();
    return;
  }

  const reliable = (point) => Boolean(body.ok) && point.v >= BODY_ARM_MIN_VISIBILITY;

  // 骨架：两端点都可信才连线（丢失时整体灰显，保留最后一帧形状方便排查）
  ctx.globalAlpha = body.ok ? 0.85 : 0.45;
  ctx.strokeStyle = body.ok ? palette.bone : palette.dim;
  ctx.lineWidth = 2;
  for (const [a, b] of BODY_BONES) {
    const pa = points[a];
    const pb = points[b];
    if (!pa || !pb || !reliable(pa) || !reliable(pb)) continue;
    ctx.beginPath();
    ctx.moveTo(pa.x * width, pa.y * height);
    ctx.lineTo(pb.x * width, pb.y * height);
    ctx.stroke();
  }

  // 13 个点：低可见度的小灰点
  for (const point of points) {
    const okPoint = reliable(point);
    ctx.globalAlpha = okPoint ? 1 : 0.55;
    ctx.fillStyle = okPoint ? palette.point : palette.dim;
    ctx.beginPath();
    ctx.arc(point.x * width, point.y * height, okPoint ? 3 : 2, 0, Math.PI * 2);
    ctx.fill();
  }

  // 角标：状态 + 数据年龄
  ctx.globalAlpha = 1;
  ctx.fillStyle = body.ok ? palette.point : palette.dim;
  ctx.font = '11px "Cascadia Mono", Consolas, monospace';
  ctx.textAlign = "left";
  ctx.fillText(bodyStatusText(body), 6, 14);
  if (body.ageMs != null) {
    ctx.fillStyle = palette.dim;
    ctx.textAlign = "right";
    ctx.fillText(`${Math.round(body.ageMs)} ms`, width - 6, height - 6);
  }
  ctx.restore();
}

// ---------------------------------------------------------------- 姿态校准（镜头没正对你）

function signedDeg(value) {
  const n = Number(value);
  const sign = n > 0 ? "+" : "";
  return `${sign}${(Number.isFinite(n) ? n : 0).toFixed(1)}°`;
}

function setCalibHint(text, tone = "muted") {
  if (!ui.calibHint) return;
  ui.calibHint.textContent = text;
  ui.calibHint.dataset.tone = tone;
}

/** 把当前偏移写进「当前角色」的 profile store，并镜像一份到旧的全局 key（老客户端兼容）。 */
function persistPoseProfile() {
  state.poseProfiles = setProfileFor(
    state.poseProfiles,
    state.modelName,
    // filter：最终滤波参数随角色一起保存（offsetToProfile 不带它就会回落默认值）
    offsetToProfile(state.poseOffsetTarget, state.poseRangeScale, state.filterProfile),
  );
  storeSet(POSE_PROFILE_STORAGE_KEY, encodePoseProfileStore(state.poseProfiles));
  // 旧版全局 key 继续写「最后生效的偏移」：老版本客户端只认它也不受影响。
  storeSet(POSE_OFFSET_STORAGE_KEY, encodePoseOffset(state.poseOffsetTarget));
}

/**
 * 把 offset 落到 state / localStorage / 归一化窗口，并刷新校准 UI。
 *
 * 公式不变：最终角度 = track.pose + pcOffset（track.pose 已减手机基线，PC 端只叠加）。
 *
 * smooth=true 时经 OffsetRamp 限速过渡（手动重置 / 手机重做基线用，避免模型瞬移）；
 * 默认 snap：立即生效（滑块 / __vfc.setPoseOffset，行为与旧版一致）。
 */
function applyPoseCalibration(offset, { persist = true, resetNormalizers = true, smooth = false } = {}) {
  const target = sanitizePoseOffset(offset);
  state.poseOffsetTarget = target;
  state.poseOffset = state.poseRamp.setTarget(target, { snap: !smooth });
  // 旧的自适应量程是按旧零点观察到的：换零点后重来，否则前几秒模型会「不跟手」。
  if (resetNormalizers) mapper.reset();
  // 手动 / 一键校准给出新锚点：缓慢自适应只允许在它 ±10° 内慢慢漂，不会无限跑飞。
  state.poseAdaptive.setAnchor(target);
  if (persist) persistPoseProfile();
  renderCalibUi();
  publishDebug();
  return { ...state.poseOffsetTarget };
}

/**
 * 切换角色：加载该角色的 profile（三轴偏移 + 量程偏好）并立即生效。
 * 每个角色各存各的 offset，切回时原样恢复（验收：不同角色保存不同 offset，切换后生效）。
 */
function activatePoseProfile(modelName) {
  const key = profileKeyFor(modelName);
  if (!state.poseProfiles.profiles[key]) {
    // 迁移：该角色还没有 profile → 用旧全局偏移播种（升级前的行为延续给每个角色）。
    const seeded = sanitizePoseProfile({
      ...decodePoseOffset(storeGet(POSE_OFFSET_STORAGE_KEY)),
      rangeScale: DEFAULT_POSE_PROFILE.rangeScale,
    });
    state.poseProfiles = setProfileFor(state.poseProfiles, modelName, seeded);
  }
  const profile = state.poseProfiles.profiles[key];
  state.poseRangeScale = profile.rangeScale;
  mapper.setRangeScale(profile.rangeScale); // 量程偏好：1 = 与现在完全一致
  state.poseAdaptive.reset();
  // M4：最终观感滤波参数也随角色恢复（总开关默认关 = 与旧版行为完全一致）
  applyFilterProfile(profile.filter, { persist: false });
  applyPoseCalibration(profileToOffset(profile), { persist: true, resetNormalizers: true, smooth: false });
}

/**
 * M4：应用最终观感滤波 profile（含总开关）。
 *
 * - enabled = false（默认）：完全走现有滤波参数（时间轴插值 + 每帧缓动 + 上半身 160ms 低通），
 *   归一化旋钮也回到默认（AdaptiveAngleNormalizer 保持「向外快 0.1 / 向内慢 0.05→0.01」）；
 * - enabled = true：live2d.js 用速度自适应低通**替换**那两段低通（不叠加），头/脸与上半身分开配置；
 * - persist=true 时写进当前角色的 profile（vfc.poseProfiles），换角色各用各的。
 */
function applyFilterProfile(profile, { persist = true } = {}) {
  state.filterProfile = sanitizeFilterProfile(profile);
  stage.setFilterProfile(state.filterProfile);
  mapper.configureNormalizers(state.filterProfile.enabled ? state.filterProfile.normalizer : null);
  if (persist) persistPoseProfile();
  renderFilterUi();
  publishDebug();
  return { ...state.filterProfile };
}

/** 滤波滑块 ↔ profile 字段映射（头/脸 与 上半身 分开配置）。 */
const FILTER_SLIDER_MAP = {
  headSlow: ["head", "slowTauMs"],
  headFast: ["head", "fastTauMs"],
  bodySlow: ["body", "slowTauMs"],
  bodyFast: ["body", "fastTauMs"],
};

/** 把当前 filterProfile 回填到开关 / 滑块（拖动中不回写，与校准滑块同一约定）。 */
function renderFilterUi() {
  const profile = state.filterProfile;
  if (ui.tgFinalFilter && document.activeElement !== ui.tgFinalFilter) {
    ui.tgFinalFilter.checked = Boolean(profile.enabled);
  }
  for (const [key, [group, field]] of Object.entries(FILTER_SLIDER_MAP)) {
    const input = ui.filterSliders?.[key];
    const label = ui.filterValues?.[key];
    const value = profile[group][field];
    if (input && document.activeElement !== input) input.value = String(value);
    if (label) label.textContent = `${Math.round(value)} ms`;
  }
  if (ui.btnFilterReset) ui.btnFilterReset.disabled = isDefaultFilterProfile(profile);
}

/**
 * 可选缓慢自适应（默认关闭）：低运动 + 低表情变化时极慢地把偏移朝「最终角度归零」挪。
 * 说话 / 眨眼 / 摇头都会被 AdaptivePoseOffset 的门限挡掉，不污染 offset。
 */
function maybeAdaptOffset(track) {
  const outcome = state.poseAdaptive.update(track, state.poseOffsetTarget);
  if (!outcome.changed) return;
  state.poseOffsetTarget = outcome.offset;
  state.poseOffset = { ...outcome.offset };
  state.poseRamp.setTarget(outcome.offset, { snap: true });
  // 持久化节流：累计变化 ≥ 0.2° 或距上次保存 ≥ 5s 才写 localStorage（每帧都写会伤盘）。
  const now = performance.now();
  const last = state.lastAdaptiveSavedOffset;
  const drift = last
    ? Math.max(
        Math.abs(outcome.offset.pitch - last.pitch),
        Math.abs(outcome.offset.yaw - last.yaw),
        Math.abs(outcome.offset.roll - last.roll),
      )
    : Infinity;
  if (drift >= 0.2 || now - state.lastAdaptiveSavedAt >= 5000) {
    persistPoseProfile();
    state.lastAdaptiveSavedAt = now;
    state.lastAdaptiveSavedOffset = { ...outcome.offset };
  }
  renderCalibUi();
  publishDebug();
}

/** 开关缓慢自适应（默认关；只影响 PC 本地偏移，不改协议、不动手机端）。 */
function setAdaptiveEnabled(enabled) {
  state.poseAdaptive.enabled = Boolean(enabled);
  state.adaptiveEnabled = state.poseAdaptive.enabled;
  state.poseAdaptive.reset();
  state.poseAdaptive.setAnchor(state.poseOffsetTarget);
  storeSet(POSE_ADAPTIVE_STORAGE_KEY, state.poseAdaptive.enabled ? "1" : "0");
  if (ui.tgPoseAdaptive) ui.tgPoseAdaptive.checked = state.poseAdaptive.enabled;
  setCalibHint(
    state.poseAdaptive.enabled
      ? "已开启缓慢自适应：只在你安静不动、不说话时极慢微调偏移；想固定就关掉或点「清零」。"
      : "已关闭缓慢自适应：偏移只由「一键校准 / 滑块 / 清零」决定。",
    "muted",
  );
  publishDebug();
}

function renderCalibUi() {
  // 滑块 / 提示显示「目标偏移」（平滑过渡期间模型还在追，但 UI 已是新值）。
  const offset = state.poseOffsetTarget;
  for (const axis of POSE_AXES) {
    const input = ui.calibSliders[axis];
    const label = ui.calibValues[axis];
    if (!input || !label) continue;
    // 拖动中不要回写 value，否则滑块会被拽回去。
    if (document.activeElement !== input) input.value = String(offset[axis]);
    label.textContent = signedDeg(offset[axis]);
  }
  if (ui.btnCalibReset) ui.btnCalibReset.disabled = isNeutralOffset(offset);
  const summary = ui.calibBox?.querySelector("summary");
  if (summary) {
    summary.textContent = isNeutralOffset(offset)
      ? "手动微调姿态（未校准）"
      : `手动微调姿态（已校准 ${formatPoseOffset(offset)}）`;
  }
  if (ui.btnAlign) {
    ui.btnAlign.classList.toggle("active", !!state.align);
    ui.btnAlign.title = state.align ? "点击取消本次校准" : "保持坐姿 3 秒，把当前头姿记为中性（镜头不正对你时用）";
    ui.btnAlign.textContent = state.align ? "取消校准" : "一键校准";
  }
}

/**
 * 一键校准：接下来 durationMs 内收到的 pose 取「截尾平均」并取反，作为新的中性零点。
 * 返回的 Promise 在采样结束 / 取消 / 帧数不足时 resolve，便于端到端测试。
 */
function startAlign(durationMs = ALIGN_DURATION_MS) {
  if (state.align) return state.align.promise;
  if (!state.connected && !state.frozen) {
    setStatus("未连接，无法校准", "error");
    setCalibHint("还没连上手机，先连接再校准。", "warn");
    return Promise.resolve({ ok: false, reason: "not-connected" });
  }

  const totalMs = Math.max(300, Number(durationMs) || ALIGN_DURATION_MS);
  const collector = new PoseOffsetCollector();
  let resolveFn;
  const promise = new Promise((resolve) => {
    resolveFn = resolve;
  });
  state.align = {
    collector,
    startedAt: performance.now(),
    durationMs: totalMs,
    timer: null,
    promise,
    resolve: resolveFn,
  };
  setCalibHint(`保持自然坐姿，看向屏幕：${(totalMs / 1000).toFixed(1)} 秒后把当前头姿记为中性…`, "active");
  setStatus("校准中：保持自然坐姿…", "connecting");
  renderCalibUi();
  state.align.timer = setInterval(() => tickAlign(false), ALIGN_TICK_MS);
  tickAlign(false);
  return promise;
}

function tickAlign(cancelled) {
  const align = state.align;
  if (!align) return;
  const remaining = Math.max(0, align.durationMs - (performance.now() - align.startedAt));
  if (!cancelled) {
    setCalibHint(
      `保持当前坐姿：剩余 ${(remaining / 1000).toFixed(1)} s · 已采集 ${align.collector.count} 帧`,
      "active",
    );
  }
  if (cancelled || remaining <= 0) finishAlign(cancelled);
}

function finishAlign(cancelled) {
  const align = state.align;
  if (!align) return null;
  state.align = null;
  clearInterval(align.timer);

  const report = align.collector.report();
  let outcome;
  if (cancelled) {
    setCalibHint("已取消校准，偏移未改变。", "muted");
    setStatus(state.connected ? (state.device ? `已连接 · ${state.device}` : "已连接") : "未连接", connPhase());
    outcome = { ok: false, reason: "cancelled", report };
  } else if (!report.ready) {
    setCalibHint(
      `只收到 ${report.samples} 帧有效人脸（至少要 12 帧）。确认手机还在推流、画面里能看到脸，再试一次。`,
      "warn",
    );
    setStatus("校准失败：没有足够的人脸帧", "error");
    outcome = { ok: false, reason: "no-samples", report };
  } else {
    const applied = applyPoseCalibration(report.offset, { persist: true });
    const steady = report.stable;
    state.lastAlign = {
      at: Date.now(),
      samples: report.samples,
      spread: report.spread,
      offset: applied,
    };
    setCalibHint(
      `已校准：偏移 ${formatPoseOffset(applied)}，${report.samples} 帧，抖动 ${report.spread.toFixed(1)}°。` +
        (steady ? "下次换座位 / 重新架手机后再点一次即可。" : "刚才头在动，建议坐定后再点一次更准。"),
      steady ? "ok" : "warn",
    );
    setStatus(
      steady ? "校准完成：已把当前头姿记为中性" : "校准完成（刚才头在动，可能不够准）",
      "connected",
    );
    outcome = { ok: true, offset: applied, samples: report.samples, spread: report.spread, report };
  }

  renderCalibUi();
  publishDebug();
  align.resolve(outcome);
  return outcome;
}

function cancelAlign() {
  if (!state.align) return { ok: false, reason: "idle" };
  return finishAlign(true);
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
    // 每个角色各存各的偏移 + 量程偏好：加载完模型就把该角色的 profile 装上（立即生效）。
    activatePoseProfile(target.name);
    activateBodyBaselineProfile(target.name);
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
  const interp = stage.getInterpolatorStats();
  const rows = [
    ["bs.eyeBlinkL", fmt(bs[BS_INDEX.eyeBlinkLeft], 3)],
    ["bs.eyeBlinkR", fmt(bs[BS_INDEX.eyeBlinkRight], 3)],
    ["bs.jawOpen", fmt(bs[BS_INDEX.jawOpen], 3)],
    ["bs.smile", fmt(((bs[BS_INDEX.mouthSmileLeft] ?? 0) + (bs[BS_INDEX.mouthSmileRight] ?? 0)) / 2, 3)],
    ["pose pitch", fmt(pose[0], 2)],
    ["pose yaw", fmt(pose[1], 2)],
    ["pose roll", fmt(pose[2], 2)],
    ["偏移 p/y/r", formatPoseOffset(state.poseOffsetTarget)],
    ["校准后 pose", state.lastAppliedPose
      ? `${fmt(state.lastAppliedPose.pitch, 2)} / ${fmt(state.lastAppliedPose.yaw, 2)} / ${fmt(state.lastAppliedPose.roll, 2)}`
      : "-"],
    ["缓慢自适应", state.poseAdaptive.enabled ? `开 · 已更 ${state.poseAdaptive.stats.updates} 次` : "关（默认）"],
    ["最终滤波", state.filterProfile.enabled ? "开 · 速度自适应" : "关（默认）"],
    [
      "滤波 τ 头/身",
      `${Math.round(state.filterProfile.head.slowTauMs)}→${Math.round(state.filterProfile.head.fastTauMs)}` +
        ` / ${Math.round(state.filterProfile.body.slowTauMs)}→${Math.round(state.filterProfile.body.fastTauMs)} ms`,
    ],
    ["动作速度", fmt(stage.timeline.motionSpeed, 2) + " 量程/s"],
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
    ["BodyAngleY →", fmt(stage.getEngineParam("ParamBodyAngleY"), 2)],
    ["BodyAngleZ →", fmt(stage.getEngineParam("ParamBodyAngleZ"), 2)],
    ["BodyAngleX 头+身", fmt(state.mergedParams?.ParamBodyAngleX, 2)],
    ["上半身", bodyStatusText(state.body)],
    ["ub age", state.body?.ageMs == null ? "-" : `${Math.round(state.body.ageMs)} ms`],
    ["torso r/y", `${fmt(state.body?.torso?.[0], 1)} / ${fmt(state.body?.torso?.[1], 1)}`],
    ["arm L/R", `${fmt(state.body?.metrics?.armAngleL, 0)}° / ${fmt(state.body?.metrics?.armAngleR, 0)}°`],
    ["elbow L/R", `${fmt(state.body?.metrics?.elbowBendL, 0)}° / ${fmt(state.body?.metrics?.elbowBendR, 0)}°`],
    ["肩 差动/脊柱", `${fmt(state.body?.metrics?.clavicleRelTilt, 1)}° / ${fmt(state.body?.metrics?.spineLean, 1)}°`],
    ["骨骼参数", bodyParamSupportText()],
    ["target AngleX", fmt(params.ParamAngleX, 2)],
    ["target AngleY", fmt(params.ParamAngleY, 2)],
    ["目标帧率", stage.targetFps ? String(stage.targetFps) : "不限"],
    ["实测渲染", fmt(state.renderFps, 1)],
    ["输入间隔", fmt(interp.inputIntervalMs, 1) + " ms"],
    ["输入抖动", fmt(interp.jitterMs, 1) + " ms" + (interp.starvedEpisodes ? ` · 见底 ${interp.starvedEpisodes} 次` : "")],
    ["插值延迟", fmt(interp.delayMs, 1) + " ms" + (interp.active ? " · 插值中" : "")],
    ["缓冲档位", (interp.primed === false ? "预缓冲中 · " : "") + ({ stable: "稳定", jitter: "抖动", heavy: "剧烈" }[interp.depthTier] ?? interp.depthTier ?? "-") + (interp.targetDelayMs != null ? ` · 目标 ${fmt(interp.targetDelayMs, 0)} ms` : "")],
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
    state.renderFps = stage.getRenderFps();

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
    // 手机校准期间 pose 是「未归零」的原始值，正在采样的一键校准会被污染。
    cancelAlign();
    mapper.reset();
    setStatus("已发送重新校准命令，等待手机 ack…", "connecting");
    setCalibHint("已让手机重做中性脸基线：校准完成后本地对齐偏移会自动清零，需要再点「一键校准」做最后的对齐。", "active");
  });

  // ---- 姿态校准：一键对齐 + 手动微调 ----

  ui.btnAlign.addEventListener("click", () => {
    if (state.align) cancelAlign();
    else startAlign();
  });

  for (const axis of POSE_AXES) {
    const input = ui.calibSliders[axis];
    if (!input) continue;
    input.min = String(-45);
    input.max = String(45);
    input.step = "0.5";
    input.addEventListener("input", () => {
      const next = { ...state.poseOffsetTarget, [axis]: Number(input.value) };
      applyPoseCalibration(next, { persist: false });
      setCalibHint(
        `正在微调「${POSE_AXIS_LABELS[axis]}」${signedDeg(state.poseOffsetTarget[axis])}：拖到模型正对镜头，松手后自动保存。`,
        "muted",
      );
    });
    input.addEventListener("change", () => {
      persistPoseProfile();
      setCalibHint(
        `已保存偏移 ${formatPoseOffset(state.poseOffsetTarget)}（仅当前角色）。换座位 / 重新架手机后点一次「一键校准」更省事。`,
        "ok",
      );
    });
  }

  ui.btnCalibReset?.addEventListener("click", () => {
    // 手动重置：目标清零但**限速过渡**（OffsetRamp），模型缓慢回正、不瞬移。
    applyPoseCalibration(DEFAULT_POSE_OFFSET, { persist: true, smooth: true });
    setCalibHint("已清零（平滑过渡）：模型正缓慢回到手机端基线驱动。", "muted");
  });

  // 可选缓慢自适应偏移：默认关闭，开关状态持久化（vfc.poseAdaptive）。
  ui.tgPoseAdaptive?.addEventListener("change", () => {
    setAdaptiveEnabled(ui.tgPoseAdaptive.checked);
  });

  // M4 最终观感滤波：总开关 + 头/脸与上半身分开的慢/快强度（按角色 profile 自动保存）
  ui.tgFinalFilter?.addEventListener("change", () => {
    applyFilterProfile({ ...state.filterProfile, enabled: ui.tgFinalFilter.checked });
  });
  for (const [key, [group, field]] of Object.entries(FILTER_SLIDER_MAP)) {
    const input = ui.filterSliders?.[key];
    if (!input) continue;
    input.addEventListener("input", () => {
      applyFilterProfile(
        { ...state.filterProfile, [group]: { ...state.filterProfile[group], [field]: Number(input.value) } },
        { persist: false },
      );
    });
    // 松手才落盘（与校准滑块同一约定）：拖动过程中只改内存里的 profile
    input.addEventListener("change", () => persistPoseProfile());
  }
  ui.btnFilterReset?.addEventListener("click", () => {
    applyFilterProfile(sanitizeFilterProfile(null));
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

  ui.debugBox.addEventListener("toggle", () => {
    renderDebugGrid();
    syncSkeletonVisibility();
  });

  window.addEventListener("keydown", (event) => {
    if (event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement) return;
    if (event.key === "h" || event.key === "H") {
      toggleUiHidden();
    } else if (event.key === "d" || event.key === "D") {
      ui.debugBox.open = !ui.debugBox.open;
      renderDebugGrid();
      syncSkeletonVisibility();
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

  /**
   * 手动喂上半身数据（没有手机 / 截图自测）：不经过 WS，直接更新 body 状态并立即渲染。
   *
   *   __vfc.applyUpperBody({ ub: [...52 个数], body: [roll, yaw], ubOk: true })
   *   __vfc.applyUpperBody({ ubOk: false })   // 验证平滑回默认
   *
   * 会自动冻结 WS（与 applyTrack 一致），返回本帧的 body 调试信息。
   *
   * @param {{ub?:number[], body?:number[], ubOk?:boolean, ubT?:number}} [payload]
   */
  applyUpperBody(payload = {}) {
    state.frozen = true;
    stage.setSmoothing(1);
    const base = state.lastTrack && typeof state.lastTrack === "object" ? state.lastTrack : {};
    const track = {
      ...base,
      type: "track",
      seq: Number.isFinite(Number(base.seq)) ? Number(base.seq) + 1 : state.frames + 1,
      t: Number.isFinite(Number(base.t)) ? Number(base.t) + 33 : Date.now(),
    };
    const ubInput = payload?.ub;
    if (Array.isArray(ubInput) || ArrayBuffer.isView(ubInput)) track.ub = ubInput;
    const bodyInput = payload?.body;
    if (Array.isArray(bodyInput) || ArrayBuffer.isView(bodyInput)) track.body = bodyInput;
    track.ubOk = payload?.ubOk !== undefined ? Boolean(payload.ubOk) : track.ub != null;
    const ubT = Number(payload?.ubT);
    track.ubT = Number.isFinite(ubT) ? ubT : Number(track.t);
    // 截图 / 自测要确定性：这一帧直接落到目标（丢失时直接回 0），不按 15%/帧慢慢衰减
    if (state.body) state.body.snap = true;
    feedTrack(track);
    if (stage.ready) {
      stage.renderNow();
      publishDebug();
    }
    return { body: { ...(debugState.body ?? {}) }, params: state.mergedParams };
  },

  /** 本地姿态偏移（镜头没正对你时的校准量，度）——当前**应用中**的值（平滑过渡期间可能未到目标）。
   *  叠加公式：最终角度 = track.pose + poseOffset（track.pose 已减手机基线，PC 端不再减）。 */
  get poseOffset() {
    return { ...state.poseOffset };
  },
  /** 目标偏移（滑块 / 持久化值；手动重置等平滑过渡结束后与 poseOffset 相等）。 */
  get poseOffsetTarget() {
    return { ...state.poseOffsetTarget };
  },
  /** 直接设置三轴偏移（度），立即生效并按当前角色持久化。 */
  setPoseOffset(offset) {
    return applyPoseCalibration(offset, { persist: true });
  },
  /** 手动重置：偏移清零，限速平滑过渡（不瞬移），并持久化到当前角色。 */
  resetPoseOffset() {
    return applyPoseCalibration(DEFAULT_POSE_OFFSET, { persist: true, smooth: true });
  },
  /** 读某角色的 profile（{pitchOffset,yawOffset,rollOffset,rangeScale}）。 */
  /** 手动重置当前角色的上半身基线（M2；与 UI「重置上半身基线」按钮同源）。 */
  resetBodyBaseline() {
    resetBodyBaselineForProfile();
    return true;
  },
  /** 后处理管线 / 基线状态（验收与调试用）。 */
  bodyPostState() {
    return {
      calibrated: bodyPost.baseline.isCalibrated,
      baseline: bodyPost.serializeBaseline(),
    };
  },
  getPoseProfile(modelName) {
    return { ...getProfileFor(state.poseProfiles, modelName ?? state.modelName) };
  },
  /** 按角色 profile store 的原始快照（调试 / 验收用）。 */
  get poseProfiles() {
    return decodePoseProfileStore(encodePoseProfileStore(state.poseProfiles));
  },
  /** 可选缓慢自适应：开关（默认关）+ 当前统计。 */
  setAdaptiveEnabled(enabled) {
    setAdaptiveEnabled(Boolean(enabled));
    return state.poseAdaptive.enabled;
  },
  get poseAdaptive() {
    return {
      enabled: state.poseAdaptive.enabled,
      rate: state.poseAdaptive.rate,
      quietStreak: state.poseAdaptive.quietStreak,
      updates: state.poseAdaptive.stats.updates,
      skippedMotion: state.poseAdaptive.stats.skippedMotion,
      skippedExpression: state.poseAdaptive.stats.skippedExpression,
      maxDriftDeg: state.poseAdaptive.maxDriftDeg,
    };
  },
  /** 一键校准状态（verifier / 调试用）。 */
  get calibration() {
    return {
      active: !!state.align,
      samples: state.align ? state.align.collector.count : 0,
      remainingMs: state.align
        ? Math.max(0, Math.round(state.align.startedAt + state.align.durationMs - performance.now()))
        : 0,
      offset: { ...state.poseOffsetTarget },
      neutral: isNeutralOffset(state.poseOffsetTarget),
      lastAlign: state.lastAlign ? { ...state.lastAlign } : null,
    };
  },
  /**
   * 一键校准：接下来 seconds 秒内采样收到的 pose，取反后作为新的中性零点。
   * @returns {Promise<{ok:boolean, offset?:object, samples?:number, spread?:number, reason?:string}>}
   */
  align(seconds = ALIGN_DURATION_MS / 1000) {
    const value = Number(seconds);
    const durationMs = Number.isFinite(value) ? Math.max(0.3, value) * 1000 : ALIGN_DURATION_MS;
    return startAlign(durationMs);
  },
  cancelAlign() {
    return cancelAlign();
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
      align: state.align
        ? {
            active: true,
            samples: state.align.collector.count,
            remainingMs: Math.max(0, Math.round(state.align.startedAt + state.align.durationMs - performance.now())),
          }
        : null,
      poseOffset: { ...state.poseOffset },
      params: state.lastResult?.params ?? null,
      mergedParams: state.mergedParams ?? null,
      body: { ...(state.body ?? createBodyState()) },
      normalized: state.lastResult?.normalized ?? null,
      ready: stage.ready,
      modelUrl: stage.modelUrl,
      smoothing: stage.smoothing,
      targetFps: stage.targetFps,
      renderFps: stage.getRenderFps(),
      interp: stage.getInterpolatorStats(),
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
  /** M4 最终观感滤波：当前完整 profile（含总开关；按角色随 vfc.poseProfiles 保存）。 */
  get filterProfile() {
    return { ...state.filterProfile };
  },
  /** 整体替换最终滤波 profile（自动持久化到当前角色）。 */
  setFilterProfile(profile) {
    return applyFilterProfile(profile);
  },
  /** 只切总开关（默认 false = 与旧版行为完全一致）。 */
  setFinalFilter(enabled) {
    return applyFilterProfile({ ...state.filterProfile, enabled: Boolean(enabled) });
  },
  /** 改渲染目标帧率（0 = 不限制），返回实际生效值。 */
  setTargetFps(value) {
    const fps = stage.setTargetFps(value);
    state.targetFps = fps;
    publishDebug();
    return fps;
  },
  getRenderFps() {
    return stage.getRenderFps();
  },
  /** 插值缓冲状态：{ active, delayMs, inputIntervalMs, samples }。 */
  get interp() {
    return stage.getInterpolatorStats();
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
  initSkeletonView();
  syncSkeletonVisibility();
  renderCalibUi(); // 把 localStorage 里保存的本地姿态偏移回填到滑块 / 提示
  initBodyBaselineUi();
  renderFilterUi(); // M4：最终滤波开关 / 滑块回填（模型加载后 activatePoseProfile 会再按角色套用）
  // 平滑过渡器与应用中偏移对齐；缓慢自适应默认关闭，只有显式存过 "1" 才开（行为与现在一致）。
  state.poseRamp.setTarget(state.poseOffsetTarget, { snap: true });
  const adaptiveOn = storeGet(POSE_ADAPTIVE_STORAGE_KEY) === "1";
  state.poseAdaptive.enabled = adaptiveOn;
  state.adaptiveEnabled = adaptiveOn;
  if (ui.tgPoseAdaptive) ui.tgPoseAdaptive.checked = adaptiveOn;
  await initNativeControls();
  ui.renderFps.title =
    stage.targetFps > 0 ? `目标 ${stage.targetFps} fps（URL ?fps=<n> 可改，0 = 不限）` : "不限制帧率（跟随 vsync）";
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
