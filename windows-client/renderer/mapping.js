/**
 * mapping.js — ARKit-52 + 头部姿态 → Live2D 参数映射（协议 docs/tracking-protocol.md §8）
 *
 * 纯函数模块：**不 import 任何 DOM / pixi / cubism 代码**，Node 端可直接
 *   node -e "import('./renderer/mapping.js').then(m => console.log(m.mapTrack({bs:[],pose:[10,-20,5]})))"
 * 做数值单测。渲染层只负责把这里算出来的 { 参数名: 数值 } 写进 Cubism coreModel。
 *
 * 算法与旧版 Android 渲染器（Live2DAvatarAdapter + LAppModel::ModelParameterUpdate）逐行等价。
 */

/** ARKit-52 索引顺序（协议 §5.1），索引 0 = neutral，恒为 0。 */
export const BS_INDEX = Object.freeze({
  neutral: 0,
  browDownLeft: 1,
  browDownRight: 2,
  browInnerUp: 3,
  browOuterUpLeft: 4,
  browOuterUpRight: 5,
  cheekPuff: 6,
  cheekSquintLeft: 7,
  cheekSquintRight: 8,
  eyeBlinkLeft: 9,
  eyeBlinkRight: 10,
  eyeLookDownLeft: 11,
  eyeLookDownRight: 12,
  eyeLookInLeft: 13,
  eyeLookInRight: 14,
  eyeLookOutLeft: 15,
  eyeLookOutRight: 16,
  eyeLookUpLeft: 17,
  eyeLookUpRight: 18,
  eyeSquintLeft: 19,
  eyeSquintRight: 20,
  eyeWideLeft: 21,
  eyeWideRight: 22,
  jawForward: 23,
  jawLeft: 24,
  jawOpen: 25,
  jawRight: 26,
  mouthClose: 27,
  mouthDimpleLeft: 28,
  mouthDimpleRight: 29,
  mouthFrownLeft: 30,
  mouthFrownRight: 31,
  mouthFunnel: 32,
  mouthLeft: 33,
  mouthLowerDownLeft: 34,
  mouthLowerDownRight: 35,
  mouthPressLeft: 36,
  mouthPressRight: 37,
  mouthPucker: 38,
  mouthRight: 39,
  mouthRollLower: 40,
  mouthRollUpper: 41,
  mouthShrugLower: 42,
  mouthShrugUpper: 43,
  mouthSmileLeft: 44,
  mouthSmileRight: 45,
  mouthStretchLeft: 46,
  mouthStretchRight: 47,
  mouthUpperUpLeft: 48,
  mouthUpperUpRight: 49,
  noseSneerLeft: 50,
  noseSneerRight: 51,
});

/** 协议要求 bs 恰好 52 项（§5.1）。 */
export const BS_COUNT = 52;

/** 每帧写入 cubism 的参数 id 全集（协议 §8.1 + §8.2）。 */
export const PARAM_IDS = Object.freeze([
  "ParamEyeLOpen",
  "ParamEyeROpen",
  "ParamMouthOpenY",
  "ParamMouthForm",
  "ParamEyeBallX",
  "ParamEyeBallY",
  "ParamAngleX",
  "ParamAngleY",
  "ParamAngleZ",
  "ParamBodyAngleX",
]);

/** createMapper 默认系数（协议 §8.2）。rangeScale = 角色量程偏好，1 = 协议默认（行为不变）。 */
export const DEFAULT_OPTIONS = Object.freeze({
  invertPitch: false,
  invertYaw: false,
  invertRoll: false,
  angleScale: 30,
  bodyScale: 10,
  rangeScale: 1,
});

export function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v;
}

/**
 * AdaptiveAngleNormalizer 的旋钮默认值（任务 M4：可写进角色 profile）。
 *
 * 「向外快、向内慢」的数值基线：
 *   - 向外扩张系数 adaptationRate = 0.1；
 *   - 向内收缩 = contractAlpha(前 100 帧 0.05 / 之后 0.01) × contractScale(0.1)。
 * 默认值与历史行为**逐位一致**：不改这组数，行为就和旧版完全一样（M4 验收清单）。
 */
export const NORMALIZER_KNOB_DEFAULTS = Object.freeze({
  adaptationRate: 0.1,
  contractAlphaEarly: 0.05,
  contractAlphaLate: 0.01,
  contractScale: 0.1,
});

/**
 * 非对称自适应归一化器（协议 §8.2 伪码的直译）。
 *
 * 负向 / 正向各自维护一个「观测到的动作幅度」pctNeg / pctPos；量程随实际动作自适应，
 * 前 100 帧在「默认量程」与「观测量程」之间线性混合，越界直接截断到 [-1,0] / [0,1]。
 * 因此**新建的归一化器第一帧不会给出满量程**（pitch=+20 → 约 -15.14 而非 -30），这是预期行为。
 */
export class AdaptiveAngleNormalizer {
  /**
   * @param {number} defaultNegativeRange 默认负向满量程（度）
   * @param {number} defaultPositiveRange 默认正向满量程（度）
   * @param {number} [minRange=5] 最小量程（度），避免幅度过小时除零 / 放大噪声
   * @param {number} [adaptationRate=0.1] 向外扩展方向的跟随速率
   * @param {object} [knobs] 可选旋钮（M4 角色 profile）：{adaptationRate, contractAlphaEarly,
   *                        contractAlphaLate, contractScale}，缺省 = NORMALIZER_KNOB_DEFAULTS（旧行为）
   */
  constructor(defaultNegativeRange, defaultPositiveRange, minRange = 5, adaptationRate = 0.1, knobs = null) {
    this.defaultNeg = defaultNegativeRange;
    this.defaultPos = defaultPositiveRange;
    /** 未缩放的基准量程（角色 profile 的量程偏好只乘在基准上，1 = 与协议默认一致）。 */
    this.baseNeg = defaultNegativeRange;
    this.basePos = defaultPositiveRange;
    /** 当前量程偏好倍率（0.5~2，1 = 不缩放）。 */
    this.scale = 1;
    this.minRange = minRange;
    /** 向外扩展方向的跟随速率（「向外快」，默认 0.1）。 */
    this.rate = adaptationRate;
    this._defaultRate = adaptationRate;
    this._pctNeg = 0;
    this._pctPos = 0;
    this._samples = 0;
    // 收缩（「向内慢」）旋钮：默认 = alpha(前 100 帧 0.05 / 之后 0.01) × 0.1，与历史行为逐位一致
    this._contractAlphaEarly = NORMALIZER_KNOB_DEFAULTS.contractAlphaEarly;
    this._contractAlphaLate = NORMALIZER_KNOB_DEFAULTS.contractAlphaLate;
    this._contractScale = NORMALIZER_KNOB_DEFAULTS.contractScale;
    if (knobs) this.configure(knobs);
  }

  /**
   * 套用滤波 profile 里的归一化旋钮（M4：可按角色保存）。传 null / 缺省恢复默认。
   * 默认值 = 向外扩张 0.1、收缩 alpha 0.05(前 100 帧)/0.01(之后) × 0.1 —— 与旧行为逐位一致。
   */
  configure(knobs = null) {
    const k = knobs && typeof knobs === "object" ? knobs : {};
    const rate = Number(k.adaptationRate);
    this.rate = Number.isFinite(rate) && rate > 0 ? Math.min(1, rate) : this._defaultRate;
    const clamp01 = (value, fallback) => {
      const n = Number(value);
      return Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : fallback;
    };
    this._contractAlphaEarly = clamp01(k.contractAlphaEarly, NORMALIZER_KNOB_DEFAULTS.contractAlphaEarly);
    this._contractAlphaLate = clamp01(k.contractAlphaLate, NORMALIZER_KNOB_DEFAULTS.contractAlphaLate);
    this._contractScale = clamp01(k.contractScale, NORMALIZER_KNOB_DEFAULTS.contractScale);
    return this.knobs;
  }

  /** 当前旋钮快照（写回角色 profile / 调试用）。 */
  get knobs() {
    return {
      adaptationRate: this.rate,
      contractAlphaEarly: this._contractAlphaEarly,
      contractAlphaLate: this._contractAlphaLate,
      contractScale: this._contractScale,
    };
  }

  /**
   * 应用「量程偏好」（角色 profile 的 rangeScale）：整体缩放默认量程，
   * 不改变符号约定与自适应逻辑。scale=1 时与协议默认完全一致。
   */
  setScale(scale) {
    const s = Number(scale);
    const clamped = Number.isFinite(s) ? Math.max(0.5, Math.min(2, s)) : 1;
    this.scale = clamped;
    this.defaultNeg = this.baseNeg * clamped;
    this.defaultPos = this.basePos * clamped;
    return clamped;
  }

  /** 已观测帧数（只读）。 */
  get samples() {
    return this._samples;
  }

  /** 当前负向观测量程（未 clamp，调试用）。 */
  get pctNeg() {
    return this._pctNeg;
  }

  /** 当前正向观测量程（未 clamp，调试用）。 */
  get pctPos() {
    return this._pctPos;
  }

  /** 归一化一次（内部会先 update 自适应幅度）。 */
  normalize(v) {
    this.update(v);
    return v < 0 ? clamp(v / this.effectiveNeg(), -1, 0) : clamp(v / this.effectivePos(), 0, 1);
  }

  /** 只更新自适应幅度，不返回值（供需要自适应的其它用途）。 */
  update(v) {
    this._samples++;
    // 收缩 alpha = 0.05(前 100 帧) / 0.01(之后)，再乘 contractScale(0.1) —— 默认与历史行为逐位一致
    const alpha = this._samples < 100 ? this._contractAlphaEarly : this._contractAlphaLate;
    if (v < 0) {
      const a = -v;
      this._pctNeg += a > this._pctNeg ? (a - this._pctNeg) * this.rate : (a - this._pctNeg) * alpha * this._contractScale;
    } else {
      this._pctPos += v > this._pctPos ? (v - this._pctPos) * this.rate : (v - this._pctPos) * alpha * this._contractScale;
    }
  }

  effectiveNeg() {
    const blend = Math.min(1, this._samples / 100);
    return Math.max(this.defaultNeg * (1 - blend) + this._pctNeg * blend, this.minRange);
  }

  effectivePos() {
    const blend = Math.min(1, this._samples / 100);
    return Math.max(this.defaultPos * (1 - blend) + this._pctPos * blend, this.minRange);
  }

  /** 重新落座 / 换人 / calib active:true 时调用，清掉旧幅度记忆。 */
  reset() {
    this._pctNeg = 0;
    this._pctPos = 0;
    this._samples = 0;
  }
}

/** 协议 §8.2：低头 ~20°、抬头 ~40°。rangeScale = 角色量程偏好（可选，1 = 默认）。 */
export function createPitchNormalizer(rangeScale = 1, knobs = null) {
  return withScale(new AdaptiveAngleNormalizer(20, 40, 10, 0.1, knobs), rangeScale);
}
/** 协议 §8.2：左右各 45°。 */
export function createYawNormalizer(rangeScale = 1, knobs = null) {
  return withScale(new AdaptiveAngleNormalizer(45, 45, 15, 0.1, knobs), rangeScale);
}
/** 协议 §8.2：左右各 30°。 */
export function createRollNormalizer(rangeScale = 1, knobs = null) {
  return withScale(new AdaptiveAngleNormalizer(30, 30, 10, 0.1, knobs), rangeScale);
}

/** 工厂内部：建好归一化器后套用量程偏好（scale=1 时与协议默认完全一致）。 */
function withScale(normalizer, rangeScale) {
  normalizer.setScale(rangeScale);
  return normalizer;
}

/**
 * 三个归一化器（pitch / yaw / roll）。
 * @param {number} [rangeScale=1] 角色量程偏好（0.5~2，1 = 协议默认量程，行为不变）
 * @param {object} [knobs] 归一化旋钮（M4 角色 profile；缺省 = 旧行为）
 * @returns {{pitch:AdaptiveAngleNormalizer,yaw:AdaptiveAngleNormalizer,roll:AdaptiveAngleNormalizer}}
 */
export function createNormalizers(rangeScale = 1, knobs = null) {
  return {
    pitch: createPitchNormalizer(rangeScale, knobs),
    yaw: createYawNormalizer(rangeScale, knobs),
    roll: createRollNormalizer(rangeScale, knobs),
  };
}

/** 把 bs 补齐成 52 项有限数字，坏数据不抛异常（渲染路径不能崩）。 */
export function normalizeBs(bs) {
  const out = new Array(BS_COUNT).fill(0);
  if (Array.isArray(bs) || ArrayBuffer.isView(bs)) {
    const n = Math.min(BS_COUNT, bs.length);
    for (let i = 0; i < n; i++) {
      const v = Number(bs[i]);
      out[i] = Number.isFinite(v) ? v : 0;
    }
  }
  return out;
}

/**
 * 协议 §8.1：直通参数（每帧绝对赋值）。
 * @param {ArrayLike<number>} bs 52 项 ARKit 权重
 * @returns {{eyeLOpen:number,eyeROpen:number,mouthOpen:number,mouthForm:number,eyeBallX:number,eyeBallY:number}}
 */
export function mapBshapes(bs) {
  const b = normalizeBs(bs);
  const eyeLOpen = 1 - b[BS_INDEX.eyeBlinkLeft];
  const eyeROpen = 1 - b[BS_INDEX.eyeBlinkRight];

  const mouthOpen = b[BS_INDEX.jawOpen];
  const mouthForm =
    (b[BS_INDEX.mouthSmileLeft] + b[BS_INDEX.mouthSmileRight]) / 2 -
    (b[BS_INDEX.mouthFrownLeft] + b[BS_INDEX.mouthFrownRight]) / 2;

  const eyeBallX =
    (b[BS_INDEX.eyeLookOutLeft] - b[BS_INDEX.eyeLookInLeft] +
      (b[BS_INDEX.eyeLookInRight] - b[BS_INDEX.eyeLookOutRight])) / 2;
  const eyeBallY =
    (b[BS_INDEX.eyeLookUpLeft] - b[BS_INDEX.eyeLookDownLeft] +
      (b[BS_INDEX.eyeLookUpRight] - b[BS_INDEX.eyeLookDownRight])) / 2;

  return {
    eyeLOpen,
    eyeROpen,
    mouthOpen,
    mouthForm: clamp(mouthForm, -1, 1),
    eyeBallX: clamp(eyeBallX, -1, 1),
    eyeBallY: clamp(eyeBallY, -1, 1),
  };
}

/**
 * 协议 §8.2：归一化后乘系数的头部姿态。
 * @param {ArrayLike<number>} pose [pitch, yaw, roll]，单位度
 * @param {{pitch:AdaptiveAngleNormalizer,yaw:AdaptiveAngleNormalizer,roll:AdaptiveAngleNormalizer}} norms
 * @param {{angleScale?:number,bodyScale?:number,invertPitch?:boolean,invertYaw?:boolean,invertRoll?:boolean}} [opts]
 * @returns {{params:{ParamAngleX:number,ParamAngleY:number,ParamAngleZ:number,ParamBodyAngleX:number},normalized:{pitch:number,yaw:number,roll:number},raw:{pitch:number,yaw:number,roll:number}}}
 */
export function mapPose(pose, norms, opts = {}) {
  const o = { ...DEFAULT_OPTIONS, ...opts };
  const rawPitch = (Number(pose?.[0]) || 0) * (o.invertPitch ? -1 : 1);
  const rawYaw = (Number(pose?.[1]) || 0) * (o.invertYaw ? -1 : 1);
  const rawRoll = (Number(pose?.[2]) || 0) * (o.invertRoll ? -1 : 1);

  const p = norms.pitch.normalize(rawPitch);
  const y = norms.yaw.normalize(rawYaw);
  const r = norms.roll.normalize(rawRoll);

  return {
    params: {
      ParamAngleX: -y * o.angleScale,
      ParamAngleY: -p * o.angleScale,
      ParamAngleZ: r * o.angleScale,
      ParamBodyAngleX: -y * o.bodyScale,
    },
    normalized: { pitch: p, yaw: y, roll: r },
    raw: { pitch: rawPitch, yaw: rawYaw, roll: rawRoll },
  };
}

function emptyResult() {
  return {
    params: Object.fromEntries(PARAM_IDS.map((id) => [id, 0])),
    normalized: { pitch: 0, yaw: 0, roll: 0 },
    raw: { pitch: 0, yaw: 0, roll: 0 },
  };
}

/**
 * 有状态的每帧映射器（渲染主路径用它）。
 *
 * apply 返回值的结构与 mapper.result 相同，可 JSON.stringify（无 Map / 循环引用）：
 *   { params: { ParamEyeLOpen, ..., ParamBodyAngleX },
 *     normalized: { pitch, yaw, roll },
 *     raw: { pitch, yaw, roll } }
 *
 * @param {{invertPitch?:boolean,invertYaw?:boolean,invertRoll?:boolean,angleScale?:number,bodyScale?:number,normalizer?:object}} [opts]
 *   normalizer = AdaptiveAngleNormalizer 旋钮（M4 角色 profile，缺省 = 旧行为）
 */
export function createMapper(opts = {}) {
  const o = { ...DEFAULT_OPTIONS, ...opts };
  const norms = createNormalizers(o.rangeScale, o.normalizer);

  const mapper = {
    options: o,
    result: emptyResult(),
    /** 三个归一化器（单测 / 调试可读 samples、effectiveNeg 等）。 */
    normalizers: norms,
    get pitch() {
      return norms.pitch;
    },
    get yaw() {
      return norms.yaw;
    },
    get roll() {
      return norms.roll;
    },
    reset() {
      norms.pitch.reset();
      norms.yaw.reset();
      norms.roll.reset();
    },
    /**
     * 切换角色时套用该角色的量程偏好（profile.rangeScale，0.5~2，1 = 默认）。
     * 只缩放默认量程，不动符号约定与自适应逻辑；随后 reset() 归一化窗口。
     */
    setRangeScale(scale) {
      o.rangeScale = norms.pitch.setScale(scale);
      norms.yaw.setScale(o.rangeScale);
      norms.roll.setScale(o.rangeScale);
      mapper.reset();
      return o.rangeScale;
    },
    /**
     * 套用滤波 profile 的归一化旋钮（M4）：传 null 恢复默认（旧行为）。
     * 只改「向外快、向内慢」的速率，不动量程与符号约定。
     */
    configureNormalizers(knobs = null) {
      o.normalizer = norms.pitch.configure(knobs);
      norms.yaw.configure(knobs);
      norms.roll.configure(knobs);
      return o.normalizer;
    },
    /**
     * @param {ArrayLike<number>} bs 52 项权重
     * @param {ArrayLike<number>} pose [pitch, yaw, roll] 度
     */
    apply(bs, pose) {
      // 兼容 apply(track) 形式
      if (bs && typeof bs === "object" && !Array.isArray(bs) && !ArrayBuffer.isView(bs) && pose === undefined) {
        pose = bs.pose;
        bs = bs.bs;
      }
      const f = mapBshapes(bs ?? []);
      const a = mapPose(pose ?? [0, 0, 0], norms, o);
      mapper.result = {
        params: {
          ParamEyeLOpen: f.eyeLOpen,
          ParamEyeROpen: f.eyeROpen,
          ParamMouthOpenY: f.mouthOpen,
          ParamMouthForm: f.mouthForm,
          ParamEyeBallX: f.eyeBallX,
          ParamEyeBallY: f.eyeBallY,
          ParamAngleX: a.params.ParamAngleX,
          ParamAngleY: a.params.ParamAngleY,
          ParamAngleZ: a.params.ParamAngleZ,
          ParamBodyAngleX: a.params.ParamBodyAngleX,
        },
        normalized: a.normalized,
        raw: a.raw,
      };
      return mapper.result;
    },
  };

  return mapper;
}

/**
 * 一次性映射（用一组传入的归一化器；会推进它们的状态）。
 * 便于单测直接传入自建的 norms。
 * @param {{bs?:ArrayLike<number>,pose?:ArrayLike<number>}|ArrayLike<number>} track
 * @param {ReturnType<createNormalizers>} [norms]
 */
export function mapTrackToParams(track, norms) {
  const n = norms ?? createNormalizers();
  const bs = Array.isArray(track) || ArrayBuffer.isView(track) ? track : track?.bs ?? [];
  const pose = Array.isArray(track) || ArrayBuffer.isView(track) ? [0, 0, 0] : track?.pose ?? [0, 0, 0];
  const f = mapBshapes(bs);
  const a = mapPose(pose, n);
  return {
    ParamEyeLOpen: f.eyeLOpen,
    ParamEyeROpen: f.eyeROpen,
    ParamMouthOpenY: f.mouthOpen,
    ParamMouthForm: f.mouthForm,
    ParamEyeBallX: f.eyeBallX,
    ParamEyeBallY: f.eyeBallY,
    ParamAngleX: a.params.ParamAngleX,
    ParamAngleY: a.params.ParamAngleY,
    ParamAngleZ: a.params.ParamAngleZ,
    ParamBodyAngleX: a.params.ParamBodyAngleX,
  };
}

/** 无状态便捷映射（每次新建归一化器，适合单测 / 一次性采样）。 */
export function mapTrack(track) {
  return mapTrackToParams(track);
}

/** 兼容包装：类形式的映射器，apply(track) 直接返回平铺参数表。 */
export class TrackMapper {
  constructor(opts) {
    this.mapper = createMapper(opts);
  }

  get result() {
    return this.mapper.result;
  }

  reset() {
    this.mapper.reset();
  }

  apply(track) {
    return this.mapper.apply(track).params;
  }
}

// ---------------------------------------------------------------- 上半身关键点（协议 §4.2 / §5.3）

/**
 * 上半身 13 个关键点在 `track.ub` 里的**协议顺序下标**（docs/tracking-protocol.md §5.3）。
 *
 * 必须与 Android 端 `CommonData/UpperBodyLandmarkIndex.ORDER` 逐项一致；这里的数字是
 * 「协议顺序」（0..12），**不是** MediaPipe Pose 的原始 33 点索引。改顺序 / 改数量都属于
 * 协议不兼容变更，必须同步 Android 端、协议文档与这张表。
 */
export const BODY_LANDMARK_ORDER = Object.freeze({
  nose: 0,
  eye_left: 1,
  eye_right: 2,
  ear_left: 3,
  ear_right: 4,
  shoulder_left: 5,
  shoulder_right: 6,
  elbow_left: 7,
  elbow_right: 8,
  wrist_left: 9,
  wrist_right: 10,
  hip_left: 11,
  hip_right: 12,
});

/** 协议里的上半身点数（13）。 */
export const BODY_COUNT = 13;

/** `track.ub` 的扁平长度：13 点 × (x, y, z, visibility)。 */
export const BODY_FLOATS = BODY_COUNT * 4;

/**
 * 15 条骨架边（协议顺序下标），与 Android 端 `UpperBodySkeleton.BONES` 逐项一致。
 * 手机预览叠加层与 PC 调试骨架共用这张表，两端看起来才一样。
 */
export const BODY_BONES = Object.freeze([
  Object.freeze([BODY_LANDMARK_ORDER.nose, BODY_LANDMARK_ORDER.eye_left]),
  Object.freeze([BODY_LANDMARK_ORDER.nose, BODY_LANDMARK_ORDER.eye_right]),
  Object.freeze([BODY_LANDMARK_ORDER.eye_left, BODY_LANDMARK_ORDER.ear_left]),
  Object.freeze([BODY_LANDMARK_ORDER.eye_right, BODY_LANDMARK_ORDER.ear_right]),
  Object.freeze([BODY_LANDMARK_ORDER.eye_left, BODY_LANDMARK_ORDER.eye_right]),
  Object.freeze([BODY_LANDMARK_ORDER.nose, BODY_LANDMARK_ORDER.shoulder_left]),
  Object.freeze([BODY_LANDMARK_ORDER.nose, BODY_LANDMARK_ORDER.shoulder_right]),
  Object.freeze([BODY_LANDMARK_ORDER.shoulder_left, BODY_LANDMARK_ORDER.shoulder_right]),
  Object.freeze([BODY_LANDMARK_ORDER.shoulder_left, BODY_LANDMARK_ORDER.elbow_left]),
  Object.freeze([BODY_LANDMARK_ORDER.elbow_left, BODY_LANDMARK_ORDER.wrist_left]),
  Object.freeze([BODY_LANDMARK_ORDER.shoulder_right, BODY_LANDMARK_ORDER.elbow_right]),
  Object.freeze([BODY_LANDMARK_ORDER.elbow_right, BODY_LANDMARK_ORDER.wrist_right]),
  Object.freeze([BODY_LANDMARK_ORDER.shoulder_left, BODY_LANDMARK_ORDER.hip_left]),
  Object.freeze([BODY_LANDMARK_ORDER.shoulder_right, BODY_LANDMARK_ORDER.hip_right]),
  Object.freeze([BODY_LANDMARK_ORDER.hip_left, BODY_LANDMARK_ORDER.hip_right]),
]);

/**
 * 上半身派生出的 Live2D 参数（全部是**可选参数**：模型没有时由 live2d.js 查参数表跳过）。
 *
 * ParamBodyAngleX 同时被头部姿态驱动（协议 §8.2 的 `-yaw*10`），叠加规则见 combineBodyAngleX()。
 *
 * 骨骼驱动（buildUpperBodySkeleton 的层级关节角，协议 §8.5）：
 *   ParamBodyAngleX/Y/Z  躯干（手机端 TorsoAngles，body = [roll, yaw]）
 *   ParamArmLA/RA        上臂抬起角（肩→腕与竖直向下的夹角，0 = 下垂、150 = 满量程）
 *   ParamArmLB/RB        肘关节角（前臂相对上臂的屈曲；侧别镜像后**正 = 屈肘**）
 *   ParamShoulderL/R     锁骨角（肩线相对髋线的差动倾斜；**正 = 该侧肩抬起**）
 *   ParamHandL/R         手部旋转（前臂朝向的近似；协议没有手部关键点，腕朝向无从测量）
 *   ParamHandAngleL/R    同上（Haru 等模型用的参数别名，值与 ParamHandL/R 相同）
 *
 * ⚠️ 这里的数值是「契约单位」（±30 量程），写进模型时由 live2d.js **按模型实际参数量程
 * 等比缩放**（Haru 的 ParamArmLA 量程是 [-1,1]，直接写 27 会被 clamp 死）。
 */
export const BODY_PARAM_IDS = Object.freeze([
  "ParamBodyAngleX",
  "ParamBodyAngleY",
  "ParamBodyAngleZ",
  "ParamArmLA",
  "ParamArmRA",
  "ParamArmLB",
  "ParamArmRB",
  "ParamShoulderL",
  "ParamShoulderR",
  "ParamHandL",
  "ParamHandR",
  "ParamHandAngleL",
  "ParamHandAngleR",
]);

/** 上半身快照多久没更新就按「丢失」处理（协议 §4.2：手机端 1500 ms 过期）。 */
export const BODY_STALE_MS = 1500;

/** 肩 / 肘 / 腕可见度低于该值时，该侧手臂参数不输出（交回模型默认姿态）。 */
export const BODY_ARM_MIN_VISIBILITY = 0.3;

/** 躯干 / 手臂参数绝对值上限（与 Cubism 模型常见参数范围一致）。 */
export const BODY_ANGLE_LIMIT = 30;

/** mapBody 默认系数（协议 §4.2 的参考映射，没有额外玄学）。 */
export const BODY_DEFAULTS = Object.freeze({
  /** roll → ParamBodyAngleZ（roll > 0 = 右肩下沉 → 身体向对应方向倾）。 */
  bodyZScale: 0.5,
  /** yaw → ParamBodyAngleX（yaw > 0 = 向本人左侧转 → 与头部同向叠加）。 */
  bodyXScale: 0.3,
  /** roll → ParamBodyAngleY（幅度很小，只为接口完整）。 */
  bodyYScale: 0.2,
  /** 手臂抬起角满量程（度）：0 = 自然下垂，150 = 举过头顶。 */
  armAngleMax: 150,
  /** armUp（0..1）× armScale → ParamArmLA/RA。 */
  armScale: 30,
  /** 肘屈曲角满量程（度）：0 = 手臂伸直，150 = 折到极限。 */
  elbowAngleMax: 150,
  /** elbowBend（-1..1）× elbowScale → ParamArmLB/RB。模型铰链方向相反时传负值。 */
  elbowScale: 30,
  /** 锁骨差动倾斜满量程（度）：肩线相对髋线倾斜多少算「满量程耸肩」。 */
  clavicleTiltMax: 25,
  /** clavicleRelTilt（-1..1）× shoulderScale → ParamShoulderL/R。 */
  shoulderScale: 30,
  /** 手部（前臂朝向）满量程（度）：150 与手臂同一口径。 */
  handAngleMax: 150,
  /** handDeg（-1..1）× handScale → ParamHandL/R、ParamHandAngleL/R。 */
  handScale: 30,
  /** 该侧肩 / 肘 / 腕可见度下限。 */
  minVisibility: BODY_ARM_MIN_VISIBILITY,
  /** 所有躯干 / 手臂参数的绝对值上限。 */
  angleLimit: BODY_ANGLE_LIMIT,
});

/** ubOk=false / 过期时每帧向 0 靠拢的比例（平滑回默认，不能突然跳）。 */
export const BODY_DECAY_PER_FRAME = 0.15;

/** 手臂链（协议下标）：肩 → 肘 → 腕。可见度取三者最小值。 */
const BODY_ARM_CHAINS = Object.freeze({
  L: Object.freeze([BODY_LANDMARK_ORDER.shoulder_left, BODY_LANDMARK_ORDER.elbow_left, BODY_LANDMARK_ORDER.wrist_left]),
  R: Object.freeze([BODY_LANDMARK_ORDER.shoulder_right, BODY_LANDMARK_ORDER.elbow_right, BODY_LANDMARK_ORDER.wrist_right]),
});

function finiteOr(value, fallback = 0) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

/** 只认 Array / TypedArray（DataView 没有 length，不认）。 */
function isNumericArray(value) {
  return Array.isArray(value) || (ArrayBuffer.isView(value) && typeof value.length === "number");
}

function sanitizeBodyPoint(point) {
  return {
    x: finiteOr(point?.x),
    y: finiteOr(point?.y),
    z: finiteOr(point?.z),
    v: clamp(finiteOr(point?.v), 0, 1),
  };
}

/** 扁平 52 项 / 已解析的 13 点 → 13 个 {x,y,z,v}；其它长度返回 null。 */
function toBodyPoints(input) {
  if (!input) return null;
  if (isNumericArray(input) && input.length === BODY_FLOATS) {
    const points = new Array(BODY_COUNT);
    for (let i = 0; i < BODY_COUNT; i++) {
      const base = i * 4;
      points[i] = {
        x: finiteOr(input[base]),
        y: finiteOr(input[base + 1]),
        z: finiteOr(input[base + 2]),
        v: clamp(finiteOr(input[base + 3]), 0, 1),
      };
    }
    return points;
  }
  if (Array.isArray(input) && input.length === BODY_COUNT) {
    return Array.from({ length: BODY_COUNT }, (_, i) => sanitizeBodyPoint(input[i]));
  }
  return null;
}

/**
 * 解析 `track.ub`（13 点 × 4 个 float 的扁平数组，顺序 = BODY_LANDMARK_ORDER）。
 *
 * - 长度不是 52 → `null`（整帧当作没有上半身，交给「平滑回默认」逻辑）；
 * - 单个分量坏掉（NaN / Infinity / 非数字）→ 该分量记 0（渲染路径不能崩，同 normalizeBs）；
 * - visibility 会 clamp 到 [0,1]。
 *
 * @param {ArrayLike<number>} ub
 * @returns {{x:number,y:number,z:number,v:number}[]|null}
 */
export function parseUpperBody(ub) {
  if (!isNumericArray(ub) || ub.length !== BODY_FLOATS) return null;
  return toBodyPoints(ub);
}

/**
 * 肩 → 腕向量与「竖直向下」的夹角（度）。
 *
 * 画面 y 向下为正，所以竖直向下 = (0, +1)；用 `atan2(|dx|, dy)` 一次算出 0..180°：
 * 0 = 手腕在肩正下方（自然下垂）、90 = 平举、180 = 举过头顶。左右对称（取 |dx|）。
 *
 * 注意：ub 的 x / y 是按画面宽 / 高**分别**归一化的，这里没有做长宽比矫正（协议不带
 * 画面尺寸）；因为只取角度、且手臂主要是上下运动，误差可以接受。
 */
function armAngleFromDown(shoulder, wrist) {
  const dx = wrist.x - shoulder.x;
  const dy = wrist.y - shoulder.y;
  if (Math.hypot(dx, dy) < 1e-6) return 0;
  return (Math.atan2(Math.abs(dx), dy) * 180) / Math.PI;
}

/** 向量与「竖直向下」的**带符号**夹角（度，(-180,180]）：+ = 向画面右偏。 */
function signedAngleFromDown(dx, dy) {
  if (Math.hypot(dx, dy) < 1e-6) return 0;
  return (Math.atan2(dx, dy) * 180) / Math.PI;
}

/** 把角度折回 (-180, 180]。 */
function wrapDeg(angle) {
  let a = angle % 360;
  if (a > 180) a -= 360;
  if (a <= -180) a += 360;
  return a;
}

/** 两点连线相对水平线的倾角（度）：+ = 右端（b）下沉，与协议 §5.3 的 roll 同号。 */
function lineTiltDeg(a, b) {
  if (Math.hypot(b.x - a.x, b.y - a.y) < 1e-6) return 0;
  return (Math.atan2(b.y - a.y, b.x - a.x) * 180) / Math.PI;
}

const EMPTY_ARM_BONE = Object.freeze({
  /** 上臂（肩→肘）与竖直向下的夹角（0..180，无符号）。 */
  upperArmDeg: 0,
  /** 前臂（肘→腕）与竖直向下的夹角（0..180，无符号）。 */
  forearmDeg: 0,
  /** 整条手臂（肩→腕）抬起角（0..180，无符号）——mapBody 的 armAngleL/R 同源。 */
  elevationDeg: 0,
  /** 上臂相对**躯干坐标系**的抬起角（带符号）：躯干跟着倾斜时手臂不会被误读为「抬起」。 */
  elevationTorsoDeg: 0,
  /** 肘关节角 = 前臂相对上臂的旋转（带符号，侧别镜像后 + = 屈肘）。 */
  elbowBendDeg: 0,
  /** 前臂朝向（带符号，侧别镜像后 + = 向画面外/上）——手部参数的近似数据源（协议无手部关键点）。 */
  handDeg: 0,
  /** 该侧肩 / 肘 / 腕可见度的最小值。 */
  visibility: 0,
  /** 肩→肘→腕 三段都非退化（重合点会让关节角失去意义）。 */
  valid: false,
});

/**
 * 13 点 → 上半身**运动骨骼**（层级关节角）。纯函数，无 DOM，Node 可直接单测。
 *
 * 骨骼层级（角度都是**相对父骨**的旋转，父骨动了子骨跟着动、不会重复计数）：
 *
 * ```
 * root   髋中点（只有位置）
 * └─ spine      髋中点 → 肩中点      leanDeg        相对「竖直向上」，+ = 肩向画面右倾
 *    ├─ clavicle    肩线相对髋线      relTiltDeg     + = 右肩相对下沉（= 左肩相对抬起）
 *    └─ upperArm L/R  肩 → 肘        elevationTorsoDeg  相对躯干的抬起角（带符号）
 *       └─ forearm L/R  肘 → 腕     elbowBendDeg    相对上臂的屈曲（带符号，+ = 屈肘）
 * ```
 *
 * 约定（画面 y 向下；ub 已镜像，本人左 = 画面左，协议 §5.3）：
 * - `*Deg` 里「与竖直向下的夹角」沿用 armAngleFromDown：0 = 下垂、90 = 平举、180 = 上举；
 * - 带符号角 + = 向画面右；`elbowBendDeg` 做了**侧别镜像**（左手取反），两侧屈肘同为正；
 * - 可见度不足的点照样算（方便调试面板看原始角度），`valid` / `visibility` 由调用方门控。
 *
 * @param {ArrayLike<number>|{x:number,y:number,z:number,v:number}[]} ub `track.ub` 或 parseUpperBody() 的结果
 * @returns {{points:object[]|null, spine:object, clavicle:object, arms:{L:object,R:object}}}
 */
export function buildUpperBodySkeleton(ub) {
  const points = toBodyPoints(ub);
  const arms = { L: { ...EMPTY_ARM_BONE }, R: { ...EMPTY_ARM_BONE } };
  const skeleton = {
    points,
    spine: {
      /** 脊柱相对「竖直向上」的倾斜（度，+ = 肩向画面右）。 */
      leanDeg: 0,
      shoulderMid: { x: 0, y: 0 },
      hipMid: { x: 0, y: 0 },
      valid: false,
    },
    clavicle: {
      /** 肩线倾角（+ = 右肩下沉，与协议 roll 同号）。 */
      tiltDeg: 0,
      /** 髋线倾角（同上）。 */
      pelvisTiltDeg: 0,
      /** 肩线相对髋线的差动倾斜：整体侧倾时 ≈ 0，耸肩 / 塌肩时才有值。 */
      relTiltDeg: 0,
      valid: false,
    },
    arms,
  };
  if (!points) return skeleton;

  const shoulderL = points[BODY_LANDMARK_ORDER.shoulder_left];
  const shoulderR = points[BODY_LANDMARK_ORDER.shoulder_right];
  const hipL = points[BODY_LANDMARK_ORDER.hip_left];
  const hipR = points[BODY_LANDMARK_ORDER.hip_right];

  const shoulderMid = { x: (shoulderL.x + shoulderR.x) / 2, y: (shoulderL.y + shoulderR.y) / 2 };
  const hipMid = { x: (hipL.x + hipR.x) / 2, y: (hipL.y + hipR.y) / 2 };
  skeleton.spine.shoulderMid = shoulderMid;
  skeleton.spine.hipMid = hipMid;
  const spineDx = shoulderMid.x - hipMid.x;
  const spineDy = shoulderMid.y - hipMid.y; // 通常为负：肩在髋上方
  if (Math.hypot(spineDx, spineDy) >= 1e-4) {
    skeleton.spine.leanDeg = (Math.atan2(spineDx, -spineDy) * 180) / Math.PI;
    skeleton.spine.valid = true;
  }

  const tilt = lineTiltDeg(shoulderL, shoulderR);
  const pelvisTilt = lineTiltDeg(hipL, hipR);
  skeleton.clavicle.tiltDeg = tilt;
  skeleton.clavicle.pelvisTiltDeg = pelvisTilt;
  skeleton.clavicle.relTiltDeg = wrapDeg(tilt - pelvisTilt);
  skeleton.clavicle.valid =
    Math.hypot(shoulderR.x - shoulderL.x, shoulderR.y - shoulderL.y) >= 1e-4 &&
    Math.hypot(hipR.x - hipL.x, hipR.y - hipL.y) >= 1e-4;

  for (const side of ["L", "R"]) {
    const [shoulderIndex, elbowIndex, wristIndex] = BODY_ARM_CHAINS[side];
    const shoulder = points[shoulderIndex];
    const elbow = points[elbowIndex];
    const wrist = points[wristIndex];
    const arm = arms[side];
    const upperDx = elbow.x - shoulder.x;
    const upperDy = elbow.y - shoulder.y;
    const foreDx = wrist.x - elbow.x;
    const foreDy = wrist.y - elbow.y;
    const armDx = wrist.x - shoulder.x;
    const armDy = wrist.y - shoulder.y;
    const upperOk = Math.hypot(upperDx, upperDy) >= 1e-4;
    const foreOk = Math.hypot(foreDx, foreDy) >= 1e-4;

    arm.visibility = Math.min(shoulder.v, elbow.v, wrist.v);
    arm.upperArmDeg = armAngleFromDown(shoulder, elbow);
    arm.forearmDeg = armAngleFromDown(elbow, wrist);
    arm.elevationDeg = armAngleFromDown(shoulder, wrist);
    const upperRot = upperOk ? signedAngleFromDown(upperDx, upperDy) : 0;
    const foreRot = foreOk ? signedAngleFromDown(foreDx, foreDy) : 0;
    const bend = wrapDeg(foreRot - upperRot);
    arm.elbowBendDeg = upperOk && foreOk ? (side === "L" ? -bend : bend) : 0;
    // 手：前臂朝向的侧别镜像值（协议没有手部关键点，腕/手掌朝向以前臂方向近似）
    arm.handDeg = foreOk ? (side === "L" ? -foreRot : foreRot) : 0;
    const armRot = Math.hypot(armDx, armDy) >= 1e-4 ? signedAngleFromDown(armDx, armDy) : 0;
    // 躯干「向下」轴与竖直向下的夹角恰好是 -leanDeg：手臂相对躯干 = armRot - (-lean)
    arm.elevationTorsoDeg = wrapDeg(armRot + skeleton.spine.leanDeg);
    arm.valid = upperOk && foreOk && Math.hypot(armDx, armDy) >= 1e-4;
  }

  return skeleton;
}

/**
 * 上半身运动骨骼 → Live2D 可选参数（纯函数，无 DOM，Node 可直接单测）。
 *
 * @param {ArrayLike<number>} bodyAngles 协议 §4.2 的 `body` = [roll, yaw]（相对基线，度）
 * @param {ArrayLike<number>|{x:number,y:number,z:number,v:number}[]} ub `track.ub` 或 parseUpperBody() 的结果
 * @param {Partial<typeof BODY_DEFAULTS>} [opts]
 * @returns {{params:object, metrics:object, skeleton:ReturnType<buildUpperBodySkeleton>}}
 *
 * 算法（协议 §8.5 / docs/upper-body.md §3.6）：
 *   ParamBodyAngleZ = clamp(roll * 0.5, ±30)   // roll > 0 = 右肩下沉
 *   ParamBodyAngleX = clamp(-yaw * 0.3, ±30)   // yaw > 0 = 向本人左侧转（与头部同向）
 *   ParamBodyAngleY = clamp(-roll * 0.2, ±30)  // 幅度很小，保持接口完整
 *   armAngle(side)  = 肩→腕 与竖直向下的夹角（度，0..180）
 *   armUp(side)     = clamp(armAngle / 150, 0, 1) → ParamArm{side}A = armUp * 30
 *   elbow(side)     = 前臂相对上臂的屈曲（度，侧别镜像后 + = 屈肘）
 *                     → ParamArm{side}B = clamp(elbow / 150, -1, 1) * 30
 *   hand(side)      = 前臂朝向（度，侧别镜像；协议无手部关键点，腕朝向以此近似）
 *                     → ParamHand{side} = ParamHandAngle{side} = clamp(hand / 150, -1, 1) * 30
 *   shrug           = clamp(锁骨差动倾斜 / 25, -1, 1)
 *                     → ParamShoulderL = shrug * 30、ParamShoulderR = -shrug * 30
 *
 * params 只包含「这一帧想驱动」的参数：
 *   - ParamBodyAngleX/Y/Z 始终输出（躯干角来自手机端 TorsoAngles，与关键点可见度无关）；
 *   - ParamArmLA/RA、ParamArmLB/RB 只在该侧肩 / 肘 / 腕可见度都 ≥ minVisibility 时输出；
 *   - ParamShoulderL/R 只在双肩 + 双胯可见度都 ≥ minVisibility 且肩 / 髋线非退化时输出；
 *   低可见度 → 不输出，由客户端把该参数平滑回模型默认，而不是拿坏关键点硬驱动。
 *
 * metrics.armAngleL/R、elbowBendL/R 是**原始角度**（不可信侧也有值，方便调试面板看），
 * 是否真的驱动看 params；skeleton 是层级骨骼（buildUpperBodySkeleton 的结果）。
 */
export function mapBody(bodyAngles, ub, opts = {}) {
  // docs/upper-body.md §3.6 写的是 mapBody(ub, body, opts)。两个参数都是数组，这里按长度
  // 识别并交换，避免调用方按文档顺序写时静默拿到全 0。
  if (isNumericArray(bodyAngles) && bodyAngles.length === BODY_FLOATS && isNumericArray(ub) && ub.length === 2) {
    const swap = bodyAngles;
    bodyAngles = ub;
    ub = swap;
  }

  const o = { ...BODY_DEFAULTS, ...opts };
  const roll = finiteOr(bodyAngles?.[0]);
  const yaw = finiteOr(bodyAngles?.[1]);
  const limit = Math.max(1, finiteOr(o.angleLimit, BODY_ANGLE_LIMIT));
  const armScale = finiteOr(o.armScale, 30);
  const armAngleMax = Math.max(1, finiteOr(o.armAngleMax, 150));
  const elbowScale = finiteOr(o.elbowScale, 30);
  const elbowAngleMax = Math.max(1, finiteOr(o.elbowAngleMax, 150));
  const shoulderScale = finiteOr(o.shoulderScale, 30);
  const clavicleTiltMax = Math.max(1, finiteOr(o.clavicleTiltMax, 25));
  const handScale = finiteOr(o.handScale, 30);
  const handAngleMax = Math.max(1, finiteOr(o.handAngleMax, 150));
  const minVisibility = clamp(finiteOr(o.minVisibility, BODY_ARM_MIN_VISIBILITY), 0, 1);

  const params = {
    ParamBodyAngleX: clamp(-yaw * finiteOr(o.bodyXScale, 0.3), -limit, limit),
    ParamBodyAngleY: clamp(-roll * finiteOr(o.bodyYScale, 0.2), -limit, limit),
    ParamBodyAngleZ: clamp(roll * finiteOr(o.bodyZScale, 0.5), -limit, limit),
  };
  const metrics = {
    roll,
    yaw,
    armAngleL: null,
    armAngleR: null,
    elbowBendL: null,
    elbowBendR: null,
    handAngleL: null,
    handAngleR: null,
    spineLean: 0,
    clavicleTilt: 0,
    clavicleRelTilt: 0,
    shoulderWidth: 0,
    confidence: 0,
  };

  const skeleton = buildUpperBodySkeleton(ub);
  const points = skeleton.points;
  if (!points) return { params, metrics, skeleton };

  // 肩宽与 Android 端 UpperBodyPoseFrame.shoulderSpan() 同一公式（便于两端对数字）。
  const shoulderL = points[BODY_LANDMARK_ORDER.shoulder_left];
  const shoulderR = points[BODY_LANDMARK_ORDER.shoulder_right];
  metrics.shoulderWidth = Math.abs(shoulderL.x - shoulderR.x) + Math.abs(shoulderL.y - shoulderR.y) * 0.5;
  metrics.confidence = Math.min(
    shoulderL.v,
    shoulderR.v,
    points[BODY_LANDMARK_ORDER.elbow_left].v,
    points[BODY_LANDMARK_ORDER.elbow_right].v,
    points[BODY_LANDMARK_ORDER.wrist_left].v,
    points[BODY_LANDMARK_ORDER.wrist_right].v,
  );
  metrics.spineLean = skeleton.spine.leanDeg;
  metrics.clavicleTilt = skeleton.clavicle.tiltDeg;
  metrics.clavicleRelTilt = skeleton.clavicle.relTiltDeg;

  for (const side of ["L", "R"]) {
    const arm = skeleton.arms[side];
    const angleKey = side === "L" ? "armAngleL" : "armAngleR";
    const bendKey = side === "L" ? "elbowBendL" : "elbowBendR";
    const handKey = side === "L" ? "handAngleL" : "handAngleR";
    metrics[angleKey] = arm.elevationDeg;
    metrics[bendKey] = arm.elbowBendDeg;
    metrics[handKey] = arm.handDeg;
    if (arm.visibility < minVisibility) continue; // 不可信 → 不输出该侧手臂参数
    const armUp = clamp(arm.elevationDeg / armAngleMax, 0, 1);
    params[side === "L" ? "ParamArmLA" : "ParamArmRA"] = armUp * armScale;
    // 肘：前臂相对上臂的屈曲（侧别镜像后正 = 屈肘）→ ParamArmLB/RB
    const elbowBend = clamp(arm.elbowBendDeg / elbowAngleMax, -1, 1);
    params[side === "L" ? "ParamArmLB" : "ParamArmRB"] = elbowBend * elbowScale;
    // 手：前臂朝向的近似（协议没有手部关键点）→ ParamHandL/R 与别名 ParamHandAngleL/R 同值
    const hand = clamp(arm.handDeg / handAngleMax, -1, 1) * handScale;
    params[side === "L" ? "ParamHandL" : "ParamHandR"] = hand;
    params[side === "L" ? "ParamHandAngleL" : "ParamHandAngleR"] = hand;
  }

  // 锁骨：肩线相对髋线的差动倾斜（整体侧倾归 body roll → ParamBodyAngleZ，这里只管肩膀差动）
  const clavicleVisibility = Math.min(
    shoulderL.v,
    shoulderR.v,
    points[BODY_LANDMARK_ORDER.hip_left].v,
    points[BODY_LANDMARK_ORDER.hip_right].v,
  );
  if (skeleton.clavicle.valid && clavicleVisibility >= minVisibility) {
    const shrug = clamp(skeleton.clavicle.relTiltDeg / clavicleTiltMax, -1, 1);
    // 符号约定（协议 §8.5）：正 = 该侧肩抬起。右肩相对下沉（relTilt > 0）= 左肩抬起。
    params.ParamShoulderL = shrug * shoulderScale;
    params.ParamShoulderR = -shrug * shoulderScale;
  }

  return { params, metrics, skeleton };
}

/**
 * 头部与躯干共用 ParamBodyAngleX：**相加后** clamp 到 ±30，不能互相覆盖。
 * （头部来自协议 §8.2 的 `-yaw*10`，躯干来自 mapBody 的 `-yaw_body*0.3`。）
 */
export function combineBodyAngleX(headBodyAngleX, torsoBodyAngleX, limit = BODY_ANGLE_LIMIT) {
  const max = Math.max(1, finiteOr(limit, BODY_ANGLE_LIMIT));
  return clamp(finiteOr(headBodyAngleX) + finiteOr(torsoBodyAngleX), -max, max);
}

/**
 * 解析一条 `track` 报文里的上半身字段（纯函数，Node 可直接单测）。
 *
 * 字段语义（协议 §4.2）：
 *   - `ub`    13×4 扁平数组（可选；缺省 = 这一帧完全没有上半身结果）
 *   - `ubOk`  false = 本帧没有可用结果（缺省按 true 处理，兼容只发 ub 的早期实现）
 *   - `ubT`   手机采集时间（ms）。⚠️ 两端时钟不同步，**不能**和本机 now 直接比较；
 *              这里只用它判断「是不是新采集的一帧」，过期用**本地到达时间**的 age 量：
 *              同一个 ubT 连续出现（手机还在发面捕、但上半身停了）→ age 一直涨 → 1.5s 后 stale。
 *   - `body`  [roll, yaw]（度；缺省 = [0,0]）。**语义由 `ubRaw` 显式声明**：
 *              ubRaw 缺省 = 手机端平滑 + 基线后的相对角（旧语义，逐字节不变）；
 *              ubRaw:true = 原始角（未 One-Euro、未减基线），由 PC 端后处理链负责
 *              平滑 / 减基线（见 UpperBodyPostProcessor）。
 *   - `ubRaw` / `ubRawBody`（upperBodyPostV1 协商成功后手机才会发，协议 §4.1 / §4.2）
 *
 * @param {object} track
 * @param {number} now 本机单调时钟（performance.now() 口径）
 * @param {object|null} prev 上一次的返回值（用于 ubT 变化检测 / 保留最后一帧点做调试骨架）
 */
export function readUpperBodyTrack(track, now = 0, prev = null) {
  const message = track && typeof track === "object" ? track : {};
  const last = prev && typeof prev === "object" ? prev : {};
  const nowMs = finiteOr(now);
  const hasUb = message.ub != null;
  const points = hasUb ? parseUpperBody(message.ub) : null;
  const ubT = Number(message.ubT);
  const hasStamp = Number.isFinite(ubT);
  const rawTorso = message.body;
  const torso =
    isNumericArray(rawTorso) && rawTorso.length >= 2
      ? [finiteOr(rawTorso[0]), finiteOr(rawTorso[1])]
      : [0, 0];

  // upperBodyPostV1（协议 §4.1 / §4.2）：`ubRaw:true` 显式声明本帧 ub 是**原始点**
  //（未 One-Euro、未减基线），且 body / ubRawBody 是**原始躯干角**（未平滑未减基线）。
  // 语义由 ubRaw 显式声明，不存在「字段名没变但语义变了」；ubRaw 缺省时 body 仍是
  // 旧语义（手机端平滑 + 基线后的相对角），老报文行为完全不变。
  const rawDeclared = message.ubRaw === true;
  const rawBody = message.ubRawBody;
  const phoneRawTorso =
    isNumericArray(rawBody) && rawBody.length >= 2
      ? [finiteOr(rawBody[0]), finiteOr(rawBody[1])]
      : null;

  let receivedAt = typeof last.receivedAt === "number" && Number.isFinite(last.receivedAt) ? last.receivedAt : null;
  if (hasUb) {
    if (hasStamp) {
      // 新的采集时间戳才算新帧；同一个 ubT 反复出现 = 手机端上半身没更新，age 继续累积
      if (last.t == null || ubT !== last.t) receivedAt = nowMs;
    } else {
      // 没有 ubT 的老实现：只要这一帧带了 ub，就当作新鲜
      receivedAt = nowMs;
    }
  }
  const ageMs = receivedAt == null ? null : Math.max(0, nowMs - receivedAt);
  const stale = ageMs != null && ageMs > BODY_STALE_MS;
  const declared = message.ubOk;
  const ubOk = declared === undefined || declared === null ? true : Boolean(declared);

  return {
    /** 这一帧的上半身数据是否可用（有 ub + 长度对 + ubOk + 未过期）。 */
    ok: Boolean(hasUb) && points !== null && ubOk && !stale,
    /** 报文里是否出现了 ub 字段（老手机的 track 里没有）。 */
    present: hasUb,
    ubOk,
    stale,
    ageMs,
    /** 13 个点；本帧没有就保留上一帧（调试骨架画最后一帧，配 ok=false 灰显）。 */
    points: points ?? (Array.isArray(last.points) ? last.points : null),
    /** [roll, yaw]：ubRaw 缺省 = 相对基线角（旧语义）；ubRaw:true = 原始角（未平滑未减基线）。 */
    torso,
    /** ubRaw：本帧是否声明为原始点 + 原始角模式（协议 upperBodyPostV1）。 */
    raw: rawDeclared,
    /** ubRawBody：手机端算出的原始躯干角（可选字段，缺省 null；与 torso 同值，用于对拍/调试）。 */
    phoneRawTorso,
    /** 手机采集时间戳（没有就是 null）。 */
    t: hasStamp ? ubT : hasUb ? null : typeof last.t === "number" ? last.t : null,
    /** 本机到达时间（age 的基准）。 */
    receivedAt,
  };
}

/**
 * 上半身参数平滑（ubOk=false / ubT 过期时**按帧**回 0，不能突然跳到默认姿态）。
 *
 * 规则：
 *   - 本帧有目标值 → 直接取目标（连续性与缓动交给 live2d 的时间轴平滑）；
 *   - 本帧没有目标（丢失 / 过期 / 该侧低可见度）→ 在上一帧输出值基础上**向 0 前进 15%**；
 *   - 已经衰减到 0（|v| < epsilon）→ 从输出里删掉，并停止输出：把该参数交还给模型的
 *     idle motion / breath（否则会每帧写死 0，模型的身体摆动就没了）；
 *   - 从没被上半身驱动过的参数不输出（老手机 / 关闭上半身时行为与以前完全一致）。
 *
 * @param {object|null} current 上一帧 smoothBodyParams 的返回值
 * @param {object|null} target 本帧 mapBody(...).params（不可用传 null）
 * @param {{decay?:number, epsilon?:number, snap?:boolean}} [opts] snap = 直接落到目标（截图 / 自测用）
 * @returns {object} 新的输出表（只含需要继续驱动的参数）
 */
export function smoothBodyParams(current, target, opts = {}) {
  const prev = current && typeof current === "object" ? current : {};
  const goal = target && typeof target === "object" ? target : {};
  const decay = clamp(finiteOr(opts.decay, BODY_DECAY_PER_FRAME), 0.01, 1);
  const epsilon = Math.max(0, finiteOr(opts.epsilon, 0.01));
  const snap = Boolean(opts.snap);
  const out = {};

  for (const id of BODY_PARAM_IDS) {
    const hasGoal = typeof goal[id] === "number" && Number.isFinite(goal[id]);
    const hasPrev = Object.prototype.hasOwnProperty.call(prev, id);
    if (!hasGoal && !hasPrev) continue; // 从未被上半身驱动：不输出，交给 idle motion
    const base = hasPrev && Number.isFinite(prev[id]) ? prev[id] : 0;
    let value;
    if (hasGoal) value = goal[id];
    else value = snap ? 0 : base * (1 - decay);
    if (!Number.isFinite(value)) value = 0;
    if (Math.abs(value) < epsilon) value = 0;
    out[id] = value;
  }

  // 本帧完全没有目标且都已衰减到 0：全部删掉，让模型自己的 idle motion 接管。
  const anyGoal = BODY_PARAM_IDS.some((id) => typeof goal[id] === "number" && Number.isFinite(goal[id]));
  if (!anyGoal) {
    for (const id of Object.keys(out)) if (out[id] === 0) delete out[id];
  }
  return out;
}

// ---------------------------------------------------------------- 上半身后处理链（任务 M2：upperBodyPostV1）
//
// 协商成功（hello 里 upperBodyPostV1）后，手机端只发**原始** 13 点 + 原始躯干角
//（ubRaw:true），One-Euro 平滑、躯干角计算、TorsoBaseline 全部迁到本模块：
//
//   原始 13 点 ──One-Euro(1.2/0.02)──▶ 平滑点 ──computeTorsoAngles──▶ 原始角
//                                        │                            │
//                                        ▼                            ▼
//                              mapBody(点链)                  One-Euro(0.6/0.01)
//                                                                     │
//                                                             TorsoBaseline(10 帧)
//                                                                     │
//                                                          相对角 ──▶ mapBody(躯干)
//
// 与 Kotlin（UpperBodySmoother / UpperBodyGeometry.torso / TorsoBaseline /
// UpperBodyPoseDetector.buildFrame）**逐行等价**，参数一致，由
// tools/verify/ub-post-test.mjs 与 Android 端 UpperBodyPostEquivalenceTest 对同一份
// 夹具（MediapipeUpperBody/src/test/resources/ub_post_fixture.txt）对拍。
//
// 滤波责任归属（总时间常数估算见 docs/upper-body.md §3.2）：
//   - 信号调理（去抖动）= 本模块 One-Euro，唯一的一层；
//   - 观感层（渲染低通 / 速度自适应）= live2d.js / filter.js，只允许**一层**轻量低通，
//     原始模式建议 ≤ 60ms（RAW_BODY_RENDER_TAU_MS），避免 One-Euro + 160ms 双重过度平滑；
//   - 三层回退（1500ms 过期 / ×0.85 按帧衰减 / 从输出删除）不变，仍由
//     readUpperBodyTrack + smoothBodyParams 负责。

/** 13 点 One-Euro 参数（与 Kotlin OneEuroFilter.DEFAULT_* 一致）。 */
export const BODY_ONE_EURO_DEFAULTS = Object.freeze({
  minCutoff: 1.2,
  beta: 0.02,
  derivativeCutoff: 1.0,
});

/** 躯干角 One-Euro 参数（与 Kotlin UpperBodySmoother.DEFAULT_TORSO_* 一致）。 */
export const TORSO_ONE_EURO_DEFAULTS = Object.freeze({
  minCutoff: 0.6,
  beta: 0.01,
  derivativeCutoff: 1.0,
});

/** 归一化坐标下水平基准的最小值（与 Kotlin UpperBodyGeometry.MIN_REFERENCE_SPAN 一致）。 */
export const TORSO_MIN_REFERENCE_SPAN = 0.02;

/** 躯干线可用所需的最低可见度（与 Kotlin MIN_TORSO_VISIBILITY 一致）。 */
export const TORSO_MIN_VISIBILITY = 0.3;

/** 单点可信的可见度阈值（与 Kotlin RELIABLE_VISIBILITY 一致）。 */
export const BODY_TRACKING_MIN_VISIBILITY = 0.5;

/** 至少几个可信点才算 tracking（与 Kotlin MIN_TRACKING_POINTS 一致）。 */
export const BODY_TRACKING_MIN_POINTS = 7;

/** TorsoBaseline 校准帧数（与 Kotlin TorsoBaseline.DEFAULT_CALIBRATION_FRAMES 一致）。 */
export const BODY_BASELINE_CALIBRATION_FRAMES = 10;

/** 后处理管线参数默认值（与 Kotlin UpperBodyPoseDetector 的 dt 规则一致）。 */
export const BODY_POST_DEFAULTS = Object.freeze({
  minCutoff: BODY_ONE_EURO_DEFAULTS.minCutoff,
  beta: BODY_ONE_EURO_DEFAULTS.beta,
  pointDerivativeCutoff: BODY_ONE_EURO_DEFAULTS.derivativeCutoff,
  torsoMinCutoff: TORSO_ONE_EURO_DEFAULTS.minCutoff,
  torsoBeta: TORSO_ONE_EURO_DEFAULTS.beta,
  torsoDerivativeCutoff: TORSO_ONE_EURO_DEFAULTS.derivativeCutoff,
  calibrationFrames: BODY_BASELINE_CALIBRATION_FRAMES,
  /** 首帧（平滑器重置后）的 dt：1 / 15fps（Kotlin 用 targetFps=15）。 */
  defaultDtSeconds: 1 / 15,
  /** 相邻帧 dt 的上限（与 Kotlin MAX_PIPELINE_GAP_MS 一致）。 */
  maxPipelineGapMs: 500,
});

/**
 * 原始模式下**渲染层**低通时间常数的建议上限（ms）：One-Euro 已负责信号调理，
 * 渲染层只留一层轻量观感平滑。与 M4 filter.js 的 body 组 slowTauMs 对接用；
 * 未协商（旧链）保持旧的 160ms 逐位不变。
 */
export const RAW_BODY_RENDER_TAU_MS = 60;

/**
 * One-Euro 低通（Kotlin OneEuroFilter 的逐行移植；双精度，误差由对拍容差吸收）。
 *
 * cutoff = minCutoff + beta * |edx|，tau = 1/(2π·cutoff)，alpha = 1/(1+tau/dt)。
 * dt ≤ 0 / NaN 按 1ms，dt > 0.5s 截断到 0.5s（与 Kotlin MIN/MAX_DT_SECONDS 一致）。
 */
export class OneEuroFilter {
  constructor(
    minCutoff = BODY_ONE_EURO_DEFAULTS.minCutoff,
    beta = BODY_ONE_EURO_DEFAULTS.beta,
    derivativeCutoff = BODY_ONE_EURO_DEFAULTS.derivativeCutoff,
  ) {
    this.minCutoff = minCutoff;
    this.beta = beta;
    this.derivativeCutoff = derivativeCutoff;
    this.lastValue = NaN;
    this.lastDerivative = 0;
  }

  /** 是否已经收到过一个有效输入。 */
  get hasValue() {
    return Number.isFinite(this.lastValue);
  }

  /** 清空状态（丢帧 / 重新捕获目标时调用，避免把旧位置拖过来）。 */
  reset() {
    this.lastValue = NaN;
    this.lastDerivative = 0;
  }

  /**
   * 过滤一个采样点。
   * @param {number} value 新采样值
   * @param {number} dtSeconds 与上一次采样的时间差（秒）
   * @returns {number} 平滑后的值；输入非有限时返回上一次有效值（从未有效则 0）
   */
  filter(value, dtSeconds) {
    const v = Number(value);
    if (!Number.isFinite(v)) return Number.isFinite(this.lastValue) ? this.lastValue : 0;
    if (!Number.isFinite(this.lastValue)) {
      this.lastValue = v;
      this.lastDerivative = 0;
      return v;
    }
    const rawDt = Number(dtSeconds);
    const dt = Number.isFinite(rawDt) && rawDt > 0 ? Math.min(rawDt, 0.5) : 0.001;
    const rawDerivative = (v - this.lastValue) / dt;
    const derivativeAlpha = this.alpha(this.derivativeCutoff, dt);
    this.lastDerivative += derivativeAlpha * (rawDerivative - this.lastDerivative);
    const cutoff = this.minCutoff + this.beta * Math.abs(this.lastDerivative);
    const valueAlpha = this.alpha(cutoff, dt);
    const filtered = this.lastValue + valueAlpha * (v - this.lastValue);
    this.lastValue = filtered;
    return filtered;
  }

  alpha(cutoff, dt) {
    if (!(cutoff > 0)) return 1;
    const tau = 1 / (2 * Math.PI * cutoff);
    return 1 / (1 + tau / dt);
  }
}

/** 13 点里至少几个可见度 > 0.5 才算 tracking（Kotlin UpperBodyGeometry.isTracking）。 */
export function isUpperBodyTracking(points) {
  const list = toBodyPoints(points);
  if (!list) return false;
  let reliable = 0;
  for (const p of list) {
    if (p.v > BODY_TRACKING_MIN_VISIBILITY) reliable += 1;
  }
  return reliable >= BODY_TRACKING_MIN_POINTS;
}

/**
 * 13 点 → 原始躯干角 [roll, yaw]（度，未平滑未减基线）。
 *
 * 与 Kotlin UpperBodyGeometry.torso **逐行等价**，符号约定（协议 §5.3）：
 *   horizontal = max(|right.x - left.x|, 0.02)   ← 0.02 下限，禁止直接除 dx
 *   roll = deg(atan2(right.y - left.y, horizontal))   // 右肩下沉为正
 *   yaw  = deg(atan2(-(right.z - left.z), horizontal)) // 本人左侧转为正
 *
 * 肩线不可见时按「肩 → 肘 → 胯」退化；两肩在画面中重合时靠 0.02 下限兜住，
 * 不会出现几十度假角度。z 是相对深度（非米制），只进 atan2。
 *
 * @param {ArrayLike<number>|{x,y,z,v}[]} ub
 * @returns {[number, number]} [roll, yaw]
 */
export function computeTorsoAngles(ub) {
  const points = toBodyPoints(ub);
  if (!points) return [0, 0];
  const pairs = [
    [BODY_LANDMARK_ORDER.shoulder_left, BODY_LANDMARK_ORDER.shoulder_right],
    [BODY_LANDMARK_ORDER.elbow_left, BODY_LANDMARK_ORDER.elbow_right],
    [BODY_LANDMARK_ORDER.hip_left, BODY_LANDMARK_ORDER.hip_right],
  ];
  for (const [li, ri] of pairs) {
    const l = points[li];
    const r = points[ri];
    if (!Number.isFinite(l.v) || !Number.isFinite(r.v)) continue;
    if (Math.min(l.v, r.v) < TORSO_MIN_VISIBILITY) continue;
    if (![l.x, l.y, l.z, r.x, r.y, r.z].every(Number.isFinite)) continue;
    const dx = r.x - l.x;
    const dy = r.y - l.y;
    const dz = r.z - l.z;
    const horizontal = Math.max(Math.abs(dx), TORSO_MIN_REFERENCE_SPAN);
    const roll = (Math.atan2(dy, horizontal) * 180) / Math.PI;
    const yaw = (Math.atan2(-dz, horizontal) * 180) / Math.PI;
    if (!Number.isFinite(roll) || !Number.isFinite(yaw)) continue;
    return [roll, yaw];
  }
  return [0, 0];
}

/**
 * 躯干角「相对基线」（Kotlin TorsoBaseline 的逐行移植）。
 *
 * [reset] 之后先收集 calibrationFrames 个有效帧，用均值作中性姿态：
 *   - 校准期间输出 = (当前值 − 已收集帧均值) × collected/calibrationFrames（0→1 渐入，
 *     起播 10 帧内不会从 0 突跳到基线）；
 *   - 校准完成后输出 = 当前值 − 基线。
 *
 * 基线可持久化（按角色 profile 区分，见 serialize/deserialize：app.js 把它存进
 * `vfc.bodyBaseline.<profileKey>`，换角色各存各的、手动重置即清掉重采）。
 */
export class TorsoBaseline {
  constructor(calibrationFrames = BODY_BASELINE_CALIBRATION_FRAMES) {
    this.calibrationFrames = calibrationFrames > 0 ? calibrationFrames : 1;
    this.reset();
  }

  /** 是否已用满校准帧数、基线固定下来了。 */
  get isCalibrated() {
    return this.collected >= this.calibrationFrames;
  }

  /** 重置：下一次 apply 开始重新收集中性姿态。 */
  reset() {
    this.collected = 0;
    this.sumRoll = 0;
    this.sumYaw = 0;
    this.baselineRoll = 0;
    this.baselineYaw = 0;
  }

  /**
   * 原始躯干角 → 相对基线角。
   * @param {[number, number]} angles [roll, yaw]（未减基线）
   * @returns {[number, number]} 相对角；非法输入返回 [0,0] 且不污染校准均值
   */
  apply(angles) {
    const roll = Number(angles?.[0]);
    const yaw = Number(angles?.[1]);
    if (!Number.isFinite(roll) || !Number.isFinite(yaw)) return [0, 0];
    if (this.isCalibrated) return [roll - this.baselineRoll, yaw - this.baselineYaw];
    this.collected += 1;
    this.sumRoll += roll;
    this.sumYaw += yaw;
    const meanRoll = this.sumRoll / this.collected;
    const meanYaw = this.sumYaw / this.collected;
    if (this.collected >= this.calibrationFrames) {
      this.baselineRoll = meanRoll;
      this.baselineYaw = meanYaw;
    }
    const weight = Math.min(1, this.collected / this.calibrationFrames);
    return [(roll - meanRoll) * weight, (yaw - meanYaw) * weight];
  }

  /** 持久化快照（JSON 可序列化）。 */
  serialize() {
    return {
      version: 1,
      calibrationFrames: this.calibrationFrames,
      collected: this.collected,
      sumRoll: this.sumRoll,
      sumYaw: this.sumYaw,
      baselineRoll: this.baselineRoll,
      baselineYaw: this.baselineYaw,
    };
  }

  /**
   * 从持久化快照恢复（坏数据返回 null，调用方按「无基线」处理）。
   * 已校准的快照恢复后 isCalibrated=true，基线原样生效；未校准的快照继续收集。
   */
  static deserialize(raw) {
    const data = raw && typeof raw === "object" ? raw : null;
    if (!data || data.version !== 1) return null;
    const frames = Number(data.calibrationFrames);
    const collected = Number(data.collected);
    const sumRoll = Number(data.sumRoll);
    const sumYaw = Number(data.sumYaw);
    const baselineRoll = Number(data.baselineRoll);
    const baselineYaw = Number(data.baselineYaw);
    if (![frames, collected, sumRoll, sumYaw, baselineRoll, baselineYaw].every(Number.isFinite)) return null;
    if (collected < 0 || frames <= 0) return null;
    const baseline = new TorsoBaseline(frames);
    baseline.collected = Math.floor(collected);
    baseline.sumRoll = sumRoll;
    baseline.sumYaw = sumYaw;
    baseline.baselineRoll = baselineRoll;
    baseline.baselineYaw = baselineYaw;
    return baseline;
  }
}

/**
 * 上半身后处理管线（原始点 → 平滑点 + 相对躯干角）。
 *
 * 与 Kotlin UpperBodyPoseDetector.buildFrame 的平滑段逐行等价：
 *   dt 规则：无锚点 → defaultDtSeconds（1/15s）；否则 clamp(t−lastT, 1, maxPipelineGapMs)/1000；
 *   tracking 丢失（isTracking 不满足）→ 平滑器全重置 + dt 锚点清零，**基线不动**；
 *   坏点（x=y=z=v=0）透传不参与平滑，避免坏帧把该点拖向原点。
 */
export class UpperBodyPostProcessor {
  /** @param {Partial<typeof BODY_POST_DEFAULTS>} [options] */
  constructor(options = {}) {
    this.options = { ...BODY_POST_DEFAULTS, ...options };
    const o = this.options;
    this.pointFilters = Array.from({ length: BODY_COUNT }, () => [
      new OneEuroFilter(o.minCutoff, o.beta, o.pointDerivativeCutoff),
      new OneEuroFilter(o.minCutoff, o.beta, o.pointDerivativeCutoff),
      new OneEuroFilter(o.minCutoff, o.beta, o.pointDerivativeCutoff),
    ]);
    this.torsoFilters = [
      new OneEuroFilter(o.torsoMinCutoff, o.torsoBeta, o.torsoDerivativeCutoff),
      new OneEuroFilter(o.torsoMinCutoff, o.torsoBeta, o.torsoDerivativeCutoff),
    ];
    this.baseline = new TorsoBaseline(o.calibrationFrames);
    this._lastT = null;
  }

  /** 平滑器 + dt 锚点重置（tracking 丢失 / 上报 ubOk=false）；基线不动。 */
  resetSmoothers() {
    for (const triple of this.pointFilters) {
      triple[0].reset();
      triple[1].reset();
      triple[2].reset();
    }
    this.torsoFilters[0].reset();
    this.torsoFilters[1].reset();
    this._lastT = null;
  }

  /** 手动重置基线（UI「重置上半身基线」/ 换人重新校准）：下次开始重新收集 10 帧均值。 */
  resetBaseline() {
    this.baseline.reset();
  }

  /** 基线持久化快照。 */
  serializeBaseline() {
    return this.baseline.serialize();
  }

  /** 恢复持久化的基线；坏数据静默忽略（保持冷启动行为）。 */
  loadBaseline(raw) {
    const restored = TorsoBaseline.deserialize(raw);
    if (restored) this.baseline = restored;
    return restored != null;
  }

  /**
   * 处理一帧原始 13 点。
   * @param {ArrayLike<number>|{x,y,z,v}[]} ub 原始 13 点（parseUpperBody 的输出即可）
   * @param {number} tMs 本帧采集时间（ubT；缺采集戳时传本地到达时间）
   * @returns {{tracking:boolean, points:object[]|null, torso:[number,number],
   *            torsoRaw:[number,number], torsoSmoothed:[number,number], dtSeconds:number}}
   *   points = 平滑后的 13 点（喂 mapBody 的点链）；torso = 相对基线角（喂 mapBody 的躯干）。
   */
  process(ub, tMs) {
    const points = toBodyPoints(ub);
    if (!points || !isUpperBodyTracking(points)) {
      this.resetSmoothers();
      return { tracking: false, points: null, torso: [0, 0], torsoRaw: [0, 0], torsoSmoothed: [0, 0], dtSeconds: 0 };
    }
    const t = Number(tMs);
    let dtSeconds;
    if (this._lastT != null && Number.isFinite(t)) {
      dtSeconds = Math.min(Math.max(t - this._lastT, 1), this.options.maxPipelineGapMs) / 1000;
    } else {
      dtSeconds = this.options.defaultDtSeconds;
    }
    if (Number.isFinite(t)) this._lastT = t;

    const smoothed = points.map((p, i) => {
      // 缺失 / 非法坐标（extract 置为全 0）直接透传、不参与平滑（与 Kotlin 一致）
      if (p.x === 0 && p.y === 0 && p.z === 0 && p.v === 0) return { ...p };
      const [fx, fy, fz] = this.pointFilters[i];
      return {
        x: fx.filter(p.x, dtSeconds),
        y: fy.filter(p.y, dtSeconds),
        z: fz.filter(p.z, dtSeconds),
        v: p.v,
      };
    });

    const torsoRaw = computeTorsoAngles(smoothed);
    const torsoSmoothed = [
      this.torsoFilters[0].filter(torsoRaw[0], dtSeconds),
      this.torsoFilters[1].filter(torsoRaw[1], dtSeconds),
    ];
    const torso = this.baseline.apply(torsoSmoothed);
    return {
      tracking: true,
      points: smoothed,
      torso: [finiteOr(torso[0]), finiteOr(torso[1])],
      torsoRaw,
      torsoSmoothed,
      dtSeconds,
    };
  }
}

/**
 * 上半身状态机初始值（连接 / 重连 / 断线时复位）——app.js 的 createBodyState 即本函数。
 *
 * points / torso 保留最后一帧给调试骨架灰显；ok=false 时所有 body 参数按帧回 0。
 */
export function createBodyState() {
  return {
    /** 本帧上半身数据是否可用（有 ub + 长度对 + ubOk + 未过期）。 */
    ok: false,
    /** 报文里出现过 ub 字段（老手机的 track 没有）。 */
    present: false,
    ubOk: true,
    stale: false,
    ageMs: null,
    points: null,
    torso: [0, 0],
    /** ubRaw：本帧是否为原始点 + 原始角模式（upperBodyPostV1）。 */
    raw: false,
    /** ubRawBody：手机端原始角（对拍 / 调试；缺省 null）。 */
    phoneRawTorso: null,
    t: null,
    receivedAt: null,
    metrics: null,
    /** 本帧实际写给 live2d 的上半身参数（smoothBodyParams 的结果）。 */
    current: {},
    /** 下一帧直接落到目标（__vfc.applyUpperBody 截图 / 自测用）。 */
    snap: false,
  };
}

/**
 * 解析 track 的上半身字段并算出这一帧要驱动的上半身参数（app.js updateBodyState 的纯函数版）。
 *
 * 与旧链唯一差别：`raw:true`（ubRaw）时先过 [UpperBodyPostProcessor]（One-Euro 平滑 +
 * 躯干角 + 基线），再把**平滑点 + 相对角**喂给 mapBody —— mapBody / smoothBodyParams
 * 与三层回退（1500ms 过期 / ×0.85 按帧衰减 / 从输出删除）完全不变。
 *
 * @param {object} track 一条 track 报文
 * @param {number} now 本机单调时钟（performance.now() 口径）
 * @param {object|null} prev 上一帧的返回值
 * @param {{pipeline?:UpperBodyPostProcessor|null, decay?:number, epsilon?:number}} [options]
 *   pipeline = 原始模式的后处理管线（老手机 / 未协商时为 null，走原链）
 * @returns {object} 新的 body 状态（含 metrics / current）
 */
export function updateBodyStateFromTrack(track, now, prev, options = {}) {
  const before = prev && typeof prev === "object" ? prev : createBodyState();
  const next = readUpperBodyTrack(track, now, before);
  const pipeline = options.pipeline ?? null;
  let torso = next.torso;
  let points = next.points;
  if (next.ok && next.raw && pipeline) {
    const out = pipeline.process(points, Number.isFinite(Number(next.t)) ? next.t : finiteOr(now));
    if (out.tracking) {
      points = out.points;
      torso = out.torso;
    } else {
      // 手机说 ok 但点质量不过关：按不可用处理（映射不出去），平滑器已由 process 重置
      next.ok = false;
    }
  } else if (pipeline && !next.ok) {
    // 丢失 / 过期：与手机端 tracking 丢失一致，重置平滑器与 dt 锚点（基线不动）
    pipeline.resetSmoothers();
  }
  const mapped = next.ok ? mapBody(torso, points) : null;
  next.metrics = mapped ? mapped.metrics : before.metrics;
  next.current = smoothBodyParams(before.current, mapped ? mapped.params : null, {
    decay: finiteOr(options.decay, BODY_DECAY_PER_FRAME),
    snap: Boolean(before.snap),
    epsilon: options.epsilon,
  });
  next.snap = false; // snap 只作用于本帧
  return next;
}

