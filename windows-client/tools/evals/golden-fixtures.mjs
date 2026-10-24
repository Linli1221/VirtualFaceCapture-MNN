/**
 * golden-fixtures.mjs — 合成 478 点人脸 fixture 生成器
 *
 * 用途：黄金对齐评测（golden-run.mjs）与 node 单测共用。
 *
 * 设计原则：
 *  - 生成的点位是**可控的合成几何**，不是真人数据，因此每个量（EAR / MAR /
 *    smileFeature）都能被解析地算出来，便于断言；
 *  - 坐标为归一化 [0,1]，y 向下（与 MediaPipe 一致），z 为相对深度；
 *  - 所有输出都是 Float32Array(478*3) 交错 x/y/z，符合 blendshape-extractor 的入参。
 *
 * 关键几何（neutral 状态）：
 *   faceWidth  = dist(33, 263) = 0.28
 *   faceHeight = dist(168, 152) = 0.30
 *   EAR(睁眼)  = 0.018 / 0.10 = 0.18   (= DEFAULT_EYE_OPEN_RATIO)
 *   EAR(闭眼)  = 0.010 / 0.10 = 0.10   (= DEFAULT_EYE_CLOSED_RATIO)
 *   MAR(闭嘴)  = 0.016 / 0.16 = 0.10   (= DEFAULT_MOUTH_CLOSED_RATIO)
 *   MAR(张大)  = 0.072 / 0.16 = 0.45   (= DEFAULT_MOUTH_OPEN_RATIO)
 */

/** 协议/点位常量（与 FaceLandmarkIndices.kt 一致，仅取 fixture 需要的）。 */
export const IDX = Object.freeze({
  NOSE_TIP: 1,
  NOSE_ROOT: 168,
  FACE_OVAL_BOTTOM: 152,
  JAW_CHIN_CENTER: 152,
  IMG_R_EYE_OUTER: 33,
  IMG_R_EYE_INNER: 133,
  IMG_L_EYE_OUTER: 263,
  IMG_L_EYE_INNER: 362,
  IMG_R_IRIS: 468,
  IMG_L_IRIS: 473,
  MOUTH_IMG_L_CORNER: 61,
  MOUTH_IMG_R_CORNER: 291,
  UPPER_LIP_TOP: 0,
  UPPER_LIP_CENTER: 13,
  LOWER_LIP_BOTTOM: 17,
  LOWER_LIP_CENTER: 14,
});

export const IMG_R_EYE_UPPER = [246, 161, 160, 159, 158, 157, 173];
export const IMG_R_EYE_LOWER = [7, 163, 144, 145, 153, 154, 155];
export const IMG_L_EYE_UPPER = [466, 388, 387, 386, 385, 384, 398];
export const IMG_L_EYE_LOWER = [249, 390, 373, 374, 380, 381, 382];

export const MOUTH_INNER = [78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95];
export const MOUTH_OUTER = [61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185];

export const POINT_COUNT = 478;
export const STRIDE = 3;

/** 眼睛几何：水平宽度、睁眼高度、闭眼高度。 */
export const EYE = Object.freeze({ width: 0.10, openHeight: 0.018, closedHeight: 0.010 });

/** 嘴巴几何：宽度、闭嘴高度、张大高度。 */
export const MOUTH = Object.freeze({ width: 0.16, closedHeight: 0.016, wideOpenHeight: 0.072 });

/**
 * 生成一张中性人脸（睁眼、闭嘴、不笑）。
 * @returns {Float32Array} 478*3 交错点位
 */
export function makeNeutralFace() {
  const lm = new Float32Array(POINT_COUNT * STRIDE);
  // 所有点先铺一个默认位置，避免任何 NaN / 未初始化
  for (let i = 0; i < POINT_COUNT; i++) {
    lm[i * 3] = 0.5;
    lm[i * 3 + 1] = 0.5;
    lm[i * 3 + 2] = 0;
  }

  set(lm, IDX.NOSE_ROOT, 0.5, 0.45, -0.02);
  set(lm, IDX.NOSE_TIP, 0.5, 0.52, -0.05);
  set(lm, IDX.FACE_OVAL_BOTTOM, 0.5, 0.75, 0);
  set(lm, IDX.JAW_CHIN_CENTER, 0.5, 0.75, 0);

  // 眼睛（睁眼）
  applyEyeOpen(lm, EYE.openHeight, EYE.openHeight);

  // 嘴巴（闭嘴）
  applyMouthOpen(lm, 0);
  applySmile(lm, 0);

  // 眉毛（advancedMode 用，这里给一个稳定位置）
  set(lm, 105, 0.40, 0.355, 0); set(lm, 107, 0.44, 0.35, 0); set(lm, 46, 0.33, 0.35, 0);
  set(lm, 334, 0.60, 0.355, 0); set(lm, 336, 0.56, 0.35, 0); set(lm, 276, 0.67, 0.35, 0);

  // 脸颊 / 鼻翼 / 下巴 / 酒窝（advancedMode 用）
  for (const i of [36, 205, 206, 187, 123]) set(lm, i, 0.36, 0.55, 0);
  for (const i of [266, 425, 426, 411, 352]) set(lm, i, 0.64, 0.55, 0);
  set(lm, 98, 0.47, 0.53, 0);
  set(lm, 327, 0.53, 0.53, 0);
  set(lm, 117, 0.40, 0.47, 0); set(lm, 111, 0.40, 0.445, 0);
  set(lm, 346, 0.60, 0.47, 0); set(lm, 340, 0.60, 0.445, 0);
  set(lm, 206, 0.41, 0.63, 0); set(lm, 426, 0.59, 0.63, 0);
  for (const i of [49, 129, 102, 48]) set(lm, i, 0.475, 0.525, -0.04);
  for (const i of [279, 358, 331, 278]) set(lm, i, 0.525, 0.525, -0.04);
  for (const i of [40, 39, 37]) set(lm, i, 0.47, 0.615, 0);
  for (const i of [270, 269, 267]) set(lm, i, 0.53, 0.615, 0);
  for (const i of [84, 181, 91]) set(lm, i, 0.47, 0.645, 0);
  for (const i of [314, 405, 321]) set(lm, i, 0.53, 0.645, 0);

  return lm;
}

function set(lm, i, x, y, z) { lm[i * 3] = x; lm[i * 3 + 1] = y; lm[i * 3 + 2] = z; }

/**
 * 设置眼睛开合高度（像素/归一化单位下的眼睑垂直距离）。
 * 上眼睑在中心上方 h/2，下眼睑在中心下方 h/2，逐点 x 对齐 —— 因此
 * EAR = h / EYE.width 可以解析算出。
 */
export function applyEyeOpen(lm, leftHeight, rightHeight) {
  placeEye(lm, IMG_R_EYE_UPPER, IMG_R_EYE_LOWER, IDX.IMG_R_EYE_OUTER, IDX.IMG_R_EYE_INNER, IDX.IMG_R_IRIS, rightHeight);
  placeEye(lm, IMG_L_EYE_UPPER, IMG_L_EYE_LOWER, IDX.IMG_L_EYE_OUTER, IDX.IMG_L_EYE_INNER, IDX.IMG_L_IRIS, leftHeight);
}

function placeEye(lm, upper, lower, outerIdx, innerIdx, irisIdx, height) {
  const xOuter = outerIdx === IDX.IMG_R_EYE_OUTER ? 0.36 : 0.64;
  const xInner = innerIdx === IDX.IMG_R_EYE_INNER ? 0.46 : 0.54;
  const cy = 0.42;
  set(lm, outerIdx, xOuter, cy, 0);
  set(lm, innerIdx, xInner, cy, 0);
  const n = Math.min(upper.length, lower.length);
  for (let i = 0; i < upper.length; i++) {
    const x = xOuter + (xInner - xOuter) * (i + 1) / (upper.length + 1);
    set(lm, upper[i], x, cy - height / 2, 0);
  }
  for (let i = 0; i < lower.length; i++) {
    const x = xOuter + (xInner - xOuter) * (i + 1) / (lower.length + 1);
    set(lm, lower[i], x, cy + height / 2, 0);
  }
  // 虹膜居中 -> eyeLook* 全 0
  set(lm, irisIdx, (xOuter + xInner) / 2, cy, 0);
}

/**
 * 设置嘴巴开合高度（上下唇内缘的垂直距离）。
 * @param {number} openAmount 0 = 闭嘴（0.016），1 = 张大（0.072）
 */
export function applyMouthOpen(lm, openAmount) {
  const h = MOUTH.closedHeight + (MOUTH.wideOpenHeight - MOUTH.closedHeight) * openAmount;
  const cy = 0.63;
  const halfW = MOUTH.width / 2;
  set(lm, MOUTH_INNER[0], 0.5 - halfW, cy, 0);
  set(lm, MOUTH_INNER[10], 0.5 + halfW, cy, 0);
  set(lm, MOUTH_INNER[5], 0.5, cy - h / 2, 0);
  set(lm, MOUTH_INNER[15], 0.5, cy + h / 2, 0);

  set(lm, MOUTH_OUTER[0], 0.5 - halfW, cy, 0);
  set(lm, MOUTH_OUTER[10], 0.5 + halfW, cy, 0);
  set(lm, MOUTH_OUTER[15], 0.5, cy - h / 2 - 0.015, 0);
  set(lm, MOUTH_OUTER[5], 0.5, cy + h / 2 + 0.015, 0);
  set(lm, IDX.UPPER_LIP_TOP, 0.5, cy - h / 2 - 0.015, 0);
  set(lm, IDX.UPPER_LIP_CENTER, 0.5, cy - h / 2, 0);
  set(lm, IDX.LOWER_LIP_BOTTOM, 0.5, cy + h / 2 + 0.015, 0);
  set(lm, IDX.LOWER_LIP_CENTER, 0.5, cy + h / 2, 0);
}

/**
 * 设置微笑/撇嘴：嘴角相对嘴唇中心的垂直偏移。
 * smileFeature = (mouthCenterY - cornerY) / faceHeight
 * @param {number} amount 正 = 微笑（嘴角上扬），负 = 撇嘴
 */
export function applySmile(lm, amount) {
  const faceHeight = 0.30;
  const cy = 0.63;
  const dy = amount * faceHeight; // 嘴角要上移 dy
  set(lm, MOUTH_OUTER[0], 0.5 - MOUTH.width / 2, cy - dy, 0);
  set(lm, MOUTH_OUTER[10], 0.5 + MOUTH.width / 2, cy - dy, 0);
  set(lm, MOUTH_INNER[0], 0.5 - MOUTH.width / 2, cy - dy, 0);
  set(lm, MOUTH_INNER[10], 0.5 + MOUTH.width / 2, cy - dy, 0);
}

/**
 * 生成一段帧序列。
 *
 * @param {object} spec
 *   spec.frames    帧数
 *   spec.fps       帧率（决定 t 间隔）
 *   spec.pose      [pitch, yaw, roll] 或 (i, frames) => [p,y,r]
 *   spec.eyeHeight (i, frames) => [leftH, rightH]
 *   spec.mouthOpen (i, frames) => number
 *   spec.smile     (i, frames) => number
 * @returns {Array<{t:number, landmarks:Float32Array, pose:number[]}>}
 */
export function makeSequence(spec) {
  const frames = spec.frames || 60;
  const fps = spec.fps || 30;
  const dtMs = 1000 / fps;
  const out = [];
  for (let i = 0; i < frames; i++) {
    const lm = makeNeutralFace();
    const eye = spec.eyeHeight ? spec.eyeHeight(i, frames) : [EYE.openHeight, EYE.openHeight];
    applyEyeOpen(lm, eye[0], eye[1]);
    applyMouthOpen(lm, spec.mouthOpen ? spec.mouthOpen(i, frames) : 0);
    applySmile(lm, spec.smile ? spec.smile(i, frames) : 0);
    const pose = spec.pose
      ? (typeof spec.pose === "function" ? spec.pose(i, frames) : spec.pose)
      : [0, 0, 0];
    // 【关键】时间戳必须是**整数毫秒**：Kotlin BlinkCurveProcessor.process(currentTimeMs: Long)
    // 只收 Long，小数 t 会让 delta 序列在两端分叉（33.3333… vs 33/34 交替），
    // 眨眼曲线 curveProgress 逐步累积出 ~1e-2 级偏差，直接打爆 1e-4 容差。
    // 取 Math.round(i * dtMs)（累计后取整）而非逐帧累加，避免漂移。
    out.push({ t: Math.round(i * dtMs), landmarks: lm, pose: [pose[0], pose[1], pose[2]] });
  }
  return out;
}

/**
 * 标准场景集合（黄金评测的分层 fixture）。
 *
 * 分层依据（Lead 裁决）：
 *   第一阶段 = advancedMode=false 的场景（与 Android 当前实际行为一致）；
 *   第二阶段 = advancedMode=true 的场景，单独出报告。
 */
export const SCENARIOS = Object.freeze({
  // ---- 第一阶段：advancedMode = false ----
  neutral: () => makeSequence({ frames: 60 }),
  blink: () => makeSequence({
    frames: 60,
    eyeHeight: (i) => (i >= 20 && i < 26 ? [EYE.closedHeight, EYE.closedHeight] : [EYE.openHeight, EYE.openHeight]),
  }),
  squint: () => makeSequence({
    frames: 120,
    eyeHeight: (i) => (i >= 20 ? [0.0135, 0.0135] : [EYE.openHeight, EYE.openHeight]),
  }),
  mouthOpen: () => makeSequence({
    frames: 60,
    mouthOpen: (i) => (i >= 20 ? (i - 20) / 20 : 0),
  }),
  smile: () => makeSequence({
    frames: 60,
    smile: (i) => (i >= 20 ? 0.02 : 0),
  }),
  frown: () => makeSequence({
    frames: 60,
    smile: (i) => (i >= 20 ? -0.015 : 0),
  }),
  yawLeft: () => makeSequence({ frames: 60, pose: [0, 20, 0] }),
  yawRight: () => makeSequence({ frames: 60, pose: [0, -20, 0] }),
  pitchDown: () => makeSequence({ frames: 60, pose: [20, 0, 0] }),
  pitchUp: () => makeSequence({ frames: 60, pose: [-20, 0, 0] }),
  rollTilt: () => makeSequence({ frames: 60, pose: [0, 0, 15] }),
  // 长间隔：两帧之间隔 300ms（考验 dt 口径，见 test-temporal-filter）
  longDt: () => makeSequence({ frames: 30, fps: 3.333 }),
  // 人脸丢失后恢复
  faceLost: () => {
    const seq = makeSequence({ frames: 60 });
    for (let i = 25; i < 40; i++) seq[i].landmarks.fill(0);
    return seq;
  },

  // ---- 第二阶段：advancedMode = true（单独覆盖）----
  browUp: () => makeSequence({
    frames: 60,
    advanced: true,
  }),
});
