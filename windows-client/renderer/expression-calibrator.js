/**
 * expression-calibrator.js — 中性表情采样窗口外壳
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 职责边界（重要，别扩大）：
 *   SimpleEye / SimpleMouth / SimpleSmile 的 EAR / MAR / smile 特征累计与阈值落地
 *   **全部在 extract 过程中自我完成**（各自独立的 1000ms + >=10 帧窗口）。
 *   本类**不做任何 EAR/MAR/smile 累计**，否则基线会变成两份、数值就错了。
 *   它只负责：
 *     1) 窗口进度观测与上报（frames / elapsedMs / remainingMs）；
 *     2) serialize() / load()：把校准结果导出 / 注入 CalibFile；
 *     3) reset()；
 *     4) advancedMode = true 时累计 478 点均值并调 extractor.calibrate(avgLandmarks)
 *        （那是 AdvancedBlendShapeExtractor 的基线）；默认 false 下整步是 no-op。
 *
 * ⚠️ BlendShapeExtractor.calibrate() **只校准 Advanced**（Kotlin
 *   BlendShapeExtractor.kt:295-301 的真实职责；那句「All BlendShape extractors
 *   calibrated」日志是误导）。
 *
 * ⚠️ isCalibrating 的合成口径（FaceCapturePipeline.kt:70 + BlendShapeExtractor.kt:24-26）：
 *   !poseIsCalibrated || 眼 || 嘴 || 微笑（**不含 advanced**；眼是左右两套独立窗口，
 *   可能在不同帧落地）。
 */

export class ExpressionCalibrator {
  /**
   * @param {import('./blendshape-extractor.js').BlendShapeExtractor} extractor
   *   校准累计器持有者（SimpleEye / SimpleMouth / SimpleSmile / Advanced 的状态都在它内部）
   * @param {{durationMs?: number, minFrames?: number}} [options]
   */
  constructor(extractor, options = {}) {
    this.extractor = extractor;
    this.durationMs = Number.isFinite(options.durationMs) ? options.durationMs : 1000;
    this.minFrames = Number.isFinite(options.minFrames) ? options.minFrames : 10;
    this.reset();
  }

  /** 合成校准态（眼||嘴||微笑；**不含 advanced**）。 */
  get isCalibrating() { return this.extractor.isCalibrating; }

  /** 采样进度（供 calib-state 消息上报）。 */
  progress(nowMs) {
    // 用 null 作「尚未开始」哨兵（不能用 0：nowMs 本身可能就是 0）
    const elapsed = this._startMs === null ? 0 : Math.max(0, nowMs - this._startMs);
    return {
      frames: this._frames,
      elapsedMs: elapsed,
      remainingMs: Math.max(0, this.durationMs - elapsed),
      calibrating: this.extractor.isCalibrating,
    };
  }

  begin(nowMs) {
    this.reset();
    this._startMs = nowMs;
    this._active = true;
  }

  /**
   * 喂一帧。**不累计任何 EAR/MAR/smile**（extractor 在 extractFullBlendShapes 里已做）。
   * 只统计窗口进度；advancedMode 时顺带累计 478 点均值。
   *
   * @param {Float32Array} landmarks 478*3
   * @param {number[]|Float32Array|null} _headPoseRaw 仅为接口对称保留，本类不使用
   * @param {number} nowMs
   */
  add(landmarks, _headPoseRaw, nowMs) {
    if (!this._active) return false;
    if (this._frames === 0) this._startMs = nowMs;
    this._frames++;
    if (this.extractor.advancedMode) {
      this.extractor.accumulateCalibrationLandmarks(landmarks);
    }
    return this._frames >= this.minFrames && (nowMs - this._startMs) >= this.durationMs;
  }

  /**
   * 窗口落地。
   * ⚠️ 只有 advancedMode = true 时才调 extractor.calibrate(avgLandmarks)，
   * 否则 no-op（眼/嘴/微笑基线已经在 extract 里自动落地了）。
   * ⚠️ 前置：poseCalib.finish() -> extractor.setCalibrationPose(baseline) 必须先发生。
   */
  finish() {
    if (this.extractor.advancedMode) {
      const avg = this.extractor.averageAccumulatedLandmarks();
      if (avg) {
        const pts = avg;
        const faceWidth = dist2D(pts, 33, 263);
        const faceHeight = dist2D(pts, 168, 152);
        this.extractor.calibrate(avg, faceHeight, faceWidth);
      }
    }
    this.extractor.clearCalibrationAccumulator();
    this._active = false;
    return this.serialize();
  }

  cancel() {
    this.extractor.clearCalibrationAccumulator();
    this._active = false;
  }

  /**
   * 从 extractor 内部状态读出校准结果 -> CalibFile["expression"]。
   *（不是从本类自己累计的数据算 —— 本类没有累计数据。）
   */
  serialize() {
    const eye = this.extractor.simpleEyeExtractor;
    const mouth = this.extractor.simpleMouthExtractor;
    const smile = this.extractor.simpleSmileExtractor;
    const adv = this.extractor.advancedExtractor;
    return {
      eye: {
        left: {
          closedRatio: eye.imageLeftEyeCalibration.calibratedClosedRatio,
          openRatio: eye.imageLeftEyeCalibration.calibratedOpenRatio,
          wideRatio: eye.imageLeftEyeCalibration.calibratedWideRatio,
          meanEAR: eye.imageLeftEyeCalibration.meanEAR,
          stdEAR: eye.imageLeftEyeCalibration.stdEAR,
          isCalibrated: eye.imageLeftEyeCalibration.isCalibrated,
        },
        right: {
          closedRatio: eye.imageRightEyeCalibration.calibratedClosedRatio,
          openRatio: eye.imageRightEyeCalibration.calibratedOpenRatio,
          wideRatio: eye.imageRightEyeCalibration.calibratedWideRatio,
          meanEAR: eye.imageRightEyeCalibration.meanEAR,
          stdEAR: eye.imageRightEyeCalibration.stdEAR,
          isCalibrated: eye.imageRightEyeCalibration.isCalibrated,
        },
        sensitivity: eye.constructor.eyeSensitivity,
        closedThresholdOffset: eye.closedThresholdOffset,
        openThresholdOffset: eye.openThresholdOffset,
        calibrationPitch: eye.calibrationPitch,
        calibrationYaw: eye.calibrationYaw,
        calibrationFaceRatio: eye.calibrationFaceRatio,
      },
      mouth: {
        closedRatio: mouth.mouthCalibration.calibratedClosedRatio,
        openRatio: mouth.mouthCalibration.calibratedOpenRatio,
        meanMAR: mouth.mouthCalibration.meanMAR,
        stdMAR: mouth.mouthCalibration.stdMAR,
        isCalibrated: mouth.mouthCalibration.isCalibrated,
      },
      smile: {
        baselineLeftFeature: smile.calibration.baselineLeftFeature,
        baselineRightFeature: smile.calibration.baselineRightFeature,
        stdLeftFeature: smile.calibration.stdLeftFeature,
        stdRightFeature: smile.calibration.stdRightFeature,
        observedSmileMax: smile.calibration.observedSmileMax,
        observedFrownMax: smile.calibration.observedFrownMax,
        isCalibrated: smile.calibration.isCalibrated,
      },
      advanced: {
        baselineBrowHeight: adv.baselineBrowHeight,
        baselineCheekWidth: adv.baselineCheekWidth,
        baselineNoseWidth: adv.baselineNoseWidth,
        isBaselineSet: adv.isBaselineSet,
      },
    };
  }

  /** 注入 CalibFile["expression"]（load 后立刻可用，不再重采样）。 */
  load(obj) {
    if (!obj) return;
    const eye = this.extractor.simpleEyeExtractor;
    const mouth = this.extractor.simpleMouthExtractor;
    const smile = this.extractor.simpleSmileExtractor;
    const adv = this.extractor.advancedExtractor;

    if (obj.eye) {
      applyEye(eye.imageLeftEyeCalibration, obj.eye.left);
      applyEye(eye.imageRightEyeCalibration, obj.eye.right);
      if (Number.isFinite(obj.eye.sensitivity)) eye.constructor.eyeSensitivity = obj.eye.sensitivity;
      if (Number.isFinite(obj.eye.closedThresholdOffset)) eye.closedThresholdOffset = obj.eye.closedThresholdOffset;
      if (Number.isFinite(obj.eye.openThresholdOffset)) eye.openThresholdOffset = obj.eye.openThresholdOffset;
      if (Number.isFinite(obj.eye.calibrationPitch)) eye.calibrationPitch = obj.eye.calibrationPitch;
      if (Number.isFinite(obj.eye.calibrationYaw)) eye.calibrationYaw = obj.eye.calibrationYaw;
      if (Number.isFinite(obj.eye.calibrationFaceRatio)) eye.calibrationFaceRatio = obj.eye.calibrationFaceRatio;
    }
    if (obj.mouth) {
      const m = mouth.mouthCalibration;
      if (Number.isFinite(obj.mouth.closedRatio)) m.calibratedClosedRatio = obj.mouth.closedRatio;
      if (Number.isFinite(obj.mouth.openRatio)) m.calibratedOpenRatio = obj.mouth.openRatio;
      if (Number.isFinite(obj.mouth.meanMAR)) m.meanMAR = obj.mouth.meanMAR;
      if (Number.isFinite(obj.mouth.stdMAR)) m.stdMAR = obj.mouth.stdMAR;
      m.isCalibrated = obj.mouth.isCalibrated !== false;
    }
    if (obj.smile) {
      const s = smile.calibration;
      if (Number.isFinite(obj.smile.baselineLeftFeature)) s.baselineLeftFeature = obj.smile.baselineLeftFeature;
      if (Number.isFinite(obj.smile.baselineRightFeature)) s.baselineRightFeature = obj.smile.baselineRightFeature;
      if (Number.isFinite(obj.smile.stdLeftFeature)) s.stdLeftFeature = obj.smile.stdLeftFeature;
      if (Number.isFinite(obj.smile.stdRightFeature)) s.stdRightFeature = obj.smile.stdRightFeature;
      if (Number.isFinite(obj.smile.observedSmileMax)) s.observedSmileMax = obj.smile.observedSmileMax;
      if (Number.isFinite(obj.smile.observedFrownMax)) s.observedFrownMax = obj.smile.observedFrownMax;
      s.isCalibrated = obj.smile.isCalibrated !== false;
    }
    if (obj.advanced) {
      adv.baselineBrowHeight = Number(obj.advanced.baselineBrowHeight) || 0;
      adv.baselineCheekWidth = Number(obj.advanced.baselineCheekWidth) || 0;
      adv.baselineNoseWidth = Number(obj.advanced.baselineNoseWidth) || 0;
      adv.isBaselineSet = obj.advanced.isBaselineSet === true;
    }
  }

  reset() {
    this._active = false;
    this._startMs = null;
    this._frames = 0;
  }
}

function applyEye(cal, src) {
  if (!src) return;
  if (Number.isFinite(src.closedRatio)) cal.calibratedClosedRatio = src.closedRatio;
  if (Number.isFinite(src.openRatio)) cal.calibratedOpenRatio = src.openRatio;
  if (Number.isFinite(src.wideRatio)) cal.calibratedWideRatio = src.wideRatio;
  if (Number.isFinite(src.meanEAR)) cal.meanEAR = src.meanEAR;
  if (Number.isFinite(src.stdEAR)) cal.stdEAR = src.stdEAR;
  cal.isCalibrated = src.isCalibrated !== false;
}

function dist2D(lm, ia, ib) {
  const a = ia * 3, b = ib * 3;
  const dx = lm[a] - lm[b];
  const dy = lm[a + 1] - lm[b + 1];
  return Math.sqrt(dx * dx + dy * dy);
}
