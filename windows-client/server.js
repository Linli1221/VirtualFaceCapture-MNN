#!/usr/bin/env node
/**
 * server.js — Windows 客户端本地静态服务（零第三方依赖）
 *
 *   node server.js                 # http://127.0.0.1:17890/
 *   node server.js --port 17900
 *
 * 路由：
 *   GET /                渲染页（renderer/index.html）
 *   GET /models/...      windows-client/models/...（模型资源，含 .moc3 / 纹理 / physics）
 *   GET /api/models      自动发现的模型清单 JSON
 *   GET /api/health      健康检查
 *   GET /node_modules/... pixi.js / pixi-live2d-display 的兜底直连（vendor 拷贝缺失时）
 *
 * 也作为模块被 electron/main.js 与 tools/web-launch.js 复用：startServer()。
 */
import http from "node:http";
import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = path.dirname(fileURLToPath(import.meta.url));

export const RENDERER_DIR = path.join(ROOT, "renderer");
export const MODELS_DIR = path.join(ROOT, "models");
export const NODE_MODULES_DIR = path.join(ROOT, "node_modules");
export const DEFAULT_HOST = "127.0.0.1";
export const DEFAULT_PORT = 17890;

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".cjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".map": "application/json; charset=utf-8",
  ".moc3": "application/octet-stream",
  ".bin": "application/octet-stream",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".gif": "image/gif",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".wav": "audio/wav",
  ".mp3": "audio/mpeg",
  ".ogg": "audio/ogg",
  ".txt": "text/plain; charset=utf-8",
  ".md": "text/plain; charset=utf-8",
};

/** 目录扫描：每个子目录里找 *.model3.json 作为入口。 */
export async function discoverModels(modelsDir = MODELS_DIR) {
  let entries;
  try {
    entries = await fsp.readdir(modelsDir, { withFileTypes: true });
  } catch {
    return [];
  }
  const models = [];
  for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
    if (!entry.isDirectory() || entry.name.startsWith(".")) continue;
    const dir = path.join(modelsDir, entry.name);
    let files;
    try {
      files = await fsp.readdir(dir);
    } catch {
      continue;
    }
    const lower = files.map((f) => f.toLowerCase());
    let idx = lower.findIndex((f) => f.endsWith(".model3.json"));
    if (idx < 0) idx = lower.findIndex((f) => f.endsWith(".model.json"));
    if (idx < 0) continue;
    const entryFile = files[idx];
    models.push({
      name: entry.name,
      entry: `/models/${encodeURIComponent(entry.name)}/${encodeURIComponent(entryFile)}`,
      dir,
    });
  }
  return models;
}

/** 把 URL 相对路径安全地拼到 baseDir 下；逃逸（..）返回 null。 */
function resolveInside(baseDir, relativePath) {
  let decoded;
  try {
    decoded = decodeURIComponent(relativePath);
  } catch {
    return null;
  }
  if (decoded.includes("\0")) return null;
  const base = path.resolve(baseDir);
  const abs = path.resolve(base, "." + path.sep + decoded.replace(/^[/\\]+/, ""));
  if (abs !== base && !abs.startsWith(base + path.sep)) return null;
  return abs;
}

function sendText(res, status, body, contentType = "text/plain; charset=utf-8") {
  res.writeHead(status, {
    "Content-Type": contentType,
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  res.end(body);
}

function sendJson(res, status, value) {
  sendText(res, status, JSON.stringify(value), "application/json; charset=utf-8");
}

async function serveFile(res, absPath, method) {
  let stat;
  try {
    stat = await fsp.stat(absPath);
  } catch {
    sendText(res, 404, `404 Not Found: ${path.basename(absPath)}`);
    return;
  }
  if (stat.isDirectory()) {
    const index = path.join(absPath, "index.html");
    try {
      const indexStat = await fsp.stat(index);
      if (indexStat.isFile()) return serveFile(res, index, method);
    } catch {
      /* fallthrough */
    }
    sendText(res, 404, "404 Not Found (directory without index.html)");
    return;
  }
  if (!stat.isFile()) {
    sendText(res, 404, "404 Not Found");
    return;
  }

  const ext = path.extname(absPath).toLowerCase();
  res.writeHead(200, {
    "Content-Type": MIME[ext] ?? "application/octet-stream",
    "Content-Length": stat.size,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  if (method === "HEAD") {
    res.end();
    return;
  }
  const stream = fs.createReadStream(absPath);
  stream.on("error", () => {
    res.destroy();
  });
  stream.pipe(res);
}

export function createRequestHandler() {
  const startedAt = Date.now();
  return async function handle(req, res) {
    try {
      if (req.method !== "GET" && req.method !== "HEAD") {
        sendText(res, 405, "405 Method Not Allowed");
        return;
      }

      const url = new URL(req.url ?? "/", "http://127.0.0.1");
      const pathname = url.pathname;

      if (pathname === "/api/models" || pathname === "/api/models/") {
        const models = await discoverModels();
        sendJson(res, 200, {
          count: models.length,
          default: models[0]?.name ?? null,
          models: models.map(({ name, entry }) => ({ name, entry, url: entry })),
        });
        return;
      }

      if (pathname === "/api/health" || pathname === "/api/health/") {
        sendJson(res, 200, {
          ok: true,
          uptimeMs: Date.now() - startedAt,
          models: (await discoverModels()).map((m) => m.name),
          electron: false,
        });
        return;
      }

      if (pathname === "/favicon.ico") {
        res.writeHead(204, { "Cache-Control": "no-store" });
        res.end();
        return;
      }

      let absPath;
      if (pathname === "/" || pathname === "/index.html") {
        absPath = path.join(RENDERER_DIR, "index.html");
      } else if (pathname.startsWith("/models/")) {
        absPath = resolveInside(MODELS_DIR, pathname.slice("/models/".length));
      } else if (pathname.startsWith("/node_modules/")) {
        absPath = resolveInside(NODE_MODULES_DIR, pathname.slice("/node_modules/".length));
      } else {
        absPath = resolveInside(RENDERER_DIR, pathname);
      }

      if (!absPath) {
        sendText(res, 403, "403 Forbidden");
        return;
      }
      await serveFile(res, absPath, req.method);
    } catch (err) {
      sendText(res, 500, "500 Internal Server Error: " + (err?.message ?? err));
    }
  };
}

/**
 * 启动本地服务。
 * @param {{host?:string, port?:number, quiet?:boolean}} [options]
 * @returns {Promise<{server: import("node:http").Server, host:string, port:number, url:string}>}
 */
export function startServer({ host = DEFAULT_HOST, port = DEFAULT_PORT, quiet = false } = {}) {
  return new Promise((resolve, reject) => {
    const server = http.createServer(createRequestHandler());
    server.once("error", (err) => reject(err));
    server.listen(port, host, async () => {
      const address = server.address();
      const actualPort = typeof address === "object" && address ? address.port : port;
      const url = `http://${host}:${actualPort}/`;
      if (!quiet) {
        const models = await discoverModels();
        console.log(`${"=".repeat(64)}`);
        console.log(`  VirtualFaceCapture · Windows 客户端静态服务`);
        console.log(`  ${url}`);
        console.log(`  模型 ${models.length} 个：${models.map((m) => m.name).join(", ") || "(未找到)"}`);
        console.log(`  渲染页：${url}?ws=ws://127.0.0.1:8765/ws&model=${models[0]?.name ?? "ATRI"}`);
        console.log(`  自检：curl ${url}api/models`);
        console.log(`${"=".repeat(64)}`);
      }
      resolve({ server, host, port: actualPort, url });
    });
  });
}

function parseArgs(argv) {
  const out = { host: DEFAULT_HOST, port: DEFAULT_PORT };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--port" || arg === "-p") out.port = Number(argv[++i]) || DEFAULT_PORT;
    else if (arg === "--host") out.host = argv[++i] || DEFAULT_HOST;
    else if (arg === "--quiet") out.quiet = true;
  }
  return out;
}

const isMain = process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;
if (isMain) {
  const args = parseArgs(process.argv.slice(2));
  startServer(args)
    .then(({ server }) => {
      const shutdown = () => server.close(() => process.exit(0));
      process.on("SIGINT", shutdown);
      process.on("SIGTERM", shutdown);
    })
    .catch((err) => {
      if (err?.code === "EADDRINUSE") {
        console.error(`端口 ${args.port} 已被占用：可能已经有一个服务在跑。`);
        console.error(`  直接用浏览器打开 http://${args.host}:${args.port}/ ，或换端口：node server.js --port 17900`);
      } else {
        console.error("启动静态服务失败：", err);
      }
      process.exitCode = 1;
    });
}
