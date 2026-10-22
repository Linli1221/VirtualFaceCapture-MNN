package com.example.mediapipeupperbody

import com.example.commondata.TorsoAngles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TorsoBaseline] 的 JVM 单元测试。 */
class TorsoBaselineTest {

    @Test
    fun calibrationFramesProduceZeroRelativeAngle() {
        val baseline = TorsoBaseline(calibrationFrames = 10)
        repeat(10) {
            val relative = baseline.apply(TorsoAngles(20f, -10f))
            assertEquals(0f, relative.roll, 1e-4f)
            assertEquals(0f, relative.yaw, 1e-4f)
        }
        assertTrue(baseline.isCalibrated)
    }

    @Test
    fun relativeAngleIsSubtractedAfterCalibration() {
        val baseline = TorsoBaseline(calibrationFrames = 10)
        repeat(10) { baseline.apply(TorsoAngles(20f, -10f)) }
        val relative = baseline.apply(TorsoAngles(35f, 5f))
        assertEquals(15f, relative.roll, 1e-4f)
        assertEquals(15f, relative.yaw, 1e-4f)
    }

    @Test
    fun resetRecalibratesFromScratch() {
        val baseline = TorsoBaseline(calibrationFrames = 3)
        repeat(3) { baseline.apply(TorsoAngles(20f, 0f)) }
        assertEquals(30f, baseline.apply(TorsoAngles(50f, 0f)).roll, 1e-4f)

        baseline.reset()
        assertFalse(baseline.isCalibrated)
        val relative = baseline.apply(TorsoAngles(5f, 5f))
        assertEquals(0f, relative.roll, 1e-4f)
        assertEquals(0f, relative.yaw, 1e-4f)
    }

    @Test
    fun invalidAnglesDoNotPolluteCalibration() {
        val baseline = TorsoBaseline(calibrationFrames = 2)
        assertEquals(TorsoAngles.ZERO, baseline.apply(TorsoAngles(Float.NaN, 0f)))
        assertFalse(baseline.isCalibrated)
        // 第一帧有效值本身就是均值 → 相对角为 0，此时只累计了 1 帧（还没校准完）
        assertEquals(0f, baseline.apply(TorsoAngles(10f, 0f)).roll, 1e-4f)
        assertFalse(baseline.isCalibrated)
        // 第二帧：均值 = (10 + 15) / 2 = 12.5 → 相对角 = 15 - 12.5 = 2.5，且校准完成
        assertEquals(2.5f, baseline.apply(TorsoAngles(15f, 0f)).roll, 1e-4f)
        assertTrue(baseline.isCalibrated)
    }
}
