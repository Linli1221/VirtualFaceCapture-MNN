package com.live2d.facecapture

import android.os.SystemClock
import android.util.Log
import com.example.commondata.ARKitBlendShapes
import com.example.commondata.CALIBRATION_DURATION_MS
import com.example.commondata.FaceLandmarkIndices
import com.example.commondata.HeadPose
import com.example.commondata.MIN_CALIBRATION_FRAMES
import com.example.commondata.Point3D


/**
 * Face Capture Pipeline - 完整的面部追踪管线
 */
class FaceCapturePipeline {
    // ============ 组件 ============

    // BlendShape 特征提取器
    val extractor = BlendShapeExtractor()


    // ============ 姿态基线 ============

    private var baselinePitch: Float = 0f
    private var baselineYaw: Float = 0f
    private var baselineRoll: Float = 0f
    private var isPoseCalibrated: Boolean = false

    // 用于自动校准的累积数据
    //
    // 校准窗口现在按"时长 + 最小帧数"双门限触发，而不是写死的帧数 —— 这样无论手机
    // 性能 / 实际帧率高低，校准都基于大致相同的真实时间窗口，不会在 release 包飙到
    // 60 FPS 时只用 ~330ms 就草草结束。
    private var calibrationFrameCount: Int = 0
    private var calibrationStartTimestampMs: Long = 0L
    private var calibrationPitchSum: Float = 0f
    private var calibrationYawSum: Float = 0f
    private var calibrationRollSum: Float = 0f

    // ============ 点位平均的累积求和器（常数内存） ============
    //
    // 旧实现 calibrationLandmarksAccumulator 把校准窗口内每一帧的 landmarks 列表都保存下来，
    // 窗口结束时再逐点求平均。窗口按 1000ms 计，60 FPS 下等于同时持有 ~60 × 478 个 Point3D
    // 引用（约 2.8 万个引用 / 230KB+，外加 60 个 ArrayList），是启动后最集中的一次内存与
    // GC 峰值，而且启动阶段一旦 GC 就会拖慢首秒的校准帧率。
    //
    // 新实现：每帧把坐标累加进 3 个 DoubleArray（478 个求和槽，约 11.5KB 常数内存），
    // 窗口结束时用同一个求和结果求平均。累加顺序与旧实现的 `sumOf { it[i].x.toDouble() }`
    // 完全一致（坐标维度上按帧顺序累加），因此平均值逐位可复现。
    private var calibrationLandmarkFrameCount: Int = 0
    private var calibrationLandmarkSumX: DoubleArray? = null
    private var calibrationLandmarkSumY: DoubleArray? = null
    private var calibrationLandmarkSumZ: DoubleArray? = null

    /**
     * 处理结果
     */
    data class ProcessResult(
        val blendShapes: ARKitBlendShapes,
        val relativePose: HeadPose,  // 相对于基线的姿态角（用于 Live2D 和 UI）
        val rawPose: HeadPose,       // 原始姿态角（用于调试）
        val isCalibrating: Boolean = false              // 是否正在校准中
    )

    /**
     * 是否正在校准期
     * 综合判断：姿态校准 + 眼睛校准 + 嘴部校准 + 微笑校准
     */
    val isCalibrating: Boolean
        get() = !isPoseCalibrated || extractor.isCalibrating

    /**
     * 处理一帧 MediaPipe 数据
     *
     * @param landmarks MediaPipe FaceMesh 输出的 478 个点
     * @param imageWidth 图像宽度
     * @param imageHeight 图像高度
     * @param rawPitch 原始俯仰角（度）
     * @param rawYaw 原始偏航角（度）
     * @param rawRoll 原始翻滚角（度）
     * @return 处理结果，包含 BlendShape 和相对姿态角
     */
    fun processWithPose(
        landmarks: List<Point3D>,
        imageWidth: Int = 0,
        imageHeight: Int = 0,
        rawPitch: Float,
        rawYaw: Float,
        rawRoll: Float
    ): ProcessResult {
        // 更新人脸距离估计（用于动态调整透视补偿系数）
        updateFaceDistanceFromLandmarks(landmarks, imageWidth, imageHeight)

        // 3. 提取关键点（先提取，因为校准需要用到）
        val keyPoints = FaceLandmarkIndices.extractKeyPoints(landmarks, imageWidth, imageHeight)

        // 1. 自动校准姿态基线（按"时长 + 最小帧数"窗口取平均）
        if (!isPoseCalibrated) {
            if (calibrationFrameCount == 0) {
                calibrationStartTimestampMs = SystemClock.uptimeMillis()
            }
            calibrationFrameCount++
            calibrationPitchSum += rawPitch
            calibrationYawSum += rawYaw
            calibrationRollSum += rawRoll

            // 累积点位数据（常数内存：3 × 478 个 Double 求和槽，不再保存每帧列表）
            accumulateCalibrationLandmarks(landmarks)

            val elapsedMs = SystemClock.uptimeMillis() - calibrationStartTimestampMs
            val windowReady = elapsedMs >= CALIBRATION_DURATION_MS &&
                calibrationFrameCount >= MIN_CALIBRATION_FRAMES

            if (windowReady) {
                // 注意：除数必须是实际收集到的帧数，而不是任何常量 —— 在变长窗口下，
                // 不同帧率 / 不同设备拿到的样本数都会不同。
                val n = calibrationFrameCount.toFloat()
                baselinePitch = calibrationPitchSum / n
                baselineYaw = calibrationYawSum / n
                baselineRoll = calibrationRollSum / n
                isPoseCalibrated = true

                // 使用累积的点位数据计算平均值进行校准
                calibrateWithAccumulatedData()

                Log.i("FaceCapturePipeline",
                    "✅ Auto-calibrated: pose=(%.1f, %.1f, %.1f), %d frames over %d ms".format(
                        baselinePitch, baselineYaw, baselineRoll,
                        calibrationFrameCount, elapsedMs
                    ))
            }
        }

        // 2. 计算相对姿态角（相对于校准基线）
        val relativePitch = if (isPoseCalibrated) rawPitch - baselinePitch else rawPitch
        val relativeYaw = if (isPoseCalibrated) rawYaw - baselineYaw else rawYaw
        val relativeRoll = if (isPoseCalibrated) rawRoll - baselineRoll else rawRoll

        val relativePose = HeadPose(relativePitch, relativeYaw, relativeRoll)
        val rawPose = HeadPose(rawPitch, rawYaw, rawRoll)

        // 4. 提取 BlendShape
        val rawBlendShapes = extractor.extractFullBlendShapes(keyPoints, landmarks, rawPose)

        return ProcessResult(
            blendShapes = rawBlendShapes,
            relativePose = relativePose,
            rawPose = rawPose,
            isCalibrating = isCalibrating
        )
    }

    /**
     * 使用累积的点位数据进行校准（取平均值）
     * 在自动校准完成时调用
     */
    private fun calibrateWithAccumulatedData() {
        if (calibrationLandmarkFrameCount == 0) {
            Log.w("FaceCapturePipeline", "No accumulated data for calibration")
            return
        }

        val accumulatedCount = calibrationLandmarkFrameCount

        // 计算平均 landmarks（由求和槽还原，不再遍历每帧列表）
        val avgLandmarks = averageAccumulatedLandmarks()

        // 从平均 landmarks 提取 keyPoints
        val avgKeyPoints = FaceLandmarkIndices.extractKeyPoints(avgLandmarks)

        // 使用平均值进行校准
        extractor.calibrate(avgKeyPoints, avgLandmarks)

        // 设置嘴部补偿的校准姿态（使用原始姿态角的平均值）
        extractor.setCalibrationPose(baselinePitch, baselineYaw, baselineRoll)

        // 释放累积槽
        clearCalibrationLandmarkAccumulator()

        Log.i("FaceCapturePipeline",
            "✅ Expression calibrated with $accumulatedCount frames average landmarks")
    }

    /**
     * 把一帧 landmarks 累加到求和槽里。
     *
     * 只做加法，不持有列表引用；窗口内内存占用恒定。
     */
    private fun accumulateCalibrationLandmarks(landmarks: List<Point3D>) {
        if (landmarks.isEmpty()) return

        if (calibrationLandmarkSumX == null) {
            val size = landmarks.size
            calibrationLandmarkSumX = DoubleArray(size)
            calibrationLandmarkSumY = DoubleArray(size)
            calibrationLandmarkSumZ = DoubleArray(size)
        }

        val sumX = calibrationLandmarkSumX ?: return
        val sumY = calibrationLandmarkSumY ?: return
        val sumZ = calibrationLandmarkSumZ ?: return

        // 帧尺寸与首帧不一致时跳过该帧并告警：旧实现在这种情况下会在求平均时越界崩溃，
        // 这里选择丢弃这一帧（不参与平均），不会污染基线数值。
        if (landmarks.size != sumX.size) {
            Log.w("FaceCapturePipeline",
                "Skip landmark frame with mismatched size: ${landmarks.size}, expected ${sumX.size}")
            return
        }

        val size = landmarks.size
        for (i in 0 until size) {
            val point = landmarks[i]
            sumX[i] += point.x.toDouble()
            sumY[i] += point.y.toDouble()
            sumZ[i] += point.z.toDouble()
        }
        calibrationLandmarkFrameCount++
    }

    /**
     * 由求和槽还原平均 landmarks。
     *
     * 数值路径与旧实现保持一致：先在 Double 中求和 → 转 Float → 再除以 Float 帧数，
     * 因此结果与「逐帧 sumOf().toFloat() / n」逐位相同。
     */
    private fun averageAccumulatedLandmarks(): List<Point3D> {
        val sumX = calibrationLandmarkSumX
        val sumY = calibrationLandmarkSumY
        val sumZ = calibrationLandmarkSumZ
        if (sumX == null || sumY == null || sumZ == null || calibrationLandmarkFrameCount == 0) {
            return emptyList()
        }

        val n = calibrationLandmarkFrameCount.toFloat()
        val size = sumX.size
        val avgLandmarks = ArrayList<Point3D>(size)
        for (i in 0 until size) {
            val avgX = sumX[i].toFloat() / n
            val avgY = sumY[i].toFloat() / n
            val avgZ = sumZ[i].toFloat() / n
            avgLandmarks.add(Point3D(avgX, avgY, avgZ))
        }
        return avgLandmarks
    }

    /**
     * 清空（释放）点位累积槽
     */
    private fun clearCalibrationLandmarkAccumulator() {
        calibrationLandmarkFrameCount = 0
        calibrationLandmarkSumX = null
        calibrationLandmarkSumY = null
        calibrationLandmarkSumZ = null
    }

    /**
     * 从 landmarks 估算人脸大小，更新动态补偿系数
     */
    private fun updateFaceDistanceFromLandmarks(
        landmarks: List<Point3D>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        if (landmarks.isEmpty()) return

        // 从 landmarks 计算人脸边界框
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (point in landmarks) {
            if (point.x < minX) minX = point.x
            if (point.x > maxX) maxX = point.x
            if (point.y < minY) minY = point.y
            if (point.y > maxY) maxY = point.y
        }

        // 检查是否有有效的边界框
        if (minX >= maxX || minY >= maxY) return

        val faceWidth: Float
        val faceHeight: Float

        // 判断坐标是归一化的还是像素坐标
        // 如果 maxX <= 1.0 且 maxY <= 1.0，说明是归一化坐标
        if (maxX <= 1.5f && maxY <= 1.5f && minX >= -0.5f && minY >= -0.5f) {
            // 归一化坐标，直接使用
            faceWidth = maxX - minX
            faceHeight = maxY - minY
        } else if (imageWidth > 0 && imageHeight > 0) {
            // 像素坐标，需要归一化
            faceWidth = (maxX - minX) / imageWidth
            faceHeight = (maxY - minY) / imageHeight
        } else {
            // 无法确定，跳过
            return
        }

        // 更新 SimpleEyeExtractor 的距离估计
        extractor.updateFaceDistanceEstimateNormalized(faceWidth, faceHeight)
    }

    fun setCameraFov(fov: Float) {
        extractor.setCameraFov(fov)
    }

    /**
     * 重置所有状态
     */
    fun reset() {
        // 重置姿态基线
        baselinePitch = 0f
        baselineYaw = 0f
        baselineRoll = 0f
        isPoseCalibrated = false
        calibrationFrameCount = 0
        calibrationStartTimestampMs = 0L
        calibrationPitchSum = 0f
        calibrationYawSum = 0f
        calibrationRollSum = 0f

        // 重置累积数据
        clearCalibrationLandmarkAccumulator()

        // 重置表情提取器
        extractor.reset()

        Log.i("FaceCapturePipeline", "🔄 Pipeline reset")
    }
}