package com.example.virtualfacecapture

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.example.commondata.FaceLandmarkIndices
import com.example.commondata.FaceVisualizationData
import com.example.commondata.HeadPoseData
import com.example.commondata.Point3D
import com.example.commondata.UpperBodyLandmarkIndex
import com.example.commondata.UpperBodyPoseFrame
import com.example.commondata.UpperBodySkeleton
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Custom View for rendering camera preview with face landmarks overlay
 * Uses double-buffering with AtomicReference to avoid race conditions
 */
class CameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // ---- 双缓冲：不再每帧 Bitmap.copy() --------------------------------------
    // 相机线程只写「当前没在展示」的那一块 Bitmap，写完再原子发布；UI 线程只读发布出去
    // 的那一块。每帧只有一次 setPixels，不再有整帧 ARGB_8888 拷贝（720p ≈ 3.7MB/帧）。
    private val displayBitmap = AtomicReference<Bitmap?>(null)

    // 后台缓冲（相机线程写，UI 线程不读），始终不等于 displayBitmap
    private var backBitmap: Bitmap? = null

    // 缓冲尺寸：只有尺寸变化时才重建 Bitmap
    private var frameWidth = 0
    private var frameHeight = 0

    /** UI 线程是否正在 onDraw 里读位图；绘制期间相机线程最多丢一帧，绝不覆写同一块。 */
    @Volatile
    private var uiDrawing = false

    /** 保护 displayBitmap / backBitmap / frameWidth / frameHeight 的切换。 */
    private val bitmapLock = Any()

    /** 回收旧 Bitmap 统一走主线程，避免和 UI 线程正在进行的绘制撞车。 */
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Reusable pixel array（只有相机线程访问）
    private var pixelBuffer: IntArray? = null

    // Reusable RGB scratch buffer（只有相机线程访问）——720p 下每帧 2.7 MB，
    // 逐字节 ByteBuffer.get() 会做上百万次边界检查，改成一整块拷进数组再打包。
    private var rgbBytesBuffer: ByteArray? = null
    
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    
    private val detectionBoxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    // 普通点位（灰色）
    // 普通点位（白色，半透明）
    private val landmarkPaint = Paint().apply {
        color = Color.argb(180, 255, 255, 255)  // 半透明白色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // ============ 分部位颜色定义 ============
    
    // 图像右侧眼睛- 蓝色
    private val imageRightEyePaint = Paint().apply {
        color = Color.rgb(0, 150, 255)  // 浅蓝
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 图像左侧眼睛- 青色
    private val imageLeftEyePaint = Paint().apply {
        color = Color.rgb(0, 255, 200)  // 青色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 图像右侧眉毛 - 紫色
    private val imageRightEyebrowPaint = Paint().apply {
        color = Color.rgb(180, 100, 255)  // 紫色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 图像左侧眉毛 - 粉色
    private val imageLeftEyebrowPaint = Paint().apply {
        color = Color.rgb(255, 100, 180)  // 粉色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 鼻子 - 橙色
    private val nosePaint = Paint().apply {
        color = Color.rgb(255, 165, 0)  // 橙色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 嘴巴外轮廓 - 白色
    private val mouthOuterPaint = Paint().apply {
        color = Color.rgb(255, 255, 255)  // 白色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 嘴巴内轮廓 - 深红色
    private val mouthInnerPaint = Paint().apply {
        color = Color.rgb(200, 50, 50)  // 深红色
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val mouthInnerKeyPaint = Paint().apply {
        color = Color.rgb(0, 255, 0)  // 深红色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 脸部轮廓 - 绿色
    private val faceOvalPaint = Paint().apply {
        color = Color.rgb(100, 255, 100)  // 绿色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 虹膜中心 - 黄色
    private val irisCenterPaint = Paint().apply {
        color = Color.rgb(255, 255, 0)  // 黄色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 虹膜边缘 - 金色
    private val irisEdgePaint = Paint().apply {
        color = Color.rgb(255, 200, 0)  // 金色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val meshLinePaint = Paint().apply {
        color = Color.argb(100, 0, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    // ---- 上半身骨架（MediaPipe Pose 13 点）--------------------------------
    // 连线用青色，关节点用黄色；可见度低的点用半透明 / 直接跳过。
    private val bodyBonePaint = Paint().apply {
        color = Color.rgb(0, 229, 255)  // #FF00E5FF
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val bodyJointPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val bodyJointDimPaint = Paint().apply {
        color = Color.argb(110, 255, 255, 0)  // 半透明黄：点可见但不那么可信
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 36f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    
    private val textBackgroundPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }
    
    private val srcRect = Rect()
    private val dstRect = RectF()
    
    // Thread-safe visualization data
    private val visualizationDataRef = AtomicReference<FaceVisualizationData?>(null)

    // 上半身最新一帧（MediaPipe 回调线程写、UI 线程读）
    private val upperBodyFrameRef = AtomicReference<UpperBodyPoseFrame?>(null)

    // 上半身 13 点的屏幕坐标 / 可见度复用数组：onDraw 里不允许每帧分配
    private val bodyScreenX = FloatArray(UpperBodyLandmarkIndex.COUNT)
    private val bodyScreenY = FloatArray(UpperBodyLandmarkIndex.COUNT)
    private val bodyVisibility = FloatArray(UpperBodyLandmarkIndex.COUNT)
    
    // Drawing options
    var drawLandmarks = true
    var drawDetectionBox = true
    var drawMesh = false
    var drawKeyPoints = true
    var drawIris = true
    var mirrorHorizontal = true
    var rotationDegrees = 0f

    /** 上半身骨架叠加开关（关闭时 onDraw 里连读引用都省掉）。 */
    var drawUpperBody = true
    
    companion object {
        /** 上半身连线门槛：两端可见度都 >= 这个值才画，避免低置信度点拉出乱线。 */
        private const val BODY_LINE_VISIBILITY = 0.3f

        /** 上半身关节点门槛：低于此值直接跳过；[0.3, 0.5) 用半透明画。 */
        private const val BODY_POINT_VISIBILITY = 0.3f

        /** 关节点半径（像素）。 */
        private const val BODY_JOINT_RADIUS = 5f

        // 使用统一的点位定义
        private val IMAGE_RIGHT_EYE_INDICES = FaceLandmarkIndices.ImageRightEye.ALL_EYELID.toSet()
        private val IMAGE_LEFT_EYE_INDICES = FaceLandmarkIndices.ImageLeftEye.ALL_EYELID.toSet()
        private val IMAGE_RIGHT_EYEBROW_INDICES = FaceLandmarkIndices.ImageRightEyebrow.ALL.toSet()
        private val IMAGE_LEFT_EYEBROW_INDICES = FaceLandmarkIndices.ImageLeftEyebrow.ALL.toSet()
        private val NOSE_INDICES = FaceLandmarkIndices.Nose.KEY_POINTS.toSet()
        // 使用完整的嘴巴点位定义进行绘制
        private val MOUTH_OUTER_INDICES = FaceLandmarkIndices.Mouth.OUTER_FULL.toSet()
        private val MOUTH_INNER_INDICES = FaceLandmarkIndices.Mouth.INNER_FULL.toSet()
        private val FACE_OVAL_INDICES = FaceLandmarkIndices.FaceOval.ALL.toSet()
        private val IRIS_CENTER_INDICES = FaceLandmarkIndices.Iris.ALL_CENTERS.toSet()
        private val IRIS_ALL_INDICES = FaceLandmarkIndices.Iris.ALL.toSet()
    }
    
    /**
     * Update camera frame - thread-safe with double buffering
     * Camera now outputs already-rotated frames, so no rotation needed here
     *
     * 只在相机线程调用。整数像素数组是相机线程私有的，位图是双缓冲的：
     * 这里永远只写「没在展示」的那一块，写完再原子发布，UI 线程按引用读取。
     */
    fun updateCameraFrame(rgbBuffer: ByteBuffer, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val pixelCount = width * height
        val byteCount = pixelCount * 3
        // 防御：尺寸和 buffer 对不上时直接丢帧，避免越界读
        if (rgbBuffer.capacity() < byteCount) return

        // UI 正在绘制：这一帧直接丢掉（转换也一起省掉），保证下面要写的那块位图
        // 一定没人在读。720p 下一帧转换不便宜，能省就省。
        if (uiDrawing) return

        // Reuse pixel buffer（尺寸不变时零分配）
        val cached = pixelBuffer
        val pixels = if (cached != null && cached.size == pixelCount) {
            cached
        } else {
            IntArray(pixelCount).also { pixelBuffer = it }
        }
        
        // Convert RGB to ARGB（写的是相机线程私有数组，不需要加锁）。
        // 先整块 memcpy 进 ByteArray，再按像素打包：直接对 direct ByteBuffer
        // 逐字节 get() 在 720p 下就是每帧 270 万次调用，光边界检查就能把相机
        // 线程的帧预算吃掉。
        val cachedBytes = rgbBytesBuffer
        val rgbBytes = if (cachedBytes != null && cachedBytes.size == byteCount) {
            cachedBytes
        } else {
            ByteArray(byteCount).also { rgbBytesBuffer = it }
        }
        rgbBuffer.clear()
        rgbBuffer.get(rgbBytes)
        var src = 0
        for (i in 0 until pixelCount) {
            val r = rgbBytes[src].toInt() and 0xFF
            val g = rgbBytes[src + 1].toInt() and 0xFF
            val b = rgbBytes[src + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            src += 3
        }
        
        // UI 正在绘制时会在上一段提前丢帧，走到这里说明可以安全发布
        synchronized(bitmapLock) {
            // 尺寸变了：摘掉发布出去的引用，旧图交给主线程回收后重建
            if (frameWidth != width || frameHeight != height) {
                recycleOnMain(displayBitmap.getAndSet(null))
                recycleOnMain(backBitmap)
                backBitmap = null
                frameWidth = width
                frameHeight = height
            }
            
            val displayed = displayBitmap.get()
            var reusable = backBitmap
            if (reusable != null && (reusable === displayed || reusable.isRecycled ||
                    reusable.width != width || reusable.height != height)
            ) {
                // 后台缓冲不能再用：不是展示中的那块才回收，否则只丢引用等 GC
                if (reusable !== displayed && !reusable.isRecycled) {
                    recycleOnMain(reusable)
                }
                reusable = null
            }
            val buffer = reusable
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            buffer.setPixels(pixels, 0, width, 0, 0, width, height)
            
            // 原子发布：UI 下一次 onDraw 拿到的就是刚写完的这块
            val previous = displayBitmap.getAndSet(buffer)
            // 上一块「展示中」的位图退回给相机线程当后台缓冲。它可能还被本帧之前发起的
            // 那次 onDraw 读着，所以下一帧写它之前会先检查 uiDrawing。
            backBitmap = if (previous !== buffer) previous else null
        }
        
        postInvalidate()
    }
    
    fun updateVisualizationData(data: FaceVisualizationData?) {
        visualizationDataRef.set(data)
        postInvalidate()
    }

    /**
     * 更新上半身骨架（约 15fps，从 MediaPipe 回调线程调用）。
     *
     * 传 null 表示关闭 / 已停止，下一帧 onDraw 就不会再画骨架。
     *
     * 坐标语义（docs/upper-body.md §3.2 / §3.5）：[UpperBodyPoseFrame.points] 的 x/y
     * **已经是镜像后的「预览方向」**（检测器在镜像过的输入像素上推理），所以这里不
     * 需要（也**不能**）再跟随 [drawBitmapFrame] 里 bitmap 的那个 scale(-1,1) 翻转。
     */
    fun updateUpperBody(frame: UpperBodyPoseFrame?) {
        upperBodyFrameRef.set(frame)
        postInvalidate()
    }
    
    /**
     * 把旧 Bitmap 丢给主线程回收。
     *
     * 相机线程不能直接 recycle：UI 线程可能正在 onDraw 里读同一块位图。
     * 重复进入这里也没关系，回收前会再确认一次 isRecycled。
     */
    private fun recycleOnMain(bitmap: Bitmap?) {
        if (bitmap == null) return
        mainHandler.post {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
    
    /**
     * 清空预览状态。
     *
     * 只在主线程调用（LaunchedEffect / onDetachedFromWindow）：主线程上不可能有正在
     * 进行的 onDraw，所以这里可以立刻摘掉引用并回收。
     */
    fun clear() {
        synchronized(bitmapLock) {
            recycleOnMain(displayBitmap.getAndSet(null))
            recycleOnMain(backBitmap)
            backBitmap = null
            frameWidth = 0
            frameHeight = 0
        }
        visualizationDataRef.set(null)
        upperBodyFrameRef.set(null)
        pixelBuffer = null
        rgbBytesBuffer = null
        postInvalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 告诉相机线程「UI 正在读发布出去的位图」：这段时间它只会丢帧，不会覆写同一块
        uiDrawing = true
        try {
            drawBitmapFrame(canvas)
        } finally {
            uiDrawing = false
        }
    }
    
    private fun drawBitmapFrame(canvas: Canvas) {
        val bitmap = displayBitmap.get() ?: return
        if (bitmap.isRecycled) return
        
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val bitmapWidth = bitmap.width.toFloat()
        val bitmapHeight = bitmap.height.toFloat()
        
        val viewAspect = viewWidth / viewHeight
        val bitmapAspect = bitmapWidth / bitmapHeight
        
        val scale: Float
        val dx: Float
        val dy: Float
        
        if (bitmapAspect > viewAspect) {
            scale = viewWidth / bitmapWidth
            dx = 0f
            dy = (viewHeight - bitmapHeight * scale) / 2
        } else {
            scale = viewHeight / bitmapHeight
            dx = (viewWidth - bitmapWidth * scale) / 2
            dy = 0f
        }
        
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        dstRect.set(dx, dy, dx + bitmapWidth * scale, dy + bitmapHeight * scale)
        
        try {
            if (mirrorHorizontal) {
                canvas.save()
                canvas.scale(-1f, 1f, viewWidth / 2, viewHeight / 2)
                canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
                canvas.restore()
            } else {
                canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
            }
        } catch (e: Exception) {
            return
        }
        
        visualizationDataRef.get()?.let { data ->
            drawFaceVisualization(canvas, data, dstRect)
            drawPoseInfo(canvas, data.headPose)
        }

        // 上半身骨架（可选功能）：没有帧 / 没检出 / 开关关闭时完全不画。
        // 注意在 bitmap 的 canvas.scale(-1,1) 之外画 —— 关键点坐标已经是预览方向。
        if (drawUpperBody) {
            val upperBodyFrame = upperBodyFrameRef.get()
            if (upperBodyFrame != null && upperBodyFrame.tracking) {
                drawUpperBodySkeleton(canvas, upperBodyFrame, dstRect)
            }
        }
    }

    /**
     * 画上半身骨架：13 个关节点 + [UpperBodySkeleton.BONES] 连线。
     *
     * 坐标系：直接映射到 [dstRect]（与相机画面完全重合的区域）。前置摄像头时整张
     * bitmap 在 [drawBitmapFrame] 里被 canvas.scale(-1,1) 翻转，但上半身点是检测器在
     * **已镜像的输入像素**上算出来的，x/y 已经是预览方向，所以这里不能再翻转。
     *
     * 性能：坐标写进复用数组，onDraw 里零分配；低可见度的点和线跳过 / 半透明。
     */
    private fun drawUpperBodySkeleton(canvas: Canvas, frame: UpperBodyPoseFrame, dstRect: RectF) {
        if (frame.points.size < UpperBodyLandmarkIndex.COUNT) return

        val offsetX = dstRect.left
        val offsetY = dstRect.top
        val scaleX = dstRect.width()
        val scaleY = dstRect.height()

        for (index in 0 until UpperBodyLandmarkIndex.COUNT) {
            val point = frame.points[index]
            bodyScreenX[index] = offsetX + point.x * scaleX
            bodyScreenY[index] = offsetY + point.y * scaleY
            bodyVisibility[index] = point.visibility
        }

        // 骨架连线：两端都足够可信才画
        for (bone in UpperBodySkeleton.BONES) {
            val a = bone[0]
            val b = bone[1]
            if (bodyVisibility[a] < BODY_LINE_VISIBILITY || bodyVisibility[b] < BODY_LINE_VISIBILITY) {
                continue
            }
            canvas.drawLine(
                bodyScreenX[a], bodyScreenY[a],
                bodyScreenX[b], bodyScreenY[b],
                bodyBonePaint,
            )
        }

        // 关节点：不可信的跳过，勉强可信的半透明
        for (index in 0 until UpperBodyLandmarkIndex.COUNT) {
            val visibility = bodyVisibility[index]
            if (visibility < BODY_POINT_VISIBILITY) continue
            val paint = if (visibility >= 0.5f) bodyJointPaint else bodyJointDimPaint
            canvas.drawCircle(bodyScreenX[index], bodyScreenY[index], BODY_JOINT_RADIUS, paint)
        }
    }
    
    private fun drawFaceVisualization(canvas: Canvas, data: FaceVisualizationData, dstRect: RectF) {
        val scaleX = dstRect.width()
        val scaleY = dstRect.height()
        val offsetX = dstRect.left
        val offsetY = dstRect.top
        
        fun transformPoint(lx: Float, ly: Float): Pair<Float, Float> {
            var tx = lx
            val ty = ly
            
            if (mirrorHorizontal) {
                tx = 1f - tx
            }
            
            return Pair(offsetX + tx * scaleX, offsetY + ty * scaleY)
        }
        
        // Detection box
        if (drawDetectionBox && data.detectionBox != null) {
            val box = data.detectionBox!!
            val (x1, y1) = transformPoint(box.x1, box.y1)
            val (x2, y2) = transformPoint(box.x2, box.y2)
            
            val boxRect = RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
            canvas.drawRect(boxRect, detectionBoxPaint)
            canvas.drawText(String.format("%.2f", box.score), boxRect.left + 5, boxRect.top - 5, textPaint)
        }
        
        // Landmarks - 按部位用不同颜色绘制
        if (drawLandmarks && data.landmarks.isNotEmpty()) {
            for ((index, landmark) in data.landmarks.withIndex()) {
                val (x, y) = transformPoint(landmark.x, landmark.y)
                
                // 根据点位所属部位选择颜色和大小
                val (paint, radius) = when {
                    // 虹膜（最高优先级）
                    drawIris && index in IRIS_CENTER_INDICES -> Pair(irisCenterPaint, 6f)
                    drawIris && index in IRIS_ALL_INDICES -> Pair(irisEdgePaint, 4f)
                    
                    // 眼睛
                    drawKeyPoints && index in IMAGE_RIGHT_EYE_INDICES -> Pair(imageRightEyePaint, 4f)
                    drawKeyPoints && index in IMAGE_LEFT_EYE_INDICES -> Pair(imageLeftEyePaint, 4f)
                    
                    // 眉毛
                    drawKeyPoints && index in IMAGE_RIGHT_EYEBROW_INDICES -> Pair(imageRightEyebrowPaint, 4f)
                    drawKeyPoints && index in IMAGE_LEFT_EYEBROW_INDICES -> Pair(imageLeftEyebrowPaint, 4f)
                    
                    // 鼻子
                    drawKeyPoints && index in NOSE_INDICES -> Pair(nosePaint, 4f)
                    
                    // 嘴巴
                    drawKeyPoints && index in MOUTH_OUTER_INDICES -> Pair(mouthOuterPaint, 4f)
                    drawKeyPoints && index in MOUTH_INNER_INDICES && drawKeyPoints && index == FaceLandmarkIndices.Mouth.INNER_FULL[0] -> Pair(mouthInnerKeyPaint, 3f)
                    drawKeyPoints && index in MOUTH_INNER_INDICES && drawKeyPoints && index == FaceLandmarkIndices.Mouth.INNER_FULL[10] -> Pair(mouthInnerKeyPaint, 3f)
                    drawKeyPoints && index in MOUTH_INNER_INDICES && drawKeyPoints && index == FaceLandmarkIndices.Mouth.INNER_FULL[5] -> Pair(mouthInnerKeyPaint, 3f)
                    drawKeyPoints && index in MOUTH_INNER_INDICES -> Pair(mouthInnerPaint, 3f)

                    // 脸部轮廓
                    drawKeyPoints && index in FACE_OVAL_INDICES -> Pair(faceOvalPaint, 3f)
                    
                    // 普通点
                    else -> Pair(landmarkPaint, 2f)
                }
                
                canvas.drawCircle(x, y, radius, paint)
            }
        }
        
        // Mesh
        if (drawMesh && data.landmarks.size >= 468) {
            // 眉毛连线
            drawLandmarkPath(canvas, data.landmarks, FaceLandmarkIndices.ImageRightEyebrow.ALL, ::transformPoint, imageRightEyebrowPaint)
            drawLandmarkPath(canvas, data.landmarks, FaceLandmarkIndices.ImageLeftEyebrow.ALL, ::transformPoint, imageLeftEyebrowPaint)
            
            // 脸部轮廓连线
            drawLandmarkPath(canvas, data.landmarks, FaceLandmarkIndices.FaceOval.ALL, ::transformPoint, meshLinePaint)
        }
    }
    
    /**
     * 绘制点位连线
     */
    private fun drawLandmarkPath(
        canvas: Canvas,
        landmarks: List<Point3D>,
        indices: List<Int>,
        transformPoint: (Float, Float) -> Pair<Float, Float>,
        paint: Paint
    ) {
        val path = Path()
        var first = true
        for (index in indices) {
            if (index >= landmarks.size) continue
            val (x, y) = transformPoint(landmarks[index].x, landmarks[index].y)
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        
        val linePaint = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = 150
        }
        canvas.drawPath(path, linePaint)
    }
    
    private fun drawPoseInfo(canvas: Canvas, pose: HeadPoseData) {
        val padding = 16f
        val lineHeight = 40f
        val x = padding
        var y = height - padding - lineHeight * 3
        
        canvas.drawRoundRect(RectF(x - 8, y - lineHeight, x + 280, y + lineHeight * 3 + 8), 8f, 8f, textBackgroundPaint)
        
        canvas.drawText(String.format("Pitch: %+6.1f°", pose.pitch), x, y, textPaint)
        y += lineHeight
        canvas.drawText(String.format("Yaw:   %+6.1f°", pose.yaw), x, y, textPaint)
        y += lineHeight
        canvas.drawText(String.format("Roll:  %+6.1f°", pose.roll), x, y, textPaint)
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clear()
    }
}