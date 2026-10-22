#!/usr/bin/env node
/**
 * VFC Windows-client end-to-end verifier.
 * -----------------------------------------------------------------
 * Flow:
 *   1. preflight: Chrome, windows-client/server.js, ports
 *   2. start tools/verify/mock-tracker.js (protocol v1, :8765)
 *   3. WS self-check of the mock tracker (hello/track shape/ping/command)
 *   4. start windows-client/server.js (http://127.0.0.1:17890/)
 *   5. puppeteer-core + system Chrome (headless) opens
 *      http://127.0.0.1:17890/?ws=ws://127.0.0.1:8765/ws&model=ATRI
 *   6. capture console errors / page errors / failed local requests
 *   7. drive the mock tracker to neutral / blink / yaw, screenshot each
 *   8. pixel analysis: canvas contributes content, blink and yaw change pixels
 *   9. write build/tmp/verify/result.json + *.png + report log
 *
 * Usage:
 *   node run-verify.mjs
 *   node run-verify.mjs --keep            # keep processes alive for debugging
 *   node run-verify.mjs --wait-minutes 40 # poll for windows-client/server.js
 *   node run-verify.mjs --model ATRI --settle 1200
 *
 * Exit codes: 0 pass, 1 fail, 2 precondition/pending (client files missing).
 */
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import process from 'node:process';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import puppeteer from 'puppeteer-core';
import { PNG } from 'pngjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..', '..');
const VERIFY_DIR = __dirname;
const OUT_DIR = path.join(ROOT, 'build', 'tmp', 'verify');
const MOCK_SCRIPT = path.join(VERIFY_DIR, 'mock-tracker.js');
const CLIENT_SERVER = path.join(ROOT, 'windows-client', 'server.js');
const CLIENT_RENDERER = path.join(ROOT, 'windows-client', 'renderer');
const CLIENT_INDEX = path.join(CLIENT_RENDERER, 'index.html');

const ARGS = readArgs(process.argv.slice(2));
const CONF = {
  httpPort: Number(process.env.VFC_HTTP_PORT || 17890),
  wsPort: Number(process.env.VFC_WS_PORT || 8765),
  host: '127.0.0.1',
  model: ARGS.model || 'ATRI',
  settleMs: Number(ARGS.settle || 1200),
  pageLoadTimeout: 60000,
  debugWaitMs: 25000,
  waitMinutes: Number(ARGS['wait-minutes'] || 0),
  keep: !!ARGS.keep,
  quiet: !!ARGS.quiet,
  canvasContributionMin: 0.005,
  blinkDiffMin: 0.0002,
  yawDiffMin: 0.005,
  eyeOpenMin: 0.7,
  eyeClosedMax: 0.3,
  yawAngleMin: 20
};

const RESULT = {
  tool: 'run-verify.mjs',
  protocolRef: 'docs/tracking-protocol.md (v1)',
  startedAt: new Date().toISOString(),
  env: {},
  pageUrl: '',
  checks: [],
  pixel: {},
  debug: {},
  console: { errors: [], warnings: [], pageErrors: [], failedRequests: [], badResponses: [] },
  screenshots: {},
  files: {},
  processes: {},
  timing: {},
  verdict: 'FAIL',
  pending: false,
  notes: []
};
const LOGS = { mock: [], server: [] };
const CHILDREN = [];
let browser = null;

function readArgs(argv) {
  const o = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const k = a.slice(2);
      const v = argv[i + 1];
      if (v === undefined || v.startsWith('--')) { o[k] = true; }
      else { o[k] = v; i++; }
    }
  }
  return o;
}

function log(...a) {
  const line = a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ');
  console.log(line);
  const arr = LOGS.harness || (LOGS.harness = []);
  arr.push(line);
  if (arr.length > 2000) arr.shift();
}

function check(name, ok, detail) {
  RESULT.checks.push({ name, ok: !!ok, detail: detail === undefined ? null : detail });
  log((ok ? '[PASS] ' : '[FAIL] ') + name + (detail !== undefined && !ok ? '  -> ' + JSON.stringify(detail) : ''));
  return !!ok;
}

function info(name, detail) {
  RESULT.checks.push({ name, ok: true, advisory: true, detail: detail === undefined ? null : detail });
  log('[INFO] ' + name + (detail === undefined ? '' : '  ' + JSON.stringify(detail)));
}

function psRun(script) {
  return spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script], {
    encoding: 'utf8', timeout: 30000, windowsHide: true
  });
}

function portInUse(port, host) {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.once('error', (e) => resolve(e.code === 'EADDRINUSE'));
    s.once('listening', () => s.close(() => resolve(false)));
    s.listen(port, host || '0.0.0.0');
  });
}

async function ensurePortFree(port) {
  const busy = await portInUse(port);
  if (!busy) return true;
  const script = [
    '$ErrorActionPreference = "SilentlyContinue"',
    '$out = @()',
    '$conns = Get-NetTCPConnection -LocalPort ' + port + ' -State Listen',
    'foreach ($c in $conns) {',
    '  $id = $c.OwningProcess',
    '  $proc = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $id)',
    '  $cl = if ($proc) { [string]$proc.CommandLine } else { "" }',
    '  if ($cl -match "mock-tracker|windows-client|server\\\\.js|puppeteer|chrome") {',
    '    Stop-Process -Id $id -Force',
    '    $out += ("KILLED:" + $id + ":" + $cl)',
    '  } else {',
    '    $out += ("BUSY:" + $id + ":" + $cl)',
    '  }',
    '}',
    'Write-Output ($out -join "|")'
  ].join('; ');
  const r = psRun(script);
  const text = ((r.stdout || '') + (r.stderr || '')).trim();
  await sleep(400);
  const still = await portInUse(port);
  if (still) {
    throw new Error('port ' + port + ' is in use by a non-verifier process: ' + text);
  }
  log('[port] freed ' + port + ' (' + text + ')');
  return true;
}

function findChrome() {
  const candidates = [
    process.env.CHROME_PATH,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  ].filter(Boolean);
  for (const p of candidates) if (fs.existsSync(p)) return p;
  return null;
}

function chromeVersion(exe) {
  const r = psRun('(Get-Item ' + JSON.stringify(exe).replace(/"/g, "'") + ').VersionInfo.ProductVersion');
  return (r.stdout || '').trim() || 'unknown';
}

function waitForFiles(timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return (async () => {
    while (Date.now() < deadline) {
      const hasServer = fs.existsSync(CLIENT_SERVER);
      const hasRenderer = fs.existsSync(CLIENT_RENDERER) &&
        fs.readdirSync(CLIENT_RENDERER).some(f => f === 'mapping.js' || f.endsWith('.js'));
      const hasIndex = fs.existsSync(CLIENT_INDEX);
      if (hasServer && hasRenderer && hasIndex) return true;
      log('[wait] windows-client not ready yet (server.js=' + hasServer + ', renderer/*.js=' + hasRenderer + ', renderer/index.html=' + hasIndex + '), retry in 60s ...');
      await sleep(60000);
    }
    return false;
  })();
}

function startProcess(name, cmd, args, opts) {
  const cp = spawn(cmd, args, {
    cwd: opts.cwd,
    env: { ...process.env, ...(opts.env || {}) },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true
  });
  CHILDREN.push({ name, cp });
  RESULT.processes[name] = { pid: cp.pid, cmd: cmd + ' ' + args.join(' ') };
  const sink = LOGS[name] || (LOGS[name] = []);
  const push = (stream, chunk) => {
    const s = chunk.toString();
    sink.push(s);
    if (sink.length > 4000) sink.splice(0, sink.length - 4000);
    if (!CONF.quiet) process.stdout.write('[' + name + ':' + stream + '] ' + s);
  };
  cp.stdout.on('data', (d) => push('out', d));
  cp.stderr.on('data', (d) => push('err', d));
  cp.on('exit', (code, signal) => {
    RESULT.processes[name].exit = { code, signal, at: new Date().toISOString() };
    log('[proc] ' + name + ' exited code=' + code + ' signal=' + signal);
  });
  return cp;
}

async function waitForHttp(url, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let lastErr = '';
  while (Date.now() < deadline) {
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(2500) });
      if (r.ok) return r;
      lastErr = 'HTTP ' + r.status;
    } catch (e) {
      lastErr = e && e.message ? e.message : String(e);
    }
    await sleep(400);
  }
  throw new Error('timeout waiting for ' + url + ' (' + lastErr + ')');
}

async function control(mode) {
  const url = 'http://' + CONF.host + ':' + CONF.wsPort + '/__control?mode=' + encodeURIComponent(mode);
  const r = await fetch(url, { signal: AbortSignal.timeout(3000) });
  if (!r.ok) throw new Error('mock tracker control failed: HTTP ' + r.status);
  return r.json();
}

function trackerSelfCheck() {
  return new Promise((resolve, reject) => {
    const url = 'ws://' + CONF.host + ':' + CONF.wsPort + '/ws';
    const ws = new WebSocket(url);
    const state = { hello: null, tracks: [], pong: null, ack: null, errors: [] };
    let done = false;
    const finish = (err) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try { ws.close(); } catch {}
      if (err) reject(err);
      else resolve(state);
    };
    const timer = setTimeout(() => finish(new Error('tracker WS self-check timed out')), 15000);
    ws.on('error', (e) => finish(new Error('tracker WS error: ' + e.message)));
    ws.on('open', () => {
      setTimeout(() => {
        try { ws.send(JSON.stringify({ type: 'ping', t: Date.now() })); } catch {}
        try { ws.send(JSON.stringify({ type: 'command', name: 'recalibrate' })); } catch {}
      }, 250);
    });
    ws.on('message', (raw) => {
      let m;
      try { m = JSON.parse(raw.toString()); } catch { return; }
      if (m.type === 'hello') state.hello = m;
      else if (m.type === 'track') {
        state.tracks.push(m);
        if (state.tracks.length >= 40) finish(null);
      } else if (m.type === 'pong') state.pong = m;
      else if (m.type === 'ack') state.ack = m;
    });
  });
}

function validateTracker(s) {
  const t = s.tracks;
  const first = t[0] || {};
  const last = t[t.length - 1] || {};
  const dur = (last.t - first.t) / 1000;
  const fps = dur > 0 ? (t.length - 1) / dur : 0;
  const seqOk = t.every((tr, i) => i === 0 || tr.seq === t[i - 1].seq + 1);
  const bsOk = t.every((tr) => Array.isArray(tr.bs) && tr.bs.length === 52 && tr.bs.every((v) => typeof v === 'number' && v >= -0.001 && v <= 1.001));
  const poseOk = t.every((tr) => Array.isArray(tr.pose) && tr.pose.length === 3 && tr.pose.every((v) => typeof v === 'number' && Number.isFinite(v)));
  const boolOk = t.every((tr) => typeof tr.face === 'boolean' && Number.isInteger(tr.seq) && Number.isInteger(tr.t));
  const detail = { tracks: t.length, fps: Math.round(fps * 10) / 10, seqOk, bsOk, poseOk, boolOk, hello: s.hello, pong: !!s.pong, ack: !!s.ack };
  const helloOk = !!s.hello && s.hello.protocol === 'vfc-tracker' && s.hello.version === 1 && s.hello.targetFps > 0;
  check('mock-tracker: hello protocol=vfc-tracker version=1', helloOk, s.hello);
  check('mock-tracker: 40 track frames, seq monotonic', t.length >= 40 && seqOk, { tracks: t.length, seqOk });
  check('mock-tracker: bs is float[52] in [0,1]', bsOk, { sample: first.bs ? first.bs.length : null });
  check('mock-tracker: pose is float[3]', poseOk, { sample: first.pose });
  check('mock-tracker: face bool + int seq/t', boolOk, {});
  check('mock-tracker: measured fps in [45,75]', fps >= 45 && fps <= 75, { fps: Math.round(fps * 10) / 10 });
  check('mock-tracker: ping -> pong', !!s.pong, s.pong);
  check('mock-tracker: command -> ack', !!s.ack, s.ack);
  return detail;
}

function imageStats(png) {
  const { width, height, data } = png;
  const total = width * height;
  let nonTransparent = 0;
  let content = 0;
  let sum = 0;
  let sum2 = 0;
  const colors = new Set();
  const bg = [data[0], data[1], data[2], data[3]];
  for (let i = 0; i < total; i++) {
    const o = i * 4;
    const r = data[o], g = data[o + 1], b = data[o + 2], a = data[o + 3];
    if (a > 16) nonTransparent++;
    if (Math.abs(r - bg[0]) + Math.abs(g - bg[1]) + Math.abs(b - bg[2]) + Math.abs(a - bg[3]) > 18) content++;
    const lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
    sum += lum;
    sum2 += lum * lum;
    colors.add(((r >> 4) << 12) | ((g >> 4) << 8) | ((b >> 4) << 4) | (a >> 4));
  }
  const mean = sum / total;
  const variance = Math.max(0, sum2 / total - mean * mean);
  return {
    width, height,
    nonTransparentRatio: round6(nonTransparent / total),
    contentRatio: round6(content / total),
    meanLuminance: round3(mean),
    stdLuminance: round3(Math.sqrt(variance)),
    uniqueColors: colors.size,
    cornerPixel: bg
  };
}

function diffStats(a, b) {
  if (a.width !== b.width || a.height !== b.height) throw new Error('size mismatch in diffStats');
  const total = a.width * a.height;
  let changed = 0;
  let strong = 0;
  let sum = 0;
  for (let i = 0; i < total; i++) {
    const o = i * 4;
    const d = Math.abs(a.data[o] - b.data[o]) + Math.abs(a.data[o + 1] - b.data[o + 1]) +
      Math.abs(a.data[o + 2] - b.data[o + 2]) + Math.abs(a.data[o + 3] - b.data[o + 3]);
    if (d > 24) { changed++; sum += d; }
    if (d > 96) strong++;
  }
  return {
    changedPixels: changed,
    changedRatio: round6(changed / total),
    strongPixels: strong,
    strongRatio: round6(strong / total),
    meanChangedDelta: changed ? round2(sum / changed) : 0
  };
}

function round3(v) { return Math.round(v * 1000) / 1000; }
function round2(v) { return Math.round(v * 100) / 100; }
function round6(v) { return Math.round(v * 1e6) / 1e6; }

function readPng(buf) { return PNG.sync.read(buf); }

async function readDebug(page) {
  try {
    return await page.evaluate(() => {
      const d = window.__vfcDebug;
      if (!d || typeof d !== 'object') return null;
      const out = {
        version: d.version === undefined ? null : d.version,
        model: d.model === undefined ? null : d.model,
        wsUrl: d.wsUrl === undefined ? null : d.wsUrl,
        connected: d.connected === undefined ? null : d.connected,
        frames: typeof d.frames === 'number' ? d.frames : null,
        ready: d.ready === undefined ? null : d.ready,
        seq: typeof d.seq === 'number' ? d.seq : null,
        fps: typeof d.fps === 'number' ? d.fps : null,
        error: d.error ? String(d.error) : null
      };
      if (d.angles && typeof d.angles === 'object') out.angles = { x: d.angles.x, y: d.angles.y, z: d.angles.z };
      if (d.normalized && typeof d.normalized === 'object') out.normalized = { pitch: d.normalized.pitch, yaw: d.normalized.yaw, roll: d.normalized.roll };
      if (d.raw && typeof d.raw === 'object') out.raw = { pitch: d.raw.pitch, yaw: d.raw.yaw, roll: d.raw.roll };
      if (Array.isArray(d.missingParams)) out.missingParams = d.missingParams.slice(0, 20);
      const p = d.params || d.lastParams || d.parameters || d.live2dParams;
      if (p && typeof p === 'object') {
        out.params = {};
        if (Array.isArray(p)) {
          for (const it of p) {
            if (it && it.id !== undefined && typeof it.value === 'number') out.params[String(it.id)] = it.value;
          }
        } else {
          for (const k of Object.keys(p)) {
            const v = p[k];
            if (typeof v === 'number') out.params[k] = v;
            else if (v && typeof v === 'object' && typeof v.value === 'number') out.params[k] = v.value;
          }
        }
      }
      const t = d.lastTrack;
      if (t && typeof t === 'object') {
        out.track = {
          seq: typeof t.seq === 'number' ? t.seq : null,
          yaw: Array.isArray(t.pose) ? t.pose[1] : null,
          pitch: Array.isArray(t.pose) ? t.pose[0] : null,
          bs9: Array.isArray(t.bs) ? t.bs[9] : null,
          bs25: Array.isArray(t.bs) ? t.bs[25] : null,
          face: t.face === undefined ? null : t.face
        };
      }
      return out;
    });
  } catch (e) {
    return { error: 'evaluate failed: ' + (e && e.message ? e.message : String(e)) };
  }
}

function paramsOf(dbg) {
  return dbg && dbg.params && typeof dbg.params === 'object' ? dbg.params : null;
}

function getParam(params, names) {
  if (!params) return null;
  for (const n of names) {
    if (typeof params[n] === 'number') return params[n];
  }
  return null;
}

async function screenshotClip(page, clip, file) {
  const buf = await page.screenshot({ type: 'png', clip });
  fs.writeFileSync(file, buf);
  return buf;
}

/**
 * Deterministic CLI check: --scenario hold-blink --frames 30 --exit-after
 * proves the mock tracker emits exactly a bounded, held scenario and exits.
 */
function connectWsWithRetry(url, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const ws = new WebSocket(url);
      const timer = setTimeout(() => {
        try { ws.terminate(); } catch {}
        retry(new Error('connect timeout'));
      }, 700);
      ws.once('open', () => { clearTimeout(timer); resolve(ws); });
      ws.once('error', (e) => { clearTimeout(timer); retry(e); });
    };
    const retry = (err) => {
      if (Date.now() >= deadline) return reject(err);
      setTimeout(attempt, 100);
    };
    attempt();
  });
}

async function trackerDeterminismCheck() {
  const port = CONF.wsPort + 1;
  await ensurePortFree(port);
  const cp = spawn(process.execPath, [
    MOCK_SCRIPT, '--fps', '60', '--frames', '30', '--exit-after',
    '--scenario', 'hold-blink', '--quiet', '--port', String(port)
  ], { cwd: VERIFY_DIR, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const tracks = [];
  const helloRef = { value: null };
  let ws = null;
  let connectError = null;
  try {
    ws = await connectWsWithRetry('ws://' + CONF.host + ':' + port + '/ws', 3000);
  } catch (e) {
    connectError = e && e.message ? e.message : String(e);
  }
  if (ws) {
    ws.on('message', (raw) => {
      let m;
      try { m = JSON.parse(raw.toString()); } catch { return; }
      if (m.type === 'hello') helloRef.value = m;
      else if (m.type === 'track') tracks.push(m);
    });
  }
  const exitPromise = new Promise((resolve) => cp.on('exit', () => resolve(true)));
  await Promise.race([exitPromise, sleep(6000)]);
  await sleep(250);
  try { if (ws) ws.close(); } catch {}
  let exitCode = cp.exitCode;
  if (exitCode === null) {
    const deadline = Date.now() + 3000;
    while (Date.now() < deadline && cp.exitCode === null) await sleep(100);
    exitCode = cp.exitCode;
  }
  const allBlink = tracks.length > 0 && tracks.every((t) => t.bs[9] === 1 && t.bs[10] === 1 && t.pose[0] === 0 && t.pose[1] === 0 && t.pose[2] === 0);
  check('mock-tracker: deterministic hold-blink --frames 30 --exit-after', tracks.length > 0 && allBlink && exitCode === 0, {
    received: tracks.length,
    connectError,
    helloVersion: helloRef.value ? helloRef.value.version : null,
    exitCode,
    sample: tracks[0] ? { bs9: tracks[0].bs[9], bs10: tracks[0].bs[10], pose: tracks[0].pose } : null
  });
  try { if (cp.exitCode === null) spawnSync('taskkill', ['/PID', String(cp.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); } catch {}
  await ensurePortFree(port);
}

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  RESULT.env = {
    node: process.version,
    platform: process.platform,
    osRelease: fs.existsSync('C:\\') ? 'win32' : process.platform,
    cwd: process.cwd(),
    chromePath: null,
    chromeVersion: null,
    puppeteerCore: (() => {
      try { return JSON.parse(fs.readFileSync(path.join(VERIFY_DIR, 'node_modules', 'puppeteer-core', 'package.json'), 'utf8')).version; } catch { return 'unknown'; }
    })()
  };

  // ---------- precondition -----------------------------------------
  log('=== VFC end-to-end verification ===');
  const chrome = findChrome();
  if (!chrome) {
    check('precondition: system Chrome/Edge found', false, { candidates: 'CHROME_PATH / Program Files Chrome / Edge' });
    throw new Error('no Chrome/Edge executable found; set CHROME_PATH');
  }
  RESULT.env.chromePath = chrome;
  RESULT.env.chromeVersion = chromeVersion(chrome);
  check('precondition: system Chrome found (no bundled Chromium)', true, { path: chrome, version: RESULT.env.chromeVersion });

  if (!fs.existsSync(CLIENT_SERVER) || !fs.existsSync(CLIENT_INDEX)) {
    if (CONF.waitMinutes > 0) {
      log('[wait] waiting up to ' + CONF.waitMinutes + ' min for windows-client/server.js + renderer/index.html ...');
      await waitForFiles(CONF.waitMinutes * 60000);
    }
  }
  if (!fs.existsSync(CLIENT_SERVER)) {
    RESULT.pending = true;
    check('precondition: windows-client/server.js exists', false, { path: CLIENT_SERVER, hint: 'run again after windows-client lands' });
    RESULT.verdict = 'PENDING';
    throw new Error('PENDING: windows-client/server.js not found: ' + CLIENT_SERVER);
  }
  check('precondition: windows-client/server.js exists', true, { path: CLIENT_SERVER });
  if (!fs.existsSync(CLIENT_INDEX)) {
    RESULT.pending = true;
    check('precondition: windows-client/renderer/index.html exists', false, { path: CLIENT_INDEX });
    RESULT.verdict = 'PENDING';
    throw new Error('PENDING: renderer/index.html not found yet: ' + CLIENT_INDEX);
  }
  check('precondition: windows-client/renderer/index.html exists', true, { path: CLIENT_INDEX });
  const rendererFiles = fs.existsSync(CLIENT_RENDERER) ? fs.readdirSync(CLIENT_RENDERER) : [];
  check('precondition: windows-client/renderer/*.js exists', rendererFiles.some(f => f.endsWith('.js')), { rendererFiles });
  RESULT.files.mappingJs = fs.existsSync(path.join(CLIENT_RENDERER, 'mapping.js'));
  const vendorBundle = path.join(CLIENT_RENDERER, 'vendor', 'pixi-live2d-display.js');
  if (fs.existsSync(vendorBundle)) {
    const vendorText = fs.readFileSync(vendorBundle, 'utf8');
    const hasCubism2Throw = vendorText.includes('Could not find Cubism 2 runtime');
    RESULT.env.vendorBundle = { bytes: Buffer.byteLength(vendorText), hasCubism2Throw };
    check('precondition: vendor/pixi-live2d-display.js is Cubism4-only build (no Cubism2 runtime throw)',
      !hasCubism2Throw, { bytes: Buffer.byteLength(vendorText), hasCubism2Throw });
  } else {
    check('precondition: vendor/pixi-live2d-display.js exists', false, { path: vendorBundle });
  }

  // ---------- free ports -------------------------------------------
  await ensurePortFree(CONF.wsPort);
  await ensurePortFree(CONF.httpPort);

  // ---------- 1) mock tracker -------------------------------------
  log('--- starting mock tracker ---');
  const mock = startProcess('mock', process.execPath, [
    MOCK_SCRIPT, '--fps', '60', '--quiet', '--port', String(CONF.wsPort)
  ], { cwd: VERIFY_DIR });
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (LOGS.mock.join('').includes('READY')) break;
    await sleep(150);
  }
  const mockReady = LOGS.mock.join('').includes('READY');
  check('mock-tracker: process starts and listens on :' + CONF.wsPort, mockReady, { pid: mock.pid });
  if (!mockReady) throw new Error('mock tracker did not become ready');
  const statusRes = await waitForHttp('http://' + CONF.host + ':' + CONF.wsPort + '/status', 5000);
  const status = await statusRes.json();
  check('mock-tracker: GET /status protocol/version/flags', status.protocol === 'vfc-tracker' && status.version === 1 && status.running === true, status);

  log('--- tracker WS self-check ---');
  const self = await trackerSelfCheck();
  RESULT.tracker = validateTracker(self);
  RESULT.tracker.status = status;
  await trackerDeterminismCheck();

  // ---------- 2) windows client server ----------------------------
  log('--- starting windows-client server ---');
  const server = startProcess('server', process.execPath, [CLIENT_SERVER, '--host', CONF.host, '--port', String(CONF.httpPort)], {
    cwd: ROOT,
    env: { PORT: String(CONF.httpPort), VFC_PORT: String(CONF.httpPort), VFC_TRACKER_PORT: String(CONF.wsPort) }
  });
  RESULT.processes.server.baseUrl = 'http://' + CONF.host + ':' + CONF.httpPort + '/';
  let serverOk = false;
  try {
    const r = await waitForHttp(RESULT.processes.server.baseUrl, 25000);
    serverOk = true;
    const body = await r.text();
    check('windows-client: static server responds 200 at :' + CONF.httpPort, true, { bytes: body.length, pid: server.pid, contentType: r.headers.get('content-type') });
    RESULT.files.serverIndex = true;
  } catch (e) {
    check('windows-client: static server responds 200 at :' + CONF.httpPort, false, { error: String(e && e.message || e), log: LOGS.server.slice(-30) });
    throw e;
  }

  // ---------- 3) browser ------------------------------------------
  const url = 'http://' + CONF.host + ':' + CONF.httpPort + '/?ws=' + encodeURIComponent('ws://' + CONF.host + ':' + CONF.wsPort + '/ws') + '&model=' + encodeURIComponent(CONF.model);
  RESULT.pageUrl = url;
  log('--- launching headless Chrome: ' + url + ' ---');
  const launchArgs = [
    '--no-sandbox',
    '--disable-setuid-sandbox',
    '--disable-dev-shm-usage',
    '--enable-unsafe-swiftshader',
    '--use-angle=swiftshader',
    '--ignore-gpu-blocklist',
    '--enable-webgl',
    '--hide-scrollbars',
    '--mute-audio',
    '--window-size=1280,800',
    '--disable-features=Translate,MediaRouter'
  ];
  browser = await puppeteer.launch({
    executablePath: chrome,
    headless: true,
    args: launchArgs,
    defaultViewport: { width: 1280, height: 800, deviceScaleFactor: 1 },
    protocolTimeout: 120000
  });
  RESULT.env.browserVersion = await browser.version();

  const page = await browser.newPage();
  const consoleErrors = RESULT.console.errors;
  const consoleWarnings = RESULT.console.warnings;
  const pageErrors = RESULT.console.pageErrors;
  const failedRequests = RESULT.console.failedRequests;
  const badResponses = RESULT.console.badResponses;
  const isFavicon = (u) => /favicon\.ico/i.test(u);

  function attachPageHandlers(p) {
    p.on('console', (msg) => {
      const text = msg.text();
      const type = msg.type();
      if (type === 'error' && !isFavicon(text)) consoleErrors.push({ text, location: msg.location() });
      else if (type === 'warning') consoleWarnings.push({ text, location: msg.location() });
    });
    p.on('pageerror', (err) => pageErrors.push({ message: err && err.message ? err.message : String(err), stack: err && err.stack ? String(err.stack).slice(0, 4000) : null }));
    p.on('requestfailed', (req) => {
      const u = req.url();
      if (isFavicon(u)) return;
      failedRequests.push({ url: u, error: req.failure() ? req.failure().errorText : null, resourceType: req.resourceType() });
    });
    p.on('response', (res) => {
      const u = res.url();
      if (u.startsWith('http://' + CONF.host + ':') && res.status() >= 400 && !isFavicon(u)) {
        badResponses.push({ url: u, status: res.status(), resourceType: res.request().resourceType() });
      }
    });
  }
  attachPageHandlers(page);

  const t0 = Date.now();
  const resp = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: CONF.pageLoadTimeout });
  check('page: HTTP ' + (resp ? resp.status() : '?') + ' + DOM loaded', !!resp && resp.status() === 200, { status: resp ? resp.status() : null, ms: Date.now() - t0 });

  await page.waitForSelector('canvas', { timeout: 30000 });
  const canvasHandle = await page.evaluateHandle(() => {
    const cs = Array.from(document.querySelectorAll('canvas'));
    cs.sort((a, b) => (b.clientWidth * b.clientHeight) - (a.clientWidth * a.clientHeight));
    return cs[0] || null;
  });
  const canvasEl = canvasHandle.asElement();
  check('page: canvas element present', !!canvasEl, { canvasCount: await page.evaluate(() => document.querySelectorAll('canvas').length) });
  if (!canvasEl) throw new Error('no canvas element on page');

  const canvasInfo = await canvasEl.evaluate((el) => {
    const gl = (() => {
      try {
        const g = el.getContext('webgl2') || el.getContext('webgl');
        return g ? { ok: true, version: g.getParameter(g.VERSION), renderer: g.getParameter(g.RENDERER) } : { ok: false };
      } catch (e) { return { ok: false, error: String(e) }; }
    })();
    return {
      id: el.id || null,
      className: el.className || null,
      width: el.width,
      height: el.height,
      clientWidth: el.clientWidth,
      clientHeight: el.clientHeight,
      drawingBufferWidth: gl && gl.ok ? el.width : null,
      gl,
      contextType: (() => { try { return el.getContext('webgl2') ? 'webgl2' : (el.getContext('webgl') ? 'webgl1' : 'none'); } catch { return 'unknown'; } })()
    };
  }, canvasEl);
  const bbox = await canvasEl.boundingBox();
  RESULT.canvas = { ...canvasInfo, bbox };
  check('page: canvas has non-zero size', !!(bbox && bbox.width > 10 && bbox.height > 10), bbox);
  check('page: WebGL context alive on model canvas', !!(canvasInfo.gl && canvasInfo.gl.ok), canvasInfo.gl);
  if (canvasInfo.gl && canvasInfo.gl.ok) info('WebGL renderer: ' + canvasInfo.gl.renderer, canvasInfo.gl);

  const webglProbe = await page.evaluate(() => {
    const c = document.createElement('canvas');
    try {
      const gl = c.getContext('webgl2') || c.getContext('webgl');
      return gl ? { ok: true, version: gl.getParameter(gl.VERSION) } : { ok: false, reason: 'getContext null' };
    } catch (e) { return { ok: false, reason: String(e) }; }
  });
  check('page: WebGL available in headless Chrome', webglProbe.ok, webglProbe);

  const toData = await canvasEl.evaluate((el) => {
    try {
      const s = el.toDataURL('image/png');
      return { length: s ? s.length : 0, prefix: s ? s.slice(0, 30) : '' };
    } catch (e) { return { error: String(e) }; }
  });
  RESULT.canvas.toDataURL = toData;
  if (toData && toData.length > 1000) info('canvas.toDataURL non-empty', { length: toData.length });
  else info('canvas.toDataURL empty/short (preserveDrawingBuffer may be false; page.screenshot still captures compositor)', toData);

  // wait until renderer consumes tracks (__vfcDebug), else a fixed grace period
  let dbg0 = null;
  const debugDeadline = Date.now() + CONF.debugWaitMs;
  while (Date.now() < debugDeadline) {
    dbg0 = await readDebug(page);
    if (dbg0 && typeof dbg0.frames === 'number' && dbg0.frames > 10) break;
    await sleep(300);
  }
  RESULT.debug.hasDebugHook = !!dbg0;
  RESULT.debug.initial = dbg0;
  if (dbg0) {
    check('renderer: window.__vfcDebug exposed', true, { version: dbg0.version, model: dbg0.model, frames: dbg0.frames });
    check('renderer: __vfcDebug.frames advancing', typeof dbg0.frames === 'number' && dbg0.frames > 0, { frames: dbg0.frames });
    if (dbg0.error) check('renderer: __vfcDebug.error is null', false, dbg0.error);
  } else {
    info('renderer: window.__vfcDebug not exposed (optional); relying on pixels + console', {});
    await sleep(3000);
  }

  // wait for model ready (not just WS frames)
  if (dbg0) {
    let readyDbg = dbg0;
    const readyDeadline = Date.now() + 30000;
    while (Date.now() < readyDeadline) {
      readyDbg = await readDebug(page);
      if (readyDbg && (readyDbg.ready === true || readyDbg.error)) break;
      await sleep(300);
    }
    check('renderer: model ready (__vfcDebug.ready === true)', !!(readyDbg && readyDbg.ready === true), {
      ready: readyDbg ? readyDbg.ready : null,
      model: readyDbg ? readyDbg.model : null,
      error: readyDbg ? readyDbg.error : null
    });
    if (readyDbg && readyDbg.error) check('renderer: __vfcDebug.error is null', false, readyDbg.error);
  }

  // ---------- 4) three moments + screenshots ----------------------
  const clip = {
    x: Math.max(0, Math.floor(bbox.x)),
    y: Math.max(0, Math.floor(bbox.y)),
    width: Math.min(1280 - Math.max(0, Math.floor(bbox.x)), Math.floor(bbox.width)),
    height: Math.min(800 - Math.max(0, Math.floor(bbox.y)), Math.floor(bbox.height))
  };
  RESULT.canvas.clip = clip;
  const modes = ['neutral', 'blink', 'yaw'];
  const shots = {};
  const debugSnaps = {};
  for (const mode of modes) {
    log('--- moment: ' + mode + ' (settle ' + CONF.settleMs + ' ms) ---');
    await control(mode);
    await sleep(CONF.settleMs);
    const dbg = await readDebug(page);
    const vfcState = await page.evaluate(() => {
      try {
        const s = window.__vfc && window.__vfc.state ? window.__vfc.state() : null;
        return s == null ? null : JSON.parse(JSON.stringify(s));
      } catch (e) { return { error: String(e) }; }
    });
    const pxStats = await page.evaluate(() => (window.__vfc && typeof window.__vfc.pixelStats === 'function' ? window.__vfc.pixelStats(192) : null));
    if (!RESULT.debug.pixelStats) RESULT.debug.pixelStats = {};
    RESULT.debug.pixelStats[mode] = pxStats;
    const viewport = await page.screenshot({ type: 'png' });
    const clipBuf = await screenshotClip(page, clip, path.join(OUT_DIR, mode + '.canvas.png'));
    fs.writeFileSync(path.join(OUT_DIR, mode + '.png'), viewport);
    shots[mode] = { viewport: path.join(OUT_DIR, mode + '.png'), canvas: path.join(OUT_DIR, mode + '.canvas.png') };
    RESULT.screenshots[mode] = {
      viewport: 'build/tmp/verify/' + mode + '.png',
      canvas: 'build/tmp/verify/' + mode + '.canvas.png',
      bytes: { viewport: viewport.length, canvas: clipBuf.length }
    };
    debugSnaps[mode] = dbg;
    if (!RESULT.debug.vfcStates) RESULT.debug.vfcStates = {};
    RESULT.debug.vfcStates[mode] = vfcState;
    log('  debug=' + JSON.stringify(dbg ? { frames: dbg.frames, connected: dbg.connected, error: dbg.error, ready: dbg.ready, eye: getParam(paramsOf(dbg), ['ParamEyeLOpen']), angleX: getParam(paramsOf(dbg), ['ParamAngleX']), normalized: dbg.normalized } : null));
  }
  RESULT.debug.snapshots = debugSnaps;

  // canvas contribution: hide the model canvas, screenshot the same clip, diff
  await control('neutral');
  await sleep(CONF.settleMs);
  const visiblePng = readPng(fs.readFileSync(path.join(OUT_DIR, 'neutral.canvas.png')));
  const hiddenFile = path.join(OUT_DIR, 'neutral.canvas.hidden.png');
  await canvasEl.evaluate((el) => { el.style.visibility = 'hidden'; });
  await sleep(250);
  const hiddenBuf = await page.screenshot({ type: 'png', clip });
  fs.writeFileSync(hiddenFile, hiddenBuf);
  await canvasEl.evaluate((el) => { el.style.visibility = ''; });
  RESULT.screenshots.neutralHidden = 'build/tmp/verify/neutral.canvas.hidden.png';
  const hiddenPng = readPng(hiddenBuf);

  const stats = {
    neutral: imageStats(readPng(fs.readFileSync(path.join(OUT_DIR, 'neutral.canvas.png')))),
    blink: imageStats(readPng(fs.readFileSync(path.join(OUT_DIR, 'blink.canvas.png')))),
    yaw: imageStats(readPng(fs.readFileSync(path.join(OUT_DIR, 'yaw.canvas.png')))),
    hiddenBackground: imageStats(hiddenPng),
    canvasContribution: diffStats(visiblePng, hiddenPng),
    diffBlink: diffStats(readPng(fs.readFileSync(path.join(OUT_DIR, 'neutral.canvas.png'))), readPng(fs.readFileSync(path.join(OUT_DIR, 'blink.canvas.png')))),
    diffYaw: diffStats(readPng(fs.readFileSync(path.join(OUT_DIR, 'neutral.canvas.png'))), readPng(fs.readFileSync(path.join(OUT_DIR, 'yaw.canvas.png')))),
    diffBlinkYaw: diffStats(readPng(fs.readFileSync(path.join(OUT_DIR, 'blink.canvas.png'))), readPng(fs.readFileSync(path.join(OUT_DIR, 'yaw.canvas.png'))))
  };
  RESULT.pixel = stats;

  check('pixels: canvas actually renders content (visible vs hidden diff)',
    stats.canvasContribution.changedRatio >= CONF.canvasContributionMin,
    { changedRatio: stats.canvasContribution.changedRatio, min: CONF.canvasContributionMin, strongRatio: stats.canvasContribution.strongRatio });
  check('pixels: neutral frame has image content',
    stats.neutral.contentRatio > 0.005 || stats.neutral.nonTransparentRatio > 0.005,
    { contentRatio: stats.neutral.contentRatio, nonTransparentRatio: stats.neutral.nonTransparentRatio, uniqueColors: stats.neutral.uniqueColors });
  check('pixels: blink changes rendered pixels',
    stats.diffBlink.changedRatio >= CONF.blinkDiffMin,
    { changedRatio: stats.diffBlink.changedRatio, min: CONF.blinkDiffMin, changedPixels: stats.diffBlink.changedPixels });
  check('pixels: big yaw changes rendered pixels',
    stats.diffYaw.changedRatio >= CONF.yawDiffMin,
    { changedRatio: stats.diffYaw.changedRatio, min: CONF.yawDiffMin, changedPixels: stats.diffYaw.changedPixels });
  check('pixels: blink and yaw frames differ from each other',
    stats.diffBlinkYaw.changedRatio >= CONF.blinkDiffMin,
    { changedRatio: stats.diffBlinkYaw.changedRatio });

  // ---------- 5) debug-driven parameter assertions -----------------
  const neutralP = paramsOf(debugSnaps.neutral);
  const blinkP = paramsOf(debugSnaps.blink);
  const yawP = paramsOf(debugSnaps.yaw);
  if (neutralP && blinkP && yawP) {
    RESULT.debug.paramsPresent = true;
    const eyeNeutral = getParam(neutralP, ['ParamEyeLOpen', 'ParamEyeOpen', 'EyeLOpen']);
    const eyeBlink = getParam(blinkP, ['ParamEyeLOpen', 'ParamEyeOpen', 'EyeLOpen']);
    const angleX = getParam(yawP, ['ParamAngleX', 'ParamAngle', 'AngleX']);
    check('debug: neutral eye open (' + eyeNeutral + ' >= ' + CONF.eyeOpenMin + ')',
      eyeNeutral !== null && eyeNeutral >= CONF.eyeOpenMin, { value: eyeNeutral });
    check('debug: blink eye closed (' + eyeBlink + ' <= ' + CONF.eyeClosedMax + ')',
      eyeBlink !== null && eyeBlink <= CONF.eyeClosedMax, { value: eyeBlink });
    check('debug: yaw +45 -> ParamAngleX <= -' + CONF.yawAngleMin,
      angleX !== null && angleX <= -CONF.yawAngleMin, { value: angleX, expectedAbout: -30 });
    const framesSeq = ['neutral', 'blink', 'yaw'].map(m => debugSnaps[m] && typeof debugSnaps[m].frames === 'number' ? debugSnaps[m].frames : null);
    check('debug: frames increase across the three moments',
      framesSeq.every((v, i) => i === 0 || (v !== null && framesSeq[i - 1] !== null && v > framesSeq[i - 1])),
      { framesSeq });
    const trackOk = ['neutral', 'blink', 'yaw'].every(m => {
      const t = debugSnaps[m] && debugSnaps[m].track;
      return t && (m === 'neutral' ? Math.abs(t.yaw || 0) < 1 : m === 'blink' ? t.bs9 === 1 : Math.abs((t.yaw || 0) - 45) < 1);
    });
    check('debug: lastTrack mirrors mock tracker values', trackOk, ['neutral', 'blink', 'yaw'].map(m => debugSnaps[m] && debugSnaps[m].track));
  } else {
    RESULT.debug.paramsPresent = false;
    info('debug: params not exposed via __vfcDebug (pixel evidence only)', {});
  }

  // in-page canvas thumbnail stats (independent of the screenshot path)
  const pxStats = RESULT.debug.pixelStats || {};
  if (pxStats.neutral && pxStats.blink && pxStats.yaw) {
    const hashes = [pxStats.neutral.hash, pxStats.blink.hash, pxStats.yaw.hash];
    check('in-page pixelStats: neutral/blink/yaw canvas hashes all differ', new Set(hashes).size === 3, { hashes });
    check('in-page pixelStats: model pixels present in all three moments',
      [pxStats.neutral, pxStats.blink, pxStats.yaw].every((s) => s.nonTransparentRatio > 0.001),
      { ratios: [pxStats.neutral.nonTransparentRatio, pxStats.blink.nonTransparentRatio, pxStats.yaw.nonTransparentRatio] });
  } else {
    info('in-page pixelStats not available (advisory)', {});
  }
  const missingParams = RESULT.debug.initial && RESULT.debug.initial.missingParams;
  if (Array.isArray(missingParams)) {
    check('debug: all 10 mapped params exist on model (missingParams empty)', missingParams.length === 0, { missingParams });
  }

  // ---------- 5b) deterministic injection through window.__vfc (real render path) ----------
  const vfcApi = await page.evaluate(() => !!(window.__vfc && typeof window.__vfc.applyTrack === 'function' && typeof window.__vfc.freeze === 'function'));
  RESULT.realPath = { injectionAvailable: vfcApi, checkpoints: {} };
  if (vfcApi) {
    const zeroBs = new Array(52).fill(0);
    const bsOf = (over) => {
      const a = new Array(52).fill(0);
      for (const k of Object.keys(over)) a[Number(k)] = over[k];
      return a;
    };
    const inject = async (name, bs, pose, n) => {
      await page.evaluate((bs2, pose2, n2) => {
        for (let i = 0; i < n2; i++) {
          window.__vfc.applyTrack({ bs: bs2, pose: pose2, face: true, seq: i, t: Date.now() });
        }
      }, bs, pose, n);
      await sleep(150);
      const d = await readDebug(page);
      const vfcState = await page.evaluate(() => {
        try {
          const s = window.__vfc.state ? window.__vfc.state() : null;
          return s == null ? null : JSON.parse(JSON.stringify(s));
        } catch (e) { return { error: String(e) }; }
      });
      RESULT.realPath.checkpoints[name] = { debug: d, vfcState, input: { bs, pose, n } };
      return d;
    };
    const expectParam = (label, dbg, names, want, tol) => {
      const got = getParam(paramsOf(dbg), names);
      return check('realpath: ' + label, got !== null && Number.isFinite(got) && Math.abs(got - want) <= tol, { got, want, tol });
    };

    try {
      await page.evaluate(() => window.__vfc.freeze(true));
      await sleep(250);

      const pNeutral = await inject('eyeNeutral', zeroBs, [0, 0, 0], 2);
      expectParam('eyeL open when bs[9]=0 (=1)', pNeutral, ['ParamEyeLOpen'], 1, 1e-6);

      const pBlink = await inject('eyeBlink', bsOf({ 9: 1, 10: 1 }), [0, 0, 0], 2);
      expectParam('eyeL closed when bs[9]=1 (=0)', pBlink, ['ParamEyeLOpen'], 0, 1e-6);
      expectParam('eyeR closed when bs[10]=1 (=0)', pBlink, ['ParamEyeROpen'], 0, 1e-6);

      const pMouth = await inject('mouthOpen', bsOf({ 25: 0.5 }), [0, 0, 0], 2);
      expectParam('ParamMouthOpenY when bs[25]=0.5 (=0.5)', pMouth, ['ParamMouthOpenY'], 0.5, 1e-6);

      const pForm = await inject('mouthForm', bsOf({ 44: 1, 45: 1, 30: 0.5, 31: 0.5 }), [0, 0, 0], 2);
      expectParam('ParamMouthForm smile-frown (=0.5)', pForm, ['ParamMouthForm'], 0.5, 1e-6);

      const pBall = await inject('eyeBall', bsOf({ 15: 1, 13: 0.2, 14: 0.4, 16: 0.1, 17: 1, 11: 0.2, 18: 0.3, 12: 0.1 }), [0, 0, 0], 2);
      expectParam('ParamEyeBallX formula (=0.55)', pBall, ['ParamEyeBallX'], 0.55, 1e-6);
      expectParam('ParamEyeBallY formula (=0.5)', pBall, ['ParamEyeBallY'], 0.5, 1e-6);

      await inject('pitchWarm40', zeroBs, [40, 0, 0], 220);
      const pPitch20 = await inject('pitch20AfterWarm', zeroBs, [20, 0, 0], 1);
      expectParam('warm pitch=+20 -> ParamAngleY ~ -15.0', pPitch20, ['ParamAngleY'], -15.0, 0.05);
      const pPitch40 = await inject('pitch40AfterWarm', zeroBs, [40, 0, 0], 1);
      expectParam('warm pitch=+40 -> ParamAngleY clamp -30', pPitch40, ['ParamAngleY'], -30, 0.01);

      await inject('yawWarm45', zeroBs, [0, 45, 0], 220);
      const pYaw45 = await inject('yaw45AfterWarm', zeroBs, [0, 45, 0], 1);
      expectParam('warm yaw=+45 -> ParamAngleX = -30', pYaw45, ['ParamAngleX'], -30, 0.01);
      const pYawNeg = await inject('yawNeg90Clamp', zeroBs, [0, -90, 0], 1);
      expectParam('yaw=-90 clamps -> ParamAngleX = +30', pYawNeg, ['ParamAngleX'], 30, 0.01);

      await inject('rollWarm30', zeroBs, [0, 0, 30], 220);
      const pRoll = await inject('roll30AfterWarm', zeroBs, [0, 0, 30], 1);
      expectParam('warm roll=+30 -> ParamAngleZ = 30', pRoll, ['ParamAngleZ'], 30, 0.01);

      if (pRoll && pRoll.normalized) {
        check('realpath: __vfcDebug.normalized exposed (pitch/yaw/roll)', ['pitch', 'yaw', 'roll'].every((k) => typeof pRoll.normalized[k] === 'number'), pRoll.normalized);
      } else {
        info('realpath: __vfcDebug.normalized not exposed (advisory)', {});
      }
    } finally {
      try { await page.evaluate(() => window.__vfc.freeze(false)); } catch {}
      await sleep(400);
    }
    const afterUnfreeze = await readDebug(page);
    RESULT.realPath.afterUnfreeze = afterUnfreeze;
    if (afterUnfreeze && typeof afterUnfreeze.frames === 'number') {
      let lastInjectedFrames = null;
      const cpLast = RESULT.realPath.checkpoints.roll30AfterWarm;
      if (cpLast && cpLast.debug && typeof cpLast.debug.frames === 'number') lastInjectedFrames = cpLast.debug.frames;
      check('realpath: WS stream resumes after freeze(false)', lastInjectedFrames === null || afterUnfreeze.frames > lastInjectedFrames, { before: lastInjectedFrames, after: afterUnfreeze.frames });
    } else {
      info('realpath: frames not exposed after unfreeze (advisory)', {});
    }
  } else {
    info('realpath: window.__vfc.applyTrack/freeze not exposed; injection phase skipped', {});
  }

  // ---------- 5b-2) 本地姿态校准（镜头没正对你时的「一键校准」） ----------
  if (vfcApi) {
    const calib = await page.evaluate(async () => {
      const vfc = window.__vfc;
      const api = {
        align: typeof vfc.align === 'function',
        setPoseOffset: typeof vfc.setPoseOffset === 'function',
        poseOffset: !!vfc.poseOffset,
        ui: !!document.getElementById('btn-align'),
        sliders: ['cal-yaw', 'cal-pitch', 'cal-roll'].filter((id) => !!document.getElementById(id)).length,
      };
      if (!api.align || !api.setPoseOffset || !vfc.applyTrack) return { api, ok: false, reason: 'align API missing' };
      const bs = new Array(52).fill(0);
      const pose = [4, 12, -6]; // 模拟「镜头斜着架」造成的固定头姿偏移
      vfc.freeze(true);
      vfc.setPoseOffset({ pitch: 0, yaw: 0, roll: 0 });
      const alignment = vfc.align(0.6);
      const activeUi = { active: vfc.calibration.active, label: document.getElementById('btn-align').textContent };
      for (let i = 0; i < 40; i++) {
        vfc.applyTrack({ bs, pose, face: true, seq: i, t: Date.now() });
        await new Promise((resolve) => setTimeout(resolve, 20));
      }
      const result = await alignment;
      const offset = vfc.poseOffset;
      const stored = (() => { try { return window.localStorage.getItem('vfc.poseOffset'); } catch { return null; } })();
      // 校准后同一姿势应映射到 0 角度；清零后再喂同一姿势应重新偏出去
      const after = vfc.applyTrack({ bs, pose, face: true, seq: 999, t: Date.now() });
      vfc.setPoseOffset({ pitch: 0, yaw: 0, roll: 0 });
      const cleared = vfc.applyTrack({ bs, pose, face: true, seq: 1000, t: Date.now() });
      const calibrationDebug = JSON.parse(JSON.stringify(window.__vfcDebug.calibration || null));
      // 手动微调：input 即时生效、change 落盘、标签同步
      const yawSlider = document.getElementById('cal-yaw');
      yawSlider.value = '7.5';
      yawSlider.dispatchEvent(new Event('input', { bubbles: true }));
      const liveOffset = vfc.poseOffset;
      yawSlider.dispatchEvent(new Event('change', { bubbles: true }));
      const sliderStored = (() => { try { return JSON.parse(window.localStorage.getItem('vfc.poseOffset') || 'null'); } catch { return null; } })();
      const sliderLabel = document.getElementById('cal-yaw-val').textContent;
      vfc.setPoseOffset({ pitch: 0, yaw: 0, roll: 0 });
      yawSlider.value = '0';
      yawSlider.dispatchEvent(new Event('input', { bubbles: true }));
      yawSlider.dispatchEvent(new Event('change', { bubbles: true }));
      // 手机重做基线（calib active:false）→ 本地偏移应自动清零，避免双重校准
      vfc.setPoseOffset({ pitch: 2, yaw: 5, roll: -1 });
      vfc.client.handleMessage(JSON.stringify({ type: 'calib', active: true }));
      vfc.client.handleMessage(JSON.stringify({ type: 'calib', active: false }));
      const afterPhoneCalib = { offset: vfc.poseOffset, hint: document.getElementById('calib-hint').textContent };
      vfc.freeze(false);
      return {
        api,
        activeUi,
        ok: !!(result && result.ok),
        result,
        offset,
        stored,
        afterParams: after ? after.params : null,
        clearedParams: cleared ? cleared.params : null,
        calibrationDebug,
        slider: { live: liveOffset, stored: sliderStored, label: sliderLabel },
        afterPhoneCalib,
      };
    });
    RESULT.calibration = calib;
    check('calib: 校准 API / UI 就位（按钮 + 3 个滑块）', calib.api.ui && calib.api.sliders === 3 && calib.api.align && calib.api.setPoseOffset, calib.api);
    check('calib: 一键校准成功采样（>=12 帧）', !!(calib.result && calib.result.ok && calib.result.samples >= 12), calib.result);
    check('calib: 采样中按钮变「取消校准」且 calibration.active=true',
      !!calib.activeUi && calib.activeUi.active === true && calib.activeUi.label === '取消校准', calib.activeUi);
    const offsetClose = (a, b) => typeof a === 'number' && Math.abs(a - b) <= 1.5;
    check('calib: offset ≈ [-4,-12,+6]（把固定偏移取反）',
      !!calib.offset && offsetClose(calib.offset.pitch, -4) && offsetClose(calib.offset.yaw, -12) && offsetClose(calib.offset.roll, 6),
      calib.offset);
    check('calib: 校准后同一姿势 → ParamAngleX/Y/Z ≈ 0',
      !!calib.afterParams && ['ParamAngleX', 'ParamAngleY', 'ParamAngleZ'].every((k) => Math.abs(calib.afterParams[k] ?? 99) < 0.01),
      calib.afterParams && { x: calib.afterParams.ParamAngleX, y: calib.afterParams.ParamAngleY, z: calib.afterParams.ParamAngleZ });
    check('calib: 清零后同一姿势重新偏出去（offset 真的作用在映射前）',
      !!calib.clearedParams && Math.abs(calib.clearedParams.ParamAngleX ?? 0) + Math.abs(calib.clearedParams.ParamAngleY ?? 0) + Math.abs(calib.clearedParams.ParamAngleZ ?? 0) > 1,
      calib.clearedParams && { x: calib.clearedParams.ParamAngleX, y: calib.clearedParams.ParamAngleY, z: calib.clearedParams.ParamAngleZ });
    check('calib: offset 持久化到 localStorage',
      !!calib.stored && Math.abs(JSON.parse(calib.stored).yaw - (-12)) <= 1.5,
      calib.stored);
    check('calib: 滑块 input 即时生效 / change 落盘 / 数值标签同步',
      !!calib.slider && Math.abs(calib.slider.live.yaw - 7.5) < 1e-9 &&
        !!calib.slider.stored && Math.abs(calib.slider.stored.yaw - 7.5) < 1e-9 &&
        String(calib.slider.label).includes('+7.5'),
      calib.slider);
    check('calib: __vfcDebug.calibration 暴露状态（stored=false + lastAlign 采样数）',
      !!calib.calibrationDebug && calib.calibrationDebug.stored === false &&
        calib.calibrationDebug.lastAlignSamples >= 12,
      calib.calibrationDebug);
    const pc = calib.afterPhoneCalib;
    check('calib: 手机重做基线后本地偏移自动清零（避免双重校准）',
      !!pc && Math.abs(pc.offset.pitch) < 1e-9 && Math.abs(pc.offset.yaw) < 1e-9 && Math.abs(pc.offset.roll) < 1e-9 &&
        String(pc.hint).includes('已清零'),
      pc);
    await sleep(300);
  } else {
    info('calib: window.__vfc.align 不可用，跳过本地姿态校准检查（advisory）', {});
  }

  // ---------- 5c) extra acceptance: models list / model switch / reconnect / transparency ----------
  // (a) /api/models
  try {
    const modelsRes = await fetch('http://' + CONF.host + ':' + CONF.httpPort + '/api/models', { signal: AbortSignal.timeout(5000) });
    const modelsJson = await modelsRes.json();
    const list = Array.isArray(modelsJson) ? modelsJson : (modelsJson.models || []);
    const names = list.map((m) => m && m.name);
    RESULT.models = { status: modelsRes.status, count: names.length, names };
    check('server: /api/models lists 4 models', modelsRes.ok && names.length >= 4, { count: names.length, names });
    check('server: /api/models includes ATRI + sensei', names.includes('ATRI') && names.includes('sensei'), { names });
  } catch (e) {
    check('server: /api/models reachable', false, { error: String((e && e.message) || e) });
  }

  // (b) model switch ATRI -> sensei via window.__vfc.setModel
  const swInfo = await page.evaluate(async () => {
    if (!window.__vfc || typeof window.__vfc.setModel !== 'function') return { ok: false, reason: 'window.__vfc.setModel missing' };
    const before = window.__vfcDebug ? window.__vfcDebug.model : null;
    try {
      await window.__vfc.setModel('sensei');
    } catch (e) {
      return { ok: false, reason: String(e), before };
    }
    return { ok: true, before, requested: 'sensei' };
  });
  let swDbg = null;
  const swDeadline = Date.now() + 30000;
  while (Date.now() < swDeadline) {
    swDbg = await readDebug(page);
    if (swDbg && swDbg.ready === true && /sensei/i.test(String(swDbg.model || ''))) break;
    await sleep(300);
  }
  RESULT.modelSwitch = { info: swInfo, debug: swDbg };
  check('model switch: window.__vfc.setModel available', !!swInfo.ok, swInfo);
  check('model switch: ATRI -> sensei loads and becomes ready',
    !!(swDbg && swDbg.ready === true && /sensei/i.test(String(swDbg.model || ''))),
    { model: swDbg && swDbg.model, ready: swDbg && swDbg.ready, error: swDbg && swDbg.error });
  await control('neutral');
  await sleep(CONF.settleMs);
  const swVisibleBuf = await page.screenshot({ type: 'png', clip });
  fs.writeFileSync(path.join(OUT_DIR, 'model-sensei.canvas.png'), swVisibleBuf);
  if (canvasEl) await canvasEl.evaluate((el) => { el.style.visibility = 'hidden'; });
  await sleep(200);
  const swHiddenBuf = await page.screenshot({ type: 'png', clip });
  if (canvasEl) await canvasEl.evaluate((el) => { el.style.visibility = ''; });
  fs.writeFileSync(path.join(OUT_DIR, 'model-sensei.canvas.hidden.png'), swHiddenBuf);
  const swDiff = diffStats(readPng(swVisibleBuf), readPng(swHiddenBuf));
  RESULT.modelSwitch.canvasContribution = swDiff;
  RESULT.screenshots.modelSensei = 'build/tmp/verify/model-sensei.canvas.png';
  check('pixels: sensei renders after model switch (no white screen)',
    swDiff.changedRatio >= CONF.canvasContributionMin,
    { changedRatio: swDiff.changedRatio, min: CONF.canvasContributionMin, strongRatio: swDiff.strongRatio });

  // (c) disconnect / auto-reconnect: kill mock tracker, expect connected=false, restart, expect connected=true
  const mockProc = CHILDREN.find((c) => c.name === 'mock');
  if (mockProc && mockProc.cp && mockProc.cp.exitCode === null) {
    spawnSync('taskkill', ['/PID', String(mockProc.cp.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
    log('[reconnect] killed mock tracker pid ' + mockProc.cp.pid);
  }
  let discDbg = null;
  const discDeadline = Date.now() + 8000;
  while (Date.now() < discDeadline) {
    discDbg = await readDebug(page);
    if (discDbg && discDbg.connected === false) break;
    await sleep(200);
  }
  const discState = await page.evaluate(() => {
    try { return window.__vfc && window.__vfc.state ? JSON.parse(JSON.stringify(window.__vfc.state())) : null; } catch (e) { return { error: String(e) }; }
  });
  RESULT.reconnect = { afterKill: { debug: discDbg, statusText: discState && discState.statusText } };
  check('reconnect: client sees mock-tracker stop (__vfcDebug.connected=false)',
    !!(discDbg && discDbg.connected === false),
    { connected: discDbg && discDbg.connected, statusText: discState && discState.statusText });

  await ensurePortFree(CONF.wsPort);
  startProcess('mock2', process.execPath, [MOCK_SCRIPT, '--fps', '60', '--quiet', '--port', String(CONF.wsPort)], { cwd: VERIFY_DIR });
  await waitForHttp('http://' + CONF.host + ':' + CONF.wsPort + '/status', 15000);
  let rcDbg = null;
  const rcDeadline = Date.now() + 15000;
  while (Date.now() < rcDeadline) {
    rcDbg = await readDebug(page);
    if (rcDbg && rcDbg.connected === true) break;
    await sleep(300);
  }
  const framesBefore = rcDbg && rcDbg.frames;
  await sleep(1500);
  const rcDbg2 = await readDebug(page);
  const rcState = await page.evaluate(() => {
    try { return window.__vfc && window.__vfc.state ? JSON.parse(JSON.stringify(window.__vfc.state())) : null; } catch (e) { return { error: String(e) }; }
  });
  RESULT.reconnect.afterRestart = { debug: rcDbg2, statusText: rcState && rcState.statusText };
  check('reconnect: client auto-reconnects after mock restart',
    !!(rcDbg && rcDbg.connected === true),
    { connected: rcDbg && rcDbg.connected, frames: rcDbg2 && rcDbg2.frames, statusText: rcState && rcState.statusText });
  check('reconnect: track frames grow again after reconnect',
    !!(rcDbg2 && typeof rcDbg2.frames === 'number' && typeof framesBefore === 'number' && rcDbg2.frames > framesBefore),
    { before: framesBefore, after: rcDbg2 && rcDbg2.frames });

  // (d) transparent background: new page with ?transparent=1, omitBackground screenshot, alpha corners
  const trUrl = url + '&transparent=1';
  try {
    const trPage = await browser.newPage();
    attachPageHandlers(trPage);
    const trResp = await trPage.goto(trUrl, { waitUntil: 'domcontentloaded', timeout: CONF.pageLoadTimeout });
    await trPage.waitForSelector('canvas', { timeout: 30000 });
    let trDbg = null;
    const trDeadline = Date.now() + 30000;
    while (Date.now() < trDeadline) {
      trDbg = await readDebug(trPage);
      if (trDbg && trDbg.ready === true && trDbg.connected === true) break;
      await sleep(300);
    }
    await trPage.evaluate(() => {
      const ui = document.getElementById('ui');
      if (ui) ui.style.display = 'none';
      const hint = document.getElementById('hidden-hint');
      if (hint) hint.style.display = 'none';
    });
    await sleep(500);
    const trStyle = await trPage.evaluate(() => {
      const c = document.getElementById('live2d');
      const cs = (el) => (el ? getComputedStyle(el).backgroundColor : null);
      let backgroundAlpha = null;
      try { backgroundAlpha = window.__vfc && window.__vfc.stage && window.__vfc.stage.app ? window.__vfc.stage.app.renderer.backgroundAlpha : null; } catch (e) { /* ignore */ }
      return {
        bodyClass: document.body.className,
        htmlClass: document.documentElement.className,
        htmlBackgroundColor: cs(document.documentElement),
        bodyBackgroundColor: cs(document.body),
        stageBackgroundColor: cs(document.getElementById('stage')),
        canvasBackgroundColor: cs(c),
        rendererBackgroundAlpha: backgroundAlpha,
        canvasDisplayWidth: c ? c.clientWidth : null,
        canvasDisplayHeight: c ? c.clientHeight : null
      };
    });
    // authoritative alpha evidence: Cubism canvas is preserveDrawingBuffer:true, so toDataURL keeps alpha
    const dataUrl = await trPage.evaluate(() => {
      try { return document.getElementById('live2d').toDataURL('image/png'); } catch (e) { return null; }
    });
    let canvasPng = null;
    if (dataUrl && dataUrl.startsWith('data:image/png;base64,')) {
      const buf = Buffer.from(dataUrl.split(',')[1], 'base64');
      fs.writeFileSync(path.join(OUT_DIR, 'transparent.canvas.png'), buf);
      canvasPng = readPng(buf);
    }
    // supplementary compositor screenshot (headless Chrome flattens alpha to opaque white); evidence only
    const trPageBuf = await trPage.screenshot({ type: 'png' });
    fs.writeFileSync(path.join(OUT_DIR, 'transparent.page.png'), trPageBuf);

    let corners = null;
    let opaqueRatio = null;
    let visibleAlphaRatio = null;
    if (canvasPng) {
      const trW = canvasPng.width;
      const trH = canvasPng.height;
      const alphaAt = (x, y) => canvasPng.data[(y * trW + x) * 4 + 3];
      corners = {
        topLeft: alphaAt(0, 0),
        topRight: alphaAt(trW - 1, 0),
        bottomLeft: alphaAt(0, trH - 1),
        bottomRight: alphaAt(trW - 1, trH - 1)
      };
      let opaque = 0;
      let visibleAlpha = 0;
      const total = trW * trH;
      for (let i = 0; i < total; i++) {
        const a = canvasPng.data[i * 4 + 3];
        if (a > 250) opaque++;
        if (a > 16) visibleAlpha++;
      }
      opaqueRatio = round6(opaque / total);
      visibleAlphaRatio = round6(visibleAlpha / total);
    }
    const isTransparentCss = (v) => typeof v === 'string' && (v === 'transparent' || /rgba?\(\s*0,\s*0,\s*0,\s*0\s*\)/.test(v));
    RESULT.transparency = {
      pageUrl: trUrl,
      httpStatus: trResp ? trResp.status() : null,
      debug: trDbg,
      style: trStyle,
      corners,
      opaqueRatio,
      visibleAlphaRatio,
      canvasCapture: 'build/tmp/verify/transparent.canvas.png',
      pageScreenshot: 'build/tmp/verify/transparent.page.png',
      note: 'page.screenshot alpha unreliable in headless Chrome (flattened to opaque white); canvas.toDataURL + computed styles are authoritative'
    };
    check('transparent=1: body has .is-transparent class', /is-transparent/.test(trStyle.bodyClass), trStyle);
    check('transparent=1: html background is transparent', isTransparentCss(trStyle.htmlBackgroundColor), { htmlBackgroundColor: trStyle.htmlBackgroundColor });
    check('transparent=1: body background is transparent', isTransparentCss(trStyle.bodyBackgroundColor), { bodyBackgroundColor: trStyle.bodyBackgroundColor });
    check('transparent=1: #stage background is transparent', isTransparentCss(trStyle.stageBackgroundColor), { stageBackgroundColor: trStyle.stageBackgroundColor });
    if (trStyle.rendererBackgroundAlpha !== null) {
      check('transparent=1: PIXI renderer.backgroundAlpha === 0', trStyle.rendererBackgroundAlpha === 0, { backgroundAlpha: trStyle.rendererBackgroundAlpha });
    }
    check('transparent=1: canvas alpha=0 at 4 corners (toDataURL)', !!(corners && Object.values(corners).every((a) => a === 0)), corners);
    check('transparent=1: model drawn opaque over transparent canvas', !!(opaqueRatio !== null && opaqueRatio > 0.001 && opaqueRatio < 0.95), { opaqueRatio, visibleAlphaRatio });
    RESULT.screenshots.transparent = 'build/tmp/verify/transparent.canvas.png';
    await trPage.close();
  } catch (e) {
    check('transparent=1: transparency check executed', false, { error: String((e && e.message) || e) });
  }

  // ---------- 6) console / network health -------------------------
  check('console: no console errors', consoleErrors.length === 0, consoleErrors.slice(0, 10));
  check('console: no uncaught page errors', pageErrors.length === 0, pageErrors.slice(0, 10));
  check('network: no failed local requests', failedRequests.length === 0, failedRequests.slice(0, 10));
  check('network: no local HTTP >= 400', badResponses.length === 0, badResponses.slice(0, 10));

  if (consoleWarnings.length) info('console: ' + consoleWarnings.length + ' warning(s)', consoleWarnings.slice(0, 8));
}

async function cleanup() {
  if (CONF.keep) { log('[cleanup] --keep set, leaving processes alive: ' + JSON.stringify(RESULT.processes)); return; }
  try { if (browser) await browser.close(); } catch {}
  browser = null;
  for (const { name, cp } of CHILDREN) {
    try {
      if (cp && cp.exitCode === null) {
        spawnSync('taskkill', ['/PID', String(cp.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
      }
    } catch {}
  }
  await sleep(400);
  // final safety: free our ports if something of ours survived
  for (const port of [CONF.wsPort, CONF.httpPort]) {
    if (await portInUse(port)) {
      try { await ensurePortFree(port); } catch (e) { log('[cleanup] ' + String(e.message)); }
    }
  }
  const stillBusy = [];
  for (const port of [CONF.wsPort, CONF.httpPort]) if (await portInUse(port)) stillBusy.push(port);
  log('[cleanup] done; busy ports: ' + (stillBusy.length ? stillBusy.join(',') : 'none'));
}

function writeArtifacts() {
  RESULT.finishedAt = new Date().toISOString();
  RESULT.durationMs = Date.parse(RESULT.finishedAt) - Date.parse(RESULT.startedAt);
  RESULT.verdict = RESULT.pending ? 'PENDING' : (RESULT.checks.filter(c => !c.advisory).every(c => c.ok) ? 'PASS' : 'FAIL');
  RESULT.counts = {
    total: RESULT.checks.length,
    failed: RESULT.checks.filter(c => !c.ok).length,
    advisory: RESULT.checks.filter(c => c.advisory).length
  };
  RESULT.logs = { mock: LOGS.mock.slice(-300), server: LOGS.server.slice(-300) };
  // params.json: last applied Live2D params + normalizer state (live stream + injected)
  try {
    const paramsJson = {
      generatedAt: RESULT.finishedAt,
      pageUrl: RESULT.pageUrl,
      protocolRef: 'docs/tracking-protocol.md §8 (v1)',
      fromLiveStream: {},
      fromInjection: RESULT.realPath ? RESULT.realPath.checkpoints : {},
      vfcStates: RESULT.debug.vfcStates || null,
      afterUnfreeze: RESULT.realPath ? RESULT.realPath.afterUnfreeze : null
    };
    for (const mode of ['neutral', 'blink', 'yaw']) {
      const d = RESULT.debug.snapshots ? RESULT.debug.snapshots[mode] : null;
      if (!d) continue;
      paramsJson.fromLiveStream[mode] = {
        seq: d.seq, frames: d.frames, connected: d.connected, ready: d.ready,
        params: d.params || null,
        normalized: d.normalized || null,
        raw: d.raw || (d.track ? { pitch: d.track.pitch, yaw: d.track.yaw } : null),
        lastTrack: d.track || null
      };
    }
    fs.writeFileSync(path.join(OUT_DIR, 'params.json'), JSON.stringify(paramsJson, null, 2));
    RESULT.files.paramsJson = 'build/tmp/verify/params.json';
  } catch (e) {
    console.error('[artifacts] params.json write failed: ' + e.message);
  }
  try {
    fs.mkdirSync(OUT_DIR, { recursive: true });
    fs.writeFileSync(path.join(OUT_DIR, 'result.json'), JSON.stringify(RESULT, null, 2));
    fs.writeFileSync(path.join(OUT_DIR, 'harness.log'), (LOGS.harness || []).join('\n'));
    fs.writeFileSync(path.join(OUT_DIR, 'mock-tracker.log'), LOGS.mock.join(''));
    fs.writeFileSync(path.join(OUT_DIR, 'client-server.log'), LOGS.server.join(''));
    RESULT.files.resultJson = 'build/tmp/verify/result.json';
  } catch (e) {
    console.error('[artifacts] write failed: ' + e.message);
  }
}

let exitCode = 1;
try {
  await main();
  exitCode = RESULT.pending ? 2 : (RESULT.checks.filter(c => !c.advisory).every(c => c.ok) ? 0 : 1);
} catch (e) {
  if (RESULT.pending) {
    exitCode = 2;
  } else {
    check('harness: no unexpected exception', false, { message: e && e.message ? e.message : String(e), stack: e && e.stack ? String(e.stack).slice(0, 3000) : null });
  }
  log('[error] ' + (e && e.stack ? e.stack : e));
} finally {
  await cleanup();
}

// final console health re-check (after cleanup, nothing should have errored)
writeArtifacts();
const failed = RESULT.checks.filter(c => !c.ok);
console.log('');
console.log('========== VERDICT: ' + RESULT.verdict + ' ==========');
console.log('checks: ' + RESULT.checks.length + '  failed: ' + failed.length + '  advisory: ' + RESULT.counts.advisory);
for (const f of failed) console.log('  FAILED: ' + f.name + '  ' + JSON.stringify(f.detail));
console.log('screenshots: ' + Object.keys(RESULT.screenshots).map(k => {
  const v = RESULT.screenshots[k];
  return typeof v === 'string' ? v : JSON.stringify(v);
}).join(', '));
console.log('result: ' + path.join(OUT_DIR, 'result.json'));
process.exit(exitCode);
