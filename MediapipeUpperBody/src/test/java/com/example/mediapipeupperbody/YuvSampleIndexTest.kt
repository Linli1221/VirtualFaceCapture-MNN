package com.example.mediapipeupperbody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [YuvSampleTable] / [YuvPlaneIndex] / [YuvSampleCache] 的纯数学测试：
 * 把行列分解后的平面索引与旧的逐像素 [YuvGeometry.sourceX] / [YuvGeometry.sourceY]
 * 路径对拍，保证优化前后采样到的 Y / UV 字节下标**逐位一致**。
 */
class YuvSampleIndexTest {

    /** 一组测试几何：源尺寸 + 窗口尺寸 + 窗口原点。 */
    private data class Geo(
        val srcW: Int,
        val srcH: Int,
        val windowW: Int,
        val windowH: Int,
        val windowX: Int,
        val windowY: Int,
    )

    /** 旧实现的逐像素参考：输出像素 → 旋转后坐标 → 源坐标 → 平面字节下标。 */
    private fun referenceIndexes(
        key: YuvSampleKey,
        windowX: Int,
        windowY: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
    ): Pair<IntArray, IntArray> {
        val yIndex = IntArray(key.outWidth * key.outHeight)
        val uvIndex = IntArray(key.outWidth * key.outHeight)
        for (outY in 0 until key.outHeight) {
            for (outX in 0 until key.outWidth) {
                val local = (outX * key.windowWidth) / key.outWidth
                val rotatedX = if (key.mirror) {
                    windowX + key.windowWidth - 1 - local
                } else {
                    windowX + local
                }
                val rotatedY = windowY + (outY * key.windowHeight) / key.outHeight
                val srcX = YuvGeometry.sourceX(rotatedX, rotatedY, key.srcWidth, key.srcHeight, key.rotation)
                val srcY = YuvGeometry.sourceY(rotatedX, rotatedY, key.srcWidth, key.srcHeight, key.rotation)
                val i = outY * key.outWidth + outX
                yIndex[i] = srcY * yRowStride + srcX
                uvIndex[i] = (srcY shr 1) * uvRowStride + (srcX shr 1) * uvPixelStride
            }
        }
        return yIndex to uvIndex
    }

    private fun assertMatchesReference(
        key: YuvSampleKey,
        windowX: Int,
        windowY: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
    ) {
        val table = YuvSampleTable.build(key)
        val index = YuvPlaneIndex(key.outWidth, key.outHeight)
        index.fill(table, windowX, windowY, yRowStride, uvRowStride, uvPixelStride)

        val (refY, refUv) = referenceIndexes(key, windowX, windowY, yRowStride, uvRowStride, uvPixelStride)
        for (outY in 0 until key.outHeight) {
            for (outX in 0 until key.outWidth) {
                val i = outY * key.outWidth + outX
                assertEquals(
                    "yIndex mismatch at ($outX,$outY) key=$key",
                    refY[i],
                    index.rowY[outY] + index.colY[outX],
                )
                assertEquals(
                    "uvIndex mismatch at ($outX,$outY) key=$key",
                    refUv[i],
                    index.rowUv[outY] + index.colUv[outX],
                )
            }
        }
    }

    @Test
    fun indexesMatchGeometryReference() {
        val geometries = listOf(
            Geo(640, 480, 320, 240, 10, 20),
            Geo(1280, 720, 640, 360, 100, 50),
            Geo(1080, 1920, 1080, 1920, 0, 0),
            Geo(352, 288, 100, 80, 251, 207),
            Geo(1000, 1000, 3, 3, 997, 997),
        )
        for (rotation in listOf(0, 90, 180, 270, -90, 450)) {
            for (mirror in listOf(false, true)) {
                for (geo in geometries) {
                    val srcW = geo.srcW
                    val srcH = geo.srcH
                    val windowW = geo.windowW
                    val windowH = geo.windowH
                    val windowX = geo.windowX
                    val windowY = geo.windowY
                    // 旋转 90/270 时帧尺寸交换，窗口必须落在旋转后的帧内。
                    val rotatedW = if (rotation % 180 == 0) srcW else srcH
                    val rotatedH = if (rotation % 180 == 0) srcH else srcW
                    if (windowX + windowW > rotatedW || windowY + windowH > rotatedH) continue

                    val outW = 128
                    val outH = 96
                    val key = YuvSampleKey(
                        srcWidth = srcW,
                        srcHeight = srcH,
                        windowWidth = windowW,
                        windowHeight = windowH,
                        rotation = rotation,
                        mirror = mirror,
                        outWidth = outW,
                        outHeight = outH,
                    )
                    // semi-planar（uvPixelStride = 2）+ 带 padding 的 stride
                    assertMatchesReference(key, windowX, windowY, srcW + 16, srcW + 8, 2)
                    // planar（uvPixelStride = 1）、紧凑 stride
                    assertMatchesReference(key, windowX, windowY, srcW, srcW, 1)
                }
            }
        }
    }

    @Test
    fun mirrorFlipsColumnsWithinWindow() {
        val key = YuvSampleKey(
            srcWidth = 640, srcHeight = 480, windowWidth = 100, windowHeight = 80,
            rotation = 0, mirror = true, outWidth = 100, outHeight = 80,
        )
        val table = YuvSampleTable.build(key)
        // mirror 时输出列 0 映射到窗口局部右缘（与旧 rotatedX 的翻转逐位一致）。
        assertEquals(99, table.colLocalX[0])
        assertEquals(0, table.colLocalX[99])
        assertEquals(0, table.rowLocalY[0])

        val noMirror = YuvSampleTable.build(key.copy(mirror = false))
        assertEquals(0, noMirror.colLocalX[0])
        assertEquals(99, noMirror.colLocalX[99])
    }

    @Test
    fun cacheReturnsSameTableForSameShape() {
        val cache = YuvSampleCache()
        val key = YuvSampleKey(
            srcWidth = 1280, srcHeight = 720, windowWidth = 320, windowHeight = 240,
            rotation = 90, mirror = true, outWidth = 320, outHeight = 240,
        )
        val first = cache.obtain(key)
        // ROI 原点不在键里：平移 ROI 返回同一张表（缓存不被击穿）。
        assertSame(first, cache.obtain(key.copy()))
        assertEquals(1, cache.size)
        assertTrue(cache.contains(key))
    }

    @Test
    fun cacheEvictsLeastRecentlyUsed() {
        val cache = YuvSampleCache(capacity = 2)
        val base = YuvSampleKey(
            srcWidth = 640, srcHeight = 480, windowWidth = 100, windowHeight = 100,
            rotation = 0, mirror = false, outWidth = 100, outHeight = 100,
        )
        val a = base.copy(windowWidth = 100)
        val b = base.copy(windowWidth = 200)
        val c = base.copy(windowWidth = 300)
        cache.obtain(a)
        cache.obtain(b)
        cache.obtain(c)
        assertEquals(2, cache.size)
        assertFalse(cache.contains(a))
        assertTrue(cache.contains(b))
        assertTrue(cache.contains(c))
    }

    @Test
    fun planeIndexSkipsRefillWhenUnchanged() {
        val key = YuvSampleKey(
            srcWidth = 640, srcHeight = 480, windowWidth = 320, windowHeight = 240,
            rotation = 180, mirror = false, outWidth = 160, outHeight = 120,
        )
        val table = YuvSampleTable.build(key)
        val index = YuvPlaneIndex(key.outWidth, key.outHeight)
        assertFalse(index.isFilled)

        index.fill(table, 10, 20, 640, 640, 2)
        assertTrue(index.isFilled)
        assertEquals(1, index.fillCount)

        // 参数全没变：免算快路径，不重摊平。
        index.fill(table, 10, 20, 640, 640, 2)
        assertEquals(1, index.fillCount)

        // ROI 平移：只重摊平一次（O(输出行+输出列)），坐标表本身不变。
        index.fill(table, 11, 20, 640, 640, 2)
        assertEquals(2, index.fillCount)

        // stride 变化也要重摊平。
        index.fill(table, 11, 20, 656, 640, 2)
        assertEquals(3, index.fillCount)
    }
}
