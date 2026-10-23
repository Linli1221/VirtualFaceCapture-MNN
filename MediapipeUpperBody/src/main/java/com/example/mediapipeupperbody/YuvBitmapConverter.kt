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
 *  - **坐标表缓存 + 行列分解索引**（YuvSampleIndex.kt）：输出行列 → 源像素的采样映射按
 *    「源尺寸 + ROI 尺寸 + rotation + mirror + 输出尺寸」缓存坐标表（[YuvSampleCache]），
 *    Y / U / V 平面行索引与 UV 索引摊平成 rowY / colY / rowUv / colUv 四张小表
 *    （每帧 O(输出行+输出列)，几何 / 原点 / stride 未变时直接复用）；内层循环只剩
 *    数组寻址 + YUV → ARGB，不再有 per-pixel 的 sourceX / sourceY / UV 地址计算。
 *    逐位等价性由 [YuvSampleIndexTest] 与 [YuvGeometry] 对拍保证；
 *  - 像素数组复用，Bitmap 只在每帧创建一次（MediaPipe LIVE_STREAM 异步持有它直到
 *    回调返回，不能复用同一张 Bitmap；若要省这次分配，只能做**带生命周期确认的
 *    2~3 张 Bitmap 环形池**，等 MediaPipe 确认释放上一张之后才回池，不能盲目复用）；
 *  - 后续方向：JNI / NEON 实现 ROI 转换内核（第 3 层的数组寻址 + YUV→ARGB 循环）。
 *  - 旋转 / 镜像映射全部来自纯 Kotlin 的 [YuvGeometry]，方便单测。
 *
 * 线程：由 [UpperBodyPoseDetector.processFrame] 在 `upper-body-worker` 线程执行
 * （相机线程只把平面拷进 [YuvFrameRing]，不再做像素转换）；
 * [convert] 加了锁，多线程误调也不会踩到复用数组。
 */
internal class YuvBitmapConverter {

    private var pixels: IntArray = IntArray(0)

    /** 采样坐标表缓存（键 = 几何形状，ROI 平移 / stride 波动不击穿）。 */
    private val sampleCache = YuvSampleCache()

    /** 行 / 列分解的平面索引（输出尺寸不变时跨帧复用）。 */
    private var planeIndex: YuvPlaneIndex? = null

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

        // ① 坐标表：输出行列 → 窗口局部坐标（镜像折叠在表内）。
        //    键不含 ROI 原点 / stride，ROI 随脸平移照样命中；整帧（window == null）与旧版逐位一致。
        val table = sampleCache.obtain(
            YuvSampleKey(
                srcWidth = frame.width,
                srcHeight = frame.height,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                rotation = rotation,
                mirror = frame.mirrorHorizontal,
                outWidth = outWidth,
                outHeight = outHeight,
            )
        )

        // ② 平面索引：yIndex = rowY + colY，uvIndex = rowUv + colUv。
        //    每帧 O(outWidth + outHeight) 摊平；几何 / 原点 / stride 未变时免算直接复用。
        val cachedIndex = planeIndex
        val index = if (cachedIndex == null ||
            cachedIndex.outWidth != outWidth ||
            cachedIndex.outHeight != outHeight
        ) {
            val fresh = YuvPlaneIndex(outWidth, outHeight)
            planeIndex = fresh
            fresh
        } else {
            cachedIndex
        }
        index.fill(
            table = table,
            windowX = windowX,
            windowY = windowY,
            yRowStride = frame.yRowStride,
            uvRowStride = frame.uvRowStride,
            uvPixelStride = frame.uvPixelStride,
        )

        val yBuffer = frame.y
        val uBuffer = frame.u
        val vBuffer = frame.v
        val yLimit = yBuffer.limit()
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()

        // ③ 内层循环：数组寻址 + YUV → ARGB（越界兜底与旧实现逐位一致）。
        var outIndex = 0
        for (outY in 0 until outHeight) {
            val yRowBase = index.rowY[outY]
            val uvRowBase = index.rowUv[outY]
            for (outX in 0 until outWidth) {
                val yIndex = yRowBase + index.colY[outX]
                val luma = if (yIndex >= 0 && yIndex < yLimit) {
                    yBuffer.get(yIndex).toInt() and 0xFF
                } else {
                    16
                }

                val uvIndex = uvRowBase + index.colUv[outX]
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
