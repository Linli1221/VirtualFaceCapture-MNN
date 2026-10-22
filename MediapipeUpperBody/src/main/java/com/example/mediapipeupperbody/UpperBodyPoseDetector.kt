package com.example.mediapipeupperbody

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
 * 相机 YUV_420_888
 *   → pushFrame()（相机线程）
 *   → 旋转 + 镜像 + 采样缩放到长边 256 的 ARGB Bitmap
 *   → detectAsync()（异步，同一时刻最多一个在飞）
 *   → MediaPipe 回调线程：33 点 → 13 点 → One-Euro 平滑 → 相对基线躯干角
 *   → onResult(UpperBodyPoseFrame)
 * ```
 *
 * 使用方式：
 * ```
 * val detector = UpperBodyPoseDetector(context)
 * detector.onResult = { frame -> ... }
 * detector.onError = { message -> Log.w(TAG, message) }
 * if (!detector.isModelAvailable() || !detector.initialize()) return  // 只关功能，不崩
 * detector.start()
 * // 相机回调里：
 * detector.pushFrame(yuv420Frame)
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
     * @param dropped 因限帧 / 上一帧仍在推理 / 转换失败而被丢弃的帧数
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

    /** 保活：MediaPipe 异步推理期间 Bitmap 必须一直可达。 */
    @Volatile
    private var inFlightBitmap: Bitmap? = null

    private var lastPipelineTimestampMs: Long = 0L

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
        started = true
    }

    /** 停止接受新帧（不销毁 landmarker，可再次 [start]）。 */
    fun stop() {
        started = false
        lastSubmitMs = 0L
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

    // ------------------------------ 输入 ------------------------------

    /**
     * 提交一帧相机 YUV（**相机线程同步调用**）。
     *
     * 本方法立即完成像素转换（约 1~4ms @ 长边 256），随后才异步推理；
     * 转换只读 `ByteBuffer.get(index)`，**不依赖 position**，也不会在返回后保留
     * ByteBuffer 引用 —— 调用返回后 [Yuv420Frame] 里的 ByteBuffer 可以安全失效。
     *
     * 以下情况直接丢弃（计入 `dropped`）且不抛异常：
     *  - 未 initialize / 未 start；
     *  - 距离上一帧不足 `1000/targetFps` 毫秒；
     *  - 上一次 detectAsync 还没回调（单飞）。
     */
    fun pushFrame(frame: Yuv420Frame) {
        if (!ready || !started) return

        stats.markInput()
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

        val bitmap: Bitmap? = try {
            converter.convert(frame, TARGET_LONG_SIDE)
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
        pendingFrameTimestampMs = System.currentTimeMillis()
        pendingSubmitNanos = System.nanoTime()
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

    // ------------------------------ MediaPipe 回调 ------------------------------

    /** 在 MediaPipe 回调线程执行。 */
    private fun onPoseResult(resultGeneration: Int, result: PoseLandmarkerResult) {
        // 旧实例（initialize / setBackend 重建前）的迟到回调：不要动新实例的单飞状态，
        // 否则会让相机线程以为空闲，进而对同一个 landmarker 并发 detectAsync。
        if (resultGeneration != generation) return

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
            val frame = buildFrame(result, frameTimestampMs)
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

    private fun buildFrame(result: PoseLandmarkerResult, timestampMs: Long): UpperBodyPoseFrame {
        val frameTimestampMs = if (timestampMs > 0L) timestampMs else System.currentTimeMillis()
        val pose = result.landmarks().firstOrNull()
        val rawPoints: List<BodyPoint>? = pose?.let { landmarks ->
            val converted = ArrayList<PoseLandmark>(landmarks.size)
            for (landmark in landmarks) {
                converted.add(
                    PoseLandmark(
                        x = landmark.x(),
                        y = landmark.y(),
                        z = landmark.z(),
                        visibility = landmark.visibility().orElse(0f),
                    )
                )
            }
            UpperBodyGeometry.extract(converted)
        }

        if (rawPoints == null || !UpperBodyGeometry.isTracking(rawPoints)) {
            synchronized(pipelineLock) {
                smoother.reset()
                lastPipelineTimestampMs = 0L
            }
            return UpperBodyPoseFrame.empty(frameTimestampMs)
        }

        val smoothed: List<BodyPoint>
        val relativeTorso: TorsoAngles
        synchronized(pipelineLock) {
            val dtSeconds = if (lastPipelineTimestampMs > 0L) {
                ((frameTimestampMs - lastPipelineTimestampMs).coerceIn(1L, MAX_PIPELINE_GAP_MS)) / 1000f
            } else {
                1f / targetFps.coerceIn(1, 60)
            }
            lastPipelineTimestampMs = frameTimestampMs

            smoothed = smoother.smooth(rawPoints, dtSeconds)
            val rawTorso = UpperBodyGeometry.torso(smoothed)
            val smoothTorso = smoother.smoothTorso(rawTorso, dtSeconds)
            relativeTorso = baseline.apply(smoothTorso)
        }

        return UpperBodyPoseFrame(
            points = smoothed,
            torso = if (relativeTorso.isValid) relativeTorso else TorsoAngles.ZERO,
            tracking = true,
            quality = UpperBodyGeometry.quality(rawPoints),
            timestampMs = frameTimestampMs,
        )
    }

    // ------------------------------ 内部工具 ------------------------------

    /**
     * Live-stream 时间戳：单调递增的毫秒。
     *
     * 用 CAS 而不是 synchronized(stateLock)：pushFrame 在相机线程调用，不能因为主线程
     * 正在 initialize / setBackend 而阻塞掉面捕帧。
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
        private const val TARGET_LONG_SIDE = Yuv420Frame.DEFAULT_MAX_LONG_SIDE
        private const val DEFAULT_TARGET_FPS = 15
        private const val CALIBRATION_FRAMES = TorsoBaseline.DEFAULT_CALIBRATION_FRAMES
        private const val DEFAULT_MIN_CONFIDENCE = 0.5f
        private const val ERROR_THROTTLE_MS = 2_000L
        private const val MAX_PIPELINE_GAP_MS = 500L
    }
}
