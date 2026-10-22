#!/usr/bin/env node
/**
 * VFC Mock Tracker — standalone fake Android phone for protocol v1.
 * ------------------------------------------------------------------
 * Implements docs/tracking-protocol.md v1 on :8765:
 *   GET  /             info page (HTML)
 *   GET  /status       JSON status (running/tracking/clients/fps/...)
 *   WS   /ws           hello -> track @ targetFps
 *   ping -> pong, command{recalibrate} -> ack
 *
 * Deterministic test hooks (verifier-only, NOT part of the phone contract):
 *   GET  /__control?mode=neutral|blink|yaw|jaw|sway|cycle
 *   POST /__control  {"mode":"yaw"}
 *     static modes hold one fixed frame at 60 Hz so screenshots are stable;
 *     "cycle" resumes the 4 s auto scenario below.
 *
 * Auto scenario (240 frames @60 fps = 4 s):
 *   f 0..44    neutral   (bs=0, pose=0)
 *   f 45..74   blink     (bs[9]=bs[10]=1)
 *   f 75..119  yaw       (pose=[0,+45,0])
 *   f 120..149 jaw       (bs[25]=0.8)
 *   f 150..239 sway      (pose/pitch/yaw/roll + jawOpen sine, face:false @170..172)
 *
 * Usage:
 *   node mock-tracker.js                       # 60 Hz forever, cycle scenario
 *   node mock-tracker.js --frames 120          # stop tracks after 120 frames (HTTP stays up)
 *   node mock-tracker.js --once                # exactly 1 neutral frame, then idle
 *   node mock-tracker.js --once --exit-after   # ... and exit the process
 *   node mock-tracker.js --scenario blink --port 8765
 *   node mock-tracker.js --scenario hold-blink --frames 300   # hold blink 300 frames then freeze (for convergence tests)
 */
'use strict';

const http = require('node:http');
const { WebSocketServer, WebSocket } = require('ws');

// ---------------------------------------------------------------- args
function readArgs(argv) {
  const o = {
    port: 8765,
    host: '0.0.0.0',
    fps: 60,
    frames: 0,          // 0 = unlimited
    once: false,
    exitAfter: false,
    mode: 'cycle',
    quiet: false
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case '--port': o.port = Number(next()); break;
      case '--host': o.host = next(); break;
      case '--fps': o.fps = Number(next()); break;
      case '--frames': o.frames = Number(next()); break;
      case '--once': o.once = true; break;
      case '--exit-after': o.exitAfter = true; break;
      case '--scenario': o.mode = next(); break;
      case '--quiet': o.quiet = true; break;
      case '--help': case '-h':
        console.log(require('node:fs').readFileSync(__filename, 'utf8').split('\n').slice(1, 40).map(l => l.replace(/^ \* ?/, '')).join('\n'));
        process.exit(0); break;
      default:
        console.error('[mock-tracker] unknown arg: ' + a);
        process.exit(2);
    }
  }
  if (typeof o.mode === 'string' && o.mode.startsWith('hold-')) o.mode = o.mode.slice(5); // hold-blink === blink held at 60 Hz
  if (typeof o.mode === 'string' && o.mode === 'auto') o.mode = 'cycle';
  if (o.once) o.frames = 1;
  if (!Number.isFinite(o.port) || o.port <= 0 || o.port > 65535) throw new Error('bad --port');
  if (!Number.isFinite(o.fps) || o.fps <= 0 || o.fps > 240) throw new Error('bad --fps');
  if (!Number.isFinite(o.frames) || o.frames < 0) throw new Error('bad --frames');
  return o;
}

const OPTS = readArgs(process.argv.slice(2));
const STATIC_MODES = new Set(['neutral', 'blink', 'yaw', 'jaw', 'sway']);
const CYCLE_FRAMES = 240;

const isStatic = () => STATIC_MODES.has(state.mode);

// ---------------------------------------------------------- synthetic
const state = {
  mode: OPTS.mode === 'auto' ? 'cycle' : OPTS.mode,
  cycleStart: 0,          // monotonic tick when cycle(0) began
  tickIndex: 0,           // total ticks since start
  trackFrames: 0,         // ticks that actually emitted a track frame
  seq: 0,
  stopped: false,
  clients: new Map(),
  connectedAt: Date.now(),
  lastSecond: { t: Date.now(), n: 0, fps: 0 }
};

/** 4 s auto scenario, frame index in [0, CYCLE_FRAMES). */
function cycleFrame(i) {
  const bs = new Array(52).fill(0);
  const pose = [0, 0, 0];
  let face = true;
  if (i < 45) {
    // neutral
  } else if (i < 75) {
    bs[9] = 1; bs[10] = 1;
  } else if (i < 120) {
    pose[0] = 0; pose[1] = 45; pose[2] = 0;
  } else if (i < 150) {
    bs[25] = 0.8;
  } else {
    const u = (i - 150) / 90;                 // 0..1 over 1.5 s
    const w = u * Math.PI * 2;                // one full sway cycle
    pose[0] = 10 * Math.sin(w);               // pitch  ±10
    pose[1] = 20 * Math.sin(w);               // yaw    ±20
    pose[2] = 8 * Math.sin(w + Math.PI / 2);  // roll   ±8
    bs[25] = 0.05 + 0.25 * (0.5 + 0.5 * Math.sin(w));
    if ((i - 150) % 45 < 4) { bs[9] = 1; bs[10] = 1; }
    if (i >= 170 && i <= 172) face = false;   // occasional lost face
  }
  return { bs, pose, face };
}

/** Fixed frames for the verifier-only static modes. */
function staticFrame(mode) {
  const bs = new Array(52).fill(0);
  const pose = [0, 0, 0];
  switch (mode) {
    case 'neutral': break;
    case 'blink': bs[9] = 1; bs[10] = 1; break;
    case 'yaw': pose[1] = 45; break;
    case 'jaw': bs[25] = 0.8; break;
    case 'sway': bs[25] = 0.2; bs[9] = 0.2; bs[10] = 0.2; pose[0] = 5; pose[1] = 15; pose[2] = 4; break;
    default: break;
  }
  return { bs, pose, face: true };
}

function makeTrack() {
  let f;
  if (isStatic()) {
    f = staticFrame(state.mode);
  } else {
    const idx = (state.tickIndex - state.cycleStart) % CYCLE_FRAMES;
    f = cycleFrame(idx);
  }
  return {
    type: 'track',
    seq: state.seq,
    t: Date.now(),
    bs: f.bs.map(v => Math.round(v * 1e4) / 1e4),
    pose: f.pose.map(v => Math.round(v * 1e4) / 1e4),
    face: f.face,
    score: f.face ? 0.98 : 0.05,
    fps: state.lastSecond.fps || OPTS.fps,
    calibrating: false
  };
}

function log(...a) { if (!OPTS.quiet) console.log('[mock-tracker]', ...a); }

// ------------------------------------------------------------- server
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://' + (req.headers.host || 'localhost'));
  const send = (code, type, body) => {
    const buf = Buffer.isBuffer(body) ? body : Buffer.from(String(body));
    res.writeHead(code, { 'Content-Type': type, 'Content-Length': buf.length, 'Cache-Control': 'no-store' });
    res.end(buf);
  };

  if (req.method === 'GET' && url.pathname === '/status') {
    return send(200, 'application/json', JSON.stringify({
      running: !state.stopped,
      tracking: !state.stopped && OPTS.frames === 0 ? true : state.trackFrames > 0,
      clients: state.clients.size,
      fps: state.lastSecond.fps,
      device: 'Mock Pixel 8 Pro',
      protocol: 'vfc-tracker',
      version: 1,
      seq: state.seq,
      // --- verifier extensions (ignore in production) ---
      mode: state.mode,
      trackFrames: state.trackFrames,
      maxFrames: OPTS.frames
    }));
  }

  if (url.pathname === '/__control' && (req.method === 'GET' || req.method === 'POST')) {
    const applyMode = (m) => {
      if (m === 'cycle' || m === 'auto') { state.mode = 'cycle'; state.cycleStart = state.tickIndex; return true; }
      if (STATIC_MODES.has(m)) { state.mode = m; return true; }
      return false;
    };
    if (req.method === 'GET') {
      const m = url.searchParams.get('mode');
      if (!m) return send(400, 'application/json', JSON.stringify({ ok: false, error: 'missing mode' }));
      if (!applyMode(m)) return send(400, 'application/json', JSON.stringify({ ok: false, error: 'bad mode' }));
      log('control -> mode=' + state.mode);
      return send(200, 'application/json', JSON.stringify({ ok: true, mode: state.mode, seq: state.seq }));
    }
    let body = '';
    req.on('data', c => { body += c; if (body.length > 4096) req.destroy(); });
    req.on('end', () => {
      let m = '';
      try { m = JSON.parse(body || '{}').mode || ''; } catch { /* ignore */ }
      if (!applyMode(m)) return send(400, 'application/json', JSON.stringify({ ok: false, error: 'bad mode' }));
      log('control -> mode=' + state.mode);
      return send(200, 'application/json', JSON.stringify({ ok: true, mode: state.mode, seq: state.seq }));
    });
    return;
  }

  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
    const html = '<!doctype html><meta charset="utf-8"><title>VFC Mock Tracker</title>' +
      '<style>body{font:14px/1.5 system-ui;max-width:40em;margin:3em auto;padding:0 1em}' +
      'code{background:#eee;padding:1px 4px;border-radius:3px}</style>' +
      '<h1>VFC Mock Tracker v1</h1>' +
      '<p>WebSocket endpoint: <code>ws://' + (req.headers.host || 'localhost') + '/ws</code></p>' +
      '<pre id="s">loading /status ...</pre>' +
      '<script>setInterval(async()=>{try{const t=await (await fetch("/status")).text();' +
      'document.getElementById("s").textContent=t}catch(e){document.getElementById("s").textContent=String(e)}},1000)</script>';
    return send(200, 'text/html; charset=utf-8', html);
  }

  send(404, 'text/plain', 'not found');
});

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 64 * 1024 });

wss.on('connection', (ws, req) => {
  if (state.clients.size >= 4) {
    ws.send(JSON.stringify({ type: 'bye', reason: 'too many clients (max 4)' }));
    ws.close(1013, 'too many clients');
    return;
  }
  const id = Math.random().toString(36).slice(2, 8);
  state.clients.set(id, ws);
  log('client connected id=' + id + ' from ' + (req.socket.remoteAddress || '?') + ' total=' + state.clients.size);

  ws.send(JSON.stringify({
    type: 'hello',
    protocol: 'vfc-tracker',
    version: 1,
    device: 'Mock Pixel 8 Pro',
    app: 'VirtualFaceCapture/mock-1.0.0',
    decimation: 1,
    targetFps: OPTS.fps,
    t: Date.now()
  }));

  ws.on('message', (data) => {
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }
    if (!msg || typeof msg !== 'object') return;
    if (msg.type === 'ping') {
      ws.send(JSON.stringify({ type: 'pong', t: Date.now(), echo: msg.t }));
    } else if (msg.type === 'command') {
      ws.send(JSON.stringify({ type: 'ack', name: msg.name, t: Date.now() }));
      if (msg.name === 'recalibrate') {
        log('recalibrate requested');
        ws.send(JSON.stringify({ type: 'calib', active: true, seq: state.seq, t: Date.now() }));
        setTimeout(() => { try { ws.send(JSON.stringify({ type: 'calib', active: false, seq: state.seq, t: Date.now() })); } catch {} }, 400);
      }
    } else if (msg.type === 'pong') {
      // ignore
    }
    // unknown type: ignore (forward compat)
  });

  ws.on('close', () => {
    state.clients.delete(id);
    log('client disconnected id=' + id + ' total=' + state.clients.size);
  });
  ws.on('error', () => { /* ignore */ });
});

// ------------------------------------------------------------- ticker
function broadcast(payload) {
  for (const ws of state.clients.values()) {
    if (ws.readyState === WebSocket.OPEN) ws.send(payload);
  }
}

function tick() {
  if (state.stopped) return;
  if (OPTS.frames > 0 && state.trackFrames >= OPTS.frames) return;

  const track = makeTrack();
  state.seq++;
  state.trackFrames++;
  state.tickIndex++;

  // fps over a sliding 1 s window
  const now = Date.now();
  state.lastSecond.n++;
  if (now - state.lastSecond.t >= 1000) {
    state.lastSecond.fps = Math.round((state.lastSecond.n * 1000) / (now - state.lastSecond.t) * 10) / 10;
    state.lastSecond.n = 0;
    state.lastSecond.t = now;
  }

  if (state.clients.size === 0) return; // still counts as a tick (seq advances)
  broadcast(JSON.stringify(track));

  if (OPTS.frames > 0 && state.trackFrames >= OPTS.frames) {
    log('reached --frames ' + OPTS.frames + ' (seq=' + state.seq + '), track stream frozen');
    if (OPTS.exitAfter) setTimeout(() => shutdown(0), 150);
  }
}

// Drift-corrected 60 Hz scheduler (setInterval alone drifts to ~56-58 fps).
let nextTickAt = Date.now();
let timer = null;
function scheduleNext() {
  const step = 1000 / OPTS.fps;
  nextTickAt += step;
  const delay = Math.max(0, nextTickAt - Date.now());
  timer = setTimeout(() => { tick(); if (!state.stopped) scheduleNext(); }, delay);
}
timer = setTimeout(() => { tick(); if (!state.stopped) scheduleNext(); }, 1000 / OPTS.fps);

// ------------------------------------------------------------ lifecycle
function shutdown(code) {
  if (state.stopped) return;
  state.stopped = true;
  clearTimeout(timer);
  log('shutting down');
  for (const ws of state.clients.values()) {
    try { ws.send(JSON.stringify({ type: 'bye', reason: 'service stopped' })); } catch {}
  }
  try { wss.close(); } catch {}
  server.close(() => process.exit(code));
  setTimeout(() => process.exit(code), 1500).unref();
}

process.on('SIGINT', () => shutdown(0));
process.on('SIGTERM', () => shutdown(0));

server.on('error', (err) => {
  console.error('[mock-tracker] server error:', err.code === 'EADDRINUSE'
    ? 'port ' + OPTS.port + ' already in use'
    : err.message);
  process.exit(1);
});

server.listen(OPTS.port, OPTS.host, () => {
  const addr = server.address();
  log('listening on ' + OPTS.host + ':' + addr.port + '  (ws://127.0.0.1:' + addr.port + '/ws)');
  log('fps=' + OPTS.fps + ' mode=' + state.mode + (OPTS.frames ? ' frames=' + OPTS.frames : ' (unlimited)'));
  console.log('[mock-tracker] READY ' + addr.port);
});
