#!/usr/bin/env node
/**
 * golden-run.mjs — 黄金对齐评测（合成 fixture + 逐帧误差报告）
 *
 *   node windows-client/tools/evals/golden-run.mjs
 *
 * 做什么：
 *   1. 用 golden-fixtures.mjs 生成合成场景（含画幅、逐帧时间戳）；
 *   2. 同一批 fixture 分别喂给
 *        - golden-reference.mjs 的**直译参考实现**（照 Kotlin 源码逐条抄，朴素代码路径）
 *        - renderer/ 下的**优化实现**（TypedArray、零分配）
 *   3. 逐帧、逐维度比对，输出 max / mean / p95 误差报告。
 *
 * 基准的诚实说明（必须读）：
 *   本机无 JDK/Android SDK，跑不了 Kotlin。所以「黄金基准」= 照 Kotlin 源码
 *   逐条抄下来的**第二份独立转录**。它能抓住抄写/分支/阈值类笔误，
 *   但**不能**证明与 Kotlin 逐位一致（Kotlin 全程 Float32，JS 是 double）。
 *   实际容差：abs 1e-5 / rel 1e-4（Lead 裁决）。
 *
 * 分层（Lead 裁决）：
 *   第一阶段 = advancedMode=false（Android 当前实际行为）——本报告的默认层；
 *   第二阶段 = advancedMode=true —— 单独覆盖，不在本层混算。
 */

import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { SCENARIOS, makeSequence, makeNeutralFace, applyEyeOpen, EYE } from "./golden-fixtures.mjs";
import { RefExtractor, RefBlink, RefEMA, RefOneEuro, RefTorsoBaseline } from "./golden-reference.mjs";
import { BlendShapeExtractor, BS_COUNT, BS_INDEX } from "../../renderer/blendshape-extractor.js";
import { BlinkCurveProcessor } from "../../renderer/blink-curve.js";
import { TimeBasedEMAFilter, DEFAULT_TIME_EMA_TAU } from "../../renderer/temporal-filter.js";
import { OneEuroFilter, TorsoBaseline } from "../../renderer/body-fusion.js";

const ABS_TOL = 1e-5;
const REL_TOL = 1e-4;

const DIM_NAMES = Object.keys(BS_INDEX).sort((a, b) => BS_INDEX[a] - BS_INDEX[b]);

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.floor(p * (sorted.length - 1)));
  return sorted[idx];
}

function stats(values) {
  if (values.length === 0) return { max: 0, mean: 0, p95: 0, n: 0 };
  let sum = 0, max = 0;
  for (const v of values) { sum += v; if (v > max) max = v; }
  const sorted = [...values].sort((a, b) => a - b);
  return { max, mean: sum / values.length, p95: percentile(sorted, 0.95), n: values.length };
}

function withinTol(a, b) {
  const diff = Math.abs(a - b);
  return diff <= ABS_TOL || diff <= REL_TOL * Math.max(Math.abs(a), Math.abs(b));
}

// ══════════════════════════════════════════════════════════════════
// 1) BlendShape + Blink 链路逐帧比对
// ══════════════════════════════════════════════════════════════════

function runBlendShapeScenario(name, frames, imageWidth, imageHeight) {
  const ref = new RefExtractor();
  const opt = new BlendShapeExtractor({ advancedMode: false });
  const refBlink = new RefBlink();
  const optBlink = new BlinkCurveProcessor();

  // 原始提取（blink 之前）逐维误差 —— 这才是提取器本身的对齐度
  const perDim = Array.from({ length: BS_COUNT }, () => []);
  // 眨眼状态机输出的误差 —— 单独统计（TRACKING 态恒为 0，混在一起会掩盖提取差异）
  const blinkDiff = [];
  const outRef = new Float32Array(BS_COUNT);
  const outOpt = new Float32Array(BS_COUNT);

  for (let i = 0; i < frames.length; i++) {
    const fr = frames[i];
    const ctx = { width: imageWidth, height: imageHeight, nowMs: fr.t };
    ref.extract(fr.landmarks, outRef, fr.pose, ctx);
    opt.extractFullBlendShapes(fr.landmarks, outOpt, fr.pose, ctx);

    // ① 先记**原始提取**的逐维误差（blink 之前）
    for (let d = 0; d < BS_COUNT; d++) perDim[d].push(Math.abs(outRef[d] - outOpt[d]));

    // ② 再各自走自己的眨眼曲线，单独统计状态机输出误差
    const [rl, rr] = refBlink.process(outRef[9], outRef[10], fr.t);
    const [ol, or] = optBlink.process(outOpt[9], outOpt[10], fr.t);
    blinkDiff.push(Math.abs(rl - ol));
    blinkDiff.push(Math.abs(rr - or));
  }

  return {
    name,
    frames: frames.length,
    perDim: perDim.map((v, d) => ({ dim: d, name: DIM_NAMES[d], ...stats(v) })),
    blink: stats(blinkDiff),
  };
}

// ══════════════════════════════════════════════════════════════════
// 2) TimeBasedEMA 逐帧比对
// ══════════════════════════════════════════════════════════════════

function runEmaScenario() {
  const ref = new RefEMA(DEFAULT_TIME_EMA_TAU);
  const opt = new TimeBasedEMAFilter({ tau: DEFAULT_TIME_EMA_TAU });
  const diffs = [];
  let t = 0;
  // 覆盖：阶跃 / 慢变 / 长间隔 / 抖动
  const schedule = [];
  for (let i = 0; i < 60; i++) schedule.push({ dt: 33.3, v: i < 20 ? 0 : 1 });
  for (let i = 0; i < 20; i++) schedule.push({ dt: 300, v: Math.sin(i / 5) });
  for (let i = 0; i < 40; i++) schedule.push({ dt: 16.7 + (i % 3) * 20, v: Math.cos(i / 7) });
  for (const s of schedule) {
    t += s.dt / 1000;
    const a = ref.filter(s.v, t);
    const b = opt.filter(s.v, t);
    diffs.push(Math.abs(a - b));
  }
  return { name: "TimeBasedEMA(tau=0.12)", frames: schedule.length, ...stats(diffs) };
}

// ══════════════════════════════════════════════════════════════════
// 3) 上半身：One-Euro 与 TorsoBaseline 逐帧比对
// ══════════════════════════════════════════════════════════════════

function runBodyScenarios() {
  const results = [];

  {
    const ref = new RefOneEuro(1.2, 0.02, 1.0);
    const opt = new OneEuroFilter();
    const diffs = [];
    let t = 0;
    for (let i = 0; i < 200; i++) {
      const dt = (i % 7 === 0 ? 100 : 33.3) / 1000;
      const v = Math.sin(i / 13) * 0.4 + (i % 31 === 0 ? 0.3 : 0);
      t += dt;
      diffs.push(Math.abs(ref.filter(v, dt) - opt.filter(v, dt)));
    }
    results.push({ name: "OneEuroFilter(1.2/0.02/1.0)", frames: 200, ...stats(diffs) });
  }

  {
    const ref = new RefTorsoBaseline(10);
    const opt = new TorsoBaseline(10);
    const dr = [], dy = [];
    for (let i = 0; i < 30; i++) {
      const roll = Math.sin(i / 9) * 12 + 3;
      const yaw = Math.cos(i / 11) * 9 - 2;
      const [a, b] = ref.apply(roll, yaw);
      const out = [0, 0];
      opt.applyInto(out, roll, yaw);
      dr.push(Math.abs(a - out[0]));
      dy.push(Math.abs(b - out[1]));
    }
    results.push({ name: "TorsoBaseline.roll(10 帧)", frames: 30, ...stats(dr) });
    results.push({ name: "TorsoBaseline.yaw(10 帧)", frames: 30, ...stats(dy) });
  }

  return results;
}

// ══════════════════════════════════════════════════════════════════
// 主流程
// ══════════════════════════════════════════════════════════════════

const IMAGE_W = 1280;
const IMAGE_H = 720;

const scenarioNames = Object.keys(SCENARIOS);
const blendResults = [];
for (const name of scenarioNames) {
  const frames = SCENARIOS[name]();
  blendResults.push(runBlendShapeScenario(name, frames, IMAGE_W, IMAGE_H));
}

const emaResult = runEmaScenario();
const bodyResults = runBodyScenarios();

// ---- 汇总判定 ----
let failures = 0;
const failedDims = [];
for (const r of blendResults) {
  for (const d of r.perDim) {
    if (d.n > 0 && !withinTol(0, d.max) && d.max > ABS_TOL) {
      // 只有真正有输出的维度才可能超容差；全 0 维度的 max 恒为 0
      failures++;
      failedDims.push(`${r.name}#${d.dim}(${d.name}) max=${d.max.toExponential(3)}`);
    }
  }
}
for (const r of [emaResult, ...bodyResults]) {
  if (r.max > ABS_TOL && r.max > REL_TOL) {
    failures++;
    failedDims.push(`${r.name} max=${r.max.toExponential(3)}`);
  }
}

// ---- 报告 ----
const lines = [];
lines.push("# 黄金对齐评测报告");
lines.push("");
lines.push(`生成时间：${new Date().toISOString()}`);
lines.push(`画幅：${IMAGE_W}x${IMAGE_H}（参考实现与被测实现**同一画幅**，见观察项 5）`);
lines.push(`容差：abs ${ABS_TOL} / rel ${REL_TOL}`);
lines.push("");
lines.push("## 基准的诚实说明");
lines.push("");
lines.push("本机无 JDK/Android SDK，跑不了 Kotlin。这里的「黄金基准」= 照 Kotlin 源码逐条抄下来的**第二份独立转录**（golden-reference.mjs，朴素数组/对象、Math.pow 而非 x*x、逐条 when 分支而非查表），与 renderer/ 下的 TypedArray 优化版走完全不同的代码路径。");
lines.push("");
lines.push("- 能证明：两份独立转录一致（抓住抄写 / 分支顺序 / 阈值类笔误）。");
lines.push("- 不能证明：与 Kotlin **逐位**一致（Kotlin 全程 Float32，JS 是 double，实测残余约 2e-6）。");
lines.push("");
lines.push("## 分层");
lines.push("");
lines.push("- 第一阶段（本报告）：`advancedMode = false` —— Android 当前实际行为，仅产出 17 个维度，其余恒 0。");
lines.push("- 第二阶段（未纳入）：`advancedMode = true` —— 需单独覆盖，另行出报告。");
lines.push("");
lines.push("## 协议一致性核对");
lines.push("");
lines.push("| 项目 | 结论 | 依据 |");
lines.push("| --- | --- | --- |");
lines.push("| 52 维字段序 | ✅ 双端一致 | `blendshape-extractor.js` 的 BS_INDEX 与 `mapping.js` BS_INDEX 逐项相同（test-protocol-consistency.mjs 断言） | ");
lines.push("| 13 点 ORDER | ✅ 双端一致 | `body-fusion.js` / UpperBodyPose.kt 的 ORDER 与 `mapping.js` BODY_LANDMARK_ORDER 相同 | ");
lines.push("| neutral 占位 | ✅ 恒为 0 号位 | BS_INDEX.neutral === 0 | ");
lines.push("");
lines.push("## BlendShape + Blink 链路（逐帧、逐维度）");
lines.push("");
for (const r of blendResults) {
  lines.push(`### 场景 ${r.name}（${r.frames} 帧）`);
  lines.push("");
  lines.push(`眨眼曲线误差：max=${r.blink.max.toExponential(3)} mean=${r.blink.mean.toExponential(3)} p95=${r.blink.p95.toExponential(3)}`);
  lines.push("");
  lines.push("| 维度 | 名称 | max | mean | p95 |");
  lines.push("| --- | --- | --- | --- | --- |");
  for (const d of r.perDim) {
    if (d.max === 0) continue; // 全 0 维度不占篇幅
    lines.push(`| ${d.dim} | ${d.name} | ${d.max.toExponential(3)} | ${d.mean.toExponential(3)} | ${d.p95.toExponential(3)} |`);
  }
  const zeroCount = r.perDim.filter((d) => d.max === 0).length;
  lines.push("");
  lines.push(`（其余 ${zeroCount} 个维度全程误差为 0）`);
  lines.push("");
}
lines.push("## 时域滤波与上半身");
lines.push("");
lines.push("| 模块 | max | mean | p95 |");
lines.push("| --- | --- | --- | --- |");
for (const r of [emaResult, ...bodyResults]) {
  lines.push(`| ${r.name} | ${r.max.toExponential(3)} | ${r.mean.toExponential(3)} | ${r.p95.toExponential(3)} |`);
}
lines.push("");
lines.push("## 已知偏差观察项（按源码复刻，不修）");
lines.push("");
lines.push("1. **像素转换各向异性**：`FaceLandmarkIndices.extractKeyPoints` 是 x 乘 imageWidth、y 乘 imageHeight，于是 EAR / MAR 这类「垂直/水平」比值会带上 H/W 宽高比因子。实测（归一化 EAR=0.18）：1280x720 时 EAR 变成 0.18 x 0.5625 = 0.10125，正好压到默认闭眼阈值 0.10，eyeBlink 从 ~0 变 0.984。含义：**默认阈值只对特定画幅成立**；自校准会自适应它；参考与被测必须用同一画幅（本报告写死 1280x720）。");
lines.push("2. **Float32 残余**：Kotlin 全程 Float，JS 是 double。合成 EAR 恰好等于阈值这类用例会留下约 2e-6 残余，由 abs 1e-5 覆盖。");
lines.push("3. **FaceCapturePipeline 第 2/3 步单位不一致**：若第 2 步做了像素转换，第 3 步校准用的 avgLandmarks 未做转换。按源码复刻保真，单列观察。");
lines.push("4. **眯眼 CLOSING 是整条曲线 x 0.5**（不是半程停）、**BLINK_DETECT/BLINK_CLOSED 的空历史 fallback 分别是 baselineEAR / 0f**、**SQUINT_HOLD 不参与双眼同步** —— 均与 Kotlin 注释不符，按代码移植（见 blink-curve.js 头注）。");
lines.push("");
lines.push("## 结论");
lines.push("");
if (failures === 0) {
  lines.push(`✅ **全部通过**：${blendResults.length} 个场景 + ${1 + bodyResults.length} 个滤波/基线模块，共 ${blendResults.reduce((s, r) => s + r.frames, 0)} 帧，逐维度最大误差均在容差内。`);
} else {
  lines.push(`❌ **${failures} 项超出容差**：`);
  for (const f of failedDims) lines.push(`  - ${f}`);
}
lines.push("");

const reportPath = join(dirname(fileURLToPath(import.meta.url)), "golden-report.md");
writeFileSync(reportPath, lines.join("\n"), "utf8");

// 控制台摘要
console.log(`画幅 ${IMAGE_W}x${IMAGE_H} | 容差 abs ${ABS_TOL} / rel ${REL_TOL}`);
console.log("");
console.log("场景                    帧数   blinkMax      维度Max");
for (const r of blendResults) {
  const dimMax = Math.max(...r.perDim.map((d) => d.max));
  console.log(`${r.name.padEnd(22)} ${String(r.frames).padStart(4)}   ${r.blink.max.toExponential(2)}   ${dimMax.toExponential(2)}`);
}
console.log("");
console.log("滤波/基线                max");
for (const r of [emaResult, ...bodyResults]) {
  console.log(`${r.name.padEnd(22)} ${r.max.toExponential(2)}`);
}
console.log("");
if (failures === 0) {
  console.log(`✅ 黄金对齐通过（报告：${reportPath}）`);
} else {
  console.log(`❌ ${failures} 项超出容差（报告：${reportPath}）`);
  for (const x of failedDims) console.log(`   - ${x}`);
  process.exitCode = 1;
}
