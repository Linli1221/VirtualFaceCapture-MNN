#!/usr/bin/env node
/**
 * tools/package-windows.js — 组装 Windows 便携版（免安装，解压即用）
 *
 * 产物目录结构：
 *
 *   dist/windows/VFC-Live2D-win32-x64/
 *   ├─ VFC-Live2D.exe               ← Electron 运行时（electron.exe 改名）
 *   ├─ resources/app/               ← 客户端代码 + 模型（package.json / server.js / electron / renderer / models / tools）
 *   ├─ 启动说明.txt
 *   └─ ... Electron 自带的 locales / *.dll / *.pak / icudtl.dat
 *
 * 用法：
 *   node tools/package-windows.js                     # 输出到 dist/windows/VFC-Live2D-win32-x64
 *   node tools/package-windows.js --out build/win     # 改输出根目录
 *   node tools/package-windows.js --name VFC-dev      # 改目录名（zip 名也一般跟着改）
 *   node tools/package-windows.js --electron-dir D:\electron\dist   # 指定 Electron 运行时（默认用 npm i 装好的）
 *
 * 依赖 Electron 运行时（npm i 里是 optionalDependency，装失败时 `npm run web` 仍然能用）。
 * 打包后可用 `VFC-Live2D.exe --smoke` 在 CI / 无 GPU 环境自检（见 electron/main.js）。
 */
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(path.join(ROOT, "package.json"));

const APP_FILES = ["package.json", "server.js", "README.md"];
const APP_DIRS = ["electron", "renderer", "models", "tools"];
const EXE_NAME = "VFC-Live2D.exe";
const PRODUCT_NAME = "VFC Live2D";

function fail(message) {
  console.error("\n  ✖ " + message + "\n");
  process.exit(1);
}

function parseArgs(argv) {
  const options = {
    out: "dist/windows",
    name: "VFC-Live2D-win32-x64",
    electronDir: "",
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--out") options.out = argv[++i] ?? options.out;
    else if (arg === "--name") options.name = argv[++i] ?? options.name;
    else if (arg === "--electron-dir") options.electronDir = argv[++i] ?? "";
    else if (arg === "--help" || arg === "-h") {
      console.log("用法：node tools/package-windows.js [--out <dir>] [--name <dir>] [--electron-dir <dir>]");
      process.exit(0);
    } else {
      fail("未知参数：" + arg + "（用 --help 查看用法）");
    }
  }
  return options;
}

/** Electron 运行时目录：优先 --electron-dir，否则用 npm i 装好的 electron 包。 */
function resolveElectronDir(explicit) {
  if (explicit) {
    const dir = path.resolve(ROOT, explicit);
    if (!fs.existsSync(path.join(dir, "electron.exe"))) {
      fail("--electron-dir 里没有 electron.exe：" + dir);
    }
    return dir;
  }
  let exe = null;
  try {
    exe = require("electron");
  } catch {
    exe = null;
  }
  if (typeof exe !== "string" || !fs.existsSync(exe)) {
    fail(
      [
        "没有找到 Electron 运行时（打包成 exe 需要它）。",
        "",
        "  cd windows-client",
        "  npm i                              # optionalDependency，会下载 Electron 运行时",
        "  # 国内网络可加：$env:ELECTRON_MIRROR='https://npmmirror.com/mirrors/electron/'",
        "",
        "不想装 Electron 也能用：npm run web（纯 Node 静态服务 + Edge/Chrome 窗口）。",
        "或者用 --electron-dir 指向任意一份解压好的 Electron 发行版。",
      ].join("\n"),
    );
  }
  return path.dirname(exe);
}

/** 目录总字节数。 */
function totalBytes(dir) {
  let sum = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    sum += entry.isDirectory() ? totalBytes(full) : fs.statSync(full).size;
  }
  return sum;
}

function formatBytes(bytes) {
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + " MB";
  if (bytes >= 1024) return (bytes / 1024).toFixed(0) + " KB";
  return bytes + " B";
}

const options = parseArgs(process.argv.slice(2));
const electronDir = resolveElectronDir(options.electronDir);

const outRoot = path.resolve(ROOT, options.out);
const outDir = path.join(outRoot, options.name);
const appDir = path.join(outDir, "resources", "app");

if (!fs.existsSync(path.join(ROOT, "models"))) {
  fail("找不到 models 目录（Windows 客户端的 Live2D 模型是仓库里的一部分，请检查检出是否完整）");
}

fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });

// 1) Electron 运行时（含 locales / dll / pak / icudtl.dat）
fs.cpSync(electronDir, outDir, { recursive: true });

// 2) 业务代码与模型放进 resources/app（Electron 默认的 asar 目录，这里用散文件，方便改模型）
const resources = path.join(outDir, "resources");
fs.rmSync(path.join(resources, "default_app.asar"), { force: true });
fs.mkdirSync(appDir, { recursive: true });

for (const file of APP_FILES) {
  const from = path.join(ROOT, file);
  if (!fs.existsSync(from)) fail("缺少文件：" + path.relative(ROOT, from));
  fs.copyFileSync(from, path.join(appDir, file));
}
for (const dir of APP_DIRS) {
  const from = path.join(ROOT, dir);
  if (!fs.existsSync(from)) fail("缺少目录：" + path.relative(ROOT, from));
  fs.cpSync(from, path.join(appDir, dir), { recursive: true });
}

// 3) 打包版 package.json：去掉 dependencies / scripts（运行时不需要 node_modules）
const pkg = JSON.parse(fs.readFileSync(path.join(ROOT, "package.json"), "utf8"));
const packed = {
  name: pkg.name,
  productName: PRODUCT_NAME,
  version: pkg.version,
  description: pkg.description,
  type: pkg.type,
  main: pkg.main,
};
for (const key of ["author", "license", "homepage"]) {
  if (pkg[key]) packed[key] = pkg[key];
}
fs.writeFileSync(path.join(appDir, "package.json"), JSON.stringify(packed, null, 2) + "\n");

// 4) 主程序改名，避免和系统里其它 Electron 应用混淆
fs.renameSync(path.join(outDir, "electron.exe"), path.join(outDir, EXE_NAME));

// 5) 启动说明
fs.writeFileSync(
  path.join(outDir, "启动说明.txt"),
  [
    `${PRODUCT_NAME}（Windows 客户端 · 便携版 ${pkg.version}）`,
    "",
    `1. 双击 ${EXE_NAME} 启动（首次启动会弹 Windows 防火墙提示，选“允许”，否则手机连不上）。`,
    "2. 面板里填手机的追踪地址：ws://<手机IP>:8765/ws ，点“连接”。",
    "3. 手机端：开始面捕 → 网络输出（追踪服务）启动，手机上会显示同样的地址。",
    "",
    "透明 / 无边框 / 置顶 / 鼠标穿透 四个开关在 Electron 下生效；",
    "鼠标穿透后点不到面板，按 Ctrl+Alt+T 取消穿透。",
    "URL 参数 ?fps=<n> 可改渲染目标帧率（默认 60，0 = 不限制）。",
    "",
    "OBS：窗口捕获本窗口；想要透明底就同时打开“透明”和“无边框”。",
    "",
    "自检：VFC-Live2D.exe --smoke （启动后等渲染就绪，结果写到 resources/app/electron/smoke-result.json）",
    "",
    "配置与窗口位置保存在 %APPDATA%\\vfc-windows-client\\ 。",
    "卸载：直接删掉本目录即可（无注册表、无安装程序）。",
  ].join("\r\n"),
);

// 6) 汇总
const appBytes = totalBytes(appDir);
const total = totalBytes(outDir);
console.log("[package] Electron 运行时：" + electronDir);
console.log("[package] 输出：" + outDir);
console.log(
  "[package] app " + formatBytes(appBytes) + " · 合计 " + formatBytes(total) + " · 版本 " + pkg.version,
);
console.log("[package] app 内容：" + fs.readdirSync(appDir).join(", "));
