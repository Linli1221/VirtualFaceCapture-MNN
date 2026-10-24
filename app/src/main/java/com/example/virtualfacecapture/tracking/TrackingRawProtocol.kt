package com.example.virtualfacecapture.tracking

import com.example.commondata.Point3D
import com.example.commondata.UpperBodyPoseFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * rawTrackV1 二进制追踪帧编码器（docs/raw-track-protocol.md §4 / §5 —— 逐字节唯一契约）。
 *
 * 一条 WS message（OP_BINARY）的 payload = 72 字节帧头 + 478 × 6 字节关键点块 +
 * （可选）128 字节 ubRaw 扩展块，全部 little-endian。
 *
 * 职责边界：
 *  - 本对象**只做序列化**：量化、打包、flags 置位、非有限值兜底；
 *  - 不做任何后处理（基线 / 平滑 / BlendShape / 眨眼），rawTrackV1 帧携带真·未滤波
 *    MNN 感知输出（flags bit5 = LANDMARKS_PRE_SMOOTHED 主口径恒 0）；
 *  - 不依赖任何 Android API（与 [TrackingProtocol] 同口径），可直接进 JVM 单测。
 *
 * 性能：每帧一次 [ByteBuffer] 顺序写（协议 §9 预算 < 0.3 ms）；逐点只做标量运算 +
 * putShort / putFloat，**不逐点装箱**。输出 ByteArray 是每帧新分配（3068 B 上限）——
 * 发送队列是异步消费的，复用同一底层数组会让排队中的帧互相踩踏，正确性优先。
 */
object TrackingRawProtocol {

    // ---------------------------------------------------------------- 帧格式常量

    /** 帧头 magic："VFCR"。 */
    const val MAGIC_0 = 0x56 // 'V'
    const val MAGIC_1 = 0x46 // 'F'
    const val MAGIC_2 = 0x43 // 'C'
    const val MAGIC_3 = 0x52 // 'R'

    /** 帧格式版本（协议 §4.1，当前 1）。 */
    const val FRAME_VERSION = 1

    /** msgType：0x01 = RAW_TRACK，其余保留。 */
    const val MSG_TYPE_RAW_TRACK = 0x01

    /** 帧头大小（0x48）。 */
    const val HEADER_BYTES = 72

    /** 关键点块：478 点 × 6 字节。 */
    const val LANDMARK_COUNT = 478
    const val LANDMARK_STRIDE = 6
    const val LANDMARK_BLOCK_BYTES = LANDMARK_COUNT * LANDMARK_STRIDE

    /** ubRaw 扩展块大小（§5）。 */
    const val UB_EXT_BYTES = 128

    /** ubRaw 扩展块内的 13 点 × 8 字节。 */
    const val UB_POINT_COUNT = 13
    private const val UB_POINT_STRIDE = 8

    /** 关键点 z / ubRaw z 的统一 scale 因子（§4.3）。 */
    const val Z_SCALE = 5.0f

    // ---------------------------------------------------------------- flags 位

    /** bit0：本帧检测到人脸（0 = 无脸，关键点块全 0 占位）。 */
    const val FLAG_FACE_VALID = 1 shl 0

    /** bit1：帧尾存在 128 字节 ubRaw 扩展块。 */
    const val FLAG_UB_EXT_PRESENT = 1 shl 1

    /** bit2：poseRaw 三轴有限有效（0 时接收端忽略 poseRaw）。 */
    const val FLAG_POSE_VALID = 1 shl 2

    /** bit3：量化时发生过 x/y/z 裁剪（诊断用）。 */
    const val FLAG_LANDMARKS_CLAMPED = 1 shl 3

    /** bit4：sensor timestamp 扩展（保留；v1 发送端恒 0，接收端忽略）。 */
    const val FLAG_SENSOR_TIMESTAMP_EXT = 1 shl 4

    /** bit5：关键点域已在手机端滤波（回退口径才置 1；主口径旁路 EMA 恒 0）。 */
    const val FLAG_LANDMARKS_PRE_SMOOTHED = 1 shl 5

    /** 量化常量。 */
    private const val XY_SCALE = 65535.0
    private const val Z_Q_MAX = 32767.0

    // ---------------------------------------------------------------- 量化（协议 §4.3 / §5）

    /**
     * x/y 量化：x_q = round(clamp(x, 0, 1) * 65535)，u16。
     *
     * [clamped] 为逐点累计的裁剪标志（非有限值按 0 处理并同样计裁剪，§8.4）。
     */
    fun quantizeXy(value: Float, clamped: BooleanArray): Int {
        if (!value.isFinite()) {
            clamped[0] = true
            return 0
        }
        val v = if (value < 0f) {
            clamped[0] = true
            0.0
        } else if (value > 1f) {
            clamped[0] = true
            1.0
        } else {
            value.toDouble()
        }
        return Math.round(v * XY_SCALE).toInt()
    }

    /** z 量化：z_q = round(clamp(z / Z_SCALE, -1, 1) * 32767)，i16。 */
    fun quantizeZ(value: Float, clamped: BooleanArray): Int {
        if (!value.isFinite()) {
            clamped[0] = true
            return 0
        }
        val scaled = value / Z_SCALE
        val v = if (scaled < -1f) {
            clamped[0] = true
            -1.0
        } else if (scaled > 1f) {
            clamped[0] = true
            1.0
        } else {
            scaled.toDouble()
        }
        return Math.round(v * Z_Q_MAX).toInt()
    }

    /** visibility 量化：vis_q = round(clamp(vis, 0, 1) * 65535)，u16。 */
    fun quantizeVisibility(value: Float): Int {
        if (!value.isFinite()) return 0
        val v = value.coerceIn(0f, 1f).toDouble()
        return Math.round(v * XY_SCALE).toInt()
    }

    /** 浮点字段写入前的非有限兜底（presence / box.score 另行裁剪 [0,1]）。 */
    private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f

    private fun clampUnitOrZero(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    // ---------------------------------------------------------------- 编码

    /**
     * 编码一帧 RAW_TRACK（msgType 0x01）。
     *
     * @param frameId 采集序号（相机回调处分配的主键，协议 §7；按 u32 存储，取低 32 位）
     * @param timestampMs 采集时间戳：相机回调 wall-clock 毫秒（u64）
     * @param cameraFps 相机采集帧率（无统计时 0）
     * @param width 当帧图幅宽（px，旋转后）
     * @param height 当帧图幅高（px，旋转后）
     * @param presence 人脸存在置信度（裁剪 [0,1]）
     * @param inferenceMs 本帧感知推理耗时（ms，无统计时 0）
     * @param posePitch / poseYaw / poseRoll 原始头姿（**rawHeadPose，未减任何基线**）；
     *   任一轴非有限时 POSE_VALID = 0 且 poseRaw 字节写 0
     * @param boxLeft / boxTop / boxRight / boxBottom 检测框（归一化，可越界；非有限写 0）
     * @param boxScore 检测框置信度（裁剪 [0,1]，无检测时 0）
     * @param faceValid 本帧是否检测到人脸（对应 flags FACE_VALID）
     * @param landmarks 478 点（原始索引序）；null 或数量不符时按 FACE_VALID = 0 的全 0 占位打包
     * @param preSmoothed 关键点域是否已手机端滤波（回退口径才 true → flags bit5）
     * @param upperBody ubRaw 扩展块（§5）；非 null 时整块发送（全有或全无），
     *   期望是 [UpperBodyPoseFrame.rawMode] = true 的原始 13 点帧
     * @return 完整 WS message payload（72 + 2868 [+ 128] 字节）
     */
    fun encodeRawTrack(
        frameId: Long,
        timestampMs: Long,
        cameraFps: Float,
        width: Int,
        height: Int,
        presence: Float,
        inferenceMs: Float,
        posePitch: Float,
        poseYaw: Float,
        poseRoll: Float,
        boxLeft: Float,
        boxTop: Float,
        boxRight: Float,
        boxBottom: Float,
        boxScore: Float,
        faceValid: Boolean,
        landmarks: List<Point3D>?,
        preSmoothed: Boolean = false,
        upperBody: UpperBodyPoseFrame? = null,
    ): ByteArray {
        val ubExt = upperBody != null
        val total = HEADER_BYTES + LANDMARK_BLOCK_BYTES + if (ubExt) UB_EXT_BYTES else 0
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)

        var flags = 0
        val clamped = BooleanArray(1)

        // ---- 帧头（72 B，逐字段按偏移写，不依赖结构体对齐）----
        buffer.put(0, MAGIC_0.toByte())
        buffer.put(1, MAGIC_1.toByte())
        buffer.put(2, MAGIC_2.toByte())
        buffer.put(3, MAGIC_3.toByte())
        buffer.put(4, FRAME_VERSION.toByte())
        buffer.put(5, MSG_TYPE_RAW_TRACK.toByte())
        // flags（偏移 6）在关键点块量化后回填（LANDMARKS_CLAMPED 由量化决定）

        buffer.putInt(8, (frameId and 0xFFFFFFFFL).toInt())
        buffer.putFloat(12, finiteOrZero(cameraFps))
        buffer.putLong(16, timestampMs)
        buffer.putShort(24, width.coerceIn(0, 0xFFFF).toShort())
        buffer.putShort(26, height.coerceIn(0, 0xFFFF).toShort())
        buffer.putFloat(28, clampUnitOrZero(presence))
        buffer.putFloat(32, finiteOrZero(inferenceMs).coerceAtLeast(0f))

        val poseValid = posePitch.isFinite() && poseYaw.isFinite() && poseRoll.isFinite()
        if (poseValid) {
            flags = flags or FLAG_POSE_VALID
            buffer.putFloat(36, posePitch)
            buffer.putFloat(40, poseYaw)
            buffer.putFloat(44, poseRoll)
        } else {
            buffer.putFloat(36, 0f)
            buffer.putFloat(40, 0f)
            buffer.putFloat(44, 0f)
        }

        buffer.putFloat(48, finiteOrZero(boxLeft))
        buffer.putFloat(52, finiteOrZero(boxTop))
        buffer.putFloat(56, finiteOrZero(boxRight))
        buffer.putFloat(60, finiteOrZero(boxBottom))
        buffer.putShort(64, LANDMARK_COUNT.toShort())
        buffer.putShort(66, LANDMARK_STRIDE.toShort())
        buffer.putFloat(68, clampUnitOrZero(boxScore))

        // ---- 关键点块（478 × 6 B，偏移 72 起）----
        val landmarksValid = faceValid && landmarks != null && landmarks.size == LANDMARK_COUNT
        if (landmarksValid) {
            flags = flags or FLAG_FACE_VALID
            var offset = HEADER_BYTES
            for (index in 0 until LANDMARK_COUNT) {
                val point = landmarks[index]
                val xQ = quantizeXy(point.x, clamped)
                val yQ = quantizeXy(point.y, clamped)
                val zQ = quantizeZ(point.z, clamped)
                buffer.putShort(offset, xQ.toShort())
                buffer.putShort(offset + 2, yQ.toShort())
                buffer.putShort(offset + 4, zQ.toShort())
                offset += LANDMARK_STRIDE
            }
        }
        // FACE_VALID = 0：结构长度恒定，块内容保持全 0（ByteBuffer.allocate 已零填充）
        // ---- ubRaw 扩展块（可选 128 B，偏移 2940 起）----
        // 量化裁剪标志与关键点块共用一个累计位（flags bit3 覆盖全帧量化）
        if (upperBody != null) {
            encodeUpperBody(buffer, HEADER_BYTES + LANDMARK_BLOCK_BYTES, upperBody, clamped)
        }

        if (clamped[0]) flags = flags or FLAG_LANDMARKS_CLAMPED
        if (preSmoothed) flags = flags or FLAG_LANDMARKS_PRE_SMOOTHED
        if (ubExt) flags = flags or FLAG_UB_EXT_PRESENT
        buffer.putShort(6, flags.toShort())

        return buffer.array()
    }

    /**
     * ubRaw 扩展块（§5）：13 × 8 B 原始点 + ubT + torsoRaw + quality + ubTracking + 保留位。
     *
     * ubTracking = 0 时 13 点全 0、torso = [0,0]、quality = 0（结构稳定）。
     */
    private fun encodeUpperBody(
        buffer: ByteBuffer,
        base: Int,
        frame: UpperBodyPoseFrame,
        clamped: BooleanArray,
    ) {
        val tracking = frame.tracking
        var offset = base
        if (tracking) {
            for (index in 0 until UB_POINT_COUNT) {
                val point = frame.point(index)
                buffer.putShort(offset, quantizeXy(point.x, clamped).toShort())
                buffer.putShort(offset + 2, quantizeXy(point.y, clamped).toShort())
                buffer.putShort(offset + 4, quantizeZ(point.z, clamped).toShort())
                buffer.putShort(offset + 6, quantizeVisibility(point.visibility).toShort())
                offset += UB_POINT_STRIDE
            }
        } else {
            offset += UB_POINT_COUNT * UB_POINT_STRIDE
        }
        // ubT：关键点帧采集时间（手机 Unix ms）；无帧时 0
        buffer.putLong(base + 104, if (tracking) frame.timestampMs else 0L)
        // torsoRaw：原始躯干角（未平滑、未减基线）
        val roll = if (tracking && frame.torso.roll.isFinite()) frame.torso.roll else 0f
        val yaw = if (tracking && frame.torso.yaw.isFinite()) frame.torso.yaw else 0f
        buffer.putFloat(base + 112, roll)
        buffer.putFloat(base + 116, yaw)
        buffer.putFloat(base + 120, if (tracking) clampUnitOrZero(frame.quality) else 0f)
        buffer.put(base + 124, (if (tracking) 1 else 0).toByte())
        // 125..127 保留，必须为 0（allocate 零填充）
    }
}
