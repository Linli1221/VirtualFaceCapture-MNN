package com.example.virtualfacecapture

import android.content.Context
import android.util.Log
import com.example.commondata.UpperBodyPoseFrame
import com.example.mediapipefacelandmark.YuvFrameData
import com.example.mediapipeupperbody.UpperBodyPoseDetector
import com.example.mediapipeupperbody.Yuv420Frame
import java.util.concurrent.atomic.AtomicLong

/**
 * 上半身关键点采集的 App 侧总控（契约见 docs/upper-body.md §3.5）。
 *
 * 职责边界：
 *  - 持有 `:MediapipeUpperBody` 的 [UpperBodyPoseDetector]，管它的初始化 / 启停 / 释放；
 *  - 把相机线程上的 [YuvFrameData] 转成模块认识的 [Yuv420Frame]（**不拷贝像素**，
 *    ByteBuffer 直接透传，因为检测器要求在相机回调里同步转换，见 §3.2）；
 *  - 把结果的 [UpperBodyPoseFrame] 分发给 UI（预览骨架 / HUD）和网络（由 MainActivity 接线，
 *    本类**不依赖** tracking 包）。
 *
 * 线程模型：
 *  - [pushYuvFrame] 由**相机线程**同步调用，只做「判活 + 转对象 + 交给检测器」，不阻塞、不抛异常；
 *  - [onFrame] / [onOverlay] 在 MediaPipe 回调线程触发（§3.2 第 7 条），上层自己切线程 / 节流；
 *  - [initialize] / [start] / [stop] / [release] 内部有锁，主线程可以直接调；但 initialize
 *    要加载模型（几百毫秒），建议放到后台线程（MainActivity 就是这么做的）。
 *
 * 生命周期：默认关闭。关闭时相机线程上不挂 tap（faceTracker.onYuvFrameForBody = null），
 * 因此没有任何逐帧开销，也不会加载 MediaPipe Tasks 运行时。
 *
 * @param context 用于读取 assets / filesDir 里的模型（交给检测器）
 */
class UpperBodyPoseController(private val context: Context) {

    companion object {
        private const val TAG = "UpperBodyPose"

        /** 相机 YUV 抽帧间隔：66ms ≈ 15fps（与 faceTracker.bodyTapIntervalMs 默认值一致）。 */
        const val TAP_INTERVAL_MS = 66L

        /** Lite 模型文件名（与 UpperBodyPoseDetector.MODEL_LITE 一致，这里只用于中文提示）。 */
        const val MODEL_LITE = "pose_landmarker_lite.task"

        /** 模型缺失时给用户的指引（仓库自带下载脚本）。 */
        private const val FETCH_MODEL_HINT = "请运行 tools/fetch-pose-model.ps1"
    }

    /** 控制器状态机。 */
    enum class State {
        /** 未开启 / 已停止。 */
        OFF,

        /** 模型缺失（未放 pose_landmarker_lite.task），功能不可用。 */
        UNAVAILABLE,

        /** 正在初始化 / 启动。 */
        STARTING,

        /** 正在采集。 */
        ON,

        /** 初始化或启动失败。 */
        ERROR,
    }

    /** 控制器 + 检测器的运行统计快照（HUD / 日志用）。 */
    data class Stats(
        val framesIn: Long,
        val framesOut: Long,
        val failures: Long,
        val dropped: Long,
        val avgLatencyMs: Float,
        val outputFps: Float,
    )

    // ---- 对外状态 ---------------------------------------------------------

    @Volatile
    var state: State = State.OFF
        private set

    /** 是否正在采集（读无锁，等价于 state == ON）。 */
    val isRunning: Boolean get() = running

    /** 最近 1 秒实际产出的上半身帧率（HUD 用）。 */
    @Volatile
    var outputFps: Float = 0f
        private set

    /** 每帧结果（约 15fps，在 MediaPipe 回调线程触发）。 */
    var onFrame: ((UpperBodyPoseFrame) -> Unit)? = null

    /** 预览叠加层：正常帧 / 停止时 null（用于清空骨架）。 */
    var onOverlay: ((UpperBodyPoseFrame?) -> Unit)? = null

    /** 状态与错误的中文提示（可能从任意线程触发）。 */
    var onStatus: ((String) -> Unit)? = null

    /**
     * 当前摄像头的画面是否镜像（前置 = true）。
     *
     * 检测器按这个值把输入像素镜像后再推理，所以产出的 [UpperBodyPoseFrame.points] 的
     * x/y 已经是「预览方向」的坐标（§3.2 第 2 条），叠加层直接按 x 映射即可。
     * 由 MainActivity 在 onCameraChanged 里设置。
     */
    @Volatile
    var mirrorHorizontal: Boolean = true

    // ---- 内部实现 ---------------------------------------------------------

    private val detector = UpperBodyPoseDetector(context)

    /** 保护 initialized / running / 检测器生命周期。 */
    private val monitor = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var running = false

    // 统计计数器（相机线程 / 回调线程都会写，用原子量）
    private val framesIn = AtomicLong(0)
    private val framesOut = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val outputFrameCount = AtomicLong(0)

    @Volatile
    private var fpsWindowStartMs: Long = 0L

    // ========================================================================
    // 生命周期
    // ========================================================================

    /**
     * 加载模型并初始化 MediaPipe 任务（幂等）。
     *
     * @return true = 可以 [start]；false = 模型缺失 / 初始化失败，此时 [state] 为
     *         [State.UNAVAILABLE] 或 [State.ERROR]，[onStatus] 里有中文原因与操作指引。
     */
    fun initialize(): Boolean {
        synchronized(monitor) {
            if (initialized) return true
            state = State.STARTING

            val available = try {
                detector.isModelAvailable()
            } catch (t: Throwable) {
                Log.e(TAG, "检查上半身模型失败", t)
                false
            }
            if (!available) {
                state = State.UNAVAILABLE
                notifyStatus("未找到 " + MODEL_LITE + "，" + FETCH_MODEL_HINT + " 下载模型后重试")
                return false
            }

            detector.onResult = { frame -> dispatch(frame) }
            detector.onError = { message -> onDetectorError(message) }

            val ok = try {
                detector.initialize()
            } catch (t: Throwable) {
                Log.e(TAG, "上半身检测器初始化异常", t)
                false
            }
            if (!ok) {
                state = State.ERROR
                notifyStatus("上半身检测器初始化失败（已自动关闭该功能，细节见 logcat）")
                return false
            }

            initialized = true
            state = State.OFF
            Log.i(TAG, "上半身检测器已就绪")
            return true
        }
    }

    /**
     * 开始接收帧（幂等）。未初始化时会先自动 [initialize] 一次。
     *
     * @return true = 已进入 [State.ON]。
     */
    fun start(): Boolean {
        synchronized(monitor) {
            if (running) return true
            if (!initialized && !initialize()) return false

            state = State.STARTING
            val ok = try {
                detector.start()
                // 检测器的 start() 是 Unit：未就绪时它只回调 onError 并忽略，
                // 所以这里再用 isReady() 复核一次，避免状态机谎报 ON。
                detector.isReady()
            } catch (t: Throwable) {
                Log.e(TAG, "上半身检测器启动失败", t)
                false
            }
            if (!ok) {
                state = State.ERROR
                notifyStatus("上半身启动失败（已保持关闭）")
                return false
            }

            running = true
            state = State.ON
            framesIn.set(0)
            framesOut.set(0)
            outputFrameCount.set(0)
            fpsWindowStartMs = 0L
            outputFps = 0f
            Log.i(TAG, "上半身关键点采集已开启（tap 间隔 " + TAP_INTERVAL_MS + "ms）")
            return true
        }
    }

    /** 停止采集（幂等）。会清空预览骨架。 */
    fun stop() {
        synchronized(monitor) {
            val wasRunning = running
            running = false
            if (wasRunning) {
                try {
                    detector.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "停止上半身检测器失败", t)
                }
                Log.i(TAG, "上半身关键点采集已停止")
            }
            if (state == State.ON || state == State.STARTING) {
                state = State.OFF
            }
            outputFps = 0f
            outputFrameCount.set(0)
            fpsWindowStartMs = 0L
        }
        // 在锁外通知 UI：骨架要立刻清掉，否则画面会停在上一次结果上
        notifyOverlay(null)
    }

    /** 释放（Activity onDestroy）：停止并放掉 MediaPipe 运行时。 */
    fun release() {
        synchronized(monitor) {
            running = false
            initialized = false
            try {
                detector.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "停止上半身检测器失败", t)
            }
            try {
                detector.release()
            } catch (t: Throwable) {
                Log.w(TAG, "释放上半身检测器失败", t)
            }
            detector.onResult = null
            detector.onError = null
            state = State.OFF
            outputFps = 0f
        }
        notifyOverlay(null)
        onFrame = null
        onOverlay = null
        onStatus = null
        Log.i(TAG, "上半身控制器已释放")
    }

    /** 重取躯干中性基线（切摄像头 / 点「重置基线」时调用）；未初始化时是空操作。 */
    fun resetBaseline() {
        if (!initialized) return
        try {
            detector.resetBaseline()
            Log.i(TAG, "上半身躯干基线已重置")
        } catch (t: Throwable) {
            Log.w(TAG, "重置上半身基线失败", t)
        }
    }

    // ========================================================================
    // 数据入口
    // ========================================================================

    /**
     * 把相机线程上的 YUV 帧喂给检测器。
     *
     * **只能在 [com.facecapture.sdk.PipelineFaceTracker.onYuvFrameForBody] 回调里同步调用**：
     * 检测器会在这里立刻完成 YUV → ARGB Bitmap 的转换（像素不能跨线程存活），之后异步推理。
     * 本方法不抛异常，任何失败只记日志并计入统计。
     */
    fun pushYuvFrame(frame: YuvFrameData) {
        // 关闭时相机侧根本不会调到这里（tap = null），这只是防御
        if (!running) return
        if (frame.width <= 0 || frame.height <= 0) return

        try {
            val yuv = Yuv420Frame(
                // duplicate()：与面捕共用同一块像素内存（不拷贝），但位置指针独立 ——
                // 面捕的两条通道稍后还会用同一批 ByteBuffer 做相对读取，不能被这边改掉 position。
                y = frame.yBuffer.duplicate(),
                u = frame.uBuffer.duplicate(),
                v = frame.vBuffer.duplicate(),
                width = frame.width,
                height = frame.height,
                yRowStride = frame.yRowStride,
                uvRowStride = frame.uvRowStride,
                uvPixelStride = frame.uvPixelStride,
                rotationDegrees = frame.rotation,
                mirrorHorizontal = mirrorHorizontal,
            )
            framesIn.incrementAndGet()
            detector.pushFrame(yuv)
        } catch (t: Throwable) {
            val total = failures.incrementAndGet()
            // 只在第一次 / 每 100 次打日志：相机线程上不做无谓的字符串拼接
            if (total == 1L || total % 100L == 0L) {
                Log.e(TAG, "上半身帧转换/入队失败（累计 " + total + " 次）", t)
            }
        }
    }

    /** 运行统计快照（HUD / 日志用）；检测器没就绪时也能安全返回。 */
    fun getStats(): Stats {
        val detectorStats = try {
            if (initialized) detector.getStats() else null
        } catch (t: Throwable) {
            Log.w(TAG, "读取上半身检测器统计失败", t)
            null
        }
        return Stats(
            framesIn = framesIn.get(),
            framesOut = framesOut.get(),
            failures = failures.get(),
            dropped = detectorStats?.dropped ?: 0L,
            avgLatencyMs = detectorStats?.avgLatencyMs ?: 0f,
            outputFps = outputFps,
        )
    }

    /** 状态的中文短标签（HUD / 控制面板用）。 */
    fun stateLabelZh(): String = when (state) {
        State.OFF -> "关"
        State.UNAVAILABLE -> "缺少模型"
        State.STARTING -> "启动中"
        State.ON -> "运行中"
        State.ERROR -> "出错"
    }

    // ========================================================================
    // 内部：结果分发 / 状态提示
    // ========================================================================

    /** 检测器结果（MediaPipe 回调线程）。 */
    private fun dispatch(frame: UpperBodyPoseFrame) {
        if (!running) return
        framesOut.incrementAndGet()
        updateOutputFps()

        val frameConsumer = onFrame
        if (frameConsumer != null) {
            try {
                frameConsumer(frame)
            } catch (t: Throwable) {
                failures.incrementAndGet()
                Log.e(TAG, "上半身结果分发失败", t)
            }
        }
        // 叠加层和网络输出解耦：网络那条抛异常也要让预览继续画
        notifyOverlay(frame)
    }

    private fun notifyOverlay(frame: UpperBodyPoseFrame?) {
        val consumer = onOverlay ?: return
        try {
            consumer(frame)
        } catch (t: Throwable) {
            failures.incrementAndGet()
            Log.e(TAG, "上半身叠加层回调失败", t)
        }
    }

    private fun notifyStatus(message: String) {
        val consumer = onStatus ?: return
        try {
            consumer(message)
        } catch (t: Throwable) {
            Log.e(TAG, "上半身状态回调失败", t)
        }
    }

    /**
     * 检测器报错。
     *
     * 契约 §3.2 第 5 条：GPU delegate 创建失败会自动回退 CPU，此时 onError 只是一次提示，
     * 所以这里**不能**把 state 改成 ERROR，只记日志 + 提示用户。
     */
    private fun onDetectorError(message: String) {
        Log.w(TAG, "上半身检测器提示: " + message)
        notifyStatus("上半身: " + message)
    }

    /** 每秒滚动一次实际产出帧率（只在 MediaPipe 回调线程上调用）。 */
    private fun updateOutputFps() {
        val count = outputFrameCount.incrementAndGet()
        val now = System.currentTimeMillis()
        if (fpsWindowStartMs == 0L) {
            fpsWindowStartMs = now
            return
        }
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= 1000L) {
            outputFps = count * 1000f / elapsed
            outputFrameCount.set(0)
            fpsWindowStartMs = now
        }
    }
}
