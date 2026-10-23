package com.example.virtualfacecapture.tracking

import com.example.commondata.ARKitBlendShapes
import com.example.commondata.BodyPoint
import com.example.commondata.UpperBodyLandmarkIndex
import com.example.commondata.UpperBodyPoseFrame

/**
 * VFC Tracking Protocol v1 —— 报文的序列化 / 解析部分。
 *
 * 唯一契约：docs/tracking-protocol.md。字段名、索引顺序、符号约定都以此为准。
 *
 * 这个对象刻意**不依赖任何 Android API**（不 import android.*），因此可以在本地 JVM
 * 单元测试（TrackingProtocolTest）里直接跑，不需要 Robolectric / 真机。
 *
 * 性能：所有报文都用 [StringBuilder] 手写（协议 §9 要求每帧序列化 < 0.3ms），
 * 不用反射式 JSON 库；浮点统一最多 4 位小数并去掉无意义的尾随 0。
 */
object TrackingProtocol {

    /** 协议名，hello / status 里固定为这个值。 */
    const val PROTOCOL = "vfc-tracker"

    /** 协议版本。改动语义时 +1，并同步更新 docs/tracking-protocol.md。 */
    const val VERSION = 1

    /** 固定端口。 */
    const val PORT = 8765

    /** ARKit-52 的长度。 */
    const val BS_COUNT = 52

    /** 上半身协议点数（13，必须与 [UpperBodyLandmarkIndex.COUNT] 一致）。 */
    const val UB_COUNT = UpperBodyLandmarkIndex.COUNT

    /** 信息页。 */
    const val PATH_INFO = "/"

    /** JSON 状态。 */
    const val PATH_STATUS = "/status"

    /** 追踪数据长连接。 */
    const val PATH_WS = "/ws"

    /** 每处理 N 帧发 1 帧；1 = 全量。 */
    const val DEFAULT_DECIMATION = 1

    /**
     * 手机端发送帧缓冲时长（毫秒）：追踪帧先攒够这段时间再匀速发给 PC。
     *
     * 「算完一帧就发一帧」会把推理耗时抖动、系统调度、Wi-Fi 突发原样传给客户端，
     * PC 端的 Live2D 看起来就是一卡一卡。固定 0.5 s 的缓冲能把输出帧间隔抹平，
     * 代价是端到端延迟固定多 0.5 s（协议 §7.1 / §9）。
     *
     * 这个常量现在是 `send.bufferMs` 配置的**默认值与坏值回落值**（见
     * [normalizeSendBufferMs]）；用户可以把配置改成 0（算完即发，但需 PC 声明
     * [FEATURE_JITTER_BUFFER_V1]，见 [resolveEffectiveBufferMs]）或
     * [SEND_BUFFER_MIN_MS] ~ [SEND_BUFFER_MAX_MS] 之间的值。
     */
    const val FRAME_BUFFER_MS = 500L

    /**
     * 手机端发送缓冲配置 `send.bufferMs` 的合法区间（毫秒）。
     *
     * 合法值只有 0（关闭缓冲，算完即发）或 [SEND_BUFFER_MIN_MS] ~ [SEND_BUFFER_MAX_MS]；
     * 其它任何值（负数、1~99、超过上限、SharedPreferences 里被写坏的值）一律回落
     * [FRAME_BUFFER_MS]，保证「坏配置 = 保守模式」而不是「坏配置 = 悄悄变成低延迟」。
     */
    const val SEND_BUFFER_MIN_MS = 100L
    const val SEND_BUFFER_MAX_MS = 2000L

    /**
     * 客户端能力标记：PC 侧实现了自适应抖动缓冲（jitterBufferV1）。
     *
     * 只有当**所有**已连接客户端都在 hello.features 里声明了它，手机才被允许把
     * `send.bufferMs` 配成 0（延迟责任移交给 PC，见 §7.1 与 docs/jitter-buffer.md）。
     */
    const val FEATURE_JITTER_BUFFER_V1 = "jitterBufferV1"

    /**
     * 客户端能力标记：PC 侧实现了上半身后处理（One-Euro / 躯干角 / TorsoBaseline，任务 M2）。
     *
     * PC 在 hello.features 里声明它（或在顶层带 `"upperBodyPostV1":true` 布尔兜底）后，
     * 手机才被允许把该连接标记为「已协商」，track 里才可能出现 `ubRaw` / `ubRawBody`
     * 与「未平滑、未减基线」语义的 `body`（见 [buildTrack]）。
     */
    const val FEATURE_UPPER_BODY_POST_V1 = "upperBodyPostV1"

    /** 唯一的 command 名，等价于 UI 上的 Reset Baseline。 */
    const val COMMAND_RECALIBRATE = "recalibrate"

    private const val QUOTE = '"'
    private const val COLON = ':'
    private const val BACKSLASH_CODE = 0x5C
    private const val QUOTE_CODE = 0x22

    private val POW10 = intArrayOf(1, 10, 100, 1000, 10000)

    // ------------------------------------------------------------------ 数值

    /**
     * 把一个浮点数按协议要求写进 builder：最多 4 位小数，去掉尾随 0。
     *
     * 例：0.12345f -> 0.1235，0.5f -> 0.5，-0.00004f -> 0，59.1f -> 59.1。
     * NaN / Infinity 一律写 0（JSON 不允许这两个字面量，否则客户端 parse 会失败）。
     */
    fun appendDecimal4(builder: StringBuilder, value: Float) {
        if (value.isNaN() || value.isInfinite()) {
            builder.append('0')
            return
        }
        val negative = value < 0f
        // 用 Double 做四舍五入，避开 Float 的二进制尾巴（0.1f * 10000 可能是 999.99994）
        val scaled = Math.round(Math.abs(value.toDouble()) * 10000.0)
        if (scaled == 0L) {
            // -0.0 / 极小数归一成 0，避免出现 "-0"
            builder.append('0')
            return
        }
        if (negative) builder.append('-')
        builder.append(scaled / 10000L)
        var fraction = (scaled % 10000L).toInt()
        if (fraction == 0) return

        var digits = 4
        while (digits > 1 && fraction % 10 == 0) {
            fraction /= 10
            digits--
        }
        builder.append('.')
        for (index in 0 until digits) {
            builder.append(('0' + ((fraction / POW10[digits - 1 - index]) % 10)))
        }
    }

    /** 同上，返回字符串（低频调用 / 单元测试用；热路径请用 StringBuilder 版本）。 */
    fun decimal4(value: Float): String {
        val builder = StringBuilder(8)
        appendDecimal4(builder, value)
        return builder.toString()
    }

    /**
     * 上半身数值清洗：先判断有限性再裁剪。
     *
     * 不能用 `coerceIn` 直接兜底 —— 它会把 ±Infinity 截到边界值（+Inf -> max），
     * 而协议要求 NaN / Infinity 一律写 0。
     */
    private fun sanitized(value: Float, min: Float, max: Float): Float =
        if (value.isFinite()) value.coerceIn(min, max) else 0f

    /**
     * 写一个上半身关键点的 `x,y,z,visibility`（协议 §5.3）。
     *
     * 范围按协议裁剪：x/y ∈ [0,1]、z ∈ [-5,5]、visibility ∈ [0,1]；
     * NaN / Infinity 一律写 0，保证 JSON 合法、客户端不会 parse 失败。
     */
    private fun appendUpperBodyPoint(builder: StringBuilder, point: BodyPoint) {
        appendDecimal4(builder, sanitized(point.x, 0f, 1f))
        builder.append(',')
        appendDecimal4(builder, sanitized(point.y, 0f, 1f))
        builder.append(',')
        appendDecimal4(builder, sanitized(point.z, -5f, 5f))
        builder.append(',')
        appendDecimal4(builder, sanitized(point.visibility, 0f, 1f))
    }

    /** `ub` 结构保持稳定的占位：[UB_COUNT] × 4 个 0（`ubOk=false` 时使用）。 */
    private fun appendUpperBodyZeros(builder: StringBuilder) {
        for (index in 0 until UB_COUNT * 4) {
            if (index > 0) builder.append(',')
            builder.append('0')
        }
    }

    // ------------------------------------------------------------------ 转义

    /** 最小 JSON 字符串转义（设备名 / 原因里可能有引号、反斜杠、控制字符）。 */
    fun escape(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch.code) {
                QUOTE_CODE -> builder.append(92.toChar()).append(QUOTE)
                BACKSLASH_CODE -> builder.append(92.toChar()).append(92.toChar())
                0x0A -> builder.append(92.toChar()).append('n')
                0x0D -> builder.append(92.toChar()).append('r')
                0x09 -> builder.append(92.toChar()).append('t')
                else -> if (ch.code < 0x20) builder.append(' ') else builder.append(ch)
            }
        }
        return builder.toString()
    }

    // ------------------------------------------------------------- JSON 片段

    private fun StringBuilder.jsonKey(name: String, first: Boolean = false): StringBuilder {
        append(if (first) '{' else ',').append(QUOTE).append(name).append(QUOTE).append(COLON)
        return this
    }

    private fun StringBuilder.jsonString(name: String, value: String, first: Boolean = false): StringBuilder {
        jsonKey(name, first).append(QUOTE).append(escape(value)).append(QUOTE)
        return this
    }

    private fun StringBuilder.jsonLong(name: String, value: Long, first: Boolean = false): StringBuilder {
        jsonKey(name, first).append(value)
        return this
    }

    private fun StringBuilder.jsonBool(name: String, value: Boolean, first: Boolean = false): StringBuilder {
        jsonKey(name, first).append(if (value) "true" else "false")
        return this
    }

    private fun StringBuilder.jsonFloat(name: String, value: Float, first: Boolean = false): StringBuilder {
        jsonKey(name, first)
        appendDecimal4(this, value)
        return this
    }

    // ------------------------------------------------------------- 消息构造

    /**
     * §4.1 hello：连接建立后立即发送一次。
     *
     * @param upperBody 手机端上半身采集是否启用；true 时追加可选字段 `"upperBody":true`，
     *   关闭时不出现该字段（老客户端忽略未知字段）。
     * @param upperBodyPostV1 能力声明（任务 M2）：手机端上半身启用且后处理可迁 PC 时传 true，
     *   追加可选字段 `"upperBodyPostV1":true`；默认 false 时不出现该字段，报文与 M1 逐字节一致。
     */
    fun buildHello(
        device: String,
        app: String,
        decimation: Int = DEFAULT_DECIMATION,
        targetFps: Int = 60,
        upperBody: Boolean = false,
        upperBodyPostV1: Boolean = false,
        timestampMs: Long = System.currentTimeMillis(),
    ): String {
        val builder = StringBuilder(160)
        builder.jsonString("type", "hello", first = true)
        builder.jsonString("protocol", PROTOCOL)
        builder.jsonLong("version", VERSION.toLong())
        builder.jsonString("device", device)
        builder.jsonString("app", app)
        builder.jsonLong("decimation", decimation.coerceAtLeast(1).toLong())
        builder.jsonLong("targetFps", targetFps.coerceIn(1, 240).toLong())
        // §4.1 可选字段：只在手机端开启上半身采集时下发 true；关闭时字段不出现（保持报文精简）。
        if (upperBody) builder.jsonBool("upperBody", true)
        // 任务 M2 可选能力声明：默认不发，老客户端/老链路的报文逐字节不变。
        if (upperBodyPostV1) builder.jsonBool("upperBodyPostV1", true)
        builder.jsonLong("t", timestampMs)
        builder.append('}')
        return builder.toString()
    }

    /**
     * §4.2 track：每帧一条。bs 必须是 §5.1 的 52 项顺序，pose 为 [pitch, yaw, roll]（度）。
     *
     * @param face 本帧是否检测到人脸；false 时调用方应沿用上一帧的 bs / pose。
     * @param upperBody 可选上半身帧；null = 未开启，报文里**不出现** ubOk / ub / ubT / body。
     * @param upperBodyOk 本帧上半身结果是否可用；false 时仍发 ubOk:false、ub 与 body 全 0
     *   （结构稳定，客户端据 ubOk 回默认姿态）。
     * @param upperBodyPostV1 本连接（广播场景下 = 全体客户端聚合）是否已协商 upperBodyPostV1
     *   （任务 M2）。默认 false：输出与改动前**逐字节一致**。
     *
     * **ubRaw 语义（任务 M2，`raw` 判定 = `upperBodyPostV1 && upperBody.rawMode`）**：
     *  - `raw` 判定为真时在 `body` 之后追加 `"ubRaw":true` 与 `"ubRawBody":[roll,yaw]`；
     *    `ubRaw:true` 声明本帧 `ub` 是**未做 One-Euro、未减基线的原始点**
     *    （x/y 已镜像、z 仍为相对深度）。
     *  - 此时 `body` = **原始躯干角（未平滑、未减基线）**，与 `ubRawBody` 同值同义
     *    （`ubRawBody` 是显式命名的别名，供客户端按名取角；两者一致，若不一致以 `ubRawBody` 为准）。
     *  - `ubRaw` 缺省（false）时 `body` 语义与 M1 **完全一致** = 平滑 + 基线后的相对躯干角。
     *    不存在「字段名没变但语义变了」：语义由 `ubRaw` 显式声明，两种模式各自写清。
     *  - `ubOk` / `ub` / `ubT` / `body` 的全有或全无规则不变（`ubOk=false` 时 ub 52 个 0、
     *    body 全 0 占位、结构稳定）；`ubOk=false` 时 `ubRaw` / `ubRawBody` 在 raw 判定为真时
     *    **照样出现**（`ubRawBody` 为 [0,0]），保持逐帧语义一致。
     */
    fun buildTrack(
        seq: Long,
        timestampMs: Long,
        bs: FloatArray,
        pose: FloatArray,
        face: Boolean,
        score: Float,
        fps: Float,
        calibrating: Boolean,
        upperBody: UpperBodyPoseFrame? = null,
        upperBodyOk: Boolean = upperBody != null && upperBody.isUsable(),
        upperBodyPostV1: Boolean = false,
    ): String {
        // 开启上半身时多 52 个数，预留大一点的容量，避免 StringBuilder 中途扩容
        val builder = StringBuilder(if (upperBody != null) 1024 else 640)
        builder.jsonString("type", "track", first = true)
        builder.jsonLong("seq", seq)
        builder.jsonLong("t", timestampMs)
        builder.jsonKey("bs").append('[')
        for (index in 0 until BS_COUNT) {
            if (index > 0) builder.append(',')
            // 协议约定 bs 取值 [0,1]
            appendDecimal4(builder, if (index < bs.size) bs[index].coerceIn(0f, 1f) else 0f)
        }
        builder.append(']')
        builder.jsonKey("pose").append('[')
        for (index in 0 until 3) {
            if (index > 0) builder.append(',')
            appendDecimal4(builder, if (index < pose.size) pose[index].coerceIn(-180f, 180f) else 0f)
        }
        builder.append(']')
        builder.jsonBool("face", face)
        builder.jsonFloat("score", score.coerceIn(0f, 1f))
        builder.jsonFloat("fps", fps.coerceIn(0f, 1000f))
        builder.jsonBool("calibrating", calibrating)
        // §4.2 可选上半身字段：只有开启时才出现（未开启时老客户端完全感知不到）。
        // ubOk=false 时仍保持结构稳定：ub = 52 个 0、body = [0,0]，客户端据 ubOk 回默认姿态。
        if (upperBody != null) {
            val ok = upperBodyOk && upperBody.tracking
            // 任务 M2 raw 判定：已协商 + 本帧是原始输出。ubOk=false 时照样出现（值 [0,0]）。
            val raw = upperBodyPostV1 && upperBody.rawMode
            builder.jsonBool("ubOk", ok)
            builder.jsonKey("ub").append('[')
            if (ok) {
                for (index in 0 until UB_COUNT) {
                    if (index > 0) builder.append(',')
                    appendUpperBodyPoint(builder, upperBody.point(index))
                }
            } else {
                appendUpperBodyZeros(builder)
            }
            builder.append(']')
            // 无结果时保留最后一次采集时间（0 = 启用后还没收到过帧），供客户端判重 / 插值
            builder.jsonLong("ubT", upperBody.timestampMs.coerceAtLeast(0L))
            builder.jsonKey("body").append('[')
            appendDecimal4(builder, if (ok) sanitized(upperBody.torso.roll, -180f, 180f) else 0f)
            builder.append(',')
            appendDecimal4(builder, if (ok) sanitized(upperBody.torso.yaw, -180f, 180f) else 0f)
            builder.append(']')
            // 任务 M2：raw 帧显式声明语义。body 与 ubRawBody 同值同义（不一致时以 ubRawBody 为准）。
            if (raw) {
                builder.jsonBool("ubRaw", true)
                builder.jsonKey("ubRawBody").append('[')
                appendDecimal4(builder, if (ok) sanitized(upperBody.torso.roll, -180f, 180f) else 0f)
                builder.append(',')
                appendDecimal4(builder, if (ok) sanitized(upperBody.torso.yaw, -180f, 180f) else 0f)
                builder.append(']')
            }
        }
        builder.append('}')
        return builder.toString()
    }

    /** 便捷重载：直接吃 ARKitBlendShapes（内部走 §5.1 的 ARKitBlendShapes.toMediaPipeArray）。 */
    fun buildTrack(
        seq: Long,
        timestampMs: Long,
        blendShapes: ARKitBlendShapes,
        pitch: Float,
        yaw: Float,
        roll: Float,
        face: Boolean,
        score: Float,
        fps: Float,
        calibrating: Boolean,
        upperBody: UpperBodyPoseFrame? = null,
        upperBodyOk: Boolean = upperBody != null && upperBody.isUsable(),
        upperBodyPostV1: Boolean = false,
    ): String = buildTrack(
        seq = seq,
        timestampMs = timestampMs,
        bs = blendShapes.toMediaPipeArray(),
        pose = floatArrayOf(pitch, yaw, roll),
        face = face,
        score = score,
        fps = fps,
        calibrating = calibrating,
        upperBody = upperBody,
        upperBodyOk = upperBodyOk,
        upperBodyPostV1 = upperBodyPostV1,
    )

    /** §4.3 calib：进入 / 离开校准窗口各一次。 */
    fun buildCalib(active: Boolean, seq: Long, timestampMs: Long = System.currentTimeMillis()): String {
        val builder = StringBuilder(80)
        builder.jsonString("type", "calib", first = true)
        builder.jsonBool("active", active)
        builder.jsonLong("seq", seq)
        builder.jsonLong("t", timestampMs)
        builder.append('}')
        return builder.toString()
    }

    /** §4.4 pong：原样回带客户端的 t（没有就回手机当前时间）。 */
    fun buildPong(timestampMs: Long): String {
        val builder = StringBuilder(48)
        builder.jsonString("type", "pong", first = true)
        builder.jsonLong("t", timestampMs)
        builder.append('}')
        return builder.toString()
    }

    /** §4.4 ack：命令确认。 */
    fun buildAck(name: String): String {
        val builder = StringBuilder(64)
        builder.jsonString("type", "ack", first = true)
        builder.jsonString("name", name)
        builder.append('}')
        return builder.toString()
    }

    /** §4.4 bye：停止服务 / 退出前主动告知客户端。 */
    fun buildBye(reason: String): String {
        val builder = StringBuilder(80)
        builder.jsonString("type", "bye", first = true)
        builder.jsonString("reason", reason)
        builder.append('}')
        return builder.toString()
    }

    /** §6 GET /status 的 JSON。 */
    fun buildStatus(
        running: Boolean,
        tracking: Boolean,
        clients: Int,
        fps: Float,
        device: String,
        seq: Long,
    ): String {
        val builder = StringBuilder(192)
        builder.jsonBool("running", running, first = true)
        builder.jsonBool("tracking", tracking)
        builder.jsonLong("clients", clients.coerceAtLeast(0).toLong())
        builder.jsonFloat("fps", fps)
        builder.jsonString("device", device)
        builder.jsonString("protocol", PROTOCOL)
        builder.jsonLong("version", VERSION.toLong())
        builder.jsonLong("seq", seq)
        builder.append('}')
        return builder.toString()
    }

    // ------------------------------------------------- 发送缓冲配置（send.bufferMs）

    /**
     * 归一化 `send.bufferMs` 配置值：合法值 0 或 [SEND_BUFFER_MIN_MS]..[SEND_BUFFER_MAX_MS] 原样返回，
     * 其它任何值（负数、1~99、超过上限）一律回落 [FRAME_BUFFER_MS]。
     *
     * SharedPreferences 里可能存着被旧版本 / 用户写坏的值，读出来必须先过这里再用。
     */
    fun normalizeSendBufferMs(rawMs: Long): Long =
        if (rawMs == 0L || rawMs in SEND_BUFFER_MIN_MS..SEND_BUFFER_MAX_MS) rawMs else FRAME_BUFFER_MS

    /**
     * 生效缓冲时长（协议 §7.1）：决定是否旁路 [TrackingFrameBuffer] 的纯规则。
     *
     *  - 配置 > 0 → 恒等于配置值（保守模式：手机自己保留发送帧缓冲）；
     *  - 配置 == 0 → 仅当**当前所有已连接客户端**都声明了 [FEATURE_JITTER_BUFFER_V1] 才为 0
     *    （延迟责任移交 PC 侧自适应抖动缓冲）；只要有一个客户端没声明（含老 PC、
     *    还没来得及发 hello 的新连接），就回落 [FRAME_BUFFER_MS]；
     *  - 无客户端时无所谓（本来就不发帧），按「全部满足」处理返回 0。
     *
     * @param clientCount 当前已连接的 WS 客户端数。
     * @param jitterCapableClients 其中声明了 [FEATURE_JITTER_BUFFER_V1] 的数量。
     */
    fun resolveEffectiveBufferMs(
        configuredMs: Long,
        clientCount: Int,
        jitterCapableClients: Int,
    ): Long {
        val configured = normalizeSendBufferMs(configuredMs)
        if (configured > 0L) return configured
        if (clientCount <= 0) return 0L
        return if (jitterCapableClients >= clientCount) 0L else FRAME_BUFFER_MS
    }

    // ------------------------------------------------------------- 消息解析

    /**
     * 解析顶层字段 name 的字符串值（只处理我们需要的 type / name 这类扁平字段）。
     *
     * 手写扫描而不是上 JSON 库：客户端上行只有 ping / command / hello，量极小，
     * 但服务端不应该为此引入反射式解析器。未知结构一律返回 null（协议要求忽略）。
     */
    fun parseStringField(message: String, name: String): String? {
        val start = findValueStart(message, name) ?: return null
        if (start >= message.length || message[start] != QUOTE) return null
        return readQuotedString(message, start)?.first
    }

    /**
     * 从下标 [startQuote]（必须指向引号）读一个 JSON 字符串，返回「内容 + 结束下标（右引号后一位）」。
     *
     * 引号未闭合 / 转义非法时返回 null（与「未知结构一律忽略」一致，绝不抛异常）。
     * [parseStringField] 与 [parseClientFeatures] 共用这段转义处理，保证两处行为一致。
     */
    private fun readQuotedString(message: String, startQuote: Int): Pair<String, Int>? {
        val builder = StringBuilder(16)
        var index = startQuote + 1
        while (index < message.length) {
            val ch = message[index]
            when {
                ch == QUOTE -> return builder.toString() to (index + 1)
                ch.code == BACKSLASH_CODE -> {
                    index++
                    if (index >= message.length) return null
                    when (val escaped = message[index]) {
                        'n' -> builder.append(10.toChar())
                        'r' -> builder.append(13.toChar())
                        't' -> builder.append(9.toChar())
                        'b' -> builder.append(8.toChar())
                        'f' -> builder.append(12.toChar())
                        'u' -> {
                            if (index + 4 >= message.length) return null
                            val code = message.substring(index + 1, index + 5).toIntOrNull(16) ?: return null
                            builder.append(code.toChar())
                            index += 4
                        }
                        else -> builder.append(escaped)
                    }
                }
                else -> builder.append(ch)
            }
            index++
        }
        return null
    }

    /** 解析顶层字段 name 的整数值（long），不存在或不是数字时返回 null。 */
    fun parseLongField(message: String, name: String): Long? {
        val start = findValueStart(message, name) ?: return null
        var index = start
        val negative = index < message.length && message[index] == '-'
        if (negative) index++
        var value = 0L
        var digits = 0
        while (index < message.length) {
            val ch = message[index]
            if (ch < '0' || ch > '9') break
            value = value * 10 + (ch - '0')
            digits++
            index++
        }
        if (digits == 0) return null
        return if (negative) -value else value
    }

    /**
     * 解析顶层字段 name 的布尔值，不存在或不是 `true` / `false` 字面量时返回 null。
     *
     * 与 [parseLongField] 一样是手写扫描（上行报文量极小，不引入 JSON 库）：
     * 字段值必须完整是 `true` / `false`（后面紧跟字母 / 数字 / 下划线的不算，如 `truex`），
     * 其它一律返回 null（字符串 `"true"`、数字 1、`null`、畸形输入都不认）。
     */
    fun parseBoolField(message: String, name: String): Boolean? {
        val start = findValueStart(message, name) ?: return null
        return when {
            matchesLiteral(message, start, "true") -> true
            matchesLiteral(message, start, "false") -> false
            else -> null
        }
    }

    /** [start] 起是否完整匹配 [literal]（后面不允许紧跟标识符字符），用于 [parseBoolField]。 */
    private fun matchesLiteral(message: String, start: Int, literal: String): Boolean {
        val end = start + literal.length
        if (end > message.length) return false
        for (offset in literal.indices) {
            if (message[start + offset] != literal[offset]) return false
        }
        if (end < message.length) {
            val next = message[end]
            if (next.isLetterOrDigit() || next == '_') return false
        }
        return true
    }

    /** 形如 type 字段为 ping 的消息返回 "ping"；不是合法对象 / 没有 type 时返回 null。 */
    fun parseType(message: String): String? = parseStringField(message, "type")

    /**
     * 解析客户端 hello 里的 `features` 字符串数组（协议 §4.5），返回能力标记集合。
     *
     * 纯函数、不依赖 Android API，可在 JVM 单测里直接验证。宽容解析（协议要求
     * 「未知字段必须忽略」，这里绝不抛异常）：
     *
     *  - `features` 字段缺失 / 不是数组 → 空集合（老客户端不发也完全兼容）；
     *  - 数组里的非字符串元素、畸形转义直接跳过 / 截断，返回已解析出的部分；
     *  - 未知的能力名**原样保留**（调用方只认自己认识的，其余忽略）；
     *  - 去重但保持出现顺序（LinkedHashSet，日志可读性好）。
     */
    fun parseClientFeatures(message: String): Set<String> {
        val start = findValueStart(message, "features") ?: return emptySet()
        if (start >= message.length || message[start] != '[') return emptySet()
        val features = LinkedHashSet<String>()
        var index = start + 1
        while (index < message.length) {
            when (val ch = message[index]) {
                ']' -> return features
                ',' -> index++
                '[' -> return features   // 数组里不允许再嵌数组：截断，返回已解析部分
                else -> if (ch.isWhitespace()) {
                    index++
                } else if (ch == QUOTE) {
                    val parsed = readQuotedString(message, index) ?: return features
                    features.add(parsed.first)
                    index = parsed.second
                } else {
                    // 非字符串元素（数字 / true / null …）：按协议忽略，跳到下一个逗号或数组尾
                    val end = indexOfAnyChar(message, index, ',', ']')
                    index = if (end < 0) return features else end
                }
            }
        }
        return features
    }

    /** 找 [chars] 中任意字符在 [from] 之后第一次出现的下标；都没有就返回 -1。 */
    private fun indexOfAnyChar(message: String, from: Int, vararg chars: Char): Int {
        for (index in from until message.length) {
            if (message[index] in chars) return index
        }
        return -1
    }

    /** 客户端上行消息的分类结果（协议 §3 / §4.4 / §4.5）。 */
    enum class ClientMessage {
        /** 心跳：回 pong（原样回带 t）。 */
        PING,

        /** command:recalibrate：触发重置基线并回 ack。 */
        RECALIBRATE,

        /**
         * 客户端能力声明 hello（§4.5）：记录 [parseClientFeatures] 解析出的 feature 集合，
         * 并按 §7.1 重算生效发送缓冲（纯增量，老客户端不发也完全兼容）。
         * 任务 M2：`features` 含 [FEATURE_UPPER_BODY_POST_V1] 或顶层布尔
         * `"upperBodyPostV1":true`（[parseBoolField] 兜底）任一命中时，该连接标记为
         * 「已协商 upperBodyPostV1」，track 才可能携带 `ubRaw` / `ubRawBody`。
         */
        CLIENT_HELLO,

        /** 未知 type / 未知 command / 畸形 JSON：静默忽略（向前兼容，不抛异常）。 */
        IGNORED,
    }

    /**
     * 把客户端上行报文归类。
     *
     * 协议要求「未知字段必须忽略，未知 type 必须忽略」，所以这里对任何畸形输入
     * 都只返回 [ClientMessage.IGNORED]，绝不会抛异常。
     */
    fun classifyClientMessage(message: String): ClientMessage {
        return when (parseType(message)) {
            "ping" -> ClientMessage.PING
            "command" -> if (parseStringField(message, "name") == COMMAND_RECALIBRATE) {
                ClientMessage.RECALIBRATE
            } else {
                ClientMessage.IGNORED
            }
            "hello" -> ClientMessage.CLIENT_HELLO
            else -> ClientMessage.IGNORED
        }
    }

    /**
     * 找到顶层 key 对应的 value 起始下标。
     *
     * 只接受左花括号或逗号之后的同名字段，避免把字符串值里的同名片段误当成 key。
     */
    private fun findValueStart(message: String, name: String): Int? {
        val key = QUOTE + name + QUOTE
        var searchFrom = 0
        while (searchFrom < message.length) {
            val keyIndex = message.indexOf(key, searchFrom)
            if (keyIndex < 0) return null
            searchFrom = keyIndex + key.length

            var previous = keyIndex - 1
            while (previous >= 0 && message[previous].isWhitespace()) previous--
            if (previous >= 0 && message[previous] != '{' && message[previous] != ',') continue

            var index = keyIndex + key.length
            while (index < message.length && message[index].isWhitespace()) index++
            if (index >= message.length || message[index] != COLON) continue
            index++
            while (index < message.length && message[index].isWhitespace()) index++
            return index
        }
        return null
    }
}
