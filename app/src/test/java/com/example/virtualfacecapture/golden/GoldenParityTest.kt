/**
 * 跨端黄金对拍（task-7）：用 win-postproc 导出的 fixture 驱动 **Kotlin 原实现**，
 * 与 JS 侧 expected 逐维对拍，堵住「JS 参考实现只是第二份转录」的验收缺口——
 * android-ci 跑的是真·Kotlin 输出。
 *
 * fixture 来源：windows-client/tools/evals/golden-export/（拷贝见 resources/golden/README.md，
 * 含来源 commit 与 SHA-256）；生成器为 export-golden.mjs（确定性）。
 *
 * 口径（fixture meta 与 README 同步约定，勿改）：
 *  - 画幅 1280x720 —— 像素转换各向异性（x*W / y*H），EAR/MAR 带 H/W 因子，换画幅必假阳性；
 *  - advancedMode=false、**不调用 setCalibrationPose**（校准姿态保持默认 0）；
 *  - 时间戳为**整数毫秒** t，nowMs 与 blink 时间戳都用它（与导出侧一致，两端同一时间线）；
 *  - 起始帧率 30 fps（longDt 场景也不改 updateFrameRate —— 与导出一致）。
 *
 * 比对拆两条独立统计（绝不合并——眨眼曲线 TRACKING 态恒 0 会盖住提取差异）：
 *  1. 原始提取逐维误差：BlendShapeExtractor.extractFullBlendShapes 输出 vs expectedBs52，
 *     排除 9/10 两维（fixture 里已是眨眼曲线后的值）；
 *  2. 眨眼状态机输出误差：BlinkCurveProcessor.process 输出 vs expectedBlinkOut。
 *
 * 通过条件（Lead 终裁）：|a-b| <= max(1e-4, 1e-4*|b|)。
 */
package com.example.virtualfacecapture.golden

import com.example.commondata.FaceLandmarkIndices
import com.example.commondata.HeadPose
import com.example.commondata.Point3D
import com.live2d.facecapture.BlendShapeExtractor
import com.live2d.facecapture.BlinkCurveProcessor
import com.live2d.facecapture.SimpleMouthExtractor
import com.live2d.facecapture.SimpleSmileExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class GoldenParityTest {

    companion object {
        private const val TOL_ABS = 1e-4f
        private const val TOL_REL = 1e-4f
        private const val IMAGE_W = 1280
        private const val IMAGE_H = 720
        private const val START_FPS = 30f
        private const val FRAME_COUNT = 40
        private const val DIM_COUNT = 52
    }

    /** 单条统计的最大误差及其位置（诊断输出用，两条统计各自独立累计）。 */
    private class Stat(val name: String) {
        var maxErr = 0f
        var where: String = ""
        fun observe(err: Float, actual: Float, expected: Float, at: String) {
            if (err > maxErr) {
                maxErr = err
                where = at + " kotlin=" + actual + " js=" + expected
            }
        }
    }

    private fun loadScenario(name: String): JSONObject {
        val stream = javaClass.classLoader.getResourceAsStream("golden/" + name + ".json")
            ?: throw AssertionError("fixture 缺失: golden/" + name + ".json")
        return JSONObject(stream.bufferedReader(Charsets.UTF_8).readText())
    }

    /** base64(Float32Array little-endian, 478*3 交错 x,y,z) -> 478 个 Point3D（不重算几何）。 */
    private fun decodeLandmarks(b64: String): List<Point3D> {
        val bytes = Base64.getDecoder().decode(b64)
        assertEquals("landmark 字节数（478*3*4）", 478 * 3 * 4, bytes.size)
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertEquals("landmark 浮点数（478*3）", 478 * 3, fb.remaining())
        val out = ArrayList<Point3D>(478)
        for (i in 0 until 478) out.add(Point3D(fb.get(), fb.get(), fb.get()))
        return out
    }

    private fun dist2d(a: Point3D, b: Point3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /** fixture 解码自检（README 探针）：faceWidth=dist(33,263)=0.28，faceHeight=dist(168,152)=0.30。 */
    private fun assertGeometryProbes(lm: List<Point3D>) {
        val fw = dist2d(lm[33], lm[263])
        val fh = dist2d(lm[168], lm[152])
        assertTrue("faceWidth 探针应为 0.28，实得 " + fw, Math.abs(fw - 0.28f) <= TOL_ABS)
        assertTrue("faceHeight 探针应为 0.30，实得 " + fh, Math.abs(fh - 0.30f) <= TOL_ABS)
    }

    /** Lead 终裁通过条件：|a-b| <= max(1e-4, 1e-4*|b|)。 */
    private fun withinTol(actual: Float, expected: Float): Boolean {
        val diff = Math.abs(actual - expected)
        return diff <= Math.max(TOL_ABS, TOL_REL * Math.abs(expected))
    }

    /** 取 BlendShapeExtractor 私有 by-lazy 成员（mouth / smile 提取器未公开，测试需驱动其校准窗口）。 */
    private fun lazyValue(target: Any, fieldName: String): Any {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(target) as Lazy<Any>).value
    }

    private fun dimIndexOf(dimOrder: JSONArray, name: String): Int {
        for (i in 0 until dimOrder.length()) if (dimOrder.getString(i) == name) return i
        throw AssertionError("dimOrder 缺少 " + name)
    }

    /**
     * 跑一个场景并断言两条统计逐点通过；失败信息带 jsInternalReferenceAgreement 与
     * t/delta 时间线（Lead 要求的防御②），用于一眼分辨「基准可疑」还是「Kotlin 有差」。
     */
    private fun runScenario(name: String) {
        val json = loadScenario(name)
        assertEquals("scenario 名", name, json.getString("scenario"))
        assertEquals("画幅 width（不可换）", IMAGE_W, json.getInt("width"))
        assertEquals("画幅 height（不可换）", IMAGE_H, json.getInt("height"))

        val meta = json.getJSONObject("meta")
        assertEquals("advancedMode 口径", false, meta.getBoolean("advancedMode"))
        val tolerance = meta.getJSONObject("tolerance")
        assertEquals("容差 abs（Lead 终裁）", 1e-4, tolerance.getDouble("abs"), 1e-12)
        assertEquals("容差 rel（Lead 终裁）", 1e-4, tolerance.getDouble("rel"), 1e-12)
        val jsAgreement = if (meta.has("jsInternalReferenceAgreement"))
            meta.getDouble("jsInternalReferenceAgreement") else -1.0

        val dimOrder = meta.getJSONArray("dimOrder")
        assertEquals("dimOrder 长度", DIM_COUNT, dimOrder.length())
        val dimBlinkL = dimIndexOf(dimOrder, "eyeBlinkLeft")
        val dimBlinkR = dimIndexOf(dimOrder, "eyeBlinkRight")
        // wire 序 sanity：9/10 是眨眼替换位（README 下标表）
        assertEquals("eyeBlinkLeft 应为 wire 下标 9", 9, dimBlinkL)
        assertEquals("eyeBlinkRight 应为 wire 下标 10", 10, dimBlinkR)

        // Kotlin 原实现（advancedMode=false，绝不调用 setCalibrationPose——口径）
        val extractor = BlendShapeExtractor(advancedMode = false)
        val blink = BlinkCurveProcessor()
        blink.updateFrameRate(START_FPS)

        // 自校准窗口时间源对齐：提取器内部用 SystemClock.uptimeMillis()（JVM android.jar 桩恒 0，
        // 走不了表），而 JS 导出用 ctx.nowMs = fixture t。每帧提取前把各校准对象的
        // startTimestampMs 设为 -(t_i - t_0)，内部 isWindowReady 的
        // 「uptimeMillis() - startTimestampMs」即恰为 fixture 经过时间，与 JS 的
        // 「nowMs - startTimestampMs」逐帧同序（含 addFrame 后立即检查、窗口就绪同帧生效、
        // 眼/嘴就绪后每帧 refinish 等全部语义）。窗口门限同为 count>=10 且 elapsed>=1000ms
        // （CommonData MIN_CALIBRATION_FRAMES / CALIBRATION_DURATION_MS）。
        val eyeExtractor = extractor.simpleEyeExtractor
        val mouthExtractor = lazyValue(extractor, "simpleMouthExtractor") as SimpleMouthExtractor
        val smileExtractor = lazyValue(extractor, "simpleSmileExtractor") as SimpleSmileExtractor

        val frames = json.getJSONArray("frames")
        assertEquals("帧数", FRAME_COUNT, frames.length())

        // 防御①：t 必须是整数毫秒（防 fixture 时间口径再次漂移）
        val tList = ArrayList<Long>(frames.length())
        for (i in 0 until frames.length()) {
            val t = frames.getJSONObject(i).getDouble("t")
            assertTrue("t 必须是整数毫秒（frame " + i + " t=" + t + "）", t == Math.floor(t))
            tList.add(t.roundToLong())
        }
        // 防御②：t/delta 时间线预格式化，任何 stat② 断言失败信息都带上它
        val timeDiag = buildString {
            append("t=")
            append(tList.joinToString(","))
            append(" delta=")
            for (i in 1 until tList.size) {
                if (i > 1) append(",")
                append(tList[i] - tList[i - 1])
            }
            append(" jsInternalReferenceAgreement=").append(jsAgreement)
        }

        val bsStat = Stat("原始提取逐维")
        val blinkStat = Stat("眨眼状态机输出")
        for (i in 0 until frames.length()) {
            val frame = frames.getJSONObject(i)
            val tMs = tList[i]
            // 驱动校准窗口（见上）：frame 0 时 addFrame 会把 startTimestampMs 写为
            // uptimeMillis()=0，与 -(t_0 - t_0)=0 相同，无副作用。
            val windowShift = -(tMs - tList[0])
            eyeExtractor.imageLeftEyeCalibration.startTimestampMs = windowShift
            eyeExtractor.imageRightEyeCalibration.startTimestampMs = windowShift
            mouthExtractor.mouthCalibration.startTimestampMs = windowShift
            smileExtractor.calibration.startTimestampMs = windowShift
            val lm = decodeLandmarks(frame.getString("landmarks"))
            if (i == 0) assertGeometryProbes(lm)

            val pose = frame.getJSONArray("poseDeg")
            // 与生产同径：归一化 -> 像素（各向异性 x*W / y*H），喂原始头姿（未减基线）
            val keyPoints = FaceLandmarkIndices.extractKeyPoints(lm, IMAGE_W, IMAGE_H)
            val bs = extractor.extractFullBlendShapes(
                keyPoints, lm,
                HeadPose(
                    pose.getDouble(0).toFloat(),
                    pose.getDouble(1).toFloat(),
                    pose.getDouble(2).toFloat(),
                ),
            )
            val out = bs.toMediaPipeArray()
            // wire 序 sanity：toMediaPipeArray 下标与 fixture dimOrder 同序
            assertEquals("Kotlin 数组序 vs wire 序（left）", bs.eyeBlinkLeft, out[dimBlinkL])
            assertEquals("Kotlin 数组序 vs wire 序（right）", bs.eyeBlinkRight, out[dimBlinkR])

            val expected = frame.getJSONArray("expectedBs52")
            // ===== 统计①：原始提取逐维（排除 9/10 —— fixture 该两维是眨眼曲线后的值）=====
            for (d in 0 until DIM_COUNT) {
                if (d == dimBlinkL || d == dimBlinkR) continue
                val a = out[d]
                val b = expected.getDouble(d).toFloat()
                bsStat.observe(Math.abs(a - b), a, b, "frame " + i + " dim " + d + " (" + dimOrder.getString(d) + ")")
                assertTrue(
                    name + " 提取超差 frame=" + i + " dim=" + d + " (" + dimOrder.getString(d) + ") " +
                        "kotlin=" + a + " js=" + b + " | " + timeDiag,
                    withinTol(a, b),
                )
            }

            // ===== 统计②：眨眼状态机（原始 eyeBlink 进状态机，与导出同序）=====
            val blinkOut = blink.process(out[dimBlinkL], out[dimBlinkR], tMs)
            val expectedBlink = frame.getJSONArray("expectedBlinkOut")
            val outs = floatArrayOf(blinkOut.first, blinkOut.second)
            for (eye in 0..1) {
                val a = outs[eye]
                val b = expectedBlink.getDouble(eye).toFloat()
                blinkStat.observe(Math.abs(a - b), a, b, "frame " + i + " eye " + eye)
                assertTrue(
                    name + " 眨眼超差 frame=" + i + " eye=" + eye + " kotlin=" + a + " js=" + b +
                        " | " + timeDiag,
                    withinTol(a, b),
                )
            }
        }
        println("[golden] " + name + " 提取最大误差=" + bsStat.maxErr + " @ " + bsStat.where)
        println("[golden] " + name + " 眨眼最大误差=" + blinkStat.maxErr + " @ " + blinkStat.where)
    }

    @Test fun goldenNeutral() = runScenario("neutral")
    @Test fun goldenBlink() = runScenario("blink")
    @Test fun goldenSquint() = runScenario("squint")
    @Test fun goldenMouthOpen() = runScenario("mouthOpen")
    @Test fun goldenSmile() = runScenario("smile")
    @Test fun goldenFrown() = runScenario("frown")
    @Test fun goldenYawLeft() = runScenario("yawLeft")
    @Test fun goldenYawRight() = runScenario("yawRight")
    @Test fun goldenPitchDown() = runScenario("pitchDown")
    @Test fun goldenPitchUp() = runScenario("pitchUp")
    @Test fun goldenRollTilt() = runScenario("rollTilt")
    @Test fun goldenLongDt() = runScenario("longDt")
}
