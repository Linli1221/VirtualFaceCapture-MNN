import { PoseCalibrator, CALIBRATION_DURATION_MS, MIN_CALIBRATION_FRAMES } from "../renderer/pose-calibrator.js";
import { ExpressionCalibrator } from "../renderer/expression-calibrator.js";
import { BlendShapeExtractor } from "../renderer/blendshape-extractor.js";
import { makeNeutralFace } from "../tools/evals/golden-fixtures.mjs";

function assert(cond, msg) { if (!cond) throw new Error(msg); }
function assertClose(a, b, eps, msg) {
  if (!(Math.abs(a - b) <= eps)) throw new Error(`${msg}: ${a} != ${b}`);
}

export const tests = [
  {
    name: "PoseCalibrator：双门限（1000ms + 10 帧）缺一不可",
    fn() {
      const p = new PoseCalibrator();
      p.begin(0);
      // 只给 10 帧但时间不到 1000ms
      for (let i = 0; i < 10; i++) assert(p.add(1, 2, 3, i * 33) === false, "时间不足不应落地");
      assert(p.isCalibrated === false, "不应已校准");

      // 只给时间但帧数不足
      const p2 = new PoseCalibrator();
      p2.begin(0);
      for (let i = 0; i < 3; i++) assert(p2.add(1, 2, 3, i * 500) === false, "帧数不足不应落地");
      assert(p2.isCalibrated === false, "不应已校准");
    },
  },
  {
    name: "PoseCalibrator：baseline = 窗口均值，除数是实际帧数",
    fn() {
      const p = new PoseCalibrator();
      p.begin(0);
      const vals = [];
      for (let i = 0; i < 12; i++) vals.push([i, i * 2, i * 3]);
      let landed = false;
      for (let i = 0; i < 12; i++) landed = p.add(vals[i][0], vals[i][1], vals[i][2], i * 100) || landed;
      assert(landed || p.isCalibrated, "应已落地");
      // 落地那一帧为止的均值；这里 12 帧 × 100ms = 1200ms，在第 11 帧(i=10)就满足条件
      const n = 11;
      let sp = 0, sy = 0, sr = 0;
      for (let i = 0; i < n; i++) { sp += vals[i][0]; sy += vals[i][1]; sr += vals[i][2]; }
      assertClose(p.baseline[0], sp / n, 1e-9, "pitch 均值");
      assertClose(p.baseline[1], sy / n, 1e-9, "yaw 均值");
      assertClose(p.baseline[2], sr / n, 1e-9, "roll 均值");
    },
  },
  {
    name: "PoseCalibrator：relativePose = raw - baseline；未校准时 = raw",
    fn() {
      const p = new PoseCalibrator();
      const out = [0, 0, 0];
      p.relativePoseInto(out, 10, 20, 30);
      assertClose(out[0], 10, 1e-12, "未校准 pitch 透传");
      assertClose(out[1], 20, 1e-12, "未校准 yaw 透传");

      p.load({ baseline: { pitch: 1, yaw: 2, roll: 3 }, calibrated: true });
      p.relativePoseInto(out, 10, 20, 30);
      assertClose(out[0], 9, 1e-12, "pitch 差");
      assertClose(out[1], 18, 1e-12, "yaw 差");
      assertClose(out[2], 27, 1e-12, "roll 差");
    },
  },
  {
    name: "PoseCalibrator：serialize / load 往返一致",
    fn() {
      const p = new PoseCalibrator();
      p.load({ baseline: { pitch: -1.5, yaw: 2.25, roll: 0.5 }, calibrated: true, frames: 42 });
      const s = p.serialize();
      const q = new PoseCalibrator();
      q.load(s);
      assertClose(q.baseline[0], -1.5, 1e-12, "pitch");
      assertClose(q.baseline[1], 2.25, 1e-12, "yaw");
      assertClose(q.baseline[2], 0.5, 1e-12, "roll");
      assert(q.isCalibrated, "应保持校准态");
    },
  },
  {
    name: "PoseCalibrator：常量与 CommonData 一致",
    fn() {
      assert(CALIBRATION_DURATION_MS === 1000, "1000ms");
      assert(MIN_CALIBRATION_FRAMES === 10, "10 帧");
    },
  },
  {
    name: "ExpressionCalibrator：add() 不做任何 EAR/MAR/smile 累计（防双份基线）",
    fn() {
      const ex = new BlendShapeExtractor();
      const ec = new ExpressionCalibrator(ex);
      ec.begin(0);
      const before = ex.simpleEyeExtractor.imageLeftEyeCalibration.frameCount;
      const lm = makeNeutralFace();
      for (let i = 0; i < 20; i++) ec.add(lm, [0, 0, 0], i * 50);
      assert(ex.simpleEyeExtractor.imageLeftEyeCalibration.frameCount === before,
        "add() 绝不能推进 extractor 的校准累计器");
    },
  },
  {
    name: "ExpressionCalibrator：isCalibrating 直通 extractor 的合成口径（不含 advanced）",
    fn() {
      const ex = new BlendShapeExtractor();
      const ec = new ExpressionCalibrator(ex);
      assert(ec.isCalibrating === ex.isCalibrating, "应等于 extractor.isCalibrating");
      assert(ec.isCalibrating === true, "初始应为校准中");
    },
  },
  {
    name: "ExpressionCalibrator：advancedMode=false 时 finish() 不碰 Advanced 基线",
    fn() {
      const ex = new BlendShapeExtractor();
      const ec = new ExpressionCalibrator(ex);
      ec.begin(0);
      ec.add(makeNeutralFace(), [0, 0, 0], 0);
      ec.finish();
      assert(ex.advancedExtractor.isBaselineSet === false, "Advanced 不应被校准");
    },
  },
  {
    name: "ExpressionCalibrator：serialize / load 往返一致（阈值 + 微笑范围）",
    fn() {
      const ex = new BlendShapeExtractor();
      const ec = new ExpressionCalibrator(ex);
      ex.simpleEyeExtractor.imageLeftEyeCalibration.calibratedClosedRatio = 0.11;
      ex.simpleEyeExtractor.imageLeftEyeCalibration.calibratedOpenRatio = 0.22;
      ex.simpleEyeExtractor.imageLeftEyeCalibration.calibratedWideRatio = 0.30;
      ex.simpleMouthExtractor.mouthCalibration.calibratedClosedRatio = 0.12;
      ex.simpleSmileExtractor.calibration.observedSmileMax = 0.09;
      ex.simpleSmileExtractor.calibration.observedFrownMax = 0.07;
      const s = ec.serialize();

      const ex2 = new BlendShapeExtractor();
      const ec2 = new ExpressionCalibrator(ex2);
      ec2.load(s);
      assertClose(ex2.simpleEyeExtractor.imageLeftEyeCalibration.calibratedClosedRatio, 0.11, 1e-12, "closed");
      assertClose(ex2.simpleEyeExtractor.imageLeftEyeCalibration.calibratedOpenRatio, 0.22, 1e-12, "open");
      assertClose(ex2.simpleEyeExtractor.imageLeftEyeCalibration.calibratedWideRatio, 0.30, 1e-12, "wide");
      assertClose(ex2.simpleMouthExtractor.mouthCalibration.calibratedClosedRatio, 0.12, 1e-12, "mouth closed");
      assertClose(ex2.simpleSmileExtractor.calibration.observedSmileMax, 0.09, 1e-12, "smileMax");
      assertClose(ex2.simpleSmileExtractor.calibration.observedFrownMax, 0.07, 1e-12, "frownMax");
    },
  },
  {
    name: "ExpressionCalibrator：左右眼是两套独立校准数据（可不同帧落地）",
    fn() {
      const ex = new BlendShapeExtractor();
      const l = ex.simpleEyeExtractor.imageLeftEyeCalibration;
      const r = ex.simpleEyeExtractor.imageRightEyeCalibration;
      assert(l !== r, "必须是两个独立对象");
      l.calibratedOpenRatio = 0.31;
      assert(r.calibratedOpenRatio !== 0.31, "改左眼不应影响右眼");
    },
  },
  {
    name: "ExpressionCalibrator：progress 上报窗口进度",
    fn() {
      const ex = new BlendShapeExtractor();
      const ec = new ExpressionCalibrator(ex, { durationMs: 1000, minFrames: 10 });
      ec.begin(0);
      for (let i = 0; i < 5; i++) ec.add(makeNeutralFace(), [0, 0, 0], i * 50);
      const p = ec.progress(250);
      assert(p.frames === 5, `frames=${p.frames}`);
      assert(p.elapsedMs === 250, `elapsed=${p.elapsedMs}`);
      assert(p.remainingMs === 750, `remaining=${p.remainingMs}`);
    },
  },
];
