#!/usr/bin/env node
/**
 * tools/selftest.js — 不依赖浏览器的自检：映射数值 + 输入帧率/抖动缓冲 + 模型发现 + 静态服务路由
 *
 *   node tools/selftest.js
 *
 * 覆盖协议 §8.1 / §8.2（含自适应归一化首帧行为、reset 可复现性）、
 * 输入帧率低于渲染帧率 / 输入间隔不稳定时的插值（renderer/interp.js）与 server.js 的静态服务。
 */
import assert from "node:assert/strict";
import { createMapper, mapBshapes, mapTrackToParams, AdaptiveAngleNormalizer, BS_INDEX, BS_COUNT, PARAM_IDS } from "../renderer/mapping.js";
import {
  PoseOffsetCollector,
  applyPoseOffset,
  decodePoseOffset,
  encodePoseOffset,
  formatPoseOffset,
  isNeutralOffset,
  sanitizePoseOffset,
} from "../renderer/calibrate.js";
import { ParamTimeline } from "../renderer/interp.js";
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

console.log("[selftest] calibrate.js · 本地姿态校准（镜头没正对你时）");

await test("applyPoseOffset：三轴相加、返回长度 3、不修改输入", () => {
  const pose = [2, 8, -3];
  const out = applyPoseOffset(pose, { pitch: -2, yaw: -8, roll: 3 });
  assert.deepEqual(out, [0, 0, 0]);
  assert.deepEqual(pose, [2, 8, -3], "不应修改调用方的 pose 数组");
  assert.equal(out.length, 3);
});

await test("applyPoseOffset：坏数据 / 缺字段按 0 处理，不抛异常", () => {
  assert.deepEqual(applyPoseOffset(undefined, { yaw: 5 }), [0, 5, 0]);
  assert.deepEqual(applyPoseOffset([NaN, "x", null], null), [0, 0, 0]);
  assert.deepEqual(applyPoseOffset({}, { pitch: 1 }), [1, 0, 0]);
});

await test("sanitizePoseOffset：±45° 截断 + 非数字归零 + 接受数组", () => {
  assert.deepEqual(sanitizePoseOffset({ pitch: 90, yaw: -90, roll: "x" }), { pitch: 45, yaw: -45, roll: 0 });
  assert.deepEqual(sanitizePoseOffset([1, 2, 3]), { pitch: 1, yaw: 2, roll: 3 });
  assert.deepEqual(sanitizePoseOffset(null), { pitch: 0, yaw: 0, roll: 0 });
});

await test("encode / decode：坏 JSON / 空值回落到 0，合法值往返一致", () => {
  const offset = { pitch: -2.5, yaw: 8, roll: 0.5 };
  assert.deepEqual(decodePoseOffset(encodePoseOffset(offset)), offset);
  assert.deepEqual(decodePoseOffset("not json"), { pitch: 0, yaw: 0, roll: 0 });
  assert.deepEqual(decodePoseOffset(null), { pitch: 0, yaw: 0, roll: 0 });
  assert.deepEqual(decodePoseOffset('{"yaw":999}'), { pitch: 0, yaw: 45, roll: 0 });
});

await test("isNeutralOffset / formatPoseOffset", () => {
  assert.equal(isNeutralOffset({ pitch: 0.01, yaw: -0.02, roll: 0 }), true);
  assert.equal(isNeutralOffset({ pitch: 0, yaw: 0.2, roll: 0 }), false);
  assert.equal(formatPoseOffset({ pitch: 2.5, yaw: -8, roll: 0 }), "+2.5° / -8.0° / 0.0°");
});

await test("PoseOffsetCollector：face=false / 非有限 pose 不计入，帧数够才 ready", () => {
  const collector = new PoseOffsetCollector({ minSamples: 3 });
  assert.equal(collector.addTrack({ face: false, pose: [1, 2, 3] }), false);
  assert.equal(collector.addTrack({ face: true, pose: [NaN, 0, 0] }), false);
  assert.equal(collector.addPose([500, 0, 0]), false, "超出 ±100° 的坏帧应丢弃");
  assert.equal(collector.ready, false);
  collector.addTrack({ face: true, pose: [2, 8, -3] });
  collector.addTrack({ face: true, pose: [2, 8, -3] });
  assert.equal(collector.ready, false);
  collector.addTrack({ face: true, pose: [2, 8, -3] });
  assert.equal(collector.ready, true);
  assert.deepEqual(collector.report().offset, { pitch: -2, yaw: -8, roll: 3 });
  assert.equal(collector.rejectedFrames, 3);
});

await test("PoseOffsetCollector：截尾平均忽略偶发极值（抖一帧不会把零点带歪）", () => {
  const collector = new PoseOffsetCollector({ minSamples: 6, outlierFraction: 0.2 });
  for (let i = 0; i < 6; i++) collector.addPose([4, 10, -2]);
  collector.addPose([40, 60, -40]);
  collector.addPose([4, 10, -2]);
  collector.addPose([4, 10, -2]);
  const report = collector.report();
  assert.deepEqual(report.offset, { pitch: -4, yaw: -10, roll: 2 });
  assert.ok(report.spread > 5, "应能看出抖动，实际 " + report.spread);
  assert.equal(report.stable, false);
});

await test("PoseOffsetCollector：maxSamples 上限 + reset + 稳定判定", () => {
  const collector = new PoseOffsetCollector({ minSamples: 2, maxSamples: 5 });
  for (let i = 0; i < 10; i++) collector.addPose([1, 2, 3]);
  assert.equal(collector.count, 5);
  const report = collector.report();
  assert.deepEqual(report.offset, { pitch: -1, yaw: -2, roll: -3 });
  assert.equal(report.stable, true);
  assert.equal(report.spread, 0);
  collector.reset();
  assert.equal(collector.count, 0);
  assert.equal(collector.ready, false);
});

await test("校准后零姿态映射到 ParamAngle 0（本地偏移抵消固定角度）", () => {
  const offset = { pitch: -2, yaw: -8, roll: 3 };
  const mapper = createMapper();
  const result = mapper.apply(makeBs(), applyPoseOffset([2, 8, -3], offset));
  near(result.params.ParamAngleX, 0, 1e-12);
  near(result.params.ParamAngleY, 0, 1e-12);
  near(result.params.ParamAngleZ, 0, 1e-12);
  near(result.params.ParamBodyAngleX, 0, 1e-12);
});

console.log("[selftest] interp.js · 输入帧率低 / 间隔不稳定时的插值与抖动缓冲");

/** 只让 ParamAngleX 变化，便于用「值 = 斜率 × 时间」验证插值是否均匀。 */
function makeAngleParams(angleX) {
  const params = Object.fromEntries(PARAM_IDS.map((id) => [id, 0]));
  params.ParamAngleX = angleX;
  return params;
}

/**
 * 按指定输入 / 渲染节奏同时推进时间轴与采样（模拟真实收帧 + rAF），
 * 返回每两个渲染帧之间的参数步进（跳过前 600 ms，让自适应延迟先收敛）。
 */
function simulateTimeline({ inputHz, renderHz, durationMs }) {
  const timeline = new ParamTimeline();
  const inputInterval = 1000 / inputHz;
  const renderInterval = 1000 / renderHz;
  const steps = [];
  let prev = null;
  let index = 0;
  let nextInputAt = 0;
  for (let t = 0; t <= durationMs; t += renderInterval) {
    while (nextInputAt <= t + 1e-9) {
      timeline.push(makeAngleParams(index), nextInputAt);
      index += 1;
      nextInputAt = index * inputInterval;
    }
    const value = timeline.sample(t).ParamAngleX;
    if (prev !== null && t > 600) steps.push(value - prev);
    prev = value;
  }
  return { timeline, steps };
}

/** 可复现的伪随机（LCG），避免 Math.random 让测试偶发失败。 */
function makeRandom(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state * 1664525 + 1013904223) >>> 0;
    return state / 4294967296;
  };
}

/**
 * 模拟「输入间隔不稳定」：生产节奏由 intervalsMs 循环给出；
 * arrivalJitterMs > 0 时到达时间 = 生产时间 + 0..jitter 的随机延迟（WS 有序，保证单调）；
 * useStamps = true 时把生产时间当作 track.t 传给 push（模拟真实协议）。
 * driftMs 让到达时间每帧额外偏移一点（模拟两端时钟频率略有差异）。
 * 返回渲染侧步进、sampleAt 序列与缓冲见底比例。
 */
function simulateUnstable({
  intervalsMs = [1000 / 30],
  renderHz = 60,
  durationMs = 2000,
  arrivalJitterMs = 0,
  driftMs = 0,
  useStamps = false,
  seed = 1,
  skipMs = 900,
} = {}) {
  const timeline = new ParamTimeline();
  const random = makeRandom(seed);
  const renderInterval = 1000 / renderHz;
  const slack = intervalsMs.reduce((sum, value) => sum + value, 0);
  const events = [];
  let production = 0;
  let prevArrival = -Infinity;
  let index = 0;
  while (production <= durationMs + slack) {
    const interval = intervalsMs[index % intervalsMs.length];
    let arrival = production + driftMs * index + (arrivalJitterMs > 0 ? random() * arrivalJitterMs : 0);
    if (index === 0) arrival = production;
    if (arrival < prevArrival + 1) arrival = prevArrival + 1;
    prevArrival = arrival;
    events.push({ production, arrival, params: makeAngleParams(index) });
    production += interval;
    index += 1;
  }

  const steps = [];
  const sampleAts = [];
  let prev = null;
  let cursor = 0;
  let starved = 0;
  let frames = 0;
  for (let t = 0; t <= durationMs; t += renderInterval) {
    while (cursor < events.length && events[cursor].arrival <= t + 1e-9) {
      const event = events[cursor];
      timeline.push(event.params, event.arrival, useStamps ? event.production : null);
      cursor += 1;
    }
    const starvedBefore = timeline.starvedFrames;
    const sampled = timeline.sample(t);
    if (!sampled) continue;
    if (t >= skipMs) {
      frames += 1;
      if (timeline.starvedFrames > starvedBefore) starved += 1;
      if (prev !== null) steps.push(sampled.ParamAngleX - prev);
    }
    prev = sampled.ParamAngleX;
    sampleAts.push(t - timeline.delayMs);
  }
  return { timeline, steps, sampleAts, starvedRatio: frames ? starved / frames : 0 };
}

await test("输入 60 fps / 渲染 60 fps：不启用缓冲，延迟保持 0，逐帧跟随最新输入", () => {
  const { timeline, steps } = simulateTimeline({ inputHz: 60, renderHz: 60, durationMs: 1500 });
  assert.equal(timeline.delayMs, 0);
  assert.equal(timeline.interpolating, false);
  assert.ok(steps.length > 40, "样本太少：" + steps.length);
  // 60 / 60：一帧输入撑一帧渲染，步进就是 1（不做多余延迟）
  for (const step of steps) assert.ok(step > 0.9 && step < 1.1, "步进应≈1，实际 " + step);
});

await test("输入 30 fps / 渲染 60 fps：延迟 ≈ 一个输入间隔 + 抖动余量，渲染步进均匀（每帧 0.5）", () => {
  const { timeline, steps } = simulateTimeline({ inputHz: 30, renderHz: 60, durationMs: 1900 });
  const input = 1000 / 30;
  near(timeline.targetDelayMs(), input + 8, 0.5);
  assert.ok(steps.length > 40, "样本太少：" + steps.length);
  for (const step of steps) assert.ok(step > 0.45 && step < 0.55, "步进应≈0.5，实际 " + step);
  near(timeline.delayMs, input + 8, 3);
});

await test("输入 15 fps / 渲染 60 fps：渲染步进仍是均匀的 0.25", () => {
  const { steps } = simulateTimeline({ inputHz: 15, renderHz: 60, durationMs: 2000 });
  assert.ok(steps.length > 40, "样本太少：" + steps.length);
  for (const step of steps) assert.ok(step > 0.22 && step < 0.28, "步进应≈0.25，实际 " + step);
});

await test("乱序帧被丢弃、同时刻帧覆盖，不影响节奏", () => {
  const timeline = new ParamTimeline();
  assert.equal(timeline.push(makeAngleParams(1), 100), true);
  assert.equal(timeline.push(makeAngleParams(2), 50), false);
  assert.equal(timeline.count, 1);
  assert.equal(timeline.push(makeAngleParams(3), 100), true);
  assert.equal(timeline.count, 1);
  assert.equal(timeline.sample(100).ParamAngleX, 3);
});

await test("断流：最多外推 40 ms / 0.75 个输入间隔，不会让参数飞出去", () => {
  const timeline = new ParamTimeline();
  for (let i = 0; i < 10; i++) timeline.push(makeAngleParams(i * 2), i * (1000 / 30));
  for (let t = 0; t <= 500; t += 1000 / 60) timeline.sample(t);
  const value = timeline.sample(5000).ParamAngleX;
  assert.ok(Number.isFinite(value), "外推值应为有限数字");
  assert.ok(value >= 18 - 1e-9, "不应回退，实际 " + value);
  assert.ok(value <= 18 + 0.75 * 2 + 1e-9, "外推应被限制在 0.75 个输入间隔内，实际 " + value);
});

await test("断流超过 maxIntervalMs：旧样本作废，节奏重新测", () => {
  const timeline = new ParamTimeline();
  timeline.push(makeAngleParams(0), 0);
  timeline.push(makeAngleParams(1), 33);
  timeline.push(makeAngleParams(2), 1500);
  assert.equal(timeline.count, 1);
  assert.equal(timeline.inputIntervalMs, 0);
});

await test("参数缺失 / 非数字：按 0 处理，不抛异常", () => {
  const timeline = new ParamTimeline();
  timeline.push({ ParamAngleX: 10, ParamEyeLOpen: "x" }, 0);
  const sampled = timeline.sample(0);
  assert.equal(sampled.ParamAngleX, 10);
  assert.equal(sampled.ParamEyeLOpen, 0);
});

await test("reset 清空样本、延迟与节奏", () => {
  const timeline = new ParamTimeline();
  timeline.push(makeAngleParams(1), 0);
  timeline.push(makeAngleParams(2), 33);
  timeline.sample(66);
  timeline.reset();
  assert.equal(timeline.count, 0);
  assert.equal(timeline.delayMs, 0);
  assert.equal(timeline.inputIntervalMs, 0);
  assert.equal(timeline.sample(100), null);
});

await test("输入间隔抖动（10/50 ms 交替）：延迟按 p85 覆盖，缓冲不被抽干，播放时间单调", () => {
  const { timeline, steps, sampleAts, starvedRatio } = simulateUnstable({
    intervalsMs: [10, 50],
    durationMs: 3000,
    skipMs: 1000,
  });
  assert.ok(timeline.inputJitterMs > 15, "应测出间隔抖动（p85 − 中位数），实际 " + timeline.inputJitterMs);
  assert.ok(timeline.delayMs > 40 && timeline.delayMs <= 120, "延迟应覆盖 50 ms 的长间隔，实际 " + timeline.delayMs);
  assert.ok(starvedRatio < 0.03, "缓冲见底比例应 < 3%，实际 " + starvedRatio);
  const average = steps.reduce((sum, value) => sum + value, 0) / steps.length;
  assert.ok(average > 0.42 && average < 0.58, "平均步进应接近 30/60 = 0.5，实际 " + average);
  let prev = -Infinity;
  for (const at of sampleAts) {
    assert.ok(at >= prev - 1e-6, "sampleAt 不应倒退：" + prev + " -> " + at);
    prev = at;
  }
});

await test("传输抖动（到达时间 0~25 ms 随机延迟）：用手机采集时间戳排轴，步进恢复均匀", () => {
  const { timeline, steps, starvedRatio } = simulateUnstable({
    intervalsMs: [1000 / 30],
    arrivalJitterMs: 25,
    useStamps: true,
    durationMs: 3200,
    skipMs: 1500,
    seed: 7,
  });
  assert.ok(timeline.transitJitterMs > 5, "应测出传输滞后，实际 " + timeline.transitJitterMs);
  assert.ok(starvedRatio < 0.02, "缓冲见底比例应 < 2%，实际 " + starvedRatio);
  for (const step of steps) assert.ok(step > 0.42 && step < 0.58, "步进应≈0.5，实际 " + step);
});

await test("WS 批量到达（两帧挤在一起）：手机时间戳让播放间隔恢复正常", () => {
  const inputInterval = 1000 / 30;
  const run = (useStamps) => {
    const timeline = new ParamTimeline();
    const events = [];
    for (let i = 0; i < 120; i++) {
      const production = i * inputInterval;
      const batch = Math.floor(i / 2);
      const arrival = (batch * 2 + 1) * inputInterval + (i % 2); // 每两帧同一批，相差 1 ms
      events.push({ production, arrival, params: makeAngleParams(i) });
    }
    const steps = [];
    let prev = null;
    let cursor = 0;
    for (let t = 0; t <= 3400; t += 1000 / 60) {
      while (cursor < events.length && events[cursor].arrival <= t + 1e-9) {
        const event = events[cursor];
        timeline.push(event.params, event.arrival, useStamps ? event.production : null);
        cursor += 1;
      }
      const sampled = timeline.sample(t);
      if (!sampled) continue;
      if (prev !== null && t >= 1600) steps.push(sampled.ParamAngleX - prev);
      prev = sampled.ParamAngleX;
    }
    return { maxStep: Math.max(...steps.map((value) => Math.abs(value))), steps };
  };

  const withStamps = run(true);
  const without = run(false);
  assert.ok(withStamps.maxStep < 0.62, "有时间戳时应均匀（≤0.62），实际 " + withStamps.maxStep);
  assert.ok(without.maxStep > 1.0, "没有时间戳时突发会跳帧（>1.0），实际 " + without.maxStep);
});

await test("手机时钟回跳 / 时间戳缺失：重新锚定，不倒退、不抛异常", () => {
  const timeline = new ParamTimeline();
  assert.equal(timeline.push(makeAngleParams(1), 0, 1000), true);
  assert.equal(timeline.push(makeAngleParams(2), 33, 1033), true);
  assert.equal(timeline.push(makeAngleParams(3), 66, 500), true, "时钟回跳后应重新锚定并接受");
  assert.equal(timeline.count, 1);
  assert.equal(timeline.sample(66).ParamAngleX, 3);
  assert.equal(timeline.push(makeAngleParams(4), 99, 533), true);
  assert.ok(timeline.count >= 2);
  assert.ok(Number.isFinite(timeline.sample(99).ParamAngleX));

  const fallback = new ParamTimeline();
  fallback.push(makeAngleParams(1), 0, 1000);
  fallback.push(makeAngleParams(2), 33, 1033);
  fallback.push(makeAngleParams(3), 66); // 时间戳丢失：退回到达时间
  assert.equal(fallback.count, 1, "两套时间基准不能混用");
  fallback.push(makeAngleParams(4), 99);
  assert.equal(fallback.count, 2);
  const value = fallback.sample(99).ParamAngleX;
  assert.ok(value >= 3 && value <= 4, "回退后应继续插值，实际 " + value);
});

await test("输入节奏突变：延迟快升慢降，sampleAt 始终单调（不倒带）", () => {
  const timeline = new ParamTimeline();
  const renderInterval = 1000 / 60;
  let nextInput = 0;
  let index = 0;
  const sampleAts = [];
  let delayAfterSlow = null;
  let delayAfterRecover = null;
  for (let t = 0; t <= 5200; t += renderInterval) {
    while (nextInput <= t + 1e-9) {
      timeline.push(makeAngleParams(index), nextInput);
      index += 1;
      nextInput += nextInput < 1000 ? 1000 / 60 : nextInput < 2500 ? 1000 / 15 : 1000 / 60;
    }
    timeline.sample(t);
    sampleAts.push(t - timeline.delayMs);
    if (t >= 1250 && delayAfterSlow === null) delayAfterSlow = timeline.delayMs;
    if (t >= 3000 && delayAfterRecover === null) delayAfterRecover = timeline.delayMs;
  }
  assert.ok(delayAfterSlow > 40, "切到 15 fps 后 250 ms 内延迟应明显上升，实际 " + delayAfterSlow);
  assert.ok(delayAfterRecover > 15, "切回 60 fps 后 500 ms 仍应缓慢回落，实际 " + delayAfterRecover);
  assert.ok(timeline.delayMs < 5, "再过 2 s 应基本回落，实际 " + timeline.delayMs);
  let prev = -Infinity;
  for (const at of sampleAts) {
    assert.ok(at >= prev - 1e-6, "sampleAt 不应倒退：" + prev + " -> " + at);
    prev = at;
  }
});

await test("到达节奏比手机时钟略快（两端时钟频差）：偏移有界，媒体时间不会跑到到达时间前面", () => {
  // 到达时间每帧早 0.2 ms（相当于 6000 ppm 的时钟频差，真实设备远小于此），跑 20 s
  const { timeline, starvedRatio } = simulateUnstable({
    intervalsMs: [1000 / 30],
    arrivalJitterMs: 2,
    driftMs: -0.2,
    useStamps: true,
    durationMs: 20000,
    seed: 7,
    skipMs: 2000,
  });
  const ahead = Math.max(0, -Math.min(...timeline._lags));
  assert.ok(ahead < 8, "媒体时间最多领先到达时间 8 ms，实际 " + ahead);
  assert.ok(timeline.transitJitterMs < 8, "到达规律时传输抖动应很小，实际 " + timeline.transitJitterMs);
  near(timeline.delayMs, 1000 / 30 + 8, 8);
  assert.ok(starvedRatio < 0.02, "见底比例 " + starvedRatio);
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
