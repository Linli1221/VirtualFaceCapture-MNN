package com.example.virtualfacecapture.tracking

import android.util.Base64
import android.util.Log
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
import java.util.concurrent.LinkedBlockingQueue
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
 * 数据流：面捕管线的每帧结果由 [submitTrack] 推进来 → 手写 JSON → 进**发送帧缓冲**
 * （默认 0.5 s，见 [TrackingFrameBuffer]）→ 缓冲线程按生产节奏匀速放行 → 每个客户端
 * 各自一个「最新一帧」槽位（覆盖式写入）。网络慢的客户端只会丢帧，绝不会拖慢推理线程。
 * 控制类消息（hello / calib / pong / ack / bye）走独立小队列，不会被数据帧冲掉。
 */
class TrackingServer(
    private val port: Int = TrackingProtocol.PORT,
    private val deviceName: String,
    private val appName: String,
    private val onRecalibrate: () -> Unit = {},
    /** 发送帧缓冲时长（毫秒）；0 = 关闭缓冲，算完就发。见 [TrackingProtocol.FRAME_BUFFER_MS]。 */
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

    /**
     * 发送帧缓冲：先攒 [frameBufferMs] 再匀速放行（协议 §7.1）。
     *
     * 内部不是线程安全的，[frameBuffer] 的每一次访问都要在 [frameBufferLock] 里；
     * 入队 / 停止后 signal [frameBufferCondition]，让 [playoutThread] 立刻重算到期时间。
     */
    private val frameBuffer = TrackingFrameBuffer(frameBufferMs)
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
        // 缓冲关闭时不需要放行线程：dispatch() 直接广播
        if (frameBufferMs > 0L) {
            val playoutId = playoutGeneration.incrementAndGet()
            playoutThread = Thread({ playoutLoop(playoutId) }, "tracking-playout").also {
                it.isDaemon = true
                it.start()
            }
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
        val upperBodyFrame = if (upperBodyOn) {
            upperBodySnapshot ?: emptyUpperBodyFrame
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
        )
        dispatch(message)
    }

    /**
     * 把一条已序列化的报文投给发送链路（协议 §7.1）。
     *
     * [frameBufferMs] > 0 时先入帧缓冲，由 [playoutThread] 攒够延迟后匀速放行；
     * 为 0 时退化成「算完就发」，和旧行为完全一致（`control` 决定走哪个槽位）。
     *
     * @param control true = calib 这类控制消息；关闭缓冲时走 [TrackingWsConnection.offerControl]，
     *   保证不会被紧随其后的 track 帧覆盖。
     */
    private fun dispatch(message: String, control: Boolean = false) {
        if (frameBufferMs <= 0L) {
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
        Log.i(TAG, "发送帧缓冲已启用: " + frameBufferMs + " ms")
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

            // 每个客户端只有一个「最新一帧」槽位：这里赶上网络慢的客户端时也是覆盖式丢帧
            for (client in wsClients) client.offerTrack(frame)
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
        connection.start()

        // §4.1：连接建立后立即发 hello
        connection.offerControl(
            TrackingProtocol.buildHello(
                device = deviceName,
                app = appName,
                decimation = decimation,
                targetFps = targetFps,
                upperBody = upperBodyOn,
                timestampMs = System.currentTimeMillis(),
            )
        )

        Log.i(TAG, "WebSocket 客户端已连接，共 " + wsClients.size)
        return true
    }

    /** 客户端上行：只认 ping 与 command（协议 §3 / §4.4），其余一律忽略。 */
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
 *  - writer 线程：控制消息按队列顺序发；track 帧只保留**最新一帧**（覆盖式），
 *    网络跟不上就直接丢旧帧，保证低延迟、不堆积（协议 §7.1）
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

        /** 控制消息队列上限。 */
        private const val MAX_PENDING_CONTROL = 32

        private val EMPTY_PAYLOAD = ByteArray(0)
    }

    /** 控制消息（hello / calib / pong / ack / bye）按序排队，不能被数据帧冲掉。 */
    private val controlQueue = LinkedBlockingQueue<String>(MAX_PENDING_CONTROL)

    /** 数据帧只有 1 个槽位：覆盖式写入，读走即空。 */
    private val pendingTrack = AtomicReference<String?>(null)

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

    /** 投递一帧追踪 JSON；上一帧还没发出去就直接覆盖（保最新帧）。 */
    fun offerTrack(message: String) {
        if (closed) return
        pendingTrack.set(message)
    }

    /** 投递控制消息（不会被覆盖）。 */
    fun offerControl(message: String) {
        if (closed) return
        if (!controlQueue.offer(message)) {
            // 队列满：挤掉最旧的一条，保证 bye / ack 这类关键消息仍有发送机会
            controlQueue.poll()
            controlQueue.offer(message)
        }
    }

    /** 让 writer 线程发一个协议层 ping。 */
    fun sendPing() {
        if (closed) return
        pendingPing.set(true)
    }

    /** 队列里的控制消息发完后再关闭连接（[TrackingServer.stop] 用它送出 bye）。 */
    fun requestClose() {
        closeRequested.set(true)
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
                if (closeRequested.get() && controlQueue.isEmpty() && pendingTrack.get() == null) break

                val control = controlQueue.poll()
                if (control != null) {
                    sendFrame(OP_TEXT, control.toByteArray(Charsets.UTF_8))
                    continue
                }
                if (pendingPing.getAndSet(false)) {
                    sendFrame(OP_PING, EMPTY_PAYLOAD)
                    continue
                }
                val pong = pendingPong.getAndSet(null)
                if (pong != null) {
                    sendFrame(OP_PONG, pong)
                    continue
                }
                val track = pendingTrack.getAndSet(null)
                if (track != null) {
                    sendFrame(OP_TEXT, track.toByteArray(Charsets.UTF_8))
                    continue
                }

                // 空闲：短阻塞等控制消息，醒来再检查一次最新帧槽位
                val next = controlQueue.poll(20L, TimeUnit.MILLISECONDS)
                if (next != null) sendFrame(OP_TEXT, next.toByteArray(Charsets.UTF_8))
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

        pendingTrack.set(null)
        controlQueue.clear()
        pendingPong.set(null)
        pendingPing.set(false)
        try {
            socket.close()
        } catch (ignored: IOException) {
        }
        listener.onClosed()
    }
}
