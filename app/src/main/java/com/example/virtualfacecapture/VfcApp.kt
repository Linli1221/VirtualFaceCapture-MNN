package com.example.virtualfacecapture

import android.app.Application
import android.content.pm.ApplicationInfo
import com.vfc.log.VfcLog

/**
 * 应用入口：只在 **debug 包**里开启文件日志 + 崩溃栈落盘。
 *
 * debug 包判断用 [ApplicationInfo.FLAG_DEBUGGABLE]，不依赖 BuildConfig，
 * 这样所有模块（含 library）都能保持一致行为。
 *
 * release 包里 [VfcLog.init] 不会被调用，日志调用只走系统 logcat，
 * 行为与改动前完全一致。
 */
class VfcApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return

        VfcLog.init(this, enable = true)
        installCrashHandler()
    }

    /**
     * 未捕获异常落盘：手机上没法随时连 adb，崩溃栈往往是排查的唯一线索。
     *
     * 写完立刻 flush 再交给系统默认处理器，保证进程被杀之前日志已经落盘。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                VfcLog.e(
                    "Crash",
                    "未捕获异常: thread=${thread.name} (${thread.javaClass.name})",
                    throwable
                )
                VfcLog.flush(2000)
            } catch (ignored: Throwable) {
                // 崩溃处理里再抛异常就失去意义了
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
