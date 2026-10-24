#!/usr/bin/env node
/** verify.mjs — 核对 golden-export 数据集的 SHA-256 */
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const dir = dirname(fileURLToPath(import.meta.url));
const m = JSON.parse(readFileSync(join(dir, "manifest.json"), "utf8"));
let bad = 0;
for (const f of m.files) {
  const actual = createHash("sha256").update(readFileSync(join(dir, f.file), "utf8")).digest("hex");
  const ok = actual === f.sha256;
  if (!ok) bad++;
  console.log((ok ? "OK   " : "FAIL ") + f.file + "  " + actual.slice(0, 24));
}
console.log(bad === 0 ? "\n全部通过 (" + m.files.length + " 个文件)" : "\n" + bad + " 个文件哈希不符");
process.exitCode = bad === 0 ? 0 : 1;
