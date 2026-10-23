package com.example.virtualfacecapture

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.example.commondata.ARKitBlendShapes
import com.example.commondata.CameraDeviceInfo
import com.example.commondata.HeadPoseData
import com.example.commondata.UpperBodyPoseFrame
import com.example.mediapipefacelandmark.MediaPipeFaceDetector
import com.example.mediapipefacelandmark.MnnBackend
import com.example.mediapipefacelandmark.YuvFrameData
import com.example.virtualfacecapture.tracking.TrackingOutputManager
import com.example.virtualfacecapture.tracking.TrackingProtocol
import com.example.virtualfacecapture.ui.theme.VirtualFaceCaptureTheme
import com.facecapture.sdk.PipelineFaceTracker
import com.live2d.facecapture.SimpleEyeExtractor
import com.vfc.log.VfcLog as Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CAMERA_PREFS_NAME = "face_capture_prefs"
private const val KEY_PREFERRED_CAMERA_ID = "preferred_camera_id"

/** 导出日志时等待 SAF 返回的 zip 路径（进程被杀后重建时用它恢复，避免丢弃用户选好的位置）。 */
private const val KEY_PENDING_LOG_ZIP = "pending_log_zip"

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
    // ---- 上半身（可选功能，默认关）----
    /** 中文状态：关 / 缺少模型 / 启动中 / 运行中 / 出错。 */
    val bodyState: String = "关",
    /** 实际产出的 body 帧率（≈15fps 目标）。 */
    val bodyFps: Float = 0f,
    /** 可见度 ≥ 0.5 的关键点个数，满分 13。 */
    val bodyPoints: Int = 0,
    /** 13 点平均可见度 [0,1]。 */
    val bodyQuality: Float = 0f,
    /** 相对躯干角（度）：roll 正值 = 右肩下沉，yaw 正值 = 向本人左侧转。 */
    val bodyRoll: Float = 0f,
    val bodyYaw: Float = 0f,
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

    // 上半身（约 15fps 回调）：同样只在 10Hz 的发布节奏里写进快照
    private var bodyState: String = "关"
    private var bodyFps: Float = 0f
    private var bodyPoints: Int = 0
    private var bodyQuality: Float = 0f
    private var bodyRoll: Float = 0f
    private var bodyYaw: Float = 0f

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

    /**
     * 上半身结果（约 15fps，在 MediaPipe 回调线程触发）。
     *
     * [frame] 为 null 表示功能已关闭 / 已停止，HUD 要显示成空状态。
     * 和面捕的其它字段一起走同一个 10Hz 节流，绝不每帧写 Compose state。
     */
    fun updateUpperBody(frame: UpperBodyPoseFrame?, stateLabel: String, outputFps: Float) {
        if (!enabled) return
        synchronized(lock) {
            bodyState = stateLabel
            bodyFps = outputFps
            if (frame == null || !frame.tracking) {
                bodyPoints = 0
                bodyQuality = 0f
                bodyRoll = 0f
                bodyYaw = 0f
            } else {
                // count {} 是 inline 的，不会在回调线程上额外分配
                bodyPoints = frame.points.count { it.visibility >= 0.5f }
                bodyQuality = frame.quality
                bodyRoll = frame.torso.roll
                bodyYaw = frame.torso.yaw
            }
        }
        publishIfDue()
    }

    private fun publishIfDue() {
        val now = System.nanoTime()
        val snapshot: DebugSnapshot? = synchronized(lock) {
            if (now - lastPublishNanos < intervalNanos) {
                null
            } else {
                lastPublishNanos = now
                DebugSnapshot(
                    fps = fps,
                    blendShapes = blendShapes,
                    headPose = headPose,
                    bodyState = bodyState,
                    bodyFps = bodyFps,
                    bodyPoints = bodyPoints,
                    bodyQuality = bodyQuality,
                    bodyRoll = bodyRoll,
                    bodyYaw = bodyYaw,
                )
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

    /** 是否 debug（可调试）包：文件日志 / 导出日志按钮只在这种包里启用。 */
    private val isDebuggable: Boolean by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /** 导出按钮的进行中状态与结果文案（Compose 直接读）。 */
    private val logExporting = mutableStateOf(false)
    private val logExportStatus = mutableStateOf("")

    /** 已打好包、等待用户在 SAF 里选保存位置的 zip。 */
    private var pendingLogZip: File? = null

    /** 上半身关键点控制器（可选功能，默认关；契约见 docs/upper-body.md §3.5）。 */
    private lateinit var upperBody: UpperBodyPoseController

    /** 上半身开关（用户意图）：开着但面捕没跑时不采集，开始面捕时自动接力。 */
    private var upperBodyEnabled = mutableStateOf(false)

    /**
     * 相机线程上的上半身 YUV 抽头（带脸框提示）。
     *
     * 做成一个字段而不是每次新建 lambda：挂上 / 摘掉时零分配。
     * 关闭时 [PipelineFaceTracker.onYuvFrameForBodyWithHint] 为 null，
     * 相机回调里只多一次判空。
     *
     * [faceBox] 是 SDK 一级扩充（1.15x）后的「脸 ROI」，可能为 null（无脸 /
     * 提示过期）；上半身 ROI 没有提示时会自行回退到整帧。
     */
    private val bodyFrameTap: (YuvFrameData, MediaPipeFaceDetector.FaceBox?) -> Unit = { frame, faceBox ->
        upperBody.pushYuvFrame(frame, faceBox)
    }

    /**
     * 挂上 / 摘掉上半身帧抽头（相机线程侧）。
     *
     * 只挂带提示的新回调，并把旧的 [PipelineFaceTracker.onYuvFrameForBody]
     * 置 null：两者同时挂上时 SDK 只会调新的，这里显式清掉避免语义歧义。
     *
     * @param on true = 按 66ms（≈15fps）抽帧；false = 两个 tap 都置 null，
     *           面捕热路径零额外开销。
     */
    private fun attachBodyTap(on: Boolean) {
        if (on) {
            faceTracker.onYuvFrameForBodyWithHint = bodyFrameTap
            faceTracker.onYuvFrameForBody = null
        } else {
            faceTracker.onYuvFrameForBodyWithHint = null
            faceTracker.onYuvFrameForBody = null
        }
        faceTracker.bodyTapIntervalMs = UpperBodyPoseController.TAP_INTERVAL_MS
    }

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

    /**
     * 导出日志的保存位置选择器（SAF，不需要存储权限）。
     *
     * 流程：按钮 → IO 线程打包 zip → 主线程拉起系统「保存到」对话框 →
     * 用户选好目录后把 zip 写进对应 URI。
     */
    private val exportLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val zip = pendingLogZip
        pendingLogZip = null
        if (uri == null) {
            logExporting.value = false
            logExportStatus.value = "导出已取消"
            Log.i("MainActivity", "导出日志已取消")
            // 取消后把临时 zip 删掉，避免用户反复点「导出」把 cache 撑大
            zip?.delete()
            return@registerForActivityResult
        }
        if (zip == null || !zip.isFile) {
            logExporting.value = false
            logExportStatus.value = "日志文件已丢失，请重新导出"
            Log.w("MainActivity", "导出失败：待写入的日志包不存在")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                LogExporter.writeTo(this@MainActivity, uri, zip)
            }
            logExporting.value = false
            logExportStatus.value = if (ok) "日志已导出: " + zip.name else "导出失败，请重试"
            Toast.makeText(this@MainActivity, logExportStatus.value, Toast.LENGTH_LONG).show()
            Log.i("MainActivity", "导出日志 -> $uri, ok=$ok")
        }
    }

    /**
     * 收集日志并导出（仅 debug 包可见的按钮会调用）。
     *
     * 打包在 IO 线程完成；失败只会更新状态文案，不影响面捕 / 推流。
     */
    private fun exportLogs() {
        if (logExporting.value) return
        logExporting.value = true
        logExportStatus.value = "正在收集日志..."
        Log.i("MainActivity", "开始导出日志...")
        lifecycleScope.launch {
            val zip = withContext(Dispatchers.IO) {
                try {
                    LogExporter.buildZip(this@MainActivity)
                } catch (t: Throwable) {
                    Log.e("MainActivity", "打包日志失败", t)
                    null
                }
            }
            if (zip == null) {
                logExporting.value = false
                logExportStatus.value = "打包失败，请重试"
                Toast.makeText(this@MainActivity, logExportStatus.value, Toast.LENGTH_LONG).show()
                return@launch
            }
            pendingLogZip = zip
            logExportStatus.value = "请选择保存位置"
            try {
                exportLogLauncher.launch(zip.name)
                Log.i("MainActivity", "日志包已生成: " + zip.absolutePath + " (" + zip.length() + "B)")
            } catch (t: Throwable) {
                Log.e("MainActivity", "无法打开保存对话框", t)
                // 没有 SAF 请求在等，清掉 pending 以免旋转/重建后按钮永久卡在「导出中」
                pendingLogZip = null
                logExporting.value = false
                logExportStatus.value = "无法打开保存对话框，日志在 " + zip.absolutePath
                Toast.makeText(this@MainActivity, logExportStatus.value, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SAF 对话框期间进程被杀：重建后恢复待写入的 zip，否则用户选好的位置会被丢弃
        savedInstanceState?.getString(KEY_PENDING_LOG_ZIP)?.let { path ->
            val file = File(path)
            if (file.isFile) {
                pendingLogZip = file
                logExporting.value = true
                logExportStatus.value = "请选择保存位置"
            }
        }

        Log.i("MainActivity", "onCreate: Initializing face capture (tracking-only build)...")

        // 面捕管线（MNN 推理 + MediaPipe 拓扑 + OpenSeeFace 后处理）
        faceTracker = PipelineFaceTracker(this)

        // 恢复上次选择的摄像头（如果该 id 在本次开机后不存在，相机会自动回退到默认前置）
        val cameraPrefs = getSharedPreferences(CAMERA_PREFS_NAME, Context.MODE_PRIVATE)
        faceTracker.setPreferredCamera(cameraPrefs.getString(KEY_PREFERRED_CAMERA_ID, null))

        // 上半身关键点（:MediapipeUpperBody）：默认关，打开后才挂相机 YUV 抽头并加载模型。
        // 控制器自己不依赖 tracking / UI，帧的分发由下面两个回调接线。
        upperBody = UpperBodyPoseController(this)

        // 网络输出（追踪数据）：默认关闭，用户可在控制面板里开启。
        // PC 端发 command:recalibrate 时回到主线程触发与 UI 相同的「重置基线」逻辑。
        // 任务 M2：全体 PC 客户端都协商 upperBodyPostV1 时，把上半身检测器切到原始输出
        // （One-Euro / 躯干角 / 基线迁到 PC 侧做），否则保持 M1 的手机端后处理链。
        trackingOutput = TrackingOutputManager(
            context = this,
            onUpperBodyPostV1 = { enabled -> upperBody.setRawOutput(enabled) },
            onRecalibrate = {
                runOnUiThread { faceTracker.resetAdaptiveBaseline() }
            },
        )

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

        // 上半身结果 → 网络输出（协议组：内部只存最新快照，track 报文按需带 ub / ubOk / body）。
        // 预览骨架由 FaceCaptureScreen 里的 onOverlay 接（那边才有 CameraPreviewView 实例）。
        upperBody.onFrame = { frame -> trackingOutput.submitUpperBody(frame) }

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
                    upperBody = upperBody,
                    upperBodyEnabled = upperBodyEnabled,
                    onAttachBodyTap = ::attachBodyTap,
                    isDebugBuild = isDebuggable,
                    logExporting = logExporting.value,
                    logExportStatus = logExportStatus.value,
                    onExportLogs = ::exportLogs,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingLogZip?.let { outState.putString(KEY_PENDING_LOG_ZIP, it.absolutePath) }
    }

    override fun onResume() {
        super.onResume()
        // 从后台回来：之前是「面捕中」就重新开相机（onPause 里释放过）
        if (isTracking.value && !faceTracker.isTracking()) {
            faceTracker.start()
        }
        // 上半身：onPause 停过检测器；回前台且开关还开着就重新挂 tap、接着采
        if (upperBodyEnabled.value) {
            attachBodyTap(true)
            if (isTracking.value && faceTracker.isTracking()) {
                upperBody.start()
            }
        }
        trackingOutput.setTrackingActive(isTracking.value && faceTracker.isTracking())
    }

    override fun onPause() {
        super.onPause()
        if (faceTracker.isTracking()) {
            // stop() 里会顺手把上半身 tap 置 null，避免相机停掉后仍有回调
            faceTracker.stop()
        }
        // 没有帧源就没有意义：停掉上半身检测器（用户开关状态保留，回前台自动恢复）
        upperBody.stop()
        // 相机已释放，/status 的 tracking 要如实变成 false（PC 端据此判断「手机在不在面捕」）
        trackingOutput.setTrackingActive(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        trackingOutput.dispose()
        // 先放掉上半身（它持有 MediaPipe Tasks 运行时），再释放面捕管线
        upperBody.release()
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
    upperBody: UpperBodyPoseController,
    upperBodyEnabled: MutableState<Boolean>,
    onAttachBodyTap: (Boolean) -> Unit,
    isDebugBuild: Boolean = false,
    logExporting: Boolean = false,
    logExportStatus: String = "",
    onExportLogs: () -> Unit = {},
) {
    // 调试面板数据：fps / BlendShape / 姿态合并为一个快照，且限制在 ≤10Hz（见 DebugSnapshotBatcher）
    val debugSnapshotState = remember { mutableStateOf(DebugSnapshot()) }
    var showDebug by remember { mutableStateOf(true) }
    var showTrackingDialog by remember { mutableStateOf(false) }
    var showCameraSelectorDialog by remember { mutableStateOf(false) }

    // 横屏适配：控制面板高度不能超过屏幕可用高度（否则面板顶部被顶出屏幕、滚不到），
    // 横屏首次进入默认收起面板——否则 280dp 的面板会盖住几乎整个横屏画面。
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val controlPanelMaxHeight = (configuration.screenHeightDp.dp - 88.dp)
        .coerceAtLeast(160.dp)
    var showControlPanel by rememberSaveable { mutableStateOf(!isLandscape) }

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
    // 关键点推理的 GPU 并行度：gpuChannels = 期望值，gpuActiveChannels = 实际生效
    var gpuChannels by remember { mutableStateOf(faceTracker.getGpuChannelCount()) }
    var gpuActiveChannels by remember { mutableStateOf(faceTracker.getActiveChannelCount()) }

    var cameraPreviewView by remember { mutableStateOf<CameraPreviewView?>(null) }

    /** 直出预览容器（TextureView 底图 + 叠加层）；null = View 还没建好。 */
    var previewContainer by remember { mutableStateOf<CameraPreviewSurfaceContainer?>(null) }

    // 上半身：初始化（加载模型）期间按钮显示「启动中」；模型缺失 / 出错时显示中文提示
    var upperBodyBusy by remember { mutableStateOf(false) }
    var upperBodyMessage by remember { mutableStateOf("") }

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

        // 上半身：每帧（~15fps，MediaPipe 回调线程）刷新预览骨架；
        // HUD 数值走合批器（≤10Hz 写 Compose state），不逐帧重组。
        upperBody.onOverlay = { frame ->
            cameraPreviewView?.updateUpperBody(frame)
            debugBatcher.updateUpperBody(frame, upperBody.stateLabelZh(), upperBody.outputFps)
        }

        // 上半身状态 / 错误的中文提示（模型缺失、GPU 回退 CPU 等）
        upperBody.onStatus = { message ->
            upperBodyMessage = message
            Log.w("UpperBodyPose", message)
        }

        faceTracker.onCameraChanged = { camera ->
            currentCamera = camera
            // 前置预览习惯性镜像，后置 / 外接相机保持真实方向
            cameraPreviewView?.mirrorHorizontal = camera?.isFrontFacing ?: true
            // 直出预览的几何同步：旋转角 / 镜像 / 采集尺寸变了都要重算变换
            previewContainer?.apply {
                mirrorHorizontal = camera?.isFrontFacing ?: true
                contentRotationDegrees = faceTracker.previewRotationDegrees
                faceTracker.previewFrameSize?.let { size ->
                    setFrameSize(size.width, size.height)
                }
            }
            // 上半身：检测器按「镜像后的像素」推理，产出的 x/y 才和预览方向一致（契约 §3.2）
            upperBody.mirrorHorizontal = camera?.isFrontFacing ?: true
            // 相机几何 / FOV 变了，躯干的中性基线跟着重取
            upperBody.resetBaseline()
            // 管线内部重启（切摄像头 / 切后端 / 改并行度）时 stop() 会清掉 tap，这里按开关补挂
            if (upperBodyEnabled.value) {
                onAttachBodyTap(true)
            }
        }

        // HAL 拒绝「YUV 采集 + 直出预览」双流组合：回退到限速 / 缩尺寸的位图预览
        faceTracker.setOnPreviewSurfaceRejected {
            val container = previewContainer
            if (container != null) {
                container.surfaceModeEnabled = false
                container.refreshPreviewPath()
            } else {
                faceTracker.setCameraPreviewFramesEnabled(true)
            }
        }

        // 摄像头列表 + 初始选中项（用于在 Start 之前就显示正确的标签/镜像）
        availableCameras = faceTracker.getAvailableCameras()
        currentCamera = faceTracker.getCurrentCamera()
            ?: availableCameras.firstOrNull { it.key == faceTracker.getPreferredCameraId() }
            ?: availableCameras.firstOrNull { it.isFrontFacing }
            ?: availableCameras.firstOrNull()

        // 第一帧之前就把镜像方向同步给上半身
        upperBody.mirrorHorizontal = currentCamera?.isFrontFacing ?: true

        // 直出预览几何兜底同步：onCameraChanged 可能在本回调接线之前就触发过
        // （相机在权限回调里先启动），这里补一次，避免预览旋转角 / 采集尺寸陈旧。
        previewContainer?.apply {
            mirrorHorizontal = currentCamera?.isFrontFacing ?: true
            contentRotationDegrees = faceTracker.previewRotationDegrees
            faceTracker.previewFrameSize?.let { size ->
                setFrameSize(size.width, size.height)
            }
        }
    }

    // 相机预览门控：只在 UI 可见（生命周期 ≥ STARTED）时挂预览。预览优先走 Camera2
    // 直出 Surface（零 YUV→RGB→Bitmap），Surface 不可用时回退到「限速 ≤30fps +
    // 尺寸 ≤640×360」的 RGB 位图预览；UI 不可见时两个模式都关，相机线程不再
    // 白做整帧 YUV→RGB（推理管线走 YUV 直通，本来就用不到这些 RGB 帧）。
    //
    // 时序：ON_START 在 Activity.onStart（相机 onResume 里才重新 start）之前，
    // ON_STOP 在 onPause（相机已 stop）之后，两个方向都不会引起多余的会话重建。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    previewContainer?.uiVisible = true
                    previewContainer?.refreshPreviewPath()
                }
                Lifecycle.Event.ON_STOP -> {
                    previewContainer?.uiVisible = false
                    previewContainer?.refreshPreviewPath()
                    // 容器还没建好时直接关 SDK 侧开关，兜住所有时序
                    if (previewContainer == null) {
                        faceTracker.setCameraPreviewFramesEnabled(false)
                        faceTracker.setCameraPreviewSurface(null)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 组合销毁：兜底关掉两个预览模式
            previewContainer?.onPreviewPath = null
            previewContainer?.uiVisible = false
            faceTracker.setCameraPreviewFramesEnabled(false)
            faceTracker.setCameraPreviewSurface(null)
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

    /**
     * 「上半身: 开/关」按钮（控制面板）。
     *
     * 开启：初始化模型（后台线程，几百毫秒）→ 通知网络层 → 挂相机抽帧 tap → 启动检测器。
     * 关闭：先摘 tap（相机线程立刻不再调用）→ 通知网络层 → 停检测器 → 清预览骨架。
     *
     * 模型缺失时只提示中文指引、不改变开关状态；面捕没在跑时只保留开关 + tap，
     * 等「开始面捕」时再真正 start()（见 onToggleTracking）。
     */
    val onToggleUpperBody: () -> Unit = {
        if (!upperBodyBusy) {
            if (upperBodyEnabled.value) {
                // ---- 关闭 ----
                upperBodyBusy = true
                upperBodyEnabled.value = false
                onAttachBodyTap(false)
                trackingOutput.setUpperBodyEnabled(false)
                cameraPreviewView?.updateUpperBody(null)
                upperBodyMessage = ""
                scope.launch {
                    withContext(Dispatchers.Default) { upperBody.stop() }
                    upperBodyBusy = false
                }
            } else {
                // ---- 开启 ----
                upperBodyBusy = true
                scope.launch {
                    val ready = withContext(Dispatchers.Default) { upperBody.initialize() }
                    if (!ready) {
                        // onStatus 通常已经给了原因（模型缺失 / 初始化失败），这里兜底
                        if (upperBodyMessage.isEmpty()) {
                            upperBodyMessage = "上半身不可用：未找到 " +
                                UpperBodyPoseController.MODEL_LITE +
                                "，请运行 tools/fetch-pose-model.ps1 下载模型后重试"
                        }
                        upperBodyEnabled.value = false
                        upperBodyBusy = false
                        return@launch
                    }
                    upperBodyEnabled.value = true
                    trackingOutput.setUpperBodyEnabled(true)
                    if (isTracking.value && faceTracker.isTracking()) {
                        onAttachBodyTap(true)
                        withContext(Dispatchers.Default) { upperBody.start() }
                    }
                    upperBodyMessage = ""
                    upperBodyBusy = false
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 主画面：相机预览 + 关键点 / 检测框叠加（手机端不再渲染 Live2D）
        AndroidView(
            factory = { ctx ->
                // 直出 TextureView（底图）+ CameraPreviewView（叠加层）：
                // 相机预览由 HAL 直接送显卡合成，不再走 YUV→RGB→Bitmap。
                CameraPreviewSurfaceContainer(ctx).also { container ->
                    previewContainer = container
                    cameraPreviewView = container.previewView
                    container.previewView.drawLandmarks = drawLandmarks
                    container.previewView.drawKeyPoints = drawLandmarks
                    container.previewView.drawDetectionBox = drawDetectionBox
                    container.mirrorHorizontal = currentCamera?.isFrontFacing ?: true
                    // 摆正角实时取（转屏不重建 Activity，换相机时推的值会陈旧）
                    container.contentRotationDegreesProvider = { faceTracker.previewRotationDegrees }
                    // 视图可能在相机已启动后（重新）创建：先把几何补齐再挂 Surface，
                    // 保证 Surface 进会话时 buffer 尺寸已钉在采集帧尺寸上。
                    faceTracker.previewFrameSize?.let { size ->
                        container.setFrameSize(size.width, size.height)
                    }
                    container.contentRotationDegrees = faceTracker.previewRotationDegrees
                    container.onPreviewPath = { surface, bitmapFrames ->
                        faceTracker.setCameraPreviewSurface(surface)
                        faceTracker.setCameraPreviewFramesEnabled(bitmapFrames)
                    }
                    container.refreshPreviewPath()
                }
            },
            update = { container ->
                // 转屏（含 180° 翻转：宽高不变、onSizeChanged 不触发）后强制重算一次
                // 变换矩阵；值没变时容器内部会直接短路，代价可忽略。
                container.contentRotationDegrees = faceTracker.previewRotationDegrees
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
                currentCameraKey = currentCamera?.key ?: faceTracker.getPreferredCameraId(),
                onCameraSelected = { camera ->
                    showCameraSelectorDialog = false
                    scope.launch {
                        val switched = faceTracker.switchCamera(camera.key)
                        if (switched) {
                            currentCamera = camera
                            context.getSharedPreferences(CAMERA_PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString(KEY_PREFERRED_CAMERA_ID, camera.key)
                                .apply()
                            // 相机几何 / FOV 变了，重做基线校准，避免姿态与表情残留旧基线
                            faceTracker.resetAdaptiveBaseline()
                            Log.i(
                                "FaceCapturePipeline",
                                "Camera switched to " + camera.label + " (key=" + camera.key + ")"
                            )
                        } else {
                            Log.e("FaceCapturePipeline", "Failed to switch camera to " + camera.key)
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
                    gpuChannels = gpuChannels,
                    gpuActiveChannels = gpuActiveChannels,
                    upperBodyEnabled = upperBodyEnabled.value,
                    upperBodyBusy = upperBodyBusy,
                    upperBodyMessage = upperBodyMessage,
                    isDebugBuild = isDebugBuild,
                    logExporting = logExporting,
                    logExportStatus = logExportStatus,
                    maxHeight = controlPanelMaxHeight,
                    onToggleTracking = {
                        scope.launch {
                            if (isTracking.value) {
                                Log.i("FaceCapturePipeline", "Stopping face tracking...")
                                faceTracker.stop()
                                isTracking.value = false
                                trackingOutput.setTrackingActive(false)
                                // 面捕停了就没有 YUV 帧源：摘 tap + 停上半身（开关状态保留）
                                if (upperBodyEnabled.value) {
                                    onAttachBodyTap(false)
                                    withContext(Dispatchers.Default) { upperBody.stop() }
                                    cameraPreviewView?.updateUpperBody(null)
                                }
                            } else {
                                Log.i("FaceCapturePipeline", "Starting face tracking...")
                                faceTracker.start()
                                isTracking.value = true
                                trackingOutput.setTrackingActive(true)
                                // 实际生效几路要等 start() 之后才知道
                                gpuChannels = faceTracker.getGpuChannelCount()
                                gpuActiveChannels = faceTracker.getActiveChannelCount()
                                // 上半身开关开着 → 跟着面捕一起恢复采集
                                if (upperBodyEnabled.value) {
                                    onAttachBodyTap(true)
                                    withContext(Dispatchers.Default) { upperBody.start() }
                                }
                            }
                        }
                    },
                    onResetBaseline = {
                        Log.i("FaceCapturePipeline", "Resetting adaptive baseline...")
                        faceTracker.resetAdaptiveBaseline()
                        // 上半身的躯干基线一起重取
                        upperBody.resetBaseline()
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
                    onToggleUpperBody = onToggleUpperBody,
                    onExportLogs = onExportLogs,
                    onToggleGpuChannels = {
                        // 并行度变化要在 start() 里拉起/收起推理线程，属于管线重启，
                        // 和切后端一样放 IO 线程，避免主线程卡顿。
                        // UI 上最多给到 3 路（4 路留给代码里手动开）。
                        val next = if (gpuChannels >= 3) 1 else gpuChannels + 1
                        Log.i("FaceCapturePipeline", "Switching GPU channels to $next ...")
                        scope.launch(Dispatchers.IO) {
                            try {
                                faceTracker.setGpuChannelCount(next)
                            } catch (t: Throwable) {
                                Log.e("FaceCapturePipeline", "Switching GPU channels failed", t)
                            }
                            // 以 SDK 真实状态为准：显存不够时会少于期望值
                            gpuChannels = faceTracker.getGpuChannelCount()
                            gpuActiveChannels = faceTracker.getActiveChannelCount()
                            Log.i(
                                "FaceCapturePipeline",
                                "GPU channels now " + gpuActiveChannels + "/" + gpuChannels
                            )
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
        bodyState = value.bodyState,
        bodyFps = value.bodyFps,
        bodyPoints = value.bodyPoints,
        bodyQuality = value.bodyQuality,
        bodyRoll = value.bodyRoll,
        bodyYaw = value.bodyYaw,
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
    bodyState: String = "关",
    bodyFps: Float = 0f,
    bodyPoints: Int = 0,
    bodyQuality: Float = 0f,
    bodyRoll: Float = 0f,
    bodyYaw: Float = 0f,
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

            // 上半身（可选功能）：状态 / body fps / 有效点数 / 质量 / 相对躯干角。
            // 数据来自 DebugSnapshotBatcher（≤10Hz），不会每帧重组。
            HorizontalDivider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = "上半身: " + bodyState + " · body %.1f fps".format(bodyFps),
                style = MaterialTheme.typography.bodySmall,
                color = if (bodyState == "运行中") Color(0xFF00E5FF) else Color.Gray
            )

            Text(
                text = "点数 %d/13 · 质量 %.2f".format(bodyPoints, bodyQuality),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )

            Text(
                text = "躯干: roll %+.1f° · yaw %+.1f°".format(bodyRoll, bodyYaw),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )

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
    gpuChannels: Int,
    gpuActiveChannels: Int,
    upperBodyEnabled: Boolean,
    upperBodyBusy: Boolean,
    upperBodyMessage: String,
    maxHeight: Dp = 380.dp,
    isDebugBuild: Boolean = false,
    logExporting: Boolean = false,
    logExportStatus: String = "",
    onExportLogs: () -> Unit = {},
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
    onToggleGpuChannels: () -> Unit,
    onToggleUpperBody: () -> Unit,
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
                .heightIn(max = maxHeight)
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

            // 第五行之后：关键点推理的 GPU 并行度
            // 关键点推理是整条管线的瓶颈，单路时 GPU 利用率并不高（一次推理里还夹着
            // 预处理 / 命令录制 / 回读）；多开几路引擎抢同一个帧队列，GPU 就一直有活儿干。
            Button(
                onClick = onToggleGpuChannels,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (gpuActiveChannels > 1) Color(0xFF3949AB)
                    else MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(
                    text = when {
                        gpuChannels <= 1 -> "关键点: 单路 (点击开 GPU 并行)"
                        gpuActiveChannels >= gpuChannels ->
                            "关键点: GPU 并行 ×$gpuActiveChannels (点击 " +
                                (if (gpuChannels >= 3) "关" else "加一路") + ")"
                        else -> "关键点: GPU 并行 ×$gpuActiveChannels/$gpuChannels (资源不足)"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 上半身关键点（MediaPipe Pose）：与面捕完全解耦的可选功能，默认关。
            // 打开后才挂相机 YUV 抽头（66ms ≈ 15fps）并加载 pose_landmarker_lite.task。
            Button(
                onClick = onToggleUpperBody,
                enabled = !upperBodyBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (upperBodyEnabled) Color(0xFF00BCD4)
                    else MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(
                    text = when {
                        upperBodyBusy -> "上半身: 启动中..."
                        upperBodyEnabled -> "上半身: 开 (点击关闭)"
                        else -> "上半身: 关 (点击开启)"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 模型缺失 / 初始化失败 / GPU 回退 CPU 等提示（中文，显示在按钮下方）
            if (upperBodyMessage.isNotEmpty()) {
                Text(
                    text = upperBodyMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
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

            // 导出日志（仅 debug 包）：把 VfcLog 落盘的日志 + 设备 / 版本信息 + 首帧 dump
            // 打成一个 zip，通过系统「保存到」对话框导出（SAF，不需要存储权限）。
            if (isDebugBuild) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onExportLogs,
                    enabled = !logExporting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF546E7A)
                    )
                ) {
                    Text(
                        text = when {
                            logExporting && logExportStatus == "请选择保存位置" -> "等待选择保存位置..."
                            logExporting -> "正在导出日志..."
                            else -> "导出日志 (debug)"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (logExportStatus.isNotEmpty()) {
                    Text(
                        text = logExportStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
    currentCameraKey: String?,
    onCameraSelected: (CameraDeviceInfo) -> Unit,
    onDismiss: () -> Unit
) {
    // 横屏时屏幕高度只有 300dp 出头，固定 320dp 的列表会把按钮顶出屏幕
    val maxListHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.5f)
        .coerceAtLeast(120.dp)
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
                LazyColumn(modifier = Modifier.heightIn(max = maxListHeight)) {
                    items(cameras) { camera ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCameraSelected(camera) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = camera.key == currentCameraKey,
                                onClick = { onCameraSelected(camera) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = camera.label,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "ID " + camera.key + " · 传感器 " +
                                        camera.sensorOrientation + "° · FOV %.0f°".format(
                                        camera.verticalFovDegrees
                                    ) +
                                        (camera.equivalentFocalLengthMm?.let {
                                            " · 等效 %.0fmm".format(it)
                                        } ?: "") +
                                        (if (camera.isPhysical) " · 物理流" else ""),
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

                    // 生效发送缓冲：协商 / 回落之后的真实值（配置 0 且所有 PC 都声明
                    // jitterBufferV1 时才是 0；有老客户端时自动回落 500 ms，见协议 §7.1）
                    Text(
                        text = if (manager.effectiveBufferMs.value > 0L) {
                            "生效发送缓冲: %d ms（先攒满再匀速发送，端到端延迟 +%.1f s）".format(
                                manager.effectiveBufferMs.value,
                                manager.effectiveBufferMs.value / 1000f,
                            )
                        } else {
                            "生效发送缓冲: 0 ms（旁路，算完即发；PC 侧 jitterBufferV1 吸收抖动）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 发送模式（send.bufferMs，持久化）：500 ms 保守匀速 / 0 ms 低延迟。
                // 低延迟只有所有已连接 PC 都声明 jitterBufferV1 时才真正生效，否则回落 500 ms。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (manager.sendBufferMs.value == 0L) {
                            "发送模式: 低延迟（0 ms，算完即发，需 PC 支持 jitterBufferV1）"
                        } else {
                            "发送模式: 保守（" + manager.sendBufferMs.value + " ms，匀速发送）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = manager.sendBufferMs.value == 0L,
                        onCheckedChange = { checked ->
                            manager.setSendBufferMs(
                                if (checked) 0L else TrackingProtocol.FRAME_BUFFER_MS
                            )
                        }
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
                    text = "发送缓冲（send.bufferMs）：保守模式（500 ms）下追踪帧先攒够再按生产节奏匀速发给电脑，" +
                        "抹平推理耗时 / Wi-Fi 突发带来的帧间隔抖动，代价是端到端延迟固定 +0.5 s；" +
                        "低延迟模式（0 ms）下算完即发，帧间隔抖动由 PC 侧自适应抖动缓冲（jitterBufferV1）吸收。" +
                        "只有当前所有已连接客户端都声明支持 jitterBufferV1 时低延迟才真正生效，" +
                        "否则自动回落 500 ms 保守模式（协商与回落规则见 docs/tracking-protocol.md §7.1）。",
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
