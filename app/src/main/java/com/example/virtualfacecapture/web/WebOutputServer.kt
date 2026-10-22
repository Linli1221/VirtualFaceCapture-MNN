package com.example.virtualfacecapture.web

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网 HTTP / WebSocket 服务器：把手机端渲染好的画面推给浏览器（OBS 浏览器源）。
 *
 * 路由：
 * ```
 * GET /              -> 网页（assets/web/index.html，默认透明背景）
 * GET /index.html    -> 同上
 * GET /status        -> JSON 状态，排障用
 * GET /stream.mjpg   -> MJPEG 备用流（无透明通道）
 * GET /favicon.ico   -> 204
 * WS  /ws            -> 二进制图像帧 + 文本状态 JSON
 * ```
 *
 * 图像帧只由编码线程通过 [broadcast] 推入，每个客户端各自维护「最新一帧」，
 * 网络慢的客户端只会丢帧，不会拖慢整个管线。
 */
class WebOutputServer(
    private val context: Context,
    private val port: Int,
    private val infoProvider: () -> StreamInfo,
) {

    data class StreamInfo(
        val fps: Float,
        val width: Int,
        val height: Int,
        val model: String,
    )

    companion object {
        private const val TAG = "WebOutputServer"
        private const val BOUNDARY = "vfc-frame"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val PAGE_ASSET = "web/index.html"
        private const val MAX_CONNECTIONS = 24
        private const val MAX_WS_CLIENTS = 8

        /** 握手后读超时：页面每 10s 发一次 ping，长时间收不到就认为连接已死并回收。 */
        private const val WS_IDLE_TIMEOUT_MS = 120_000
    }

    private val activeConnections = AtomicInteger()

    private val wsClients = CopyOnWriteArrayList<WsConnection>()
    private val mjpegClients = CopyOnWriteArrayList<MjpegClient>()

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var statusThread: Thread? = null

    @Volatile
    private var pageBytes: ByteArray? = null

    @Volatile
    var lastError: String? = null
        private set

    val webSocketClientCount: Int
        get() = wsClients.size

    val mjpegClientCount: Int
        get() = mjpegClients.size

    fun hasWebSocketClients(): Boolean = wsClients.isNotEmpty()

    fun hasMjpegClients(): Boolean = mjpegClients.isNotEmpty()

    /** @return 是否成功监听（端口被占用等会返回 false，原因见 [lastError]）。 */
    fun start(): Boolean {
        if (running) return true
        lastError = null

        val socket = try {
            ServerSocket()
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "创建 ServerSocket 失败", e)
            return false
        }

        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
            socket.soTimeout = 800
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "无法监听端口 $port", e)
            closeQuietly(socket)
            return false
        }

        serverSocket = socket
        running = true

        acceptThread = Thread({ acceptLoop(socket) }, "web-accept").apply {
            isDaemon = true
            start()
        }
        statusThread = Thread({ statusLoop() }, "web-status").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "浏览器输出已启动: http://0.0.0.0:$port/")
        return true
    }

    fun stop() {
        if (!running) return
        running = false

        for (client in wsClients) client.close()
        wsClients.clear()
        for (client in mjpegClients) client.close()
        mjpegClients.clear()

        closeQuietly(serverSocket)
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        statusThread?.interrupt()
        statusThread = null

        Log.i(TAG, "浏览器输出已停止")
    }

    /** 广播一帧已编码图像；两个通道都只保留最新帧。 */
    fun broadcast(frame: EncodedFrame) {
        val webp = frame.webp
        if (webp != null && wsClients.isNotEmpty()) {
            for (client in wsClients) client.offerFrame(webp)
        }
        val jpeg = frame.jpeg
        if (jpeg != null && mjpegClients.isNotEmpty()) {
            for (client in mjpegClients) client.offer(jpeg)
        }
    }

    // ---------------------------------------------------------------- accept

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept 失败: ${e.message}")
                break
            }

            // 简单的并发上限：正常情况下 OBS 只会开 2~3 条连接，超过上限直接拒绝，
            // 避免异常客户端把线程数撑爆。
            if (activeConnections.get() >= MAX_CONNECTIONS) {
                Log.w(TAG, "连接数超过上限 $MAX_CONNECTIONS，拒绝新连接")
                closeQuietly(client)
                continue
            }

            activeConnections.incrementAndGet()
            try {
                client.tcpNoDelay = true
                client.soTimeout = 15000
                Thread({ handleClient(client) }, "web-client").apply {
                    isDaemon = true
                    start()
                }
            } catch (t: Throwable) {
                activeConnections.decrementAndGet()
                Log.w(TAG, "创建连接线程失败: ${t.message}")
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
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

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
                socket.soTimeout = WS_IDLE_TIMEOUT_MS
                handoverToWebSocket = handleWebSocket(socket, input, output, headers)
                return
            }

            socket.soTimeout = 15000
            when {
                method != "GET" -> {
                    writeSimpleResponse(output, "405 Method Not Allowed", "text/plain; charset=utf-8", "Method Not Allowed")
                }

                path == "/" || path == "/index.html" -> servePage(output)

                path == "/status" -> serveStatus(output)

                path == "/stream.mjpg" -> {
                    socket.soTimeout = 0
                    streamMjpeg(socket, output)
                }

                path == "/favicon.ico" -> {
                    output.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }

                else -> {
                    writeSimpleResponse(output, "404 Not Found", "text/plain; charset=utf-8", "404 Not Found")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "连接异常: ${e.message}")
        } finally {
            if (!handoverToWebSocket) {
                closeQuietly(socket)
            }
        }
    }

    // -------------------------------------------------------------- websocket

    /**
     * 完成 WebSocket 握手并启动读写线程。
     * @return 是否已经把 socket 移交给 WebSocket 连接（false 表示握手失败，调用方负责关闭）。
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
            writeSimpleResponse(output, "503 Service Unavailable", "text/plain; charset=utf-8", "Too many clients")
            return false
        }

        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + WS_GUID).toByteArray(Charsets.ISO_8859_1)),
            Base64.NO_WRAP,
        )

        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        lateinit var connection: WsConnection
        connection = WsConnection(
            socket = socket,
            input = input,
            output = output,
            listener = object : WsConnection.Listener {
                override fun onText(text: String) {
                    if (text.contains("\"type\":\"ping\"") || text.contains("\"type\": \"ping\"")) {
                        connection.sendText("{\"type\":\"pong\"}")
                    }
                }

                override fun onClosed() {
                    wsClients.remove(connection)
                    Log.i(TAG, "WebSocket 客户端断开，剩余 ${wsClients.size}")
                }
            },
        )

        wsClients.add(connection)
        connection.start()
        Log.i(TAG, "WebSocket 客户端已连接，共 ${wsClients.size}")

        // 第一时间把状态发一份，HUD 不用等下一个心跳
        connection.sendText(buildStatusMessage())
        return true
    }

    private fun statusLoop() {
        var tick = 0
        while (running) {
            try {
                if (wsClients.isNotEmpty()) {
                    val message = buildStatusMessage()
                    for (client in wsClients) {
                        // sendText 只是写进「最新文本」槽位，不会阻塞在这里
                        client.sendText(message)
                        // 每 30s 由服务端发一个协议层 ping：浏览器网络层自动回 pong，
                        // 不依赖页面 JS 定时器（OBS 冻结/节流页面时也能保活）。
                        if (tick % 30 == 0) client.sendPing()
                    }
                }
                tick++
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                break
            } catch (t: Throwable) {
                Log.w(TAG, "状态推送异常: ${t.message}")
            }
        }
    }

    private fun buildStatusMessage(): String {
        val info = infoProvider()
        val fps = String.format(Locale.US, "%.1f", info.fps)
        return "{\"type\":\"status\",\"fps\":$fps,\"width\":${info.width},\"height\":${info.height}," +
            "\"model\":\"${escapeJson(info.model)}\",\"clients\":${wsClients.size}}"
    }

    // ------------------------------------------------------------------- http

    private fun servePage(output: OutputStream) {
        val bytes = pageBytes ?: loadPage()
        if (bytes == null) {
            writeSimpleResponse(output, "500 Internal Server Error", "text/plain; charset=utf-8", "web/index.html not found")
            return
        }

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun loadPage(): ByteArray? {
        return try {
            context.assets.open(PAGE_ASSET).use { it.readBytes() }.also { pageBytes = it }
        } catch (e: IOException) {
            Log.e(TAG, "读取 $PAGE_ASSET 失败", e)
            null
        }
    }

    private fun serveStatus(output: OutputStream) {
        val info = infoProvider()
        val body = buildString {
            append("{\"app\":\"VirtualFaceCapture\",\"running\":").append(running)
            append(",\"fps\":").append(String.format(Locale.US, "%.1f", info.fps))
            append(",\"width\":").append(info.width)
            append(",\"height\":").append(info.height)
            append(",\"model\":\"").append(escapeJson(info.model)).append('"')
            append(",\"wsClients\":").append(wsClients.size)
            append(",\"mjpegClients\":").append(mjpegClients.size)
            append('}')
        }
        writeSimpleResponse(output, "200 OK", "application/json; charset=utf-8", body)
    }

    private fun writeSimpleResponse(output: OutputStream, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
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
                buffer[length - 4] == '\r'.code.toByte() &&
                buffer[length - 3] == '\n'.code.toByte() &&
                buffer[length - 2] == '\r'.code.toByte() &&
                buffer[length - 1] == '\n'.code.toByte()
            ) {
                return String(buffer, 0, length - 4, Charsets.ISO_8859_1)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ mjpeg

    private fun streamMjpeg(socket: Socket, output: OutputStream) {
        val client = MjpegClient(socket, output)
        mjpegClients.add(client)
        Log.i(TAG, "MJPEG 客户端已连接，共 ${mjpegClients.size}")
        try {
            client.run()
        } finally {
            mjpegClients.remove(client)
            closeQuietly(socket)
            Log.i(TAG, "MJPEG 客户端断开，剩余 ${mjpegClients.size}")
        }
    }

    /**
     * MJPEG 客户端：与 WebSocket 客户端一样，只保留最新一帧。
     * 没有透明通道，胜在任何播放器（含 OBS 媒体源/浏览器源直链）都能直接吃。
     */
    private inner class MjpegClient(
        private val socket: Socket,
        private val output: OutputStream,
    ) {
        /** 容量 1 的最新帧槽位。 */
        private val pendingFrames = LinkedBlockingQueue<ByteArray>(1)

        @Volatile
        private var closed = false

        fun offer(jpeg: ByteArray) {
            if (closed) return
            if (!pendingFrames.offer(jpeg)) {
                pendingFrames.poll()
                pendingFrames.offer(jpeg)
            }
        }

        fun close() {
            closed = true
            pendingFrames.clear()
            closeQuietly(socket)
        }

        fun run() {
            try {
                output.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                        "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                output.flush()

                while (!closed) {
                    val frame = pendingFrames.poll(500, TimeUnit.MILLISECONDS) ?: continue

                    output.write(
                        ("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n")
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    output.write(frame)
                    output.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }
            } catch (e: InterruptedException) {
                // 正常退出
            } catch (t: Throwable) {
                Log.d(TAG, "MJPEG 客户端退出: ${t.message}")
            } finally {
                close()
            }
        }
    }

    // ----------------------------------------------------------------- helper

    private fun escapeJson(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch.code < 0x20) builder.append(' ') else builder.append(ch)
            }
        }
        return builder.toString()
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (ignored: IOException) {
        }
    }
}
