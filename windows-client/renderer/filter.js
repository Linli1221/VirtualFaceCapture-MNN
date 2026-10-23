/**
 * filter.js — PC 端「最终表现层滤波」（任务 M4：统一到 PC 的观感滤波层）
 *
 * 定位（协议分工）：
 *   手机端保留  landmark EMA（tau=0.12）+ 头姿 Time-EMA（tau=0.3）+ FaceCapturePipeline
 *              + BlinkCurveProcessor —— 这些**都不在本层重复**，本层不碰手机侧、不碰模型层；
 *   PC 端负责   最终观感滤波（本文件）+ 速度自适应 + 角色风格化（按角色 profile 保存参数）。
 *
 * 速度自适应低通（一阶，τ 按动作速度插值）：
 *   - 慢动作 → τ 走 slowTauMs（强滤波，压掉慢速转头的可见抖动）；
 *   - 快动作 → τ 走 fastTauMs（弱滤波，快速甩头直接跟上，不发肉）；
 *   - 「快升慢降」作用在**速度估计**上：速度上升按 attackMs 快速跟上（马上弱滤波，避免滞后），
 *     速度回落按 releaseMs 慢慢松开（逐渐恢复强滤波，避免一停就抖）。
 *     同一个动作里不会在强 / 弱滤波之间来回横跳，抖动与滞后不会同时出现。
 *
 * 分组（头/脸与上半身**分开配置**）：
 *   - head：ParamAngleX/Y/Z、眼球、眼、嘴 —— 更跟手（slowTau 小、fastTau 更小）；
 *   - body：ParamBodyAngleX 及上半身骨骼参数 —— 更稳（slowTau 大）。
 *
 * 总开关（FILTER_DEFAULTS.enabled，默认 **false**）：
 *   关闭时 live2d.js 完全走现有滤波参数（时间轴插值 + 原每帧缓动 + 上半身 160ms 低通），
 *   与本文件无关、数值行为与旧版逐位一致；打开后才用本层替换（不是叠加）那两段低通，
 *   避免「多层强滤波叠一起把动作做肉」。
 *
 * 纯函数模块：不 import 任何 DOM / pixi / cubism 代码，Node 可直接
 *   node -e "import('./renderer/filter.js').then(m => console.log(m.sanitizeFilterProfile(null)))"
 * 做数值单测。
 */
import { BODY_PARAM_IDS, NORMALIZER_KNOB_DEFAULTS, PARAM_IDS } from "./mapping.js";

/** 归一化旋钮默认值来自 mapping.js（与 AdaptiveAngleNormalizer 历史行为逐位一致），这里再导出方便 profile 使用。 */
export { NORMALIZER_KNOB_DEFAULTS };

/** profile 持久化用的 localStorage key（由 app.js 读写，格式 { 角色名: profile }）。 */
export const FILTER_PROFILE_STORAGE_KEY = "vfc.filterProfiles";

/** 慢速阈值允许范围（ms）：slowTauMs / fastTauMs 都在这个区间里 clamp。 */
const TAU_MIN_MS = 0;
const TAU_MAX_MS = 600;

/** 速度微分的最小时间基准（ms）：目标两次变化挨得极近时防止除出无穷大速度。 */
const SPEED_MIN_INTERVAL_MS = 2;

/**
 * 每个参数的「满量程」参考跨度（参数单位）：速度按「每秒走过多少个满量程」归一化，
 * 这样头姿（±30）与表情（0..1）可以用同一组速度阈值。
 */
export const PARAM_SPEED_SPANS = Object.freeze({
  ParamEyeLOpen: 1,
  ParamEyeROpen: 1,
  ParamMouthOpenY: 1,
  ParamMouthForm: 2,
  ParamEyeBallX: 2,
  ParamEyeBallY: 2,
  ParamAngleX: 30,
  ParamAngleY: 30,
  ParamAngleZ: 30,
  ParamBodyAngleX: 30,
  ParamBodyAngleY: 30,
  ParamBodyAngleZ: 30,
  ParamArmLA: 30,
  ParamArmRA: 30,
  ParamArmLB: 30,
  ParamArmRB: 30,
  ParamShoulderL: 30,
  ParamShoulderR: 30,
  ParamHandL: 30,
  ParamHandR: 30,
  ParamHandAngleL: 30,
  ParamHandAngleR: 30,
});

/** 参数满量程（缺省按 ±30 的契约单位处理）。 */
export function paramSpan(id) {
  const span = PARAM_SPEED_SPANS[id];
  return typeof span === "number" && Number.isFinite(span) && span > 0 ? span : 30;
}

/**
 * 参数 → 滤波分组：上半身骨骼参数（含 ParamBodyAngleX）归 body，其余（头姿 / 表情）归 head。
 * 头/脸要更跟手，上半身要更稳（任务 M4 实施要求 5）。
 */
export function filterGroupOf(id) {
  return BODY_PARAM_IDS.includes(id) ? "body" : "head";
}

/**
 * 一组滤波参数（head / body 各一份，分开配置）。
 *
 *   slowTauMs   慢动作时的低通时间常数（强滤波）
 *   fastTauMs   快动作时的低通时间常数（弱滤波，接近直通）
 *   speedLow    「慢」速度上限（满量程/秒）：低于它按 slowTau
 *   speedHigh   「快」速度下限（满量程/秒）：高于它按 fastTau
 *   attackMs    速度上升（慢→快）的跟随时间常数：小 = 快升，马上弱滤波跟手
 *   releaseMs   速度回落（快→慢）的松开时间常数：大 = 慢降，逐渐恢复强滤波防抖
 */
export const FILTER_GROUP_DEFAULTS = Object.freeze({
  /** 头/脸：更跟手（慢速也只做轻平滑，快速接近直通）。 */
  head: Object.freeze({
    slowTauMs: 90,
    fastTauMs: 6,
    speedLow: 0.35,
    speedHigh: 2.2,
    attackMs: 60,
    releaseMs: 260,
  }),
  /** 上半身：更稳（Pose 帧率 15~30 fps，慢速要压得住）。 */
  body: Object.freeze({
    slowTauMs: 260,
    fastTauMs: 30,
    speedLow: 0.25,
    speedHigh: 1.6,
    attackMs: 100,
    releaseMs: 420,
  }),
});

/**
 * AdaptiveAngleNormalizer 的可调旋钮默认值见 mapping.js 的 NORMALIZER_KNOB_DEFAULTS
 * （向外扩张 0.1；向内收缩 = alpha 0.05 前 100 帧 / 0.01 之后 × 0.1 —— 「向外快、向内慢」）。
 * 只有总开关打开、且角色 profile 显式覆盖时才会偏离默认；默认与旧行为逐位一致。
 */

/**
 * 最终滤波 profile 的完整默认值（新装 / 老客户端升级后第一次打开都是这份）。
 * enabled=false：总开关关闭，行为与现状完全一致（任务 M4 验收清单）。
 */
export const FILTER_DEFAULTS = Object.freeze({
  enabled: false,
  head: FILTER_GROUP_DEFAULTS.head,
  body: FILTER_GROUP_DEFAULTS.body,
  normalizer: NORMALIZER_KNOB_DEFAULTS,
});

// ---------------------------------------------------------------- profile 序列化

function finiteOr(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

function clampNumber(value, lo, hi, fallback) {
  return Math.min(hi, Math.max(lo, finiteOr(value, fallback)));
}

function sanitizeGroup(raw, defaults) {
  const input = raw && typeof raw === "object" ? raw : {};
  return {
    slowTauMs: clampNumber(input.slowTauMs, TAU_MIN_MS, TAU_MAX_MS, defaults.slowTauMs),
    fastTauMs: clampNumber(input.fastTauMs, TAU_MIN_MS, TAU_MAX_MS, defaults.fastTauMs),
    speedLow: clampNumber(input.speedLow, 0, 100, defaults.speedLow),
    speedHigh: clampNumber(input.speedHigh, 0, 100, defaults.speedHigh),
    attackMs: clampNumber(input.attackMs, 1, 2000, defaults.attackMs),
    releaseMs: clampNumber(input.releaseMs, 1, 5000, defaults.releaseMs),
  };
}

function sanitizeKnobs(raw) {
  const input = raw && typeof raw === "object" ? raw : {};
  return {
    adaptationRate: clampNumber(input.adaptationRate, 0.001, 1, NORMALIZER_KNOB_DEFAULTS.adaptationRate),
    contractAlphaEarly: clampNumber(input.contractAlphaEarly, 0, 1, NORMALIZER_KNOB_DEFAULTS.contractAlphaEarly),
    contractAlphaLate: clampNumber(input.contractAlphaLate, 0, 1, NORMALIZER_KNOB_DEFAULTS.contractAlphaLate),
    contractScale: clampNumber(input.contractScale, 0, 1, NORMALIZER_KNOB_DEFAULTS.contractScale),
  };
}

/**
 * 把任意来源（localStorage / __vfc / UI）的 profile 规整成完整合法的 profile。
 * 缺失字段回落默认值；未知字段丢弃；坏值不抛异常（渲染路径不能崩）。
 * @param {object|null|undefined} raw
 * @returns {{enabled:boolean, head:object, body:object, normalizer:object}}
 */
export function sanitizeFilterProfile(raw) {
  const input = raw && typeof raw === "object" ? raw : {};
  return {
    enabled: Boolean(input.enabled),
    head: sanitizeGroup(input.head, FILTER_GROUP_DEFAULTS.head),
    body: sanitizeGroup(input.body, FILTER_GROUP_DEFAULTS.body),
    normalizer: sanitizeKnobs(input.normalizer),
  };
}

/** profile 是否与默认值一致（UI 展示「已恢复默认」用）。 */
export function isDefaultFilterProfile(profile) {
  return JSON.stringify(sanitizeFilterProfile(profile)) === JSON.stringify(sanitizeFilterProfile(null));
}

/** 解析 localStorage 里的 { 角色名: profile } 表；坏 JSON / 坏结构返回空表。 */
export function decodeFilterProfiles(raw) {
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    const out = {};
    for (const [name, profile] of Object.entries(parsed)) {
      if (typeof name === "string" && name && profile && typeof profile === "object") {
        out[name] = sanitizeFilterProfile(profile);
      }
    }
    return out;
  } catch {
    return {};
  }
}

/** 序列化 { 角色名: profile } 表（存 localStorage）。 */
export function encodeFilterProfiles(map) {
  const out = {};
  if (map && typeof map === "object") {
    for (const [name, profile] of Object.entries(map)) {
      if (typeof name === "string" && name) out[name] = sanitizeFilterProfile(profile);
    }
  }
  return JSON.stringify(out);
}

// ---------------------------------------------------------------- 速度自适应低通

/** 单参数的一阶速度自适应低通（τ 按速度在 slowTau ↔ fastTau 之间插值）。 */
export class VelocityAdaptiveFilter {
  /**
   * @param {{slowTauMs:number, fastTauMs:number, speedLow:number, speedHigh:number, attackMs:number, releaseMs:number}} config
   * @param {number} [span] 速度归一化用的满量程跨度（参数单位），见 PARAM_SPEED_SPANS
   */
  constructor(config = FILTER_GROUP_DEFAULTS.head, span = 30) {
    this.config = config;
    this.span = span > 0 ? span : 30;
    this.value = 0;
    this.speedEma = 0;
    this.tauMs = config.slowTauMs;
    this._has = false;
    this._prevGoal = 0;
    this._sinceChangeMs = 0;
  }

  reset() {
    this.value = 0;
    this.speedEma = 0;
    this.tauMs = this.config.slowTauMs;
    this._has = false;
    this._prevGoal = 0;
    this._sinceChangeMs = 0;
  }

  /**
   * 推进一帧。
   * @param {number} goal 目标值（插值后的目标参数）
   * @param {number} dtMs 距上一帧的毫秒数
   * @param {number} [feedSpeed] 输入侧速度前馈（满量程/秒，见 ParamTimeline.speedOf）；0 = 只用自身微分
   * @returns {number} 滤波后的值
   */
  step(goal, dtMs, feedSpeed = 0) {
    const o = this.config;
    const dt = Math.min(Math.max(Number(dtMs) || 0, 1), 100);
    const g = Number(goal);
    if (!Number.isFinite(g)) return this._has ? this.value : 0;
    if (!this._has) {
      // 第一帧直接落位：重建连接 / 换模型时不从 0 缓动过来（不产生甩尾）
      this._has = true;
      this._prevGoal = g;
      this.value = g;
      this.speedEma = 0;
      this.tauMs = o.slowTauMs;
      this._sinceChangeMs = 0;
      return g;
    }

    // 归一化速度（满量程/秒）：微分按「目标两次变化之间的间隔」计算——
    // 目标在输入帧之间被 hold 住时（上半身 15~30 fps / 不插值），用渲染 dt 会把速度算成尖峰。
    this._sinceChangeMs += dt;
    let deriv = 0;
    if (g !== this._prevGoal) {
      const changeDt = Math.max(this._sinceChangeMs, SPEED_MIN_INTERVAL_MS) / 1000;
      deriv = Math.abs(g - this._prevGoal) / this.span / changeDt;
      this._prevGoal = g;
      this._sinceChangeMs = 0;
    }
    const feed = Math.max(0, finiteOr(feedSpeed, 0));
    const speed = Math.min(Math.max(deriv, feed), 200); // 上限防数值尖峰

    // 快升慢降：速度飙升马上弱滤波（跟手），速度回落慢慢恢复强滤波（防抖）
    const tau = speed > this.speedEma ? o.attackMs : o.releaseMs;
    this.speedEma += (speed - this.speedEma) * (1 - Math.exp(-dt / Math.max(1, tau)));

    // 速度 → 滤波强度：slowTau（强）↔ fastTau（弱），中间线性插值
    const spanR = Math.max(1e-6, o.speedHigh - o.speedLow);
    const norm = Math.min(1, Math.max(0, (this.speedEma - o.speedLow) / spanR));
    this.tauMs = o.slowTauMs + (o.fastTauMs - o.slowTauMs) * norm;

    const alpha = this.tauMs > 0 ? 1 - Math.exp(-dt / this.tauMs) : 1;
    this.value += (g - this.value) * alpha;
    // 足够接近就落位，避免浮点尾巴让参数永远「差一点」（与 live2d.js 的既有约定一致）
    if (Math.abs(g - this.value) < 0.0005) this.value = g;
    return this.value;
  }
}

/**
 * 参数滤波器组：head / body 两组分开配置，按参数 id 自动分组。
 * 总开关 enabled=false 时 step 直接返回目标值（供 live2d.js 之外的调用方安全共用）。
 */
export class ParamFilterBank {
  /** @param {Partial<{enabled:boolean, head:object, body:object}>} [profile] */
  constructor(profile = null) {
    this._filters = new Map();
    this.setProfile(profile);
  }

  /** 应用（或恢复默认）profile；参数组变化后所有滤波器重新落位。 */
  setProfile(profile = null) {
    this.profile = sanitizeFilterProfile(profile);
    this.enabled = Boolean(this.profile.enabled);
    this._filters.clear();
    return this.profile;
  }

  reset() {
    for (const filter of this._filters.values()) filter.reset();
    this._filters.clear();
  }

  get config() {
    return { enabled: this.profile.enabled, head: this.profile.head, body: this.profile.body };
  }

  /**
   * 滤波一个参数。enabled=false 时原样返回（不产生任何行为差异）。
   * @param {string} id 参数 id（分组按 filterGroupOf 自动判定）
   * @param {number} goal 目标值
   * @param {number} dtMs 距上一帧毫秒数
   * @param {number} [feedSpeed] 输入侧速度前馈（满量程/秒）
   */
  step(id, goal, dtMs, feedSpeed = 0) {
    return this.stepGroup(filterGroupOf(id), id, goal, dtMs, feedSpeed);
  }

  /**
   * 滤波任意一路状态（key 只区分滤波器实例，group 决定用哪组参数）。
   * 例：头部驱动与躯干驱动的 ParamBodyAngleX 是两路状态，用 "ParamBodyAngleX" / "torsoX" 两个 key。
   */
  stepGroup(group, key, goal, dtMs, feedSpeed = 0) {
    const g = Number(goal);
    if (!Number.isFinite(g)) return g;
    if (!this.enabled) return g;
    const groupName = group === "body" ? "body" : "head";
    const stateKey = `${groupName}:${key}`;
    let filter = this._filters.get(stateKey);
    if (!filter) {
      filter = new VelocityAdaptiveFilter(this.profile[groupName], paramSpan(key));
      this._filters.set(stateKey, filter);
    }
    return filter.step(g, dtMs, feedSpeed);
  }
}
