package com.example.mediapipeupperbody

import com.example.commondata.BodyPoint
import com.example.commondata.TorsoAngles
import com.example.commondata.UpperBodyLandmarkIndex

/**
 * 一帧上半身结果的平滑器（纯 Kotlin，JVM 可测）。
 *
 * - 13 个点逐点做 One-Euro（x / y / z 各一个滤波器；visibility 不平滑，保持原始值供
 *   上层判断可信度）；
 * - 躯干角 roll / yaw 单独用更低截止频率的 One-Euro 做轻度平滑（抖动比位置更明显）。
 *
 * 丢帧时调用 [reset]，下次重新捕获目标时从新位置开始，不会把旧位置拖过来。
 */
class UpperBodySmoother(
    pointMinCutoff: Float = OneEuroFilter.DEFAULT_MIN_CUTOFF,
    pointBeta: Float = OneEuroFilter.DEFAULT_BETA,
    torsoMinCutoff: Float = DEFAULT_TORSO_MIN_CUTOFF,
    torsoBeta: Float = DEFAULT_TORSO_BETA,
) {

    private val filtersX = Array(UpperBodyLandmarkIndex.COUNT) { OneEuroFilter(pointMinCutoff, pointBeta) }
    private val filtersY = Array(UpperBodyLandmarkIndex.COUNT) { OneEuroFilter(pointMinCutoff, pointBeta) }
    private val filtersZ = Array(UpperBodyLandmarkIndex.COUNT) { OneEuroFilter(pointMinCutoff, pointBeta) }
    private val torsoRoll = OneEuroFilter(torsoMinCutoff, torsoBeta)
    private val torsoYaw = OneEuroFilter(torsoMinCutoff, torsoBeta)

    /** 清空全部滤波器状态（丢帧 / release / 切换后端的重建）。 */
    fun reset() {
        for (i in 0 until UpperBodyLandmarkIndex.COUNT) {
            filtersX[i].reset()
            filtersY[i].reset()
            filtersZ[i].reset()
        }
        torsoRoll.reset()
        torsoYaw.reset()
    }

    /**
     * 平滑 13 个点。
     *
     * @param points 长度应为 13（按协议顺序）；不足时用 [BodyPoint.ZERO] 补齐
     * @param dtSeconds 与上一有效帧的时间差（秒）；丢帧后的第一帧会直接透传
     */
    fun smooth(points: List<BodyPoint>, dtSeconds: Float): List<BodyPoint> {
        return List(UpperBodyLandmarkIndex.COUNT) { index ->
            val point = points.getOrNull(index)
            // 缺失 / 非法坐标（extract 会置为 ZERO）直接透传、不参与平滑：
            // 否则坏帧会把该点从旧位置拖向原点，恢复时再滑回来。
            if (point == null || point == BodyPoint.ZERO) {
                BodyPoint.ZERO
            } else {
                BodyPoint(
                    x = filtersX[index].filter(point.x, dtSeconds),
                    y = filtersY[index].filter(point.y, dtSeconds),
                    z = filtersZ[index].filter(point.z, dtSeconds),
                    visibility = point.visibility,
                )
            }
        }
    }

    /** 对原始躯干角做轻度 One-Euro 平滑；非法输入原样返回（交给基线层处理）。 */
    fun smoothTorso(angles: TorsoAngles, dtSeconds: Float): TorsoAngles {
        if (!angles.isValid) return angles
        return TorsoAngles(
            roll = torsoRoll.filter(angles.roll, dtSeconds),
            yaw = torsoYaw.filter(angles.yaw, dtSeconds),
        )
    }

    companion object {
        /** 躯干角截止频率比位置更低：角度的噪声更显眼，宁可慢一点。 */
        const val DEFAULT_TORSO_MIN_CUTOFF = 0.6f
        const val DEFAULT_TORSO_BETA = 0.01f
    }
}
