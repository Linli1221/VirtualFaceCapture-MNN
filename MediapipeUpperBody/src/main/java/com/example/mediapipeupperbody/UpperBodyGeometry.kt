package com.example.mediapipeupperbody

import com.example.commondata.BodyPoint
import com.example.commondata.TorsoAngles
import com.example.commondata.UpperBodyLandmarkIndex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * MediaPipe Pose 33 点中的一个。
 *
 * 这是 MediaPipe `NormalizedLandmark` 的**纯 Kotlin 镜像**：模块内所有几何计算都基于
 * 它和 [BodyPoint]，不 import `com.google.mediapipe.**` / `android.**`，因此几何逻辑
 * 可以在 JVM 单元测试里直接验证（见 [UpperBodyGeometryTest]）。
 *
 * @param x 归一化 X（输入 Bitmap 坐标，[0,1]）
 * @param y 归一化 Y（[0,1]，向下为正）
 * @param z 相对深度（MediaPipe 原值：越小越靠近镜头）
 * @param visibility 可见度 [0,1]；缺失时调用方传 0
 */
data class PoseLandmark(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val visibility: Float = 0f,
)

/**
 * 33 点 → 13 点上半身、可见度 / 质量、躯干角（纯 Kotlin，JVM 可测）。
 *
 * 坐标 / 符号约定（与 docs/upper-body.md §3.1 / §4 一致）：
 *  - [BodyPoint.x] / [BodyPoint.y] 是喂给 MediaPipe 的那张 Bitmap 的归一化坐标，
 *    即已经按 rotationDegrees 旋转、按 mirrorHorizontal 镜像过的画面坐标；
 *  - [TorsoAngles.roll] > 0 = 被拍者**右肩下沉**；
 *  - [TorsoAngles.yaw] > 0 = 被拍者**向自己的左侧转**。
 *
 * 关于 yaw：契约里写的是 `atan2(z_R - z_L, x_R - x_L)`。MediaPipe 的 z 越小越靠近
 * 镜头，所以「向本人左侧转」时右肩靠近镜头（z_R < z_L），z_R - z_L 为负；而镜像后
 * 画面里的左右肩顺序又依赖相机/标签，直接按字面写容易出现 ±180° 的常量偏置（
 * 基线相减后还会因 atan2 的 ±180 回绕突跳）。这里等价地实现为
 * `atan2(z_L - z_R, |x_R - x_L|)`：水平基准取绝对值、方向由深度差决定，
 * 保证「正对镜头 = 0°、向左转 > 0°」，并且值域稳定在 (-90°, 90°)。
 */
object UpperBodyGeometry {

    /** 一个点算「可信」的可见度阈值（协议 §5.3）。 */
    const val RELIABLE_VISIBILITY = 0.5f

    /** 13 点里至少多少个可信才认为本帧 tracking（契约：> 0.5 的点 ≥ 7）。 */
    const val MIN_TRACKING_POINTS = 7

    /** 躯干线（肩 / 肘 / 胯）可用所需的最低可见度。 */
    const val MIN_TORSO_VISIBILITY = 0.3f

    /** 归一化坐标下水平基准的最小值，避免两肩在画面里几乎重合时 atan2 爆掉。 */
    const val MIN_REFERENCE_SPAN = 0.02f

    private val TORSO_PAIRS: Array<IntArray> = arrayOf(
        // 优先肩线，其次肘线，最后胯线（肩不可见时的退化路径）
        intArrayOf(UpperBodyLandmarkIndex.P_LEFT_SHOULDER, UpperBodyLandmarkIndex.P_RIGHT_SHOULDER),
        intArrayOf(UpperBodyLandmarkIndex.P_LEFT_ELBOW, UpperBodyLandmarkIndex.P_RIGHT_ELBOW),
        intArrayOf(UpperBodyLandmarkIndex.P_LEFT_HIP, UpperBodyLandmarkIndex.P_RIGHT_HIP),
    )

    /**
     * 从 MediaPipe 的 33 点里按 [UpperBodyLandmarkIndex.ORDER] 取出 13 点。
     *
     * @return 长度固定为 13 的列表；输入为空 / 点数不足以覆盖 RIGHT_HIP 时返回 null
     *   （视为本帧没有 pose）。单个点坐标非法时该点置零、可见度置 0，不影响其它点。
     */
    fun extract(landmarks: List<PoseLandmark>?): List<BodyPoint>? {
        if (landmarks == null || landmarks.size <= UpperBodyLandmarkIndex.RIGHT_HIP) return null
        val points = ArrayList<BodyPoint>(UpperBodyLandmarkIndex.COUNT)
        for (index in UpperBodyLandmarkIndex.ORDER) {
            val landmark = landmarks.getOrNull(index) ?: return null
            points.add(toBodyPoint(landmark))
        }
        return points
    }

    /** 13 点的平均可见度（quality）。点数不足时返回 0。 */
    fun quality(points: List<BodyPoint>?): Float {
        if (points == null || points.size < UpperBodyLandmarkIndex.COUNT) return 0f
        var sum = 0f
        for (i in 0 until UpperBodyLandmarkIndex.COUNT) {
            val visibility = points[i].visibility
            sum += if (visibility.isFinite()) visibility.coerceIn(0f, 1f) else 0f
        }
        return sum / UpperBodyLandmarkIndex.COUNT
    }

    /**
     * 本帧是否算「检测到上半身」：13 个点里可见度 > [RELIABLE_VISIBILITY] 的至少
     * [MIN_TRACKING_POINTS] 个（契约：至少 7 个 > 0.5）。
     */
    fun isTracking(points: List<BodyPoint>?): Boolean {
        if (points == null || points.size < UpperBodyLandmarkIndex.COUNT) return false
        var reliable = 0
        for (i in 0 until UpperBodyLandmarkIndex.COUNT) {
            if (points[i].visibility > RELIABLE_VISIBILITY) reliable++
        }
        return reliable >= MIN_TRACKING_POINTS
    }

    /**
     * 由肩线（不可见时退化到肘 / 胯）计算**原始**躯干角（未减基线）。
     *
     * 计算不做平滑：调用方负责先平滑点、再对结果做轻度平滑，最后交给
     * [TorsoBaseline] 相对化。
     */
    fun torso(points: List<BodyPoint>?): TorsoAngles {
        if (points == null || points.size < UpperBodyLandmarkIndex.COUNT) return TorsoAngles.ZERO
        for (pair in TORSO_PAIRS) {
            val left = points[pair[0]]
            val right = points[pair[1]]
            if (!left.visibility.isFinite() || !right.visibility.isFinite()) continue
            if (minOf(left.visibility, right.visibility) < MIN_TORSO_VISIBILITY) continue
            if (!left.x.isFinite() || !left.y.isFinite() || !left.z.isFinite()) continue
            if (!right.x.isFinite() || !right.y.isFinite() || !right.z.isFinite()) continue

            val dx = right.x - left.x
            val dy = right.y - left.y
            val dz = right.z - left.z
            val horizontal = max(abs(dx), MIN_REFERENCE_SPAN)

            // roll：右肩比左肩低（画面 y 更大）时为正
            val roll = degrees(atan2(dy, horizontal))
            // yaw：右肩更靠近镜头（dz < 0）= 向本人左侧转 = 正
            val yaw = degrees(atan2(-dz, horizontal))
            if (!roll.isFinite() || !yaw.isFinite()) continue
            return TorsoAngles(roll = roll, yaw = yaw)
        }
        return TorsoAngles.ZERO
    }

    private fun toBodyPoint(landmark: PoseLandmark): BodyPoint {
        if (!landmark.x.isFinite() || !landmark.y.isFinite()) {
            return BodyPoint.ZERO
        }
        val visibility = if (landmark.visibility.isFinite()) {
            landmark.visibility.coerceIn(0f, 1f)
        } else {
            0f
        }
        return BodyPoint(
            x = landmark.x,
            y = landmark.y,
            z = if (landmark.z.isFinite()) landmark.z else 0f,
            visibility = visibility,
        )
    }

    private fun degrees(radians: Float): Float = (radians * 180.0 / PI).toFloat()
}
