#!/usr/bin/env node
/**
 * tools/vendor-assets.js — 把 node_modules 里的第三方浏览器构建复制到 renderer/vendor/
 *
 * 目的：渲染页只依赖本地相对路径（无 CDN / 无 importmap），npm i 之后自动就绪。
 * 也允许重复执行（幂等）。缺失依赖时只警告，不让 npm i 整体失败。
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const VENDOR = path.join(ROOT, "renderer", "vendor");
const NODE_MODULES = path.join(ROOT, "node_modules");

const COPIES = [
  {
    from: path.join(NODE_MODULES, "pixi.js", "dist", "pixi.min.js"),
    to: path.join(VENDOR, "pixi.min.js"),
    name: "pixi.js",
  },
  {
    // 必须用 Cubism4-only 构建：dist/index.js 是 Cubism2+Cubism4 全量包，
    // 加载时无条件要求 window.Live2D（Cubism 2 runtime），只加载 Cubism Core 4 会直接 throw。
    from: path.join(NODE_MODULES, "pixi-live2d-display", "dist", "cubism4.min.js"),
    to: path.join(VENDOR, "pixi-live2d-display.js"),
    name: "pixi-live2d-display (cubism4)",
  },
];

fs.mkdirSync(VENDOR, { recursive: true });

let copied = 0;
for (const item of COPIES) {
  if (!fs.existsSync(item.from)) {
    console.warn(`[vendor] 跳过 ${item.name}：找不到 ${path.relative(ROOT, item.from)}（先跑 npm i）`);
    continue;
  }
  fs.copyFileSync(item.from, item.to);
  copied++;
  const size = fs.statSync(item.to).size;
  console.log(`[vendor] ${path.relative(ROOT, item.to)}  <- ${item.name} (${size} bytes)`);
}

const core = path.join(VENDOR, "live2dcubismcore.min.js");
if (!fs.existsSync(core)) {
  console.warn("[vendor] 警告：renderer/vendor/live2dcubismcore.min.js 缺失（Cubism Core 需要单独放置，不随 npm 包分发）");
} else {
  console.log(`[vendor] live2dcubismcore.min.js 就绪 (${fs.statSync(core).size} bytes)`);
}

console.log(`[vendor] 完成，复制 ${copied}/${COPIES.length} 个第三方浏览器构建`);
