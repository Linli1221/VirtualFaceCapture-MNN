package com.example.commondata

import kotlin.math.abs
import kotlin.math.max

/**
 * 上半身关键点（MediaPipe Pose / BlazePose 33 点里挑选出来的 13 个）。
 *
 * 这一份是**两端共享的唯一语义定义**：
 *  - Android 端 [MediapipeUpperBody] 模块产出 [UpperBodyPoseFrame]；
 *  - [com.example.virtualfacecapture.tracking.TrackingProtocol] 按 [UpperBodyLandmarkIndex.ORDER]
 *    的顺序把 13 个点写进 `track.ub`（见 docs/tracking-protocol.md §5.3）；
 *  - Windows 客户端 `renderer/mapping.js` 里的 `BODY_LANDMARK_ORDER` 必须与这里逐项一致。
 *
 * 改动顺序 / 数量 = 协议不兼容变更，必须同步三处 + 协议文档。
 */
object UpperBodyLandmarkIndex {

    // ---- MediaPipe Pose 原始 33 点索引 -------------------------------------

    const val NOSE = 0
    const val LEFT_EYE_INNER = 1
    const val LEFT_EYE = 2
    const val LEFT_EYE_OUTER = 3
    const val RIGHT_EYE_INNER = 4
    const val RIGHT_EYE = 5
    const val RIGHT_EYE_OUTER = 6
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val MOUTH_LEFT = 9
    const val MOUTH_RIGHT = 10
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_PINKY = 17
    const val RIGHT_PINKY = 18
    const val LEFT_INDEX = 19
    const val RIGHT_INDEX = 20
    const val LEFT_THUMB = 21
    const val RIGHT_THUMB = 22
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24

    /**
     * 协议顺序（`track.ub` 的 13 个点，4 个 float/点：x, y, z, visibility）。
     *
     * 顺序 = 从上到下：鼻子 → 眼睛 → 耳朵 → 肩膀 → 肘 → 腕 → 胯。
     * 「左 / 右」指**被拍摄者本人的左右**（MediaPipe 的语义），不是画面左右。
     */
    val ORDER: IntArray = intArrayOf(
        NOSE,          // 0
        LEFT_EYE,      // 1
        RIGHT_EYE,     // 2
        LEFT_EAR,      // 3
        RIGHT_EAR,     // 4
        LEFT_SHOULDER, // 5
        RIGHT_SHOULDER,// 6
        LEFT_ELBOW,    // 7
        RIGHT_ELBOW,   // 8
        LEFT_WRIST,    // 9
        RIGHT_WRIST,   // 10
        LEFT_HIP,      // 11
        RIGHT_HIP,     // 12
    )

    /** 协议里的点数（13）。 */
    const val COUNT = 13

    /** 协议顺序里的位置（0..12），用于两端画骨架时索引 `points`。 */
    const val P_NOSE = 0
    const val P_LEFT_EYE = 1
    const val P_RIGHT_EYE = 2
    const val P_LEFT_EAR = 3
    const val P_RIGHT_EAR = 4
    const val P_LEFT_SHOULDER = 5
    const val P_RIGHT_SHOULDER = 6
    const val P_LEFT_ELBOW = 7
    const val P_RIGHT_ELBOW = 8
    const val P_LEFT_WRIST = 9
    const val P_RIGHT_WRIST = 10
    const val P_LEFT_HIP = 11
    const val P_RIGHT_HIP = 12

    /** 调试 / HUD 用名称。 */
    fun nameOf(protocolIndex: Int): String = when (protocolIndex) {
        P_NOSE -> "nose"
        P_LEFT_EYE -> "eye_left"
        P_RIGHT_EYE -> "eye_right"
        P_LEFT_EAR -> "ear_left"
        P_RIGHT_EAR -> "ear_right"
        P_LEFT_SHOULDER -> "shoulder_left"
        P_RIGHT_SHOULDER -> "shoulder_right"
        P_LEFT_ELBOW -> "elbow_left"
        P_RIGHT_ELBOW -> "elbow_right"
        P_LEFT_WRIST -> "wrist_left"
        P_RIGHT_WRIST -> "wrist_right"
        P_LEFT_HIP -> "hip_left"
        P_RIGHT_HIP -> "hip_right"
        else -> "unknown_$protocolIndex"
    }
}

/**
 * 上半身骨架连线（按 [UpperBodyLandmarkIndex.ORDER] 的下标，不是 MediaPipe 原始索引）。
 *
 * 手机端预览叠加层与 Windows 端调试骨架都按这张表画，保证两端看起来一样。
 */
object UpperBodySkeleton {

    /** 每条边 `{a, b}`：a、b 都是协议顺序下标（0..12）。 */
    val BONES: Array<IntArray> = arrayOf(
        // 头部
        intArrayOf(UpperBodyLandmarkIndex.P_NOSE, UpperBodyLandmarkIndex.P_LEFT_EYE),
        intArrayOf(UpperBodyLandmarkIndex.P_NOSE, UpperBodyLandmarkIndex.P_RIGHT_EYE),
        intArrayOf(UpperBodyLandmarkIndex.P_LEFT_EYE, UpperBodyLandmarkIndex.P_LEFT_EAR),
        intArrayOf(UpperBodyLandmarkIndex.P_RIGHT_EYE, UpperBodyLandmarkIndex.P_RIGHT_EAR),
        intArrayOf(UpperBodyLandmarkIndex.P_LEFT_EYE, UpperBodyLandmarkIndex.P_RIGHT_EYE),
        // 脖子 / 肩线
        intArrayOf(UpperBodyLandmarkIndex.P_NOSE, UpperBodyLandmarkIndex.P_LEFT_SHOULDER),
        intArrayOf(UpperBodyLandmarkIndex.P_NOSE, UpperBodyLandmarkIndex.P_RIGHT_SHOULDER),
        intArrayOf(UpperBodyLandmarkIndex.P_LEFT_SHOULDER, UpperBodyLandmarkIndex.P_RIGHT_SHOULDER),
        // 手臂
        intArrayOf(UpperBodyLandmarkIndex.P_LEFT_SHOULDER, UpperBodyLandmarkIndex.P_LEFT_ELBOW),
        intArrayOf(UpperBodyLandmarkIndex.P_LEFT_ELBOW, UpperBodyLandmarkIndex.P_LEFT_WRIST),
        intArrayOf(UpperBodyLandmarkIndex.P_RIGHT_SHOULDER, UpperBodyLandmarkIndex.P_RIGHT_ELBOW),
        intArrayOf(UpperBodyLandmarkIndex.P_RIGHT_ELBOW, UpperBodyLandmarkIndex.P_RIGHT_WRIST),
        // 躯干
        intArrayOf(UpperBodyLandmarkIndex.P_LEFT_SHOULDER, UpperBodyLandmarkIndex.P_LEFT_HIP),
        intArrayOf(UpperBodyLandmarkIndex.P_RIGHT_SHOULDER, UpperBodyLandmarkIndex.P_RIGHT_HIP),
        intArrayOf(UpperBodyLandmarkIndex.P_LEFT_HIP, UpperBodyLandmarkIndex.P_RIGHT_HIP),
    )
}

/**
 * 单个上半身关键点。
 *
 * 坐标语义（协议 §5.3）：
 *  - [x] / [y] 是**归一化图像坐标** [0,1]，已经过「前置摄像头镜像」处理，
 *    即与手机预览上看到的方向一致；
 *  - [z] 是 MediaPipe 的相对深度（越小越靠近镜头），量纲与 x 相同（约为图宽度的比例）；
 *  - [visibility] 是 MediaPipe 给出的可见度 [0,1]，用来判断这个点能不能信。
 *
 * @param x 归一化 X，[0,1]
 * @param y 归一化 Y，[0,1]
 * @param z 相对深度（MediaPipe 原值）
 * @param visibility 可见度 [0,1]
 */
data class BodyPoint(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val visibility: Float = 0f,
) {
    /** 该点是否可信（默认可见度阈值 0.5）。 */
    fun isReliable(threshold: Float = 0.5f): Boolean = visibility >= threshold

    companion object {
        val ZERO = BodyPoint()
    }
}

/**
 * 相对躯干角（度），已减去首帧 / 重置基线时的中性姿态。
 *
 * @param roll 肩线在画面里的倾斜角，**正值 = 被拍者右肩下沉（画面上顺时针）**
 * @param yaw 肩线绕竖轴的转角，**正值 = 被拍者向自己的左侧转**（与 [HeadPoseData.yaw] 同号）
 */
data class TorsoAngles(
    val roll: Float = 0f,
    val yaw: Float = 0f,
) {
    val isValid: Boolean get() = roll.isFinite() && yaw.isFinite()

    companion object {
        val ZERO = TorsoAngles()
    }
}

/**
 * 一帧上半身关键点结果（MediaPipe Pose 产出 → 手机端平滑 / 基线后的结果）。
 *
 * 当 [tracking] = false 时 [points] 是长度 [UpperBodyLandmarkIndex.COUNT] 的全 0 占位，
 * [torso] 为 0，调用方（协议 / 客户端）应把形象的手臂平滑回默认姿态，而不是保留旧值。
 *
 * @param points 13 个点，顺序严格按 [UpperBodyLandmarkIndex.ORDER]
 * @param torso 相对躯干角（已减基线）
 * @param tracking 本帧是否真的检出了上半身
 * @param quality 关键点平均可见度 [0,1]，可以当置信度用
 * @param timestampMs 采集时间（手机本地 Unix 毫秒）
 */
data class UpperBodyPoseFrame(
    val points: List<BodyPoint> = emptyList(),
    val torso: TorsoAngles = TorsoAngles.ZERO,
    val tracking: Boolean = false,
    val quality: Float = 0f,
    val timestampMs: Long = 0L,
) {
    /** 取第 [protocolIndex] 个点（协议顺序），越界返回 [BodyPoint.ZERO]。 */
    fun point(protocolIndex: Int): BodyPoint =
        points.getOrNull(protocolIndex) ?: BodyPoint.ZERO

    /** 是否是一个「有内容的」帧（长度对、tracking、有限数值）。 */
    fun isUsable(): Boolean {
        if (!tracking) return false
        if (points.size != UpperBodyLandmarkIndex.COUNT) return false
        if (!torso.isValid) return false
        for (p in points) {
            if (!p.x.isFinite() || !p.y.isFinite() || !p.z.isFinite() || !p.visibility.isFinite()) {
                return false
            }
        }
        return true
    }

    /** 肩线中点（归一化坐标），没有人时返回 null。 */
    fun shoulderCenter(): Pair<Float, Float>? {
        val left = point(UpperBodyLandmarkIndex.P_LEFT_SHOULDER)
        val right = point(UpperBodyLandmarkIndex.P_RIGHT_SHOULDER)
        if (left.visibility < 0.3f || right.visibility < 0.3f) return null
        return (left.x + right.x) * 0.5f to (left.y + right.y) * 0.5f
    }

    /** 肩宽（归一化），越宽 = 越正对镜头。 */
    fun shoulderSpan(): Float {
        val left = point(UpperBodyLandmarkIndex.P_LEFT_SHOULDER)
        val right = point(UpperBodyLandmarkIndex.P_RIGHT_SHOULDER)
        return abs(left.x - right.x) + abs(left.y - right.y) * 0.5f
    }

    companion object {
        /** 没有检测结果时的占位帧（长度为 13 的全 0 点）。 */
        fun empty(timestampMs: Long = 0L): UpperBodyPoseFrame = UpperBodyPoseFrame(
            points = List(UpperBodyLandmarkIndex.COUNT) { BodyPoint.ZERO },
            torso = TorsoAngles.ZERO,
            tracking = false,
            quality = 0f,
            timestampMs = timestampMs,
        )

        /** 把任意长度 / 坏数据规整成合法的 13 点列表（协议序列化前的兜底）。 */
        fun sanitizePoints(points: List<BodyPoint>?): List<BodyPoint> {
            val count = UpperBodyLandmarkIndex.COUNT
            if (points == null || points.isEmpty()) return List(count) { BodyPoint.ZERO }
            return List(count) { index ->
                val p = points.getOrNull(index) ?: BodyPoint.ZERO
                BodyPoint(
                    x = if (p.x.isFinite()) p.x else 0f,
                    y = if (p.y.isFinite()) p.y else 0f,
                    z = if (p.z.isFinite()) p.z else 0f,
                    visibility = if (p.visibility.isFinite()) p.visibility.coerceIn(0f, 1f) else 0f,
                )
            }
        }
    }
}
