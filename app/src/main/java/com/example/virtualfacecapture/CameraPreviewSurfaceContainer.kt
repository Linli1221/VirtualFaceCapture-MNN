package com.example.virtualfacecapture

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout

/**
 * 相机预览容器：底图优先走 Camera2 直出 TextureView，叠加层走 [CameraPreviewView]。
 *
 * 直出时相机预览**不再经过 YUV→RGB→Bitmap**：相机 HAL 直接送显卡合成，
 * 相机线程上零转换，预览不再与推理抢 CPU。Surface 不可用（HAL 拒绝双流组合 /
 * View 未就绪）或 [surfaceModeEnabled] 被关闭时，宿主会收到
 * onPreviewPath(null, true) 并回退到限速 + 缩尺寸的 RGB 位图预览 ——
 * [CameraPreviewView] 保持透明，叠加层在两种底图上都成立。
 *
 * 显示几何与 CameraPreviewView.drawBitmapFrame 保持同一套约定：
 * 旋转摆正 → 等比适配（fit-center，整幅画面可见）→ 前置水平镜像。
 * 叠加层坐标因此与直出画面天然对齐。
 */
class CameraPreviewSurfaceContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 叠加层（关键点 / 检测框 / 上半身骨架），永远盖在预览之上。 */
    @JvmField
    val previewView: CameraPreviewView = CameraPreviewView(context)

    private val textureView: TextureView = TextureView(context)

    /**
     * 预览路径决策回调：(cameraSurface, bitmapFrames)。
     *
     * - cameraSurface != null：挂 Camera2 直出 Surface，bitmapFrames 恒 false；
     * - cameraSurface == null：回退位图预览，bitmapFrames = UI 是否可见。
     */
    var onPreviewPath: ((Surface?, Boolean) -> Unit)? = null

    /**
     * UI 是否可见（生命周期 ≥ STARTED）。不可见时直出 Surface 与位图帧都关掉，
     * 相机线程不再为不可见的预览做任何事。
     */
    var uiVisible: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                dispatchPreviewPath()
            }
        }

    /**
     * 是否允许走直出 Surface。Camera2 报告「双流组合被 HAL 拒绝」时由宿主置
     * false，预览回退位图路径；下次拿到新 Surface（View 重建）时自动恢复。
     */
    var surfaceModeEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                dispatchPreviewPath()
            }
        }

    /**
     * 预览画面摆正所需的顺时针旋转角（与位图路径的
     * Camera2Manager.getRotationDegrees 同源），由宿主随相机更新。
     */
    var contentRotationDegrees: Int = 0
        set(value) {
            if (field != value) {
                field = value
                updateTransform()
            }
        }

    /** 前置预览习惯性镜像；必须与叠加层坐标约定一致。 */
    var mirrorHorizontal: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                updateTransform()
            }
        }

    /**
     * 摆正角的实时来源（可空；宿主给 `faceTracker.previewRotationDegrees`）。
     *
     * 转屏时 Activity 不重建（`configChanges`），相机流始终是传感器方向，但显示方向
     * 变了——摆正角必须**每次重算变换时现取**。只在换相机时推一次的
     * [contentRotationDegrees] 在转屏后会陈旧，画面会整体歪 90°。
     */
    var contentRotationDegreesProvider: (() -> Int)? = null

    /** 相机采集帧尺寸（未旋转），由宿主随相机启动 / 切换更新。 */
    private var frameWidth = 0
    private var frameHeight = 0

    /** 当前挂到 Camera2 的 Surface；TextureView 重建时换一份。 */
    private var previewSurface: Surface? = null

    /**
     * 当前 SurfaceTexture 本体。Camera2 是按 Surface **当时的默认 buffer 尺寸**协商
     * 预览流的，而 TextureView 会反复把它钉回 View 尺寸（AOSP TextureView.onSizeChanged
     * / getTextureLayer 都会 `setDefaultBufferSize(viewW, viewH)`）——必须钉回采集帧
     * 尺寸，[updateTransform] 的「纹理 == frameWidth × frameHeight」几何才成立。
     */
    private var surfaceTexture: SurfaceTexture? = null

    init {
        addView(
            textureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            previewView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                this@CameraPreviewSurfaceContainer.surfaceTexture = surfaceTexture
                // 在 Surface 进相机会话之前把 buffer 尺寸钉到采集帧尺寸；
                // 此刻还没采集尺寸（相机未启动）就保持默认，setFrameSize 会补钉。
                applyFrameBufferSize(surfaceTexture)
                previewSurface?.release()
                previewSurface = Surface(surfaceTexture)
                // 新 Surface = 新一次尝试机会（上次被 HAL 拒绝不代表这次也会）
                surfaceModeEnabled = true
                updateTransform()
                dispatchPreviewPath()
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                // TextureView.onSizeChanged 刚把 buffer 尺寸改回 View 尺寸，钉回来。
                applyFrameBufferSize(surfaceTexture)
                updateTransform()
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                this@CameraPreviewSurfaceContainer.surfaceTexture = null
                previewSurface?.release()
                previewSurface = null
                dispatchPreviewPath()
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
    }

    // 布局 / 分屏尺寸变化后重算 fit-center 变换（TextureView 铺满本容器）
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    /** 宿主告知当前采集帧尺寸（未旋转），相机启动 / 切换时调用。 */
    fun setFrameSize(width: Int, height: Int) {
        if (frameWidth != width || frameHeight != height) {
            frameWidth = width
            frameHeight = height
            surfaceTexture?.let { applyFrameBufferSize(it) }
            updateTransform()
        }
    }

    /**
     * 把预览流 buffer 尺寸钉在采集帧尺寸（未旋转）上。
     *
     * 不钉的话 TextureView 默认用 View 尺寸当 buffer 尺寸，Camera2 就按 View 尺寸
     * 协商预览流（竖屏 View ⇒ 竖长条流），画面被拉扯不说，[updateTransform] 里的
     * 旋转也会整体转错 90°（竖屏看着像横屏、横屏看着像竖屏）。相机出帧后以协商
     * 尺寸为准，TextureView 之后再改默认尺寸不会影响已建好的流。
     *
     * 没有采集尺寸（相机还没启动）时不动作。
     */
    private fun applyFrameBufferSize(surfaceTexture: SurfaceTexture) {
        if (frameWidth > 0 && frameHeight > 0) {
            surfaceTexture.setDefaultBufferSize(frameWidth, frameHeight)
        }
    }

    /** 重新评估预览路径（宿主接线完成 / 外部状态变化后调用，幂等）。 */
    fun refreshPreviewPath() {
        dispatchPreviewPath()
    }

    /** 按当前状态挑选预览路径并通知宿主。 */
    private fun dispatchPreviewPath() {
        val callback = onPreviewPath ?: return
        val surface = if (uiVisible && surfaceModeEnabled) previewSurface else null
        if (surface != null) {
            // 直出预览：预览帧由相机送显卡，不再生成 RGB 位图帧
            callback(surface, false)
        } else {
            // 回退位图预览；UI 不可见时两个模式都关
            callback(null, uiVisible)
        }
    }

    /**
     * 计算 TextureView 的显示变换：旋转摆正 → 等比适配（fit-center）→ 前置镜像。
     *
     * TextureView 默认把整幅 buffer 拉伸铺满 View，TextureView.setTransform 的矩阵
     * 作用在「拉伸后的内容」上，所以先把 View 坐标反查回 buffer 像素，再旋转 /
     * 缩放 / 平移到最终位置（Matrix 的 post* 按书写顺序依次施加）。
     */
    private fun updateTransform() {
        // 几何同步可能来自相机回调线程（onCameraChanged）；View 操作只能在 UI 线程，
        // 这里统一切回主线程，重入也只是多算一次（每帧只在几何变化时触发）。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { updateTransform() }
            return
        }
        // 转屏后显示方向变了（相机流不变），摆正角必须现取，不能用换相机时推的旧值
        val rotationDegrees = contentRotationDegreesProvider?.invoke() ?: contentRotationDegrees
        // 叠加层（直出模式下没有位图）用同一套几何算显示框，才压得住底图
        previewView.setSourceFrame(frameWidth, frameHeight, rotationDegrees)

        val bufferW = frameWidth.toFloat()
        val bufferH = frameHeight.toFloat()
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (bufferW <= 0f || bufferH <= 0f || viewW <= 0f || viewH <= 0f) return

        val rotated = rotationDegrees % 180 != 0
        val contentW = if (rotated) bufferH else bufferW
        val contentH = if (rotated) bufferW else bufferH
        // 与 drawBitmapFrame 相同的 fit-center：整幅画面可见，多余部分留空
        val scale = minOf(viewW / contentW, viewH / contentH)

        val matrix = Matrix()
        // 拉伸坐标 → buffer 像素
        matrix.setScale(bufferW / viewW, bufferH / viewH)
        // 以 buffer 中心为原点，顺时针摆正（屏幕坐标 y 轴向下）
        matrix.postTranslate(-bufferW / 2f, -bufferH / 2f)
        matrix.postRotate(rotationDegrees.toFloat())
        // fit-center 后移回 View 中心
        matrix.postScale(scale, scale)
        matrix.postTranslate(viewW / 2f, viewH / 2f)
        // 前置镜像：与位图路径 canvas.scale(-1, 1, viewW/2, viewH/2) 同一几何
        if (mirrorHorizontal) {
            matrix.postScale(-1f, 1f, viewW / 2f, viewH / 2f)
        }
        textureView.setTransform(matrix)
    }
}
