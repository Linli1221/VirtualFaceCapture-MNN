/**
 * BlendShape 特征提取器
 * 从关键点提取 ARKit BlendShape 参数
 */

package com.live2d.facecapture

import com.vfc.log.VfcLog as Log
import com.example.commondata.ARKitBlendShapes
import com.example.commondata.EyePoints
import com.example.commondata.FaceKeyPoints
import com.example.commondata.HeadPose
import com.example.commondata.Point3D

/**
 * Live2D BlendShape 特征提取器
 * 核心算法：从面部关键点计算表情参数
 */
class BlendShapeExtractor(
    val advancedMode: Boolean = false
) {
    val simpleEyeExtractor by lazy { SimpleEyeExtractor() }

    private val simpleMouthExtractor by lazy { SimpleMouthExtractor() }

    private val simpleSmileExtractor by lazy { SimpleSmileExtractor() }

    private val advancedExtractor by lazy { AdvancedBlendShapeExtractor() }

    val isCalibrating: Boolean
        get() = simpleEyeExtractor.isCalibrating ||
                simpleMouthExtractor.isCalibrating ||
                simpleSmileExtractor.isCalibrating

    /**
     * 从关键点提取 BlendShape (带头部姿态补偿)
     *
     * @param keyPoints 提取的面部关键点
     * @param headPose 头部姿态角 (pitch, yaw, roll in degrees)，用于补偿眼部 BlendShape
     * @param landmarks 原始的 478 个点位 (可选，用于 SAR 方案)
     * @return ARKit BlendShape 参数
     */
    private fun extractBlendShapes(
        keyPoints: FaceKeyPoints,
        headPose: HeadPose?,
        landmarks: List<Point3D>? = null
    ): ARKitBlendShapes {
        val blendShapes = ARKitBlendShapes()

        // 1. 提取眼部 BlendShape (支持头部姿态补偿)
        extractEyeBlendShapes(keyPoints, blendShapes, headPose)

        // 2. 提取眼球方向 (基于虹膜位置)
        extractEyeGaze(keyPoints, blendShapes)

        // 3. 提取嘴部 BlendShape
        extractMouthBlendShapes(keyPoints, blendShapes, headPose, landmarks)

        // 注意：clamp() 会 this.copy(...) 出一整个新的 ARKitBlendShapes（52 个字段），
        // 所以这里不再 clamp —— 统一放到 extractFullBlendShapes 结束处做一次。
        // clamp() 只改写 7 个字段（eyeBlinkLeft/Right、eyeWideLeft/Right、jawOpen、
        // mouthSmileLeft/Right），而高级提取器（advancedMode）只写 eyeSquint / jawL/R/
        // Forward / noseSneer / brow* / cheek* / mouthFunnel 等其余字段，两者不重叠；
        // 高级提取器也只读取 landmarks、不读取 blendShapes 的旧值。
        // 因此「先 clamp 再让高级提取器改字段，最后再 clamp 一次」与「只在最后 clamp 一次」
        // 的输出完全相同，但后者每帧少分配一个 52 字段的对象。
        return blendShapes
    }
    
    /**
     * 从关键点提取完整的 BlendShape (包括高级表情)
     *
     * @param keyPoints 提取的面部关键点
     * @param landmarks 原始的 478 个点位 (用于高级 BlendShape)
     * @param headPose 头部姿态角
     * @return 完整的 ARKit BlendShape 参数
     */
    fun extractFullBlendShapes(
        keyPoints: FaceKeyPoints,
        landmarks: List<Point3D>,
        headPose: HeadPose?,
    ): ARKitBlendShapes {
        // 首先提取基础 BlendShape
        val blendShapes = extractBlendShapes(keyPoints, headPose, landmarks)
        
        // 然后提取高级 BlendShape
        if (landmarks.size >= 468 && advancedMode) {
            advancedExtractor.extractAdvancedBlendShapes(
                landmarks = landmarks,
                blendShapes = blendShapes,
                faceHeight = keyPoints.faceHeight,
                faceWidth = keyPoints.faceWidth
            )
            
            // 提取眼部挤压
            advancedExtractor.extractEyeSquint(
                landmarks = landmarks,
                blendShapes = blendShapes,
                faceHeight = keyPoints.faceHeight
            )
        }
        
        return blendShapes.clamp()
    }

    /**
     * 提取眼部 BlendShape (最关键！)
     * @param headPose 头部姿态角，用于补偿眼部遮挡
     */
    private fun extractEyeBlendShapes(
        keyPoints: FaceKeyPoints,
        blendShapes: ARKitBlendShapes,
        headPose: HeadPose? = null
    ) {
        simpleEyeExtractor.extractEyeBlendShapes(
            keyPoints.imageLeftEye,
            keyPoints.imageRightEye,
            blendShapes,
            headPose  // 传递头部姿态用于补偿
        )
    }

    /**
     * 提取眼球方向 (基于虹膜位置)
     *
     * 原理：计算虹膜中心相对于眼眶中心的偏移
     * - 水平方向：向内看 (eyeLookIn) / 向外看 (eyeLookOut)
     * - 垂直方向：向上看 (eyeLookUp) / 向下看 (eyeLookDown)
     *
     * 输出范围 [0, 1]，其中 0 = 不看该方向，1 = 最大程度看该方向
     */
    private fun extractEyeGaze(
        keyPoints: FaceKeyPoints,
        blendShapes: ARKitBlendShapes
    ) {
        // 左眼眼球方向
        val imageLeftGaze = calculateEyeGaze(keyPoints.imageLeftEye, isLeftEye = true)
        blendShapes.eyeLookInLeft = imageLeftGaze.lookIn
        blendShapes.eyeLookOutLeft = imageLeftGaze.lookOut
        blendShapes.eyeLookUpLeft = imageLeftGaze.lookUp
        blendShapes.eyeLookDownLeft = imageLeftGaze.lookDown

        // 右眼眼球方向
        val imageRightGaze = calculateEyeGaze(keyPoints.imageRightEye, isLeftEye = false)
        blendShapes.eyeLookInRight = imageRightGaze.lookIn
        blendShapes.eyeLookOutRight = imageRightGaze.lookOut
        blendShapes.eyeLookUpRight = imageRightGaze.lookUp
        blendShapes.eyeLookDownRight = imageRightGaze.lookDown
    }

    /**
     * 眼球方向数据
     */
    private data class EyeGaze(
        val lookIn: Float,   // 向内看 (鼻子方向)
        val lookOut: Float,  // 向外看 (耳朵方向)
        val lookUp: Float,   // 向上看
        val lookDown: Float  // 向下看
    )

    /**
     * 计算一组点的几何中心。
     *
     * 旧写法是 `points.map { it.x }.average().toFloat()` 连写三次 —— 每个轴都会分配一个
     * List<Float>（Float 装箱）、一个迭代器，再做一次 Double 累加。每帧两只眼睛上下眼睑
     * 各 3 个轴，等于每帧 12 个 List + 约 84 个装箱 Float + 12 个迭代器，全部是短命对象。
     *
     * 这里改成单次遍历、Double 累加：求和顺序（下标升序）与 `average()` 完全一致，
     * 除法也同样是 Double / Int，所以结果逐位相同。
     */
    private fun centerOf(points: List<Point3D>): Point3D {
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        for (point in points) {
            sumX += point.x.toDouble()
            sumY += point.y.toDouble()
            sumZ += point.z.toDouble()
        }
        val count = points.size
        if (count == 0) return Point3D(0f, 0f, 0f)
        return Point3D(
            (sumX / count).toFloat(),
            (sumY / count).toFloat(),
            (sumZ / count).toFloat()
        )
    }

    /**
     * 计算单眼的眼球方向
     *
     * @param eye 眼部关键点
     * @param isLeftEye 是否是左眼 (影响 lookIn/lookOut 的方向判定)
     * @return 眼球方向数据
     */
    private fun calculateEyeGaze(eye: EyePoints, isLeftEye: Boolean): EyeGaze {
        val irisCenter = eye.irisCenter

        // 如果没有虹膜数据，返回默认值 (眼球居中)
        if (irisCenter == null) {
            return EyeGaze(0f, 0f, 0f, 0f)
        }

        // 计算眼眶中心 (内外眼角的中点)
        val eyeCenter = Point3D(
            (eye.innerCorner.x + eye.outerCorner.x) / 2f,
            (eye.innerCorner.y + eye.outerCorner.y) / 2f,
            (eye.innerCorner.z + eye.outerCorner.z) / 2f
        )

        // 计算眼眶宽度 (用于归一化)
        val eyeWidth = eye.innerCorner.distance2DTo(eye.outerCorner)

        // 计算眼眶高度 (上下眼睑中心的距离)
        val upperCenter = if (eye.upper.isNotEmpty()) {
            centerOf(eye.upper)
        } else eyeCenter

        val lowerCenter = if (eye.lower.isNotEmpty()) {
            centerOf(eye.lower)
        } else eyeCenter

        val eyeHeight = upperCenter.distance2DTo(lowerCenter)

        // 计算虹膜相对于眼眶中心的偏移 (归一化)
        // 注意：图像坐标系 Y 轴向下
        val offsetX = (irisCenter.x - eyeCenter.x) / (eyeWidth / 2f + 1e-6f)
        val offsetY = (irisCenter.y - eyeCenter.y) / (eyeHeight / 2f + 1e-6f)

        // 限制偏移范围 (虹膜不可能移出眼眶)
        val clampedOffsetX = offsetX.coerceIn(-1f, 1f)
        val clampedOffsetY = offsetY.coerceIn(-1f, 1f)

        // 转换为 ARKit BlendShape
        // 水平方向：
        // - 左眼：正 offsetX = 向右 = lookOut, 负 offsetX = 向左 = lookIn
        // - 右眼：正 offsetX = 向右 = lookIn, 负 offsetX = 向左 = lookOut
        val lookIn: Float
        val lookOut: Float

        if (isLeftEye) {
            // 左眼：向右(正X) = 向外，向左(负X) = 向内
            lookOut = clampedOffsetX.coerceIn(0f, 1f)
            lookIn = (-clampedOffsetX).coerceIn(0f, 1f)
        } else {
            // 右眼：向右(正X) = 向内，向左(负X) = 向外
            lookIn = clampedOffsetX.coerceIn(0f, 1f)
            lookOut = (-clampedOffsetX).coerceIn(0f, 1f)
        }

        // 垂直方向：
        // - 正 offsetY = 向下 (图像坐标系 Y 向下)
        // - 负 offsetY = 向上
        val lookDown = clampedOffsetY.coerceIn(0f, 1f)
        val lookUp = (-clampedOffsetY).coerceIn(0f, 1f)

        return EyeGaze(lookIn, lookOut, lookUp, lookDown)
    }

    /**
     * 提取嘴部 BlendShape (使用校准基线)
     *
     * 微笑/撇嘴检测使用 SmileFeature：
     * - 校准时记录中性表情基准
     * - 使用面部几何预估微笑范围
     * - 渐进式范围扩展（只扩大不缩小）
     * - 支持 Roll 补偿（头部倾斜时嘴角位移补偿）
     */
    private fun extractMouthBlendShapes(
        keyPoints: FaceKeyPoints,
        blendShapes: ARKitBlendShapes,
        headPose: HeadPose? = null,
        landmarks: List<Point3D>? = null
    ) {
        simpleMouthExtractor.extractMouthBlendShapes(
            keyPoints,
            blendShapes,
            headPose,
            landmarks
        )
        simpleSmileExtractor.extractSmileBlendShapes(
            keyPoints,
            blendShapes,
            headPose,
            landmarks
        )
    }

    /**
     * 校准 BlendShape 提取器
     *
     * @param keyPoints 当前帧的面部关键点 (用户应该保持中性表情)
     * @param landmarks 原始的 478 个点位 (用于高级 BlendShape 校准)
     */
    fun calibrate(keyPoints: FaceKeyPoints, landmarks: List<Point3D>) {
        // 校准高级 BlendShape 提取器
        if (landmarks.size >= 468 && advancedMode) {
            advancedExtractor.calibrate(
                landmarks,
                keyPoints.faceHeight,
                keyPoints.faceWidth
            )
        }
        
        Log.i("BlendShapeExtractor", "✅ All BlendShape extractors calibrated")
    }

    fun updateFaceDistanceEstimateNormalized(
        normalizedWidth: Float,
        normalizedHeight: Float
    ){
        simpleEyeExtractor.updateFaceDistanceEstimateNormalized(
            normalizedWidth,
            normalizedHeight
        )
    }

    fun setCameraFov(fovDegrees: Float){
        simpleEyeExtractor.setCameraFov(fovDegrees)
    }

    fun setCalibrationPose(pitch: Float, yaw: Float, roll: Float) {
        simpleMouthExtractor.setCalibrationPose(pitch, yaw, roll)
        simpleSmileExtractor.setCalibrationPose(pitch, yaw, roll)
        simpleEyeExtractor.setCalibrationPose(pitch, yaw)
    }

    /**
     * 重置所有自适应特征
     * 调用此方法后，系统将重新学习用户的表情基线
     */
    fun reset() {
        simpleMouthExtractor.reset()
        simpleSmileExtractor.reset()
        simpleEyeExtractor.resetCalibration()  // 重置眼睛自适应校准
        advancedExtractor.reset()
        Log.i("BlendShapeExtractor", "🔄 All adaptive features reset")
    }
}


