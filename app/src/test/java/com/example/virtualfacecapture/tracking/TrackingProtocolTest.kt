package com.example.virtualfacecapture.tracking

import com.example.commondata.ARKitBlendShapes
import com.example.commondata.BodyPoint
import com.example.commondata.TorsoAngles
import com.example.commondata.UpperBodyLandmarkIndex
import com.example.commondata.UpperBodyPoseFrame
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
 *  5. 上半身增量字段（hello.upperBody、track.ubOk/ub/ubT/body）的开关、边界与数值清洗
 *  6. 客户端 hello 协商（§4.5）：CLIENT_HELLO 归类与 features 解析（宽容、纯函数）
 *  7. send.bufferMs 配置归一化与生效缓冲回落规则（§7.1 的纯函数）
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

    // --------------------------------------------- §4.1 / §4.2 上半身增量字段

    /** 13 个点各不相同的样例帧，用来校验顺序 / 展平下标（协议 §5.3）。 */
    private fun sampleUpperBody(timestampMs: Long = 1730000000100L): UpperBodyPoseFrame {
        val points = List(UpperBodyLandmarkIndex.COUNT) { index ->
            BodyPoint(
                x = 0.1f + index * 0.05f,
                y = 0.2f + index * 0.04f,
                z = -0.01f * index,
                visibility = 0.9f - index * 0.01f,
            )
        }
        return UpperBodyPoseFrame(
            points = points,
            torso = TorsoAngles(roll = 2.5f, yaw = -4.25f),
            tracking = true,
            quality = 0.85f,
            timestampMs = timestampMs,
        )
    }

    private fun buildTrackWithUpperBody(
        upperBody: UpperBodyPoseFrame?,
        upperBodyOk: Boolean,
    ): String = TrackingProtocol.buildTrack(
        seq = 129L,
        timestampMs = 1730000000200L,
        bs = sampleShapes().toMediaPipeArray(),
        pose = floatArrayOf(-3.5f, 8.25f, 0.4f),
        face = true,
        score = 0.9f,
        fps = 60f,
        calibrating = false,
        upperBody = upperBody,
        upperBodyOk = upperBodyOk,
    )

    @Test
    fun trackJsonCarriesFullUpperBody() {
        val json = JSONObject(buildTrackWithUpperBody(sampleUpperBody(), true))
        assertTrue(json.getBoolean("ubOk"))
        assertEquals(1730000000100L, json.getLong("ubT"))

        val body = json.getJSONArray("body")
        assertEquals(2, body.length())
        assertEquals(2.5, body.getDouble(0), 1e-6)
        assertEquals(-4.25, body.getDouble(1), 1e-6)

        val ub = json.getJSONArray("ub")
        assertEquals(52, ub.length())
        // 第 0 点（鼻子）：x=0.1, y=0.2, z=0, visibility=0.9
        assertEquals(0.1, ub.getDouble(0), 1e-6)
        assertEquals(0.2, ub.getDouble(1), 1e-6)
        assertEquals(0.0, ub.getDouble(2), 1e-6)
        assertEquals(0.9, ub.getDouble(3), 1e-6)
        // 第 5 点（左肩）：x=0.35, y=0.4, z=-0.05, visibility=0.85
        val leftShoulder = UpperBodyLandmarkIndex.P_LEFT_SHOULDER * 4
        assertEquals(0.35, ub.getDouble(leftShoulder), 1e-4)
        assertEquals(0.4, ub.getDouble(leftShoulder + 1), 1e-4)
        assertEquals(-0.05, ub.getDouble(leftShoulder + 2), 1e-6)
        assertEquals(0.85, ub.getDouble(leftShoulder + 3), 1e-6)
        // 第 12 点（右胯）：x=0.7, y=0.68, z=-0.12, visibility=0.78
        val rightHip = UpperBodyLandmarkIndex.P_RIGHT_HIP * 4
        assertEquals(0.7, ub.getDouble(rightHip), 1e-4)
        assertEquals(0.68, ub.getDouble(rightHip + 1), 1e-4)
        assertEquals(-0.12, ub.getDouble(rightHip + 2), 1e-6)
        assertEquals(0.78, ub.getDouble(rightHip + 3), 1e-6)
    }

    @Test
    fun upperBodyOkDefaultsFromFrameUsability() {
        val json = JSONObject(
            TrackingProtocol.buildTrack(
                seq = 1L,
                timestampMs = 2L,
                bs = FloatArray(52),
                pose = floatArrayOf(0f, 0f, 0f),
                face = true,
                score = 1f,
                fps = 60f,
                calibrating = false,
                upperBody = sampleUpperBody(),
            )
        )
        assertTrue(json.getBoolean("ubOk"))
    }

    @Test
    fun trackJsonOmitsUpperBodyFieldsWhenDisabled() {
        val json = JSONObject(sampleTrackJson())
        assertFalse("未开启上半身时不应出现 ubOk", json.has("ubOk"))
        assertFalse("未开启上半身时不应出现 ub", json.has("ub"))
        assertFalse("未开启上半身时不应出现 ubT", json.has("ubT"))
        assertFalse("未开启上半身时不应出现 body", json.has("body"))
    }

    @Test
    fun noUpperBodyResultKeepsStableZeroStructure() {
        val json = JSONObject(buildTrackWithUpperBody(sampleUpperBody(), false))
        assertFalse(json.getBoolean("ubOk"))
        // ubT 保留最后一次采集时间，供客户端判重 / 判断陈旧程度
        assertEquals(1730000000100L, json.getLong("ubT"))

        val ub = json.getJSONArray("ub")
        assertEquals(52, ub.length())
        for (index in 0 until 52) {
            assertEquals("ubOk=false 时 ub 必须全 0（下标 " + index + "）", 0.0, ub.getDouble(index), 0.0)
        }

        val body = json.getJSONArray("body")
        assertEquals(2, body.length())
        assertEquals(0.0, body.getDouble(0), 0.0)
        assertEquals(0.0, body.getDouble(1), 0.0)

        // 调用方即使误传 upperBodyOk=true，帧本身 tracking=false 时也必须发 ubOk=false
        val notTracking = sampleUpperBody().copy(tracking = false)
        assertFalse(JSONObject(buildTrackWithUpperBody(notTracking, true)).getBoolean("ubOk"))
    }

    @Test
    fun upperBodyValuesAreSanitizedAndClamped() {
        val points = MutableList(UpperBodyLandmarkIndex.COUNT) {
            BodyPoint(Float.NaN, Float.POSITIVE_INFINITY, 99f, -1f)
        }
        points[1] = BodyPoint(-1f, 2f, -99f, 2f)
        val frame = UpperBodyPoseFrame(
            points = points,
            torso = TorsoAngles(Float.NaN, Float.POSITIVE_INFINITY),
            tracking = true,
            quality = 1f,
            timestampMs = 100L,
        )
        val raw = buildTrackWithUpperBody(frame, true)
        assertFalse("NaN 不能写进 JSON", raw.contains("NaN"))
        assertFalse("Infinity 不能写进 JSON", raw.contains("Infinity"))

        val json = JSONObject(raw)
        val ub = json.getJSONArray("ub")
        assertEquals(52, ub.length())
        // 第 0 点：NaN / Inf 写 0；z 截到 5；visibility 截到 0
        assertEquals(0.0, ub.getDouble(0), 0.0)
        assertEquals(0.0, ub.getDouble(1), 0.0)
        assertEquals(5.0, ub.getDouble(2), 0.0)
        assertEquals(0.0, ub.getDouble(3), 0.0)
        // 第 1 点：x / y 截到 [0,1]，z 截到 -5，visibility 截到 1
        assertEquals(0.0, ub.getDouble(4), 0.0)
        assertEquals(1.0, ub.getDouble(5), 0.0)
        assertEquals(-5.0, ub.getDouble(6), 0.0)
        assertEquals(1.0, ub.getDouble(7), 0.0)

        val body = json.getJSONArray("body")
        assertEquals(0.0, body.getDouble(0), 0.0)
        assertEquals(0.0, body.getDouble(1), 0.0)
    }

    @Test
    fun helloAdvertisesUpperBodyOnlyWhenEnabled() {
        val enabled = JSONObject(
            TrackingProtocol.buildHello(device = "Pixel 8 Pro", app = "app", upperBody = true)
        )
        assertTrue(enabled.getBoolean("upperBody"))

        val disabled = JSONObject(
            TrackingProtocol.buildHello(device = "Pixel 8 Pro", app = "app")
        )
        assertFalse("关闭上半身时 hello 不应带 upperBody 字段", disabled.has("upperBody"))
    }

    @Test
    fun upperBodyBodyAnglesUseAtMostFourDecimals() {
        val frame = sampleUpperBody().copy(torso = TorsoAngles(roll = 12.34567f, yaw = -0.00004f))
        val raw = buildTrackWithUpperBody(frame, true)
        assertTrue("body 应保留 4 位小数并去掉尾随 0，实际: " + raw, raw.contains("\"body\":[12.3457,0]"))

        val body = JSONObject(raw).getJSONArray("body")
        assertEquals(12.3457, body.getDouble(0), 1e-9)
        assertEquals(0.0, body.getDouble(1), 0.0)
        assertTrue("上半身报文小数位也必须 <= 4", maxFractionDigits(raw) <= 4)
    }

    @Test
    fun trackWithUpperBodyFitsOnePointTwoKilobytes() {
        val bytes = buildTrackWithUpperBody(sampleUpperBody(), true).toByteArray(Charsets.UTF_8)
        assertTrue("开启上半身后单帧应 < 1.2KB，实际 " + bytes.size, bytes.size < 1229)
    }

    // ------------------------------------------- 客户端 hello 协商（§4.5）

    @Test
    fun clientHelloIsClassifiedAsClientHello() {
        val message = """{"type":"hello","protocol":"vfc-tracker","version":1,"features":["jitterBufferV1"],"t":1730000000000}"""
        assertEquals(
            TrackingProtocol.ClientMessage.CLIENT_HELLO,
            TrackingProtocol.classifyClientMessage(message),
        )
    }

    @Test
    fun clientHelloFeaturesParseJitterBufferV1() {
        val message = """{"type":"hello","protocol":"vfc-tracker","version":1,"features":["jitterBufferV1"],"t":1730000000000}"""
        val features = TrackingProtocol.parseClientFeatures(message)
        assertEquals(setOf(TrackingProtocol.FEATURE_JITTER_BUFFER_V1), features)
    }

    @Test
    fun clientHelloFeaturesKeepUnknownNamesAndDeduplicate() {
        // 未知能力名原样保留（调用方只认自己认识的）、去重但保持出现顺序
        val message = """{"type":"hello","features":["jitterBufferV1","foo","jitterBufferV1","bar"]}"""
        val features = TrackingProtocol.parseClientFeatures(message)
        assertEquals(listOf("jitterBufferV1", "foo", "bar"), features.toList())
    }

    @Test
    fun clientHelloWithoutFeaturesYieldsEmptySet() {
        // 老客户端不发 hello / 不带 features 都必须完全兼容
        assertEquals(emptySet<String>(), TrackingProtocol.parseClientFeatures("""{"type":"hello"}"""))
        assertEquals(
            emptySet<String>(),
            TrackingProtocol.parseClientFeatures("""{"type":"hello","features":[]}"""),
        )
        // features 不是数组（畸形）→ 空集合
        assertEquals(
            emptySet<String>(),
            TrackingProtocol.parseClientFeatures("""{"type":"hello","features":"jitterBufferV1"}"""),
        )
    }

    @Test
    fun clientHelloFeaturesTolerateMalformedJson() {
        // 非字符串元素跳过；引号未闭合就截断返回已解析部分；绝不能抛异常
        assertEquals(
            setOf(TrackingProtocol.FEATURE_JITTER_BUFFER_V1),
            TrackingProtocol.parseClientFeatures("""{"features":[1,"jitterBufferV1",true,null]}"""),
        )
        assertEquals(
            setOf("a"),
            TrackingProtocol.parseClientFeatures("""{"features":["a","b"""),   // 未闭合
        )
        assertEquals(
            emptySet<String>(),
            TrackingProtocol.parseClientFeatures("""{"features":[}"""),
        )
        assertEquals(
            emptySet<String>(),
            TrackingProtocol.parseClientFeatures("not json at all"),
        )
    }

    @Test
    fun unknownClientTypesRemainIgnored() {
        // hello 归类是纯增量：其它未知 type 的忽略行为不许变
        for (message in listOf(
            """{"type":"hello2","features":["jitterBufferV1"]}""",
            """{"type":"HELLO","features":["jitterBufferV1"]}""",
            """{"features":["jitterBufferV1"]}""",
        )) {
            assertEquals(
                "未知 type 必须继续忽略: " + message,
                TrackingProtocol.ClientMessage.IGNORED,
                TrackingProtocol.classifyClientMessage(message),
            )
        }
    }

    // --------------------------------- send.bufferMs 配置与生效缓冲规则（§7.1）

    @Test
    fun normalizeSendBufferMsAcceptsOnlyZeroOrHundredToTwoThousand() {
        // 合法值原样返回
        assertEquals(0L, TrackingProtocol.normalizeSendBufferMs(0L))
        assertEquals(100L, TrackingProtocol.normalizeSendBufferMs(100L))
        assertEquals(500L, TrackingProtocol.normalizeSendBufferMs(500L))
        assertEquals(2000L, TrackingProtocol.normalizeSendBufferMs(2000L))
        // 其它值一律回落保守 500
        assertEquals(TrackingProtocol.FRAME_BUFFER_MS, TrackingProtocol.normalizeSendBufferMs(1L))
        assertEquals(TrackingProtocol.FRAME_BUFFER_MS, TrackingProtocol.normalizeSendBufferMs(99L))
        assertEquals(TrackingProtocol.FRAME_BUFFER_MS, TrackingProtocol.normalizeSendBufferMs(-1L))
        assertEquals(TrackingProtocol.FRAME_BUFFER_MS, TrackingProtocol.normalizeSendBufferMs(2001L))
        assertEquals(
            TrackingProtocol.FRAME_BUFFER_MS,
            TrackingProtocol.normalizeSendBufferMs(Long.MAX_VALUE),
        )
    }

    @Test
    fun effectiveBufferAlwaysFollowsPositiveConfig() {
        // 配置 > 0：不管客户端协商结果如何都等于配置值（第一步验证模式，手机保留 500 ms）
        assertEquals(500L, TrackingProtocol.resolveEffectiveBufferMs(500L, 0, 0))
        assertEquals(500L, TrackingProtocol.resolveEffectiveBufferMs(500L, 2, 2))
        assertEquals(500L, TrackingProtocol.resolveEffectiveBufferMs(500L, 1, 0))
        assertEquals(2000L, TrackingProtocol.resolveEffectiveBufferMs(2000L, 1, 0))
        // 非法配置先归一化成 500，仍走「配置 > 0 恒生效」
        assertEquals(500L, TrackingProtocol.resolveEffectiveBufferMs(300L, 1, 0))
    }

    @Test
    fun effectiveBufferZeroRequiresEveryClientToDeclareJitterBufferV1() {
        // 全部声明 jitterBufferV1（含「无客户端」这一无所谓的情形）→ 才允许真正旁路
        assertEquals(0L, TrackingProtocol.resolveEffectiveBufferMs(0L, 0, 0))
        assertEquals(0L, TrackingProtocol.resolveEffectiveBufferMs(0L, 2, 2))
        assertEquals(0L, TrackingProtocol.resolveEffectiveBufferMs(0L, 1, 1))
        // 有一个没声明（含老 PC、还没发 hello 的新连接）→ 回落 FRAME_BUFFER_MS
        assertEquals(TrackingProtocol.FRAME_BUFFER_MS, TrackingProtocol.resolveEffectiveBufferMs(0L, 2, 1))
        assertEquals(TrackingProtocol.FRAME_BUFFER_MS, TrackingProtocol.resolveEffectiveBufferMs(0L, 1, 0))
        assertEquals(TrackingProtocol.FRAME_BUFFER_MS, TrackingProtocol.resolveEffectiveBufferMs(0L, 4, 3))
    }
}
