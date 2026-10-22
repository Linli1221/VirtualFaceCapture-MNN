package com.example.mediapipeupperbody

import kotlin.math.PI
import kotlin.math.abs

/**
 * One-Euro 低通滤波器（纯 Kotlin，JVM 可测）。
 *
 * 与经典实现（Casiez et al. 2012）一致：
 *  - 对输入的一阶导数做低通，得到「速度」估计 edx；
 *  - 截止频率 cutoff = minCutoff + beta * |edx| —— 慢时重平滑、快时跟得上；
 *  - 用 tau = 1 / (2π · cutoff) 算 alpha，输出一阶低通结果。
 *
 * 时间基准使用**相邻两帧的时间差**（秒），而不是绝对时间戳：Unix 毫秒转成 Float 秒会
 * 丢精度（1.7e9 附近 Float 的分辨率约 128 秒），这里由调用方直接传 dt。
 *
 * @param minCutoff 最小截止频率（Hz），越小越平滑
 * @param beta 速度增益，越大越快跟随
 * @param derivativeCutoff 速度估计的截止频率（Hz）
 */
class OneEuroFilter(
    private val minCutoff: Float = DEFAULT_MIN_CUTOFF,
    private val beta: Float = DEFAULT_BETA,
    private val derivativeCutoff: Float = DEFAULT_DERIVATIVE_CUTOFF,
) {

    private var lastValue: Float = Float.NaN
    private var lastDerivative: Float = 0f

    /** 是否已经收到过一个有效输入。 */
    val hasValue: Boolean
        get() = lastValue.isFinite()

    /** 清空状态。重新捕获目标（或丢帧后重新出现）时调用，避免把旧位置拖过来。 */
    fun reset() {
        lastValue = Float.NaN
        lastDerivative = 0f
    }

    /**
     * 过滤一个采样点。
     *
     * @param value 新采样值
     * @param dtSeconds 与上一次采样的时间差（秒）；<= 0 / NaN 时按 1ms 处理
     * @return 平滑后的值；输入 NaN/Inf 时返回上一次有效值（从未有效则返回 0）
     */
    fun filter(value: Float, dtSeconds: Float): Float {
        if (!value.isFinite()) {
            return if (lastValue.isFinite()) lastValue else 0f
        }
        if (!lastValue.isFinite()) {
            lastValue = value
            lastDerivative = 0f
            return value
        }
        val dt = if (dtSeconds.isFinite() && dtSeconds > 0f) {
            dtSeconds.coerceAtMost(MAX_DT_SECONDS)
        } else {
            MIN_DT_SECONDS
        }

        // 速度估计（低通）
        val rawDerivative = (value - lastValue) / dt
        val derivativeAlpha = alpha(derivativeCutoff, dt)
        lastDerivative += derivativeAlpha * (rawDerivative - lastDerivative)

        // 自适应截止频率
        val cutoff = minCutoff + beta * abs(lastDerivative)
        val valueAlpha = alpha(cutoff, dt)
        val filtered = lastValue + valueAlpha * (value - lastValue)
        lastValue = filtered
        return filtered
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        if (cutoff <= 0f) return 1f
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    companion object {
        /** 上半身关键点位置平滑默认值（docs/upper-body.md §3.2）。 */
        const val DEFAULT_MIN_CUTOFF = 1.2f
        const val DEFAULT_BETA = 0.02f
        const val DEFAULT_DERIVATIVE_CUTOFF = 1.0f

        private const val MIN_DT_SECONDS = 1f / 1000f
        private const val MAX_DT_SECONDS = 0.5f
    }
}
