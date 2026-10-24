/**
 * blendshape-extractor.js — 478 点几何 -> ARKit-52 BlendShape
 *
 * 移植来源（逐公式对照，禁止重新发明）：
 *   OpenSeeFaceProcess/src/main/java/com/live2d/facecapture/
 *     BlendShapeExtractor.kt / SimpleEyeExtractor.kt / SimpleMouthExtractor.kt /
 *     SimpleSmileExtractor.kt / AdvancedBlendShapeExtractor.kt
 *   CommonData/src/main/java/com/example/commondata/FaceLandmarkIndices.kt
 *   CommonData/src/main/java/com/example/commondata/Landmark.kt
 *   FaceCapturePipeline.kt（像素转换、faceWidth/faceHeight、人脸距离估计）
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 52 维字段序（唯一权威 = Landmark.kt 的 BS_* 常量 / toMediaPipeArray()）：
 *   ⚠️ ARKitBlendShapes **构造器声明顺序**（眼14/眉5/颊3/颌4/嘴23/鼻2/舌1）
 *   与 **协议数组下标顺序** 完全不同。本文件一律按下标，见 BS_INDEX。
 *   tongueOut 只是 Kotlin 内部字段，**不在 52 维数组里**。
 *   clamp() **只 clamp 7 个字段**：eyeBlinkLeft/Right、eyeWideLeft/Right、
 *   jawOpen、mouthSmileLeft/Right（Landmark.kt:clamp）。
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ⚠️ 关键默认：advancedMode 默认 **false**。FaceCapturePipeline 用的是
 *   new BlendShapeExtractor()，而高级分支要求 landmarks.size >= 468 && advancedMode。
 *   因此 Android **实际只产出**下标 9,10,11,12,13,14,15,16,17,18,21,22,25,30,31,44,45，
 *   **其余恒为 0**。黄金对齐第一阶段按 advancedMode=false 对齐，true 分支单独覆盖。
 *
 * 性能约束：处理链内不逐帧建普通对象。点位用 Float32Array(478*3) 下标取，
 * 索引表用 Int32Array 常量，输出就地写入传入的 Float32Array(52)。
 * 校准状态对象只在构造时创建一次。
 */

// ══════════════════════════════════════════════════════════════════════
// 52 维下标（= CommonData/Landmark.kt 的 BS_* 常量 = mapping.js BS_INDEX）
// ══════════════════════════════════════════════════════════════════════
export const BS_INDEX = Object.freeze({
  neutral: 0,
  browDownLeft: 1, browDownRight: 2, browInnerUp: 3, browOuterUpLeft: 4, browOuterUpRight: 5,
  cheekPuff: 6, cheekSquintLeft: 7, cheekSquintRight: 8,
  eyeBlinkLeft: 9, eyeBlinkRight: 10,
  eyeLookDownLeft: 11, eyeLookDownRight: 12,
  eyeLookInLeft: 13, eyeLookInRight: 14,
  eyeLookOutLeft: 15, eyeLookOutRight: 16,
  eyeLookUpLeft: 17, eyeLookUpRight: 18,
  eyeSquintLeft: 19, eyeSquintRight: 20,
  eyeWideLeft: 21, eyeWideRight: 22,
  jawForward: 23, jawLeft: 24, jawOpen: 25, jawRight: 26,
  mouthClose: 27, mouthDimpleLeft: 28, mouthDimpleRight: 29,
  mouthFrownLeft: 30, mouthFrownRight: 31, mouthFunnel: 32, mouthLeft: 33,
  mouthLowerDownLeft: 34, mouthLowerDownRight: 35,
  mouthPressLeft: 36, mouthPressRight: 37, mouthPucker: 38, mouthRight: 39,
  mouthRollLower: 40, mouthRollUpper: 41, mouthShrugLower: 42, mouthShrugUpper: 43,
  mouthSmileLeft: 44, mouthSmileRight: 45,
  mouthStretchLeft: 46, mouthStretchRight: 47,
  mouthUpperUpLeft: 48, mouthUpperUpRight: 49,
  noseSneerLeft: 50, noseSneerRight: 51,
});

/** 协议要求 bs 恰好 52 项。 */
export const BS_COUNT = 52;

// ══════════════════════════════════════════════════════════════════════
// MediaPipe 478 点位索引（= CommonData/FaceLandmarkIndices.kt）
// 命名按**图像左/右**而非真人左右（前置镜像下图像左 = 真人右）。
// 468/473 是虹膜中心，469-472 / 474-477 是虹膜边缘。
// ══════════════════════════════════════════════════════════════════════

/** ImageRightEye（图像右侧眼睛） */
export const IMG_R_EYE_UPPER = new Int32Array([246, 161, 160, 159, 158, 157, 173]);
export const IMG_R_EYE_LOWER = new Int32Array([7, 163, 144, 145, 153, 154, 155]);
export const IMG_R_EYE_INNER = 133;
export const IMG_R_EYE_OUTER = 33;
export const IMG_R_IRIS_CENTER = 468;

/** ImageLeftEye（图像左侧眼睛） */
export const IMG_L_EYE_UPPER = new Int32Array([466, 388, 387, 386, 385, 384, 398]);
export const IMG_L_EYE_LOWER = new Int32Array([249, 390, 373, 374, 380, 381, 382]);
export const IMG_L_EYE_INNER = 362;
export const IMG_L_EYE_OUTER = 263;
export const IMG_L_IRIS_CENTER = 473;

/** 眉毛 */
export const IMG_R_BROW_INNER = 107, IMG_R_BROW_MIDDLE = 105, IMG_R_BROW_OUTER = 46;
export const IMG_L_BROW_INNER = 336, IMG_L_BROW_MIDDLE = 334, IMG_L_BROW_OUTER = 276;

/** 鼻子 */
export const NOSE_TIP = 1, NOSE_ROOT = 168;
export const NOSE_IMG_L_WING = 98, NOSE_IMG_R_WING = 327;
export const NOSE_L_SNEER_AREA = new Int32Array([49, 129, 102, 48]);
export const NOSE_R_SNEER_AREA = new Int32Array([279, 358, 331, 278]);

/** 嘴巴（Mouth.INNER_FULL / OUTER_FULL 各 20 点，下标语义见 Landmark.kt） */
export const MOUTH_INNER = new Int32Array([
  78, 191, 80, 81, 82, 13, 312, 311, 310, 415,
  308, 324, 318, 402, 317, 14, 87, 178, 88, 95,
]);
export const MOUTH_OUTER = new Int32Array([
  61, 146, 91, 181, 84, 17, 314, 405, 321, 375,
  291, 409, 270, 269, 267, 0, 37, 39, 40, 185,
]);
export const MOUTH_IMG_L_CORNER = 61, MOUTH_IMG_R_CORNER = 291;
export const UPPER_LIP_TOP = 0, UPPER_LIP_CENTER = 13;
export const LOWER_LIP_BOTTOM = 17, LOWER_LIP_CENTER = 14;
export const MOUTH_IMG_L_DIMPLE = 206, MOUTH_IMG_R_DIMPLE = 426;
export const UPPER_LIP_IMG_L = new Int32Array([40, 39, 37]);
export const UPPER_LIP_IMG_R = new Int32Array([270, 269, 267]);
export const LOWER_LIP_IMG_L = new Int32Array([84, 181, 91]);
export const LOWER_LIP_IMG_R = new Int32Array([314, 405, 321]);

/** 脸颊 */
export const CHEEK_IMG_L = new Int32Array([36, 205, 206, 187, 123]);
export const CHEEK_IMG_R = new Int32Array([266, 425, 426, 411, 352]);
export const CHEEK_IMG_L_SQUINT = 117, CHEEK_IMG_R_SQUINT = 346;
export const CHEEK_IMG_L_LOWER_EYELID = 111, CHEEK_IMG_R_LOWER_EYELID = 340;

/** 下巴 / 脸部轮廓 */
export const JAW_CHIN_CENTER = 152;
export const FACE_OVAL_BOTTOM = 152;

/** eyeSquint 用眼睑点组（AdvancedBlendShapeExtractor.extractEyeSquint 里的字面量） */
export const SQUINT_L_UPPER = new Int32Array([159, 158, 157, 173]);
export const SQUINT_L_LOWER = new Int32Array([144, 145, 153, 154]);
export const SQUINT_R_UPPER = new Int32Array([386, 385, 384, 398]);
export const SQUINT_R_LOWER = new Int32Array([373, 374, 380, 381]);

// ══════════════════════════════════════════════════════════════════════
// 几何基元（作用于 Float32Array(478*3) 的交错 x/y/z，零分配）
// ══════════════════════════════════════════════════════════════════════

/** Point3D.distance2DTo：Math.sqrt(dx*dx + dy*dy)（不用 hypot，避免微差）。 */
function dist2D(lm, ia, ib) {
  const a = ia * 3, b = ib * 3;
  const dx = lm[a] - lm[b];
  const dy = lm[a + 1] - lm[b + 1];
  return Math.sqrt(dx * dx + dy * dy);
}

/** 点组的几何中心（BlendShapeExtractor.centerOf 的零分配版本，求和顺序一致）。 */
function centerOf(lm, idx, out, outOffset) {
  let sx = 0, sy = 0, sz = 0;
  const n = idx.length;
  for (let i = 0; i < n; i++) {
    const p = idx[i] * 3;
    sx += lm[p];
    sy += lm[p + 1];
    sz += lm[p + 2];
  }
  if (n === 0) { out[outOffset] = 0; out[outOffset + 1] = 0; out[outOffset + 2] = 0; return; }
  out[outOffset] = sx / n;
  out[outOffset + 1] = sy / n;
  out[outOffset + 2] = sz / n;
}

/** 点组某一轴的平均（AdvancedBlendShapeExtractor 里的 map{}.average()）。 */
function meanOf(lm, idx, axis) {
  let s = 0;
  const n = idx.length;
  for (let i = 0; i < n; i++) s += lm[idx[i] * 3 + axis];
  return n === 0 ? 0 : s / n;
}

function clamp01(v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

/**
 * computeEyeAspectRatio（SimpleEyeExtractor.computeEyeAspectRatio）。
 *
 * EAR = 垂直距离均值 / 水平距离
 *  垂直：对每个上眼睑点求「到任一下眼睑点的最小 2D 距离」，再取平均
 *  水平：内眼角 <-> 外眼角的 2D 距离
 *  下眼睑为空时按 0 计入平均（Kotlin minOfOrNull 返回 null -> 0f）。
 */
function computeEyeAspectRatio(lm, upper, lower, inner, outer) {
  let sumVertical = 0;
  const upperCount = upper.length;
  const lowerCount = lower.length;
  for (let u = 0; u < upperCount; u++) {
    let minDist = Infinity;
    for (let l = 0; l < lowerCount; l++) {
      const d = dist2D(lm, upper[u], lower[l]);
      if (d < minDist) minDist = d;
    }
    sumVertical += (minDist === Infinity ? 0 : minDist);
  }
  const avgVertical = upperCount > 0 ? sumVertical / upperCount : 0;
  const horizontal = dist2D(lm, inner, outer);
  return horizontal > 1e-6 ? avgVertical / horizontal : 0;
}

// ══════════════════════════════════════════════════════════════════════
// 校准数据（构造一次，逐帧只写标量字段）
// ══════════════════════════════════════════════════════════════════════

/** 校准窗口的时长（毫秒）。CommonData: CALIBRATION_DURATION_MS = 1000L */
export const CALIBRATION_DURATION_MS = 1000;

/** 校准窗口需要的最小帧数兜底。CommonData: MIN_CALIBRATION_FRAMES = 10 */
export const MIN_CALIBRATION_FRAMES = 10;

/**
 * 眼部校准数据（SimpleEyeExtractor.EyeCalibrationData）。
 * 左右眼**各自独立**一套，且可能在不同帧落地。
 */
export class EyeCalibrationData {
  constructor() { this.reset(); }

  /** Kotlin addFrame(ear)。 */
  addFrame(ear, nowMs) {
    if (this.frameCount === 0) this.startTimestampMs = nowMs;
    this.sumEAR += ear;
    this.sumSquaredEAR += ear * ear;
    if (ear < this.minEAR) this.minEAR = ear;
    if (ear > this.maxEAR) this.maxEAR = ear;
    this.frameCount++;
  }

  /** Kotlin isWindowReady()：n >= 10 && elapsed >= 1000ms。 */
  isWindowReady(nowMs) {
    if (this.frameCount < MIN_CALIBRATION_FRAMES) return false;
    return nowMs - this.startTimestampMs >= CALIBRATION_DURATION_MS;
  }

  /**
   * Kotlin finishCalibration()（n < 10 直接 return，不校准）。
   *
   * 【与 Kotlin 注释不符，按代码】若 (open - closed) < 0.04 会回退三个默认值，
   * 但 isCalibrated **仍然置 true**。
   */
  finishCalibration() {
    if (this.frameCount < 10) return;

    const n = this.frameCount;
    this.meanEAR = this.sumEAR / n;
    const variance = this.sumSquaredEAR / n - this.meanEAR * this.meanEAR;
    this.stdEAR = Math.sqrt(Math.max(0, variance));

    const observedRange = this.maxEAR - this.minEAR;

    const observedOpenRatio = observedRange > 0.02
      ? this.maxEAR - 0.1 * observedRange
      : this.meanEAR - this.stdEAR;

    const inferredClosedRatio = (observedRange > 0.03 && this.minEAR < this.meanEAR - 2 * this.stdEAR)
      ? this.minEAR + 0.1 * observedRange
      : observedOpenRatio * 0.6;

    const open = clamp(observedOpenRatio, 0.12, 0.35);
    const closed = clamp(inferredClosedRatio, 0.05, open - 0.03);
    this.calibratedOpenRatio = open;
    this.calibratedClosedRatio = closed;
    this.calibratedWideRatio = open + (open - closed) * 0.8;

    const finalRange = this.calibratedOpenRatio - this.calibratedClosedRatio;
    if (finalRange < 0.04) {
      this.calibratedClosedRatio = DEFAULT_EYE_CLOSED_RATIO;
      this.calibratedOpenRatio = DEFAULT_EYE_OPEN_RATIO;
      this.calibratedWideRatio = DEFAULT_EYE_WIDE_RATIO;
    }
    this.isCalibrated = true;
  }

  reset() {
    this.sumEAR = 0;
    this.sumSquaredEAR = 0;
    this.minEAR = Infinity;
    this.maxEAR = -Infinity;
    this.frameCount = 0;
    this.startTimestampMs = 0;
    this.meanEAR = 0;
    this.stdEAR = 0;
    this.calibratedClosedRatio = DEFAULT_EYE_CLOSED_RATIO;
    this.calibratedOpenRatio = DEFAULT_EYE_OPEN_RATIO;
    this.calibratedWideRatio = DEFAULT_EYE_WIDE_RATIO;
    this.isCalibrated = false;
  }
}

function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }

const DEFAULT_EYE_CLOSED_RATIO = 0.10;
const DEFAULT_EYE_OPEN_RATIO = 0.18;
const DEFAULT_EYE_WIDE_RATIO = 0.26;

/**
 * 简化的眼睛 BlendShape 提取器（SimpleEyeExtractor）。
 * 支持自适应阈值校准和头部姿态补偿。
 */
export class SimpleEyeExtractor {
  constructor() {
    /** 左右眼分别校准（imageLeft / imageRight 按**图像**左右）。 */
    this.imageLeftEyeCalibration = new EyeCalibrationData();
    this.imageRightEyeCalibration = new EyeCalibrationData();

    // ---- 校准时的头部姿态（用于「相对于校准时刻」的补偿）----
    this.calibrationPitch = 0;
    this.calibrationYaw = 0;
    this.calibrationFaceRatio = 0.5;

    // ---- 可调参数（Kotlin var，逐条对应）----
    this.closedThresholdOffset = 0;   // coerceIn(-0.08, 0.08)
    this.openThresholdOffset = 0;     // coerceIn(-0.08, 0.08)
    this.rangeShrinkFactorPerDegree = 0.012;
    this.pitchDownShrinkMultiplier = 1.2;
    this.minShrinkRatio = 0.4;
    this.pitchCompensationThreshold = 5;
    this.yawCompensationFactor = 0.4;
    this.yawCompensationThreshold = 1;
    this.nearSideCompensationMultiplier = 5.0;  // 动态模式下被覆盖
    this.enableDynamicCompensation = true;
    this.cameraFovDegrees = 63;
    this.referenceRatioRatio = 0.4;
    this.baseCompensationMultiplier = 3.0;
    this.distanceSensitivity = 2.5;
    this.enableHeadPoseCompensation = true;
    this._currentFaceRatio = 0.5;
    this._perspectiveStrength = 1.0;
    this._updatePerspectiveStrength();
  }

  /** Kotlin companion var eyeSensitivity：全局共享，clamp(0.3, 3.0)。 */
  static get eyeSensitivity() { return SimpleEyeExtractor._eyeSensitivity; }
  static set eyeSensitivity(v) { SimpleEyeExtractor._eyeSensitivity = clamp(v, 0.3, 3.0); }

  get isCalibrating() {
    return !this.imageLeftEyeCalibration.isCalibrated || !this.imageRightEyeCalibration.isCalibrated;
  }

  resetCalibration() {
    this.imageLeftEyeCalibration.reset();
    this.imageRightEyeCalibration.reset();
    this.calibrationPitch = 0;
    this.calibrationYaw = 0;
    this.calibrationFaceRatio = 0.5;
  }

  /** Kotlin setCalibrationPose(pitch, yaw)。 */
  setCalibrationPose(pitch, yaw) {
    this.calibrationPitch = pitch;
    this.calibrationYaw = yaw;
    this.calibrationFaceRatio = this._currentFaceRatio;
  }

  /** Kotlin cameraFovDegrees setter -> updatePerspectiveStrength()。 */
  setCameraFov(fovDegrees) {
    this.cameraFovDegrees = clamp(fovDegrees, 30, 120);
    this._updatePerspectiveStrength();
  }

  _updatePerspectiveStrength() {
    const halfFovRad = (this.cameraFovDegrees / 2) * Math.PI / 180;
    const referenceFovRad = 30 * Math.PI / 180; // 60 度 FOV 的一半
    this._perspectiveStrength = Math.tan(halfFovRad) / Math.tan(referenceFovRad);
  }

  /** Kotlin getEffectiveClosedRatio。 */
  getEffectiveClosedRatio(cal) {
    const baseRatio = cal.isCalibrated ? cal.calibratedClosedRatio : DEFAULT_EYE_CLOSED_RATIO;
    const baseOpen = cal.isCalibrated ? cal.calibratedOpenRatio : DEFAULT_EYE_OPEN_RATIO;
    const baseRange = baseOpen - baseRatio;
    const adjustFactor = baseRange * 0.3;
    const sensitivityAdjusted = baseRatio + (SimpleEyeExtractor.eyeSensitivity - 1) * adjustFactor;
    const withOffset = sensitivityAdjusted + this.closedThresholdOffset;
    return clamp(withOffset, 0.05, baseOpen - 0.02);
  }

  /** Kotlin getEffectiveOpenRatio。 */
  getEffectiveOpenRatio(cal) {
    const baseClosed = cal.isCalibrated ? cal.calibratedClosedRatio : DEFAULT_EYE_CLOSED_RATIO;
    const baseRatio = cal.isCalibrated ? cal.calibratedOpenRatio : DEFAULT_EYE_OPEN_RATIO;
    const baseWide = cal.isCalibrated ? cal.calibratedWideRatio : DEFAULT_EYE_WIDE_RATIO;
    const baseRange = baseRatio - baseClosed;
    const adjustFactor = baseRange * 0.3;
    const sensitivityAdjusted = baseRatio - (SimpleEyeExtractor.eyeSensitivity - 1) * adjustFactor;
    const withOffset = sensitivityAdjusted - this.openThresholdOffset;
    return clamp(withOffset, baseClosed + 0.02, baseWide - 0.02);
  }

  /** Kotlin calculateShrinkRatio(pitch)。 */
  calculateShrinkRatio(pitch) {
    const absPitch = Math.abs(pitch);
    if (absPitch <= this.pitchCompensationThreshold) return 1;
    const effectivePitch = absPitch - this.pitchCompensationThreshold;
    let shrinkAmount = effectivePitch * this.rangeShrinkFactorPerDegree;
    if (pitch < 0) shrinkAmount *= this.pitchDownShrinkMultiplier;
    return clamp(1 - shrinkAmount, this.minShrinkRatio, 1);
  }

  /**
   * Kotlin compensateEarForPitch(ear, pitch, calibration)。
   * 动态范围补偿：只补偿「本来应该睁眼但因头部姿态变小」的情况，不补偿真闭眼。
   */
  compensateEarForPitch(ear, pitch, cal) {
    if (Math.abs(pitch) <= this.pitchCompensationThreshold) return ear;

    const normalClosed = this.getEffectiveClosedRatio(cal);
    const normalOpen = this.getEffectiveOpenRatio(cal);

    if (ear <= normalClosed * 1.2) return ear; // 真正闭眼，不补偿

    const shrinkRatio = this.calculateShrinkRatio(pitch);
    const shrunkOpen = normalClosed + (normalOpen - normalClosed) * shrinkRatio;

    if (ear >= shrunkOpen) return normalOpen;
    if (ear <= normalClosed) return ear;

    const shrunkRange = shrunkOpen - normalClosed;
    if (shrunkRange < 0.01) return ear;

    const normalizedPosition = (ear - normalClosed) / shrunkRange;
    return normalizedPosition * (normalOpen - normalClosed) + normalClosed;
  }

  /**
   * Kotlin updateFaceDistanceEstimateNormalized(w, h)。
   * 动态透视补偿系数：multiplier = base * (1 + sensitivity * perspective * distanceFactor)
   */
  updateFaceDistanceEstimateNormalized(normalizedWidth, normalizedHeight) {
    if (!(normalizedWidth > 0) || !(normalizedHeight > 0) ||
        normalizedWidth > 1.5 || normalizedHeight > 1.5) {
      return;
    }
    const faceRatio = Math.sqrt(normalizedWidth * normalizedHeight);
    this._currentFaceRatio = this._currentFaceRatio * 0.9 + faceRatio * 0.1;

    if (this.enableDynamicCompensation) {
      const distanceFactor = clamp(
        (this._currentFaceRatio - this.referenceRatioRatio) / this.referenceRatioRatio,
        -0.5, 1.5);
      const dynamicMultiplier = this.baseCompensationMultiplier *
        (1 + this.distanceSensitivity * this._perspectiveStrength * distanceFactor);
      this.nearSideCompensationMultiplier = clamp(dynamicMultiplier, 0.5, 15.0);
    }
  }

  /** Kotlin earToBlinkValue(ear, calibration)。 */
  earToBlinkValue(ear, cal) {
    const effectiveClosedRatio = this.getEffectiveClosedRatio(cal);
    const effectiveOpenRatio = this.getEffectiveOpenRatio(cal);
    if (ear <= effectiveClosedRatio) return 1;
    if (ear >= effectiveOpenRatio) return 0;
    const range = effectiveOpenRatio - effectiveClosedRatio;
    const offset = effectiveOpenRatio - ear;
    return clamp(offset / range, 0, 1);
  }

  /** Kotlin detectEyeWide(ear, calibration)。 */
  detectEyeWide(ear, cal) {
    const openRatio = this.getEffectiveOpenRatio(cal);
    const wideRatio = cal.isCalibrated ? cal.calibratedWideRatio : DEFAULT_EYE_WIDE_RATIO;
    if (ear > openRatio) {
      return clamp((ear - openRatio) / (wideRatio - openRatio), 0, 1);
    }
    return 0;
  }

  /**
   * Kotlin extractEyeBlendShapes(imageLeftEye, imageRightEye, blendShapes, headPose)。
   *
   * Pipeline 顺序（**不可换**）：
   *   1) 算原始 EAR；2) pitch 动态范围补偿；3) yaw 相对补偿；
   *   4) 校准收集 **rawEAR（未补偿）**；5) 用补偿后 EAR 转 BlendShape。
   *
   * @param {Float32Array} lm 478*3 交错点位（像素或归一化，只要自洽）
   * @param {Float32Array} out52 输出（就地写 [9][10][21][22]）
   * @param {number[]|null} headPose [pitch, yaw, roll]（用原始头姿，不是相对姿态）
   * @param {number} nowMs 当前时间（毫秒），驱动校准窗口
   */
  extractEyeBlendShapes(lm, out52, headPose, nowMs) {
    // ---- Step 1: 原始 EAR ----
    const rawEarRight = computeEyeAspectRatio(lm, IMG_R_EYE_UPPER, IMG_R_EYE_LOWER, IMG_R_EYE_INNER, IMG_R_EYE_OUTER);
    const rawEarLeft = computeEyeAspectRatio(lm, IMG_L_EYE_UPPER, IMG_L_EYE_LOWER, IMG_L_EYE_INNER, IMG_L_EYE_OUTER);

    // ---- Step 2/3: 头部姿态补偿（在转换之前）----
    let earRight = rawEarRight;
    let earLeft = rawEarLeft;

    const hasPose = headPose !== null && headPose !== undefined;
    const pitch = hasPose ? headPose[0] : 0;
    const yaw = hasPose ? headPose[1] : 0;
    const pitchNearZero = !hasPose || Math.abs(pitch) <= this.pitchCompensationThreshold;
    const yawNearZero = !hasPose || Math.abs(yaw) <= this.yawCompensationThreshold;

    if (this.enableHeadPoseCompensation && hasPose && !pitchNearZero) {
      earRight = this.compensateEarForPitch(rawEarRight, pitch, this.imageRightEyeCalibration);
      earLeft = this.compensateEarForPitch(rawEarLeft, pitch, this.imageLeftEyeCalibration);
    }

    if (this.enableHeadPoseCompensation && hasPose && !yawNearZero) {
      const currentAbsYaw = Math.abs(yaw);
      const currentYawFactor = Math.max(0, (currentAbsYaw - this.yawCompensationThreshold) / 90);
      const calibrationAbsYaw = Math.abs(this.calibrationYaw);
      const calibrationYawFactor = Math.max(0, (calibrationAbsYaw - this.yawCompensationThreshold) / 90);
      const deltaYawFactor = currentYawFactor - calibrationYawFactor;

      const rightClosed = this.getEffectiveClosedRatio(this.imageRightEyeCalibration);
      const rightOpen = this.getEffectiveOpenRatio(this.imageRightEyeCalibration);
      const leftClosed = this.getEffectiveClosedRatio(this.imageLeftEyeCalibration);
      const leftOpen = this.getEffectiveOpenRatio(this.imageLeftEyeCalibration);
      const rightEarRange = rightOpen - rightClosed;
      const leftEarRange = leftOpen - leftClosed;

      // 自适应补偿衰减：睁眼(decay=1)完全补偿，闭眼(decay=0)不补偿
      const rightDecay = clamp((rawEarRight - rightClosed) / rightEarRange, 0, 1);
      const leftDecay = clamp((rawEarLeft - leftClosed) / leftEarRange, 0, 1);

      if (deltaYawFactor > 0) {
        const farSideCompensation = deltaYawFactor * this.yawCompensationFactor;
        const nearSideCompensation = deltaYawFactor * this.yawCompensationFactor * this.nearSideCompensationMultiplier;
        // 【与 Kotlin 注释不符，按代码】注释对 yaw 正负说法自相矛盾，
        // 这里照搬代码分支：yaw > 0 时 imageLeft = 远侧、imageRight = 近侧。
        if (yaw > 0) {
          earLeft += farSideCompensation * leftEarRange * leftDecay;
          earRight += nearSideCompensation * rightEarRange * rightDecay;
        } else {
          earRight += farSideCompensation * rightEarRange * rightDecay;
          earLeft += nearSideCompensation * leftEarRange * leftDecay;
        }
      }

      // 转头方向改变的情况（校准时向左、现在向右，需要双向补偿）
      const calibrationDirection = this.calibrationYaw >= 0 ? 1 : -1;
      const currentDirection = yaw >= 0 ? 1 : -1;
      if (calibrationDirection !== currentDirection && calibrationAbsYaw > this.yawCompensationThreshold) {
        const oppositeCompensation = calibrationYawFactor * this.yawCompensationFactor;
        const oppositeNearCompensation = calibrationYawFactor * this.yawCompensationFactor * this.nearSideCompensationMultiplier;
        if (currentDirection > 0) {
          earRight += oppositeCompensation * rightEarRange * rightDecay;
          earLeft += oppositeNearCompensation * leftEarRange * leftDecay;
        } else {
          earLeft += oppositeCompensation * leftEarRange * leftDecay;
          earRight += oppositeNearCompensation * rightEarRange * rightDecay;
        }
      }
    }

    // ---- Step 3: 自适应校准（收集的是 rawEAR，未补偿）----
    if (!this.imageRightEyeCalibration.isCalibrated) {
      this.imageRightEyeCalibration.addFrame(rawEarRight, nowMs);
      if (this.imageRightEyeCalibration.isWindowReady(nowMs)) {
        this.imageRightEyeCalibration.finishCalibration();
        // Kotlin：左眼 finish 那一瞬间记录校准姿态（右眼不写）
      }
    }
    if (!this.imageLeftEyeCalibration.isCalibrated) {
      this.imageLeftEyeCalibration.addFrame(rawEarLeft, nowMs);
      if (this.imageLeftEyeCalibration.isWindowReady(nowMs)) {
        this.imageLeftEyeCalibration.finishCalibration();
        // 【顺序不可换】这里记下的 calibrationPitch/Yaw/FaceRatio 随后会被
        // BlendShapeExtractor.setCalibrationPose(baseline) 覆盖（见 FaceCapturePipeline）。
        if (hasPose) {
          this.calibrationPitch = pitch;
          this.calibrationYaw = yaw;
          this.calibrationFaceRatio = this._currentFaceRatio;
        }
      }
    }

    // ---- Step 4: EAR -> BlendShape（用补偿后 EAR + 校准阈值）----
    out52[BS_INDEX.eyeBlinkLeft] = this.earToBlinkValue(earLeft, this.imageLeftEyeCalibration);
    out52[BS_INDEX.eyeBlinkRight] = this.earToBlinkValue(earRight, this.imageRightEyeCalibration);
    out52[BS_INDEX.eyeWideLeft] = this.detectEyeWide(earLeft, this.imageLeftEyeCalibration);
    out52[BS_INDEX.eyeWideRight] = this.detectEyeWide(earRight, this.imageRightEyeCalibration);
  }
}
SimpleEyeExtractor._eyeSensitivity = 1.0;

// ══════════════════════════════════════════════════════════════════════
// SimpleMouthExtractor（jawOpen）
// ══════════════════════════════════════════════════════════════════════

const DEFAULT_MOUTH_CLOSED_RATIO = 0.10;
const DEFAULT_MOUTH_OPEN_RATIO = 0.45;

/** 嘴部校准数据（SimpleMouthExtractor.MouthCalibrationData）。 */
export class MouthCalibrationData {
  constructor() { this.reset(); }

  addFrame(mar, nowMs) {
    if (this.frameCount === 0) this.startTimestampMs = nowMs;
    this.sumMAR += mar;
    this.sumSquaredMAR += mar * mar;
    if (mar < this.minMAR) this.minMAR = mar;
    if (mar > this.maxMAR) this.maxMAR = mar;
    this.frameCount++;
  }

  isWindowReady(nowMs) {
    if (this.frameCount < MIN_CALIBRATION_FRAMES) return false;
    return nowMs - this.startTimestampMs >= CALIBRATION_DURATION_MS;
  }

  /** 假设校准期间用户闭嘴，MAR 应该较小。 */
  finishCalibration() {
    if (this.frameCount < 10) return;
    const n = this.frameCount;
    this.meanMAR = this.sumMAR / n;
    const variance = this.sumSquaredMAR / n - this.meanMAR * this.meanMAR;
    this.stdMAR = Math.sqrt(Math.max(0, variance));

    // closedRatio = meanMAR + 2*std（允许小幅抖动），clamp(0.05, 0.25)
    this.calibratedClosedRatio = clamp(this.meanMAR + 2 * this.stdMAR, 0.05, 0.25);
    // openRatio 基于 closedRatio 推断（张大嘴约为闭嘴的 3.5 倍）
    this.calibratedOpenRatio = clamp(this.calibratedClosedRatio * 3.5, 0.25, 0.70);
    this.isCalibrated = true;
  }

  reset() {
    this.sumMAR = 0;
    this.sumSquaredMAR = 0;
    this.minMAR = Infinity;
    this.maxMAR = -Infinity;
    this.frameCount = 0;
    this.startTimestampMs = 0;
    this.meanMAR = 0;
    this.stdMAR = 0;
    this.calibratedClosedRatio = DEFAULT_MOUTH_CLOSED_RATIO;
    this.calibratedOpenRatio = DEFAULT_MOUTH_OPEN_RATIO;
    this.isCalibrated = false;
  }
}

/**
 * 嘴部 BlendShape 提取器（SimpleMouthExtractor），支持俯仰角补偿。
 *
 * 补偿原理：低头/抬头时嘴巴可见高度因透视被压缩，导致 jawOpen 偏小。
 * 策略是「相对于校准时刻的透视变化量」：compensated = raw + [f(currentPitch) - f(calibrationPitch)]。
 */
export class SimpleMouthExtractor {
  constructor() {
    this.mouthCalibration = new MouthCalibrationData();
    this.calibrationPitch = 0;
    this.calibrationYaw = 0;
    this.calibrationRoll = 0;
    this.enablePitchCompensation = true;
    this.pitchCompensationThreshold = 5;
    this.pitchCompensationFactor = 0.008;
    this.pitchDownMultiplier = 1.3;
  }

  get isCalibrating() { return !this.mouthCalibration.isCalibrated; }

  setCalibrationPose(pitch, yaw, roll) {
    this.calibrationPitch = pitch;
    this.calibrationYaw = yaw;
    this.calibrationRoll = roll;
  }

  /**
   * Kotlin calculatePitchCompensation(currentPitch, rawMAR)。
   * 【修复点，照代码】只有当 rawMAR 已经超过闭嘴阈值的 1.5 倍时才补偿，
   * 避免闭嘴状态被错误放大。
   */
  calculatePitchCompensation(currentPitch, rawMAR) {
    const deltaPitch = currentPitch - this.calibrationPitch;
    if (Math.abs(deltaPitch) <= this.pitchCompensationThreshold) return 0;

    const closedThreshold = this.mouthCalibration.isCalibrated
      ? this.mouthCalibration.calibratedClosedRatio
      : DEFAULT_MOUTH_CLOSED_RATIO;
    if (rawMAR < closedThreshold * 1.5) return 0;

    const effectiveDelta = deltaPitch > 0
      ? (deltaPitch - this.pitchCompensationThreshold) * this.pitchDownMultiplier
      : -(Math.abs(deltaPitch) - this.pitchCompensationThreshold);
    return effectiveDelta * this.pitchCompensationFactor;
  }

  /**
   * MAR = 上下唇高度 / 嘴宽。
   * mouthInner[5] = 上唇内中心(13)、mouthInner[15] = 下唇内中心(14)、
   * mouthInner[0] = 左嘴角(78)、mouthInner[10] = 右嘴角(308)。
   */
  computeMouthAspectRatio(lm) {
    const upperLipTop = MOUTH_INNER[5];
    const lowerLipBottom = MOUTH_INNER[15];
    const leftCorner = MOUTH_INNER[0];
    const rightCorner = MOUTH_INNER[10];
    const mouthHeight = dist2D(lm, upperLipTop, lowerLipBottom);
    const mouthWidth = dist2D(lm, leftCorner, rightCorner);
    return mouthWidth > 1e-6 ? mouthHeight / mouthWidth : 0;
  }

  /** MAR -> jawOpen [0,1]。 */
  marToJawOpen(mar) {
    const closedRatio = this.mouthCalibration.isCalibrated
      ? this.mouthCalibration.calibratedClosedRatio : DEFAULT_MOUTH_CLOSED_RATIO;
    const openRatio = this.mouthCalibration.isCalibrated
      ? this.mouthCalibration.calibratedOpenRatio : DEFAULT_MOUTH_OPEN_RATIO;
    if (mar <= closedRatio) return 0;
    if (mar >= openRatio) return 1;
    return clamp((mar - closedRatio) / (openRatio - closedRatio), 0, 1);
  }

  extractMouthBlendShapes(lm, out52, headPose, nowMs) {
    const rawMAR = this.computeMouthAspectRatio(lm);

    let compensatedMAR = rawMAR;
    if (this.enablePitchCompensation && headPose != null && this.mouthCalibration.isCalibrated) {
      const compensation = this.calculatePitchCompensation(headPose[0], rawMAR);
      compensatedMAR = Math.max(0, rawMAR + compensation);
    }

    // 校准期收集 **rawMAR**（未补偿）
    if (!this.mouthCalibration.isCalibrated) {
      this.mouthCalibration.addFrame(rawMAR, nowMs);
      if (this.mouthCalibration.isWindowReady(nowMs)) this.mouthCalibration.finishCalibration();
    }

    out52[BS_INDEX.jawOpen] = this.marToJawOpen(compensatedMAR);
  }

  resetCalibration() {
    this.mouthCalibration.reset();
    this.calibrationPitch = 0;
    this.calibrationYaw = 0;
    this.calibrationRoll = 0;
  }
  reset() { this.resetCalibration(); }
}

// ══════════════════════════════════════════════════════════════════════
// SimpleSmileExtractor（mouthSmile/Frown L/R）
// ══════════════════════════════════════════════════════════════════════

const SMILE_DEAD_ZONE = 0.003;
const FROWN_DEAD_ZONE = 0.002;
const DEFAULT_SMILE_MAX = 0.06;
const DEFAULT_FROWN_MAX = 0.04;

/** 微笑校准数据（SimpleSmileExtractor.SmileCalibrationData）。 */
export class SmileCalibrationData {
  constructor() { this.reset(); }

  addFrame(leftFeature, rightFeature, nowMs) {
    if (this.frameCount === 0) this.startTimestampMs = nowMs;
    this.sumLeftFeature += leftFeature;
    this.sumRightFeature += rightFeature;
    this.sumSquaredLeftFeature += leftFeature * leftFeature;
    this.sumSquaredRightFeature += rightFeature * rightFeature;
    this.frameCount++;
  }

  isWindowReady(nowMs) {
    if (this.frameCount < MIN_CALIBRATION_FRAMES) return false;
    return nowMs - this.startTimestampMs >= CALIBRATION_DURATION_MS;
  }

  finishCalibration() {
    if (this.frameCount < 10) return;
    const n = this.frameCount;
    this.baselineLeftFeature = this.sumLeftFeature / n;
    this.baselineRightFeature = this.sumRightFeature / n;
    const varL = this.sumSquaredLeftFeature / n - this.baselineLeftFeature * this.baselineLeftFeature;
    const varR = this.sumSquaredRightFeature / n - this.baselineRightFeature * this.baselineRightFeature;
    this.stdLeftFeature = Math.sqrt(Math.max(0, varL));
    this.stdRightFeature = Math.sqrt(Math.max(0, varR));
    this.isCalibrated = true;
  }

  /** 动态更新观察范围（**只扩大不缩小**）。 */
  updateObservedRange(leftDelta, rightDelta) {
    const avgDelta = (leftDelta + rightDelta) / 2;
    if (avgDelta > 0) {
      const newMax = avgDelta * 1.2; // 留 20% 余量
      if (newMax > this.observedSmileMax) this.observedSmileMax = Math.min(newMax, 0.15);
    } else if (avgDelta < 0) {
      const newMax = Math.abs(avgDelta) * 1.2;
      if (newMax > this.observedFrownMax) this.observedFrownMax = Math.min(newMax, 0.10);
    }
  }

  reset() {
    this.sumLeftFeature = 0;
    this.sumRightFeature = 0;
    this.sumSquaredLeftFeature = 0;
    this.sumSquaredRightFeature = 0;
    this.frameCount = 0;
    this.startTimestampMs = 0;
    this.baselineLeftFeature = 0;
    this.baselineRightFeature = 0;
    this.stdLeftFeature = 0;
    this.stdRightFeature = 0;
    this.observedSmileMax = DEFAULT_SMILE_MAX;
    this.observedFrownMax = DEFAULT_FROWN_MAX;
    this.isCalibrated = false;
  }
}

/**
 * 微笑/撇嘴 BlendShape 提取器（SimpleSmileExtractor）。
 *
 * 特征 = (嘴唇中心 Y - 嘴角 Y) / faceHeight
 *   正值 = 嘴角高于中心 = 微笑；负值 = 嘴角低于中心 = 撇嘴。
 */
export class SimpleSmileExtractor {
  constructor() {
    this.calibration = new SmileCalibrationData();
    this.calibrationPitch = 0;
    this.calibrationYaw = 0;
    this.calibrationRoll = 0;
    this.enablePitchCompensation = true;
    this.pitchCompensationThreshold = 5;
    this.pitchCompensationFactor = 0.0008;
    this.rollCompensationFactor = 0.001;
  }

  get isCalibrating() { return !this.calibration.isCalibrated; }

  setCalibrationPose(pitch, yaw, roll) {
    this.calibrationPitch = pitch;
    this.calibrationYaw = yaw;
    this.calibrationRoll = roll;
  }

  calculatePitchCompensation(currentPitch) {
    const deltaPitch = currentPitch - this.calibrationPitch;
    if (Math.abs(deltaPitch) <= this.pitchCompensationThreshold) return 0;
    const effectiveDelta = deltaPitch > 0
      ? deltaPitch - this.pitchCompensationThreshold
      : deltaPitch + this.pitchCompensationThreshold;
    return effectiveDelta * this.pitchCompensationFactor;
  }

  /** delta -> smile [0,1]（与原 featureDeltaToBlendShape 的微笑分支一致）。 */
  deltaToSmile(delta, smileMax) {
    if (delta > SMILE_DEAD_ZONE) {
      return clamp((delta - SMILE_DEAD_ZONE) / (smileMax - SMILE_DEAD_ZONE), 0, 1);
    }
    return 0;
  }

  /** delta -> frown [0,1]。 */
  deltaToFrown(delta, frownMax) {
    if (delta < -FROWN_DEAD_ZONE) {
      return clamp((Math.abs(delta) - FROWN_DEAD_ZONE) / (frownMax - FROWN_DEAD_ZONE), 0, 1);
    }
    return 0;
  }

  /**
   * @param {Float32Array} lm 478*3
   * @param {Float32Array} out52 就地写 [44][45][30][31]
   * @param {number[]|null} headPose [pitch, yaw, roll]
   * @param {number} faceHeight 面部高度（鼻根到下巴）
   * @param {number} nowMs
   */
  extractSmileBlendShapes(lm, out52, headPose, faceHeight, nowMs) {
    // 前置：mouthOuter 少于 16 点 / faceHeight 无效时整帧 return（Kotlin 同）
    if (MOUTH_OUTER.length < 16) return;
    if (!(faceHeight > 1e-6)) return;

    const leftCorner = MOUTH_OUTER[0];
    const rightCorner = MOUTH_OUTER[10];
    const upperLipCenter = MOUTH_OUTER[15];
    const lowerLipCenter = MOUTH_OUTER[5];

    const mouthCenterY = (lm[upperLipCenter * 3 + 1] + lm[lowerLipCenter * 3 + 1]) / 2;

    const rawLeftFeature = (mouthCenterY - lm[leftCorner * 3 + 1]) / faceHeight;
    const rawRightFeature = (mouthCenterY - lm[rightCorner * 3 + 1]) / faceHeight;

    let compensatedLeftFeature = rawLeftFeature;
    let compensatedRightFeature = rawRightFeature;

    if (this.enablePitchCompensation && headPose != null && this.calibration.isCalibrated) {
      const pitchComp = this.calculatePitchCompensation(headPose[0]);
      compensatedLeftFeature += pitchComp;
      compensatedRightFeature += pitchComp;

      // Roll 补偿：|deltaRoll| <= 3 度时两个补偿量均为 0，直接跳过
      const deltaRoll = headPose[2] - this.calibrationRoll;
      if (Math.abs(deltaRoll) > 3) {
        const rollCompensation = deltaRoll * this.rollCompensationFactor;
        compensatedLeftFeature += -rollCompensation;
        compensatedRightFeature += rollCompensation;
      }
    }

    // 校准期收集 **原始特征**（未补偿）
    if (!this.calibration.isCalibrated) {
      this.calibration.addFrame(rawLeftFeature, rawRightFeature, nowMs);
      if (this.calibration.isWindowReady(nowMs)) {
        this.calibration.finishCalibration();
        if (headPose != null) this.setCalibrationPose(headPose[0], headPose[1], headPose[2]);
      }
    }

    const leftDelta = compensatedLeftFeature - this.calibration.baselineLeftFeature;
    const rightDelta = compensatedRightFeature - this.calibration.baselineRightFeature;

    if (this.calibration.isCalibrated) this.calibration.updateObservedRange(leftDelta, rightDelta);

    out52[BS_INDEX.mouthSmileLeft] = this.deltaToSmile(leftDelta, this.calibration.observedSmileMax);
    out52[BS_INDEX.mouthFrownLeft] = this.deltaToFrown(leftDelta, this.calibration.observedFrownMax);
    out52[BS_INDEX.mouthSmileRight] = this.deltaToSmile(rightDelta, this.calibration.observedSmileMax);
    out52[BS_INDEX.mouthFrownRight] = this.deltaToFrown(rightDelta, this.calibration.observedFrownMax);
  }

  resetCalibration() {
    this.calibration.reset();
    this.calibrationPitch = 0;
    this.calibrationYaw = 0;
    this.calibrationRoll = 0;
  }
  reset() { this.resetCalibration(); }
}

// ══════════════════════════════════════════════════════════════════════
// AdvancedBlendShapeExtractor（advancedMode = true 才跑）
// ══════════════════════════════════════════════════════════════════════

const BROW_UP_SENSITIVITY = 2.5;
const BROW_DOWN_SENSITIVITY = 3.0;
const CHEEK_PUFF_SENSITIVITY = 3.0;
const CHEEK_SQUINT_SENSITIVITY = 2.5;
const JAW_MOVE_SENSITIVITY = 3.0;
const NOSE_SNEER_SENSITIVITY = 4.0;
const LIP_ROLL_SENSITIVITY = 3.0;
const LIP_STRETCH_SENSITIVITY = 2.5;
const MOUTH_MOVE_SENSITIVITY = 3.0;

/**
 * 高级 BlendShape 提取器（AdvancedBlendShapeExtractor）。
 *
 * ⚠️ 只有 advancedMode = true 且点数 >= 468 时才会被调用；
 * 当前 FaceCapturePipeline 用的是默认 false，因此这一整块在 Android 上**不跑**。
 * 黄金对齐第一阶段不覆盖本类，第二阶段单独出报告。
 */
export class AdvancedBlendShapeExtractor {
  constructor() {
    this.baselineBrowHeight = 0;
    this.baselineCheekWidth = 0;
    this.baselineNoseWidth = 0;
    this.isBaselineSet = false;
    this._c = new Float32Array(6);
    this._c2 = new Float32Array(12);
  }

  /** Kotlin calibrate(landmarks, faceHeight, faceWidth)，需 landmarks >= 478。 */
  calibrate(lm, faceHeight, faceWidth) {
    if (!(faceHeight > 0) || !(faceWidth > 0)) return;
    const leftBrowY = lm[IMG_R_BROW_MIDDLE * 3 + 1];
    const leftEyeY = lm[IMG_R_EYE_UPPER[3] * 3 + 1];
    this.baselineBrowHeight = (leftEyeY - leftBrowY) / faceHeight;
    this.baselineCheekWidth = dist2D(lm, CHEEK_IMG_L[0], CHEEK_IMG_R[0]) / faceWidth;
    this.baselineNoseWidth = dist2D(lm, NOSE_IMG_L_WING, NOSE_IMG_R_WING) / faceWidth;
    this.isBaselineSet = true;
  }

  reset() {
    this.baselineBrowHeight = 0;
    this.baselineCheekWidth = 0;
    this.baselineNoseWidth = 0;
    this.isBaselineSet = false;
  }

  extractAdvancedBlendShapes(lm, out52, faceHeight, faceWidth) {
    this._extractBrow(lm, out52, faceHeight);
    this._extractCheek(lm, out52, faceWidth);
    this._extractJaw(lm, out52, faceWidth);
    this._extractNose(lm, out52, faceWidth);
    this._extractMouthAdvanced(lm, out52, faceWidth, faceHeight);
  }

  _extractBrow(lm, out52, faceHeight) {
    const leftBrowInner = IMG_R_BROW_INNER, leftBrowMiddle = IMG_R_BROW_MIDDLE;
    const rightBrowInner = IMG_L_BROW_INNER, rightBrowMiddle = IMG_L_BROW_MIDDLE;
    const noseRoot = NOSE_ROOT;
    const leftEyeTop = IMG_R_EYE_UPPER[3];
    const rightEyeTop = IMG_L_EYE_UPPER[3];
    const leftBrowToEye = (lm[leftEyeTop * 3 + 1] - lm[leftBrowMiddle * 3 + 1]) / faceHeight;
    const rightBrowToEye = (lm[rightEyeTop * 3 + 1] - lm[rightBrowMiddle * 3 + 1]) / faceHeight;
    const baseline = this.isBaselineSet ? this.baselineBrowHeight : 0.08;
    const leftBrowDownOffset = baseline - leftBrowToEye;
    const rightBrowDownOffset = baseline - rightBrowToEye;
    out52[BS_INDEX.browDownLeft] = clamp01(leftBrowDownOffset * BROW_DOWN_SENSITIVITY / 0.02);
    out52[BS_INDEX.browDownRight] = clamp01(rightBrowDownOffset * BROW_DOWN_SENSITIVITY / 0.02);
    const browInnerY = (lm[leftBrowInner * 3 + 1] + lm[rightBrowInner * 3 + 1]) / 2;
    const innerUpOffset = (lm[noseRoot * 3 + 1] - browInnerY) / faceHeight - 0.05;
    out52[BS_INDEX.browInnerUp] = clamp01(innerUpOffset * BROW_UP_SENSITIVITY / 0.02);
    const leftOuterUp = leftBrowToEye - baseline;
    const rightOuterUp = rightBrowToEye - baseline;
    out52[BS_INDEX.browOuterUpLeft] = clamp01(leftOuterUp * BROW_UP_SENSITIVITY / 0.02);
    out52[BS_INDEX.browOuterUpRight] = clamp01(rightOuterUp * BROW_UP_SENSITIVITY / 0.02);
  }

  _extractCheek(lm, out52, faceWidth) {
    const c = this._c;
    centerOf(lm, CHEEK_IMG_L, c, 0);
    centerOf(lm, CHEEK_IMG_R, c, 3);
    const dx = c[0] - c[3], dy = c[1] - c[4];
    const cheekDistance = Math.sqrt(dx * dx + dy * dy) / faceWidth;
    const cheekBaseline = this.isBaselineSet ? this.baselineCheekWidth : 0.8;
    const puffOffset = cheekDistance - cheekBaseline;
    out52[BS_INDEX.cheekPuff] = clamp01(puffOffset * CHEEK_PUFF_SENSITIVITY / 0.05);
    const leftSquintDist = dist2D(lm, CHEEK_IMG_L_LOWER_EYELID, CHEEK_IMG_L_SQUINT) / faceWidth;
    const rightSquintDist = dist2D(lm, CHEEK_IMG_R_LOWER_EYELID, CHEEK_IMG_R_SQUINT) / faceWidth;
    const baselineSquintDist = 0.08;
    out52[BS_INDEX.cheekSquintLeft] = clamp01((baselineSquintDist - leftSquintDist) * CHEEK_SQUINT_SENSITIVITY / 0.03);
    out52[BS_INDEX.cheekSquintRight] = clamp01((baselineSquintDist - rightSquintDist) * CHEEK_SQUINT_SENSITIVITY / 0.03);
  }

  _extractJaw(lm, out52, faceWidth) {
    const chinCenter = JAW_CHIN_CENTER;
    const mouthCenter = UPPER_LIP_CENTER;
    const noseRoot = NOSE_ROOT;
    const faceCenterX = (lm[noseRoot * 3] + lm[mouthCenter * 3]) / 2;
    const chinOffset = (lm[chinCenter * 3] - faceCenterX) / faceWidth;
    out52[BS_INDEX.jawRight] = clamp01(chinOffset * JAW_MOVE_SENSITIVITY / 0.02);
    out52[BS_INDEX.jawLeft] = clamp01(-chinOffset * JAW_MOVE_SENSITIVITY / 0.02);
    const chinZOffset = lm[noseRoot * 3 + 2] - lm[chinCenter * 3 + 2];
    out52[BS_INDEX.jawForward] = clamp01(chinZOffset * JAW_MOVE_SENSITIVITY / 0.05);
  }

  _extractNose(lm, out52, faceWidth) {
    const noseTip = NOSE_TIP;
    const leftWingY = lm[NOSE_IMG_L_WING * 3 + 1] - lm[noseTip * 3 + 1];
    const rightWingY = lm[NOSE_IMG_R_WING * 3 + 1] - lm[noseTip * 3 + 1];
    const leftSneerZ = meanOf(lm, NOSE_L_SNEER_AREA, 2);
    const rightSneerZ = meanOf(lm, NOSE_R_SNEER_AREA, 2);
    const baselineZ = lm[noseTip * 3 + 2];
    const leftSneerOffset = (baselineZ - leftSneerZ) + (-leftWingY * 0.5);
    const rightSneerOffset = (baselineZ - rightSneerZ) + (-rightWingY * 0.5);
    out52[BS_INDEX.noseSneerLeft] = clamp01(leftSneerOffset * NOSE_SNEER_SENSITIVITY / 0.02);
    out52[BS_INDEX.noseSneerRight] = clamp01(rightSneerOffset * NOSE_SNEER_SENSITIVITY / 0.02);
  }

  _extractMouthAdvanced(lm, out52, faceWidth, faceHeight) {
    const leftCorner = MOUTH_IMG_L_CORNER, rightCorner = MOUTH_IMG_R_CORNER;
    const upperLipTop = UPPER_LIP_TOP, upperLipCenter = UPPER_LIP_CENTER;
    const lowerLipCenter = LOWER_LIP_CENTER, lowerLipBottom = LOWER_LIP_BOTTOM;
    const noseTip = NOSE_TIP;
    const mouthCenterX = (lm[leftCorner * 3] + lm[rightCorner * 3]) / 2;
    const mouthCenterY = (lm[upperLipCenter * 3 + 1] + lm[lowerLipCenter * 3 + 1]) / 2;
    const faceCenterX = lm[noseTip * 3];
    const mouthOffsetX = (mouthCenterX - faceCenterX) / faceWidth;
    out52[BS_INDEX.mouthRight] = clamp01(mouthOffsetX * MOUTH_MOVE_SENSITIVITY / 0.02);
    out52[BS_INDEX.mouthLeft] = clamp01(-mouthOffsetX * MOUTH_MOVE_SENSITIVITY / 0.02);
    const mouthAvgZ = (lm[upperLipCenter * 3 + 2] + lm[lowerLipCenter * 3 + 2] +
      lm[leftCorner * 3 + 2] + lm[rightCorner * 3 + 2]) / 4;
    const mouthForwardOffset = lm[noseTip * 3 + 2] - mouthAvgZ;
    const mouthWidth = dist2D(lm, leftCorner, rightCorner) / faceWidth;
    const mouthWidthBaseline = 0.35;
    const widthNarrowing = mouthWidthBaseline - mouthWidth;
    out52[BS_INDEX.mouthFunnel] = clamp01((mouthForwardOffset * 0.5 + widthNarrowing) * 3 / 0.05);
    out52[BS_INDEX.mouthPucker] = clamp01(widthNarrowing * 4 / 0.05);
    const upperLipHeight = (lm[upperLipCenter * 3 + 1] - lm[upperLipTop * 3 + 1]) / faceHeight;
    const lowerLipHeight = (lm[lowerLipBottom * 3 + 1] - lm[lowerLipCenter * 3 + 1]) / faceHeight;
    const lipHeightBaseline = 0.02;
    out52[BS_INDEX.mouthRollUpper] = clamp01((lipHeightBaseline - upperLipHeight) * LIP_ROLL_SENSITIVITY / 0.01);
    out52[BS_INDEX.mouthRollLower] = clamp01((lipHeightBaseline - lowerLipHeight) * LIP_ROLL_SENSITIVITY / 0.01);
    const lipOpenHeight = (lm[lowerLipCenter * 3 + 1] - lm[upperLipCenter * 3 + 1]) / faceHeight;
    const lipOpenBaseline = 0.02;
    const shrugOffset = lipOpenBaseline - lipOpenHeight;
    out52[BS_INDEX.mouthShrugLower] = clamp01(shrugOffset * 3 / 0.01);
    out52[BS_INDEX.mouthShrugUpper] = clamp01(shrugOffset * 2 / 0.01);
    const mouthWidthOffset = mouthWidth - mouthWidthBaseline;
    out52[BS_INDEX.mouthStretchLeft] = clamp01(mouthWidthOffset * LIP_STRETCH_SENSITIVITY / 0.03);
    out52[BS_INDEX.mouthStretchRight] = clamp01(mouthWidthOffset * LIP_STRETCH_SENSITIVITY / 0.03);
    const dimpleDepth = 0.01;
    out52[BS_INDEX.mouthDimpleLeft] = clamp01((lm[MOUTH_IMG_L_DIMPLE * 3 + 2] - lm[leftCorner * 3 + 2]) * 5 / dimpleDepth);
    out52[BS_INDEX.mouthDimpleRight] = clamp01((lm[MOUTH_IMG_R_DIMPLE * 3 + 2] - lm[rightCorner * 3 + 2]) * 5 / dimpleDepth);
    const totalLipHeight = (lm[lowerLipBottom * 3 + 1] - lm[upperLipTop * 3 + 1]) / faceHeight;
    const pressOffset = 0.06 - totalLipHeight;
    out52[BS_INDEX.mouthPressLeft] = clamp01(pressOffset * 3 / 0.02);
    out52[BS_INDEX.mouthPressRight] = clamp01(pressOffset * 3 / 0.02);
    const leftLowerY = meanOf(lm, LOWER_LIP_IMG_L, 1);
    const rightLowerY = meanOf(lm, LOWER_LIP_IMG_R, 1);
    out52[BS_INDEX.mouthLowerDownLeft] = clamp01((leftLowerY - mouthCenterY) / faceHeight * 3 / 0.03);
    out52[BS_INDEX.mouthLowerDownRight] = clamp01((rightLowerY - mouthCenterY) / faceHeight * 3 / 0.03);
    const leftUpperY = meanOf(lm, UPPER_LIP_IMG_L, 1);
    const rightUpperY = meanOf(lm, UPPER_LIP_IMG_R, 1);
    out52[BS_INDEX.mouthUpperUpLeft] = clamp01((mouthCenterY - leftUpperY) / faceHeight * 3 / 0.03);
    out52[BS_INDEX.mouthUpperUpRight] = clamp01((mouthCenterY - rightUpperY) / faceHeight * 3 / 0.03);
    const lipDistance = dist2D(lm, lowerLipCenter, upperLipCenter) / faceHeight;
    const closeThreshold = 0.01;
    out52[BS_INDEX.mouthClose] = clamp01((closeThreshold - lipDistance) * 5 / closeThreshold);
  }

  /**
   * extractEyeSquint：眼睛挤压（微笑时的眯眼）。
   * openness 介于 squintThreshold 与 openBaseline 之间才算 squint。
   */
  extractEyeSquint(lm, out52, faceHeight) {
    const leftOpenness = this._eyeOpenness(lm, SQUINT_L_UPPER, SQUINT_L_LOWER) / faceHeight;
    const rightOpenness = this._eyeOpenness(lm, SQUINT_R_UPPER, SQUINT_R_LOWER) / faceHeight;
    const openBaseline = 0.035;
    const squintThreshold = 0.025;
    out52[BS_INDEX.eyeSquintLeft] = clamp01(
      (leftOpenness < openBaseline && leftOpenness > squintThreshold)
        ? (openBaseline - leftOpenness) / (openBaseline - squintThreshold) : 0);
    out52[BS_INDEX.eyeSquintRight] = clamp01(
      (rightOpenness < openBaseline && rightOpenness > squintThreshold)
        ? (openBaseline - rightOpenness) / (openBaseline - squintThreshold) : 0);
  }

  _eyeOpenness(lm, upper, lower) {
    const count = Math.min(upper.length, lower.length);
    if (count === 0) return 0;
    let total = 0;
    for (let i = 0; i < count; i++) total += dist2D(lm, upper[i], lower[i]);
    return total / count;
  }
}

// ══════════════════════════════════════════════════════════════════════
// BlendShapeExtractor（编排器 = Kotlin BlendShapeExtractor + FaceCapturePipeline 的点位部分）
// ══════════════════════════════════════════════════════════════════════

/**
 * 478 点 -> 52 维 BlendShape 提取器。
 *
 * ⚠️ advancedMode 默认 **false**（与 FaceCapturePipeline 的 new BlendShapeExtractor() 一致）。
 *
 * 与 Kotlin 的对应关系：
 *   - 内部持有 SimpleEye / SimpleMouth / SimpleSmile / Advanced 四个**有状态**对象
 *     （校准累计器就在这里，不能由外部二次喂入，否则基线双份）；
 *   - calibrate(avgLandmarks) **只校准 Advanced**（Kotlin BlendShapeExtractor.calibrate
 *     的真实职责；那句「All BlendShape extractors calibrated」日志是误导）；
 *   - setCalibrationPose(pitch, yaw, roll) 把姿态基线喂给眼/嘴/微笑的相对补偿。
 */
export class BlendShapeExtractor {
  /** @param {{advancedMode?: boolean, cameraFovDeg?: number}} [options] */
  constructor(options = {}) {
    this.advancedMode = options.advancedMode === true;
    this.simpleEyeExtractor = new SimpleEyeExtractor();
    this.simpleMouthExtractor = new SimpleMouthExtractor();
    this.simpleSmileExtractor = new SimpleSmileExtractor();
    this.advancedExtractor = new AdvancedBlendShapeExtractor();
    if (Number.isFinite(options.cameraFovDeg)) this.setCameraFov(options.cameraFovDeg);
    this._pts = new Float32Array(478 * 3);
    this._calSum = new Float64Array(478 * 3);
    this._calFrames = 0;
    this._calAvg = new Float32Array(478 * 3);
  }

  /** Kotlin BlendShapeExtractor.isCalibrating（眼||嘴||微笑，**不含 advanced**）。 */
  get isCalibrating() {
    return this.simpleEyeExtractor.isCalibrating ||
      this.simpleMouthExtractor.isCalibrating ||
      this.simpleSmileExtractor.isCalibrating;
  }

  setCameraFov(fovDegrees) { this.simpleEyeExtractor.setCameraFov(fovDegrees); }

  /**
   * Kotlin setCalibrationPose(pitch, yaw, roll)。
   * ⚠️ 只能在采样窗口结束时调一次（会覆盖 SimpleEye 在左眼 finish 瞬间记的姿态）。
   */
  setCalibrationPose(pitch, yaw, roll) {
    this.simpleMouthExtractor.setCalibrationPose(pitch, yaw, roll);
    this.simpleSmileExtractor.setCalibrationPose(pitch, yaw, roll);
    this.simpleEyeExtractor.setCalibrationPose(pitch, yaw);
  }

  /**
   * Kotlin BlendShapeExtractor.calibrate(keyPoints, landmarks)。
   * ⚠️ **只校准 AdvancedBlendShapeExtractor**；Simple* 在 extract 过程中自我校准。
   */
  calibrate(avgLandmarks, faceHeight, faceWidth) {
    if (avgLandmarks && avgLandmarks.length >= 478 * 3 && this.advancedMode) {
      this.advancedExtractor.calibrate(avgLandmarks, faceHeight, faceWidth);
    }
  }

  /** Kotlin updateFaceDistanceEstimateNormalized（动态透视补偿）。 */
  updateFaceDistanceEstimateNormalized(w, h) {
    this.simpleEyeExtractor.updateFaceDistanceEstimateNormalized(w, h);
  }

  /**
   * FaceCapturePipeline.updateFaceDistanceFromLandmarks 的移植：
   * 从 478 点 bbox 估人脸大小（**用未做像素转换的原始坐标**，与 Kotlin 一致）。
   */
  updateFaceDistanceFromLandmarks(rawLandmarks, imageWidth, imageHeight) {
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (let i = 0; i < 478; i++) {
      const x = rawLandmarks[i * 3], y = rawLandmarks[i * 3 + 1];
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
    if (!(minX < maxX) || !(minY < maxY)) return;
    let faceWidth, faceHeight;
    if (maxX <= 1.5 && maxY <= 1.5 && minX >= -0.5 && minY >= -0.5) {
      faceWidth = maxX - minX;
      faceHeight = maxY - minY;
    } else if (imageWidth > 0 && imageHeight > 0) {
      faceWidth = (maxX - minX) / imageWidth;
      faceHeight = (maxY - minY) / imageHeight;
    } else {
      return;
    }
    this.updateFaceDistanceEstimateNormalized(faceWidth, faceHeight);
  }

  /**
   * FaceLandmarkIndices.extractKeyPoints 的像素转换规则：
   * 「给了图幅尺寸且 landmarks[0].x <= 1 && y <= 1 -> 逐点乘 W/H（z 不变）」。
   */
  _preparePoints(rawLandmarks, imageWidth, imageHeight) {
    const pts = this._pts;
    const convert = imageWidth > 0 && imageHeight > 0 &&
      rawLandmarks[0] <= 1 && rawLandmarks[1] <= 1;
    if (convert) {
      for (let i = 0; i < 478; i++) {
        pts[i * 3] = rawLandmarks[i * 3] * imageWidth;
        pts[i * 3 + 1] = rawLandmarks[i * 3 + 1] * imageHeight;
        pts[i * 3 + 2] = rawLandmarks[i * 3 + 2];
      }
    } else {
      pts.set(rawLandmarks.subarray(0, 478 * 3));
    }
    return pts;
  }

  /**
   * 提取完整的 52 维 BlendShape（含高级表情，若开启）。
   *
   * @param {Float32Array} rawLandmarks 478*3 交错点位
   * @param {Float32Array} out52 就地写，长度 >= 52（BS_INDEX 下标序）
   * @param {number[]|Float32Array|null} headPoseRaw [pitch, yaw, roll] **原始**头姿
   *   ⚠️ FaceCapturePipeline 传的是 rawPose 而不是 relativePose；
   *   相对补偿由 setCalibrationPose(baseline) 承担。
   * @param {{width?: number, height?: number, nowMs?: number}} [ctx] 帧图幅与时间
   */
  extractFullBlendShapes(rawLandmarks, out52, headPoseRaw, ctx) {
    const imageWidth = ctx && ctx.width ? ctx.width : 0;
    const imageHeight = ctx && ctx.height ? ctx.height : 0;
    const nowMs = ctx && Number.isFinite(ctx.nowMs) ? ctx.nowMs : 0;

    this.updateFaceDistanceFromLandmarks(rawLandmarks, imageWidth, imageHeight);
    const pts = this._preparePoints(rawLandmarks, imageWidth, imageHeight);

    const faceWidth = dist2D(pts, IMG_R_EYE_OUTER, IMG_L_EYE_OUTER);
    const faceHeight = dist2D(pts, NOSE_ROOT, FACE_OVAL_BOTTOM);

    // extractBlendShapes 里 new ARKitBlendShapes() 的等价物
    out52.fill(0);
    out52[BS_INDEX.neutral] = 0;

    this.simpleEyeExtractor.extractEyeBlendShapes(pts, out52, headPoseRaw, nowMs);
    this._extractEyeGaze(pts, out52);
    this.simpleMouthExtractor.extractMouthBlendShapes(pts, out52, headPoseRaw, nowMs);
    this.simpleSmileExtractor.extractSmileBlendShapes(pts, out52, headPoseRaw, faceHeight, nowMs);

    if (this.advancedMode) {
      this.advancedExtractor.extractAdvancedBlendShapes(pts, out52, faceHeight, faceWidth);
      this.advancedExtractor.extractEyeSquint(pts, out52, faceHeight);
    }

    // clamp()：**只 clamp 7 个字段**（Landmark.kt:clamp）
    out52[BS_INDEX.eyeBlinkLeft] = clamp01(out52[BS_INDEX.eyeBlinkLeft]);
    out52[BS_INDEX.eyeBlinkRight] = clamp01(out52[BS_INDEX.eyeBlinkRight]);
    out52[BS_INDEX.eyeWideLeft] = clamp01(out52[BS_INDEX.eyeWideLeft]);
    out52[BS_INDEX.eyeWideRight] = clamp01(out52[BS_INDEX.eyeWideRight]);
    out52[BS_INDEX.jawOpen] = clamp01(out52[BS_INDEX.jawOpen]);
    out52[BS_INDEX.mouthSmileLeft] = clamp01(out52[BS_INDEX.mouthSmileLeft]);
    out52[BS_INDEX.mouthSmileRight] = clamp01(out52[BS_INDEX.mouthSmileRight]);
    return out52;
  }

  _extractEyeGaze(pts, out52) {
    this._eyeGaze(pts, out52, IMG_R_EYE_UPPER, IMG_R_EYE_LOWER, IMG_R_EYE_INNER, IMG_R_EYE_OUTER, IMG_R_IRIS_CENTER, true);
    this._eyeGaze(pts, out52, IMG_L_EYE_UPPER, IMG_L_EYE_LOWER, IMG_L_EYE_INNER, IMG_L_EYE_OUTER, IMG_L_IRIS_CENTER, false);
  }

  _eyeGaze(pts, out52, upper, lower, inner, outer, irisCenterIdx, isImageRightEye) {
    const eyeCenterX = (pts[inner * 3] + pts[outer * 3]) / 2;
    const eyeCenterY = (pts[inner * 3 + 1] + pts[outer * 3 + 1]) / 2;
    const eyeWidth = dist2D(pts, inner, outer);
    const c = this._c2 || (this._c2 = new Float32Array(12));
    centerOf(pts, upper, c, 0);
    centerOf(pts, lower, c, 3);
    const udX = c[0] - c[3], udY = c[1] - c[4];
    const eyeHeight = Math.sqrt(udX * udX + udY * udY);
    const i3 = irisCenterIdx * 3;
    const offsetX = (pts[i3] - eyeCenterX) / (eyeWidth / 2 + 1e-6);
    const offsetY = (pts[i3 + 1] - eyeCenterY) / (eyeHeight / 2 + 1e-6);
    const clampedOffsetX = clamp(offsetX, -1, 1);
    const clampedOffsetY = clamp(offsetY, -1, 1);
    let lookIn, lookOut;
    if (isImageRightEye) {
      lookOut = clamp01(clampedOffsetX);
      lookIn = clamp01(-clampedOffsetX);
    } else {
      lookIn = clamp01(clampedOffsetX);
      lookOut = clamp01(-clampedOffsetX);
    }
    const lookDown = clamp01(clampedOffsetY);
    const lookUp = clamp01(-clampedOffsetY);
    if (isImageRightEye) {
      out52[BS_INDEX.eyeLookInRight] = lookIn;
      out52[BS_INDEX.eyeLookOutRight] = lookOut;
      out52[BS_INDEX.eyeLookUpRight] = lookUp;
      out52[BS_INDEX.eyeLookDownRight] = lookDown;
    } else {
      out52[BS_INDEX.eyeLookInLeft] = lookIn;
      out52[BS_INDEX.eyeLookOutLeft] = lookOut;
      out52[BS_INDEX.eyeLookUpLeft] = lookUp;
      out52[BS_INDEX.eyeLookDownLeft] = lookDown;
    }
  }

  // ---- 校准窗口累计（对应 FaceCapturePipeline 的 478 点求和槽）----
  accumulateCalibrationLandmarks(rawLandmarks) {
    const s = this._calSum;
    for (let i = 0; i < 478 * 3; i++) s[i] += rawLandmarks[i];
    this._calFrames++;
  }

  averageAccumulatedLandmarks() {
    if (this._calFrames === 0) return null;
    const n = this._calFrames;
    const s = this._calSum, avg = this._calAvg;
    for (let i = 0; i < 478 * 3; i++) avg[i] = s[i] / n;
    return avg;
  }

  clearCalibrationAccumulator() {
    this._calSum.fill(0);
    this._calFrames = 0;
  }

  reset() {
    this.simpleMouthExtractor.reset();
    this.simpleSmileExtractor.reset();
    this.simpleEyeExtractor.resetCalibration();
    this.advancedExtractor.reset();
    this.clearCalibrationAccumulator();
  }
}
