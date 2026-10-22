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
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * 实际抓帧高度。开着 [autoScale] 时它可能低于 [captureHeight]：
     * 编码跟不上目标帧率就自动降一档，负载下来再升回去。
     */
    val effectiveHeight = mutableStateOf(640)

    /** 编码跟不上目标帧率时是否自动降低输出高度（默认开）。 */
    val autoScale = mutableStateOf(true)

    /** 最近的平均单帧编码耗时（ms），给 UI 看，也用来判断要不要降档。 */
    val encodeMs = mutableStateOf(0f)

    /** 实际抓帧帧率（每秒提交给编码线程的帧数），用来区分「抓不上来」和「编码太慢」。 */
    val captureFps = mutableStateOf(0f)

    // ---- 内部实现 ---------------------------------------------------------
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameQueue = LinkedBlockingQueue<NativeFrame>(1)

    private val poolLock = Any()
    private val freeBuffers = ArrayDeque<ByteBuffer>()
    private var poolFrameBytes = 0

    /** GL 线程每秒提交的帧数（只在两个线程各自读写一次，够用）。 */
    private val capturedFrames = AtomicInteger()

    /** 单帧编码耗时的指数滑动平均（ms）。编码线程写、主线程读。 */
    @Volatile
    private var encodeCostMs = 0.0

    /** 用户上一次设置的目标高度，用来发现滑块被拖动。 */
    @Volatile
    private var lastDesiredHeight = 0

    /** 连续「预算有余量」的统计窗口数，升档要先攒够两次，避免来回抖。 */
    @Volatile
    private var smoothWindows = 0

    /** 输出高度档位，与对话框里的滑块一致。 */
    private val heightLadder = intArrayOf(360, 480, 640, 720, 1080)

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
            // 用户拖了高度滑块、或者关掉了自动降级：立刻让实际抓帧高度跟随设置
            val desired = captureHeight.value
            if (desired != lastDesiredHeight) {
                lastDesiredHeight = desired
                encodeCostMs = 0.0
                smoothWindows = 0
                if (effectiveHeight.value != desired) {
                    effectiveHeight.value = desired
                }
            } else if (!autoScale.value && effectiveHeight.value != desired) {
                // 关掉自动降级：把被降下去的高度立刻补回用户设定值
                effectiveHeight.value = desired
            }
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

        // 每次启动都从用户设定的高度开始，统计也清零
        effectiveHeight.value = captureHeight.value
        lastDesiredHeight = captureHeight.value
        encodeCostMs = 0.0
        smoothWindows = 0
        encodeMs.value = 0f
        captureFps.value = 0f
        capturedFrames.set(0)

        // 每次启动都用独立的编码器 + 停止标记，避免快速 stop/start 时旧线程还在跑
        val alive = AtomicBoolean(true)
        val encoder = FrameEncoder()
        encoderAlive = alive
        encoderThread = Thread({
            // 编码线程和面捕流水线一视同仁地提一点优先级：
            // 默认优先级下，相机/MNN 那些 +2 的线程会把编码线程挤到小核上，掉帧非常明显
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_DEFAULT + 2
                )
            } catch (t: Throwable) {
                Log.w(TAG, "设置编码线程优先级失败: ${t.message}")
            }
            encodeLoop(alive, encoder)
        }, "web-encoder").apply {
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

    override fun requestedHeight(): Int =
        if (autoScale.value) effectiveHeight.value else captureHeight.value

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
        capturedFrames.incrementAndGet()
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
                        val startedNanos = System.nanoTime()
                        val encoded = encoder.encode(
                            rgba = frame.buffer,
                            width = frame.width,
                            height = frame.height,
                            needWebp = needWebp,
                            needJpeg = needJpeg,
                            quality = quality.value,
                        )
                        val costMs = (System.nanoTime() - startedNanos) / 1_000_000.0
                        encodeCostMs = if (encodeCostMs <= 0.0) {
                            costMs
                        } else {
                            encodeCostMs * 0.7 + costMs * 0.3
                        }
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
                    val captured = capturedFrames.getAndSet(0) * 1000f / elapsed
                    val cost = encodeCostMs
                    frames = 0
                    windowStart = now
                    mainHandler.post {
                        outputFps.value = fps
                        captureFps.value = captured
                        encodeMs.value = cost.toFloat()
                    }
                    if (autoScale.value) {
                        maybeAdjustHeight(cost)
                    }
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

    /**
     * 保帧率：编码耗时超过目标帧率预算的 85% 就降一档输出高度；
     * 升档要看「投影成本」——把当前耗时按像素数换算到下一档，估算出来仍低于预算的
     * 70% 才升，并且要连续两个统计窗口都满足。
     *
     * 只看当前耗时的话，降档后一定有富余，下一秒又升回去，画面会在两档之间反复糊/清。
     */
    private fun maybeAdjustHeight(costMs: Double) {
        if (costMs <= 0.0) return
        val budget = 1000.0 / targetFps.value.coerceIn(1, 120)
        val desired = captureHeight.value
        val current = minOf(effectiveHeight.value, desired)

        if (costMs > budget * 0.85) {
            val lower = heightLadder.lastOrNull { it < current } ?: current
            if (lower != current) {
                smoothWindows = 0
                Log.i(
                    TAG,
                    "编码 %.1fms 超出 %.0ffps 的预算，输出高度降到 %dp"
                        .format(costMs, 1000.0 / budget, lower),
                )
                mainHandler.post { effectiveHeight.value = lower }
            }
            return
        }

        val higher = heightLadder.firstOrNull { it > current && it <= desired }
        if (higher == null) {
            smoothWindows = 0
            return
        }

        val ratio = higher.toDouble() / current
        val projected = costMs * ratio * ratio
        if (projected < budget * 0.7) {
            smoothWindows++
            if (smoothWindows < 2) return
            smoothWindows = 0
            Log.i(
                TAG,
                "编码 %.1fms（升档预估 %.1fms）预算有余量，输出高度升到 %dp"
                    .format(costMs, projected, higher),
            )
            mainHandler.post { effectiveHeight.value = higher }
        } else {
            smoothWindows = 0
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
