#!/usr/bin/env node
/**
 * ub-post-test.mjs — 任务 M2 验收测试：上半身后处理链（One-Euro / 躯干角 / TorsoBaseline）
 * 的 PC 实现（windows-client/renderer/mapping.js）与跨语言夹具对拍 + 行为验收。
 *
 *   node tools/verify/ub-post-test.mjs
 *
 * 覆盖：
 *   1. 夹具对拍：同一份 ub 原始样本，PC 输出的 body 与独立参考实现（= Kotlin 原实现的
 *      逐行转写）误差 ≤ 0.05°（验收线 0.5° 的十倍严），抽样平滑坐标差 ≤ 0.001；
 *   2. 起播 10 帧内不从 0 突跳到基线（TorsoBaseline 渐入）；
 *   3. 两肩画面重合（dx < 0.02）不出几十度假角度（MIN_REFERENCE_SPAN）；
 *   4. 三层回退不变：1500ms 过期 / ×0.85 按帧衰减 / 衰减到 0 从输出删除（交还 idle）；
 *   5. 基线持久化 round-trip + 手动重置重新渐入；按角色 profile 的 key 由 app.js 拼，
 *      这里验证 serialize/deserialize 语义；
 *   6. 未协商（老报文）路径行为不变：readUpperBodyTrack / updateBodyStateFromTrack
 *      不引入新语义（raw=false，body 原样直通）。
 *
 * 退出码：0 全部通过，1 有失败。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  BODY_DECAY_PER_FRAME,
  BODY_POST_DEFAULTS,
  OneEuroFilter,
  TorsoBaseline,
  UpperBodyPostProcessor,
  computeTorsoAngles,
  createBodyState,
  isUpperBodyTracking,
  mapBody,
  readUpperBodyTrack,
  smoothBodyParams,
  updateBodyStateFromTrack,
} from '../../windows-client/renderer/mapping.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..', '..');
const FIXTURE = path.join(ROOT, 'MediapipeUpperBody', 'src', 'test', 'resources', 'ub_post_fixture.txt');

const results = [];
async function test(name, fn) {
  try {
    await fn();
    results.push({ name, ok: true });
    console.log(`  ✓ ${name}`);
  } catch (error) {
    results.push({ name, ok: false, error });
    console.log(`  ✗ ${name}\n    ${error?.message ?? error}`);
  }
}

const near = (actual, expected, tolerance, what = '') =>
  assert.ok(
    Math.abs(actual - expected) <= tolerance,
    `${what} 期望 ~${expected}，实际 ${actual}（容差 ${tolerance}）`,
  );

// ---------------------------------------------------------------- 夹具解析

function parseFixture(file) {
  const params = {};
  const events = [];
  for (const rawLine of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#') || line === 'vfc-ub-post-fixture v1' || line === 'END') continue;
    const parts = line.split(/\s+/);
    const kind = parts[0];
    if (kind === 'PARAM') {
      params[parts[1]] = Number(parts[2]);
    } else if (kind === 'LOSS') {
      events.push({ kind: 'loss', t: Number(parts[1]) });
    } else if (kind === 'FRAME') {
      const nums = parts.slice(2).map(Number);
      assert.ok(nums.length === 52 + 8, `FRAME 数字个数不对：${nums.length}`);
      events.push({
        kind: 'frame',
        t: Number(parts[1]),
        ub: nums.slice(0, 52),
        expBody: [nums[52], nums[53]],
        expShL: [nums[54], nums[55], nums[56]],
        expWrR: [nums[57], nums[58], nums[59]],
      });
    }
  }
  return { params, events };
}

function fixturePipelineOptions(params) {
  return {
    minCutoff: params.pointMinCutoff,
    beta: params.pointBeta,
    pointDerivativeCutoff: params.pointDerivativeCutoff,
    torsoMinCutoff: params.torsoMinCutoff,
    torsoBeta: params.torsoBeta,
    torsoDerivativeCutoff: params.torsoDerivativeCutoff,
    calibrationFrames: params.calibrationFrames,
    defaultDtSeconds: params.defaultDtSeconds,
    maxPipelineGapMs: params.maxPipelineGapMs,
  };
}

const { params, events } = parseFixture(FIXTURE);
const frames = events.filter((e) => e.kind === 'frame');
const frameIndexOf = new Map(frames.map((f, i) => [f, i]));

// ---------------------------------------------------------------- 1. 夹具对拍

await test('夹具对拍：PC 的 body 与独立参考实现逐帧误差 ≤ 0.05°', () => {
  const pipeline = new UpperBodyPostProcessor(fixturePipelineOptions(params));
  for (const e of events) {
    if (e.kind === 'loss') {
      pipeline.resetSmoothers();
      continue;
    }
    const out = pipeline.process(e.ub, e.t);
    assert.ok(out.tracking, `t=${e.t} 应当 tracking`);
    near(out.torso[0], e.expBody[0], 0.05, `t=${e.t} roll`);
    near(out.torso[1], e.expBody[1], 0.05, `t=${e.t} yaw`);
    const shL = out.points[5];
    const wrR = out.points[10];
    near(shL.x, e.expShL[0], 0.001, `t=${e.t} shL.x`);
    near(shL.y, e.expShL[1], 0.001, `t=${e.t} shL.y`);
    near(shL.z, e.expShL[2], 0.001, `t=${e.t} shL.z`);
    near(wrR.x, e.expWrR[0], 0.001, `t=${e.t} wrR.x`);
    near(wrR.y, e.expWrR[1], 0.001, `t=${e.t} wrR.y`);
    near(wrR.z, e.expWrR[2], 0.001, `t=${e.t} wrR.z`);
  }
});

// ---------------------------------------------------------------- 2. 起播渐入

await test('起播 10 帧内 body 从 0 连续渐入，不突跳到基线', () => {
  const pipeline = new UpperBodyPostProcessor(fixturePipelineOptions(params));
  const first10 = frames.slice(0, 10).map((e) => {
    const out = pipeline.process(e.ub, e.t);
    return out.torso;
  });
  assert.deepEqual(first10[0], [0, 0], '首帧应为 [0,0]');
  for (let i = 1; i < first10.length; i++) {
    for (let axis = 0; axis < 2; axis++) {
      const step = Math.abs(first10[i][axis] - first10[i - 1][axis]);
      assert.ok(step <= 2, `第 ${i + 1} 帧轴 ${axis} 跳变 ${step.toFixed(3)}° > 2°`);
    }
    const magnitude = Math.max(Math.abs(first10[i][0]), Math.abs(first10[i][1]));
    assert.ok(magnitude <= 15, `第 ${i + 1} 帧 |body| = ${magnitude.toFixed(3)}° 过大（疑似突跳）`);
  }
});

// ---------------------------------------------------------------- 3. 肩重合

await test('两肩画面重合（dx < 0.02）不出几十度假角度', () => {
  // 精确公式抽查：horizontal 走 0.02 下限。z 只进 atan2（相对深度，非米制/角度）。
  // 噪声取真实量级（±0.002）：若没有 0.02 下限，dx=0.002 会把 ±0.6° 的肩线噪声放大成几十度。
  const coincident = Array.from({ length: 13 }, () => ({ x: 0.5, y: 0.45, z: 0, v: 0.9 }));
  coincident[5] = { x: 0.499, y: 0.451, z: 0.002, v: 0.9 };
  coincident[6] = { x: 0.501, y: 0.449, z: -0.002, v: 0.9 };
  const [roll, yaw] = computeTorsoAngles(coincident);
  assert.ok(Math.abs(roll) < 15, `roll=${roll.toFixed(2)}° 应被 0.02 下限兜住`);
  assert.ok(Math.abs(yaw) < 15, `yaw=${yaw.toFixed(2)}° 应被 0.02 下限兜住`);
  near(roll, (Math.atan2(-0.002, 0.02) * 180) / Math.PI, 1e-9, 'roll 公式');
  near(yaw, (Math.atan2(0.004, 0.02) * 180) / Math.PI, 1e-9, 'yaw 公式');

  // 夹具里的肩重合段（有效帧下标 28..33）
  const pipeline = new UpperBodyPostProcessor(fixturePipelineOptions(params));
  for (const e of events) {
    if (e.kind === 'loss') {
      pipeline.resetSmoothers();
      continue;
    }
    const out = pipeline.process(e.ub, e.t);
    const idx = frameIndexOf.get(e);
    if (idx >= 28 && idx <= 33) {
      assert.ok(Math.abs(out.torso[0]) <= 20, `重合段第 ${idx} 帧 roll=${out.torso[0].toFixed(2)}°`);
      assert.ok(Math.abs(out.torso[1]) <= 20, `重合段第 ${idx} 帧 yaw=${out.torso[1].toFixed(2)}°`);
    }
  }
});

await test('computeTorsoAngles：公式与符号约定（右肩下沉为正 / 本人左转为正）', () => {
  const pts = Array.from({ length: 13 }, () => ({ x: 0.5, y: 0.45, z: 0, v: 0.9 }));
  pts[5] = { x: 0.4, y: 0.45, z: 0.1, v: 0.9 };
  pts[6] = { x: 0.6, y: 0.55, z: -0.1, v: 0.9 };
  const [roll, yaw] = computeTorsoAngles(pts);
  near(roll, (Math.atan2(0.1, 0.2) * 180) / Math.PI, 1e-9, 'roll = atan2(dy, 0.2)');
  near(yaw, (Math.atan2(0.2, 0.2) * 180) / Math.PI, 1e-9, 'yaw = atan2(-dz, 0.2)');
  assert.ok(roll > 0 && yaw > 0);

  // 肩不可见 → 退化到肘线
  pts[5].v = 0.1;
  pts[6].v = 0.1;
  pts[7] = { x: 0.4, y: 0.6, z: 0, v: 0.9 };
  pts[8] = { x: 0.6, y: 0.5, z: 0, v: 0.9 };
  const [rollElbow] = computeTorsoAngles(pts);
  near(rollElbow, (Math.atan2(-0.1, 0.2) * 180) / Math.PI, 1e-9, '肘线 roll');
});

await test('isUpperBodyTracking：可见度 > 0.5 至少 7 个点', () => {
  const pts = Array.from({ length: 13 }, () => ({ x: 0.5, y: 0.5, z: 0, v: 0.6 }));
  assert.equal(isUpperBodyTracking(pts), true);
  pts[0].v = 0.4;
  pts[1].v = 0.4;
  pts[2].v = 0.4;
  pts[3].v = 0.4;
  pts[4].v = 0.4;
  pts[5].v = 0.4;
  pts[6].v = 0.4; // 6 个 0.6 → 不足 7
  assert.equal(isUpperBodyTracking(pts), false);
});

// ---------------------------------------------------------------- 4. 三层回退

await test('三层回退不变：×0.85 按帧衰减 → 衰减到 0 从输出删除（交还 idle motion）', () => {
  let current = { ParamArmLA: 20, ParamBodyAngleX: -10 };
  const seen = [];
  for (let i = 0; i < 60; i++) {
    current = smoothBodyParams(current, null, { decay: BODY_DECAY_PER_FRAME });
    seen.push({ ...current });
  }
  // 第一帧：20 × 0.85 = 17（×0.85 按帧衰减，不突然跳）——现有行为逐值不变
  near(seen[0].ParamArmLA, 17, 1e-9, '首帧衰减');
  // 20 → <0.01 需要 ~46 帧（0.85^n），随后从输出删除（键消失 = 交还 idle motion）
  assert.ok(!('ParamArmLA' in seen[seen.length - 1]), '最终应从输出删除');
  assert.ok(!('ParamBodyAngleX' in seen[seen.length - 1]), '最终应从输出删除');
  // 从未驱动的参数不输出
  const fresh = smoothBodyParams({}, null, {});
  assert.deepEqual(fresh, {});
});

await test('三层回退不变：1500ms 过期（BODY_STALE_MS）→ ok=false', () => {
  const flat = [];
  for (let i = 0; i < 13; i++) flat.push(0.5, 0.5, 0, 0.9);
  const track = { ub: flat, ubOk: true, ubT: 1000, body: [1, 2] };
  const first = readUpperBodyTrack(track, 1000, null);
  assert.equal(first.ok, true);
  const stale = readUpperBodyTrack(track, 3000, first); // 同一个 ubT：age 涨到 2000ms
  assert.equal(stale.ok, false);
  assert.equal(stale.stale, true);
});

// ---------------------------------------------------------------- 5. 基线持久化

await test('基线持久化 round-trip + 手动重置重新渐入（按角色 profile 持久化用）', () => {
  const a = new UpperBodyPostProcessor(fixturePipelineOptions(params));
  const b = new UpperBodyPostProcessor(fixturePipelineOptions(params));
  const head = frames.slice(0, 12);
  let outA = null;
  for (const e of head) outA = a.process(e.ub, e.t);
  const saved = JSON.parse(JSON.stringify(a.serializeBaseline()));
  assert.equal(b.loadBaseline(saved), true);
  // 恢复的只有**基线**（平滑器状态不持久化）：两边都冷启动平滑器后，
  // 同样的输入必须逐值一致 —— 验证「退出重开 / 换角色回来」基线不丢、不重采。
  a.resetSmoothers();
  b.resetSmoothers();
  for (const e of frames.slice(12, 20)) {
    const oa = a.process(e.ub, e.t);
    const ob = b.process(e.ub, e.t);
    near(ob.torso[0], oa.torso[0], 1e-9, '持久化恢复 roll');
    near(ob.torso[1], oa.torso[1], 1e-9, '持久化恢复 yaw');
    near(ob.points[5].x, oa.points[5].x, 1e-9, '持久化恢复平滑点');
  }
  assert.ok(outA != null);

  // 手动重置：重新渐入，首帧回到 0
  a.resetBaseline();
  const first = a.process(frames[0].ub, frames[0].t);
  assert.deepEqual(first.torso, [0, 0], '重置后首帧应为 [0,0]');

  // 坏快照不炸、返回 false
  assert.equal(b.loadBaseline({ version: 2 }), false);
  assert.equal(b.loadBaseline('not json'), false);
});

// ---------------------------------------------------------------- 6. 老路径不变

await test('未协商：老报文 body 语义原样直通（raw=false，无新字段）', () => {
  const flat = [];
  for (let i = 0; i < 13; i++) flat.push(0.5 + i * 0.01, 0.5, 0, 0.9);
  const track = { ub: flat, ubOk: true, ubT: 1000, body: [3.5, -2.25] };
  const parsed = readUpperBodyTrack(track, 1000, null);
  assert.equal(parsed.raw, false);
  assert.equal(parsed.phoneRawTorso, null);
  assert.deepEqual(parsed.torso, [3.5, -2.25]);

  // updateBodyStateFromTrack 无 pipeline 时与 mapBody + smoothBodyParams 直连一致
  const state = updateBodyStateFromTrack(track, 1000, createBodyState(), {});
  const direct = mapBody([3.5, -2.25], parsed.points);
  const directSmooth = smoothBodyParams({}, direct.params, { decay: BODY_DECAY_PER_FRAME });
  assert.deepEqual(state.current, directSmooth);
});

await test('ubRaw 报文：raw=true、phoneRawTorso 取自 ubRawBody，管线接管平滑/基线', () => {
  const flat = [];
  for (let i = 0; i < 13; i++) flat.push(0.5 + i * 0.01, 0.5, 0, 0.9);
  const track = { ub: flat, ubOk: true, ubT: 1000, body: [3.5, -2.25], ubRaw: true, ubRawBody: [3.5, -2.25] };
  const parsed = readUpperBodyTrack(track, 1000, null);
  assert.equal(parsed.raw, true);
  assert.deepEqual(parsed.phoneRawTorso, [3.5, -2.25]);

  const pipeline = new UpperBodyPostProcessor(fixturePipelineOptions(params));
  let state = createBodyState();
  state = updateBodyStateFromTrack(track, 1000, state, { pipeline });
  assert.equal(state.ok, true);
  // 首帧基线渐入：躯干参数来自相对角（≈0），不出现原始 3.5° 直通
  const direct = mapBody([0, 0], parsed.points);
  assert.deepEqual(state.current, smoothBodyParams({}, direct.params, { decay: BODY_DECAY_PER_FRAME }));

  // 丢失帧：平滑器重置、参数按帧衰减
  const lost = { ub: new Array(52).fill(0), ubOk: false, ubT: 1100, body: [0, 0], ubRaw: true, ubRawBody: [0, 0] };
  const next = updateBodyStateFromTrack(lost, 1100, state, { pipeline });
  assert.equal(next.ok, false);
  assert.ok(next.current.ParamBodyAngleZ === undefined || Math.abs(next.current.ParamBodyAngleZ) < 0.5);
});

await test('OneEuroFilter：透传首帧、dt 钳制（1ms~0.5s）、非有限输入回落上一值', () => {
  const f = new OneEuroFilter(1.2, 0.02, 1.0);
  assert.equal(f.filter(1, 0.066), 1, '首帧透传');
  const stepped = f.filter(2, 0.066);
  assert.ok(stepped > 1 && stepped < 2, '一阶低通应介于两值之间');
  const f2 = new OneEuroFilter(1.2, 0.02, 1.0);
  f2.filter(1, 10); // dt 超上限 → 0.5s，权重变大
  const big = f2.filter(2, 10);
  const f3 = new OneEuroFilter(1.2, 0.02, 1.0);
  f3.filter(1, 0.01);
  const small = f3.filter(2, 0.01);
  assert.ok(big >= small, 'dt 被截到 0.5s 时 alpha 更大');
  near(f3.filter(NaN, 0.01), f3.lastValue, 0, 'NaN 输入回落上一值');
});

// ---------------------------------------------------------------- 汇总

const failed = results.filter((r) => !r.ok);
console.log(`\nub-post-test：${results.length - failed.length}/${results.length} 通过`);
if (failed.length > 0) process.exitCode = 1;
