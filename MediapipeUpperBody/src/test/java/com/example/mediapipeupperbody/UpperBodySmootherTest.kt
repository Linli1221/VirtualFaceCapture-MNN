package com.example.mediapipeupperbody

import com.example.commondata.BodyPoint
import com.example.commondata.TorsoAngles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OneEuroFilter] / [UpperBodySmoother] 的 JVM 单元测试（不依赖 Android）。 */
class UpperBodySmootherTest {

    private val dt = 1f / 30f

    @Test
    fun oneEuroConvergesToConstantInput() {
        val filter = OneEuroFilter()
        var output = 0f
        repeat(240) { output = filter.filter(1f, dt) }
        assertTrue("收敛后的值应接近 1，实际 = " + output, output > 0.99f)
        assertTrue("不应超过输入，实际 = " + output, output <= 1.0001f)
        assertTrue(filter.hasValue)
    }

    @Test
    fun oneEuroNeverOvershootsMonotonicRamp() {
        val filter = OneEuroFilter()
        filter.filter(0f, dt)
        var previousInput = 0f
        for (step in 1..120) {
            val input = step / 120f
            val output = filter.filter(input, dt)
            assertTrue("输出必须有限", output.isFinite())
            // 一阶低通不会越过「上一次输出 → 当前输入」的区间
            assertTrue("输出上界: input=" + input + " output=" + output, output <= input + 1e-4f)
            assertTrue("输出下界: input=" + input + " output=" + output, output >= previousInput - 1e-4f)
            previousInput = output
        }
    }

    @Test
    fun oneEuroIsNanSafe() {
        val filter = OneEuroFilter()
        assertEquals(0f, filter.filter(Float.NaN, dt), 0f)
        assertEquals(0.5f, filter.filter(0.5f, dt), 1e-6f)
        assertEquals(0.5f, filter.filter(Float.NaN, dt), 1e-6f)
        assertEquals(0.5f, filter.filter(Float.POSITIVE_INFINITY, dt), 0f)
        // dt 非法也不能崩
        assertEquals(0.5f, filter.filter(0.5f, Float.NaN), 0f)
        assertEquals(0.5f, filter.filter(0.5f, 0f), 0f)
    }

    @Test
    fun oneEuroResetDropsHistory() {
        val filter = OneEuroFilter()
        repeat(30) { filter.filter(1f, dt) }
        filter.reset()
        assertFalse(filter.hasValue)
        assertEquals(0f, filter.filter(0f, dt), 0f)
    }

    @Test
    fun smootherKeepsProtocolCountAndVisibility() {
        val smoother = UpperBodySmoother()
        val input = List(13) { index -> BodyPoint(0.1f + index * 0.05f, 0.2f, 0f, 0.8f) }
        val output = smoother.smooth(input, dt)
        assertEquals(13, output.size)
        for (point in output) {
            assertEquals(0.8f, point.visibility, 0f)
            assertTrue(point.x.isFinite())
            assertTrue(point.y.isFinite())
            assertTrue(point.z.isFinite())
        }
    }

    @Test
    fun smootherConvergesToStaticPose() {
        val smoother = UpperBodySmoother()
        val target = List(13) { BodyPoint(0.3f, 0.4f, -0.1f, 0.9f) }
        var output = target
        repeat(180) { output = smoother.smooth(target, dt) }
        for (point in output) {
            assertEquals(0.3f, point.x, 1e-3f)
            assertEquals(0.4f, point.y, 1e-3f)
            assertEquals(-0.1f, point.z, 1e-3f)
        }
    }

    @Test
    fun smootherResetStartsFromNewPose() {
        val smoother = UpperBodySmoother()
        val oldPose = List(13) { BodyPoint(1f, 1f, 0f, 0.9f) }
        smoother.smooth(oldPose, dt)
        smoother.smooth(oldPose, dt)
        smoother.reset()
        val newPose = List(13) { BodyPoint(0f, 0f, 0f, 0.9f) }
        val output = smoother.smooth(newPose, dt)
        assertEquals(0f, output[0].x, 1e-6f)
        assertEquals(0f, output[0].y, 1e-6f)
    }

    @Test
    fun torsoIsSmoothedAndConverges() {
        val smoother = UpperBodySmoother()
        smoother.smoothTorso(TorsoAngles(0f, 0f), dt)
        var output = TorsoAngles(0f, 0f)
        repeat(180) {
            output = smoother.smoothTorso(TorsoAngles(30f, -20f), dt)
            assertTrue(output.roll >= -1e-3f && output.roll <= 30f + 1e-3f)
            assertTrue(output.yaw >= -20f - 1e-3f && output.yaw <= 1e-3f)
        }
        assertEquals(30f, output.roll, 0.01f)
        assertEquals(-20f, output.yaw, 0.01f)
    }

    @Test
    fun smootherPassesMissingPointThroughAsZero() {
        val smoother = UpperBodySmoother()
        val pose = List(13) { BodyPoint(0.2f, 0.3f, 0f, 0.9f) }
        smoother.smooth(pose, dt)
        val mixed = pose.toMutableList()
        mixed[0] = BodyPoint.ZERO
        val output = smoother.smooth(mixed, dt)
        assertEquals(BodyPoint.ZERO, output[0])
        // 其它点继续平滑，不受影响
        assertEquals(0.2f, output[1].x, 1e-6f)
        assertEquals(0.3f, output[1].y, 1e-6f)
    }

    @Test
    fun torsoWithInvalidAnglesIsPassedThrough() {
        val smoother = UpperBodySmoother()
        val result = smoother.smoothTorso(TorsoAngles(Float.NaN, 0f), dt)
        assertTrue(result.roll.isNaN())
        assertEquals(0f, result.yaw, 0f)
    }
}
