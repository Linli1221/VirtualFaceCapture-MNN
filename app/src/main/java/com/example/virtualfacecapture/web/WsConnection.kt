package com.example.virtualfacecapture.web

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 极简 WebSocket 服务端连接（RFC 6455 子集）。
 *
 * OBS 的「浏览器源」本质上就是一个 Chromium，只能通过网页 + WebSocket 拿到实时画面，
 * 所以这里手写一个不依赖第三方库的最小子集：
 *  - 发送：二进制帧（图像）/ 文本帧（状态 JSON）/ pong / close
 *  - 接收：文本、分片、ping、pong、close（客户端通常只发这些）
 *
 * 线程模型：
 *  - reader 线程：解析客户端帧、回应 ping/close
 *  - writer 线程：只保留「最新一帧」，编码/网络跟不上时自动丢旧帧，保证低延迟
 */
internal class WsConnection(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: Listener,
) {

    interface Listener {
        /** 收到客户端文本消息（页面会发 {"type":"ping"} 做保活）。 */
        fun onText(text: String)

        /** 连接已关闭（只会回调一次）。 */
        fun onClosed()
    }

    companion object {
        private const val TAG = "WsConnection"

        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        /** 客户端协议上行的最大帧，防止恶意/异常数据把内存打爆。 */
        private const val MAX_INCOMING_FRAME = 1024 * 1024

        private val EMPTY_PAYLOAD = ByteArray(0)
    }

    /** 容量 1 的「最新帧」槽位：写入时覆盖旧帧，读走即空。 */
    private val pendingFrames = LinkedBlockingQueue<ByteArray>(1)

    /** 状态文本也是「最新覆盖」，由 writer 线程统一发送，statusLoop 永不阻塞。 */
    private val pendingText = AtomicReference<String?>(null)

    /** 待回给对端的 pong（对端发协议层 ping 时）。 */
    private val pendingPong = AtomicReference<ByteArray?>(null)

    /** 服务端主动发起的协议层 ping（不依赖页面 JS 定时器，保活用）。 */
    private val pendingPing = AtomicBoolean(false)

    @Volatile
    private var closed = false

    private val readerThread = Thread({ readLoop() }, "ws-reader")
    private val writerThread = Thread({ writeLoop() }, "ws-writer")

    fun start() {
        readerThread.isDaemon = true
        writerThread.isDaemon = true
        writerThread.start()
        readerThread.start()
    }

    /** 投递一帧图像；上一帧还没发出去就直接覆盖（保最新帧）。 */
    fun offerFrame(frame: ByteArray) {
        if (closed) return
        if (!pendingFrames.offer(frame)) {
            pendingFrames.poll()
            pendingFrames.offer(frame)
        }
    }

    fun sendText(text: String) {
        if (text.isEmpty() || closed) return
        pendingText.set(text)
    }

    /** 让 writer 线程发一个协议层 ping（浏览器会自动回 pong，不依赖页面脚本）。 */
    fun sendPing() {
        if (closed) return
        pendingPing.set(true)
    }

    /** 只有 writer 线程会调用，所以不需要锁。 */
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
                if (pendingPing.getAndSet(false)) {
                    sendFrame(OP_PING, EMPTY_PAYLOAD)
                }
                pendingPong.getAndSet(null)?.let { sendFrame(OP_PONG, it) }
                pendingText.getAndSet(null)?.let {
                    sendFrame(OP_TEXT, it.toByteArray(Charsets.UTF_8))
                }
                val frame = pendingFrames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                sendFrame(OP_BINARY, frame)
            }
        } catch (e: InterruptedException) {
            // 正常退出
        } catch (t: Throwable) {
            Log.w(TAG, "writer loop exit: ${t.message}")
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
            // 对端断开/服务停止，均属正常
        } catch (t: Throwable) {
            Log.w(TAG, "reader loop exit: ${t.message}")
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
        var firstTime = false
        synchronized(this) {
            if (!closed) {
                closed = true
                firstTime = true
            }
        }
        if (!firstTime) return

        pendingFrames.clear()
        pendingText.set(null)
        pendingPong.set(null)
        pendingPing.set(false)
        try {
            socket.close()
        } catch (ignored: IOException) {
        }
        listener.onClosed()
    }
}
