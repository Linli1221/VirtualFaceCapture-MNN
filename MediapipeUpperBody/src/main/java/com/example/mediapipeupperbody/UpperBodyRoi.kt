package com.example.mediapipeupperbody

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 同一帧上的脸框提示（纯 Kotlin，JVM 可测）。
 *
 * 坐标约定与面捕的 MediaPipeFaceDetector.FaceBox 一致：**按 rotationDegrees 旋转后、
 * 与预览同方向、未镜像**的归一化坐标 [0,1]。镜像由检测器在采样 / 回映时按
 * [Yuv420Frame.mirrorHorizontal] 处理，这里只描述「几何上的脸在哪」。
 *
 * @param left 脸框左边界
 * @param top 脸框上边界
 * @param right 脸框右边界
 * @param bottom 脸框下边界
 * @param score 置信度 [0,1]；越界视为非法提示（宁可整帧，也不要拿不可信的框去裁）
 */
data class FaceRoiHint(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float = 1f,
) {

    /** 归一化宽度（可能 ≤ 0，调用方先看 [isValid]）。 */
    val width: Float get() = right - left

    /** 归一化高度（可能 ≤ 0，调用方先看 [isValid]）。 */
    val height: Float get() = bottom - top

    /**
     * 是否是一个可用的提示：坐标全部有限、right > left、bottom > top、score ∈ [0,1]。
     *
     * 注意这里**不做**「离帧太远 / 太小」的判断：前者由 [UpperBodyRoi.fromFace] 的
     * clamp 兜底，后者由 [UpperBodyRoi.MIN_FACE_PX] 兜底。
     */
    val isValid: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            score.isFinite() && score >= 0f && score <= 1f &&
            right > left && bottom > top
}

/**
 * 一个裁切窗口，坐标是**旋转后、未镜像、未缩放**的源图像素坐标。
 *
 * 这个坐标系与 [YuvGeometry.sourceX] / [YuvGeometry.sourceY] 的入参一致，因此
 * [YuvBitmapConverter] 可以直接把它当采样窗口用；[width] / [height] 是窗口尺寸（右 / 下边界
 * 不含）。
 *
 * @param x0 左边界（含）
 * @param y0 上边界（含）
 * @param width 宽度（像素）
 * @param height 高度（像素）
 */
data class RoiWindow(
    val x0: Int,
    val y0: Int,
    val width: Int,
    val height: Int,
) {

    /** 右边界（不含）。 */
    val right: Int get() = x0 + width

    /** 下边界（不含）。 */
    val bottom: Int get() = y0 + height

    /** 0 / 负数尺寸 = 空窗口（转换器会回退整帧）。 */
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** 是否正好是整帧（用于「没有 ROI 时不改变旧行为」的判断）。 */
    fun isFullFrame(frameWidth: Int, frameHeight: Int): Boolean =
        x0 == 0 && y0 == 0 && width == frameWidth && height == frameHeight

    /** 是否完全落在 [frameWidth] × [frameHeight] 的旋转后帧内（越界 = 不可用于采样）。 */
    fun fitsWithin(frameWidth: Int, frameHeight: Int): Boolean =
        width > 0 && height > 0 &&
            x0 >= 0 && y0 >= 0 &&
            x0 + width <= frameWidth && y0 + height <= frameHeight
}

/**
 * 脸框 → 上半身 ROI 的几何（纯 Kotlin，**不 import android.***，JVM 可直接测）。
 *
 * 改造目的（docs/upper-body.md §2 的流程图）：不再把整帧缩到 256，而是
 *
 *     脸框 → 上半身 ROI（像素）→ 只裁这块 → 缩放到长边上限 320（只缩不放）→ PoseLandmarker
 *
 * 同一个人占的像素多了 4~5 倍，远距离 / 小目标的关键点明显更稳；采样量仍然只有 10 万
 * 像素量级（整帧 720p 是 92 万），比整帧转 ARGB 便宜一个数量级。
 *
 * 坐标空间（三个，别混）：
 *  1. **旋转后源像素**：ROI 窗口 [RoiWindow] 所在的坐标系（未镜像、未缩放）；
 *  2. **ROI 局部归一化**：MediaPipe 在 ROI Bitmap 上输出的 [0,1] 坐标（已按帧的
 *     mirrorHorizontal 在 ROI 内部翻转）；
 *  3. **整帧预览归一化**：协议 ub[] 的语义 —— 旋转 + 镜像后的整帧坐标。结果点必须回映到这里，
 *     否则 ROI 一变坐标就跳。
 */
object UpperBodyRoi {

    /** 上半身宽度 ≈ 脸宽的倍数（肩宽量级）。 */
    const val FACE_WIDTH_TO_BODY = 4.5f

    /** 上半身高度 ≈ 脸高的倍数（头顶到胯部）。 */
    const val FACE_HEIGHT_TO_BODY = 5.5f

    /** ROI 顶部相对脸顶再往上留的余量（脸高的倍数）：别把头顶 / 抬手的空间裁掉。 */
    const val HEAD_TOP_MARGIN = 0.35f

    /** 脸宽 / 脸高小于这个像素数时不做 ROI（人脸太小，框出来的区域没有意义）。 */
    const val MIN_FACE_PX = 12f

    /** ROI 任一边小于这个像素数时回退整帧（太小的 ROI 放大后全是噪声）。 */
    const val MIN_ROI_PX = 48f

    /**
     * 整帧窗口；帧尺寸非法时返回 0 尺寸窗口（转换器会直接返回 null）。
     */
    fun fullFrame(frameWidth: Int, frameHeight: Int): RoiWindow =
        RoiWindow(0, 0, max(0, frameWidth), max(0, frameHeight))

    /**
     * 由脸框算出旋转后源像素坐标里的上半身 ROI。
     *
     * 规则：
     *  - face 为 null / 非法 / 脸小于 [MIN_FACE_PX] → 整帧；
     *  - bodyW = faceW × [FACE_WIDTH_TO_BODY]、bodyH = faceH × [FACE_HEIGHT_TO_BODY]；
     *  - 水平方向以脸中心为中心，垂直方向 top = faceTop - faceH × [HEAD_TOP_MARGIN]
     *    （脸以上留出头顶 / 抬手空间，往下覆盖到胯部）；
     *  - 先 clamp **尺寸**到帧内（保持 4.5 : 5.5 的比例不再二次变形），再 clamp **位置**到
     *    [0, frameSize - bodySize]；
     *  - 四舍五入成整数像素；结果任一边 < [MIN_ROI_PX] 时回退整帧。
     *
     * @param face 同一帧的脸框（旋转后、未镜像的归一化坐标）
     * @param frameWidth 旋转后帧宽（= [Yuv420Frame.rotatedWidth]）
     * @param frameHeight 旋转后帧高（= [Yuv420Frame.rotatedHeight]）
     */
    fun fromFace(face: FaceRoiHint?, frameWidth: Int, frameHeight: Int): RoiWindow {
        val frame = fullFrame(frameWidth, frameHeight)
        if (frame.isEmpty) return frame
        if (face == null || !face.isValid) return frame

        val faceWidthPx = face.width * frameWidth
        val faceHeightPx = face.height * frameHeight
        if (!faceWidthPx.isFinite() || !faceHeightPx.isFinite()) return frame
        if (min(faceWidthPx, faceHeightPx) < MIN_FACE_PX) return frame

        // 先定尺寸（clamp 到帧内），后面只 clamp 位置 —— 比例不会被挤扁。
        val bodyWidth = (faceWidthPx * FACE_WIDTH_TO_BODY).roundToInt().coerceIn(1, frameWidth)
        val bodyHeight = (faceHeightPx * FACE_HEIGHT_TO_BODY).roundToInt().coerceIn(1, frameHeight)
        if (bodyWidth < MIN_ROI_PX || bodyHeight < MIN_ROI_PX) return frame

        val centerX = (face.left + face.right) * 0.5f * frameWidth
        val bodyTop = face.top * frameHeight - faceHeightPx * HEAD_TOP_MARGIN

        val x = (centerX - bodyWidth * 0.5f).roundToInt().coerceIn(0, frameWidth - bodyWidth)
        val y = bodyTop.roundToInt().coerceIn(0, frameHeight - bodyHeight)
        return RoiWindow(x0 = x, y0 = y, width = bodyWidth, height = bodyHeight)
    }

    /**
     * 把 ROI 局部归一化坐标回映到**整帧预览归一化坐标**（协议 ub[] 的语义）。
     *
     * 记 sx = roi.width / frameWidth、sy = roi.height / frameHeight、
     * left = roi.x0 / frameWidth、top = roi.y0 / frameHeight：
     *  - 非镜像：previewX = left + lx × sx
     *  - 镜像：  previewX = (1 - left - sx) + lx × sx
     *    （整帧预览是水平翻转的，ROI 内部也已经翻转过，所以 ROI 的「局部左」对应整帧预览里
     *    这块区域的「镜像左」，即 left + sx 处；lx 增大时 previewX 仍然增大）
     *  - previewY = top + ly × sy
     *  - z = lz × sx：MediaPipe 的 z 与 x 同尺度（以输入图宽度为单位），ROI 裁切后必须按
     *    宽度比缩放回整帧尺度，否则躯干 yaw = atan2(Δz, Δx) 会随 ROI 大小漂移。
     *
     * 不做 [0,1] 裁剪：MediaPipe 对出画的手腕会给略超出 [0,1] 的坐标，裁掉会让手臂角度失真
     * （改造前整帧路径也没有裁剪，语义保持一致）。
     *
     * 精度：采样用的是整数像素镜像（x0 + width - 1 - local），这里用归一化近似，
     * 两者差 ≤ 1 个 ROI 像素（≤ 1 / frameWidth），对关键点位置可以忽略。
     *
     * @param roi 本帧使用的 ROI（整帧时映射等价于恒等）
     * @param mirrored 整帧预览是否水平镜像（= [Yuv420Frame.mirrorHorizontal]）
     */
    fun mapLocalToPreview(
        localX: Float,
        localY: Float,
        localZ: Float,
        roi: RoiWindow,
        frameWidth: Int,
        frameHeight: Int,
        mirrored: Boolean,
    ): PreviewPoint {
        if (frameWidth <= 0 || frameHeight <= 0) return PreviewPoint(localX, localY, localZ)
        val sx = roi.width.toFloat() / frameWidth
        val sy = roi.height.toFloat() / frameHeight
        val left = roi.x0.toFloat() / frameWidth
        val top = roi.y0.toFloat() / frameHeight

        val previewX = if (mirrored) {
            (1f - left - sx) + localX * sx
        } else {
            left + localX * sx
        }
        return PreviewPoint(
            x = previewX,
            y = top + localY * sy,
            // z 只跟着 ROI 宽度缩放（深度与水平尺度同源），镜像不改变深度。
            z = localZ * sx,
        )
    }

    /**
     * ROI 缩放到长边不超过 [maxLongSide] 之后的输出尺寸。
     *
     * 直接复用 [YuvGeometry.scaledSize]：只缩不放，长边等于 [maxLongSide]（除非 ROI 更小）。
     */
    fun scaledSize(roi: RoiWindow, maxLongSide: Int): YuvGeometry.Size =
        YuvGeometry.scaledSize(roi.width, roi.height, maxLongSide)

    /** 回映后的一个点（整帧预览归一化坐标 + 整帧尺度的相对深度）。 */
    data class PreviewPoint(
        val x: Float,
        val y: Float,
        val z: Float,
    )
}
