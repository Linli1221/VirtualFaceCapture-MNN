package com.example.mediapipefacelandmark

import android.content.Context
import android.util.Log
import com.example.commondata.BlendShapeResult
import com.example.commondata.Point3D
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Smoothing filter type enumeration
 * Controls which smoothing algorithm is used for landmarks and detection
 */
enum class SmoothingFilterType(val value: Int) {
    ONE_EURO(0),       // Original MediaPipe filter (velocity adaptive) - can jitter at high FPS
    TIME_BASED_EMA(1), // Frame-rate independent EMA (recommended for high FPS)
    GAUSSIAN(2),       // Gaussian moving average
    EMA(3),            // Simple fixed-alpha EMA
    NONE(4)            // No smoothing
}

/**
 * Filter configuration parameters
 */
data class FilterConfig(
    val type: SmoothingFilterType = SmoothingFilterType.TIME_BASED_EMA,

    // One Euro Filter params
    val oneEuroMinCutoff: Float = 0.05f,
    val oneEuroBeta: Float = 80.0f,
    val oneEuroDerivCutoff: Float = 1.0f,

    // Time-Based EMA params (recommended)
    val timeEmaTau: Float = 0.08f,  // 80ms time constant

    // Gaussian params
    val gaussianWindowSize: Int = 5,
    val gaussianSigma: Float = 1.5f,

    // Simple EMA params
    val emaAlpha: Float = 0.3f
)

/**
 * MNN 推理后端。
 *
 * 三个模型（检测 / Landmark / BlendShape）历史上全部强制跑
 * `MNN_FORWARD_VULKAN`。实际的整机帧率取决于 GPU 驱动、与 Live2D 渲染的 GPU
 * 争用、以及每帧 GPU→CPU 回读的同步开销，不同机型差异极大：
 *
 * - [VULKAN]：默认后端，GPU 上算子更快，但每帧有上传/回读同步；
 * - [CPU]：纯 CPU，配合多线程后没有 GPU 争用与回读停顿，在高刷/大分辨率
 *   机型上往往更「稳」（帧时间抖动更小）。
 *
 * 现在可以在运行时切换，方便按机型实测选出能稳住 60fps 的那一档。
 */
enum class MnnBackend {
    VULKAN,
    CPU;

    companion object {
        fun fromNative(useVulkan: Boolean): MnnBackend = if (useVulkan) VULKAN else CPU
    }
}

/**
 * Landmark detection result with presence score
 * @param landmarks 478 facial landmarks
 * @param presenceScore Confidence that a face is present [0, 1]
 */
data class LandmarkResult(
    val landmarks: List<Point3D>,
    val presenceScore: Float
) {
    /**
     * Whether the detection is valid (presence score >= 0.5)
     */
    val isValid: Boolean get() = presenceScore >= 0.5f && landmarks.isNotEmpty()
}

/**
 * MediaPipe Face Detector - Kotlin wrapper for native face detection
 * Detects 478 facial landmarks from RGB image data
 */
class MediaPipeFaceDetector(private val context: Context) {
    
    private var isInitialized = false
    
    companion object {
        private const val TAG = "MediaPipeFaceDetector"

        /**
         * 除了主引擎之外，最多还能开几路并行关键点引擎（GPU 并行）。
         * 必须与原生侧的 `kMaxEngines - 1` 保持一致。
         */
        const val MAX_EXTRA_ENGINES = 3
        
        // Model file names in assets
        private const val DETECTOR_MODEL = "face_detector_mp.mnn"
        private const val LANDMARKER_MODEL = "face_landmark_detector_mp.mnn"
        private const val BLENDSHAPE_MODEL = "face_blendshape_mp.mnn"
        
        // Default detection parameters
        private const val DEFAULT_SCORE_THRESHOLD = 0.5f
        private const val DEFAULT_IOU_THRESHOLD = 0.3f
        private const val DEFAULT_PRESENCE_THRESHOLD = 0.5f

        /**
         * 推理线程数默认值。
         *
         * Vulkan 后端下 numThread 基本不参与计算；一旦切到 CPU 后端，线程数直接
         * 决定 landmark 推理耗时（2 线程约 24ms，4 线程可降到 12~16ms），也就是
         * 能不能稳住 60fps 的关键旋钮。上限压在 4，避免长时间占满大核后触发降频。
         */
        private val DEFAULT_NUM_THREADS: Int
            get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        
        // BlendShape indices (ARKit compatible)
        const val BS_NEUTRAL = 0
        const val BS_BROW_DOWN_LEFT = 1
        const val BS_BROW_DOWN_RIGHT = 2
        const val BS_BROW_INNER_UP = 3
        const val BS_BROW_OUTER_UP_LEFT = 4
        const val BS_BROW_OUTER_UP_RIGHT = 5
        const val BS_CHEEK_PUFF = 6
        const val BS_CHEEK_SQUINT_LEFT = 7
        const val BS_CHEEK_SQUINT_RIGHT = 8
        const val BS_EYE_BLINK_LEFT = 9
        const val BS_EYE_BLINK_RIGHT = 10
        const val BS_EYE_LOOK_DOWN_LEFT = 11
        const val BS_EYE_LOOK_DOWN_RIGHT = 12
        const val BS_EYE_LOOK_IN_LEFT = 13
        const val BS_EYE_LOOK_IN_RIGHT = 14
        const val BS_EYE_LOOK_OUT_LEFT = 15
        const val BS_EYE_LOOK_OUT_RIGHT = 16
        const val BS_EYE_LOOK_UP_LEFT = 17
        const val BS_EYE_LOOK_UP_RIGHT = 18
        const val BS_EYE_SQUINT_LEFT = 19
        const val BS_EYE_SQUINT_RIGHT = 20
        const val BS_EYE_WIDE_LEFT = 21
        const val BS_EYE_WIDE_RIGHT = 22
        const val BS_JAW_FORWARD = 23
        const val BS_JAW_LEFT = 24
        const val BS_JAW_OPEN = 25
        const val BS_JAW_RIGHT = 26
        const val BS_MOUTH_CLOSE = 27
        const val BS_MOUTH_DIMPLE_LEFT = 28
        const val BS_MOUTH_DIMPLE_RIGHT = 29
        const val BS_MOUTH_FROWN_LEFT = 30
        const val BS_MOUTH_FROWN_RIGHT = 31
        const val BS_MOUTH_FUNNEL = 32
        const val BS_MOUTH_LEFT = 33
        const val BS_MOUTH_LOWER_DOWN_LEFT = 34
        const val BS_MOUTH_LOWER_DOWN_RIGHT = 35
        const val BS_MOUTH_PRESS_LEFT = 36
        const val BS_MOUTH_PRESS_RIGHT = 37
        const val BS_MOUTH_PUCKER = 38
        const val BS_MOUTH_RIGHT = 39
        const val BS_MOUTH_ROLL_LOWER = 40
        const val BS_MOUTH_ROLL_UPPER = 41
        const val BS_MOUTH_SHRUG_LOWER = 42
        const val BS_MOUTH_SHRUG_UPPER = 43
        const val BS_MOUTH_SMILE_LEFT = 44
        const val BS_MOUTH_SMILE_RIGHT = 45
        const val BS_MOUTH_STRETCH_LEFT = 46
        const val BS_MOUTH_STRETCH_RIGHT = 47
        const val BS_MOUTH_UPPER_UP_LEFT = 48
        const val BS_MOUTH_UPPER_UP_RIGHT = 49
        const val BS_NOSE_SNEER_LEFT = 50
        const val BS_NOSE_SNEER_RIGHT = 51
        const val BS_COUNT = 52
        
        init {
            System.loadLibrary("mediapipefacelandmark")
        }
    }
    
    // Native methods
    private external fun nativeInit(
        assetManager: android.content.res.AssetManager,
        detectorModelPath: String,
        landmarkerModelPath: String,
        blendshapeModelPath: String?,
        numThreads: Int
    ): Boolean

    /**
     * 用初始化时缓存的模型路径重建三个 MNN session，切换到指定后端。
     * @return 切换成功与否；失败时原生侧会尝试回滚到旧后端。
     */
    private external fun nativeSetBackend(useVulkan: Boolean): Boolean

    /** @return true 表示当前跑在 Vulkan 后端。 */
    private external fun nativeGetBackend(): Boolean
    
    private external fun nativeDetectLandmarks(
        imageData: ByteArray,
        width: Int,
        height: Int,
        scoreThreshold: Float,
        iouThreshold: Float,
        presenceThreshold: Float
    ): FloatArray?
    
    // Separate detection stage - returns bounding box [x1, y1, x2, y2, score]
    private external fun nativeDetectFace(
        imageData: ByteArray,
        width: Int,
        height: Int,
        scoreThreshold: Float,
        iouThreshold: Float
    ): FloatArray?
    
    // Separate landmark stage - uses provided bounding box
    private external fun nativeDetectLandmarksWithBox(
        imageData: ByteArray,
        width: Int,
        height: Int,
        boxX1: Float,
        boxY1: Float,
        boxX2: Float,
        boxY2: Float,
        presenceThreshold: Float
    ): FloatArray?
    
    private external fun nativeEstimateHeadPose(
        landmarks: FloatArray,
        imageWidth: Int,
        imageHeight: Int
    ): FloatArray?
    
    private external fun nativePredictBlendshapes(
        landmarks: FloatArray,
        imageWidth: Int,
        imageHeight: Int,
        useHeadPoseCompensation: Boolean
    ): FloatArray?
    
    private external fun nativePredictBlendshapesRaw(
        landmarks: FloatArray,
        imageWidth: Int,
        imageHeight: Int
    ): FloatArray?
    
    private external fun nativeGetLastHeadPose(): FloatArray?
    
    private external fun nativeGetBlendshapeName(index: Int): String
    
    private external fun nativeIsBlendshapeAvailable(): Boolean
    
    private external fun nativeRelease()
    
    // ========== GPU 多路并行（多引擎）原生接口 ==========
    // 引擎按线程绑定：每个推理线程调用一次 nativeSelectEngine 选定槽位，
    // 之后该线程上的所有 per-frame 调用都会落到选定槽位的 MNN session 上。
    
    /** 把当前线程绑定到引擎槽位（0 = 主引擎，1..MAX_EXTRA_ENGINES = 并行关键点引擎）。 */
    private external fun nativeSelectEngine(index: Int)
    
    /** 当前线程绑定的引擎槽位（调试用）。 */
    private external fun nativeGetEngineIndex(): Int
    
    /**
     * 初始化一个额外的关键点引擎（只加载 landmarker）。须在 [initialize] 之后调用。
     * @param slot 槽位，1..[MAX_EXTRA_ENGINES]
     * @param useVulkan true = 再开一路 Vulkan（默认，走 GPU 并行）；false = CPU 后端
     * @param numThreads MNN 线程数，<=0 沿用主引擎线程数
     */
    private external fun nativeInitEngine(slot: Int, useVulkan: Boolean, numThreads: Int): Boolean
    
    /** 指定槽位的引擎是否就绪（slot 0 = 主引擎）。 */
    private external fun nativeIsEngineReady(slot: Int): Boolean
    
    /** 释放指定槽位的额外引擎（slot 0 不可释放）。 */
    private external fun nativeReleaseEngine(slot: Int)
    
    /** 释放全部额外引擎占用的模型内存。 */
    private external fun nativeReleaseExtraEngines()
    
    private external fun nativeSetDumpDirectory(dumpDir: String?)
    
    // Smoothing control native methods
    private external fun nativeSetSmoothingEnabled(enabled: Boolean)
    private external fun nativeResetSmoothing()
    
    // Camera FOV control native methods
    private external fun nativeSetCameraFov(fovDegrees: Float)
    private external fun nativeGetCameraFov(): Float

    // Filter configuration native methods
    private external fun nativeSetLandmarkFilterType(filterType: Int)
    private external fun nativeGetLandmarkFilterType(): Int
    private external fun nativeConfigureLandmarkFilter(
        filterType: Int,
        oneEuroMinCutoff: Float,
        oneEuroBeta: Float,
        oneEuroDerivCutoff: Float,
        timeEmaTau: Float,
        gaussianWindowSize: Int,
        gaussianSigma: Float,
        emaAlpha: Float
    )
    private external fun nativeSetDetectionFilterType(filterType: Int)
    private external fun nativeGetDetectionFilterType(): Int
    private external fun nativeConfigureDetectionFilter(
        filterType: Int,
        oneEuroMinCutoff: Float,
        oneEuroBeta: Float,
        oneEuroDerivCutoff: Float,
        timeEmaTau: Float,
        gaussianWindowSize: Int,
        gaussianSigma: Float,
        emaAlpha: Float
    )
    private external fun nativeSetHeadPoseFilterType(filterType: Int)
    private external fun nativeSetHeadPoseSmoothingEnabled(enabled: Boolean)

    // ========== YUV Direct Input Native Methods ==========
    // These allow direct YUV processing without intermediate RGB conversion
    
    private external fun nativeDetectFaceFromYuv(
        yBuffer: java.nio.ByteBuffer,
        uBuffer: java.nio.ByteBuffer,
        vBuffer: java.nio.ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int,
        rotation: Int,
        scoreThreshold: Float,
        iouThreshold: Float
    ): FloatArray?
    
    private external fun nativeDetectLandmarksFromYuv(
        yBuffer: java.nio.ByteBuffer,
        uBuffer: java.nio.ByteBuffer,
        vBuffer: java.nio.ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int,
        rotation: Int,
        boxX1: Float,
        boxY1: Float,
        boxX2: Float,
        boxY2: Float,
        presenceThreshold: Float
    ): FloatArray?
    
    private external fun nativeDetectAllFromYuv(
        yBuffer: java.nio.ByteBuffer,
        uBuffer: java.nio.ByteBuffer,
        vBuffer: java.nio.ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int,
        rotation: Int,
        scoreThreshold: Float,
        iouThreshold: Float,
        presenceThreshold: Float
    ): FloatArray?

    /**
     * Set camera FOV for accurate head pose estimation
     * Call this with real camera FOV before head pose estimation for best results
     * @param fovDegrees Vertical field of view in degrees (typical: 50-70 for mobile cameras)
     */
    fun setCameraFov(fovDegrees: Float) {
        nativeSetCameraFov(fovDegrees)
        Log.i(TAG, "Camera FOV set to $fovDegrees degrees")
    }

    /**
     * Get current camera FOV
     * @return Current vertical FOV in degrees
     */
    fun getCameraFov(): Float {
        return nativeGetCameraFov()
    }

    /**
     * Enable/disable landmark smoothing (One Euro Filter)
     * MediaPipe uses this by default for temporal smoothing
     */
    fun setSmoothingEnabled(enabled: Boolean) {
        if (isInitialized) {
            nativeSetSmoothingEnabled(enabled)
            Log.i(TAG, "Landmark smoothing ${if (enabled) "enabled" else "disabled"}")
        }
    }
    
    /**
     * Reset smoothing filter state
     * Call this when face tracking is lost or needs to be reset
     */
    fun resetSmoothing() {
        if (isInitialized) {
            nativeResetSmoothing()
            Log.i(TAG, "Smoothing filter reset")
        }
    }
    
    // ============================================================================
    // Filter Configuration Methods
    // ============================================================================

    /**
     * Set landmark smoothing filter type
     * Use TIME_BASED_EMA for best results at high frame rates
     *
     * @param type Filter type (ONE_EURO, TIME_BASED_EMA, GAUSSIAN, EMA, NONE)
     */
    fun setLandmarkFilterType(type: SmoothingFilterType) {
        if (isInitialized) {
            nativeSetLandmarkFilterType(type.value)
            Log.i(TAG, "Landmark filter type set to $type")
        }
    }

    /**
     * Get current landmark filter type
     */
    fun getLandmarkFilterType(): SmoothingFilterType {
        return if (isInitialized) {
            SmoothingFilterType.entries.find { it.value == nativeGetLandmarkFilterType() }
                ?: SmoothingFilterType.NONE
        } else {
            SmoothingFilterType.NONE
        }
    }

    /**
     * Configure landmark filter with detailed parameters
     *
     * @param config Filter configuration
     */
    fun configureLandmarkFilter(config: FilterConfig) {
        if (isInitialized) {
            nativeConfigureLandmarkFilter(
                config.type.value,
                config.oneEuroMinCutoff,
                config.oneEuroBeta,
                config.oneEuroDerivCutoff,
                config.timeEmaTau,
                config.gaussianWindowSize,
                config.gaussianSigma,
                config.emaAlpha
            )
            Log.i(TAG, "Landmark filter configured: type=${config.type}, tau=${config.timeEmaTau}")
        }
    }

    /**
     * Set detection box smoothing filter type
     *
     * @param type Filter type (ONE_EURO, TIME_BASED_EMA, GAUSSIAN, EMA, NONE)
     */
    fun setDetectionFilterType(type: SmoothingFilterType) {
        if (isInitialized) {
            nativeSetDetectionFilterType(type.value)
            Log.i(TAG, "Detection filter type set to $type")
        }
    }

    /**
     * Get current detection filter type
     */
    fun getDetectionFilterType(): SmoothingFilterType {
        return if (isInitialized) {
            SmoothingFilterType.entries.find { it.value == nativeGetDetectionFilterType() }
                ?: SmoothingFilterType.NONE
        } else {
            SmoothingFilterType.NONE
        }
    }

    /**
     * Configure detection filter with detailed parameters
     *
     * @param config Filter configuration
     */
    fun configureDetectionFilter(config: FilterConfig) {
        if (isInitialized) {
            nativeConfigureDetectionFilter(
                config.type.value,
                config.oneEuroMinCutoff,
                config.oneEuroBeta,
                config.oneEuroDerivCutoff,
                config.timeEmaTau,
                config.gaussianWindowSize,
                config.gaussianSigma,
                config.emaAlpha
            )
            Log.i(TAG, "Detection filter configured: type=${config.type}, tau=${config.timeEmaTau}")
        }
    }

    /**
     * Set head pose smoothing filter type
     * Note: Head pose filter uses a different enum (0=ONE_EURO, 1=GAUSSIAN, 2=EMA, 3=TIME_BASED_EMA)
     *
     * @param type 0=ONE_EURO, 1=GAUSSIAN, 2=EMA, 3=TIME_BASED_EMA (recommended)
     */
    fun setHeadPoseFilterType(type: Int) {
        if (isInitialized) {
            nativeSetHeadPoseFilterType(type)
            Log.i(TAG, "Head pose filter type set to $type")
        }
    }

    /**
     * 开关 head pose 时域平滑（rawTrackV1 主口径旁路，docs/raw-track-protocol.md §4.3 /
     * docs/post-pipeline-migration.md 附录 K.4）。
     *
     * false = estimateHeadPose 直接返回 Procrustes 原始欧拉角（真·未滤波）；true = 恢复
     * 原 TIME_BASED_EMA。这是本次迁移唯一的 C++ 绑定新增（nativeSetHeadPoseSmoothingEnabled
     * 是既有 FaceLandmarker::setHeadPoseSmoothingEnabled 的纯胶水）。
     *
     * 模式切换时调用方应连带 [resetSmoothing]（清掉关键点/检测/头姿三路滤波历史），
     * 避免上一模式的滤波状态跨模式串味。
     */
    fun setHeadPoseSmoothingEnabled(enabled: Boolean) {
        if (isInitialized) {
            nativeSetHeadPoseSmoothingEnabled(enabled)
            Log.i(TAG, "Head pose smoothing " + if (enabled) "enabled" else "disabled")
        }
    }

    /**
     * Configure all filters with Time-Based EMA for optimal high-FPS performance
     *
     * @param landmarkTau Landmark smoothing time constant (default: 0.08 = 80ms)
     * @param detectionTau Detection smoothing time constant (default: 0.06 = 60ms)
     * @param headPoseTau Head pose smoothing uses filter type 3 (TIME_BASED_EMA)
     */
    fun configureForHighFps(
        landmarkTau: Float = 0.08f,
        detectionTau: Float = 0.06f
    ) {
        if (isInitialized) {
            // Configure landmark filter
            configureLandmarkFilter(FilterConfig(
                type = SmoothingFilterType.TIME_BASED_EMA,
                timeEmaTau = landmarkTau
            ))

            // Configure detection filter
            configureDetectionFilter(FilterConfig(
                type = SmoothingFilterType.TIME_BASED_EMA,
                timeEmaTau = detectionTau
            ))

            // Configure head pose filter (type 3 = TIME_BASED_EMA)
            setHeadPoseFilterType(3)

            Log.i(TAG, "Configured for high FPS: landmark_tau=$landmarkTau, detection_tau=$detectionTau")
        }
    }

    /**
     * Set directory for dumping first input frame for debugging
     * The first frame passed to detector will be saved as a PPM image file
     * @param dumpDir Directory path where to save the debug frame, or null to disable
     */
    fun setDumpDirectory(dumpDir: String?) {
        Log.i(TAG, "Setting dump directory to: $dumpDir")
        nativeSetDumpDirectory(dumpDir)
    }

    // ========== MNN Backend selection ==========

    /** 当前生效的推理后端（默认与历史行为一致：Vulkan）。 */
    @Volatile
    var currentBackend: MnnBackend = MnnBackend.VULKAN
        private set

    /**
     * 运行时切换 MNN 推理后端。
     *
     * 内部会用初始化时缓存的模型路径重建三个 session，因此调用前必须保证
     * **没有其它线程正在做推理**（[com.facecapture.sdk.PipelineFaceTracker]
     * 会先 stop 再切、再 start）。
     *
     * @return true 表示切换成功；失败时原生侧会尝试回滚到原后端，此时返回值
     *         为 false 且 [currentBackend] 保持不变。
     */
    fun setBackend(backend: MnnBackend): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "setBackend($backend) ignored: detector not initialized")
            return false
        }
        if (backend == currentBackend) return true

        return try {
            val ok = nativeSetBackend(backend == MnnBackend.VULKAN)
            if (ok) {
                currentBackend = backend
                Log.i(TAG, "MNN backend switched to $backend")
            } else {
                Log.e(TAG, "MNN backend switch to $backend failed, kept $currentBackend")
            }
            ok
        } catch (t: Throwable) {
            // 老版本 .so 里没有 nativeSetBackend 时会抛 UnsatisfiedLinkError
            Log.e(TAG, "MNN backend switch failed: ${t.message}")
            false
        }
    }

    /** 当前生效的推理后端。 */
    fun getBackend(): MnnBackend = currentBackend
    
    /**
     * Initialize the face detector with models from assets
     * Must be called before detection
     * @param numThreads Number of threads for inference
     * @param enableBlendshape Whether to load blendshape model for expression tracking
     */
    fun initialize(numThreads: Int = DEFAULT_NUM_THREADS, enableBlendshape: Boolean = true): Boolean {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return true
        }
        
        Log.i(TAG, "Initializing MediaPipe face detector with blendshape=$enableBlendshape...")
        
        try {
            // Copy models from assets to cache directory for native access
            val detectorPath = copyAssetToCache(DETECTOR_MODEL)
            val landmarkerPath = copyAssetToCache(LANDMARKER_MODEL)
            val blendshapePath = if (enableBlendshape) {
                try {
                    copyAssetToCache(BLENDSHAPE_MODEL)
                } catch (e: Exception) {
                    Log.w(TAG, "BlendShape model not found in assets, blendshape disabled")
                    null
                }
            } else null
            
            // Initialize native detector
            isInitialized = nativeInit(
                context.assets,
                detectorPath,
                landmarkerPath,
                blendshapePath,
                numThreads
            )
            
            if (isInitialized) {
                Log.i(TAG, "MediaPipe face detector initialized successfully")
                // 与原生侧实际生效的后端对齐（默认 Vulkan）
                currentBackend = try {
                    MnnBackend.fromNative(nativeGetBackend())
                } catch (t: Throwable) {
                    MnnBackend.VULKAN
                }
                Log.i(TAG, "MNN backend in use: $currentBackend")
                if (nativeIsBlendshapeAvailable()) {
                    Log.i(TAG, "BlendShape prediction enabled")
                }
            } else {
                Log.e(TAG, "Failed to initialize native detector")
            }
            
            return isInitialized
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing detector", e)
            return false
        }
    }
    
    /**
     * 初始化一个额外的关键点引擎（GPU 多路并行）。
     *
     * 额外引擎只加载 landmarker：它唯一的职责是从帧队列里抢下关键点推理，
     * 让 GPU 一直有活儿干、把利用率顶满。检测 / blendshape 仍由主引擎负责，
     * 因此每个额外引擎的常驻内存只有一份 landmark 模型。
     *
     * 必须在 [initialize] 成功后调用；调用是阻塞的（加载模型约几百毫秒），
     * 通常放在 SDK 的初始化流程里，不要放在每帧路径上。
     *
     * @param slot 引擎槽位，1..[MAX_EXTRA_ENGINES]
     * @param useVulkan 期望后端。默认 true（再开一路 Vulkan，GPU 并行）；
     *        传 false 则跑 CPU 后端。注意**不会**在 Vulkan 失败时自动退回 CPU：
     *        这一路的目的就是吃 GPU，退回 CPU 只会和重排 / 后处理抢核。
     * @param numThreads MNN 线程数，<=0 表示沿用 [initialize] 时的线程数
     * @return 该槽位是否可用；失败时主引擎照常工作，管线少一路并行
     */
    fun initializeExtraEngine(
        slot: Int,
        useVulkan: Boolean = true,
        numThreads: Int = 0,
    ): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "Cannot init extra engine $slot: detector not initialized")
            return false
        }
        if (slot < 1 || slot > MAX_EXTRA_ENGINES) {
            Log.e(TAG, "Cannot init extra engine: slot $slot out of range 1..$MAX_EXTRA_ENGINES")
            return false
        }
        return try {
            val ok = nativeInitEngine(slot, useVulkan, numThreads)
            Log.i(
                TAG,
                if (ok) "Extra engine $slot ready" else "Extra engine $slot unavailable",
            )
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "Error initializing extra engine $slot", t)
            false
        }
    }

    /** 指定槽位的引擎是否可用（slot 0 = 主引擎）。 */
    fun isEngineReady(slot: Int): Boolean = try {
        if (slot < 0 || slot > MAX_EXTRA_ENGINES) false else nativeIsEngineReady(slot)
    } catch (t: Throwable) {
        Log.e(TAG, "Error querying engine $slot", t)
        false
    }

    /** 释放指定槽位额外引擎的模型内存；之后该引擎上的调用会返回 null。 */
    fun releaseExtraEngine(slot: Int) {
        if (slot < 1 || slot > MAX_EXTRA_ENGINES) return
        try {
            nativeReleaseEngine(slot)
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing extra engine $slot", t)
        }
    }

    /** 释放全部额外引擎的模型内存。 */
    fun releaseExtraEngines() {
        try {
            nativeReleaseExtraEngines()
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing extra engines", t)
        }
    }

    /**
     * 把**当前线程**绑定到指定的引擎槽位。
     *
     * 底层模型是线程本地选择的：一个推理线程只绑定一次，之后同一线程上的
     * `detectLandmarksFromYuvWithScore` / `estimateHeadPose` 等调用都会落到该引擎。
     * 默认槽位 0（主引擎），所以不调用它的单线程代码行为不变。
     *
     * @return 绑定后该线程实际生效的槽位
     */
    fun bindEngine(index: Int): Int {
        nativeSelectEngine(index)
        return nativeGetEngineIndex()
    }
    
    /**
     * Detect facial landmarks from camera frame
     * @param imageData RGB image data (ByteBuffer from camera)
     * @param width Image width
     * @param height Image height
     * @return List of 478 landmarks as Point3D, or null if no face detected
     */
    fun detectLandmarks(
        imageData: ByteBuffer,
        width: Int,
        height: Int,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
        presenceThreshold: Float = DEFAULT_PRESENCE_THRESHOLD
    ): List<Point3D>? {
        if (!isInitialized) {
            Log.e(TAG, "Detector not initialized. Call initialize() first.")
            return null
        }
        
        // Convert ByteBuffer to ByteArray for JNI
        val byteArray = ByteArray(imageData.remaining())
        imageData.get(byteArray)
        imageData.rewind()
        
        // Call native detection
        val landmarksArray = nativeDetectLandmarks(
            byteArray,
            width,
            height,
            scoreThreshold,
            iouThreshold,
            presenceThreshold
        ) ?: return null
        
        // Convert float array to List<Point3D>
        val landmarks = ArrayList<Point3D>(landmarksArray.size / 3)
        for (i in landmarksArray.indices step 3) {
            landmarks.add(
                Point3D(
                    x = landmarksArray[i],
                    y = landmarksArray[i + 1],
                    z = landmarksArray[i + 2]
                )
            )
        }
        
        Log.d(TAG, "Detected ${landmarks.size} landmarks")
        return landmarks
    }
    
    /**
     * Face detection box result
     */
    data class FaceBox(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float
    )
    
    /**
     * Stage 1: Detect face bounding box only (fast, ~4ms)
     * Can run in parallel with landmark detection on previous frame
     * 
     * @param imageData RGB image data (ByteBuffer from camera)
     * @param width Image width
     * @param height Image height
     * @return FaceBox with normalized coordinates [0, 1], or null if no face
     */
    fun detectFaceOnly(
        imageData: ByteBuffer,
        width: Int,
        height: Int,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD
    ): FaceBox? {
        if (!isInitialized) {
            Log.e(TAG, "Detector not initialized")
            return null
        }
        
        val byteArray = ByteArray(imageData.remaining())
        imageData.get(byteArray)
        imageData.rewind()
        
        val result = nativeDetectFace(byteArray, width, height, scoreThreshold, iouThreshold)
            ?: return null
        
        return FaceBox(
            x1 = result[0],
            y1 = result[1],
            x2 = result[2],
            y2 = result[3],
            score = result[4]
        )
    }
    
    /**
     * Stage 2: Detect landmarks using pre-computed bounding box (slower, ~24ms)
     * Can use bounding box from previous frame for pipeline parallelism
     * 
     * @param imageData RGB image data
     * @param width Image width
     * @param height Image height
     * @param box Pre-computed face bounding box
     * @return List of 478 landmarks as Point3D, or null if detection fails
     */
    fun detectLandmarksWithBox(
        imageData: ByteBuffer,
        width: Int,
        height: Int,
        box: FaceBox,
        presenceThreshold: Float = DEFAULT_PRESENCE_THRESHOLD
    ): List<Point3D>? {
        val result = detectLandmarksWithBoxAndScore(imageData, width, height, box, presenceThreshold)
        return result?.landmarks
    }
    
    /**
     * Stage 2: Detect landmarks with presence score using pre-computed bounding box
     * Returns LandmarkResult containing both landmarks and presence_score
     * 
     * @param imageData RGB image data
     * @param width Image width
     * @param height Image height
     * @param box Pre-computed face bounding box
     * @return LandmarkResult with landmarks and presenceScore, or null if detection fails
     */
    fun detectLandmarksWithBoxAndScore(
        imageData: ByteBuffer,
        width: Int,
        height: Int,
        box: FaceBox,
        presenceThreshold: Float = DEFAULT_PRESENCE_THRESHOLD
    ): LandmarkResult? {
        if (!isInitialized) {
            Log.e(TAG, "Detector not initialized")
            return null
        }
        
        val byteArray = ByteArray(imageData.remaining())
        imageData.get(byteArray)
        imageData.rewind()
        
        val landmarksArray = nativeDetectLandmarksWithBox(
            byteArray, width, height,
            box.x1, box.y1, box.x2, box.y2,
            presenceThreshold
        ) ?: return null
        
        // Array format: [x,y,z, x,y,z, ..., presence_score]
        // The last element is presence_score
        if (landmarksArray.size < 4) return null  // At least one landmark + score
        
        val presenceScore = landmarksArray[landmarksArray.size - 1]

        // Parse landmarks (all except the last element)
        val landmarkCount = (landmarksArray.size - 1) / 3
        // 预分配容量，避免逐帧扩容
        val landmarks = ArrayList<Point3D>(landmarkCount)
        for (i in 0 until landmarkCount) {
            val idx = i * 3
            landmarks.add(Point3D(
                x = landmarksArray[idx],
                y = landmarksArray[idx + 1],
                z = landmarksArray[idx + 2]
            ))
        }
        
        return LandmarkResult(landmarks, presenceScore)
    }
    
    // ========== YUV Direct Input Methods (Zero-copy optimization) ==========
    
    /**
     * Detect face directly from YUV camera data with rotation
     * This integrates YUV->RGB conversion into the MNN preprocessing pipeline,
     * eliminating the need for a separate conversion step.
     *
     * @param yBuffer Y plane ByteBuffer (must be direct)
     * @param uBuffer U plane ByteBuffer (must be direct)
     * @param vBuffer V plane ByteBuffer (must be direct)
     * @param yRowStride Y plane row stride
     * @param uvRowStride UV plane row stride
     * @param uvPixelStride UV pixel stride (1=I420, 2=NV12/NV21)
     * @param width Source image width (before rotation)
     * @param height Source image height (before rotation)
     * @param rotation Rotation in degrees (0, 90, 180, 270)
     * @return FaceBox in rotated image coordinates, or null if no face
     */
    fun detectFaceFromYuv(
        yBuffer: java.nio.ByteBuffer,
        uBuffer: java.nio.ByteBuffer,
        vBuffer: java.nio.ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int,
        rotation: Int,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD
    ): FaceBox? {
        if (!isInitialized) {
            Log.e(TAG, "Detector not initialized")
            return null
        }
        
        val result = nativeDetectFaceFromYuv(
            yBuffer, uBuffer, vBuffer,
            yRowStride, uvRowStride, uvPixelStride,
            width, height, rotation,
            scoreThreshold, iouThreshold
        ) ?: return null
        
        return FaceBox(
            x1 = result[0],
            y1 = result[1],
            x2 = result[2],
            y2 = result[3],
            score = result[4]
        )
    }
    
    /**
     * Detect landmarks directly from YUV data with pre-computed bounding box
     *
     * @param yBuffer Y plane ByteBuffer (must be direct)
     * @param uBuffer U plane ByteBuffer (must be direct)
     * @param vBuffer V plane ByteBuffer (must be direct)
     * @param yRowStride Y plane row stride
     * @param uvRowStride UV plane row stride
     * @param uvPixelStride UV pixel stride
     * @param width Image width (after rotation)
     * @param height Image height (after rotation)
     * @param rotation Rotation in degrees
     * @param box Pre-computed face bounding box (in rotated image coords)
     * @return List of 478 landmarks, or null on failure
     */
    fun detectLandmarksFromYuv(
        yBuffer: java.nio.ByteBuffer,
        uBuffer: java.nio.ByteBuffer,
        vBuffer: java.nio.ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int,
        rotation: Int,
        box: FaceBox,
        presenceThreshold: Float = DEFAULT_PRESENCE_THRESHOLD
    ): List<Point3D>? {
        val result = detectLandmarksFromYuvWithScore(
            yBuffer, uBuffer, vBuffer,
            yRowStride, uvRowStride, uvPixelStride,
            width, height, rotation, box, presenceThreshold
        )
        return result?.landmarks
    }
    
    /**
     * Detect landmarks from YUV data with presence score
     * Returns LandmarkResult containing both landmarks and presence_score
     *
     * @return LandmarkResult with landmarks and presenceScore, or null on failure
     */
    fun detectLandmarksFromYuvWithScore(
        yBuffer: java.nio.ByteBuffer,
        uBuffer: java.nio.ByteBuffer,
        vBuffer: java.nio.ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int,
        rotation: Int,
        box: FaceBox,
        presenceThreshold: Float = DEFAULT_PRESENCE_THRESHOLD
    ): LandmarkResult? {
        if (!isInitialized) {
            Log.e(TAG, "Detector not initialized")
            return null
        }
        
        val landmarksArray = nativeDetectLandmarksFromYuv(
            yBuffer, uBuffer, vBuffer,
            yRowStride, uvRowStride, uvPixelStride,
            width, height, rotation,
            box.x1, box.y1, box.x2, box.y2,
            presenceThreshold
        ) ?: return null
        
        // Array format: [x,y,z, x,y,z, ..., presence_score]
        if (landmarksArray.size < 4) return null
        
        val presenceScore = landmarksArray[landmarksArray.size - 1]

        // 预分配容量：478 个点，避免 ArrayList 反复扩容（逐帧 60 次）
        val landmarkCount = (landmarksArray.size - 1) / 3
        val landmarks = ArrayList<Point3D>(landmarkCount)
        
        for (i in 0 until landmarkCount) {
            val idx = i * 3
            landmarks.add(Point3D(
                x = landmarksArray[idx],
                y = landmarksArray[idx + 1],
                z = landmarksArray[idx + 2]
            ))
        }
        
        return LandmarkResult(landmarks, presenceScore)
    }
    
    /**
     * Full pipeline: Detect face and landmarks from YUV in one call
     * This is the most efficient method for processing camera frames.
     *
     * @param yBuffer Y plane ByteBuffer (must be direct)
     * @param uBuffer U plane ByteBuffer (must be direct)
     * @param vBuffer V plane ByteBuffer (must be direct)
     * @param yRowStride Y plane row stride
     * @param uvRowStride UV plane row stride
     * @param uvPixelStride UV pixel stride
     * @param width Source image width (before rotation)
     * @param height Source image height (before rotation)
     * @param rotation Rotation in degrees
     * @return List of 478 landmarks, or null if no face
     */
    fun detectAllFromYuv(
        yBuffer: java.nio.ByteBuffer,
        uBuffer: java.nio.ByteBuffer,
        vBuffer: java.nio.ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int,
        rotation: Int,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
        presenceThreshold: Float = DEFAULT_PRESENCE_THRESHOLD
    ): List<Point3D>? {
        if (!isInitialized) {
            Log.e(TAG, "Detector not initialized")
            return null
        }
        
        val landmarksArray = nativeDetectAllFromYuv(
            yBuffer, uBuffer, vBuffer,
            yRowStride, uvRowStride, uvPixelStride,
            width, height, rotation,
            scoreThreshold, iouThreshold, presenceThreshold
        ) ?: return null
        
        val landmarks = ArrayList<Point3D>(landmarksArray.size / 3)
        for (i in landmarksArray.indices step 3) {
            landmarks.add(Point3D(
                x = landmarksArray[i],
                y = landmarksArray[i + 1],
                z = landmarksArray[i + 2]
            ))
        }
        
        return landmarks
    }
    
    /**
     * Estimate head pose (pitch, yaw, roll) from landmarks
     * @param landmarks List of facial landmarks (478 points)
     * @param imageWidth Image width in pixels
     * @param imageHeight Image height in pixels
     * @return Triple of (pitch, yaw, roll) in degrees, or null on error
     */
    fun estimateHeadPose(
        landmarks: List<Point3D>,
        imageWidth: Int,
        imageHeight: Int
    ): Triple<Float, Float, Float>? {
        if (!isInitialized) {
            Log.e(TAG, "Detector not initialized")
            return null
        }
        
        // Convert landmarks to float array
        val landmarksArray = FloatArray(landmarks.size * 3)
        landmarks.forEachIndexed { index, point ->
            landmarksArray[index * 3] = point.x
            landmarksArray[index * 3 + 1] = point.y
            landmarksArray[index * 3 + 2] = point.z
        }
        
        val pose = nativeEstimateHeadPose(landmarksArray, imageWidth, imageHeight)
            ?: return null
        
        return Triple(pose[0], pose[1], pose[2]) // pitch, yaw, roll
    }
    

    /**
     * Predict blendshapes from facial landmarks
     * Uses MediaPipe's BlendShape model for ARKit-compatible expression coefficients
     * 
     * @param landmarks List of 478 facial landmarks
     * @param imageWidth Image width in pixels
     * @param imageHeight Image height in pixels
     * @param useHeadPoseCompensation Apply head rotation compensation to reduce artifacts
     * @return BlendShapeResult with 52 coefficients, or null on error
     */
    fun predictBlendshapes(
        landmarks: List<Point3D>,
        imageWidth: Int,
        imageHeight: Int,
        useHeadPoseCompensation: Boolean = true
    ): BlendShapeResult? {
        if (!isInitialized || !nativeIsBlendshapeAvailable()) {
            Log.e(TAG, "BlendShape prediction not available")
            return null
        }
        
        // Convert landmarks to float array
        val landmarksArray = FloatArray(landmarks.size * 3)
        landmarks.forEachIndexed { index, point ->
            landmarksArray[index * 3] = point.x
            landmarksArray[index * 3 + 1] = point.y
            landmarksArray[index * 3 + 2] = point.z
        }
        
        val blendshapes = nativePredictBlendshapes(
            landmarksArray, 
            imageWidth, 
            imageHeight,
            useHeadPoseCompensation
        ) ?: return null
        
        return BlendShapeResult(
            values = blendshapes,
            eyeBlinkLeft = blendshapes.getOrElse(BS_EYE_BLINK_LEFT) { 0f },
            eyeBlinkRight = blendshapes.getOrElse(BS_EYE_BLINK_RIGHT) { 0f },
            jawOpen = blendshapes.getOrElse(BS_JAW_OPEN) { 0f },
            mouthSmileLeft = blendshapes.getOrElse(BS_MOUTH_SMILE_LEFT) { 0f },
            mouthSmileRight = blendshapes.getOrElse(BS_MOUTH_SMILE_RIGHT) { 0f },
            browInnerUp = blendshapes.getOrElse(BS_BROW_INNER_UP) { 0f },
            browDownLeft = blendshapes.getOrElse(BS_BROW_DOWN_LEFT) { 0f },
            browDownRight = blendshapes.getOrElse(BS_BROW_DOWN_RIGHT) { 0f }
        )
    }
    
    /**
     * Predict blendshapes without head pose compensation (raw model output)
     */
    fun predictBlendshapesRaw(
        landmarks: List<Point3D>,
        imageWidth: Int,
        imageHeight: Int
    ): FloatArray? {
        if (!isInitialized || !nativeIsBlendshapeAvailable()) {
            return null
        }
        
        val landmarksArray = FloatArray(landmarks.size * 3)
        landmarks.forEachIndexed { index, point ->
            landmarksArray[index * 3] = point.x
            landmarksArray[index * 3 + 1] = point.y
            landmarksArray[index * 3 + 2] = point.z
        }
        
        return nativePredictBlendshapesRaw(landmarksArray, imageWidth, imageHeight)
    }
    
    /**
     * Get the last estimated head pose from blendshape prediction
     * @return HeadPose with pitch, yaw, roll and translation, or null
     */
    fun getLastHeadPose(): HeadPoseResult? {
        val pose = nativeGetLastHeadPose() ?: return null
        return HeadPoseResult(
            pitch = pose[0],
            yaw = pose[1],
            roll = pose[2],
            translationX = pose.getOrElse(3) { 0f },
            translationY = pose.getOrElse(4) { 0f },
            translationZ = pose.getOrElse(5) { 0f }
        )
    }
    
    data class HeadPoseResult(
        val pitch: Float,
        val yaw: Float,
        val roll: Float,
        val translationX: Float,
        val translationY: Float,
        val translationZ: Float
    )
    
    /**
     * Get BlendShape name by index
     */
    fun getBlendshapeName(index: Int): String = nativeGetBlendshapeName(index)
    
    /**
     * Check if BlendShape prediction is available
     */
    fun isBlendshapeAvailable(): Boolean = isInitialized && nativeIsBlendshapeAvailable()
    
    /**
     * Release native resources
     * Should be called when detector is no longer needed
     */
    fun release() {
        if (isInitialized) {
            // 额外引擎先放：它们只持有 landmarker，但多份模型同时在内存里时
            // 释放顺序反过来会让峰值内存多撑一会儿。
            releaseExtraEngines()
            nativeRelease()
            isInitialized = false
            Log.i(TAG, "MediaPipe face detector released")
        }
    }
    
    /**
     * Copy asset file to cache directory for native access
     */
    private fun copyAssetToCache(assetName: String): String {
        val cacheFile = File(context.cacheDir, assetName)
        
        // Skip if already exists
        if (cacheFile.exists()) {
            Log.d(TAG, "Model already cached: $assetName")
            return cacheFile.absolutePath
        }
        
        Log.i(TAG, "Copying asset to cache: $assetName")
        
        context.assets.open(assetName).use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        
        return cacheFile.absolutePath
    }
    
    /**
     * Check if detector is initialized
     */
    fun isReady(): Boolean = isInitialized
}
