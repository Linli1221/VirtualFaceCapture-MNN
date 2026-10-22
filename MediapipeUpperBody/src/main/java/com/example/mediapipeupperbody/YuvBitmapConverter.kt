package com.example.mediapipeupperbody

import android.graphics.Bitmap

/**
 * YUV_420_888 → 旋转 / 镜像 / 缩小后的 ARGB_8888 Bitmap。
 *
 * 性能策略（docs/upper-body.md §2 ①）：
 *  - **不做整帧转换**：直接按输出像素反查源坐标（最近邻采样），输出长边 256 时
 *    一个 1080p 竖屏帧只要采样约 192×256 ≈ 5 万像素，实测量级在 1~4ms；
 *  - 像素数组复用，Bitmap 只在每帧创建一次（MediaPipe LIVE_STREAM 异步持有它直到
 *    回调返回，不能复用同一张 Bitmap）；
 *  - 旋转 / 镜像映射全部来自纯 Kotlin 的 [YuvGeometry]，方便单测。
 *
 * 线程：由 [UpperBodyPoseDetector.pushFrame] 在调用线程同步执行（相机线程）；
 * [convert] 加了锁，多线程误调也不会踩到复用数组。
 */
internal class YuvBitmapConverter {

    private var pixels: IntArray = IntArray(0)

    @Synchronized
    fun convert(frame: Yuv420Frame, maxLongSide: Int): Bitmap? {
        if (!frame.isValid()) return null

        val rotation = frame.normalizedRotation
        val rotatedWidth = frame.rotatedWidth
        val rotatedHeight = frame.rotatedHeight
        val target = YuvGeometry.scaledSize(rotatedWidth, rotatedHeight, maxLongSide)
        if (target.isEmpty) return null

        val outWidth = target.width
        val outHeight = target.height
        val pixelCount = outWidth * outHeight
        if (pixels.size != pixelCount) {
            pixels = IntArray(pixelCount)
        }

        // 输出坐标 → 旋转后坐标（镜像在旋转后的画面上做水平翻转）。
        // 预计算映射表，内层循环只做整数查表 + 采样。
        val mirror = frame.mirrorHorizontal
        val rotatedX = IntArray(outWidth) { outX ->
            val raw = (outX * rotatedWidth) / outWidth
            if (mirror) rotatedWidth - 1 - raw else raw
        }
        val rotatedY = IntArray(outHeight) { outY -> (outY * rotatedHeight) / outHeight }

        val yBuffer = frame.y
        val uBuffer = frame.u
        val vBuffer = frame.v
        val yRowStride = frame.yRowStride
        val uvRowStride = frame.uvRowStride
        val uvPixelStride = frame.uvPixelStride
        val yLimit = yBuffer.limit()
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()
        val srcWidth = frame.width
        val srcHeight = frame.height

        var outIndex = 0
        for (outY in 0 until outHeight) {
            val rotatedYValue = rotatedY[outY]
            for (outX in 0 until outWidth) {
                val rotatedXValue = rotatedX[outX]
                val srcX = YuvGeometry.sourceX(rotatedXValue, rotatedYValue, srcWidth, srcHeight, rotation)
                val srcY = YuvGeometry.sourceY(rotatedXValue, rotatedYValue, srcWidth, srcHeight, rotation)

                val yIndex = srcY * yRowStride + srcX
                val luma = if (yIndex >= 0 && yIndex < yLimit) {
                    yBuffer.get(yIndex).toInt() and 0xFF
                } else {
                    16
                }

                val uvX = srcX shr 1
                val uvY = srcY shr 1
                val uvIndex = uvY * uvRowStride + uvX * uvPixelStride
                val cb = if (uvIndex >= 0 && uvIndex < uLimit) {
                    uBuffer.get(uvIndex).toInt() and 0xFF
                } else {
                    128
                }
                val cr = if (uvIndex >= 0 && uvIndex < vLimit) {
                    vBuffer.get(uvIndex).toInt() and 0xFF
                } else {
                    128
                }

                pixels[outIndex++] = yuvToArgb(luma, cb, cr)
            }
        }

        return Bitmap.createBitmap(pixels, 0, outWidth, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    }

    /** BT.601 limited range YUV → ARGB（整数运算，避免每像素浮点开销）。 */
    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val d = u - 128
        val e = v - 128
        val r = (298 * c + 409 * e + 128) shr 8
        val g = (298 * c - 100 * d - 208 * e + 128) shr 8
        val b = (298 * c + 516 * d + 128) shr 8
        val red = r.coerceIn(0, 255)
        val green = g.coerceIn(0, 255)
        val blue = b.coerceIn(0, 255)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
