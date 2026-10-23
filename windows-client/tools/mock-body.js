/**
 * tools/mock-body.js — mock 手机端的「上半身关键点」合成器（纯函数，Node 可直接 import 单测）
 *
 * 产出协议 §4.2 / §5.3 规定的：
 *   - `ub`：13 点 × 4 个 float 的扁平数组（x, y, z, visibility），顺序 = 协议顺序下标；
 *   - `body`：[roll, yaw]（相对基线躯干角，度）；
 *   - `ubOk`：false 表示这一帧没有上半身结果（Android 端此时也发全 0 的 ub / body）。
 *
 * 用途：tools/mock-tracker.js 组装 track 报文；tools/selftest.js 直接单测它（不启服务）。
 *
 * 注意：这里**故意不复用** renderer/mapping.js 的 BODY_LANDMARK_ORDER —— mock 扮演手机端，
 * 两端共用常量会让「下标写错」在自测里互相抵消。selftest 里有专门的交叉校验用例。
 */

/** 协议顺序下标（与 CommonData.UpperBodyLandmarkIndex.ORDER 一致，由 selftest 交叉校验）。 */
const P = Object.freeze({
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

const COUNT = 13;

export const MOCK_BODY_DEFAULTS = Object.freeze({
  /** 躯干 roll / yaw 的摆动周期与幅度（秒 / 度）。 */
  rollPeriodSec: 7.3,
  rollAmpDeg: 6,
  yawPeriodSec: 11.7,
  yawAmpDeg: 9,
  /** 左右手抬起：同周期、相位错开，双手交替抬起放下。 */
  armPeriodSec: 5.2,
  armPhaseRight: 0.5,
  /** 手臂与竖直向下的夹角：静止 12°、抬起时再 +126°（约 138°，接近举过头顶）。 */
  armRestDeg: 12,
  armLiftDeg: 126,
  /** 肘屈曲（前臂相对上臂）：静止 8°、周期性弯起再 +95°（两侧同相，验证 ParamArmLB/RB）。 */
  elbowPeriodSec: 6.1,
  elbowPhaseRight: 0,
  elbowRestDeg: 8,
  elbowCurlDeg: 95,
  /** 耸肩差动（肩线相对髋线的倾斜）：周期性一侧抬肩一侧沉肩（验证 ParamShoulderL/R）。 */
  shrugPeriodSec: 8.3,
  shrugAmpDeg: 7,
  /** 每 9 秒丢 0.7 秒上半身（ubOk=false），用来验证客户端「平滑回默认」。0 = 不丢。 */
  dropPeriodSec: 9,
  dropLengthSec: 0.7,
  /** 关键点可见度基准（图像上半身点都很清楚）。 */
  visibility: 0.95,
  /** 骨架几何（归一化坐标；ub 已镜像：本人左 = 画面左）。 */
  noseY: 0.16,
  eyeY: 0.15,
  eyeDx: 0.04,
  earY: 0.17,
  earDx: 0.085,
  shoulderY: 0.4,
  shoulderHalfSpan: 0.15,
  upperArmLen: 0.17,
  foreArmLen: 0.17,
  hipY: 0.85,
  hipHalfSpan: 0.11,
});

const round4 = (value) => Math.round(value * 10000) / 10000;
const finiteOr = (value, fallback = 0) => {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
};

/** 肩 → 肘 → 腕：上臂从「竖直向下」转 raiseDeg（画面 y 向下为正，左臂往左抬）；
 *  前臂再相对上臂屈曲 curlDeg（+ = 屈肘，两侧镜像对称）。 */
function armChain(side, shoulder, raiseDeg, curlDeg, o) {
  const sideSign = side === "L" ? -1 : 1;
  const dir = (angleDeg) => {
    const radians = (angleDeg * Math.PI) / 180;
    return { x: sideSign * Math.sin(radians), y: Math.cos(radians) };
  };
  const upper = dir(raiseDeg);
  const fore = dir(raiseDeg + curlDeg);
  const elbow = { x: shoulder.x + o.upperArmLen * upper.x, y: shoulder.y + o.upperArmLen * upper.y };
  return {
    elbow,
    wrist: { x: elbow.x + o.foreArmLen * fore.x, y: elbow.y + o.foreArmLen * fore.y },
  };
}

/**
 * 合成一帧上半身。
 *
 * @param {number} t 帧时间（秒；mock 用「计划采集时间」，与 track.t 同一口径）
 * @param {Partial<typeof MOCK_BODY_DEFAULTS>} [options]
 * @returns {{ubOk:boolean, ub:number[], body:[number,number]}}
 */
export function buildUpperBody(t, options = {}) {
  const o = { ...MOCK_BODY_DEFAULTS, ...options };
  const time = Math.max(0, finiteOr(t));
  const dropPeriod = Math.max(0, finiteOr(o.dropPeriodSec));
  const dropLength = Math.max(0, finiteOr(o.dropLengthSec));
  const dropped = dropPeriod > 0 && dropLength > 0 && time % dropPeriod > dropPeriod - dropLength;
  if (dropped) {
    // 与 Android 端「没有结果」时的报文一致：全 0 占位 + ubOk=false，结构保持稳定
    return { ubOk: false, ub: new Array(COUNT * 4).fill(0), body: [0, 0] };
  }

  const roll = finiteOr(o.rollAmpDeg) * Math.sin((time / Math.max(0.1, finiteOr(o.rollPeriodSec, 7.3))) * 2 * Math.PI);
  const yaw =
    finiteOr(o.yawAmpDeg) *
    Math.sin((time / Math.max(0.1, finiteOr(o.yawPeriodSec, 11.7))) * 2 * Math.PI + 0.4);
  const armPhase = (time / Math.max(0.1, finiteOr(o.armPeriodSec, 5.2))) * 2 * Math.PI;
  const rest = finiteOr(o.armRestDeg);
  const lift = finiteOr(o.armLiftDeg);
  const raiseL = rest + lift * Math.max(0, Math.sin(armPhase));
  const raiseR = rest + lift * Math.max(0, Math.sin(armPhase + 2 * Math.PI * finiteOr(o.armPhaseRight, 0.5)));

  // 肘屈曲：前臂相对上臂周期性弯起（两侧同相，验证 ParamArmLB/RB 对称驱动）
  const elbowPhase = (time / Math.max(0.1, finiteOr(o.elbowPeriodSec, 6.1))) * 2 * Math.PI;
  const curlRest = finiteOr(o.elbowRestDeg);
  const curl = finiteOr(o.elbowCurlDeg);
  const curlL = curlRest + curl * Math.max(0, Math.sin(elbowPhase));
  const curlR = curlRest + curl * Math.max(0, Math.sin(elbowPhase + 2 * Math.PI * finiteOr(o.elbowPhaseRight, 0)));

  // roll > 0 = 右肩下沉：肩线绕画面前后倾斜（忽略宽高比，与映射侧一致）；
  // 髋线同倾**同样角度** → 骨架层的「锁骨差动」只剩耸肩分量（整体侧倾归 body roll → ParamBodyAngleZ）
  const sink = o.shoulderHalfSpan * Math.tan((roll * Math.PI) / 180);
  const sinkHip = o.hipHalfSpan * Math.tan((roll * Math.PI) / 180);
  // 耸肩差动：一侧抬肩、另一侧沉肩（肩线相对髋线的倾斜，验证 ParamShoulderL/R）
  const shrugDeg =
    finiteOr(o.shrugAmpDeg) * Math.sin((time / Math.max(0.1, finiteOr(o.shrugPeriodSec, 8.3))) * 2 * Math.PI);
  const shrug = o.shoulderHalfSpan * Math.tan((shrugDeg * Math.PI) / 180);
  const shoulderL = { x: 0.5 - o.shoulderHalfSpan, y: o.shoulderY - sink - shrug };
  const shoulderR = { x: 0.5 + o.shoulderHalfSpan, y: o.shoulderY + sink + shrug };
  const chainL = armChain("L", shoulderL, raiseL, curlL, o);
  const chainR = armChain("R", shoulderR, raiseR, curlR, o);

  const points = new Array(COUNT);
  points[P.nose] = { x: 0.5, y: o.noseY };
  points[P.eye_left] = { x: 0.5 - o.eyeDx, y: o.eyeY };
  points[P.eye_right] = { x: 0.5 + o.eyeDx, y: o.eyeY };
  points[P.ear_left] = { x: 0.5 - o.earDx, y: o.earY };
  points[P.ear_right] = { x: 0.5 + o.earDx, y: o.earY };
  points[P.shoulder_left] = shoulderL;
  points[P.shoulder_right] = shoulderR;
  points[P.elbow_left] = chainL.elbow;
  points[P.elbow_right] = chainR.elbow;
  points[P.wrist_left] = chainL.wrist;
  points[P.wrist_right] = chainR.wrist;
  points[P.hip_left] = { x: 0.5 - o.hipHalfSpan, y: o.hipY - sinkHip };
  points[P.hip_right] = { x: 0.5 + o.hipHalfSpan, y: o.hipY + sinkHip };

  const baseVisibility = Math.max(0, Math.min(1, finiteOr(o.visibility, 0.95)));
  const ub = new Array(COUNT * 4).fill(0);
  for (let i = 0; i < COUNT; i++) {
    const point = points[i];
    const offset = i * 4;
    // 面部点的可见度略低一点（MediaPipe 对耳朵通常更不确定），数值仍是确定的
    const visibility = baseVisibility - (i <= P.ear_right ? 0.05 : 0);
    ub[offset] = round4(Math.max(0, Math.min(1, point.x)));
    ub[offset + 1] = round4(Math.max(0, Math.min(1, point.y)));
    ub[offset + 2] = 0;
    ub[offset + 3] = round4(Math.max(0, Math.min(1, visibility)));
  }

  return { ubOk: true, ub, body: [round4(roll), round4(yaw)] };
}
