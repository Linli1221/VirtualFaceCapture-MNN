package com.example.virtualfacecapture.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机端发送帧缓冲（[TrackingFrameBuffer]）的本地 JVM 单元测试。
 *
 * 全部用假时钟（纳秒时间戳由测试自己传）驱动，不 sleep、不依赖真机，
 * 因此可以精确断言「什么时候才允许放行哪一帧」。
 */
class TrackingFrameBufferTest {

    /** 测试用假时钟起点（非 0，顺便验证实现没有把 0 当成有效时间戳的假设）。 */
    private val t0 = 1_000_000_000L

    /** 60 fps 的帧间隔，与 [TrackingFrameBuffer] 内部的节奏初值一致。 */
    private val step = 16_666_667L

    /** 0.5 s 缓冲。 */
    private val bufferNanos = 500_000_000L

    // ------------------------------------------------------- 固定 0.5s 延迟

    @Test
    fun holdsFirstFrameUntilBufferIsFull() {
        val buffer = TrackingFrameBuffer(bufferMs = 500)
        buffer.enqueue("f0", t0)

        // 差 1 ns 都不能发：缓冲时间内必须一帧都不放行
        assertNull(buffer.poll(t0 + bufferNanos - 1L))
        assertEquals("f0", buffer.poll(t0 + bufferNanos))
    }

    @Test
    fun rebuffersFullDelayAfterAStall() {
        val buffer = TrackingFrameBuffer(bufferMs = 500)
        buffer.enqueue("f0", t0)
        assertEquals("f0", buffer.poll(t0 + bufferNanos))

        // 断流 10 s 后重新开始推流：新帧同样要重新攒满 0.5 s
        val t1 = t0 + 10_000_000_000L
        buffer.enqueue("f1", t1)
        assertNull(buffer.poll(t1 + bufferNanos - 1L))
        assertEquals("f1", buffer.poll(t1 + bufferNanos))
    }

    // ---------------------------------------------------------- 匀速放行

    @Test
    fun releasesBufferedFramesAtProductionInterval() {
        val buffer = TrackingFrameBuffer(bufferMs = 500)
        buffer.enqueue("f0", t0)
        buffer.enqueue("f1", t0 + step)
        buffer.enqueue("f2", t0 + step * 2)

        assertEquals("f0", buffer.poll(t0 + bufferNanos))
        // 下一帧的到期时刻 = 入队时间 + 0.5s = 上一次放行 + 一个帧间隔
        assertNull(buffer.poll(t0 + bufferNanos + step - 1L))
        assertEquals("f1", buffer.poll(t0 + bufferNanos + step))
        assertEquals("f2", buffer.poll(t0 + bufferNanos + step * 2))
    }

    @Test
    fun smoothsBurstArrivalIntoEvenFrames() {
        // 5 帧挤在同一个瞬间到达（推理一次出一批 / Wi-Fi 突发）
        val buffer = TrackingFrameBuffer(bufferMs = 500)
        for (index in 0 until 5) buffer.enqueue("f$index", t0)

        // 第一帧照常攒满 0.5 s 后放行
        assertEquals("f0", buffer.poll(t0 + bufferNanos))
        // 其余 4 帧不能一口气全发，而是按一个帧间隔一帧
        assertNull(buffer.poll(t0 + bufferNanos))
        assertEquals("f1", buffer.poll(t0 + bufferNanos + step))
        assertEquals("f2", buffer.poll(t0 + bufferNanos + step * 2))
        assertEquals("f3", buffer.poll(t0 + bufferNanos + step * 3))
        assertEquals("f4", buffer.poll(t0 + bufferNanos + step * 4))
    }

    @Test
    fun productionHitchDoesNotDelayFramesAlreadyInBuffer() {
        // 缓冲的意义：推理偶尔卡一下只是把队列填厚，不该让 PC 收到一段空洞
        val buffer = TrackingFrameBuffer(bufferMs = 500)
        for (index in 0 until 11) buffer.enqueue("f$index", t0 + step * index)

        // 起播，并按节奏把 f0 ~ f10 放出去
        var now = t0 + bufferNanos
        assertEquals("f0", buffer.poll(now))
        for (index in 1..10) {
            now += step
            assertEquals("f$index", buffer.poll(now))
        }

        // 推理卡了 100 ms：f11 迟到，但它的到达时刻仍然远早于它该上场的时刻
        buffer.enqueue("f11", t0 + step * 11 + 100_000_000L)

        // 到点就发。若按「入队时间 + 0.5 s」放行，这里会多出 100 ms 的空洞（返回 null）
        assertNull(buffer.poll(now + step - 1L))
        assertEquals("f11", buffer.poll(now + step))
    }

    @Test
    fun followsSlowerProductionRate() {
        // 30 fps：节奏估计要收敛到 33.3 ms，而不是死按 60 fps 发
        val slowStep = 33_333_333L
        val buffer = TrackingFrameBuffer(bufferMs = 500)
        // 先喂足够多帧让 EMA 收敛
        for (index in 0 until 24) buffer.enqueue("f$index", t0 + slowStep * index)

        var now = t0 + bufferNanos
        assertEquals("f0", buffer.poll(now))
        // 放行一帧后，下一帧至少隔一个「生产间隔」（允许 EMA 还没完全收敛的误差）
        assertNull(buffer.poll(now + 20_000_000L))
    }

    // ------------------------------------------------------------ 保护逻辑

    @Test
    fun restoresBufferDepthAfterAProductionHitch() {
        // 长时间运行：卡顿会吃掉缓冲深度，深度调节器要把它补回来，否则延迟越跑越短
        val buffer = TrackingFrameBuffer(bufferMs = 500)
        val hitchFrom = t0 + 3_000_000_000L
        val hitchTo = hitchFrom + 150_000_000L
        var now = t0
        var nextProduce = t0
        var seq = 0
        var lastReleaseNanos = 0L
        var maxGapNanos = 0L

        while (now < t0 + 8_000_000_000L) {
            val stalling = now >= hitchFrom && now < hitchTo
            if (!stalling && now >= nextProduce) {
                buffer.enqueue("f$seq", now)
                seq++
                nextProduce = now + step
            }
            if (buffer.poll(now) != null) {
                if (lastReleaseNanos != 0L) {
                    maxGapNanos = maxOf(maxGapNanos, now - lastReleaseNanos)
                }
                lastReleaseNanos = now
            }
            now += 1_000_000L
        }

        // 推理卡了 150 ms，但缓冲垫着 0.5 s：发送侧最多慢一档节奏，不会跟着卡 150 ms
        assertTrue("最大帧间隔 ${maxGapNanos / 1_000_000} ms", maxGapNanos <= 25_000_000L)
        // 被吃掉的深度已经补回来（60 fps 下 0.5 s ≈ 30 帧）
        assertTrue("结束时缓冲深度 ${buffer.size} 帧", buffer.size >= 25)
    }

    @Test
    fun dropsOldestFramesWhenQueueOverflows() {
        val buffer = TrackingFrameBuffer(bufferMs = 500, maxQueued = 3)
        for (index in 0 until 5) buffer.enqueue("f$index", t0 + step * index)

        assertEquals(3, buffer.size)
        assertEquals(2L, buffer.droppedCount)
        // f0 / f1 被丢掉，队头变成 f2
        assertEquals("f2", buffer.poll(t0 + step * 2 + bufferNanos))
        assertEquals("f3", buffer.poll(t0 + step * 3 + bufferNanos))
        assertEquals("f4", buffer.poll(t0 + step * 4 + bufferNanos))
    }

    @Test
    fun zeroBufferReleasesImmediatelyButKeepsPacing() {
        val buffer = TrackingFrameBuffer(bufferMs = 0)
        buffer.enqueue("f0", t0)
        assertEquals("f0", buffer.poll(t0))

        buffer.enqueue("f1", t0)
        assertNull(buffer.poll(t0))
        assertEquals("f1", buffer.poll(t0 + step))
    }

    @Test
    fun clearDropsPendingFramesAndRestartsDelay() {
        val buffer = TrackingFrameBuffer(bufferMs = 500)
        buffer.enqueue("f0", t0)
        buffer.clear()

        assertEquals(0, buffer.size)
        assertNull(buffer.poll(t0 + bufferNanos))

        // 清空后新来的帧仍然要重新攒满 0.5 s
        buffer.enqueue("f1", t0)
        assertEquals("f1", buffer.poll(t0 + bufferNanos))
    }

    @Test
    fun nextDueNanosTracksQueueState() {
        val buffer = TrackingFrameBuffer(bufferMs = 500)
        assertEquals(Long.MAX_VALUE, buffer.nextDueNanos())

        buffer.enqueue("f0", t0)
        assertEquals(t0 + bufferNanos, buffer.nextDueNanos())

        buffer.poll(t0 + bufferNanos)
        assertEquals(Long.MAX_VALUE, buffer.nextDueNanos())
    }
}
