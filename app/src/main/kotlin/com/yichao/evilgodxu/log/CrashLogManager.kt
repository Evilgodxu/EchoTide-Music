package com.yichao.evilgodxu.log

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import kotlin.system.exitProcess

/** 记录未捕获与捕获到的异常；日志按天写入，仅留今日 */
object CrashLogManager : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashLogManager"

    private const val LOG_DIR_NAME = "logs"

    private const val LOG_FILE_PREFIX = "EchoTideMusic_"

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private var logDir: File? = null

    /** 初始化时读一次的版本，保证日志头一致 */
    private var appVersion = "unknown"
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var lastCleanDate: LocalDate? = null

    // 单线程异步写日志，避免阻塞调用线程
    private val logExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CrashLogWriter").apply { isDaemon = true }
    }

    /** 在 Application.onCreate 最前调用 */
    fun init(context: Context) {
        logDir = logDirectory(context).apply { mkdirs() }
        appVersion = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("unknown")

        // 接管默认处理器，保留系统崩溃流程
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)

        cleanOldLogs()
    }

    /** 记录一般异常，标题为类名: 描述 */
    fun logException(className: String, description: String, throwable: Throwable? = null) {
        if (logDir == null) {
            // 未初始化时降级到系统日志
            Log.e(TAG, "$className: $description", throwable)
            return
        }
        logExecutor.execute {
            writeLog(title = "$className: $description", throwable = throwable)
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 崩溃日志须同步落盘
        writeLog(title = "未捕获异常（线程 ${thread.name}）", thread = thread, throwable = throwable)
        previousHandler?.uncaughtException(thread, throwable) ?: exitProcess(2)
    }

    @Synchronized
    private fun writeLog(title: String, thread: Thread? = null, throwable: Throwable?) {
        val dir = logDir ?: return
        // 每天清理一次，避免每次遍历
        val today = LocalDate.now()
        if (lastCleanDate != today) {
            lastCleanDate = today
            cleanOldLogs()
        }
        val logFile = File(dir, "$LOG_FILE_PREFIX${today.format(dateFormat)}.log")
        try {
            ensureHeader(logFile)
            FileWriter(logFile, true).use { writer ->
                appendEntry(writer, title, thread, throwable)
            }
        } catch (e: Exception) {
            // 写入失败降级系统日志，避免递归
            Log.e(TAG, "写入日志失败", e)
        }
    }

    /** 新文件写头；版本变化时连正文重写 */
    private fun ensureHeader(logFile: File) {
        when {
            !logFile.exists() -> FileWriter(logFile).use { writeHeader(it) }
            headerVersionOutdated(logFile) -> rewriteHeader(logFile)
        }
    }

    private fun headerVersionOutdated(logFile: File): Boolean {
        val expected = "版本: $appVersion"
        runCatching {
            BufferedReader(FileReader(logFile)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("版本: ")) return line != expected
                    if (line.isBlank()) break // 读到正文仍未发现版本行
                }
            }
        }
        return false
    }

    /** 重写头部，保留旧正文 */
    private fun rewriteHeader(logFile: File) {
        val body = runCatching {
            val text = logFile.readText()
            val headerEnd = text.indexOf("\n\n")
            if (headerEnd >= 0) text.substring(headerEnd + 2) else ""
        }.getOrDefault("")
        FileWriter(logFile, false).use { writer ->
            writeHeader(writer)
            writer.append(body)
        }
    }

    private fun appendEntry(
        writer: FileWriter,
        title: String,
        thread: Thread?,
        throwable: Throwable?,
    ) {
        writer.appendLine("================ $title ================")
        writer.appendLine("时间: ${LocalDateTime.now().format(timeFormat)}")
        if (thread != null) {
            writer.appendLine("线程: ${thread.name}")
            writer.appendLine("进程: ${android.os.Process.myPid()}")
        }
        if (throwable != null) {
            writer.appendLine("异常: ${throwable.javaClass.name}: ${throwable.message}")
            writer.appendLine("堆栈:")
            StringWriter().use { sw ->
                throwable.printStackTrace(PrintWriter(sw))
                writer.append(sw.toString())
            }
        }
        writer.appendLine()
    }

    /** 今日日志文件，供设置页分享 */
    fun todayLogFile(): File? {
        val dir = logDir ?: return null
        val today = LocalDate.now().format(dateFormat)
        return File(dir, "$LOG_FILE_PREFIX$today.log").takeIf { it.exists() }
    }

    /** 日志目录，存储统计按此定位 */
    fun logDirectory(context: Context): File = File(context.getExternalFilesDir(null), LOG_DIR_NAME)

    /** 写头部：设备与环境信息 */
    private fun writeHeader(writer: FileWriter) {
        writer.appendLine("======== EchoTideMusic 日志 ========")
        writer.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        writer.appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        writer.appendLine("版本: $appVersion")
        writer.appendLine()
    }

    /** 仅保留今日日志 */
    private fun cleanOldLogs() {
        val dir = logDir ?: return
        val today = LocalDate.now()
        dir.listFiles { f -> f.isFile && f.name.startsWith(LOG_FILE_PREFIX) }
            ?.filter { !it.name.startsWith("$LOG_FILE_PREFIX${today.format(dateFormat)}") }
            ?.forEach { it.delete() }
    }
}
