/**
 * pose-calibrator.js — 头姿基线校准
 *
 * 移植来源：FaceCapturePipeline.kt 的姿态基线段（:119-121 及校准窗口逻辑）。
 *
 * 语义：
 *   - 校准窗口按「时长 + 最小帧数」双门限触发（CALIBRATION_DURATION_MS = 1000ms、
 *     MIN_CALIBRATION_FRAMES = 10），而不是写死帧数 —— 无论帧率高低，
 *     校准都覆盖大致相同的真实时间窗口；
 *   - baseline = 窗口内 raw pitch/yaw/roll 的算术平均，**除数是实际收集到的帧数**；
 *   - relativePose = rawPose - baseline；**未校准时 relativePose = rawPose**（Kotlin 同）。
 *
 * ⚠️ 顺序耦合：窗口落地后必须立刻调 extractor.setCalibrationPose(baseline) ——
 * 那一步会覆盖 SimpleEyeExtractor 在左眼 finish 瞬间记录的 calibrationPitch/Yaw/FaceRatio。
 * 采样中途不可调。
 */

/** CommonData: CALIBRATION_DURATION_MS = 1000L */
export const CALIBRATION_DURATION_MS = 1000;

/** CommonData: MIN_CALIBRATION_FRAMES = 10 */
export const MIN_CALIBRATION_FRAMES = 10;

export class PoseCalibrator {
  /** @param {{durationMs?: number, minFrames?: number}} [options] */
  constructor(options = {}) {
    this.durationMs = Number.isFinite(options.durationMs) ? options.durationMs : CALIBRATION_DURATION_MS;
    this.minFrames = Number.isFinite(options.minFrames) ? options.minFrames : MIN_CALIBRATION_FRAMES;
    this.reset();
  }

  get isCalibrated() { return this._calibrated; }

  /** 当前基线（未校准时为 [0,0,0]）。 */
  get baseline() { return [this._baselinePitch, this._baselineYaw, this._baselineRoll]; }

  /** 采样进度（供 UI / calib-state 上报）。 */
  progress(nowMs) {
    // 用 null 作「尚未开始」哨兵（不能用 0：nowMs 本身可能就是 0）
    const elapsed = this._startMs === null ? 0 : Math.max(0, nowMs - this._startMs);
    return {
      frames: this._frames,
      elapsedMs: elapsed,
      remainingMs: Math.max(0, this.durationMs - elapsed),
      calibrated: this._calibrated,
    };
  }

  /** 开始一次采样窗口。 */
  begin(nowMs) {
    this.reset();
    this._startMs = nowMs;
    this._active = true;
  }

  /**
   * 喂一帧**原始**头姿（不是相对姿态）。
   *
   * @returns {boolean} 本帧是否让窗口落地了（调用方据此触发 setCalibrationPose）
   */
  add(pitch, yaw, roll, nowMs) {
    if (this._calibrated || !this._active) return false;
    if (this._frames === 0) this._startMs = nowMs;
    this._frames++;
    this._sumPitch += pitch;
    this._sumYaw += yaw;
    this._sumRoll += roll;

    const elapsedMs = nowMs - this._startMs;
    const windowReady = elapsedMs >= this.durationMs && this._frames >= this.minFrames;
    if (!windowReady) return false;

    // 除数必须是实际收集到的帧数（变长窗口下样本数不同）
    const n = this._frames;
    this._baselinePitch = this._sumPitch / n;
    this._baselineYaw = this._sumYaw / n;
    this._baselineRoll = this._sumRoll / n;
    this._calibrated = true;
    return true;
  }

  /** 手动落定（窗口已满时等价于最后一帧 add 后的自动落定）。 */
  finish() {
    if (this._calibrated || this._frames === 0) return this._calibrated;
    const n = this._frames;
    this._baselinePitch = this._sumPitch / n;
    this._baselineYaw = this._sumYaw / n;
    this._baselineRoll = this._sumRoll / n;
    this._calibrated = true;
    return true;
  }

  /**
   * 相对姿态角（Kotlin FaceCapturePipeline 的 relativePose 计算）。
   * **未校准时原样返回 raw**。
   *
   * @param {Float32Array|number[]} out 长度 >= 3，就地写 [pitch, yaw, roll]
   */
  relativePoseInto(out, pitch, yaw, roll) {
    if (this._calibrated) {
      out[0] = pitch - this._baselinePitch;
      out[1] = yaw - this._baselineYaw;
      out[2] = roll - this._baselineRoll;
    } else {
      out[0] = pitch;
      out[1] = yaw;
      out[2] = roll;
    }
    return out;
  }

  /** 便捷形式（会新建数组；热路径用 relativePoseInto）。 */
  relativePose(pitch, yaw, roll) {
    const out = [0, 0, 0];
    return this.relativePoseInto(out, pitch, yaw, roll);
  }

  /** 导出到 CalibFile["pose"]。 */
  serialize() {
    return {
      baseline: {
        pitch: this._baselinePitch,
        yaw: this._baselineYaw,
        roll: this._baselineRoll,
      },
      calibrated: this._calibrated,
      frames: this._frames,
      durationMs: this.durationMs,
      minFrames: this.minFrames,
    };
  }

  /** 从 CalibFile["pose"] 注入（load 后立刻可用，不再重采样）。 */
  load(obj) {
    this.reset();
    if (!obj || !obj.baseline) return;
    this._baselinePitch = Number(obj.baseline.pitch) || 0;
    this._baselineYaw = Number(obj.baseline.yaw) || 0;
    this._baselineRoll = Number(obj.baseline.roll) || 0;
    this._calibrated = obj.calibrated !== false;
    this._frames = Number(obj.frames) || 0;
  }

  reset() {
    this._active = false;
    this._startMs = null;
    this._frames = 0;
    this._sumPitch = 0;
    this._sumYaw = 0;
    this._sumRoll = 0;
    this._baselinePitch = 0;
    this._baselineYaw = 0;
    this._baselineRoll = 0;
    this._calibrated = false;
  }
}
