#!/usr/bin/env node
/**
 * tools/start-electron.js — npm start 的入口
 * 找到 npm i 装好的 Electron 可执行文件，用本目录（windows-client）作为 app 启动。
 * Electron 是 optionalDependency：下载失败时这里给出可操作的中文提示，而不是让 npm i 整体失败。
 */
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(import.meta.url);

let electronBinary = null;
try {
  electronBinary = require("electron");
} catch {
  electronBinary = null;
}

if (typeof electronBinary !== "string" || !fs.existsSync(electronBinary)) {
  console.error("");
  console.error("  ✖ 没有找到 Electron 可执行文件。");
  console.error("    Electron 是可选依赖，二进制从 npmmirror 镜像下载。请执行：");
  console.error("");
  console.error("      cd windows-client");
  console.error("      $env:ELECTRON_MIRROR='https://npmmirror.com/mirrors/electron/'");
  console.error("      npm i --registry=https://registry.npmmirror.com");
  console.error("");
  console.error("    不想装 Electron 也能用：npm run web（纯 Node 静态服务 + Edge/Chrome app 窗口）");
  console.error("");
  process.exit(1);
}

const smoke = process.argv.includes("--smoke");
// 自检模式在无交互桌面 / 无 GPU 的 CI 里跑：Chromium 的 GPU 与沙箱启动可能直接崩，
// 这两个开关只影响 --smoke，正常 npm start 不加。
const smokeSwitches = smoke ? ["--no-sandbox", "--disable-gpu"] : [];
const args = [...smokeSwitches, ROOT, ...process.argv.slice(2)];
const smokeResultFile = path.join(ROOT, "electron", "smoke-result.json");

// Electron 会继承宿主进程的环境变量。若外层（例如某些 IDE / 桌面壳）设置了
// ELECTRON_RUN_AS_NODE=1，Electron 会退化成纯 Node，import app 直接 undefined。
// npm start 的语义是启动 GUI，这里显式清掉。
const childEnv = { ...process.env };
delete childEnv.ELECTRON_RUN_AS_NODE;

if (smoke && fs.existsSync(smokeResultFile)) fs.rmSync(smokeResultFile, { force: true });

const child = spawn(electronBinary, args, { stdio: "inherit", env: childEnv });
child.on("error", (error) => {
  console.error("[vfc] 启动 Electron 失败：", error);
  process.exit(1);
});
child.on("exit", (code, signal) => {
  if (smoke) {
    // GUI 程序的 stdout 在 Windows 无控制台场景下不可见，结果以文件为准
    let result = null;
    try {
      result = JSON.parse(fs.readFileSync(smokeResultFile, "utf8"));
    } catch {
      result = null;
    }
    if (result) {
      console.log("[vfc][smoke] " + JSON.stringify(result));
      if (result.ready) {
        console.log("[vfc][smoke] 通过：渲染器已就绪");
        process.exit(0);
      }
      console.error("[vfc][smoke] 失败：渲染器未就绪");
      process.exit(1);
    }
  }
  if (signal) process.kill(process.pid, signal);
  else process.exit(code ?? 0);
});
