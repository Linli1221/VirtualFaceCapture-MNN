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
 *       pose_calibrated = pose_received + offset
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
