package com.facecapture.sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vfc.log.VfcLog as Log
import com.example.commondata.ARKitBlendShapes
import com.example.commondata.CameraDeviceInfo
import com.example.commondata.DetectionBox
import com.example.commondata.FaceVisualizationData
import com.example.commondata.HeadPoseData
import com.example.commondata.Point3D
import com.example.mediapipefacelandmark.Camera2Manager
import com.example.mediapipefacelandmark.MediaPipeFaceDetector
import com.example.mediapipefacelandmark.MnnBackend
import com.example.mediapipefacelandmark.YuvFrameData
import com.live2d.facecapture.BlinkCurveProcessor
import com.live2d.facecapture.FaceCapturePipeline
import com.live2d.facecapture.TrackingConfidenceDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Pipeline-based Face Tracker with TRUE parallel processing stages.
 *
 * This is the **direct / advanced** API of the face-capture AAR. It owns its
 * own [Camera2Manager], so callers only need to construct it, wire the
 * callbacks they care about and call [start] / [stop].
 *
 * For a stricter, IO-agnostic facade see [FaceCaptureEngine].
 *
 * Architecture (Two-Stage Parallel Pipeline):
 *
 * The key insight is that Face Detection and Landmark Detection use SEPARATE
 * MNN Sessions, so they CAN run in parallel.
 *
 * - Stage 1: Face Detection (~4ms)      - finds face bounding box
 * - Stage 2: Landmark Detection (~24ms) - uses PREVIOUS frame's bbox
 * - Stage 3: BlendShape Processing (~10ms) - uses current landmarks
 *
 * This achieves: max(4ms Det, 24ms Landmark) = 24ms = ~41 FPS theoretical.
 *
 * Note: Using previous frame's bbox is acceptable because face position
 * changes very little between consecutive frames at 30+ FPS.
 */
class PipelineFaceTracker(private val context: Context) {

    companion object {
        private const val TAG = "PipelineFaceTracker"

        // Pipeline configuration
        private const val DETECTION_CHANNEL_CAPACITY = 2
        private const val LANDMARK_CHANNEL_CAPACITY = 3
        private const val PROCESSING_CHANNEL_CAPACITY = 4
        private const val FPS_UPDATE_INTERVAL = 1000L

        /** 乱序关键点结果通道容量（多路推理写入，重排阶段消费）。 */
        private const val LANDMARK_MERGE_CAPACITY = 8

        /** 重排缓冲最多缓存多少帧，超过就认定缺口补不上、直接跳过。 */
        private const val REORDER_PENDING_CAPACITY = 8

        /** 等一个缺口（某帧还在另一路上算）的最长时间。 */
        private const val REORDER_GAP_TIMEOUT_MS = 60L

        /**
         * 关键点推理并行度（GPU 引擎个数，**含主引擎**）的默认值。
         *
         * 关键点阶段占掉一帧 80% 的推理时间，单路 Vulkan 时 GPU 利用率并不高：
         * 一次推理里还夹着预处理、命令录制、回读这些 CPU 段。多开一路引擎后，
         * 两路各自交错跑自己的 CPU 段、GPU 命令队列始终有活儿，利用率明显上去。
         *
         * 1 = 单路（历史行为）；2 = 主引擎 + 1 路额外 GPU 引擎。
         */
        private const val GPU_CHANNEL_DEFAULT = 2

        /** 允许的最大并行度（含主引擎），与原生侧 kMaxEngines 一致。 */
        const val MAX_GPU_CHANNELS = 1 + MediaPipeFaceDetector.MAX_EXTRA_ENGINES

        /**
         * 稳定追踪时人脸检测的最小间隔（毫秒）。
         *
         * 人脸框在 100ms 内的位移极小，但每次检测都要占满一次完整推理并和
         * landmark 阶段抢 CPU/GPU。按 10Hz 的节奏刷新脸框，把省下来的算力留给
         * landmark（真正的瓶颈），帧时间会更平稳。
         *
         * 脸框失效（掉脸 / 低置信度主动重置）时会忽略该间隔、立即重检。
         */
        private const val DETECTION_INTERVAL_MS_STABLE = 100L

        /**
         * 脸框提示的有效期（毫秒）。
         *
         * 上半身 tap 在相机线程上每 [bodyTapIntervalMs] 取一次最近脸框；超过这个
         * 年龄还没刷新（掉脸 / 检测节流 / 关键点触发重检）就认定提示过期，传 null
         * 让上半身回退到整帧 ROI，避免拿一个已经跑偏的框去裁人。
         */
        private const val FACE_BOX_HINT_TTL_MS = 500L

        /**
         * 帧年龄上限（毫秒）。超过这个年龄才被处理到的帧只会增加延迟，
         * 对流畅度没有任何帮助，直接丢弃。
         */
        private const val MAX_FRAME_AGE_MS = 300L

        // Performance profiling
        private const val ENABLE_PERF_PROFILING = true
        private const val PERF_LOG_INTERVAL = 60
    }

    // Performance stats
    private data class PerfStats(
        var total: Double = 0.0,
        var min: Double = Double.MAX_VALUE,
        var max: Double = 0.0,
        var count: Int = 0,
    ) {
        fun add(ms: Double) {
            total += ms
            min = minOf(min, ms)
            max = maxOf(max, ms)
            count++
        }
        fun avg(): Double = if (count > 0) total / count else 0.0
        fun reset() { total = 0.0; min = Double.MAX_VALUE; max = 0.0; count = 0 }
    }

    /** [logPerfStats] 的锁内快照：取值 + 重置都在锁里，格式化/日志在锁外。 */
    private class PerfSnapshot(
        val detAvg: Double,
        val lmAvg: Double,
        val procAvg: Double,
        val bsAvg: Double,
        val applyAvg: Double,
        val detRuns: Int,
        val detSkips: Int,
        val redetects: Int,
        val staleDrops: Int,
    )

    private val perfDetectionTotal = PerfStats()
    private val perfLandmarkTotal = PerfStats()
    private val perfProcessingTotal = PerfStats()
    private val perfBlendShape = PerfStats()
    private val perfApplyToLive2D = PerfStats()
    private var perfFrameCount = 0
    private val perfLock = Any()

    // ---- 帧间隔抖动统计 ----------------------------------------------------
    // 「稳定 60 帧」看的是抖动（p95 / max），不只是平均帧率：平均 60 但每 10 帧
    // 卡一次 40ms 的体验比稳定 55 帧还差。这里只保留最近 120 个帧间隔（约 2s）。
    private val FRAME_INTERVAL_WINDOW = 120
    private val frameIntervalsMs = FloatArray(FRAME_INTERVAL_WINDOW)
    private var frameIntervalCursor = 0
    private var frameIntervalFilled = 0
    private var lastProcessedFrameNanos = 0L

    private var detectionRunCount = 0
    private var detectionSkipCount = 0
    private var redetectTriggerCount = 0
    private var staleFrameDropCount = 0

    // Core components
    private val camera2Manager = Camera2Manager(context)
    private val faceDetector = MediaPipeFaceDetector(context)
    val faceCapturePipeline: FaceCapturePipeline = FaceCapturePipeline()
    val blinkCurveProcessor = BlinkCurveProcessor()

    /**
     * 最近一次检测到的人脸框（**一级扩充后**的脸 ROI，归一化、旋转后坐标）。
     *
     * 语义变化：检测阶段写入前会先过 [expandFaceBox]（以框中心放大 1.15x），
     * 所以这里存的不是裸检测框，而是给 landmarker 的二级扩充（原生 1.5x）
     * 和上半身 ROI 用的「脸 ROI」。[lastFaceBoxTimestampMs] 记录写入时刻，
     * 供相机线程的 tap 判断提示是否新鲜。
     */
    @Volatile private var lastFaceBox: MediaPipeFaceDetector.FaceBox? = null

    /** [lastFaceBox] 的写入时间戳（毫秒）；0 = 没有可用脸框。 */
    @Volatile private var lastFaceBoxTimestampMs: Long = 0L

    private val faceBoxLock = Any()

    /** 累计的 stop() 请求数，用于在 setMnnBackend() 里识别外部并发停止。 */
    private val stopRequestCount = AtomicInteger(0)

    /**
     * 每个流水线阶段独占一条常驻线程。
     *
     * 之前用的是 `Dispatchers.Default.limitedParallelism(1)`：它只限制并发度为 1，
     * 实际执行仍会落在共享线程池的任意线程上，还会和 GC / 其它 Default 任务抢
     * 调度。换成独立线程 + 略高的线程优先级后，各阶段的帧时间抖动明显更小 ——
     * 对「稳住 60 帧」来说，抖动比平均值更致命。
     */
    private val faceDetectionDispatcher: ExecutorCoroutineDispatcher =
        newStageThread("face-detect").asCoroutineDispatcher()

    private val landmarkDispatcher: ExecutorCoroutineDispatcher =
        newStageThread("face-landmark").asCoroutineDispatcher()

    /**
     * GPU 多路并行的推理线程（下标 = 引擎槽位）。
     *
     * 每条线程在启动时把自己的 native 线程绑定到对应的引擎槽位（0 = 主引擎，
     * 1.. = 额外 GPU 引擎），因此多个 MNN session 可以同时跑不同的帧。
     * 重排阶段再把各路结果按 frameId 拉回时间顺序。
     */
    private val gpuLandmarkDispatchers: List<ExecutorCoroutineDispatcher> =
        List(MAX_GPU_CHANNELS) { index ->
            newStageThread("face-landmark-gpu$index").asCoroutineDispatcher()
        }

    /** 乱序结果重排阶段（单线程，保证下游看到的帧序严格递增）。 */
    private val reorderDispatcher: ExecutorCoroutineDispatcher =
        newStageThread("face-landmark-merge").asCoroutineDispatcher()

    private val processingDispatcher: ExecutorCoroutineDispatcher =
        newStageThread("face-process").asCoroutineDispatcher()

    private val outputDispatcher: ExecutorCoroutineDispatcher =
        newStageThread("face-output").asCoroutineDispatcher()

    private fun newStageThread(stage: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fc-$stage").apply {
                // NORM_PRIORITY(5) + 2 = 7：高于普通后台线程，低于 UI 线程
                priority = Thread.NORM_PRIORITY + 2
            }
        }

    // Channels are recreated on every start() because Channel.close() is one-shot.
    // Keeping them mutable also lets stop() drop any in-flight frames (whose
    // backing ByteBuffers point into Camera2 Image planes that get freed by
    // stopCamera()), preventing a use-after-free crash in JNI on the next start.
    private var frameChannel: Channel<FrameData> = newFrameChannel()
    private var landmarkFrameChannel: Channel<FrameData> = newFrameChannel()
    private var landmarkChannel: Channel<LandmarkData> = newLandmarkChannel()

    /**
     * 多路并行模式下，各路关键点推理的原始结果先到这里（**不保证顺序**），
     * 再由重排阶段（跑在独立的 CPU 线程上）按 frameId 排序后写入 [landmarkChannel]。
     *
     * 单路模式下这条路只承担「转发」职责，行为与之前完全一致。
     */
    private var landmarkMergeChannel: Channel<LandmarkData> = newLandmarkMergeChannel()

    /** 帧重排缓冲；缺口最多等 [REORDER_GAP_TIMEOUT_MS]。 */
    private val reorderBuffer = FrameReorderBuffer<LandmarkData>(
        maxPending = REORDER_PENDING_CAPACITY,
        gapTimeoutMs = REORDER_GAP_TIMEOUT_MS,
    )

    /**
     * 关键点推理的**期望**并行度（GPU 引擎个数，含主引擎）。
     *
     * 1 = 单路；2 = 主引擎 + 1 路额外 GPU 引擎。真正生效的并行度还取决于
     * 主引擎是否跑在 Vulkan 上、额外引擎是否初始化成功，见 [getActiveChannelCount]。
     * 运行前改这个值，下次 [start] 生效；运行中请用 [setGpuChannelCount]。
     */
    @Volatile
    private var gpuChannelCount: Int = GPU_CHANNEL_DEFAULT

    /** 本轮 [start] 实际跑起来的并行度（1 = 单路）。 */
    @Volatile
    private var activeChannelCount = 1

    /**
     * 处理完成、等待派发回调的帧。
     *
     * 历史上这一步是「每帧 `pipelineScope.launch(Dispatchers.Default)`」——
     * 60fps 下等于每秒创建 60 个协程 + 60 次线程调度，而且回调一旦变慢就会
     * 无上限地堆积协程。改成单消费者 + 容量 1 的 DROP_OLDEST 通道后：没有逐帧
     * 协程开销，回调变慢时只保留最新一帧，延迟也不会滚雪球。
     */
    private var outputChannel: Channel<OutputFrame> = newOutputChannel()

    private fun newFrameChannel() = Channel<FrameData>(
        capacity = DETECTION_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private fun newLandmarkChannel() = Channel<LandmarkData>(
        capacity = PROCESSING_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private fun newOutputChannel() = Channel<OutputFrame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private fun newLandmarkMergeChannel() = Channel<LandmarkData>(
        capacity = LANDMARK_MERGE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private fun newYuvChannel(capacity: Int) = Channel<YuvFrameDataInternal>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val isRunning = AtomicBoolean(false)
    @Volatile private var isInitialized = false
    private var lastHeadPose = HeadPoseData()

    /** 初始化时实际使用的 MNN 推理线程数（仅用于性能日志）。 */
    private var mnnThreadCount = 0

    private val trackingConfidenceDetector by lazy { TrackingConfidenceDetector() }

    // 注意：姿态校准现在由 FaceCapturePipeline 内部管理
    // 注意：角度归一化（用于驱动 Live2D 等下游 avatar）由调用方负责，
    //       PipelineFaceTracker 只输出原始相对姿态（已减去校准基线，单位：度）。

    private var frameCount = AtomicLong(0)
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0f

    private var landmarkOutputCount = AtomicLong(0)
    private var lastLandmarkFpsTime = System.currentTimeMillis()

    private var cameraFrameCount = AtomicLong(0)
    private var lastCameraFpsTime = System.currentTimeMillis()

    // Callbacks
    var onCameraFrameUpdate: ((ByteBuffer, Int, Int) -> Unit)? = null
    var onBlendShapesUpdate: ((ARKitBlendShapes) -> Unit)? = null
    var onHeadPoseUpdate: ((HeadPoseData) -> Unit)? = null
    var onVisualizationUpdate: ((FaceVisualizationData) -> Unit)? = null
    var onFpsUpdate: ((Float) -> Unit)? = null
    var onCalibrationStatusUpdate: ((Boolean) -> Unit)? = null

    /**
     * Invoked when the active camera changes (initial start / camera switch).
     *
     * The payload is the camera actually opened; `null` means no camera could
     * be opened. It fires on the thread that called [start] / [switchCamera]
     * (usually the main thread), so UI updates are safe.
     */
    var onCameraChanged: ((CameraDeviceInfo?) -> Unit)? = null

    /**
     * 原子组合回调：BlendShape + HeadPose 同帧到达，专门给下游 avatar 驱动用。
     * 与 [onBlendShapesUpdate] / [onHeadPoseUpdate] 的区别：后两者面向 UI 调试，可独立订阅；
     * 这个回调保证同一帧的两类数据原子配对，避免下游 avatar 出现 BS/姿态错位。
     */
    var onFaceCaptureFrame: ((ARKitBlendShapes, HeadPoseData) -> Unit)? = null

    /**
     * 原子的“完整结果”回调：在同一帧内同时携带
     *
     *  - **原始感知层**：478 个 MediaPipe FaceMesh 关键点（归一化坐标）、对应的图像
     *    宽高、检测框、Landmark 模型的 presence score；
     *  - **后处理层**：原始/相对头部姿态、52 维 ARKit BlendShape、是否仍处于
     *    校准窗口、当前 FPS。
     *
     * 这条回调是 SDK 对外暴露的“一站式”结果通道，[FaceCaptureEngine] 即基于此封装。
     * 上层调用方如果只关心后处理结果（如直接驱动 Live2D），订阅 [onFaceCaptureFrame]
     * 即可；如果需要拿到原始点位做自定义渲染 / 调试 / 二次后处理，请订阅本回调。
     *
     * 与多路独立回调相比，本回调可保证「关键点 / 姿态 / BlendShape」三者来自同一帧，
     * 不会出现错位。
     */
    var onFaceCaptureResult: ((SdkFaceCaptureFrame) -> Unit)? = null

    /**
     * 同帧原子的全量数据包。字段语义详见 [FaceCaptureResult]（SDK Facade 即由本类映射）。
     */
    data class SdkFaceCaptureFrame(
        val frameId: Long,
        val landmarks: List<Point3D>,
        val imageWidth: Int,
        val imageHeight: Int,
        val detectionBox: DetectionBox,
        val rawHeadPose: HeadPoseData,
        val relativeHeadPose: HeadPoseData,
        val blendShapes: ARKitBlendShapes,
        val presenceScore: Float,
        val isCalibrating: Boolean,
        val fps: Float,
    )

    // ============================================================================
    // Internal data classes
    // ============================================================================

    private data class FrameData(
        val frameId: Long,
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private data class YuvFrameDataInternal(
        val frameId: Long,
        val yuvData: YuvFrameData,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private data class LandmarkData(
        val frameId: Long,
        val landmarks: List<Point3D>,
        val width: Int,
        val height: Int,
        val timestamp: Long,
        val headPose: Triple<Float, Float, Float>?,
        val presenceScore: Float = 1f,
    )

    /** 一帧的完整后处理结果，用于派发到下游回调。 */
    private class OutputFrame(
        val frameId: Long,
        val blendShapes: ARKitBlendShapes,
        val headPose: HeadPoseData,
        val rawPose: HeadPoseData,
        val landmarks: List<Point3D>,
        val width: Int,
        val height: Int,
        val presenceScore: Float,
        val isCalibrating: Boolean,
        val fps: Float,
    )

    /** 相机预览（调试画面）的 RGB 帧是否需要生成。默认关闭，按需打开。 */
    @Volatile
    private var cameraPreviewFramesRequested = false

    /** Use direct YUV processing (recommended; avoids Kotlin-side YUV->RGB conversion). */
    var useDirectYuvMode: Boolean = true

    /**
     * 相机 YUV 帧 tap（上层做**附加**推理用，例如上半身关键点，见 docs/upper-body.md §3.3）。
     *
     * 线程与生命周期约定：
     *  - 回调跑在**相机线程**上、同步执行，[YuvFrameData] 里的 ByteBuffer 直接指向
     *    Camera2 `Image` 的 plane —— `image.close()` 之后立刻失效，所以回调里必须
     *    **同步消费完**（拷成 Bitmap / 数组），不能存下来异步读；
     *  - 回调耗时直接吃相机线程的帧预算，别在这里做整帧推理；
     *  - 任何异常都在本类内被捕获并记日志，**绝不允许影响面捕热路径**；
     *  - [stop] 会把它置 null（避免释放后还回调），需要在重新 start 后由上层重新挂上。
     *
     * 关闭该功能时保持为 null：相机回调里只多一次判空，零额外开销。
     *
     * 新接入方请优先使用 [onYuvFrameForBodyWithHint]（多带一个脸框提示）；
     * 本回调保留以兼容旧调用方，两者同时挂上时只有带提示的那个会被调用。
     */
    @Volatile
    var onYuvFrameForBody: ((YuvFrameData) -> Unit)? = null

    /**
     * 带脸框提示的 YUV 帧 tap（**优先于** [onYuvFrameForBody]）。
     *
     * 行为契约：
     *  - 本回调非 null 时只调它，[onYuvFrameForBody] 不再触发；本回调为 null 时
     *    回退到 [onYuvFrameForBody]，旧接入方行为完全不变；
     *  - 第二个参数是**旋转后归一化坐标**（[0,1]）的脸框，已经过一级扩充
     *    （[expandFaceBox]，中心放大 1.15x）。它可能为 null：没有脸框，或提示
     *    超过 [FACE_BOX_HINT_TTL_MS] 没刷新 —— 此时上层应回退到整帧 ROI；
     *  - 仍在**相机线程**上同步调用（与 [onYuvFrameForBody] 相同）：[YuvFrameData]
     *    里的 ByteBuffer 只在回调内有效，回调里必须同步消费完；异常由本类兜住；
     *  - [stop] 会把它置 null，重新 [start] 后由上层重新挂上。
     */
    @Volatile
    var onYuvFrameForBodyWithHint: ((YuvFrameData, MediaPipeFaceDetector.FaceBox?) -> Unit)? = null

    /**
     * 上半身 tap（[onYuvFrameForBody] / [onYuvFrameForBodyWithHint]）的抽帧间隔（毫秒）：
     * 默认 66ms ≈ 15fps，**0 = 每帧都回调**。
     *
     * 运行中改这个值即可生效（相机线程下一帧就会看到新值），无需重启管线。
     */
    @Volatile
    var bodyTapIntervalMs: Long = 66L

    /** 上一次调用上半身 tap 的时间戳（相机线程读写、start() 时归零）。 */
    @Volatile
    private var lastBodyTapMs: Long = 0L

    private var yuvFrameChannel: Channel<YuvFrameDataInternal> = newYuvChannel(DETECTION_CHANNEL_CAPACITY)
    private var yuvLandmarkFrameChannel: Channel<YuvFrameDataInternal> = newYuvChannel(LANDMARK_CHANNEL_CAPACITY)

    // ============================================================================
    // Lifecycle
    // ============================================================================

    fun initialize(): Boolean {
        Log.i(TAG, "Initializing pipeline face tracker...")

        // 线程数按 CPU 核数自适应：Vulkan 后端下 numThread 基本不参与计算，
        // 一旦切到 CPU 后端，它直接决定 landmark 推理耗时（也就是能不能上 60fps）。
        // 上限 4，避免长时间跑满大核之后触发降频、反而让帧率抖动。
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        // enableBlendshape = false：原生侧的 blendShape MNN 模型在本工程里**没有任何
        // 调用点**（52 维表情实际由 OpenSeeFaceProcess 的 Kotlin 几何启发式算出）。
        // 之前它会被加载并建一个 Vulkan session 常驻，既占显存/内存又拉长启动时间，
        // 对帧率零贡献，所以这里显式关掉。
        if (!faceDetector.initialize(numThreads = threads, enableBlendshape = false)) {
            Log.e(TAG, "Failed to initialize face detector")
            return false
        }
        mnnThreadCount = threads
        Log.i(TAG, "MNN inference threads: $threads")

        // 首帧 PPM dump 只在可调试包里开：它会在检测线程上做 921,600 次像素循环
        // 并写 2.7 MB 文件，正式包里属于白做功（还会和首帧推理抢核）。
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            faceDetector.setDumpDirectory(context.cacheDir.absolutePath)
        } else {
            faceDetector.setDumpDirectory(null)
        }

        // 额外的 GPU 关键点引擎（并行推理用）：只加载 landmarker，跑 Vulkan。
        // 这里失败不影响主通道，start() 会自动少开一路。
        //
        // 只有**主引擎真的跑在 Vulkan 上**才去开：如果主引擎实际是 CPU 后端
        // （比如 libMNN_Vulkan.so 没加载 / 设备不支持），多开一路只会多占一份
        // 模型内存、在 CPU 上和主通道抢核，对帧率是负收益。
        if (gpuChannelCount > 1 && faceDetector.getBackend() == MnnBackend.VULKAN) {
            ensureExtraEngines(gpuChannelCount)
        } else if (gpuChannelCount > 1) {
            Log.w(
                TAG,
                "Skip extra GPU engines: main engine is running on " +
                    "${faceDetector.getBackend()} (Vulkan unavailable)",
            )
        }
        // 初始化阶段就把「实际能跑几路」算出来：UI 在追踪开始前也能显示真实并行度。
        activeChannelCount = resolveActiveChannelCount()
        Log.i(
            TAG,
            "MNN backend in use: ${faceDetector.getBackend()} " +
                "(requested Vulkan), GPU parallel channels: $activeChannelCount / $gpuChannelCount",
        )

        isInitialized = true
        Log.i(TAG, "Pipeline face tracker initialized with parallel stages")
        return true
    }

    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Pipeline already running")
            return
        }

        Log.i(TAG, "Starting parallel pipeline face tracker (YUV direct mode: $useDirectYuvMode)...")

        // 并行度只在「YUV 直通 + 主引擎 Vulkan + 额外引擎就绪」时才 >1：
        // 主引擎本身跑 CPU 时再开 GPU 引擎就成了两种后端混跑，收益不明确，
        // 不如老老实实单路。
        // 先算并行度，再按并行度开队列：N 路并行时队列至少能排 N+1 帧，
        // 否则每一路都要等下一帧到货，GPU 会出现空转。
        activeChannelCount = resolveActiveChannelCount()
        val landmarkQueueCapacity = maxOf(LANDMARK_CHANNEL_CAPACITY, activeChannelCount + 1)

        // Recreate ALL channels for this run. A closed Channel cannot be reused,
        // and any frame left in the previous run's YUV channels would carry a
        // ByteBuffer pointing into a now-released Camera2 Image plane -> crash.
        frameChannel = newFrameChannel()
        landmarkFrameChannel = newFrameChannel()
        landmarkChannel = newLandmarkChannel()
        landmarkMergeChannel = newLandmarkMergeChannel()
        reorderBuffer.reset()
        outputChannel = newOutputChannel()
        yuvFrameChannel = newYuvChannel(DETECTION_CHANNEL_CAPACITY)
        yuvLandmarkFrameChannel = newYuvChannel(landmarkQueueCapacity)

        // Drop any stale face box (and its hint timestamp) from a previous session.
        synchronized(faceBoxLock) {
            lastFaceBox = null
            lastFaceBoxTimestampMs = 0L
        }

        // 新的采集会话：上半身 tap 立刻触发第一帧，不用先等一个抽帧间隔。
        lastBodyTapMs = 0L

        val channelDesc = if (activeChannelCount > 1) {
            "GPU x $activeChannelCount (parallel, queue=$landmarkQueueCapacity)"
        } else {
            "single (backend=${faceDetector.getBackend()})"
        }
        Log.i(TAG, "Landmark channels: $channelDesc")

        if (useDirectYuvMode) {
            startYuvFaceDetectionStage()
            startYuvLandmarkStages()
        } else {
            startFaceDetectionStage()
            startLandmarkStage()
        }
        // 单路时关键点结果是天然有序的，重排阶段属于纯开销（每帧多一次通道唤醒 +
        // TreeMap 维护，并且它的 60ms 缺口超时是「永久丢一帧 → 输出定格」的来源）。
        // 只有多路并行才需要按 frameId 还原顺序。
        val needsReorder = useDirectYuvMode && activeChannelCount > 1
        if (needsReorder) {
            startLandmarkReorderStage()
        }
        startProcessingStage()
        startOutputStage()

        var frameId = 0L

        if (useDirectYuvMode) {
            camera2Manager.useDirectYuvMode = true
            // 预览 RGB 帧默认不生成：整帧 YUV→RGB 是相机线程上的大头开销，
            // 只有调试预览真的可见时才需要。
            camera2Manager.enablePreviewInYuvMode = cameraPreviewFramesRequested

            camera2Manager.setFrameCallback { frameData, width, height ->
                onCameraFrameUpdate?.invoke(frameData.duplicate(), width, height)
            }

            camera2Manager.setYuvFrameCallback { yuvData ->
                cameraFrameCount.incrementAndGet()
                val now = System.currentTimeMillis()
                val elapsed = now - lastCameraFpsTime
                if (elapsed >= 5000) {
                    val cameraFps = cameraFrameCount.get() * 1000f / elapsed
                    Log.i(TAG, "Camera input rate: %.1f FPS (YUV direct mode)".format(cameraFps))
                    cameraFrameCount.set(0)
                    lastCameraFpsTime = now
                }

                val data = YuvFrameDataInternal(frameId = frameId++, yuvData = yuvData)
                yuvFrameChannel.trySend(data)
                yuvLandmarkFrameChannel.trySend(data.copy())

                // 上半身等附加推理的抽帧 tap：面捕的两条通道先喂完再调用，保证面捕优先；
                // 回调里自己会同步消费 ByteBuffer，异常必须在这里兜住 —— 相机线程一旦
                // 被抛出去，面捕也会跟着停。
                //
                // 两个回调各拍一次局部快照：都没挂时只多两次 volatile 读（零额外开销）；
                // 挂了带提示的新回调就只在抽帧时刻进 faceBoxLock 取一次脸框。
                val bodyTapWithHint = onYuvFrameForBodyWithHint
                val bodyTapLegacy = onYuvFrameForBody
                if (bodyTapWithHint != null || bodyTapLegacy != null) {
                    val nowMs = System.currentTimeMillis()
                    val intervalMs = bodyTapIntervalMs
                    if (intervalMs <= 0L || nowMs - lastBodyTapMs >= intervalMs) {
                        lastBodyTapMs = nowMs
                        try {
                            if (bodyTapWithHint != null) {
                                bodyTapWithHint(yuvData, currentFaceBoxHint(nowMs))
                            } else {
                                bodyTapLegacy?.invoke(yuvData)
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Upper-body YUV tap failed", t)
                        }
                    }
                }
            }
        } else {
            camera2Manager.useDirectYuvMode = false
            camera2Manager.setFrameCallback { frameData, width, height ->
                onCameraFrameUpdate?.invoke(frameData.duplicate(), width, height)

                cameraFrameCount.incrementAndGet()
                val now = System.currentTimeMillis()
                val elapsed = now - lastCameraFpsTime
                if (elapsed >= 5000) {
                    val cameraFps = cameraFrameCount.get() * 1000f / elapsed
                    Log.i(TAG, "Camera input rate: %.1f FPS".format(cameraFps))
                    cameraFrameCount.set(0)
                    lastCameraFpsTime = now
                }

                val buffer = copyBuffer(frameData)
                val data = FrameData(frameId = frameId++, buffer = buffer, width = width, height = height)
                frameChannel.trySend(data)
                landmarkFrameChannel.trySend(data.copy(buffer = copyBuffer(frameData)))
            }
        }

        camera2Manager.startCamera()

        // The selected camera is known synchronously (before the async camera
        // open completes), so UI can update mirroring/labels immediately and
        // the first processed frame already uses the right FOV — important when
        // switching between cameras with different lenses.
        onCameraChanged?.invoke(camera2Manager.selectedCameraInfo)

        val fov = camera2Manager.cameraVerticalFovDegrees
        faceDetector.setCameraFov(fov)
        faceCapturePipeline.setCameraFov(fov)
        Log.i(TAG, "Camera FOV set to face detector: $fov degrees")

        lastFpsTime = System.currentTimeMillis()
        frameCount.set(0)
    }

    fun stop() {
        // Count every stop request (even the ones that find us already stopped) so
        // setMnnBackend() can tell "the stop I made myself" from "someone else asked
        // us to stop while the backend was being reloaded".
        stopRequestCount.incrementAndGet()

        // 先摘 tap / 作废脸框：即使本次 stop 发现管线已经停了（release() 在未运行时
        // 也会走到这里），也要保证上层释放后不会再有回调持有引用。
        onYuvFrameForBody = null
        onYuvFrameForBodyWithHint = null
        synchronized(faceBoxLock) {
            lastFaceBox = null
            lastFaceBoxTimestampMs = 0L
        }

        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping pipeline face tracker...")
        // IMPORTANT: stop the camera FIRST so no further frames get enqueued,
        // then close every channel. Closing the YUV channels here is critical:
        // their elements wrap Camera2 Image planes that become invalid the
        // moment stopCamera() releases the ImageReader. Leaving stale items in
        // the channel would cause use-after-free on the next start().
        camera2Manager.stopCamera()
        frameChannel.close()
        landmarkFrameChannel.close()
        landmarkChannel.close()
        landmarkMergeChannel.close()
        reorderBuffer.reset()
        outputChannel.close()
        yuvFrameChannel.close()
        yuvLandmarkFrameChannel.close()

        // 相机与通道已停；tap / 脸框已在函数入口摘掉，重新 start() 后由上层按需重挂
        // （MainActivity 在 onCameraChanged 里补挂）。
    }

    fun release() {
        stop()
        isInitialized = false
        faceDetector.release()
        pipelineScope.cancel()
        Log.i(TAG, "Pipeline face tracker released")
    }

    fun hasPermission(): Boolean = camera2Manager.hasPermission()
    fun isTracking(): Boolean = isRunning.get()

    // ============================================================================
    // Camera selection / switching
    // ============================================================================

    /**
     * List every camera exposed by the platform (front / back / external) plus
     * the physical sensors hidden behind logical multi-cameras (API 28+), such
     * as the rear ultra-wide lens.
     */
    fun getAvailableCameras(): List<CameraDeviceInfo> = camera2Manager.getAvailableCameras()

    /** Camera selected by the current / most recent session, or null before start. */
    fun getCurrentCamera(): CameraDeviceInfo? = camera2Manager.selectedCameraInfo

    /**
     * Camera key requested for the next start, or null for automatic front-first.
     * `"0"` = logical camera, `"0#2"` = physical sensor of logical camera `0`.
     */
    fun getPreferredCameraId(): String? = camera2Manager.getPreferredCameraId()

    /** Select the camera used by the next [start]; takes effect on the next start. */
    fun setPreferredCamera(cameraId: String?) {
        camera2Manager.setPreferredCamera(cameraId)
    }

    /**
     * Switch cameras while keeping the caller-visible tracking state.
     *
     * The pipeline is stopped and restarted so Camera2 releases the previous
     * device before opening the new one. The pose / expression baseline is
     * reset afterwards because FOV and sensor geometry usually differ between
     * cameras (especially front ↔ back ↔ external / ultra-wide).
     *
     * @param cameraId a [CameraDeviceInfo.key] (`"0"` for a logical camera,
     *                 `"0#2"` for a physical sensor).
     * @return true if the camera key exists and the switch was applied.
     */
    fun switchCamera(cameraId: String): Boolean {
        val target = camera2Manager.getAvailableCameras().firstOrNull { it.key == cameraId }
        if (target == null) {
            Log.e(TAG, "Cannot switch camera: key $cameraId is not available")
            return false
        }

        val wasRunning = isRunning.get()
        val alreadyActive = wasRunning &&
            camera2Manager.selectedCameraInfo?.key == target.key
        Log.i(
            TAG,
            "Switching camera to ${target.label} (key=${target.key}), " +
                "running=$wasRunning, alreadyActive=$alreadyActive",
        )
        camera2Manager.setPreferredCamera(target.key)

        if (alreadyActive) {
            // Same camera selected again — keep the session, just refresh the UI.
            onCameraChanged?.invoke(target)
            return true
        }

        if (wasRunning) {
            stop()
            start()
        } else {
            onCameraChanged?.invoke(target)
        }

        if (isInitialized) {
            // Fresh neutral baseline for the new camera geometry.
            resetAdaptiveBaseline()
        }
        return true
    }

    fun getLastHeadPose(): HeadPoseData = lastHeadPose
    fun getCurrentFps(): Float = currentFps

    // ============================================================================
    // Performance knobs
    // ============================================================================

    /**
     * 打开/关闭相机预览（调试画面）的 RGB 帧生成。
     *
     * 关闭时相机线程不再做整帧 YUV→RGB —— 480p 下这一项每帧要花几毫秒 CPU，
     * 而处理管线根本用不到它（推理走 YUV 直通）。只有在 UI 真的显示相机画面
     * 时才需要打开。
     *
     * 可以随时调用，立即生效（相机线程下一帧就会看到新值）。
     */
    fun setCameraPreviewFramesEnabled(enabled: Boolean) {
        cameraPreviewFramesRequested = enabled
        camera2Manager.enablePreviewInYuvMode = enabled
        Log.i(TAG, "Camera preview frames: ${if (enabled) "ON" else "OFF"}")
    }

    /** 相机预览 RGB 帧当前是否开启。 */
    fun isCameraPreviewFramesEnabled(): Boolean = cameraPreviewFramesRequested

    /**
     * 运行时切换 MNN 推理后端（Vulkan ⇄ CPU）。
     *
     * 后端会话不能和推理并发重建，所以这里会先 [stop]（若正在追踪），
     * 切换后按原状态自动 [start]。切换期间画面会中断一帧左右。
     *
     * @return true 表示切换成功；false 表示失败且仍停留在原后端（或原后端
     *         已不可用，此时会尝试恢复）。
     */
    fun setMnnBackend(backend: MnnBackend): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "setMnnBackend($backend) ignored: pipeline not initialized")
            return false
        }
        if (backend == faceDetector.getBackend()) {
            Log.i(TAG, "MNN backend already $backend")
            return true
        }

        val wasRunning = isRunning.get()
        val stopsBefore = stopRequestCount.get()
        if (wasRunning) stop()

        val switched = faceDetector.setBackend(backend)

        // 主引擎都跑 CPU 时并行 GPU 引擎没有意义，把它们的模型放掉省内存；
        // 切回 Vulkan 时再按需重建，并行才能继续生效。
        //
        // 注意判据用 **实际** 后端（getBackend 读的是原生侧真实 session 类型）：
        // 有些设备上 Vulkan 请求会静默落 CPU，此时再开一路只会抢核。
        if (switched) {
            if (faceDetector.getBackend() == MnnBackend.VULKAN) {
                ensureExtraEngines(gpuChannelCount)
            } else {
                faceDetector.releaseExtraEngines()
                Log.w(TAG, "Backend is actually CPU; extra GPU engines released")
            }
        }

        // 切换期间外部又调用了 stop()（例如 Activity 进入后台）时不要再自动 start，
        // 否则相机会在后台被重新打开。start()/stop() 本身都是幂等的，这里只是判断
        // 「我这次 stop() 之外是否还有别人要求停止」。
        val expectedStops = if (wasRunning) 1 else 0
        val externalStopRequested = stopRequestCount.get() > stopsBefore + expectedStops

        // 无论切换成功与否都要恢复追踪：失败时仍在原后端，不能把管线留在停止状态。
        if (wasRunning && !externalStopRequested) start()

        Log.i(TAG, "MNN backend switch to $backend -> $switched (now ${faceDetector.getBackend()})")
        return switched
    }

    /** 当前生效的 MNN 推理后端。 */
    fun getMnnBackend(): MnnBackend = faceDetector.getBackend()

    /** 期望的关键点推理并行度（GPU 引擎个数，含主引擎；1 = 单路）。 */
    fun getGpuChannelCount(): Int = gpuChannelCount

    /** 本轮实际生效的并行度（1 = 单路）。 */
    fun getActiveChannelCount(): Int = activeChannelCount

    /** 是否正在多路 GPU 并行推理。 */
    fun isGpuParallelActive(): Boolean = activeChannelCount > 1

    /**
     * 运行时调整关键点推理并行度。
     *
     * 1 = 退回单路（省显存 / 省电 / 降发热）；2..[MAX_GPU_CHANNELS] = 多开几路
     * GPU 引擎并行抢帧，把 GPU 喂饱。切换需要重启管线（推理线程在 start() 里拉起），
     * 相机会短暂中断，因此只适合在设置面板里点，别放在每帧路径上。
     *
     * @return 切换后实际生效的并行度
     */
    fun setGpuChannelCount(count: Int): Int {
        if (!isInitialized) {
            Log.w(TAG, "setGpuChannelCount($count) ignored: pipeline not initialized")
            return activeChannelCount
        }

        val target = count.coerceIn(1, MAX_GPU_CHANNELS)
        gpuChannelCount = target
        // 与 initialize() 一致：主引擎不在 Vulkan 上时不开额外引擎（纯浪费内存与 CPU）。
        if (target > 1 && faceDetector.getBackend() == MnnBackend.VULKAN) {
            ensureExtraEngines(target)
        } else {
            ensureExtraEngines(1)
        }

        val wasRunning = isRunning.get()
        val stopsBefore = stopRequestCount.get()
        if (wasRunning) stop()

        // 与 setMnnBackend 一致：切换期间若外部又要求停止，就不要自动重启相机。
        val externalStopRequested = stopRequestCount.get() > stopsBefore + 1
        if (wasRunning && !externalStopRequested) start()

        Log.i(TAG, "GPU channels -> requested=$target, active=$activeChannelCount")
        return activeChannelCount
    }

    /**
     * 计算本轮实际能跑几路：
     * 单路 / 主引擎不是 Vulkan 时固定 1 路，否则取「期望值」与「已就绪槽位前缀」的较小值。
     */
    private fun resolveActiveChannelCount(): Int {
        if (!useDirectYuvMode) return 1
        if (faceDetector.getBackend() != MnnBackend.VULKAN) return 1
        val requested = gpuChannelCount.coerceIn(1, MAX_GPU_CHANNELS)
        if (requested <= 1) return 1

        var ready = 1
        while (ready < requested && faceDetector.isEngineReady(ready)) ready++
        return ready
    }

    /**
     * 按需补齐额外 GPU 引擎：多出来的槽位放掉，缺的补上。
     *
     * 任意一路创建失败就停在那里 —— 前缀里的路照常工作，并行度降一级。
     */
    private fun ensureExtraEngines(requested: Int) {
        val target = requested.coerceIn(1, MAX_GPU_CHANNELS)

        // 期望的并行度变小了：把多出来的引擎放掉，省显存 / 内存。
        for (slot in target until MAX_GPU_CHANNELS) {
            if (faceDetector.isEngineReady(slot)) {
                faceDetector.releaseExtraEngine(slot)
            }
        }

        for (slot in 1 until target) {
            if (faceDetector.isEngineReady(slot)) continue
            val ok = faceDetector.initializeExtraEngine(
                slot = slot,
                useVulkan = true,
                numThreads = mnnThreadCount,
            )
            if (!ok) {
                Log.w(TAG, "Extra GPU engine $slot unavailable, capping parallelism at $slot")
                return
            }
        }
    }

    /**
     * 一级扩充：以检测框中心为基准，把宽高放大 [factor] 倍并 clamp 到 [0, 1]。
     *
     * 检测框通常刚好贴住脸的边界，大角度 / 快速移动时顶点容易被裁掉。
     * 这里先往外让 1.15x，landmarker 内部还会做自己的二级扩充（原生 1.5x），
     * 两级叠加后给顶点留出足够上下文。score 原样保留。
     */
    private fun expandFaceBox(
        box: MediaPipeFaceDetector.FaceBox,
        factor: Float = 1.15f,
    ): MediaPipeFaceDetector.FaceBox {
        val cx = (box.x1 + box.x2) * 0.5f
        val cy = (box.y1 + box.y2) * 0.5f
        val halfW = (box.x2 - box.x1) * 0.5f * factor
        val halfH = (box.y2 - box.y1) * 0.5f * factor
        return MediaPipeFaceDetector.FaceBox(
            x1 = (cx - halfW).coerceIn(0f, 1f),
            y1 = (cy - halfH).coerceIn(0f, 1f),
            x2 = (cx + halfW).coerceIn(0f, 1f),
            y2 = (cy + halfH).coerceIn(0f, 1f),
            score = box.score,
        )
    }

    /**
     * 相机线程：取当前可用的脸框提示。
     *
     * 只在挂了 [onYuvFrameForBodyWithHint] 且到了抽帧间隔时才调用（≈66ms 一次）；
     * 锁内只有两次字段读取，与检测 / 关键点阶段抢锁的时间可以忽略。
     *
     * @return 一级扩充后的脸框；无脸框或提示超过 [FACE_BOX_HINT_TTL_MS] 时为 null。
     */
    private fun currentFaceBoxHint(nowMs: Long): MediaPipeFaceDetector.FaceBox? {
        // 用 var 而不是在 inline lambda 里初始化 val / 非局部 return：
        // 不依赖编译器对 inline 调用的数据流分析，行为最直白。
        var box: MediaPipeFaceDetector.FaceBox? = null
        var timestampMs = 0L
        synchronized(faceBoxLock) {
            box = lastFaceBox
            timestampMs = lastFaceBoxTimestampMs
        }
        return if (box != null && nowMs - timestampMs <= FACE_BOX_HINT_TTL_MS) box else null
    }

    /**
     * Reset adaptive baseline for BlendShape extraction.
     * 这会同时重置：
     * - 姿态基线（FaceCapturePipeline 内部管理）
     * - 表情基线
     *
     * 注意：下游 avatar 适配器（如 Live2DAvatarAdapter）的归一化器需要由
     *      调用方在收到本方法的副作用后另行 reset，本类不再耦合任何 avatar 相关状态。
     */
    fun resetAdaptiveBaseline() {
        Log.i(TAG, "Resetting all baselines...")
        faceCapturePipeline.reset()
        faceDetector.resetSmoothing()
        blinkCurveProcessor.reset()
        trackingConfidenceDetector.reset()
        synchronized(faceBoxLock) {
            lastFaceBox = null
            lastFaceBoxTimestampMs = 0L
        }
        Log.i(TAG, "✅ All baselines reset, auto-calibration will start on next frames")
    }

    @Deprecated("Use resetAdaptiveBaseline() instead")
    fun recalibrateHeadPose() {
        Log.i(TAG, "Resetting head pose calibration via pipeline...")
        faceCapturePipeline.reset()
    }

    // ============================================================================
    // Pipeline stages
    // ============================================================================

    private fun startFaceDetectionStage() {
        pipelineScope.launch(faceDetectionDispatcher) {
            Log.i(TAG, "Face detection stage started")
            for (frame in frameChannel) {
                if (!isRunning.get()) break
                try {
                    val startTime = if (ENABLE_PERF_PROFILING) System.nanoTime() else 0L
                    val box = faceDetector.detectFaceOnly(frame.buffer, frame.width, frame.height)
                    if (box != null) {
                        // 一级扩充后再存：lastFaceBox 现在是「脸 ROI」而不是裸检测框。
                        val faceRoi = expandFaceBox(box)
                        synchronized(faceBoxLock) {
                            lastFaceBox = faceRoi
                            lastFaceBoxTimestampMs = System.currentTimeMillis()
                        }
                    }
                    if (ENABLE_PERF_PROFILING) {
                        synchronized(perfLock) {
                            perfDetectionTotal.add((System.nanoTime() - startTime) / 1_000_000.0)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Face detection error", e)
                }
            }
            Log.i(TAG, "Face detection stage stopped")
        }
    }

    private fun startLandmarkStage() {
        pipelineScope.launch(landmarkDispatcher) {
            Log.i(TAG, "Landmark detection stage started")
            for (frame in landmarkFrameChannel) {
                if (!isRunning.get()) break
                try {
                    val box = synchronized(faceBoxLock) { lastFaceBox } ?: continue
                    val startTime = if (ENABLE_PERF_PROFILING) System.nanoTime() else 0L
                    val landmarks = faceDetector.detectLandmarksWithBox(
                        frame.buffer, frame.width, frame.height, box,
                    )
                    if (landmarks != null && landmarks.isNotEmpty()) {
                        val headPose = faceDetector.estimateHeadPose(
                            landmarks, frame.width, frame.height,
                        )
                        val landmarkData = LandmarkData(
                            frameId = frame.frameId,
                            landmarks = landmarks,
                            width = frame.width,
                            height = frame.height,
                            timestamp = frame.timestamp,
                            headPose = headPose,
                        )
                        landmarkOutputCount.incrementAndGet()
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastLandmarkFpsTime
                        if (elapsed >= 5000) {
                            val landmarkFps = landmarkOutputCount.get() * 1000f / elapsed
                            Log.i(TAG, "Landmark output rate: %.1f FPS".format(landmarkFps))
                            landmarkOutputCount.set(0)
                            lastLandmarkFpsTime = now
                        }
                        landmarkChannel.trySend(landmarkData)
                    }
                    if (ENABLE_PERF_PROFILING) {
                        synchronized(perfLock) {
                            perfLandmarkTotal.add((System.nanoTime() - startTime) / 1_000_000.0)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Landmark detection error", e)
                }
            }
            Log.i(TAG, "Landmark detection stage stopped")
        }
    }

    private fun startYuvFaceDetectionStage() {
        pipelineScope.launch(faceDetectionDispatcher) {
            Log.i(TAG, "YUV Face detection stage started")
            var lastDetectionMs = 0L
            for (frame in yuvFrameChannel) {
                if (!isRunning.get()) break
                try {
                    // 检测节流：脸框在 100ms 内变化极小，但每次检测都要跑一遍完整
                    // 推理并和 landmark 抢算力。稳定追踪时按固定节奏刷新脸框即可；
                    // 脸框为空（掉脸 / 低置信度重置）时立即重检，不影响跟脸。
                    val nowMs = System.currentTimeMillis()
                    val hasFaceBox = synchronized(faceBoxLock) { lastFaceBox != null }
                    if (hasFaceBox &&
                        nowMs - lastDetectionMs < DETECTION_INTERVAL_MS_STABLE
                    ) {
                        synchronized(perfLock) { detectionSkipCount++ }
                        continue
                    }
                    lastDetectionMs = nowMs

                    val startTime = if (ENABLE_PERF_PROFILING) System.nanoTime() else 0L
                    val yuvData = frame.yuvData
                    val box = faceDetector.detectFaceFromYuv(
                        yuvData.yBuffer, yuvData.uBuffer, yuvData.vBuffer,
                        yuvData.yRowStride, yuvData.uvRowStride, yuvData.uvPixelStride,
                        yuvData.width, yuvData.height, yuvData.rotation,
                    )
                    if (box != null) {
                        // 一级扩充后再存：lastFaceBox 现在是「脸 ROI」而不是裸检测框。
                        val faceRoi = expandFaceBox(box)
                        synchronized(faceBoxLock) {
                            lastFaceBox = faceRoi
                            lastFaceBoxTimestampMs = System.currentTimeMillis()
                        }
                    }
                    if (ENABLE_PERF_PROFILING) {
                        synchronized(perfLock) {
                            detectionRunCount++
                            perfDetectionTotal.add((System.nanoTime() - startTime) / 1_000_000.0)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "YUV Face detection error", e)
                }
            }
            Log.i(TAG, "YUV Face detection stage stopped")
        }
    }

    /**
     * 启动关键点阶段：一路或 N 路（GPU 并行）。
     *
     * 各路共用同一个 `yuvLandmarkFrameChannel`：kotlinx 的 Channel 在多个
     * 消费者之间是「分派」语义（一帧只会被一个消费者取走），于是哪一路空下来
     * 就接下一帧，天然是负载均衡的抢帧模型 —— 只要还有帧在排队，GPU 就一直有
     * 活儿干。帧序由 [startLandmarkReorderStage] 还原。
     */
    private fun startYuvLandmarkStages() {
        val count = activeChannelCount.coerceIn(1, MAX_GPU_CHANNELS)
        // 单路直达后处理通道（跳过重排）；多路才走 merge + reorder。
        val directToProcessing = count <= 1
        for (index in 0 until count) {
            startYuvLandmarkWorker(
                label = if (count == 1) "single" else "gpu$index",
                engineIndex = index,
                dispatcher = gpuLandmarkDispatchers[index],
                // 追踪置信度检测是有状态的（连续低置信度才触发重检），多路都更新
                // 会把它的判定节奏打乱，所以只让主通道负责。
                runConfidenceCheck = index == 0,
                directToProcessing = directToProcessing,
            )
        }
    }

    private fun startYuvLandmarkWorker(
        label: String,
        engineIndex: Int,
        dispatcher: CoroutineDispatcher,
        runConfidenceCheck: Boolean,
        directToProcessing: Boolean = false,
    ) {
        pipelineScope.launch(dispatcher) {
            // 把当前线程绑定到对应的原生引擎槽位（线程本地状态，绑一次即可）。
            val bound = faceDetector.bindEngine(engineIndex)
            Log.i(TAG, "YUV Landmark worker '$label' started on engine slot $bound")
            // 捕获本轮 start() 的通道实例：万一 stop()/start() 期间这个协程还没退出，
            // 也不会把上一轮的帧塞进新一轮的通道里。
            val inputChannel = yuvLandmarkFrameChannel
            val mergeChannel = landmarkMergeChannel
            // 单路：直接投后处理通道（结果本来就有序，不经过重排）；
            // 多路：先投 merge 通道，由重排阶段按 frameId 还原顺序。
            val outChannel = if (directToProcessing) landmarkChannel else mergeChannel
            for (frame in inputChannel) {
                if (!isRunning.get()) break
                try {
                    val box = synchronized(faceBoxLock) { lastFaceBox }
                    if (box == null) {
                        synchronized(perfLock) { detectionSkipCount++ }
                        continue
                    }
                    val startTime = if (ENABLE_PERF_PROFILING) System.nanoTime() else 0L
                    val yuvData = frame.yuvData
                    val landmarkResult = faceDetector.detectLandmarksFromYuvWithScore(
                        yuvData.yBuffer, yuvData.uBuffer, yuvData.vBuffer,
                        yuvData.yRowStride, yuvData.uvRowStride, yuvData.uvPixelStride,
                        yuvData.rotatedWidth, yuvData.rotatedHeight, yuvData.rotation, box,
                    )
                    if (landmarkResult != null && landmarkResult.landmarks.isNotEmpty()) {
                        val landmarks = landmarkResult.landmarks
                        val presenceScore = landmarkResult.presenceScore

                        if (runConfidenceCheck) {
                            val trackingStatus = trackingConfidenceDetector.updateAndCheck(
                                currentLandmarks = landmarks,
                                presenceScore = presenceScore,
                            )
                            if (trackingStatus.shouldRedetect) {
                                Log.d(TAG, "Tracking confidence low, triggering re-detection: ${trackingStatus.reason}")
                                synchronized(faceBoxLock) {
                                    lastFaceBox = null
                                    lastFaceBoxTimestampMs = 0L
                                }
                                synchronized(perfLock) { redetectTriggerCount++ }
                                trackingConfidenceDetector.onDetectionPerformed()
                            }
                        }

                        val headPose = faceDetector.estimateHeadPose(
                            landmarks, yuvData.rotatedWidth, yuvData.rotatedHeight,
                        )
                        val landmarkData = LandmarkData(
                            frameId = frame.frameId,
                            landmarks = landmarks,
                            width = yuvData.rotatedWidth,
                            height = yuvData.rotatedHeight,
                            timestamp = frame.timestamp,
                            headPose = headPose,
                            presenceScore = presenceScore,
                        )

                        landmarkOutputCount.incrementAndGet()
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastLandmarkFpsTime
                        if (elapsed >= 5000) {
                            val landmarkFps = landmarkOutputCount.get() * 1000f / elapsed
                            Log.i(TAG, "YUV Landmark output rate: %.1f FPS".format(landmarkFps))
                            landmarkOutputCount.set(0)
                            lastLandmarkFpsTime = now
                        }
                        // 结果先交给重排阶段：多路并行下到达顺序不等于采集顺序。
                        outChannel.trySend(landmarkData)
                    }
                    if (ENABLE_PERF_PROFILING) {
                        synchronized(perfLock) {
                            perfLandmarkTotal.add((System.nanoTime() - startTime) / 1_000_000.0)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "YUV Landmark detection error", e)
                }
            }
            Log.i(TAG, "YUV Landmark worker '$label' stopped")
        }
    }

    /**
     * 乱序结果重排阶段：把两路推理的结果按 frameId 还原成时间顺序，再交给后处理。
     *
     * 单路时帧本来就是顺序到达的，这一级只做转发，行为与之前完全一致；
     * 多路并行时才会临时缓存等待另一路，等待上限 [REORDER_GAP_TIMEOUT_MS]。
     */
    private fun startLandmarkReorderStage() {
        pipelineScope.launch(reorderDispatcher) {
            Log.i(TAG, "Landmark reorder stage started (channels=$activeChannelCount)")
            // 同 worker：绑定本轮 start() 创建的通道实例。
            val mergeChannel = landmarkMergeChannel
            val outChannel = landmarkChannel
            while (isRunning.get()) {
                val waitingForGap = reorderBuffer.size > 0
                val next: LandmarkData? = if (!waitingForGap) {
                    mergeChannel.receiveCatching().getOrNull()
                } else {
                    // 有帧在等缺口：最多等一个超时，然后放行手里已有的帧。
                    withTimeoutOrNull(REORDER_GAP_TIMEOUT_MS) {
                        mergeChannel.receiveCatching().getOrNull()
                    }
                }

                if (next != null) {
                    val ready = reorderBuffer.offer(next.frameId, next, System.currentTimeMillis())
                    for (item in ready) outChannel.trySend(item)
                } else {
                    val ready = reorderBuffer.poll(System.currentTimeMillis())
                    for (item in ready) outChannel.trySend(item)
                }
            }
            reorderBuffer.reset()
            Log.i(TAG, "Landmark reorder stage stopped")
        }
    }

    private fun startProcessingStage() {
        pipelineScope.launch(processingDispatcher) {
            Log.i(TAG, "Processing stage started")
            for (data in landmarkChannel) {
                if (!isRunning.get()) break
                try {
                    // 丢掉已经过期的帧：算力应该花在最新一帧上，否则延迟会累积
                    val ageMs = System.currentTimeMillis() - data.timestamp
                    if (ageMs > MAX_FRAME_AGE_MS) {
                        synchronized(perfLock) { staleFrameDropCount++ }
                        continue
                    }

                    val stageStart = if (ENABLE_PERF_PROFILING) System.nanoTime() else 0L
                    val headPose = data.headPose
                    val bsStart = if (ENABLE_PERF_PROFILING) System.nanoTime() else 0L
                    val rawPitch = headPose?.first ?: 0f
                    val rawYaw = headPose?.second ?: 0f
                    val rawRoll = headPose?.third ?: 0f

                    val result = faceCapturePipeline.processWithPose(
                        data.landmarks, data.width, data.height,
                        rawPitch, rawYaw, rawRoll,
                    )
                    val blendShapes = result.blendShapes

                    onCalibrationStatusUpdate?.invoke(result.isCalibrating)

                    if (ENABLE_PERF_PROFILING) {
                        synchronized(perfLock) {
                            perfBlendShape.add((System.nanoTime() - bsStart) / 1_000_000.0)
                        }
                    }

                    val relativePose = result.relativePose
                    val headPoseData = HeadPoseData(
                        relativePose.pitch, relativePose.yaw, relativePose.roll,
                    )
                    val rawPoseData = HeadPoseData(
                        result.rawPose.pitch, result.rawPose.yaw, result.rawPose.roll,
                    )
                    lastHeadPose = headPoseData

                    updateFps()

                    // 眨眼曲线按真实帧率推进（之前写死 60fps，实际帧率不足时曲线
                    // 会被拉长/压缩，眨眼的起落时间就不准了）
                    blinkCurveProcessor.updateFrameRate(currentFps.coerceIn(15f, 120f))

                    val processedBlendShapes = if (blinkCurveProcessor.isEnabled) {
                        val (leftBlink, rightBlink) = blinkCurveProcessor.process(
                            blendShapes.eyeBlinkLeft,
                            blendShapes.eyeBlinkRight,
                            System.currentTimeMillis(),
                        )
                        blendShapes.copy(
                            eyeBlinkLeft = leftBlink,
                            eyeBlinkRight = rightBlink,
                        )
                    } else {
                        blendShapes
                    }

                    // 只把结果推给派发阶段，本阶段不再创建协程、不碰 UI/渲染
                    outputChannel.trySend(
                        OutputFrame(
                            frameId = data.frameId,
                            blendShapes = processedBlendShapes,
                            headPose = headPoseData,
                            rawPose = rawPoseData,
                            landmarks = data.landmarks,
                            width = data.width,
                            height = data.height,
                            presenceScore = data.presenceScore,
                            isCalibrating = result.isCalibrating,
                            fps = currentFps,
                        )
                    )

                    if (ENABLE_PERF_PROFILING) {
                        var logNow = false
                        synchronized(perfLock) {
                            perfProcessingTotal.add((System.nanoTime() - stageStart) / 1_000_000.0)
                            perfFrameCount++
                            if (perfFrameCount % PERF_LOG_INTERVAL == 0) logNow = true
                        }
                        // 必须在锁外输出：13 条 Log.i + String.format 本身可能耗时
                        // 十几毫秒，持锁打日志会顶住关键词 worker 的每帧计数。
                        if (logNow) logPerfStats()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Processing error", e)
                }
            }
            Log.i(TAG, "Processing stage stopped")
        }
    }

    /**
     * 回调派发阶段。
     *
     * 单消费者：同一时刻最多一帧在派发；通道容量 1 + DROP_OLDEST 保证下游变慢
     * 时只保留最新结果，不会像之前「每帧 launch 一个协程」那样无上限堆积。
     */
    private fun startOutputStage() {
        pipelineScope.launch(outputDispatcher) {
            Log.i(TAG, "Output stage started")
            for (frame in outputChannel) {
                if (!isRunning.get()) break
                try {
                    val dispatchStart = if (ENABLE_PERF_PROFILING) System.nanoTime() else 0L
                    dispatchOutput(frame)
                    if (ENABLE_PERF_PROFILING) {
                        // 现在测到的才是真正的「派发 + Live2D 参数写入」耗时
                        synchronized(perfLock) {
                            perfApplyToLive2D.add(
                                (System.nanoTime() - dispatchStart) / 1_000_000.0
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Output dispatch error", e)
                }
            }
            Log.i(TAG, "Output stage stopped")
        }
    }

    private fun dispatchOutput(frame: OutputFrame) {
        // 1) avatar 驱动：主链路，先派发
        onFaceCaptureFrame?.invoke(frame.blendShapes, frame.headPose)

        // 2) 检测框是每帧 O(478) 的额外遍历，只有真的有人订阅时才计算
        val wantsResult = onFaceCaptureResult != null
        val wantsVisualization = onVisualizationUpdate != null
        val detectionBox = if (wantsResult || wantsVisualization) {
            calculateBoundingBox(frame.landmarks)
        } else {
            DetectionBox(0f, 0f, 1f, 1f)
        }

        if (wantsResult) {
            // SDK-facing atomic snapshot: raw landmarks + post-processed
            // BlendShape / head-pose in a single payload, guaranteed to
            // belong to the same frame.
            onFaceCaptureResult?.invoke(
                SdkFaceCaptureFrame(
                    frameId = frame.frameId,
                    landmarks = frame.landmarks,
                    imageWidth = frame.width,
                    imageHeight = frame.height,
                    detectionBox = detectionBox,
                    rawHeadPose = frame.rawPose,
                    relativeHeadPose = frame.headPose,
                    blendShapes = frame.blendShapes,
                    presenceScore = frame.presenceScore,
                    isCalibrating = frame.isCalibrating,
                    fps = frame.fps,
                )
            )
        }

        if (wantsVisualization) {
            onVisualizationUpdate?.invoke(
                FaceVisualizationData(
                    landmarks = frame.landmarks,
                    detectionBox = detectionBox,
                    headPose = frame.headPose,
                    imageWidth = frame.width,
                    imageHeight = frame.height,
                )
            )
        }

        onBlendShapesUpdate?.invoke(frame.blendShapes)
        onHeadPoseUpdate?.invoke(frame.headPose)
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    private fun logPerfStats() {
        // 快照 + 重置都在 perfLock 内（几十次浮点运算，微秒级）；格式化与 Log.i
        // 全部放到锁外 —— 之前整段 13 条日志都持锁跑，2 路关键词 worker 的每帧
        // 计数会被顶住 1~20ms，正好在重排缓冲里制造出 60ms 缺口。
        val s = synchronized(perfLock) {
            val snap = PerfSnapshot(
                detAvg = perfDetectionTotal.avg(),
                lmAvg = perfLandmarkTotal.avg(),
                procAvg = perfProcessingTotal.avg(),
                bsAvg = perfBlendShape.avg(),
                applyAvg = perfApplyToLive2D.avg(),
                detRuns = detectionRunCount,
                detSkips = detectionSkipCount,
                redetects = redetectTriggerCount,
                staleDrops = staleFrameDropCount,
            )
            perfDetectionTotal.reset()
            perfLandmarkTotal.reset()
            perfProcessingTotal.reset()
            perfBlendShape.reset()
            perfApplyToLive2D.reset()
            detectionRunCount = 0
            detectionSkipCount = 0
            redetectTriggerCount = 0
            staleFrameDropCount = 0
            snap
        }

        val theoreticalMaxFps = if (s.lmAvg > 0) 1000.0 / s.lmAvg else 0.0
        val totalFrames = s.detRuns + s.detSkips
        val detectionRate = if (totalFrames > 0) 100.0 * s.detRuns / totalFrames else 0.0
        val skipRate = if (totalFrames > 0) 100.0 * s.detSkips / totalFrames else 0.0
        val outFps = currentFps
        val camFps = camera2Manager.cameraDeliveredFps
        val frameDurMs = camera2Manager.appliedFrameDurationNs / 1_000_000.0

        Log.i(TAG, "=== Parallel Pipeline Perf (avg of $PERF_LOG_INTERVAL frames) ===")
        Log.i(TAG, "  Face Detection: %.2f ms (runs in parallel)".format(s.detAvg))
        Log.i(TAG, "    Detection runs: ${s.detRuns} (%.1f%%)".format(detectionRate))
        Log.i(TAG, "    Skipped (reuse box): ${s.detSkips} (%.1f%%)".format(skipRate))
        Log.i(TAG, "    Re-detect triggers: ${s.redetects} (low confidence)")
        Log.i(TAG, "  Landmark Detection: %.2f ms (runs in parallel)".format(s.lmAvg))
        Log.i(TAG, "  Processing: %.2f ms".format(s.procAvg))
        Log.i(TAG, "    BlendShape: %.2f ms (%.1f%%)".format(
            s.bsAvg, if (s.procAvg > 0) 100.0 * s.bsAvg / s.procAvg else 0.0))
        Log.i(TAG, "    Dispatch:   %.2f ms (callback + Live2D params)".format(s.applyAvg))
        Log.i(TAG, "  Stale frames dropped: ${s.staleDrops} (> ${MAX_FRAME_AGE_MS}ms old)")
        Log.i(TAG, "  Bottleneck: Landmark (%.2f ms)".format(s.lmAvg))
        Log.i(TAG, "  Theoretical max FPS (1000/landmark_ms): %.0f FPS".format(theoreticalMaxFps))
        Log.i(TAG, "  Actual output FPS (UI display): %.1f FPS".format(outFps))
        frameJitterStats()?.let { (p50, p95, max) ->
            Log.i(TAG, "  Frame interval p50=%.1fms p95=%.1fms max=%.1fms (target 16.7ms)".format(p50, p95, max))
        }
        Log.i(TAG, "  MNN backend: ${faceDetector.getBackend()}, MNN threads: $mnnThreadCount")

        // 相机侧真相：CPU/GPU 都不忙却上不去 60，绝大多数情况就卡在这一行。
        val cbMs = camera2Manager.cameraCallbackAvgMs
        Log.i(TAG, "  Camera delivered: %.1f FPS (applied frameDuration=%.2f ms, callback avg=%.2f ms)"
            .format(camFps, frameDurMs, cbMs))
        Log.i(TAG, "    Requested AE range: ${camera2Manager.activeFpsRange}, " +
            "applied: ${camera2Manager.appliedFpsRange}, " +
            "exposure=${camera2Manager.appliedExposureNs / 1_000_000.0} ms, " +
            "pinFrameRate=${camera2Manager.pinFrameRate}")
        if (camFps > 0f && outFps > 0f && outFps < camFps * 0.85f) {
            Log.w(TAG, "  ⚠ Camera delivers %.1f FPS but pipeline outputs %.1f FPS ".format(camFps, outFps) +
                "→ 瓶颈在推理/后处理，不是相机")
        } else if (frameDurMs >= 30.0) {
            Log.w(TAG, ("  ⚠ SENSOR_FRAME_DURATION=%.2f ms（%.1f fps）→ 传感器/AE 本身就不是 60fps，" +
                "降采集尺寸 / 砍预览都无效").format(frameDurMs, 1000.0 / frameDurMs))
        } else if (camFps > 0f && camFps < 55f && cbMs >= 16.0f) {
            Log.w(TAG, ("  ⚠ 相机回调平均 %.2f ms 超过 16.7ms 预算 → 是处理链路在拖慢交付帧率，" +
                "不是传感器").format(cbMs))
        } else if (camFps > 0f && camFps < 55f) {
            Log.w(TAG, "  ⚠ Camera only delivers %.1f FPS ".format(camFps) +
                "→ 相机是瓶颈，先解决采集帧率再谈推理优化")
        }
    }

    private fun copyBuffer(source: ByteBuffer): ByteBuffer {
        source.rewind()
        val copy = ByteBuffer.allocateDirect(source.remaining())
        copy.put(source)
        copy.rewind()
        source.rewind()
        return copy
    }

    private fun calculateBoundingBox(landmarks: List<Point3D>): DetectionBox {
        if (landmarks.isEmpty()) return DetectionBox(0f, 0f, 1f, 1f)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (landmark in landmarks) {
            minX = minOf(minX, landmark.x)
            minY = minOf(minY, landmark.y)
            maxX = maxOf(maxX, landmark.x)
            maxY = maxOf(maxY, landmark.y)
        }

        val padding = 0.02f
        return DetectionBox(
            x1 = (minX - padding).coerceAtLeast(0f),
            y1 = (minY - padding).coerceAtLeast(0f),
            x2 = (maxX + padding).coerceAtMost(1f),
            y2 = (maxY + padding).coerceAtMost(1f),
            score = 1.0f,
        )
    }

    private fun updateFps() {
        recordFrameInterval()
        frameCount.incrementAndGet()
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsTime

        if (elapsed >= FPS_UPDATE_INTERVAL) {
            val finalCount = frameCount.getAndSet(0)
            val finalElapsed = now - lastFpsTime
            lastFpsTime = now

            currentFps = finalCount * 1000f / finalElapsed
            Log.d(TAG, "UI FPS update: count=$finalCount, elapsed=${finalElapsed}ms, fps=%.1f".format(currentFps))
            onFpsUpdate?.invoke(currentFps)
        }
    }

    /** 记录一帧的处理间隔，用于统计 jitter（p50 / p95 / max）。 */
    private fun recordFrameInterval() {
        val now = System.nanoTime()
        val last = lastProcessedFrameNanos
        if (last != 0L) {
            val deltaMs = (now - last) / 1_000_000.0f
            frameIntervalsMs[frameIntervalCursor] = deltaMs
            frameIntervalCursor = (frameIntervalCursor + 1) % FRAME_INTERVAL_WINDOW
            if (frameIntervalFilled < FRAME_INTERVAL_WINDOW) frameIntervalFilled++
        }
        lastProcessedFrameNanos = now
    }

    /**
     * @return Triple(p50, p95, max) 帧间隔（毫秒），样本不足时返回 null。
     */
    private fun frameJitterStats(): Triple<Float, Float, Float>? {
        val n = frameIntervalFilled
        if (n < 10) return null
        // 每 60 帧才调用一次，这里的一次性排序开销可以忽略
        val sorted = FloatArray(n)
        System.arraycopy(frameIntervalsMs, 0, sorted, 0, n)
        java.util.Arrays.sort(sorted)
        val p50 = sorted[(n * 0.50f).toInt().coerceIn(0, n - 1)]
        val p95 = sorted[(n * 0.95f).toInt().coerceIn(0, n - 1)]
        return Triple(p50, p95, sorted[n - 1])
    }
}
