package com.example.mediapipeupperbody

import java.nio.ByteBuffer

/**
 * 相机帧的「只保留最新」环形缓冲：池化 DirectByteBuffer 槽位 + 顶掉旧帧的邮箱语义。
 *
 * 背景（docs/upper-body.md §3.2-7）：YUV [ByteBuffer] 只在相机回调期间有效，异步 worker
 * 不能直接持有它；而 ROI 采样 + YUV→Bitmap（约 1~4ms）如果留在相机线程，会直接吃掉
 * Camera2 的 16.7ms 帧预算。所以把这一段拆成两半：
 *
 *     相机回调线程（[submit]）
 *         -> 只把 Y/U/V 按 stride 拷进池化 DirectByteBuffer
 *            （行内紧凑、丢掉行尾 padding，典型 0.15~0.4ms）
 *         -> 发布为「最新帧」，立即返回
 *
 *     upper-body-worker 线程（[takeLatest] / [release]）
 *         -> 取最新一帧 -> ROI / YUV 转 Bitmap -> detectAsync
 *
 * 队列语义：**只保留最新帧**，绝不无限排队（排队只会让延迟越滚越大）。任意时刻最多一帧
 * 在等消费（pending）：新的 [submit] 直接顶掉旧 pending，被顶掉的帧计入 [onDropped]。
 *
 * 槽位总数 [slotCount]（默认 3）= 「worker 正在转换」最多 1 个 + pending 1 个 + 空闲 ≥1 个，
 * 这样 worker 转换的那几毫秒里到达的帧不必顶掉上一个 pending。槽位里的 DirectByteBuffer
 * 只增不减、按帧尺寸增长后复用（分辨率不变时零分配），典型 720p 下 3 槽约 6MB 常驻。
 *
 * 线程约束：**单生产者**（相机线程）+ **单消费者**（upper-body-worker）。
 * [submit] 不会因为消费者而阻塞：所有临界区只做指针操作和 memcpy，等待只发生在消费者侧。
 *
 * @param onDropped 一帧在被消费前就被丢掉时回调（用于统计，勿做重活）。
 */
internal class YuvFrameRing(
    private val slotCount: Int = DEFAULT_SLOT_COUNT,
    private val onDropped: () -> Unit = {},
) {

    /** 一帧像素的池化槽位：三块按行紧凑的 DirectByteBuffer + 本帧元数据。 */
    class Slot internal constructor() {
        internal var y: ByteBuffer = EMPTY_BUFFER
        internal var u: ByteBuffer = EMPTY_BUFFER
        internal var v: ByteBuffer = EMPTY_BUFFER

        /** 交给 worker 的帧（指向本槽位的三块缓冲，紧凑 stride）。 */
        @Volatile
        var frame: Yuv420Frame? = null
            internal set

        /** 同一帧的脸框提示（旋转后、未镜像的归一化坐标）。 */
        @Volatile
        var faceHint: FaceRoiHint? = null
            internal set

        /** 相机回调时刻（帧时间戳用，比 worker 处理时刻更接近真实采集时刻）。 */
        @Volatile
        var captureTimestampMs: Long = 0L
            internal set

        internal var inUse: Boolean = false
    }

    private val lock = Any()
    private val free = java.util.ArrayDeque<Slot>()

    /** 最新一帧未消费帧；null = 没有帧在等。 */
    private var pending: Slot? = null

    @Volatile
    private var closed = false

    /** 队列是否已关闭（worker 线程的退出条件之一）。 */
    val isClosed: Boolean
        get() = closed

    init {
        require(slotCount >= 2) { "slotCount 至少 2（1 个 pending + 1 个转换中/空闲）" }
        repeat(slotCount) { free.addLast(Slot()) }
    }

    /**
     * 相机线程：把一帧 YUV 按 stride 拷进池化槽位并发布为最新帧。
     *
     * 只做「拷贝 + 发布」，不做 ROI / 缩放 / Bitmap —— 那些在 worker 线程做。
     *
     * @param frame 相机帧（ByteBuffer 只在本次调用内被读取，返回后调用方可立即让它失效）
     * @param faceHint 同一帧的脸框提示，原样带给 worker
     * @param captureTimestampMs 相机回调时刻，原样带给 worker
     * @return true = 已发布（顶掉的旧 pending 计入丢帧）；false = 非法帧 / 已关闭 / 无槽可用
     */
    fun submit(
        frame: Yuv420Frame,
        faceHint: FaceRoiHint? = null,
        captureTimestampMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!frame.isValid()) return false
        synchronized(lock) {
            if (closed) return false
            val slot = acquireLocked() ?: return false
            try {
                copyInto(slot, frame)
            } catch (t: Throwable) {
                slot.inUse = false
                free.addLast(slot)
                throw t
            }
            slot.faceHint = faceHint
            slot.captureTimestampMs = captureTimestampMs
            slot.frame = Yuv420Frame(
                y = slot.y,
                u = slot.u,
                v = slot.v,
                width = frame.width,
                height = frame.height,
                yRowStride = frame.width,
                uvRowStride = ((frame.width + 1) / 2) * frame.uvPixelStride,
                uvPixelStride = frame.uvPixelStride,
                rotationDegrees = frame.rotationDegrees,
                mirrorHorizontal = frame.mirrorHorizontal,
            )
            slot.inUse = true
            replacePendingLocked(slot)
            lock.notifyAll()
            return true
        }
    }

    /**
     * worker 线程：取最新的未消费帧（没有帧时最多等 [waitMs] 毫秒）。
     *
     * 返回的 [Slot] 必须用 [release] 归还。超时 / 已关闭 / 被中断时返回 null。
     */
    fun takeLatest(waitMs: Long): Slot? {
        val deadline = System.currentTimeMillis() + waitMs
        synchronized(lock) {
            while (!closed) {
                val slot = pending
                if (slot != null) {
                    pending = null
                    return slot
                }
                val remainMs = deadline - System.currentTimeMillis()
                if (remainMs <= 0L) return null
                try {
                    lock.wait(remainMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return null
        }
    }

    /** worker 线程：归还槽位（其像素缓冲继续复用，元数据清空）。 */
    fun release(slot: Slot) {
        synchronized(lock) {
            if (!slot.inUse) return
            slot.inUse = false
            slot.frame = null
            slot.faceHint = null
            slot.captureTimestampMs = 0L
            free.addLast(slot)
        }
    }

    /** 丢掉未消费的 pending（不算丢帧）；用于 stop() 之后不再处理旧帧。 */
    fun drain() {
        synchronized(lock) {
            drainLockedNoCount()
        }
    }

    /**
     * 关闭：[drain] + 之后 [submit] 一律拒绝 + 唤醒等待中的 worker（用于 join 前收尾）。
     * 关闭时被丢掉的 pending 不算丢帧（是关停清理，不是性能丢帧）。
     */
    fun close() {
        synchronized(lock) {
            closed = true
            drainLockedNoCount()
            lock.notifyAll()
        }
    }

    /** 复用前重开（检测器 release() 之后再次 initialize/start 的场景）。 */
    fun reset() {
        synchronized(lock) {
            closed = false
            drainLockedNoCount()
            lock.notifyAll()
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 取一个可写槽位：优先空闲槽，全部占用时顶掉 pending（它本来也活不过下一帧）。 */
    private fun acquireLocked(): Slot? {
        val freeSlot = free.pollFirst()
        if (freeSlot != null) return freeSlot
        val stale = pending ?: return null
        pending = null
        stale.inUse = false
        stale.frame = null
        stale.faceHint = null
        free.addLast(stale)
        markDroppedLocked()
        return free.pollFirst()
    }

    /** 发布新 pending；旧 pending（如果还有一帧没来得及消费）按「只保留最新」丢掉。 */
    private fun replacePendingLocked(slot: Slot) {
        val stale = pending
        pending = slot
        if (stale != null) {
            stale.inUse = false
            stale.frame = null
            stale.faceHint = null
            free.addLast(stale)
            markDroppedLocked()
        }
    }

    private fun drainLockedNoCount() {
        val stale = pending
        pending = null
        if (stale != null) {
            stale.inUse = false
            stale.frame = null
            stale.faceHint = null
            free.addLast(stale)
        }
    }

    private fun markDroppedLocked() {
        try {
            onDropped()
        } catch (t: Throwable) {
            // 统计回调的异常不能影响相机线程
        }
    }

    /**
     * 按 stride 把三个平面拷进槽位的 DirectByteBuffer，行内紧凑（丢掉行尾 padding），
     * 于是拷出来的帧 yRowStride = width、uvRowStride = uvWidth×uvPixelStride。
     *
     * 典型 1080p（Y stride 1920 = width）就是约 4MB 的 memcpy，720p 约 2MB，实测 0.1~0.5ms。
     */
    private fun copyInto(slot: Slot, frame: Yuv420Frame) {
        val width = frame.width
        val height = frame.height
        val uvPixelStride = frame.uvPixelStride
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val yBytes = width * height
        val uvBytes = uvWidth * uvHeight * uvPixelStride

        slot.y = ensureCapacity(slot.y, yBytes)
        slot.u = ensureCapacity(slot.u, uvBytes)
        slot.v = ensureCapacity(slot.v, uvBytes)

        copyPlane(frame.y, frame.yRowStride, height, width, yBytes, Y_FALLBACK, slot.y)
        val uvRowBytes = uvWidth * uvPixelStride
        copyPlane(frame.u, frame.uvRowStride, uvHeight, uvRowBytes, uvBytes, UV_FALLBACK, slot.u)
        copyPlane(frame.v, frame.uvRowStride, uvHeight, uvRowBytes, uvBytes, UV_FALLBACK, slot.v)
    }

    private fun ensureCapacity(buffer: ByteBuffer, bytes: Int): ByteBuffer {
        if (buffer.capacity() >= bytes) return buffer
        return ByteBuffer.allocateDirect(bytes)
    }

    /**
     * 拷一个平面：源按 [srcRowStride] 跳行，目标按 [rowBytes] 紧凑排列。
     *
     * 源缓冲被截断（异常帧）时，缺口按 [padValue] 补齐 —— 与
     * [YuvBitmapConverter] 对越界索引的回退值一致（Y=16 / UV=128），保证拷贝前后的像素逐位一致。
     */
    private fun copyPlane(
        src: ByteBuffer,
        srcRowStride: Int,
        rows: Int,
        rowBytes: Int,
        totalBytes: Int,
        padValue: Int,
        dst: ByteBuffer,
    ) {
        val srcLimit = src.limit()
        val view = src.duplicate()
        dst.clear()
        for (row in 0 until rows) {
            val start = row * srcRowStride
            val available = if (start >= 0 && start < srcLimit) srcLimit - start else 0
            val count = if (available >= rowBytes) rowBytes else available
            if (count > 0) {
                view.clear()
                view.limit(srcLimit)
                view.position(start)
                view.limit(start + count)
                dst.put(view)
            }
            var pad = rowBytes - count
            while (pad > 0) {
                dst.put(padValue.toByte())
                pad--
            }
        }
        // 写满 totalBytes，回到「可绝对读」的状态（limit = 完整平面，position 无所谓）
        dst.position(0)
        dst.limit(totalBytes)
    }

    companion object {
        /**
         * 默认槽数：1 个「worker 正在转换」+ 1 个 pending + 1 个空闲，
         * 保证转换的几毫秒里到达的帧不必顶掉上一个 pending。
         */
        const val DEFAULT_SLOT_COUNT = 3

        /** 源缓冲截断时的像素回退值（与 YuvBitmapConverter 的越界回退一致）。 */
        private const val Y_FALLBACK = 16
        private const val UV_FALLBACK = 128

        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0)
    }
}
