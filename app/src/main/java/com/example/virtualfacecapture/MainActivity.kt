package com.example.virtualfacecapture

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.commondata.ARKitBlendShapes
import com.example.commondata.CameraDeviceInfo
import com.example.commondata.HeadPoseData
import com.example.mediapipefacelandmark.MnnBackend
import com.example.virtualfacecapture.tracking.TrackingOutputManager
import com.example.virtualfacecapture.ui.theme.VirtualFaceCaptureTheme
import com.facecapture.sdk.PipelineFaceTracker
import com.live2d.facecapture.SimpleEyeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val CAMERA_PREFS_NAME = "face_capture_prefs"
private const val KEY_PREFERRED_CAMERA_ID = "preferred_camera_id"

/**
 * 调试面板数据的合并快照。
 *
 * 面捕回调每秒触发 30~60 次，fps / BlendShape / 头部姿态原本对应三个独立的
 * Compose state，每来一次回调就要写三次、让 DebugOverlay 整棵子树重组一次。
 * 合并成一个不可变快照后，一次写入就能带齐三个字段。
 */
private data class DebugSnapshot(
    val fps: Float = 0f,
    val blendShapes: ARKitBlendShapes? = null,
    val headPose: HeadPoseData = HeadPoseData(),
)

/** 调试面板的刷新间隔：10Hz 已经足够看清数值，重组次数降到原来的 1/6 以下。 */
private const val DEBUG_SNAPSHOT_INTERVAL_MS = 100L

/**
 * 面捕回调 → Compose state 的合批器。
 *
 * 面捕回调和 UI 不在同一个线程（PipelineFaceTracker 在 Dispatchers.Default 上触发），
 * 这里用普通字段累积「最新值」（加锁保证跨线程可见性），最多每 [intervalNanos]
 * 毫秒才向 Compose state 写一次；[enabled] 为 false（调试面板收起）时一次都不写。
 */
private class DebugSnapshotBatcher(
    private val intervalNanos: Long,
    private val sink: (DebugSnapshot) -> Unit,
) {
    private val lock = Any()

    /** 调试面板是否可见；不可见时完全不写 Compose state。 */
    @Volatile
    var enabled: Boolean = false

    private var fps: Float = 0f
    private var blendShapes: ARKitBlendShapes? = null
    private var headPose: HeadPoseData = HeadPoseData()
    private var lastPublishNanos: Long = 0L

    fun updateFps(value: Float) {
        if (!enabled) return
        synchronized(lock) { fps = value }
        publishIfDue()
    }

    fun updateBlendShapes(value: ARKitBlendShapes) {
        if (!enabled) return
        synchronized(lock) { blendShapes = value }
        publishIfDue()
    }

    fun updateHeadPose(value: HeadPoseData) {
        if (!enabled) return
        synchronized(lock) { headPose = value }
        publishIfDue()
    }

    private fun publishIfDue() {
        val now = System.nanoTime()
        val snapshot: DebugSnapshot? = synchronized(lock) {
            if (now - lastPublishNanos < intervalNanos) {
                null
            } else {
                lastPublishNanos = now
                DebugSnapshot(fps = fps, blendShapes = blendShapes, headPose = headPose)
            }
        }
        if (snapshot != null) {
            // 在锁外写 Compose state，避免把快照锁和 Compose 的全局锁叠在一起
            sink(snapshot)
        }
    }
}

/**
 * 面捕采集端主界面。
 *
 * 这个 app 现在只做两件事：相机面捕 + 把每帧追踪数据通过局域网 WebSocket 推给 PC。
 * 手机端**不再渲染 Live2D**、**不再编码视频帧**，界面就是「相机画面 + 调试 HUD + 控制面板」。
 */
class MainActivity : ComponentActivity() {

    private lateinit var faceTracker: PipelineFaceTracker
    private lateinit var trackingOutput: TrackingOutputManager
    private var isTracking = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("MainActivity", "✓ Camera permission granted")
            initializeFaceTracker()
        } else {
            Log.e("MainActivity", "✗ Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i("MainActivity", "onCreate: Initializing face capture (tracking-only build)...")

        // 面捕管线（MNN 推理 + MediaPipe 拓扑 + OpenSeeFace 后处理）
        faceTracker = PipelineFaceTracker(this)

        // 恢复上次选择的摄像头（如果该 id 在本次开机后不存在，相机会自动回退到默认前置）
        val cameraPrefs = getSharedPreferences(CAMERA_PREFS_NAME, Context.MODE_PRIVATE)
        faceTracker.setPreferredCamera(cameraPrefs.getString(KEY_PREFERRED_CAMERA_ID, null))

        // 网络输出（追踪数据）：默认关闭，用户可在控制面板里开启。
        // PC 端发 command:recalibrate 时回到主线程触发与 UI 相同的「重置基线」逻辑。
        trackingOutput = TrackingOutputManager(this) {
            runOnUiThread { faceTracker.resetAdaptiveBaseline() }
        }

        // 每处理完一帧就把追踪数据喂给网络输出；服务未开启时 submitFrame 只做一次判空即返回。
        faceTracker.onFaceCaptureResult = { frame ->
            trackingOutput.submitFrame(
                blendShapes = frame.blendShapes,
                headPose = frame.relativeHeadPose,
                presenceScore = frame.presenceScore,
                fps = frame.fps,
                calibrating = frame.isCalibrating,
            )
        }

        if (checkCameraPermission()) {
            initializeFaceTracker()
        } else {
            requestCameraPermission()
        }

        setContent {
            VirtualFaceCaptureTheme {
                FaceCaptureScreen(
                    faceTracker = faceTracker,
                    trackingOutput = trackingOutput,
                    isTracking = isTracking,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从后台回来：之前是「面捕中」就重新开相机（onPause 里释放过）
        if (isTracking.value && !faceTracker.isTracking()) {
            faceTracker.start()
        }
        trackingOutput.setTrackingActive(isTracking.value && faceTracker.isTracking())
    }

    override fun onPause() {
        super.onPause()
        if (faceTracker.isTracking()) {
            faceTracker.stop()
        }
        // 相机已释放，/status 的 tracking 要如实变成 false（PC 端据此判断「手机在不在面捕」）
        trackingOutput.setTrackingActive(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        trackingOutput.dispose()
        faceTracker.release()
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun initializeFaceTracker() {
        if (!faceTracker.initialize()) {
            Log.e("MainActivity", "Failed to initialize face tracker")
        }
    }
}

@Composable
fun FaceCaptureScreen(
    faceTracker: PipelineFaceTracker,
    trackingOutput: TrackingOutputManager,
    isTracking: MutableState<Boolean>,
) {
    // 调试面板数据：fps / BlendShape / 姿态合并为一个快照，且限制在 ≤10Hz（见 DebugSnapshotBatcher）
    val debugSnapshotState = remember { mutableStateOf(DebugSnapshot()) }
    var showDebug by remember { mutableStateOf(true) }
    var showTrackingDialog by remember { mutableStateOf(false) }
    var showCameraSelectorDialog by remember { mutableStateOf(false) }
    var showControlPanel by rememberSaveable { mutableStateOf(true) }

    // 相机预览叠加层开关（关键点 / 检测框）
    var drawLandmarks by rememberSaveable { mutableStateOf(true) }
    var drawDetectionBox by rememberSaveable { mutableStateOf(true) }

    var availableCameras by remember { mutableStateOf<List<CameraDeviceInfo>>(emptyList()) }
    var currentCamera by remember { mutableStateOf<CameraDeviceInfo?>(null) }
    var isCalibrating by remember { mutableStateOf(false) }
    var blinkCurveEnabled by remember { mutableStateOf(faceTracker.blinkCurveProcessor.isEnabled) }
    var blinkSpeedScale by remember { mutableStateOf(faceTracker.blinkCurveProcessor.blinkSpeedScale) }
    // MNN 推理后端（Vulkan ⇄ CPU）：让用户在真机上实测哪一个能稳住 60 帧
    var mnnBackend by remember { mutableStateOf(faceTracker.getMnnBackend()) }
    var backendSwitching by remember { mutableStateOf(false) }
    // CPU + GPU 双通道关键点推理：关掉就退回单路 Vulkan（省电、降发热）
    var dualChannel by remember { mutableStateOf(faceTracker.isDualChannelActive()) }

    var cameraPreviewView by remember { mutableStateOf<CameraPreviewView?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 合批器：回调线程只更新普通字段，最多 10Hz 写一次 Compose state
    val debugBatcher = remember {
        DebugSnapshotBatcher(
            intervalNanos = DEBUG_SNAPSHOT_INTERVAL_MS * 1_000_000L,
            sink = { snapshot -> debugSnapshotState.value = snapshot },
        )
    }

    // 调试面板收起时（showDebug = false）合批器完全不写 state
    SideEffect {
        debugBatcher.enabled = showDebug
    }

    // Set up callbacks
    LaunchedEffect(Unit) {
        faceTracker.onFpsUpdate = { newFps ->
            debugBatcher.updateFps(newFps)
        }

        faceTracker.onBlendShapesUpdate = { blendShapes ->
            debugBatcher.updateBlendShapes(blendShapes)
        }

        faceTracker.onHeadPoseUpdate = { headPose ->
            debugBatcher.updateHeadPose(headPose)
        }

        faceTracker.onVisualizationUpdate = { data ->
            cameraPreviewView?.updateVisualizationData(data)
        }

        faceTracker.onCameraFrameUpdate = { frameData, width, height ->
            cameraPreviewView?.updateCameraFrame(frameData, width, height)
        }

        faceTracker.onCalibrationStatusUpdate = { calibrating ->
            isCalibrating = calibrating
        }

        faceTracker.onCameraChanged = { camera ->
            currentCamera = camera
            // 前置预览习惯性镜像，后置 / 外接相机保持真实方向
            cameraPreviewView?.mirrorHorizontal = camera?.isFrontFacing ?: true
        }

        // 摄像头列表 + 初始选中项（用于在 Start 之前就显示正确的标签/镜像）
        availableCameras = faceTracker.getAvailableCameras()
        currentCamera = faceTracker.getCurrentCamera()
            ?: availableCameras.firstOrNull { it.id == faceTracker.getPreferredCameraId() }
            ?: availableCameras.firstOrNull { it.isFrontFacing }
            ?: availableCameras.firstOrNull()
    }

    // 相机预览帧门控：本 app 只有相机预览一个画面，进前台常开、离开时关掉，
    // 避免相机线程白做整帧 YUV→RGB（每帧几十万次像素运算，推理管线走 YUV 直通用不到）。
    DisposableEffect(Unit) {
        faceTracker.setCameraPreviewFramesEnabled(true)
        onDispose {
            faceTracker.setCameraPreviewFramesEnabled(false)
        }
    }

    // 叠加层开关同步给预览 View
    LaunchedEffect(drawLandmarks, drawDetectionBox) {
        cameraPreviewView?.apply {
            this.drawLandmarks = drawLandmarks
            this.drawKeyPoints = drawLandmarks
            this.drawDetectionBox = drawDetectionBox
        }
    }

    // 面捕管线状态同步给追踪服务（/status 的 tracking 字段）
    val tracking: Boolean = isTracking.value
    LaunchedEffect(tracking) {
        trackingOutput.setTrackingActive(tracking)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 主画面：相机预览 + 关键点 / 检测框叠加（手机端不再渲染 Live2D）
        AndroidView(
            factory = { ctx ->
                CameraPreviewView(ctx).also {
                    cameraPreviewView = it
                    it.drawLandmarks = drawLandmarks
                    it.drawKeyPoints = drawLandmarks
                    it.drawDetectionBox = drawDetectionBox
                    it.mirrorHorizontal = currentCamera?.isFrontFacing ?: true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 校准覆盖层 - 在追踪开始时且正在校准期间显示
        CalibrationOverlay(
            isCalibrating = isTracking.value && isCalibrating,
            modifier = Modifier.fillMaxSize(),
            text = "面捕正在校准中..."
        )

        // Debug overlay
        if (showDebug) {
            DebugOverlayHost(
                snapshot = debugSnapshotState,
                isTracking = isTracking.value,
                currentCameraName = currentCamera?.label ?: "自动",
                trackingEnabled = trackingOutput.isRunning.value,
                trackingClients = trackingOutput.clientCount.value,
                faceTracker = faceTracker,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )
        }

        // 网络输出（追踪数据）对话框
        if (showTrackingDialog) {
            TrackingOutputDialog(
                manager = trackingOutput,
                onDismiss = { showTrackingDialog = false }
            )
        }

        // 多摄像头选择对话框
        if (showCameraSelectorDialog) {
            CameraSelectorDialog(
                cameras = availableCameras,
                currentCameraId = currentCamera?.id ?: faceTracker.getPreferredCameraId(),
                onCameraSelected = { camera ->
                    showCameraSelectorDialog = false
                    scope.launch {
                        val switched = faceTracker.switchCamera(camera.id)
                        if (switched) {
                            currentCamera = camera
                            context.getSharedPreferences(CAMERA_PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString(KEY_PREFERRED_CAMERA_ID, camera.id)
                                .apply()
                            // 相机几何 / FOV 变了，重做基线校准，避免姿态与表情残留旧基线
                            faceTracker.resetAdaptiveBaseline()
                            Log.i(
                                "FaceCapturePipeline",
                                "Camera switched to " + camera.label + " (" + camera.id + ")"
                            )
                        } else {
                            Log.e("FaceCapturePipeline", "Failed to switch camera to " + camera.id)
                            availableCameras = faceTracker.getAvailableCameras()
                        }
                    }
                },
                onDismiss = { showCameraSelectorDialog = false }
            )
        }

        // Control panel - 底部可折叠面板（向下隐藏）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // 折叠/展开把手 - 始终可见，位于面板顶部中央
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp
                ),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .width(72.dp)
                    .height(28.dp)
                    .clickable { showControlPanel = !showControlPanel }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (showControlPanel) "⌄" else "⌃",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 控制面板内容 - 向下滑动隐藏
            AnimatedVisibility(
                visible = showControlPanel,
                enter = slideInVertically(
                    animationSpec = tween(durationMillis = 250),
                    initialOffsetY = { it }
                ) + fadeIn(animationSpec = tween(200)),
                exit = slideOutVertically(
                    animationSpec = tween(durationMillis = 250),
                    targetOffsetY = { it }
                ) + fadeOut(animationSpec = tween(200))
            ) {
                ControlPanel(
                    isTracking = isTracking.value,
                    showDebug = showDebug,
                    currentCameraName = currentCamera?.label ?: "自动",
                    trackingEnabled = trackingOutput.isRunning.value,
                    trackingClients = trackingOutput.clientCount.value,
                    blinkCurveEnabled = blinkCurveEnabled,
                    blinkSpeedScale = blinkSpeedScale,
                    drawLandmarks = drawLandmarks,
                    drawDetectionBox = drawDetectionBox,
                    mnnBackend = mnnBackend,
                    backendSwitching = backendSwitching,
                    dualChannel = dualChannel,
                    onToggleTracking = {
                        scope.launch {
                            if (isTracking.value) {
                                Log.i("FaceCapturePipeline", "Stopping face tracking...")
                                faceTracker.stop()
                                isTracking.value = false
                                trackingOutput.setTrackingActive(false)
                            } else {
                                Log.i("FaceCapturePipeline", "Starting face tracking...")
                                faceTracker.start()
                                isTracking.value = true
                                trackingOutput.setTrackingActive(true)
                                // 双通道是否真的跑起来要等 start() 之后才知道
                                dualChannel = faceTracker.isDualChannelActive()
                            }
                        }
                    },
                    onResetBaseline = {
                        Log.i("FaceCapturePipeline", "Resetting adaptive baseline...")
                        faceTracker.resetAdaptiveBaseline()
                    },
                    onToggleDebug = {
                        showDebug = !showDebug
                        Log.d("FaceCapturePipeline", "Debug overlay: " + if (showDebug) "shown" else "hidden")
                    },
                    onOpenCameraSelector = {
                        availableCameras = faceTracker.getAvailableCameras()
                        showCameraSelectorDialog = true
                    },
                    onOpenTrackingOutput = {
                        showTrackingDialog = true
                    },
                    onToggleBlinkCurve = {
                        blinkCurveEnabled = !blinkCurveEnabled
                        faceTracker.blinkCurveProcessor.isEnabled = blinkCurveEnabled
                        if (blinkCurveEnabled) {
                            faceTracker.blinkCurveProcessor.reset()
                        }
                        Log.i("FaceCapturePipeline", "Blink Curve Processor: " + if (blinkCurveEnabled) "ENABLED" else "DISABLED")
                    },
                    onBlinkSpeedChange = { newSpeed ->
                        blinkSpeedScale = newSpeed
                        faceTracker.blinkCurveProcessor.blinkSpeedScale = newSpeed
                        Log.d("FaceCapturePipeline", "Blink speed scale: " + newSpeed)
                    },
                    onToggleLandmarks = {
                        drawLandmarks = !drawLandmarks
                    },
                    onToggleDetectionBox = {
                        drawDetectionBox = !drawDetectionBox
                    },
                    onToggleMnnBackend = {
                        // 后端切换 = 销毁 → 重建推理 session，耗时可达数百毫秒，
                        // 必须放到 IO 线程，否则会把主线程卡住（掉帧/ANR）。
                        if (!backendSwitching) {
                            val target = if (mnnBackend == MnnBackend.VULKAN) {
                                MnnBackend.CPU
                            } else {
                                MnnBackend.VULKAN
                            }
                            backendSwitching = true
                            Log.i("FaceCapturePipeline", "Switching MNN backend to " + target + " ...")
                            scope.launch(Dispatchers.IO) {
                                val ok = try {
                                    faceTracker.setMnnBackend(target)
                                } catch (t: Throwable) {
                                    // 老 .so 没有 nativeSetBackend 时可能抛 UnsatisfiedLinkError，
                                    // 任何异常都不能把 UI 卡在「切换中」
                                    Log.e("FaceCapturePipeline", "Switching MNN backend failed", t)
                                    false
                                }
                                // 以 SDK 的真实状态为准：失败时它仍停在原后端
                                mnnBackend = faceTracker.getMnnBackend()
                                backendSwitching = false
                                Log.i(
                                    "FaceCapturePipeline",
                                    "MNN backend switch (ok=" + ok + ") -> " + faceTracker.getMnnBackend()
                                )
                            }
                        }
                    },
                    onToggleDualChannel = {
                        // 双通道的开关要在 start() 里拉起/收起关键点线程，属于管线重启，
                        // 和切后端一样放 IO 线程，避免主线程卡顿。
                        val target = !dualChannel
                        Log.i("FaceCapturePipeline", "Switching dual channel to " + target + " ...")
                        scope.launch(Dispatchers.IO) {
                            try {
                                faceTracker.setDualChannelEnabled(target)
                            } catch (t: Throwable) {
                                Log.e("FaceCapturePipeline", "Switching dual channel failed", t)
                            }
                            // 以 SDK 真实状态为准：副引擎不可用时会自动停在单通道
                            dualChannel = faceTracker.isDualChannelActive()
                            Log.i("FaceCapturePipeline", "Dual channel now " + dualChannel)
                        }
                    }
                )
            }
        }
    }
}

/**
 * 把 [DebugSnapshot] 的读取关在一个独立的重组作用域里。
 *
 * 如果直接在 [FaceCaptureScreen] 的函数体里读快照，每次数据更新都会让整个屏幕
 * （包括相机预览的 AndroidView、控制面板）重新走一遍组合；放到这里以后只有
 * 这一小块会随面捕数据（≤10Hz）重组。
 */
@Composable
private fun DebugOverlayHost(
    snapshot: State<DebugSnapshot>,
    isTracking: Boolean,
    currentCameraName: String,
    trackingEnabled: Boolean,
    trackingClients: Int,
    faceTracker: PipelineFaceTracker,
    modifier: Modifier = Modifier
) {
    val value = snapshot.value
    DebugOverlay(
        fps = value.fps,
        isTracking = isTracking,
        currentCameraName = currentCameraName,
        trackingEnabled = trackingEnabled,
        trackingClients = trackingClients,
        blendShapes = value.blendShapes,
        headPose = value.headPose,
        modifier = modifier,
        faceTracker = faceTracker
    )
}

@Composable
fun DebugOverlay(
    fps: Float,
    isTracking: Boolean,
    currentCameraName: String,
    trackingEnabled: Boolean,
    trackingClients: Int,
    blendShapes: ARKitBlendShapes?,
    headPose: HeadPoseData,
    modifier: Modifier = Modifier,
    faceTracker: PipelineFaceTracker
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // FPS / 状态 + 性能曲线
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "FPS: %.1f".format(fps),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )

                    // 帧耗时 = 端到端每帧处理时间（含推理 + 后处理），与 FPS 互为倒数
                    Text(
                        text = "帧耗时: %.1f ms".format(if (fps > 0f) 1000f / fps else 0f),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )

                    Text(
                        text = if (isTracking) "面捕: 运行中" else "面捕: 已停止",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isTracking) Color.Green else Color.Red
                    )

                    Text(
                        text = "摄像头: " + currentCameraName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Cyan
                    )

                    Text(
                        text = if (trackingEnabled) "网络输出: ON (" + trackingClients + " 客户端)" else "网络输出: OFF",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (trackingEnabled) Color(0xFF4CAF50) else Color.Gray
                    )
                }

                PerformanceChartPanel(
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            HorizontalDivider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = "头部姿态（相对基线）:",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Yellow
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Pitch: %+6.1f°".format(headPose.pitch),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.width(100.dp)
                )
                PoseIndicator(
                    value = headPose.pitch,
                    maxValue = 30f,
                    color = Color.Red
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Yaw:   %+6.1f°".format(headPose.yaw),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.width(100.dp)
                )
                PoseIndicator(
                    value = headPose.yaw,
                    maxValue = 45f,
                    color = Color.Green
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Roll:  %+6.1f°".format(headPose.roll),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.width(100.dp)
                )
                PoseIndicator(
                    value = headPose.roll,
                    maxValue = 30f,
                    color = Color.Blue
                )
            }

            blendShapes?.let { bs ->
                HorizontalDivider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "ARKit-52:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )

                Text(
                    text = "Eye L: %.2f".format(bs.eyeBlinkLeft),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                Text(
                    text = "Eye R: %.2f".format(bs.eyeBlinkRight),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                Text(
                    text = "Mouth: %.2f".format(bs.jawOpen),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                Text(
                    text = "Smile: %.2f".format((bs.mouthSmileLeft + bs.mouthSmileRight) * 0.5f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }

            // Eye Sensitivity Control Section
            HorizontalDivider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            EyeSensitivityControl(faceTracker)
        }
    }
}

/**
 * Eye Sensitivity Control Slider
 * 允许用户实时调整眼睛检测的灵敏度
 */
@Composable
fun EyeSensitivityControl(faceTracker: PipelineFaceTracker) {
    var sensitivity by remember { mutableFloatStateOf(SimpleEyeExtractor.eyeSensitivity) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "眼睛灵敏度:",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Yellow
            )
            Text(
                text = "%.1fx".format(sensitivity),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
        }

        Slider(
            value = sensitivity,
            onValueChange = { newValue ->
                sensitivity = newValue
                SimpleEyeExtractor.eyeSensitivity = newValue
            },
            valueRange = 0.5f..2.0f,
            steps = 10,
            colors = SliderDefaults.colors(
                thumbColor = Color.Cyan,
                activeTrackColor = Color.Cyan.copy(alpha = 0.8f),
                inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // 显示当前有效的 EAR 阈值
        val thresholds = faceTracker.faceCapturePipeline.extractor.simpleEyeExtractor.getEffectiveThresholds()
        Text(
            text = "EAR 阈值: closed=%.2f, open=%.2f".format(thresholds.first, thresholds.second),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )

        Text(
            text = when {
                sensitivity < 0.8f -> "低: 需要较大眼动"
                sensitivity > 1.3f -> "高: 细微动作也会响应"
                else -> "正常: 平衡检测"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                sensitivity < 0.8f -> Color.Blue
                sensitivity > 1.3f -> Color.Red
                else -> Color.Green
            }
        )
    }
}

/**
 * Visual indicator for pose values
 * Shows a horizontal bar representing the pose angle
 */
@Composable
fun PoseIndicator(
    value: Float,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val normalizedValue = (value / maxValue).coerceIn(-1f, 1f)
    val barWidth = 80.dp

    Box(
        modifier = modifier
            .width(barWidth)
            .height(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Gray.copy(alpha = 0.3f))
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .align(Alignment.Center)
                .background(Color.White.copy(alpha = 0.5f))
        )

        val indicatorWidth = (kotlin.math.abs(normalizedValue) * barWidth.value / 2).dp
        val indicatorOffset = if (normalizedValue >= 0) {
            barWidth / 2
        } else {
            barWidth / 2 - indicatorWidth
        }

        Box(
            modifier = Modifier
                .width(indicatorWidth)
                .fillMaxHeight()
                .offset(x = indicatorOffset)
                .background(color.copy(alpha = 0.8f))
        )
    }
}

/**
 * 底部控制面板（中文 UI，Compose Material3）。
 *
 * 只保留面捕采集端真正需要的开关：开始/停止、重置基线、摄像头、网络输出、
 * 眨眼曲线、调试开关、叠加层开关、MNN 推理后端。
 */
@Composable
fun ControlPanel(
    isTracking: Boolean,
    showDebug: Boolean,
    currentCameraName: String,
    trackingEnabled: Boolean,
    trackingClients: Int,
    blinkCurveEnabled: Boolean,
    blinkSpeedScale: Float,
    drawLandmarks: Boolean,
    drawDetectionBox: Boolean,
    mnnBackend: MnnBackend,
    backendSwitching: Boolean,
    dualChannel: Boolean,
    onToggleTracking: () -> Unit,
    onResetBaseline: () -> Unit,
    onToggleDebug: () -> Unit,
    onOpenCameraSelector: () -> Unit,
    onOpenTrackingOutput: () -> Unit,
    onToggleBlinkCurve: () -> Unit,
    onBlinkSpeedChange: (Float) -> Unit,
    onToggleLandmarks: () -> Unit,
    onToggleDetectionBox: () -> Unit,
    onToggleMnnBackend: () -> Unit,
    onToggleDualChannel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(
            topStart = 12.dp,
            topEnd = 12.dp,
            bottomStart = 0.dp,
            bottomEnd = 0.dp
        ),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "摄像头: " + currentCameraName + " · 网络输出: " +
                    (if (trackingEnabled) "已开启 (" + trackingClients + " 客户端)" else "已关闭"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 第一行：开始/停止 + 重置基线
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onToggleTracking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTracking)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isTracking) "停止面捕" else "开始面捕")
                }

                Button(
                    onClick = onResetBaseline,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("重置基线")
                }
            }

            // 第二行：摄像头切换 + 网络输出
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onOpenCameraSelector,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(
                        text = "摄像头: " + currentCameraName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onOpenTrackingOutput,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (trackingEnabled)
                            Color(0xFF2196F3)
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(
                        text = if (trackingEnabled) "网络输出: ON" else "网络输出",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 第三行：眨眼曲线 + 调试
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onToggleBlinkCurve,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (blinkCurveEnabled)
                            Color(0xFF4CAF50)
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(
                        text = if (blinkCurveEnabled) "眨眼曲线: ON" else "眨眼曲线: OFF",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onToggleDebug,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (showDebug) "隐藏调试" else "显示调试",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 第四行：预览叠加层（关键点 / 检测框）
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onToggleLandmarks,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (drawLandmarks)
                            Color(0xFF7C4DFF)
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(
                        text = if (drawLandmarks) "关键点: ON" else "关键点: OFF",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onToggleDetectionBox,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (drawDetectionBox)
                            Color(0xFF00897B)
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(
                        text = if (drawDetectionBox) "检测框: ON" else "检测框: OFF",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 第五行：MNN 推理后端（Vulkan ⇄ CPU）
            // 切换会重建推理 session（管线短暂重启），按钮在切换期间禁用并显示进度文案。
            Button(
                onClick = onToggleMnnBackend,
                enabled = !backendSwitching,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (mnnBackend) {
                        MnnBackend.VULKAN -> Color(0xFF7C4DFF)
                        MnnBackend.CPU -> Color(0xFF00897B)
                    }
                )
            ) {
                Text(
                    text = when {
                        backendSwitching -> "推理后端: 切换中..."
                        mnnBackend == MnnBackend.VULKAN -> "推理后端: Vulkan (点击切 CPU)"
                        else -> "推理后端: CPU (点击切 Vulkan)"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 第五行之后：CPU + GPU 双通道关键点推理
            // 关键点推理是整条管线的瓶颈，单路 Vulkan 时 CPU 大核基本闲置；
            // 打开后额外起一路 CPU 引擎，两路从同一个帧队列抢帧并行推理。
            Button(
                onClick = onToggleDualChannel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (dualChannel) Color(0xFF3949AB)
                    else MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(
                    text = if (dualChannel) "关键点: CPU+GPU 双通道 (点击关)"
                    else "关键点: 单路 (点击开双通道)",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 眨眼速度滑块（仅在 Blink Curve 启用时显示）
            if (blinkCurveEnabled) {
                Column {
                    Text(
                        text = "眨眼速度: %.1fx (%.0fms)".format(
                            blinkSpeedScale,
                            230f * blinkSpeedScale
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = blinkSpeedScale,
                        onValueChange = onBlinkSpeedChange,
                        valueRange = 0.5f..3.0f,
                        steps = 9,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF4CAF50),
                            activeTrackColor = Color(0xFF4CAF50).copy(alpha = 0.8f),
                            inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = when {
                            blinkSpeedScale < 0.8f -> "快眨"
                            blinkSpeedScale > 1.5f -> "慢而柔和"
                            else -> "正常速度"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            blinkSpeedScale < 0.8f -> Color.Cyan
                            blinkSpeedScale > 1.5f -> Color(0xFF4CAF50)
                            else -> Color.Gray
                        }
                    )
                }
            }
        }
    }
}

/**
 * 多摄像头选择对话框。
 *
 * 列出 Camera2 暴露的全部摄像头（前置 / 后置 / 逻辑多摄 / 外接 USB 摄像头），
 * 选择后若正在追踪会原地重启采集管线而不改变追踪开关状态。
 */
@Composable
fun CameraSelectorDialog(
    cameras: List<CameraDeviceInfo>,
    currentCameraId: String?,
    onCameraSelected: (CameraDeviceInfo) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择摄像头") },
        text = {
            if (cameras.isEmpty()) {
                Text(
                    text = "没有找到摄像头，请检查相机权限后重试。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(cameras) { camera ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCameraSelected(camera) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = camera.id == currentCameraId,
                                onClick = { onCameraSelected(camera) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = camera.label,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "ID " + camera.id + " · 传感器 " +
                                        camera.sensorOrientation + "° · FOV %.0f°".format(
                                        camera.verticalFovDegrees
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * 「网络输出」对话框：启停追踪服务，显示 PC 客户端要连的 WebSocket 地址。
 *
 * 手机端只做面捕，不传图像；PC 端连 ws://手机IP:8765/ws 收 hello / track / calib。
 */
@Composable
fun TrackingOutputDialog(
    manager: TrackingOutputManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val running = manager.isRunning.value
    val clients = manager.clientCount.value
    val outputFps = manager.outputFps.value
    val error = manager.errorMessage.value
    val seq = manager.latestSeq.value
    val wsUrls = remember(running) { manager.wsUrls() }
    val httpUrls = remember(running) { manager.httpUrls() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网络输出（追踪数据）") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (running) {
                        "状态: 运行中 · 客户端 " + clients
                    } else {
                        "状态: 已停止"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (running) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (running) {
                    Text(
                        text = "输出 %.1f fps · 最新帧序号 %d".format(outputFps, seq),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 0.5s 发送帧缓冲：先攒满再匀速发，用来吸收推理耗时 / Wi-Fi 抖动
                    Text(
                        text = if (manager.frameBufferMs > 0L) {
                            "帧缓冲: %d ms（先攒满再匀速发送，端到端延迟 +%.1f s）".format(
                                manager.frameBufferMs,
                                manager.frameBufferMs / 1000f,
                            )
                        } else {
                            "帧缓冲: 已关闭（最低延迟，帧间隔直接跟随推理耗时）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "PC 客户端 WebSocket 地址（填这个）：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (wsUrls.isEmpty()) {
                    Text(
                        text = "未找到局域网地址，请先连接 Wi-Fi / 热点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    wsUrls.forEach { url ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2196F3),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { copyToClipboard(context, url) }) {
                                Text("复制")
                            }
                        }
                    }
                    if (httpUrls.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "浏览器状态页: " + httpUrls.first(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { copyToClipboard(context, httpUrls.first()) }) {
                                Text("复制")
                            }
                        }
                    }
                }

                Button(
                    onClick = { if (manager.isRunning.value) manager.stop() else manager.start() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running)
                            MaterialTheme.colorScheme.error
                        else
                            Color(0xFF2196F3)
                    )
                ) {
                    Text(if (running) "停止服务" else "启动服务")
                }

                error?.let {
                    Text(
                        text = "启动失败: " + it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    text = "说明：手机端只做面捕，不传图像，每帧约 0.4~0.7 KB JSON。" +
                        "Windows 客户端连接上面的 ws:// 地址，按 track.seq 丢弃旧帧；" +
                        "可发 ping 保活、发 command:recalibrate 让手机重做中性脸基线校准。" +
                        "手机与电脑需在同一局域网，输出期间请保持 App 在前台。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "帧缓冲：追踪帧先攒够 " + manager.frameBufferMs +
                        " ms 再按生产节奏匀速发给电脑，用来抹平推理耗时 / Wi-Fi 突发带来的帧间隔抖动；" +
                        "代价是端到端延迟固定多这么多。把 TrackingProtocol.FRAME_BUFFER_MS 改成 0 即可关闭。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("VirtualFaceCapture", text))
}
