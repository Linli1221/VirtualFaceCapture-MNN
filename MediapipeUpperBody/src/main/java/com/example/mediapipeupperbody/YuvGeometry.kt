package com.example.mediapipeupperbody

import kotlin.math.max

/**
 * 纯 Kotlin 的 YUV 尺寸 / 旋转坐标数学。
 *
 * 这里**不 import 任何 android.***，所以 JVM 单元测试可以直接跑（见
 * [com.example.mediapipeupperbody.Yuv420FrameTest]）。Android 侧的
 * `YuvBitmapConverter` 只负责把这里的映射结果读成 ARGB 像素。
 *
 * 坐标约定：
 *  - 源图 = 相机 ImageReader 给出的 YUV 尺寸（width × height，未旋转、未镜像）；
 *  - 旋转后 = 顺时针旋转 rotationDegrees 之后、与手机预览方向一致的图；
 *  - 镜像 = 在**旋转后**的图上做水平翻转（前置摄像头）。
 */
object YuvGeometry {

    /** 二维尺寸（像素）。 */
    data class Size(val width: Int, val height: Int) {
        val isEmpty: Boolean get() = width <= 0 || height <= 0
    }

    /**
     * 把任意角度就近归一化到 0 / 90 / 180 / 270。
     *
     * 例如 -90 → 270、450 → 90、37 → 0。相机给出的角度本来只会是这四个值，
     * 这里兜底是为了不让异常值把采样坐标算飞。
     */
    fun normalizeRotation(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return when (normalized) {
            0, 90, 180, 270 -> normalized
            else -> (((normalized + 45) / 90) * 90) % 360
        }
    }

    /** 顺时针旋转后的图像尺寸（90 / 270 交换宽高）。 */
    fun rotatedSize(width: Int, height: Int, rotationDegrees: Int): Size {
        if (width <= 0 || height <= 0) return Size(0, 0)
        return if (normalizeRotation(rotationDegrees) % 180 == 0) {
            Size(width, height)
        } else {
            Size(height, width)
        }
    }

    /**
     * 计算「长边不超过 maxLongSide、保持宽高比」的输出尺寸。
     *
     * 只缩小不放大：源图本来就比 [maxLongSide] 小时原样返回（放大不会增加信息量，
     * 反而多花 CPU）。结果的长边一定等于 [maxLongSide]（除非原图更小）。
     */
    fun scaledSize(width: Int, height: Int, maxLongSide: Int): Size {
        if (width <= 0 || height <= 0) return Size(0, 0)
        val limit = if (maxLongSide > 0) maxLongSide else 1
        val longSide = max(width, height)
        if (longSide <= limit) return Size(width, height)
        return if (width >= height) {
            val scaledHeight = ((height.toLong() * limit + width / 2) / width).toInt()
            Size(limit, max(1, scaledHeight))
        } else {
            val scaledWidth = ((width.toLong() * limit + height / 2) / height).toInt()
            Size(max(1, scaledWidth), limit)
        }
    }

    /**
     * 旋转后坐标 (rotatedX, rotatedY) 对应的源图 X。
     *
     * 旋转方向 = 顺时针。90° 时：源图左上角转到输出右上角。
     */
    fun sourceX(rotatedX: Int, rotatedY: Int, srcWidth: Int, srcHeight: Int, rotationDegrees: Int): Int =
        when (normalizeRotation(rotationDegrees)) {
            90 -> rotatedY
            180 -> srcWidth - 1 - rotatedX
            270 -> srcWidth - 1 - rotatedY
            else -> rotatedX
        }

    /**
     * 旋转后坐标 (rotatedX, rotatedY) 对应的源图 Y。
     *
     * 与 [sourceX] 成对使用；90° / 270° 的映射是转置的，所以参数名故意保留
     * rotatedX / rotatedY 两个方向。
     */
    fun sourceY(rotatedX: Int, rotatedY: Int, srcWidth: Int, srcHeight: Int, rotationDegrees: Int): Int =
        when (normalizeRotation(rotationDegrees)) {
            90 -> srcHeight - 1 - rotatedX
            180 -> srcHeight - 1 - rotatedY
            270 -> rotatedX
            else -> rotatedY
        }
}
