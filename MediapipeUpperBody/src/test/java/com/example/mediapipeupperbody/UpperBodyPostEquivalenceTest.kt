package com.example.mediapipeupperbody

import com.example.commondata.BodyPoint
import com.example.commondata.TorsoAngles
import com.example.commondata.UpperBodyLandmarkIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 上半身后处理链（One-Euro 平滑 → 躯干角 → TorsoBaseline）的**跨语言等价测试**（任务 M2）。
 *
 * 背景：M2 把上半身后处理从手机迁到 PC（`windows-client/renderer/mapping.js` 的
 * OneEuroFilter / computeTorsoAngles / TorsoBaseline / UpperBodyPostProcessor），手机端在
 * raw 模式下只发**未做 One-Euro、未减基线的原始点**。两端必须严格对拍：
 * 同一份 ub 原始样本，PC 算出的 body 与 Kotlin 原实现误差 ≤ [ANGLE_TOLERANCE_DEG]。
 *
 * 夹具 `ub_post_fixture.txt`（同模块 src/test/resources，由独立参考实现生成）：
 *  - `PARAM <name> <value>`：链路参数（One-Euro 截止频率、校准帧数、dt 规则）；
 *  - `FRAME <tMs> <52 个浮点（13 点 × x y z visibility，协议顺序）> <expRoll> <expYaw>
 *    <shoulder_left 平滑 x y z> <wrist_right 平滑 x y z>`：一帧输入 + 参考实现期望输出；
 *  - `LOSS <tMs>`：等价于 tracking=false → smoother.reset() + dt 锚点清零，**baseline 不重置**；
 *  - `END`。
 *
 * 测试逻辑逐行复刻 [UpperBodyPoseDetector.buildFrame] 的平滑段（dt 规则、reset 时机、
 * torso/smoothTorso/baseline 顺序），纯 JVM 可跑：只 import commondata 与
 * mediapipeupperbody 的纯 Kotlin 类，不碰 android.* / mediapipe tasks。
 */
class UpperBodyPostEquivalenceTest {

    companion object {
        /** 夹具资源名（`MediapipeUpperBody/src/test/resources/ub_post_fixture.txt`）。 */
        private const val FIXTURE_RESOURCE = "ub_post_fixture.txt"

        /** 夹具首行（版本标记）。 */
        private const val FIXTURE_HEADER = "vfc-ub-post-fixture v1"

        /** 角度等价容差（度）：吸收 Kotlin Float 与参考实现 double 的差异。 */
        private const val ANGLE_TOLERANCE_DEG = 0.5

        /** 平滑点等价容差（归一化坐标）。 */
        private const val POINT_TOLERANCE = 0.002

        /** 校准首帧断言（a）用的更小容差：(0,0) 是精确值。 */
        private const val ZERO_TOLERANCE = 1e-6

        /** 断言（a）：校准期内相邻有效帧的 body 跳变上限（度）。 */
        private const val CALIBRATION_MAX_STEP_DEG = 2f

        /** 断言（b）：两肩重合段允许的角度上限（度）。 */
        private const val DEGENERATE_MAX_ANGLE_DEG = 20f

        /** 断言（b）：两肩重合段的有效帧序号（1 起，含端点）。 */
        private const val DEGENERATE_FIRST = 29
        private const val DEGENERATE_LAST = 34
    }

    /** 夹具一行事件：有效帧 / 跟踪丢失（LOSS）。 */
    private sealed class Event {
        data class Frame(
            val tMs: Long,
            /** 13 个点，协议顺序（[UpperBodyLandmarkIndex.ORDER]）。 */
            val points: List<BodyPoint>,
            /** 参考实现算出的相对躯干角（度）。 */
            val expectedRoll: Float,
            val expectedYaw: Float,
            /** 参考实现平滑后的 shoulder_left（协议下标 5）x/y/z。 */
            val expectedShoulderLeft: Triple<Float, Float, Float>,
            /** 参考实现平滑后的 wrist_right（协议下标 10）x/y/z。 */
            val expectedWristRight: Triple<Float, Float, Float>,
        ) : Event()

        data class Loss(val tMs: Long) : Event()
    }

    /** 解析后的夹具。 */
    private class Fixture(
        val params: Map<String, Float>,
        val events: List<Event>,
    ) {
        fun param(name: String): Float =
            params[name] ?: error("夹具缺少 PARAM $name（$FIXTURE_RESOURCE 格式见类 KDoc）")
    }

    /** 一个有效帧跑完链路后的结果（与夹具期望值并排，便于断言消息定位）。 */
    private data class FrameResult(
        /** 有效帧序号（1 起）。 */
        val index: Int,
        val frame: Event.Frame,
        val body: TorsoAngles,
        val smoothed: List<BodyPoint>,
    )

    // ------------------------------------------------------------------ 夹具解析

    private fun loadFixture(): Fixture {
        val stream = javaClass.classLoader.getResourceAsStream(FIXTURE_RESOURCE)
            ?: error(
                "找不到类路径资源 $FIXTURE_RESOURCE：" +
                    "应位于 MediapipeUpperBody/src/test/resources/（同模块 test classpath）"
            )
        val params = LinkedHashMap<String, Float>()
        val events = ArrayList<Event>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val tokens = line.split(Regex("\\s+"))
                when (tokens[0]) {
                    "vfc-ub-post-fixture" -> assertEquals(FIXTURE_HEADER, line)
                    "PARAM" -> {
                        assertEquals("PARAM 行格式: $line", 3, tokens.size)
                        params[tokens[1]] = tokens[2].toFloat()
                    }

                    "FRAME" -> {
                        // FRAME <tMs> + 60 个浮点：52（13 点 × 4）+ 2（期望角）+ 3 + 3（期望平滑点）
                        assertEquals("FRAME 行格式: t=" + tokens[1], 62, tokens.size)
                        val values = tokens.drop(2).map { it.toFloat() }
                        val points = List(UpperBodyLandmarkIndex.COUNT) { index ->
                            BodyPoint(
                                x = values[index * 4],
                                y = values[index * 4 + 1],
                                z = values[index * 4 + 2],
                                visibility = values[index * 4 + 3],
                            )
                        }
                        events.add(
                            Event.Frame(
                                tMs = tokens[1].toLong(),
                                points = points,
                                expectedRoll = values[52],
                                expectedYaw = values[53],
                                expectedShoulderLeft = Triple(values[54], values[55], values[56]),
                                expectedWristRight = Triple(values[57], values[58], values[59]),
                            )
                        )
                    }

                    "LOSS" -> {
                        assertEquals("LOSS 行格式: $line", 2, tokens.size)
                        events.add(Event.Loss(tokens[1].toLong()))
                    }

                    "END" -> Unit
                    else -> error("夹具出现未知行: $line")
                }
            }
        }
        assertTrue("夹具必须至少有一个 FRAME", events.any { it is Event.Frame })
        return Fixture(params, events)
    }

    // -------------------------------------------------- 后处理链复刻（buildFrame 平滑段）

    /**
     * 逐行复刻 [UpperBodyPoseDetector.buildFrame] 的平滑段，跑完整条夹具。
     *
     *  - 链路 = [UpperBodySmoother] + [UpperBodyGeometry.torso] + [TorsoBaseline]；
     *  - dt 规则：无锚点 → `defaultDtSeconds`，否则
     *    `((t - lastT).coerceIn(1, maxPipelineGapMs)) / 1000f`；
     *  - `LOSS` = tracking=false：`smoother.reset()` + dt 锚点清零，**baseline 不重置**。
     */
    private fun runChain(fixture: Fixture): List<FrameResult> {
        // UpperBodySmoother 的 derivativeCutoff 固定为 OneEuroFilter 默认值：
        // 夹具必须使用同一默认值，4 参构造才与参考实现严格等价。
        assertEquals(
            "夹具 pointDerivativeCutoff 必须等于 OneEuroFilter 默认值",
            OneEuroFilter.DEFAULT_DERIVATIVE_CUTOFF.toDouble(),
            fixture.param("pointDerivativeCutoff").toDouble(),
            0.0,
        )
        assertEquals(
            "夹具 torsoDerivativeCutoff 必须等于 OneEuroFilter 默认值",
            OneEuroFilter.DEFAULT_DERIVATIVE_CUTOFF.toDouble(),
            fixture.param("torsoDerivativeCutoff").toDouble(),
            0.0,
        )

        val smoother = UpperBodySmoother(
            pointMinCutoff = fixture.param("pointMinCutoff"),
            pointBeta = fixture.param("pointBeta"),
            torsoMinCutoff = fixture.param("torsoMinCutoff"),
            torsoBeta = fixture.param("torsoBeta"),
        )
        val baseline = TorsoBaseline(fixture.param("calibrationFrames").toInt())
        val defaultDtSeconds = fixture.param("defaultDtSeconds")
        val maxPipelineGapMs = fixture.param("maxPipelineGapMs").toLong()

        val results = ArrayList<FrameResult>()
        var lastTimestampMs = 0L
        for (event in fixture.events) {
            when (event) {
                is Event.Loss -> {
                    smoother.reset()
                    lastTimestampMs = 0L
                }

                is Event.Frame -> {
                    assertTrue(
                        "夹具 FRAME 必须都满足 isTracking（t=" + event.tMs + "）",
                        UpperBodyGeometry.isTracking(event.points),
                    )
                    val dtSeconds = if (lastTimestampMs > 0L) {
                        ((event.tMs - lastTimestampMs).coerceIn(1L, maxPipelineGapMs)) / 1000f
                    } else {
                        defaultDtSeconds
                    }
                    lastTimestampMs = event.tMs

                    val smoothed = smoother.smooth(event.points, dtSeconds)
                    val rawTorso = UpperBodyGeometry.torso(smoothed)
                    val smoothTorso = smoother.smoothTorso(rawTorso, dtSeconds)
                    val relativeTorso = baseline.apply(smoothTorso)
                    val body = if (relativeTorso.isValid) relativeTorso else TorsoAngles.ZERO
                    results.add(
                        FrameResult(
                            index = results.size + 1,
                            frame = event,
                            body = body,
                            smoothed = smoothed,
                        )
                    )
                }
            }
        }
        return results
    }

    private fun assertPoint3Close(
        frameIndex: Int,
        protocolIndex: Int,
        actual: BodyPoint,
        expected: Triple<Float, Float, Float>,
    ) {
        val name = UpperBodyLandmarkIndex.nameOf(protocolIndex)
        assertEquals(
            "第 $frameIndex 帧 $name.x 平滑值必须与参考实现等价",
            expected.first.toDouble(), actual.x.toDouble(), POINT_TOLERANCE,
        )
        assertEquals(
            "第 $frameIndex 帧 $name.y 平滑值必须与参考实现等价",
            expected.second.toDouble(), actual.y.toDouble(), POINT_TOLERANCE,
        )
        assertEquals(
            "第 $frameIndex 帧 $name.z 平滑值必须与参考实现等价",
            expected.third.toDouble(), actual.z.toDouble(), POINT_TOLERANCE,
        )
    }

    // ------------------------------------------------------------------ 断言

    @Test
    fun postProcessingChainMatchesReferenceImplementation() {
        val results = runChain(loadFixture())
        for (result in results) {
            val frame = result.frame
            val where = "第 " + result.index + " 帧（t=" + frame.tMs + "）"
            assertEquals(
                "$where roll 必须与参考实现等价",
                frame.expectedRoll.toDouble(), result.body.roll.toDouble(), ANGLE_TOLERANCE_DEG,
            )
            assertEquals(
                "$where yaw 必须与参考实现等价",
                frame.expectedYaw.toDouble(), result.body.yaw.toDouble(), ANGLE_TOLERANCE_DEG,
            )
            // 抽样点：shoulder_left（协议下标 5）与 wrist_right（下标 10）的平滑后 x/y/z
            assertPoint3Close(
                result.index,
                UpperBodyLandmarkIndex.P_LEFT_SHOULDER,
                result.smoothed[UpperBodyLandmarkIndex.P_LEFT_SHOULDER],
                frame.expectedShoulderLeft,
            )
            assertPoint3Close(
                result.index,
                UpperBodyLandmarkIndex.P_RIGHT_WRIST,
                result.smoothed[UpperBodyLandmarkIndex.P_RIGHT_WRIST],
                frame.expectedWristRight,
            )
        }
    }

    @Test
    fun calibrationStartsAtZeroWithoutJump() {
        // 验收 (a)：前 10 个有效帧首帧 body = (0,0)，相邻帧 |Δ| <= 2°（不从 0 突跳到基线）
        val results = runChain(loadFixture())
        assertTrue("夹具至少要有 10 个有效帧，实际 " + results.size, results.size >= 10)

        val first = results.first().body
        assertEquals("首个有效帧 body 的 roll 必须是 0", 0.0, first.roll.toDouble(), ZERO_TOLERANCE)
        assertEquals("首个有效帧 body 的 yaw 必须是 0", 0.0, first.yaw.toDouble(), ZERO_TOLERANCE)

        var previous = TorsoAngles.ZERO
        for (index in 0 until 10) {
            val body = results[index].body
            val deltaRoll = abs(body.roll - previous.roll)
            val deltaYaw = abs(body.yaw - previous.yaw)
            assertTrue(
                "第 " + (index + 1) + " 个有效帧的 |Δroll| 必须 <= " + CALIBRATION_MAX_STEP_DEG +
                    "°，实际 " + deltaRoll,
                deltaRoll <= CALIBRATION_MAX_STEP_DEG,
            )
            assertTrue(
                "第 " + (index + 1) + " 个有效帧的 |Δyaw| 必须 <= " + CALIBRATION_MAX_STEP_DEG +
                    "°，实际 " + deltaYaw,
                deltaYaw <= CALIBRATION_MAX_STEP_DEG,
            )
            previous = body
        }
    }

    @Test
    fun coincidentShouldersStayWithinSaneAngles() {
        // 验收 (b)：两肩重合段（第 29~34 个有效帧）|roll|、|yaw| <= 20°（不出几十度假角度）
        val results = runChain(loadFixture())
        assertTrue(
            "夹具至少要有 $DEGENERATE_LAST 个有效帧，实际 " + results.size,
            results.size >= DEGENERATE_LAST,
        )
        for (frameIndex in DEGENERATE_FIRST..DEGENERATE_LAST) {
            val result = results[frameIndex - 1]
            val left = result.frame.points[UpperBodyLandmarkIndex.P_LEFT_SHOULDER]
            val right = result.frame.points[UpperBodyLandmarkIndex.P_RIGHT_SHOULDER]
            assertTrue(
                "夹具第 $frameIndex 帧必须是两肩重合段（水平跨度 < MIN_REFERENCE_SPAN）",
                abs(right.x - left.x) < UpperBodyGeometry.MIN_REFERENCE_SPAN,
            )
            assertTrue(
                "第 $frameIndex 帧（两肩重合）|roll| 必须 <= $DEGENERATE_MAX_ANGLE_DEG°，实际 " +
                    result.body.roll,
                abs(result.body.roll) <= DEGENERATE_MAX_ANGLE_DEG,
            )
            assertTrue(
                "第 $frameIndex 帧（两肩重合）|yaw| 必须 <= $DEGENERATE_MAX_ANGLE_DEG°，实际 " +
                    result.body.yaw,
                abs(result.body.yaw) <= DEGENERATE_MAX_ANGLE_DEG,
            )
        }
    }
}
