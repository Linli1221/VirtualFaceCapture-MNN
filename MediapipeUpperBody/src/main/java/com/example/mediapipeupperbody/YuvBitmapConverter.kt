package com.example.mediapipeupperbody

import android.graphics.Bitmap

/**
 * YUV_420_888 → 旋转 / 镜像 / 缩小后的 ARGB_8888 Bitmap。
 *
 * 性能策略（docs/upper-body.md §2 ①）：
 *  - **不做整帧转换**：直接按输出像素反查源坐标（最近邻采样），输出长边上限 320 时
 *    一个 1080p 竖屏帧只要采样约 180×320 ≈ 5.8 万像素（256 档再省 36%）；带 ROI 时只采样 ROI
 *    （典型 10 万像素上下），实测量级在 1~4ms；
 *  - **ROI 裁切**：传入 [RoiWindow] 时只采样窗口内的旋转后像素，输出仍是「预览方向」
 *    （镜像在 ROI 内部翻转）。roi = null / 全帧 / 非法时与旧版逐位一致；
 *  - 像素数组复用，Bitmap 只在每帧创建一次（MediaPipe LIVE_STREAM 异步持有它直到
 *    回调返回，不能复用同一张 Bitmap）；
 *  - 旋转 / 镜像映射全部来自纯 Kotlin 的 [YuvGeometry]，方便单测。
 *
 * 线程：由 [UpperBodyPoseDetector.processFrame] 在 `upper-body-worker` 线程执行
 * （相机线程只把平面拷进 [YuvFrameRing]，不再做像素转换）；
 * [convert] 加了锁，多线程误调也不会踩到复用数组。
 */
internal class YuvBitmapConverter {

    private var pixels: IntArray = IntArray(0)

    /**
     * @param frame 相机 YUV 帧（未旋转 / 未镜像的源图）
     * @param maxLongSide 输出图长边上限（只缩不放）
     * @param roi 旋转后源像素坐标里的裁切窗口；null / 整帧 / 非法（越界、0 尺寸）都按整帧处理。
     *   非法 ROI 选择**回退整帧**而不是返回 null：宁可多采样一点像素，也不要让这一帧
     *   （乃至整个上半身功能）因为一个坏窗口而丢掉。
     */
    @Synchronized
    fun convert(frame: Yuv420Frame, maxLongSide: Int, roi: RoiWindow? = null): Bitmap? {
        if (!frame.isValid()) return null

        val rotation = frame.normalizedRotation
        val rotatedWidth = frame.rotatedWidth
        val rotatedHeight = frame.rotatedHeight

        // 采样窗口（旋转后坐标）。roi == null 时就是整帧，走与旧版逐位一致的路径。
        val window = if (roi != null && roi.fitsWithin(rotatedWidth, rotatedHeight)) roi else null
        val windowX = window?.x0 ?: 0
        val windowY = window?.y0 ?: 0
        val windowWidth = window?.width ?: rotatedWidth
        val windowHeight = window?.height ?: rotatedHeight

        val target = YuvGeometry.scaledSize(windowWidth, windowHeight, maxLongSide)
        if (target.isEmpty) return null

        val outWidth = target.width
        val outHeight = target.height
        val pixelCount = outWidth * outHeight
        if (pixels.size != pixelCount) {
            pixels = IntArray(pixelCount)
        }

        // 输出坐标 → 旋转后坐标（镜像在旋转后的画面上做水平翻转，且只翻转 ROI 内部）。
        // 预计算映射表，内层循环只做整数查表 + 采样。
        // 整帧（window == null）时 local 就是旧版的 raw，逐位一致。
        val mirror = frame.mirrorHorizontal
        val rotatedX = IntArray(outWidth) { outX ->
            val local = (outX * windowWidth) / outWidth
            if (mirror) {
                windowX + windowWidth - 1 - local
            } else {
                windowX + local
            }
        }
        val rotatedY = IntArray(outHeight) { outY ->
            windowY + (outY * windowHeight) / outHeight
        }

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
