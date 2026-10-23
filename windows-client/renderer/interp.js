/**
 * interp.js — 输入帧率低 / 不稳定时的播放时间轴与抖动缓冲（纯函数模块，Node 可直接 import）
 *
 * 背景：手机端可能只推 20~30 fps，而且由于推理耗时波动、Wi-Fi 调度、PC 端 GC / 渲染卡顿，
 * 帧的**到达间隔并不均匀**（有时两帧挤在同一毫秒，有时隔了 60 ms）。PC 端按 60 fps 渲染。
 *
 * 若「收到一帧就把参数设成目标值、每帧再缓动追它」：
 *   - 一次输入要撑 2~3 个渲染帧，画面先冲一大步、再拖尾，输入越慢越像「一卡一卡」；
 *   - 到达间隔一抖，模型速度就跟着忽快忽慢，挤在一起的两帧甚至会在一个渲染帧里跳过去。
 *
 * 做法：维护一条**播放时间轴**，渲染时取「当前时刻 − 自适应延迟」处的位置做线性插值。
 *
 * 时间轴用哪个时间？
 *   - 优先用手机报文里的 `t`（采集时间戳，协议 §4.2）。WS/TCP 是有序的，但**到达**时间会被
 *     PC 的调度批量推迟：两帧隔 1 ms 一起到达，并不代表它们在采集时只隔 1 ms。用 `t` 可以把
 *     这种「传输/调度抖动」从运动时间里剔除，两帧仍按真实间隔播放。
 *   - 手机与本机时钟不同步：用「到达时间 − 采集时间」的**最近窗口最小值**估计偏移（网络只会加
 *     延迟，最小值最接近真实偏移），但偏移**每帧最多跟随 offsetStepMs**，不会因为一个新最小值就
 *     把时间轴往回拽 —— 时间轴一拽，播放就会压缩／拉伸一帧，看起来反而是一次跳帧。
 *     剩下的传输抖动交给延迟（滞后 p90）兜底。
 *   - 偏移一次性跳变超过 offsetJumpMs（手机端帧缓冲重新预缓冲 / 网络路径切换）：重新锚定并清空
 *     样本，绝不让时间轴倒退。
 *   - 没有 `t` 时退化为按到达时间排队（旧行为）。
 *
 * 延迟（抖动缓冲）怎么定？
 *   - 最近 intervalWindow 个输入间隔取 p85（intervalQuantile）：单次 200 ms 的抽风不会把延迟
 *     永久抬高，但持续抖动会被覆盖；
 *   - 再加上传输滞后 p90（lagQuantile，仅 `t` 模式）：帧可能比它的播放时间晚到多久
 *     （展示用的「输入抖动」取滞后 p90 − p10 的跨度）；
 *   - 再留 jitterMarginMs 余量，上限 maxDelayMs（默认 120 ms）。
 *   - 输入够快（p85 ≤ 渲染间隔 × slowInputRatio，即 60 fps 输入）时延迟为 0：
 *     直接显示最新帧，不额外加延迟。
 *
 * 延迟怎么变？
 *   - 快升慢降：需要更多缓冲时按 attackMs 靠近，缓冲足够后再按 releaseMs 慢慢回落，
 *     避免输入节奏一抖延迟就来回跳；
 *   - 上升 / 下降都限速（maxRiseRate / maxFallRate），保证 sampleAt 单调递增，
 *     画面只会轻微放慢或加快，**不会倒带**。
 *
 * ────────────────────────────────────────────────────────────────────────────
 * jitterBufferV1 模式（options.jitterBuffer = true，M1：把手机端固定 500 ms 发送缓冲
 * 迁到 PC）——**职责边界**（不要在别处再叠一层缓冲，两层延迟会相加）：
 *
 *   ┌─ jitter buffer（自适应缓冲深度控制器，本类的 delayMs / primed 部分）────────┐
 *   │  决定「渲染时刻应落后输入时间轴多少」：                                     │
 *   │   · 起播预缓冲（primed）与断流后重新预缓冲；                                │
 *   │   · 深度目标分档：稳定 80~150 ms / 抖动 200~300 ms / 剧烈 400~500 ms；      │
 *   │   · 依据：输入间隔 p85/p95、连续大间隔次数、seq 跳变次数、缓冲见底次数；    │
 *   │   · 快升慢降 + 变化限速（保证 sampleAt 单调、绝不倒带）；                   │
 *   │   · 见底 / 溢出统计。它**不持有报文队列**，只输出一个 delayMs。             │
 *   └──────────────────────────────────────────────────────────────────────────┘
 *   ┌─ ParamTimeline（播放时间轴，本类的 samples / push / sample 部分）───────────┐
 *   │  决定「给定播放时刻，参数应该是什么」：                                     │
 *   │   · 样本存储（track.t + 时钟偏移 → 媒体时间轴）与线性插值；                 │
 *   │   · 时钟偏移估计（最近 12 帧「到达−采集」最小值，逐帧小步跟随）；           │
 *   │   · 断流外推上限（≤40 ms 且 ≤0.75 个输入间隔）与乱序丢弃。                 │
 *   │   它不决定延迟多少，只消费 jitter buffer 给出的 delayMs。                   │
 *   └──────────────────────────────────────────────────────────────────────────┘
 * 两层共用同一条时间轴（sampleAt = now − delayMs 在 samples 上插值），
 * **不存在第二套队列**，所以延迟不会叠加。
 *
 * jitterBufferV1 的三档深度由信号强弱决定（见 _targetDelayJitter）：
 *   - 稳定（无大间隔、无 seq 跳变、无见底、传输滞后小）：80~150 ms；
 *   - 抖动（偶发大间隔 / 少量 seq 跳变 / 滞后 p90-p10 ≥ 40 ms）：200~300 ms；
 *   - 剧烈（连续 ≥3 次大间隔 / 2 s 内 ≥3 次 seq 跳变或见底 / 滞后 ≥150 ms）：400~500 ms。
 * 上升快（attack，抖动一出现立即加深）、回落慢（release，稳定后慢慢收回）。
 * 延迟估计一律用**分位数**（p85/p95），不用最大值——单次 200 ms 抽风不应永久抬高延迟。
 * ────────────────────────────────────────────────────────────────────────────
 *
 * 边界行为：
 *   - 乱序样本直接丢弃（seq 丢旧帧已在上层做过，这里只做最后一道保险）；
 *   - 断流时最多外推 extrapolateMs（默认 40 ms），不会让模型飞出去；
 *   - 断流超过 maxIntervalMs 清空样本、重新测节奏；jitterBufferV1 下同时重新预缓冲。
 *
 * `sample()` 返回的是内部复用对象（用完即弃），调用方不要保存引用。
 */

import { PARAM_IDS } from "./mapping.js";
import { paramSpan } from "./filter.js";

export const INTERP_DEFAULTS = Object.freeze({
  /** 目标渲染帧间隔（ms），用于判断「输入是否比渲染慢」。 */
  renderIntervalMs: 1000 / 60,
  /** 最多保留多少帧输入（12 帧 ≈ 60 fps 下 200 ms，30 fps 下 400 ms）。 */
  maxSamples: 12,
  /** 插值延迟上限（ms），防止低帧率输入把延迟拉到不可接受。 */
  maxDelayMs: 120,
  /** 输入间隔超过渲染间隔的多少倍时，才启用插值缓冲。 */
  slowInputRatio: 1.2,
  /** 断流时最多向后外推的时长（ms）。 */
  extrapolateMs: 40,
  /** 外推最多到「一个输入间隔」的多少倍。 */
  maxExtrapolateRatio: 0.75,
  /** 参与节奏估计的最小 / 最大帧间隔（ms）。 */
  minIntervalMs: 4,
  maxIntervalMs: 250,
  /** 间隔统计窗口长度（帧）：8 帧在 30 fps 下约 270 ms，节奏变化能较快跟上。 */
  intervalWindow: 8,
  intervalQuantile: 0.85,
  /** 传输滞后（到达时间 − 采集时间 − 偏移）的窗口长度与分位数。 */
  lagWindow: 12,
  lagQuantile: 0.9,
  /** 延迟里固定留的余量（ms）。 */
  jitterMarginMs: 8,
  /** 延迟上升 / 回落的时间常数（ms）：快升慢降。 */
  attackMs: 120,
  releaseMs: 600,
  /** 延迟每次采样最多变化「经过时间 × 该系数」，保证播放不会倒带。 */
  maxRiseRate: 0.6,
  maxFallRate: 0.5,
  /** 输入间隔 EMA 系数（仅用于展示 inputIntervalMs）。 */
  intervalSmoothing: 0.25,
  /** 手机→本机时钟偏移每帧最多跟随多少（ms）：限制时间轴的压缩/拉伸，抖动交给延迟兜底。 */
  offsetStepMs: 1,
  /** 估计偏移时取「最近多少帧的（到达时间 − 采集时间）」最小值。 */
  offsetWindow: 12,
  /** 偏移突变超过该值时重新锚定（ms）：手机端帧缓冲重新预缓冲 / 网络路径切换。 */
  offsetJumpMs: 120,

  // ---- jitterBufferV1（PC 侧自适应抖动缓冲）专属默认值 ----
  /** 总开关：true = 自适应深度 + 起播预缓冲（M1 新逻辑）；false = 旧行为（快速输入延迟为 0）。 */
  jitterBuffer: false,
  /** 缓冲深度分档（ms）：稳定 / 抖动 / 剧烈。 */
  depthMinMs: 80,
  depthStableMaxMs: 150,
  depthJitterMinMs: 200,
  depthJitterMaxMs: 300,
  depthHeavyMinMs: 400,
  depthHeavyMaxMs: 500,
  /** 深度估计用的间隔分位数（p85 做基线展示、p95 做余量；禁止用最大值）。 */
  intervalMidQuantile: 0.5,
  intervalHighQuantile: 0.95,
  /** 单个间隔超过「p50 × 该倍数」（且不小于 bigGapMinMs）算一次大间隔。 */
  bigGapRatio: 2.0,
  bigGapMinMs: 80,
  /** 连续大间隔 ≥ 该次数 → 判为剧烈抖动。 */
  bigGapStreakHeavy: 3,
  /** 2 s 窗口内 seq 跳变 ≥ 该次数：1 次 → 抖动档，heavy 次 → 剧烈档。 */
  seqGapWindowMs: 2000,
  seqGapHeavyCount: 3,
  /** 2 s 窗口内缓冲见底 ≥ 该次数 → 剧烈档（见底说明当前深度不够用）。 */
  starveWindowMs: 2000,
  starveHeavyCount: 3,
  /** 传输滞后抖动（p90 − p10）阈值：≥ light 进抖动档，≥ heavy 进剧烈档（ms）。 */
  transitJitterLightMs: 40,
  transitJitterHeavyMs: 150,
  /** 输入间隔抖动（p95 − p50）阈值：≥ light 进抖动档，≥ heavy 进剧烈档（ms）。 */
  spreadLightMs: 40,
  spreadHeavyMs: 120,
  /** 起播预缓冲超时（ms）：低帧率 / 单帧流下等不到「攒够深度」时兜底起播。 */
  primeTimeoutMs: 800,
  /** jitter 模式的延迟变化时间常数与限速：快升慢降，且 rise rate < 1 保证不倒带。 */
  attackMsJitter: 100,
  releaseMsJitter: 2500,
  maxRiseRateJitter: 0.6,
  maxFallRateJitter: 0.1,
  /** 档位迟滞：升档立即（快升），降档要保持 tierHoldMs（慢降），
   *  避免信号在阈值附近抖动时深度目标 200↔400 来回横跳、画面反复放慢。 */
  tierHoldMs: 2000,
});

function nowMs() {
  return typeof performance !== "undefined" && typeof performance.now === "function"
    ? performance.now()
    : Date.now();
}

/**
 * 输入侧动作速度（任务 M4：最终滤波的「快升」前馈，见 filter.js）。
 *
 * 时间轴最清楚「输入帧之间隔了多久」（播放时间口径，不是到达时间），在这里按
 * `|Δ参数| / 满量程 / Δt` 估计每个参数的**归一化速度**（满量程/秒）。filter.js 的
 * 速度自适应低通把它当 feed-forward：快动作在输入侧一出现就弱滤波（跟手），不用等
 * 渲染侧微分慢半拍；快慢塑形（快升慢降）仍由 filter.js 统一处理，这里只做轻 EMA。
 */
const SPEED_EMA = 0.5;
/** 速度样本的最小时间间隔（ms）：同一时刻的重复帧不参与速度估计。 */
const SPEED_MIN_INTERVAL_MS = 2;

function isFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

/** 把 src 里的 10 个参数写进 out（缺失 / 非数字的按 0 处理）。 */
function copyParams(src, out) {
  for (const id of PARAM_IDS) {
    const value = src?.[id];
    out[id] = isFiniteNumber(value) ? value : 0;
  }
  return out;
}

/** out = a + (b - a) * u，u 允许 > 1（线性外推）。 */
function lerpParams(a, b, u, out) {
  for (const id of PARAM_IDS) {
    const av = a?.[id];
    const bv = b?.[id];
    const aOk = isFiniteNumber(av);
    const bOk = isFiniteNumber(bv);
    out[id] = aOk ? (bOk ? av + (bv - av) * u : av) : bOk ? bv : 0;
  }
  return out;
}

/** 分位数（最近秩，不改动入参数组）。空数组返回 0。 */
function percentile(values, q) {
  const n = values.length;
  if (!n) return 0;
  if (n === 1) return values[0];
  const sorted = values.slice().sort((a, b) => a - b);
  const pos = Math.min(n - 1, Math.max(0, Math.round((n - 1) * q)));
  return sorted[pos];
}

export class ParamTimeline {
  /** @param {Partial<typeof INTERP_DEFAULTS>} [options] */
  constructor(options = {}) {
    this.options = { ...INTERP_DEFAULTS, ...options };
    const o = this.options;
    this.jitter = Boolean(o.jitterBuffer);
    // jitter 模式要留得住「一个缓冲深度」的样本（500 ms @ 60 fps ≈ 30 帧），否则溢出把
    // 深度吃掉、见底计数飙升；样本对象很轻（t + 参数引用），64 条上限仍然有界。
    if (this.jitter && options.maxSamples == null) this.options.maxSamples = 64;
    /** @type {{t:number, params:object}[]} 按时间升序排列（t 为播放时间轴，不是到达时间） */
    this.samples = [];
    /** 最近一次 sample() 的复用输出对象。 */
    this.out = Object.create(null);
    /** 输入间隔 EMA（ms），0 = 还没测出来；仅用于展示。 */
    this.inputIntervalMs = 0;
    /** 最近间隔的 p85 − p15（ms）：采集/发送节奏本身有多不齐。 */
    this.inputJitterMs = 0;
    /** 传输滞后 p90 − p10（ms）：到达时间相对播放时间轴抖得多厉害（仅手机时间戳模式）。 */
    this.transitJitterMs = 0;
    /** 当前插值延迟（ms），0 = 直接取最新帧。 */
    this.delayMs = 0;
    /** sample() 时缓冲已经见底的帧数（累计，统计用；正常应接近 0）。 */
    this.starvedFrames = 0;
    /** 缓冲见底的次数（连续见底只记一次；调试面板显示这个更有意义）。 */
    this.starvedEpisodes = 0;
    this._intervals = [];
    this._lags = [];
    this._lastAt = 0;
    this._delayAt = null;
    /** 本机时间 − 手机采集时间（ms），每帧最多跟随 offsetStepMs。 */
    this._stampBase = null;
    /** 最近 offsetWindow 帧的「到达时间 − 采集时间」，用于取最小值估计偏移。 */
    this._baseWindow = [];
    this._lastStamp = null;
    this._starved = false;

    // ---- jitterBufferV1 状态 ----
    /** 是否已起播（jitter 模式）：false = 还在预缓冲，sample() 保持首帧不推进时间轴。 */
    this.primed = !this.jitter;
    /** 本次预缓冲的起点（到达时间口径，超时兜底用）。 */
    this._primeStartAt = null;
    /** sampleAt 单调保护：播放时刻绝不允许倒退。 */
    this._lastSampleAt = -Infinity;
    /** 最近 seqGapWindowMs 内 seq 跳变发生的时刻（到达时间口径）。 */
    this._seqGapAt = [];
    /** 最近 starveWindowMs 内见底 episode 开始的时刻。 */
    this._starveAt = [];
    /** 连续大间隔计数（正常间隔清零）。 */
    this._bigGapStreak = 0;
    /** 上一帧 seq（丢帧 / 跳变检测用；null = 未见过）。 */
    this._lastSeq = null;
    /** 当前深度档（调试 / 调试面板展示）："stable" | "jitter" | "heavy"。 */
    this.depthTier = "stable";
    /** 档位迟滞：开始考虑降档的时刻（null = 没有在考虑降档）。 */
    this._tierDownAt = null;

    // ---- M4：输入侧速度估计（feed-forward，见文件头 SPEED_EMA） ----
    /** 每个参数的归一化速度 EMA（满量程/秒），speedOf(id) 读取。 */
    this._speeds = new Map();
    this._lastSpeedAt = null;
    this._lastSpeedParams = null;
  }

  get count() {
    return this.samples.length;
  }

  /** 是否正在用插值缓冲（延迟 > 0.5 ms）。 */
  get interpolating() {
    return this.delayMs > 0.5;
  }

  /**
   * @param {{keepTiming?:boolean, keepPriming?:boolean}} [options]
   *   keepTiming  = 保留已测出的节奏与时钟偏移；
   *   keepPriming = 保留起播状态（截图 / 单步 renderNow 用：不想因为一次 reset 又进入预缓冲）。
   */
  reset(options = {}) {
    this.samples.length = 0;
    this.delayMs = 0;
    this._delayAt = null;
    this._lags.length = 0;
    this.starvedFrames = 0;
    this.starvedEpisodes = 0;
    this._starved = false;
    this._lastSampleAt = -Infinity;
    // M4：时间轴清空 = 运动基线重来，速度估计一起清（否则断线恢复后前几帧速度是旧的）
    this._speeds.clear();
    this._lastSpeedAt = null;
    this._lastSpeedParams = null;
    if (this.jitter && !options.keepPriming) {
      // 新会话 / 断线：重新走起播预缓冲（M1 要求：onopen / onhello / 重连都要重新 primed）
      this.primed = false;
      this._primeStartAt = null;
    }
    if (!options.keepTiming) {
      this._intervals.length = 0;
      this.inputIntervalMs = 0;
      this.inputJitterMs = 0;
      this.transitJitterMs = 0;
      this._stampBase = null;
      this._baseWindow.length = 0;
      this._lastStamp = null;
      this._seqGapAt.length = 0;
      this._starveAt.length = 0;
      this._bigGapStreak = 0;
      this._lastSeq = null;
      this.depthTier = "stable";
      this._tierDownAt = null;
    }
  }

  /** 重开一段：清空样本，按当前帧重新建立时间基准。 */
  _rebase(atMs, stampMs) {
    this.samples.length = 0;
    this._lags.length = 0;
    this._stampBase = stampMs == null ? null : atMs - stampMs;
    this._baseWindow.length = 0;
    if (stampMs != null) this._baseWindow.push(atMs - stampMs);
    this._lastStamp = stampMs;
    this._starved = false;
    if (this.jitter) {
      // 时间基准重开（手机时钟回跳 / 时间戳模式切换）：重新预缓冲，避免新段时间轴被旧延迟拖着
      this.primed = false;
      this._primeStartAt = atMs;
    }
  }

  /**
   * 记录一帧输入参数。
   * @param {object} params 形如 { ParamAngleX: number, ... }
   * @param {number} [at] 到达时间（performance.now() 口径，ms）
   * @param {number} [stamp] 手机采集时间戳（track.t，手机本地毫秒）；缺省则按到达时间排队
   * @param {number} [seq] 帧序号（track.seq）；仅用于跳变统计，去重在上层已做过
   * @returns {boolean} 是否入队（乱序 / 参数缺失返回 false）
   */
  push(params, at = nowMs(), stamp = null, seq = null) {
    if (!params || typeof params !== "object") return false;
    const o = this.options;
    const atMs = isFiniteNumber(at) ? at : this.samples.length ? this._lastAt : 0;
    const stampMs = isFiniteNumber(stamp) ? Number(stamp) : null;

    // seq 跳变统计（2 s 窗口）：跳变 = 丢帧 / 网络拥塞，是加深缓冲的信号之一。
    // seq 回跳（新会话从 0 重新计数）不算跳变，只重置基线。
    const seqNum = isFiniteNumber(seq) ? Number(seq) : null;
    if (seqNum != null) {
      if (this._lastSeq != null && seqNum > this._lastSeq + 1) {
        this._seqGapAt.push(atMs);
        this._pruneWindow(this._seqGapAt, atMs, o.seqGapWindowMs);
      }
      this._lastSeq = seqNum;
    }

    if (stampMs == null) {
      // 从「有手机时间戳」退回「按到达时间」：两套时间基准不能混用，重开一段
      if (this._lastStamp != null || this._stampBase != null) this._rebase(atMs, null);
    } else {
      // 手机时钟回跳：按新基准重开一段
      if (this._lastStamp != null && stampMs < this._lastStamp) this._rebase(atMs, stampMs);

      const base = atMs - stampMs;
      const window = this._baseWindow;
      window.push(base);
      if (window.length > o.offsetWindow) window.shift();
      const targetBase = Math.min(...window);
      if (this._stampBase == null) {
        // 首个样本留一点余量，避免开局媒体时间跑到到达时间前面
        this._stampBase = targetBase - o.jitterMarginMs;
      } else if (Math.abs(targetBase - this._stampBase) > o.offsetJumpMs) {
        // 偏移突变（手机端帧缓冲重新预缓冲、网络路径切换）：重锚，别让时间轴整体滞后
        this._stampBase = base;
        window.length = 0;
        window.push(base);
        this.samples.length = 0;
        this._lags.length = 0;
      } else {
        // 每帧只走一小步：网络抖动不会把时间轴往回拽（那会造成播放压缩/跳帧）
        const delta = targetBase - this._stampBase;
        this._stampBase += Math.max(-o.offsetStepMs, Math.min(o.offsetStepMs, delta));
      }
    }

    if (stampMs != null && stampMs === this._lastStamp && this.samples.length) {
      // 同一毫秒内的第二帧：时间轴表达不了两个不同时刻，直接覆盖成最新内容
      const dup = this.samples[this.samples.length - 1];
      dup.params = params;
      this._lastAt = dup.t;
      return true;
    }

    let t = stampMs != null ? stampMs + this._stampBase : atMs;
    const last = this.samples[this.samples.length - 1];

    if (last) {
      if (t < last.t) return false; // 乱序：丢弃
      if (t === last.t) {
        last.params = params; // 同一时刻的重复帧：覆盖，不计入节奏
        this._lastAt = t;
        this._lastStamp = stampMs;
        return true;
      }
      const dt = t - last.t;
      if (dt > o.maxIntervalMs) {
        // 断流太久：旧样本对插值已无意义，重新起一段（节奏也重新测）
        this.samples.length = 0;
        this._intervals.length = 0;
        this._lags.length = 0;
        this.inputIntervalMs = 0;
        this.inputJitterMs = 0;
        this.transitJitterMs = 0;
        this._bigGapStreak = 0;
        if (this.jitter) {
          // 断流恢复：重新走起播预缓冲（不倒带：新段时间轴只向前，sampleAt 由单调保护兜底）
          this.primed = false;
          this._primeStartAt = atMs;
        }
        if (stampMs != null) {
          this._stampBase = atMs - stampMs;
          this._baseWindow.length = 0;
          this._baseWindow.push(this._stampBase);
        }
        t = atMs;
      } else if (dt >= o.minIntervalMs) {
        this._intervals.push(dt);
        if (this._intervals.length > o.intervalWindow) this._intervals.shift();
        this.inputIntervalMs =
          this.inputIntervalMs > 0 ? this.inputIntervalMs + (dt - this.inputIntervalMs) * o.intervalSmoothing : dt;
        // 连续大间隔统计：和「最近窗口最慢节奏」（min，与偏移估计同思路）比，
        // 超过 bigGapRatio 倍才算大间隔——均匀慢（如稳定 5 fps）不算抖动，单帧抽风也不累进。
        const baseline = Math.min(...this._intervals);
        if (dt > Math.max(o.bigGapMinMs, baseline * o.bigGapRatio)) this._bigGapStreak += 1;
        else this._bigGapStreak = 0;
      }
    }

    if (stampMs != null) {
      // 有符号：负值表示媒体时间跑到了到达时间后面（多出来的延迟由 offset 吃掉）
      this._lags.push(atMs - t);
      if (this._lags.length > o.lagWindow) this._lags.shift();
    }

    this._updateJitterStats();
    this._updateSpeed(t, params);

    this.samples.push({ t, params });
    if (this.samples.length > o.maxSamples) this.samples.shift();
    this._lastAt = t;
    this._lastStamp = stampMs;
    if (this.jitter && !this.primed && this._primeStartAt == null) this._primeStartAt = atMs;
    return true;
  }

  /**
   * 输入侧速度估计（M4 前馈）：相邻两帧输入的归一化速度（满量程/秒）做轻 EMA。
   * 只在真正排进时间轴的新帧（dt ≥ SPEED_MIN_INTERVAL_MS）时更新；覆盖帧 / 乱序帧不算。
   */
  _updateSpeed(t, params) {
    const lastT = this._lastSpeedAt;
    const lastParams = this._lastSpeedParams;
    this._lastSpeedAt = t;
    this._lastSpeedParams = params;
    if (lastT == null || !lastParams) return;
    const dt = (t - lastT) / 1000;
    if (!(dt * 1000 >= SPEED_MIN_INTERVAL_MS)) return;
    for (const id of PARAM_IDS) {
      const a = Number(lastParams?.[id]);
      const b = Number(params?.[id]);
      if (!isFiniteNumber(a) || !isFiniteNumber(b)) continue;
      const speed = Math.min(200, Math.abs(b - a) / paramSpan(id) / dt);
      const prev = this._speeds.get(id) ?? 0;
      this._speeds.set(id, prev + (speed - prev) * SPEED_EMA);
    }
  }

  /** 某个参数的输入侧速度（满量程/秒，轻 EMA；没测过返回 0）。filter.js 做 feed-forward。 */
  speedOf(id) {
    const value = this._speeds.get(id);
    return isFiniteNumber(value) ? value : 0;
  }

  /** 全部参数里最大的输入速度（调试面板 / 自检展示用）。 */
  get motionSpeed() {
    let max = 0;
    for (const value of this._speeds.values()) if (value > max) max = value;
    return max;
  }

  _updateJitterStats() {
    const o = this.options;
    if (this._intervals.length >= 3) {
      const high = percentile(this._intervals, o.intervalQuantile);
      const low = percentile(this._intervals, 1 - o.intervalQuantile);
      this.inputJitterMs = Math.max(0, high - low);
    } else {
      this.inputJitterMs = 0;
    }
    this.transitJitterMs =
      this._lags.length >= 3
        ? Math.max(0, percentile(this._lags, o.lagQuantile) - percentile(this._lags, 1 - o.lagQuantile))
        : 0;
  }

  /** 根据输入节奏 / 传输抖动算出的目标延迟：输入跟得上渲染就是 0。 */
  targetDelayMs() {
    if (this.jitter) return this._targetDelayJitter(this._lastAt);
    const o = this.options;
    if (!this._intervals.length) return 0;
    const high = percentile(this._intervals, o.intervalQuantile);
    if (!(high > o.renderIntervalMs * o.slowInputRatio)) return 0;
    // 只有「帧比时间轴晚到」才需要额外缓冲；早到由偏移吸收，不加延迟
    const lag = this._lags.length ? Math.max(0, percentile(this._lags, o.lagQuantile)) : 0;
    return Math.min(high + lag + o.jitterMarginMs, o.maxDelayMs);
  }

  /** 从时刻数组里剔除掉出窗口的记录（数组按时间升序，头部弹出即可）。 */
  _pruneWindow(list, nowMs, windowMs) {
    while (list.length && nowMs - list[0] > windowMs) list.shift();
  }

  /**
   * jitterBufferV1 的深度目标：按信号强弱分三档（稳定 / 抖动 / 剧烈），
   * 档内由「输入间隔 p85 + 传输滞后 p95 + 余量」的**分位数**决定（绝不用最大值）。
   *
   * 信号一览（详见类头部文档）：
   *   · 输入间隔 p85/p95：p95 − p50 越大说明生产节奏越不稳；
   *   · 连续大间隔次数（_bigGapStreak）：≥3 次直接判剧烈；
   *   · seq 跳变（2 s 窗口）：丢帧 / 拥塞的直接证据；
   *   · 缓冲见底次数（2 s 窗口）：当前深度不够用的直接证据；
   *   · 传输滞后抖动（p90 − p10）：到达时间被网络 / 调度抖成什么样。
   */
  _targetDelayJitter(now) {
    const o = this.options;
    if (!this._intervals.length) {
      // 节奏还没测出来（预缓冲初期）：先按稳定档下限起步，别以 0 延迟开播
      this.depthTier = "stable";
      return o.depthMinMs;
    }
    const p50 = percentile(this._intervals, o.intervalMidQuantile);
    const p85 = percentile(this._intervals, o.intervalQuantile);
    const p95 = percentile(this._intervals, o.intervalHighQuantile);
    const spread = Math.max(0, p95 - p50);
    const lag95 = this._lags.length ? Math.max(0, percentile(this._lags, o.lagQuantile)) : 0;

    this._pruneWindow(this._seqGapAt, now, o.seqGapWindowMs);
    this._pruneWindow(this._starveAt, now, o.starveWindowMs);
    const seqGaps = this._seqGapAt.length;
    const starves = this._starveAt.length;

    const heavy =
      this._bigGapStreak >= o.bigGapStreakHeavy ||
      seqGaps >= o.seqGapHeavyCount ||
      starves >= o.starveHeavyCount ||
      this.transitJitterMs >= o.transitJitterHeavyMs ||
      spread >= o.spreadHeavyMs;
    const light =
      this._bigGapStreak >= 1 ||
      seqGaps >= 1 ||
      starves >= 1 ||
      this.transitJitterMs >= o.transitJitterLightMs ||
      spread >= o.spreadLightMs;
    let tier = heavy ? "heavy" : light ? "jitter" : "stable";

    // 档位迟滞：升档立即生效（抖动一出现就加深），降档必须稳定保持 tierHoldMs ——
    // 信号在阈值附近抖动时深度目标不会 200↔400 来回横跳（横跳会反复触发「加深→放慢」）。
    const rank = { stable: 0, jitter: 1, heavy: 2 };
    if (rank[tier] < rank[this.depthTier]) {
      if (this._tierDownAt == null) this._tierDownAt = now;
      if (now - this._tierDownAt < o.tierHoldMs) tier = this.depthTier;
      else this._tierDownAt = null;
    } else {
      this._tierDownAt = null;
    }
    this.depthTier = tier;

    // 分位数估计（p85 覆盖典型节奏、lag95 覆盖典型迟到）+ 固定余量，再夹进**迟滞后**档位的深度范围
    const base = p85 + lag95 + o.jitterMarginMs;
    const target =
      tier === "heavy"
        ? Math.min(Math.max(base, o.depthHeavyMinMs), o.depthHeavyMaxMs)
        : tier === "jitter"
          ? Math.min(Math.max(base, o.depthJitterMinMs), o.depthJitterMaxMs)
          : Math.min(Math.max(base, o.depthMinMs), o.depthStableMaxMs);
    // 逃逸：输入本身很慢（如稳定 5 fps，p85=200 ms）时，分档上限会系统性盖不住真实需要，
    // 宁可突破档位上限（不超过剧烈档上限），也不能让缓冲长期饿着——正常局域网不会触发。
    if (tier !== "heavy" && base > target) return Math.min(base, o.depthHeavyMaxMs);
    return target;
  }

  /**
   * 取 at 时刻应该显示的参数（内部复用对象）。
   * @param {number} [at]
   * @returns {object|null} 没有样本时返回 null
   */
  sample(at = nowMs()) {
    const count = this.samples.length;
    if (!count) return null;
    const o = this.options;
    const now = isFiniteNumber(at) ? at : this._lastAt;

    // ---- 起播预缓冲（jitterBufferV1）----
    // 缓冲深度直接定到目标（此时还没播过任何东西，跳变不算倒带）；
    // 内容攒够一个深度（或超时兜底，防止单帧流永远不播）才开始推进播放时间轴。
    // 预缓冲期间保持**首帧**：恢复播放时从它接着播，绝不「冻结在最后一帧」或往回跳。
    if (this.jitter && !this.primed) {
      this.delayMs = this._targetDelayJitter(now);
      this._delayAt = now;
      const first0 = this.samples[0];
      const last0 = this.samples[count - 1];
      const enough = last0.t - first0.t >= this.delayMs;
      const timedOut = this._primeStartAt != null && now - this._primeStartAt >= o.primeTimeoutMs;
      if (enough || timedOut) {
        this.primed = true;
      } else {
        return copyParams(first0.params, this.out);
      }
    }

    // 延迟缓变：快升慢降 + 限速，保证 sampleAt 单调递增（不会「倒带」）
    const target = this.jitter ? this._targetDelayJitter(now) : this.targetDelayMs();
    const attackMs = this.jitter ? o.attackMsJitter : o.attackMs;
    const releaseMs = this.jitter ? o.releaseMsJitter : o.releaseMs;
    const riseRate = this.jitter ? o.maxRiseRateJitter : o.maxRiseRate;
    const fallRate = this.jitter ? o.maxFallRateJitter : o.maxFallRate;
    if (this._delayAt == null) this._delayAt = now;
    const elapsed = Math.max(0, Math.min(now - this._delayAt, 100));
    this._delayAt = Math.max(this._delayAt, now);
    if (elapsed > 0) {
      if (target <= 0 && this.delayMs <= 0.5) {
        this.delayMs = 0;
      } else {
        const rising = target > this.delayMs;
        const tau = rising ? attackMs : releaseMs;
        let next = this.delayMs + (target - this.delayMs) * (1 - Math.exp(-elapsed / tau));
        const maxDelta = elapsed * (rising ? riseRate : fallRate);
        next = Math.max(this.delayMs - maxDelta, Math.min(this.delayMs + maxDelta, next));
        this.delayMs = Math.max(0, Math.min(next, this.jitter ? o.depthHeavyMaxMs : o.maxDelayMs));
        if (target <= 0 && this.delayMs < 0.5) this.delayMs = 0;
      }
    } else if (target <= 0 && this.delayMs <= 0.5) {
      this.delayMs = 0;
    }

    // 播放时刻单调保护：无论延迟怎么变，sampleAt 只前进、绝不倒退（不会倒带 / 跳帧回放）
    let sampleAt = now - this.delayMs;
    if (sampleAt < this._lastSampleAt) sampleAt = this._lastSampleAt;
    this._lastSampleAt = sampleAt;

    const first = this.samples[0];
    const last = this.samples[count - 1];
    if (count === 1 || sampleAt <= first.t) {
      this._starved = false;
      return copyParams(first.params, this.out);
    }

    if (sampleAt >= last.t) {
      // 缓冲见底：最多外推 extrapolateMs；延迟为 0 时直接保持最新帧
      if (this.delayMs > 0.5) {
        this.starvedFrames += 1;
        if (!this._starved) {
          this._starved = true;
          this.starvedEpisodes += 1;
          // 见底是「当前深度不够」的直接证据：记入 2 s 窗口，参与分档（见 _targetDelayJitter）
          this._starveAt.push(now);
          this._pruneWindow(this._starveAt, now, o.starveWindowMs);
        }
        const prev = this.samples[count - 2];
        if (count >= 2 && prev) {
          const span = last.t - prev.t;
          if (span > 0) {
            const ahead = Math.min(sampleAt - last.t, o.extrapolateMs);
            const k = Math.min(ahead / span, o.maxExtrapolateRatio);
            return lerpParams(prev.params, last.params, 1 + k, this.out);
          }
        }
      } else {
        this._starved = false;
      }
      return copyParams(last.params, this.out);
    }

    this._starved = false;
    let a = first;
    let b = last;
    for (let i = count - 1; i > 0; i--) {
      if (this.samples[i - 1].t <= sampleAt) {
        a = this.samples[i - 1];
        b = this.samples[i];
        break;
      }
    }
    const span = b.t - a.t;
    const u = span > 0 ? (sampleAt - a.t) / span : 1;
    return lerpParams(a.params, b.params, u, this.out);
  }
}
