package com.facecapture.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FrameReorderBuffer] 的单元测试（纯 JVM，假时钟）。
 *
 * 场景都按「双通道」的真实时序构造：GPU 通道 ~12ms、CPU 通道 ~20ms，
 * 两路结果会以任意顺序到达重排缓冲。
 */
class FrameReorderBufferTest {

    private val t0 = 1_000_000L

    @Test
    fun firstFramePassesThroughImmediately() {
        val buffer = FrameReorderBuffer<String>(maxPending = 8, gapTimeoutMs = 60)

        val out = buffer.offer(0, "f0", t0)

        assertEquals(listOf("f0"), out)
        assertFalse(buffer.isWaitingForGap)
    }

    @Test
    fun inOrderFramesPassThroughOneByOne() {
        val buffer = FrameReorderBuffer<String>(maxPending = 8, gapTimeoutMs = 60)

        assertEquals(listOf("f0"), buffer.offer(0, "f0", t0))
        assertEquals(listOf("f1"), buffer.offer(1, "f1", t0 + 16))
        assertEquals(listOf("f2"), buffer.offer(2, "f2", t0 + 33))
        assertEquals(0, buffer.size)
    }

    @Test
    fun outOfOrderFramesAreEmittedInOrder() {
        val buffer = FrameReorderBuffer<String>(maxPending = 8, gapTimeoutMs = 60)

        assertEquals(listOf("f0"), buffer.offer(0, "f0", t0))
        // GPU 通道先算完第 2 帧，CPU 通道的第 1 帧还在路上。
        assertTrue(buffer.offer(2, "f2", t0 + 10).isEmpty())
        assertTrue(buffer.isWaitingForGap)

        // 第 1 帧到达后，两帧一起按顺序放行。
        assertEquals(listOf("f1", "f2"), buffer.offer(1, "f1", t0 + 20))
        assertFalse(buffer.isWaitingForGap)
    }

    @Test
    fun gapWaitsUntilTimeoutThenSkips() {
        val buffer = FrameReorderBuffer<String>(maxPending = 8, gapTimeoutMs = 60)

        assertEquals(listOf("f0"), buffer.offer(0, "f0", t0))
        assertTrue(buffer.offer(2, "f2", t0 + 10).isEmpty())

        // 还没到超时时间：继续等第 1 帧。
        assertTrue(buffer.poll(t0 + 20).isEmpty())
        // 超过 60ms 缺口仍未补上：接受丢帧，放行手里的第 2 帧。
        assertEquals(listOf("f2"), buffer.poll(t0 + 70))
    }

    @Test
    fun lateFrameAfterGapWasSkippedIsDropped() {
        val buffer = FrameReorderBuffer<String>(maxPending = 8, gapTimeoutMs = 60)

        buffer.offer(0, "f0", t0)
        buffer.offer(2, "f2", t0 + 10)
        assertEquals(listOf("f2"), buffer.poll(t0 + 70))

        // 第 1 帧姗姗来迟：已经发过 2，它比 2 旧，直接丢弃。
        assertTrue(buffer.offer(1, "f1", t0 + 80).isEmpty())
    }

    @Test
    fun staleAndDuplicateIdsAreDropped() {
        val buffer = FrameReorderBuffer<String>(maxPending = 8, gapTimeoutMs = 60)

        assertEquals(listOf("f3"), buffer.offer(3, "f3", t0))
        // 更旧 / 重复的帧都没有意义。
        assertTrue(buffer.offer(3, "f3-again", t0 + 5).isEmpty())
        assertTrue(buffer.offer(0, "f0", t0 + 6).isEmpty())
        assertEquals(0, buffer.size)
    }

    @Test
    fun duplicateBeforeEmissionKeepsFirstValue() {
        val buffer = FrameReorderBuffer<String>(maxPending = 8, gapTimeoutMs = 60)

        assertEquals(listOf("f0"), buffer.offer(0, "f0", t0))
        assertTrue(buffer.offer(2, "f2-first", t0 + 10).isEmpty())
        assertTrue(buffer.offer(2, "f2-second", t0 + 11).isEmpty())

        assertEquals(listOf("f1", "f2-first"), buffer.offer(1, "f1", t0 + 20))
    }

    @Test
    fun pendingOverflowForcesGapSkip() {
        // 只允许缓存 2 帧、缺口超时给得极长：一旦堆到第 3 帧就必须强推。
        val buffer = FrameReorderBuffer<String>(maxPending = 2, gapTimeoutMs = 60_000)

        assertEquals(listOf("f0"), buffer.offer(0, "f0", t0))
        assertTrue(buffer.offer(2, "f2", t0 + 10).isEmpty())
        assertTrue(buffer.offer(3, "f3", t0 + 11).isEmpty())

        // 第 3 帧入队让堆积超过上限 -> 放弃等第 1 帧，连续放行。
        assertEquals(listOf("f2", "f3", "f4"), buffer.offer(4, "f4", t0 + 12))
    }

    @Test
    fun zeroTimeoutNeverWaits() {
        val buffer = FrameReorderBuffer<String>(maxPending = 8, gapTimeoutMs = 0)

        assertEquals(listOf("f0"), buffer.offer(0, "f0", t0))
        // 第 1 帧缺了就缺了，第 2 帧立刻放行。
        assertEquals(listOf("f2"), buffer.offer(2, "f2", t0 + 10))
    }

    @Test
    fun resetClearsState() {
        val buffer = FrameReorderBuffer<String>(maxPending = 8, gapTimeoutMs = 60)

        buffer.offer(0, "f0", t0)
        assertTrue(buffer.offer(2, "f2", t0 + 10).isEmpty())
        assertEquals(1, buffer.size)

        buffer.reset()

        assertEquals(0, buffer.size)
        assertFalse(buffer.isWaitingForGap)
        // 重置后第一个帧又可以立即放行。
        assertEquals(listOf("f9"), buffer.offer(9, "f9", t0 + 20))
    }
}
