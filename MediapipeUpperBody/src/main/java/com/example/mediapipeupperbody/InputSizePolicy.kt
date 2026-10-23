package com.example.mediapipeupperbody

/**
 * 送进 PoseLandmarker 的 Bitmap 长边档位（A/B 与自适应策略）。
 *
 *  - [PRECISION]：固定 320 —— **优先精度**（A/B 的「320」组，也是 ROI 改造后的固定值）；
 *  - [FRAME_RATE]：固定 256 —— **优先帧率**（A/B 的「256」组；长边 256 的像素量是 320 的 64%）；
 *  - [ADAPTIVE]（默认）：正常跟踪用 256，关键点质量下降 / 脸框变小（人变远）/ ROI miss 时
 *    临时升到 320，连续 [InputSizePolicy] 的 exitFrames 帧质量恢复后再回落 256。
 *
 * 只改输入预处理尺寸，**不改模型**；坐标回映（[UpperBodyRoi.mapLocalToPreview]）与长边无关，
 * 三种档位的协议输出语义完全一致。
 */
enum class InputSizeMode {
    /** 固定 320：优先精度。 */
    PRECISION,

    /** 固定 256：优先帧率。 */
    FRAME_RATE,

    /** 自适应：256 起步，质量下降 / 脸框变小 / ROI miss 升 320，质量恢复后回落。 */
    ADAPTIVE,
}

/**
 * 输入长边的升降档状态机（纯 Kotlin，JVM 可测）。
 *
 * 决策分两拍：
 *  1. **提交前**（相机线程，[selectLongSide]）：按当前档位 + 脸框变小 / ROI 回退窗口选定本帧长边；
 *     脸框变小或 ROI miss 是「立刻需要更多像素」的信号，当场升档，不等结果；
 *  2. **结果后**（MediaPipe 回调线程，[onFrameResult]）：按关键点质量维护滞回 ——
 *     质量差 / 没跟踪到 → 立刻升档；只有**连续 [exitFrames] 帧**质量都够好才回落，
 *     中间带（[enterQuality, exitQuality)）保持现状，避免 256/320 来回抖。
 *
 * 线程：两个入口都 @Synchronized（调用频率 ~15Hz，锁竞争可忽略）。
 *
 * @param normalLongSide 质量正常时的长边（256，优先帧率）
 * @param boostLongSide 质量吃紧时的长边（320，优先精度）
 * @param enterQuality 低于它算「质量差」，立刻升档（13 点平均可见度）
 * @param exitQuality 高于它才算「质量好」，计入恢复连击
 * @param exitFrames 连续多少帧质量好才允许从 320 回落 256
 * @param smallFaceAreaFraction 脸框面积（归一化）低于它算「人变远」，立刻升档；0 = 不启用
 */
internal class InputSizePolicy(
    val normalLongSide: Int = DEFAULT_NORMAL_LONG_SIDE,
    val boostLongSide: Int = DEFAULT_BOOST_LONG_SIDE,
    private val enterQuality: Float = DEFAULT_ENTER_QUALITY,
    private val exitQuality: Float = DEFAULT_EXIT_QUALITY,
    private val exitFrames: Int = DEFAULT_EXIT_FRAMES.coerceAtLeast(1),
    private val smallFaceAreaFraction: Float = DEFAULT_SMALL_FACE_AREA_FRACTION,
) {

    @Volatile
    var mode: InputSizeMode = InputSizeMode.ADAPTIVE
        set(value) {
            if (field == value) return
            field = value
            reset()
        }

    /** 当前是否处于升档（320）状态。 */
    private var boosted: Boolean = false

    /** 连续「质量好」帧数（回落 256 的连击）。 */
    private var goodStreak: Int = 0

    @Synchronized
    fun isBoosted(): Boolean = boosted

    /** 清空升降档状态（换模式 / start / release / 重建 landmarker 时调用）。 */
    @Synchronized
    fun reset() {
        boosted = false
        goodStreak = 0
    }

    /**
     * 选定本帧的输入长边（相机线程，先于转换）。
     *
     * 脸框面积小于 [smallFaceAreaFraction]（人变远、关键点天然更难）或整帧回退窗口
     * 正在生效（ROI miss）时**立刻升档**，并让后续结果连击清零。
     *
     * @param faceAreaFraction 同帧脸框的归一化面积（宽×高）；0 / 非法 = 这一帧没有脸框提示
     * @param roiFallbackActive ROI miss 的整帧回退窗口是否生效
     */
    @Synchronized
    fun selectLongSide(faceAreaFraction: Float, roiFallbackActive: Boolean): Int {
        when (mode) {
            InputSizeMode.PRECISION -> return boostLongSide
            InputSizeMode.FRAME_RATE -> return normalLongSide
            InputSizeMode.ADAPTIVE -> Unit
        }
        val faceTooSmall = faceAreaFraction.isFinite() &&
            faceAreaFraction > 0f &&
            faceAreaFraction < smallFaceAreaFraction
        if (faceTooSmall || roiFallbackActive) {
            boosted = true
            goodStreak = 0
        }
        return if (boosted) boostLongSide else normalLongSide
    }

    /**
     * 用推理结果维护升降档（MediaPipe 回调线程）。
     *
     * @param tracked 本帧是否跟踪到上半身（[UpperBodyGeometry.isTracking]）
     * @param quality 13 点平均可见度（[UpperBodyGeometry.quality]）
     */
    @Synchronized
    fun onFrameResult(tracked: Boolean, quality: Float) {
        if (mode != InputSizeMode.ADAPTIVE) return
        val q = if (quality.isFinite()) quality.coerceIn(0f, 1f) else 0f
        if (!tracked || q < enterQuality) {
            // 质量差 / 直接丢目标：立刻升档救精度
            boosted = true
            goodStreak = 0
            return
        }
        if (q >= exitQuality) {
            goodStreak++
            if (boosted && goodStreak >= exitFrames) {
                boosted = false
                goodStreak = 0
            }
        } else {
            // 中间带：保持现状，恢复连击清零（宁可多留一会儿 320，也不来回抖）
            goodStreak = 0
        }
    }

    companion object {
        /** 质量正常时的长边：优先帧率。 */
        const val DEFAULT_NORMAL_LONG_SIDE = 256

        /** 质量吃紧时的长边：优先精度。 */
        const val DEFAULT_BOOST_LONG_SIDE = 320

        /** 低于它立刻升档的平均可见度。 */
        const val DEFAULT_ENTER_QUALITY = 0.5f

        /** 高于它才计入「恢复连击」的平均可见度。 */
        const val DEFAULT_EXIT_QUALITY = 0.7f

        /** 连续多少帧质量好才从 320 回落 256（15fps 下约 0.4s）。 */
        const val DEFAULT_EXIT_FRAMES = 6

        /** 脸框归一化面积（宽×高）低于它算「人变远」（≈ 脸宽高都小于帧的 1/10）。 */
        const val DEFAULT_SMALL_FACE_AREA_FRACTION = 0.01f
    }
}
