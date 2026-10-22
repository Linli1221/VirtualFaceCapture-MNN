package com.live2d.facecapture

import android.os.SystemClock
import android.util.Log
import com.example.commondata.ARKitBlendShapes
import com.example.commondata.CALIBRATION_DURATION_MS
import com.example.commondata.FaceKeyPoints
import com.example.commondata.HeadPose
import com.example.commondata.MIN_CALIBRATION_FRAMES
import com.example.commondata.Point3D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Author: Voine
 * Date: 2025/12/30
 * 
 * 微笑/撇嘴 BlendShape 提取器
 * 
 * ==================== 设计原理 ====================
 * 
 * 微笑检测基于嘴角相对于嘴唇中心的垂直位置：
 * - 微笑时：嘴角上扬，嘴角 Y 坐标 < 嘴唇中心 Y 坐标
 * - 撇嘴时：嘴角下垂，嘴角 Y 坐标 > 嘴唇中心 Y 坐标
 * 
 * 为了消除头部姿态的影响，我们使用相对量：
 * smileFeature = (mouthCenter.y - cornerAvg.y) / faceHeight
 * 
 * 正值 = 微笑（嘴角高于中心）
 * 负值 = 撇嘴（嘴角低于中心）
 * 
 * ==================== 俯仰角补偿 ====================
 * 
 * 当用户低头时，嘴角相对于嘴唇中心的投影位置会发生变化：
 * - 低头 (pitch > 0)：嘴角看起来更低（相对于嘴唇中心）
 * - 抬头 (pitch < 0)：嘴角看起来更高
 * 
 * 补偿策略：计算相对于校准时刻的变化量
 * 
 * ==================== Live2D 输出 ====================
 * 
 * Live2D 使用 ParamMouthForm 参数：
 * - 范围: [-1, 1] 或 [0, 1]，取决于模型
 * - 正值 = 微笑
 * - 负值 = 撇嘴
 * 
 * 计算方式: mouthForm = (smileLeft + smileRight) / 2 - (frownLeft + frownRight) / 2
 */
class SimpleSmileExtractor {

    companion object {
        private const val TAG = "SimpleSmileExtractor"

        // ============ 默认阈值 ============

        /**
         * 微笑检测死区阈值
         * 特征变化量小于此值时认为是中性表情，消除抖动
         * 数值越小，越容易检测到微笑
         */
        private const val SMILE_DEAD_ZONE = 0.003f

        /**
         * 撇嘴检测死区阈值
         * 数值越小，越容易检测到撇嘴
         */
        private const val FROWN_DEAD_ZONE = 0.002f

        /**
         * 最大微笑/撇嘴特征值（用于归一化）
         */
        private const val DEFAULT_SMILE_MAX = 0.06f
        private const val DEFAULT_FROWN_MAX = 0.04f

    }
    // ============ 校准数据 ============
    /**
     * 微笑校准数据
     */
    data class SmileCalibrationData(
        // 累积统计
        var sumLeftFeature: Float = 0f,
        var sumRightFeature: Float = 0f,
        var sumSquaredLeftFeature: Float = 0f,
        var sumSquaredRightFeature: Float = 0f,
        var frameCount: Int = 0,
        // 校准窗口起始时间（uptimeMillis），用于按时长触发结束
        var startTimestampMs: Long = 0L,
        
        // 校准结果：中性表情时的特征基线
        var baselineLeftFeature: Float = 0f,
        var baselineRightFeature: Float = 0f,
        var stdLeftFeature: Float = 0f,
        var stdRightFeature: Float = 0f,
        
        // 观察到的范围（用于动态扩展）
        var observedSmileMax: Float = DEFAULT_SMILE_MAX,
        var observedFrownMax: Float = DEFAULT_FROWN_MAX,
        
        var isCalibrated: Boolean = false
    ) {
        /**
         * 添加一帧特征数据
         */
        fun addFrame(leftFeature: Float, rightFeature: Float) {
            if (frameCount == 0) {
                startTimestampMs = SystemClock.uptimeMillis()
            }
            sumLeftFeature += leftFeature
            sumRightFeature += rightFeature
            sumSquaredLeftFeature += leftFeature * leftFeature
            sumSquaredRightFeature += rightFeature * rightFeature
            frameCount++
        }

        /**
         * 校准窗口是否已经收集够样本（时长 + 最小帧数 双门限）。
         */
        fun isWindowReady(): Boolean {
            if (frameCount < MIN_CALIBRATION_FRAMES) return false
            val elapsed = SystemClock.uptimeMillis() - startTimestampMs
            return elapsed >= CALIBRATION_DURATION_MS
        }
        
        /**
         * 完成校准
         */
        fun finishCalibration() {
            if (frameCount < 10) {
                Log.w(TAG, "Too few frames for smile calibration: $frameCount")
                return
            }
            
            // 计算平均值作为基线
            baselineLeftFeature = sumLeftFeature / frameCount
            baselineRightFeature = sumRightFeature / frameCount
            
            // 计算标准差
            val varianceLeft = (sumSquaredLeftFeature / frameCount) - (baselineLeftFeature * baselineLeftFeature)
            val varianceRight = (sumSquaredRightFeature / frameCount) - (baselineRightFeature * baselineRightFeature)
            stdLeftFeature = sqrt(max(0f, varianceLeft))
            stdRightFeature = sqrt(max(0f, varianceRight))
            
            isCalibrated = true
            
            Log.i(TAG, String.format(
                "😊 Smile calibrated: baseline L=%.4f R=%.4f, std L=%.4f R=%.4f",
                baselineLeftFeature, baselineRightFeature, stdLeftFeature, stdRightFeature
            ))
        }
        
        /**
         * 动态更新观察范围（只扩大不缩小）
         */
        fun updateObservedRange(leftDelta: Float, rightDelta: Float) {
            val avgDelta = (leftDelta + rightDelta) / 2f
            
            if (avgDelta > 0) {
                // 微笑方向
                val newMax = avgDelta * 1.2f  // 留 20% 余量
                if (newMax > observedSmileMax) {
                    observedSmileMax = newMax.coerceAtMost(0.15f)  // 上限
                }
            } else if (avgDelta < 0) {
                // 撇嘴方向
                val newMax = abs(avgDelta) * 1.2f
                if (newMax > observedFrownMax) {
                    observedFrownMax = newMax.coerceAtMost(0.10f)  // 上限
                }
            }
        }
        
        /**
         * 重置
         */
        fun reset() {
            sumLeftFeature = 0f
            sumRightFeature = 0f
            sumSquaredLeftFeature = 0f
            sumSquaredRightFeature = 0f
            frameCount = 0
            startTimestampMs = 0L
            baselineLeftFeature = 0f
            baselineRightFeature = 0f
            stdLeftFeature = 0f
            stdRightFeature = 0f
            observedSmileMax = DEFAULT_SMILE_MAX
            observedFrownMax = DEFAULT_FROWN_MAX
            isCalibrated = false
        }
    }
    
    val calibration = SmileCalibrationData()

    /**
     * 是否正在校准期
     */
    val isCalibrating: Boolean
        get() = !calibration.isCalibrated
    
    // ============ 俯仰角补偿 ============
    
    /**
     * 校准时的头部姿态
     */
    private var calibrationPitch: Float = 0f
    private var calibrationYaw: Float = 0f
    private var calibrationRoll: Float = 0f
    
    /**
     * 是否启用俯仰角补偿
     */
    var enablePitchCompensation: Boolean = true
    
    /**
     * 俯仰角补偿阈值（度）
     */
    var pitchCompensationThreshold: Float = 5f
    
    /**
     * 俯仰角补偿系数
     * 经验值：每度俯仰对微笑特征的影响
     */
    var pitchCompensationFactor: Float = 0.0008f
    
    /**
     * Roll 角补偿系数
     * 当头部倾斜时，左右嘴角高度差会变化
     */
    var rollCompensationFactor: Float = 0.001f
    
    /**
     * 设置校准时的头部姿态
     */
    fun setCalibrationPose(pitch: Float, yaw: Float, roll: Float) {
        calibrationPitch = pitch
        calibrationYaw = yaw
        calibrationRoll = roll
        Log.i(TAG, String.format(
            "📐 Smile calibration pose: pitch=%.1f°, yaw=%.1f°, roll=%.1f°",
            pitch, yaw, roll
        ))
    }
    
    /**
     * 计算俯仰角补偿
     */
    private fun calculatePitchCompensation(currentPitch: Float): Float {
        val deltaPitch = currentPitch - calibrationPitch
        if (abs(deltaPitch) <= pitchCompensationThreshold) {
            return 0f
        }
        
        // 低头时嘴角看起来更低，需要正向补偿
        val effectiveDelta = if (deltaPitch > 0) {
            deltaPitch - pitchCompensationThreshold
        } else {
            deltaPitch + pitchCompensationThreshold
        }
        
        return effectiveDelta * pitchCompensationFactor
    }
    
    fun extractSmileBlendShapes(
        keyPoints: FaceKeyPoints,
        blendShapes: ARKitBlendShapes,
        headPose: HeadPose? = null,
        landmarks: List<Point3D>? = null
    ) {
        // 使用嘴巴外轮廓点
        val mouthOuter = keyPoints.mouthOuter
        if (mouthOuter.size < 16) {
            Log.w(TAG, "Not enough mouth points: ${mouthOuter.size}")
            return
        }
        
        // 获取面部高度用于归一化
        val faceHeight = keyPoints.faceHeight
        if (faceHeight < 1e-6f) {
            return
        }
        
        // 1. 提取原始微笑特征
        //
        // 特征 = (嘴唇中心Y - 嘴角Y) / faceHeight
        // 正值 = 嘴角高于中心 = 微笑；负值 = 嘴角低于中心 = 撇嘴
        //
        // mouthOuter 索引：[0] 左嘴角、[5] 下唇中心、[10] 右嘴角、[15] 上唇中心
        //
        // 旧实现调用 extractSmileFeatures() 返回 Pair<Float, Float>：每帧 1 个 Pair
        // 对象 + 2 个装箱 Float；这里内联成两个局部 Float，运算与求和顺序完全不变。
        val leftCorner = mouthOuter[0]
        val rightCorner = mouthOuter[10]
        val upperLipCenter = mouthOuter[15]
        val lowerLipCenter = mouthOuter[5]

        // 嘴唇中心（上下唇的平均）
        val mouthCenterY = (upperLipCenter.y + lowerLipCenter.y) / 2f

        val rawLeftFeature = (mouthCenterY - leftCorner.y) / faceHeight
        val rawRightFeature = (mouthCenterY - rightCorner.y) / faceHeight

        // 2. 应用俯仰角和 Roll 角补偿
        var compensatedLeftFeature = rawLeftFeature
        var compensatedRightFeature = rawRightFeature

        if (enablePitchCompensation && headPose != null && calibration.isCalibrated) {
            val pitchComp = calculatePitchCompensation(headPose.pitch)
            compensatedLeftFeature += pitchComp
            compensatedRightFeature += pitchComp

            // Roll 补偿（原 calculateRollCompensation 返回 Pair，每帧多 1 个 Pair + 2 个装箱 Float）。
            // |Δroll| <= 3° 时两个补偿量均为 0，原实现也是直接返回 (0f, 0f)，
            // 所以这里直接跳过，效果完全相同。
            val deltaRoll = headPose.roll - calibrationRoll
            if (abs(deltaRoll) > 3f) {
                // 当头向右倾斜 (roll > 0)，左嘴角看起来更高，右嘴角看起来更低
                val rollCompensation = deltaRoll * rollCompensationFactor
                compensatedLeftFeature += -rollCompensation
                compensatedRightFeature += rollCompensation
            }
        }
        
        // 3. 校准期收集数据（使用原始特征）
        if (!calibration.isCalibrated) {
            calibration.addFrame(rawLeftFeature, rawRightFeature)
            if (calibration.isWindowReady()) {
                calibration.finishCalibration()
                // 设置校准姿态
                if (headPose != null) {
                    setCalibrationPose(headPose.pitch, headPose.yaw, headPose.roll)
                }
            }
        }
        
        // 4. 计算相对于基线的变化量
        val leftDelta = compensatedLeftFeature - calibration.baselineLeftFeature
        val rightDelta = compensatedRightFeature - calibration.baselineRightFeature
        
        // 5. 动态更新范围
        if (calibration.isCalibrated) {
            calibration.updateObservedRange(leftDelta, rightDelta)
        }
        
        // 6. 转换为 BlendShape 值
        //    原实现每帧调用两次 featureDeltaToBlendShape()，每次返回 Pair(smile, frown)
        //    （其中必然一个是 0f）⇒ 每帧 2 个 Pair + 4 个装箱 Float。
        //    拆成两个标量函数后不再产生临时对象，分支条件与原实现完全互斥等价。
        blendShapes.mouthSmileLeft = deltaToSmile(leftDelta, calibration.observedSmileMax)
        blendShapes.mouthFrownLeft = deltaToFrown(leftDelta, calibration.observedFrownMax)
        blendShapes.mouthSmileRight = deltaToSmile(rightDelta, calibration.observedSmileMax)
        blendShapes.mouthFrownRight = deltaToFrown(rightDelta, calibration.observedFrownMax)
    }

    /**
     * 特征变化量 → 微笑 BlendShape [0, 1]
     * 与原 featureDeltaToBlendShape() 的微笑分支逐位一致。
     */
    private fun deltaToSmile(delta: Float, smileMax: Float): Float {
        return if (delta > SMILE_DEAD_ZONE) {
            // 微笑
            ((delta - SMILE_DEAD_ZONE) / (smileMax - SMILE_DEAD_ZONE)).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    /**
     * 特征变化量 → 撇嘴 BlendShape [0, 1]
     * 与原 featureDeltaToBlendShape() 的撇嘴分支逐位一致。
     */
    private fun deltaToFrown(delta: Float, frownMax: Float): Float {
        return if (delta < -FROWN_DEAD_ZONE) {
            // 撇嘴
            ((abs(delta) - FROWN_DEAD_ZONE) / (frownMax - FROWN_DEAD_ZONE)).coerceIn(0f, 1f)
        } else {
            0f
        }
    }
    
    /**
     * 重置校准
     */
    fun resetCalibration() {
        calibration.reset()
        calibrationPitch = 0f
        calibrationYaw = 0f
        calibrationRoll = 0f
        Log.i(TAG, "🔄 Smile calibration reset")
    }

    fun reset() {
        resetCalibration()
    }
}