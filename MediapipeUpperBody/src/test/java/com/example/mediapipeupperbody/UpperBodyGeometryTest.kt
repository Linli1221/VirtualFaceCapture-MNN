package com.example.mediapipeupperbody

import com.example.commondata.UpperBodyLandmarkIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpperBodyGeometry] 的 JVM 单元测试（不依赖 Android / MediaPipe）。
 *
 * 数值预期全部来自手算：
 *  - roll = atan2(dy, |dx|)，dy = y_R - y_L；
 *  - yaw  = atan2(z_L - z_R, |dx|)（正值 = 向本人左侧转）。
 */
class UpperBodyGeometryTest {

    private fun baseLandmarks(): MutableList<PoseLandmark> {
        val list = MutableList(33) { PoseLandmark(0.5f, 0.5f, 0f, 0.9f) }
        list[UpperBodyLandmarkIndex.NOSE] = PoseLandmark(0.50f, 0.18f, -0.05f, 0.99f)
        list[UpperBodyLandmarkIndex.LEFT_EYE] = PoseLandmark(0.47f, 0.20f, -0.04f, 0.99f)
        list[UpperBodyLandmarkIndex.RIGHT_EYE] = PoseLandmark(0.53f, 0.20f, -0.04f, 0.99f)
        list[UpperBodyLandmarkIndex.LEFT_EAR] = PoseLandmark(0.44f, 0.24f, -0.02f, 0.98f)
        list[UpperBodyLandmarkIndex.RIGHT_EAR] = PoseLandmark(0.56f, 0.24f, -0.02f, 0.98f)
        list[UpperBodyLandmarkIndex.LEFT_SHOULDER] = PoseLandmark(0.40f, 0.50f, -0.10f, 0.95f)
        list[UpperBodyLandmarkIndex.RIGHT_SHOULDER] = PoseLandmark(0.60f, 0.50f, -0.10f, 0.95f)
        list[UpperBodyLandmarkIndex.LEFT_ELBOW] = PoseLandmark(0.34f, 0.64f, -0.08f, 0.90f)
        list[UpperBodyLandmarkIndex.RIGHT_ELBOW] = PoseLandmark(0.66f, 0.64f, -0.08f, 0.90f)
        list[UpperBodyLandmarkIndex.LEFT_WRIST] = PoseLandmark(0.31f, 0.78f, -0.06f, 0.85f)
        list[UpperBodyLandmarkIndex.RIGHT_WRIST] = PoseLandmark(0.69f, 0.78f, -0.06f, 0.85f)
        list[UpperBodyLandmarkIndex.LEFT_HIP] = PoseLandmark(0.42f, 0.82f, 0f, 0.80f)
        list[UpperBodyLandmarkIndex.RIGHT_HIP] = PoseLandmark(0.58f, 0.82f, 0f, 0.80f)
        return list
    }

    @Test
    fun extractMapsLandmarksInProtocolOrder() {
        val points = UpperBodyGeometry.extract(baseLandmarks())
        assertNotNull(points)
        val actual = points!!
        assertEquals(UpperBodyLandmarkIndex.COUNT, actual.size)
        assertEquals(0.50f, actual[UpperBodyLandmarkIndex.P_NOSE].x, EPS)
        assertEquals(0.18f, actual[UpperBodyLandmarkIndex.P_NOSE].y, EPS)
        assertEquals(0.31f, actual[UpperBodyLandmarkIndex.P_LEFT_WRIST].x, EPS)
        assertEquals(0.69f, actual[UpperBodyLandmarkIndex.P_RIGHT_WRIST].x, EPS)
        assertEquals(0.42f, actual[UpperBodyLandmarkIndex.P_LEFT_HIP].x, EPS)
        assertEquals(0.58f, actual[UpperBodyLandmarkIndex.P_RIGHT_HIP].x, EPS)
    }

    @Test
    fun extractReturnsNullWhenNotEnoughLandmarks() {
        assertNull(UpperBodyGeometry.extract(null))
        assertNull(UpperBodyGeometry.extract(emptyList()))
        // 只到 LEFT_HIP（23），缺 RIGHT_HIP（24）
        assertNull(UpperBodyGeometry.extract(baseLandmarks().subList(0, 24)))
    }

    @Test
    fun qualityIsMeanVisibility() {
        val points = UpperBodyGeometry.extract(baseLandmarks())!!
        // 13 个点的可见度：0.99×3 + 0.98×2 + 0.95×2 + 0.90×2 + 0.85×2 + 0.80×2 = 11.93
        assertEquals(11.93f / 13f, UpperBodyGeometry.quality(points), 1e-4f)
    }

    @Test
    fun trackingNeedsSevenReliablePoints() {
        val sixReliable = baseLandmarks()
        for (slot in 0 until UpperBodyLandmarkIndex.COUNT) {
            val index = UpperBodyLandmarkIndex.ORDER[slot]
            sixReliable[index] = sixReliable[index].copy(visibility = if (slot < 6) 0.9f else 0.4f)
        }
        assertFalse(UpperBodyGeometry.isTracking(UpperBodyGeometry.extract(sixReliable)))

        val sevenReliable = baseLandmarks()
        for (slot in 0 until UpperBodyLandmarkIndex.COUNT) {
            val index = UpperBodyLandmarkIndex.ORDER[slot]
            sevenReliable[index] = sevenReliable[index].copy(visibility = if (slot < 7) 0.9f else 0.4f)
        }
        assertTrue(UpperBodyGeometry.isTracking(UpperBodyGeometry.extract(sevenReliable)))
    }

    @Test
    fun torsoIsZeroForLevelShouldersAndEqualDepth() {
        val torso = UpperBodyGeometry.torso(UpperBodyGeometry.extract(baseLandmarks()))
        assertEquals(0f, torso.roll, 1e-4f)
        assertEquals(0f, torso.yaw, 1e-4f)
    }

    @Test
    fun rollIsPositiveWhenRightShoulderSinks() {
        val landmarks = baseLandmarks()
        landmarks[UpperBodyLandmarkIndex.RIGHT_SHOULDER] = PoseLandmark(0.60f, 0.55f, -0.10f, 0.95f)
        val torso = UpperBodyGeometry.torso(UpperBodyGeometry.extract(landmarks))
        // atan2(0.05, 0.20) = 14.036°
        assertEquals(14.036f, torso.roll, 0.01f)
    }

    @Test
    fun rollUsesAbsoluteHorizontalSpanRegardlessOfScreenSide() {
        // 右肩在画面左侧（x_R < x_L）时，右肩下沉仍应为正 roll
        val landmarks = baseLandmarks()
        landmarks[UpperBodyLandmarkIndex.LEFT_SHOULDER] = PoseLandmark(0.60f, 0.50f, -0.10f, 0.95f)
        landmarks[UpperBodyLandmarkIndex.RIGHT_SHOULDER] = PoseLandmark(0.40f, 0.55f, -0.10f, 0.95f)
        val torso = UpperBodyGeometry.torso(UpperBodyGeometry.extract(landmarks))
        assertEquals(14.036f, torso.roll, 0.01f)
    }

    @Test
    fun yawIsPositiveWhenTurningToOwnLeft() {
        val landmarks = baseLandmarks()
        // 向右本人左侧转：右肩更靠近镜头（MediaPipe z 越小越近）
        landmarks[UpperBodyLandmarkIndex.LEFT_SHOULDER] = PoseLandmark(0.40f, 0.50f, -0.10f, 0.95f)
        landmarks[UpperBodyLandmarkIndex.RIGHT_SHOULDER] = PoseLandmark(0.60f, 0.50f, -0.20f, 0.95f)
        val torso = UpperBodyGeometry.torso(UpperBodyGeometry.extract(landmarks))
        // atan2(0.10, 0.20) = 26.565°
        assertEquals(26.565f, torso.yaw, 0.01f)

        val opposite = baseLandmarks()
        opposite[UpperBodyLandmarkIndex.LEFT_SHOULDER] =
            PoseLandmark(0.40f, 0.50f, -0.20f, 0.95f)
        opposite[UpperBodyLandmarkIndex.RIGHT_SHOULDER] =
            PoseLandmark(0.60f, 0.50f, -0.10f, 0.95f)
        assertTrue(UpperBodyGeometry.torso(UpperBodyGeometry.extract(opposite)).yaw < 0f)
    }

    @Test
    fun torsoFallsBackToElbowsWhenShouldersAreInvisible() {
        val landmarks = baseLandmarks()
        landmarks[UpperBodyLandmarkIndex.LEFT_SHOULDER] =
            landmarks[UpperBodyLandmarkIndex.LEFT_SHOULDER].copy(visibility = 0.1f)
        landmarks[UpperBodyLandmarkIndex.RIGHT_SHOULDER] =
            landmarks[UpperBodyLandmarkIndex.RIGHT_SHOULDER].copy(visibility = 0.1f)
        landmarks[UpperBodyLandmarkIndex.LEFT_ELBOW] = PoseLandmark(0.35f, 0.60f, 0f, 0.9f)
        landmarks[UpperBodyLandmarkIndex.RIGHT_ELBOW] = PoseLandmark(0.65f, 0.70f, 0f, 0.9f)
        val torso = UpperBodyGeometry.torso(UpperBodyGeometry.extract(landmarks))
        // atan2(0.10, 0.30) = 18.435°
        assertEquals(18.435f, torso.roll, 0.01f)
        assertEquals(0f, torso.yaw, 1e-4f)
    }

    @Test
    fun torsoIsZeroWhenNoPairIsVisible() {
        val landmarks = baseLandmarks()
        for (index in UpperBodyLandmarkIndex.ORDER) {
            landmarks[index] = landmarks[index].copy(visibility = 0.1f)
        }
        val torso = UpperBodyGeometry.torso(UpperBodyGeometry.extract(landmarks))
        assertEquals(0f, torso.roll, 1e-4f)
        assertEquals(0f, torso.yaw, 1e-4f)
    }

    @Test
    fun nonFiniteCoordinatesAreSanitized() {
        val landmarks = baseLandmarks()
        landmarks[UpperBodyLandmarkIndex.LEFT_WRIST] =
            PoseLandmark(Float.NaN, Float.NaN, Float.NaN, 0.9f)
        val points = UpperBodyGeometry.extract(landmarks)
        assertNotNull(points)
        val wrist = points!![UpperBodyLandmarkIndex.P_LEFT_WRIST]
        assertEquals(0f, wrist.x, 0f)
        assertEquals(0f, wrist.y, 0f)
        assertEquals(0f, wrist.z, 0f)
        assertEquals(0f, wrist.visibility, 0f)
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
