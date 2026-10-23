package com.example.mediapipeupperbody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FaceRoiHint] / [RoiWindow] / [UpperBodyRoi] 的 JVM 单元测试（不依赖 Android）。
 *
 * 数值全部手算（帧 1000×800，脸框 0.45..0.55 × 0.20..0.30）：
 *  - faceW = 100px、faceH = 80px；
 *  - bodyW = 100 × 4.5 = 450、bodyH = 80 × 5.5 = 440；
 *  - 水平以脸中心 500px 为中心 → x0 = 500 - 225 = 275；
 *  - bodyTop = 0.20 × 800 - 80 × 0.35 = 160 - 28 = 132。
 */
class UpperBodyRoiTest {

    private val frameWidth = 1000
    private val frameHeight = 800

    /** 脸框 0.45..0.55 × 0.20..0.30 → 100×80 px。 */
    private val face = FaceRoiHint(left = 0.45f, top = 0.20f, right = 0.55f, bottom = 0.30f, score = 0.95f)

    private fun hint(left: Float, top: Float, right: Float, bottom: Float, score: Float = 0.9f) =
        FaceRoiHint(left = left, top = top, right = right, bottom = bottom, score = score)

    // ---------------------------------------------------------------- 提示合法性

    @Test
    fun hintValidityChecksFiniteSpanAndScore() {
        assertTrue(face.isValid)
        // 分数低是 controller 的过滤条件，不是几何非法
        assertTrue(hint(0.45f, 0.20f, 0.55f, 0.30f, score = 0.1f).isValid)

        assertFalse(hint(0.55f, 0.20f, 0.45f, 0.30f).isValid)
        assertFalse(hint(0.45f, 0.30f, 0.55f, 0.20f).isValid)
        assertFalse(hint(Float.NaN, 0.20f, 0.55f, 0.30f).isValid)
        assertFalse(hint(0.45f, 0.20f, Float.POSITIVE_INFINITY, 0.30f).isValid)
        assertFalse(hint(0.45f, 0.20f, 0.55f, 0.30f, score = Float.NaN).isValid)
        assertFalse(hint(0.45f, 0.20f, 0.55f, 0.30f, score = -0.1f).isValid)
        assertFalse(hint(0.45f, 0.20f, 0.55f, 0.30f, score = 1.2f).isValid)

        assertEquals(0.1f, face.width, EPS)
        assertEquals(0.1f, face.height, EPS)
    }

    // ---------------------------------------------------------------- 脸框 → ROI

    @Test
    fun noFaceHintUsesFullFrame() {
        assertEquals(RoiWindow(0, 0, frameWidth, frameHeight), UpperBodyRoi.fromFace(null, frameWidth, frameHeight))
        // 帧尺寸非法：返回 0 尺寸窗口（转换器会直接返回 null）
        assertTrue(UpperBodyRoi.fromFace(null, 0, frameHeight).isEmpty)
    }

    @Test
    fun invalidFaceHintUsesFullFrame() {
        val full = RoiWindow(0, 0, frameWidth, frameHeight)
        assertEquals(full, UpperBodyRoi.fromFace(face.copy(right = 0.40f), frameWidth, frameHeight))
        assertEquals(full, UpperBodyRoi.fromFace(face.copy(bottom = 0.10f), frameWidth, frameHeight))
        assertEquals(full, UpperBodyRoi.fromFace(face.copy(left = Float.NaN), frameWidth, frameHeight))
        assertEquals(full, UpperBodyRoi.fromFace(face.copy(score = Float.NaN), frameWidth, frameHeight))
        assertEquals(full, UpperBodyRoi.fromFace(face.copy(score = 1.5f), frameWidth, frameHeight))
        // 零宽
        assertEquals(full, UpperBodyRoi.fromFace(face.copy(left = 0.5f, right = 0.5f), frameWidth, frameHeight))
    }

    @Test
    fun faceHintExpandsToUpperBodyRoi() {
        val roi = UpperBodyRoi.fromFace(face, frameWidth, frameHeight)

        // bodyW = 100 × 4.5 = 450、bodyH = 80 × 5.5 = 440
        assertEquals(450, roi.width)
        assertEquals(440, roi.height)
        // 水平以脸中心为中心：500 - 450 / 2 = 275
        assertEquals(275, roi.x0)
        assertEquals(500, roi.x0 + roi.width / 2)
        // 顶部 = 脸顶(160) - 脸高(80) × 0.35 = 132，底部覆盖到胯部区域
        assertEquals(132, roi.y0)
        assertEquals(572, roi.y0 + roi.height)

        assertTrue("body 应比脸宽", roi.width > (face.width * frameWidth).toInt())
        assertTrue("body 应比脸高", roi.height > (face.height * frameHeight).toInt())
        assertTrue("ROI 顶部应在脸顶之上", roi.y0 < (face.top * frameHeight).toInt())
        assertTrue("ROI 底部应在脸底之下", roi.y0 + roi.height > (face.bottom * frameHeight).toInt())
    }

    @Test
    fun roiIsClampedToFrameEdges() {
        // 贴左上角：x0 / y0 会算成负数 → clamp 到 0，尺寸保持 450×440
        assertEquals(
            RoiWindow(0, 0, 450, 440),
            UpperBodyRoi.fromFace(hint(0f, 0f, 0.10f, 0.10f), frameWidth, frameHeight),
        )
        // 贴右下角：位置夹到 frame - body
        assertEquals(
            RoiWindow(550, 360, 450, 440),
            UpperBodyRoi.fromFace(hint(0.90f, 0.90f, 1f, 1f), frameWidth, frameHeight),
        )
        // 脸几乎占满整帧：尺寸先夹到帧大小，位置归零
        assertEquals(
            RoiWindow(0, 0, frameWidth, frameHeight),
            UpperBodyRoi.fromFace(hint(0f, 0f, 1f, 1f), frameWidth, frameHeight),
        )
        // 脸一半在画面外：ROI 仍然完全落在帧内
        val halfOutside = UpperBodyRoi.fromFace(hint(-0.05f, -0.05f, 0.15f, 0.15f), frameWidth, frameHeight)
        assertTrue(halfOutside.fitsWithin(frameWidth, frameHeight))
        assertEquals(0, halfOutside.x0)
        assertEquals(0, halfOutside.y0)
    }

    @Test
    fun roiAlwaysContainsTheFaceBox() {
        val cases = listOf(
            hint(0.30f, 0.20f, 0.40f, 0.30f),
            hint(0.10f, 0.60f, 0.20f, 0.70f),
            hint(0.60f, 0.05f, 0.80f, 0.20f),
        )
        for (case in cases) {
            val roi = UpperBodyRoi.fromFace(case, frameWidth, frameHeight)
            assertTrue("ROI 左边界应覆盖脸框", roi.x0 <= case.left * frameWidth + 1f)
            assertTrue("ROI 右边界应覆盖脸框", roi.x0 + roi.width >= case.right * frameWidth - 1f)
            assertTrue("ROI 上边界应覆盖脸框", roi.y0 <= case.top * frameHeight + 1f)
            assertTrue("ROI 下边界应覆盖脸框", roi.y0 + roi.height >= case.bottom * frameHeight - 1f)
        }
    }

    @Test
    fun tinyFaceFallsBackToFullFrame() {
        val full = RoiWindow(0, 0, frameWidth, frameHeight)
        // 5×5 px 的脸：小于 MIN_FACE_PX（12）
        assertEquals(full, UpperBodyRoi.fromFace(hint(0.50f, 0.50f, 0.505f, 0.505f), frameWidth, frameHeight))
        // 只有高度小于 12px 也要回退
        assertEquals(full, UpperBodyRoi.fromFace(hint(0.40f, 0.50f, 0.50f, 0.503f), frameWidth, frameHeight))
    }

    @Test
    fun tooSmallRoiFallsBackToFullFrame() {
        // 40×40 的帧：脸够大（16px），但 4.5 倍宽度只有 40px < MIN_ROI_PX（48）
        assertEquals(
            RoiWindow(0, 0, 40, 40),
            UpperBodyRoi.fromFace(hint(0.1f, 0.1f, 0.5f, 0.5f), 40, 40),
        )
    }

    // ---------------------------------------------------------------- 坐标回映

    @Test
    fun mapIsIdentityForFullFrame() {
        val full = RoiWindow(0, 0, frameWidth, frameHeight)
        val plain = UpperBodyRoi.mapLocalToPreview(0.3f, 0.7f, -0.25f, full, frameWidth, frameHeight, mirrored = false)
        assertEquals(0.3f, plain.x, EPS)
        assertEquals(0.7f, plain.y, EPS)
        assertEquals(-0.25f, plain.z, EPS)

        // 满帧 + 镜像：整帧已经翻转过，回映必须原样返回（旧行为不变）
        val mirrored = UpperBodyRoi.mapLocalToPreview(0.3f, 0.7f, -0.25f, full, frameWidth, frameHeight, mirrored = true)
        assertEquals(0.3f, mirrored.x, EPS)
        assertEquals(0.7f, mirrored.y, EPS)
        assertEquals(-0.25f, mirrored.z, EPS)
    }

    @Test
    fun mapRoiLocalToPreviewWithoutMirror() {
        // left = 0.1、sx = 0.4、top = 0.25、sy = 0.5
        val roi = RoiWindow(100, 200, 400, 400)
        val topLeft = UpperBodyRoi.mapLocalToPreview(0f, 0f, 0f, roi, frameWidth, frameHeight, mirrored = false)
        assertEquals(0.1f, topLeft.x, EPS)
        assertEquals(0.25f, topLeft.y, EPS)
        val bottomRight = UpperBodyRoi.mapLocalToPreview(1f, 1f, 0f, roi, frameWidth, frameHeight, mirrored = false)
        assertEquals(0.5f, bottomRight.x, EPS)
        assertEquals(0.75f, bottomRight.y, EPS)
        val middle = UpperBodyRoi.mapLocalToPreview(0.5f, 0.5f, 0f, roi, frameWidth, frameHeight, false)
        assertEquals(0.3f, middle.x, EPS)
    }

    @Test
    fun mapRoiLocalToPreviewWithMirror() {
        val roi = RoiWindow(100, 200, 400, 400)
        // 镜像后 ROI 的局部左 = 整帧预览的 1 - left - sx = 0.5
        val topLeft = UpperBodyRoi.mapLocalToPreview(0f, 0f, 0f, roi, frameWidth, frameHeight, mirrored = true)
        assertEquals(0.5f, topLeft.x, EPS)
        assertEquals(0.25f, topLeft.y, EPS)
        val bottomRight = UpperBodyRoi.mapLocalToPreview(1f, 1f, 0f, roi, frameWidth, frameHeight, mirrored = true)
        assertEquals(0.9f, bottomRight.x, EPS)
        assertEquals(0.75f, bottomRight.y, EPS)

        // ROI 贴右边缘时，局部左 = 预览最左（镜像的正确表现）
        val rightEdge = UpperBodyRoi.mapLocalToPreview(
            0f, 0f, 0f, RoiWindow(600, 0, 400, frameHeight), frameWidth, frameHeight, mirrored = true,
        )
        assertEquals(0f, rightEdge.x, EPS)
    }

    @Test
    fun mapScalesDepthByRoiWidthRatio() {
        // z 以整帧宽度为单位：LOCAL z × sx（sx = 0.4）
        val roi = RoiWindow(100, 200, 400, 400)
        assertEquals(-0.2f, UpperBodyRoi.mapLocalToPreview(0f, 0f, -0.5f, roi, frameWidth, frameHeight, false).z, EPS)
        assertEquals(-0.2f, UpperBodyRoi.mapLocalToPreview(0f, 0f, -0.5f, roi, frameWidth, frameHeight, true).z, EPS)
        // 更窄的 ROI：z 同比变小（躯干 yaw 不会被 ROI 大小带偏）
        val narrow = RoiWindow(0, 0, 200, 400)
        val narrowZ = UpperBodyRoi.mapLocalToPreview(0f, 0f, -0.5f, narrow, frameWidth, frameHeight, false)
        assertEquals(-0.1f, narrowZ.z, EPS)
    }

    // ---------------------------------------------------------------- 输出尺寸 / 窗口

    @Test
    fun scaledSizeUsesLongSide320() {
        // 450×440 → 320×313（长边 320，四舍五入）
        val landscape = UpperBodyRoi.scaledSize(RoiWindow(275, 132, 450, 440), 320)
        assertEquals(320, landscape.width)
        assertEquals(313, landscape.height)

        // 竖长 ROI：320 在高度上
        val portrait = UpperBodyRoi.scaledSize(RoiWindow(0, 0, 200, 500), 320)
        assertEquals(128, portrait.width)
        assertEquals(320, portrait.height)

        // 只缩不放
        val small = UpperBodyRoi.scaledSize(RoiWindow(0, 0, 100, 80), 320)
        assertEquals(100, small.width)
        assertEquals(80, small.height)
    }

    @Test
    fun roiWindowHelpers() {
        val full = RoiWindow(0, 0, frameWidth, frameHeight)
        assertTrue(full.isFullFrame(frameWidth, frameHeight))
        assertFalse(RoiWindow(0, 0, frameWidth - 1, frameHeight).isFullFrame(frameWidth, frameHeight))
        assertFalse(full.isEmpty)
        assertTrue(RoiWindow(0, 0, 0, frameHeight).isEmpty)
        assertTrue(RoiWindow(600, 0, 400, frameHeight).fitsWithin(frameWidth, frameHeight))
        assertFalse(RoiWindow(601, 0, 400, frameHeight).fitsWithin(frameWidth, frameHeight))
        assertFalse(RoiWindow(-1, 0, 400, frameHeight).fitsWithin(frameWidth, frameHeight))
        assertFalse(RoiWindow(0, 0, 0, frameHeight).fitsWithin(frameWidth, frameHeight))
        assertEquals(frameWidth, full.right)
        assertEquals(frameHeight, full.bottom)
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
