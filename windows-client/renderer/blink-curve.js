/**
 * blink-curve.js — 眨眼曲线处理器
 *
 * 移植来源（逐条对应，**禁止重新发明**）：
 *   OpenSeeFaceProcess/src/main/java/com/live2d/facecapture/BlinkCurveProcessor.kt
 *
 * 核心思路（Kotlin 原注释）：
 *   1. 检测到眨眼事件时，用预设的平滑曲线替代原始 EAR 输出；
 *   2. 检测到眯眼时，用曲线平滑过渡到固定值 0.5；
 *   3. 消除 Landmark 抖动导致的眼皮颤动。
 *
 * 状态机：TRACKING / BLINK_DETECT / CLOSING / BLINK_CLOSED / BLINK_OPENING / SQUINT_HOLD
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 【与 Kotlin 注释不符，按代码】的四处（移植时逐条照代码，不要照注释）：
 *  1. transitionTo() 的 curveProgress 清零：Kotlin 那段嵌套 if 恒为真，实际语义是
 *     「除进入 SQUINT_HOLD 外一律清零」。本文件按该语义实现。
 *  2. 眯眼的 CLOSING：注释写「曲线值到达 0.5 对应的进度就停」，代码是 curveProgress
 *     >= 1 才停，输出是整条 closingCurve * 0.5 —— 即 0 -> 0.5 铺满整个 80ms，
 *     而不是半程停。
 *  3. BLINK_DETECT 与 BLINK_CLOSED 对 earHistory 为空时的 fallback 不同：
 *     前者用 baselineEAR，后者用 0f。别统一。
 *  4. SQUINT_HOLD 不参与双眼同步（同步集合只有 CLOSING/BLINK_CLOSED/BLINK_OPENING）。
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 数值说明：Kotlin 全程 Float（32 位），JS number 是 double。常量按原值书写，
 * 中间计算不做 fround（Kotlin 也不在这些路径上逐次收窄）。逐位一致不可能，
 * 黄金评测按 abs 1e-5 / rel 1e-4 容差比对。
 */

// ============ 默认时间参数（毫秒）============
/** Kotlin: DEFAULT_CLOSING_DURATION_MS = 80f */
export const DEFAULT_CLOSING_DURATION_MS = 80;
/** Kotlin: DEFAULT_CLOSED_DURATION_MS = 30f */
export const DEFAULT_CLOSED_DURATION_MS = 30;
/** Kotlin: DEFAULT_OPENING_DURATION_MS = 120f */
export const DEFAULT_OPENING_DURATION_MS = 120;

// ============ 眨眼检测参数 ============
/** 连续下降帧数确认眨眼（30fps 基准）。Kotlin: DEFAULT_BLINK_CONFIRM_FRAMES = 2 */
export const DEFAULT_BLINK_CONFIRM_FRAMES = 2;
/** 每帧下降率阈值（相对于 baseline）。Kotlin: DEFAULT_DROP_RATE_THRESHOLD = 0.12f */
export const DEFAULT_DROP_RATE_THRESHOLD = 0.12;
/** 总下降量阈值（相对于 baseline）。Kotlin: DEFAULT_MIN_TOTAL_DROP = 0.35f */
export const DEFAULT_MIN_TOTAL_DROP = 0.35;

// ============ 眯眼检测参数 ============
/** 持续低位时间阈值（毫秒）。Kotlin: DEFAULT_SQUINT_TIME_THRESHOLD_MS = 300f */
export const DEFAULT_SQUINT_TIME_THRESHOLD_MS = 300;
/** 眯眼时的固定 blink 值。Kotlin: SQUINT_BLINK_VALUE = 0.5f */
export const SQUINT_BLINK_VALUE = 0.5;

// ============ 双眼同步参数 ============
/** 判定为「同时眨眼」的时间窗口（毫秒）。Kotlin: SYNC_WINDOW_MS = 50L */
export const SYNC_WINDOW_MS = 50;

// ============ 单眼状态 ============
/**
 * 眼睛状态枚举（与 Kotlin EyeState 一一对应）。
 *
 * 状态设计思路（Kotlin 原注释）：
 *  - 眨眼和眯眼共用同一条闭眼曲线；
 *  - 眨眼：曲线播放到底(1.0)，然后保持，再睁眼；
 *  - 眯眼：曲线播放到一半(0.5)就停住，固定不动。
 */
export const EyeState = Object.freeze({
  TRACKING: 0,       // 正常跟随 EAR
  BLINK_DETECT: 1,   // 检测到可能的眨眼/眯眼（下降中）
  CLOSING: 2,        // 播放闭眼曲线（眨眼和眯眼共用）
  BLINK_CLOSED: 3,   // 眨眼：保持完全闭眼
  BLINK_OPENING: 4,  // 眨眼：播放睁眼曲线
  SQUINT_HOLD: 5,    // 眯眼：固定在 0.5 不动
});

/** 状态名（调试用，对应 Kotlin EyeState.name）。 */
export const EYE_STATE_NAMES = Object.freeze([
  "TRACKING", "BLINK_DETECT", "CLOSING", "BLINK_CLOSED", "BLINK_OPENING", "SQUINT_HOLD",
]);

/** 是否属于「眨眼」相关状态（CLOSING / BLINK_CLOSED / BLINK_OPENING）。
 *  Kotlin 原实现每帧 setOf(...).contains()，这里按 isBlinkState() 的纯比较实现。
 *  SQUINT_HOLD 不属于眨眼态 —— 眯眼不同步。 */
export function isBlinkState(state) {
  return state === EyeState.CLOSING ||
    state === EyeState.BLINK_CLOSED ||
    state === EyeState.BLINK_OPENING;
}

/**
 * 闭眼曲线 (0 -> 1)。Kotlin: closingCurve(t) = (1 - cos(clamp(t,0,1) * PI)) / 2
 *   t=0.0 -> 0.0 / t=0.25 -> 0.146 / t=0.5 -> 0.5 / t=0.75 -> 0.854 / t=1.0 -> 1.0
 */
export function closingCurve(t) {
  const clampedT = t < 0 ? 0 : t > 1 ? 1 : t;
  return (1 - Math.cos(clampedT * Math.PI)) / 2;
}

/**
 * 睁眼曲线 (1 -> 0)。Kotlin: openingCurve(t) = (1 + cos(clamp(t,0,1) * PI)) / 2
 *   t=0.0 -> 1.0 / t=0.25 -> 0.854 / t=0.5 -> 0.5 / t=0.75 -> 0.146 / t=1.0 -> 0.0
 */
export function openingCurve(t) {
  const clampedT = t < 0 ? 0 : t > 1 ? 1 : t;
  return (1 + Math.cos(clampedT * Math.PI)) / 2;
}

/**
 * 单眼处理器（Kotlin BlinkCurveProcessor.SingleEyeProcessor 的逐条移植）。
 *
 * 核心设计：
 *  - 只有三种目标状态：睁眼(0)、半睁眼(0.5)、闭眼(1)；
 *  - 所有状态之间的切换都用平滑曲线；
 *  - 永远不直接返回原始值，彻底消除抖动。
 *
 * 输入乱序保护：状态机按 currentTimeMs 推进，调用方必须顺序喂。
 * 重复/回退的时间戳会让 deltaMs 被 clamp 到 1ms（Kotlin 的 coerceIn(1,100) 同样如此）。
 */
export class SingleEyeProcessor {
  /** @param {string} eyeName 调试名（"LeftEye" / "RightEye"） */
  constructor(eyeName) {
    this.eyeName = eyeName;

    // ---- 状态 ----
    this.state = EyeState.TRACKING;
    this.stateStartTimeMs = 0;
    this._lastUpdateTimeMs = 0;

    // ---- EAR 历史（容量 5，对应 Kotlin ArrayDeque<Float>(5)）----
    this._earHistory = new Float32Array(5);
    this._earCount = 0;

    // ---- 基线 EAR（睁眼状态的平均值）----
    this._baselineEAR = 0.18;

    // ---- 眨眼检测计数器 ----
    this._consecutiveDropFrames = 0;
    this._dropStartEAR = 0;

    // ---- 曲线播放进度 ----
    this.curveProgress = 0;

    // ---- 平滑输出系统 ----
    this._currentOutputValue = 0;
    // 死字段（Kotlin 里也只在 reset 里赋值），保留以对齐 reset 语义。
    this._targetValue = 0;
    this._transitionStartValue = 0;

    // ---- 眯眼检测 ----
    this._lowEarStartTimeMs = 0;
    this._isInLowEarZone = false;

    // ---- 当前 CLOSING 是眨眼(true)还是眯眼(false) ----
    this._isBlinkNotSquint = true;

    // ---- 动态帧率相关 ----
    this._currentFrameIntervalMs = 33.3; // 默认 30fps
  }

  /** 当前状态（只读语义；直接改会破坏状态机）。 */
  get currentState() { return this.state; }

  /** 基线 EAR（诊断用）。 */
  get baselineEAR() { return this._baselineEAR; }

  /**
   * 更新帧率。Kotlin: updateFrameRate(fps) -> currentFrameIntervalMs = fps>0 ? 1000/fps : 33.3
   */
  updateFrameRate(fps) {
    this._currentFrameIntervalMs = fps > 0 ? 1000 / fps : 33.3;
  }

  /**
   * 动态调整后的检测帧数。
   * Kotlin: (DEFAULT_BLINK_CONFIRM_FRAMES * (33.3 / currentFrameIntervalMs)).toInt().coerceIn(1, 5)
   * Kotlin 的 toInt() 向零截断，这里用 Math.trunc 对齐。
   */
  _getDynamicConfirmFrames() {
    const ratio = 33.3 / this._currentFrameIntervalMs;
    const v = Math.trunc(DEFAULT_BLINK_CONFIRM_FRAMES * ratio);
    return v < 1 ? 1 : v > 5 ? 5 : v;
  }

  /** 历史条数（诊断用）。 */
  get historyCount() { return this._earCount; }

  /** 取历史第 index 个（0 = 最旧）。 */
  _histAt(index) {
    // 环形：容量 5，_earCount <= 5。最旧元素的位置 = (head - _earCount) mod 5。
    const head = this._histHead === undefined ? 0 : this._histHead;
    return this._earHistory[(head - this._earCount + index + 5 * 2) % 5];
  }

  /** Kotlin earHistory.last()（最新一条）。 */
  _histLast() { return this._histAt(this._earCount - 1); }

  /** Kotlin earHistory[size-2]（上一条）。 */
  _histPrev() { return this._histAt(this._earCount - 2); }

  /** Kotlin earHistory.lastOrNull()，空时返回 fallback。 */
  _histLastOrNull(fallback) {
    return this._earCount > 0 ? this._histLast() : fallback;
  }

  /** Kotlin earHistory.addLast + size>5 时 removeFirst。 */
  _histPush(value) {
    if (this._histHead === undefined) this._histHead = 0;
    this._earHistory[this._histHead] = value;
    this._histHead = (this._histHead + 1) % 5;
    if (this._earCount < 5) this._earCount++;
  }

  /** Kotlin earHistory.clear()。 */
  _histClear() {
    this._earCount = 0;
    this._histHead = 0;
  }

  /**
   * 处理一帧。
   *
   * @param {number} rawBlinkValue 原始 blink 值 [0,1]，0=睁眼，1=闭眼
   * @param {number} currentTimeMs 当前时间戳（毫秒）
   * @returns {number} 处理后的 blink 值（永远是平滑的）
   */
  process(rawBlinkValue, currentTimeMs) {
    // 反算 EAR（blink 值越大，EAR 越小）
    const earProxy = 1 - rawBlinkValue;

    // 更新历史
    this._histPush(earProxy);

    // 计算时间增量（Kotlin: coerceIn(1, 100)；首帧用 currentFrameIntervalMs.toLong()）
    let deltaMs;
    if (this._lastUpdateTimeMs > 0) {
      const d = currentTimeMs - this._lastUpdateTimeMs;
      deltaMs = d < 1 ? 1 : d > 100 ? 100 : d;
    } else {
      // Kotlin 的 Float.toLong() 向零截断
      deltaMs = Math.trunc(this._currentFrameIntervalMs);
    }
    this._lastUpdateTimeMs = currentTimeMs;

    // 根据状态处理（更新目标值和曲线进度）
    switch (this.state) {
      case EyeState.TRACKING:
        this._processTracking(earProxy, currentTimeMs); break;
      case EyeState.BLINK_DETECT:
        this._processBlinkDetect(earProxy, currentTimeMs); break;
      case EyeState.CLOSING:
        this._processClosing(currentTimeMs, deltaMs); break;
      case EyeState.BLINK_CLOSED:
        this._processBlinkClosed(currentTimeMs, deltaMs); break;
      case EyeState.BLINK_OPENING:
        this._processBlinkOpening(currentTimeMs, deltaMs); break;
      case EyeState.SQUINT_HOLD:
        this._processSquintHold(earProxy, currentTimeMs); break;
    }

    // 计算当前输出值（基于曲线进度）
    this._currentOutputValue = this._calculateSmoothedOutput();
    return this._currentOutputValue;
  }

  /** 计算平滑输出值（Kotlin calculateSmoothedOutput 的逐条移植）。 */
  _calculateSmoothedOutput() {
    switch (this.state) {
      case EyeState.TRACKING:
        // 睁眼状态，固定返回 0
        return 0;
      case EyeState.BLINK_DETECT:
        // 检测中，保持在睁眼状态（0），等待确认
        return 0;
      case EyeState.CLOSING: {
        const curveValue = closingCurve(this.curveProgress);
        // 【与 Kotlin 注释不符，按代码】眯眼是整条曲线 * 0.5，不是半程停
        return this._isBlinkNotSquint ? curveValue : curveValue * SQUINT_BLINK_VALUE;
      }
      case EyeState.BLINK_CLOSED:
        // 完全闭眼，固定返回 1
        return 1;
      case EyeState.BLINK_OPENING:
        // 睁眼过程中，使用曲线 1 -> 0
        return openingCurve(this.curveProgress);
      case EyeState.SQUINT_HOLD:
        // 眯眼保持，固定返回 0.5
        return SQUINT_BLINK_VALUE;
    }
    return 0;
  }

  /** TRACKING：睁眼状态，检测是否开始眨眼/眯眼。 */
  _processTracking(earProxy, currentTimeMs) {
    // 更新基线（使用高位 EAR 的移动平均）
    if (earProxy > this._baselineEAR * 0.85) {
      this._baselineEAR = this._baselineEAR * 0.95 + earProxy * 0.05;
    }

    // 检测快速下降（可能是眨眼）
    if (this._earCount >= 2) {
      const prev = this._histPrev();
      const curr = this._histLast();
      const dropRate = (prev - curr) / this._baselineEAR;

      if (dropRate > DEFAULT_DROP_RATE_THRESHOLD) {
        this._consecutiveDropFrames = 1;
        this._dropStartEAR = prev;
        this._transitionTo(EyeState.BLINK_DETECT, currentTimeMs);
        return;
      }
    }

    // 检测眯眼（持续低位）
    const lowThreshold = this._baselineEAR * 0.65;
    if (earProxy < lowThreshold) {
      if (!this._isInLowEarZone) {
        this._isInLowEarZone = true;
        this._lowEarStartTimeMs = currentTimeMs;
      }
      // 如果持续低位超过阈值，进入眯眼
      if (currentTimeMs - this._lowEarStartTimeMs > DEFAULT_SQUINT_TIME_THRESHOLD_MS) {
        this._isBlinkNotSquint = false;
        this._transitionTo(EyeState.CLOSING, currentTimeMs);
      }
    } else {
      this._isInLowEarZone = false;
    }
  }

  /**
   * BLINK_DETECT：检测中，等待确认眨眼或眯眼。
   * 在此状态下输出保持为 0（睁眼），不返回原始值。
   */
  _processBlinkDetect(_earProxy, currentTimeMs) {
    const timeInState = currentTimeMs - this.stateStartTimeMs;

    if (this._earCount >= 2) {
      const prev = this._histPrev();
      const curr = this._histLast();
      const dropRate = (prev - curr) / this._baselineEAR;
      const totalDrop = (this._dropStartEAR - curr) / this._baselineEAR;

      // 快速大幅下降 -> 确认眨眼
      if (totalDrop > DEFAULT_MIN_TOTAL_DROP * 1.2) {
        this._isBlinkNotSquint = true;
        this._transitionTo(EyeState.CLOSING, currentTimeMs);
        return;
      }

      if (dropRate > DEFAULT_DROP_RATE_THRESHOLD * 0.5) {
        this._consecutiveDropFrames++;
        if (this._consecutiveDropFrames >= this._getDynamicConfirmFrames() &&
            totalDrop > DEFAULT_MIN_TOTAL_DROP) {
          this._isBlinkNotSquint = true;
          this._transitionTo(EyeState.CLOSING, currentTimeMs);
          return;
        }
      } else if (dropRate < -DEFAULT_DROP_RATE_THRESHOLD * 0.3 &&
                 totalDrop < DEFAULT_MIN_TOTAL_DROP * 0.5) {
        // EAR 回升且下降量不够，取消检测，回到睁眼
        this._transitionTo(EyeState.TRACKING, currentTimeMs);
        this._consecutiveDropFrames = 0;
        return;
      }
    }

    // 超时判定
    if (timeInState > DEFAULT_SQUINT_TIME_THRESHOLD_MS) {
      // 【与 Kotlin 注释不符，按代码】这里 fallback 是 baselineEAR（BLINK_CLOSED 用的是 0f）
      const currentEar = this._histLastOrNull(this._baselineEAR);
      if (currentEar < this._baselineEAR * 0.4) {
        // 眼睛很闭 -> 慢速眨眼
        this._isBlinkNotSquint = true;
        this._transitionTo(EyeState.CLOSING, currentTimeMs);
      } else {
        // 眼睛半闭 -> 眯眼
        this._isBlinkNotSquint = false;
        this._transitionTo(EyeState.CLOSING, currentTimeMs);
      }
    }
  }

  /** CLOSING：播放闭眼曲线。 */
  _processClosing(currentTimeMs, deltaMs) {
    const duration = DEFAULT_CLOSING_DURATION_MS * this._blinkSpeedScale;
    this.curveProgress += deltaMs / duration;

    if (this._isBlinkNotSquint) {
      // 眨眼：播放到底
      if (this.curveProgress >= 1) {
        this.curveProgress = 0;
        this._transitionTo(EyeState.BLINK_CLOSED, currentTimeMs);
      }
    } else {
      // 眯眼：【与 Kotlin 注释不符，按代码】curveProgress >= 1 才停（注释写 0.5）
      if (this.curveProgress >= 1) {
        this._transitionTo(EyeState.SQUINT_HOLD, currentTimeMs);
      }
    }
  }

  /**
   * BLINK_CLOSED：保持闭眼。
   * 检测原始 EAR 来区分「快速眨眼」和「持续闭眼」：
   *  - EAR 持续很低 -> 用户想保持闭眼 -> 继续保持；
   *  - EAR 开始回升 -> 用户想睁眼 -> 开始睁眼曲线。
   */
  _processBlinkClosed(currentTimeMs, deltaMs) {
    const duration = DEFAULT_CLOSED_DURATION_MS * this._blinkSpeedScale;
    this.curveProgress += deltaMs / duration;

    // 【与 Kotlin 注释不符，按代码】这里 fallback 是 0f（BLINK_DETECT 用的是 baselineEAR）
    const currentEar = this._histLastOrNull(0);
    const isEyeStillClosed = currentEar < this._baselineEAR * 0.5;

    if (this.curveProgress >= 1) {
      if (isEyeStillClosed) {
        // 用户仍在闭眼，保持 BLINK_CLOSED 状态，不自动睁眼
        this.curveProgress = 0;
      } else {
        // 用户想睁眼了，开始睁眼曲线
        this.curveProgress = 0;
        this._transitionTo(EyeState.BLINK_OPENING, currentTimeMs);
      }
    }
  }

  /** BLINK_OPENING：播放睁眼曲线。 */
  _processBlinkOpening(currentTimeMs, deltaMs) {
    const duration = DEFAULT_OPENING_DURATION_MS * this._blinkSpeedScale;
    this.curveProgress += deltaMs / duration;

    if (this.curveProgress >= 1) {
      this.curveProgress = 0;
      this._consecutiveDropFrames = 0;
      this._transitionTo(EyeState.TRACKING, currentTimeMs);
    }
  }

  /** SQUINT_HOLD：眯眼保持。 */
  _processSquintHold(earProxy, currentTimeMs) {
    // 检测是否恢复睁眼
    if (earProxy > this._baselineEAR * 0.8) {
      // 从 0.5 平滑回到 0，使用睁眼曲线
      this._transitionTo(EyeState.BLINK_OPENING, currentTimeMs);
      // 从半闭开始睁眼，进度设为 0.5（必须在 transitionTo 之后设）
      this.curveProgress = 0.5;
      this._isInLowEarZone = false;
      return;
    }

    // 检测是否变成眨眼（继续闭眼）
    if (this._earCount >= 2) {
      const prev = this._histPrev();
      const curr = this._histLast();
      const dropRate = (prev - curr) / this._baselineEAR;

      if (dropRate > DEFAULT_DROP_RATE_THRESHOLD && curr < this._baselineEAR * 0.3) {
        this._isBlinkNotSquint = true;
        this._transitionTo(EyeState.CLOSING, currentTimeMs);
        // 从 0.5 开始继续闭眼（必须在 transitionTo 之后设）
        this.curveProgress = 0.5;
      }
    }
  }

  /**
   * 状态转换。
   *
   * 【与 Kotlin 注释不符，按代码】Kotlin 原文是
   *     state = newState; stateStartTimeMs = now;
   *     if (newState != BLINK_OPENING || state != SQUINT_HOLD) {
   *         if (state != SQUINT_HOLD) curveProgress = 0f
   *     }
   * 赋值后 state == newState，外层条件恒为真，因此实际语义就是
   * 「除进入 SQUINT_HOLD 外一律 curveProgress = 0」。下面按该语义实现。
   * 调用方在 transitionTo **之后**再设 curveProgress = 0.5 的两处例外保留原样。
   */
  _transitionTo(newState, currentTimeMs) {
    if (this.state !== newState) {
      this.state = newState;
      this.stateStartTimeMs = currentTimeMs;
      if (newState !== EyeState.SQUINT_HOLD) {
        this.curveProgress = 0;
      }
    }
  }

  /** 重置状态（Kotlin reset() 逐字段对应）。 */
  reset() {
    this.state = EyeState.TRACKING;
    this.stateStartTimeMs = 0;
    this._lastUpdateTimeMs = 0;
    this._histClear();
    this._baselineEAR = 0.18;
    this._consecutiveDropFrames = 0;
    this._dropStartEAR = 0;
    this.curveProgress = 0;
    this._currentOutputValue = 0;
    this._targetValue = 0;
    this._transitionStartValue = 0;
    this._lowEarStartTimeMs = 0;
    this._isInLowEarZone = false;
    this._isBlinkNotSquint = true;
  }
}

// blinkSpeedScale 存在原型外的可写字段上，需要在类外定义 setter 语义：
// Kotlin 的 var blinkSpeedScale 带 coerceIn(0.3f, 5.0f) 的 setter。
Object.defineProperty(SingleEyeProcessor.prototype, "_blinkSpeedScale", {
  value: 1.0,
  writable: true,
  enumerable: false,
  configurable: true,
});

/**
 * 眨眼曲线处理器（双眼）。
 *
 * 用法：
 *   const bp = new BlinkCurveProcessor();
 *   bp.updateFrameRate(fps);
 *   const [l, r] = bp.process(eyeBlinkLeft, eyeBlinkRight, timestampMs);
 *   // 就地写回 bs52[9] / bs52[10]
 */
export class BlinkCurveProcessor {
  /**
   * @param {{blinkSpeedScale?: number, enableBinocularSync?: boolean, isEnabled?: boolean}} [options]
   */
  constructor(options = {}) {
    this.leftEye = new SingleEyeProcessor("LeftEye");
    this.rightEye = new SingleEyeProcessor("RightEye");
    this._blinkSpeedScale = 1.0;
    this.enableBinocularSync = options.enableBinocularSync !== false;
    this._isEnabled = true;
    if (Number.isFinite(options.blinkSpeedScale)) this.blinkSpeedScale = options.blinkSpeedScale;
    if (options.isEnabled === false) this.isEnabled = false;
  }

  /**
   * 眨眼速度系数（Kotlin setter: coerceIn(0.3f, 5.0f)）。
   *   1.0 = 默认速度（总计约 230ms）；推荐范围 0.5 ~ 3.0
   */
  get blinkSpeedScale() { return this._blinkSpeedScale; }
  set blinkSpeedScale(value) {
    const v = value < 0.3 ? 0.3 : value > 5 ? 5 : value;
    this._blinkSpeedScale = v;
    this.leftEye._blinkSpeedScale = v;
    this.rightEye._blinkSpeedScale = v;
  }

  /**
   * 是否启用。
   * 【与 Kotlin 一致】setter 置 true 时会 reset()（Kotlin: if (value) reset()）。
   */
  get isEnabled() { return this._isEnabled; }
  set isEnabled(value) {
    this._isEnabled = !!value;
    if (value) this.reset();
  }

  /** 更新帧率（PipelineFaceTracker.kt:1430 按 currentFps.coerceIn(15f,120f) 调）。 */
  updateFrameRate(fps) {
    this.leftEye.updateFrameRate(fps);
    this.rightEye.updateFrameRate(fps);
  }

  /**
   * 处理双眼 blink 值。
   *
   * 【签名说明】Kotlin 是 process(left, right, System.currentTimeMillis())，
   * delta 由处理器内部算并 coerceIn(1,100)。这里保持同一语义。
   *
   * @param {number} leftBlinkRaw 左眼原始 blink 值 [0,1]
   * @param {number} rightBlinkRaw 右眼原始 blink 值 [0,1]
   * @param {number} currentTimeMs 当前时间戳（毫秒）
   * @returns {[number, number]} [leftBlink, rightBlink]
   */
  process(leftBlinkRaw, rightBlinkRaw, currentTimeMs) {
    if (!this._isEnabled) {
      return [leftBlinkRaw, rightBlinkRaw];
    }

    // 先独立处理两只眼睛
    const leftResult = this.leftEye.process(leftBlinkRaw, currentTimeMs);
    const rightResult = this.rightEye.process(rightBlinkRaw, currentTimeMs);

    // 双眼同步逻辑
    if (this.enableBinocularSync) {
      return this._synchronizeBlink(leftResult, rightResult, currentTimeMs);
    }
    return [leftResult, rightResult];
  }

  /**
   * 便捷入口：直接在 52 维数组上就地改写 blink 项（下标 9 / 10）。
   * 返回 [left, right] 便于调用方统计。
   */
  processInto(bs52, currentTimeMs) {
    const [l, r] = this.process(bs52[9], bs52[10], currentTimeMs);
    bs52[9] = l;
    bs52[10] = r;
    return [l, r];
  }

  /**
   * 双眼同步逻辑。
   *
   * 当两只眼睛都在眨眼状态（CLOSING/CLOSED/OPENING）时，
   * 将两只眼睛的 blink 值拉齐到较大的那个值（更闭的那个）。
   * 【与 Kotlin 注释不符，按代码】SQUINT_HOLD 不参与同步。
   */
  _synchronizeBlink(leftResult, rightResult, currentTimeMs) {
    const leftState = this.leftEye.state;
    const rightState = this.rightEye.state;

    const leftInBlink = isBlinkState(leftState);
    const rightInBlink = isBlinkState(rightState);

    if (leftInBlink && rightInBlink) {
      const timeDiff = Math.abs(this.leftEye.stateStartTimeMs - this.rightEye.stateStartTimeMs);
      // 放宽时间窗口（SYNC_WINDOW_MS * 3），因为状态可能不同步
      if (timeDiff <= SYNC_WINDOW_MS * 3) {
        const syncedValue = Math.max(leftResult, rightResult);
        return [syncedValue, syncedValue];
      }
    }

    // 如果一只眼睛刚进入眨眼，另一只也在检测中，同步触发
    if (leftState === EyeState.CLOSING && rightState === EyeState.BLINK_DETECT) {
      const timeDiff = currentTimeMs - this.rightEye.stateStartTimeMs;
      if (timeDiff <= SYNC_WINDOW_MS * 2) {
        return [leftResult, leftResult];
      }
    }

    if (rightState === EyeState.CLOSING && leftState === EyeState.BLINK_DETECT) {
      const timeDiff = currentTimeMs - this.leftEye.stateStartTimeMs;
      if (timeDiff <= SYNC_WINDOW_MS * 2) {
        return [rightResult, rightResult];
      }
    }

    return [leftResult, rightResult];
  }

  /** 重置所有状态。 */
  reset() {
    this.leftEye.reset();
    this.rightEye.reset();
  }

  /** 获取当前状态（用于调试）。对应 Kotlin getDebugInfo()。 */
  getDebugInfo() {
    const ln = EYE_STATE_NAMES[this.leftEye.state].slice(0, 4);
    const rn = EYE_STATE_NAMES[this.rightEye.state].slice(0, 4);
    return "L:" + ln + " R:" + rn + " spd=" + this._blinkSpeedScale;
  }
}
