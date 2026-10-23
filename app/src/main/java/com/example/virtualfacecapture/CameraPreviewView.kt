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
import com.vfc.log.VfcLog as Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
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
    
    // 预览帧契约与 master 一致：Camera2Manager 回调输出 RGB（w*h*3 字节）。
    // 相机线程把它转成 ARGB IntArray 后 setPixels 进位图（master 的写法）。
    // pixelBuffer 跨帧复用，尺寸不变时零分配。
    // frameFormatErrorLogged 只用于「同一种错误只打一条日志」，避免相机线程被日志刷屏。
    private var frameFormatErrorLogged = false
    private var pixelBuffer: IntArray? = null

    // ---- postInvalidate 节流（≤ 30 Hz）--------------------------------------
    // 相机帧 30 fps 与叠加数据 60 fps 会各自 postInvalidate，同一帧被标脏两次；
    // 数据源本身是持续帧流，直接丢掉多余的调用不会造成画面停滞。
    private val lastInvalidateNs = AtomicLong(0L)

    // ---- 叠加层 LUT：根治「478 点 × N 次 Set<Int>.contains 装箱」-------------
    private val lutPaint = arrayOfNulls<Paint>(LANDMARK_LUT_SIZE)
    private val lutRadius = FloatArray(LANDMARK_LUT_SIZE)

    /** LUT 构建时的开关位（drawKeyPoints / drawIris）；变化时才重建（478 次循环）。 */
    private var lutFlagsKey = -1

    // ---- 文本缓存：姿态 / 检测框分数最多 10 Hz 重新格式化 --------------------
    private var poseTextNs = 0L
    private var posePitchText = ""
    private var poseYawText = ""
    private var poseRollText = ""
    private var boxTextNs = 0L
    private var boxScoreText = ""

    // ---- onDraw 复用的绘制对象（不再每帧 new）-------------------------------
    private val detectionBoxRect = RectF()
    private val poseBackgroundRect = RectF()
    private val meshPath = Path()
    
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

    // 眉毛 / 轮廓的「连线」画笔：一次性建好复用。
    // 旧实现每次 drawLandmarkPath 都 `Paint(paint).apply { ... }`，drawMesh 打开时
    // 每帧多 3 个 Paint + 3 个 Path + 3 个局部函数引用。
    private val imageRightEyebrowStrokePaint = Paint(imageRightEyebrowPaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 150
    }

    private val imageLeftEyebrowStrokePaint = Paint(imageLeftEyebrowPaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 150
    }

    private val meshStrokePaint = Paint(meshLinePaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 150
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
        private const val TAG = "CameraPreviewView"

        /** 叠加层重绘门限：30 fps。相机预览本身也只有 30 fps，再快没有意义。 */
        private const val INVALIDATE_INTERVAL_NS = 33_000_000L

        /** 姿态 / 分数文本的重格式化门限：10 Hz（String.format 单次 5~10 µs）。 */
        private const val TEXT_REFRESH_INTERVAL_NS = 100_000_000L

        /** MediaPipe FaceMesh 的 478 个点（LUT 覆盖范围）。 */
        private const val LANDMARK_LUT_SIZE = 478

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
     * 更新相机预览位图（RGB 输入，与 master 的 Camera2Manager 输出契约一致）。
     *
     * 只在相机线程调用。旋转 / YUV→RGB 在 Camera2Manager 里完成，这里做一次
     * RGB→ARGB 转换后 [Bitmap.setPixels] 写入位图。
     *
     * 并发契约不变：相机线程只写「没在展示」的那块位图，写完原子发布；UI 线程只读
     * 发布出去的那块。uiDrawing 期间相机线程最多丢一帧，绝不覆写同一块。
     *
     * @param rgbBuffer 直接缓冲区，容量必须 ≥ width*height*3（R,G,B 顺序）
     */
    fun updateCameraFrame(rgbBuffer: ByteBuffer, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val pixelCount = width * height
        // 防御：容量和尺寸对不上就丢这一帧，绝不越界读
        if (rgbBuffer.capacity() < pixelCount * 3) {
            if (!frameFormatErrorLogged) {
                frameFormatErrorLogged = true
                Log.w(
                    TAG,
                    "updateCameraFrame expects RGB (w*h*3=" + pixelCount * 3 +
                        "), got capacity " + rgbBuffer.capacity() + ", dropping preview frames",
                )
            }
            return
        }

        // RGB → ARGB（写的是相机线程私有数组，不需要加锁；尺寸不变时零分配）
        val cached = pixelBuffer
        val pixels = if (cached != null && cached.size == pixelCount) {
            cached
        } else {
            IntArray(pixelCount).also { pixelBuffer = it }
        }
        rgbBuffer.rewind()
        for (i in pixels.indices) {
            val r = rgbBuffer.get().toInt() and 0xFF
            val g = rgbBuffer.get().toInt() and 0xFF
            val b = rgbBuffer.get().toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        rgbBuffer.rewind()

        // UI 正在绘制：这一帧直接丢掉，保证下面要写的那块位图一定没人在读
        if (uiDrawing) return

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

            // 原子发布：UI 下一次 onDraw 拿到的就是刚写完的那块
            val previous = displayBitmap.getAndSet(buffer)
            // 上一块「展示中」的位图退回给相机线程当后台缓冲。它可能还被本帧之前发起的
            // 那次 onDraw 读着，所以下一帧写它之前会先检查 uiDrawing。
            backBitmap = if (previous !== buffer) previous else null
        }

        invalidateThrottled()
    }

    /**
     * 带节流的 postInvalidate（≤ 30 Hz）。
     *
     * 相机帧（30 fps）与叠加数据（60 fps）会各自 postInvalidate，同一帧被标脏两次；
     * 数据源本身是持续帧流，丢掉多余的调用只会把这次更新延后一帧。用 CAS 做无锁
     * 节流：跨越门限的那一次调用负责重绘。
     */
    private fun invalidateThrottled() {
        val now = System.nanoTime()
        val last = lastInvalidateNs.get()
        if (now - last < INVALIDATE_INTERVAL_NS) return
        if (lastInvalidateNs.compareAndSet(last, now)) {
            postInvalidate()
        }
    }
    
    fun updateVisualizationData(data: FaceVisualizationData?) {
        visualizationDataRef.set(data)
        invalidateThrottled()
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
        invalidateThrottled()
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
        // 强制下一次绘制：清空后画面上不该再留任何东西
        lastInvalidateNs.set(0L)
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
        val mirror = mirrorHorizontal

        // Detection box（不再每帧 new RectF / 不每帧 String.format）
        if (drawDetectionBox && data.detectionBox != null) {
            val box = data.detectionBox!!
            val x1 = if (mirror) 1f - box.x1 else box.x1
            val x2 = if (mirror) 1f - box.x2 else box.x2
            val px1 = offsetX + x1 * scaleX
            val px2 = offsetX + x2 * scaleX
            val py1 = offsetY + box.y1 * scaleY
            val py2 = offsetY + box.y2 * scaleY
            detectionBoxRect.set(
                minOf(px1, px2), minOf(py1, py2),
                maxOf(px1, px2), maxOf(py1, py2),
            )
            canvas.drawRect(detectionBoxRect, detectionBoxPaint)
            canvas.drawText(
                refreshBoxScoreText(box.score),
                detectionBoxRect.left + 5,
                detectionBoxRect.top - 5,
                textPaint,
            )
        }

        // Landmarks：LUT + 下标循环，全程零分配
        // （旧实现每点 2 个 Pair + 最多 9 次 Set<Int> 装箱查找 + withIndex 的 IndexedValue）
        if (drawLandmarks && data.landmarks.isNotEmpty()) {
            ensureLandmarkLut()
            val landmarks = data.landmarks
            val count = minOf(landmarks.size, LANDMARK_LUT_SIZE)
            for (index in 0 until count) {
                val paint = lutPaint[index] ?: continue
                val landmark = landmarks[index]
                var tx = landmark.x
                if (mirror) tx = 1f - tx
                canvas.drawCircle(
                    offsetX + tx * scaleX,
                    offsetY + landmark.y * scaleY,
                    lutRadius[index],
                    paint,
                )
            }
        }

        // Mesh（drawMesh 默认 false；打开时也不再分配 Path / Paint / 函数引用）
        if (drawMesh && data.landmarks.size >= 468) {
            // 眉毛连线
            drawLandmarkPath(canvas, data.landmarks, FaceLandmarkIndices.ImageRightEyebrow.ALL, offsetX, offsetY, scaleX, scaleY, mirror, imageRightEyebrowStrokePaint)
            drawLandmarkPath(canvas, data.landmarks, FaceLandmarkIndices.ImageLeftEyebrow.ALL, offsetX, offsetY, scaleX, scaleY, mirror, imageLeftEyebrowStrokePaint)

            // 脸部轮廓连线
            drawLandmarkPath(canvas, data.landmarks, FaceLandmarkIndices.FaceOval.ALL, offsetX, offsetY, scaleX, scaleY, mirror, meshStrokePaint)
        }
    }
    
    /**
     * 绘制点位连线（复用 Path + 预建画笔，onDraw 零分配）。
     *
     * 旧实现每次调用都会 `Path()`、`Paint(paint).apply{...}`，并借 `::transformPoint`
     * 生成函数引用对象；drawMesh 打开时每帧 3 套。
     */
    private fun drawLandmarkPath(
        canvas: Canvas,
        landmarks: List<Point3D>,
        indices: List<Int>,
        offsetX: Float,
        offsetY: Float,
        scaleX: Float,
        scaleY: Float,
        mirror: Boolean,
        paint: Paint,
    ) {
        val path = meshPath
        path.reset()
        var first = true
        val count = indices.size
        for (k in 0 until count) {
            val index = indices[k]
            if (index >= landmarks.size) continue
            val landmark = landmarks[index]
            var tx = landmark.x
            if (mirror) tx = 1f - tx
            val x = offsetX + tx * scaleX
            val y = offsetY + landmark.y * scaleY
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawPoseInfo(canvas: Canvas, pose: HeadPoseData) {
        val padding = 16f
        val lineHeight = 40f
        val x = padding
        var y = height - padding - lineHeight * 3

        refreshPoseText(pose)
        poseBackgroundRect.set(x - 8, y - lineHeight, x + 280, y + lineHeight * 3 + 8)
        canvas.drawRoundRect(poseBackgroundRect, 8f, 8f, textBackgroundPaint)

        canvas.drawText(posePitchText, x, y, textPaint)
        y += lineHeight
        canvas.drawText(poseYawText, x, y, textPaint)
        y += lineHeight
        canvas.drawText(poseRollText, x, y, textPaint)
    }

    /**
     * 姿态文本最多 10 Hz 重新格式化：String.format 单次 5~10 µs，60 fps 下纯属浪费。
     */
    private fun refreshPoseText(pose: HeadPoseData) {
        val now = System.nanoTime()
        if (now - poseTextNs < TEXT_REFRESH_INTERVAL_NS) return
        poseTextNs = now
        posePitchText = String.format("Pitch: %+6.1f°", pose.pitch)
        poseYawText = String.format("Yaw:   %+6.1f°", pose.yaw)
        poseRollText = String.format("Roll:  %+6.1f°", pose.roll)
    }

    /** 检测框分数文本同样限到 10 Hz（返回缓存值，首帧立即生成）。 */
    private fun refreshBoxScoreText(score: Float): String {
        val now = System.nanoTime()
        if (now - boxTextNs >= TEXT_REFRESH_INTERVAL_NS) {
            boxTextNs = now
            boxScoreText = String.format("%.2f", score)
        }
        return boxScoreText
    }

    /**
     * 构建 / 刷新 landmark 的 LUT：索引 → (Paint, 半径)。
     *
     * 旧实现每个点都要走一遍 `when` + 最多 9 次 `Set<Int>.contains`（每次装箱一个
     * `Integer`），478 点 ⇒ 每帧约 2,900 次哈希查找 + 装箱。这里只在
     * [drawKeyPoints] / [drawIris] 变化时重建一次（478 次循环），onDraw 里退化成
     * 「一次数组下标 + 一次判空」，零分配、零哈希。
     */
    private fun ensureLandmarkLut() {
        val flagsKey = (if (drawKeyPoints) 1 else 0) or (if (drawIris) 2 else 0)
        if (flagsKey == lutFlagsKey) return
        lutFlagsKey = flagsKey
        val keyPoints = drawKeyPoints
        val iris = drawIris
        for (index in 0 until LANDMARK_LUT_SIZE) {
            // 默认就是「普通点」；命中某个部位再覆盖，省掉 when 的 else 分支
            var paint = landmarkPaint
            var radius = 2f
            when {
                // 虹膜（最高优先级）
                iris && index in IRIS_CENTER_INDICES -> { paint = irisCenterPaint; radius = 6f }
                iris && index in IRIS_ALL_INDICES -> { paint = irisEdgePaint; radius = 4f }

                // 眼睛
                keyPoints && index in IMAGE_RIGHT_EYE_INDICES -> { paint = imageRightEyePaint; radius = 4f }
                keyPoints && index in IMAGE_LEFT_EYE_INDICES -> { paint = imageLeftEyePaint; radius = 4f }

                // 眉毛
                keyPoints && index in IMAGE_RIGHT_EYEBROW_INDICES -> { paint = imageRightEyebrowPaint; radius = 4f }
                keyPoints && index in IMAGE_LEFT_EYEBROW_INDICES -> { paint = imageLeftEyebrowPaint; radius = 4f }

                // 鼻子
                keyPoints && index in NOSE_INDICES -> { paint = nosePaint; radius = 4f }

                // 嘴巴（三个关键点的亮绿标记保持原逻辑）
                keyPoints && index in MOUTH_OUTER_INDICES -> { paint = mouthOuterPaint; radius = 4f }
                keyPoints && index in MOUTH_INNER_INDICES && index == FaceLandmarkIndices.Mouth.INNER_FULL[0] -> { paint = mouthInnerKeyPaint; radius = 3f }
                keyPoints && index in MOUTH_INNER_INDICES && index == FaceLandmarkIndices.Mouth.INNER_FULL[10] -> { paint = mouthInnerKeyPaint; radius = 3f }
                keyPoints && index in MOUTH_INNER_INDICES && index == FaceLandmarkIndices.Mouth.INNER_FULL[5] -> { paint = mouthInnerKeyPaint; radius = 3f }
                keyPoints && index in MOUTH_INNER_INDICES -> { paint = mouthInnerPaint; radius = 3f }

                // 脸部轮廓
                keyPoints && index in FACE_OVAL_INDICES -> { paint = faceOvalPaint; radius = 3f }
            }
            lutPaint[index] = paint
            lutRadius[index] = radius
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clear()
    }
}