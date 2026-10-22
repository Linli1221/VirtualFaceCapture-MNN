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
 * 边界行为：
 *   - 乱序样本直接丢弃（seq 丢旧帧已在上层做过，这里只做最后一道保险）；
 *   - 断流时最多外推 extrapolateMs（默认 40 ms），不会让模型飞出去；
 *   - 断流超过 maxIntervalMs 清空样本、重新测节奏。
 *
 * `sample()` 返回的是内部复用对象（用完即弃），调用方不要保存引用。
 */

import { PARAM_IDS } from "./mapping.js";

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
});

function nowMs() {
  return typeof performance !== "undefined" && typeof performance.now === "function"
    ? performance.now()
    : Date.now();
}

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
  }

  get count() {
    return this.samples.length;
  }

  /** 是否正在用插值缓冲（延迟 > 0.5 ms）。 */
  get interpolating() {
    return this.delayMs > 0.5;
  }

  /** @param {{keepTiming?:boolean}} [options] keepTiming = 保留已测出的节奏与时钟偏移。 */
  reset(options = {}) {
    this.samples.length = 0;
    this.delayMs = 0;
    this._delayAt = null;
    this._lags.length = 0;
    this.starvedFrames = 0;
    this.starvedEpisodes = 0;
    this._starved = false;
    if (!options.keepTiming) {
      this._intervals.length = 0;
      this.inputIntervalMs = 0;
      this.inputJitterMs = 0;
      this.transitJitterMs = 0;
      this._stampBase = null;
      this._baseWindow.length = 0;
      this._lastStamp = null;
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
  }

  /**
   * 记录一帧输入参数。
   * @param {object} params 形如 { ParamAngleX: number, ... }
   * @param {number} [at] 到达时间（performance.now() 口径，ms）
   * @param {number} [stamp] 手机采集时间戳（track.t，手机本地毫秒）；缺省则按到达时间排队
   * @returns {boolean} 是否入队（乱序 / 参数缺失返回 false）
   */
  push(params, at = nowMs(), stamp = null) {
    if (!params || typeof params !== "object") return false;
    const o = this.options;
    const atMs = isFiniteNumber(at) ? at : this.samples.length ? this._lastAt : 0;
    const stampMs = isFiniteNumber(stamp) ? Number(stamp) : null;

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
      }
    }

    if (stampMs != null) {
      // 有符号：负值表示媒体时间跑到了到达时间后面（多出来的延迟由 offset 吃掉）
      this._lags.push(atMs - t);
      if (this._lags.length > o.lagWindow) this._lags.shift();
    }

    this._updateJitterStats();

    this.samples.push({ t, params });
    if (this.samples.length > o.maxSamples) this.samples.shift();
    this._lastAt = t;
    this._lastStamp = stampMs;
    return true;
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
    const o = this.options;
    if (!this._intervals.length) return 0;
    const high = percentile(this._intervals, o.intervalQuantile);
    if (!(high > o.renderIntervalMs * o.slowInputRatio)) return 0;
    // 只有「帧比时间轴晚到」才需要额外缓冲；早到由偏移吸收，不加延迟
    const lag = this._lags.length ? Math.max(0, percentile(this._lags, o.lagQuantile)) : 0;
    return Math.min(high + lag + o.jitterMarginMs, o.maxDelayMs);
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

    // 延迟缓变：快升慢降 + 限速，保证 sampleAt 单调递增（不会「倒带」）
    const target = this.targetDelayMs();
    if (this._delayAt == null) this._delayAt = now;
    const elapsed = Math.max(0, Math.min(now - this._delayAt, 100));
    this._delayAt = Math.max(this._delayAt, now);
    if (elapsed > 0) {
      if (target <= 0 && this.delayMs <= 0.5) {
        this.delayMs = 0;
      } else {
        const rising = target > this.delayMs;
        const tau = rising ? o.attackMs : o.releaseMs;
        let next = this.delayMs + (target - this.delayMs) * (1 - Math.exp(-elapsed / tau));
        const maxDelta = elapsed * (rising ? o.maxRiseRate : o.maxFallRate);
        next = Math.max(this.delayMs - maxDelta, Math.min(this.delayMs + maxDelta, next));
        this.delayMs = Math.max(0, Math.min(next, o.maxDelayMs));
        if (target <= 0 && this.delayMs < 0.5) this.delayMs = 0;
      }
    } else if (target <= 0 && this.delayMs <= 0.5) {
      this.delayMs = 0;
    }

    const first = this.samples[0];
    const last = this.samples[count - 1];
    const sampleAt = now - this.delayMs;

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
