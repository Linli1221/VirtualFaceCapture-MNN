/**
 * raw-frame-reorder.js — 帧重排缓冲 + 帧间隔/时间戳估计
 *
 * 移植来源（逐条对应，语义不得另行发明）：
 *   facecapture-sdk/src/main/java/com/facecapture/sdk/FrameReorderBuffer.kt
 *
 * 职责：
 *   1. 把并行推理产生的**乱序**结果按 frameId 排回时间顺序；
 *   2. 已经放行过的（或更旧的）frameId 一律丢弃 —— 迟到的帧没有价值；
 *   3. 首个结果立刻放行，不为追求严格连续而空等；
 *   4. 出现缺口（expected 还没到）时最多等 gapTimeoutMs，超时或 maxPending
 *      堆积过多就接受缺口、放行手里最旧的一帧。
 *
 * 【与 Kotlin 注释不符，按代码】Kotlin 的 pending 是 TreeMap<Long, T>，key 为
 * frameId，因此 firstEntry() 天然是「手里最旧（id 最小）的那帧」。JS 没有有序 Map，
 * 这里用「按 id 升序的稀疏数组」等价实现：插入时找位置，drain 时总取 slot[0]。
 * maxPending 默认 8，数组长度极小，线性插入比维护二叉堆更省。
 *
 * frameId 语义（docs/raw-track-protocol.md §7）：上游 decode 层用 32 位序列算术
 * 把 uint32 frameId 展开成单调 Number（extended = lastExtended + int32diff(raw, lastRaw)），
 * 回绕约 828 天一次、展开后无感。本模块只见**严格单调递增的 id**，因此
 * frameId <= lastEmittedId 的过期判定直接成立。epoch(frameId>>7) / seq(frameId&127)
 * 仅进诊断，不参与重排。
 *
 * 有状态模块：**必须顺序喂**，乱序/重复输入由本模块自己裁决（而不是静默接受）。
 */

/** 与 Kotlin FrameReorderBuffer.DEFAULT_MAX_PENDING 一致。 */
export const DEFAULT_MAX_PENDING = 8;

/** 与 Kotlin FrameReorderBuffer.DEFAULT_GAP_TIMEOUT_MS 一致。 */
export const DEFAULT_GAP_TIMEOUT_MS = 60;

/**
 * 帧重排缓冲。
 *
 * 用法：
 *   const buf = new RawFrameReorderBuffer();
 *   for (const f of buf.offer(frame.frameId, frame, nowMs)) pipeline.push(f);
 *   for (const f of buf.poll(nowMs)) pipeline.push(f);   // 无新帧时轮询，防缺口永久卡住
 */
export class RawFrameReorderBuffer {
  /**
   * @param {{maxPending?: number, gapTimeoutMs?: number}} [options]
   *   maxPending   最多缓存多少帧（超出即判定为「缺口不可能补上」），默认 8
   *   gapTimeoutMs 等待缺口的毫秒数；<=0 表示不等待，永远按到达顺序放行，默认 60
   */
  constructor(options = {}) {
    const maxPending = options.maxPending;
    const gapTimeoutMs = options.gapTimeoutMs;
    this.maxPending = Number.isFinite(maxPending) && maxPending > 0
      ? Math.floor(maxPending)
      : DEFAULT_MAX_PENDING;
    this.gapTimeoutMs = Number.isFinite(gapTimeoutMs) ? gapTimeoutMs : DEFAULT_GAP_TIMEOUT_MS;

    /** 待放行帧，按 frameId 升序；元素为 {id, frame}。 */
    this._pending = [];
    /** 最近一次放行的 frameId；null = 还没放行过任何帧。 */
    this._lastEmittedId = null;
    /** 当前缺口的起始时间（毫秒），0 = 没有缺口。 */
    this._gapStartMs = 0;
    /** 堆积超过上限时置位：下一次 drain 直接跳过缺口。 */
    this._forceSkipGap = false;

    // ---- 诊断计数（stats() 用；不是每帧分配）----
    this._droppedLate = 0;
    this._droppedDup = 0;
    this._emitted = 0;
    this._gapsSkipped = 0;
  }

  /** 当前缓存深度（诊断 / 单测用）。 */
  get size() {
    return this._pending.length;
  }

  /** 是否因为缺口正在等待。 */
  get isWaitingForGap() {
    return this._gapStartMs !== 0;
  }

  /**
   * 收到一帧乱序结果，返回**本次可以按顺序放行**的帧（可能为空 / 可能多帧）。
   *
   * @param {number} frameId 单调递增的帧 id（回绕展开由上游 decode 层负责）
   * @param {*} frame 帧负载，本模块不解释其内容
   * @param {number} nowMs 当前时刻（毫秒），用于缺口超时判定
   * @param {Array} [out] 可选的输出数组（复用以避免每帧分配）；不传则新建
   * @returns {Array} 放行的帧，顺序 = frameId 升序
   */
  offer(frameId, frame, nowMs, out = []) {
    out.length = 0;

    // 已经放行过的不再重复；同一帧重复投递也只保留第一份。
    if (this._lastEmittedId !== null && frameId <= this._lastEmittedId) {
      this._droppedLate++;
      return out;
    }
    for (let i = 0; i < this._pending.length; i++) {
      if (this._pending[i].id === frameId) {
        this._droppedDup++;
        return out;
      }
    }

    this._insert(frameId, frame);

    if (this._pending.length > this.maxPending) {
      // 堆积超过上限说明缺口补不上了，强制跳过它（下一轮 drain 生效）。
      this._forceSkipGap = true;
    }
    return this._drain(nowMs, out);
  }

  /**
   * 缺口超时检查。调用方在没有新帧到达时按需轮询，避免为等一帧永久阻塞。
   *
   * @param {number} nowMs 当前时刻（毫秒）
   * @param {Array} [out] 可选输出数组
   * @returns {Array} 放行的帧
   */
  poll(nowMs, out = []) {
    out.length = 0;
    return this._drain(nowMs, out);
  }

  /** 清空（停止 / 重启管线时调用，避免旧会话的帧混进来）。 */
  reset() {
    this._pending.length = 0;
    this._lastEmittedId = null;
    this._gapStartMs = 0;
    this._forceSkipGap = false;
  }

  /** 诊断快照（低频调用，允许分配）。 */
  stats() {
    return {
      pending: this._pending.length,
      lastEmittedId: this._lastEmittedId,
      gapWaiting: this._gapStartMs !== 0,
      forceSkip: this._forceSkipGap,
      droppedLate: this._droppedLate,
      droppedDup: this._droppedDup,
      emitted: this._emitted,
      gapsSkipped: this._gapsSkipped,
    };
  }

  // -------------------------------------------------------------------

  /** 按 frameId 升序插入（线性找位；maxPending 很小，够快且零额外结构）。 */
  _insert(frameId, frame) {
    const pending = this._pending;
    let i = pending.length;
    while (i > 0 && pending[i - 1].id > frameId) i--;
    pending.splice(i, 0, { id: frameId, frame });
  }

  /**
   * Kotlin FrameReorderBuffer.drain() 的逐条等价实现。
   * 【与 Kotlin 注释不符，按代码】Kotlin 用 firstEntry() 取 key 最小的元素，
   * 对应这里的 pending[0]；末尾的 else 分支在 Kotlin 注释里写「理论上不可达」，
   * 这里同样保留（offer 已拦掉过期帧）。
   */
  _drain(nowMs, out) {
    if (this._pending.length === 0) {
      this._gapStartMs = 0;
      return out;
    }

    while (this._pending.length > 0) {
      const first = this._pending[0];
      const firstId = first.id;

      if (this._lastEmittedId === null) {
        // 首个结果直接放行，不空等前一帧。
        out.push(first.frame);
        this._pending.shift();
        this._lastEmittedId = firstId;
        this._gapStartMs = 0;
        this._emitted++;
        continue;
      }

      const expected = this._lastEmittedId + 1;

      if (firstId === expected) {
        out.push(first.frame);
        this._pending.shift();
        this._lastEmittedId = firstId;
        this._gapStartMs = 0;
        this._emitted++;
        continue;
      }

      if (firstId > expected) {
        if (this._gapStartMs === 0) this._gapStartMs = nowMs;
        const timedOut = this._forceSkipGap ||
          this.gapTimeoutMs <= 0 ||
          nowMs - this._gapStartMs >= this.gapTimeoutMs;
        if (!timedOut) break;
        // 缺口超时 / 堆积过多：接受丢帧，直接放行手里最旧的一帧。
        out.push(first.frame);
        this._pending.shift();
        this._lastEmittedId = firstId;
        this._gapStartMs = 0;
        this._forceSkipGap = false;
        this._gapsSkipped++;
        this._emitted++;
        continue;
      }

      // else：理论上不可达（offer 已经拦掉过期帧），保险起见丢弃。
      this._pending.shift();
    }
    return out;
  }
}

/**
 * uint32 frameId 的回绕展开（docs/raw-track-protocol.md §7.4）。
 *
 * 32 位序列算术：extended = lastExtended + int32diff(raw, lastRaw)，
 * 其中 int32diff(a,b) 把 (a-b) 的低 32 位按有符号解释。
 * 等价于「(int32)(a-b) < 0 视为 a 更旧」的环回比较，并把约 828 天一次的
 * 回绕无感展开成单调 Number。decode 层调用一次即可，重排层只见单调 id。
 *
 * @param {number} raw 当前 uint32 frameId
 * @param {number|null} lastRaw 上一次的 uint32 frameId（null = 首帧）
 * @param {number|null} lastExtended 上一次展开后的 id（null = 首帧）
 * @returns {{raw: number, extended: number}}
 */
export function unwrapFrameId(raw, lastRaw, lastExtended) {
  const u = raw >>> 0;
  if (lastRaw === null || lastRaw === undefined || lastExtended === null || lastExtended === undefined) {
    return { raw: u, extended: u };
  }
  let diff = (u - (lastRaw >>> 0)) | 0; // 32 位有符号差
  return { raw: u, extended: lastExtended + diff };
}

/**
 * 帧间隔 / 时间戳估计（任务书：raw-frame-reorder.js 负责「帧间隔/时间戳计算」）。
 *
 * 同时承担两件事，避免下游各自算一遍：
 *   1. 每帧 dt（秒）—— 给 temporal-filter 与 body-fusion 用；
 *   2. 实测 fps 的 EMA —— 给 blink-curve 的 updateFrameRate() 用
 *      （PipelineFaceTracker.kt:1430 是 blinkCurveProcessor.updateFrameRate(
 *        currentFps.coerceIn(15f, 120f))）。
 *
 * 【三个消费域的 dt clamp 口径不同，不能共用一个裁剪后的值】（docs/post-pipeline-migration.md
 * 附录 B / H / K.1）：
 *   ① temporal-filter（TimeBasedEMA，478 点域 + rawHeadPose）：dt clamp [0.001, 0.5] 秒；
 *   ② body-fusion（One-Euro / TorsoBaseline）：dt clamp [1, 500] 毫秒，且「无锚点」
 *      （首帧 / tracking 丢失后）取 1/15 秒 —— 那个兜底在 body-fusion 内部做，本类不管；
 *   ③ blink-curve：deltaMs clamp [1, 100] 毫秒（Kotlin BlinkCurveProcessor 的 coerceIn）。
 * 因此 push() 返回**未裁剪**的原始 dtMs，三个域各自按自己的契约取用；
 * fps EMA 维持现状（喂 updateFrameRate 前已 clamp [15,120]，与 Kotlin
 * currentFps.coerceIn(15f, 120f) 一致）。
 */
export class FrameIntervalTracker {
  /**
   * @param {{initialFps?: number, minFps?: number, maxFps?: number, fpsEmaAlpha?: number}} [options]
   */
  constructor(options = {}) {
    this.minFps = Number.isFinite(options.minFps) ? options.minFps : 15;
    this.maxFps = Number.isFinite(options.maxFps) ? options.maxFps : 120;
    this._fps = Number.isFinite(options.initialFps) ? options.initialFps : 30;
    this._fpsEmaAlpha = Number.isFinite(options.fpsEmaAlpha) ? options.fpsEmaAlpha : 0.1;
    this._lastT = null;
    /** 最近一次**未裁剪**的原始 dt（毫秒）。 */
    this.rawDtMs = 1000 / this._fps;
    /** 时间戳回退次数（诊断；不静默接受乱序）。 */
    this.regressed = 0;
  }

  /** 实测 fps（已 clamp 到 [minFps, maxFps]），直接喂 BlinkCurveProcessor.updateFrameRate。 */
  get fps() {
    return this._fps;
  }

  /**
   * 喂一帧的采集时间戳，返回该帧与上一帧的**原始** dt（毫秒，未裁剪）。
   *
   * 时间戳回退时返回 0（并计数），不静默接受 —— 乱序由上游重排层裁决。
   *
   * @param {number} timestampMs 帧的采集时间（与手机时钟同源）
   * @returns {number} dt（毫秒）；首帧返回 1000/fps 估计值
   */
  push(timestampMs) {
    if (this._lastT === null) {
      this._lastT = timestampMs;
      this.rawDtMs = 1000 / this._fps;
      return this.rawDtMs;
    }
    const dtMs = timestampMs - this._lastT;
    if (dtMs < 0) {
      // 时间戳回退：不推进状态，交由调用方决定是否丢帧。
      this.regressed++;
      this.rawDtMs = 0;
      return 0;
    }
    this._lastT = timestampMs;
    this.rawDtMs = dtMs;

    // fps 的 EMA：用裁剪到 [1,100]ms 的口径（对应 Kotlin updateFrameRate 的输入稳定）。
    const clamped = dtMs < 1 ? 1 : dtMs > 100 ? 100 : dtMs;
    const inst = 1000 / clamped;
    this._fps += this._fpsEmaAlpha * (inst - this._fps);
    this._fps = Math.min(this.maxFps, Math.max(this.minFps, this._fps));
    return this.rawDtMs;
  }

  /** ① temporal-filter 口径：dt clamp [0.001, 0.5] 秒。 */
  get dtFilterSeconds() {
    const s = this.rawDtMs / 1000;
    return s < 0.001 ? 0.001 : s > 0.5 ? 0.5 : s;
  }

  /** ② body-fusion 口径：dt clamp [1, 500] 毫秒 -> 秒；「无锚点」的 1/15 秒兜底由 body-fusion 自己做。 */
  get dtBodySeconds() {
    const ms = this.rawDtMs < 1 ? 1 : this.rawDtMs > 500 ? 500 : this.rawDtMs;
    return ms / 1000;
  }

  /** ③ blink-curve 口径：deltaMs clamp [1, 100] 毫秒（Kotlin coerceIn(1,100)）。 */
  get dtBlinkMs() {
    return this.rawDtMs < 1 ? 1 : this.rawDtMs > 100 ? 100 : this.rawDtMs;
  }

  reset() {
    this._lastT = null;
    this._fps = 30;
    this.rawDtMs = 1000 / this._fps;
    this.regressed = 0;
  }
}
