package com.vfc.log

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log as AndroidLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * 带文件落盘的日志门面（debug 包专用的「导出日志」功能的数据源）。
 *
 * 为什么需要它：
 * Android 4.1 之后普通 App 没有 `READ_LOGS` 权限，`Runtime.exec("logcat")` 读不到自己的
 * 日志，所以**没法在 App 内直接抓 logcat**。这里在每次 `Log.x()` 的同时，把同一行
 * 异步追加到 `filesDir/logs/vfc.log`，导出功能只需打包这些文件即可。
 *
 * 设计要点：
 * - **零侵入**：外部代码用 `import com.vfc.log.VfcLog as Log` 就能保持 `Log.i(TAG, ...)`
 *   的写法不变，不需要逐行改调用点。
 * - **默认关闭**：只有 debug 包（[init] 传 `enable = true`）才落盘；release 包里
 *   [enabled] 恒为 false，调用只多一次 volatile 读，行为与系统 Log 完全一致。
 * - **不阻塞调用方**：写文件在单线程池（队列 4096）里串行执行，队列满直接丢弃并计数，
 *   绝不反压相机 / 推理线程。
 * - **不抛异常**：写盘失败只关掉当前文件，连续失败 5 次后自动停用，永不把日志问题
 *   变成 App 崩溃。
 * - **可导出**：导出时先 [flush] 再用 [withWriteLock] 读文件，保证读到的是完整行。
 *
 * 日志格式：`2026-10-23 22:48:56.123 I/Tag(pid-tid): message`
 */
object VfcLog {

    // 与 android.util.Log 的级别常量保持一致（供 Log.isLoggable 之类的调用点使用）
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    private const val TAG = "VfcLog"
    private const val DIR_NAME = "logs"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_FILES = 3
    private const val QUEUE_CAPACITY = 4096
    private const val MAX_WRITE_FAILURES = 5

    @Volatile
    private var enabled = false

    /** 是否已经创建过线程池；用于 init 幂等（写盘失败自动停用后仍可重新启用）。 */
    @Volatile
    private var started = false

    @Volatile
    private var logDirectory: File? = null

    @Volatile
    private var executor: ThreadPoolExecutor? = null

    /** 只有日志线程会写文件；导出时持有这把锁，避免「边写边读」。 */
    private val writeLock = ReentrantLock()

    private val dropped = AtomicInteger(0)

    /** 因轮转失败被迫截断文件的次数；导出时写进 app-info.txt，提示日志可能不完整。 */
    private val forcedRotations = AtomicInteger(0)

    /** 以下状态只在持有 [writeLock] 时访问。 */
    private var currentFile: File? = null
    private var writer: BufferedWriter? = null
    private var writeFailures = 0

    private val pid: Int = Process.myPid()

    private val lineFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * 初始化文件日志（应由 `Application.onCreate` 调用，保证早于任何业务日志）。
     *
     * @param enable 传 false 表示完全不落盘（release 包）
     */
    @Synchronized
    fun init(context: Context, enable: Boolean = true) {
        if (!enable) {
            enabled = false
            return
        }
        // 已经初始化过：只恢复 enabled（例如连续写盘失败自动停用后再次 init）
        if (started) {
            enabled = true
            writeLock.lock()
            try {
                writeFailures = 0
            } finally {
                writeLock.unlock()
            }
            return
        }
        if (enabled) return

        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            AndroidLog.w(TAG, "无法创建日志目录: " + dir.absolutePath)
            return
        }
        logDirectory = dir

        val pool = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),
            ThreadFactory { runnable ->
                Thread(runnable, "vfc-log").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
        )
        // 队列满时丢弃而不是阻塞调用方；丢弃条数会写进导出的 app-info.txt
        pool.rejectedExecutionHandler = RejectedExecutionHandler { _, _ -> dropped.incrementAndGet() }
        executor = pool
        enabled = true
        started = true

        i(TAG, "文件日志已开启: " + dir.absolutePath)
        i(
            TAG,
            "===== 应用启动 pid=$pid ${Build.MANUFACTURER} ${Build.MODEL} / " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ====="
        )
    }

    /** 是否正在写文件（release 包恒为 false）。 */
    fun isEnabled(): Boolean = enabled

    /** 日志目录；未初始化时为 null。 */
    fun logDir(): File? = logDirectory

    /** 停止文件日志（进程退出 / 测试用）。之后调用只走系统 logcat。 */
    @Synchronized
    fun close() {
        flush(1000)
        enabled = false
        started = false
        val pool = executor
        executor = null
        pool?.shutdownNow()
        try {
            pool?.awaitTermination(500, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        writeLock.lock()
        try {
            closeWriterLocked()
            currentFile = null
        } finally {
            writeLock.unlock()
        }
    }

    /** 因队列满被丢弃的行数。 */
    fun droppedLineCount(): Int = dropped.get()

    /** 因轮转失败被迫截断文件的次数（>0 表示导出的日志可能不完整）。 */
    fun forcedRotationCount(): Int = forcedRotations.get()

    /**
     * 等待队列里已有的日志全部写完（导出前调用）。
     *
     * @param timeoutMs 最长等待时间，超时直接返回（导出仍可进行）
     */
    fun flush(timeoutMs: Long = 3000) {
        val pool = executor ?: return
        val latch = CountDownLatch(1)
        try {
            pool.execute { latch.countDown() }
        } catch (e: RejectedExecutionException) {
            // 队列满 / 已关闭：没什么可等的
            return
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 在持有日志写锁的情况下执行 [block]。
     *
     * 导出时用：先 [flush]，再在锁内读日志文件，期间日志线程只会排队不会写，
     * 保证文件不会一边写一边被读。
     */
    fun <T> withWriteLock(block: () -> T): T {
        writeLock.lock()
        try {
            return block()
        } finally {
            writeLock.unlock()
        }
    }

    // ------------------------------------------------------------------
    // 与 android.util.Log 对齐的 API
    // ------------------------------------------------------------------

    fun isLoggable(tag: String, level: Int): Boolean = AndroidLog.isLoggable(tag, level)

    fun getStackTraceString(t: Throwable): String = AndroidLog.getStackTraceString(t)

    fun v(tag: String, msg: String) {
        AndroidLog.v(tag, msg)
        append('V', tag, msg, null)
    }

    fun v(tag: String, msg: String, t: Throwable) {
        AndroidLog.v(tag, msg, t)
        append('V', tag, msg, t)
    }

    fun d(tag: String, msg: String) {
        AndroidLog.d(tag, msg)
        append('D', tag, msg, null)
    }

    fun d(tag: String, msg: String, t: Throwable) {
        AndroidLog.d(tag, msg, t)
        append('D', tag, msg, t)
    }

    fun i(tag: String, msg: String) {
        AndroidLog.i(tag, msg)
        append('I', tag, msg, null)
    }

    fun i(tag: String, msg: String, t: Throwable) {
        AndroidLog.i(tag, msg, t)
        append('I', tag, msg, t)
    }

    fun w(tag: String, msg: String) {
        AndroidLog.w(tag, msg)
        append('W', tag, msg, null)
    }

    fun w(tag: String, msg: String, t: Throwable) {
        AndroidLog.w(tag, msg, t)
        append('W', tag, msg, t)
    }

    fun e(tag: String, msg: String) {
        AndroidLog.e(tag, msg)
        append('E', tag, msg, null)
    }

    fun e(tag: String, msg: String, t: Throwable) {
        AndroidLog.e(tag, msg, t)
        append('E', tag, msg, t)
    }

    fun wtf(tag: String, msg: String) {
        AndroidLog.wtf(tag, msg)
        append('A', tag, msg, null)
    }

    fun wtf(tag: String, msg: String, t: Throwable) {
        AndroidLog.wtf(tag, msg, t)
        append('A', tag, msg, t)
    }

    // android.util.Log 的「只有 tag + Throwable」重载（部分系统代码 / 老调用点会用到）
    fun w(tag: String, t: Throwable) {
        AndroidLog.w(tag, t)
        append('W', tag, "", t)
    }

    fun e(tag: String, t: Throwable) {
        // android.util.Log 没有 e(String, Throwable) 重载（w/wtf 有，e 没有），
        // 用空消息 + Throwable 的三参重载等价实现。
        AndroidLog.e(tag, "", t)
        append('E', tag, "", t)
    }

    fun wtf(tag: String, t: Throwable) {
        AndroidLog.wtf(tag, t)
        append('A', tag, "", t)
    }

    fun println(priority: Int, tag: String, msg: String) {
        AndroidLog.println(priority, tag, msg)
        val level = when (priority) {
            AndroidLog.VERBOSE -> 'V'
            AndroidLog.DEBUG -> 'D'
            AndroidLog.INFO -> 'I'
            AndroidLog.WARN -> 'W'
            AndroidLog.ERROR -> 'E'
            else -> 'A'
        }
        append(level, tag, msg, null)
    }

    // ------------------------------------------------------------------
    // 落盘实现
    // ------------------------------------------------------------------

    private fun append(level: Char, tag: String, msg: String, t: Throwable?) {
        if (!enabled) return
        val pool = executor ?: return

        // 时间 / 线程号在调用线程取，保证与事件时刻一致；格式化放到日志线程做。
        val time = System.currentTimeMillis()
        val tid = Process.myTid()
        try {
            pool.execute {
                // close() 之后排队中的任务不再落盘
                if (!enabled) return@execute
                val line = StringBuilder(msg.length + 96)
                val fmt = lineFormat.get()
                    ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                line.append(fmt.format(Date(time)))
                line.append(' ').append(level).append('/').append(tag)
                line.append('(').append(pid).append('-').append(tid).append("): ")
                line.append(msg)
                if (t != null) {
                    line.append('\n')
                    line.append(AndroidLog.getStackTraceString(t))
                }
                line.append('\n')

                writeLock.lock()
                try {
                    writeLocked(line.toString())
                } finally {
                    writeLock.unlock()
                }
            }
        } catch (e: RejectedExecutionException) {
            dropped.incrementAndGet()
        }
    }

    private fun writeLocked(line: String) {
        try {
            var file = currentFile
            if (writer == null || file == null || !file.exists()) {
                if (!rotateLocked()) {
                    onWriteFailure(IOException("无法打开日志文件"))
                    return
                }
                file = currentFile
            }
            if (file != null && file.length() > MAX_FILE_BYTES) {
                val rotated = rotateLocked()
                val activeFile = currentFile
                if (!rotated || (activeFile != null && activeFile.length() > MAX_FILE_BYTES)) {
                    // 轮转失败（重命名 / 删除被占用）或新文件仍然超限：截断当前文件继续写，
                    // 否则之后每一行都会重试轮转，把日志线程拖死。
                    closeWriterLocked()
                    if (!openWriterLocked(truncate = true)) {
                        onWriteFailure(IOException("无法轮转日志文件"))
                        return
                    }
                    forcedRotations.incrementAndGet()
                    AndroidLog.w(TAG, "日志轮转失败，已截断 vfc.log 继续记录（可能丢失旧日志）")
                }
            }
            val active = writer
            if (active == null) {
                onWriteFailure(IOException("日志写入器不可用"))
                return
            }
            active.write(line)
            active.flush()
            writeFailures = 0
        } catch (e: IOException) {
            onWriteFailure(e)
        } catch (e: SecurityException) {
            onWriteFailure(e)
        } catch (e: RuntimeException) {
            onWriteFailure(e)
        }
    }

    private fun onWriteFailure(e: Exception) {
        writeFailures++
        AndroidLog.w(TAG, "写入日志失败（第 $writeFailures 次）: " + e.message)
        closeWriterLocked()
        if (writeFailures >= MAX_WRITE_FAILURES) {
            enabled = false
            AndroidLog.w(TAG, "文件日志连续写入失败，已自动关闭")
        }
    }

    private fun rotateLocked(): Boolean {
        closeWriterLocked()
        val dir = logDirectory ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false
        try {
            File(dir, "vfc.${MAX_FILES - 1}.log").delete()
            for (i in (MAX_FILES - 2) downTo 1) {
                val from = File(dir, "vfc.$i.log")
                if (from.exists() && !from.renameTo(File(dir, "vfc.${i + 1}.log"))) {
                    // 重命名失败就删掉旧档，尽量给当前文件腾位置
                    from.delete()
                }
            }
            val base = File(dir, "vfc.log")
            if (base.exists() && !base.renameTo(File(dir, "vfc.1.log"))) {
                // 挪不走就直接删，避免后面逐行重试轮转
                base.delete()
            }
        } catch (e: Exception) {
            // 轮转失败：交给 openWriterLocked 判断是否还能写
        }
        return openWriterLocked(truncate = false)
    }

    private fun openWriterLocked(truncate: Boolean): Boolean {
        val dir = logDirectory ?: return false
        closeWriterLocked()
        return try {
            val file = File(dir, "vfc.log")
            writer = BufferedWriter(FileWriter(file, !truncate), 8 * 1024)
            currentFile = file
            true
        } catch (e: IOException) {
            writer = null
            currentFile = null
            false
        } catch (e: SecurityException) {
            writer = null
            currentFile = null
            false
        }
    }

    private fun closeWriterLocked() {
        try {
            writer?.flush()
        } catch (e: IOException) {
            // ignore
        }
        try {
            writer?.close()
        } catch (e: IOException) {
            // ignore
        }
        writer = null
    }
}
