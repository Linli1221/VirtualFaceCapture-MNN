/**
 * temporal-filter.js — 时域平滑（移植 MNN 原生侧的关键点/头姿滤波）
 *
 * 移植来源（逐条对应）：
 *   MediapipeFaceLandmark/src/main/cpp/include/face_pipeline_filter.h
 *   MediapipeFaceLandmark/src/main/cpp/src/core/face_landmarker.cpp
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 作用域（docs/post-pipeline-migration.md 附录 K）：
 *   **套在 478 点关键点域（x/y/z）与 rawHeadPose 上，不套 52 维 BlendShape 域**。
 *   Android 在 bs 域没有任何平滑，黄金对齐以此为基准；链序中本模块在
 *   BlendShape **提取之前**（任务书原「时间滤波在眨眼后」作废）。
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ── 常量来源（两个 tau 数字都真实存在，但只有一个在线上生效）──
 *   0.12 = face_pipeline_filter.h:600 DEFAULT_TIME_EMA_TAU —— **线上实跑值**。
 *        face_landmarker.cpp:93-99 的构造里那行 config.time_ema_tau = 0.08f 是
 *        **注释掉的**，所以 config 常量默认 0.12 直接生效；而 Kotlin 的
 *        configureForHighFps(landmarkTau = 0.08f) 形参默认**全仓无任何调用点**，
 *        从未生效。本文件默认取 0.12。
 *   0.08 仅作可配值保留。
 *   0.3  = face_pipeline_filter.h:601 DEFAULT_TIME_EMA_TAU_HEAD_POSE（头姿专用）。
 *
 * ── 公式（TimeBasedEMAFilter）──
 *   alpha = 1 - exp(-dt / tau)；dt clamp 到 [0.001, 0.5] 秒；首样本直通并记 last_time。
 *
 * ── 不移植的部分（有意为之）──
 *   GAUSSIAN / 固定 EMA 在 Kotlin 的 landmark/detection 滤波枚举上是**静默旁路**
 *   （原生侧没实现），不能当参考实现，因此这里**不提供**这两个假实现。
 *
 * ── 两套 One-Euro 参数，互不混用 ──
 *   本文件的 OneEuroFilterFace = face_pipeline_filter.h 那套（minCutoff 0.05 / beta 80）；
 *   body-fusion.js 的 OneEuroFilter = MediapipeUpperBody 那套（minCutoff 1.2 / beta 0.02）。
 *   两者是完全不同的滤波器，改参数前先看清在哪个文件里。
 *
 * 数值口径：全程**秒**（tau 与 timestamp 同为秒），与 C++ 同口径，便于黄金对齐逐位比对。
 */

// ============ 常量（face_pipeline_filter.h:595-608）============

/** 线上实跑的关键点/头姿之外通用 tau（秒）。face_pipeline_filter.h:600 */
export const DEFAULT_TIME_EMA_TAU = 0.12;

/** 头姿专用 tau（秒）。face_pipeline_filter.h:601 */
export const DEFAULT_TIME_EMA_TAU_HEAD_POSE = 0.3;

/** 可配下限（C++ TimeBasedEMAFilter::setTau 的 std::max(0.01f, tau)）。 */
export const MIN_TIME_EMA_TAU = 0.01;

/** dt 下限（秒）。C++: std::max(0.001f, ...) */
export const MIN_DT_SECONDS = 0.001;

/** dt 上限（秒）。C++: std::min(0.5f, ...) */
export const MAX_DT_SECONDS = 0.5;

/** face_pipeline_filter.h 的 One-Euro 默认参数（**与 body-fusion 的那套不同**）。 */
export const FACE_ONE_EURO_DEFAULTS = Object.freeze({
  minCutoff: 0.05,
  beta: 80.0,
  derivCutoff: 1.0,
});

/**
 * 时间基准型 EMA（一维）。
 *
 * 移植 C++ TimeBasedEMAFilter（face_pipeline_filter.h:441-502）：
 *   - 首个有效样本直通，记 last_time；
 *   - dt = timestamp - last_time，clamp 到 [0.001, 0.5] 秒；
 *   - alpha = 1 - exp(-dt / tau)；
 *   - value = alpha * value + (1 - alpha) * value_。
 */
export class TimeBasedEMAFilter {
  /** @param {{tau?: number}} [options] tau 单位秒，默认 0.12，下限 0.01 */
  constructor(options = {}) {
    this._tau = clampTau(options.tau === undefined ? DEFAULT_TIME_EMA_TAU : options.tau);
    this._initialized = false;
    this._value = 0;
    this._lastTime = -1;
  }

  get tau() { return this._tau; }
  get hasValue() { return this._initialized; }
  get lastValue() { return this._value; }

  /** C++ setTau：std::max(0.01f, tau) */
  setTau(tau) { this._tau = clampTau(tau); }

  /**
   * @param {number} value 新样本
   * @param {number} timestampSeconds 时间戳（秒，与上一帧同源）
   * @returns {number} 平滑后的值
   */
  filter(value, timestampSeconds) {
    if (!this._initialized) {
      this._initialized = true;
      this._value = value;
      this._lastTime = timestampSeconds;
      return value;
    }

    let dt = timestampSeconds - this._lastTime;
    this._lastTime = timestampSeconds;
    // C++: dt = std::max(0.001f, std::min(0.5f, dt))
    dt = dt < MIN_DT_SECONDS ? MIN_DT_SECONDS : dt > MAX_DT_SECONDS ? MAX_DT_SECONDS : dt;

    const alpha = 1 - Math.exp(-dt / this._tau);
    this._value = alpha * value + (1 - alpha) * this._value;
    return this._value;
  }

  reset() {
    this._initialized = false;
    this._value = 0;
    this._lastTime = -1;
  }
}

/** 时间基准型 EMA（三维，三个分量共享同一时间戳）。C++ TimeBasedEMAFilter3D */
export class TimeBasedEMAFilter3D {
  /** @param {{tau?: number}} [options] */
  constructor(options = {}) {
    this._x = new TimeBasedEMAFilter(options);
    this._y = new TimeBasedEMAFilter(options);
    this._z = new TimeBasedEMAFilter(options);
  }

  get tau() { return this._x.tau; }
  setTau(tau) { this._x.setTau(tau); this._y.setTau(tau); this._z.setTau(tau); }

  /**
   * @param {Float32Array|number[]} out 长度 >= 3 的输出（就地写，避免每帧分配）
   * @param {number} x @param {number} y @param {number} z
   * @param {number} timestampSeconds
   */
  filterInto(out, x, y, z, timestampSeconds) {
    out[0] = this._x.filter(x, timestampSeconds);
    out[1] = this._y.filter(y, timestampSeconds);
    out[2] = this._z.filter(z, timestampSeconds);
    return out;
  }

  /** 便捷形式（会新建数组；热路径请用 filterInto）。 */
  filter(x, y, z, timestampSeconds) {
    return [
      this._x.filter(x, timestampSeconds),
      this._y.filter(y, timestampSeconds),
      this._z.filter(z, timestampSeconds),
    ];
  }

  reset() { this._x.reset(); this._y.reset(); this._z.reset(); }
}

/**
 * 头姿平滑器（rawHeadPose 专用，默认 tau = 0.3）。
 * C++ 侧是 HeadPoseFilterType::TIME_BASED_EMA，公式与 TimeBasedEMAFilter 相同。
 * android-raw 会在原生侧加 JNI 开关旁路它；本模块由 config.smoothing.enabled 控制。
 */
export class HeadPoseSmoother {
  /** @param {{tau?: number}} [options] tau 默认 DEFAULT_TIME_EMA_TAU_HEAD_POSE = 0.3 */
  constructor(options = {}) {
    const tau = options.tau === undefined ? DEFAULT_TIME_EMA_TAU_HEAD_POSE : options.tau;
    this._p = new TimeBasedEMAFilter({ tau });
    this._y = new TimeBasedEMAFilter({ tau });
    this._r = new TimeBasedEMAFilter({ tau });
  }

  /** @param {Float32Array|number[]} out 长度 >= 3，就地写 [pitch, yaw, roll] */
  filterInto(out, pitch, yaw, roll, timestampSeconds) {
    out[0] = this._p.filter(pitch, timestampSeconds);
    out[1] = this._y.filter(yaw, timestampSeconds);
    out[2] = this._r.filter(roll, timestampSeconds);
    return out;
  }

  reset() { this._p.reset(); this._y.reset(); this._r.reset(); }
}

/**
 * face_pipeline_filter.h 的 One-Euro（**备用**，与 body-fusion 的 OneEuroFilter 不是一套）。
 *
 * 移植 C++ OneEuroFilter（face_pipeline_filter.h:56-120）：
 *   dx = (value - lastValue) / dt（首帧 dx = 0）
 *   edx = 低通(dx, alpha(derivCutoff))
 *   cutoff = minCutoff + beta * |edx|
 *   out = 低通(value, alpha(cutoff))
 *   alpha(cutoff, dt) = 1 / (1 + tau / dt)，tau = 1 / (2*PI*cutoff)
 */
export class OneEuroFilterFace {
  /** @param {{minCutoff?: number, beta?: number, derivCutoff?: number}} [options] */
  constructor(options = {}) {
    this.minCutoff = options.minCutoff === undefined ? FACE_ONE_EURO_DEFAULTS.minCutoff : options.minCutoff;
    this.beta = options.beta === undefined ? FACE_ONE_EURO_DEFAULTS.beta : options.beta;
    this.derivCutoff = options.derivCutoff === undefined ? FACE_ONE_EURO_DEFAULTS.derivCutoff : options.derivCutoff;
    this._hasLast = false;
    this._lastValue = 0;
    this._lastDeriv = 0;
  }

  /** C++ computeAlpha：cutoff<=0 时 tau 会爆，这里按 cutoff<=0 返回 1（完全跟随）。 */
  static computeAlpha(cutoff, dt) {
    if (!(cutoff > 0)) return 1;
    const tau = 1 / (2 * Math.PI * cutoff);
    return 1 / (1 + tau / dt);
  }

  filter(value, timestampSeconds) {
    if (!this._hasLast) {
      this._hasLast = true;
      this._lastValue = value;
      this._lastTime = timestampSeconds;
      return value;
    }
    let dt = timestampSeconds - this._lastTime;
    this._lastTime = timestampSeconds;
    dt = dt < MIN_DT_SECONDS ? MIN_DT_SECONDS : dt > MAX_DT_SECONDS ? MAX_DT_SECONDS : dt;

    const dx = (value - this._lastValue) / dt;
    const dxAlpha = OneEuroFilterFace.computeAlpha(this.derivCutoff, dt);
    this._lastDeriv += dxAlpha * (dx - this._lastDeriv);

    const cutoff = this.minCutoff + this.beta * Math.abs(this._lastDeriv);
    const alpha = OneEuroFilterFace.computeAlpha(cutoff, dt);
    const out = this._lastValue + alpha * (value - this._lastValue);
    this._lastValue = out;
    return out;
  }

  reset() {
    this._hasLast = false;
    this._lastValue = 0;
    this._lastDeriv = 0;
    this._lastTime = -1;
  }
}

function clampTau(tau) {
  return tau < MIN_TIME_EMA_TAU ? MIN_TIME_EMA_TAU : tau;
}

/**
 * 478 点关键点域的平滑器（x/y/z 各一个 3D 滤波器组）。
 *
 * 入参与出参都是 Float32Array(478*3) 的交错布局，**就地写**，逐帧零分配。
 * 规格要求「处理链中不逐帧建普通对象」，因此这里不做任何对象/数组创建。
 */
export class LandmarkSmoother {
  /**
   * @param {{count?: number, tau?: number, enabled?: boolean}} [options]
   *   count   点数，默认 478
   *   tau     秒，默认 DEFAULT_TIME_EMA_TAU = 0.12
   *   enabled false 时直通（协议 flags bit5 LANDMARKS_PRE_SMOOTHED=1 的场景）
   */
  constructor(options = {}) {
    this.count = Number.isFinite(options.count) ? options.count : 478;
    this.enabled = options.enabled !== false;
    this._tau = clampTau(options.tau === undefined ? DEFAULT_TIME_EMA_TAU : options.tau);
    // 三个交错平面：x/y/z 各 count 个滤波器的状态（值 + 已初始化 + lastTime）。
    // 用 TypedArray 平铺而不是 count 个对象，避免 1434 个对象常驻。
    this._v = new Float32Array(this.count * 3);
    this._init = new Uint8Array(this.count * 3);
    this._lastT = new Float64Array(this.count * 3);
  }

  get tau() { return this._tau; }
  setTau(tau) { this._tau = clampTau(tau); }

  /**
   * 平滑一帧 478 点（就地写回同一个数组）。
   *
   * @param {Float32Array} points 长度 count*3 的交错 x/y/z
   * @param {number} timestampSeconds 帧时间戳（秒）
   * @returns {Float32Array} 同一个数组
   */
  filterInPlace(points, timestampSeconds) {
    if (!this.enabled) return points;
    const n = this.count * 3;
    const v = this._v, init = this._init, lastT = this._lastT, tau = this._tau;
    for (let i = 0; i < n; i++) {
      const value = points[i];
      if (!init[i]) {
        init[i] = 1;
        v[i] = value;
        lastT[i] = timestampSeconds;
        continue;
      }
      let dt = timestampSeconds - lastT[i];
      lastT[i] = timestampSeconds;
      dt = dt < MIN_DT_SECONDS ? MIN_DT_SECONDS : dt > MAX_DT_SECONDS ? MAX_DT_SECONDS : dt;
      const alpha = 1 - Math.exp(-dt / tau);
      const out = alpha * value + (1 - alpha) * v[i];
      v[i] = out;
      points[i] = out;
    }
    return points;
  }

  reset() {
    this._init.fill(0);
    this._v.fill(0);
    this._lastT.fill(0);
  }
}

/**
 * 工厂：按 config 创建一整套时域平滑。
 *
 * config（与 win-integrate 的 worker 消息契约一致）：
 *   {smoothing: {enabled, landmarkTauSec, headPoseTauSec}}
 *   enabled=false 时整体直通 —— 对应协议 flags bit5 LANDMARKS_PRE_SMOOTHED=1，
 *   即手机端原生 EMA 没被旁路、raw_track 已带滤波点位，Windows 不再平滑。
 */
export function createTemporalFilter(config = {}) {
  const smoothing = config.smoothing || config;
  const enabled = smoothing.enabled !== false;
  const landmarkTauSec = smoothing.landmarkTauSec;
  const headPoseTauSec = smoothing.headPoseTauSec;
  const landmarks = new LandmarkSmoother({ tau: landmarkTauSec, enabled });
  const pose = new HeadPoseSmoother({ tau: headPoseTauSec });
  return {
    enabled,
    landmarks,
    pose,
    /** 直通/平滑 478 点（就地）。 */
    filterLandmarks: (points, timestampSeconds) => landmarks.filterInPlace(points, timestampSeconds),
    /** 直通/平滑头姿，写入 out[0..2]。 */
    filterPose: (out, pitch, yaw, roll, timestampSeconds) => {
      if (!enabled) { out[0] = pitch; out[1] = yaw; out[2] = roll; return out; }
      return pose.filterInto(out, pitch, yaw, roll, timestampSeconds);
    },
    reset: () => { landmarks.reset(); pose.reset(); },
  };
}
