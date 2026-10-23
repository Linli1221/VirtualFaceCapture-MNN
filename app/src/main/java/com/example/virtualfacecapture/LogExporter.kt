package com.example.virtualfacecapture

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.vfc.log.VfcLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * debug 包「导出日志」的打包器。
 *
 * 生成一个 zip，内容：
 * ```
 * README.txt           说明与排查入口
 * device-info.txt      机型 / 系统 / ABI / 屏幕 / 内存 / 语言
 * app-info.txt         版本、构建类型、进程、日志状态
 * logs/vfc.log ...     [VfcLog] 落盘的应用日志（含轮转文件）
 * dump/<ts>.ppm        检测器首帧 dump（如果本次运行生成过）
 * prefs/<name>.xml     应用 SharedPreferences（相机选择等）
 * ```
 *
 * 只打包 App 自己能读到的内容：**不包含 logcat / native 日志 / tombstone**，
 * 那些需要 adb（见 README「导出日志」一节）。
 */
object LogExporter {

    private const val TAG = "LogExporter"
    private const val EXPORT_DIR = "log-export"

    /** 缓存里超过这个时间的旧导出包会被清理。 */
    private const val KEEP_EXPORT_MS = 3L * 24 * 60 * 60 * 1000

    /**
     * 收集日志并打成 zip（放在 cache 里），返回 zip 文件。
     *
     * 调用方负责用 SAF 让用户选择保存位置（见 `MainActivity`）。
     * 失败时抛异常，由调用方兜底提示。
     */
    fun buildZip(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outDir = File(context.cacheDir, EXPORT_DIR)
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw IllegalStateException("无法创建导出目录: " + outDir.absolutePath)
        }
        cleanupOldExports(outDir)

        val out = File(outDir, "vfc-log-$stamp.zip")
        val snapshotDir = File(outDir, "snapshot-$stamp")

        // 先把已经排队的日志行写完；然后在写锁内只做「复制快照」（毫秒级），
        // 真正的 zip 打包放在锁外，避免导出期间日志线程被长时间挡住、队列塞满丢日志。
        VfcLog.flush(3000)
        try {
            val logSnapshot = VfcLog.withWriteLock { snapshotLogs(snapshotDir) }
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
                zipText(zip, "README.txt", buildReadme(context))
                zipText(zip, "device-info.txt", buildDeviceInfo(context))
                zipText(zip, "app-info.txt", buildAppInfo(context, logSnapshot))
                zipLogSnapshot(zip, logSnapshot)
                zipFirstFrameDump(context, zip)
                zipSharedPrefs(context, zip)
            }
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            snapshotDir.deleteRecursively()
        }
        return out
    }

    /** 把已生成的 zip 写到用户选中的 URI；失败返回 false。 */
    fun writeTo(context: Context, uri: Uri, zip: File): Boolean {
        return try {
            val output = context.contentResolver.openOutputStream(uri) ?: return false
            output.use { os ->
                FileInputStream(zip).use { input -> input.copyTo(os) }
            }
            true
        } catch (t: Throwable) {
            VfcLog.e(TAG, "写入导出文件失败: $uri", t)
            false
        }
    }

    // ------------------------------------------------------------------
    // zip 内容
    // ------------------------------------------------------------------

    private fun buildReadme(context: Context): String = buildString {
        appendLine("VirtualFaceCapture 调试日志包")
        appendLine("生成时间: " + timeText())
        appendLine("应用版本: " + versionText(context))
        appendLine()
        appendLine("目录说明:")
        appendLine("  logs/          应用自己的运行日志（VfcLog 落盘，含轮转文件 vfc.1.log / vfc.2.log）")
        appendLine("  dump/          检测器首帧 dump（PPM，可用 ffmpeg / ImageMagick 转 PNG 查看）")
        appendLine("  prefs/         SharedPreferences（相机选择等）")
        appendLine("  device-info   机型 / 系统 / ABI / 屏幕 / 内存")
        appendLine("  app-info      版本、构建类型、进程 PID、日志丢弃计数")
        appendLine()
        appendLine("不包含:")
        appendLine("  - logcat 其它进程日志、native(C++) 日志、tombstone —— 普通 App 没有读日志权限，")
        appendLine("    需要在电脑上执行: adb logcat -v threadtime > vfc-log.txt")
        appendLine("  - 视频 / 相机原始帧（除首帧 dump 外不做任何图像落盘）")
        appendLine()
        appendLine("排查入口（按 TAG 搜索 logs/vfc.log）:")
        appendLine("  PipelineFaceTracker   面捕流水线；每 60 帧一段 === Parallel Pipeline Perf ===")
        appendLine("  Camera2Manager        相机枚举 / 采集尺寸 / FPS 自检 / 自适应降档")
        appendLine("  MediaPipeFaceDetector 检测器初始化 / MNN 后端切换")
        appendLine("  UpperBodyPoseDetector 上半身 MediaPipe Pose 初始化与 ROI 命中")
        appendLine("  TrackingServer        WebSocket 服务与客户端连接")
        appendLine("  MainActivity          页面生命周期与按钮操作")
        appendLine("  Crash                 未捕获异常栈")
    }

    private fun buildDeviceInfo(context: Context): String = buildString {
        appendLine("时间: " + timeText())
        appendLine("厂商: " + Build.MANUFACTURER)
        appendLine("型号: " + Build.MODEL)
        appendLine("设备: " + Build.DEVICE)
        appendLine("主板: " + Build.BOARD)
        appendLine("硬件: " + Build.HARDWARE)
        appendLine("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
        appendLine("安全补丁: " + Build.VERSION.SECURITY_PATCH)
        appendLine("构建指纹: " + Build.FINGERPRINT)
        appendLine("主 ABI: " + Build.SUPPORTED_ABIS.joinToString(", "))
        appendLine("64 位: " + Build.SUPPORTED_64_BIT_ABIS.joinToString(", "))
        appendLine("语言 / 地区: " + Locale.getDefault())
        appendLine("时区: " + TimeZone.getDefault().id)
        appendLine("开机时长: " + formatDuration(SystemClock.elapsedRealtime()))
        val processStart = android.os.Process.getStartElapsedRealtime()
        appendLine("进程运行时长: " + formatDuration(SystemClock.elapsedRealtime() - processStart))
        val metrics = context.resources.displayMetrics
        appendLine(
            "屏幕: " + metrics.widthPixels + "x" + metrics.heightPixels +
                " @ " + metrics.density + "x (dpi=" + metrics.densityDpi + ")"
        )
        appendMemoryInfo(context)
    }

    private fun StringBuilder.appendMemoryInfo(context: Context) {
        val runtime = Runtime.getRuntime()
        appendLine("Java 堆上限: " + formatBytes(runtime.maxMemory()))
        appendLine("Java 堆已用: " + formatBytes(runtime.totalMemory() - runtime.freeMemory()))
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                appendLine("系统可用内存: " + formatBytes(info.availMem))
                appendLine("系统总内存: " + formatBytes(info.totalMem))
                appendLine("低内存状态: " + info.lowMemory)
            }
        } catch (t: Throwable) {
            appendLine("系统内存信息读取失败: " + t.message)
        }
    }

    private fun buildAppInfo(context: Context, logSnapshot: LogSnapshot): String = buildString {
        val packageName = context.packageName
        appendLine("包名: " + packageName)
        appendLine("版本: " + versionText(context))
        appendLine("构建类型: " + if (isDebuggable(context)) "debug (debuggable)" else "release")
        appendLine("进程 PID: " + android.os.Process.myPid())
        appendLine("文件日志: " + if (VfcLog.isEnabled()) "已开启" else "未开启")
        appendLine("日志目录: " + (VfcLog.logDir()?.absolutePath ?: "(未初始化)"))
        appendLine("日志丢弃行数: " + VfcLog.droppedLineCount())
        appendLine("轮转失败截断次数: " + VfcLog.forcedRotationCount())

        if (logSnapshot.files.isEmpty()) {
            appendLine("日志文件: (无)")
        } else {
            appendLine("日志文件（本次导出实际包含）:")
            for (f in logSnapshot.files) {
                appendLine("  " + f.name + "  " + formatBytes(f.length()))
            }
        }
        if (logSnapshot.skipped.isNotEmpty()) {
            appendLine("读取失败被跳过的日志文件: " + logSnapshot.skipped.joinToString(", "))
        }
        try {
            val appInfo = context.applicationInfo
            appendLine("数据目录: " + appInfo.dataDir)
            appendLine("nativeLibraryDir: " + appInfo.nativeLibraryDir)
        } catch (t: Throwable) {
            appendLine("应用信息读取失败: " + t.message)
        }
    }

    /**
     * 在 [VfcLog] 写锁内把日志文件复制到 [snapshotDir]（调用方负责删除）。
     *
     * 复制期间日志线程只会排队，保证每个快照文件都是完整行；复制完成后立刻释放锁，
     * 后面打包 6 MB 日志 / PPM 的耗时不会挡住实时日志。
     */
    private fun snapshotLogs(snapshotDir: File): LogSnapshot {
        val source = VfcLog.logDir() ?: return LogSnapshot(emptyList(), emptyList())
        val files = source.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.toList()
            .orEmpty()
        if (files.isEmpty()) return LogSnapshot(emptyList(), emptyList())
        // vfc.log（最新）在前，轮转文件按序号递增
        val ordered = files.sortedWith(
            compareBy<File>({ if (it.name == "vfc.log") 0 else 1 }, { it.name })
        )
        if (!snapshotDir.exists() && !snapshotDir.mkdirs()) {
            return LogSnapshot(emptyList(), ordered.map { it.name })
        }

        val snapshot = ArrayList<File>(ordered.size)
        val skipped = ArrayList<String>(0)
        for (file in ordered) {
            try {
                val dst = File(snapshotDir, file.name)
                file.inputStream().use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                snapshot.add(dst)
            } catch (t: Throwable) {
                // 单个文件读不出来就跳过，不能拖垮整次导出；文件名会写进 app-info.txt
                skipped.add(file.name)
            }
        }
        return LogSnapshot(snapshot, skipped)
    }

    private fun zipLogSnapshot(zip: ZipOutputStream, snapshot: LogSnapshot) {
        if (snapshot.files.isEmpty()) {
            zipText(zip, "logs/README.txt", "文件日志未初始化或本次运行还没有产生日志文件。")
            return
        }
        for (file in snapshot.files) {
            zipFile(zip, "logs/" + file.name, file)
        }
        if (snapshot.skipped.isNotEmpty()) {
            zipText(
                zip,
                "logs/SKIPPED.txt",
                "以下日志文件读取失败，未能包含在本次导出中:\n" +
                    snapshot.skipped.joinToString("\n")
            )
        }
    }

    /** 日志快照：复制成功的文件 + 读取失败被跳过的文件名。 */
    private class LogSnapshot(val files: List<File>, val skipped: List<String>)

    private fun zipFirstFrameDump(context: Context, zip: ZipOutputStream) {
        val names = arrayOf("detector_input_frame.ppm", "detector_input_frame_yuv.ppm")
        for (name in names) {
            val file = File(context.cacheDir, name)
            if (file.isFile && file.length() > 0) {
                zipFile(zip, "dump/" + name, file)
            }
        }
    }

    private fun zipSharedPrefs(context: Context, zip: ZipOutputStream) {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            val prefs = prefsDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }
                ?.sortedBy { it.name }
                .orEmpty()
            for (file in prefs) {
                zipFile(zip, "prefs/" + file.name, file)
            }
        } catch (t: Throwable) {
            zipText(zip, "prefs/README.txt", "读取 SharedPreferences 失败: " + t.message)
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private fun zipText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun zipFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun cleanupOldExports(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (now - file.lastModified() <= KEEP_EXPORT_MS) return@forEach
            if (file.isFile && file.name.endsWith(".zip")) {
                file.delete()
            } else if (file.isDirectory && file.name.startsWith("snapshot-")) {
                // 进程在打包途中被杀时可能留下快照目录，一并清掉
                file.deleteRecursively()
            }
        }
    }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun versionText(context: Context): String {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            info.versionName + " (" + code + ")"
        } catch (t: Throwable) {
            "未知 (" + t.message + ")"
        }
    }

    private fun timeText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> bytes.toString() + " B"
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return hours.toString() + "h " + minutes + "m " + seconds + "s"
    }
}
