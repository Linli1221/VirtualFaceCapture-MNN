#!/usr/bin/env node
/**
 * tools/mock-tracker.js — 模拟手机端（协议 v1 的 WS 服务端），用来在本机自测渲染客户端
 *
 * 零依赖：自己实现 WebSocket 握手 + 帧编解码（只做 ws 服务端需要的那一小部分）。
 *
 *   node tools/mock-tracker.js                       # ws://127.0.0.1:8765/ws，60Hz，一直发
 *   node tools/mock-tracker.js --port 8765 --frames 600
 *   node tools/mock-tracker.js --scenario closed     # 眼睛全闭（截图对比用）
 *   node tools/mock-tracker.js --scenario sway       # 只摆头
 *   node tools/mock-tracker.js --scenario neutral    # 全中性
 *   node tools/mock-tracker.js --hz 30 --jitter 20   # 30 Hz，发送时间随机晚 0~20 ms（测抖动缓冲）
 *
 * 参数：
 *   --port <n>        监听端口（默认 8765，协议固定值）
 *   --host <ip>       监听地址（默认 0.0.0.0，方便真机/局域网客户端连）
 *   --frames <n>      发 n 帧后停止推流（默认 0 = 不限）
 *   --hz <n>          每秒帧数（默认 60）
 *   --jitter <ms>     模拟传输抖动：发送时间在计划时间后随机晚 0~ms
 *                     （报文里的 `t` 仍按计划采集时间递增，用来验证 PC 端时间轴）
 *   --scenario <name> all | neutral | blink | sway | jaw | closed | open
 *   --quiet           不打印每秒统计
 *
 * 同时提供 HTTP：GET /（信息页）、GET /status（JSON，同协议 §6）、
 *                 GET /__control?mode=blink（运行时切换 scenario）
 */
import http from "node:http";
import crypto from "node:crypto";

const WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
const PROTOCOL = "vfc-tracker";
const VERSION = 1;

const BS = {
  eyeLookUpLeft: 17,
  eyeLookDownLeft: 11,
  eyeLookUpRight: 18,
  eyeLookDownRight: 12,
  eyeBlinkLeft: 9,
  eyeBlinkRight: 10,
  jawOpen: 25,
  mouthFrownLeft: 30,
  mouthFrownRight: 31,
  mouthSmileLeft: 44,
  mouthSmileRight: 45,
};

const SCENARIOS = {
  all: { blink: true, sway: true, jaw: true, expression: true, faceDrop: true },
  neutral: {},
  blink: { blink: true },
  sway: { sway: true },
  jaw: { jaw: true },
  closed: { staticBlink: 1 },
  open: { staticBlink: 0 },
  face: { blink: true, sway: true, jaw: true, faceDrop: true },
};

const options = {
  host: "0.0.0.0",
  port: 8765,
  frames: 0,
  hz: 60,
  jitter: 0,
  scenario: "all",
  quiet: false,
};

function parseArgs(argv) {
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--port" || arg === "-p") options.port = Number(argv[++i]) || options.port;
    else if (arg === "--host") options.host = argv[++i] || options.host;
    else if (arg === "--frames" || arg === "-n") options.frames = Math.max(0, Number(argv[++i]) || 0);
    else if (arg === "--hz") options.hz = Math.max(1, Number(argv[++i]) || options.hz);
    else if (arg === "--jitter") options.jitter = Math.max(0, Number(argv[++i]) || 0);
    else if (arg === "--scenario" || arg === "--mode") options.scenario = argv[++i] || options.scenario;
    else if (arg === "--quiet" || arg === "-q") options.quiet = true;
    else if (arg === "--help" || arg === "-h") {
      console.log(`用法：node tools/mock-tracker.js [--port 8765] [--frames N] [--hz 60] [--jitter 0] [--scenario all|neutral|blink|sway|jaw|closed|open]`);
      process.exit(0);
    }
  }
  if (!SCENARIOS[options.scenario]) {
    console.warn(`[mock] 未知 scenario「${options.scenario}」，回退到 all。可选：${Object.keys(SCENARIOS).join(", ")}`);
    options.scenario = "all";
  }
}

// ------------------------------------------------------------- 合成数据

const round4 = (value) => Math.round(value * 10000) / 10000;

/** 按帧号确定性生成一帧 track（同样帧号永远得到同样数据，方便截图复现）。 */
function buildTrack(seq, timestampMs) {
  const t = seq / options.hz;
  const flags = SCENARIOS[options.scenario] ?? SCENARIOS.all;
  const bs = new Array(52).fill(0);
  const pose = [0, 0, 0];

  // 眨眼：2.4s 周期，其中约 0.32s 闭眼（带平滑开合）
  let blink = 0;
  if (typeof flags.staticBlink === "number") {
    blink = flags.staticBlink;
  } else if (flags.blink) {
    const period = 2.4;
    const close = 0.32;
    const phase = t % period;
    if (phase < close) {
      const k = phase / close;
      blink = k < 0.3 ? k / 0.3 : k > 0.7 ? (1 - k) / 0.3 : 1;
    }
  }
  if (blink > 0) {
    bs[BS.eyeBlinkLeft] = blink;
    bs[BS.eyeBlinkRight] = blink;
  }

  // 头部摆动（度，协议 §5.2 的符号：yaw<0 = 真人向右转）
  if (flags.sway) {
    pose[0] = 6 * Math.sin((2 * Math.PI * t) / 5.3 + 0.7);
    pose[1] = 18 * Math.sin((2 * Math.PI * t) / 4.0);
    pose[2] = 5 * Math.sin((2 * Math.PI * t) / 3.1);
  }

  // 张嘴（正弦 + 平方，像说话）
  if (flags.jaw) {
    const v = Math.max(0, Math.sin((2 * Math.PI * t) / 1.7));
    bs[BS.jawOpen] = Math.min(1, 0.08 + 0.8 * v * v);
  }

  // 微笑 / 皱眉（mouthForm = smile - frown）
  if (flags.expression) {
    const smile = 0.25 + 0.2 * Math.sin((2 * Math.PI * t) / 6.0);
    bs[BS.mouthSmileLeft] = Math.max(0, smile);
    bs[BS.mouthSmileRight] = Math.max(0, smile);
    bs[BS.mouthFrownLeft] = 0.04;
    bs[BS.mouthFrownRight] = 0.04;
  }

  // 偶发丢脸（每 9s 有 0.6s face:false）
  let face = true;
  if (flags.faceDrop && t % 9 > 8.4) {
    face = false;
    for (let i = 0; i < 52; i++) bs[i] = 0;
    pose[0] = 0;
    pose[1] = 0;
    pose[2] = 0;
  }

  return {
    type: "track",
    seq,
    t: Number.isFinite(timestampMs) ? timestampMs : Date.now(),
    bs: bs.map(round4),
    pose: pose.map(round4),
    face,
    score: face ? 0.98 : 0,
    fps: options.hz,
    calibrating: false,
  };
}

function helloMessage() {
  return {
    type: "hello",
    protocol: PROTOCOL,
    version: VERSION,
    device: "Mock Phone (VFC)",
    app: "VirtualFaceCapture-mock/1.0",
    decimation: 1,
    targetFps: options.hz,
    t: Date.now(),
  };
}

// ------------------------------------------------------------- WebSocket 服务端

const clients = new Set();
let frame = 0;
let startedAt = 0;
let sentSinceSample = 0;
let actualFps = 0;
let sampleAt = Date.now();
let timer = null;
let sending = false;

function encodeFrame(opcode, payload) {
  const length = payload.length;
  let header;
  if (length < 126) {
    header = Buffer.alloc(2);
    header[1] = length;
  } else if (length < 65536) {
    header = Buffer.alloc(4);
    header[1] = 126;
    header.writeUInt16BE(length, 2);
  } else {
    header = Buffer.alloc(10);
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(length), 2);
  }
  header[0] = 0x80 | opcode;
  return Buffer.concat([header, payload]);
}

/** 解析一帧客户端 → 服务端的数据（客户端帧一定带 mask）。返回 null 表示数据还不够。 */
function decodeFrame(buffer) {
  if (buffer.length < 2) return null;
  const first = buffer[0];
  const second = buffer[1];
  const opcode = first & 0x0f;
  const masked = (second & 0x80) !== 0;
  let length = second & 0x7f;
  let offset = 2;

  if (length === 126) {
    if (buffer.length < offset + 2) return null;
    length = buffer.readUInt16BE(offset);
    offset += 2;
  } else if (length === 127) {
    if (buffer.length < offset + 8) return null;
    const big = buffer.readBigUInt64BE(offset);
    if (big > 1000000n) return null; // 客户端不会发大包
    length = Number(big);
    offset += 8;
  }

  let mask = null;
  if (masked) {
    if (buffer.length < offset + 4) return null;
    mask = buffer.subarray(offset, offset + 4);
    offset += 4;
  }
  if (buffer.length < offset + length) return null;

  const payload = Buffer.from(buffer.subarray(offset, offset + length));
  if (mask) {
    for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
  }
  return { opcode, payload, consumed: offset + length };
}

function handleText(client, text) {
  let message;
  try {
    message = JSON.parse(text);
  } catch {
    return;
  }
  if (!message || typeof message !== "object") return;
  if (message.type === "ping") {
    client.send({ type: "pong", t: message.t ?? Date.now() });
  } else if (message.type === "command") {
    if (message.name === "recalibrate") {
      client.send({ type: "calib", active: true, seq: frame, t: Date.now() });
      setTimeout(() => client.send({ type: "calib", active: false, seq: frame, t: Date.now() }), 1200);
    }
    client.send({ type: "ack", name: message.name ?? "command" });
  }
  // 其它类型忽略（协议 §3）
}

function handleUpgrade(req, socket) {
  const pathname = new URL(req.url ?? "/", "http://localhost").pathname;
  if (pathname !== "/ws") {
    socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  const key = req.headers["sec-websocket-key"];
  const version = req.headers["sec-websocket-version"];
  if (!key || String(version) !== "13") {
    socket.write("HTTP/1.1 426 Upgrade Required\r\nSec-WebSocket-Version: 13\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  const accept = crypto.createHash("sha1").update(String(key) + WS_GUID).digest("base64");
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\n" +
      "Connection: Upgrade\r\n" +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  );

  const client = {
    socket,
    buffer: Buffer.alloc(0),
    closed: false,
    send(object) {
      if (client.closed || socket.destroyed) return;
      const payload = Buffer.from(typeof object === "string" ? object : JSON.stringify(object), "utf8");
      try {
        socket.write(encodeFrame(0x1, payload));
      } catch {
        client.close();
      }
    },
    close() {
      if (client.closed) return;
      client.closed = true;
      clients.delete(client);
      try {
        socket.write(encodeFrame(0x8, Buffer.alloc(0)));
      } catch {
        /* ignore */
      }
      socket.destroy();
    },
  };

  clients.add(client);
  client.send(helloMessage());
  if (!sending) startSending();
  log(`客户端接入，当前 ${clients.size} 个`);

  socket.on("data", (chunk) => {
    client.buffer = Buffer.concat([client.buffer, chunk]);
    for (;;) {
      const frameData = decodeFrame(client.buffer);
      if (!frameData) break;
      client.buffer = client.buffer.subarray(frameData.consumed);
      if (frameData.opcode === 0x8) {
        client.close();
        return;
      }
      if (frameData.opcode === 0x9) {
        if (!client.closed) socket.write(encodeFrame(0xa, frameData.payload));
        continue;
      }
      if (frameData.opcode === 0x1) handleText(client, frameData.payload.toString("utf8"));
    }
  });
  socket.on("close", () => {
    client.close();
  });
  socket.on("error", () => {
    client.close();
  });
}

// ------------------------------------------------------------- 推流循环

function log(...args) {
  if (!options.quiet) console.log("[mock]", ...args);
}

function tick() {
  if (options.frames > 0 && frame >= options.frames) {
    stopSending();
    log(`已发送 ${options.frames} 帧，停止推流（服务仍在监听，Ctrl+C 退出）`);
    return;
  }
  // `t` 用「计划采集时间」而不是发送时刻：真实手机也是采集完就记时间，
  // 传输/调度抖动只影响到达时间。--jitter 时两者会拉开，正好用来验证 PC 端时间轴。
  const productionAt = startedAt + Math.round((frame * 1000) / options.hz);
  const track = buildTrack(frame, productionAt);
  const text = JSON.stringify(track);
  for (const client of clients) client.send(text);
  frame += 1;
  sentSinceSample += 1;
}

function scheduleNext() {
  if (!sending) return;
  const interval = 1000 / options.hz;
  // 抖动只往后拖（传输只会加延迟），按绝对计划时间排，避免误差累积
  const jitter = options.jitter > 0 ? Math.random() * options.jitter : 0;
  const deadline = startedAt + (frame * 1000) / options.hz + jitter;
  const wait = Math.max(1, Math.round(deadline - Date.now()));
  timer = setTimeout(() => {
    if (!sending) return;
    tick();
    scheduleNext();
  }, wait);
}

function startSending() {
  if (sending) return;
  sending = true;
  startedAt = Date.now();
  scheduleNext();
}

function stopSending() {
  if (!sending) return;
  sending = false;
  if (timer) clearTimeout(timer);
  timer = null;
}

setInterval(() => {
  const now = Date.now();
  const elapsed = (now - sampleAt) / 1000;
  if (elapsed >= 1) {
    actualFps = Math.round((sentSinceSample / elapsed) * 10) / 10;
    sentSinceSample = 0;
    sampleAt = now;
    if (!options.quiet && clients.size) {
      log(`clients=${clients.size} seq=${frame} fps=${actualFps} scenario=${options.scenario}`);
    }
  }
}, 1000).unref();

// ------------------------------------------------------------- HTTP

const infoPage = () => `<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<title>VFC Mock Tracker</title>
<style>body{font:14px/1.6 "Segoe UI",sans-serif;background:#0f1420;color:#e6edf7;padding:32px;max-width:760px;margin:auto}
code{background:#1b2333;padding:2px 6px;border-radius:5px}a{color:#5ac8fa}</style>
<h1>VFC Mock Tracker</h1>
<p>模拟手机端在 <code>ws://127.0.0.1:${options.port}/ws</code> 推送协议 v1 的 <code>track</code> 数据。</p>
<ul>
  <li>scenario: <code>${options.scenario}</code>（<a href="/__control?mode=all">all</a> |
      <a href="/__control?mode=neutral">neutral</a> | <a href="/__control?mode=blink">blink</a> |
      <a href="/__control?mode=sway">sway</a> | <a href="/__control?mode=closed">closed</a> |
      <a href="/__control?mode=open">open</a>）</li>
  <li>hz: <code>${options.hz}</code>，抖动: <code>${options.jitter} ms</code>，已发帧：<code>${frame}</code>，客户端：<code>${clients.size}</code></li>
  <li><a href="/status">/status</a></li>
</ul>
<p>渲染页：<a href="http://127.0.0.1:17890/?ws=ws://127.0.0.1:${options.port}/ws&model=ATRI">
http://127.0.0.1:17890/?ws=ws://127.0.0.1:${options.port}/ws&amp;model=ATRI</a></p>
</html>`;

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://localhost");
  if (url.pathname === "/ws") {
    res.writeHead(400, { "Content-Type": "text/plain; charset=utf-8" });
    res.end("请用 WebSocket 连接 /ws");
    return;
  }
  if (url.pathname === "/status") {
    res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
    res.end(
      JSON.stringify({
        running: true,
        tracking: sending,
        clients: clients.size,
        fps: actualFps,
        device: "Mock Phone (VFC)",
        protocol: PROTOCOL,
        version: VERSION,
        seq: frame,
        scenario: options.scenario,
        hz: options.hz,
        jitter: options.jitter,
      }),
    );
    return;
  }
  if (url.pathname === "/__control") {
    const mode = url.searchParams.get("mode");
    if (mode && SCENARIOS[mode]) {
      options.scenario = mode;
      log(`scenario -> ${mode}`);
    }
    const pause = url.searchParams.get("pause");
    if (pause === "1") stopSending();
    else if (pause === "0") startSending();
    res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ ok: true, scenario: options.scenario, sending }));
    return;
  }
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
  res.end(infoPage());
});

server.on("upgrade", (req, socket) => handleUpgrade(req, socket));

server.on("error", (error) => {
  if (error?.code === "EADDRINUSE") {
    console.error(`[mock] 端口 ${options.port} 已被占用：可能已有一个 mock 在跑，或手机端占用了 8765。`);
  } else {
    console.error("[mock] 服务错误：", error);
  }
  process.exit(1);
});

parseArgs(process.argv.slice(2));

server.listen(options.port, options.host, () => {
  console.log(`[mock] 协议 ${PROTOCOL} v${VERSION} · ${options.hz}Hz · scenario=${options.scenario}${options.jitter ? ` · 发送抖动 0~${options.jitter}ms` : ""}`);
  console.log(`[mock] WS   ws://127.0.0.1:${options.port}/ws`);
  console.log(`[mock] 页面 http://127.0.0.1:${options.port}/`);
  console.log(`[mock] 渲染 http://127.0.0.1:17890/?ws=ws://127.0.0.1:${options.port}/ws&model=ATRI`);
  if (options.frames > 0) console.log(`[mock] 只发 ${options.frames} 帧`);
  startSending();
});

const shutdown = () => {
  console.log(`\n[mock] 退出，共发送 ${frame} 帧`);
  for (const client of clients) client.close();
  stopSending();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 500).unref();
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
