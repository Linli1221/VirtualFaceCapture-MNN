package com.example.mediapipeupperbody

import java.nio.ByteBuffer

/**
 * 相机线程上的一帧 YUV_420_888。
 *
 * **ByteBuffer 只在相机回调期间有效**，所以 app 侧必须在 `onYuvFrameForBodyWithHint`
 * （或旧 `onYuvFrameForBody`）回调里
 * 同步调用 [UpperBodyPoseDetector.pushFrame]；本模块会在 pushFrame 内立即把像素
 * 采样 / 旋转 / 缩放成 Bitmap，不会保留这些 ByteBuffer。
 *
 * 三个平面都允许带 row stride（相机常见：Y 行宽 > 图宽；UV 交错时
 * uvPixelStride = 2、uvRowStride = 图宽，平面模式时 uvPixelStride = 1）。
 *
 * @param y Y 平面（Luma）
 * @param u U 平面（Cb）
 * @param v V 平面（Cr）
 * @param width 源图宽（未旋转）
 * @param height 源图高（未旋转）
 * @param yRowStride Y 平面行跨度（字节）
 * @param uvRowStride UV 平面行跨度（字节）
 * @param uvPixelStride UV 平面像素跨度（1 = planar，2 = semi-planar）
 * @param rotationDegrees 需要顺时针旋转的角度：0 / 90 / 180 / 270
 * @param mirrorHorizontal 是否水平镜像（前置摄像头 = true；只镜像像素，不交换左右语义）
 */
class Yuv420Frame(
    val y: ByteBuffer,
    val u: ByteBuffer,
    val v: ByteBuffer,
    val width: Int,
    val height: Int,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    val rotationDegrees: Int,
    val mirrorHorizontal: Boolean,
) {

    /** 归一化后的旋转角（0 / 90 / 180 / 270）。 */
    val normalizedRotation: Int
        get() = YuvGeometry.normalizeRotation(rotationDegrees)

    /** 旋转后（未缩放）的图像宽。 */
    val rotatedWidth: Int
        get() = YuvGeometry.rotatedSize(width, height, normalizedRotation).width

    /** 旋转后（未缩放）的图像高。 */
    val rotatedHeight: Int
        get() = YuvGeometry.rotatedSize(width, height, normalizedRotation).height

    /** 缩放到长边不超过 [maxLongSide] 之后的输出尺寸。 */
    fun targetSize(maxLongSide: Int = DEFAULT_MAX_LONG_SIDE): YuvGeometry.Size =
        YuvGeometry.scaledSize(rotatedWidth, rotatedHeight, maxLongSide)

    /** 帧是否具备可供采样的最小合法性（尺寸 / stride 都要成立）。 */
    fun isValid(): Boolean {
        if (width <= 0 || height <= 0) return false
        if (yRowStride < width) return false
        if (uvPixelStride <= 0) return false
        // UV 平面的最小行跨度：每行至少 ceil(width / 2) 个 UV 采样，每个采样 uvPixelStride 字节
        val minUvRowStride = ((width + 1) / 2) * uvPixelStride
        if (uvRowStride < minUvRowStride) return false
        if (y.limit() <= 0 || u.limit() <= 0 || v.limit() <= 0) return false
        return true
    }

    companion object {
        /** [targetSize] 的默认长边（256，只缩不放）。检测器实际用 320（见 UpperBodyPoseDetector）。 */
        const val DEFAULT_MAX_LONG_SIDE = 256
    }
}
