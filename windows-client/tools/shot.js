#!/usr/bin/env node
/**
 * tools/shot.js — 用系统 Chrome/Edge 的无头模式 + 原始 CDP 截图（不依赖 puppeteer）
 *
 *   node tools/shot.js --url "http://127.0.0.1:17890/?ws=ws://127.0.0.1:8765/ws&model=ATRI" --out shots/a.png
 *   node tools/shot.js --url "..." --out shots/closed.png --eval "const bs=new Array(52).fill(0);bs[9]=bs[10]=1;__vfc.applyTrack({bs,pose:[0,0,0]})"
 *
 * 参数：
 *   --url <url>        必填
 *   --out <file>       截图输出（默认 shots/shot-<时间戳>.png）
 *   --size <WxH>       视口尺寸（默认 1280x900）
 *   --wait <ms>        模型就绪后再等多久才截图（默认 800）
 *   --after <ms>       --eval 执行后再等多久（默认 400）
 *   --eval <js>        截图前在页面里执行的 JS（可 await）
 *   --timeout <ms>     等待 window.__vfcDebug.ready 的超时（默认 20000）
 *   --chrome <path>    指定浏览器可执行文件
 *   --allow-console-errors  有 console error 也返回 0
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
];

function parseArgs(argv) {
  const out = {
    url: null,
    out: null,
    width: 1280,
    height: 900,
    wait: 800,
    after: 400,
    eval: null,
    timeout: 20000,
    chrome: null,
    allowConsoleErrors: false,
    keepProfile: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--url") out.url = argv[++i];
    else if (arg === "--out") out.out = argv[++i];
    else if (arg === "--size") {
      const [w, h] = String(argv[++i] ?? "").split("x");
      out.width = Number(w) || out.width;
      out.height = Number(h) || out.height;
    } else if (arg === "--wait") out.wait = Number(argv[++i]) || 0;
    else if (arg === "--after") out.after = Number(argv[++i]) || 0;
    else if (arg === "--eval") out.eval = argv[++i];
    else if (arg === "--eval-file") out.evalFile = argv[++i];
    else if (arg === "--timeout") out.timeout = Number(argv[++i]) || out.timeout;
    else if (arg === "--chrome") out.chrome = argv[++i];
    else if (arg === "--allow-console-errors") out.allowConsoleErrors = true;
    else if (arg === "--keep-profile") out.keepProfile = true;
    else if (arg === "--help" || arg === "-h") {
      console.log(`用法：node tools/shot.js --url <url> --out <file.png> [--size 1280x900] [--eval "<js>"]`);
      process.exit(0);
    }
  }
  if (!out.url) {
    console.error("缺少 --url");
    process.exit(2);
  }
  if (!out.out) {
    out.out = path.join(ROOT, "shots", `shot-${Date.now()}.png`);
  }
  out.out = path.resolve(ROOT, out.out);
  return out;
}

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function findChrome(explicit) {
  const candidates = explicit ? [explicit] : CHROME_CANDIDATES;
  for (const candidate of candidates) {
    if (candidate && fs.existsSync(candidate)) return candidate;
  }
  return null;
}

class CdpConnection {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Map();
    this.ws = null;
  }

  async connect() {
    this.ws = new WebSocket(this.wsUrl);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("CDP WebSocket 连接超时")), 10000);
      this.ws.addEventListener("open", () => {
        clearTimeout(timer);
        resolve();
      });
      this.ws.addEventListener("error", (event) => {
        clearTimeout(timer);
        reject(new Error("CDP WebSocket 连接失败：" + (event?.message ?? "error")));
      });
    });
    this.ws.addEventListener("message", (event) => {
      let message;
      try {
        message = JSON.parse(event.data);
      } catch {
        return;
      }
      if (message.id && this.pending.has(message.id)) {
        const { resolve, reject } = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (message.error) reject(new Error(`${message.error.message} (${message.error.code})`));
        else resolve(message.result);
        return;
      }
      if (message.method) {
        for (const handler of this.listeners.get(message.method) ?? []) {
          try {
            handler(message.params);
          } catch {
            /* ignore */
          }
        }
      }
    });
  }

  on(method, handler) {
    if (!this.listeners.has(method)) this.listeners.set(method, []);
    this.listeners.get(method).push(handler);
  }

  send(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error(`CDP ${method} 超时`));
        }
      }, 30000).unref?.();
    });
  }

  close() {
    try {
      this.ws?.close();
    } catch {
      /* ignore */
    }
  }
}

async function readDevToolsPort(profileDir, timeoutMs) {
  const file = path.join(profileDir, "DevToolsActivePort");
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const text = fs.readFileSync(file, "utf8").trim();
      const [portLine, pathLine] = text.split(/\r?\n/);
      const port = Number(portLine);
      if (port) return { port, browserWsPath: pathLine ?? "" };
    } catch {
      /* 还没生成 */
    }
    await delay(100);
  }
  throw new Error("等待 Chrome DevToolsActivePort 超时（浏览器可能启动失败）");
}

async function fetchJson(url, init) {
  const response = await fetch(url, init);
  if (!response.ok) throw new Error(`HTTP ${response.status} ${url}`);
  return response.json();
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const chromePath = findChrome(options.chrome);
  if (!chromePath) {
    console.error("找不到 Chrome/Edge，请用 --chrome <路径> 指定。");
    process.exit(2);
  }

  const profileDir = fs.mkdtempSync(path.join(os.tmpdir(), "vfc-shot-"));
  const consoleErrors = [];
  const consoleWarnings = [];
  const logs = [];

  const chromeArgs = [
    "--headless=new",
    "--remote-debugging-port=0",
    `--user-data-dir=${profileDir}`,
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-extensions",
    "--disable-background-timer-throttling",
    "--disable-backgrounding-occluded-windows",
    "--disable-renderer-backgrounding",
    "--hide-scrollbars",
    "--mute-audio",
    `--window-size=${options.width},${options.height}`,
    "--force-device-scale-factor=1",
    "--use-gl=angle",
    "--use-angle=swiftshader",
    "--enable-unsafe-swiftshader",
    "--disable-dev-shm-usage",
    "about:blank",
  ];

  const chrome = spawn(chromePath, chromeArgs, { stdio: ["ignore", "pipe", "pipe"] });
  chrome.stderr.on("data", (chunk) => {
    const text = String(chunk);
    if (/ERROR|FATAL/i.test(text) && !/DevTools listening/i.test(text)) logs.push(text.trim());
  });

  let connection = null;
  let exitCode = 0;
  try {
    const { port } = await readDevToolsPort(profileDir, 20000);
    const base = `http://127.0.0.1:${port}`;

    // 新开一个 tab（新版 Chrome 要求 PUT）
    const target = await fetchJson(`${base}/json/new?${encodeURIComponent("about:blank")}`, { method: "PUT" });
    connection = new CdpConnection(target.webSocketDebuggerUrl);
    await connection.connect();

    connection.on("Runtime.consoleAPICalled", (params) => {
      const text = (params.args ?? [])
        .map((arg) => arg.value ?? arg.description ?? arg.unserializableValue ?? "")
        .join(" ");
      if (params.type === "error") consoleErrors.push(text);
      else if (params.type === "warning") consoleWarnings.push(text);
    });
    connection.on("Runtime.exceptionThrown", (params) => {
      const details = params.exceptionDetails ?? {};
      consoleErrors.push(`未捕获异常：${details.exception?.description ?? details.text}`);
    });
    connection.on("Log.entryAdded", (params) => {
      const entry = params.entry ?? {};
      if (entry.level === "error") consoleErrors.push(`[log:${entry.source}] ${entry.text}`);
    });

    await connection.send("Runtime.enable");
    await connection.send("Page.enable");
    await connection.send("Log.enable");
    await connection.send("Emulation.setDeviceMetricsOverride", {
      width: options.width,
      height: options.height,
      deviceScaleFactor: 1,
      mobile: false,
    });

    await connection.send("Page.navigate", { url: options.url });

    // 等 window.__vfcDebug.ready
    const deadline = Date.now() + options.timeout;
    let ready = false;
    let lastState = null;
    while (Date.now() < deadline) {
      await delay(250);
      try {
        const result = await connection.send("Runtime.evaluate", {
          expression:
            "(() => { const d = window.__vfcDebug; return { ready: !!(d && d.ready), model: d && d.model, connected: d && d.connected, frames: d && d.frames, error: (d && d.error) || null, env: !!(window.PIXI && window.Live2DCubismCore) }; })()",
          returnByValue: true,
        });
        lastState = result?.result?.value ?? null;
        if (lastState?.ready) {
          ready = true;
          break;
        }
        if (lastState?.error) break;
      } catch {
        /* 页面还在导航 */
      }
    }

    if (options.wait > 0) await delay(options.wait);

    let evalResult = null;
    if (options.evalFile) {
      options.eval = fs.readFileSync(path.resolve(ROOT, options.evalFile), "utf8");
    }
    if (options.eval) {
      const result = await connection.send("Runtime.evaluate", {
        expression: options.eval,
        awaitPromise: true,
        returnByValue: true,
      });
      if (result.exceptionDetails) {
        console.error("[shot] --eval 抛异常：", result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
        exitCode = 1;
      } else {
        evalResult = result.result?.value ?? null;
      }
      if (options.after > 0) await delay(options.after);
    }

    const shot = await connection.send("Page.captureScreenshot", {
      format: "png",
      fromSurface: true,
      captureBeyondViewport: false,
    });
    fs.mkdirSync(path.dirname(options.out), { recursive: true });
    fs.writeFileSync(options.out, Buffer.from(shot.data, "base64"));

    const debugSummary = await connection
      .send("Runtime.evaluate", {
        expression:
          "(() => { const d = window.__vfcDebug || {}; return { ready: d.ready, model: d.model, wsUrl: d.wsUrl, connected: d.connected, frames: d.frames, seq: d.seq, fps: d.fps, latencyMs: d.latencyMs, params: d.params, angles: d.angles, engine: d.engine, error: d.error }; })()",
        returnByValue: true,
      })
      .then((result) => result?.result?.value ?? null)
      .catch(() => null);

    console.log(`[shot] ${path.relative(ROOT, options.out)}  (${options.width}x${options.height})`);
    console.log("[shot] 页面就绪：" + (ready ? "是" : "否（超时，仍截图）"));
    if (lastState?.model) console.log("[shot] 模型：" + lastState.model);
    if (evalResult !== null) console.log("[shot] eval → " + JSON.stringify(evalResult));
    if (debugSummary) console.log("[shot] __vfcDebug：" + JSON.stringify(debugSummary));
    if (consoleWarnings.length) console.log("[shot] console.warn " + consoleWarnings.length + " 条：" + consoleWarnings.slice(0, 5).join(" | "));
    if (consoleErrors.length) {
      console.error("[shot] console.error " + consoleErrors.length + " 条：");
      for (const error of consoleErrors.slice(0, 10)) console.error("   - " + error);
      if (!options.allowConsoleErrors) exitCode = 1;
    } else {
      console.log("[shot] console.error：0 条");
    }
    if (logs.length) console.log("[shot] 浏览器 stderr：" + logs.slice(0, 5).join(" | "));

    if (!ready && !lastState?.error) exitCode = 1;
  } finally {
    connection?.close();
    try {
      chrome.kill();
    } catch {
      /* ignore */
    }
    if (!options.keepProfile) {
      setTimeout(() => {
        try {
          fs.rmSync(profileDir, { recursive: true, force: true });
        } catch {
          /* ignore */
        }
      }, 500).unref?.();
    }
  }

  process.exit(exitCode);
}

main().catch((error) => {
  console.error("[shot] 失败：", error);
  process.exit(1);
});
