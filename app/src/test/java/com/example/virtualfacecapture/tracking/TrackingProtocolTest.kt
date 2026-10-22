package com.example.virtualfacecapture.tracking

import com.example.commondata.ARKitBlendShapes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 追踪协议（docs/tracking-protocol.md）的本地 JVM 单元测试。
 *
 * 覆盖：
 *  1. ARKitBlendShapes.toMediaPipeArray() 的 52 项长度与 §5.1 索引顺序
 *  2. track 报文能被 org.json 解析、字段齐全、小数位 <= 4、单帧 < 1KB
 *  3. hello / status / calib / pong / ack / bye 的 JSON 合法性
 *  4. 客户端上行 ping / command / 畸形 JSON 的分类（必须是 PING / RECALIBRATE / IGNORED，且不抛异常）
 */
class TrackingProtocolTest {

    // ---------------------------------------------------------------- 工具

    /** 手写字面量里的“最多 4 位小数”检查（跳过字符串内部，避免设备名里的点被误算）。 */
    private fun maxFractionDigits(json: String): Int {
        var inString = false
        var escaped = false
        var inFraction = false
        var digits = 0
        var maxDigits = 0
        for (ch in json) {
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch.code == 92) {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '.' -> {
                    inFraction = true
                    digits = 0
                }
                in '0'..'9' -> if (inFraction) {
                    digits++
                    if (digits > maxDigits) maxDigits = digits
                }
                else -> {
                    inFraction = false
                    digits = 0
                }
            }
        }
        return maxDigits
    }

    private fun sampleShapes(): ARKitBlendShapes = ARKitBlendShapes(
        eyeBlinkLeft = 0.5f,
        eyeBlinkRight = 0.25f,
        eyeSquintLeft = 0.44f,
        eyeSquintRight = 0.46f,
        eyeWideLeft = 0.53f,
        eyeWideRight = 0.55f,
        eyeLookUpLeft = 0.31f,
        eyeLookUpRight = 0.33f,
        browDownLeft = 0.11f,
        browDownRight = 0.12f,
        cheekPuff = 0.22f,
        jawOpen = 0.7f,
        jawLeft = 0.13f,
        mouthPressRight = 0.66f,
        mouthSmileLeft = 0.4f,
        mouthSmileRight = 0.45f,
        mouthUpperUpLeft = 0.77f,
        noseSneerLeft = 0.19f,
        noseSneerRight = 0.3f,
        tongueOut = 0.99f,
    )

    private fun sampleTrackJson(): String = TrackingProtocol.buildTrack(
        seq = 128L,
        timestampMs = 1730000000123L,
        bs = sampleShapes().toMediaPipeArray(),
        pose = floatArrayOf(-3.5f, 8.25f, 0.4f),
        face = true,
        score = 0.98765f,
        fps = 59.123456f,
        calibrating = false,
    )

    // ---------------------------------------------------- §5.1 bs 索引顺序

    @Test
    fun blendShapeArrayHasExactly52Entries() {
        val array = sampleShapes().toMediaPipeArray()
        assertEquals(52, array.size)
        // 0 号位 neutral 恒为 0
        assertEquals(0f, array[0], 0f)
        // tongueOut 不进入协议数组（ARKit-52 没有舌头）
        for (value in array) {
            assertTrue("tongueOut 不应出现在协议数组里", value != 0.99f)
        }
    }

    @Test
    fun blendShapeArrayUsesProtocolIndexOrder() {
        val array = sampleShapes().toMediaPipeArray()
        assertEquals(0.11f, array[1], 0f)   // browDownLeft
        assertEquals(0.22f, array[6], 0f)   // cheekPuff
        assertEquals(0.5f, array[9], 0f)    // eyeBlinkLeft
        assertEquals(0.25f, array[10], 0f)  // eyeBlinkRight
        assertEquals(0.31f, array[17], 0f)  // eyeLookUpLeft
        assertEquals(0.33f, array[18], 0f)  // eyeLookUpRight
        assertEquals(0.44f, array[19], 0f)  // eyeSquintLeft
        assertEquals(0.46f, array[20], 0f)  // eyeSquintRight
        assertEquals(0.53f, array[21], 0f)  // eyeWideLeft
        assertEquals(0.55f, array[22], 0f)  // eyeWideRight
        assertEquals(0.13f, array[24], 0f)  // jawLeft
        assertEquals(0.7f, array[25], 0f)   // jawOpen
        assertEquals(0.66f, array[37], 0f)  // mouthPressRight
        assertEquals(0.4f, array[44], 0f)   // mouthSmileLeft
        assertEquals(0.45f, array[45], 0f)  // mouthSmileRight
        assertEquals(0.77f, array[48], 0f)  // mouthUpperUpLeft
        assertEquals(0.19f, array[50], 0f)  // noseSneerLeft
        assertEquals(0.3f, array[51], 0f)   // noseSneerRight
    }

    // ------------------------------------------------------- §4.2 track 报文

    @Test
    fun trackJsonHasAllProtocolFields() {
        val json = JSONObject(sampleTrackJson())

        assertEquals("track", json.getString("type"))
        assertEquals(128L, json.getLong("seq"))
        assertEquals(1730000000123L, json.getLong("t"))
        assertTrue(json.getBoolean("face"))
        assertEquals(0.9877, json.getDouble("score"), 1e-4)
        assertEquals(59.1235, json.getDouble("fps"), 1e-4)
        assertFalse(json.getBoolean("calibrating"))

        val bs = json.getJSONArray("bs")
        assertEquals(52, bs.length())
        assertEquals(0.5, bs.getDouble(9), 1e-6)
        assertEquals(0.25, bs.getDouble(10), 1e-6)

        val pose = json.getJSONArray("pose")
        assertEquals(3, pose.length())
        assertEquals(-3.5, pose.getDouble(0), 1e-6)
        assertEquals(8.25, pose.getDouble(1), 1e-6)
        assertEquals(0.4, pose.getDouble(2), 1e-6)
    }

    @Test
    fun trackJsonHasAtMostFourDecimalPlaces() {
        val json = sampleTrackJson()
        assertTrue("小数位必须 <= 4，实际 " + maxFractionDigits(json), maxFractionDigits(json) <= 4)
        assertTrue("小数位应当存在（用于验证舍入）", maxFractionDigits(json) >= 1)
    }

    @Test
    fun trackValuesAreClampedToProtocolRanges() {
        val shapes = ARKitBlendShapes(
            eyeBlinkLeft = 1.7f,
            eyeBlinkRight = -0.4f,
        )
        val json = JSONObject(
            TrackingProtocol.buildTrack(
                seq = 1L,
                timestampMs = 2L,
                bs = shapes.toMediaPipeArray(),
                pose = floatArrayOf(400f, -400f, 0f),
                face = false,
                score = 3f,
                fps = -1f,
                calibrating = true,
            )
        )
        val bs = json.getJSONArray("bs")
        assertEquals(1.0, bs.getDouble(9), 1e-6)
        assertEquals(0.0, bs.getDouble(10), 1e-6)

        val pose = json.getJSONArray("pose")
        assertEquals(180.0, pose.getDouble(0), 1e-6)
        assertEquals(-180.0, pose.getDouble(1), 1e-6)

        assertEquals(1.0, json.getDouble("score"), 1e-6)
        assertEquals(0.0, json.getDouble("fps"), 1e-6)
        assertTrue(json.getBoolean("calibrating"))
    }

    @Test
    fun trackMessageFitsOneKilobyte() {
        val bytes = sampleTrackJson().toByteArray(Charsets.UTF_8)
        assertTrue("单帧报文应 < 1KB，实际 " + bytes.size, bytes.size < 1024)
    }

    @Test
    fun trackSeqIsSerializedAsInteger() {
        val json = JSONObject(
            TrackingProtocol.buildTrack(
                seq = 9007199254740991L,
                timestampMs = 1L,
                bs = FloatArray(52),
                pose = floatArrayOf(0f, 0f, 0f),
                face = true,
                score = 1f,
                fps = 60f,
                calibrating = false,
            )
        )
        assertEquals(9007199254740991L, json.getLong("seq"))
    }

    // ---------------------------------------------------------- §4.1 hello

    @Test
    fun helloJsonHasAllProtocolFields() {
        val json = JSONObject(
            TrackingProtocol.buildHello(
                device = "Pixel 8 Pro",
                app = "VirtualFaceCapture/1.0",
                decimation = 2,
                targetFps = 60,
                timestampMs = 1730000000000L,
            )
        )
        assertEquals("hello", json.getString("type"))
        assertEquals("vfc-tracker", json.getString("protocol"))
        assertEquals(1, json.getInt("version"))
        assertEquals("Pixel 8 Pro", json.getString("device"))
        assertEquals("VirtualFaceCapture/1.0", json.getString("app"))
        assertEquals(2, json.getInt("decimation"))
        assertEquals(60, json.getInt("targetFps"))
        assertEquals(1730000000000L, json.getLong("t"))
    }

    // --------------------------------------------------------- §6 /status

    @Test
    fun statusJsonHasAllProtocolFields() {
        val json = JSONObject(
            TrackingProtocol.buildStatus(
                running = true,
                tracking = false,
                clients = 2,
                fps = 59.94f,
                device = "Pixel 8 Pro",
                seq = 4321L,
            )
        )
        assertTrue(json.getBoolean("running"))
        assertFalse(json.getBoolean("tracking"))
        assertEquals(2, json.getInt("clients"))
        assertEquals(59.94, json.getDouble("fps"), 1e-4)
        assertEquals("Pixel 8 Pro", json.getString("device"))
        assertEquals("vfc-tracker", json.getString("protocol"))
        assertEquals(1, json.getInt("version"))
        assertEquals(4321L, json.getLong("seq"))

        val keys = json.keys()
        val names = HashSet<String>()
        while (keys.hasNext()) names.add(keys.next())
        assertEquals(
            setOf("running", "tracking", "clients", "fps", "device", "protocol", "version", "seq"),
            names,
        )
    }

    // -------------------------------------------------- §4.3 / §4.4 控制消息

    @Test
    fun controlMessagesAreValidJson() {
        val calib = JSONObject(TrackingProtocol.buildCalib(active = true, seq = 42L, timestampMs = 7L))
        assertEquals("calib", calib.getString("type"))
        assertTrue(calib.getBoolean("active"))
        assertEquals(42L, calib.getLong("seq"))
        assertEquals(7L, calib.getLong("t"))

        val pong = JSONObject(TrackingProtocol.buildPong(1730000000123L))
        assertEquals("pong", pong.getString("type"))
        assertEquals(1730000000123L, pong.getLong("t"))

        val ack = JSONObject(TrackingProtocol.buildAck("recalibrate"))
        assertEquals("ack", ack.getString("type"))
        assertEquals("recalibrate", ack.getString("name"))

        val bye = JSONObject(TrackingProtocol.buildBye("service stopped"))
        assertEquals("bye", bye.getString("type"))
        assertEquals("service stopped", bye.getString("reason"))
    }

    @Test
    fun escapedStringsStayValidJson() {
        val weird = "Pixel " + 34.toChar() + "8" + 34.toChar() + " " + 92.toChar() + " Pro"
        val json = JSONObject(
            TrackingProtocol.buildHello(
                device = weird,
                app = "app",
            )
        )
        assertEquals(weird, json.getString("device"))
    }

    // ----------------------------------------------------------- 数值格式化

    @Test
    fun decimalFormattingRoundsAndTrims() {
        assertEquals("0.1235", TrackingProtocol.decimal4(0.12345f))
        assertEquals("0.5", TrackingProtocol.decimal4(0.5f))
        assertEquals("0", TrackingProtocol.decimal4(0f))
        assertEquals("0", TrackingProtocol.decimal4(-0.00004f))
        assertEquals("0", TrackingProtocol.decimal4(Float.NaN))
        assertEquals("0", TrackingProtocol.decimal4(Float.POSITIVE_INFINITY))
        assertEquals("-1.5", TrackingProtocol.decimal4(-1.5f))
        assertEquals("59.1235", TrackingProtocol.decimal4(59.123456f))
        // 小于 0.00005 的四舍五入边界：0.00006 -> 0.0001，0.00001 -> 0
        assertEquals("0.0001", TrackingProtocol.decimal4(0.00006f))
        assertEquals("0", TrackingProtocol.decimal4(0.00001f))
    }

    // --------------------------------------------------------- 上行消息解析

    @Test
    fun pingIsClassifiedAsPing() {
        val message = """{"type":"ping","t":1730000000123}"""
        assertEquals(TrackingProtocol.ClientMessage.PING, TrackingProtocol.classifyClientMessage(message))
        assertEquals(1730000000123L, TrackingProtocol.parseLongField(message, "t"))
        assertEquals("ping", TrackingProtocol.parseType(message))
    }

    @Test
    fun recalibrateCommandIsClassifiedAsRecalibrate() {
        val message = """{"type":"command","name":"recalibrate"}"""
        assertEquals(
            TrackingProtocol.ClientMessage.RECALIBRATE,
            TrackingProtocol.classifyClientMessage(message),
        )
        assertEquals("recalibrate", TrackingProtocol.parseStringField(message, "name"))
    }

    @Test
    fun unknownCommandIsIgnored() {
        assertEquals(
            TrackingProtocol.ClientMessage.IGNORED,
            TrackingProtocol.classifyClientMessage("""{"type":"command","name":"dance"}"""),
        )
        assertEquals(
            TrackingProtocol.ClientMessage.IGNORED,
            TrackingProtocol.classifyClientMessage("""{"type":"bye"}"""),
        )
    }

    @Test
    fun malformedMessagesAreIgnoredWithoutThrowing() {
        val malformed = listOf(
            "",
            "   ",
            "not json at all",
            """{"type":""",
            """{"type":}""",
            """{"type":"ping""",
            """{"name":"recalibrate"}""",
            "null",
            "[1,2,3]",
            """{"type":123}""",
            """{"type":"command"}""",
            """{"type":"command","name":""",
            """{"type":"command","name":"recalib""",
        )
        for (message in malformed) {
            assertEquals(
                "畸形报文必须被忽略: " + message,
                TrackingProtocol.ClientMessage.IGNORED,
                TrackingProtocol.classifyClientMessage(message),
            )
        }
    }

    @Test
    fun parserToleratesWhitespaceAndFieldOrder() {
        val message = """  { "name" : "recalibrate" , "type" : "command" }  """
        assertEquals(
            TrackingProtocol.ClientMessage.RECALIBRATE,
            TrackingProtocol.classifyClientMessage(message),
        )
    }

    @Test
    fun chunkedProtocolVersionIsReadable() {
        // hello.version 不匹配时客户端仍应能读到 version（协议 §10）
        val hello = TrackingProtocol.buildHello(device = "d", app = "a")
        assertEquals(1L, TrackingProtocol.parseLongField(hello, "version"))
    }
}
