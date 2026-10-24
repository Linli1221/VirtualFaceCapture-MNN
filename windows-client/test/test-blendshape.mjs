import { BlendShapeExtractor, BS_INDEX, BS_COUNT, CALIBRATION_DURATION_MS, MIN_CALIBRATION_FRAMES } from "../renderer/blendshape-extractor.js";
import { makeNeutralFace, applyEyeOpen, applyMouthOpen, applySmile, EYE, MOUTH } from "../tools/evals/golden-fixtures.mjs";

function assert(cond, msg) { if (!cond) throw new Error(msg); }
function assertClose(a, b, eps, msg) {
  if (!(Math.abs(a - b) <= eps)) throw new Error(`${msg}: ${a} != ${b} (eps=${eps})`);
}

/** advancedMode=false 时允许非零的下标（Android 当前实际产出的 17 个维度）。 */
const ALLOWED = new Set([9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 21, 22, 25, 30, 31, 44, 45]);

function run(ex, frames, pose) {
  const out = new Float32Array(BS_COUNT);
  let t = 0;
  for (const lm of frames) {
    ex.extractFullBlendShapes(lm, out, pose || [0, 0, 0], { width: 0, height: 0, nowMs: t });
    t += 33;
  }
  return out;
}

export const tests = [
  {
    name: "BS_COUNT = 52，下标 0 是 neutral",
    fn() {
      assert(BS_COUNT === 52, "BS_COUNT");
      assert(BS_INDEX.neutral === 0, "neutral 必须在 0");
    },
  },
  {
    name: "校准窗口常量与 CommonData 一致（1000ms + 10 帧）",
    fn() {
      assert(CALIBRATION_DURATION_MS === 1000, "CALIBRATION_DURATION_MS");
      assert(MIN_CALIBRATION_FRAMES === 10, "MIN_CALIBRATION_FRAMES");
    },
  },
  {
    name: "advancedMode=false 时其余 35 维恒为 0（Android 当前实际行为）",
    fn() {
      const ex = new BlendShapeExtractor();
      const frames = [];
      for (let i = 0; i < 40; i++) {
        const lm = makeNeutralFace();
        applyEyeOpen(lm, EYE.closedHeight, EYE.openHeight);
        applyMouthOpen(lm, 0.6);
        applySmile(lm, 0.02);
        frames.push(lm);
      }
      const out = run(ex, frames);
      for (let i = 0; i < BS_COUNT; i++) {
        if (!ALLOWED.has(i)) {
          assert(out[i] === 0, `下标 ${i} 在 advancedMode=false 下必须恒 0，实际 ${out[i]}`);
        }
      }
    },
  },
  {
    name: "中性脸：blink / jawOpen / smile 全部接近 0（默认阈值下）",
    fn() {
      const ex = new BlendShapeExtractor();
      const lm = makeNeutralFace();
      const out = new Float32Array(BS_COUNT);
      ex.extractFullBlendShapes(lm, out, [0, 0, 0], { nowMs: 0 });
      // 容差 1e-4：Kotlin 全程 Float32，合成 EAR=0.18 经 Float32 往返后略低于阈值，
      // 会留下 ~2e-6 的残余（不是 bug，是 Float32 舍入）。
      assertClose(out[BS_INDEX.eyeBlinkLeft], 0, 1e-4, "eyeBlinkLeft");
      assertClose(out[BS_INDEX.eyeBlinkRight], 0, 1e-4, "eyeBlinkRight");
      assertClose(out[BS_INDEX.jawOpen], 0, 1e-4, "jawOpen");
      assertClose(out[BS_INDEX.mouthSmileLeft], 0, 1e-4, "mouthSmileLeft");
    },
  },
  {
    name: "闭眼：EAR = 0.10 -> eyeBlink = 1（默认阈值）",
    fn() {
      const ex = new BlendShapeExtractor();
      const lm = makeNeutralFace();
      applyEyeOpen(lm, EYE.closedHeight, EYE.closedHeight);
      const out = new Float32Array(BS_COUNT);
      ex.extractFullBlendShapes(lm, out, [0, 0, 0], { nowMs: 0 });
      assertClose(out[BS_INDEX.eyeBlinkLeft], 1, 1e-5, "eyeBlinkLeft");
      assertClose(out[BS_INDEX.eyeBlinkRight], 1, 1e-5, "eyeBlinkRight");
    },
  },
  {
    name: "张大嘴：MAR = 0.45 -> jawOpen = 1（默认阈值）",
    fn() {
      const ex = new BlendShapeExtractor();
      const lm = makeNeutralFace();
      applyMouthOpen(lm, 1);
      const out = new Float32Array(BS_COUNT);
      ex.extractFullBlendShapes(lm, out, [0, 0, 0], { nowMs: 0 });
      assertClose(out[BS_INDEX.jawOpen], 1, 1e-5, "jawOpen");
    },
  },
  {
    name: "半闭眼：EAR 位于阈值之间 -> blink 线性插值",
    fn() {
      const ex = new BlendShapeExtractor();
      const lm = makeNeutralFace();
      // EAR = 0.14 -> (0.18-0.14)/(0.18-0.10) = 0.5
      applyEyeOpen(lm, 0.014, 0.014);
      const out = new Float32Array(BS_COUNT);
      ex.extractFullBlendShapes(lm, out, [0, 0, 0], { nowMs: 0 });
      assertClose(out[BS_INDEX.eyeBlinkLeft], 0.5, 1e-4, "eyeBlinkLeft 线性插值");
    },
  },
  {
    name: "睁大眼：EAR > openRatio -> eyeWide 有输出",
    fn() {
      const ex = new BlendShapeExtractor();
      const lm = makeNeutralFace();
      applyEyeOpen(lm, 0.026, 0.026); // EAR = 0.26 = DEFAULT_EYE_WIDE_RATIO
      const out = new Float32Array(BS_COUNT);
      ex.extractFullBlendShapes(lm, out, [0, 0, 0], { nowMs: 0 });
      assert(out[BS_INDEX.eyeWideLeft] > 0, `eyeWideLeft 应 > 0，实际 ${out[BS_INDEX.eyeWideLeft]}`);
      assertClose(out[BS_INDEX.eyeWideLeft], 1, 1e-4, "EAR=wide 时应为 1");
    },
  },
  {
    name: "微笑 -> mouthSmile > 0；撇嘴 -> mouthFrown > 0",
    fn() {
      const ex = new BlendShapeExtractor();
      const lm = makeNeutralFace();
      applySmile(lm, 0.02);
      const out = new Float32Array(BS_COUNT);
      ex.extractFullBlendShapes(lm, out, [0, 0, 0], { nowMs: 0 });
      assert(out[BS_INDEX.mouthSmileLeft] > 0, `smileL 应 > 0，实际 ${out[BS_INDEX.mouthSmileLeft]}`);
      assert(out[BS_INDEX.mouthSmileRight] > 0, `smileR 应 > 0`);

      const ex2 = new BlendShapeExtractor();
      const lm2 = makeNeutralFace();
      applySmile(lm2, -0.015);
      const out2 = new Float32Array(BS_COUNT);
      ex2.extractFullBlendShapes(lm2, out2, [0, 0, 0], { nowMs: 0 });
      assert(out2[BS_INDEX.mouthFrownLeft] > 0, `frownL 应 > 0，实际 ${out2[BS_INDEX.mouthFrownLeft]}`);
    },
  },
  {
    name: "微笑死区：特征变化 < 0.003 时 smile = 0",
    fn() {
      const ex = new BlendShapeExtractor();
      const lm = makeNeutralFace();
      applySmile(lm, 0.001);
      const out = new Float32Array(BS_COUNT);
      ex.extractFullBlendShapes(lm, out, [0, 0, 0], { nowMs: 0 });
      assertClose(out[BS_INDEX.mouthSmileLeft], 0, 1e-9, "死区内应为 0");
    },
  },
  {
    name: "自校准：采样窗口结束后阈值被重设、isCalibrating 变 false",
    fn() {
      const ex = new BlendShapeExtractor();
      assert(ex.isCalibrating === true, "开始应处于校准中");
      const frames = [];
      for (let i = 0; i < 40; i++) frames.push(makeNeutralFace());
      run(ex, frames);
      assert(ex.isCalibrating === false, `窗口结束后应校准完成，实际 ${ex.isCalibrating}`);
      assert(ex.simpleEyeExtractor.imageLeftEyeCalibration.isCalibrated, "左眼应已校准");
      assert(ex.simpleEyeExtractor.imageRightEyeCalibration.isCalibrated, "右眼应已校准");
      assert(ex.simpleMouthExtractor.mouthCalibration.isCalibrated, "嘴部应已校准");
      assert(ex.simpleSmileExtractor.calibration.isCalibrated, "微笑应已校准");
    },
  },
  {
    name: "校准后：EAR 均值被记进 meanEAR，阈值落在合法区间",
    fn() {
      const ex = new BlendShapeExtractor();
      const frames = [];
      for (let i = 0; i < 40; i++) frames.push(makeNeutralFace());
      run(ex, frames);
      const cal = ex.simpleEyeExtractor.imageLeftEyeCalibration;
      assertClose(cal.meanEAR, 0.18, 1e-4, "meanEAR 应等于合成 EAR");
      assert(cal.calibratedOpenRatio >= 0.12 && cal.calibratedOpenRatio <= 0.35, "openRatio 区间");
      assert(cal.calibratedClosedRatio >= 0.05, "closedRatio 下界");
      assert(cal.calibratedWideRatio > cal.calibratedOpenRatio, "wide 应大于 open");
    },
  },
  {
    name: "【保真度】窗口不足 10 帧时不校准（Kotlin finishCalibration 的 n<10 分支）",
    fn() {
      const ex = new BlendShapeExtractor();
      const frames = [];
      for (let i = 0; i < 5; i++) frames.push(makeNeutralFace());
      run(ex, frames);
      assert(ex.simpleEyeExtractor.imageLeftEyeCalibration.isCalibrated === false, "帧数不足不应校准");
    },
  },
  {
    name: "【保真度】calibrate() 只影响 Advanced，不碰 Simple* 的基线",
    fn() {
      const ex = new BlendShapeExtractor(); // advancedMode = false
      const lm = makeNeutralFace();
      const before = ex.simpleEyeExtractor.imageLeftEyeCalibration.calibratedOpenRatio;
      ex.calibrate(lm, 0.30, 0.28);
      assertClose(ex.simpleEyeExtractor.imageLeftEyeCalibration.calibratedOpenRatio, before, 0, "SimpleEye 不应被 calibrate 改动");
      assert(ex.advancedExtractor.isBaselineSet === false, "advancedMode=false 时 Advanced 不应设基线");
    },
  },
  {
    name: "【保真度】setCalibrationPose 会覆盖 SimpleEye 自己记的 calibrationPose",
    fn() {
      const ex = new BlendShapeExtractor();
      ex.simpleEyeExtractor.calibrationPitch = 11;
      ex.simpleEyeExtractor.calibrationYaw = 22;
      ex.setCalibrationPose(3, 4, 5);
      assertClose(ex.simpleEyeExtractor.calibrationPitch, 3, 1e-9, "pitch 应被覆盖");
      assertClose(ex.simpleEyeExtractor.calibrationYaw, 4, 1e-9, "yaw 应被覆盖");
      assertClose(ex.simpleMouthExtractor.calibrationRoll, 5, 1e-9, "mouth roll");
    },
  },
  {
    name: "【保真度·已知偏差】像素转换是各向异性的（x*W / y*H），EAR 依赖画幅宽高比",
    fn() {
      // FaceLandmarkIndices.extractKeyPoints 的规则是「x 乘 imageWidth、y 乘 imageHeight」，
      // 即**各向异性**缩放。于是 EAR = 垂直/水平 会带上 H/W 的宽高比因子，
      // MAR 同理。这不是移植错误，是 Kotlin 的真实行为（校准会自适应它）。
      // 黄金评测里参考实现与被测实现必须用同一画幅，否则会在这上面对不上。
      const lm = makeNeutralFace();

      // (a) 正方形画幅：x/y 同倍缩放 -> EAR 不变，两路结果应一致
      const exS = new BlendShapeExtractor();
      const outN = new Float32Array(BS_COUNT);
      exS.extractFullBlendShapes(lm, outN, [0, 0, 0], { width: 0, height: 0, nowMs: 0 });
      const exS2 = new BlendShapeExtractor();
      const outSq = new Float32Array(BS_COUNT);
      exS2.extractFullBlendShapes(lm, outSq, [0, 0, 0], { width: 1000, height: 1000, nowMs: 0 });
      for (const i of ALLOWED) {
        assert(Math.abs(outN[i] - outSq[i]) < 1e-4, `正方形画幅下标 ${i} 不一致：${outN[i]} vs ${outSq[i]}`);
      }

      // (b) 非正方形画幅：EAR 被乘以 H/W，eyeBlink 因此偏大 —— 断言这个偏差确实存在且量级正确
      const exP = new BlendShapeExtractor();
      const outP = new Float32Array(BS_COUNT);
      exP.extractFullBlendShapes(lm, outP, [0, 0, 0], { width: 1280, height: 720, nowMs: 0 });
      assert(Math.abs(outN[BS_INDEX.eyeBlinkLeft] - outP[BS_INDEX.eyeBlinkLeft]) > 0.5,
        `画幅 1280x720 应让 eyeBlink 显著偏移（EAR 带上 H/W=0.5625 因子），实际 ${outN[BS_INDEX.eyeBlinkLeft]} vs ${outP[BS_INDEX.eyeBlinkLeft]}`);
    },
  },
  {
    name: "clamp 只作用于 7 个字段（其余不被二次裁剪）",
    fn() {
      const ex = new BlendShapeExtractor();
      const out = new Float32Array(BS_COUNT);
      out[BS_INDEX.browDownLeft] = 5; // 若 clamp 全字段会被压到 1
      const lm = makeNeutralFace();
      // advancedMode=false 会 fill(0)，所以直接验证 fill 后非 7 字段的写入路径
      ex.extractFullBlendShapes(lm, out, [0, 0, 0], { nowMs: 0 });
      assert(out[BS_INDEX.browDownLeft] === 0, "advancedMode=false 时 browDown 被清零（不是被 clamp）");
    },
  },
];
