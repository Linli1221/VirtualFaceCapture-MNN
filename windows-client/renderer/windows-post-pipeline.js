/**
 * windows-post-pipeline.js — raw_track 后处理链总装编排器（task-4 P3 集成）
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 职责（docs/post-pipeline-migration.md §5 的「windows-post-pipeline」行）：
 *   编排器 —— 步骤顺序、线程模型（worker / 同步回退）、Transferable 移交、
 *   固定 500 ms playout、状态生命周期、降级（文本 legacy）。**不含算法本体**：
 *   算法全部在 raw-frame-reorder / temporal-filter / blendshape-extractor /
 *   blink-curve / expression-calibrator / pose-calibrator / body-fusion
 *   （task-3 win-postproc 移植，导出签名已冻结）。
 *
 * 处理链顺序（docs/post-pipeline-migration.md §4 的 12 步，顺序不可调换）：
 *   [worker 内] 1 接收 → 2 frameId 重排 → 3 丢过期帧 → 4 帧间隔/时间戳
 *            → 5 时间滤波（478 点域 + rawHeadPose，提取**之前**）
 *            → 6 BlendShape 提取 → 7 姿态/表情校准 → 8 眨眼状态机 → 9 上半身融合
 *   [主线程]  10 参数映射（mapping.js，由 app.js 注入 mapFrame）
 *            → 11 固定 500 ms playout（本文件 PlayoutBuffer，全链唯一人为延迟）
 *            → 12 插值（interp.js，**不改**）→ Live2D 应用
 *
 * ⚠️ 实现层偏差说明（Lead 已批准）：任务书示意图写「主线程只收最终 Live2D 参数」，
 *   本实现把**步骤 10 参数映射留在主线程**（worker 只到步骤 9）。理由：
 *   mapping.js 的 createMapper 单实例同时服务 v1 文本 track 与 raw 两条路径，进 worker
 *   会分叉实现。重负载（478 点解析 / 几何提取 / 校准 / 眨眼 / 滤波）全在 worker，
 *   主线程每帧只做 ~20 个参数的映射 + 定时下发，实时性不受影响。
 *
 * 线程模型：renderer/worker/post-pipeline-worker.js（薄适配层）内跑 RawFrameProcessor；
 *   worker 缺失 / 构造失败 / 运行时异常 → **同步回退**：同一个 RawFrameProcessor 在
 *   主线程直跑（docs 裁决 5）。两条路径的下游（mapFrame → playout → onPlayout）一致。
 *
 * SharedArrayBuffer 评估结论：Electron 加载 http://127.0.0.1:17890（server.js 无
 *   COOP/COEP 头）→ crossOriginIsolated=false → SAB 不可用；**禁用 SAB**，
 *   跨线程一律 Transferable ArrayBuffer / TypedArray（零拷贝）。
 *
 * 延迟语义（Lead 裁决 D2）：raw 链的 500 ms playout 是全链路**唯一人为延迟**；
 *   interp.js 不改，但 raw 模式下 stage 以 jitterBuffer **最小口径**启动（由 app.js
 *   运行时改 stage.timeline.options 深度档），只保留小抖动插值与断流外推；
 *   v1 文本 track 路径的 jitterBufferV1 行为完全不变（本文件不碰 interp.js）。
 *
 * 字节契约：docs/raw-track-protocol.md §4/§5（72 B 帧头 + 478×6 B 关键点 +
 *   可选 128 B ubRaw 扩展块，little-endian）。长度 / 魔数 / 版本不符整条丢弃（§8.4）。
 *
 * 校准所有权（协议 §6）：校准在 Windows。本文件实现 begin/cancel/finish/save/
 *   restore-default + CalibFile（vfc-calibration/v1，model + cameraProfile 维度）。
 *   手机只响应 resetTracking / startStream / stopStream（由 app.js 发文本控制消息）。
 */
import { FrameIntervalTracker, RawFrameReorderBuffer, unwrapFrameId } from "./raw-frame-reorder.js";
import { createTemporalFilter } from "./temporal-filter.js";
import { BS_COUNT, BlendShapeExtractor } from "./blendshape-extractor.js";
import { BlinkCurveProcessor } from "./blink-curve.js";
import { ExpressionCalibrator } from "./expression-calibrator.js";
import { PoseCalibrator } from "./pose-calibrator.js";
import { BodyFusion } from "./body-fusion.js";

// ================================================================ 协议常量（docs/raw-track-protocol.md §4/§5）

/** 帧头魔数 "VFCR"（0x56 0x46 0x43 0x52）。 */
const RAW_MAGIC0 = 0x56, RAW_MAGIC1 = 0x46, RAW_MAGIC2 = 0x43, RAW_MAGIC3 = 0x52;
/** 帧格式版本（只接受 1）。 */
const RAW_VERSION = 1;
/** msgType = 0x01 RAW_TRACK。 */
const MSG_RAW_TRACK = 0x01;
/** 帧头 72 字节。 */
export const HEADER_BYTES = 72;
/** 关键点：478 点 × 6 字节（x_q u16 / y_q u16 / z_q i16）。 */
export const LANDMARK_COUNT = 478;
export const LANDMARK_STRIDE = 6;
export const LANDMARK_BYTES = LANDMARK_COUNT * LANDMARK_STRIDE; // 2868
/** ubRaw 扩展块 128 字节（flags bit1 置位时存在）。 */
export const UB_EXT_BYTES = 128;
/** z 的统一 scale 因子（§4.3：z_q = round(clamp(z/5,-1,1)*32767)）。 */
export const Z_SCALE = 5.0;

// flags（u16，§4.2）
export const FLAG_FACE_VALID = 1 << 0;
export const FLAG_UB_EXT_PRESENT = 1 << 1;
export const FLAG_POSE_VALID = 1 << 2;
export const FLAG_LANDMARKS_CLAMPED = 1 << 3;
export const FLAG_SENSOR_TIMESTAMP_EXT = 1 << 4;
export const FLAG_LANDMARKS_PRE_SMOOTHED = 1 << 5;

/** 13 点 × (x,y,z,vis) 扁平长度（ubRaw 扩展块解码后）。 */
const UB_POINTS_FLOATS = 13 * 4;

// ================================================================ playout（步骤 11：全链唯一人为延迟）

/** raw 链固定 playout 延迟（ms）：docs §4 第 11 步 / 迁移文档裁决 3。 */
export const DEFAULT_PLAYOUT_DELAY_MS = 500;
/** playout 队列上限（TrackingFrameBuffer 语义：120 条溢出丢最旧）。 */
export const PLAYOUT_MAX_QUEUE = 120;
/** 兜底放行阈值（ms）：媒体时间戳异常（停滞/回跳）导致 dueAt 卡住时强制放行。 */
export const PLAYOUT_STUCK_MS = 2000;

/**
 * 固定 500 ms playout 缓冲（TrackingFrameBuffer 语义移植：预缓冲 → 按生产节奏匀速放行）。
 *
 * 时间模型：以本段首帧到达时刻 firstNow 为基准，第 i 帧的放行时刻
 *     dueAt_i = firstNow + delayMs + (t_i − t_0)
 * 即「媒体时间轴整体后移 delayMs」：
 *   - 固定延迟 = delayMs（500 ms），**不再叠加任何自适应深度**（自适应深度只属于
 *     legacy 路径的 jitterBufferV1，见 interp.js；raw 模式下由 app.js 切最小口径）；
 *   - WS 批量到达 / 网络抖动被摊平成按生产节奏匀速放行（用 t 而不是到达时间排程）；
 *   - 起播 = 首帧在 firstNow + delayMs 放行（天然预缓冲 500 ms，无需攒够内容）。
 *
 * 边界：
 *   - 媒体时间 t 回退（新会话 / 重置 / 时钟重锚）→ 整体重开一段，绝不倒带；
 *   - 队列 > PLAYOUT_MAX_QUEUE → 丢最旧（宁丢不堵，与发送端 UDP 式语义一致）；
 *   - dueAt 卡住（媒体时间戳停滞）超过 PLAYOUT_STUCK_MS → 强制放行，不出死帧；
 *   - 断流：剩余帧按 dueAt 放完即止，之后不再输出（app.js 由 idle 接管）。
 */
export class PlayoutBuffer {
  /**
   * @param {{delayMs?: number, maxQueue?: number, stuckMs?: number,
   *          onEmit?: (item: any) => void, onDrop?: (count: number) => void}} [options]
   */
  constructor(options = {}) {
    this.delayMs = Number.isFinite(options.delayMs) ? Math.max(0, options.delayMs) : DEFAULT_PLAYOUT_DELAY_MS;
    this.maxQueue = Number.isFinite(options.maxQueue) ? options.maxQueue : PLAYOUT_MAX_QUEUE;
    this.stuckMs = Number.isFinite(options.stuckMs) ? options.stuckMs : PLAYOUT_STUCK_MS;
    this.onEmit = options.onEmit ?? null;
    this.onDrop = options.onDrop ?? null;
    /** 待放行队列：{dueAt, enqueuedAt, t, frameId, payload}，push 即算 dueAt，天然有序。 */
    this._queue = [];
    this._timer = null;
    this._baseT = null;
    this._baseDue = null;
    this._dropped = 0;
    this._emitted = 0;
  }

  /** 当前排队深度（调试 / stats）。 */
  get size() {
    return this._queue.length;
  }

  /** 媒体时间轴是否已建立（reset 后首帧之前为 false）。 */
  get primed() {
    return this._baseT !== null;
  }

  /**
   * 入队一帧（步骤 10 映射后的最终参数）。
   * @param {{t:number, frameId:number}} meta 媒体时间与帧号（t = 采集时间戳 ms）
   * @param {*} payload 透传给 onEmit 的负载
   * @param {number} nowMs 本机单调时钟（performance.now() 口径）
   */
  push(meta, payload, nowMs) {
    const t = Number(meta?.t);
    if (!Number.isFinite(t)) return false;
    const at = Number.isFinite(nowMs) ? nowMs : nowClock();
    if (this._baseT === null || t < this._baseT) {
      // 首帧 / 媒体时间回退：重开一段（新会话、时钟重锚），绝不倒带
      this._baseT = t;
      this._baseDue = at + this.delayMs;
    }
    const dueAt = this._baseDue + (t - this._baseT);
    this._queue.push({ dueAt, enqueuedAt: at, t, frameId: meta.frameId, payload });
    while (this._queue.length > this.maxQueue) {
      this._queue.shift();
      this._dropped += 1;
      this.onDrop?.(1);
    }
    this._arm();
    return true;
  }

  /** 清空（新会话 / 断线 / 切模式）。 */
  reset() {
    this._queue.length = 0;
    this._baseT = null;
    this._baseDue = null;
    this._disarm();
  }

  /** 诊断快照。 */
  stats() {
    return {
      queued: this._queue.length,
      delayMs: this.delayMs,
      dropped: this._dropped,
      emitted: this._emitted,
      primed: this.primed,
    };
  }

  // ---------------------------------------------------------------- 内部

  _disarm() {
    if (this._timer !== null) {
      clearTimeout(this._timer);
      this._timer = null;
    }
  }

  _arm() {
    if (this._timer !== null || this._queue.length === 0) return;
    const head = this._queue[0];
    const wait = Math.max(0, head.dueAt - nowClock());
    // 分片等待：兜底判定最多晚 stuckMs 的一半，绝不出死帧
    this._timer = setTimeout(() => {
      this._timer = null;
      this._drain();
    }, Math.min(wait, 100));
  }

  _drain() {
    const now = nowClock();
    while (this._queue.length > 0) {
      const head = this._queue[0];
      const stuck = now - head.enqueuedAt > this.stuckMs;
      if (now < head.dueAt && !stuck) break;
      this._queue.shift();
      this._emitted += 1;
      this.onEmit?.(head.payload);
    }
    this._arm();
  }
}

/** 本机单调时钟（Node 单测里没有 performance 也能跑）。 */
function nowClock() {
  return typeof performance !== "undefined" && typeof performance.now === "function" ? performance.now() : Date.now();
}

// ================================================================ decode（步骤 1：二进制帧 → RawFrame）

/**
 * 解码一条 raw_track 二进制消息（docs/raw-track-protocol.md §4/§5）。
 *
 * 校验（§8.4）：magic "VFCR" / version==1 / msgType==0x01 / landmarkCount==478 /
 * landmarkStride==6 / 总长 == 72 + 2868 (+ 128 若 UB_EXT_PRESENT)；任何一项不符
 * **整条丢弃**（返回 null，由调用方计数 badBinaryFrames），不抛异常、不断连。
 *
 * @param {ArrayBuffer|ArrayBufferView} buffer WS message payload
 * @param {{lastRaw?: number|null, lastExtended?: number|null}} [unwrap] frameId 回绕展开状态
 * @returns {object|null} RawFrame（landmarks 为归一化 Float32Array(478*3)）
 */
export function decodeRawFrame(buffer, unwrap = {}) {
  let bytes;
  if (buffer instanceof ArrayBuffer) bytes = new Uint8Array(buffer);
  else if (ArrayBuffer.isView(buffer)) bytes = new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  else return null;

  if (bytes.byteLength < HEADER_BYTES + LANDMARK_BYTES) return null;
  if (bytes[0] !== RAW_MAGIC0 || bytes[1] !== RAW_MAGIC1 || bytes[2] !== RAW_MAGIC2 || bytes[3] !== RAW_MAGIC3) return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (view.getUint8(4) !== RAW_VERSION) return null;
  if (view.getUint8(5) !== MSG_RAW_TRACK) return null;

  const flags = view.getUint16(6, true);
  const landmarkCount = view.getUint16(64, true);
  const landmarkStride = view.getUint16(66, true);
  const hasUb = (flags & FLAG_UB_EXT_PRESENT) !== 0;
  if (landmarkCount !== LANDMARK_COUNT || landmarkStride !== LANDMARK_STRIDE) return null;
  const expected = HEADER_BYTES + landmarkCount * landmarkStride + (hasUb ? UB_EXT_BYTES : 0);
  if (view.byteLength < expected) return null; // 截断帧整条丢弃

  // ---- 帧头（§4.1）----
  const rawFrameId = view.getUint32(8, true);
  const unwrapped = unwrapFrameId(rawFrameId, unwrap.lastRaw ?? null, unwrap.lastExtended ?? null);
  unwrap.lastRaw = unwrapped.raw;
  unwrap.lastExtended = unwrapped.extended;
  const cameraFps = view.getFloat32(12, true);
  // u64 t：毫秒级 Unix 时间远小于 2^53，直接转 Number 安全
  const t = Number(view.getBigUint64(16, true));
  const width = view.getUint16(24, true);
  const height = view.getUint16(26, true);
  const presence = view.getFloat32(28, true);
  const inferenceMs = view.getFloat32(32, true);
  const poseRaw = new Float32Array([
    view.getFloat32(36, true), // pitch（未减任何基线）
    view.getFloat32(40, true), // yaw
    view.getFloat32(44, true), // roll
  ]);
  const box = new Float32Array([
    view.getFloat32(48, true), view.getFloat32(52, true),
    view.getFloat32(56, true), view.getFloat32(60, true),
    view.getFloat32(68, true), // box.score（offset 68，紧跟 landmarkCount/Stride 之后）
  ]);

  // ---- 关键点块（§4.3）：478 × 6 B → 归一化 Float32Array(478*3) ----
  const landmarks = new Float32Array(LANDMARK_COUNT * 3);
  let off = HEADER_BYTES;
  for (let i = 0; i < LANDMARK_COUNT; i++) {
    const xq = view.getUint16(off, true);
    const yq = view.getUint16(off + 2, true);
    const zq = view.getInt16(off + 4, true);
    landmarks[i * 3] = xq / 65535;
    landmarks[i * 3 + 1] = yq / 65535;
    landmarks[i * 3 + 2] = (zq * Z_SCALE) / 32767;
    off += LANDMARK_STRIDE;
  }

  // ---- ubRaw 扩展块（§5，可选）----
  let ub = null;
  if (hasUb) {
    const base = HEADER_BYTES + LANDMARK_BYTES;
    const points = new Float32Array(UB_POINTS_FLOATS);
    for (let i = 0; i < 13; i++) {
      const p = base + i * 8;
      points[i * 4] = view.getUint16(p, true) / 65535;
      points[i * 4 + 1] = view.getUint16(p + 2, true) / 65535;
      points[i * 4 + 2] = (view.getInt16(p + 4, true) * Z_SCALE) / 32767;
      points[i * 4 + 3] = view.getUint16(p + 6, true) / 65535;
    }
    const ubT = Number(view.getBigUint64(base + 104, true));
    const torsoRaw = new Float32Array([
      view.getFloat32(base + 112, true), // roll（未平滑未减基线）
      view.getFloat32(base + 116, true), // yaw
    ]);
    const quality = view.getFloat32(base + 120, true);
    const tracking = view.getUint8(base + 124) === 1;
    ub = { present: true, points, ubT, torsoRaw, quality, tracking };
  }

  return {
    rawFrameId,
    frameId: unwrapped.extended,
    seq: rawFrameId & 0x7f,   // 仅诊断（§7.3）
    epoch: rawFrameId >>> 7,  // 仅诊断
    t,
    width,
    height,
    presence,
    inferenceMs,
    cameraFps,
    poseRaw,
    poseValid: (flags & FLAG_POSE_VALID) !== 0,
    faceValid: (flags & FLAG_FACE_VALID) !== 0,
    preSmoothed: (flags & FLAG_LANDMARKS_PRE_SMOOTHED) !== 0,
    clamped: (flags & FLAG_LANDMARKS_CLAMPED) !== 0,
    box,
    landmarks,
    ub,
  };
}

// ================================================================ RawFrameProcessor（步骤 2~9：worker / 同步共用）

/**
 * raw 帧后处理处理器（步骤 2 重排 → 9 上半身融合）。**算法本体都在各模块**，
 * 本类只按 docs/post-pipeline-migration.md §4 的固定顺序编排 + 校准控制。
 *
 * 有状态、输入必须单调（frameId 经 unwrapFrameId 展开、t 经步骤 3 裁决），
 * 禁止乱序输入 —— 乱序由步骤 2 的 RawFrameReorderBuffer 挡掉。
 *
 * 时间口径（win-postproc 调用约定，勿混）：
 *   - 校准窗口计时 = 本机 performance.now()（nowMs）；
 *   - blink 时间戳 = 帧采集时间 t（手机时钟同源，只取相邻差值）；
 *   - temporal-filter / body-fusion 的 dt = 帧采集时间相邻差值（三口径见 FrameIntervalTracker）。
 */
export class RawFrameProcessor {
  /** @param {{advancedMode?:boolean, cameraFovDeg?:number, smoothing?:object, autoPoseBaseline?:boolean,
   *          onFrame?:(r:object)=>void, onCalibState?:(s:object)=>void}} [options] */
  constructor(options = {}) {
    this.options = options;
    this.extractor = new BlendShapeExtractor({
      advancedMode: options.advancedMode === true,
      cameraFovDeg: options.cameraFovDeg,
    });
    this.poseCalib = new PoseCalibrator(options.poseCalibration ?? {});
    this.exprCalib = new ExpressionCalibrator(this.extractor, options.expressionCalibration ?? {});
    this.blink = new BlinkCurveProcessor(options.blink ?? {});
    this.bodyFusion = new BodyFusion(options.body ?? {});
    this.temporal = createTemporalFilter({ smoothing: options.smoothing ?? {} });
    this.reorder = new RawFrameReorderBuffer(options.reorder ?? {});
    this.intervals = new FrameIntervalTracker(options.intervals ?? {});
    /** frameId 回绕展开状态（decode 层持有，重排层只见单调 id）。 */
    this._unwrap = { lastRaw: null, lastExtended: null };
    /** 步骤 3 丢过期帧：已处理帧的 t 高水位（时间戳旧帧直接丢）。 */
    this._lastT = null;
    /** 会话内自动姿态基线（Android 首窗 1s 基线等价物）是否已布置。 */
    this._autoBaselineStarted = false;
    /** 校准请求幂等表：requestId → ack（协议 §6.3）。 */
    this._calibAck = new Map();
    /** 当前校准窗口（{active,target,requestId,startedAt,doneAt}）。 */
    this._calib = { active: false, target: null, requestId: null, startedAt: 0 };
    this.streamActive = true;
    this.counters = {
      frames: 0,
      badBinaryFrames: 0,
      expired: 0,
      timeRegressed: 0,
      reorderDropped: 0,
    };
    this.onFrame = options.onFrame ?? null;
    this.onCalibState = options.onCalibState ?? null;
  }

  // ------------------------------------------------------------ 帧入口

  /**
   * 投喂一条 raw_track 二进制消息（步骤 1→2）。解析失败整条丢弃计数（§8.4）。
   * @param {ArrayBuffer|ArrayBufferView} buffer
   * @param {number} [receivedAtMs] 本机到达时间（nowMs 口径；缺省取当前时刻）
   */
  ingest(buffer, receivedAtMs) {
    if (!this.streamActive) return;
    const at = Number.isFinite(receivedAtMs) ? receivedAtMs : nowClock();
    const frame = decodeRawFrame(buffer, this._unwrap);
    if (!frame) {
      this.counters.badBinaryFrames += 1;
      return;
    }
    // 步骤 2/3：frameId 重排 + 旧帧丢弃（迟到旧帧 / 重复帧在 reorder 内计数）
    const released = this.reorder.offer(frame.frameId, frame, at > 0 ? at : 1, this._released ??= []);
    for (let i = 0; i < released.length; i++) this._processFrame(released[i], at);
  }

  /**
   * 无新帧时轮询重排缓冲（缺口超时放行，绝不永久卡住）。playout 定时器 / worker
   * 定时消息都可以调；不会重复放行已处理帧。
   */
  poll(nowMs) {
    const at = Number.isFinite(nowMs) && nowMs > 0 ? nowMs : nowClock();
    const released = this.reorder.poll(at, this._released ??= []);
    for (let i = 0; i < released.length; i++) this._processFrame(released[i], at);
  }

  /** 步骤 3~9 的固定顺序处理一帧已重排帧。 */
  _processFrame(frame, receivedAtMs) {
    // ---- 3 丢过期帧：时间戳旧帧（新会话残留 / 时钟回退）直接丢，绝不喂有状态模块 ----
    if (this._lastT !== null && frame.t < this._lastT) {
      this.counters.timeRegressed += 1;
      this.counters.expired += 1;
      return;
    }
    this._lastT = frame.t;
    this.counters.frames += 1;

    // ---- 4 帧间隔 / 时间戳（dt 三口径分开取用）----
    this.intervals.push(frame.t);
    const tSec = frame.t / 1000;

    // ---- 5 时间滤波：478 点域 + rawHeadPose（提取**之前**；bs 域永远不平滑）----
    // 协议 flags bit5 LANDMARKS_PRE_SMOOTHED=1（手机端原生 EMA 未旁路）→ 跳过点域平滑。
    if (!frame.preSmoothed) {
      this.temporal.filterLandmarks(frame.landmarks, tSec);
    }
    const pose = new Float32Array(3);
    if (frame.poseValid) {
      this.temporal.filterPose(pose, frame.poseRaw[0], frame.poseRaw[1], frame.poseRaw[2], tSec);
    }

    // ---- 6 BlendShape 提取（眼/嘴/微笑校准累计器内置，extract 内自动落地）----
    const bs52 = new Float32Array(BS_COUNT);
    this.extractor.extractFullBlendShapes(frame.landmarks, bs52, frame.poseValid ? pose : null, {
      width: frame.width,
      height: frame.height,
      nowMs: receivedAtMs, // 校准窗口计时用本机时钟（blink 另用采集时间 t）
    });

    // ---- 7 姿态 / 表情校准 ----
    // 顺序耦合（不可换）：pose 窗口落地 → **立即** extractor.setCalibrationPose(baseline)
    //   （覆盖 SimpleEye 左眼落地瞬间记的 calibrationPose）→ expr 窗口结束才 exprCalib.finish()。
    const now = receivedAtMs;
    if (this.poseCalib.isCalibrated === false && this._autoBaselineStarted) {
      const landed = this.poseCalib.add(pose[0], pose[1], pose[2], now);
      if (landed) {
        const baseline = this.poseCalib.baseline;
        this.extractor.setCalibrationPose(baseline[0], baseline[1], baseline[2]);
      }
    }
    if (this._calib.active) {
      this.exprCalib.add(frame.landmarks, pose, now);
      this._maybeFinishCalibration(now);
    }

    // ---- 8 眨眼状态机（时间戳 = 采集时间 t；delta 由状态机内算）----
    this.blink.updateFrameRate(this.intervals.fps);
    this.blink.processInto(bs52, frame.t);

    // ---- 9 上半身融合（tracking=false 不喂平滑器 / 基线，坏帧不污染状态）----
    let ubOut = null;
    if (frame.ub) {
      const out = this.bodyFusion.process(frame.ub.points, this.intervals.dtBodySeconds, frame.ub.ubT);
      ubOut = {
        present: true,
        tracking: out.tracking,
        quality: out.quality,
        ubT: out.ubT,
        // 就地平滑后的 13 点 / 相对躯干角：tracking=false 时为 null（主线程按 ubOk=false 平滑回 0）
        points: out.tracking ? out.points : null,
        torso: out.tracking ? out.torso : null,
      };
    }

    this.onFrame?.({
      frameId: frame.frameId,
      rawFrameId: frame.rawFrameId,
      seq: frame.seq,
      epoch: frame.epoch,
      t: frame.t,
      receivedAt: receivedAtMs,
      faceValid: frame.faceValid,
      preSmoothed: frame.preSmoothed,
      poseValid: frame.poseValid,
      inferenceMs: frame.inferenceMs,
      cameraFps: frame.cameraFps,
      bs52,
      pose,
      ub: ubOut,
      calibrating: this.isCalibrating,
    });
  }

  // ------------------------------------------------------------ 会话生命周期

  /**
   * 状态生命周期（迁移文档 §4）：新 hello / resetTracking / 断线重连 / 切模型 →
   * 重排、dt 锚点、点域滤波、眨眼、上半身融合、playout 全部 reset。
   * keepCalibration=true 时**只清运行态**（基线 / 阈值保留，冷启动重调平滑器）。
   */
  reset(reason = "reset", keepCalibration = true) {
    this.reorder.reset();
    this.intervals.reset();
    this.temporal.reset();
    this.blink.reset();
    this._unwrap = { lastRaw: null, lastExtended: null };
    this._lastT = null;
    if (keepCalibration) {
      this.bodyFusion.smoother.reset(); // 平滑器清、躯干基线保留
    } else {
      this.bodyFusion.reset();
      this.extractor.reset();
      this.poseCalib.reset();
      this.exprCalib.reset();
    }
    // Android 首窗 1s 姿态基线的等价物：没有可用基线时自动开一个采样窗口
    this._autoBaselineStarted = !this.poseCalib.isCalibrated;
    if (this._autoBaselineStarted) this.poseCalib.begin(nowClock());
    this.streamActive = true;
    this._emitCalibState("idle", reason);
  }

  /** startStream / stopStream 的本地闸门（false 时丢帧不处理，与手机侧流控对应）。 */
  setStreamActive(active) {
    this.streamActive = active !== false;
  }

  /** 热更新点域平滑配置（只重建 temporal filter，其余状态不动）。 */
  setSmoothing(smoothing) {
    this.temporal = createTemporalFilter({ smoothing: smoothing ?? {} });
  }

  // ------------------------------------------------------------ 校准控制（协议 §6.3：PC 侧，不发手机）

  /** 合成校准态（FaceCapturePipeline.kt:70 口径：!poseIsCalibrated || 眼 || 嘴 || 微笑）。 */
  get isCalibrating() {
    return !this.poseCalib.isCalibrated || this.extractor.isCalibrating;
  }

  /**
   * 重校准（recalibrate，PC 内部动作）。幂等：同 requestId 重复提交返回同一 ack；
   * 同 target 在校准窗口内重复触发合并。
   * @param {"face"|"body"|"all"} target
   * @param {number} [requestId] 请求 id（超时重试必须换新 id）
   * @param {number} [nowMs]
   */
  recalibrate(target, requestId = null, nowMs = null) {
    const at = Number.isFinite(nowMs) ? nowMs : nowClock();
    const id = requestId == null ? null : String(requestId);
    if (id !== null && this._calibAck.has(id)) return { ...this._calibAck.get(id), merged: false, cached: true };
    if (this._calib.active && this._calib.target === target) {
      const ack = { ok: true, requestId: id, target, merged: true };
      if (id !== null) this._calibAck.set(id, ack);
      return ack;
    }

    if (target === "body") {
      this.bodyFusion.reset(); // 躯干基线重收（10 帧渐入，TorsoBaseline 语义）
      this._emitCalibState("done", "body-reset");
      const ack = { ok: true, requestId: id, target };
      if (id !== null) this._calibAck.set(id, ack);
      return ack;
    }

    // face / all：清基线 → 重新收集校准窗口（1s 且 ≥10 帧）
    this.extractor.reset();       // 眼/嘴/微笑窗口重开（extract 内自动落地）
    this.blink.reset();
    this.exprCalib.begin(at);
    this.poseCalib.begin(at);
    this._autoBaselineStarted = true;
    if (target === "all") this.bodyFusion.reset();
    this._calib = { active: true, target, requestId: id, startedAt: at };
    this._emitCalibState("sampling", "begin");
    const ack = { ok: true, requestId: id, target };
    if (id !== null) this._calibAck.set(id, ack);
    return ack;
  }

  /** 取消当前校准窗口（偏移 / 基线保持取消前的值）。 */
  cancelCalibration() {
    if (!this._calib.active) return { ok: false, reason: "idle" };
    this.exprCalib.cancel();
    this._calib = { active: false, target: null, requestId: null, startedAt: 0 };
    // 取消后若姿态基线还没落地，回到「自动首窗基线」（与冷启动一致），避免永远 isCalibrating
    if (!this.poseCalib.isCalibrated) {
      this._autoBaselineStarted = true;
      this.poseCalib.begin(nowClock());
    }
    this._emitCalibState("cancelled", "cancel");
    return { ok: true };
  }

  /**
   * 立即落定当前窗口（供 UI「完成」/ 超时兜底）。
   * 固定顺序：poseCalib.finish() → extractor.setCalibrationPose(baseline) → exprCalib.finish()。
   */
  finishCalibration() {
    if (this._calib.active) {
      this.poseCalib.finish();
      const baseline = this.poseCalib.baseline;
      this.extractor.setCalibrationPose(baseline[0], baseline[1], baseline[2]); // 只在窗口结束调一次
      this.exprCalib.finish(); // 仅 advancedMode=true 才真正 calibrate(avgLandmarks)
      this._calib = { active: false, target: null, requestId: this._calib.requestId, startedAt: 0 };
      this._emitCalibState("done", "finish");
      return true;
    }
    return false;
  }

  /** 恢复默认（清全部校准基线，回到未校准冷启动）。 */
  restoreDefaultCalibration() {
    this.extractor.reset();
    this.poseCalib.reset();
    this.exprCalib.reset();
    this.bodyFusion.reset();
    this.blink.reset();
    this._calib = { active: false, target: null, requestId: null, startedAt: 0 };
    this._calibAck.clear();
    this._autoBaselineStarted = true;
    this.poseCalib.begin(nowClock()); // 恢复默认后自动重采姿态基线（与冷启动一致）
    this._emitCalibState("idle", "restore-default");
    return { ok: true };
  }

  /** 校准状态快照（UI / calib-state 消息）。 */
  calibrationState(nowMs = null) {
    const at = Number.isFinite(nowMs) ? nowMs : nowClock();
    return {
      active: this._calib.active,
      target: this._calib.target,
      requestId: this._calib.requestId,
      isCalibrating: this.isCalibrating,
      poseCalibrated: this.poseCalib.isCalibrated,
      pose: this.poseCalib.progress(at),
      expression: this.exprCalib.progress(at),
      bodyCalibrated: this.bodyFusion.baseline.isCalibrated,
    };
  }

  /** 供外壳组装 CalibFile（model + cameraProfile 由 app.js 供给）。 */
  serializeCalibrationParts() {
    return {
      pose: this.poseCalib.serialize(),
      expression: this.exprCalib.serialize(),
      body: {
        torsoBaseline: {
          roll: this.bodyFusion.baseline.baseline[0],
          yaw: this.bodyFusion.baseline.baseline[1],
        },
        collectedFrames: this.bodyFusion.baseline.isCalibrated ? 10 : 0,
      },
    };
  }

  /** 注入 CalibFile 子树（load 后立刻可用，不再重采样）。 */
  loadCalibrationParts(parts) {
    if (!parts || typeof parts !== "object") return false;
    if (parts.pose) {
      this.poseCalib.load(parts.pose);
      if (this.poseCalib.isCalibrated) {
        const b = this.poseCalib.baseline;
        this.extractor.setCalibrationPose(b[0], b[1], b[2]);
        this._autoBaselineStarted = false;
      }
    }
    if (parts.expression) this.exprCalib.load(parts.expression);
    // 躯干基线：BodyFusion 未暴露 load API（task-3 冻结签名），能读则读、不能读就冷启动重调
    if (parts.body?.torsoBaseline && typeof this.bodyFusion.loadBaseline === "function") {
      this.bodyFusion.loadBaseline(parts.body);
    }
    this._emitCalibState("idle", "load");
    return true;
  }

  // ------------------------------------------------------------ 统计

  stats() {
    return {
      ...this.counters,
      reorder: this.reorder.stats(),
      intervals: { fps: this.intervals.fps, rawDtMs: this.intervals.rawDtMs, regressed: this.intervals.regressed },
      blink: this.blink.getDebugInfo?.() ?? null,
      calibrating: this.isCalibrating,
    };
  }

  _maybeFinishCalibration(now) {
    if (!this._calib.active) return;
    const pose = this.poseCalib.progress(now);
    const expr = this.exprCalib.progress(now);
    // 两个窗口都落地（pose 已校准 && 眼/嘴/微笑不再 calibrating）→ 自动完成
    if (this.poseCalib.isCalibrated && !this.extractor.isCalibrating) {
      this.finishCalibration();
      return;
    }
    // 窗口早已超时（> 2× 窗口时长）仍未落地 → 保持 sampling，由 UI 层提示可重试（协议 §6.3）
    if (now - this._calib.startedAt > 2 * Math.max(pose.remainingMs + pose.elapsedMs, expr.remainingMs + expr.elapsedMs) + 1000) {
      this._emitCalibState("sampling", "timeout-risk");
    }
  }

  _emitCalibState(state, reason) {
    this.onCalibState?.({
      state,
      reason,
      requestId: this._calib.requestId,
      target: this._calib.target,
      detail: this.calibrationState(),
    });
  }
}

// ================================================================ CalibFile（vfc-calibration/v1，docs §6.2）

/**
 * 校准文件 = **model + cameraProfile 维度**（换模型 / 换机 / 换镜头各存各的）。
 *
 * 字段口径：schema 骨架以 docs/post-pipeline-migration.md §6.2 为准；expression
 * 子树做「模块 serialize 形状 ↔ 文档字段名」双向映射（docExpressionFromModule /
 * moduleExpressionFromDoc），额外保真字段（meanEAR / stdMAR / isCalibrated 等）
 * 按文档规则随子对象一起存（schema 不认识的字段忽略、缺字段按默认值）。
 * checksum = sha256（WebCrypto；不可用时省略，加载端缺 checksum 按已校验处理）。
 */
export const CALIB_FILE_SCHEMA = "vfc-calibration/v1";

/** CalibFile.expression（文档字段名）← ExpressionCalibrator.serialize()（模块形状）。 */
export function docExpressionFromModule(expr) {
  const e = expr && typeof expr === "object" ? expr : {};
  const eye = e.eye ?? {};
  const left = eye.left ?? {};
  const right = eye.right ?? {};
  const mouth = e.mouth ?? {};
  const smile = e.smile ?? {};
  return {
    eyeLeft: {
      closed: left.closedRatio, open: left.openRatio, wide: left.wideRatio,
      calibPitch: eye.calibrationPitch, calibYaw: eye.calibrationYaw, calibFaceRatio: eye.calibrationFaceRatio,
      meanEAR: left.meanEAR, stdEAR: left.stdEAR, isCalibrated: left.isCalibrated,
    },
    eyeRight: {
      closed: right.closedRatio, open: right.openRatio, wide: right.wideRatio,
      calibPitch: eye.calibrationPitch, calibYaw: eye.calibrationYaw, calibFaceRatio: eye.calibrationFaceRatio,
      meanEAR: right.meanEAR, stdEAR: right.stdEAR, isCalibrated: right.isCalibrated,
    },
    mouth: {
      closed: mouth.closedRatio, open: mouth.openRatio, calibPitch: mouth.calibPitch,
      meanMAR: mouth.meanMAR, stdMAR: mouth.stdMAR, isCalibrated: mouth.isCalibrated,
    },
    smile: {
      baselineLeft: smile.baselineLeftFeature, baselineRight: smile.baselineRightFeature,
      observedSmileMax: smile.observedSmileMax, observedFrownMax: smile.observedFrownMax,
      stdLeft: smile.stdLeftFeature, stdRight: smile.stdRightFeature, isCalibrated: smile.isCalibrated,
    },
    advanced: e.advanced ?? undefined,
    eyeSensitivity: eye.sensitivity,
  };
}

/** ExpressionCalibrator.load() 的入参（模块形状）← CalibFile.expression（文档字段名）。 */
export function moduleExpressionFromDoc(doc) {
  const d = doc && typeof doc === "object" ? doc : {};
  const l = d.eyeLeft ?? {};
  const r = d.eyeRight ?? {};
  const mouth = d.mouth ?? {};
  const smile = d.smile ?? {};
  return {
    eye: {
      left: {
        closedRatio: l.closed, openRatio: l.open, wideRatio: l.wide,
        meanEAR: l.meanEAR, stdEAR: l.stdEAR, isCalibrated: l.isCalibrated,
      },
      right: {
        closedRatio: r.closed, openRatio: r.open, wideRatio: r.wide,
        meanEAR: r.meanEAR, stdEAR: r.stdEAR, isCalibrated: r.isCalibrated,
      },
      sensitivity: d.eyeSensitivity,
      calibrationPitch: l.calibPitch ?? r.calibPitch,
      calibrationYaw: l.calibYaw ?? r.calibYaw,
      calibrationFaceRatio: l.calibFaceRatio ?? r.calibFaceRatio,
    },
    mouth: {
      closedRatio: mouth.closed, openRatio: mouth.open,
      meanMAR: mouth.meanMAR, stdMAR: mouth.stdMAR, isCalibrated: mouth.isCalibrated,
    },
    smile: {
      baselineLeftFeature: smile.baselineLeft, baselineRightFeature: smile.baselineRight,
      observedSmileMax: smile.observedSmileMax, observedFrownMax: smile.observedFrownMax,
      stdLeftFeature: smile.stdLeft, stdRightFeature: smile.stdRight, isCalibrated: smile.isCalibrated,
    },
    advanced: d.advanced,
  };
}

/** 组装 CalibFile（app.js 提供 model / cameraProfile / pcOffset / rangeScale）。 */
export function buildCalibrationFile(processor, meta = {}) {
  return calibrationFileFromParts(processor.serializeCalibrationParts(), meta);
}

/** 从校准子树（serializeCalibrationParts 的形状）组装 CalibFile。 */
export function calibrationFileFromParts(parts, meta = {}) {
  return {
    schema: CALIB_FILE_SCHEMA,
    model: meta.model ?? "",
    cameraProfile: meta.cameraProfile ?? null,
    createdAt: Date.now(),
    source: meta.source ?? "auto",
    pose: {
      baseline: parts.pose.baseline,
      calibrated: parts.pose.calibrated,
      pcOffset: meta.pcOffset ?? { pitch: 0, yaw: 0, roll: 0 },
      rangeScale: Number.isFinite(meta.rangeScale) ? meta.rangeScale : 1,
    },
    expression: docExpressionFromModule(parts.expression),
    body: parts.body,
  };
}

/** 把 CalibFile 注入处理器（schema 不符按未校准处理）。 */
export function applyCalibrationFile(processor, file, meta = {}) {
  if (!file || file.schema !== CALIB_FILE_SCHEMA) return false;
  processor.loadCalibrationParts({
    pose: file.pose,
    expression: moduleExpressionFromDoc(file.expression),
    body: file.body,
  });
  return true;
}

/** CalibFile 校验和（sha256，WebCrypto；不可用返回 null，调用方省略 checksum 字段）。 */
export async function calibrationChecksum(file) {
  try {
    const subtle = globalThis.crypto?.subtle;
    if (!subtle) return null;
    const payload = JSON.stringify({ ...file, checksum: undefined });
    const digest = await subtle.digest("SHA-256", new TextEncoder().encode(payload));
    const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
    return "sha256:" + hex;
  } catch {
    return null;
  }
}

// ================================================================ WindowsPostPipeline（主线程编排：worker/同步 + 步骤 10~11）

/**
 * 主线程编排器：raw 帧进（Transferable）→ worker（或同步回退）跑步骤 2~9 →
 * frame-result 回主线程 → 步骤 10 参数映射（app.js 注入的 mapFrame，内部用
 * mapping.js/calibrate.js 单实例）→ 步骤 11 固定 500ms playout → onPlayout 交给
 * app.js 走 stage.pushTrackParams / setBodyParams（步骤 12 interp.js 保持不变）。
 *
 * 降级（docs 裁决 5）：worker 缺失 / 构造失败 / 运行时异常 → 同一个
 * RawFrameProcessor 在主线程直跑，onError 通知一次，UI 降级提示；--smoke 无 raw
 * 流时本类完全不启动也不影响渲染就绪。
 */
export class WindowsPostPipeline {
  /**
   * @param {{mapFrame?: (result:object)=>object|null, onPlayout?: (item:object)=>void,
   *          onCalibState?: (s:object)=>void, onStats?: (s:object)=>void,
   *          onError?: (e:object)=>void, workerUrl?:any, useWorker?:boolean,
   *          playoutDelayMs?:number, processorOptions?:object}} [options]
   */
  constructor(options = {}) {
    this.options = options;
    this.mapFrame = options.mapFrame ?? null;
    this.onPlayout = options.onPlayout ?? null;
    this.onCalibState = options.onCalibState ?? null;
    this.onStats = options.onStats ?? null;
    this.onError = options.onError ?? null;
    this.useWorker = options.useWorker !== false && typeof Worker !== "undefined";
    this.workerUrl = options.workerUrl ?? new URL("./worker/post-pipeline-worker.js", import.meta.url);
    this.worker = null;
    this.syncProcessor = null;
    this.workerFailed = false;
    this.mode = "unstarted"; // "worker" | "sync" | "unstarted"
    this.playout = new PlayoutBuffer({
      delayMs: options.playoutDelayMs ?? DEFAULT_PLAYOUT_DELAY_MS,
      onEmit: (payload) => this.onPlayout?.(payload),
    });
    this._statsAt = 0;
    this._pendingFrames = 0;
  }

  /** 启动（幂等）：优先 worker，失败回退同步。 */
  init() {
    if (this.mode !== "unstarted") return this.mode;
    if (this.useWorker) {
      try {
        const worker = new Worker(this.workerUrl, { type: "module", name: "vfc-post-pipeline" });
        worker.onmessage = (event) => this._onWorkerMessage(event.data);
        worker.onerror = (event) => {
          this._failToSync("worker-error", event?.message ?? String(event));
        };
        worker.onmessageerror = () => this._failToSync("worker-messageerror", "structured clone failed");
        this.worker = worker;
        this.mode = "worker";
        this._post({ type: "init", config: this._configPayload() });
        return this.mode;
      } catch (error) {
        this._failToSync("worker-construct", error?.message ?? String(error));
        return this.mode;
      }
    }
    this._startSync();
    return this.mode;
  }

  /** 释放（断开 / 页面卸载）。 */
  dispose() {
    if (this.worker) {
      try { this.worker.terminate(); } catch { /* ignore */ }
      this.worker = null;
    }
    this.syncProcessor = null;
    this.playout.reset();
    this.mode = "unstarted";
  }

  /**
   * 投喂一条 raw_track 二进制消息（WS 收到即转交，ArrayBuffer 以 Transferable 零拷贝移交）。
   * @param {ArrayBuffer} buffer
   * @param {number} [receivedAtMs] 本机到达时间（performance.now() 口径）
   */
  handleRawFrame(buffer, receivedAtMs) {
    const at = Number.isFinite(receivedAtMs) ? receivedAtMs : nowClock();
    this.init();
    if (this.mode === "worker" && this.worker) {
      this._pendingFrames += 1;
      try {
        this.worker.postMessage({ type: "frame", buffer, receivedAt: at }, [buffer]);
        return true;
      } catch (error) {
        // transfer 失败（buffer 已 detach 等）→ 降级同步前先把这一帧丢掉，不让渲染崩
        this._failToSync("post-frame", error?.message ?? String(error));
        return false;
      }
    }
    this.syncProcessor?.ingest(buffer, at);
    return true;
  }

  /** 无新帧时轮询（缺口超时放行；sync 模式由 playout 定时器顺带调）。 */
  poll(nowMs) {
    const at = Number.isFinite(nowMs) ? nowMs : nowClock();
    if (this.mode === "sync") this.syncProcessor?.poll(at);
    else if (this.mode === "worker") this._post({ type: "poll", nowMs: at });
  }

  /** 会话状态重置（新 hello / resetTracking / 断线 / 切模型）。 */
  reset(reason = "reset", keepCalibration = true) {
    this.playout.reset();
    this._pendingFrames = 0;
    if (this.mode === "worker") this._post({ type: "reset", reason, keepCalibration });
    else this.syncProcessor?.reset(reason, keepCalibration);
  }

  /** startStream / stopStream 本地闸门。 */
  setStreamActive(active) {
    if (this.mode === "worker") this._post({ type: "stream", active: active !== false });
    else this.syncProcessor?.setStreamActive(active);
  }

  /** 校准：recalibrate（face/body/all，幂等 + requestId）。 */
  recalibrate(target, requestId = null) {
    if (this.mode === "worker") {
      this._post({ type: "calib", action: "recalibrate", target, requestId });
      return { ok: true, requestId, target, deferred: true };
    }
    return this.syncProcessor ? this.syncProcessor.recalibrate(target, requestId) : { ok: false, reason: "not-ready" };
  }

  cancelCalibration() {
    if (this.mode === "worker") { this._post({ type: "calib", action: "cancel" }); return { ok: true, deferred: true }; }
    return this.syncProcessor?.cancelCalibration() ?? { ok: false, reason: "not-ready" };
  }

  finishCalibration() {
    if (this.mode === "worker") { this._post({ type: "calib", action: "finish" }); return { ok: true, deferred: true }; }
    return this.syncProcessor?.finishCalibration() ?? false;
  }

  restoreDefaultCalibration() {
    if (this.mode === "worker") { this._post({ type: "calib", action: "restore-default" }); return { ok: true, deferred: true }; }
    return this.syncProcessor?.restoreDefaultCalibration() ?? { ok: false, reason: "not-ready" };
  }

  /** 取校准状态（sync 直接读；worker 用最近一次 calib-state 快照）。 */
  calibrationState() {
    if (this.mode === "sync") return this.syncProcessor?.calibrationState() ?? null;
    return this._lastCalibState ?? null;
  }

  /**
   * 组装 CalibFile（含 checksum；app.js 负责持久化与导出）。
   * worker 模式下向 worker 要校准子树（calib-parts 应答），带 500 ms 超时兜底。
   */
  async buildCalibrationFile(meta = {}) {
    let parts = null;
    if (this.mode === "sync" && this.syncProcessor) {
      parts = this.syncProcessor.serializeCalibrationParts();
    } else {
      parts = await this._requestParts();
    }
    if (!parts) return null;
    const file = calibrationFileFromParts(parts, meta);
    const sum = await calibrationChecksum(file);
    if (sum) file.checksum = sum;
    return file;
  }

  /** 向 worker 请求校准子树（calib-parts），500 ms 超时返回 null。 */
  _requestParts() {
    if (!this.worker) return Promise.resolve(null);
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (this._partsResolve === resolve) this._partsResolve = null;
        resolve(null);
      }, 500);
      this._partsResolve = (parts) => {
        clearTimeout(timer);
        if (this._partsResolve === resolve) this._partsResolve = null;
        resolve(parts);
      };
      this._post({ type: "calib", action: "serialize" });
    });
  }

  /** 注入 CalibFile（app.js 从 localStorage / 导入文件读到后调用）。 */
  loadCalibrationFile(file) {
    if (this.mode === "sync" && this.syncProcessor) return applyCalibrationFile(this.syncProcessor, file);
    this._post({ type: "calib", action: "load", payload: file });
    return true;
  }

  /** 热更新配置（平滑参数 / playout 延迟；不动校准）。 */
  setConfig(patch = {}) {
    if (Number.isFinite(patch.playoutDelayMs)) {
      this.playout.delayMs = Math.max(0, patch.playoutDelayMs);
    }
    if (this.mode === "worker") this._post({ type: "config", patch });
    else if (this.syncProcessor && patch.smoothing) {
      // 同步路径：重建 temporal filter 配置（保留其余状态）
      this.syncProcessor.temporal = createTemporalFilter({ smoothing: patch.smoothing });
    }
  }

  /** 诊断快照（调试面板 / __vfcDebug.raw）。 */
  stats() {
    return {
      mode: this.mode,
      workerFailed: this.workerFailed,
      pendingFrames: this._pendingFrames,
      playout: this.playout.stats(),
      processor: this._lastStats ?? (this.syncProcessor ? this.syncProcessor.stats() : null),
    };
  }

  // ---------------------------------------------------------------- 内部

  _configPayload() {
    return {
      smoothing: this.options.processorOptions?.smoothing ?? {},
      advancedMode: this.options.processorOptions?.advancedMode === true,
      cameraFovDeg: this.options.processorOptions?.cameraFovDeg,
    };
  }

  _post(message) {
    if (!this.worker) return false;
    try {
      this.worker.postMessage(message);
      return true;
    } catch {
      return false;
    }
  }

  _startSync() {
    if (this.syncProcessor) return;
    this.syncProcessor = new RawFrameProcessor({
      ...(this.options.processorOptions ?? {}),
      onFrame: (result) => this._onFrameResult(result),
      onCalibState: (state) => this._onCalibState(state),
    });
    this.mode = "sync";
  }

  _failToSync(code, message) {
    if (this.workerFailed && this.mode === "sync") return;
    this.workerFailed = true;
    try { this.worker?.terminate(); } catch { /* ignore */ }
    this.worker = null;
    this._startSync();
    this.onError?.({ code, message, recoverable: true, fallback: "sync" });
  }

  _onWorkerMessage(message) {
    if (!message || typeof message !== "object") return;
    switch (message.type) {
      case "frame-result":
        this._pendingFrames = Math.max(0, this._pendingFrames - 1);
        this._onFrameResult(message.result);
        break;
      case "calib-state":
        this._onCalibState(message.state);
        break;
      case "calib-parts":
        this._lastParts = message.parts ?? null;
        this._partsResolve?.(this._lastParts);
        break;
      case "stats":
        this._lastStats = message.stats;
        this.onStats?.(message.stats);
        break;
      case "error":
        if (message.fatal) this._failToSync(message.code ?? "worker-fatal", message.message ?? "worker error");
        else this.onError?.({ code: message.code, message: message.message, recoverable: true });
        break;
      default:
        break;
    }
  }

  _onFrameResult(result) {
    // 步骤 10：参数映射（app.js 注入；mapping.js / calibrate.js 的单实例在这里复用）
    const mapped = this.mapFrame ? this.mapFrame(result) : null;
    if (!mapped) return;
    // 步骤 11：固定 500ms playout（全链唯一人为延迟）
    this.playout.push({ t: result.t, frameId: result.frameId }, mapped, nowClock());
    if (nowClock() - this._statsAt > 250) {
      this._statsAt = nowClock();
      this.onStats?.(this.stats());
    }
  }

  _onCalibState(state) {
    this._lastCalibState = state;
    this.onCalibState?.(state);
  }
}


