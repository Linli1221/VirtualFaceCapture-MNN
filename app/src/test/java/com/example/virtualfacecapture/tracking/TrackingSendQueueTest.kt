package com.example.virtualfacecapture.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * per-client 有限 FIFO 发送队列（[TrackingSendQueue]）的本地 JVM 单元测试。
 *
 * 覆盖：
 *  1. track 与 control 混投时严格 FIFO 保序（calib / track 不再错配）
 *  2. track 溢出丢最旧的 track、绝不丢 control
 *  3. control 自己爆仓时丢最旧 control 兜底
 *  4. 容量上限（8 track + 32 control = 40）与空队列语义
 *  5. 并发 offer 不炸、不丢一致性（结束后队列内容合法）
 */
class TrackingSendQueueTest {

    // ------------------------------------------------------------- 顺序保持

    @Test
    fun keepsStrictFifoOrderAcrossTrackAndControl() {
        val queue = TrackingSendQueue()
        // 模拟真实顺序：track(track) → calib(control) → track(track)（采集顺序）
        queue.offer("t1", control = false)
        queue.offer("c1", control = true)
        queue.offer("t2", control = false)
        queue.offer("c2", control = true)

        // 旧实现里 control 走优先队列会先出 c1/c2，把 t1 甩在后面 —— 这里必须按投递顺序
        assertEquals("t1", queue.poll())
        assertEquals("c1", queue.poll())
        assertEquals("t2", queue.poll())
        assertEquals("c2", queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun pollOnEmptyQueueReturnsNull() {
        val queue = TrackingSendQueue()
        assertNull(queue.poll())
        queue.offer("t1", control = false)
        assertEquals("t1", queue.poll())
        assertNull(queue.poll())
    }

    // ------------------------------------------------------- 溢出：丢最旧 track

    @Test
    fun trackOverflowDropsOldestTrackAndKeepsNewest() {
        val queue = TrackingSendQueue()
        repeat(TrackingSendQueue.MAX_TRACK) { index ->
            queue.offer("t$index", control = false)
        }
        queue.offer("t-new", control = false)

        // 最旧的 t0 被丢掉，其余按序保留
        assertEquals(TrackingSendQueue.MAX_TRACK, queue.size)
        assertEquals(1L, queue.droppedTrackCount)
        assertEquals("t1", queue.poll())
        repeat(TrackingSendQueue.MAX_TRACK - 2) { index ->
            assertEquals("t" + (index + 2), queue.poll())
        }
        assertEquals("t-new", queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun trackOverflowNeverDropsControl() {
        val queue = TrackingSendQueue()
        // 先塞满 control，再狂塞 track 触发多次溢出
        queue.offer("hello", control = true)
        queue.offer("calib-on", control = true)
        repeat(TrackingSendQueue.MAX_TRACK * 3) { index ->
            queue.offer("t$index", control = false)
        }
        queue.offer("calib-off", control = true)

        // control 一条不少（hello / calib-* 全在），track 只剩最新的 MAX_TRACK 条
        assertEquals(TrackingSendQueue.MAX_TRACK + 3, queue.size)
        assertEquals(0L, queue.droppedControlCount)
        assertTrue(queue.droppedTrackCount > 0L)

        // 出队顺序仍然严格：control 插在它投递时的位置上
        val drained = ArrayList<String>()
        while (true) {
            val message = queue.poll() ?: break
            drained.add(message)
        }
        assertEquals("hello", drained.first())
        assertEquals("calib-on", drained[1])
        assertEquals("calib-off", drained.last())
        // 被保留下来的 track 是「最新的 MAX_TRACK 条」
        for (offset in 0 until TrackingSendQueue.MAX_TRACK) {
            assertEquals("t" + (TrackingSendQueue.MAX_TRACK * 3 - TrackingSendQueue.MAX_TRACK + offset), drained[2 + offset])
        }
    }

    // ------------------------------------------------- 溢出：control 爆仓兜底

    @Test
    fun controlOverflowDropsOldestControlOnlyAsFallback() {
        val queue = TrackingSendQueue()
        // control 自己超过上限（正常会话不可能，属于兜底路径）
        repeat(TrackingSendQueue.MAX_CONTROL + 5) { index ->
            queue.offer("c$index", control = true)
        }
        assertEquals(TrackingSendQueue.MAX_CONTROL, queue.size)
        assertEquals(5L, queue.droppedControlCount)
        assertEquals(0L, queue.droppedTrackCount)
        // 丢掉的是最旧的 5 条，剩下的从 c5 起按序排到最后一条
        assertEquals("c5", queue.poll())
        var last = ""
        while (true) {
            val message = queue.poll() ?: break
            last = message
        }
        assertEquals("c" + (TrackingSendQueue.MAX_CONTROL + 4), last)
    }

    // ------------------------------------------------------------- 容量上限

    @Test
    fun totalCapacityNeverExceedsTrackPlusControl() {
        val queue = TrackingSendQueue()
        // 极限混合：track 与 control 都顶到上限
        repeat(TrackingSendQueue.MAX_TRACK) { queue.offer("t$it", control = false) }
        repeat(TrackingSendQueue.MAX_CONTROL) { queue.offer("c$it", control = true) }
        assertEquals(TrackingSendQueue.MAX_TOTAL, queue.size)
        assertEquals(TrackingSendQueue.MAX_TRACK, queue.queuedTracks)
        assertEquals(TrackingSendQueue.MAX_CONTROL, queue.queuedControls)

        // 再怎么投都不会涨破总量上限
        repeat(100) { index ->
            queue.offer("t-more$index", control = false)
            queue.offer("c-more$index", control = true)
        }
        assertTrue("size=" + queue.size, queue.size <= TrackingSendQueue.MAX_TOTAL)
        assertEquals(TrackingSendQueue.MAX_TOTAL, queue.size)
    }

    @Test
    fun clearResetsEverything() {
        val queue = TrackingSendQueue()
        repeat(5) { queue.offer("t$it", control = false) }
        repeat(3) { queue.offer("c$it", control = true) }
        queue.clear()
        assertEquals(0, queue.size)
        assertEquals(0, queue.queuedTracks)
        assertEquals(0, queue.queuedControls)
        assertNull(queue.poll())
    }

    // ------------------------------------------------------------- 并发安全

    @Test
    fun concurrentOffersDoNotExplodeOrCorrupt() {
        val queue = TrackingSendQueue()
        val threads = 8
        val offersPerThread = 500
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { threadIndex ->
            Thread {
                start.await()
                repeat(offersPerThread) { index ->
                    // 一半线程投 track、一半投 control，交替压力两套计数
                    val control = (threadIndex % 2 == 1) || (index % 7 == 0)
                    queue.offer("m" + threadIndex + "-" + index, control = control)
                }
                done.countDown()
            }.apply { isDaemon = true }.start()
        }
        start.countDown()
        assertTrue("并发 offer 超时", done.await(10, TimeUnit.SECONDS))

        // 结束后队列必须自洽：总量有界、计数对得上、能全部按序排空
        val total = queue.size
        assertTrue("size=" + total, total <= TrackingSendQueue.MAX_TOTAL)
        assertEquals(total, queue.queuedTracks + queue.queuedControls)

        var drained = 0
        while (queue.poll() != null) drained++
        assertEquals(total, drained)
        assertEquals(0, queue.size)
    }
}
