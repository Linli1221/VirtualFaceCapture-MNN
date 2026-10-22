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

/** createMapper 默认系数（协议 §8.2）。 */
export const DEFAULT_OPTIONS = Object.freeze({
  invertPitch: false,
  invertYaw: false,
  invertRoll: false,
  angleScale: 30,
  bodyScale: 10,
});

export function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v;
}

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
   */
  constructor(defaultNegativeRange, defaultPositiveRange, minRange = 5, adaptationRate = 0.1) {
    this.defaultNeg = defaultNegativeRange;
    this.defaultPos = defaultPositiveRange;
    this.minRange = minRange;
    this.rate = adaptationRate;
    this._pctNeg = 0;
    this._pctPos = 0;
    this._samples = 0;
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
    const alpha = this._samples < 100 ? 0.05 : 0.01;
    if (v < 0) {
      const a = -v;
      this._pctNeg += a > this._pctNeg ? (a - this._pctNeg) * this.rate : (a - this._pctNeg) * alpha * 0.1;
    } else {
      this._pctPos += v > this._pctPos ? (v - this._pctPos) * this.rate : (v - this._pctPos) * alpha * 0.1;
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

/** 协议 §8.2：低头 ~20°、抬头 ~40°。 */
export function createPitchNormalizer() {
  return new AdaptiveAngleNormalizer(20, 40, 10);
}
/** 协议 §8.2：左右各 45°。 */
export function createYawNormalizer() {
  return new AdaptiveAngleNormalizer(45, 45, 15);
}
/** 协议 §8.2：左右各 30°。 */
export function createRollNormalizer() {
  return new AdaptiveAngleNormalizer(30, 30, 10);
}

/**
 * 三个归一化器（pitch / yaw / roll）。
 * @returns {{pitch:AdaptiveAngleNormalizer,yaw:AdaptiveAngleNormalizer,roll:AdaptiveAngleNormalizer}}
 */
export function createNormalizers() {
  return { pitch: createPitchNormalizer(), yaw: createYawNormalizer(), roll: createRollNormalizer() };
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
 * @param {{invertPitch?:boolean,invertYaw?:boolean,invertRoll?:boolean,angleScale?:number,bodyScale?:number}} [opts]
 */
export function createMapper(opts = {}) {
  const o = { ...DEFAULT_OPTIONS, ...opts };
  const norms = createNormalizers();

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
