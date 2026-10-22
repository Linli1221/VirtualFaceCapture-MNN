package com.facecapture.sdk

import java.util.TreeMap

/**
 * 帧重排缓冲：把 GPU 多路并行推理产生的**乱序结果**按 frameId 排回时间顺序。
 *
 * 为什么需要它：开启多路并行后，同一帧可能被任意一路引擎处理，各路的耗时不同，
 * 结果到达顺序就不再等于采集顺序。下游的后处理（校准基线、眨眼曲线、
 * 时序滤波）以及发给 PC 的 `seq` 都假定「帧是递增的」，所以必须先排好序。
 *
 * 语义：
 *  - 已经发出过的 frameId（或更旧的）直接丢弃 —— 迟到的帧没有价值；
 *  - 收到第一个结果时立刻放行，不会为了追求严格连续而空等；
 *  - 之后出现缺口（expected 还没到）时最多等 [gapTimeoutMs]，超时或
 *    [maxPending] 堆积过多就接受缺口、直接放行手里最旧的一帧，避免为了等
 *    一帧把整条链路拖慢。
 *
 * 纯逻辑、无协程 / 无 Android 依赖，[System.nanoTime] 之类的时钟由调用方传入，
 * 因此可以在 JVM 单测里用假时钟精确验证。
 *
 * @param maxPending 最多缓存多少帧（超出即判定为「缺口不可能补上」）
 * @param gapTimeoutMs 等待缺口的时长；<=0 表示不等待，永远按到达顺序放行
 */
internal class FrameReorderBuffer<T>(
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val gapTimeoutMs: Long = DEFAULT_GAP_TIMEOUT_MS,
) {

    companion object {
        const val DEFAULT_MAX_PENDING = 8
        const val DEFAULT_GAP_TIMEOUT_MS = 60L
    }

    /** 待放行帧，按 frameId 升序。 */
    private val pending = TreeMap<Long, T>()

    /** 最近一次放行的 frameId；[Long.MIN_VALUE] 表示还没放行过任何帧。 */
    private var lastEmittedId = Long.MIN_VALUE

    /** 当前缺口的起始时间（毫秒），0 表示没有缺口。 */
    private var gapStartMs = 0L

    /** 堆积超过上限时置位：下一次 drain 直接跳过缺口。 */
    private var forceSkipGap = false

    /** 当前缓存深度（诊断 / 单测用）。 */
    val size: Int get() = pending.size

    /** 是否因为缺口正在等待。 */
    val isWaitingForGap: Boolean get() = gapStartMs != 0L

    /**
     * 收到一帧乱序结果，返回**本次可以按顺序放行**的帧（可能为空 / 可能多帧）。
     *
     * @param nowMs 当前时刻（毫秒），用于缺口超时判定
     */
    fun offer(frameId: Long, value: T, nowMs: Long): List<T> {
        // 已经放行过的不再重复；同一帧重复投递也只保留第一份。
        if (lastEmittedId != Long.MIN_VALUE && frameId <= lastEmittedId) return emptyList()
        if (pending.containsKey(frameId)) return emptyList()

        pending[frameId] = value
        if (pending.size > maxPending) {
            // 堆积超过上限说明缺口补不上了，强制跳过它（下一轮 drain 生效）。
            forceSkipGap = true
        }
        return drain(nowMs)
    }

    /**
     * 缺口超时检查。调用方在没有新帧到达时按需轮询，避免为了等一帧永久阻塞。
     */
    fun poll(nowMs: Long): List<T> = drain(nowMs)

    /** 清空（停止 / 重启管线时调用，避免旧会话的帧混进来）。 */
    fun reset() {
        pending.clear()
        lastEmittedId = Long.MIN_VALUE
        gapStartMs = 0L
        forceSkipGap = false
    }

    // ---------------------------------------------------------------------

    private fun drain(nowMs: Long): List<T> {
        if (pending.isEmpty()) {
            gapStartMs = 0L
            return emptyList()
        }

        val out = ArrayList<T>(pending.size)
        while (pending.isNotEmpty()) {
            // firstEntry() 是 Kotlin 可空类型，空表时返回 null（firstKey() 会抛异常）。
            val firstEntry = pending.firstEntry() ?: break
            val firstId = firstEntry.key

            if (lastEmittedId == Long.MIN_VALUE) {
                // 首个结果直接放行，不空等前一帧。
                out.add(pending.remove(firstId)!!)
                lastEmittedId = firstId
                gapStartMs = 0L
                continue
            }

            val expected = lastEmittedId + 1
            when {
                firstId == expected -> {
                    out.add(pending.remove(firstId)!!)
                    lastEmittedId = firstId
                    gapStartMs = 0L
                }

                firstId > expected -> {
                    if (gapStartMs == 0L) gapStartMs = nowMs
                    val timedOut = forceSkipGap ||
                        gapTimeoutMs <= 0L || nowMs - gapStartMs >= gapTimeoutMs
                    if (!timedOut) break
                    // 缺口超时 / 堆积过多：接受丢帧，直接放行手里最旧的一帧。
                    out.add(pending.remove(firstId)!!)
                    lastEmittedId = firstId
                    gapStartMs = 0L
                    forceSkipGap = false
                }

                else -> {
                    // 理论上不可达（offer 已经拦掉过期帧），保险起见丢弃。
                    pending.remove(firstId)
                }
            }
        }
        return out
    }
}
