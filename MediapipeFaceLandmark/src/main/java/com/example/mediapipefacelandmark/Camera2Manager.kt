package com.example.mediapipefacelandmark

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.vfc.log.VfcLog as Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.example.commondata.CameraDeviceInfo
import com.example.commondata.CameraLensFacing
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * YUV frame data for direct processing without conversion
 * All ByteBuffers are direct buffers pointing to camera frame data
 */
data class YuvFrameData(
    val yBuffer: ByteBuffer,
    val uBuffer: ByteBuffer,
    val vBuffer: ByteBuffer,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    val width: Int,           // Source width (before rotation)
    val height: Int,          // Source height (before rotation)
    val rotation: Int,        // Rotation degrees (0, 90, 180, 270)
    val rotatedWidth: Int,    // Width after rotation
    val rotatedHeight: Int    // Height after rotation
)

/**
 * Camera2 Manager for capturing camera frames.
 *
 * Supports **any** camera exposed by [CameraManager] — front, back, logical
 * multi-cameras and external (USB / capture-card) cameras, plus the physical
 * sensors (rear ultra-wide / telephoto) hidden behind a logical multi-camera
 * on API 28+. The caller can list the available devices via
 * [getAvailableCameras], pick one with [setPreferredCamera], and the manager
 * recalculates the sensor orientation, field of view and rotation formula for
 * that specific device.
 *
 * Frames are rotated upright from the current `Display.getRotation()` on every
 * frame, so the same session keeps working while the device is rotated between
 * portrait and landscape (the app declares `configChanges`, so rotation does
 * not recreate the activity or restart the camera).
 *
 * Provides RGB frame data for MediaPipe face detection and direct YUV output
 * for zero-copy processing.
 */
class Camera2Manager(private val context: Context) {
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Characteristics of the camera currently being opened. Needed when
    // building the CaptureRequest (AF / FPS capabilities differ per device).
    private var cameraCharacteristics: CameraCharacteristics? = null

    // LENS_FACING_* of the selected camera (front / back / external).
    @Volatile private var lensFacing: Int = CameraCharacteristics.LENS_FACING_FRONT

    // Logical camera id the caller asked for ("null" = automatic front-first choice).
    @Volatile private var preferredCameraId: String? = null

    /**
     * Physical sensor id of the preferred camera, or null for a regular logical
     * camera. Hidden rear sensors (ultra-wide / telephoto) are streams of the
     * logical camera, not openable devices — see [createCaptureSession].
     */
    @Volatile private var preferredPhysicalCameraId: String? = null

    /** Surface handed to the session callback; set right before a session is created. */
    @Volatile private var captureSurface: Surface? = null

    /** Guards the one-time fallback from a rejected physical stream to the logical camera. */
    @Volatile private var physicalFallbackTried: Boolean = false

    /**
     * Executor used by the API 28+ [SessionConfiguration] overload.
     *
     * Session callbacks must be delivered on the camera thread (same contract
     * as the handler-based overload), and [backgroundHandler] already lives
     * there, so simply post to it.
     */
    private val sessionExecutor: Executor = Executor { command ->
        backgroundHandler?.post(command) ?: command.run()
    }

    /**
     * Metadata of the camera selected by the most recent [startCamera] call.
     * Set synchronously before the asynchronous `openCamera` callback runs, so
     * callers can update their UI right after start / switch.
     */
    @Volatile var selectedCameraInfo: CameraDeviceInfo? = null
        private set

    /** Id of the currently selected camera, or null before the first start. */
    val selectedCameraId: String? get() = selectedCameraInfo?.id
    
    // Legacy RGB callback (with YUV->RGB conversion)
    private var frameCallback: ((ByteBuffer, Int, Int) -> Unit)? = null
    
    // New YUV direct callback (zero-copy, for JNI direct processing)
    private var yuvFrameCallback: ((YuvFrameData) -> Unit)? = null
    
    // Processing mode
    var useDirectYuvMode: Boolean = false

    /**
     * Enable RGB preview callback even in YUV mode (for debug visualization).
     *
     * 每帧生成一帧 RGB 预览意味着相机线程上要多做一次整帧 YUV→RGB（720p 级别
     * 约 2.7MB/帧、上百万次像素运算）。调试预览默认关闭时这项开销纯属浪费，直接
     * 拖慢相机线程（进而压缩实际采集帧率），所以改由上层按需开关，并且 volatile
     * 保证相机线程能立刻看到变化。
     */
    @Volatile
    var enablePreviewInYuvMode: Boolean = false
    
    /**
     * 当前是否真的会产出预览帧。
     *
     * 上层（PipelineFaceTracker）用它决定要不要在相机线程上做 RGB 转换 ——
     * 只有「开关打开」且「确有订阅者」时才转换。
     */
    val willProducePreviewFrames: Boolean
        get() = enablePreviewInYuvMode && frameCallback != null
    
    // Camera sensor orientation (degrees) - will be set from camera characteristics
    private var sensorOrientation: Int = 0
    
    // Camera vertical FOV in degrees - calculated from camera characteristics
    var cameraVerticalFovDegrees: Float = 63.0f
        private set

    /**
     * Cached WindowManager.
     *
     * [getRotationDegrees] runs on the camera callback thread for **every**
     * frame; doing a `getSystemService()` lookup there every time is pure
     * overhead, so resolve it once.
     */
    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    companion object {
        private const val TAG = "Camera2Manager"

        /**
         * 采集目标：1280x720（720p）@ 60 fps。
         *
         * 720p 是「清晰度」和「喂得动 60fps」之间的平衡点：预览 / 关键点 ROI 的
         * 源分辨率从 480 级升到 720p（远景、小脸更稳），像素量约 92 万，主流
         * sensor 在 720p 下仍能出 60fps。
         */
        private const val TARGET_WIDTH = 1280
        private const val TARGET_HEIGHT = 720

        /** 面捕管线的目标帧率（用于挑 AE 区间、钉住 SENSOR_FRAME_DURATION）。 */
        private const val TARGET_FPS = 60

        /** 判断「这个尺寸能不能跑满目标帧率」时的容差（实测 59.94fps 也算 60fps）。 */
        private const val FPS_TOLERANCE = 1.0f

        // Target resolution constraints
        private const val MIN_SIZE = 192       // Minimum dimension to ensure quality

        /**
         * 整帧像素硬上限（720p）。
         *
         * 720p 是采集目标，同时把 1080p 这类「更大但更难喂满 60fps」的尺寸挡在
         * 外面：大尺寸 YUV 的打包 / 旋转 / 缩放开销翻倍，对 128~256 输入的人脸
         * 检测没有收益。
         */
        private const val MAX_PIXEL_COUNT = TARGET_WIDTH * TARGET_HEIGHT

        /** 目标宽高比（16:9），没有 720p 时用于同族尺寸的次级排序。 */
        private val TARGET_ASPECT = TARGET_WIDTH.toFloat() / TARGET_HEIGHT

        /**
         * 预览 RGB 帧的最小生成间隔（纳秒）。
         *
         * 预览只用于调试观察，没必要跟满 60fps：60fps 采集下每 2 帧生成 1 帧
         * （33ms）就是 30fps 预览。相机线程上的整帧 YUV→RGB（720p 约 2.7MB）
         * + 像素打包 + Bitmap 上传因此直接减半 —— 这几乎是把「相机线程处理太慢
         * 导致 ImageReader 丢帧、交付帧率掉到 30」这个坑一次性填掉。
         */
        private const val PREVIEW_INTERVAL_NS = 33_000_000L

        /**
         * 判定「相机没在跑 60fps」的交付帧率下限。
         *
         * 58fps 这类读数属于正常抖动，不触发降档；低于 50fps 才认为相机侧确实
         * 喂不满（或回调被拖慢到丢帧）。
         */
        private const val MIN_CAPTURE_FPS = 50f

        /** 启动后最多自动降档几次（每次重开相机，代价约 1 秒）。 */
        private const val MAX_SIZE_DOWNSHIFTS = 2

        /** 相机回调耗时统计窗口（帧）。60fps 下 120 帧 ≈ 2 秒打一条日志。 */
        private const val CB_STATS_WINDOW_FRAMES = 120
    }
    
    /**
     * Check if camera permission is granted
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * List every camera reported by the platform, plus the physical sensors
     * hidden behind logical multi-cameras.
     *
     * The list covers front / back cameras, logical multi-cameras and external
     * cameras such as USB webcams or capture cards. On API 28+ the rear
     * ultra-wide / telephoto lenses are often only available as *physical*
     * cameras of the logical rear camera; those are added as extra entries
     * (with [CameraDeviceInfo.physicalCameraId] set) so they appear in the
     * picker and can be streamed via [createCaptureSession].
     *
     * Entries are sorted front → back → external, with a logical camera before
     * its physical children and the widest lens first within a facing group.
     * Device capabilities are read per camera here; cameras that cannot be
     * queried are skipped instead of failing the whole enumeration.
     */
    fun getAvailableCameras(): List<CameraDeviceInfo> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()

        val ids = try {
            manager.cameraIdList
        } catch (e: Exception) {
            Log.e(TAG, "Unable to enumerate cameras: ${e.message}")
            return emptyList()
        }

        // 1) Logical cameras: these can be opened directly with openCamera(id).
        val raw = mutableListOf<CameraDeviceInfo>()
        for (id in ids) {
            try {
                val characteristics = manager.getCameraCharacteristics(id)
                raw += describeCamera(
                    logicalId = id,
                    physicalId = null,
                    characteristics = characteristics,
                    facingOverride = null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping camera $id: ${e.message}")
            }
        }

        // 2) Physical sensors of logical multi-cameras (API 28+). Many phones
        //    hide the rear ultra-wide this way: it is not in cameraIdList and
        //    cannot be opened by id, but its stream can be configured on the
        //    logical device with OutputConfiguration.setPhysicalCameraId().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val logicalIds = ids.toSet()
            for (logical in raw.toList()) {
                val logicalChars = try {
                    manager.getCameraCharacteristics(logical.id)
                } catch (e: Exception) {
                    continue
                }
                val physicalIds = try {
                    logicalChars.physicalCameraIds
                } catch (e: Exception) {
                    emptySet<String>()
                }
                if (physicalIds.isEmpty()) continue

                val logicalEquiv = equivalentFocalLengthMm(logicalChars)
                for (physicalId in physicalIds) {
                    // Already exposed as its own logical camera: it can be opened
                    // directly, so a physical duplicate would only confuse the UI.
                    if (physicalId in logicalIds) continue

                    val physicalChars = try {
                        manager.getCameraCharacteristics(physicalId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping physical camera $physicalId: ${e.message}")
                        continue
                    }

                    // The face pipeline needs YUV; skip sensors that only expose
                    // JPEG / PRIVATE output (some auxiliary lenses do).
                    val yuvSizes = physicalChars
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(ImageFormat.YUV_420_888)
                    if (yuvSizes.isNullOrEmpty()) {
                        Log.d(TAG, "Physical camera $physicalId has no YUV output, skipped")
                        continue
                    }

                    // Skip the sensor the logical camera already streams from
                    // (usually the main lens): same focal length, duplicate entry.
                    val physicalEquiv = equivalentFocalLengthMm(physicalChars)
                    if (logicalEquiv != null && physicalEquiv != null &&
                        kotlin.math.abs(physicalEquiv - logicalEquiv) / logicalEquiv < 0.12f
                    ) {
                        Log.d(
                            TAG,
                            "Physical camera $physicalId (%.1fmm) duplicates logical %.1fmm, skipped"
                                .format(physicalEquiv, logicalEquiv),
                        )
                        continue
                    }

                    raw += describeCamera(
                        logicalId = logical.id,
                        physicalId = physicalId,
                        characteristics = physicalChars,
                        facingOverride = logical.lensFacing,
                    )
                }
            }
        }

        return labelCameras(raw)
    }

    /** Build one picker entry from a logical or physical camera's characteristics. */
    private fun describeCamera(
        logicalId: String,
        physicalId: String?,
        characteristics: CameraCharacteristics,
        facingOverride: CameraLensFacing?,
    ): CameraDeviceInfo = CameraDeviceInfo(
        id = logicalId,
        physicalCameraId = physicalId,
        lensFacing = facingOverride
            ?: mapLensFacing(characteristics.get(CameraCharacteristics.LENS_FACING)),
        // Filled in by labelCameras() once the facing group is known.
        label = "",
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
        verticalFovDegrees = calculateFovDegrees(characteristics),
        equivalentFocalLengthMm = equivalentFocalLengthMm(characteristics),
    )

    /**
     * Assign unique labels and stable ordering.
     *
     * Within one facing group a logical camera comes first, then physical
     * sensors ordered by focal length (ultra-wide → telephoto). Cameras whose
     * 35 mm-equivalent focal length is clearly wide or telephoto get a
     * `超广角` / `长焦` suffix so the picker makes the lens obvious.
     */
    private fun labelCameras(raw: List<CameraDeviceInfo>): List<CameraDeviceInfo> {
        val used = mutableMapOf<String, Int>()
        val labelled = mutableListOf<CameraDeviceInfo>()

        raw.groupBy { it.lensFacing }.values.forEach { group ->
            val sorted = group.sortedWith(
                compareBy<CameraDeviceInfo> { it.isPhysical }
                    .thenBy { it.equivalentFocalLengthMm ?: 0f },
            )
            val base = baseLabel(sorted.first().lensFacing)
            sorted.forEach { info ->
                // Tag by lens first (Back 超广角); entries that would collide
                // (e.g. two plain rear cameras) get a numeric suffix instead.
                val tag = lensTag(info.equivalentFocalLengthMm)
                val label = if (tag != null) "$base $tag" else base
                labelled += info.copy(label = uniqueLabel(label, used))
            }
        }

        return labelled.sortedWith(
            compareBy(
                { facingPriority(it.lensFacing) },
                { it.isPhysical },
                { it.equivalentFocalLengthMm ?: 0f },
            ),
        )
    }

    private fun baseLabel(facing: CameraLensFacing): String = when (facing) {
        CameraLensFacing.FRONT -> "Front"
        CameraLensFacing.BACK -> "Back"
        CameraLensFacing.EXTERNAL -> "External"
        CameraLensFacing.UNKNOWN -> "Camera"
    }

    /**
     * Lens tag from the 35 mm-equivalent focal length.
     *
     * Thresholds are conservative: only clearly wider-than-normal (< 17.5 mm)
     * and clearly telephoto lenses (>= 70 mm) get a suffix, so an ordinary
     * rear camera stays "Back" instead of being relabelled "Back 广角".
     */
    private fun lensTag(equivalentFocalLengthMm: Float?): String? = when {
        equivalentFocalLengthMm == null -> null
        equivalentFocalLengthMm < 17.5f -> "超广角"
        equivalentFocalLengthMm >= 70f -> "长焦"
        else -> null
    }

    /** Append a numeric suffix only when two entries end up with the same label. */
    private fun uniqueLabel(label: String, used: MutableMap<String, Int>): String {
        val count = (used[label] ?: 0) + 1
        used[label] = count
        return if (count == 1) label else "$label $count"
    }

    /**
     * 35 mm-equivalent focal length from focal length + physical sensor size.
     *
     * `focal * 43.27 / sensorDiagonal` — the same "equivalent focal length"
     * camera specs quote (24 mm = mainstream rear camera, ~13 mm = ultra-wide).
     */
    private fun equivalentFocalLengthMm(characteristics: CameraCharacteristics): Float? {
        val focalLength = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
            ?: return null
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        if (focalLength <= 0f || sensorSize.width <= 0f || sensorSize.height <= 0f) return null

        val diagonal = sqrt(
            sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height
        )
        if (diagonal <= 0f) return null
        return focalLength * 43.266f / diagonal
    }

    /**
     * Choose the camera used by the next [startCamera] call.
     *
     * @param cameraId a [CameraDeviceInfo.key] from [getAvailableCameras]
     *                 (`"0"` for a logical camera, `"0#2"` for a physical one),
     *                 or `null` to fall back to the automatic front-first choice.
     *                 The key is validated only when the camera is opened, so a
     *                 stale key from a previous device simply falls back to auto.
     * @param physicalCameraId optional physical sensor id; when omitted, a
     *                 `"logical#physical"` [cameraId] is parsed automatically.
     */
    fun setPreferredCamera(cameraId: String?, physicalCameraId: String? = null) {
        val parts = cameraId?.split('#', limit = 2)
        val logicalId = parts?.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val physicalId = physicalCameraId
            ?: parts?.getOrNull(1)?.takeIf { it.isNotEmpty() }

        preferredCameraId = logicalId
        preferredPhysicalCameraId = if (logicalId == null) null else physicalId
        Log.i(TAG, "Preferred camera: ${getPreferredCameraId() ?: "auto (front-first)"}")
    }

    /**
     * Camera key requested via [setPreferredCamera], or null for automatic choice.
     *
     * Returns the composite `"logical#physical"` key so it can be persisted and
     * passed back verbatim.
     */
    fun getPreferredCameraId(): String? = preferredCameraId?.let { logicalId ->
        preferredPhysicalCameraId?.let { "$logicalId#$it" } ?: logicalId
    }

    /**
     * Set callback for frame processing (legacy RGB mode)
     * The callback receives rotated RGB data
     */
    fun setFrameCallback(callback: (ByteBuffer, Int, Int) -> Unit) {
        this.frameCallback = callback
    }
    
    /**
     * Set callback for direct YUV frame processing (zero-copy mode)
     * The callback receives YUV planes with metadata for JNI direct processing
     * 
     * When using this mode, set useDirectYuvMode = true before calling startCamera()
     * 
     * @param callback Receives YuvFrameData containing Y/U/V buffers and metadata
     */
    fun setYuvFrameCallback(callback: (YuvFrameData) -> Unit) {
        this.yuvFrameCallback = callback
    }
    
    /**
     * Start camera capture using the preferred camera (see [setPreferredCamera]).
     *
     * If no preference is set (or it is no longer present), the first front
     * camera is used, then back, then external — so existing behavior is
     * preserved on single-camera phones.
     */
    fun startCamera() {
        if (!hasPermission()) {
            Log.e(TAG, "Camera permission not granted")
            return
        }
        synchronized(captureLifecycleLock) { startCameraInternal() }
    }

    private fun startCameraInternal() {
        // Guard against double-start (e.g. rapid Start taps or a switch while
        // the previous session is still tearing down).
        if (backgroundThread != null || cameraDevice != null) {
            Log.w(TAG, "Camera already started, releasing the previous session first")
            stopCameraInternal()
        }

        // 探针降档触发的重开要保留目标尺寸；手动 start / 换镜头时重新按能力挑。
        if (!keepCaptureSizeOverride) {
            captureSizeOverride = null
            sizeDownshiftCount = 0
        }
        keepCaptureSizeOverride = false

        startBackgroundThread()

        // 重开相机时重置帧率统计窗口，否则第一段读数会被上一轮的陈旧窗口污染。
        deliveredFrameCount = 0
        deliveredWindowStartNs = 0L
        cameraDeliveredFps = 0f
        deliveredFpsReady = false
        cameraCallbackAvgMs = 0f
        cbWindowFrames = 0
        cbWindowTotalNs = 0L
        cbWindowMaxNs = 0L
        appliedFrameDurationNs = 0L
        appliedExposureNs = 0L
        appliedFpsRange = null
        activeFpsRange = null
        lastCaptureResultKey = null
        lastPreviewFrameNs = 0L
        // 上一轮若因 HAL 拒绝 SENSOR_FRAME_DURATION 关了钉帧率，重开时重新尝试，
        // 否则一次回退会让之后每次采集都停在 30fps。
        pinFrameRate = true
        // 物理相机流失败退回逻辑相机的机会也重新给一次。
        physicalFallbackTried = false
        cameraSessionActive = true

        openCamera()
    }
    
    /**
     * Stop camera capture and release resources
     */
    fun stopCamera() {
        synchronized(captureLifecycleLock) { stopCameraInternal() }
    }

    private fun stopCameraInternal() {
        // 先让探针失效：旧的 2.5s/0.8s 定时任务醒来后看到代号不一致会直接退出，
        // 不会在 stop 之后把相机重新拉起来。
        cameraSessionActive = false
        fpsProbeGeneration++
        currentCaptureSize = null
        streamConfigMap = null

        // 顺序很关键：先停相机线程（quitSafely 会把已入队的回调跑完再退出），
        // 再关 session / ImageReader。反过来的话，在途回调会去操作已经 close 的
        // reader，`image.close()` 抛 IllegalStateException 会直接打死相机线程，
        // 之后整条链路永久断流。
        stopBackgroundThread()

        captureSession?.close()
        captureSession = null
        captureSurface = null
        
        cameraDevice?.close()
        cameraDevice = null
        
        imageReader?.close()
        imageReader = null
    }
    
    private fun startBackgroundThread() {
        // 相机线程必须比普通后台线程更早被调度：它的 16.7ms 预算是硬上限
        // （回调一旦超过就丢帧，交付帧率直接掉档），而后面的 fc-* 推理线程
        // 已经跑在 NORM+2 上。用默认优先级会让相机回调在 CPU 忙时被压后。
        backgroundThread = HandlerThread(
            "CameraBackground",
            android.os.Process.THREAD_PRIORITY_DISPLAY,
        ).also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }
    
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        try {
            val cameras = getAvailableCameras()
            val info = chooseCamera(cameras) ?: run {
                Log.e(TAG, "No camera available on this device")
                return
            }

            // The logical device owns the request / 3A; a physical sensor only
            // provides the stream, so its characteristics describe size,
            // orientation and FOV instead.
            val logicalCharacteristics = manager.getCameraCharacteristics(info.id)
            cameraCharacteristics = logicalCharacteristics

            var activeInfo = info
            var physicalCharacteristics: CameraCharacteristics? = null
            val requestedPhysicalId = info.physicalCameraId
            if (requestedPhysicalId != null) {
                physicalCharacteristics = try {
                    manager.getCameraCharacteristics(requestedPhysicalId)
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Physical camera $requestedPhysicalId is not queryable: ${e.message}",
                    )
                    null
                }
                if (physicalCharacteristics == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    // Never open the plain logical camera while the picker
                    // claims a physical lens — fall back to the logical entry.
                    activeInfo = cameras.firstOrNull { it.id == info.id && !it.isPhysical }
                        ?: info.copy(physicalCameraId = null)
                    physicalCharacteristics = null
                    Log.w(TAG, "Falling back to logical camera ${activeInfo.id}")
                }
            }

            // Remember the device we actually opened so callers can react
            // synchronously (preview mirroring, debug HUD, camera picker).
            selectedCameraInfo = activeInfo
            lensFacing = logicalCharacteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_FRONT
            sensorOrientation = activeInfo.sensorOrientation
            cameraVerticalFovDegrees = activeInfo.verticalFovDegrees

            val physicalSuffix = activeInfo.physicalCameraId?.let { " · physical $it" } ?: ""
            Log.i(
                TAG,
                "Camera selected: ${activeInfo.label} (id=${activeInfo.id}$physicalSuffix), " +
                    "sensor=$sensorOrientation°, fov=$cameraVerticalFovDegrees°"
            )

            // Physical sensors have their own stream map (and often a different
            // set of supported sizes), so size / fps probing must use it.
            val streamCharacteristics = physicalCharacteristics ?: logicalCharacteristics
            val map = streamCharacteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // 采集尺寸：优先用探针降档选定的尺寸（本次会话）或该相机之前探明的尺寸
            // （跨 stop/start 保留）；否则按「能跑满 60fps」重新挑。
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            streamConfigMap = map
            currentCameraKey = activeInfo.key
            captureSizeCandidates = yuvSizes
                .filter {
                    minOf(it.width, it.height) >= MIN_SIZE &&
                        it.width * it.height <= MAX_PIXEL_COUNT
                }
                .sortedByDescending { it.width * it.height }

            val rememberedSize = resolvedCaptureSizes[activeInfo.key]
            val overrideSize = (captureSizeOverride ?: rememberedSize)?.takeIf { wanted ->
                yuvSizes.any { it.width == wanted.width && it.height == wanted.height }
            }
            val previewSize = overrideSize ?: chooseCaptureSize(yuvSizes, map)
            currentCaptureSize = previewSize

            if (overrideSize != null) {
                Log.i(
                    TAG,
                    "Selected capture size (adaptive downshift): " +
                        "${previewSize.width}x${previewSize.height}",
                )
            } else {
                Log.i(TAG, "Selected capture size: ${previewSize.width}x${previewSize.height}")
            }

            // Create ImageReader for frame capture.
            // 3 个 buffer：处理端偶尔慢一拍时相机可以继续出帧，配合
            // acquireLatestImage() 只会拿到最新一帧，不会积压延迟。
            imageReader = ImageReader.newInstance(
                previewSize.width,
                previewSize.height,
                ImageFormat.YUV_420_888,
                3
            ).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }

            manager.openCamera(activeInfo.id, stateCallback, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error opening camera", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Camera configuration rejected", e)
        }
    }

    /**
     * Resolve the camera to open: explicit preference first, then front, back,
     * external. A stale preference (camera unplugged / different device / the
     * physical sensor disappeared) is ignored with a warning.
     */
    private fun chooseCamera(cameras: List<CameraDeviceInfo>): CameraDeviceInfo? {
        if (cameras.isEmpty()) return null

        val preferredKey = getPreferredCameraId()
        if (preferredKey != null) {
            cameras.firstOrNull { it.key == preferredKey }?.let { return it }

            // The physical sensor may be gone while its logical parent still
            // exists (different ROM, camera HAL update): keep using that
            // camera instead of jumping to an unrelated one.
            cameras.firstOrNull { it.id == preferredCameraId && !it.isPhysical }?.let {
                Log.w(
                    TAG,
                    "Physical camera $preferredPhysicalCameraId is unavailable, " +
                        "using logical ${it.id}",
                )
                return it
            }
            Log.w(TAG, "Preferred camera $preferredKey is unavailable, falling back to auto")
        }

        return cameras.firstOrNull { it.isFrontFacing }
            ?: cameras.firstOrNull { it.isBackFacing }
            ?: cameras.first()
    }

    private fun facingPriority(facing: CameraLensFacing): Int = when (facing) {
        CameraLensFacing.FRONT -> 0
        CameraLensFacing.BACK -> 1
        CameraLensFacing.EXTERNAL -> 2
        CameraLensFacing.UNKNOWN -> 3
    }

    private fun mapLensFacing(raw: Int?): CameraLensFacing = when (raw) {
        CameraCharacteristics.LENS_FACING_FRONT -> CameraLensFacing.FRONT
        CameraCharacteristics.LENS_FACING_BACK -> CameraLensFacing.BACK
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraLensFacing.EXTERNAL
        else -> CameraLensFacing.UNKNOWN
    }

    /**
     * Calculate vertical FOV from camera characteristics.
     * Uses focal length and physical sensor height; falls back to 63° when the
     * camera does not report those fields (common for some external cameras).
     */
    private fun calculateFovDegrees(characteristics: CameraCharacteristics): Float {
        return try {
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLengths =
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

            if (sensorSize != null && focalLengths != null && focalLengths.isNotEmpty()) {
                val sensorHeight = sensorSize.height
                val focalLength = focalLengths[0]

                if (sensorHeight > 0f && focalLength > 0f) {
                    val fovRadians =
                        2.0 * kotlin.math.atan((sensorHeight / (2.0 * focalLength)).toDouble())
                    Math.toDegrees(fovRadians).toFloat()
                } else {
                    63.0f
                }
            } else {
                63.0f
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating camera FOV: ${e.message}")
            63.0f
        }
    }

    /**
     * 选采集尺寸：优先 720p（1280x720），其次「720p 以内能跑满 60fps 的最大尺寸」。
     *
     * Camera2 只允许从设备上报的尺寸里选，所以这里不是「想要什么就是什么」，
     * 而是按下面的顺序挑最接近目标的那个：
     *
     *  1. 设备报了 1280x720（或竖屏的 720x1280）**且该尺寸能跑满 60fps** → 用它；
     *  2. 没有可用的 720p（或 720p 只到 30fps）→ 在 720p 以内、且
     *     [StreamConfigurationMap.getOutputMinFrameDuration] 证明能跑满 60fps 的
     *     尺寸里取最大的（先保证 60fps，再尽量清晰）；
     *  3. 整台相机都没有 60fps 尺寸 → 取 720p 以内最大的尺寸，并警告实际帧率。
     *
     * 注意策略 1 的条件：**帧率优先于清晰度**。很多 HAL 会把 720p 列进 YUV
     * 尺寸表却只给 30fps，死守 720p 会让整条管线被相机按在 30fps。
     *
     * 为什么用 `getOutputMinFrameDuration` 而不是只看
     * `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`：后者只说明 sensor 能跑多快，
     * 不保证 720p 这种大尺寸的带宽也能跑满 60fps。
     */
    private fun chooseCaptureSize(
        choices: Array<Size>,
        map: StreamConfigurationMap?,
    ): Size {
        if (choices.isEmpty()) {
            Log.w(TAG, "No available sizes, using default ${TARGET_WIDTH}x${TARGET_HEIGHT}")
            return Size(TARGET_WIDTH, TARGET_HEIGHT)
        }

        Log.d(TAG, "Available YUV sizes (max fps from per-size frame duration):")
        choices.sortedByDescending { it.width * it.height }.forEach { size ->
            val maxFps = maxFpsForSize(map, size)
            Log.d(
                TAG,
                "  ${size.width}x${size.height} (aspect: %.2f, max: %s)".format(
                    aspectRatioOf(size),
                    maxFps?.let { "%.0f fps".format(it) } ?: "unknown",
                ),
            )
        }

        // 只考虑 720p 以内、且不小于模型输入级别的尺寸
        val candidates = choices.filter { size ->
            minOf(size.width, size.height) >= MIN_SIZE &&
                size.width * size.height <= MAX_PIXEL_COUNT
        }
        if (candidates.isEmpty()) {
            val fallback = choices
                .filter { minOf(it.width, it.height) >= MIN_SIZE }
                .minByOrNull { it.width * it.height }
                ?: choices.minByOrNull { it.width * it.height }
                ?: choices.first()
            Log.w(TAG, "No usable size <= 720p, using ${fallback.width}x${fallback.height}")
            return fallback
        }

        // 1. 精确的 720p：只有「该尺寸确实能跑满 60fps」时才用。
        //
        //    很多 HAL 会把 720p 也列在 YUV 尺寸表里，但它的 minFrameDuration
        //    只有 30fps。上一版在这种机型上仍然死守 720p，结果整条管线被相机
        //    按在 30fps —— 推理再快也没用。所以这里改成：720p 跑不满 60fps
        //    就往下找一个能跑满的尺寸（通常是 640x480），帧率优先、清晰度次之。
        val target = candidates.firstOrNull {
            it.width == TARGET_WIDTH && it.height == TARGET_HEIGHT
        } ?: candidates.firstOrNull {
            it.width == TARGET_HEIGHT && it.height == TARGET_WIDTH
        }
        if (target != null && canDeliverTargetFps(map, target)) {
            val maxFps = maxFpsForSize(map, target)
            Log.i(
                TAG,
                "✓ Selected 720p capture: ${target.width}x${target.height} " +
                    "(max ${maxFps?.let { "%.1f".format(it) } ?: "unknown"} fps)",
            )
            return target
        }
        if (target != null) {
            val maxFps = maxFpsForSize(map, target)
            Log.w(
                TAG,
                ("⚠ 720p (${target.width}x${target.height}) is advertised but this camera " +
                    "caps it at %.1f fps (< ${TARGET_FPS}); picking a ${TARGET_FPS}fps-capable " +
                    "size instead (frame rate first)").format(maxFps),
            )
        }

        // 2. 没有可用的 720p：挑能跑满 60fps 的最大尺寸
        val sixtyFpsSizes = candidates.filter { canDeliverTargetFps(map, it) }
        val preferred = if (sixtyFpsSizes.isNotEmpty()) sixtyFpsSizes else candidates
        val best = preferred.sortedWith(
            compareByDescending<Size> { it.width * it.height }
                .thenBy { kotlin.math.abs(aspectRatioOf(it) - TARGET_ASPECT) }
        ).first()

        if (sixtyFpsSizes.isEmpty()) {
            Log.w(TAG, "⚠ No ${TARGET_FPS}fps-capable size advertised; using ${best.width}x${best.height}")
        } else {
            Log.i(TAG, "✓ Selected capture size: ${best.width}x${best.height} (target ${TARGET_FPS}fps)")
        }
        return best
    }

    /** 尺寸的宽高比，恒 >= 1.0（与上报的横/竖方向无关）。 */
    private fun aspectRatioOf(size: Size): Float =
        maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height)

    /**
     * 该输出尺寸在硬件上能跑到的最高帧率（`null` = 平台没报，无法判断）。
     *
     * `getOutputMinFrameDuration` 给的是「这个尺寸下每帧的最短间隔」，也就是这个
     * 尺寸的真·帧率上限。
     */
    private fun maxFpsForSize(map: StreamConfigurationMap?, size: Size): Float? {
        val durationNs = try {
            map?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size) ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query frame duration for ${size.width}x${size.height}: ${e.message}")
            0L
        }
        return if (durationNs > 0L) 1_000_000_000f / durationNs else null
    }

    /** 该尺寸是否满足目标 60fps（平台没报时按「可以」处理，交给采集回调验证）。 */
    private fun canDeliverTargetFps(map: StreamConfigurationMap?, size: Size): Boolean {
        val maxFps = maxFpsForSize(map, size) ?: return true
        return maxFps >= TARGET_FPS - FPS_TOLERANCE
    }
    
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.i(TAG, "Camera opened")
            cameraDevice = camera
            createCaptureSession()
        }
        
        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected")
            camera.close()
            cameraDevice = null
        }
        
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            camera.close()
            cameraDevice = null
        }
    }

    /**
     * Create the YUV capture session.
     *
     * A regular logical camera uses the classic
     * `createCaptureSession(listOf(surface), …)` call. A hidden physical sensor
     * (rear ultra-wide / telephoto) is bound to the logical device through the
     * API 28 [SessionConfiguration] + [OutputConfiguration.setPhysicalCameraId]
     * overload: the logical device stays the capture target while the stream is
     * produced by the physical sensor.
     *
     * If the physical stream is rejected ([sessionStateCallback]'s
     * `onConfigureFailed`), the session is recreated once on the logical camera
     * so face tracking still works instead of going dark.
     */
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return
        val surface = reader.surface
        captureSurface = surface

        val physicalId = selectedCameraInfo?.physicalCameraId
        if (physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val output = OutputConfiguration(surface).apply {
                    setPhysicalCameraId(physicalId)
                }
                val configuration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    sessionExecutor,
                    sessionStateCallback,
                )
                // API 29+ can tell up front whether this physical stream
                // combination is supported; some HALs expose the sensor but
                // reject the stream in the session callback instead.
                val supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        camera.isSessionConfigurationSupported(configuration)
                    } catch (e: Exception) {
                        Log.w(TAG, "isSessionConfigurationSupported failed: ${e.message}")
                        true // let createCaptureSession decide
                    }
                } else {
                    true
                }
                if (!supported) {
                    // Reflect what the session will really stream so the UI
                    // does not keep claiming an ultra-wide feed.
                    selectedCameraInfo = selectedCameraInfo?.copy(physicalCameraId = null)
                    Log.w(
                        TAG,
                        "HAL rejects physical stream $physicalId for logical " +
                            "${selectedCameraInfo?.id}; falling back to the logical camera"
                    )
                } else {
                    Log.i(
                        TAG,
                        "Creating capture session: logical ${selectedCameraInfo?.id}, " +
                            "physical $physicalId"
                    )
                    camera.createCaptureSession(configuration)
                    return
                }
            } catch (e: Exception) {
                selectedCameraInfo = selectedCameraInfo?.copy(physicalCameraId = null)
                Log.e(TAG, "Physical camera session rejected, falling back to logical", e)
            }
        }

        try {
            camera.createCaptureSession(listOf(surface), sessionStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error creating capture session", e)
        }
    }

    private val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            Log.i(TAG, "Capture session configured")
            captureSession = session

            val surface = captureSurface ?: run {
                Log.e(TAG, "Capture surface missing")
                return
            }
            val request = buildCaptureRequest(surface)
            if (request == null) {
                Log.e(TAG, "Failed to build capture request")
                return
            }

            var captureStarted = false
            try {
                // Start continuous capture
                session.setRepeatingRequest(
                    request,
                    captureCallback,
                    backgroundHandler
                )
                captureStarted = true
            } catch (e: IllegalArgumentException) {
                // 个别 HAL 不接受显式 SENSOR_FRAME_DURATION，退回到只设 AE 区间。
                Log.w(TAG, "Repeating request rejected, retrying without pinned frame rate", e)
                pinFrameRate = false
                val fallback = buildCaptureRequest(surface)
                if (fallback != null) {
                    try {
                        session.setRepeatingRequest(
                            fallback,
                            captureCallback,
                            backgroundHandler
                        )
                        captureStarted = true
                    } catch (e2: Exception) {
                        Log.e(TAG, "Error starting capture (fallback)", e2)
                    }
                }
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Error starting capture", e)
            }

            // 采集自检：启动 2.5s 后读真实交付帧率，偏低就自动降一档采集尺寸。
            // 这里不阻塞相机线程 —— 探测在独立的单线程执行器上跑。
            if (captureStarted) {
                scheduleFpsProbe()
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            if (selectedCameraInfo?.physicalCameraId != null && !physicalFallbackTried) {
                physicalFallbackTried = true
                Log.w(
                    TAG,
                    "Physical camera stream was rejected; retrying on the logical camera"
                )
                selectedCameraInfo = selectedCameraInfo?.copy(physicalCameraId = null)
                runCatching { session.close() }
                createCaptureSession()
            } else {
                Log.e(TAG, "Capture session configuration failed")
            }
        }
    }

    /**
     * Build the repeating [CaptureRequest] for the current session output.
     *
     * Kept as a helper (instead of building inline) so that a HAL which rejects
     * the pinned [CaptureRequest.SENSOR_FRAME_DURATION] can be retried with a
     * plain request — see [createCaptureSession].
     */
    private fun buildCaptureRequest(surface: android.view.Surface): CaptureRequest? {
        val camera = cameraDevice ?: return null
        return try {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyCaptureRequestTuning(this)
            }.build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build capture request", e)
            null
        }
    }

    /**
     * Tune auto-focus / exposure / frame rate for the selected camera.
     *
     * Device capabilities differ a lot — external UVC cameras often do not
     * support CONTINUOUS_PICTURE autofocus, and the 30-60 FPS range is not
     * universal. Probe the characteristics first and only apply values the
     * camera actually advertises, otherwise session configuration can fail.
     */
    private fun applyCaptureRequestTuning(builder: CaptureRequest.Builder) {
        val characteristics = cameraCharacteristics

        val aeModes = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        if (aeModes == null || aeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        val afModes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (afModes == null || afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
        }

        val fpsRanges = characteristics
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val chosen = chooseTargetFpsRange(fpsRanges)
        if (chosen != null) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen)
            activeFpsRange = chosen
            Log.i(TAG, "AE target FPS range: ${chosen.lower}-${chosen.upper}")
        }

        // ⚠️ 关键：AE 区间是「允许范围」，不是「目标帧率」。
        //
        // 选到 (30,60) 这种可变区间后如果不管它，Camera2 的 AE 在室内灯光下会从
        // 区间下端（33ms）开始跑 —— 为的是拿更长的曝光时间，代价是帧率直接砍半。
        // 现象就是「CPU 和 GPU 都不忙，但只有 30 帧」：整条管线都在等相机喂帧。
        //
        // 面捕场景里「稳定的 60fps」比「暗光下亮一点」重要得多，所以这里显式把
        // SENSOR_FRAME_DURATION 钉在区间上限（要求 >=60 时才钉，避免把 30fps 的
        // 相机强行拉到它做不到的帧率）。AE 会自动改用 ISO / 降噪去补曝光。
        if (pinFrameRate) {
            val range = chosen
            if (range != null && range.upper >= TARGET_FPS) {
                builder.set(
                    CaptureRequest.SENSOR_FRAME_DURATION,
                    1_000_000_000L / TARGET_FPS
                )
                Log.i(
                    TAG,
                    "Pinned SENSOR_FRAME_DURATION = ${1_000_000_000L / TARGET_FPS} ns " +
                        "(${TARGET_FPS} fps, AE range ${range.lower}-${range.upper})"
                )
            }
        }
    }

    /**
     * Pick the camera FPS range that best supports a **stable 60 FPS** pipeline.
     *
     * Preference order (Camera2 only accepts ranges the device advertises):
     *  1. a fixed `(60, 60)` — the steadiest possible choice;
     *  2. an adaptive range whose ceiling reaches 60 with a low floor, usually
     *     `(30, 60)`: the sensor stays at 60 in good light and is still allowed
     *     to fall back to 30 in the dark instead of producing a black frame;
     *  3. the highest-ceiling range starting at <= 30 (typically `(30, 30)`),
     *     for cameras that simply cannot do 60;
     *  4. whatever has the highest ceiling.
     */
    private fun chooseTargetFpsRange(
        ranges: Array<android.util.Range<Int>>?,
    ): android.util.Range<Int>? {
        if (ranges == null || ranges.isEmpty()) {
            return android.util.Range(30, 60)
        }

        ranges.firstOrNull { it.lower == 60 && it.upper == 60 }?.let { return it }

        ranges
            .filter { it.upper >= 60 && it.lower <= 30 }
            .maxByOrNull { it.upper }
            ?.let { return it }

        return ranges.filter { it.lower <= 30 }.maxByOrNull { it.upper }
            ?: ranges.maxByOrNull { it.upper }
    }
    
    /**
     * 把 [CaptureRequest.SENSOR_FRAME_DURATION] 钉在 AE 区间上限所用的目标帧率。
     * 只在该区间上限 >= 这个值时才钉，避免把 30fps 的相机拉到它做不到的帧率。
     */
    @Volatile
    var pinFrameRate: Boolean = true

    /** 实际下发的 AE 帧率区间（null = 未设置，交给 HAL 自己决定）。 */
    @Volatile
    var activeFpsRange: android.util.Range<Int>? = null
        private set

    /** HAL 在 CaptureResult 里回报的**实际生效** AE 区间（可能和请求值不同）。 */
    @Volatile
    var appliedFpsRange: android.util.Range<Int>? = null
        private set

    /** HAL 回报的实际帧时长（纳秒）。0 = 还没收到 CaptureResult。 */
    @Volatile
    var appliedFrameDurationNs: Long = 0L
        private set

    /** HAL 回报的实际曝光时间（纳秒），用于判断是不是 AE 在拿曝光换帧率。 */
    @Volatile
    var appliedExposureNs: Long = 0L
        private set

    /** 相机每秒**真正交付**的帧数（滚动 1 秒窗口）。 */
    @Volatile
    var cameraDeliveredFps: Float = 0f
        private set

    private var deliveredFrameCount = 0
    private var deliveredWindowStartNs = 0L
    private var lastCaptureResultKey: String? = null

    /** 是否已经凑满至少一个 1s 统计窗口（探针据此避免把「还没统计到」误判成低帧率）。 */
    @Volatile
    private var deliveredFpsReady = false

    // ---- 相机回调耗时（用来区分「传感器慢」还是「我们慢」）-------------------

    /**
     * 相机线程单个回调的平均处理耗时（毫秒）。
     *
     * ImageReader 的 listener 在**单线程** Handler 上串行执行，而
     * `acquireLatestImage()` 只保留最新帧，所以实际交付帧率 =
     * min(传感器帧率, 1000 / 回调耗时)：回调 16.7ms → 60fps，33ms → 30fps。
     * 打印这个值就能一眼分清「传感器/AE 只有 30fps」和「我们的处理把回调拖到 33ms」，
     * 两者的解法完全不同（前者换尺寸也没用，后者要砍相机线程的活）。
     */
    @Volatile
    var cameraCallbackAvgMs: Float = 0f
        private set

    /** 回调耗时统计窗口（帧）：60fps 下约 2 秒打一次日志。 */
    private var cbWindowFrames = 0
    private var cbWindowTotalNs = 0L
    private var cbWindowMaxNs = 0L

    // ---- 预览限速 / 采集尺寸自适应 -----------------------------------------

    /** 上一次生成预览 RGB 的时刻（nanoTime）；只在相机线程访问。 */
    private var lastPreviewFrameNs = 0L

    /** 当前会话实际的采集尺寸（自适应降档后更新），供日志与降档逻辑使用。 */
    @Volatile
    private var currentCaptureSize: Size? = null

    /** 实测低帧率时强制使用的采集尺寸（null = 每次按能力重新挑）。 */
    @Volatile
    private var captureSizeOverride: Size? = null

    /** 本会话已自动降档的次数（上限 [MAX_SIZE_DOWNSHIFTS]）。 */
    @Volatile
    private var sizeDownshiftCount = 0

    /** 探针代号：重开相机 / 停相机会递增，旧探针读到不一致就自动失效。 */
    @Volatile
    private var fpsProbeGeneration = 0

    /** stopCamera() 之后为 false，探针不会再把相机重新拉起来。 */
    @Volatile
    private var cameraSessionActive = false

    /** 本轮 startCamera() 是否由探针降档触发（是则保留 [captureSizeOverride]）。 */
    private var keepCaptureSizeOverride = false

    /** 当前相机的候选采集尺寸（按像素降序），供降档时挑选下一个档位。 */
    @Volatile
    private var captureSizeCandidates: List<Size> = emptyList()

    /** 当前相机（或物理镜头）的 key，用作 [resolvedCaptureSizes] 的索引。 */
    @Volatile
    private var currentCameraKey: String? = null

    /**
     * 已经探明「在这个相机上跑满 60fps」的采集尺寸（按相机 key 持久化）。
     *
     * 进程内一直保留：这样用户 stop/start、切走再切回同一个镜头时，不必再花
     * 3.3 秒重新「先按能力挑 → 实测 → 降档」。相机不同则各记各的。
     */
    private val resolvedCaptureSizes = java.util.concurrent.ConcurrentHashMap<String, Size>()

    /** 当前相机（或物理镜头）的 stream map，用于判断某尺寸能否跑满 60fps。 */
    @Volatile
    private var streamConfigMap: StreamConfigurationMap? = null

    /**
     * 帧率探针线程。
     *
     * 不能跑在相机 HandlerThread 上：降档要 stopCamera()，而 stopCamera() 会
     * join 相机线程 —— 在相机线程上调用就是自己等自己（死锁）。这里用独立单线程
     * 串行执行「探测 → 重开」，与 [captureLifecycleLock] 一起保证不会并发重入。
     */
    private val fpsProbeExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Camera-fps-probe").apply { isDaemon = true }
    }

    /** startCamera / stopCamera / 降档重开互斥，避免并发重入把相机会话打乱。 */
    private val captureLifecycleLock = Any()

    /**
     * 读取 HAL 真实生效的参数。
     *
     * `SENSOR_FRAME_DURATION` 是判断「相机到底吐了多少帧」的权威值：
     * 如果它稳定在 33.3ms，说明传感器就是 30fps，后面无论怎么优化推理都没用。
     * 只在参数变化时打日志，避免每帧刷屏（每帧只读几个字段，开销可忽略）。
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val range = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
            val frameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0

            appliedFpsRange = range
            appliedFrameDurationNs = frameDuration
            appliedExposureNs = exposure

            val key = "$range|$frameDuration|$iso"
            if (key != lastCaptureResultKey) {
                lastCaptureResultKey = key
                val fps = if (frameDuration > 0) 1_000_000_000.0 / frameDuration else 0.0
                Log.i(
                    TAG,
                    "Camera applied: AE range=$range, frameDuration=" +
                        "%.2f ms (%.1f fps), exposure=%.2f ms, ISO=%d".format(
                            frameDuration / 1_000_000.0,
                            fps,
                            exposure / 1_000_000.0,
                            iso,
                        )
                )
            }
        }
    }

    // Timing for YUV conversion performance tracking
    private var yuvConversionCount = 0
    private var yuvConversionTotalMs = 0L
    private var lastYuvLogTime = System.currentTimeMillis()

    // Reusable RGB buffer to avoid allocations
    private var reusableRgbBuffer: ByteBuffer? = null
    private var cachedRotation: Int = -1

    // Check if native YUV converter is available
    private val useNativeConverter: Boolean by lazy {
        try {
            val available = YuvConverter.isNativeAvailable()
            Log.i(TAG, "Native YUV converter available: $available")
            available
        } catch (e: Exception) {
            Log.w(TAG, "Native YUV converter not available: ${e.message}")
            false
        }
    }

    // Check if MNN-based YUV converter is available (preferred)
    private val useMNNConverter: Boolean by lazy {
        try {
            val available = MNNYuvConverter.isAvailable()
            Log.i(TAG, "MNN YUV converter available: $available")
            available
        } catch (e: Exception) {
            Log.w(TAG, "MNN YUV converter not available: ${e.message}")
            false
        }
    }

    // Configuration: which converter to prefer
    // Set to true to use MNN ImageProcess for YUV->RGB (recommended)
    var preferMNNConverter: Boolean = true

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val cbStartNs = System.nanoTime()
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "ImageReader closed while acquiring a frame: ${e.message}")
            return@OnImageAvailableListener
        } ?: return@OnImageAvailableListener

        try {
            updateDeliveredFps()

            if (useDirectYuvMode && yuvFrameCallback != null) {
                // Direct YUV mode - pass YUV data directly to JNI for zero-copy processing
                processYuvDirect(image)

                // Also generate RGB preview if enabled (for debug visualization),
                // but rate-limit it: it is the single most expensive thing on the
                // camera thread at 720p and it does NOT need 60fps.
                if (enablePreviewInYuvMode && frameCallback != null) {
                    maybeConvertPreviewFrame(image)
                }
            } else {
                // Legacy RGB mode - convert YUV to RGB first
                processYuvToRgb(image)
            }
        } catch (e: Exception) {
            // 相机线程被异常打死 = 永久断流：单帧失败只允许丢帧，不允许终止线程。
            Log.e(TAG, "Frame processing failed, dropping frame", e)
        } finally {
            try {
                image.close()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Image was already closed: ${e.message}")
            }
            recordCallbackTime(System.nanoTime() - cbStartNs)
        }
    }

    /**
     * 统计相机回调耗时（只在相机线程调用，无需加锁）。
     *
     * 这是判定「delivered=30」根因的唯一直接数据：16.7ms 说明我们的处理没问题，
     * 传感器/AE 就是 30fps；接近或超过 33ms 说明是我们在拖慢交付。
     */
    private fun recordCallbackTime(elapsedNs: Long) {
        cbWindowFrames++
        cbWindowTotalNs += elapsedNs
        if (elapsedNs > cbWindowMaxNs) cbWindowMaxNs = elapsedNs
        if (cbWindowFrames >= CB_STATS_WINDOW_FRAMES) {
            val avgMs = (cbWindowTotalNs.toDouble() / cbWindowFrames / 1_000_000.0).toFloat()
            cameraCallbackAvgMs = avgMs
            Log.i(
                TAG,
                "Camera callback: avg %.2f ms, max %.2f ms over %d frames (60fps budget = 16.67 ms)"
                    .format(avgMs, cbWindowMaxNs / 1e6f, cbWindowFrames),
            )
            if (avgMs > 16.7f) {
                Log.w(
                    TAG,
                    "⚠ Camera callback takes %.2f ms — the camera thread cannot keep up with 60fps; " +
                        "this alone caps delivered FPS at ~%.0f".format(avgMs, 1000.0 / avgMs),
                )
            }
            cbWindowFrames = 0
            cbWindowTotalNs = 0L
            cbWindowMaxNs = 0L
        }
    }
    
    /**
     * 统计相机**实际交付**的帧率（滚动 1 秒窗口）。
     *
     * 这是区分「管线算不过来」和「相机根本只给 30 帧」最直接的数字：
     * 如果它停在 30 附近，那么后面所有推理优化都是白做。
     */
    private fun updateDeliveredFps() {
        val now = System.nanoTime()
        if (deliveredWindowStartNs == 0L) {
            // 第一帧：开窗，避免把「相机还没起来的那段时间」算进第一个窗口。
            deliveredWindowStartNs = now
            deliveredFrameCount = 0
        }
        deliveredFrameCount++
        val elapsedNs = now - deliveredWindowStartNs
        if (elapsedNs >= 1_000_000_000L) {
            cameraDeliveredFps = deliveredFrameCount * 1e9f / elapsedNs
            deliveredFrameCount = 0
            deliveredWindowStartNs = now
            deliveredFpsReady = true
        }
    }

    /**
     * 预览 RGB 限速：同一时间窗口内最多转换一帧。
     *
     * 预览是相机线程上最重的一段（整帧 YUV→RGB + RGB→ARGB + Bitmap 上传），
     * 720p 60fps 时每帧要处理约 2.7MB。预览只要看着不卡即可，30fps 足够；
     * 把它减半后，相机回调能稳定在 16.7ms 预算内，ImageReader 不再因为回调慢
     * 而丢帧，`cameraDeliveredFps` 才能真的跑满 60。
     */
    private fun maybeConvertPreviewFrame(image: android.media.Image) {
        val now = System.nanoTime()
        if (lastPreviewFrameNs != 0L && now - lastPreviewFrameNs < PREVIEW_INTERVAL_NS) {
            return
        }
        lastPreviewFrameNs = now
        processYuvToRgbForPreview(image)
    }

    /**
     * 采集帧率自检 + 自动降档。
     *
     * 平台上报的「某尺寸能跑 60fps」并不可靠：不少机型在 720p 上只有 30fps，
     * 或者 HAL 声称 60fps 但实际被 AE / 带宽按在 30fps。启动 2.5s 后读一次
     * [cameraDeliveredFps]（真实交付帧率），仍低于 [MIN_CAPTURE_FPS] 就在不
     * 小于 3/4 像素的前提下自动降一档尺寸重开相机，最多降 [MAX_SIZE_DOWNSHIFTS] 次。
     *
     * 这么做的目的很明确：**先保证 60fps，再谈清晰度**。面捕是动作跟随，30fps
     * 的手感比 720p 的清晰度重要得多。
     */
    private fun scheduleFpsProbe() {
        if (sizeDownshiftCount >= MAX_SIZE_DOWNSHIFTS) return
        val generation = ++fpsProbeGeneration
        fpsProbeExecutor.execute(Runnable { runFpsProbe(generation) })
    }

    /** 探针主体：跑在 [fpsProbeExecutor] 的单线程上，因此可以放心 sleep。 */
    private fun runFpsProbe(generation: Int) {
        // 等 AE / 对焦稳定，并凑满一个 1s 交付帧率统计窗口。
        sleepQuietly(2500L)
        if (!cameraSessionActive || generation != fpsProbeGeneration) return
        if (!deliveredFpsReady) {
            Log.i(TAG, "Camera FPS self-check skipped: no delivery window yet")
            return
        }
        val firstFps = cameraDeliveredFps
        if (firstFps >= MIN_CAPTURE_FPS) {
            Log.i(TAG, "Camera FPS self-check: %.1f FPS ✓ (target $TARGET_FPS)".format(firstFps))
            return
        }
        Log.w(TAG, "Camera FPS self-check: %.1f FPS, re-checking in 0.8s".format(firstFps))
        sleepQuietly(800L)
        if (!cameraSessionActive || generation != fpsProbeGeneration) return
        val secondFps = cameraDeliveredFps
        if (secondFps >= MIN_CAPTURE_FPS) {
            Log.i(TAG, "Camera recovered to %.1f FPS — keeping size".format(secondFps))
            return
        }

        // ---- 先判因，再决定要不要降档 --------------------------------------
        // HAL 实测的 SENSOR_FRAME_DURATION 是最权威的证据：
        //   ≈16.7ms → 传感器在 60fps 出帧，delivered 低是我们回调慢（降档/砍预览有效）
        //   ≈33.3ms → 传感器/AE 本身就是 30fps（换尺寸完全无用，别白重开两次相机）
        val appliedDurMs = appliedFrameDurationNs / 1_000_000.0
        if (appliedDurMs > 20.0) {
            Log.w(
                TAG,
                ("⚠ SENSOR_FRAME_DURATION is %.2f ms (%.1f fps) — the sensor/AE itself is not " +
                    "delivering $TARGET_FPS fps; a smaller capture size cannot help").format(
                    appliedDurMs, 1000.0 / appliedDurMs,
                ),
            )
            return
        }

        Log.w(
            TAG,
            ("⚠ Camera delivers only %.1f FPS at %s (callback avg %.2f ms, downshift %d/%d) — " +
                "switching to a smaller capture size").format(
                secondFps,
                currentCaptureSize?.let { "${it.width}x${it.height}" } ?: "?",
                cameraCallbackAvgMs,
                sizeDownshiftCount + 1,
                MAX_SIZE_DOWNSHIFTS,
            ),
        )
        // 如果连 AE 区间上限都到不了 60，换尺寸也没用：这台相机 / 这个镜头
        // 就是 30fps 硬件，直接给出结论，别再白重开两次相机。
        val range = appliedFpsRange ?: activeFpsRange
        if (range != null && range.upper < TARGET_FPS) {
            Log.w(
                TAG,
                "⚠ Camera AE ranges top out at ${range.upper}fps — this lens cannot " +
                    "deliver $TARGET_FPS fps at any size; the pipeline will follow the camera",
            )
            return
        }
        restartAtSmallerSize(generation)
    }

    /** 在候选尺寸里挑「至少小 25%」的下一档，优先平台明确支持 60fps 的最大尺寸。 */
    private fun pickDownshiftSize(current: Size): Size? {
        val currentPixels = current.width * current.height
        val smaller = captureSizeCandidates
            .filter { it.width * it.height < currentPixels }
            .filter { it.width * it.height <= (currentPixels * 3) / 4 }
            .sortedByDescending { it.width * it.height }
        if (smaller.isEmpty()) return null
        val map = streamConfigMap
        // 只在「更小且仍能跑满 60fps」时才降档：如果平台明确说更小尺寸也只能 30fps，
        // 降下去只会白丢清晰度、帧率一点不涨。
        val capable = smaller.firstOrNull { canDeliverTargetFps(map, it) }
        if (capable == null) {
            Log.w(TAG, "No smaller size is advertised as ${TARGET_FPS}fps-capable; not downshifting")
        }
        return capable
    }

    /**
     * 降档：改写采集尺寸覆盖值并在同一把锁内完成「停 → 开」，避免和手动
     * start / stop / 换镜头并发交错。
     */
    private fun restartAtSmallerSize(expectedGeneration: Int) {
        synchronized(captureLifecycleLock) {
            if (!cameraSessionActive || expectedGeneration != fpsProbeGeneration) {
                Log.i(TAG, "Adaptive downshift skipped: camera session changed")
            } else {
                val current = currentCaptureSize
                if (current == null) {
                    Log.i(TAG, "Adaptive downshift skipped: no capture size")
                } else {
                    val next = pickDownshiftSize(current)
                    if (next == null) {
                        Log.w(
                            TAG,
                            "⚠ No smaller 60fps-capable capture size — camera stays at its " +
                                "frame-rate ceiling; inference is not the bottleneck",
                        )
                    } else {
                        sizeDownshiftCount++
                        captureSizeOverride = next
                        keepCaptureSizeOverride = true
                        // 记住「这个相机上跑得动 60fps 的尺寸」：下次 start / 切回该镜头
                        // 直接用，不用再花 3.3 秒重新探测降档。
                        currentCameraKey?.let { resolvedCaptureSizes[it] = next }
                        Log.w(
                            TAG,
                            "Adaptive capture downshift #$sizeDownshiftCount: " +
                                "${current.width}x${current.height} → ${next.width}x${next.height}",
                        )
                        stopCameraInternal()
                        startCameraInternal()
                    }
                }
            }
        }
    }

    /** 简单 sleep，忽略中断（探针线程是 daemon，退出时直接结束即可）。 */
    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Process image in direct YUV mode - pass YUV planes to JNI
     * This avoids the YUV->RGB conversion on Kotlin side
     */
    private fun processYuvDirect(image: android.media.Image) {
        val srcWidth = image.width
        val srcHeight = image.height
        
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        
        val rotation = getRotationDegrees()
        
        // Determine output dimensions based on rotation
        val (rotatedWidth, rotatedHeight) = when (rotation) {
            90, 270 -> Pair(srcHeight, srcWidth)
            else -> Pair(srcWidth, srcHeight)
        }
        
        // Create YUV frame data
        val yuvData = YuvFrameData(
            yBuffer = yPlane.buffer,
            uBuffer = uPlane.buffer,
            vBuffer = vPlane.buffer,
            yRowStride = yPlane.rowStride,
            uvRowStride = uPlane.rowStride,
            uvPixelStride = uPlane.pixelStride,
            width = srcWidth,
            height = srcHeight,
            rotation = rotation,
            rotatedWidth = rotatedWidth,
            rotatedHeight = rotatedHeight
        )
        
        // Pass to callback for JNI processing
        yuvFrameCallback?.invoke(yuvData)
    }
    
    /**
     * Process image in legacy RGB mode - convert YUV to RGB then callback
     */
    private fun processYuvToRgb(image: android.media.Image) {
        val startTime = System.currentTimeMillis()
        val (rgbBuffer, outWidth, outHeight) = yuv420ToRgbRotated(image)
        val elapsed = System.currentTimeMillis() - startTime

        // Track conversion time
        yuvConversionCount++
        yuvConversionTotalMs += elapsed
        val now = System.currentTimeMillis()
        if (now - lastYuvLogTime >= 5000) {
            val avgMs = if (yuvConversionCount > 0) yuvConversionTotalMs.toFloat() / yuvConversionCount else 0f
            val maxFps = if (avgMs > 0) 1000f / avgMs else 0f
            Log.i(TAG, "YUV conversion: avg=%.1fms (max possible FPS: %.0f), count=$yuvConversionCount in 5s".format(avgMs, maxFps))
            yuvConversionCount = 0
            yuvConversionTotalMs = 0
            lastYuvLogTime = now
        }

        // Callback with rotated RGB frame data
        frameCallback?.invoke(rgbBuffer, outWidth, outHeight)
    }
    
    /**
     * Convert YUV to RGB for preview display only (used in YUV direct mode)
     * This is separate from main processing - just for debug visualization
     */
    private fun processYuvToRgbForPreview(image: android.media.Image) {
        val (rgbBuffer, outWidth, outHeight) = yuv420ToRgbRotated(image)
        // Call preview callback without performance tracking
        frameCallback?.invoke(rgbBuffer, outWidth, outHeight)
    }
    
    /**
     * Calculate the clockwise rotation needed to make the camera frame upright.
     *
     * The formula depends on the lens facing:
     *  - front:    (sensor + displayRotation) % 360  — the preview is mirrored,
     *              so the mirrored-rotation convention is used here;
     *  - back:     (sensor - displayRotation + 360) % 360;
     *  - external: sensor orientation only — a USB camera is physically fixed
     *              to its own body, display rotation does not apply;
     *  - unknown:  back-camera formula as the generic fallback.
     */
    private fun getRotationDegrees(): Int {
        @Suppress("DEPRECATION")
        val displayRotation = windowManager.defaultDisplay.rotation

        val deviceRotation = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        val rotation = when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> (sensorOrientation + deviceRotation) % 360
            CameraCharacteristics.LENS_FACING_EXTERNAL -> sensorOrientation % 360
            else -> (sensorOrientation - deviceRotation + 360) % 360
        }
        return rotation
    }
    
    /**
     * Convert YUV_420_888 image to RGB ByteBuffer with rotation
     * Uses native converter when available for better performance
     * Rotates the image to portrait orientation for face detection
     * Returns: Triple of (RGB buffer, output width, output height)
     */
    private fun yuv420ToRgbRotated(image: android.media.Image): Triple<ByteBuffer, Int, Int> {
        val srcWidth = image.width
        val srcHeight = image.height
        
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        
        val rotation = getRotationDegrees()
        
        // Determine output dimensions based on rotation
        val (dstWidth, dstHeight) = when (rotation) {
            90, 270 -> Pair(srcHeight, srcWidth)  // Swap dimensions
            else -> Pair(srcWidth, srcHeight)
        }
        
        // Reuse buffer if size matches
        val bufferSize = dstWidth * dstHeight * 3
        val rgbBuffer = reusableRgbBuffer?.let {
            if (it.capacity() == bufferSize) {
                it.clear()
                it
            } else null
        } ?: ByteBuffer.allocateDirect(bufferSize).also { reusableRgbBuffer = it }

        // Try MNN converter first if preferred (uses MNN ImageProcess)
        if (preferMNNConverter && useMNNConverter) {
            try {
                MNNYuvConverter.convertYuvToRgb(
                    yBuffer, uBuffer, vBuffer,
                    yRowStride, uvRowStride, uvPixelStride,
                    srcWidth, srcHeight, rotation,
                    rgbBuffer, dstWidth, dstHeight
                )
                rgbBuffer.rewind()
                return Triple(rgbBuffer, dstWidth, dstHeight)
            } catch (e: Exception) {
                Log.w(TAG, "MNN conversion failed, falling back to native: ${e.message}")
            }
        }

        // Try native converter (custom NEON optimized)
        if (useNativeConverter) {
            try {
                YuvConverter.convertYuvToRgb(
                    yBuffer, uBuffer, vBuffer,
                    yRowStride, uvRowStride, uvPixelStride,
                    srcWidth, srcHeight, rotation,
                    rgbBuffer, dstWidth, dstHeight
                )
                rgbBuffer.rewind()
                return Triple(rgbBuffer, dstWidth, dstHeight)
            } catch (e: Exception) {
                Log.w(TAG, "Native conversion failed, falling back to Kotlin: ${e.message}")
            }
        }

        // Fallback to Kotlin implementation
        return yuv420ToRgbRotatedKotlin(
            yBuffer, uBuffer, vBuffer,
            yRowStride, uvRowStride, uvPixelStride,
            srcWidth, srcHeight, rotation,
            rgbBuffer, dstWidth, dstHeight
        )
    }

    /**
     * Pure Kotlin YUV to RGB conversion with rotation (fallback)
     */
    private fun yuv420ToRgbRotatedKotlin(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        srcWidth: Int,
        srcHeight: Int,
        rotation: Int,
        rgbBuffer: ByteBuffer,
        dstWidth: Int,
        dstHeight: Int
    ): Triple<ByteBuffer, Int, Int> {

        // Convert with rotation
        // The key is to map destination (dstX, dstY) back to source (srcX, srcY)
        for (dstY in 0 until dstHeight) {
            for (dstX in 0 until dstWidth) {
                // Calculate source coordinates based on rotation
                // Rotation transforms: where does destination pixel come from in source?
                val srcX: Int
                val srcY: Int
                
                when (rotation) {
                    90 -> {
                        // 90° clockwise rotation
                        // dst(x, y) <- src(srcHeight-1-y, x)
                        // But since dimensions are swapped: dstWidth=srcHeight, dstHeight=srcWidth
                        // So: srcX = dstY, srcY = srcHeight - 1 - dstX = dstWidth - 1 - dstX
                        srcX = dstY
                        srcY = dstWidth - 1 - dstX
                    }
                    180 -> {
                        // 180° rotation
                        srcX = srcWidth - 1 - dstX
                        srcY = srcHeight - 1 - dstY
                    }
                    270 -> {
                        // 270° clockwise (= 90° counter-clockwise)
                        // dst(x, y) <- src(y, srcWidth-1-x)
                        // But since dimensions are swapped: dstWidth=srcHeight, dstHeight=srcWidth
                        // So: srcX = dstHeight - 1 - dstY = srcWidth - 1 - dstY, srcY = dstX
                        srcX = dstHeight - 1 - dstY
                        srcY = dstX
                    }
                    else -> {
                        // No rotation
                        srcX = dstX
                        srcY = dstY
                    }
                }
                
                // Bounds check
                if (srcX < 0 || srcX >= srcWidth || srcY < 0 || srcY >= srcHeight) {
                    // This should not happen if rotation logic is correct
                    Log.e(TAG, "Out of bounds: srcX=$srcX, srcY=$srcY for src ${srcWidth}x${srcHeight}")
                    continue
                }
                
                val yIndex = srcY * yRowStride + srcX
                val uvIndex = (srcY / 2) * uvRowStride + (srcX / 2) * uvPixelStride
                
                // Additional bounds check for buffer access
                if (yIndex >= yBuffer.limit() || uvIndex >= uBuffer.limit()) {
                    continue
                }
                
                val yValue = (yBuffer.get(yIndex).toInt() and 0xFF)
                val uValue = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val vValue = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128
                
                // YUV to RGB conversion
                var r = yValue + 1.370705f * vValue
                var g = yValue - 0.337633f * uValue - 0.698001f * vValue
                var b = yValue + 1.732446f * uValue
                
                // Clamp to [0, 255]
                r = r.coerceIn(0f, 255f)
                g = g.coerceIn(0f, 255f)
                b = b.coerceIn(0f, 255f)
                
                rgbBuffer.put(r.toInt().toByte())
                rgbBuffer.put(g.toInt().toByte())
                rgbBuffer.put(b.toInt().toByte())
            }
        }
        
        rgbBuffer.rewind()
        return Triple(rgbBuffer, dstWidth, dstHeight)
    }
}
