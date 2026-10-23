#!/usr/bin/env node
/**
 * tools/selftest.js — 不依赖浏览器的自检：映射数值 + 输入帧率/抖动缓冲 + 模型发现 + 静态服务路由
 *
 *   node tools/selftest.js
 *
 * 覆盖协议 §8.1 / §8.2（含自适应归一化首帧行为、reset 可复现性）、
 * 上半身关键点（协议 §4.2 / §5.3：解析、躯干/手臂映射、丢失平滑、调试骨架用的骨架表）、
 * 输入帧率低于渲染帧率 / 输入间隔不稳定时的插值（renderer/interp.js）与 server.js 的静态服务。
 */
import assert from "node:assert/strict";
import {
  AdaptiveAngleNormalizer,
  BODY_ANGLE_LIMIT,
  BODY_ARM_MIN_VISIBILITY,
  BODY_BONES,
  BODY_COUNT,
  BODY_DECAY_PER_FRAME,
  BODY_FLOATS,
  BODY_LANDMARK_ORDER,
  BODY_PARAM_IDS,
  BODY_STALE_MS,
  BS_INDEX,
  BS_COUNT,
  PARAM_IDS,
  buildUpperBodySkeleton,
  combineBodyAngleX,
  createMapper,
  mapBshapes,
  mapBody,
  mapTrackToParams,
  parseUpperBody,
  readUpperBodyTrack,
  smoothBodyParams,
} from "../renderer/mapping.js";
import { Live2DStage, toModelRange } from "../renderer/live2d.js";
import { buildUpperBody } from "./mock-body.js";
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
await test("模型目录含 4 个内置模型（ATRI/sensei/Yuuka/zzzz），可选 Haru/Hiyori", () => {
  const names = models.map((m) => m.name);
  for (const bundled of ["ATRI", "Yuuka", "sensei", "zzzz"]) {
    assert.ok(names.includes(bundled), "缺少内置模型 " + bundled);
  }
  // Haru/Hiyori 由 tools/fetch-sample-models.mjs 按需导入（许可禁止入库，CI 上没有）：
  // 存在时校验入口文件名，不存在不算失败
  for (const optional of ["Haru", "Hiyori"]) {
    const found = models.find((m) => m.name === optional);
    if (found) assert.ok(found.entry.endsWith("/" + optional + ".model3.json"), optional + " 入口应为 *.model3.json");
  }
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
    // 内置 4 个必在；Haru/Hiyori 可选（fetch-sample-models 按需导入，CI 上没有）
    assert.ok(data.count >= 4, "至少应有 4 个内置模型，实际 " + data.count);
    assert.equal(data.models.length, data.count);
    assert.ok(data.models.some((m) => m.name === "ATRI"));
    assert.ok(data.models.some((m) => m.name === "sensei"));
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

console.log("[selftest] mapping.js · 上半身关键点（协议 §4.2 / §5.3）");

// 协议顺序下标（与 CommonData.UpperBodyLandmarkIndex.ORDER 逐项一致）
const P = BODY_LANDMARK_ORDER;

/** 构造协议 ub（13×4）：默认直立站姿、双臂自然下垂（肘在肩腕中点 = 手臂伸直，腕在肩正下方）。 */
function makeUb({
  shoulderL = [0.35, 0.4],
  shoulderR = [0.65, 0.4],
  elbowL = null,
  elbowR = null,
  wristL = [0.35, 0.78],
  wristR = [0.65, 0.78],
  hipL = [0.39, 0.86],
  hipR = [0.61, 0.86],
  visibility = 0.9,
  visibilityL = null,
  visibilityR = null,
} = {}) {
  const ub = new Array(BODY_FLOATS).fill(0);
  const set = (index, x, y, z, v) => {
    const base = index * 4;
    ub[base] = x;
    ub[base + 1] = y;
    ub[base + 2] = z;
    ub[base + 3] = v;
  };
  const vL = visibilityL == null ? visibility : visibilityL;
  const vR = visibilityR == null ? visibility : visibilityR;
  set(P.nose, 0.5, 0.16, 0, visibility);
  set(P.eye_left, 0.46, 0.15, 0, visibility);
  set(P.eye_right, 0.54, 0.15, 0, visibility);
  set(P.ear_left, 0.42, 0.17, 0, visibility);
  set(P.ear_right, 0.58, 0.17, 0, visibility);
  set(P.shoulder_left, shoulderL[0], shoulderL[1], 0, vL);
  set(P.shoulder_right, shoulderR[0], shoulderR[1], 0, vR);
  // 肘默认取肩腕中点（手臂伸直）；传了 elbowL/elbowR 就按给定位置（可造肘屈曲）
  set(P.elbow_left, ...(elbowL ?? [(shoulderL[0] + wristL[0]) / 2, (shoulderL[1] + wristL[1]) / 2]), 0, vL);
  set(P.elbow_right, ...(elbowR ?? [(shoulderR[0] + wristR[0]) / 2, (shoulderR[1] + wristR[1]) / 2]), 0, vR);
  set(P.wrist_left, wristL[0], wristL[1], 0, vL);
  set(P.wrist_right, wristR[0], wristR[1], 0, vR);
  set(P.hip_left, hipL[0], hipL[1], 0, visibility);
  set(P.hip_right, hipR[0], hipR[1], 0, visibility);
  return ub;
}

await test("BODY_LANDMARK_ORDER 与协议顺序（CommonData.UpperBodyLandmarkIndex.ORDER）逐项一致", () => {
  assert.deepEqual(Object.keys(BODY_LANDMARK_ORDER), [
    "nose",
    "eye_left",
    "eye_right",
    "ear_left",
    "ear_right",
    "shoulder_left",
    "shoulder_right",
    "elbow_left",
    "elbow_right",
    "wrist_left",
    "wrist_right",
    "hip_left",
    "hip_right",
  ]);
  assert.deepEqual(Object.values(BODY_LANDMARK_ORDER), [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
  assert.equal(BODY_COUNT, 13);
  assert.equal(BODY_FLOATS, 52);
});

await test("BODY_BONES 与 CommonData.UpperBodySkeleton.BONES 一致（15 条边，协议下标）", () => {
  assert.equal(BODY_BONES.length, 15);
  assert.deepEqual(
    BODY_BONES.map((edge) => [...edge]),
    [
      [0, 1],
      [0, 2],
      [1, 3],
      [2, 4],
      [1, 2],
      [0, 5],
      [0, 6],
      [5, 6],
      [5, 7],
      [7, 9],
      [6, 8],
      [8, 10],
      [5, 11],
      [6, 12],
      [11, 12],
    ],
  );
  for (const [a, b] of BODY_BONES) {
    assert.ok(a >= 0 && a < BODY_COUNT, "骨架边下标越界：" + a);
    assert.ok(b >= 0 && b < BODY_COUNT, "骨架边下标越界：" + b);
  }
});

await test("parseUpperBody：52 项 → 13 个 {x,y,z,v}，z / v 原样保留", () => {
  const points = parseUpperBody(makeUb());
  assert.equal(points.length, 13);
  near(points[P.shoulder_left].x, 0.35);
  near(points[P.shoulder_left].y, 0.4);
  assert.equal(points[P.nose].z, 0);
  near(points[P.wrist_right].v, 0.9);
  // 返回新对象，改结果不会写回输入
  points[P.nose].x = 0.9;
  assert.equal(makeUb()[P.nose * 4], 0.5);
});

await test("parseUpperBody：长度不对 → null（51 / 53 / 空 / 非数组 / DataView）", () => {
  for (const bad of [
    null,
    undefined,
    [],
    new Array(BODY_FLOATS - 1).fill(0),
    new Array(BODY_FLOATS + 1).fill(0),
    "52",
    52,
    {},
    new Float32Array(40),
    new DataView(new ArrayBuffer(208)),
  ]) {
    assert.equal(parseUpperBody(bad), null, "应当返回 null：" + String(bad));
  }
  // TypedArray 的合法长度要能解析
  assert.equal(parseUpperBody(new Float32Array(makeUb())).length, 13);
});

await test("parseUpperBody：NaN / Infinity → 0，visibility clamp 到 [0,1]", () => {
  const broken = makeUb();
  broken[P.nose * 4] = Number.NaN;
  broken[P.nose * 4 + 1] = Number.POSITIVE_INFINITY;
  broken[P.nose * 4 + 2] = Number.NEGATIVE_INFINITY;
  broken[P.nose * 4 + 3] = 5;
  broken[P.wrist_left * 4 + 3] = -3;
  const points = parseUpperBody(broken);
  assert.equal(points[P.nose].x, 0);
  assert.equal(points[P.nose].y, 0);
  assert.equal(points[P.nose].z, 0);
  assert.equal(points[P.nose].v, 1);
  assert.equal(points[P.wrist_left].v, 0);
});

await test("mapBody：中性肩线 + 双臂下垂 → 所有参数 0", () => {
  const mapped = mapBody([0, 0], makeUb());
  near(mapped.params.ParamBodyAngleX, 0);
  near(mapped.params.ParamBodyAngleY, 0);
  near(mapped.params.ParamBodyAngleZ, 0);
  near(mapped.params.ParamArmLA, 0);
  near(mapped.params.ParamArmRA, 0);
  near(mapped.params.ParamArmLB, 0);
  near(mapped.params.ParamArmRB, 0);
  near(mapped.params.ParamShoulderL, 0);
  near(mapped.params.ParamShoulderR, 0);
  near(mapped.params.ParamHandL, 0);
  near(mapped.params.ParamHandR, 0);
  near(mapped.metrics.armAngleL, 0);
  near(mapped.metrics.armAngleR, 0);
  near(mapped.metrics.elbowBendL, 0);
  near(mapped.metrics.elbowBendR, 0);
  near(mapped.metrics.shoulderWidth, 0.3);
  near(mapped.metrics.confidence, 0.9);
});

await test("mapBody：左臂抬起 → ParamArmLA 增大，ParamArmRA 不动", () => {
  const raised = mapBody([0, 0], makeUb({ wristL: [0.2, 0.05] }));
  // 肩→腕 = (-0.15, -0.35)：与竖直向下夹角 ≈ 156.8°（举过头顶）→ armUp 满量程
  near(raised.metrics.armAngleL, (Math.atan2(0.15, -0.35) * 180) / Math.PI, 1e-9);
  near(raised.params.ParamArmLA, 30);
  near(raised.params.ParamArmRA, 0);

  // 平举 90° → armUp = 90/150 = 0.6 → 18
  const horizontal = mapBody([0, 0], makeUb({ wristL: [0.05, 0.4] }));
  near(horizontal.metrics.armAngleL, 90);
  near(horizontal.params.ParamArmLA, 18);
  near(horizontal.params.ParamArmRA, 0);
});

await test("mapBody：右肩下沉（roll > 0）→ ParamBodyAngleZ 为正，反之为负", () => {
  const sink = mapBody([10, 0], makeUb({ shoulderR: [0.65, 0.41] }));
  assert.ok(sink.params.ParamBodyAngleZ > 0);
  near(sink.params.ParamBodyAngleZ, 5);
  near(sink.params.ParamBodyAngleY, -2);
  const raise = mapBody([-10, 0], null);
  assert.ok(raise.params.ParamBodyAngleZ < 0);
  near(raise.params.ParamBodyAngleZ, -5);
});

await test("mapBody：yaw → ParamBodyAngleX = -yaw*0.3（与头部同向）", () => {
  near(mapBody([0, 20], null).params.ParamBodyAngleX, -6);
  near(mapBody([0, -20], null).params.ParamBodyAngleX, 6);
  near(mapBody([0, 20], makeUb()).params.ParamBodyAngleX, -6);
});

await test("mapBody：参数 clamp 到 ±30（roll 300 / yaw 200 / 角度 > 150°）", () => {
  const extreme = mapBody([300, 200], null).params;
  near(extreme.ParamBodyAngleZ, BODY_ANGLE_LIMIT);
  near(extreme.ParamBodyAngleY, -BODY_ANGLE_LIMIT);
  near(extreme.ParamBodyAngleX, -BODY_ANGLE_LIMIT);
  const lifted = mapBody([0, 0], makeUb({ wristL: [0.35, 0.0] })); // 180°，远超 150° 满量程
  near(lifted.params.ParamArmLA, 30);
});

await test("mapBody：该侧肩/肘/腕可见度 < 0.3 → 手臂参数不输出（让客户端回默认）", () => {
  const low = mapBody([0, 0], makeUb({ visibilityL: 0.1, wristL: [0.2, 0.05] }));
  assert.equal("ParamArmLA" in low.params, false);
  assert.ok("ParamArmRA" in low.params);
  near(low.params.ParamArmRA, 0);
  // confidence = 肩/肘/腕可见度最小值
  near(low.metrics.confidence, 0.1);
  // 阈值正好 0.3 时仍输出（>= 判定）
  const edge = mapBody([0, 0], makeUb({ visibilityL: BODY_ARM_MIN_VISIBILITY }));
  assert.ok("ParamArmLA" in edge.params);
});

await test("mapBody：坏数据不抛异常（ub 长度错 / 全 NaN / body 非数字）", () => {
  for (const bad of [null, undefined, [], [1, 2, 3], new Array(BODY_FLOATS).fill(Number.NaN), "junk"]) {
    const mapped = mapBody([0, 0], bad);
    assert.ok(Number.isFinite(mapped.params.ParamBodyAngleZ));
    assert.equal("ParamArmLA" in mapped.params, false);
  }
  const nanAngles = mapBody([Number.NaN, undefined], null);
  near(nanAngles.params.ParamBodyAngleX, 0);
  near(nanAngles.params.ParamBodyAngleY, 0);
  near(nanAngles.params.ParamBodyAngleZ, 0);
});

await test("mapBody：兼容 docs §3.6 的 mapBody(ub, body) 参数顺序", () => {
  const docOrder = mapBody(makeUb({ wristL: [0.05, 0.4] }), [3, -7]);
  const taskOrder = mapBody([3, -7], makeUb({ wristL: [0.05, 0.4] }));
  near(docOrder.params.ParamBodyAngleX, taskOrder.params.ParamBodyAngleX);
  near(docOrder.params.ParamBodyAngleY, taskOrder.params.ParamBodyAngleY);
  near(docOrder.params.ParamBodyAngleZ, taskOrder.params.ParamBodyAngleZ);
  near(docOrder.params.ParamArmLA, taskOrder.params.ParamArmLA);
  near(docOrder.metrics.confidence, taskOrder.metrics.confidence);
});

await test("mapBody：也接受 parseUpperBody() 解析后的 13 点（省一次解析）", () => {
  const ub = makeUb({ wristL: [0.05, 0.4] });
  const viaFlat = mapBody([0, 0], ub);
  const viaPoints = mapBody([0, 0], parseUpperBody(ub));
  near(viaPoints.params.ParamArmLA, viaFlat.params.ParamArmLA);
  near(viaPoints.metrics.armAngleL, viaFlat.metrics.armAngleL);
});

await test("buildUpperBodySkeleton：层级关节角（脊柱 / 锁骨 / 上臂 / 前臂 / 肘）", () => {
  // 直立 + 双臂伸直下垂：所有关节角为 0
  const neutral = buildUpperBodySkeleton(makeUb());
  assert.equal(neutral.points.length, BODY_COUNT);
  assert.equal(neutral.spine.valid, true);
  near(neutral.spine.leanDeg, 0);
  assert.equal(neutral.clavicle.valid, true);
  near(neutral.clavicle.tiltDeg, 0);
  near(neutral.clavicle.relTiltDeg, 0);
  near(neutral.arms.L.elbowBendDeg, 0);
  near(neutral.arms.R.elbowBendDeg, 0);
  assert.equal(neutral.arms.L.valid, true);
  near(neutral.arms.L.elevationDeg, 0);

  // 左肘屈 90°（前臂平收到身体外侧）、右肘镜像同样动作：两侧 elbowBend 同为正
  const bent = buildUpperBodySkeleton(
    makeUb({
      elbowL: [0.35, 0.57],
      wristL: [0.26, 0.57],
      elbowR: [0.65, 0.57],
      wristR: [0.74, 0.57],
    }),
  );
  near(bent.arms.L.elbowBendDeg, 90, 0.01);
  near(bent.arms.R.elbowBendDeg, 90, 0.01);
  // 肘关节角是**相对上臂**的：上臂自己抬起不会算成屈肘
  const raisedStraight = buildUpperBodySkeleton(makeUb({ wristL: [0.05, 0.4] }));
  near(raisedStraight.arms.L.elbowBendDeg, 0, 0.01);
  // 退化骨（肘与腕重合）→ 关节角无意义，valid=false
  const degenerate = buildUpperBodySkeleton(makeUb({ elbowL: [0.35, 0.78], wristL: [0.35, 0.78] }));
  assert.equal(degenerate.arms.L.valid, false);
  near(degenerate.arms.L.elbowBendDeg, 0);
  // 坏输入 → 空骨架，不抛异常
  const broken = buildUpperBodySkeleton([1, 2, 3]);
  assert.equal(broken.points, null);
  assert.equal(broken.arms.L.valid, false);
});

await test("buildUpperBodySkeleton：躯干倾斜时手臂的躯干相对角不变（层级旋转不重复计数）", () => {
  // 肩中点 (0.55, 0.3)、髋中点 (0.45, 0.8) → 脊柱向画面右倾 ≈ 11.31°
  const shoulderL = [0.4, 0.3];
  const elbowL = [0.37058, 0.44709];
  const wristL = [0.34117, 0.59417]; // 左臂平行于脊柱方向下垂
  const skeleton = buildUpperBodySkeleton(
    makeUb({ shoulderL, shoulderR: [0.7, 0.3], elbowL, wristL, hipL: [0.34, 0.8], hipR: [0.56, 0.8] }),
  );
  near(skeleton.spine.leanDeg, (Math.atan2(0.1, 0.5) * 180) / Math.PI, 0.01);
  // 绝对画面角跟着躯干倾了 11.31°，但相对躯干仍然是 0 → 不会被误读为「手臂抬起」
  near(skeleton.arms.L.elevationDeg, (Math.atan2(0.1, 0.5) * 180) / Math.PI, 0.2);
  near(skeleton.arms.L.elevationTorsoDeg, 0, 0.2);
});

await test("mapBody：肘弯曲 → ParamArmLB/RB 两侧同号，伸直为 0", () => {
  const bent = mapBody(
    [0, 0],
    makeUb({
      elbowL: [0.35, 0.57],
      wristL: [0.26, 0.57],
      elbowR: [0.65, 0.57],
      wristR: [0.74, 0.57],
    }),
  );
  near(bent.params.ParamArmLB, 18); // 90/150*30
  near(bent.params.ParamArmRB, 18);
  near(bent.params.ParamHandL, 18); // 前臂水平 → 手部参数同向跟随
  near(bent.params.ParamHandR, 18);
  near(bent.params.ParamHandAngleL, 18); // Haru 的别名参数同值
  near(bent.metrics.elbowBendL, 90, 0.01);
  near(bent.metrics.elbowBendR, 90, 0.01);
  // 满量程与系数可调
  const scaled = mapBody(
    [0, 0],
    makeUb({ elbowL: [0.35, 0.57], wristL: [0.26, 0.57] }),
    { elbowAngleMax: 90, elbowScale: 10 },
  );
  near(scaled.params.ParamArmLB, 10);
  // 屈肘不该污染抬起角语义之外的参数
  near(bent.params.ParamBodyAngleZ, 0);
});

await test("mapBody：锁骨差动（肩线相对髋线）→ ParamShoulderL/R 反号对称", () => {
  // 右肩相对下沉 0.04（肩线 +7.59°、髋线 0°）→ 左肩抬起、右肩落下
  const shrug = mapBody([0, 0], makeUb({ shoulderL: [0.35, 0.38], shoulderR: [0.65, 0.42] }));
  near(shrug.metrics.clavicleRelTilt, (Math.atan2(0.04, 0.3) * 180) / Math.PI, 0.01);
  assert.ok(shrug.params.ParamShoulderL > 0, "右肩下沉 = 左肩抬起，ParamShoulderL 应为正");
  assert.ok(shrug.params.ParamShoulderR < 0);
  near(shrug.params.ParamShoulderL + shrug.params.ParamShoulderR, 0);
  near(shrug.params.ParamShoulderL, ((Math.atan2(0.04, 0.3) * 180) / Math.PI / 25) * 30, 0.01);
});

await test("mapBody：整体侧倾（肩髋同倾）不驱动肩参数（层级不重复计数）", () => {
  const tilt = (8 * Math.PI) / 180;
  const shoulderDy = 0.3 * Math.tan(tilt); // 肩半宽 0.15 → 两点各 ±dy/2
  const hipDy = 0.22 * Math.tan(tilt); // 髋半宽 0.11
  const leaned = mapBody(
    [0, 0],
    makeUb({
      shoulderL: [0.35, 0.4 - shoulderDy / 2],
      shoulderR: [0.65, 0.4 + shoulderDy / 2],
      hipL: [0.39, 0.86 - hipDy / 2],
      hipR: [0.61, 0.86 + hipDy / 2],
    }),
  );
  near(leaned.metrics.clavicleRelTilt, 0, 0.1);
  near(leaned.params.ParamShoulderL, 0, 0.5);
  near(leaned.params.ParamShoulderR, 0, 0.5);
});

await test("mapBody：肘 / 肩可见度不足 → 对应骨骼参数不输出（让客户端回默认）", () => {
  const low = mapBody(
    [0, 0],
    makeUb({
      visibilityL: 0.1,
      elbowL: [0.35, 0.57],
      wristL: [0.26, 0.57],
      shoulderL: [0.35, 0.38],
      shoulderR: [0.65, 0.42],
    }),
  );
  assert.equal("ParamArmLA" in low.params, false);
  assert.equal("ParamArmLB" in low.params, false);
  // 肩参数要双肩 + 双胯都可信：左肩可见度 0.1 → 整组不输出
  assert.equal("ParamShoulderL" in low.params, false);
  assert.equal("ParamShoulderR" in low.params, false);
  // 右侧不受影响
  assert.ok("ParamArmRB" in low.params);
  // 原始角度仍然可读（调试面板用）
  near(low.metrics.elbowBendL, 90, 0.01);
  near(low.metrics.clavicleRelTilt, (Math.atan2(0.04, 0.3) * 180) / Math.PI, 0.01);
});

await test("readUpperBodyTrack：正常帧 / ubOk=false / 缺字段 / ubT 过期 / 新 ubT 恢复", () => {
  const ub = makeUb();
  const first = readUpperBodyTrack({ type: "track", ub, ubOk: true, ubT: 1000, body: [3, -5] }, 0, null);
  assert.equal(first.ok, true);
  assert.equal(first.present, true);
  assert.equal(first.stale, false);
  near(first.ageMs, 0);
  near(first.torso[0], 3);
  near(first.torso[1], -5);

  // 同一个 ubT 一直重复（手机还在发面捕，但上半身停了）→ age 累积，1.5s 后过期
  const stale = readUpperBodyTrack({ ub, ubOk: true, ubT: 1000, body: [3, -5] }, BODY_STALE_MS + 100, first);
  assert.equal(stale.ok, false);
  assert.equal(stale.stale, true);
  near(stale.ageMs, BODY_STALE_MS + 100);

  // 新的 ubT 到达 → 立刻恢复
  const fresh = readUpperBodyTrack({ ub, ubOk: true, ubT: 2000, body: [0, 0] }, BODY_STALE_MS + 200, stale);
  assert.equal(fresh.ok, true);
  assert.equal(fresh.stale, false);
  near(fresh.ageMs, 0);

  // ubOk=false（手机端明确说这一帧没有结果）→ 有 ub 也不可用，torso 回到 0
  const notOk = readUpperBodyTrack({ ub, ubOk: false, ubT: 3000, body: [9, 9] }, BODY_STALE_MS + 300, fresh);
  assert.equal(notOk.ok, false);
  assert.equal(notOk.present, true);
  near(notOk.torso[0], 9); // body 字段仍然被解析（调试用），但 ok=false 不会驱动

  // 老手机：整组字段缺失 → present=false，points 保留上一帧（调试骨架画最后一帧）
  const missing = readUpperBodyTrack({ type: "track", bs: [], pose: [0, 0, 0] }, BODY_STALE_MS + 400, fresh);
  assert.equal(missing.ok, false);
  assert.equal(missing.present, false);
  assert.equal(missing.points.length, BODY_COUNT);
  // 缺少新帧 → age 从上一帧的到达时间继续累积（fresh 在 1700 到达，这里 now=1900）
  near(missing.ageMs, 200);

  // ub 长度不对 → present=true，但 ok=false
  const broken = readUpperBodyTrack({ ub: [0, 1, 2], ubOk: true, ubT: 4000, body: [1, 2] }, 2100, missing);
  assert.equal(broken.present, true);
  assert.equal(broken.ok, false);
});

await test("readUpperBodyTrack：没有 ubT 时按到达时间当作新鲜（stale 只依赖本地 age）", () => {
  const fresh = readUpperBodyTrack({ ub: makeUb(), ubOk: true }, 500, null);
  assert.equal(fresh.ok, true);
  assert.equal(fresh.t, null);
  const later = readUpperBodyTrack({ ub: makeUb(), ubOk: true }, 900, fresh);
  assert.equal(later.ok, true);
  near(later.ageMs, 0); // 每帧重新到达 → 一直新鲜
});

await test("smoothBodyParams：丢失后每帧向 0 前进 15%，衰减到 0 后交回 idle motion", () => {
  near(smoothBodyParams({ ParamArmLA: 30 }, null).ParamArmLA, 30 * (1 - BODY_DECAY_PER_FRAME));
  near(
    smoothBodyParams({ ParamBodyAngleZ: 10 }, null, { decay: BODY_DECAY_PER_FRAME }).ParamBodyAngleZ,
    8.5,
  );
  let current = { ParamArmLA: 30 };
  for (let i = 0; i < 100; i++) current = smoothBodyParams(current, null);
  assert.equal("ParamArmLA" in current, false);
  // 从没被驱动过的参数不输出（老手机 / 关闭上半身时行为与以前完全一致）
  assert.deepEqual(smoothBodyParams({}, null), {});
  // 有目标 → 直接取目标
  near(smoothBodyParams({ ParamArmLA: 3 }, { ParamArmLA: 12 }).ParamArmLA, 12);
  // snap：截图 / 自测要确定性，一步到位
  near(smoothBodyParams({ ParamArmLA: 30 }, { ParamArmLA: 7 }, { snap: true }).ParamArmLA, 7);
  assert.deepEqual(smoothBodyParams({ ParamArmLA: 30 }, null, { snap: true }), {});
});

await test("combineBodyAngleX：头部 + 躯干相加后 clamp 到 ±30（不互相覆盖）", () => {
  near(combineBodyAngleX(10, 25), BODY_ANGLE_LIMIT);
  near(combineBodyAngleX(-10, -25), -BODY_ANGLE_LIMIT);
  near(combineBodyAngleX(4, -7), -3);
  near(combineBodyAngleX(undefined, 5), 5);
  near(combineBodyAngleX(Number.NaN, 5), 5);
  near(combineBodyAngleX(0, Number.POSITIVE_INFINITY), 0);
});

console.log("[selftest] live2d.js · 上半身参数叠加（模型没有的参数跳过，属预期降级）");

/** 造一个不依赖 DOM/WebGL 的 stage：只测参数叠加逻辑。 */
function makeBodyStage(bodyOnlyIds) {
  const stage = new Live2DStage({ interpolate: false, smoothing: 1 });
  stage.paramIds = [...PARAM_IDS, ...bodyOnlyIds];
  stage.bodyOnlyParamIds = bodyOnlyIds.slice();
  stage.bodySupport = Object.fromEntries(
    [...PARAM_IDS, ...BODY_PARAM_IDS].map((id) => [id, stage.paramIds.includes(id)]),
  );
  return stage;
}

await test("Live2DStage：ParamBodyAngleX 与头部相加后 clamp（不互相覆盖）", () => {
  const stage = makeBodyStage(["ParamBodyAngleY", "ParamBodyAngleZ"]);
  stage.setTargetParams({ ParamBodyAngleX: 12 });
  stage.setBodyParams({ ParamBodyAngleX: 9 });
  near(stage._computeFrameValues(1000, true).ParamBodyAngleX, 21);

  stage.setTargetParams({ ParamBodyAngleX: 25 });
  stage.setBodyParams({ ParamBodyAngleX: 12 });
  near(stage._computeFrameValues(2000, true).ParamBodyAngleX, BODY_ANGLE_LIMIT);
});

await test("Live2DStage：模型没有 ParamArmLA 时绝不写；有参数时写入，释放后交回 idle", () => {
  const noArms = makeBodyStage([]);
  noArms.setTargetParams({ ParamBodyAngleX: 0 });
  noArms.setBodyParams({ ParamBodyAngleX: 3, ParamArmLA: 20 });
  const withoutArms = noArms._computeFrameValues(1000, true);
  assert.equal("ParamArmLA" in withoutArms, false);
  near(withoutArms.ParamBodyAngleX, 3);

  const withArms = makeBodyStage(["ParamArmLA"]);
  withArms.setTargetParams({ ParamBodyAngleX: 0 });
  withArms.setBodyParams({ ParamBodyAngleX: 3, ParamArmLA: 20 });
  near(withArms._computeFrameValues(1000, true).ParamArmLA, 20);

  withArms.setBodyParams({}); // 低可见度 / 丢失 → 不再驱动
  assert.equal("ParamArmLA" in withArms._computeFrameValues(2000, true), false);
});

await test("Live2DStage：上半身参数按 ~0.16s 低通（不直接跳变），immediate 落位", () => {
  const stage = makeBodyStage(["ParamArmLA"]);
  stage.setTargetParams({});
  stage.setBodyParams({ ParamArmLA: 10 });
  near(stage._computeFrameValues(1000, false).ParamArmLA, 10); // 首帧没有历史 → 直接落位
  stage.setBodyParams({ ParamArmLA: 20 });
  const eased = stage._computeFrameValues(1000 + 1000 / 60, false).ParamArmLA;
  const expected = 10 + (20 - 10) * (1 - Math.exp(-(1000 / 60) / 160));
  near(eased, expected, 1e-9);
  assert.ok(eased > 10 && eased < 20, "两帧之间应缓动，而不是直接跳到 20");
  stage.setBodyParams({ ParamArmLA: 20 });
  near(stage._computeFrameValues(2000, true).ParamArmLA, 20); // 截图 / 单步：立即落位
});

await test("toModelRange：契约 ±30 量程等比映射到模型实际量程（Haru ParamArmLA 是 [-1,1]）", () => {
  // Haru 的手臂参数 [-1,1]：27/30 → 0.9，不再被 clamp 到极值
  near(toModelRange(27, 30, { def: 0, min: -1, max: 1 }), 0.9, 1e-9);
  // 肘参数 [-1,5]：18/30 → 3（旧行为直接写 18 会被 clamp 到 5）
  near(toModelRange(18, 30, { def: 0, min: -1, max: 5 }), 3, 1e-9);
  // 负值往 min 侧映射；非零默认值为 0 点
  near(toModelRange(-30, 30, { def: 2, min: -3, max: 7 }), -3, 1e-9);
  near(toModelRange(30, 30, { def: 2, min: -3, max: 7 }), 7, 1e-9);
  near(toModelRange(0, 30, { def: 2, min: -3, max: 7 }), 2, 1e-9);
  // 超量程输入先 clamp 到 ±1；坏数据不抛异常
  near(toModelRange(90, 30, { def: 0, min: -1, max: 1 }), 1, 1e-9);
  near(toModelRange(Number.NaN, 30, { def: 0, min: -1, max: 1 }), 0, 1e-9);
});

await test("Live2DStage：有量程信息时按模型量程缩放写入，无量程时保持旧行为", () => {
  const stage = makeBodyStage(["ParamArmLA", "ParamArmLB"]);
  stage.bodyParamRanges = {
    ParamArmLA: { def: 0, min: -1, max: 1 },
    ParamArmLB: { def: 0, min: -1, max: 5 },
    ParamBodyAngleX: { def: 0, min: -10, max: 10 },
  };
  stage.setTargetParams({});
  stage.setBodyParams({ ParamArmLA: 27, ParamArmLB: 18 });
  const values = stage._computeFrameValues(1000, true);
  near(values.ParamArmLA, 0.9, 1e-9);
  near(values.ParamArmLB, 3, 1e-9);
  // ParamBodyAngleX：头部 12 + 躯干 9 = 21（±30 契约）→ 7（±10 量程）
  stage.setTargetParams({ ParamBodyAngleX: 12 });
  stage.setBodyParams({ ParamBodyAngleX: 9 });
  near(stage._computeFrameValues(2000, true).ParamBodyAngleX, 7, 1e-9);
  // 无量程信息（老 stub / 测试场景）：原样写
  const plain = makeBodyStage(["ParamArmLA"]);
  plain.setTargetParams({});
  plain.setBodyParams({ ParamArmLA: 27 });
  near(plain._computeFrameValues(1000, true).ParamArmLA, 27);
});

console.log("[selftest] tools/mock-body.js · mock 手机端的上半身合成数据");

await test("buildUpperBody：协议合法的 52 项 + [roll,yaw]，坐标/可见度都在范围内", () => {
  const sample = buildUpperBody(1.234);
  assert.equal(sample.ub.length, BODY_FLOATS);
  assert.equal(sample.ubOk, true);
  assert.equal(sample.body.length, 2);
  for (const value of sample.ub) {
    assert.ok(Number.isFinite(value), "ub 必须是有限数");
    assert.ok(value >= 0 && value <= 1, "x/y/visibility 应在 [0,1]：" + value);
  }
  const points = parseUpperBody(sample.ub);
  assert.ok(points, "mock ub 必须能解析成 13 点");
  assert.equal(points.length, BODY_COUNT);
});

await test("buildUpperBody：双手交替抬起 → 左/右臂角度周期性变化（mock 能驱动映射）", () => {
  const rest = buildUpperBody(0);
  const raisedLeft = buildUpperBody(1.3); // armPeriodSec=5.2 的 1/4 周期
  const restMapped = mapBody(rest.body, rest.ub);
  const raisedMapped = mapBody(raisedLeft.body, raisedLeft.ub);
  assert.ok(raisedMapped.params.ParamArmLA > restMapped.params.ParamArmLA + 20, "左臂应明显抬起");
  // 抬起时右手基本还在下面（相位错开 π/2）
  assert.ok(raisedMapped.params.ParamArmRA < raisedMapped.params.ParamArmLA);
});

await test("buildUpperBody：周期内会出现 ubOk=false + 全 0（验证客户端平滑回默认）", () => {
  const dropped = buildUpperBody(8.8); // dropPeriodSec=9、dropLengthSec=0.7 → 8.8s 处于丢失窗口
  assert.equal(dropped.ubOk, false);
  assert.ok(dropped.ub.every((value) => value === 0));
  assert.deepEqual(dropped.body, [0, 0]);
  // 关掉丢失窗口 + 静止姿态（neutral 场景）：稳定输出、没有随机性
  const neutral = buildUpperBody(3.7, {
    rollAmpDeg: 0,
    yawAmpDeg: 0,
    armLiftDeg: 0,
    elbowRestDeg: 0,
    elbowCurlDeg: 0,
    shrugAmpDeg: 0,
    dropPeriodSec: 0,
  });
  assert.equal(neutral.ubOk, true);
  near(neutral.body[0], 0);
  near(neutral.body[1], 0);
  // 手臂静止 12° → 12/150*30 ≈ 2.4（坐标按协议保留 4 位小数，允许一点舍入误差）
  near(mapBody(neutral.body, neutral.ub).params.ParamArmLA, 2.4, 0.01);
  near(mapBody(neutral.body, neutral.ub).params.ParamArmLB, 0, 0.01);
  near(mapBody(neutral.body, neutral.ub).params.ParamShoulderL, 0, 0.01);
});

await test("上半身链路（mock → readUpperBodyTrack → mapBody → smoothBodyParams）：参数跟随数据，丢失后 1s 内释放", () => {
  let prev = null;
  let current = {};
  let maxArmLA = 0;
  let maxArmLB = 0;
  let maxAbsShoulder = 0;
  let maxAbsZ = 0;
  for (let i = 0; i < 240; i++) {
    const nowMs = i * (1000 / 60);
    const sample = buildUpperBody(nowMs / 1000);
    const next = readUpperBodyTrack(
      { ub: sample.ub, ubOk: sample.ubOk, ubT: nowMs, body: sample.body },
      nowMs,
      prev,
    );
    const mapped = next.ok ? mapBody(next.torso, next.points) : null;
    current = smoothBodyParams(prev?.current ?? current, mapped ? mapped.params : null);
    for (const id of Object.keys(current)) {
      assert.ok(BODY_PARAM_IDS.includes(id), "输出不应包含未知参数：" + id);
    }
    maxArmLA = Math.max(maxArmLA, current.ParamArmLA ?? 0);
    maxArmLB = Math.max(maxArmLB, current.ParamArmLB ?? 0);
    maxAbsShoulder = Math.max(maxAbsShoulder, Math.abs(current.ParamShoulderL ?? 0));
    maxAbsZ = Math.max(maxAbsZ, Math.abs(current.ParamBodyAngleZ ?? 0));
    prev = { ...next, current };
  }
  assert.ok(maxArmLA > 20, "手臂参数应被 mock 数据驱动（max " + maxArmLA + "）");
  assert.ok(maxArmLB > 10, "肘参数应被 mock 数据驱动（max " + maxArmLB + "）");
  assert.ok(maxAbsShoulder > 5, "肩参数应被 mock 数据驱动（max " + maxAbsShoulder + "）");
  assert.ok(maxAbsZ > 1, "躯干侧倾应被 mock 数据驱动（max " + maxAbsZ + "）");

  // 手机端上半身结果丢失（ubOk=false 连续 60 帧 ≈ 1s）：参数单调衰减并最终释放
  let frame = 240;
  let lastArm = current.ParamArmLA ?? 0;
  for (let i = 0; i < 60; i++) {
    const nowMs = frame * (1000 / 60);
    const next = readUpperBodyTrack(
      { ub: new Array(BODY_FLOATS).fill(0), ubOk: false, ubT: frame, body: [0, 0] },
      nowMs,
      prev,
    );
    const mapped = next.ok ? mapBody(next.torso, next.points) : null;
    current = smoothBodyParams(prev.current, mapped ? mapped.params : null);
    if (typeof current.ParamArmLA === "number") {
      assert.ok(current.ParamArmLA <= lastArm, "丢失后手臂参数应单调衰减");
      lastArm = current.ParamArmLA;
    }
    prev = { ...next, current };
    frame += 1;
  }
  assert.equal("ParamArmLA" in current, false, "1s 后应把手臂参数交还给 idle motion");
});

const failed = results.filter((r) => !r.ok);
console.log("");
console.log(`[selftest] ${results.length - failed.length}/${results.length} 通过`);
if (failed.length) {
  console.error("[selftest] 失败项：");
  for (const item of failed) console.error("  - " + item.name + "：" + (item.error?.message ?? item.error));
  process.exit(1);
}
process.exit(0);
