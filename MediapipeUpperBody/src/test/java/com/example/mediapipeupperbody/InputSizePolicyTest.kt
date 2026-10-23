package com.example.mediapipeupperbody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InputSizePolicy] 的 JVM 单元测试（不依赖 Android）。
 *
 * 覆盖三件事：
 *  1. A/B 固定档：PRECISION 恒 320、FRAME_RATE 恒 256，任何信号都不改档；
 *  2. 自适应升档：脸框变小 / ROI miss / 质量差 / 没跟踪到 → 立刻 320；
 *  3. 自适应回落：只有**连续若干帧**质量好才回 256，中间带会打断连击。
 */
class InputSizePolicyTest {

    private val normal = InputSizePolicy.DEFAULT_NORMAL_LONG_SIDE
    private val boost = InputSizePolicy.DEFAULT_BOOST_LONG_SIDE

    /** 典型的脸框面积（归一化）：不算小（脸宽高约帧的 20%）。 */
    private val normalFaceArea = 0.04f

    /** 远距离的脸框面积（脸宽高约帧的 5%）。 */
    private val smallFaceArea = 0.0025f

    private fun policy(exitFrames: Int = 3) = InputSizePolicy(exitFrames = exitFrames)

    // ---------------------------------------------------------------- A/B 固定档

    @Test
    fun fixedPrecisionAlwaysUses320() {
        val p = policy()
        p.mode = InputSizeMode.PRECISION
        assertEquals(boost, p.selectLongSide(normalFaceArea, false))
        assertEquals(boost, p.selectLongSide(smallFaceArea, true))
        p.onFrameResult(tracked = false, quality = 0f)
        p.onFrameResult(tracked = true, quality = 0.99f)
        assertEquals(boost, p.selectLongSide(normalFaceArea, false))
        assertFalse(p.isBoosted())
    }

    @Test
    fun fixedFrameRateAlwaysUses256() {
        val p = policy()
        p.mode = InputSizeMode.FRAME_RATE
        assertEquals(normal, p.selectLongSide(normalFaceArea, false))
        assertEquals(normal, p.selectLongSide(smallFaceArea, true))
        p.onFrameResult(tracked = false, quality = 0f)
        p.onFrameResult(tracked = true, quality = 0.99f)
        assertEquals(normal, p.selectLongSide(smallFaceArea, true))
        assertFalse(p.isBoosted())
    }

    // ---------------------------------------------------------------- 自适应：起步 / 正常跟踪

    @Test
    fun adaptiveStartsAt256() {
        val p = policy()
        assertEquals(InputSizeMode.ADAPTIVE, p.mode)
        assertEquals(normal, p.selectLongSide(normalFaceArea, false))
        assertFalse(p.isBoosted())
    }

    @Test
    fun normalTrackingStays256() {
        val p = policy()
        repeat(10) {
            assertEquals(normal, p.selectLongSide(normalFaceArea, false))
            p.onFrameResult(tracked = true, quality = 0.9f)
        }
        assertFalse(p.isBoosted())
    }

    @Test
    fun missingFaceHintAloneDoesNotBoost() {
        // 0 = 这一帧没有脸框提示（≠ 人变远），只有 ROI 回退 / 质量反馈才升档
        val p = policy()
        assertEquals(normal, p.selectLongSide(0f, false))
        assertFalse(p.isBoosted())
    }

    // ---------------------------------------------------------------- 自适应：升档信号

    @Test
    fun smallFaceBoostsImmediately() {
        val p = policy()
        assertEquals(boost, p.selectLongSide(smallFaceArea, false))
        assertTrue(p.isBoosted())
    }

    @Test
    fun roiFallbackWindowBoostsImmediately() {
        val p = policy()
        assertEquals(boost, p.selectLongSide(normalFaceArea, true))
        assertTrue(p.isBoosted())
    }

    @Test
    fun lowQualityFrameBoostsImmediately() {
        val p = policy()
        assertEquals(normal, p.selectLongSide(normalFaceArea, false))
        p.onFrameResult(tracked = true, quality = 0.3f)
        assertTrue(p.isBoosted())
        assertEquals(boost, p.selectLongSide(normalFaceArea, false))
    }

    @Test
    fun untrackedFrameBoostsImmediately() {
        val p = policy()
        p.onFrameResult(tracked = false, quality = 0f)
        assertTrue(p.isBoosted())
    }

    @Test
    fun nonFiniteQualityTreatedAsWorst() {
        val p = policy()
        p.onFrameResult(tracked = true, quality = Float.NaN)
        assertTrue(p.isBoosted())
    }

    // ---------------------------------------------------------------- 自适应：回落滞回

    @Test
    fun recoveryNeedsConsecutiveGoodFrames() {
        val p = policy(exitFrames = 3)
        p.onFrameResult(tracked = false, quality = 0f)
        assertTrue(p.isBoosted())

        p.onFrameResult(tracked = true, quality = 0.9f)
        p.onFrameResult(tracked = true, quality = 0.9f)
        assertTrue(p.isBoosted()) // 只有 2 帧，还不够
        assertEquals(boost, p.selectLongSide(normalFaceArea, false))

        p.onFrameResult(tracked = true, quality = 0.9f)
        assertFalse(p.isBoosted())
        assertEquals(normal, p.selectLongSide(normalFaceArea, false))
    }

    @Test
    fun middleBandBreaksRecoveryStreak() {
        val p = policy(exitFrames = 2)
        p.onFrameResult(tracked = true, quality = 0.3f)
        assertTrue(p.isBoosted())

        p.onFrameResult(tracked = true, quality = 0.9f) // 连击 1
        p.onFrameResult(tracked = true, quality = 0.6f) // 中间带：保持 320，连击清零
        assertTrue(p.isBoosted())
        p.onFrameResult(tracked = true, quality = 0.9f) // 连击 1
        assertTrue(p.isBoosted())
        p.onFrameResult(tracked = true, quality = 0.9f) // 连击 2 → 回落
        assertFalse(p.isBoosted())
    }

    @Test
    fun badFrameDuringRecoveryRestartsStreak() {
        val p = policy(exitFrames = 2)
        p.onFrameResult(tracked = false, quality = 0f)
        p.onFrameResult(tracked = true, quality = 0.9f) // 连击 1
        p.onFrameResult(tracked = true, quality = 0.4f) // 又变差
        assertTrue(p.isBoosted())
        p.onFrameResult(tracked = true, quality = 0.9f) // 连击 1
        assertTrue(p.isBoosted())
        p.onFrameResult(tracked = true, quality = 0.9f) // 连击 2 → 回落
        assertFalse(p.isBoosted())
    }

    @Test
    fun smallFaceDuringRecoveryRestartsStreak() {
        val p = policy(exitFrames = 2)
        p.onFrameResult(tracked = false, quality = 0f)
        p.onFrameResult(tracked = true, quality = 0.9f) // 连击 1
        assertEquals(boost, p.selectLongSide(smallFaceArea, false)) // 脸又变小：连击清零
        assertTrue(p.isBoosted())
        p.onFrameResult(tracked = true, quality = 0.9f) // 连击 1
        assertTrue(p.isBoosted())
        p.onFrameResult(tracked = true, quality = 0.9f) // 连击 2 → 回落
        assertFalse(p.isBoosted())
    }

    // ---------------------------------------------------------------- 档位切换 / reset

    @Test
    fun switchingModeResetsBoostState() {
        val p = policy()
        p.onFrameResult(tracked = false, quality = 0f)
        assertTrue(p.isBoosted())

        p.mode = InputSizeMode.FRAME_RATE
        assertFalse(p.isBoosted())
        p.mode = InputSizeMode.ADAPTIVE
        assertFalse(p.isBoosted())
        assertEquals(normal, p.selectLongSide(normalFaceArea, false))
    }

    @Test
    fun resetClearsBoostState() {
        val p = policy()
        p.onFrameResult(tracked = false, quality = 0f)
        assertTrue(p.isBoosted())
        p.reset()
        assertFalse(p.isBoosted())
        assertEquals(normal, p.selectLongSide(normalFaceArea, false))
    }
}
