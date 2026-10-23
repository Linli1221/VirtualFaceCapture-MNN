package com.example.mediapipeupperbody

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [YuvFrameRing] 的纯 JVM 测试：最新帧策略、stride 紧凑拷贝、截断补齐、槽位复用。
 *
 * 不依赖 android.graphics.Bitmap / Android 运行时，直接喂 [Yuv420Frame]。
 */
class YuvFrameRingTest {

    /** 构造一帧带行 padding 的 YUV：Y 每行 yRowStride = width + yPad，数据带可校验的图案。 */
    private fun makeFrame(
        width: Int,
        height: Int,
        yPad: Int,
        uvPixelStride: Int,
        marker: Int,
    ): Yuv420Frame {
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val yRowStride = width + yPad
        val uvRowStride = uvWidth * uvPixelStride + yPad
        val y = ByteBuffer.allocate(yRowStride * height)
        val u = ByteBuffer.allocate(uvRowStride * uvHeight)
        val v = ByteBuffer.allocate(uvRowStride * uvHeight)
        for (row in 0 until height) {
            for (x in 0 until width) {
                y.put(row * yRowStride + x, ((marker + row * 31 + x * 7) and 0xFF).toByte())
            }
            for (p in 0 until yPad) {
                y.put(row * yRowStride + width + p, 0xEE.toByte())
            }
        }
        for (row in 0 until uvHeight) {
            for (x in 0 until uvWidth * uvPixelStride) {
                u.put(row * uvRowStride + x, ((marker + 100 + row * 13 + x * 3) and 0xFF).toByte())
                v.put(row * uvRowStride + x, ((marker + 200 + row * 17 + x * 5) and 0xFF).toByte())
            }
            for (p in 0 until yPad) {
                u.put(row * uvRowStride + uvWidth * uvPixelStride + p, 0xEE.toByte())
                v.put(row * uvRowStride + uvWidth * uvPixelStride + p, 0xEE.toByte())
            }
        }
        return Yuv420Frame(
            y = y,
            u = u,
            v = v,
            width = width,
            height = height,
            yRowStride = yRowStride,
            uvRowStride = uvRowStride,
            uvPixelStride = uvPixelStride,
            rotationDegrees = 0,
            mirrorHorizontal = true,
        )
    }

    @Test
    fun keepsOnlyLatestFrameAndCountsDrops() {
        var dropped = 0
        val ring = YuvFrameRing(slotCount = 3) { dropped++ }

        for (marker in 1..5) {
            assertTrue(ring.submit(makeFrame(4, 4, 2, 2, marker)))
        }
        assertEquals(4, dropped)

        val slot = ring.takeLatest(0)
        assertNotNull(slot)
        val frame = slot!!.frame
        assertNotNull(frame)
        // 取到的必须是最后一帧（marker = 5）：y[row*4 + 0] = 5 + row*31
        assertEquals((5 + 0 * 31) and 0xFF, frame!!.y.get(0).toInt() and 0xFF)
        assertEquals((5 + 3 * 31) and 0xFF, frame.y.get(3 * 4).toInt() and 0xFF)
        // 只保留最新：后面不能再取出积压帧
        assertNull(ring.takeLatest(0))
        ring.release(slot)
    }

    @Test
    fun compactsPaddedStrides() {
        val ring = YuvFrameRing()
        val width = 4
        val height = 4
        val uvPixelStride = 2
        val marker = 7
        assertTrue(ring.submit(makeFrame(width, height, 2, uvPixelStride, marker)))
        val slot = ring.takeLatest(0)!!
        val frame = slot.frame!!

        // stride 被紧凑成 width / uvWidth*uvPixelStride
        assertEquals(width, frame.yRowStride)
        assertEquals(((width + 1) / 2) * uvPixelStride, frame.uvRowStride)
        assertEquals(width, frame.y.limit())
        assertEquals(((width + 1) / 2) * ((height + 1) / 2) * uvPixelStride, frame.u.limit())

        // 内容逐像素一致（行 padding 不得混进紧凑缓冲）
        for (row in 0 until height) {
            for (x in 0 until width) {
                val expected = (marker + row * 31 + x * 7) and 0xFF
                assertEquals(expected, frame.y.get(row * width + x).toInt() and 0xFF)
            }
        }
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val uvRowBytes = uvWidth * uvPixelStride
        for (row in 0 until uvHeight) {
            for (x in 0 until uvRowBytes) {
                assertEquals((marker + 100 + row * 13 + x * 3) and 0xFF, frame.u.get(row * uvRowBytes + x).toInt() and 0xFF)
                assertEquals((marker + 200 + row * 17 + x * 5) and 0xFF, frame.v.get(row * uvRowBytes + x).toInt() and 0xFF)
            }
        }
        ring.release(slot)
    }

    @Test
    fun padsTruncatedSourceWithFallbackValues() {
        val ring = YuvFrameRing()
        val width = 4
        val height = 4
        // 源 Y 缓冲只给两行：剩下的行按旧路径的越界回退值 16 补齐（UV = 128）
        val y = ByteBuffer.allocate(width * 2)
        for (i in 0 until width * 2) {
            y.put(i, 0x7F)
        }
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val u = ByteBuffer.allocate(uvWidth * 2)
        val v = ByteBuffer.allocate(uvWidth * 2)
        for (i in 0 until uvWidth * 2) {
            u.put(i, 0x33)
            v.put(i, 0x44)
        }
        val frame = Yuv420Frame(
            y = y,
            u = u,
            v = v,
            width = width,
            height = height,
            yRowStride = width,
            uvRowStride = uvWidth * 2,
            uvPixelStride = 2,
            rotationDegrees = 0,
            mirrorHorizontal = false,
        )
        assertTrue(ring.submit(frame))
        val copied = ring.takeLatest(0)!!.frame!!
        assertEquals(0x7F, copied.y.get(0).toInt() and 0xFF)
        assertEquals(0x7F, copied.y.get(width * 2 - 1).toInt() and 0xFF)
        assertEquals(16, copied.y.get(width * 2).toInt() and 0xFF)
        assertEquals(16, copied.y.get(width * height - 1).toInt() and 0xFF)
        assertEquals(0x33, copied.u.get(0).toInt() and 0xFF)
        assertEquals(128, copied.u.get(uvWidth * 2).toInt() and 0xFF)
    }

    @Test
    fun carriesFaceHintAndCaptureTimestamp() {
        val ring = YuvFrameRing()
        val hint = FaceRoiHint(left = 0.1f, top = 0.1f, right = 0.4f, bottom = 0.5f, score = 0.9f)
        assertTrue(ring.submit(makeFrame(4, 4, 0, 2, 1), hint, 123456789L))
        val slot = ring.takeLatest(0)!!
        assertEquals(123456789L, slot.captureTimestampMs)
        assertSame(hint, slot.faceHint)
        ring.release(slot)
        assertNull(ring.takeLatest(0))
    }

    @Test
    fun releaseAllowsReuseAndRejectsDoubleRelease() {
        val ring = YuvFrameRing(slotCount = 2)
        assertTrue(ring.submit(makeFrame(4, 4, 0, 2, 1)))
        val slot = ring.takeLatest(0)!!
        ring.release(slot)
        ring.release(slot) // 双归还必须被忽略，不能破坏空闲池
        assertTrue(ring.submit(makeFrame(4, 4, 0, 2, 2)))
        val again = ring.takeLatest(0)!!
        assertEquals((2 + 0 * 31) and 0xFF, again.frame!!.y.get(0).toInt() and 0xFF)
        ring.release(again)
    }

    @Test
    fun drainDropsPendingWithoutCountingDrops() {
        var dropped = 0
        val ring = YuvFrameRing { dropped++ }
        assertTrue(ring.submit(makeFrame(4, 4, 0, 2, 1)))
        ring.drain()
        assertNull(ring.takeLatest(0))
        assertEquals(0, dropped)
    }

    @Test
    fun closeRejectsSubmitAndWakesConsumer() {
        val ring = YuvFrameRing()
        assertTrue(ring.submit(makeFrame(4, 4, 0, 2, 1)))
        ring.close()
        assertNull(ring.takeLatest(0))
        assertFalse(ring.submit(makeFrame(4, 4, 0, 2, 2)))
        // reset 之后可以复用（检测器 release → initialize → start 的场景）
        ring.reset()
        assertTrue(ring.submit(makeFrame(4, 4, 0, 2, 3)))
        assertNotNull(ring.takeLatest(0))
    }

    @Test
    fun rejectsInvalidFrame() {
        val ring = YuvFrameRing()
        val bad = Yuv420Frame(
            y = ByteBuffer.allocate(16),
            u = ByteBuffer.allocate(4),
            v = ByteBuffer.allocate(4),
            width = 0,
            height = 4,
            yRowStride = 4,
            uvRowStride = 4,
            uvPixelStride = 2,
            rotationDegrees = 0,
            mirrorHorizontal = false,
        )
        assertFalse(ring.submit(bad))
        assertNull(ring.takeLatest(0))
    }
}
