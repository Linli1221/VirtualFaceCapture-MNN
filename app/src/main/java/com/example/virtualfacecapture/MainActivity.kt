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
import com.example.commondata.CameraDeviceInfo
import com.example.commondata.FaceVisualizationData
import com.example.commondata.HeadPoseData
import com.example.virtualfacecapture.ui.theme.Live2DFacePipelineTheme
import com.example.commondata.ARKitBlendShapes
import com.example.virtualfacecapture.web.WebOutputManager
import com.facecapture.sdk.PipelineFaceTracker
import com.live2d.facecapture.SimpleEyeExtractor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CAMERA_PREFS_NAME = "face_capture_prefs"
private const val KEY_PREFERRED_CAMERA_ID = "preferred_camera_id"

class MainActivity : ComponentActivity() {
    
    // Use PipelineFaceTracker for parallel processing (higher throughput)
    private lateinit var faceTracker: PipelineFaceTracker
    private lateinit var modelManager: Live2DModelManager
    // Live2D 适配器：把面捕输出转成 JniBridgeJava 调用，是面捕↔Live2D 的唯一桥梁
    private val live2DAvatarAdapter = Live2DAvatarAdapter()
    // 浏览器输出（OBS 浏览器源）：把 Live2D 渲染结果推到局域网给浏览器
    private lateinit var webOutput: WebOutputManager
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
//
//    override fun onPostResume() {
//        super.onPostResume()
//        NeonOptimizationTest.runTests()
//    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i("MainActivity", "onCreate: Initializing Live2D Face Pipeline...")
        
        // Initialize model manager
        modelManager = Live2DModelManager(this)
        modelManager.initialize { models ->
            Log.i("MainActivity", "Model manager initialized with ${models.size} models")
            models.forEach { model ->
                Log.i("MainActivity", "  - ${model.name}")
            }
        }
        
        // Initialize face tracker (using pipeline for parallel processing)
        faceTracker = PipelineFaceTracker(this)

        // 恢复上次选择的摄像头（如果该 id 在本次开机后不存在，相机会自动回退到默认前置）
        val cameraPrefs = getSharedPreferences(CAMERA_PREFS_NAME, MODE_PRIVATE)
        faceTracker.setPreferredCamera(cameraPrefs.getString(KEY_PREFERRED_CAMERA_ID, null))

        // 浏览器输出（OBS）：默认关闭，用户可在控制面板里开启
        webOutput = WebOutputManager(this)

        // 把面捕输出接到 Live2D 适配器。这是 app 模块里唯一的"面捕→Live2D"桥接点，
        // PipelineFaceTracker 本身不再依赖 JniBridgeJava，可独立打入 facecapture-sdk AAR。
        faceTracker.onFaceCaptureFrame = { blendShapes, headPose ->
            live2DAvatarAdapter.onFrame(blendShapes, headPose)
        }

        // Request camera permission
        if (checkCameraPermission()) {
            initializeFaceTracker()
        } else {
            requestCameraPermission()
        }
        
        setContent {
            Live2DFacePipelineTheme {
                FaceCapturePipelineScreen(
                    faceTracker = faceTracker,
                    modelManager = modelManager,
                    avatarAdapter = live2DAvatarAdapter,
                    isTracking = isTracking,
                    webOutput = webOutput
                )
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (isTracking.value && faceTracker.isTracking()) {
            faceTracker.start()
        }
    }
    
    override fun onPause() {
        super.onPause()
        if (faceTracker.isTracking()) {
            faceTracker.stop()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        webOutput.dispose()
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
fun FaceCapturePipelineScreen(
    faceTracker: PipelineFaceTracker,
    modelManager: Live2DModelManager,
    avatarAdapter: Live2DAvatarAdapter,
    isTracking: MutableState<Boolean>,
    webOutput: WebOutputManager
) {
    var fps by remember { mutableStateOf(0f) }
    var currentBlendShapes by remember { mutableStateOf<ARKitBlendShapes?>(null) }
    var currentHeadPose by remember { mutableStateOf(HeadPoseData()) }
    var currentVisualizationData by remember { mutableStateOf<FaceVisualizationData?>(null) }
    var showDebug by remember { mutableStateOf(true) }
    var showCameraPreview by remember { mutableStateOf(false) }
    var showModelSelector by remember { mutableStateOf(false) }
    var showWebOutputDialog by remember { mutableStateOf(false) }
    var showCameraSelectorDialog by remember { mutableStateOf(false) }
    var availableCameras by remember { mutableStateOf<List<CameraDeviceInfo>>(emptyList()) }
    var currentCamera by remember { mutableStateOf<CameraDeviceInfo?>(null) }
    val context = LocalContext.current
    var blinkCurveEnabled by remember { mutableStateOf(faceTracker.blinkCurveProcessor.isEnabled) }
    var blinkSpeedScale by remember { mutableStateOf(faceTracker.blinkCurveProcessor.blinkSpeedScale) }
    var availableModels by remember { mutableStateOf<List<Live2DModelManager.ModelInfo>>(emptyList()) }
    var currentModel by remember { mutableStateOf<Live2DModelManager.ModelInfo?>(null) }
    var isCalibrating by remember { mutableStateOf(false) }  // 校准状态
    var showControlPanel by rememberSaveable { mutableStateOf(true) }  // 侧边控制面板显示状态
    val scope = rememberCoroutineScope()
    
    // Camera preview view reference
    var cameraPreviewView by remember { mutableStateOf<CameraPreviewView?>(null) }
    
    // Set up callbacks
    LaunchedEffect(Unit) {
        faceTracker.onFpsUpdate = { newFps ->
            fps = newFps
        }
        
        faceTracker.onBlendShapesUpdate = { blendShapes ->
            currentBlendShapes = blendShapes
        }
        
        faceTracker.onHeadPoseUpdate = { headPose ->
            currentHeadPose = headPose
        }
        
        faceTracker.onVisualizationUpdate = { data ->
            currentVisualizationData = data
            // Update camera preview view with visualization data
            cameraPreviewView?.updateVisualizationData(data)
        }
        
        faceTracker.onCameraFrameUpdate = { frameData, width, height ->
            // Only update camera preview if visible
            if (showCameraPreview) {
                cameraPreviewView?.updateCameraFrame(frameData, width, height)
            }
        }
        
        faceTracker.onCalibrationStatusUpdate = { calibrating ->
            isCalibrating = calibrating
        }

        faceTracker.onCameraChanged = { camera ->
            currentCamera = camera
            // 前置预览习惯性镜像，后置 / 外接相机保持真实方向
            cameraPreviewView?.mirrorHorizontal = camera?.isFrontFacing ?: true
        }
        
        // Load available models
        availableModels = modelManager.getAvailableModels()
        currentModel = modelManager.getCurrentModel()

        // 摄像头列表 + 初始选中项（用于在 Start 之前就显示正确的标签/镜像）
        availableCameras = faceTracker.getAvailableCameras()
        currentCamera = faceTracker.getCurrentCamera()
            ?: availableCameras.firstOrNull { it.id == faceTracker.getPreferredCameraId() }
            ?: availableCameras.firstOrNull { it.isFrontFacing }
            ?: availableCameras.firstOrNull()
    }

    // 把当前模型名同步给浏览器输出的状态栏（/status 与网页 HUD 用）
    LaunchedEffect(currentModel) {
        webOutput.modelName.value = currentModel?.name ?: ""
    }
    
    // Clean up camera preview callback when not showing
    LaunchedEffect(showCameraPreview) {
        if (!showCameraPreview) {
            cameraPreviewView?.clear()
        }
    }

    // 摄像头切换后同步预览镜像方向（前置镜像、后置/外接不镜像）
    LaunchedEffect(currentCamera) {
        cameraPreviewView?.mirrorHorizontal = currentCamera?.isFrontFacing ?: true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content: Live2D view or Camera Preview
        if (showCameraPreview) {
            // Camera preview with landmarks overlay
            AndroidView(
                factory = { context ->
                    CameraPreviewView(context).also {
                        cameraPreviewView = it
                        it.drawLandmarks = true
                        it.drawDetectionBox = true
                        it.drawKeyPoints = true
                        it.mirrorHorizontal = currentCamera?.isFrontFacing ?: true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Live2D rendering view
            Live2DView(
                modifier = Modifier.fillMaxSize(),
                modelManager = modelManager,
                onModelLoadComplete = {
                    Log.i("FaceCapturePipeline", "✓ Live2D model loaded in UI")
                    currentModel = modelManager.getCurrentModel()
                },
                onModelLoadError = {
                    Log.e("FaceCapturePipeline", "✗ Failed to load Live2D model in UI")
                },
                captureSink = webOutput
            )
        }
        
        // 校准覆盖层 - 在追踪开始时且正在校准期间显示
        CalibrationOverlay(
            isCalibrating = isTracking.value && isCalibrating,
            modifier = Modifier.fillMaxSize(),
            text = "面捕正在校准中..."
        )
        
        // Debug overlay
        if (showDebug) {
            DebugOverlay(
                fps = fps,
                isTracking = isTracking.value,
                currentModel = currentModel?.name ?: "None",
                blendShapes = currentBlendShapes,
                headPose = currentHeadPose,
                showCameraPreview = showCameraPreview,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
                faceTracker = faceTracker
            )
        }
        
        // Model selector dialog
        if (showModelSelector) {
            ModelSelectorDialog(
                models = availableModels,
                currentModel = currentModel,
                onModelSelected = { model ->
                    Log.i("FaceCapturePipeline", "User selected model: ${model.name}")
                    scope.launch {
                        modelManager.loadModel(model) { success ->
                            if (success) {
                                currentModel = model
                                Log.i("FaceCapturePipeline", "✓ Model switched to: ${model.name}")
                            } else {
                                Log.e("FaceCapturePipeline", "✗ Failed to switch model")
                            }
                        }
                    }
                    showModelSelector = false
                },
                onDismiss = {
                    showModelSelector = false
                }
            )
        }
        
        // 浏览器输出（OBS）设置对话框
        if (showWebOutputDialog) {
            WebOutputDialog(
                manager = webOutput,
                onDismiss = { showWebOutputDialog = false }
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
                        val wasTracking = isTracking.value
                        val switched = faceTracker.switchCamera(camera.id)
                        if (switched) {
                            currentCamera = camera
                            context.getSharedPreferences(CAMERA_PREFS_NAME, MODE_PRIVATE)
                                .edit()
                                .putString(KEY_PREFERRED_CAMERA_ID, camera.id)
                                .apply()
                            // 相机几何 / FOV 变了，重置 avatar 归一化窗口等待重新校准
                            avatarAdapter.reset()
                            Log.i(
                                "FaceCapturePipeline",
                                "Camera switched to ${camera.label} (${camera.id}), " +
                                    "wasTracking=$wasTracking"
                            )
                        } else {
                            Log.e("FaceCapturePipeline", "Failed to switch camera to ${camera.id}")
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
                    showCameraPreview = showCameraPreview,
                    currentModelName = currentModel?.name ?: "Loading...",
                    currentCameraName = currentCamera?.label ?: "Auto",
                    webOutputEnabled = webOutput.isRunning.value,
                    onOpenCameraSelector = {
                        availableCameras = faceTracker.getAvailableCameras()
                        showCameraSelectorDialog = true
                    },
                    onOpenWebOutput = {
                        showWebOutputDialog = true
                    },
                    onToggleTracking = {
                        scope.launch {
                            if (isTracking.value) {
                                Log.i("FaceCapturePipeline", "Stopping face tracking...")
                                faceTracker.stop()
                                isTracking.value = false
                            } else {
                                Log.i("FaceCapturePipeline", "Starting face tracking...")
                                faceTracker.start()
                                isTracking.value = true
                            }
                        }
                    },
                    onToggleDebug = {
                        showDebug = !showDebug
                        Log.d("FaceCapturePipeline", "Debug overlay: ${if (showDebug) "shown" else "hidden"}")
                    },
                    onToggleCameraPreview = {
                        showCameraPreview = !showCameraPreview
                        Log.d("FaceCapturePipeline", "Camera preview: ${if (showCameraPreview) "shown" else "hidden"}")
                    },
                    onSelectModel = {
                        showModelSelector = true
                    },
                        onResetBaseline = {
                            Log.i("FaceCapturePipeline", "Resetting adaptive baseline...")
                            faceTracker.resetAdaptiveBaseline()
                            // 同步重置 Live2D 适配器的归一化窗口，避免 avatar 和面捕基线错位
                            avatarAdapter.reset()
                        },
                    blinkCurveEnabled = blinkCurveEnabled,
                    blinkSpeedScale = blinkSpeedScale,
                    onToggleBlinkCurve = {
                        blinkCurveEnabled = !blinkCurveEnabled
                        faceTracker.blinkCurveProcessor.isEnabled = blinkCurveEnabled
                        if (blinkCurveEnabled) {
                            faceTracker.blinkCurveProcessor.reset()
                        }
                        Log.i("FaceCapturePipeline", "Blink Curve Processor: ${if (blinkCurveEnabled) "ENABLED" else "DISABLED"}")
                    },
                    onBlinkSpeedChange = { newSpeed ->
                        blinkSpeedScale = newSpeed
                        faceTracker.blinkCurveProcessor.blinkSpeedScale = newSpeed
                        Log.d("FaceCapturePipeline", "Blink speed scale: $newSpeed")
                    }
                )
            }
        }
    }
}

@Composable
fun DebugOverlay(
    fps: Float,
    isTracking: Boolean,
    currentModel: String,
    blendShapes: ARKitBlendShapes?,
    headPose: HeadPoseData,
    showCameraPreview: Boolean,
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
            // FPS and Performance Charts Row
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left: FPS and status
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "FPS: %.1f".format(fps),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    
                    Text(
                        text = if (isTracking) "Tracking: ON" else "Tracking: OFF",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isTracking) Color.Green else Color.Red
                    )
                    
                    Text(
                        text = "Model: $currentModel",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Cyan
                    )
                    
                    Text(
                        text = if (showCameraPreview) "View: Camera" else "View: Live2D",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Magenta
                    )

                    Text(
                        text = "Camera: ${faceTracker.getCurrentCamera()?.label ?: "Auto"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Cyan
                    )
                }
                
                // Right: CPU/Memory performance charts
                PerformanceChartPanel(
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            
            // Head Pose section
            HorizontalDivider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
            
            Text(
                text = "Head Pose (→Live2D):",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Yellow
            )
            
            // Pitch with visual indicator
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
            
            // Yaw with visual indicator
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
            
            // Roll with visual indicator
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
                    text = "BlendShapes:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                
                // Show key BlendShapes
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
    // 使用 remember + mutableStateOf 来跟踪滑块值
    var sensitivity by remember { mutableFloatStateOf(SimpleEyeExtractor.eyeSensitivity) }
    
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Eye Sensitivity:",
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
            text = "Thresholds: closed=%.2f, open=%.2f".format(thresholds.first, thresholds.second),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
        
        // 灵敏度说明
        Text(
            text = when {
                sensitivity < 0.8f -> "Low: needs larger eye movement"
                sensitivity > 1.3f -> "High: reacts to subtle movements"
                else -> "Normal: balanced detection"
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
        // Background bar
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Gray.copy(alpha = 0.3f))
        )
        
        // Center line
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .align(Alignment.Center)
                .background(Color.White.copy(alpha = 0.5f))
        )
        
        // Value indicator
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

@Composable
fun ControlPanel(
    isTracking: Boolean,
    showDebug: Boolean,
    showCameraPreview: Boolean,
    currentModelName: String,
    currentCameraName: String,
    blinkCurveEnabled: Boolean,
    blinkSpeedScale: Float,
    webOutputEnabled: Boolean,
    onToggleTracking: () -> Unit,
    onToggleDebug: () -> Unit,
    onToggleCameraPreview: () -> Unit,
    onSelectModel: () -> Unit,
    onResetBaseline: () -> Unit,
    onToggleBlinkCurve: () -> Unit,
    onBlinkSpeedChange: (Float) -> Unit,
    onOpenCameraSelector: () -> Unit,
    onOpenWebOutput: () -> Unit,
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Current model display
            Text(
                text = "Current: $currentModelName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Control buttons - first row
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
                    Text(if (isTracking) "Stop" else "Start")
                }

                Button(
                    onClick = onToggleDebug
                ) {
                    Text(if (showDebug) "Hide Debug" else "Show Debug")
                }
            }

            // Control buttons - second row
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onToggleCameraPreview,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showCameraPreview)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(if (showCameraPreview) "Show Live2D" else "Show Camera")
                }

                Button(
                    onClick = onSelectModel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Select Model")
                }
            }

            // Control buttons - third row
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onResetBaseline,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Reset Baseline")
                }

                // 眨眼曲线处理器开关
                Button(
                    onClick = onToggleBlinkCurve,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (blinkCurveEnabled)
                            Color(0xFF4CAF50)  // Green when enabled
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(if (blinkCurveEnabled) "Blink Curve: ON" else "Blink Curve: OFF")
                }
            }

            // Control buttons - fourth row：摄像头选择 + 浏览器输出（OBS 浏览器源）
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
                        text = "Camera: $currentCameraName",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onOpenWebOutput,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (webOutputEnabled)
                            Color(0xFF2196F3)   // 运行中：蓝色
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(
                        text = if (webOutputEnabled) "Web Output: ON" else "Web Output",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 眨眼速度滑块（仅在 Blink Curve 启用时显示）
            if (blinkCurveEnabled) {
                Column {
                    Text(
                        text = "Blink Speed: %.1fx (%.0fms)".format(
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
                            blinkSpeedScale < 0.8f -> "Fast blink"
                            blinkSpeedScale > 1.5f -> "Slow & smooth"
                            else -> "Normal speed"
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

@Composable
fun ModelSelectorDialog(
    models: List<Live2DModelManager.ModelInfo>,
    currentModel: Live2DModelManager.ModelInfo?,
    onModelSelected: (Live2DModelManager.ModelInfo) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Select Live2D Model")
        },
        text = {
            LazyColumn {
                items(models) { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Log.i("ModelSelector", "Model clicked: ${model.name}")
                                onModelSelected(model)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = model.name == currentModel?.name,
                            onClick = {
                                Log.i("ModelSelector", "RadioButton clicked: ${model.name}")
                                onModelSelected(model)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = model.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = model.jsonFileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
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
        title = { Text("Select Camera") },
        text = {
            if (cameras.isEmpty()) {
                Text(
                    text = "No camera found. Check the camera permission and try again.",
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
                                    text = "ID ${camera.id} · sensor ${camera.sensorOrientation}° · " +
                                        "FOV %.0f°".format(camera.verticalFovDegrees),
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
                Text("Close")
            }
        }
    )
}

/** 「浏览器输出」可选高度（px）。 */
private val WEB_OUTPUT_HEIGHTS = listOf(360, 480, 640, 720, 1080)

/**
 * 浏览器输出（OBS 浏览器源）设置对话框。
 *
 * 打开后在浏览器 / OBS 里访问 `http://<手机IP>:8765/` 即可看到 Live2D 画面，
 * 页面默认透明背景，可直接作为 OBS 的浏览器源叠加到画面上。
 */
@Composable
fun WebOutputDialog(
    manager: WebOutputManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val running = manager.isRunning.value
    val clients = manager.clientCount.value
    val outputFps = manager.outputFps.value
    val error = manager.errorMessage.value
    val captureHeight = manager.captureHeight.value
    val targetFps = manager.targetFps.value
    val quality = manager.quality.value
    val urls = remember(running) { manager.lanUrls() }
    val heightIndex = WEB_OUTPUT_HEIGHTS.indexOf(captureHeight).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("浏览器输出 (OBS)") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (running) {
                        "状态：运行中 · 客户端 $clients · 输出 %.0f fps".format(outputFps)
                    } else {
                        "状态：已停止"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (running) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (urls.isEmpty()) {
                    Text(
                        text = "未找到局域网地址，请先连接 Wi-Fi / 热点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    urls.forEach { url ->
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
                    Text(if (running) "停止输出" else "启动输出")
                }

                error?.let {
                    Text(
                        text = "启动失败：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    text = "输出高度：$captureHeight px",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = heightIndex.toFloat(),
                    onValueChange = { value ->
                        val index = value.roundToInt().coerceIn(0, WEB_OUTPUT_HEIGHTS.lastIndex)
                        manager.captureHeight.value = WEB_OUTPUT_HEIGHTS[index]
                    },
                    valueRange = 0f..(WEB_OUTPUT_HEIGHTS.size - 1).toFloat(),
                    steps = WEB_OUTPUT_HEIGHTS.size - 2
                )

                Text(
                    text = "输出帧率：$targetFps fps",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = targetFps.toFloat(),
                    onValueChange = { manager.targetFps.value = it.roundToInt() },
                    valueRange = 15f..60f,
                    steps = 8
                )

                Text(
                    text = "编码质量：$quality",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { manager.quality.value = it.roundToInt() },
                    valueRange = 40f..95f,
                    steps = 10
                )

                Text(
                    text = "提示：手机与电脑需在同一局域网；输出期间请保持 App 在前台。" +
                        "OBS → 来源 → 浏览器，URL 填上面的地址，页面默认透明背景。",
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
