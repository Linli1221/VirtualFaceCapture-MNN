#!/usr/bin/env node
/**
 * tools/web-launch.js — npm run web：不依赖 Electron 的兜底路径
 *
 * 起本地静态服务（127.0.0.1:17890），然后用 Edge/Chrome 的 --app 模式开一个无地址栏窗口。
 * 这条路径 verifier 也会用（纯 Chrome/Edge + 本地 HTTP，不需要 Electron）。
 *
 *   npm run web
 *   npm run web -- --model ATRI --ws ws://192.168.1.23:8765/ws --browser chrome --port 17890
 */
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { spawn } from "node:child_process";
import { startServer, DEFAULT_PORT } from "../server.js";

const CANDIDATES = {
  edge: [
    process.env.EDGE_PATH,
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
  ],
  chrome: [
    process.env.CHROME_PATH,
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
  ],
};

function parseArgs(argv) {
  const out = {
    port: DEFAULT_PORT,
    host: "127.0.0.1",
    model: "ATRI",
    ws: null,
    browser: "auto",
    noOpen: false,
    profile: path.join(os.tmpdir(), "vfc-web-profile"),
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--port") out.port = Number(argv[++i]) || DEFAULT_PORT;
    else if (arg === "--host") out.host = argv[++i] || "127.0.0.1";
    else if (arg === "--model") out.model = argv[++i] || out.model;
    else if (arg === "--ws") out.ws = argv[++i] || null;
    else if (arg === "--browser") out.browser = (argv[++i] || "auto").toLowerCase();
    else if (arg === "--no-open") out.noOpen = true;
    else if (arg === "--profile") out.profile = argv[++i] || out.profile;
    else if (arg === "--help" || arg === "-h") {
      console.log(`用法：node tools/web-launch.js [--port 17890] [--model ATRI] [--ws ws://ip:8765/ws] [--browser edge|chrome|auto] [--no-open]`);
      process.exit(0);
    }
  }
  return out;
}

function findBrowser(kind) {
  const order = kind === "auto" ? ["edge", "chrome"] : [kind];
  for (const name of order) {
    for (const candidate of CANDIDATES[name] ?? []) {
      if (candidate && fs.existsSync(candidate)) return { name, path: candidate };
    }
  }
  return null;
}

const options = parseArgs(process.argv.slice(2));

let serverInfo = null;
try {
  serverInfo = await startServer({ port: options.port, host: options.host });
} catch (error) {
  if (error?.code === "EADDRINUSE") {
    console.log(`[web] 端口 ${options.port} 已被占用，直接复用现有服务。`);
    serverInfo = { url: `http://${options.host}:${options.port}/`, server: null };
  } else {
    console.error("[web] 启动静态服务失败：", error);
    process.exit(1);
  }
}

const params = new URLSearchParams();
if (options.ws) params.set("ws", options.ws);
if (options.model) params.set("model", options.model);
const url = `${serverInfo.url}${params.toString() ? "?" + params.toString() : ""}`;
console.log(`[web] 渲染页：${url}`);

if (!options.noOpen) {
  const browser = findBrowser(options.browser);
  if (!browser) {
    console.warn("[web] 没找到 Edge/Chrome，请手动用浏览器打开上面的地址（或用 --no-open 关掉自动打开）。");
  } else {
    console.log(`[web] 用 ${browser.name} 打开 app 模式窗口…`);
    const child = spawn(
      browser.path,
      [
        `--app=${url}`,
        `--user-data-dir=${options.profile}`,
        "--no-first-run",
        "--no-default-browser-check",
        "--window-size=900,900",
        "--disable-features=Translate",
      ],
      { detached: true, stdio: "ignore" },
    );
    child.unref();
  }
}

console.log("[web] Ctrl+C 结束服务。");

const shutdown = () => {
  if (serverInfo?.server) serverInfo.server.close(() => process.exit(0));
  else process.exit(0);
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
