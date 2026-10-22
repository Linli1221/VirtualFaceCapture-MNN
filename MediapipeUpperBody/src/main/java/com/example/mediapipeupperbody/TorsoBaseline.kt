package com.example.mediapipeupperbody

import com.example.commondata.TorsoAngles
import kotlin.math.min

/**
 * 躯干角「相对基线」计算（纯 Kotlin，JVM 可测）。
 *
 * [reset] 之后先收集 [calibrationFrames] 个有效帧，用它们的均值作为中性姿态：
 *  - 校准期间输出 = (当前值 - 已收集帧均值) × 进度权重（0 → 1），避免刚重置时
 *    第一帧的噪声被当成「相对角」直接甩出去；
 *  - 校准完成后输出 = 当前值 - 基线；
 *  - 校准未完成就丢帧不会计入均值（[apply] 只在有效帧上调用）。
 *
 * 这与 docs/upper-body.md §3.2 第 6 条的「~10 个有效帧均值 + 10 帧内 blending」一致。
 */
class TorsoBaseline(private val calibrationFrames: Int = DEFAULT_CALIBRATION_FRAMES) {

    private var collected = 0
    private var sumRoll = 0.0
    private var sumYaw = 0.0
    private var baselineRoll = 0f
    private var baselineYaw = 0f

    /** 是否已经用满 [calibrationFrames] 帧、基线固定下来了。 */
    val isCalibrated: Boolean
        get() = collected >= effectiveFrames

    /** 重置：下一次 [apply] 开始重新收集中性姿态。 */
    fun reset() {
        collected = 0
        sumRoll = 0.0
        sumYaw = 0.0
        baselineRoll = 0f
        baselineYaw = 0f
    }

    /**
     * 把原始躯干角转成相对基线的角度。
     *
     * @param angles 几何计算出的原始躯干角（未减基线）
     * @return 相对基线的躯干角；非法输入返回 [TorsoAngles.ZERO] 且不污染校准均值
     */
    fun apply(angles: TorsoAngles): TorsoAngles {
        if (!angles.isValid) return TorsoAngles.ZERO
        if (isCalibrated) {
            return TorsoAngles(
                roll = angles.roll - baselineRoll,
                yaw = angles.yaw - baselineYaw,
            )
        }

        collected++
        sumRoll += angles.roll.toDouble()
        sumYaw += angles.yaw.toDouble()
        val meanRoll = (sumRoll / collected).toFloat()
        val meanYaw = (sumYaw / collected).toFloat()

        if (collected >= effectiveFrames) {
            baselineRoll = meanRoll
            baselineYaw = meanYaw
        }

        // 10 帧内从 0 平滑过渡到「相对当前均值」；校准完成时权重正好是 1，
        // 且均值 == 基线，所以切到稳态不会有跳变。
        val weight = min(1f, collected.toFloat() / effectiveFrames.toFloat())
        return TorsoAngles(
            roll = (angles.roll - meanRoll) * weight,
            yaw = (angles.yaw - meanYaw) * weight,
        )
    }

    private val effectiveFrames: Int
        get() = if (calibrationFrames > 0) calibrationFrames else 1

    companion object {
        /** 默认校准帧数：~10 个有效帧（15fps 下约 0.7 秒）。 */
        const val DEFAULT_CALIBRATION_FRAMES = 10
    }
}
