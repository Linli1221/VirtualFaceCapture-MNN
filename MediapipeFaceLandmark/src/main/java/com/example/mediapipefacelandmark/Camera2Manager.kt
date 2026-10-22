package com.example.mediapipefacelandmark

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.example.commondata.CameraDeviceInfo
import com.example.commondata.CameraLensFacing
import java.nio.ByteBuffer

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
 * multi-cameras and external (USB / capture-card) cameras. The caller can list
 * the available devices via [getAvailableCameras], pick one with
 * [setPreferredCamera], and the manager recalculates the sensor orientation,
 * field of view and rotation formula for that specific device.
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

    // Camera id the caller asked for ("null" = automatic front-first choice).
    @Volatile private var preferredCameraId: String? = null

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
    
    // Enable RGB preview callback even in YUV mode (for debug visualization)
    // When true, both yuvFrameCallback and frameCallback will be called
    var enablePreviewInYuvMode: Boolean = true
    
    // Camera sensor orientation (degrees) - will be set from camera characteristics
    private var sensorOrientation: Int = 0
    
    // Camera vertical FOV in degrees - calculated from camera characteristics
    var cameraVerticalFovDegrees: Float = 63.0f
        private set

    companion object {
        private const val TAG = "Camera2Manager"
        
        // Model input dimensions for reference
        // - Face Detection: 128x128
        // - Face Landmarks: 192x192 or 256x256
        private const val MODEL_INPUT_SIZE = 256
        
        // Target resolution constraints
        private const val MIN_SIZE = 192       // Minimum dimension to ensure quality
        private const val MAX_SIZE = 640       // Maximum dimension to limit computation
        private const val IDEAL_SIZE = 480     // Ideal dimension for balance
        
        // Aspect ratio preference (1.0 = perfect square)
        private const val MAX_ASPECT_RATIO = 1.5f  // Allow up to 3:2 ratio
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
     * List every camera reported by the platform.
     *
     * The list covers front / back cameras, logical multi-cameras and external
     * cameras such as USB webcams or capture cards. Entries are sorted front →
     * back → external so the default (front-first) ordering is predictable.
     *
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

        val raw = mutableListOf<CameraDeviceInfo>()
        for (id in ids) {
            try {
                val characteristics = manager.getCameraCharacteristics(id)
                raw += CameraDeviceInfo(
                    id = id,
                    lensFacing = mapLensFacing(characteristics.get(CameraCharacteristics.LENS_FACING)),
                    // Filled in below once we know how many cameras share a facing.
                    label = "",
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                    verticalFovDegrees = calculateFovDegrees(characteristics),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping camera $id: ${e.message}")
            }
        }

        val facingCounts = raw.groupingBy { it.lensFacing }.eachCount()
        val facingSeen = mutableMapOf<CameraLensFacing, Int>()
        val labelled = raw.map { info ->
            val index = (facingSeen[info.lensFacing] ?: 0) + 1
            facingSeen[info.lensFacing] = index
            val base = when (info.lensFacing) {
                CameraLensFacing.FRONT -> "Front"
                CameraLensFacing.BACK -> "Back"
                CameraLensFacing.EXTERNAL -> "External"
                CameraLensFacing.UNKNOWN -> "Camera"
            }
            info.copy(
                label = if ((facingCounts[info.lensFacing] ?: 0) > 1) "$base $index" else base,
            )
        }

        return labelled.sortedWith(
            compareBy({ facingPriority(it.lensFacing) }, { it.id }),
        )
    }

    /**
     * Choose the camera used by the next [startCamera] call.
     *
     * @param cameraId a [CameraDeviceInfo.id] from [getAvailableCameras], or
     *                 `null` to fall back to the automatic front-first choice.
     *                 The id is validated only when the camera is opened, so
     *                 stale ids from a previous device simply fall back to auto.
     */
    fun setPreferredCamera(cameraId: String?) {
        preferredCameraId = cameraId
        Log.i(TAG, "Preferred camera: ${cameraId ?: "auto (front-first)"}")
    }

    /** Camera id requested via [setPreferredCamera], or null for automatic choice. */
    fun getPreferredCameraId(): String? = preferredCameraId

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

        // Guard against double-start (e.g. rapid Start taps or a switch while
        // the previous session is still tearing down).
        if (backgroundThread != null || cameraDevice != null) {
            Log.w(TAG, "Camera already started, releasing the previous session first")
            stopCamera()
        }

        startBackgroundThread()
        openCamera()
    }
    
    /**
     * Stop camera capture and release resources
     */
    fun stopCamera() {
        captureSession?.close()
        captureSession = null
        
        cameraDevice?.close()
        cameraDevice = null
        
        imageReader?.close()
        imageReader = null
        
        stopBackgroundThread()
    }
    
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
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

            val characteristics = manager.getCameraCharacteristics(info.id)
            cameraCharacteristics = characteristics

            // Remember the device we actually opened so callers can react
            // synchronously (preview mirroring, debug HUD, camera picker).
            selectedCameraInfo = info
            lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_FRONT
            sensorOrientation = info.sensorOrientation
            cameraVerticalFovDegrees = info.verticalFovDegrees

            Log.i(
                TAG,
                "Camera selected: ${info.label} (id=${info.id}), sensor=$sensorOrientation°, " +
                    "fov=$cameraVerticalFovDegrees°"
            )

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // Choose optimal preview size for this camera
            val previewSize = chooseOptimalSize(
                map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            )

            Log.i(TAG, "Selected preview size: ${previewSize.width}x${previewSize.height}")

            // Create ImageReader for frame capture
            // Use 4 buffers to prevent frame drops when processing takes longer
            imageReader = ImageReader.newInstance(
                previewSize.width,
                previewSize.height,
                ImageFormat.YUV_420_888,
                2  // Increased from 2 to prevent frame drops
            ).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }

            manager.openCamera(info.id, stateCallback, backgroundHandler)

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
     * external. A stale preference (camera unplugged / different device) is
     * ignored with a warning.
     */
    private fun chooseCamera(cameras: List<CameraDeviceInfo>): CameraDeviceInfo? {
        if (cameras.isEmpty()) return null

        val preferred = preferredCameraId
        if (preferred != null) {
            val match = cameras.firstOrNull { it.id == preferred }
            if (match != null) return match
            Log.w(TAG, "Preferred camera $preferred is unavailable, falling back to auto")
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
     * Choose optimal camera resolution for face detection
     * 
     * Selection criteria (in order of priority):
     * 1. Aspect ratio close to 1:1 (square-like)
     * 2. Size close to model input dimension (256x256)
     * 3. Not too large (avoid wasting computation)
     * 4. Not too small (ensure detection quality)
     */
    private fun chooseOptimalSize(choices: Array<Size>): Size {
        if (choices.isEmpty()) {
            Log.w(TAG, "No available sizes, using default 640x480")
            return Size(640, 480)
        }
        
        Log.d(TAG, "Available camera resolutions:")
        choices.forEach { size ->
            val aspectRatio = size.width.toFloat() / size.height.toFloat()
            Log.d(TAG, "  ${size.width}x${size.height} (aspect: %.2f)".format(aspectRatio))
        }
        
        // Filter and score each resolution
        data class ScoredSize(val size: Size, val score: Float, val details: String)
        
        val scoredSizes = choices.mapNotNull { size ->
            val width = size.width
            val height = size.height
            val minDim = minOf(width, height)
            val maxDim = maxOf(width, height)
            
            // Skip sizes that are too small or too large
            if (minDim < MIN_SIZE || maxDim > MAX_SIZE * 2) {
                return@mapNotNull null
            }
            
            // Calculate aspect ratio (always >= 1.0)
            val aspectRatio = maxDim.toFloat() / minDim.toFloat()
            
            // Skip sizes with extreme aspect ratios
            if (aspectRatio > MAX_ASPECT_RATIO * 1.5f) {
                return@mapNotNull null
            }
            
            // Score calculation (lower is better)
            var score = 0f
            val details = StringBuilder()
            
            // 1. Aspect ratio penalty (prefer square)
            // Perfect square = 0 penalty, 4:3 = small penalty, 16:9 = large penalty
            val aspectPenalty = (aspectRatio - 1.0f) * 100f
            score += aspectPenalty
            details.append("aspect:%.1f ".format(aspectPenalty))
            
            // 2. Size difference from ideal
            // Prefer sizes where the smaller dimension is close to IDEAL_SIZE
            val sizeDiff = kotlin.math.abs(minDim - IDEAL_SIZE).toFloat()
            val sizePenalty = sizeDiff * 0.5f
            score += sizePenalty
            details.append("size:%.1f ".format(sizePenalty))
            
            // 3. Bonus for resolutions that divide evenly into model input
            // This can help with preprocessing efficiency
            val modelFit = if (minDim % MODEL_INPUT_SIZE == 0 || MODEL_INPUT_SIZE % minDim == 0) {
                -20f  // Bonus
            } else {
                0f
            }
            score += modelFit
            if (modelFit < 0) details.append("modelFit:bonus ")
            
            // 4. Slight preference for common resolutions (often better optimized)
            val commonBonus = when {
                (width == 480 && height == 480) -> -30f  // Perfect square!
                (width == 640 && height == 480) || (width == 480 && height == 640) -> -10f
                (width == 320 && height == 240) || (width == 240 && height == 320) -> -5f
                else -> 0f
            }
            score += commonBonus
            if (commonBonus < 0) details.append("common:bonus ")
            
            ScoredSize(size, score, details.toString())
        }
        
        if (scoredSizes.isEmpty()) {
            // Fallback: just pick the smallest resolution that's >= MIN_SIZE
            val fallback = choices
                .filter { minOf(it.width, it.height) >= MIN_SIZE }
                .minByOrNull { it.width * it.height }
                ?: choices.first()
            Log.w(TAG, "No ideal size found, using fallback: ${fallback.width}x${fallback.height}")
            return fallback
        }
        
        // Sort by score (ascending) and pick the best
        val sorted = scoredSizes.sortedBy { it.score }
        
        Log.d(TAG, "Resolution scores (lower is better):")
        sorted.take(5).forEach { scored ->
            Log.d(TAG, "  ${scored.size.width}x${scored.size.height}: score=%.1f (%s)".format(
                scored.score, scored.details))
        }
        
        val best = sorted.first().size
        Log.i(TAG, "✓ Selected optimal resolution: ${best.width}x${best.height}")
        
        return best
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

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return
        
        try {
            val surface = reader.surface
            
            val captureRequestBuilder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(surface)
                applyCaptureRequestTuning(this)
            }
            
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(TAG, "Capture session configured")
                        captureSession = session
                        
                        try {
                            // Start continuous capture
                            session.setRepeatingRequest(
                                captureRequestBuilder.build(),
                                null,
                                backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Error starting capture", e)
                        }
                    }
                    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                },
                backgroundHandler
            )
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error creating capture session", e)
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
        if (fpsRanges == null || fpsRanges.isEmpty()) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30, 60))
        } else {
            // Prefer the highest upper bound while still starting at <= 30 FPS
            // (30-60 on phones, 30-30 on cameras that cap at 30, etc.).
            val chosen = fpsRanges.filter { it.lower <= 30 }.maxByOrNull { it.upper }
                ?: fpsRanges.maxByOrNull { it.upper }
            chosen?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
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
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        
        try {
            if (useDirectYuvMode && yuvFrameCallback != null) {
                // Direct YUV mode - pass YUV data directly to JNI for zero-copy processing
                processYuvDirect(image)
                
                // Also generate RGB preview if enabled (for debug visualization)
                if (enablePreviewInYuvMode && frameCallback != null) {
                    processYuvToRgbForPreview(image)
                }
            } else {
                // Legacy RGB mode - convert YUV to RGB first
                processYuvToRgb(image)
            }
        } finally {
            image.close()
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
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
