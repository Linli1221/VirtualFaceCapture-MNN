package com.example.virtualfacecapture.tracking

/**
 * 手机端的**发送帧缓冲**（playout / jitter buffer）。
 *
 * 面捕管线是「算完一帧就发一帧」：MNN 推理耗时抖动、系统调度、Wi-Fi 突发都会让 PC
 * 收到的帧间隔忽长忽短。PC 端每收到一帧就把姿态写到 Live2D 参数上，帧间隔一抖，
 * 形象看起来就是「一卡一卡」的。这里把「产出」和「发送」解耦：
 *
 *  1. 每条待发报文带上入队时刻进队；
 *  2. **起播预缓冲**：队头攒够 [bufferMs]（默认 0.5 s）才开始放行 —— 这就是多出来的固定延迟；
 *  3. **起播后按播放时钟匀速放行**：间隔取「生产节奏」（相邻帧入队间隔的指数滑动平均），
 *     与某一帧具体何时到达无关。推理偶尔卡一下只是把队列垫的那层缓冲吃掉，
 *     PC 侧收到的帧间隔仍然均匀；
 *  4. **深度调节**：卡顿吃掉缓冲深度后，放行节奏会稍微放慢一点，把 0.5 s 的缓冲垫回来
 *     （否则跑久了延迟会悄悄变短，缓冲名存实亡）；
 *  5. 队列被抽空（真断流）后再来帧时，重新走一次预缓冲；
 *  6. 队列超过 [maxQueued] 条时丢最旧的，延迟被钉死在有限范围内，不会滚雪球。
 *
 * 线程模型：**本类不加锁**。调用方（[TrackingServer]）必须用同一把锁包住
 * [enqueue] / [poll] / [clear]，并负责在 [enqueue] 后唤醒自己的放行线程。
 *
 * 刻意不依赖任何 Android API、不自己起线程、时钟由调用方传入纳秒时间戳，
 * 因此可以在本地 JVM 单元测试（TrackingFrameBufferTest）里用假时钟精确验证。
 */
class TrackingFrameBuffer(
    private val bufferMs: Long,
    private val maxQueued: Int = MAX_QUEUED_MESSAGES,
) {

    /**
     * 缓冲条目。[control] 是**显式消息类型**（true = calib 等控制消息）：放行侧按它分流，
     * 不再解析 payload 内容判类型（rawTrackV1 的二进制条目根本不进本缓冲，见 §2.5）。
     */
    class Entry(val message: String, val control: Boolean, val enqueueNanos: Long)

    /** 待发报文（track / calib），按入队顺序放行。 */
    private val queue = ArrayDeque<Entry>()

    /** 上一帧的入队时刻（仅在 [hasEnqueueHistory] 为 true 时有效）。 */
    private var lastEnqueueNanos = 0L

    /** 是否已经有可用于估计生产节奏的历史样本（[clear] 后复位）。 */
    private var hasEnqueueHistory = false

    /** 生产节奏（相邻两帧入队间隔）的指数滑动平均，单位纳秒。 */
    private var intervalNanos = DEFAULT_INTERVAL_NANOS

    /** 是否已经起播（false = 还在预缓冲，true = 正在按播放时钟匀速放行）。 */
    private var primed = false

    /** 下一条报文最早可放行的时刻（只在 [primed] 为 true 时有效）。 */
    private var nextReleaseNanos = 0L

    /** 放行节奏的修正系数（千分比），1000 = 完全按生产节奏。 */
    private var pacePermille = FULL_PACE_PERMILLE

    /** 缓冲深度调节器是否已启用（缓冲曾经垫满过一次才算「有垫」可言）。 */
    private var depthRegulatorActive = false

    /** 因为队列溢出被丢掉的报文数（调试 / 日志用）。 */
    var droppedCount = 0L
        private set

    /** 当前缓冲区里排队的报文数。 */
    val size: Int
        get() = queue.size

    /** 是否已经起播（调试 / 测试用）。 */
    val isPrimed: Boolean
        get() = primed

    private val bufferNanos: Long
        get() = bufferMs.coerceAtLeast(0L) * 1_000_000L

    /** 队列抽空后，间隔超过这么久才来下一帧，就认为期间真断流、需要重新预缓冲。 */
    private val rebufferGapNanos: Long
        get() = bufferMs.coerceAtLeast(0L) / 2 * 1_000_000L

    /** [bufferMs] 对应的「应该垫多少帧」。 */
    private val targetFrames: Int
        get() = if (intervalNanos <= 0L) 0 else (bufferNanos / intervalNanos).toInt()

    /** 本次放行与下次放行之间的间隔（生产节奏 × 深度修正）。 */
    private val releaseIntervalNanos: Long
        get() = intervalNanos * pacePermille / FULL_PACE_PERMILLE

    /**
     * 入队一条待发报文（已序列化好的 JSON 字符串）。
     *
     * @param nowNanos 入队时刻，必须是单调时钟（`System.nanoTime()`），不能是墙钟。
     */
    fun enqueue(message: String, nowNanos: Long, control: Boolean = false) {
        // 队列本来已经空了、而且距离上一帧很久：说明中间真断流了，
        // 重新进入预缓冲，否则缓冲每垫一帧就放一帧，延迟回不来。
        if (queue.isEmpty() && primed && hasEnqueueHistory &&
            nowNanos - lastEnqueueNanos > rebufferGapNanos
        ) {
            primed = false
            nextReleaseNanos = 0L
        }

        // 溢出保护：丢最旧的，保证「延迟有上限」比「一帧不漏」更重要
        while (maxQueued > 0 && queue.size >= maxQueued) {
            queue.removeFirst()
            droppedCount++
        }
        queue.addLast(Entry(message, control, nowNanos))

        if (hasEnqueueHistory) {
            val delta = nowNanos - lastEnqueueNanos
            // 只看合理的帧间隔：同一批突发到达的亚毫秒间隔和断流后的长间隔都不作数
            if (delta in MIN_INTERVAL_NANOS..MAX_INTERVAL_NANOS) {
                // 单次卡顿 / 突发不改变节奏估计，把样本夹在「当前节奏的 0.5x ~ 2x」之间
                val sample = delta.coerceIn(intervalNanos / 2, intervalNanos * 2)
                intervalNanos += (sample - intervalNanos) / EMA_WEIGHT
            }
        }
        lastEnqueueNanos = nowNanos
        hasEnqueueHistory = true
    }

    /**
     * 到期则弹出一条条目并返回，否则返回 null。条目自带显式 control 标记（[Entry.control]）。
     *
     * 预缓冲阶段：队头攒够 [bufferMs] 才起播；
     * 起播之后：只看播放时钟（[nextReleaseNanos]），与每帧的到达时刻无关。
     *
     * @param nowNanos 当前时刻（`System.nanoTime()`）。
     */
    fun poll(nowNanos: Long): Entry? {
        val head = queue.firstOrNull() ?: return null

        if (!primed) {
            // 起播条件：队头已经攒够 bufferMs，说明前面有足够的帧垫着
            if (nowNanos - head.enqueueNanos < bufferNanos) return null
            primed = true
            nextReleaseNanos = nowNanos
        }

        // 缓冲曾经垫满过，才开启深度调节：否则「刚开始攒帧」的短队列会被误判成「深度不足」
        if (!depthRegulatorActive && targetFrames > 0 && queue.size >= targetFrames) {
            depthRegulatorActive = true
        }

        if (nowNanos < nextReleaseNanos) return null

        queue.removeFirst()
        adjustPace()
        // 正常情况下按生产节奏推进；卡顿 / 饿死之后以「现在」为起点重新计时，最多补发当前一帧
        nextReleaseNanos = maxOf(nextReleaseNanos + releaseIntervalNanos, nowNanos)
        return head
    }

    /**
     * 按当前缓冲深度微调放行节奏：深度不足就放慢一点把垫子补回来，深度过多就加快一点。
     *
     * 用比例控制而不是积分：节奏系数直接由「深度偏差」算出来，再平滑靠近，
     * 因此补完垫子会自己回到 1000‰，不会来回过冲（积分会）。死区 ±10% 以内不调整。
     */
    private fun adjustPace() {
        val target = targetFrames
        if (!depthRegulatorActive || target <= 0) return
        val deadband = maxOf(PACING_DEADBAND_MIN_FRAMES, target / 10)
        val error = target - queue.size
        val desiredPermille = when {
            error > deadband ->
                minOf(PACING_MAX_PERMILLE, FULL_PACE_PERMILLE + error * PACING_GAIN_PERMILLE)
            error < -deadband ->
                maxOf(PACING_MIN_PERMILLE, FULL_PACE_PERMILLE + error * PACING_GAIN_PERMILLE)
            else -> FULL_PACE_PERMILLE
        }
        pacePermille += (desiredPermille - pacePermille) / PACING_SMOOTHING
    }

    /** 下一条报文最早可放行的时刻；缓冲区为空时返回 [Long.MAX_VALUE]。 */
    fun nextDueNanos(): Long {
        val head = queue.firstOrNull() ?: return Long.MAX_VALUE
        return if (primed) nextReleaseNanos else head.enqueueNanos + bufferNanos
    }

    /** 清空缓冲（客户端全部断开 / 服务停止时调用，避免把陈旧数据发给下一个客户端）。 */
    fun clear() {
        queue.clear()
        lastEnqueueNanos = 0L
        hasEnqueueHistory = false
        primed = false
        nextReleaseNanos = 0L
        intervalNanos = DEFAULT_INTERVAL_NANOS
        pacePermille = FULL_PACE_PERMILLE
        depthRegulatorActive = false
    }

    companion object {
        /** 最小有效帧间隔（125 fps）；比这更密的入队不参与节奏估计。 */
        private const val MIN_INTERVAL_NANOS = 8_000_000L

        /** 最大有效帧间隔（4 fps）；比这更疏的入队视为断流，不参与节奏估计。 */
        private const val MAX_INTERVAL_NANOS = 250_000_000L

        /** 指数滑动平均的权重：新样本占 1/8，约十几帧收敛，不会因单帧抖动改变节奏。 */
        private const val EMA_WEIGHT = 8L

        /** 节奏初值：60 fps。 */
        private const val DEFAULT_INTERVAL_NANOS = 16_666_667L

        /** 不做任何深度修正时的节奏系数（千分比）。 */
        private const val FULL_PACE_PERMILLE = 1000

        /** 深度调节：每差 1 帧，节奏最多修正 2.5%（千分比）。 */
        private const val PACING_GAIN_PERMILLE = 25

        /** 深度调节的平滑系数：节奏系数每次只向目标值靠近 1/4，避免帧间隔突变。 */
        private const val PACING_SMOOTHING = 4

        /** 深度调节允许把节奏放慢到 115%（补垫子）或加快到 90%（消垫子）。 */
        private const val PACING_MIN_PERMILLE = 900
        private const val PACING_MAX_PERMILLE = 1150

        /** 深度死区下限：目标深度的 ±10% 与 ±[PACING_DEADBAND_MIN_FRAMES] 帧取大者。 */
        private const val PACING_DEADBAND_MIN_FRAMES = 2

        /**
         * 队列上限（条）。60 fps 下约 2 秒，30 fps 下约 4 秒；
         * 正常情况下 0.5 s 缓冲只会占 30 条左右，这个上限只在异常时兜底。
         */
        private const val MAX_QUEUED_MESSAGES = 120
    }
}
