package com.example.virtualfacecapture.web

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import com.example.virtualfacecapture.web.WebOutputServer.StreamInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「浏览器输出」总控：起 HTTP/WebSocket 服务、限制抓帧节奏、编码、统计、屏幕常亮。
 *
 * 数据流：
 * ```
 * GL 线程 --glReadPixels--> 2 块 direct buffer（自节流）
 *        --> 编码线程（RGBA->Bitmap->WebP/JPEG）
 *        --> WebOutputServer（每个客户端只保留最新一帧）
 * ```
 *
 * UI 直接读这里的 Compose State 即可。
 */
class WebOutputManager(private val context: Context) : GlCaptureSink {

    companion object {
        private const val TAG = "WebOutputManager"

        /** 固定端口，方便 README / OBS 里直接抄地址。 */
        const val PORT = 8765

        private const val MAX_BUFFERS = 2
    }

    private data class NativeFrame(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
    )

    // ---- UI 状态 ----------------------------------------------------------
    val isRunning = mutableStateOf(false)
    val errorMessage = mutableStateOf<String?>(null)
    val clientCount = mutableStateOf(0)
    val outputFps = mutableStateOf(0f)
    val streamWidth = mutableStateOf(0)
    val streamHeight = mutableStateOf(0)
    val modelName = mutableStateOf("")

    /** 输出高度：360 / 480 / 640 / 720 / 1080。 */
    val captureHeight = mutableStateOf(640)

    /** 输出帧率上限。 */
    val targetFps = mutableStateOf(30)

    /** 编码质量（WebP/JPEG 共用）。 */
    val quality = mutableStateOf(75)

    // ---- 内部实现 ---------------------------------------------------------
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameQueue = LinkedBlockingQueue<NativeFrame>(1)

    private val poolLock = Any()
    private val freeBuffers = ArrayDeque<ByteBuffer>()
    private var poolFrameBytes = 0

    @Volatile
    private var server: WebOutputServer? = null
    private var encoderThread: Thread? = null
    private var encoderAlive: AtomicBoolean? = null

    @Volatile
    private var running = false

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val current = server
            clientCount.value = (current?.webSocketClientCount ?: 0) + (current?.mjpegClientCount ?: 0)
            mainHandler.postDelayed(this, 500)
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /** @return 是否启动成功；失败原因见 [errorMessage]。 */
    fun start(): Boolean {
        if (running) return true

        val newServer = WebOutputServer(context, PORT) {
            StreamInfo(
                fps = outputFps.value,
                width = streamWidth.value,
                height = streamHeight.value,
                model = modelName.value,
            )
        }

        if (!newServer.start()) {
            errorMessage.value = newServer.lastError ?: "无法监听端口 $PORT"
            return false
        }

        server = newServer
        running = true
        isRunning.value = true
        errorMessage.value = null
        clientCount.value = 0
        outputFps.value = 0f

        // 每次启动都用独立的编码器 + 停止标记，避免快速 stop/start 时旧线程还在跑
        val alive = AtomicBoolean(true)
        val encoder = FrameEncoder()
        encoderAlive = alive
        encoderThread = Thread({ encodeLoop(alive, encoder) }, "web-encoder").apply {
            isDaemon = true
            start()
        }
        mainHandler.post(statsRunnable)
        keepScreenOn(true)

        Log.i(TAG, "浏览器输出已开启: ${lanUrls().joinToString()}")
        return true
    }

    fun stop() {
        if (!running) {
            isRunning.value = false
            return
        }

        running = false
        isRunning.value = false

        mainHandler.removeCallbacks(statsRunnable)
        server?.stop()
        server = null

        encoderAlive?.set(false)
        encoderAlive = null
        encoderThread?.interrupt()
        encoderThread = null

        while (true) {
            val frame = frameQueue.poll() ?: break
            releaseBuffer(frame.buffer)
        }
        synchronized(poolLock) {
            freeBuffers.clear()
            poolFrameBytes = 0
        }

        clientCount.value = 0
        outputFps.value = 0f
        keepScreenOn(false)

        Log.i(TAG, "浏览器输出已关闭")
    }

    fun dispose() {
        stop()
    }

    // ------------------------------------------------------------- GlCaptureSink

    override fun isCaptureWanted(): Boolean {
        if (!running) return false
        val current = server ?: return false
        return current.hasWebSocketClients() || current.hasMjpegClients()
    }

    override fun requestedHeight(): Int = captureHeight.value

    override fun requestedFps(): Int = targetFps.value.coerceIn(1, 120)

    override fun acquireBuffer(width: Int, height: Int): ByteBuffer? {
        if (!running) return null
        val size = width * height * 4
        synchronized(poolLock) {
            if (poolFrameBytes != size) {
                freeBuffers.clear()
                poolFrameBytes = size
                repeat(MAX_BUFFERS) {
                    freeBuffers.addLast(
                        ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
                    )
                }
            }
            return freeBuffers.pollFirst()
        }
    }

    override fun submitFrame(buffer: ByteBuffer, width: Int, height: Int) {
        if (!running) {
            releaseBuffer(buffer)
            return
        }
        val accepted = frameQueue.offer(NativeFrame(buffer, width, height))
        if (!accepted) {
            releaseBuffer(buffer)
        }
        if (streamWidth.value != width || streamHeight.value != height) {
            mainHandler.post {
                streamWidth.value = width
                streamHeight.value = height
            }
        }
    }

    // ------------------------------------------------------------------ 编码循环

    private fun encodeLoop(alive: AtomicBoolean, encoder: FrameEncoder) {
        var frames = 0
        var windowStart = System.currentTimeMillis()

        try {
            while (alive.get()) {
                val frame = try {
                    frameQueue.take()
                } catch (e: InterruptedException) {
                    break
                }

                try {
                    val current = server
                    val needWebp = current?.hasWebSocketClients() == true
                    val needJpeg = current?.hasMjpegClients() == true
                    if (current != null && (needWebp || needJpeg)) {
                        val encoded = encoder.encode(
                            rgba = frame.buffer,
                            width = frame.width,
                            height = frame.height,
                            needWebp = needWebp,
                            needJpeg = needJpeg,
                            quality = quality.value,
                        )
                        if (encoded.webp != null || encoded.jpeg != null) {
                            current.broadcast(encoded)
                            frames++
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "编码失败", t)
                } finally {
                    releaseBuffer(frame.buffer)
                }

                val now = System.currentTimeMillis()
                val elapsed = now - windowStart
                if (elapsed >= 1000) {
                    val fps = frames * 1000f / elapsed
                    frames = 0
                    windowStart = now
                    mainHandler.post { outputFps.value = fps }
                }
            }
        } finally {
            encoder.release()
        }
    }

    private fun releaseBuffer(buffer: ByteBuffer) {
        synchronized(poolLock) {
            if (freeBuffers.size < MAX_BUFFERS && buffer.capacity() == poolFrameBytes) {
                freeBuffers.addLast(buffer)
            }
        }
    }

    // ------------------------------------------------------------------ 地址/杂项

    /** 供 UI / README 使用的访问地址（可能多个，例如同时开 Wi-Fi 和热点）。 */
    fun lanUrls(): List<String> = lanAddresses().map { "http://$it:$PORT/" }

    fun lanAddresses(): List<String> {
        val result = ArrayList<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (address in networkInterface.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress ?: continue
                        if (host.startsWith("169.254.")) continue
                        if (!result.contains(host)) result.add(host)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "枚举局域网地址失败: ${t.message}")
        }
        return result
    }

    private fun keepScreenOn(enabled: Boolean) {
        val activity = context as? Activity ?: return
        try {
            if (enabled) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "设置屏幕常亮失败: ${t.message}")
        }
    }
}
