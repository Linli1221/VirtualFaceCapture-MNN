package com.example.virtualfacecapture.tracking

import android.util.Base64
import com.vfc.log.VfcLog as Log
import com.example.commondata.ARKitBlendShapes
import com.example.commondata.HeadPoseData
import com.example.commondata.UpperBodyPoseFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 面捕追踪数据服务端（HTTP + WebSocket），协议见 docs/tracking-protocol.md。
 *
 * 路由：
 *
 *    GET /            -> 信息页（确认服务在线 / 查看连接数，轮询 /status）
 *    GET /status      -> JSON 状态（协议 §6）
 *    WS  /ws          -> 追踪数据长连接（协议 §4）
 *    GET /favicon.ico -> 204
 *
 * 数据流：面捕管线的每帧结果由 [submitTrack] 推进来 → 手写 JSON →（生效缓冲 > 0 时）进
 * **发送帧缓冲**（[TrackingFrameBuffer]，攒够再按生产节奏匀速放行；生效缓冲 == 0 时
 * **旁路**缓冲、算完即发）→ 每个客户端一个**有限 FIFO 发送队列**（[TrackingSendQueue]，
 * track 与 control 同队严格保序）。网络慢的客户端只会丢最旧的 track，绝不会拖慢推理线程。
 *
 * 发送缓冲是**双模式**的（协议 §7.1）：`send.bufferMs` 配置 > 0 时手机自己保留固定延迟
 * （保守模式，起播预缓冲 + 匀速放行）；配置 == 0 时只有**当前所有已连接客户端**都在
 * hello.features 里声明了 `jitterBufferV1` 才真正旁路（延迟责任移交 PC 侧自适应抖动缓冲，
 * 见 docs/jitter-buffer.md），否则回落 [TrackingProtocol.FRAME_BUFFER_MS]。规则的纯函数实现在
 * [TrackingProtocol.resolveEffectiveBufferMs]，当前生效值见 [effectiveBufferMs]。
 */
class TrackingServer(
    private val port: Int = TrackingProtocol.PORT,
    private val deviceName: String,
    private val appName: String,
    private val onRecalibrate: () -> Unit = {},
    /**
     * 「全局 upperBodyPostV1 协商」结果变化时的通知回调（任务 M2，照 [onRecalibrate] 的
     * 接线模式）：true = 全体客户端都声明了 upperBodyPostV1，上层把检测器切到原始输出
     * （One-Euro / 躯干角 / 基线迁 PC）；false = 回到 M1 的手机端后处理链。
     */
    private val onUpperBodyPostV1: (Boolean) -> Unit = {},
    /**
     * 发送缓冲配置 `send.bufferMs`（毫秒）：0 = 算完即发（能否真正旁路还要看客户端协商），
     * 100~2000 = 保守模式的固定缓冲时长；非法值回落 [TrackingProtocol.FRAME_BUFFER_MS]。
     * 运行时可用 [setSendBufferMs] 修改（立即生效）。
     */
    private val frameBufferMs: Long = TrackingProtocol.FRAME_BUFFER_MS,
) {

    companion object {
        private const val TAG = "TrackingServer"

        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_HEADER_BYTES = 16 * 1024

        /** 普通 HTTP 连接上限（信息页 / status 轮询）。 */
        private const val MAX_CONNECTIONS = 16

        /** 协议 §7.2：允许多个客户端同时连（1 ~ 4），超过上限拒绝。 */
        private const val MAX_WS_CLIENTS = 4

        /** 握手后读超时：客户端建议每 5s ping 一次，长时间收不到就认为连接已死并回收。 */
        private const val WS_IDLE_TIMEOUT_MS = 120_000
        private const val ACCEPT_TIMEOUT_MS = 800
        private const val HEADER_READ_TIMEOUT_MS = 15_000

        /** 服务端协议层 ping 间隔（NAT / Wi-Fi 保活，不依赖客户端脚本）。 */
        private const val KEEPALIVE_INTERVAL_MS = 30_000L

        /** presenceScore 高于该阈值才认为本帧真的检测到人脸（与 SDK 判定一致）。 */
        private const val FACE_PRESENCE_THRESHOLD = 0.5f

        /** 上半身快照超过该时长未更新就按 ubOk=false 发（协议 §4.2）。 */
        private const val UPPER_BODY_STALE_MS = 1500L

        /** 缓冲放行线程的最长休眠时间：没有待发帧时也定期醒来检查退出/断线。 */
        private const val PLAYOUT_IDLE_WAIT_MS = 50L

        private const val PLAYOUT_IDLE_WAIT_NANOS = PLAYOUT_IDLE_WAIT_MS * 1_000_000L
    }

    // ------------------------------------------------------------- 对外状态

    /** 面捕管线是否在跑（相机 + 推理），同步给 /status 的 tracking 字段。 */
    @Volatile
    var trackingActive: Boolean = false

    /** 每处理 N 帧发 1 帧（协议 §4.1）；当前实现固定为 1（全量）。 */
    @Volatile
    var decimation: Int = TrackingProtocol.DEFAULT_DECIMATION

    /** hello 里上报的建议输出帧率。 */
    @Volatile
    var targetFps: Int = 60

    /**
     * 上半身采集是否启用（决定 hello.upperBody 与 track 是否带 ub* 字段，协议 §4.1 / §4.2）。
     *
     * 关闭时 track 里一个上半身字段都不出现；打开后即使暂无结果也发 `ubOk:false`。
     *
     * 属性名不能叫 `upperBodyEnabled`：它的 JVM setter 与 [setUpperBodyEnabled] 签名相同，
     * Kotlin 会报 Platform declaration clash（2026-10-23 的 CI 就挂在这里）。
     */
    @Volatile
    private var upperBodyOn: Boolean = false

    /** 只读状态（日志 / 自检用；外部切换一律走 [setUpperBodyEnabled]）。 */
    val isUpperBodyEnabled: Boolean get() = upperBodyOn

    /** 最近一秒实际发出的 track 帧率。 */
    @Volatile
    var sentFps: Float = 0f
        private set

    /** start() 失败原因（端口被占用等）。 */
    @Volatile
    var lastError: String? = null
        private set

    val clientCount: Int
        get() = wsClients.size

    /** 最新一帧的 seq（没有发过帧时为 0）。sequence 里存的是“下一帧”的序号。 */
    val latestSeq: Long
        get() {
            val next = sequence.get()
            return if (next <= 0L) 0L else next - 1L
        }

    val isRunning: Boolean
        get() = running

    // ------------------------------------------------------------- 内部实现

    @Volatile
    private var running = false

    private val wsClients = CopyOnWriteArrayList<TrackingWsConnection>()
    private val activeConnections = AtomicInteger()

    /** 帧序号：从 0 起单调递增（协议 §4.2）。即使当前没有客户端也照常递增。 */
    private val sequence = AtomicLong(0L)

    /** 最近一个统计窗口内真正广播出去的帧数。 */
    private val framesInWindow = AtomicInteger(0)

    /** `send.bufferMs` 配置值（已过 [TrackingProtocol.normalizeSendBufferMs] 归一化）。 */
    @Volatile
    private var configuredBufferMs: Long = TrackingProtocol.normalizeSendBufferMs(frameBufferMs)

    /**
     * 当前**生效**发送缓冲（毫秒）：0 = 旁路 [frameBuffer]（算完即发），> 0 = 缓冲时长。
     *
     * 规则见 [TrackingProtocol.resolveEffectiveBufferMs]；在客户端连入 / 断开 / hello 协商 /
     * 配置变化时由 [recomputeEffectiveBuffer] 重算。
     */
    @Volatile
    private var activeBufferMs: Long =
        TrackingProtocol.resolveEffectiveBufferMs(configuredBufferMs, 0, 0)

    /** 只读：当前生效发送缓冲（0 = 旁路模式）。UI / 日志用。 */
    val effectiveBufferMs: Long
        get() = activeBufferMs

    /**
     * 发送帧缓冲：生效缓冲 > 0 时先攒 [activeBufferMs] 再匀速放行（协议 §7.1），
     * 生效缓冲 == 0 时 [dispatch] 根本不入队（旁路），这个实例只是闲置。
     *
     * 内部不是线程安全的，[frameBuffer] 的每一次访问都要在 [frameBufferLock] 里；
     * 入队 / 停止后 signal [frameBufferCondition]，让 [playoutThread] 立刻重算到期时间。
     *
     * 实例的 bufferMs 取「配置值；配置为 0 时取回落值 [TrackingProtocol.FRAME_BUFFER_MS]」——
     * 因此「配置 0 + 客户端不支持」的回落路径用的就是这个 500 ms 实例。[setSendBufferMs]
     * 改配置时在锁内整体换新实例（旧实例连同没放行完的帧直接丢弃）。
     */
    @Volatile
    private var frameBuffer = TrackingFrameBuffer(
        if (configuredBufferMs > 0L) configuredBufferMs else TrackingProtocol.FRAME_BUFFER_MS,
    )
    private val frameBufferLock = ReentrantLock()
    private val frameBufferCondition = frameBufferLock.newCondition()

    @Volatile
    private var playoutThread: Thread? = null

    /** 放行线程的代次：stop()/start() 时 +1，旧线程看到代次变了就立刻退出。 */
    private val playoutGeneration = AtomicInteger(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var statsThread: Thread? = null

    @Volatile
    private var pageBytes: ByteArray? = null

    /** 上一次广播出去的校准状态，用来只在边界处发 calib（协议 §4.3）。 */
    @Volatile
    private var lastCalibActive: Boolean = false

    /** 最新一帧上半身结果（[submitUpperBodySnapshot] 覆盖式写入，[submitTrack] 读取）。 */
    @Volatile
    private var upperBodySnapshot: UpperBodyPoseFrame? = null

    /** 已启用上半身但还没收到任何帧时的占位帧（tracking=false、ubT=0、坐标全 0）。 */
    private val emptyUpperBodyFrame = UpperBodyPoseFrame.empty(0L)

    /** 同上的 raw 模式占位帧（rawMode=true）：协商生效期间逐帧语义一致，ubRaw 不来回跳。 */
    private val emptyRawUpperBodyFrame = UpperBodyPoseFrame.empty(0L).copy(rawMode = true)

    /**
     * 全局 upperBodyPostV1 协商结果（任务 M2）：所有已连接客户端都协商了才为 true。
     *
     * track 报文是全客户端广播同一条（[dispatch] / 帧缓冲），无法逐客户端出两种语义，
     * 所以 raw 模式按「全体一致」聚合；混合客户端时全体回退旧链，每个客户端收到的语义
     * 与其协商一致（`ubRaw` 不出现即旧语义）。由 [recomputeUpperBodyPostV1] 在客户端
     * 连入 / 断开 / hello 协商到达时重算。
     */
    @Volatile
    private var upperBodyPostV1Active: Boolean = false

    /** @return 是否成功监听；失败原因见 [lastError]。 */
    fun start(): Boolean {
        if (running) return true
        lastError = null

        val socket = try {
            ServerSocket()
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "创建 ServerSocket 失败", t)
            return false
        }

        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
            socket.soTimeout = ACCEPT_TIMEOUT_MS
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "无法监听端口 " + port, t)
            closeQuietly(socket)
            return false
        }

        serverSocket = socket
        running = true
        sequence.set(0L)
        framesInWindow.set(0)
        sentFps = 0f
        lastCalibActive = false
        frameBufferLock.withLock { frameBuffer.clear() }

        acceptThread = Thread({ acceptLoop(socket) }, "tracking-accept").also {
            it.isDaemon = true
            it.start()
        }
        statsThread = Thread({ statsLoop() }, "tracking-stats").also {
            it.isDaemon = true
            it.start()
        }
        // 放行线程始终启动：生效缓冲会在客户端协商时于 0 与非 0 之间来回切换，
        // 「按需启停线程」反而要在切换点处理竞态；空转代价可忽略（队列为空时最多每
        // 50 ms 醒来一次就继续睡），换来切换只需改一个标志位、stop() 照常干净退出。
        val playoutId = playoutGeneration.incrementAndGet()
        playoutThread = Thread({ playoutLoop(playoutId) }, "tracking-playout").also {
            it.isDaemon = true
            it.start()
        }

        Log.i(TAG, "追踪服务已启动: http://0.0.0.0:" + port + TrackingProtocol.PATH_INFO)
        return true
    }

    /** 停止服务：先给客户端发 bye（协议 §4.4），再关闭所有连接与监听。 */
    fun stop() {
        if (!running) return
        running = false

        val bye = TrackingProtocol.buildBye("service stopped")
        for (client in wsClients) {
            client.offerControl(bye)
            client.requestClose()
        }
        // 给 writer 线程一点时间把 bye 冲刷出去（局域网单帧 < 1ms，150ms 足够）。
        // 注意：这里会在调用线程上短暂阻塞，所以 TrackingOutputManager 把它放到后台线程执行。
        val deadline = System.currentTimeMillis() + 150L
        while (wsClients.isNotEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        for (client in wsClients) client.close()
        wsClients.clear()
        // 连接全部清掉：全局 upperBodyPostV1 协商随之失效，通知上层回 M1 旧链（任务 M2）
        recomputeUpperBodyPostV1()

        closeQuietly(serverSocket)
        serverSocket = null

        acceptThread?.interrupt()
        acceptThread = null
        statsThread?.interrupt()
        statsThread = null
        playoutGeneration.incrementAndGet()
        playoutThread?.interrupt()
        playoutThread = null
        // 丢掉缓冲里还没放行的帧：服务已经停了，再发出去只会让客户端看到旧表情
        frameBufferLock.withLock {
            frameBuffer.clear()
            frameBufferCondition.signalAll()
        }

        sentFps = 0f
        Log.i(TAG, "追踪服务已停止")
    }

    // ------------------------------------------------------------- 数据入口

    /**
     * 开启 / 关闭上半身数据（协议 §4.1 / §4.2）。
     *
     * 关闭时顺手丢掉快照，避免重新打开后先把关闭前的旧姿态发出去。
     */
    fun setUpperBodyEnabled(enabled: Boolean) {
        if (upperBodyOn == enabled) return
        upperBodyOn = enabled
        if (!enabled) upperBodySnapshot = null
    }

    /**
     * 运行时修改 `send.bufferMs` 配置（立即生效，不需要重启服务）。
     *
     * 行为约定（可预期）：
     *  - 非法值先过 [TrackingProtocol.normalizeSendBufferMs] 归一化（坏值 = 回落保守 500 ms）；
     *  - 缓冲实例在锁内整体替换，**旧缓冲里没放行完的帧直接丢弃**（等价于 clear() 重新预缓冲），
     *    切换瞬间客户端可能少收几帧 —— PC 按 `seq` 丢旧帧，无感知；
     *  - 帧序号 `seq` **不重置**（只在 [start] 重置，协议 §7.5）；
     *  - 改完按 [TrackingProtocol.resolveEffectiveBufferMs] 重算生效值（配置 > 0 恒生效，
     *    配置 0 还要过客户端 hello 协商这一关）。
     */
    fun setSendBufferMs(valueMs: Long) {
        val normalized = TrackingProtocol.normalizeSendBufferMs(valueMs)
        if (normalized == configuredBufferMs) return
        configuredBufferMs = normalized
        frameBufferLock.withLock {
            frameBuffer = TrackingFrameBuffer(
                if (normalized > 0L) normalized else TrackingProtocol.FRAME_BUFFER_MS,
            )
            frameBufferCondition.signalAll()
        }
        Log.i(TAG, "send.bufferMs 已改为 " + normalized + " ms")
        recomputeEffectiveBuffer()
    }

    /**
     * 重算生效发送缓冲（协议 §7.1）：客户端连入 / 断开 / 收到 hello / 配置变化时调用。
     *
     *  - 配置 > 0：生效值恒等于配置值（第一步验证模式：手机仍保留 500 ms 就是这么配的）；
     *  - 配置 == 0：只有当前**所有**已连接客户端都声明了 jitterBufferV1 才真正旁路（生效 0），
     *    有一个没声明（含老 PC、刚连上还没来得及发 hello 的新连接）就回落
     *    [TrackingProtocol.FRAME_BUFFER_MS]，并在日志里说明原因；
     *  - 无客户端时无所谓（本来就不发帧），按「全部满足」处理。
     *
     * 生效值变化时一律 [TrackingFrameBuffer.clear] 清空缓冲重新预缓冲：
     * 从 0 切回 500 是必须的（不能把旁路时代的意义不明的节奏状态带进预缓冲）；
     * 从 500 切到 0 同样清掉 —— 缓冲里没放行完的旧帧留着会晚于新的旁路帧到达造成顺序反转。
     * `seq` **不重置**（只在 [start] 重置）。
     */
    private fun recomputeEffectiveBuffer() {
        val clients = wsClients.size
        val capable = wsClients.count {
            it.clientFeatures.contains(TrackingProtocol.FEATURE_JITTER_BUFFER_V1)
        }
        val effective = TrackingProtocol.resolveEffectiveBufferMs(configuredBufferMs, clients, capable)
        if (effective == activeBufferMs) return
        val previous = activeBufferMs
        activeBufferMs = effective
        frameBufferLock.withLock {
            frameBuffer.clear()
            frameBufferCondition.signalAll()
        }
        when {
            effective == 0L -> Log.i(
                TAG,
                "生效发送缓冲: " + previous + " -> 0 ms（旁路，算完即发；" +
                    clients + " 个客户端全部声明 jitterBufferV1）",
            )

            configuredBufferMs > 0L -> Log.i(
                TAG,
                "生效发送缓冲: " + previous + " -> " + effective + " ms（配置 send.bufferMs）",
            )

            else -> Log.w(
                TAG,
                "生效发送缓冲: " + previous + " -> " + effective +
                    " ms（配置 send.bufferMs=0，但有客户端未声明 jitterBufferV1（含未协商的老客户端），" +
                    "回落保守模式缓冲）",
            )
        }
    }

    /**
     * 重算全局 upperBodyPostV1 协商结果（任务 M2）：客户端连入 / 断开 / 收到 hello 时调用。
     *
     * 聚合规则见 [upperBodyPostV1Active]：`wsClients.isNotEmpty() && 全部 upperBodyPostV1`。
     * 值变化时通过 [onUpperBodyPostV1] 通知上层切换检测器的原始输出（照 [onRecalibrate]
     * 的接线模式）；回调在调用线程直接触发（协议线程），上层自己决定要不要切线程。
     */
    private fun recomputeUpperBodyPostV1() {
        val active = wsClients.isNotEmpty() && wsClients.all { it.upperBodyPostV1 }
        if (active == upperBodyPostV1Active) return
        upperBodyPostV1Active = active
        val negotiated = wsClients.count { it.upperBodyPostV1 }
        Log.i(
            TAG,
            "上半身后处理协商: upperBodyPostV1=" + active +
                "（" + negotiated + "/" + wsClients.size +
                " 个客户端已协商；混合客户端时全体回退 M1 旧链）",
        )
        onUpperBodyPostV1(active)
    }

    /**
     * 保存最新一帧上半身关键点（约 15 Hz 调用）。
     *
     * 只做一次引用赋值，不在调用线程做任何序列化 / 计算。
     */
    fun submitUpperBodySnapshot(frame: UpperBodyPoseFrame) {
        if (!upperBodyOn) return
        upperBodySnapshot = frame
    }

    /**
     * 面捕管线每处理完一帧调用一次（协议 §4.2）。
     *
     * [headPose] 必须是**已减去首帧标定基线**的相对姿态（pose 字段，单位度）。
     * [fps] 为手机端处理帧率，仅供客户端展示。
     */
    fun submitTrack(
        blendShapes: ARKitBlendShapes,
        headPose: HeadPoseData,
        presenceScore: Float,
        fps: Float,
        calibrating: Boolean,
    ) {
        val seq = sequence.getAndIncrement()
        val now = System.currentTimeMillis()

        // 校准窗口边界：进入 / 结束各发一次 calib（协议 §4.3）。
        // calib 也走帧缓冲，否则它会被提前 0.5 s 送出去、和 track 的顺序错开。
        if (calibrating != lastCalibActive) {
            lastCalibActive = calibrating
            if (wsClients.isNotEmpty()) {
                dispatch(TrackingProtocol.buildCalib(calibrating, seq, now), control = true)
            }
        }

        // 没有客户端时连 JSON 都不生成（协议要求手机端零额外开销）
        if (wsClients.isEmpty()) return

        val face = presenceScore >= FACE_PRESENCE_THRESHOLD

        // 上半身是可选增量（协议 §4.2）：未启用时一个 ub* 字段都不出现；
        // 启用后即使本帧没结果（从未收到 / 超过 1500 ms 未更新 / tracking=false）也发
        // ubOk:false，ub / body 全 0，保证结构稳定，客户端可安全回默认姿态。
        // 任务 M2：占位帧跟随当前 raw 模式，协商生效期间 ubRaw 逐帧一致出现。
        val rawActive = upperBodyPostV1Active
        val upperBodyFrame = if (upperBodyOn) {
            upperBodySnapshot ?: if (rawActive) emptyRawUpperBodyFrame else emptyUpperBodyFrame
        } else {
            null
        }
        val upperBodyOk = upperBodyFrame != null &&
            upperBodyFrame.tracking &&
            now - upperBodyFrame.timestampMs <= UPPER_BODY_STALE_MS

        val message = TrackingProtocol.buildTrack(
            seq = seq,
            timestampMs = now,
            blendShapes = blendShapes,
            pitch = headPose.pitch,
            yaw = headPose.yaw,
            roll = headPose.roll,
            face = face,
            score = presenceScore,
            fps = fps,
            calibrating = calibrating,
            upperBody = upperBodyFrame,
            upperBodyOk = upperBodyOk,
            // 广播同一条报文，协商状态只能取全体聚合值（见 [upperBodyPostV1Active]）
            upperBodyPostV1 = rawActive,
        )
        dispatch(message)
    }

    /**
     * 把一条已序列化的报文投给发送链路（协议 §7.1）。
     *
     * [activeBufferMs] > 0 时先入帧缓冲（calib 与 track 同队，顺序天然保持），由
     * [playoutThread] 攒够延迟后匀速放行；为 0（旁路模式）时直接投进每个客户端的
     * [TrackingSendQueue] —— 该队列 track / control 同队严格保序，突发时只丢最旧的 track，
     * 因此不需要「槽位」也不会出现 calib / track 错配。
     *
     * @param control true = calib 这类控制消息；走 [TrackingWsConnection.offerControl]，
     *   溢出时保证不会被 track 挤掉。
     */
    private fun dispatch(message: String, control: Boolean = false) {
        if (activeBufferMs <= 0L) {
            for (client in wsClients) {
                if (control) client.offerControl(message) else client.offerTrack(message)
            }
            if (!control) framesInWindow.incrementAndGet()
            return
        }
        frameBufferLock.withLock {
            // 缓冲队列是单生产者（面捕派发线程）写入的 FIFO，calib 与 track 的顺序天然保持
            frameBuffer.enqueue(message, System.nanoTime())
            // 放行线程可能正睡在 await() 里，入队后立刻叫醒它重算到期时间
            frameBufferCondition.signalAll()
        }
    }

    /**
     * 帧缓冲放行线程：按 [TrackingFrameBuffer] 的到期时刻，一条一条把帧交给客户端。
     *
     * 不忙等：没有到期的帧就 `await()` 到下一帧的到期时间（最多 [PLAYOUT_IDLE_WAIT_MS]），
     * 入队 / 停止会 signal 把它叫醒。
     */
    private fun playoutLoop(playoutId: Int) {
        Log.i(TAG, "帧缓冲放行线程已启动（配置 send.bufferMs=" + configuredBufferMs + " ms）")
        // 旧线程（上一轮 start 起的）看到的代次已经不同，必须立刻退出，不能抢新会话的帧
        while (running && playoutGeneration.get() == playoutId) {
            var message: String? = null
            var interrupted = false

            frameBufferLock.withLock {
                val now = System.nanoTime()
                message = frameBuffer.poll(now)
                if (message == null) {
                    val due = frameBuffer.nextDueNanos()
                    val waitNanos = if (due == Long.MAX_VALUE) {
                        PLAYOUT_IDLE_WAIT_NANOS
                    } else {
                        // 最多睡到下一帧到期，但不超过空闲上限，保证 stop() 能及时退出
                        (due - now).coerceIn(1_000_000L, PLAYOUT_IDLE_WAIT_NANOS)
                    }
                    try {
                        frameBufferCondition.awaitNanos(waitNanos)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        interrupted = true
                    }
                }
            }

            if (interrupted) break
            val frame = message ?: continue

            // 从缓冲放行的既有 track 也有 calib（control）：按 JSON 的 type 分流到对应投递口，
            // 让每个客户端的 [TrackingSendQueue] 知道哪条可丢（track）、哪条绝不丢（control）。
            // 非 "track" 一律按 control 保护处理（缓冲里只会有 track / calib，未知类型宁可不丢）。
            val control = TrackingProtocol.parseType(frame) != "track"
            for (client in wsClients) {
                if (control) client.offerControl(frame) else client.offerTrack(frame)
            }
            // 缓冲里混着极少数 calib（每次会话最多 2 条），一并在 sentFps 里计数，影响可忽略
            framesInWindow.incrementAndGet()
        }
        Log.i(TAG, "帧缓冲放行线程已退出")
    }

    // ---------------------------------------------------------------- accept

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept 失败: " + e.message)
                break
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "accept 异常: " + t.message)
                break
            }

            if (activeConnections.get() >= MAX_CONNECTIONS) {
                Log.w(TAG, "连接数超过上限 " + MAX_CONNECTIONS + "，拒绝新连接")
                closeQuietly(client)
                continue
            }

            activeConnections.incrementAndGet()
            try {
                client.tcpNoDelay = true
                client.soTimeout = HEADER_READ_TIMEOUT_MS
                Thread({ handleClient(client) }, "tracking-client").also {
                    it.isDaemon = true
                    it.start()
                }
            } catch (t: Throwable) {
                activeConnections.decrementAndGet()
                Log.w(TAG, "创建连接线程失败: " + t.message)
                closeQuietly(client)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            handleClientInner(socket)
        } finally {
            activeConnections.decrementAndGet()
        }
    }

    private fun handleClientInner(socket: Socket) {
        var handoverToWebSocket = false
        try {
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)

            val header = readRequestHeader(input)
            if (header == null) {
                closeQuietly(socket)
                return
            }

            val lines = header.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: ""
            val rawPath = parts.getOrNull(1) ?: "/"
            val path = rawPath.substringBefore('?').ifEmpty { "/" }

            val headers = HashMap<String, String>()
            for (index in 1 until lines.size) {
                val colon = lines[index].indexOf(':')
                if (colon > 0) {
                    headers[lines[index].substring(0, colon).trim().lowercase(Locale.US)] =
                        lines[index].substring(colon + 1).trim()
                }
            }

            if (headers["upgrade"]?.lowercase(Locale.US)?.contains("websocket") == true) {
                handoverToWebSocket = handleWebSocket(socket, input, output, headers)
                return
            }

            socket.soTimeout = HEADER_READ_TIMEOUT_MS
            when {
                method != "GET" -> writeSimpleResponse(
                    output,
                    "405 Method Not Allowed",
                    "text/plain; charset=utf-8",
                    "Method Not Allowed",
                )

                path == TrackingProtocol.PATH_INFO || path == "/index.html" -> servePage(output)

                path == TrackingProtocol.PATH_STATUS -> serveStatus(output)

                path == "/favicon.ico" -> {
                    output.write(
                        "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    output.flush()
                }

                else -> writeSimpleResponse(
                    output,
                    "404 Not Found",
                    "text/plain; charset=utf-8",
                    "404 Not Found. Try / (info page), /status (JSON) or /ws (WebSocket).",
                )
            }
        } catch (t: Throwable) {
            Log.d(TAG, "连接异常: " + t.message)
        } finally {
            if (!handoverToWebSocket) closeQuietly(socket)
        }
    }

    // -------------------------------------------------------------- websocket

    /**
     * 完成 WebSocket 握手并启动读写线程。
     * @return true 表示 socket 已移交给 WebSocket 连接（false 表示握手失败，调用方负责关闭）。
     */
    private fun handleWebSocket(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
    ): Boolean {
        val key = headers["sec-websocket-key"]
        if (key.isNullOrBlank()) {
            writeSimpleResponse(output, "400 Bad Request", "text/plain; charset=utf-8", "Missing Sec-WebSocket-Key")
            return false
        }
        if (wsClients.size >= MAX_WS_CLIENTS) {
            Log.w(TAG, "WebSocket 客户端已达上限 " + MAX_WS_CLIENTS)
            writeSimpleResponse(output, "503 Service Unavailable", "text/plain; charset=utf-8", "Too many clients")
            return false
        }

        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + WS_GUID).toByteArray(Charsets.ISO_8859_1)),
            Base64.NO_WRAP,
        )

        val response = StringBuilder(160)
            .append("HTTP/1.1 101 Switching Protocols\r\n")
            .append("Upgrade: websocket\r\n")
            .append("Connection: Upgrade\r\n")
            .append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
            .append("\r\n")
            .toString()
        output.write(response.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        socket.soTimeout = WS_IDLE_TIMEOUT_MS

        lateinit var connection: TrackingWsConnection
        connection = TrackingWsConnection(
            socket = socket,
            input = input,
            output = output,
            listener = object : TrackingWsConnection.Listener {
                override fun onText(text: String) {
                    handleClientMessage(connection, text)
                }

                override fun onClosed() {
                    wsClients.remove(connection)
                    // 客户端集合变了（可能影响 jitterBufferV1 协商结果），重算生效发送缓冲
                    recomputeEffectiveBuffer()
                    // 客户端集合变了也可能改变 upperBodyPostV1 的全体聚合结果（任务 M2）
                    recomputeUpperBodyPostV1()
                    // 最后一个客户端也走了：清掉缓冲里还没放行的帧，
                    // 否则下一个客户端一连上会先收到 0.5 s 之前的老表情。
                    if (wsClients.isEmpty()) {
                        frameBufferLock.withLock { frameBuffer.clear() }
                    }
                    Log.i(TAG, "WebSocket 客户端断开，剩余 " + wsClients.size)
                }
            },
        )

        wsClients.add(connection)
        // 新连接还没发 hello，feature 集合为空 —— 配置为 0 时生效缓冲会先回落保守模式，
        // 收到 hello（下面 handleClientMessage 的 CLIENT_HELLO 分支）协商通过后再切回 0。
        recomputeEffectiveBuffer()
        // 新连接未协商 upperBodyPostV1，聚合结果必然为 false（任务 M2）
        recomputeUpperBodyPostV1()
        connection.start()

        // §4.1：连接建立后立即发 hello
        connection.offerControl(
            TrackingProtocol.buildHello(
                device = deviceName,
                app = appName,
                decimation = decimation,
                targetFps = targetFps,
                upperBody = upperBodyOn,
                // 任务 M2：上半身启用即声明支持（手机端可按 ubRaw 发原始点，后处理迁 PC）
                upperBodyPostV1 = upperBodyOn,
                timestampMs = System.currentTimeMillis(),
            )
        )

        Log.i(TAG, "WebSocket 客户端已连接，共 " + wsClients.size)
        return true
    }

    /** 客户端上行：只认 ping / command / hello（协议 §3 / §4.4 / §4.5），其余一律忽略。 */
    private fun handleClientMessage(connection: TrackingWsConnection, text: String) {
        when (TrackingProtocol.classifyClientMessage(text)) {
            TrackingProtocol.ClientMessage.PING -> {
                val clientTime = TrackingProtocol.parseLongField(text, "t")
                connection.offerControl(
                    TrackingProtocol.buildPong(clientTime ?: System.currentTimeMillis())
                )
            }

            TrackingProtocol.ClientMessage.RECALIBRATE -> {
                onRecalibrate()
                connection.offerControl(
                    TrackingProtocol.buildAck(TrackingProtocol.COMMAND_RECALIBRATE)
                )
            }

            TrackingProtocol.ClientMessage.CLIENT_HELLO -> {
                // 能力协商（协议 §4.5）：记录该连接声明的 feature 集合，重算生效发送缓冲。
                // 老客户端不发 hello，集合保持为空 —— 配置 send.bufferMs=0 时自动回落保守模式。
                val features = TrackingProtocol.parseClientFeatures(text)
                connection.clientFeatures = features
                // 任务 M2：features 含 "upperBodyPostV1" 即协商成功；顶层布尔
                // "upperBodyPostV1":true 为兜底形式，任一命中都标记该连接「已协商」。
                val upperBodyPostV1 =
                    features.contains(TrackingProtocol.FEATURE_UPPER_BODY_POST_V1) ||
                        TrackingProtocol.parseBoolField(text, "upperBodyPostV1") == true
                connection.upperBodyPostV1 = upperBodyPostV1
                Log.i(
                    TAG,
                    "客户端 hello 协商: features=" + features.sorted().joinToString(",", "[", "]") +
                        " upperBodyPostV1=" + upperBodyPostV1,
                )
                recomputeEffectiveBuffer()
                recomputeUpperBodyPostV1()
            }

            TrackingProtocol.ClientMessage.IGNORED -> Unit
        }
    }

    private fun statsLoop() {
        var windowStart = System.currentTimeMillis()
        var keepaliveElapsed = 0L
        while (running) {
            try {
                Thread.sleep(250L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            val now = System.currentTimeMillis()
            val elapsed = now - windowStart
            if (elapsed >= 1000L) {
                val frames = framesInWindow.getAndSet(0)
                sentFps = frames * 1000f / elapsed
                windowStart = now
            }

            keepaliveElapsed += 250L
            if (keepaliveElapsed >= KEEPALIVE_INTERVAL_MS) {
                keepaliveElapsed = 0L
                for (client in wsClients) client.sendPing()
            }
        }
    }

    // ------------------------------------------------------------------- http

    private fun servePage(output: OutputStream) {
        val bytes = pageBytes ?: INFO_PAGE_TEXT.toByteArray(Charsets.UTF_8).also { pageBytes = it }
        val header = StringBuilder(128)
            .append("HTTP/1.1 200 OK\r\n")
            .append("Content-Type: text/html; charset=utf-8\r\n")
            .append("Content-Length: ").append(bytes.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
            .toString()
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun serveStatus(output: OutputStream) {
        val body = TrackingProtocol.buildStatus(
            running = running,
            tracking = trackingActive,
            clients = wsClients.size,
            fps = sentFps,
            device = deviceName,
            seq = latestSeq,
        )
        writeSimpleResponse(output, "200 OK", "application/json; charset=utf-8", body)
    }

    private fun writeSimpleResponse(output: OutputStream, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = StringBuilder(128)
            .append("HTTP/1.1 ").append(status).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(bytes.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
            .toString()
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun readRequestHeader(input: InputStream): String? {
        val buffer = ByteArray(MAX_HEADER_BYTES)
        var length = 0
        while (length < buffer.size) {
            val value = input.read()
            if (value < 0) return null
            buffer[length++] = value.toByte()
            if (length >= 4 &&
                buffer[length - 4] == 13.toByte() &&
                buffer[length - 3] == 10.toByte() &&
                buffer[length - 2] == 13.toByte() &&
                buffer[length - 1] == 10.toByte()
            ) {
                return String(buffer, 0, length - 4, Charsets.ISO_8859_1)
            }
        }
        return null
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (ignored: IOException) {
        }
    }
}

/** 纯静态信息页：只用来确认服务在线 / 看连接数，不参与数据通道。 */
private val INFO_PAGE_TEXT = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>VFC Tracking Server</title>
<style>
  body { margin:0; background:#0f172a; color:#e2e8f0; font-family: system-ui, -apple-system, "Segoe UI", sans-serif; }
  .wrap { max-width: 680px; margin: 0 auto; padding: 32px 20px; }
  h1 { font-size: 20px; margin: 0 0 6px; }
  .sub { color:#94a3b8; font-size: 13px; margin-bottom: 24px; }
  .card { background:#1e293b; border-radius: 12px; padding: 14px 20px; margin-bottom: 16px; }
  .row { display:flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px dashed #334155; font-size:14px; }
  .row:last-child { border-bottom: none; }
  .k { color:#94a3b8; }
  .ok { color:#4ade80; }
  .off { color:#f87171; }
  code { background:#0b1220; padding:2px 6px; border-radius:6px; font-size:13px; }
  .hint { color:#94a3b8; font-size:12.5px; line-height:1.8; }
</style>
</head>
<body>
<div class="wrap">
  <h1>VFC Tracking Server</h1>
  <div class="sub">VirtualFaceCapture · 面部捕捉追踪数据服务（vfc-tracker v1 / 8765）</div>
  <div class="card">
    <div class="row"><span class="k">服务状态</span><span id="running" class="ok">-</span></div>
    <div class="row"><span class="k">面捕管线</span><span id="tracking" class="off">-</span></div>
    <div class="row"><span class="k">WebSocket 客户端</span><span id="clients">-</span></div>
    <div class="row"><span class="k">输出帧率</span><span id="fps">-</span></div>
    <div class="row"><span class="k">最新帧序号</span><span id="seq">-</span></div>
    <div class="row"><span class="k">设备</span><span id="device">-</span></div>
  </div>
  <div class="card hint">
    <div>Windows 客户端连接地址：<code id="wsurl">ws://...</code></div>
    <div>JSON 状态接口：<code id="statusurl">/status</code></div>
    <div style="margin-top:10px">手机与电脑需在同一局域网。本页面只用于确认服务在线，追踪数据请用 WebSocket 客户端订阅 /ws。</div>
  </div>
</div>
<script>
document.getElementById("wsurl").textContent = "ws://" + location.host + "/ws";
document.getElementById("statusurl").textContent = location.protocol + "//" + location.host + "/status";
function refresh() {
  fetch("/status", { cache: "no-store" }).then(function (r) { return r.json(); }).then(function (s) {
    var running = document.getElementById("running");
    running.textContent = s.running ? "运行中" : "已停止";
    running.className = s.running ? "ok" : "off";
    var tracking = document.getElementById("tracking");
    tracking.textContent = s.tracking ? "面捕中" : "未开始";
    tracking.className = s.tracking ? "ok" : "off";
    document.getElementById("clients").textContent = s.clients;
    document.getElementById("fps").textContent = (typeof s.fps === "number" ? s.fps.toFixed(1) : s.fps) + " fps";
    document.getElementById("seq").textContent = s.seq;
    document.getElementById("device").textContent = s.device || "-";
  }).catch(function () {
    document.getElementById("running").textContent = "连接中断";
    document.getElementById("running").className = "off";
  });
}
refresh();
setInterval(refresh, 1000);
</script>
</body>
</html>
""".trimIndent()

/**
 * 极简 WebSocket 服务端连接（RFC 6455 子集，仅文本帧 + 控制帧）。
 *
 * 线程模型：
 *  - reader 线程：解析客户端帧，回应 ping/close，文本消息交给 [Listener]
 *  - writer 线程：从 per-client 有限 FIFO（[TrackingSendQueue]）按**投递顺序**发。
 *    track 与 control（hello / calib / pong / ack / bye）同队严格保序，杜绝 calib / track
 *    错配；队列有硬上限（8 track + 32 control），网络跟不上时丢最旧的 track（保最新帧）、
 *    绝不丢 control。[offerTrack] / [offerControl] 非阻塞、不碰网络 IO ——
 *    慢客户端永远不拖慢推理线程（协议 §7.1）。
 */
internal class TrackingWsConnection(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: Listener,
) {

    interface Listener {
        /** 收到客户端文本消息（ping / command）。 */
        fun onText(text: String)

        /** 连接已关闭（只会回调一次）。 */
        fun onClosed()
    }

    companion object {
        private const val TAG = "TrackingWsConnection"

        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        /** 客户端上行的最大帧，防止异常数据把内存打爆。 */
        private const val MAX_INCOMING_FRAME = 64 * 1024

        private val EMPTY_PAYLOAD = ByteArray(0)
    }

    /**
     * per-client 有限 FIFO 发送队列：track 与 control（hello / calib / pong / ack / bye）
     * 同队严格保序；溢出丢最旧的 track、绝不丢 control。容量与丢弃策略见 [TrackingSendQueue]。
     */
    private val sendQueue = TrackingSendQueue()

    /**
     * 该连接通过客户端 hello（协议 §4.5）声明的能力集合；没发过 hello 的老客户端恒为空集。
     *
     * reader 线程（hello 到达时）写入、[TrackingServer.recomputeEffectiveBuffer] 等处读取，
     * 用 @Volatile 保证可见性。
     *
     * 注意：属性 setter 的 JVM 名就是 setClientFeatures —— 不要再定义同名方法，
     * 否则 Kotlin 会报 Platform declaration clash（参考 [TrackingServer] 里 upperBodyOn 的注释）。
     */
    @Volatile
    var clientFeatures: Set<String> = emptySet()

    /**
     * 该连接是否已协商 upperBodyPostV1（任务 M2）：收到客户端 hello 且
     * `features` 含 `upperBodyPostV1` 或顶层布尔 `"upperBodyPostV1":true` 时置 true。
     *
     * 老客户端 / 没发过 hello 的连接恒为 false。reader 线程（hello 到达时）写入、
     * [TrackingServer.recomputeUpperBodyPostV1] 等处读取，用 @Volatile 保证可见性。
     * 注意：属性 setter 的 JVM 名是 setUpperBodyPostV1 —— 不要再定义同名方法。
     */
    @Volatile
    var upperBodyPostV1: Boolean = false

    /**
     * writer 线程的唤醒信号。
     *
     * 之前 writer 空闲时阻塞 poll(20ms)，而 [offerTrack] 只写一个覆盖槽位、不会唤醒它 ——
     * 结果每帧最多要等 20ms 才被发出去，**发送帧率被硬性钉在 ~50fps**，还额外带来
     * 0~20ms 的延迟。面捕要 60fps，这里必须让投递立刻唤醒 writer。
     *
     * 许可数限制在 2 以内，避免帧率高于发送能力时许可无限累积、导致空转。
     */
    private val writerWakeup = Semaphore(0)

    /** 唤醒 writer；已有未消费的许可时不再叠加。 */
    private fun signalWriter() {
        if (writerWakeup.availablePermits() < 2) {
            writerWakeup.release()
        }
    }

    private val pendingPong = AtomicReference<ByteArray?>(null)
    private val pendingPing = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)

    @Volatile
    private var closed = false

    private val readerThread = Thread({ readLoop() }, "tracking-ws-reader")
    private val writerThread = Thread({ writeLoop() }, "tracking-ws-writer")

    fun start() {
        readerThread.isDaemon = true
        writerThread.isDaemon = true
        writerThread.start()
        readerThread.start()
    }

    /**
     * 投递一帧追踪 JSON 进 per-client FIFO（[TrackingSendQueue]）。
     *
     * 不再是「单槽覆盖」：track 与 control 同队严格保序，突发时最多排
     * [TrackingSendQueue.MAX_TRACK] 条，溢出丢最旧的 track（保最新帧）。非阻塞、
     * 不碰网络 IO，慢客户端拖不住推理线程。
     */
    fun offerTrack(message: String) {
        if (closed) return
        sendQueue.offer(message, control = false)
        // 关键：立刻叫醒 writer。否则它还在 20ms 的 await 里，发送帧率会掉到 ~50fps。
        signalWriter()
    }

    /** 投递控制消息（hello / calib / pong / ack / bye）：与 track 同队保序，溢出时绝不被 track 挤掉。 */
    fun offerControl(message: String) {
        if (closed) return
        sendQueue.offer(message, control = true)
        signalWriter()
    }

    /** 让 writer 线程发一个协议层 ping。 */
    fun sendPing() {
        if (closed) return
        pendingPing.set(true)
        signalWriter()
    }

    /** 队列里的控制消息发完后再关闭连接（[TrackingServer.stop] 用它送出 bye）。 */
    fun requestClose() {
        closeRequested.set(true)
        signalWriter()
    }

    /** 只有 writer 线程会调用，所以不需要加锁。 */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        try {
            output.write(0x80 or opcode)
            val size = payload.size
            when {
                size < 126 -> output.write(size)
                size <= 0xFFFF -> {
                    output.write(126)
                    output.write((size ushr 8) and 0xFF)
                    output.write(size and 0xFF)
                }
                else -> {
                    output.write(127)
                    val length = size.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        output.write(((length ushr shift) and 0xFF).toInt())
                    }
                }
            }
            output.write(payload)
            output.flush()
        } catch (e: IOException) {
            close()
        }
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                // requestClose 后等 FIFO 冲刷干净（bye 就排在里面）再退出
                if (closeRequested.get() && sendQueue.isEmpty) break

                // WebSocket 协议层 ping/pong 是带外控制帧，与应用消息没有顺序关系，
                // 优先服务掉（防止 FIFO 积压时把保活拖住）。
                if (pendingPing.getAndSet(false)) {
                    sendFrame(OP_PING, EMPTY_PAYLOAD)
                    continue
                }
                val pong = pendingPong.getAndSet(null)
                if (pong != null) {
                    sendFrame(OP_PONG, pong)
                    continue
                }
                // 应用消息（track 与 hello / calib / pong / ack / bye）：同一个 FIFO 严格按
                // 投递顺序发送，杜绝 calib / track 错配；溢出丢弃已经在 offer 时按策略完成。
                val message = sendQueue.poll()
                if (message != null) {
                    sendFrame(OP_TEXT, message.toByteArray(Charsets.UTF_8))
                    continue
                }

                // 空闲：阻塞等唤醒信号（数据帧 / 控制消息 / ping / close 都会 release），
                // 醒来回到循环顶部统一分发。20ms 只是兜底超时（防止漏唤醒时永久挂住）。
                writerWakeup.tryAcquire(20L, TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.w(TAG, "writer loop exit: " + t.message)
        } finally {
            close()
        }
    }

    private fun readLoop() {
        try {
            var fragmentOpcode = -1
            var fragmentBuffer: ByteArrayOutputStream? = null

            while (!closed) {
                val first = input.read()
                if (first < 0) break
                val second = input.read()
                if (second < 0) break

                val fin = (first and 0x80) != 0
                val opcode = first and 0x0F
                val masked = (second and 0x80) != 0
                var length = (second and 0x7F).toLong()

                if (length == 126L) {
                    val a = input.read()
                    val b = input.read()
                    if (a < 0 || b < 0) break
                    length = (((a shl 8) or b) and 0xFFFF).toLong()
                } else if (length == 127L) {
                    var value = 0L
                    var valid = true
                    for (i in 0 until 8) {
                        val c = input.read()
                        if (c < 0) {
                            valid = false
                            break
                        }
                        value = (value shl 8) or c.toLong()
                    }
                    if (!valid) break
                    length = value
                }

                if (length < 0 || length > MAX_INCOMING_FRAME) {
                    close()
                    break
                }

                val payload = ByteArray(length.toInt())

                // RFC 6455 §5.3：masking-key 紧跟在长度字段之后、payload 之前。
                if (masked) {
                    val mask = ByteArray(4)
                    readFully(mask)
                    readFully(payload)
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                    }
                } else {
                    readFully(payload)
                }

                when (opcode) {
                    OP_CLOSE -> {
                        close()
                        return
                    }

                    OP_PING -> pendingPong.set(payload)

                    OP_PONG -> Unit

                    OP_TEXT, OP_BINARY -> {
                        if (fin) {
                            if (opcode == OP_TEXT) {
                                listener.onText(String(payload, Charsets.UTF_8))
                            }
                        } else {
                            fragmentOpcode = opcode
                            fragmentBuffer = ByteArrayOutputStream(payload.size * 2).apply { write(payload) }
                        }
                    }

                    OP_CONTINUATION -> {
                        val buffer = fragmentBuffer
                        if (buffer != null) {
                            buffer.write(payload)
                            if (buffer.size() > MAX_INCOMING_FRAME) {
                                close()
                                return
                            }
                            if (fin) {
                                if (fragmentOpcode == OP_TEXT) {
                                    listener.onText(String(buffer.toByteArray(), Charsets.UTF_8))
                                }
                                fragmentBuffer = null
                                fragmentOpcode = -1
                            }
                        }
                    }

                    else -> Unit
                }
            }
        } catch (e: IOException) {
            // 对端断开 / 服务停止 / 读超时，均属正常
        } catch (t: Throwable) {
            Log.w(TAG, "reader loop exit: " + t.message)
        } finally {
            close()
        }
    }

    private fun readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw IOException("unexpected end of stream")
            offset += read
        }
    }

    fun close() {
        val firstTime: Boolean
        synchronized(this) {
            firstTime = !closed
            closed = true
        }
        if (!firstTime) return

        pendingPong.set(null)
        pendingPing.set(false)
        sendQueue.clear()
        // 让 writer 立刻从 20ms 兜底等待里醒来退出，而不是等超时。
        signalWriter()
        try {
            socket.close()
        } catch (ignored: IOException) {
        }
        listener.onClosed()
    }
}
