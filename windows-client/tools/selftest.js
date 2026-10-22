#!/usr/bin/env node
/**
 * tools/selftest.js — 不依赖浏览器的自检：映射数值 + 模型发现 + 静态服务路由
 *
 *   node tools/selftest.js
 *
 * 覆盖协议 §8.1 / §8.2（含自适应归一化首帧行为、reset 可复现性）与 server.js 的静态服务。
 */
import assert from "node:assert/strict";
import { createMapper, mapBshapes, mapTrackToParams, AdaptiveAngleNormalizer, BS_INDEX, BS_COUNT, PARAM_IDS } from "../renderer/mapping.js";
import { discoverModels, startServer } from "../server.js";

const results = [];
const near = (actual, expected, tolerance = 1e-6) =>
  assert.ok(Math.abs(actual - expected) <= tolerance, `期望 ~${expected}，实际 ${actual}（容差 ${tolerance}）`);

async function test(name, fn) {
  try {
    await fn();
    results.push({ name, ok: true });
    console.log(`  ✓ ${name}`);
  } catch (error) {
    results.push({ name, ok: false, error });
    console.error(`  ✗ ${name}\n      ${error?.message ?? error}`);
  }
}

function makeBs(overrides = {}) {
  const bs = new Array(BS_COUNT).fill(0);
  for (const [key, value] of Object.entries(overrides)) {
    const index = BS_INDEX[key];
    if (index === undefined) throw new Error("未知 blend shape: " + key);
    bs[index] = value;
  }
  return bs;
}

console.log("[selftest] mapping.js · 协议 §8.1 直通参数");
await test("眨眼：eyeLOpen = 1 - bs[9]", () => {
  const mapped = mapBshapes(makeBs({ eyeBlinkLeft: 0.8, eyeBlinkRight: 0.25 }));
  near(mapped.eyeLOpen, 0.2);
  near(mapped.eyeROpen, 0.75);
});

await test("嘴：jawOpen / smile-frown", () => {
  const mapped = mapBshapes(makeBs({ jawOpen: 0.42, mouthSmileLeft: 0.6, mouthSmileRight: 0.4, mouthFrownLeft: 0.1, mouthFrownRight: 0.2 }));
  near(mapped.mouthOpen, 0.42);
  near(mapped.mouthForm, 0.5 - 0.15);
});

await test("眼球：outL-inL 与 inR-outR", () => {
  // eyeBallX = ((outL - inL) + (inR - outR)) / 2
  const mapped = mapBshapes(makeBs({ eyeLookOutLeft: 0.8, eyeLookInLeft: 0.2, eyeLookInRight: 0.1, eyeLookOutRight: 0.5 }));
  near(mapped.eyeBallX, ((0.8 - 0.2) + (0.1 - 0.5)) / 2);
  const mappedY = mapBshapes(makeBs({ eyeLookUpLeft: 0.6, eyeLookDownLeft: 0.1, eyeLookUpRight: 0.3, eyeLookDownRight: 0.2 }));
  near(mappedY.eyeBallY, ((0.6 - 0.1) + (0.3 - 0.2)) / 2);
});

await test("clamp：mouthForm / eyeBallX 截断到 [-1,1]", () => {
  const mapped = mapBshapes(makeBs({ mouthSmileLeft: 1, mouthSmileRight: 1, mouthFrownLeft: 1, mouthFrownRight: 1, eyeLookOutLeft: 1, eyeLookInLeft: 1 }));
  near(mapped.mouthForm, 0);
  // ((outL - inL) + (inR - outR)) / 2 = ((1-0) + (1-0)) / 2 = 1
  const far = mapBshapes(makeBs({ eyeLookOutLeft: 1, eyeLookInRight: 1 }));
  near(far.eyeBallX, 1);
});

await test("bs 长度异常时不抛异常（补齐 / 截断到 52）", () => {
  const short = mapBshapes([0, 0, 0]);
  near(short.eyeLOpen, 1);
  const long = mapBshapes(new Array(80).fill(0.5));
  assert.equal(long.eyeLOpen, 0.5);
});

console.log("[selftest] mapping.js · 协议 §8.2 头部姿态");

await test("归一化器首帧：pitch=+20 → ParamAngleY ≈ -15.144（不是 -30）", () => {
  const mapper = createMapper();
  const result = mapper.apply(makeBs(), [20, 0, 0]);
  near(result.params.ParamAngleY, -15.143866733972741, 1e-9);
  near(result.normalized.pitch, 0.5047955577990914, 1e-9);
  assert.equal(mapper.normalizers.pitch.samples, 1);
});

await test("归一化器收敛：同方向持续 300 帧后接近满量程 -30", () => {
  const mapper = createMapper();
  for (let i = 0; i < 300; i++) mapper.apply(makeBs(), [20, 0, 0]);
  assert.ok(mapper.result.params.ParamAngleY < -29.9, "应接近 -30，实际 " + mapper.result.params.ParamAngleY);
});

await test("yaw：ParamAngleX = -yaw*30 / ParamBodyAngleX = -yaw*10（首帧按自适应量程）", () => {
  const mapper = createMapper();
  const result = mapper.apply(makeBs(), [0, -20, 0]);
  // 首帧 effectiveNeg 还不是 45，而是 45*0.99 + (20*0.1)*0.01
  const eff = mapper.normalizers.yaw.effectiveNeg();
  near(eff, 45 * 0.99 + 2 * 0.01, 1e-9);
  near(result.params.ParamAngleX, (20 / eff) * 30, 1e-9);
  near(result.params.ParamBodyAngleX, (20 / eff) * 10, 1e-9);
});

await test("roll：ParamAngleZ = roll * 30（首帧按自适应量程）", () => {
  const mapper = createMapper();
  const result = mapper.apply(makeBs(), [0, 0, 15]);
  const eff = mapper.normalizers.roll.effectivePos();
  near(eff, 30 * 0.99 + 1.5 * 0.01, 1e-9);
  near(result.params.ParamAngleZ, (15 / eff) * 30, 1e-9);
});

await test("reset 后重放同一帧结果完全一致", () => {
  const mapper = createMapper();
  const first = JSON.stringify(mapper.apply(makeBs({ jawOpen: 0.3 }), [18, -30, 12]).params);
  mapper.reset();
  const second = JSON.stringify(mapper.apply(makeBs({ jawOpen: 0.3 }), [18, -30, 12]).params);
  assert.equal(first, second);
});

await test("两个独立 mapper 互不影响（无全局状态）", () => {
  const a = createMapper();
  const b = createMapper();
  for (let i = 0; i < 50; i++) a.apply(makeBs(), [30, 30, 30]);
  const bFirst = b.apply(makeBs(), [20, 0, 0]);
  near(bFirst.params.ParamAngleY, -15.143866733972741, 1e-9);
  assert.equal(b.normalizers.pitch.samples, 1);
});

await test("invert 选项：invertYaw 翻转 ParamAngleX 符号", () => {
  const mapper = createMapper({ invertYaw: true });
  const result = mapper.apply(makeBs(), [0, -20, 0]);
  const eff = mapper.normalizers.yaw.effectivePos();
  near(result.params.ParamAngleX, -(20 / eff) * 30, 1e-9);
});

await test("apply(bs, pose) 与 apply(track) 等价", () => {
  const bs = makeBs({ eyeBlinkLeft: 0.5 });
  const first = createMapper().apply(bs, [5, 6, 7]).params;
  const second = createMapper().apply({ bs, pose: [5, 6, 7] }).params;
  assert.deepEqual(first, second);
});

await test("结果可 JSON.stringify（无 Map / 循环引用）", () => {
  const result = createMapper().apply(makeBs(), [10, 10, 10]);
  const text = JSON.stringify(result);
  assert.ok(text.includes("ParamAngleX"));
  assert.deepEqual(Object.keys(JSON.parse(text)).sort(), ["normalized", "params", "raw"]);
  assert.equal(PARAM_IDS.every((id) => typeof result.params[id] === "number"), true);
});

await test("mapTrackToParams 平铺输出 10 个参数", () => {
  const params = mapTrackToParams({ bs: makeBs({ jawOpen: 0.5 }), pose: [0, 0, 0] });
  for (const id of PARAM_IDS) assert.equal(typeof params[id], "number", "缺少参数 " + id);
  near(params.ParamMouthOpenY, 0.5);
});

await test("AdaptiveAngleNormalizer 负向量程独立自适应", () => {
  const normalizer = new AdaptiveAngleNormalizer(20, 40, 10);
  // 首帧：pctNeg = 10*0.1 = 1，effectiveNeg = 20*0.99 + 1*0.01 = 19.81
  near(normalizer.normalize(-10), -10 / (20 * 0.99 + 1 * 0.01), 1e-9);
  assert.equal(normalizer.samples, 1);
  normalizer.reset();
  assert.equal(normalizer.samples, 0);
  near(normalizer.normalize(-5), -5 / (20 * 0.99 + 0.5 * 0.01), 1e-9);
});

console.log("[selftest] server.js · 模型发现与静态路由");
const models = await discoverModels();
await test("模型目录发现 4 个模型（ATRI/sensei/Yuuka/zzzz）", () => {
  assert.equal(models.length, 4);
  // JS 默认排序按 UTF-16 码位：大写字母在小写之前
  const names = models.map((m) => m.name).sort();
  assert.deepEqual(names, ["ATRI", "Yuuka", "sensei", "zzzz"]);
  assert.ok(models.find((m) => m.name === "ATRI").entry.endsWith("/ATRI.model3.json"));
});

const { server, port } = await startServer({ port: 0, quiet: true });
const base = `http://127.0.0.1:${port}`;
try {
  await test("GET / 返回渲染页 HTML", async () => {
    const response = await fetch(base + "/");
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") ?? "", /text\/html/);
    const text = await response.text();
    assert.match(text, /live2d/);
  });

  await test("GET /api/models 返回 JSON 清单", async () => {
    const response = await fetch(base + "/api/models");
    assert.equal(response.status, 200);
    const data = await response.json();
    assert.equal(data.count, 4);
    assert.ok(data.models.some((m) => m.name === "ATRI"));
  });

  await test("GET /models/ATRI/ATRI.model3.json 可访问且为 JSON", async () => {
    const response = await fetch(base + "/models/ATRI/ATRI.model3.json");
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") ?? "", /application\/json/);
    const data = await response.json();
    assert.ok(data.FileReferences.Moc.endsWith(".moc3"));
  });

  await test("GET /vendor/*.js 全部 200（无 404）", async () => {
    for (const file of ["live2dcubismcore.min.js", "pixi.min.js", "pixi-live2d-display.js"]) {
      const response = await fetch(base + "/vendor/" + file);
      assert.equal(response.status, 200, file + " 不应 404");
      assert.match(response.headers.get("content-type") ?? "", /javascript/, file + " 的 Content-Type");
    }
  });

  await test("路径穿越被拒绝", async () => {
    const response = await fetch(base + "/models/..%2F..%2Fserver.js");
    assert.ok([403, 404].includes(response.status), "实际 " + response.status);
  });
} finally {
  server.close();
}

const failed = results.filter((r) => !r.ok);
console.log("");
console.log(`[selftest] ${results.length - failed.length}/${results.length} 通过`);
if (failed.length) {
  console.error("[selftest] 失败项：");
  for (const item of failed) console.error("  - " + item.name + "：" + (item.error?.message ?? item.error));
  process.exit(1);
}
process.exit(0);
