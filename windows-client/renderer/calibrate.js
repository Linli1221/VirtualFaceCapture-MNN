/**
 * calibrate.js — PC 端「姿态校准」：把「镜头没正对你」造成的固定头姿偏移归零。
 *
 * 背景：
 *   手机端已经做了首帧中性脸基线（pose ≈ 0 = 手机开始面捕时那个坐姿），但当手机
 *   不在正前方（斜着架、放在显示器旁边、换了座位 / 换了拍摄距离），PC 收到的
 *   `track.pose` 仍然会带一个**固定的** pitch / yaw / roll 偏移。这个偏移会一路传到
 *   Live2D 的 ParamAngleX/Y/Z，表现就是「模型脑袋一直朝一个方向歪 / 偏」，在手机上
 *   点「重置基线」也只能把手机那端再归零一次，坐回去之后偏移又回来了。
 *
 * 做法：
 *   在映射之前叠加一个本地偏移（不改协议、不要求手机配合）：
 *
 *       最终角度 = track.pose + pcOffset
 *
 *   **符号约定（严禁改变）**：pitch 上正下负、yaw 本人左转为正、roll 本人左倾为正。
 *   **基线语义（严禁重复减）**：`track.pose` 是手机端已经减过中性基线的值（手机首 1s
 *   基线 / extractor.calibrate / BlinkCurveProcessor 都在手机端，本模块不迁移、也不
 *   重新实现）。`pcOffset` 与手机基线是**叠加**关系：PC 端只做加法，绝不再减一次基线，
 *   也绝不要求手机发送 poseRaw / 478 点 / 关键点。
 *
 *   offset 有两种来源：
 *     1. 一键校准：保持当前坐姿几秒，把这段时间收到的 pose 取平均后取反
 *        （平均值 = 当前朝向，取反 = 把它当成新的零点）；
 *     2. 手动微调：左右 / 上下 / 歪头三个滑块直接改 offset。
 *
 *   offset 存在 localStorage，下次打开客户端继续生效；换座位 / 重新架手机后
 *   重新点一次「一键校准」即可。
 *
 * 这个模块是纯函数 / 纯数据（不碰 DOM），Node 端可以直接 import 做数值单测。
 */

/** 各轴偏移的绝对值上限（度）。超出这个范围基本不是「镜头偏移」，而是姿态解算坏了。 */
export const POSE_OFFSET_LIMIT_DEG = 45;

/** 手动微调滑块的步进（度）。 */
export const POSE_OFFSET_STEP_DEG = 0.5;

/** 偏移持久化用的 localStorage key（由 app.js 读写）。 */
export const POSE_OFFSET_STORAGE_KEY = "vfc.poseOffset";

/** 一键校准的默认采样时长（ms）：够 30~60 fps 手机取到几十帧，又不至于让人等太久。 */
export const ALIGN_DURATION_MS = 3000;

/** 头在抖 / 在动时给出提醒的阈值：p90 − p10 超过这个值（度）就提示「刚才头在动」。 */
export const POSE_OFFSET_STABLE_SPREAD_DEG = 6;

/** 中性偏移：三轴都是 0。 */
export const DEFAULT_POSE_OFFSET = Object.freeze({ pitch: 0, yaw: 0, roll: 0 });

/** 只保留有限数字，其余按 0 处理（坏数据不能把渲染路径带崩）。 */
export function toFiniteNumber(value, fallback = 0) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

/** 单轴偏移截断到 ±limit。 */
export function clampOffset(value, limit = POSE_OFFSET_LIMIT_DEG) {
  return Math.max(-limit, Math.min(limit, toFiniteNumber(value, 0)));
}

/**
 * 把任意输入整理成合法的 offset 对象。
 *
 * 接受 `{pitch,yaw,roll}`、`[pitch,yaw,roll]` 或 null / 坏数据；缺字段按 0，
 * 非有限数字按 0，超出 ±45° 截断。返回值永远是新的普通对象（可以直接 JSON）。
 */
export function sanitizePoseOffset(offset) {
  const source = offset && typeof offset === "object" ? offset : {};
  const at = (index) => (Array.isArray(source) || ArrayBuffer.isView(source) ? source[index] : undefined);
  return {
    pitch: clampOffset(source.pitch ?? at(0)),
    yaw: clampOffset(source.yaw ?? at(1)),
    roll: clampOffset(source.roll ?? at(2)),
  };
}

/** 三轴都接近 0（用于 UI 显示「未校准」）。 */
export function isNeutralOffset(offset, epsilon = 0.05) {
  const o = sanitizePoseOffset(offset);
  return Math.abs(o.pitch) <= epsilon && Math.abs(o.yaw) <= epsilon && Math.abs(o.roll) <= epsilon;
}

/**
 * 在收到的 pose 上叠加 offset，返回新的 `[pitch, yaw, roll]`。
 *
 * 输入可以是数组 / 类数组 / undefined；非有限数字按 0，返回的一定是长度 3 的数组，
 * 所以上层（mapping.js）不需要再做防御。
 */
export function applyPoseOffset(pose, offset) {
  const source = pose && (Array.isArray(pose) || ArrayBuffer.isView(pose)) ? pose : [];
  const o = sanitizePoseOffset(offset);
  return [
    toFiniteNumber(source[0], 0) + o.pitch,
    toFiniteNumber(source[1], 0) + o.yaw,
    toFiniteNumber(source[2], 0) + o.roll,
  ];
}

/** 一行文字形式的偏移（调试面板 / 提示用），例如 `+2.5° / -8.0° / 0.0°`。 */
export function formatPoseOffset(offset) {
  const o = sanitizePoseOffset(offset);
  const sign = (value) => (value > 0 ? "+" : "") + value.toFixed(1) + "°";
  return `${sign(o.pitch)} / ${sign(o.yaw)} / ${sign(o.roll)}`;
}

/** 序列化（存 localStorage）。 */
export function encodePoseOffset(offset) {
  return JSON.stringify(sanitizePoseOffset(offset));
}

/**
 * 反序列化（读 localStorage）。
 *
 * 坏 JSON / 旧版本 / 手改过的值都回落到默认 0，不抛异常：
 * 一个坏掉的本地配置不应该让客户端起不来。
 */
export function decodePoseOffset(raw) {
  if (raw == null || raw === "") return { ...DEFAULT_POSE_OFFSET };
  if (typeof raw === "object") return sanitizePoseOffset(raw);
  try {
    return sanitizePoseOffset(JSON.parse(String(raw)));
  } catch {
    return { ...DEFAULT_POSE_OFFSET };
  }
}

/**
 * 一键校准的采样器：喂给它一段时间的 track，它算出「把当前朝向当成零点」的 offset。
 *
 * 设计要点：
 *   - 只吃 `face !== false` 且 pose 是有限数字的帧（模型没看到脸时 pose 会保留上一帧或 0，
 *     混进来会把平均值带偏）；
 *   - 取**截尾平均**（两端各丢 10% 极值）再取反：偶发一两帧 landmark 抖到 ±20° 不会把
 *     整个零点带歪；
 *   - 同时算 `spread`（三轴里最大的 p90 − p10）：人没坐住（刚才在动）时 UI 能给出提醒；
 *   - 有上限 maxSamples，内存恒定（一帧 3 个数字）。
 */
export class PoseOffsetCollector {
  /**
   * @param {{minSamples?:number,maxSamples?:number,outlierFraction?:number}} [options]
   *   minSamples      = 至少多少帧才算校准有效（低于它说明没收到脸 / 刚连上）；
   *   maxSamples      = 最多保留多少帧（超出丢最旧，长时间按着不点也能用）；
   *   outlierFraction = 两端各丢多少比例做截尾平均（0 ~ 0.4）。
   */
  constructor(options = {}) {
    this.minSamples = Math.max(1, Math.round(toFiniteNumber(options.minSamples, 12)));
    this.maxSamples = Math.max(this.minSamples, Math.round(toFiniteNumber(options.maxSamples, 900)));
    this.outlierFraction = Math.max(0, Math.min(0.4, toFiniteNumber(options.outlierFraction, 0.1)));
    this.samples = [];
    this.rejectedFrames = 0;
  }

  /** 已采集的有效帧数。 */
  get count() {
    return this.samples.length;
  }

  /** 是否已经够 minSamples 帧。 */
  get ready() {
    return this.count >= this.minSamples;
  }

  /** 清空（取消 / 重来）。 */
  reset() {
    this.samples.length = 0;
    this.rejectedFrames = 0;
  }

  /**
   * 喂一帧 `track`（协议 §4.2）。
   * @returns {boolean} 是否作为有效样本收下
   */
  addTrack(track) {
    if (!track || typeof track !== "object") return false;
    if (track.face === false) {
      this.rejectedFrames += 1;
      return false;
    }
    return this.addPose(track.pose);
  }

  /** 直接喂 `[pitch, yaw, roll]`（度）。 */
  addPose(pose) {
    const source = pose && (Array.isArray(pose) || ArrayBuffer.isView(pose)) ? pose : null;
    if (!source || source.length < 3) {
      this.rejectedFrames += 1;
      return false;
    }
    const pitch = Number(source[0]);
    const yaw = Number(source[1]);
    const roll = Number(source[2]);
    if (!Number.isFinite(pitch) || !Number.isFinite(yaw) || !Number.isFinite(roll)) {
      this.rejectedFrames += 1;
      return false;
    }
    // 人头到不了 ±100°：解算坏帧（1e6、整型溢出等）直接丢，别让一个异常值带偏均值。
    if (Math.abs(pitch) > 100 || Math.abs(yaw) > 100 || Math.abs(roll) > 100) {
      this.rejectedFrames += 1;
      return false;
    }
    this.samples.push([pitch, yaw, roll]);
    if (this.samples.length > this.maxSamples) this.samples.shift();
    return true;
  }

  /** 三轴平均值（未截尾，调试用；真正落 offset 用 computeOffset）。 */
  mean() {
    const result = { pitch: 0, yaw: 0, roll: 0 };
    if (this.count === 0) return result;
    for (const [pitch, yaw, roll] of this.samples) {
      result.pitch += pitch;
      result.yaw += yaw;
      result.roll += roll;
    }
    result.pitch /= this.count;
    result.yaw /= this.count;
    result.roll /= this.count;
    return result;
  }

  /**
   * 截尾平均的均值（每轴排序后两端各丢 outlierFraction）。
   * @returns {{pitch:number,yaw:number,roll:number}}
   */
  trimmedMean() {
    const result = { pitch: 0, yaw: 0, roll: 0 };
    const n = this.count;
    if (n === 0) return result;
    const columns = [0, 1, 2].map((axis) => this.samples.map((sample) => sample[axis]).sort((a, b) => a - b));
    const trim = Math.min(
      Math.floor(n * this.outlierFraction),
      Math.max(0, Math.floor((n - 1) / 2)),
    );
    const kept = n - trim * 2;
    for (let axis = 0; axis < 3; axis++) {
      let sum = 0;
      for (let i = trim; i < n - trim; i++) sum += columns[axis][i];
      result[["pitch", "yaw", "roll"][axis]] = sum / Math.max(1, kept);
    }
    return result;
  }

  /** 三轴里最大的 p90 − p10（度）：越小说明采样期间头越稳。 */
  spread() {
    const n = this.count;
    if (n < 2) return 0;
    const quantile = (values, p) => {
      const sorted = values.slice().sort((a, b) => a - b);
      const index = Math.min(sorted.length - 1, Math.max(0, Math.floor((sorted.length - 1) * p)));
      return sorted[index];
    };
    let worst = 0;
    for (let axis = 0; axis < 3; axis++) {
      const values = this.samples.map((sample) => sample[axis]);
      worst = Math.max(worst, quantile(values, 0.9) - quantile(values, 0.1));
    }
    return worst;
  }

  /**
   * 当前样本对应的 offset：`offset = -trimmedMean`（截断到 ±45°）。
   * 帧数不够时仍返回可用的值（调用方用 report().ready 判断是否落盘）。
   */
  computeOffset() {
    const mean = this.trimmedMean();
    return sanitizePoseOffset({ pitch: -mean.pitch, yaw: -mean.yaw, roll: -mean.roll });
  }

  /** 一次性报告（UI / 测试用）。 */
  report() {
    return {
      samples: this.count,
      rejected: this.rejectedFrames,
      ready: this.ready,
      stable: this.count >= 2 && this.spread() <= POSE_OFFSET_STABLE_SPREAD_DEG,
      spread: Number(this.spread().toFixed(2)),
      mean: {
        pitch: Number(this.trimmedMean().pitch.toFixed(3)),
        yaw: Number(this.trimmedMean().yaw.toFixed(3)),
        roll: Number(this.trimmedMean().roll.toFixed(3)),
      },
      offset: this.computeOffset(),
    };
  }
}

// ================================================================
// 角色 profile（按角色保存偏移）+ 平滑过渡 + 可选缓慢自适应
// ================================================================

/**
 * 角色 profile 持久化 key（由 app.js 读写）。
 *
 * 存储结构（JSON）：
 *   { "version": 1,
 *     "profiles": {
 *       "<模型名>": {
 *         "pitchOffset": 0, "yawOffset": 0, "rollOffset": 0,   // 三轴偏移（度）
 *         "rangeScale": 1                                       // 可选：量程偏好（0.5~2.0）
 *       } } }
 *
 * 与旧版全局 `POSE_OFFSET_STORAGE_KEY`（vfc.poseOffset）的关系：
 *   - 新代码以 profile store 为准，按**当前角色**读写；
 *   - 每次保存时同步镜像一份到 vfc.poseOffset（最后生效的偏移），老客户端 / 老版本
 *     只认这个 key 也不受影响；
 *   - 首次迁移：某角色没有 profile 时，用 vfc.poseOffset 的值播种，行为与旧版一致。
 */
export const POSE_PROFILE_STORAGE_KEY = "vfc.poseProfiles";

/** 缓慢自适应偏移的总开关 key（默认关闭；值为 "1" 才开启）。 */
export const POSE_ADAPTIVE_STORAGE_KEY = "vfc.poseAdaptive";

/** 量程偏好的上下限（乘在映射归一化的默认量程上；1 = 与现在完全一致）。 */
export const RANGE_SCALE_MIN = 0.5;
export const RANGE_SCALE_MAX = 2;
export const DEFAULT_RANGE_SCALE = 1;

/** 中性角色 profile：三轴偏移 0、量程偏好 1。 */
export const DEFAULT_POSE_PROFILE = Object.freeze({
  pitchOffset: 0,
  yawOffset: 0,
  rollOffset: 0,
  rangeScale: DEFAULT_RANGE_SCALE,
});

/** 量程偏好：有限数字，截断到 [0.5, 2]，坏数据回落 1。 */
export function sanitizeRangeScale(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return DEFAULT_RANGE_SCALE;
  return Math.max(RANGE_SCALE_MIN, Math.min(RANGE_SCALE_MAX, n));
}

/**
 * 把任意输入整理成合法的角色 profile：
 * `{ pitchOffset, yawOffset, rollOffset, rangeScale }`。
 * 也兼容直接传 `{pitch,yaw,roll}`（自动映射到 *Offset 字段）。
 */
export function sanitizePoseProfile(profile) {
  const source = profile && typeof profile === "object" ? profile : {};
  const offset = sanitizePoseOffset({
    pitch: source.pitchOffset ?? source.pitch,
    yaw: source.yawOffset ?? source.yaw,
    roll: source.rollOffset ?? source.roll,
  });
  return {
    pitchOffset: offset.pitch,
    yawOffset: offset.yaw,
    rollOffset: offset.roll,
    rangeScale: sanitizeRangeScale(source.rangeScale),
  };
}

/** profile → offset 对象（feedTrack 用的 {pitch,yaw,roll}）。 */
export function profileToOffset(profile) {
  const p = sanitizePoseProfile(profile);
  return { pitch: p.pitchOffset, yaw: p.yawOffset, roll: p.rollOffset };
}

/** offset 对象 → profile（保留原量程偏好）。 */
export function offsetToProfile(offset, rangeScale = DEFAULT_RANGE_SCALE) {
  const o = sanitizePoseOffset(offset);
  return {
    pitchOffset: o.pitch,
    yawOffset: o.yaw,
    rollOffset: o.roll,
    rangeScale: sanitizeRangeScale(rangeScale),
  };
}

/** 角色名 → profile key（大小写不敏感，与 findModel 一致）。 */
export function profileKeyFor(modelName) {
  const name = String(modelName ?? "").trim();
  return name ? name.toLowerCase() : "__default__";
}

/**
 * 反序列化 profile store。坏 JSON / 旧版本 / 手改值都回落到空 store，不抛异常。
 * 兼容三种历史形态：空、`{profiles:{...}}`、直接 `{ "<模型名>": {...} }`。
 */
export function decodePoseProfileStore(raw) {
  const empty = { version: 1, profiles: {} };
  if (raw == null || raw === "") return empty;
  let data = raw;
  if (typeof data === "string") {
    try {
      data = JSON.parse(data);
    } catch {
      return empty;
    }
  }
  if (!data || typeof data !== "object") return empty;
  const table = data.profiles && typeof data.profiles === "object" ? data.profiles : data;
  const profiles = {};
  for (const [key, value] of Object.entries(table)) {
    if (key === "version" || key === "profiles") continue;
    if (!value || typeof value !== "object") continue;
    profiles[String(key)] = sanitizePoseProfile(value);
  }
  return { version: 1, profiles };
}

/** 序列化 profile store。 */
export function encodePoseProfileStore(store) {
  const decoded = decodePoseProfileStore(store);
  return JSON.stringify(decoded);
}

/** 读某个角色的 profile（没有则返回中性 profile，不修改 store）。 */
export function getProfileFor(store, modelName) {
  const table = decodePoseProfileStore(store).profiles;
  const hit = table[profileKeyFor(modelName)];
  return hit ? { ...hit } : { ...DEFAULT_POSE_PROFILE };
}

/** 写某个角色的 profile（返回新的 store 对象，调用方负责持久化）。 */
export function setProfileFor(store, modelName, profile) {
  const decoded = decodePoseProfileStore(store);
  decoded.profiles[profileKeyFor(modelName)] = sanitizePoseProfile(profile);
  return decoded;
}

/**
 * 偏移平滑过渡器：把「应用中的偏移」缓慢趋近「目标偏移」，避免重置 / 换零点时模型瞬移。
 *
 * 语义：
 *   - `setTarget(offset, {snap:true})` 立即生效（滑块、程序化设置走这条，行为与旧版一致）；
 *   - `setTarget(offset)`（手动重置 / 手机重做基线）按 `maxSlewDegPerSec` 限速逼近，
 *     每轴独立、同时到位，过渡期间不改变符号与叠加公式；
 *   - `sample(dtMs)` 由每帧调用，返回当前应使用的 offset。
 */
export class OffsetRamp {
  /** @param {{maxSlewDegPerSec?:number,epsilon?:number}} [options] */
  constructor(options = {}) {
    this.maxSlew = Math.max(1, toFiniteNumber(options.maxSlewDegPerSec, 25));
    this.epsilon = Math.max(1e-4, toFiniteNumber(options.epsilon, 0.01));
    this.current = { ...DEFAULT_POSE_OFFSET };
    this.target = { ...DEFAULT_POSE_OFFSET };
  }

  /** 设置目标偏移。snap=true 立即跳到目标（旧版行为）；否则限速逼近。 */
  setTarget(offset, { snap = false } = {}) {
    this.target = sanitizePoseOffset(offset);
    if (snap) this.current = { ...this.target };
    return this.snapshot();
  }

  /** 是否已经贴上目标（用于判断要不要继续走帧循环 / 持久化）。 */
  get settled() {
    for (const axis of ["pitch", "yaw", "roll"]) {
      if (Math.abs(this.target[axis] - this.current[axis]) > this.epsilon) return false;
    }
    return true;
  }

  /**
   * 推进 dtMs 毫秒，返回当前应用中的 offset（新对象）。
   * 每轴步进 ≤ maxSlew × dt，剩余距离小于一步时直接贴上，绝不超调。
   */
  sample(dtMs) {
    const dt = Math.max(0, toFiniteNumber(dtMs, 0)) / 1000;
    const step = this.maxSlew * dt;
    for (const axis of ["pitch", "yaw", "roll"]) {
      const diff = this.target[axis] - this.current[axis];
      const move = Math.abs(diff) <= Math.max(step, this.epsilon) ? diff : Math.sign(diff) * step;
      this.current[axis] = clampOffset(this.current[axis] + move);
    }
    return this.snapshot();
  }

  snapshot() {
    return { ...this.current };
  }
}

/**
 * 可选的「缓慢自适应偏移」（默认关闭）。
 *
 * 目标：人坐久了中性姿态会慢慢漂（靠背、换重心），固定 offset 会显得越来越歪。
 * 做法：**只在低运动、低表情变化时**，把 offset 以每帧 `rate`（0.001~0.005）的
 * 速度朝「让最终角度归零」的方向挪一点点：
 *
 *     offset ← offset − 最终角度 × rate      （最终角度 = track.pose + offset）
 *
 * 防污染（验收：说话 / 眨眼不许碰 offset）：
 *   - 表情门：相邻两帧 bs[52] 的最大绝对变化 > 表情阈值 → 说话 / 眨眼 / 挑眉，直接跳过；
 *     这里只用「整段 bs 向量的变化量」做门限，**不从 bs[52] 反推**任何嘴 / 眼 / 微笑特征；
 *   - 运动门：相邻两帧 pose 任一轴变化 > 运动阈值 → 头在动，直接跳过；
 *   - 需要连续 `quietFrames` 帧都安静才开始更新，偶发一两帧安静（说话间隙）不足以触发；
 *   - face === false / 坏数据一律跳过；
 *   - 自适应能挪动的范围锚定在「手动 / 一键校准」给出的 anchor ± maxDriftDeg 之内，
 *     不会无限跑飞；rate 极小（默认 0.002/帧，30fps 下时间常数约 17s）。
 *
 * 默认 disabled=true 时 update() 恒不改 offset，行为与没有这个模块完全一致。
 */
export class AdaptivePoseOffset {
  /**
   * @param {{rate?:number,motionLimitDeg?:number,expressionLimit?:number,
   *          quietFrames?:number,maxDriftDeg?:number}} [options]
   *   rate           = 每帧更新系数，合法区间 [0.001, 0.005]，默认 0.002；
   *   motionLimitDeg = 相邻帧单轴 pose 变化超过它算「在动」（度，默认 1.5）；
   *   expressionLimit= 相邻帧 bs 最大绝对变化超过它算「表情在变」（默认 0.06）；
   *   quietFrames    = 连续安静多少帧才开始更新（默认 15）；
   *   maxDriftDeg    = 相对 anchor 允许漂移的上限（度，默认 10）。
   */
  constructor(options = {}) {
    const rate = toFiniteNumber(options.rate, 0.002);
    this.rate = Math.max(0.001, Math.min(0.005, rate));
    this.motionLimitDeg = Math.max(0.05, toFiniteNumber(options.motionLimitDeg, 1.5));
    this.expressionLimit = Math.max(0.005, toFiniteNumber(options.expressionLimit, 0.06));
    this.quietFrames = Math.max(1, Math.round(toFiniteNumber(options.quietFrames, 15)));
    this.maxDriftDeg = Math.max(0.5, toFiniteNumber(options.maxDriftDeg, 10));
    /** 总开关：默认关闭（默认行为 = 现状）。 */
    this.enabled = false;
    this.anchor = { ...DEFAULT_POSE_OFFSET };
    this.prevPose = null;
    this.prevBs = null;
    this.quietStreak = 0;
    this.stats = { updates: 0, skippedMotion: 0, skippedExpression: 0, skippedFace: 0, skippedData: 0 };
  }

  /** 手动 / 一键校准落了新偏移：以它为锚点重新允许 ±maxDriftDeg 的缓慢漂移。 */
  setAnchor(offset) {
    this.anchor = sanitizePoseOffset(offset);
  }

  /** 换人 / 重连 / 手机重做基线：丢掉历史帧，避免用旧姿态污染新会话。 */
  reset() {
    this.prevPose = null;
    this.prevBs = null;
    this.quietStreak = 0;
  }

  /**
   * 喂一帧 `track`（协议 §4.2），必要时返回缓慢修正后的 offset。
   * @param {object} track 需要 `pose`（[pitch,yaw,roll]）与 `bs`（52 项）
   * @param {{pitch:number,yaw:number,roll:number}} offset 当前（目标）偏移
   * @returns {{changed:boolean, offset:object, reason:string}}
   *   changed=true 时 offset 是修正后的新偏移（已按 anchor ± maxDriftDeg 截断）。
   */
  update(track, offset) {
    const current = sanitizePoseOffset(offset);
    const safeReturn = (reason) => ({ changed: false, offset: current, reason });
    if (!this.enabled) return safeReturn("disabled");

    const pose = track && (Array.isArray(track.pose) || ArrayBuffer.isView(track.pose)) ? track.pose : null;
    const bs = track && (Array.isArray(track.bs) || ArrayBuffer.isView(track.bs)) ? track.bs : null;
    if (!pose || pose.length < 3 || !bs) {
      this.stats.skippedData += 1;
      this.reset();
      return safeReturn("bad-data");
    }
    const p = [Number(pose[0]), Number(pose[1]), Number(pose[2])];
    if (!p.every((v) => Number.isFinite(v) && Math.abs(v) <= 100)) {
      this.stats.skippedData += 1;
      this.reset();
      return safeReturn("bad-data");
    }
    if (track.face === false) {
      this.stats.skippedFace += 1;
      this.reset();
      return safeReturn("face-lost");
    }

    const prevPose = this.prevPose;
    const prevBs = this.prevBs;
    this.prevPose = p;
    this.prevBs = Float64Array.from(bs, (v) => (Number.isFinite(Number(v)) ? Number(v) : 0));
    if (!prevPose || !prevBs) return safeReturn("warming-up");

    // 表情门：只看整段 bs 向量的变化量，不反推任何具体特征。
    let exprDelta = 0;
    const n = Math.min(prevBs.length, this.prevBs.length);
    for (let i = 0; i < n; i++) {
      const d = Math.abs(this.prevBs[i] - prevBs[i]);
      if (d > exprDelta) exprDelta = d;
    }
    if (exprDelta > this.expressionLimit) {
      this.stats.skippedExpression += 1;
      this.quietStreak = 0;
      return safeReturn("expression-active");
    }

    // 运动门：相邻帧单轴 pose 变化过大 = 在动。
    const moving = p.some((v, i) => Math.abs(v - prevPose[i]) > this.motionLimitDeg);
    if (moving) {
      this.stats.skippedMotion += 1;
      this.quietStreak = 0;
      return safeReturn("motion-active");
    }

    this.quietStreak += 1;
    if (this.quietStreak < this.quietFrames) return safeReturn("quiet-warming");

    // 缓慢归零：offset ← offset − 最终角度 × rate，且不越过 anchor ± maxDriftDeg。
    const next = { ...current };
    const axes = ["pitch", "yaw", "roll"];
    for (let i = 0; i < 3; i++) {
      const axis = axes[i];
      const finalAngle = p[i] + current[axis];
      const candidate = current[axis] - finalAngle * this.rate;
      const lo = this.anchor[axis] - this.maxDriftDeg;
      const hi = this.anchor[axis] + this.maxDriftDeg;
      next[axis] = clampOffset(Math.max(lo, Math.min(hi, candidate)));
    }
    this.stats.updates += 1;
    return { changed: true, offset: next, reason: "adapted" };
  }
}
