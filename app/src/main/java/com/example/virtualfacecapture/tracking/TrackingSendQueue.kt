package com.example.virtualfacecapture.tracking

/**
 * per-client **有限 FIFO 发送队列**（协议 §7.1）。
 *
 * 背景：旧实现里 track 只有一个「最新一帧」覆盖槽位（[java.util.concurrent.atomic.AtomicReference]）、
 * control 走另一条队列 —— 两条通道没有全局顺序，会出现两类问题：
 *
 *  1. **丢中间帧**：发送节奏 = 生产节奏（突发）时 writer 稍慢一点，中间帧就被覆盖丢掉；
 *  2. **calib / track 错配**：calib 走 control 通道优先发送，而采集更早的 track 还压在槽位里，
 *     于是 PC 收到的 calib（校准窗口状态）和 track（帧数据）顺序反转，语义错位。
 *
 * 本类把 track 与 control（hello / calib / pong / ack / bye）放进**同一个 FIFO**，严格按
 * 投递顺序出队，从根上消灭顺序反转；同时保持「慢客户端绝不拖慢推理线程」这条铁律：
 *
 *  - [offer] **非阻塞**、均摊 O(1)、**不碰任何网络 IO** —— 推理线程调用它只做几条指针操作；
 *  - 容量有硬上限：最多 [MAX_TRACK] 条 track + [MAX_CONTROL] 条 control（总量 [MAX_TOTAL]），
 *    内存占用恒定有界，writer 再慢也不会把队列滚雪球；
 *  - 溢出策略**保 control 弃 track**：track 满了丢**最旧的 track**（保最新帧，PC 也会按
 *    `seq` 丢旧帧）；绝不丢 control —— hello / calib / ack / bye 丢了协议就错乱了。
 *    只有 control 自己爆掉（超过 32 条，几乎不可能）才丢最旧的 control 兜底。
 *
 * 线程安全：所有公开方法都在同一把内部锁下完成（工作量都是常数上界，锁内没有任何 IO /
 * 睡眠 / 回调），因此并发 [offer] 与 [poll] 不会互相拖住。
 *
 * 刻意不依赖任何 Android API，可在本地 JVM 单元测试（TrackingSendQueueTest）里直接验证。
 */
class TrackingSendQueue(
    private val maxTrack: Int = MAX_TRACK,
    private val maxControl: Int = MAX_CONTROL,
) {

    /** 队列条目：[control] 决定溢出时是否允许被丢弃（false = track，可丢；true = control，尽量不丢）。 */
    private class Entry(val message: String, val control: Boolean)

    /** 保护 [queue] 与两个计数的内部锁；锁内只有常数上界的工作，绝不做 IO。 */
    private val lock = Any()

    /** 单一 FIFO，按投递顺序出队，track 与 control 严格交错保序。 */
    private val queue = ArrayDeque<Entry>()

    private var trackCount = 0
    private var controlCount = 0

    private var droppedTracks = 0L
    private var droppedControls = 0L

    /** 当前排队的总条数（track + control）。 */
    val size: Int
        get() = synchronized(lock) { queue.size }

    /** 队列是否为空（writer 关闭前的冲刷判断用）。 */
    val isEmpty: Boolean
        get() = synchronized(lock) { queue.isEmpty() }

    /** 当前排队的 track 条数（调试 / 测试用）。 */
    val queuedTracks: Int
        get() = synchronized(lock) { trackCount }

    /** 当前排队的 control 条数（调试 / 测试用）。 */
    val queuedControls: Int
        get() = synchronized(lock) { controlCount }

    /** 因溢出被丢掉的 track 总数（日志 / 调试用）。 */
    val droppedTrackCount: Long
        get() = synchronized(lock) { droppedTracks }

    /** 因溢出被丢掉的 control 总数（应恒为 0，除非对端疯狂发 command）。 */
    val droppedControlCount: Long
        get() = synchronized(lock) { droppedControls }

    /**
     * 非阻塞投递一条已序列化的报文。
     *
     * @param control true = hello / calib / pong / ack / bye 这类控制消息；false = track 数据帧。
     */
    fun offer(message: String, control: Boolean) {
        synchronized(lock) {
            if (control) {
                // control 爆仓（>32 条）才丢最旧的 control 兜底；正常情况下永远走不到这里
                while (controlCount >= maxControl) {
                    if (!dropOldestControl()) break
                    droppedControls++
                }
                queue.addLast(Entry(message, true))
                controlCount++
            } else {
                // track 爆仓：丢最旧的 track（保最新帧），绝不挤掉 control
                while (trackCount >= maxTrack) {
                    if (!dropOldestTrack()) break
                    droppedTracks++
                }
                queue.addLast(Entry(message, false))
                trackCount++
            }
        }
    }

    /** 弹出队头（最旧的一条）；空队列返回 null。调用方（writer 线程）负责真正发送。 */
    fun poll(): String? {
        synchronized(lock) {
            val entry = queue.removeFirstOrNull() ?: return null
            if (entry.control) controlCount-- else trackCount--
            return entry.message
        }
    }

    /** 清空（连接关闭 / 服务停止时调用），计数与丢弃统计一并复位。 */
    fun clear() {
        synchronized(lock) {
            queue.clear()
            trackCount = 0
            controlCount = 0
            droppedTracks = 0L
            droppedControls = 0L
        }
    }

    /**
     * 丢掉最旧的一条 track。
     *
     * 队列最多 [MAX_TOTAL] 条，从头扫描最多走常数步（≈40），属于「O(1) 附近」；
     * 找不到 track 时返回 false、不动队列（防御性兜底，调用方据此退出丢弃循环）。
     */
    private fun dropOldestTrack(): Boolean {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().control) {
                iterator.remove()
                trackCount--
                return true
            }
        }
        return false
    }

    /** 丢掉最旧的一条 control（只在 control 自己爆仓时调用），同样是常数上界扫描。 */
    private fun dropOldestControl(): Boolean {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().control) {
                iterator.remove()
                controlCount--
                return true
            }
        }
        return false
    }

    companion object {
        /**
         * track 上限（条）。60 fps 下约 133 ms 的突发余量：writer 偶尔卡一下不会丢帧，
         * 卡得久了就丢最旧的 track —— 反正 PC 按 `seq` 丢旧帧、且低延迟模式下 PC 侧
         * 还有自适应抖动缓冲兜着，「保最新」比「一帧不漏」更重要。
         */
        const val MAX_TRACK = 8

        /**
         * control（hello / calib / pong / ack / bye）上限（条）。一次会话正常只会产
         * 个位数条 control，32 条是「对端疯狂 ping / command」时的兜底。
         */
        const val MAX_CONTROL = 32

        /** 总量上限 = [MAX_TRACK] + [MAX_CONTROL]（单个客户端的内存占用恒定有界）。 */
        const val MAX_TOTAL = MAX_TRACK + MAX_CONTROL
    }
}
