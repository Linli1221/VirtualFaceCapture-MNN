/**
 * body-fusion.js — 上半身融合（移植 MediapipeUpperBody 的后处理段）
 *
 * 移植来源（逐条对应）：
 *   MediapipeUpperBody/src/main/java/com/example/mediapipeupperbody/
 *     OneEuroFilter.kt / UpperBodySmoother.kt / TorsoBaseline.kt / UpperBodyGeometry.kt
 *   CommonData/src/main/java/com/example/commondata/UpperBodyPose.kt（13 点协议语义）
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 13 点协议顺序（UpperBodyLandmarkIndex.ORDER = mapping.js BODY_LANDMARK_ORDER）：
 *   0 nose / 1 eye_left / 2 eye_right / 3 ear_left / 4 ear_right /
 *   5 shoulder_left / 6 shoulder_right / 7 elbow_left / 8 elbow_right /
 *   9 wrist_left / 10 wrist_right / 11 hip_left / 12 hip_right
 * ⚠️ ORDER 变更 = 协议不兼容，必须三处同步（UpperBodyPose.kt / track.ub 序列化 /
 *   windows mapping.js）。
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 两套 One-Euro 参数**互不混用**（本文件用 MediapipeUpperBody 那套）：
 *   - 本文件 OneEuroFilter：minCutoff = 1.2, beta = 0.02, derivCutoff = 1.0
 *   - temporal-filter.js 的 OneEuroFilterFace：minCutoff = 0.05, beta = 80, derivCutoff = 1.0
 *   改参数前先看清在哪个文件里。
 *
 * 输出形态（win-integrate 的 worker 契约）：
 *   process(points13, dtSeconds) -> {torso, points, tracking, quality, ubT}
 *   - torso  = TorsoBaseline 之后的**相对**躯干角 [roll, yaw]（度）
 *   - points = 平滑后的 13 点（x,y,z,v 交错，visibility 不平滑）
 *   ±30 单位与量程缩放归 mapping.js / live2d.js，本模块不做。
 */

// ============ UpperBodyGeometry 常量 ============
/** 一个点算「可信」的可见度阈值（协议 §5.3）。 */
export const RELIABLE_VISIBILITY = 0.5;
/** 13 点里至少多少个可信才认为本帧 tracking（契约：> 0.5 的点 >= 7）。 */
export const MIN_TRACKING_POINTS = 7;
/** 躯干线（肩 / 肘 / 胯）可用所需的最低可见度。 */
export const MIN_TORSO_VISIBILITY = 0.3;
/** 归一化坐标下水平基准的最小值，避免两肩几乎重合时 atan2 爆掉。 */
export const MIN_REFERENCE_SPAN = 0.02;

// ============ OneEuroFilter 常量（MediapipeUpperBody/OneEuroFilter.kt）============
export const DEFAULT_MIN_CUTOFF = 1.2;
export const DEFAULT_BETA = 0.02;
export const DEFAULT_DERIVATIVE_CUTOFF = 1.0;
const MIN_DT_SECONDS = 1 / 1000;
const MAX_DT_SECONDS = 0.5;

// ============ UpperBodySmoother 常量 ============
/** 躯干角截止频率比位置更低：角度的噪声更显眼，宁可慢一点。 */
export const DEFAULT_TORSO_MIN_CUTOFF = 0.6;
export const DEFAULT_TORSO_BETA = 0.01;

// ============ TorsoBaseline 常量 ============
/** 默认校准帧数：约 10 个有效帧（15fps 下约 0.7 秒）。 */
export const DEFAULT_CALIBRATION_FRAMES = 10;

/**
 * One-Euro 低通滤波器（MediapipeUpperBody/OneEuroFilter.kt 逐条移植）。
 *
 * 与经典实现（Casiez et al. 2012）一致：
 *  - 对输入的一阶导数做低通，得到「速度」估计 edx；
 *  - 截止频率 cutoff = minCutoff + beta * |edx|；
 *  - 用 tau = 1 / (2*PI*cutoff) 算 alpha，输出一阶低通结果。
 *
 * 时间基准用**相邻两帧的时间差**（秒），由调用方传 dt。
 */
export class OneEuroFilter {
  /** @param {{minCutoff?: number, beta?: number, derivativeCutoff?: number}} [options] */
  constructor(options = {}) {
    this.minCutoff = options.minCutoff === undefined ? DEFAULT_MIN_CUTOFF : options.minCutoff;
    this.beta = options.beta === undefined ? DEFAULT_BETA : options.beta;
    this.derivativeCutoff = options.derivativeCutoff === undefined ? DEFAULT_DERIVATIVE_CUTOFF : options.derivativeCutoff;
    this._lastValue = NaN;
    this._lastDerivative = 0;
  }

  /** 是否已经收到过一个有效输入。 */
  get hasValue() { return Number.isFinite(this._lastValue); }

  /** 清空状态。重新捕获目标（或丢帧后重新出现）时调用。 */
  reset() { this._lastValue = NaN; this._lastDerivative = 0; }

  /** Kotlin alpha(cutoff, dt)：cutoff <= 0 时返回 1。 */
  static alpha(cutoff, dt) {
    if (!(cutoff > 0)) return 1;
    const tau = 1 / (2 * Math.PI * cutoff);
    return 1 / (1 + tau / dt);
  }

  /**
   * 过滤一个采样点。
   * @param {number} value 新采样值
   * @param {number} dtSeconds 与上一次采样的时间差（秒）；<= 0 / NaN 时按 1ms 处理
   * @returns {number} 平滑后的值；输入 NaN/Inf 时返回上一次有效值（从未有效则返回 0）
   */
  filter(value, dtSeconds) {
    if (!Number.isFinite(value)) {
      return Number.isFinite(this._lastValue) ? this._lastValue : 0;
    }
    if (!Number.isFinite(this._lastValue)) {
      this._lastValue = value;
      this._lastDerivative = 0;
      return value;
    }
    const dt = (Number.isFinite(dtSeconds) && dtSeconds > 0)
      ? Math.min(dtSeconds, MAX_DT_SECONDS)
      : MIN_DT_SECONDS;

    const rawDerivative = (value - this._lastValue) / dt;
    const derivativeAlpha = OneEuroFilter.alpha(this.derivativeCutoff, dt);
    this._lastDerivative += derivativeAlpha * (rawDerivative - this._lastDerivative);

    const cutoff = this.minCutoff + this.beta * Math.abs(this._lastDerivative);
    const valueAlpha = OneEuroFilter.alpha(cutoff, dt);
    const filtered = this._lastValue + valueAlpha * (value - this._lastValue);
    this._lastValue = filtered;
    return filtered;
  }
}

/**
 * 躯干角「相对基线」计算（TorsoBaseline.kt 逐条移植）。
 *
 * [reset] 之后先收集 calibrationFrames 个**有效帧**，用它们的均值作为中性姿态：
 *  - 校准期间输出 = (当前值 - 已收集帧均值) * 进度权重（0 -> 1）；
 *  - 校准完成后输出 = 当前值 - 基线；
 *  - 校准未完成就丢帧不会计入均值（apply 只在有效帧上调用）。
 */
export class TorsoBaseline {
  /** @param {number} [calibrationFrames] 默认 DEFAULT_CALIBRATION_FRAMES = 10 */
  constructor(calibrationFrames) {
    this.calibrationFrames = Number.isFinite(calibrationFrames) && calibrationFrames > 0
      ? Math.floor(calibrationFrames) : DEFAULT_CALIBRATION_FRAMES;
    this.reset();
  }

  get isCalibrated() { return this._collected >= this._effectiveFrames; }

  get _effectiveFrames() { return this.calibrationFrames > 0 ? this.calibrationFrames : 1; }

  reset() {
    this._collected = 0;
    this._sumRoll = 0;
    this._sumYaw = 0;
    this._baselineRoll = 0;
    this._baselineYaw = 0;
  }

  /** 当前基线（诊断用）。 */
  get baseline() { return [this._baselineRoll, this._baselineYaw]; }

  /**
   * 把原始躯干角转成相对基线的角度。
   * @param {number} roll @param {number} yaw 原始（未减基线）躯干角
   * @param {Float32Array|number[]} out 长度 >= 2，就地写 [roll, yaw]
   * @returns {Float32Array|number[]} out；非法输入写 [0,0] 且不污染校准均值
   */
  applyInto(out, roll, yaw) {
    if (!Number.isFinite(roll) || !Number.isFinite(yaw)) {
      out[0] = 0; out[1] = 0;
      return out;
    }
    if (this.isCalibrated) {
      out[0] = roll - this._baselineRoll;
      out[1] = yaw - this._baselineYaw;
      return out;
    }

    this._collected++;
    this._sumRoll += roll;
    this._sumYaw += yaw;
    const meanRoll = this._sumRoll / this._collected;
    const meanYaw = this._sumYaw / this._collected;

    if (this._collected >= this._effectiveFrames) {
      this._baselineRoll = meanRoll;
      this._baselineYaw = meanYaw;
    }

    // effectiveFrames 内从 0 平滑过渡到「相对当前均值」；校准完成时权重正好是 1，
    // 且均值 == 基线，所以切到稳态不会有跳变。
    const weight = Math.min(1, this._collected / this._effectiveFrames);
    out[0] = (roll - meanRoll) * weight;
    out[1] = (yaw - meanYaw) * weight;
    return out;
  }

  /** 便捷形式（会新建数组）。 */
  apply(roll, yaw) { return this.applyInto([0, 0], roll, yaw); }
}

/**
 * 一帧上半身结果的平滑器（UpperBodySmoother.kt 逐条移植）。
 *
 * - 13 个点逐点做 One-Euro（x / y / z 各一个滤波器；**visibility 不平滑**，
 *   保持原始值供上层判断可信度）；
 * - 躯干角 roll / yaw 单独用更低截止频率的 One-Euro 做轻度平滑。
 *
 * 【与 Kotlin 一致】BodyPoint.ZERO（x/y/z/v 全 0）直接透传、**不推进**该点的滤波器，
 * 否则坏帧会把该点从旧位置拖向原点，恢复时再滑回来。
 */
export class UpperBodySmoother {
  /**
   * @param {{pointMinCutoff?: number, pointBeta?: number, torsoMinCutoff?: number, torsoBeta?: number}} [options]
   */
  constructor(options = {}) {
    const pointMinCutoff = options.pointMinCutoff === undefined ? DEFAULT_MIN_CUTOFF : options.pointMinCutoff;
    const pointBeta = options.pointBeta === undefined ? DEFAULT_BETA : options.pointBeta;
    const torsoMinCutoff = options.torsoMinCutoff === undefined ? DEFAULT_TORSO_MIN_CUTOFF : options.torsoMinCutoff;
    const torsoBeta = options.torsoBeta === undefined ? DEFAULT_TORSO_BETA : options.torsoBeta;

    this._fx = new Array(13);
    this._fy = new Array(13);
    this._fz = new Array(13);
    for (let i = 0; i < 13; i++) {
      this._fx[i] = new OneEuroFilter({ minCutoff: pointMinCutoff, beta: pointBeta });
      this._fy[i] = new OneEuroFilter({ minCutoff: pointMinCutoff, beta: pointBeta });
      this._fz[i] = new OneEuroFilter({ minCutoff: pointMinCutoff, beta: pointBeta });
    }
    this._torsoRoll = new OneEuroFilter({ minCutoff: torsoMinCutoff, beta: torsoBeta });
    this._torsoYaw = new OneEuroFilter({ minCutoff: torsoMinCutoff, beta: torsoBeta });
  }

  reset() {
    for (let i = 0; i < 13; i++) { this._fx[i].reset(); this._fy[i].reset(); this._fz[i].reset(); }
    this._torsoRoll.reset();
    this._torsoYaw.reset();
  }

  /**
   * 平滑 13 点（就地写回同一个 Float32Array(52)）。
   * @param {Float32Array} points13 13*4 交错 x,y,z,visibility
   * @param {number} dtSeconds 与上一有效帧的时间差（秒）
   */
  smoothInPlace(points13, dtSeconds) {
    for (let i = 0; i < 13; i++) {
      const o = i * 4;
      const x = points13[o], y = points13[o + 1], z = points13[o + 2], v = points13[o + 3];
      // BodyPoint.ZERO 直接透传、不推进滤波器
      if (x === 0 && y === 0 && z === 0 && v === 0) continue;
      points13[o] = this._fx[i].filter(x, dtSeconds);
      points13[o + 1] = this._fy[i].filter(y, dtSeconds);
      points13[o + 2] = this._fz[i].filter(z, dtSeconds);
      // visibility 保持原值
    }
    return points13;
  }

  /** 对原始躯干角做轻度 One-Euro 平滑；非法输入原样返回（交给基线层处理）。 */
  smoothTorsoInto(out, roll, yaw, dtSeconds) {
    if (!Number.isFinite(roll) || !Number.isFinite(yaw)) { out[0] = roll; out[1] = yaw; return out; }
    out[0] = this._torsoRoll.filter(roll, dtSeconds);
    out[1] = this._torsoYaw.filter(yaw, dtSeconds);
    return out;
  }
}

// ============ UpperBodyGeometry ============

/** 13 点的平均可见度（quality）。 */
export function quality(points13) {
  let sum = 0;
  for (let i = 0; i < 13; i++) {
    const v = points13[i * 4 + 3];
    sum += Number.isFinite(v) ? Math.min(1, Math.max(0, v)) : 0;
  }
  return sum / 13;
}

/** 本帧是否算「检测到上半身」：可见度 > 0.5 的点至少 7 个（严格大于）。 */
export function isTracking(points13) {
  let reliable = 0;
  for (let i = 0; i < 13; i++) {
    if (points13[i * 4 + 3] > RELIABLE_VISIBILITY) reliable++;
  }
  return reliable >= MIN_TRACKING_POINTS;
}

/** 躯干角退化对：肩(5,6) -> 肘(7,8) -> 胯(11,12)。 */
const TORSO_PAIRS = [[5, 6], [7, 8], [11, 12]];

/**
 * 由肩线（不可见时退化到肘 / 胯）计算**原始**躯干角（未减基线）。
 *
 *  roll：右肩比左肩低（画面 y 更大）时为正
 *  yaw ：右肩更靠近镜头（dz < 0）= 向本人左侧转 = 正
 *  实现为 atan2(-dz, horizontal)，其中 horizontal = max(|dx|, 0.02)。
 */
export function torsoInto(out, points13) {
  for (let p = 0; p < TORSO_PAIRS.length; p++) {
    const li = TORSO_PAIRS[p][0] * 4, ri = TORSO_PAIRS[p][1] * 4;
    const lx = points13[li], ly = points13[li + 1], lz = points13[li + 2], lv = points13[li + 3];
    const rx = points13[ri], ry = points13[ri + 1], rz = points13[ri + 2], rv = points13[ri + 3];
    if (!Number.isFinite(lv) || !Number.isFinite(rv)) continue;
    if (Math.min(lv, rv) < MIN_TORSO_VISIBILITY) continue;
    if (!Number.isFinite(lx) || !Number.isFinite(ly) || !Number.isFinite(lz)) continue;
    if (!Number.isFinite(rx) || !Number.isFinite(ry) || !Number.isFinite(rz)) continue;

    const dx = rx - lx, dy = ry - ly, dz = rz - lz;
    const horizontal = Math.max(Math.abs(dx), MIN_REFERENCE_SPAN);
    const roll = Math.atan2(dy, horizontal) * 180 / Math.PI;
    const yaw = Math.atan2(-dz, horizontal) * 180 / Math.PI;
    if (!Number.isFinite(roll) || !Number.isFinite(yaw)) continue;
    out[0] = roll;
    out[1] = yaw;
    return out;
  }
  out[0] = 0;
  out[1] = 0;
  return out;
}

// ============ BodyFusion 编排器 ============

/**
 * 上半身融合：点平滑 -> 躯干角 -> 角平滑 -> 基线相对化。
 *
 * 处理顺序（UpperBodyGeometry.kt 注释要求「先平滑点、再对结果做轻度平滑，
 * 最后交给 TorsoBaseline 相对化」），不可换序。
 */
export class BodyFusion {
  /** @param {{calibrationFrames?: number, smoother?: object}} [options] */
  constructor(options = {}) {
    this.smoother = new UpperBodySmoother(options.smoother || {});
    this.baseline = new TorsoBaseline(options.calibrationFrames);
    this._rawTorso = new Float32Array(2);
    this._smoothTorso = new Float32Array(2);
    this._relTorso = new Float32Array(2);
    /** 无锚点（首帧 / tracking 丢失后）时使用的 dt（秒），docs 附录 H：1/15。 */
    this._anchorlessDtSeconds = 1 / 15;
    this._hasAnchor = false;
  }

  /**
   * 处理一帧上半身。
   *
   * @param {Float32Array} points13 13*4 交错 x,y,z,visibility（**原始**点，未平滑）
   * @param {number} dtSeconds 与上一有效帧的时间差（秒）
   * @param {number} [ubT] 该上半身帧的采集时间戳（透传给输出，便于主线程做过期判断）
   * @returns {{torso: Float32Array, points: Float32Array, tracking: boolean, quality: number, ubT: number}}
   */
  process(points13, dtSeconds, ubT) {
    const tracking = isTracking(points13);
    const q = quality(points13);

    // 无锚点（首帧 / tracking 丢失后）用 1/15 秒，避免把旧位置拖过来
    // 丢目标：清空平滑器状态，并且**不把这一帧坏数据喂进滤波器**。
    // UpperBodySmoother.kt:「丢帧时调用 reset，下次重新捕获目标时从新位置开始，
    // 不会把旧位置拖过来」；TorsoBaseline 也只在有效帧上调用 apply。
    if (!tracking) {
      this.smoother.reset();
      this._hasAnchor = false;
      this._relTorso[0] = 0;
      this._relTorso[1] = 0;
      return {
        torso: this._relTorso,
        points: points13,
        tracking,
        quality: q,
        ubT: ubT === undefined ? 0 : ubT,
      };
    }

    // 无锚点（首帧 / 丢目标后重新捕获）用 1/15 秒，避免把旧位置拖过来
    const dt = this._hasAnchor ? dtSeconds : this._anchorlessDtSeconds;

    // 1) 点平滑（就地）
    this.smoother.smoothInPlace(points13, dt);
    this._hasAnchor = true;

    // 2) 由平滑后的点算原始躯干角
    torsoInto(this._rawTorso, points13);

    // 3) 躯干角轻度平滑
    this.smoother.smoothTorsoInto(this._smoothTorso, this._rawTorso[0], this._rawTorso[1], dt);

    // 4) 基线相对化
    this.baseline.applyInto(this._relTorso, this._smoothTorso[0], this._smoothTorso[1]);

    return {
      torso: this._relTorso,
      points: points13,
      tracking,
      quality: q,
      ubT: ubT === undefined ? 0 : ubT,
    };
  }

  reset() {
    this.smoother.reset();
    this.baseline.reset();
    this._hasAnchor = false;
  }
}
