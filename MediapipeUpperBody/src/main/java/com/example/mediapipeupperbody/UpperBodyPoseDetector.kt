package com.example.mediapipeupperbody

import android.content.Context
import android.graphics.Bitmap
import com.vfc.log.VfcLog as Log
import com.example.commondata.BodyPoint
import com.example.commondata.TorsoAngles
import com.example.commondata.UpperBodyPoseFrame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 上半身关键点检测器：官方 MediaPipe Tasks **PoseLandmarker（LIVE_STREAM）**。
 *
 * 数据流（docs/upper-body.md §2 / §3.2）：
 * ```
 * 相机 YUV_420_888 + 同一帧的脸框
 *   → pushFrame(frame, faceHint)（相机线程：只把 Y/U/V 按 stride 拷进池化
 *     DirectByteBuffer，发布到「只保留最新」的 [YuvFrameRing]，立即返回）
 *   → upper-body-worker 线程：取最新帧
 *     → UpperBodyRoi.fromFace：脸框 → 上半身 ROI（旋转后源像素）
 *     → 只裁 ROI + 旋转 + 镜像 + 采样缩放到当前输入长边（256 / 320 自适应，只缩不放）的 ARGB Bitmap
 *     → detectAsync()（异步，同一时刻最多一个在飞）
 *   → MediaPipe 回调线程：33 点 → mapLocalToPreview 回映到整帧 → 13 点
 *     → One-Euro 平滑 → 相对基线躯干角（[rawOutput] = true 时跳过平滑 / 基线，产出原始点）
 *   → onResult(UpperBodyPoseFrame)
 * ```
 *
 * ROI 引导的要点：
 *  - 整帧 256 换成了「ROI 裁切 → 320p」，同样长边下上半身占的像素多 4~5 倍；
 *  - 输入长边自适应（[InputSizeMode.ADAPTIVE]）：正常跟踪用 256 保帧率，关键点质量下降 /
 *    脸框变小 / ROI miss 时临时升 320 保精度，连续若干帧质量恢复再回落（[InputSizePolicy]）；
 *  - 协议 ub[] 的语义不变：结果点回映到**整帧预览坐标**后再做 13 点提取 / 躯干角 / 平滑，
 *    所以 ROI 随脸移动时坐标不会跳；
 *  - ROI 只在「这一帧确实要送去推理」时计算（限帧 / 单飞丢掉的帧不做几何运算）；
 *  - ROI 帧没跟踪到上半身时回退整帧 [ROI_FALLBACK_MS] 再试，避免脸框偏了以后每帧空转；
 *  - 没有脸框（旧调用 / 面捕丢失）时与改造前一样按整帧推理。
 *
 * 线程模型：相机回调里只做一次平面拷贝（典型 0.15~0.4ms）就返回；ROI 计算、
 * YUV→Bitmap（1~4ms）与 detectAsync 提交全部在 `upper-body-worker` 线程
 * （[YuvFrameRing] 只保留最新帧：慢了就丢旧帧，绝不排队放大延迟）。
 *
 * 使用方式：
 * ```
 * val detector = UpperBodyPoseDetector(context)
 * detector.onResult = { frame -> ... }
 * detector.onError = { message -> Log.w(TAG, message) }
 * if (!detector.isModelAvailable() || !detector.initialize()) return  // 只关功能，不崩
 * detector.start()
 * // 相机回调里：
 * detector.pushFrame(yuv420Frame)               // 整帧（长边按 [inputSizeMode] 自适应）
 * detector.pushFrame(yuv420Frame, faceRoiHint)  // ROI 引导（推荐）
 * // 退出：
 * detector.release()
 * ```
 *
 * 约束：模块只依赖 :CommonData + tasks-vision + androidx.core；不引 Compose / App 类。
 */
class UpperBodyPoseDetector(context: Context) {

    /** 推理后端。GPU 优先；创建失败会自动回退 CPU。 */
    enum class Backend { GPU, CPU }

    /** 模型档位：lite ≈ 5.5MB（默认），full ≈ 9MB（更准、更慢）。 */
    enum class ModelVariant { LITE, FULL }

    /**
     * 运行统计（累计值，跨 start/stop 不清零）。
     *
     * @param framesIn 进入 pushFrame 且检测器处于 ready+started 的帧数
     * @param framesOut 真正产出（含 tracking=false 的空帧）的回调次数
     * @param dropped 因限帧 / 上一帧仍在推理 / 转换失败 / 被更新的帧顶掉而被丢弃的帧数
     * @param avgLatencyMs detectAsync 提交到结果回调的指数滑动平均耗时
     */
    data class Stats(
        val framesIn: Long,
        val framesOut: Long,
        val dropped: Long,
        val avgLatencyMs: Float,
    )

    // ------------------------------ 回调 ------------------------------

    /** 结果回调（**在 MediaPipe 回调线程触发**；tracking=false 时是 empty 帧）。 */
    @Volatile
    var onResult: ((UpperBodyPoseFrame) -> Unit)? = null

    /** 错误提示回调（模型缺失 / GPU 回退 / 推理错误等；不会抛异常给调用方）。 */
    @Volatile
    var onError: ((String) -> Unit)? = null

    // ------------------------------ 内部状态 ------------------------------

    private val appContext: Context = context.applicationContext
    private val stateLock = Any()
    private val pipelineLock = Any()
    private val converter = YuvBitmapConverter()

    /** 相机帧环形缓冲：只保留最新帧。相机线程只拷贝入队，upper-body-worker 线程消费。 */
    private val frameRing = YuvFrameRing { stats.markDropped() }

    /** 保护 workerThread 的创建 / 销毁。 */
    private val workerLock = Any()

    @Volatile
    private var workerThread: Thread? = null

    /** worker 线程的循环条件（false = 退出）。 */
    @Volatile
    private var workerRunning = false

    private val inputSizePolicy = InputSizePolicy()
    private val inFlight = AtomicBoolean(false)
    private val stats = StatsCounter()
    private val smoother = UpperBodySmoother()
    private val baseline = TorsoBaseline(CALIBRATION_FRAMES)

    @Volatile
    private var landmarker: PoseLandmarker? = null

    @Volatile
    private var ready = false

    @Volatile
    private var started = false

    @Volatile
    private var generation = 0

    @Volatile
    private var variant: ModelVariant = ModelVariant.LITE

    @Volatile
    private var targetFps: Int = DEFAULT_TARGET_FPS

    @Volatile
    private var lastSubmitMs: Long = 0L

    @Volatile
    private var lastErrorMs: Long = 0L

    /** 最近一次 initialize 实际使用的后端（GPU 创建失败时是 CPU）。 */
    private var activeBackend: Backend = Backend.CPU

    private var minDetection: Float = DEFAULT_MIN_CONFIDENCE
    private var minPresence: Float = DEFAULT_MIN_CONFIDENCE
    private var minTracking: Float = DEFAULT_MIN_CONFIDENCE

    /** MediaPipe 的 LIVE_STREAM 时间戳（单调递增的毫秒，无锁 CAS）。 */
    private val detectTimestampCounter = AtomicLong(0L)

    @Volatile
    private var pendingFrameTimestampMs: Long = 0L

    @Volatile
    private var pendingSubmitNanos: Long = 0L

    /**
     * 本帧提交时用的 ROI / 镜像 / 整帧尺寸。
     *
     * 单飞（[inFlight]）保证同一时刻只有一个在飞，所以这三组值一定属于「当前那一帧」；
     * 回调线程在放行单飞标记**之前**取局部快照，之后 upper-body-worker 线程才会覆盖它们。
     */
    @Volatile
    private var pendingRoi: RoiWindow? = null

    @Volatile
    private var pendingMirrored: Boolean = false

    @Volatile
    private var pendingFrameWidth: Int = 0

    @Volatile
    private var pendingFrameHeight: Int = 0

    /** ROI 帧失败后到期的整帧回退窗口（System.nanoTime 的毫秒）；0 / 已过期 = 正常用 ROI。 */
    @Volatile
    private var fallbackUntilMs: Long = 0L

    /** 首次 ROI 命中只打一条日志（worker 线程写）。 */
    @Volatile
    private var roiHitLogged: Boolean = false

    /** 首次 ROI 回退只打一条日志（回调线程写）。 */
    @Volatile
    private var roiFallbackLogged: Boolean = false

    /** 输入长边升降档只在切换时打一条日志（worker 线程 / MediaPipe 回调线程都可能触发）。 */
    @Volatile
    private var inputSizeBoostedLogged: Boolean = false

    /** 保活：MediaPipe 异步推理期间 Bitmap 必须一直可达。 */
    @Volatile
    private var inFlightBitmap: Bitmap? = null

    private var lastPipelineTimestampMs: Long = 0L

    /**
     * 「原始输出」模式（任务 M2，协议 `ubRaw`）：true 时 [buildFrame] 跳过 One-Euro 平滑 /
     * 躯干角平滑 / [TorsoBaseline]，直接产出原始 13 点与**未平滑、未减基线**的原始躯干角，
     * 供 PC 侧做后处理（One-Euro / 躯干角 / 基线迁到 PC）。
     *
     * 切换时在 [pipelineLock] 里清掉 smoother / 基线 / dt 锚点，保证切换干净：
     * 两种模式的滤波状态互不污染，切回来也不会把旧位置拖过来。
     * false（默认）时走 M1 的原有链路，任何数值与改动前完全一致。
     *
     * 由 TrackingServer 的协商聚合（所有客户端都声明 upperBodyPostV1）驱动。
     */
    @Volatile
    var rawOutput: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            synchronized(pipelineLock) {
                smoother.reset()
                lastPipelineTimestampMs = 0L
                baseline.reset()
            }
        }

    // ------------------------------ 查询 ------------------------------

    /**
     * 模型是否可用（assets 两个档位 / filesDir/models 下任一存在）。
     *
     * 不初始化、不抛异常，UI 可以在打开开关前先问一次。
     */
    fun isModelAvailable(): Boolean = findModel(variant) != null

    /** PoseLandmarker 是否已创建且未 release。 */
    fun isReady(): Boolean = ready && landmarker != null

    /** 当前累计统计。 */
    fun getStats(): Stats = stats.snapshot()

    // ------------------------------ 生命周期 ------------------------------

    /**
     * 创建 PoseLandmarker。
     *
     * 查找顺序：assets 里与 [variant] 对应的模型 → assets 里另一档位 →
     * `filesDir/models/<name>`（方便 adb push 免重新打包）。
     * 找到模型后，同一个 backend 内按「asset 相对路径 → cacheDir 绝对路径 →
     * filesDir 绝对路径」逐个尝试创建，全部失败才算这个 backend 不可用。
     * 找不到模型时返回 false 并回调一次 [onError]，**不抛异常**。
     *
     * GPU 创建失败会自动用 CPU 重建（候选失败 / 回退信息会合并成一次 onError 提示）；
     * CPU 也失败才返回 false。
     *
     * @param backend 期望后端（GPU 失败自动回退 CPU）
     * @param variant 模型档位（LITE 默认）
     */
    fun initialize(
        backend: Backend = Backend.GPU,
        variant: ModelVariant = ModelVariant.LITE,
    ): Boolean {
        val model = findModel(variant)
        if (model == null) {
            postError(
                "未找到上半身模型：assets/$MODEL_LITE、assets/$MODEL_FULL 或 " +
                    "filesDir/$FILE_MODEL_SUBDIR/ 下都没有 .task 文件。" +
                    "请运行 tools/fetch-pose-model.ps1（Windows）或 " +
                    "tools/fetch-pose-model.sh（macOS/Linux）后重新打包。"
            )
            return false
        }
        val messages = ArrayList<String>()
        var oldToClose: PoseLandmarker? = null
        var success = false
        synchronized(stateLock) {
            this.variant = variant
            oldToClose = landmarker
            landmarker = null
            ready = false
            inFlight.set(false)
            inFlightBitmap = null

            // 递增 generation：旧实例的异步回调全部作废
            val nextGeneration = generation + 1
            generation = nextGeneration

            var created: PoseLandmarker? = null
            var usedBackend = Backend.CPU
            if (backend == Backend.GPU) {
                created = tryCreateLocked(Backend.GPU, model, nextGeneration, messages)
                if (created != null) {
                    usedBackend = Backend.GPU
                } else {
                    messages += "GPU delegate 初始化失败，自动回退 CPU（仅提示，功能保持可用）"
                }
            }
            if (created == null) {
                created = tryCreateLocked(Backend.CPU, model, nextGeneration, messages)
                usedBackend = Backend.CPU
            }

            if (created != null) {
                landmarker = created
                activeBackend = usedBackend
                ready = true
                success = true
            } else {
                activeBackend = usedBackend
            }
        }

        runCatching { oldToClose?.close() }
        synchronized(pipelineLock) {
            smoother.reset()
            baseline.reset()
            lastPipelineTimestampMs = 0L
        }
        pendingFrameTimestampMs = 0L
        pendingSubmitNanos = 0L
        inputSizePolicy.reset()
        postErrors(messages)
        return success
    }

    /** 允许 [pushFrame] 提交推理（必须先 [initialize] 成功）。 */
    fun start() {
        if (!isReady()) {
            postError("UpperBodyPoseDetector.start()：尚未 initialize() 或初始化失败，已忽略。")
            return
        }
        lastSubmitMs = 0L
        // 每次 start 都是新的采集会话：清掉上一轮可能残留的 ROI 回退窗口。
        fallbackUntilMs = 0L
        inputSizePolicy.reset()
        // release() 会关掉环形缓冲与 worker，这里重开（并确保 worker 活着）
        frameRing.reset()
        ensureWorker()
        started = true
    }

    /** 停止接受新帧（不销毁 landmarker，可再次 [start]）。 */
    fun stop() {
        started = false
        lastSubmitMs = 0L
        // 已入队但还没消费的帧直接丢掉：stop 之后不再产出结果
        frameRing.drain()
    }

    /** 关闭 landmarker 并释放资源；之后可再次 [initialize] 复用本对象。 */
    fun release() {
        val old = synchronized(stateLock) {
            started = false
            ready = false
            generation++
            val current = landmarker
            landmarker = null
            current
        }
        inFlight.set(false)
        inFlightBitmap = null
        pendingFrameTimestampMs = 0L
        pendingSubmitNanos = 0L
        pendingRoi = null
        fallbackUntilMs = 0L
        roiHitLogged = false
        roiFallbackLogged = false
        inputSizePolicy.reset()
        inputSizeBoostedLogged = false
        shutdownWorker()
        runCatching { old?.close() }
        synchronized(pipelineLock) {
            smoother.reset()
            baseline.reset()
            lastPipelineTimestampMs = 0L
        }
    }

    // ------------------------------ 配置 ------------------------------

    /**
     * 运行时切换后端：先用新后端创建实例，成功才替换（失败返回 false 并保持原状态）。
     * 未 initialize 时只记录日志并返回 false。
     */
    fun setBackend(backend: Backend): Boolean {
        val messages = ArrayList<String>()
        var oldToClose: PoseLandmarker? = null
        var success = false
        synchronized(stateLock) {
            if (!ready) {
                Log.d(TAG, "setBackend(${backend.name}) 在 initialize() 之前调用，忽略")
                success = false
            } else if (activeBackend == backend) {
                success = true
            } else {
                val swap = rebuildLocked(backend, messages)
                if (swap.success) {
                    oldToClose = swap.old
                    success = true
                } else {
                    messages += "切换后端到 ${backend.name} 失败，保持 ${activeBackend.name}"
                }
            }
        }
        runCatching { oldToClose?.close() }
        postErrors(messages)
        return success
    }

    /** 重新采集中性姿态：接下来 ~10 个有效帧的均值作为新的 0° 基线。 */
    fun resetBaseline() {
        synchronized(pipelineLock) {
            baseline.reset()
            lastPipelineTimestampMs = 0L
        }
    }

    /**
     * 更新置信度阈值。若已初始化则立即重建 landmarker（失败则保持旧实例并回调 [onError]）。
     * 参数会被裁剪到 [0,1]。
     */
    fun setMinConfidences(detection: Float, presence: Float, tracking: Float) {
        val messages = ArrayList<String>()
        var oldToClose: PoseLandmarker? = null
        synchronized(stateLock) {
            minDetection = detection.coerceIn(0f, 1f)
            minPresence = presence.coerceIn(0f, 1f)
            minTracking = tracking.coerceIn(0f, 1f)
            if (ready) {
                val swap = rebuildLocked(activeBackend, messages)
                if (swap.success) {
                    oldToClose = swap.old
                } else {
                    messages += "置信度已更新，但重建 PoseLandmarker 失败，继续使用旧实例。"
                }
            }
        }
        runCatching { oldToClose?.close() }
        postErrors(messages)
    }

    /** 目标帧率（1~60，默认 15）。比这更密的帧会在 [pushFrame] 里直接丢弃。 */
    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceIn(1, 60)
        lastSubmitMs = 0L
    }

    /**
     * 输入长边档位（A/B 与自适应策略，见 [InputSizeMode]）。
     *
     *  - [InputSizeMode.PRECISION]：固定 320 —— 优先精度（A/B 的「320」组）；
     *  - [InputSizeMode.FRAME_RATE]：固定 256 —— 优先帧率（A/B 的「256」组）；
     *  - [InputSizeMode.ADAPTIVE]（默认）：正常跟踪用 256，关键点质量下降 / 脸框变小 /
     *    ROI miss 时临时升 320，连续若干帧质量恢复再回落。
     *
     * 只改输入预处理尺寸，**不改模型**；协议输出 / 坐标语义三种档位完全一致。
     * 切换档位会清空升降档状态（不继承上一档的连击）。
     */
    var inputSizeMode: InputSizeMode
        get() = inputSizePolicy.mode
        set(value) {
            inputSizePolicy.mode = value
            inputSizeBoostedLogged = false
        }

    /** 当前是否升档到 [InputSizePolicy.boostLongSide]（调试 / A/B 统计用）。 */
    fun isInputSizeBoosted(): Boolean = inputSizePolicy.isBoosted()

    // ------------------------------ 输入 ------------------------------

    /**
     * 提交一帧相机 YUV（**相机线程调用**；只拷贝 + 入队，立即返回）。
     *
     * @param frame 相机 YUV 帧（ByteBuffer 只在相机回调期间有效）
     * @param faceHint 同一帧上的脸框（旋转后、未镜像的归一化坐标）。null / 非法 / 脸太小 → 整帧；
     *   有效时按「脸框 → 上半身 ROI」裁切后再缩放到当前输入长边（256 / 320 自适应，
     *   只缩不放，见 [UpperBodyRoi] / [InputSizeMode]）。
     *
     * 本方法只把 Y/U/V 按 stride 拷进池化 DirectByteBuffer（[YuvFrameRing]，典型 0.15~0.4ms）
     * 并发布为「最新帧」，**不做** ROI / 缩放 / Bitmap —— 那些在 `upper-body-worker` 线程做
     * （见 [processFrame]）。拷贝只读 `ByteBuffer.get(index)`，**不依赖 position**，返回后
     * 不保留 ByteBuffer 引用 —— 调用返回后 [Yuv420Frame] 里的 ByteBuffer 可以安全失效。
     *
     * 队列**只保留最新帧**：上一帧还没被 worker 消费时直接被顶掉（计入 `dropped`），
     * 绝不排队放大端到端延迟。
     *
     * 以下情况直接丢弃（计入 `dropped`）且不抛异常：
     *  - 未 initialize / 未 start；
     *  - 队列已关闭（release / 停机）、帧非法或无槽可用。
     *  worker 侧还会丢掉：距离上一帧不足 `1000/targetFps` 毫秒、上一次 detectAsync
     *  还没回调（单飞）的帧（丢帧时连 ROI 都不算）。
     */
    fun pushFrame(frame: Yuv420Frame, faceHint: FaceRoiHint? = null) {
        if (!ready || !started) return

        stats.markInput()
        if (!ensureWorker()) {
            stats.markDropped()
            return
        }
        val accepted = try {
            frameRing.submit(frame, faceHint)
        } catch (t: Throwable) {
            Log.e(TAG, "上半身帧拷贝/入队失败", t)
            postErrorThrottled("上半身帧入队失败：" + (t.message ?: t.javaClass.simpleName))
            false
        }
        if (!accepted) stats.markDropped()
    }

    /**
     * `upper-body-worker` 线程：对「最新一帧」做限帧 / ROI / 转换 / detectAsync。
     *
     * 就是改造前 [pushFrame] 的同步主体整体搬到 worker 线程：限帧、单飞、ROI 计算、
     * 输入长边自适应、YUV→Bitmap（约 1~4ms）不再占用 Camera2 的 16.7ms 帧预算。
     *
     * @param captureTimestampMs 相机回调时刻（[UpperBodyPoseFrame.timestampMs] 用它，
     *   比 worker 的处理时刻更接近真实采集时刻）
     */
    private fun processFrame(frame: Yuv420Frame, faceHint: FaceRoiHint?, captureTimestampMs: Long) {
        if (!ready || !started) return

        val nowMs = System.nanoTime() / 1_000_000L
        val fps = targetFps.coerceIn(1, 60)
        val minIntervalMs = max(1L, 1000L / fps)
        val lastSubmit = lastSubmitMs
        if (lastSubmit != 0L && nowMs - lastSubmit < minIntervalMs) {
            stats.markDropped()
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            stats.markDropped()
            return
        }
        lastSubmitMs = nowMs
        // 记住这一帧所属的 generation：转换期间如果主线程重建了 landmarker，
        // 本帧必须作废，且不能去清理「新实例」的单飞标记。
        val frameGeneration = generation

        // ROI 只在「这一帧确实要送去推理」之后才算：被限帧 / 单飞丢掉的帧不做任何几何运算。
        val rotatedWidth = frame.rotatedWidth
        val rotatedHeight = frame.rotatedHeight
        val fallbackActive = nowMs < fallbackUntilMs
        // 回退窗口内一律整帧：上一帧的 ROI 结果不可信，先让 MediaPipe 看全景。
        val hint = if (fallbackActive) null else faceHint
        val roi = UpperBodyRoi.fromFace(hint, rotatedWidth, rotatedHeight)
        val usingRoi = !roi.isFullFrame(rotatedWidth, rotatedHeight)
        if (usingRoi && !roiHitLogged) {
            roiHitLogged = true
            Log.i(
                TAG,
                "ROI 引导生效：${roi.width}×${roi.height} @(${roi.x0},${roi.y0})，" +
                    "整帧 ${rotatedWidth}×${rotatedHeight}",
            )
        }

        // 输入长边：正常 256 保帧率；脸框变小 / ROI 回退 / 近期质量差 → 320 保精度。
        val faceAreaFraction = if (faceHint != null && faceHint.isValid) {
            faceHint.width * faceHint.height
        } else {
            0f
        }
        val longSide = inputSizePolicy.selectLongSide(faceAreaFraction, fallbackActive)
        logInputSizeTransition()

        val bitmap: Bitmap? = try {
            converter.convert(frame, longSide, roi)
        } catch (t: Throwable) {
            Log.e(TAG, "YUV → Bitmap 转换失败", t)
            postErrorThrottled("上半身 YUV 转换失败：${t.message ?: t.javaClass.simpleName}")
            null
        }
        if (bitmap == null) {
            if (generation == frameGeneration) inFlight.set(false)
            stats.markDropped()
            return
        }

        val currentLandmarker = if (generation == frameGeneration) landmarker else null
        if (currentLandmarker == null) {
            if (generation == frameGeneration) inFlight.set(false)
            stats.markDropped()
            return
        }

        val timestamp = nextDetectTimestamp()
        pendingFrameTimestampMs = if (captureTimestampMs > 0L) captureTimestampMs else System.currentTimeMillis()
        pendingSubmitNanos = System.nanoTime()
        // 单飞保证：同一时刻只有一个在飞，所以这一组参数一定属于本帧；
        // 回调线程放行单飞标记之前会把它们取成局部快照。
        pendingRoi = roi
        pendingMirrored = frame.mirrorHorizontal
        pendingFrameWidth = rotatedWidth
        pendingFrameHeight = rotatedHeight
        inFlightBitmap = bitmap
        try {
            currentLandmarker.detectAsync(BitmapImageBuilder(bitmap).build(), timestamp)
        } catch (t: Throwable) {
            Log.e(TAG, "detectAsync 失败", t)
            if (generation == frameGeneration) {
                inFlightBitmap = null
                inFlight.set(false)
            }
            stats.markDropped()
            postErrorThrottled("上半身 detectAsync 失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ------------------------------ 工作线程 ------------------------------

    /**
     * 确保 `upper-body-worker` 线程活着（pushFrame 在相机线程上调用，只在冷启动时进锁）。
     */
    private fun ensureWorker(): Boolean {
        if (workerThread?.isAlive == true) return true
        synchronized(workerLock) {
            val current = workerThread
            if (current != null && current.isAlive) return true
            return try {
                workerRunning = true
                val thread = Thread({ workerLoop() }, WORKER_THREAD_NAME)
                thread.isDaemon = true
                thread.start()
                workerThread = thread
                Log.i(TAG, "上半身工作线程已启动")
                true
            } catch (t: Throwable) {
                workerRunning = false
                Log.e(TAG, "启动上半身工作线程失败", t)
                false
            }
        }
    }

    /** 停掉 worker 线程并关闭环形缓冲（release 用）；之后再次 start() 会自动重建。 */
    private fun shutdownWorker() {
        val thread: Thread?
        synchronized(workerLock) {
            workerRunning = false
            thread = workerThread
            workerThread = null
        }
        frameRing.close()
        if (thread != null && thread.isAlive) {
            try {
                thread.join(WORKER_JOIN_TIMEOUT_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** `upper-body-worker` 线程主体：只消费「最新帧」，空闲时挂在环形缓冲上等。 */
    private fun workerLoop() {
        while (workerRunning) {
            val slot = frameRing.takeLatest(WORKER_IDLE_WAIT_MS) ?: continue
            try {
                val frame = slot.frame
                if (frame != null && workerRunning) {
                    processFrame(frame, slot.faceHint, slot.captureTimestampMs)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "上半身工作线程处理帧失败", t)
            } finally {
                frameRing.release(slot)
            }
        }
    }

    // ------------------------------ MediaPipe 回调 ------------------------------

    /** 在 MediaPipe 回调线程执行。 */
    private fun onPoseResult(resultGeneration: Int, result: PoseLandmarkerResult) {
        // 旧实例（initialize / setBackend 重建前）的迟到回调：不要动新实例的单飞状态，
        // 否则会让 upper-body-worker 线程以为空闲，进而对同一个 landmarker 并发 detectAsync。
        if (resultGeneration != generation) return

        // 单飞保证这一组值属于同一个提交。**先取局部快照，再放行单飞标记**：
        // 之后相机线程可能立刻提交下一帧并覆盖 pending*。
        val roi = pendingRoi
        val mirrored = pendingMirrored
        val frameWidth = pendingFrameWidth
        val frameHeight = pendingFrameHeight
        val frameTimestampMs = pendingFrameTimestampMs
        val submitNanos = pendingSubmitNanos
        val latencyMs = if (submitNanos > 0L) {
            (System.nanoTime() - submitNanos) / 1_000_000f
        } else {
            0f
        }
        inFlightBitmap = null
        pendingSubmitNanos = 0L
        inFlight.set(false)

        if (!started || !ready) return

        try {
            val frame = buildFrame(result, frameTimestampMs, roi, mirrored, frameWidth, frameHeight)
            stats.markOutput(latencyMs)
            try {
                onResult?.invoke(frame)
            } catch (t: Throwable) {
                Log.e(TAG, "onResult 回调抛异常", t)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "处理上半身检测结果失败", t)
            postErrorThrottled("处理上半身结果失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 在 MediaPipe 回调线程执行。 */
    private fun onPipelineError(resultGeneration: Int, error: RuntimeException) {
        if (resultGeneration != generation) return
        inFlightBitmap = null
        inFlight.set(false)
        if (!ready) return
        postErrorThrottled("MediaPipe Pose 推理错误：${error.message ?: error.javaClass.simpleName}")
    }

    /**
     * 33 点 → 协议帧。
     *
     * 关键顺序：**先**用 [UpperBodyRoi.mapLocalToPreview] 把 ROI 局部坐标回映到整帧预览坐标，
     * **再**交给 [UpperBodyGeometry.extract]。这样协议 ub[]、躯干角、平滑全都工作在整帧坐标上，
     * 与改造前（整帧推理）语义一致，ROI 随脸移动时也不会出现坐标跳变。
     *
     * @param roi 本帧推理用的 ROI（null / 整帧 = 恒等映射）
     * @param mirrored 本帧整帧预览是否镜像
     * @param frameWidth 旋转后帧宽（回映用）
     * @param frameHeight 旋转后帧高（回映用）
     */
    private fun buildFrame(
        result: PoseLandmarkerResult,
        timestampMs: Long,
        roi: RoiWindow?,
        mirrored: Boolean,
        frameWidth: Int,
        frameHeight: Int,
    ): UpperBodyPoseFrame {
        val frameTimestampMs = if (timestampMs > 0L) timestampMs else System.currentTimeMillis()
        val pose = result.landmarks().firstOrNull()
        val rawPoints: List<BodyPoint>? = pose?.let { landmarks ->
            val converted = ArrayList<PoseLandmark>(landmarks.size)
            for (landmark in landmarks) {
                val localX = landmark.x()
                val localY = landmark.y()
                val localZ = landmark.z()
                val mapped = if (roi != null && frameWidth > 0 && frameHeight > 0) {
                    UpperBodyRoi.mapLocalToPreview(
                        localX = localX,
                        localY = localY,
                        localZ = localZ,
                        roi = roi,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        mirrored = mirrored,
                    )
                } else {
                    // 没有 ROI 信息（理论上只可能出现在状态错乱的边界）：原样当作整帧坐标
                    UpperBodyRoi.PreviewPoint(localX, localY, localZ)
                }
                converted.add(
                    PoseLandmark(
                        x = mapped.x,
                        y = mapped.y,
                        z = mapped.z,
                        visibility = landmark.visibility().orElse(0f),
                    )
                )
            }
            UpperBodyGeometry.extract(converted)
        }

        val points = rawPoints
        if (points == null || !UpperBodyGeometry.isTracking(points)) {
            // 没跟踪到是质量最差的反馈：立刻升档，下一帧多喂一点像素
            inputSizePolicy.onFrameResult(tracked = false, quality = 0f)
            logInputSizeTransition()
            markRoiMiss(roi, frameWidth, frameHeight)
            synchronized(pipelineLock) {
                smoother.reset()
                lastPipelineTimestampMs = 0L
            }
            // 空帧也带上当前模式标记（ubOk=false 时协议的 ubRaw 仍逐帧出现，语义不来回跳）
            val empty = UpperBodyPoseFrame.empty(frameTimestampMs)
            return if (rawOutput) empty.copy(rawMode = true) else empty
        }

        // ROI 帧成功：清掉可能残留的回退窗口（回退窗口内的帧一定不是 ROI 帧，
        // 所以这里不会把「正在回退」的状态提前清掉，避免 ROI 持续失败时逐帧抖动）。
        if (roi != null && !roi.isFullFrame(frameWidth, frameHeight)) {
            fallbackUntilMs = 0L
        }

        // 任务 M2：rawOutput = true 时跳过 One-Euro / smoothTorso / TorsoBaseline，
        // points / torso 直接来自原始提取点（未平滑、未减基线），由 PC 侧做后处理；
        // rawOutput = false 时走 M1 的原有链路，计算过程逐行保持不变。
        val useRaw = rawOutput
        val smoothed: List<BodyPoint>
        val frameTorso: TorsoAngles
        if (useRaw) {
            smoothed = points
            frameTorso = UpperBodyGeometry.torso(points)
        } else {
            synchronized(pipelineLock) {
                val dtSeconds = if (lastPipelineTimestampMs > 0L) {
                    ((frameTimestampMs - lastPipelineTimestampMs).coerceIn(1L, MAX_PIPELINE_GAP_MS)) / 1000f
                } else {
                    1f / targetFps.coerceIn(1, 60)
                }
                lastPipelineTimestampMs = frameTimestampMs

                smoothed = smoother.smooth(points, dtSeconds)
                val rawTorso = UpperBodyGeometry.torso(smoothed)
                val smoothTorso = smoother.smoothTorso(rawTorso, dtSeconds)
                frameTorso = baseline.apply(smoothTorso)
            }
        }

        val quality = UpperBodyGeometry.quality(points)
        inputSizePolicy.onFrameResult(tracked = true, quality = quality)
        logInputSizeTransition()

        return UpperBodyPoseFrame(
            points = smoothed,
            torso = if (frameTorso.isValid) frameTorso else TorsoAngles.ZERO,
            tracking = true,
            quality = quality,
            timestampMs = frameTimestampMs,
            rawMode = useRaw,
        )
    }

    /**
     * 输入长边档位切换时打一条日志（只在自适应模式打；固定档位是显式 A/B 选择，不刷屏）。
     */
    private fun logInputSizeTransition() {
        if (inputSizePolicy.mode != InputSizeMode.ADAPTIVE) return
        val boosted = inputSizePolicy.isBoosted()
        if (boosted == inputSizeBoostedLogged) return
        inputSizeBoostedLogged = boosted
        if (boosted) {
            Log.i(
                TAG,
                "输入长边升档 → ${inputSizePolicy.boostLongSide}" +
                    "（关键点质量下降 / 脸框变小 / ROI miss）",
            )
        } else {
            Log.i(TAG, "输入长边回落 → ${inputSizePolicy.normalLongSide}（连续若干帧质量恢复）")
        }
    }

    /**
     * 本帧用了 ROI 但没跟踪到上半身 → 开一段整帧回退窗口（只打一次日志）。
     *
     * 为什么不逐帧清掉：ROI 若持续框偏（脸框不可信 / 人离开画面），「整帧成功一帧就回 ROI」
     * 会让 ROI / 整帧逐帧交替，ubOk 以 ~7.5Hz 抖动，客户端手臂参数会被反复拉回默认值。
     * 现在的行为：窗口内**一律**整帧，窗口到期自动恢复 ROI；ROI 帧成功才提前清掉残留窗口。
     */
    private fun markRoiMiss(roi: RoiWindow?, frameWidth: Int, frameHeight: Int) {
        if (roi == null || roi.isEmpty || roi.isFullFrame(frameWidth, frameHeight)) return
        fallbackUntilMs = System.nanoTime() / 1_000_000L + ROI_FALLBACK_MS
        if (!roiFallbackLogged) {
            roiFallbackLogged = true
            Log.i(
                TAG,
                "ROI ${roi.width}×${roi.height} @(${roi.x0},${roi.y0}) 未跟踪到上半身，" +
                    "回退整帧 ${ROI_FALLBACK_MS}ms 后再试 ROI",
            )
        }
    }

    // ------------------------------ 内部工具 ------------------------------

    /**
     * Live-stream 时间戳：单调递增的毫秒。
     *
     * 用 CAS 而不是 synchronized(stateLock)：processFrame 在 upper-body-worker 线程调用，
     * 不能因为主线程正在 initialize / setBackend 而阻塞掉这一路推理。
     */
    private fun nextDetectTimestamp(): Long {
        while (true) {
            val previous = detectTimestampCounter.get()
            var timestamp = System.nanoTime() / 1_000_000L
            if (timestamp <= previous) timestamp = previous + 1L
            if (detectTimestampCounter.compareAndSet(previous, timestamp)) return timestamp
        }
    }

    private class SwapResult(val success: Boolean, val old: PoseLandmarker?)

    /**
     * 用当前模型 / 置信度 / 指定后端重建 landmarker（调用方需持有 stateLock）。
     * 失败时不动原实例，由调用方决定是否报错。
     */
    private fun rebuildLocked(backend: Backend, messages: MutableList<String>): SwapResult {
        val model = findModel(variant)
        if (model == null) {
            messages += "模型已不可用，无法重建 PoseLandmarker。"
            return SwapResult(false, null)
        }
        val nextGeneration = generation + 1
        val created = tryCreateLocked(
            backend = backend,
            model = model,
            resultGeneration = nextGeneration,
            messages = messages,
        ) ?: return SwapResult(false, null)

        val old = landmarker
        landmarker = created
        generation = nextGeneration
        activeBackend = backend
        inFlight.set(false)
        inFlightBitmap = null
        return SwapResult(true, old)
    }

    /**
     * 依次尝试同一个 backend 下的模型候选路径（docs/upper-body.md §3.2-4）：
     *
     *  a. assets 相对路径（官方写法；部分 tasks-vision 版本只认 asset 名）；
     *  b. cacheDir 绝对路径（首次从 assets 复制，绕开部分版本/设备的 openFd 限制）；
     *  c. filesDir/models 绝对路径（adb push 的模型优先走这里，不复制）。
     *
     * assets 来源：a → b → c；filesDir 来源：c → a → b。
     * 每个候选失败都记录一条消息并继续，全部失败才返回 null。
     */
    private fun tryCreateLocked(
        backend: Backend,
        model: ModelSource,
        resultGeneration: Int,
        messages: MutableList<String>,
    ): PoseLandmarker? {
        // a / b：assets 模型先试 asset 相对路径，再试复制到 cacheDir 后的绝对路径
        if (model.asset) {
            createAt(backend, model.name, "assets/${model.name}", resultGeneration, messages)
                ?.let { return it }
            val cache = copyAssetToCache(model.name)
            if (cache == null) {
                messages += "assets/${model.name} 复制到 cacheDir 失败，跳过 cacheDir 候选。"
            } else {
                createAt(backend, cache.absolutePath, "cacheDir/${cache.name}", resultGeneration, messages)
                    ?.let { return it }
            }
        }

        // c：filesDir/models 的绝对路径（filesDir 来源的模型第一时间走这里）
        val filesDirModel = File(File(appContext.filesDir, FILE_MODEL_SUBDIR), model.name)
        if (filesDirModel.isFile && filesDirModel.length() > 0L) {
            createAt(
                backend = backend,
                modelPath = filesDirModel.absolutePath,
                label = "filesDir/$FILE_MODEL_SUBDIR/${filesDirModel.name}",
                resultGeneration = resultGeneration,
                messages = messages,
            )?.let { return it }
        }

        // filesDir 模型但绝对路径不被当前版本支持时，再回退 assets / cache
        if (!model.asset && assetExists(model.name)) {
            createAt(backend, model.name, "assets/${model.name}", resultGeneration, messages)
                ?.let { return it }
            val cache = copyAssetToCache(model.name)
            if (cache != null) {
                createAt(backend, cache.absolutePath, "cacheDir/${cache.name}", resultGeneration, messages)
                    ?.let { return it }
            }
        }
        return null
    }

    private fun createAt(
        backend: Backend,
        modelPath: String,
        label: String,
        resultGeneration: Int,
        messages: MutableList<String>,
    ): PoseLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelPath)
                .setDelegate(if (backend == Backend.GPU) Delegate.GPU else Delegate.CPU)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(minDetection)
                .setMinPosePresenceConfidence(minPresence)
                .setMinTrackingConfidence(minTracking)
                .setResultListener { result, _ ->
                    onPoseResult(resultGeneration, result)
                }
                .setErrorListener { error ->
                    onPipelineError(resultGeneration, error)
                }
                .build()

            PoseLandmarker.createFromOptions(appContext, options)
        } catch (t: Throwable) {
            Log.e(TAG, "创建 PoseLandmarker 失败（${backend.name} / $label）", t)
            messages += "创建 PoseLandmarker 失败（${backend.name} / $label）：" +
                (t.message ?: t.javaClass.simpleName)
            null
        }
    }

    // ------------------------------ 模型查找 ------------------------------

    /** 一个候选模型：要么在 assets 里，要么在 filesDir/models 里。 */
    private class ModelSource(val name: String, val asset: Boolean)

    /**
     * 查找顺序：assets 里与 variant 对应的档位 → assets 里另一档位 →
     * filesDir/models 里同名文件。
     */
    private fun findModel(variant: ModelVariant): ModelSource? {
        val names = when (variant) {
            ModelVariant.LITE -> arrayOf(MODEL_LITE, MODEL_FULL)
            ModelVariant.FULL -> arrayOf(MODEL_FULL, MODEL_LITE)
        }
        for (name in names) {
            if (assetExists(name)) return ModelSource(name = name, asset = true)
        }
        val modelsDir = File(appContext.filesDir, FILE_MODEL_SUBDIR)
        for (name in names) {
            val file = File(modelsDir, name)
            if (file.isFile && file.length() > 0L) return ModelSource(name = name, asset = false)
        }
        return null
    }

    private fun assetExists(name: String): Boolean {
        return try {
            appContext.assets.open(name).close()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 把 assets 里的模型复制到 `cacheDir/upperbody_models/`，返回缓存文件。
     *
     * 这是 [tryCreateLocked] 的候选 b：某些 tasks-vision 版本 / 设备对「压缩过的 asset」
     * 用 openFd 读取会抛 "This file can not be opened as a file descriptor"，复制成普通
     * 文件后传绝对路径可以绕过。复制只发生在首次初始化（5.5MB 级模型约几十毫秒），
     * 之后直接命中缓存。
     */
    private fun copyAssetToCache(assetName: String): File? {
        val cacheDir = File(appContext.cacheDir, CACHE_MODEL_SUBDIR)
        val target = File(cacheDir, assetName)
        if (target.isFile && target.length() > 0L) return target
        return try {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                Log.e(TAG, "无法创建模型缓存目录：$cacheDir")
                return null
            }
            val tmp = File(cacheDir, "$assetName.tmp")
            appContext.assets.open(assetName).use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (tmp.length() <= 0L) {
                tmp.delete()
                null
            } else {
                if (target.exists()) target.delete()
                if (tmp.renameTo(target)) target else tmp
            }
        } catch (t: Throwable) {
            Log.e(TAG, "复制模型 asset 到 cacheDir 失败：$assetName", t)
            null
        }
    }

    // ------------------------------ 错误 / 统计 ------------------------------

    /**
     * 把一次操作里的多条诊断信息合并成**一条** onError：
     * 契约要求 GPU 回退只提示一次，候选路径逐个失败的信息仍完整保留在文本里。
     */
    private fun postErrors(messages: List<String>) {
        if (messages.isEmpty()) return
        postError(messages.joinToString("；"))
    }

    private fun postError(message: String) {
        Log.w(TAG, message)
        try {
            onError?.invoke(message)
        } catch (t: Throwable) {
            Log.e(TAG, "onError 回调抛异常", t)
        }
    }

    /** 推理 / 转换类错误可能每帧都来，2 秒内只提示一次，避免刷屏拖慢相机线程。 */
    private fun postErrorThrottled(message: String) {
        val nowMs = System.nanoTime() / 1_000_000L
        if (nowMs - lastErrorMs < ERROR_THROTTLE_MS) return
        lastErrorMs = nowMs
        postError(message)
    }

    private class StatsCounter {
        private var framesIn: Long = 0L
        private var framesOut: Long = 0L
        private var dropped: Long = 0L
        private var avgLatencyMs: Float = 0f

        @Synchronized
        fun markInput() {
            framesIn++
        }

        @Synchronized
        fun markDropped() {
            dropped++
        }

        @Synchronized
        fun markOutput(latencyMs: Float) {
            framesOut++
            if (latencyMs.isFinite() && latencyMs >= 0f) {
                avgLatencyMs = if (framesOut == 1L) {
                    latencyMs
                } else {
                    avgLatencyMs * 0.8f + latencyMs * 0.2f
                }
            }
        }

        @Synchronized
        fun snapshot(): Stats = Stats(
            framesIn = framesIn,
            framesOut = framesOut,
            dropped = dropped,
            avgLatencyMs = avgLatencyMs,
        )
    }

    companion object {
        private const val TAG = "UpperBodyPoseDetector"

        /** 默认（lite）模型文件名：放在 `MediapipeUpperBody/src/main/assets/`。 */
        const val MODEL_LITE = "pose_landmarker_lite.task"

        /** 高精度模型文件名（full）。 */
        const val MODEL_FULL = "pose_landmarker_full.task"

        private const val FILE_MODEL_SUBDIR = "models"
        private const val CACHE_MODEL_SUBDIR = "upperbody_models"

        /**
         * 送进 PoseLandmarker 的 Bitmap 长边由 [InputSizePolicy] 逐帧选定（256 / 320 自适应，
         * 见 [InputSizeMode]），不再是一个固定常量。
         *
         * 仍不用 [Yuv420Frame.DEFAULT_MAX_LONG_SIDE]（256）：那个常量只是帧对象的默认值，
         * 保留 256 是为了不改变 [Yuv420Frame.targetSize] 的既有语义。
         */

        /** ROI 帧没跟踪到上半身时，改用整帧推理的时长（ms）。 */
        private const val ROI_FALLBACK_MS = 1_000L

        private const val DEFAULT_TARGET_FPS = 15
        private const val CALIBRATION_FRAMES = TorsoBaseline.DEFAULT_CALIBRATION_FRAMES
        private const val DEFAULT_MIN_CONFIDENCE = 0.5f

        /** 工作线程名（ROI / YUV→Bitmap / detectAsync 的执行线程）。 */
        private const val WORKER_THREAD_NAME = "upper-body-worker"

        /** worker 空闲时在环形缓冲上等一帧的时长（ms）；只影响停机响应速度。 */
        private const val WORKER_IDLE_WAIT_MS = 100L

        /** release() 停 worker 时的 join 上限（ms）；超时不再等，避免卡住调用方。 */
        private const val WORKER_JOIN_TIMEOUT_MS = 500L

        private const val ERROR_THROTTLE_MS = 2_000L
        private const val MAX_PIPELINE_GAP_MS = 500L
    }
}
