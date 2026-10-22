package com.example.virtualfacecapture

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.virtualfacecapture.web.GlCaptureSink
import com.live2d.demo.JniBridgeJava

/**
 * Live2D View for Jetpack Compose
 * Wraps GLSurfaceView with Live2D rendering
 * 使用 Live2DModelManager 来管理模型加载
 *
 * @param captureSink 可选：浏览器输出（OBS）的抓帧接收方。没人抓帧时渲染器直接画窗口，
 *                    不产生任何离屏 FBO / 贴回开销；有人抓帧时才走离屏路径（视觉上无差别）。
 */
@Composable
fun Live2DView(
    modifier: Modifier = Modifier,
    modelManager: Live2DModelManager,
    onModelLoadComplete: () -> Unit = {},
    onModelLoadError: () -> Unit = {},
    captureSink: GlCaptureSink? = null
) {
    var glSurfaceView: GLSurfaceView? by remember { mutableStateOf(null) }
    var isInitialized by remember { mutableStateOf(false) }

    // 用 rememberUpdatedState 包一层：抓帧接收方变化时不需要重建 GLSurfaceView
    val captureSinkState = rememberUpdatedState(captureSink)

    // 刷新率只在创建 GLSurfaceView 时采样一次（渲染器装在 View 上之后换不了）。
    // 读不到时按 60Hz 处理 —— 对渲染器来说 60Hz 等价于「不做任何节流」。
    val localContext = LocalContext.current
    val renderer = remember {
        Live2DCaptureRenderer(
            sinkProvider = { captureSinkState.value },
            refreshRate = resolveDisplayRefreshRate(localContext),
        )
    }
    
    DisposableEffect(Unit) {
        onDispose {
            Log.i("Live2DView", "Disposing Live2D view")
            glSurfaceView?.let {
                JniBridgeJava.nativeOnPause()
                JniBridgeJava.nativeOnStop()
            }
        }
    }
    
    AndroidView(
        modifier = modifier,
        factory = { context ->
            Log.i("Live2DView", "Creating GLSurfaceView for Live2D")
            
            // Initialize JniBridge context
            JniBridgeJava.SetContext(context)
            JniBridgeJava.SetActivityInstance(context as android.app.Activity)

            // Set load callback
            JniBridgeJava.setLive2DLoadInterface(object : JniBridgeJava.Live2DLoadInterface {
                override fun onLoadError() {
                    Log.e("Live2DView", "❌ JNI reported: Failed to load Live2D model")
                    onModelLoadError()
                }
                
                override fun onLoadDone() {
                    Log.i("Live2DView", "✓ JNI reported: Live2D model loaded successfully")
                    onModelLoadComplete()
                }
                
                override fun onLoadOneMotion(motionGroup: String?, index: Int, motionName: String?) {
                    Log.d("Live2DView", "Loaded motion: $motionGroup[$index] = $motionName")
                }
                
                override fun onLoadOneExpression(expressionName: String?, index: Int) {
                    Log.d("Live2DView", "Loaded expression: $expressionName[$index]")
                }
            })
            
            // Create GLSurfaceView
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                // 使用带「离屏 FBO + 抓帧」能力的渲染器；OBS 输出关闭时它等同于原 GLRenderer
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                
                Log.i("Live2DView", "GLSurfaceView created with OpenGL ES 2.0")
                
                glSurfaceView = this
                
                // Initialize on render thread
                queueEvent {
                    if (!isInitialized) {
                        Log.i("Live2DView", "Initializing Live2D on GL thread...")
                        JniBridgeJava.nativeOnStart()
                        
                        // 使用 ModelManager 加载第一个可用模型
                        Log.i("Live2DView", "Loading first available model via ModelManager...")
                        modelManager.loadFirstAvailableModel { success ->
                            if (success) {
                                Log.i("Live2DView", "✓ Model loaded successfully")
                                
                                // Configure Live2D for face tracking
                                Log.i("Live2DView", "Configuring Live2D for face tracking...")
                                
                                // Disable auto blink - we'll control it via face capture
                                JniBridgeJava.nativeAutoBlinkEyes(false)
                                Log.d("Live2DView", "  - Auto blink: disabled")
                                
                                // Enable manual eye ball control
                                JniBridgeJava.nativeProjectManualEyeBall(true)
                                Log.d("Live2DView", "  - Manual eye ball: enabled")
                                
                                // Enable breath animation
                                JniBridgeJava.nativeBreath(true)
                                Log.d("Live2DView", "  - Breath animation: enabled")
                                
                                Log.i("Live2DView", "✓ Live2D configuration complete")
                            } else {
                                Log.e("Live2DView", "❌ Failed to load model via ModelManager")
                            }
                        }
                        
                        isInitialized = true
                        Log.i("Live2DView", "Live2D view initialization complete")
                    }
                }
            }
        },
        update = { view ->
            // Update when recomposed if needed
        }
    )
}

/**
 * 读取屏幕刷新率（Hz）。
 *
 * 读不到（或拿到非法值）时返回 60Hz —— [Live2DCaptureRenderer] 对 ≤75Hz 完全不做
 * 帧率节流，也就是退回改动前的行为，这是最安全的兜底。
 */
@Suppress("DEPRECATION")
private fun resolveDisplayRefreshRate(context: Context): Float {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val direct = try {
            context.display?.refreshRate
        } catch (t: Throwable) {
            null
        }
        if (direct != null && direct.isFinite() && direct > 0f) {
            return direct
        }
    }

    // 所有 API 级别都能用的兜底。
    // 注意：不能用 resources.displayMetrics.refreshRate —— 它是 @hide 字段，编译期不可见。
    val legacy = try {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        windowManager?.defaultDisplay?.refreshRate
    } catch (t: Throwable) {
        null
    }
    if (legacy != null && legacy.isFinite() && legacy > 0f) {
        return legacy
    }
    return 60f
}

/**
 * Helper to load a specific Live2D model via ModelManager
 */
fun loadLive2DModel(modelManager: Live2DModelManager, modelName: String, onComplete: (Boolean) -> Unit) {
    val model = modelManager.getAvailableModels().find { it.name == modelName }
    if (model != null) {
        Log.i("Live2DView", "Loading model by name: $modelName")
        modelManager.loadModel(model, onComplete)
    } else {
        Log.e("Live2DView", "Model not found: $modelName")
        onComplete(false)
    }
}

/**
 * Helper to apply expression to Live2D model
 */
fun applyLive2DExpression(expressionName: String) {
    Log.d("Live2DView", "Applying expression: $expressionName")
    JniBridgeJava.nativeApplyExpression(expressionName)
}

/**
 * Helper to apply motion to Live2D model
 */
fun applyLive2DMotion(motionGroup: String, index: Int) {
    Log.d("Live2DView", "Applying motion: $motionGroup[$index]")
    JniBridgeJava.nativeApplyMotion(motionGroup, index)
}