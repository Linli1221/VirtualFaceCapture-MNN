package com.example.mediapipeupperbody

import java.util.LinkedHashMap

/**
 * YUV → ARGB 的采样坐标表 / 平面索引（纯 Kotlin，JVM 可测）。
 *
 * [YuvBitmapConverter] 每帧要做的映射链是：输出像素 (outX,outY) → 窗口局部坐标 →
 * 旋转后像素 → 源像素 (srcX,srcY) → Y/U/V 平面字节下标。旧实现每帧重建
 * rotatedX / rotatedY 两张表，并在**每个输出像素**里重新调用 [YuvGeometry.sourceX] /
 * [YuvGeometry.sourceY]、重算 UV 地址。这里把整条映射拆成三层，使内层循环只剩数组寻址：
 *
 *  1. **坐标表 [YuvSampleTable]**：每个输出列 / 行对应的**窗口局部**坐标（镜像已折叠）。
 *     只依赖几何形状，**不依赖 ROI 摆放位置**，按「源尺寸 + ROI 尺寸 + rotation +
 *     mirror + 输出尺寸」缓存（[YuvSampleCache]），ROI 随脸平移时缓存照样命中；
 *  2. **平面索引 [YuvPlaneIndex]**：把旋转映射 + ROI 原点 + 三个 stride 摊平成
 *     rowY / colY / rowUv / colUv 四张小表，满足：
 *
 *         yIndex  = rowY[outY] + colY[outX]
 *         uvIndex = rowUv[outY] + colUv[outX]
 *
 *     旋转 0/180° 时源 X 由列决定、源 Y 由行决定；90/270° 时两者互换（转置），两种情况
 *     都可以行列分解，包括色度 subsample 的 shr 1（在行 / 列坐标上各做一次即可）。
 *     每帧只需 O(outWidth + outHeight) 摊平；几何 / 原点 / stride 全都没变时直接复用
 *     上一帧的四张表（见 [YuvPlaneIndex.fill] 的免算快路径）；
 *  3. **内层循环**（[YuvBitmapConverter]）：两次数组寻址 + 加法 + YUV → ARGB，
 *     不再有任何 per-pixel 几何计算。
 *
 * 与旧实现的逐位等价性在 [YuvSampleIndexTest] 里与 [YuvGeometry] 逐像素对拍
 * （覆盖 4 个旋转、镜像、ROI 原点偏移、planar / semi-planar UV、带 padding 的 stride）。
 *
 * 后续优化方向（不在本类）：JNI / NEON 内核做第 3 层；Bitmap 侧只能做带生命周期确认的
 * 2~3 张环形池（MediaPipe LIVE_STREAM 异步持有当前 Bitmap，不能简单复用同一张）。
 *
 * 线程：不自带锁；[YuvBitmapConverter.convert] 用 @Synchronized 串行化访问。
 */

/**
 * 采样坐标表的缓存键：源尺寸 + ROI 尺寸 + rotation + mirror + 输出尺寸。
 *
 * **故意不含 ROI 原点 (windowX, windowY) 与 stride**：坐标表只描述「形状映射」，
 * 摆放位置和 stride 在 [YuvPlaneIndex.fill] 里摊平，这样 ROI 平移不会击穿缓存。
 */
internal data class YuvSampleKey(
    val srcWidth: Int,
    val srcHeight: Int,
    val windowWidth: Int,
    val windowHeight: Int,
    val rotation: Int,
    val mirror: Boolean,
    val outWidth: Int,
    val outHeight: Int,
)

/**
 * 输出行列 → 窗口局部坐标的映射表（镜像已折叠进 [colLocalX]）。
 *
 * 坐标语义（与旧 rotatedX / rotatedY 逐位一致）：
 *  - 列：rotatedX = windowX + colLocalX[outX]（mirror 时 colLocalX 从窗口右缘倒序）；
 *  - 行：rotatedY = windowY + rowLocalY[outY]。
 */
internal class YuvSampleTable private constructor(
    val key: YuvSampleKey,
    /** 每个输出列对应的窗口局部 X，范围 [0, windowWidth)。 */
    val colLocalX: IntArray,
    /** 每个输出行对应的窗口局部 Y，范围 [0, windowHeight)。 */
    val rowLocalY: IntArray,
) {
    companion object {
        /** 按 [key] 构造坐标表；只做 O(outWidth + outHeight) 的整数除法。 */
        fun build(key: YuvSampleKey): YuvSampleTable {
            val colLocalX = IntArray(key.outWidth) { outX ->
                val local = (outX * key.windowWidth) / key.outWidth
                if (key.mirror) key.windowWidth - 1 - local else local
            }
            val rowLocalY = IntArray(key.outHeight) { outY ->
                (outY * key.windowHeight) / key.outHeight
            }
            return YuvSampleTable(key, colLocalX, rowLocalY)
        }
    }
}

/**
 * 行 / 列分解后的 Y / UV 平面字节索引（每帧摊平、跨帧复用四张数组）。
 *
 * 使用方式（[YuvBitmapConverter] 的内层循环）：
 *
 *     val yIndex = index.rowY[outY] + index.colY[outX]
 *     val uvIndex = index.rowUv[outY] + index.colUv[outX]
 */
internal class YuvPlaneIndex(val outWidth: Int, val outHeight: Int) {

    /** 行分量：rowY[outY] + colY[outX] = Y 平面字节下标。 */
    val rowY: IntArray = IntArray(outHeight)

    /** 列分量：与 [rowY] 相加得到 Y 平面字节下标。 */
    val colY: IntArray = IntArray(outWidth)

    /** 行分量：rowUv[outY] + colUv[outX] = U / V 平面字节下标。 */
    val rowUv: IntArray = IntArray(outHeight)

    /** 列分量：与 [rowUv] 相加得到 U / V 平面字节下标。 */
    val colUv: IntArray = IntArray(outWidth)

    // 上次摊平时的参数；全部一致时 fill() 直接复用，不重算（坐标 / stride 未变的稳态帧零开销）。
    private var filledTable: YuvSampleTable? = null
    private var filledWindowX: Int = Int.MIN_VALUE
    private var filledWindowY: Int = Int.MIN_VALUE
    private var filledYRowStride: Int = -1
    private var filledUvRowStride: Int = -1
    private var filledUvPixelStride: Int = -1

    /** 是否已摊平过（测试 / 断言用）。 */
    val isFilled: Boolean get() = filledTable != null

    /** 真正重算的次数（测试用；免算快路径不计数）。 */
    var fillCount: Int = 0
        private set

    /**
     * 用 [table] + ROI 原点 + 三个 stride 摊平四张索引表。
     *
     * 旋转映射与 [YuvGeometry.sourceX] / [YuvGeometry.sourceY] 逐位一致：
     *  - 0°：源 X 由列决定、源 Y 由行决定；
     *  - 180°：两者各自翻转；
     *  - 90° / 270°：转置 —— 源 X 由行决定、源 Y 由列决定。
     *
     * 前置条件：窗口落在帧内（由 [RoiWindow.fitsWithin] / 整帧回退保证）；本方法不做
     * 防御性 clamp，越界下标由调用方的 per-pixel 限幅兜底（与旧实现相同）。
     */
    fun fill(
        table: YuvSampleTable,
        windowX: Int,
        windowY: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
    ) {
        if (filledTable === table &&
            filledWindowX == windowX &&
            filledWindowY == windowY &&
            filledYRowStride == yRowStride &&
            filledUvRowStride == uvRowStride &&
            filledUvPixelStride == uvPixelStride
        ) {
            return
        }

        val key = table.key
        val rotation = YuvGeometry.normalizeRotation(key.rotation)
        if (rotation == 0 || rotation == 180) {
            // 源 X ← 列，源 Y ← 行。
            for (outX in 0 until outWidth) {
                val rotatedX = windowX + table.colLocalX[outX]
                val srcX = if (rotation == 0) rotatedX else key.srcWidth - 1 - rotatedX
                colY[outX] = srcX
                colUv[outX] = (srcX shr 1) * uvPixelStride
            }
            for (outY in 0 until outHeight) {
                val rotatedY = windowY + table.rowLocalY[outY]
                val srcY = if (rotation == 0) rotatedY else key.srcHeight - 1 - rotatedY
                rowY[outY] = srcY * yRowStride
                rowUv[outY] = (srcY shr 1) * uvRowStride
            }
        } else {
            // 90° / 270°：转置 —— 源 X ← 行，源 Y ← 列。
            for (outY in 0 until outHeight) {
                val rotatedY = windowY + table.rowLocalY[outY]
                val srcX = if (rotation == 90) rotatedY else key.srcWidth - 1 - rotatedY
                rowY[outY] = srcX
                rowUv[outY] = (srcX shr 1) * uvPixelStride
            }
            for (outX in 0 until outWidth) {
                val rotatedX = windowX + table.colLocalX[outX]
                val srcY = if (rotation == 90) key.srcHeight - 1 - rotatedX else rotatedX
                colY[outX] = srcY * yRowStride
                colUv[outX] = (srcY shr 1) * uvRowStride
            }
        }

        filledTable = table
        filledWindowX = windowX
        filledWindowY = windowY
        filledYRowStride = yRowStride
        filledUvRowStride = uvRowStride
        filledUvPixelStride = uvPixelStride
        fillCount++
    }
}

/**
 * [YuvSampleTable] 的小型 LRU 缓存（容量默认 4：整帧 256 / 320 两档 + 两个 ROI 形状）。
 *
 * 键 = 几何形状（[YuvSampleKey]，不含 ROI 原点 / stride），所以 ROI 随脸平移、相机
 * stride 波动都不会击穿；只有 ROI 尺寸 / 旋转 / 镜像 / 输出尺寸变化才会重建一张
 * O(outWidth + outHeight) 的小表。
 *
 * 线程：调用方串行化（[YuvBitmapConverter] 的 @Synchronized）。
 */
internal class YuvSampleCache(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= 1) { "capacity 必须 >= 1" }
    }

    private val entries = object : LinkedHashMap<YuvSampleKey, YuvSampleTable>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<YuvSampleKey, YuvSampleTable>): Boolean =
            size > capacity
    }

    /** 取（或构建）一张坐标表；命中时返回同一实例。 */
    fun obtain(key: YuvSampleKey): YuvSampleTable {
        val cached = entries[key]
        if (cached != null) return cached
        val built = YuvSampleTable.build(key)
        entries[key] = built
        return built
    }

    /** 当前缓存条目数。 */
    val size: Int get() = entries.size

    /** 该键是否已在缓存里（测试 / 统计用）。 */
    fun contains(key: YuvSampleKey): Boolean = entries.containsKey(key)

    companion object {
        const val DEFAULT_CAPACITY = 4
    }
}
