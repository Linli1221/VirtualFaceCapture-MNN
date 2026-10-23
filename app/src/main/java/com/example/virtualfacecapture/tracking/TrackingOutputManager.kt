package com.example.virtualfacecapture.tracking

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.vfc.log.VfcLog as Log
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import com.example.commondata.ARKitBlendShapes
import com.example.commondata.HeadPoseData
import com.example.commondata.UpperBodyPoseFrame
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt

/**
 * 「网络输出」总控：启停追踪服务、把每帧追踪数据喂给 [TrackingServer]、给 UI 提供状态。
 *
 * 与旧的 WebOutputManager（编码 + 推 WebP 帧）的区别：
 *  - 没有任何图像编码 / GL 抓帧，手机端零编码开销；
 *  - 一帧只做一次 JSON 序列化（不做图像），慢客户端只会丢最旧的 track；
 *  - 发送缓冲是**双模式**的（协议 §7.1，配置 key `send.bufferMs`）：
 *    500 ms 保守模式 = 先攒满再匀速放行，把推理耗时抖动 / Wi-Fi 突发抹平成均匀帧间隔，
 *    代价是端到端延迟 +0.5 s；0 ms 低延迟模式 = 算完即发，抖动缓冲责任移交 PC 侧
 *    （需 PC 声明 jitterBufferV1，见 docs/jitter-buffer.md），协商不通过时自动回落保守模式。
 *
 * UI 直接读这里的 Compose State 即可。
 */
class TrackingOutputManager(
    private val context: Context,
    /**
     * 全局 upperBodyPostV1 协商结果变化时的通知回调（任务 M2，照 [onRecalibrate] 的接线
     * 模式）：true = 全体客户端都声明支持「上半身后处理迁 PC」，检测器切原始输出
     * （[com.example.mediapipeupperbody.UpperBodyPoseDetector.rawOutput]）；false = 回 M1 旧链。
     */
    private val onUpperBodyPostV1: (Boolean) -> Unit = {},
    private val onRecalibrate: () -> Unit = {},
) {

    companion object {
        private const val TAG = "TrackingOutputManager"
        const val PORT = TrackingProtocol.PORT

        /** 发送缓冲配置的 SharedPreferences key（协议 §7.1 的 `send.bufferMs`）。 */
        const val PREF_SEND_BUFFER_MS = "send.bufferMs"

        /** 与相机选择共用的应用 prefs 文件（MainActivity 的 CAMERA_PREFS_NAME）。 */
        private const val PREFS_NAME = "face_capture_prefs"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 发送缓冲配置 `send.bufferMs`（毫秒）：500 = 保守匀速；0 = 低延迟（算完即发，
     * 需 PC 支持 jitterBufferV1）。UI 持久化走 [setSendBufferMs]。
     */
    val sendBufferMs = mutableStateOf(
        TrackingProtocol.normalizeSendBufferMs(
            prefs.getLong(PREF_SEND_BUFFER_MS, TrackingProtocol.FRAME_BUFFER_MS)
        )
    )

    /** 当前**生效**发送缓冲（0 = 旁路模式）：协商 / 回落之后的真实值，运行中由 stats 轮询刷新。 */
    val effectiveBufferMs = mutableStateOf(sendBufferMs.value)

    // ---- UI 状态 ----------------------------------------------------------

    /** 服务是否在监听。 */
    val isRunning = mutableStateOf(false)

    /** 启动失败原因（端口被占用等）。 */
    val errorMessage = mutableStateOf<String?>(null)

    /** 当前 WebSocket 客户端数。 */
    val clientCount = mutableStateOf(0)

    /** 最近一秒实际发出的 track 帧率。 */
    val outputFps = mutableStateOf(0f)

    /** 最新帧序号（发过的帧数）。 */
    val latestSeq = mutableStateOf(0L)

    // ---- 内部实现 ---------------------------------------------------------

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var server: TrackingServer? = null

    /** 面捕管线是否在跑（/status 的 tracking 字段），服务没起来时也要记住。 */
    @Volatile
    private var trackingFlag = false

    /** 上半身采集是否启用；服务没起来时也要记住，[start] 时再应用。 */
    @Volatile
    private var upperBodyFlag = false

    /**
     * 全局 upperBodyPostV1 协商结果（任务 M2）：true = 全体客户端都声明了
     * [TrackingProtocol.FEATURE_UPPER_BODY_POST_V1]，track 带 `ubRaw` / 原始 body。
     * 只读快照（HUD / 日志用），由 [TrackingServer] 的协商聚合回调更新。
     */
    @Volatile
    var upperBodyPostV1: Boolean = false
        private set

    private val deviceName: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    private val appName: String = buildString {
        append("VirtualFaceCapture/")
        append(
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
            } catch (e: PackageManager.NameNotFoundException) {
                "1.0"
            }
        )
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val current = server
            clientCount.value = current?.clientCount ?: 0
            outputFps.value = current?.sentFps ?: 0f
            latestSeq.value = current?.latestSeq ?: 0L
            // 生效缓冲会随 hello 协商 / 客户端进出在 0 与非 0 之间变，UI 显示真实值
            effectiveBufferMs.value = current?.effectiveBufferMs ?: sendBufferMs.value
            mainHandler.postDelayed(this, 500L)
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /** @return 是否启动成功；失败原因见 [errorMessage]。 */
    fun start(): Boolean {
        if (running) return true

        val newServer = TrackingServer(
            port = PORT,
            deviceName = deviceName,
            appName = appName,
            onRecalibrate = onRecalibrate,
            // 任务 M2：协商聚合（全体客户端 upperBodyPostV1）→ 记录状态 → 通知上层切检测器
            onUpperBodyPostV1 = { enabled ->
                upperBodyPostV1 = enabled
                onUpperBodyPostV1(enabled)
            },
            frameBufferMs = sendBufferMs.value,
        )
        newServer.trackingActive = trackingFlag
        newServer.setUpperBodyEnabled(upperBodyFlag)

        if (!newServer.start()) {
            errorMessage.value = newServer.lastError ?: ("无法监听端口 " + PORT)
            return false
        }

        server = newServer
        running = true
        isRunning.value = true
        errorMessage.value = null
        clientCount.value = 0
        outputFps.value = 0f
        latestSeq.value = 0L
        effectiveBufferMs.value = newServer.effectiveBufferMs

        mainHandler.post(statsRunnable)
        keepScreenOn(true)

        Log.i(TAG, "追踪服务已开启: " + wsUrls().joinToString())
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

        // 停止服务时要给客户端发 bye 并关闭 socket（最多等 150ms），
        // 不能放在主线程做，否则点「停止服务」会卡一下 UI。
        val stopping = server
        server = null
        if (stopping != null) {
            Thread({ stopping.stop() }, "tracking-stop").apply { isDaemon = true }.start()
        }

        clientCount.value = 0
        outputFps.value = 0f
        keepScreenOn(false)

        Log.i(TAG, "追踪服务已关闭")
    }

    fun dispose() {
        stop()
    }

    // ---------------------------------------------------------------- 数据入口

    /** 同步面捕管线是否在跑（给 /status 的 tracking 字段用）。 */
    fun setTrackingActive(active: Boolean) {
        trackingFlag = active
        server?.trackingActive = active
    }

    /**
     * 开启 / 关闭上半身数据（协议 §4.1 的 hello.upperBody / §4.2 的 ub* 字段）。
     *
     * 服务没启动时只记住状态，[start] 时再应用；不需要等下一次连接才生效。
     */
    fun setUpperBodyEnabled(enabled: Boolean) {
        upperBodyFlag = enabled
        server?.setUpperBodyEnabled(enabled)
    }

    /**
     * 修改发送缓冲配置 `send.bufferMs`（协议 §7.1）：立即持久化到 SharedPreferences，
     * 运行中的服务立即生效（[TrackingServer.setSendBufferMs]），下次启动沿用。
     *
     * 传入非法值时由 [TrackingProtocol.normalizeSendBufferMs] 归一化（坏值 = 回落 500）；
     * UI 只提供 0（低延迟）/ 500（保守）两档，配置本身允许 100~2000（直接改 prefs 即可）。
     */
    fun setSendBufferMs(valueMs: Long) {
        val normalized = TrackingProtocol.normalizeSendBufferMs(valueMs)
        sendBufferMs.value = normalized
        prefs.edit().putLong(PREF_SEND_BUFFER_MS, normalized).apply()
        server?.setSendBufferMs(normalized)
        effectiveBufferMs.value = server?.effectiveBufferMs ?: normalized
        Log.i(TAG, "send.bufferMs 已设为 " + normalized + " ms")
    }

    /**
     * 把最新一帧上半身关键点交给服务（约 15 Hz 调用）。
     *
     * 这里只做一次判空 + 引用赋值：JSON 序列化在面捕帧线程的 [submitFrame] 里统一完成，
     * 上半身回调线程（MediaPipe）绝不做重活。
     */
    fun submitUpperBody(frame: UpperBodyPoseFrame) {
        if (!running) return
        server?.submitUpperBodySnapshot(frame)
    }

    /**
     * 每处理完一帧调用一次（协议 §4.2）。
     *
     * 服务没开时这里只做一次 volatile 判空就直接返回，不产生任何额外开销。
     *
     * @param headPose 必须是相对姿态（已减首帧基线），即 pose 字段。
     * @param presenceScore 人脸存在置信度 [0,1]。
     */
    fun submitFrame(
        blendShapes: ARKitBlendShapes,
        headPose: HeadPoseData,
        presenceScore: Float,
        fps: Float,
        calibrating: Boolean,
    ) {
        if (!running) return
        val current = server ?: return
        // hello 里的 targetFps 用实际处理帧率上报（协议 §4.1）
        val target = fps.roundToInt().coerceIn(1, 120)
        if (target != current.targetFps) current.targetFps = target
        current.submitTrack(blendShapes, headPose, presenceScore, fps, calibrating)
    }

    // ---------------------------------------------------------------- 地址/杂项

    /** WebSocket 连接地址（PC 客户端要填的那个）。 */
    fun wsUrls(): List<String> = lanAddresses().map { "ws://" + it + ":" + PORT + TrackingProtocol.PATH_WS }

    /** 信息页地址（浏览器打开确认服务在线）。 */
    fun httpUrls(): List<String> = lanAddresses().map { "http://" + it + ":" + PORT + TrackingProtocol.PATH_INFO }

    /** 枚举局域网 IPv4 地址（Wi-Fi / 热点可能同时存在多个）。 */
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
            Log.w(TAG, "枚举局域网地址失败: " + t.message)
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
            Log.w(TAG, "设置屏幕常亮失败: " + t.message)
        }
    }
}
