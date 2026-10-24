#!/usr/bin/env node
/**
 * run-all.mjs — windows-client 的 node 单测入口。
 *
 *   node windows-client/test/run-all.mjs
 *
 * 不改 package.json（任务约束），所以不注册 npm script，直接 node 跑。
 * 自动发现同目录下的 test-*.mjs，每个文件导出 tests: Array<{name, fn}>。
 * 退出码：0 = 全过；1 = 有失败。
 */
import { readdirSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));

const files = readdirSync(here)
  .filter((f) => f.startsWith("test-") && f.endsWith(".mjs"))
  .sort();

let passed = 0;
let failed = 0;
const failures = [];

for (const file of files) {
  const mod = await import(pathToFileURL(join(here, file)).href);
  const tests = mod.tests || [];
  console.log(`\n■ ${file} (${tests.length})`);
  for (const t of tests) {
    try {
      await t.fn();
      passed++;
      console.log(`  ✓ ${t.name}`);
    } catch (err) {
      failed++;
      failures.push({ file, name: t.name, err });
      console.log(`  ✗ ${t.name}`);
      console.log(`      ${err && err.stack ? err.stack.split("\n").slice(0, 4).join("\n      ") : err}`);
    }
  }
}

console.log(`\n${"=".repeat(60)}`);
console.log(`通过 ${passed} / ${passed + failed}`);
if (failed > 0) {
  console.log(`失败 ${failed}：`);
  for (const f of failures) console.log(`  - ${f.file} :: ${f.name}`);
  process.exitCode = 1;
} else {
  console.log("全部通过");
}
