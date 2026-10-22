package com.example.mediapipeupperbody

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Yuv420Frame] / [YuvGeometry] 的纯数学测试（java.nio 在 JVM 上可用，
 * 不依赖 android.graphics.Bitmap）。
 */
class Yuv420FrameTest {

    private fun frame(
        rotation: Int,
        mirror: Boolean = false,
        width: Int = 640,
        height: Int = 480,
        uvPixelStride: Int = 2,
    ): Yuv420Frame {
        val y = ByteBuffer.allocate(width * height)
        val uvSize = ((width + 1) / 2) * ((height + 1) / 2) * uvPixelStride
        return Yuv420Frame(
            y = y,
            u = ByteBuffer.allocate(uvSize),
            v = ByteBuffer.allocate(uvSize),
            width = width,
            height = height,
            yRowStride = width,
            uvRowStride = width,
            uvPixelStride = uvPixelStride,
            rotationDegrees = rotation,
            mirrorHorizontal = mirror,
        )
    }

    @Test
    fun rotationIsNormalized() {
        assertEquals(0, YuvGeometry.normalizeRotation(0))
        assertEquals(90, YuvGeometry.normalizeRotation(90))
        assertEquals(180, YuvGeometry.normalizeRotation(180))
        assertEquals(270, YuvGeometry.normalizeRotation(270))
        assertEquals(270, YuvGeometry.normalizeRotation(-90))
        assertEquals(90, YuvGeometry.normalizeRotation(450))
        assertEquals(0, YuvGeometry.normalizeRotation(37))
        assertEquals(90, YuvGeometry.normalizeRotation(80))
    }

    @Test
    fun rotatedDimensionsSwapForQuarterTurns() {
        assertEquals(640, frame(0).rotatedWidth)
        assertEquals(480, frame(0).rotatedHeight)
        assertEquals(480, frame(90).rotatedWidth)
        assertEquals(640, frame(90).rotatedHeight)
        assertEquals(640, frame(180).rotatedWidth)
        assertEquals(480, frame(180).rotatedHeight)
        assertEquals(480, frame(270).rotatedWidth)
        assertEquals(640, frame(270).rotatedHeight)
    }

    @Test
    fun targetSizeKeepsAspectRatioAndLongSide() {
        val landscape = frame(0).targetSize()
        assertEquals(256, landscape.width)
        assertEquals(192, landscape.height)

        val portrait = frame(90).targetSize()
        assertEquals(192, portrait.width)
        assertEquals(256, portrait.height)

        assertEquals(256, YuvGeometry.scaledSize(1920, 1080, 256).width)
        assertEquals(144, YuvGeometry.scaledSize(1920, 1080, 256).height)
        assertEquals(144, YuvGeometry.scaledSize(1080, 1920, 256).width)
        assertEquals(256, YuvGeometry.scaledSize(1080, 1920, 256).height)
    }

    @Test
    fun targetSizeDoesNotUpscale() {
        val small = frame(rotation = 0, width = 160, height = 120).targetSize()
        assertEquals(160, small.width)
        assertEquals(120, small.height)
        assertEquals(0, YuvGeometry.scaledSize(0, 100, 256).width)
    }

    @Test
    fun sourceMappingForQuarterTurns() {
        // 90° 顺时针：输出左上角来自源图左下角
        assertEquals(0, YuvGeometry.sourceX(0, 0, 640, 480, 90))
        assertEquals(479, YuvGeometry.sourceY(0, 0, 640, 480, 90))
        // 270°（= 逆时针 90°）：输出左上角来自源图右上角
        assertEquals(639, YuvGeometry.sourceX(0, 0, 640, 480, 270))
        assertEquals(0, YuvGeometry.sourceY(0, 0, 640, 480, 270))
        // 180°
        assertEquals(639, YuvGeometry.sourceX(0, 0, 640, 480, 180))
        assertEquals(479, YuvGeometry.sourceY(0, 0, 640, 480, 180))
        // 0°
        assertEquals(0, YuvGeometry.sourceX(10, 20, 640, 480, 0))
        assertEquals(20, YuvGeometry.sourceY(10, 20, 640, 480, 0))
    }

    @Test
    fun frameValidityCatchesBadStrides() {
        assertTrue(frame(90).isValid())
        assertFalse(frame(90, uvPixelStride = 0).isValid())

        val badStride = Yuv420Frame(
            y = ByteBuffer.allocate(100),
            u = ByteBuffer.allocate(100),
            v = ByteBuffer.allocate(100),
            width = 640,
            height = 480,
            yRowStride = 320,
            uvRowStride = 640,
            uvPixelStride = 2,
            rotationDegrees = 0,
            mirrorHorizontal = false,
        )
        assertFalse(badStride.isValid())
    }

    @Test
    fun mirrorFlagIsExposed() {
        assertTrue(frame(0, mirror = true).mirrorHorizontal)
        assertFalse(frame(0, mirror = false).mirrorHorizontal)
    }
}
