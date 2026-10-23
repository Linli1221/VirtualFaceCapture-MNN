#!/usr/bin/env node
/**
 * gen-ub-post-fixture.mjs — 生成「上半身后处理链」跨语言对拍夹具（任务 M2）。
 *
 *   node tools/verify/gen-ub-post-fixture.mjs
 *
 * 产物（单一事实来源，两端测试都读它）：
 *   MediapipeUpperBody/src/test/resources/ub_post_fixture.txt
 *
 * 夹具记录一串**原始** ub 样本（13×[x,y,z,visibility]，按协议 §5.3 顺序、
 * 4 位小数 = 线上 appendDecimal4 的精度），以及每帧经过
 *
 *     One-Euro(1.2/0.02) 平滑点 → torso(平滑点) → One-Euro(0.6/0.01) → TorsoBaseline
 *
 * 之后的期望输出（躯干角 + 抽样点的平滑后坐标）。
 * Kotlin 端（UpperBodyPostEquivalenceTest）与 PC 端（ub-post-test.mjs）各自跑
 * 同一份样本，误差 ≤ 0.5°（躯干角）/ ≤ 0.002（归一化坐标）即视为等价。
 *
 * ⚠️ 本文件里的 Ref* 是**独立参考实现**：逐行转写 Kotlin
 *    （OneEuroFilter.kt / UpperBodySmoother.kt / UpperBodyGeometry.kt / TorsoBaseline.kt
 *    / UpperBodyPoseDetector.buildFrame），刻意不 import mapping.js，
 *    避免「实现与自测共用同一份代码」的假阳性。
 *
 * 行格式（空白分隔，# 开头为注释）：
 *   vfc-ub-post-fixture v1
 *   PARAM <名字> <数值>
 *   FRAME <tMs> <52 个浮点> <expRoll> <expYaw> <shL平滑x y z> <wrR平滑x y z>
 *   LOSS <tMs>
 *   END
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..', '..');
const OUT = path.join(ROOT, 'MediapipeUpperBody', 'src', 'test', 'resources', 'ub_post_fixture.txt');

// ================================================================ 参考实现（转写 Kotlin）

const TWO_PI = 2 * Math.PI;
const MIN_DT_SECONDS = 1 / 1000;
const MAX_DT_SECONDS = 0.5;
const MIN_REFERENCE_SPAN = 0.02;
const MIN_TORSO_VISIBILITY = 0.3;
const RELIABLE_VISIBILITY = 0.5;
const MIN_TRACKING_POINTS = 7;

/** Kotlin OneEuroFilter 的逐行转写（Float 语义用 double 近似，容差吸收差异）。 */
class RefOneEuro {
  constructor(minCutoff, beta, derivativeCutoff = 1.0) {
    this.minCutoff = minCutoff;
    this.beta = beta;
    this.derivativeCutoff = derivativeCutoff;
    this.lastValue = NaN;
    this.lastDerivative = 0;
  }

  reset() {
    this.lastValue = NaN;
    this.lastDerivative = 0;
  }

  filter(value, dtSeconds) {
    if (!Number.isFinite(value)) return Number.isFinite(this.lastValue) ? this.lastValue : 0;
    if (!Number.isFinite(this.lastValue)) {
      this.lastValue = value;
      this.lastDerivative = 0;
      return value;
    }
    const dt = Number.isFinite(dtSeconds) && dtSeconds > 0 ? Math.min(dtSeconds, MAX_DT_SECONDS) : MIN_DT_SECONDS;
    const rawDerivative = (value - this.lastValue) / dt;
    const derivativeAlpha = this.alpha(this.derivativeCutoff, dt);
    this.lastDerivative += derivativeAlpha * (rawDerivative - this.lastDerivative);
    const cutoff = this.minCutoff + this.beta * Math.abs(this.lastDerivative);
    const valueAlpha = this.alpha(cutoff, dt);
    const filtered = this.lastValue + valueAlpha * (value - this.lastValue);
    this.lastValue = filtered;
    return filtered;
  }

  alpha(cutoff, dt) {
    if (cutoff <= 0) return 1;
    const tau = 1 / (TWO_PI * cutoff);
    return 1 / (1 + tau / dt);
  }
}

const POINT_COUNT = 13;
const TORSO_PAIRS = [
  [5, 6], // shoulder_left / shoulder_right
  [7, 8], // elbow_left / elbow_right
  [11, 12], // hip_left / hip_right
];

function isZeroPoint(p) {
  return p.x === 0 && p.y === 0 && p.z === 0 && p.v === 0;
}

function isTracking(points) {
  let reliable = 0;
  for (const p of points) if (p.v > RELIABLE_VISIBILITY) reliable++;
  return reliable >= MIN_TRACKING_POINTS;
}

/** Kotlin UpperBodyGeometry.torso 的逐行转写（含 0.02 分母下限与肩→肘→胯退化链）。 */
function refTorso(points) {
  for (const [li, ri] of TORSO_PAIRS) {
    const l = points[li];
    const r = points[ri];
    if (!Number.isFinite(l.v) || !Number.isFinite(r.v)) continue;
    if (Math.min(l.v, r.v) < MIN_TORSO_VISIBILITY) continue;
    if (![l.x, l.y, l.z, r.x, r.y, r.z].every(Number.isFinite)) continue;
    const dx = r.x - l.x;
    const dy = r.y - l.y;
    const dz = r.z - l.z;
    const horizontal = Math.max(Math.abs(dx), MIN_REFERENCE_SPAN);
    const roll = (Math.atan2(dy, horizontal) * 180) / Math.PI;
    const yaw = (Math.atan2(-dz, horizontal) * 180) / Math.PI;
    if (!Number.isFinite(roll) || !Number.isFinite(yaw)) continue;
    return [roll, yaw];
  }
  return [0, 0];
}

/** Kotlin TorsoBaseline 的逐行转写。 */
class RefBaseline {
  constructor(calibrationFrames = 10) {
    this.calibrationFrames = calibrationFrames > 0 ? calibrationFrames : 1;
    this.reset();
  }

  reset() {
    this.collected = 0;
    this.sumRoll = 0;
    this.sumYaw = 0;
    this.baselineRoll = 0;
    this.baselineYaw = 0;
  }

  get isCalibrated() {
    return this.collected >= this.calibrationFrames;
  }

  apply(angles) {
    const [roll, yaw] = angles;
    if (!Number.isFinite(roll) || !Number.isFinite(yaw)) return [0, 0];
    if (this.isCalibrated) return [roll - this.baselineRoll, yaw - this.baselineYaw];
    this.collected += 1;
    this.sumRoll += roll;
    this.sumYaw += yaw;
    const meanRoll = this.sumRoll / this.collected;
    const meanYaw = this.sumYaw / this.collected;
    if (this.collected >= this.calibrationFrames) {
      this.baselineRoll = meanRoll;
      this.baselineYaw = meanYaw;
    }
    const weight = Math.min(1, this.collected / this.calibrationFrames);
    return [(roll - meanRoll) * weight, (yaw - meanYaw) * weight];
  }
}

/** Kotlin UpperBodyPoseDetector.buildFrame 平滑段的逐行转写。 */
class RefPipeline {
  constructor(params) {
    this.p = params;
    this.pointFilters = [];
    for (let i = 0; i < POINT_COUNT; i++) {
      this.pointFilters.push([
        new RefOneEuro(params.pointMinCutoff, params.pointBeta, params.pointDerivativeCutoff),
        new RefOneEuro(params.pointMinCutoff, params.pointBeta, params.pointDerivativeCutoff),
        new RefOneEuro(params.pointMinCutoff, params.pointBeta, params.pointDerivativeCutoff),
      ]);
    }
    this.torsoFilters = [
      new RefOneEuro(params.torsoMinCutoff, params.torsoBeta, params.torsoDerivativeCutoff),
      new RefOneEuro(params.torsoMinCutoff, params.torsoBeta, params.torsoDerivativeCutoff),
    ];
    this.baseline = new RefBaseline(params.calibrationFrames);
    this.lastT = null;
  }

  /** tracking=false / ubOk=false：清平滑器与 dt 锚点（基线不动，与 Kotlin 一致）。 */
  onTrackingLost() {
    for (const triple of this.pointFilters) for (const f of triple) f.reset();
    for (const f of this.torsoFilters) f.reset();
    this.lastT = null;
  }

  smoothPoints(points, dt) {
    return points.map((p, i) => {
      if (isZeroPoint(p)) return { ...p };
      const [fx, fy, fz] = this.pointFilters[i];
      return {
        x: fx.filter(p.x, dt),
        y: fy.filter(p.y, dt),
        z: fz.filter(p.z, dt),
        v: p.v,
      };
    });
  }

  process(tMs, points) {
    if (!isTracking(points)) {
      this.onTrackingLost();
      return { tracking: false, body: [0, 0], smoothed: null };
    }
    let dt;
    if (this.lastT != null) {
      dt = Math.min(Math.max(tMs - this.lastT, 1), this.p.maxPipelineGapMs) / 1000;
    } else {
      dt = this.p.defaultDtSeconds;
    }
    this.lastT = tMs;

    const smoothed = this.smoothPoints(points, dt);
    const rawTorso = refTorso(smoothed);
    const smoothTorso = [
      this.torsoFilters[0].filter(rawTorso[0], dt),
      this.torsoFilters[1].filter(rawTorso[1], dt),
    ];
    const body = this.baseline.apply(smoothTorso);
    return { tracking: true, body, smoothed };
  }
}

// ================================================================ 样本流生成（确定性）

/** 数值取线上精度：TrackingProtocol.appendDecimal4 的 4 位小数。 */
function round4(v) {
  return Math.round(v * 10000) / 10000;
}

function lcg(seed) {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

const BASE_POINTS = [
  { x: 0.5, y: 0.3, v: 0.97 }, // nose
  { x: 0.46, y: 0.28, v: 0.96 }, // eye_left
  { x: 0.54, y: 0.28, v: 0.96 }, // eye_right
  { x: 0.42, y: 0.29, v: 0.93 }, // ear_left
  { x: 0.58, y: 0.29, v: 0.93 }, // ear_right
  { x: 0.4, y: 0.45, v: 0.95 }, // shoulder_left
  { x: 0.6, y: 0.45, v: 0.95 }, // shoulder_right
  { x: 0.35, y: 0.58, v: 0.88 }, // elbow_left
  { x: 0.65, y: 0.58, v: 0.88 }, // elbow_right
  { x: 0.33, y: 0.7, v: 0.84 }, // wrist_left
  { x: 0.67, y: 0.7, v: 0.84 }, // wrist_right
  { x: 0.44, y: 0.65, v: 0.9 }, // hip_left
  { x: 0.56, y: 0.65, v: 0.9 }, // hip_right
];

/**
 * 一段确定性的「人动起来」样本：
 *  - 00~11 帧：校准段，轻微漂移（验证基线 10 帧渐入，不从 0 突跳）；
 *  - 12~27 帧：roll / yaw 大幅摆动（验证动态响应）；
 *  - 28~33 帧：两肩在画面中几乎重合（dx ≈ 0.004 < 0.02 下限）+ 噪声（验证不出假角度）；
 *  - 34~39 帧：恢复正常；
 *  - LOSS ×3：tracking 丢失（验证平滑器重置、基线保留）；
 *  - 40~45 帧：恢复 + 其中一帧 wrist_left 置 ZERO（验证坏点透传不拖拽）。
 */
function buildFrames() {
  const rand = lcg(20261024);
  const noise = (amp) => (rand() * 2 - 1) * amp;
  const frames = [];
  let t = 1000;

  const emit = (i, mutate) => {
    const pts = BASE_POINTS.map((b) => ({ x: b.x, y: b.y, z: -0.02 + noise(0.004), v: b.v - rand() * 0.04 }));
    // 平滑轨迹：roll 摆动 + 缓慢漂移；yaw 由 z 差驱动
    const rollDrive = 2 + 9 * Math.sin(i * 0.32) + 3 * Math.sin(i * 0.11);
    const yawDrive = 5 * Math.sin(i * 0.23 + 0.7);
    pts[5].y += -rollDrive * 0.0025; // shoulder_left
    pts[6].y += rollDrive * 0.0025; // shoulder_right（下沉为正）
    pts[5].z += -yawDrive * 0.004;
    pts[6].z += yawDrive * 0.004;
    // 其余点跟着小幅动 + 噪声
    for (const p of pts) {
      p.x += noise(0.0008);
      p.y += noise(0.0008);
      p.z += noise(0.0015);
    }
    // 手臂小幅摆动（供点链平滑对拍）
    pts[9].y += 0.02 * Math.sin(i * 0.4);
    pts[10].y += 0.02 * Math.sin(i * 0.4 + 1.2);
    if (mutate) mutate(pts);
    frames.push({ kind: 'frame', t, points: pts.map((p) => ({ x: round4(p.x), y: round4(p.y), z: round4(p.z), v: round4(p.v) })) });
    t += 66;
  };

  for (let i = 0; i < 12; i++) emit(i); // 校准段
  for (let i = 12; i < 28; i++) emit(i); // 大幅摆动
  for (let i = 28; i < 34; i++) {
    // 两肩几乎重合：dx = 0.004，水平基准走 0.02 下限
    emit(i, (pts) => {
      pts[5].x = round4(0.5 - 0.002 + noise(0.0006));
      pts[6].x = round4(0.5 + 0.002 + noise(0.0006));
      pts[5].y = round4(0.45 + noise(0.0012));
      pts[6].y = round4(0.45 + noise(0.0012));
      pts[5].z = round4(-0.02 + noise(0.004));
      pts[6].z = round4(-0.02 + noise(0.004));
    });
  }
  for (let i = 34; i < 40; i++) emit(i); // 恢复
  for (let k = 0; k < 3; k++) {
    frames.push({ kind: 'loss', t });
    t += 66;
  }
  for (let i = 40; i < 46; i++) {
    emit(i, (pts) => {
      if (i === 42) {
        pts[9] = { x: 0, y: 0, z: 0, v: 0 }; // wrist_left 坏点 → 透传不参与平滑
      }
    });
  }
  return frames;
}

// ================================================================ 生成

function fmt(v) {
  // 期望值 6 位小数；-0 归一
  const r = Math.round(v * 1e6) / 1e6;
  return String(r === 0 ? 0 : r);
}

function main() {
  const params = {
    pointMinCutoff: 1.2,
    pointBeta: 0.02,
    pointDerivativeCutoff: 1.0,
    torsoMinCutoff: 0.6,
    torsoBeta: 0.01,
    torsoDerivativeCutoff: 1.0,
    calibrationFrames: 10,
    // 用 6 位小数的字面量而不是 1/15：夹具里的 PARAM 与期望值必须出自同一常数，
    // 两端测试直接采用 PARAM 值，才能做到「同一份输入 → 逐位同参」的对拍。
    defaultDtSeconds: 0.066667,
    maxPipelineGapMs: 500,
  };
  const pipeline = new RefPipeline(params);
  const frames = buildFrames();

  const lines = [];
  lines.push('vfc-ub-post-fixture v1');
  lines.push('# 上半身后处理链跨语言对拍夹具（任务 M2）。由 tools/verify/gen-ub-post-fixture.mjs 生成，勿手改。');
  lines.push('# FRAME <tMs> <ub 52> <expRoll> <expYaw> <shoulder_left 平滑 x y z> <wrist_right 平滑 x y z>');
  for (const [k, v] of Object.entries(params)) lines.push(`PARAM ${k} ${fmt(v)}`);

  let frameCount = 0;
  for (const f of frames) {
    if (f.kind === 'loss') {
      pipeline.onTrackingLost();
      lines.push(`LOSS ${f.t}`);
      continue;
    }
    const flat = f.points.flatMap((p) => [p.x, p.y, p.z, p.v]);
    const out = pipeline.process(f.t, f.points);
    const shL = out.smoothed ? out.smoothed[5] : { x: 0, y: 0, z: 0 };
    const wrR = out.smoothed ? out.smoothed[10] : { x: 0, y: 0, z: 0 };
    const tail = out.tracking
      ? [out.body[0], out.body[1], shL.x, shL.y, shL.z, wrR.x, wrR.y, wrR.z]
      : [0, 0, shL.x, shL.y, shL.z, wrR.x, wrR.y, wrR.z];
    lines.push(`FRAME ${f.t} ${flat.map(fmt).join(' ')} ${tail.map(fmt).join(' ')}`);
    frameCount++;
  }
  lines.push('END');

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, lines.join('\n') + '\n', 'utf8');
  console.log(`已生成 ${OUT}`);
  console.log(`  有效帧 ${frameCount}，事件 ${frames.length - frameCount} 条 LOSS`);
}

main();
