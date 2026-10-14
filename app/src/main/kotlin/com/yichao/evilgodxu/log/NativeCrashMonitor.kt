package com.yichao.evilgodxu.log

import android.content.Context
import java.io.File

/**
 * 原生崩溃的记录与回收。
 *
 * Java 侧的崩溃经 [CrashLogManager] 落到今日日志里，但原生层的 SIGSEGV / SIGABRT 等直接终止进程，
 * 一条日志都留不下——症状就是"闪退却无崩溃日志、进程无声消失"。本类在进程启动时装一个极小的信号处理器
 * （原生侧只调用 write / gettid 这类异步信号安全的系统调用），把信号号、出错地址与线程号写进独立的
 * native_crash.log；下次启动时把这份内容收进主日志，用户照常分享一个文件就能看到"上次是怎么死的"。
 *
 * 它只负责**记录**，不改变崩溃的处置：记录后恢复默认处理器并重发信号，tombstone 照常生成。
 * 反过来说，若某次"进程消失"在这里没有任何记录，那多半不是原生崩溃，而是被系统回收（LMK / ANR），
 * 须另看 logcat 的 `am_kill` 与 `dumpsys activity exit-info`。
 */
object NativeCrashMonitor {

    private const val CRASH_FILE_NAME = "native_crash.log"

    /**
     * 若上次进程留下了原生崩溃记录，收进主日志并清掉。
     *
     * 放在 [install] 之前调用：先取回上次的死因，再为本次装上处理器。
     */
    fun recover(context: Context) {
        runCatching {
            val file = crashFile(context)
            if (!file.exists() || file.length() == 0L) return
            val text = file.readText().trim()
            if (text.isNotEmpty()) {
                CrashLogManager.logInfo("NativeCrashMonitor", "上次进程以原生信号终止：\n$text")
            }
            file.delete()
        }
    }

    /** 装载库并安装信号处理器；装载失败只降级为"记不到"，不影响播放 */
    fun install(context: Context) {
        runCatching {
            System.loadLibrary("echotide_usb_iso")
            nativeInstall(crashFile(context).absolutePath)
        }
    }

    private fun crashFile(context: Context): File =
        File(CrashLogManager.logDirectory(context), CRASH_FILE_NAME)

    private external fun nativeInstall(path: String)
}
