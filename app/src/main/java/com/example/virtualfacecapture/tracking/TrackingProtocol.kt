package com.example.virtualfacecapture.tracking

import com.example.commondata.ARKitBlendShapes

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
     * 设为 0 即关闭缓冲，退化为「算完就发」的最低延迟模式。
     */
    const val FRAME_BUFFER_MS = 500L

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
     */
    fun buildHello(
        device: String,
        app: String,
        decimation: Int = DEFAULT_DECIMATION,
        targetFps: Int = 60,
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
        builder.jsonLong("t", timestampMs)
        builder.append('}')
        return builder.toString()
    }

    /**
     * §4.2 track：每帧一条。bs 必须是 §5.1 的 52 项顺序，pose 为 [pitch, yaw, roll]（度）。
     *
     * @param face 本帧是否检测到人脸；false 时调用方应沿用上一帧的 bs / pose。
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
    ): String {
        val builder = StringBuilder(640)
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
    ): String = buildTrack(
        seq = seq,
        timestampMs = timestampMs,
        bs = blendShapes.toMediaPipeArray(),
        pose = floatArrayOf(pitch, yaw, roll),
        face = face,
        score = score,
        fps = fps,
        calibrating = calibrating,
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

    // ------------------------------------------------------------- 消息解析

    /**
     * 解析顶层字段 name 的字符串值（只处理我们需要的 type / name 这类扁平字段）。
     *
     * 手写扫描而不是上 JSON 库：客户端上行只有 ping / command，量极小，
     * 但服务端不应该为此引入反射式解析器。未知结构一律返回 null（协议要求忽略）。
     */
    fun parseStringField(message: String, name: String): String? {
        val start = findValueStart(message, name) ?: return null
        if (start >= message.length || message[start] != QUOTE) return null
        val builder = StringBuilder(16)
        var index = start + 1
        while (index < message.length) {
            val ch = message[index]
            when {
                ch == QUOTE -> return builder.toString()
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

    /** 形如 type 字段为 ping 的消息返回 "ping"；不是合法对象 / 没有 type 时返回 null。 */
    fun parseType(message: String): String? = parseStringField(message, "type")

    /** 客户端上行消息的分类结果（协议 §3 / §4.4）。 */
    enum class ClientMessage {
        /** 心跳：回 pong（原样回带 t）。 */
        PING,

        /** command:recalibrate：触发重置基线并回 ack。 */
        RECALIBRATE,

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
