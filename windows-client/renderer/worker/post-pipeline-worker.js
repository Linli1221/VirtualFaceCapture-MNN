/**
 * worker/post-pipeline-worker.js — raw_track 后处理链的 Web Worker 薄适配层（task-4）
 *
 * 职责边界：只做「消息编解码 + Transferable 移交 + 调 RawFrameProcessor」；
 * 算法本体全部在 renderer/ 下的 task-3 模块（经 windows-post-pipeline.js 编排）。
 *
 * 跑的是 12 步链的**步骤 2~9**（重排 → 丢过期 → 帧间隔 → 点域 TimeBasedEMA →
 * BlendShape 提取 → 校准 → 眨眼 → 上半身融合）；步骤 10（参数映射）与 11（固定
 * 500ms playout）留在主线程（见 windows-post-pipeline.js 头部的实现层偏差说明）。
 *
 * 消息协议（与 win-integrate 的契约，Transferable 已标）：
 *   main → worker
 *     {type:"init", config:{smoothing, advancedMode, cameraFovDeg}}
 *     {type:"frame", buffer:ArrayBuffer, receivedAt:number}   transfer:[buffer]
 *     {type:"poll", nowMs:number}      // 缺口超时放行（playout 定时器顺带发）
 *     {type:"reset", reason, keepCalibration}
 *     {type:"stream", active:boolean}
 *     {type:"calib", action:"recalibrate"|"cancel"|"finish"|"restore-default"|"serialize"|"load",
 *      target?, requestId?, payload?}
 *     {type:"config", patch:{smoothing?}}
 *   worker → main
 *     {type:"frame-result", result:{frameId, seq, epoch, t, bs52:Float32Array(52),
 *       pose:Float32Array(3), ub:{points:Float32Array(52), torso:Float32Array(2), ...}|null, ...}}
 *       transfer:[bs52.buffer, pose.buffer, (ub.points.buffer, ub.torso.buffer)]
 *     {type:"calib-state", state:{...}} / {type:"calib-parts", parts:{pose, expression, body}}
 *     {type:"stats", stats:{...}}（节流 ~4 Hz）
 *     {type:"error", code, message, fatal}
 *
 * 禁用 SharedArrayBuffer（crossOriginIsolated=false）；跨线程一律 Transferable /
 * structured clone。worker 构造失败或本文件异常时主线程回退同步路径（裁决 5），
 * 因此这里绝不抛未捕获异常到影响渲染。
 */
import { RawFrameProcessor, applyCalibrationFile } from "../windows-post-pipeline.js";

let processor = null;
let lastStatsAt = 0;

function post(message, transfer) {
  if (transfer && transfer.length) self.postMessage(message, transfer);
  else self.postMessage(message);
}

function postError(code, message, fatal = false) {
  post({ type: "error", code, message: String(message ?? ""), fatal });
}

function ensureProcessor(config = {}) {
  if (processor) return processor;
  processor = new RawFrameProcessor({
    smoothing: config.smoothing ?? {},
    advancedMode: config.advancedMode === true,
    cameraFovDeg: config.cameraFovDeg,
    onFrame: (result) => {
      // Transferable 移交：bs52 / pose / ub.points / ub.torso 一次性零拷贝交给主线程
      const transfer = [result.bs52.buffer, result.pose.buffer];
      if (result.ub?.points?.buffer) transfer.push(result.ub.points.buffer);
      if (result.ub?.torso?.buffer) transfer.push(result.ub.torso.buffer);
      post({ type: "frame-result", result }, transfer);
    },
    onCalibState: (state) => post({ type: "calib-state", state }),
  });
  return processor;
}

function maybeStats() {
  const now = typeof performance !== "undefined" ? performance.now() : Date.now();
  if (now - lastStatsAt < 250) return;
  lastStatsAt = now;
  if (processor) post({ type: "stats", stats: processor.stats() });
}

self.onmessage = (event) => {
  const message = event?.data;
  if (!message || typeof message !== "object") return;
  try {
    switch (message.type) {
      case "init":
        ensureProcessor(message.config ?? {});
        break;
      case "config": {
        const p = ensureProcessor();
        // 只重建 temporal filter 配置（其余状态不动），见 RawFrameProcessor.setSmoothing
        if (message.patch?.smoothing) p.setSmoothing(message.patch.smoothing);
        break;
      }
      case "frame": {
        const p = ensureProcessor();
        p.ingest(message.buffer, message.receivedAt);
        maybeStats();
        break;
      }
      case "poll":
        ensureProcessor().poll(message.nowMs);
        break;
      case "reset":
        ensureProcessor().reset(message.reason ?? "reset", message.keepCalibration !== false);
        break;
      case "stream":
        ensureProcessor().setStreamActive(message.active);
        break;
      case "calib": {
        const p = ensureProcessor();
        switch (message.action) {
          case "recalibrate":
            p.recalibrate(message.target ?? "face", message.requestId ?? null);
            break;
          case "cancel":
            p.cancelCalibration();
            break;
          case "finish":
            p.finishCalibration();
            break;
          case "restore-default":
            p.restoreDefaultCalibration();
            break;
          case "serialize":
            post({ type: "calib-parts", parts: p.serializeCalibrationParts() });
            break;
          case "load":
            if (message.payload) {
              applyCalibrationFile(p, message.payload);
            }
            break;
          default:
            break;
        }
        break;
      }
      default:
        break;
    }
  } catch (error) {
    // 不让 worker 崩掉：报错并标注是否需要主线程接管（解析类错误不致命）
    postError("worker-exception", error?.stack ?? error?.message ?? String(error), false);
  }
};
