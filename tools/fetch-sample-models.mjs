#!/usr/bin/env node
/**
 * tools/fetch-sample-models.mjs — 导入 Live2D 官方示例模型（Haru / Hiyori）到 windows-client/models/
 *
 *   node tools/fetch-sample-models.mjs          # 下载两个
 *   node tools/fetch-sample-models.mjs Haru     # 只下载一个（Hiyori 同理）
 *
 * 来源：Live2D 官方 Cubism SDK for Web 示例仓库（GitHub: Live2D/CubismWebSamples，
 * develop 分支 Samples/Resources/<Name>/）。
 *
 * ⚠️ 许可：Live2D 免费素材许可**禁止再分发**模型数据 —— 这两个模型**不进 Git**
 *    （.gitignore 已排除 windows-client/models/Haru、windows-client/models/Hiyori），
 *    仅供本机开发 / 验收使用。完整条款见 https://www.live2d.com/terms/ 。
 *
 * 用途：自带的 4 个模型（ATRI/sensei/Yuuka/zzzz）没有 ParamArm* / ParamShoulder* 参数，
 *      看不到上半身骨骼驱动；Haru / Hiyori 带 Cubism 标准手臂 / 身体参数，
 *      用来验收肩 / 手臂 / 肘的运动骨骼映射（docs/tracking-protocol.md §8.5）。
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const DEST = path.join(ROOT, "windows-client", "models");

const REPO = "Live2D/CubismWebSamples";
const REF = "develop";
const API = `https://api.github.com/repos/${REPO}`;
const RAW = `https://raw.githubusercontent.com/${REPO}/${REF}`;

const MODELS = ["Haru", "Hiyori"];
const UA = { "User-Agent": "VirtualFaceCapture-fetch-sample-models" };

/** 递归列出 prefix 下所有文件的相对路径（GitHub contents API）。 */
async function listFiles(prefix) {
  const files = [];
  async function walk(dir) {
    const res = await fetch(`${API}/contents/${dir}?ref=${REF}`, { headers: UA });
    if (!res.ok) throw new Error(`列出 ${dir} 失败：HTTP ${res.status} ${await res.text().catch(() => "")}`);
    const entries = await res.json();
    for (const entry of entries) {
      if (entry.type === "dir") await walk(entry.path);
      else if (entry.type === "file") files.push(entry.path.slice(prefix.length + 1));
    }
  }
  await walk(prefix);
  return files;
}

/** 下载一个文件到目标目录（保持相对路径结构，model3.json 里的引用才有效）。 */
async function download(prefix, relFile, destDir) {
  const url = `${RAW}/${prefix}/${relFile}`;
  const res = await fetch(url, { headers: UA });
  if (!res.ok) throw new Error(`下载失败 ${relFile}：HTTP ${res.status}`);
  const out = path.join(destDir, relFile);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, Buffer.from(await res.arrayBuffer()));
  return fs.statSync(out).size;
}

async function fetchModel(name) {
  const prefix = `Samples/Resources/${name}`;
  const destDir = path.join(DEST, name);
  process.stdout.write(`[${name}] 列举文件…`);
  const files = await listFiles(prefix);
  let total = 0;
  for (const rel of files) {
    process.stdout.write(`\r[${name}] 下载 ${rel}          `);
    total += await download(prefix, rel, destDir);
  }
  const entry = path.join(destDir, `${name}.model3.json`);
  if (!fs.existsSync(entry)) throw new Error(`[${name}] 缺少入口 ${name}.model3.json`);
  console.log(`\r[${name}] 完成：${files.length} 个文件，共 ${(total / 1024 / 1024).toFixed(1)} MB → ${path.relative(ROOT, destDir)}`);
}

const wanted = process.argv.slice(2).filter((a) => !a.startsWith("-"));
const targets = wanted.length ? wanted : MODELS;
for (const name of targets) {
  if (!MODELS.includes(name)) {
    console.error(`未知模型 ${name}（可选：${MODELS.join(" / ")}）`);
    process.exit(1);
  }
  await fetchModel(name);
}
console.log("全部完成。启动 windows-client 后在 URL 用 ?model=Haru / ?model=Hiyori 切换；");
console.log("或 npm run mock 后打开 http://127.0.0.1:17890/?ws=ws://127.0.0.1:8765/ws&model=Haru");
