package com.pocketmind.tools

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, device-local diagnostics that survive process restarts and can be exported. */
object AppLog {
    private const val TAG = "PocketMind"
    private const val MAX_BYTES = 1024L * 1024L
    private const val DEFAULT_VIEWER_LINES = 1500
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var lastSequence: Long? = null

    fun init(context: Context) {
        info(context, "APP_START", "version=${BuildConfigVersion.value(context)} abi=${Build.SUPPORTED_ABIS.joinToString()}")
    }

    fun info(context: Context, event: String, detail: String = "") = write(context, "INFO", event, detail)
    fun warn(context: Context, event: String, detail: String = "") = write(context, "WARN", event, detail)

    fun error(context: Context, event: String, throwable: Throwable? = null, detail: String = "") {
        val suffix = buildString {
            if (detail.isNotBlank()) append(detail)
            if (throwable != null) {
                if (isNotEmpty()) append(' ')
                append("errorType=").append(throwable::class.java.simpleName)
                Log.e(TAG, "$event $detail", throwable)
            }
        }
        write(context, "ERROR", event, suffix)
    }

    fun file(context: Context): File = File(context.filesDir, "pocketmind.log")

    /** Full bounded history used by the existing Export logs action. */
    fun exportText(context: Context): String = synchronized(lock) {
        val logFile = file(context)
        if (!logFile.exists()) return@synchronized "PocketMind log is empty.\n"
        logFile.readText()
    }

    /** Recent chronological history for the on-device viewer. */
    fun recentText(context: Context, maxLines: Int = DEFAULT_VIEWER_LINES): String = synchronized(lock) {
        val logFile = file(context)
        if (!logFile.exists()) return@synchronized "No debug events have been recorded yet."
        val lines = logFile.readLines().takeLast(maxLines.coerceAtLeast(1))
        lines.joinToString("\n").ifBlank { "No debug events have been recorded yet." }
    }

    fun clear(context: Context) = synchronized(lock) {
        val logFile = file(context)
        logFile.parentFile?.mkdirs()
        logFile.writeText("")
        lastSequence = 0L
    }

    private fun write(context: Context, level: String, event: String, detail: String) {
        synchronized(lock) {
            val safeEvent = event.replace(Regex("[\\r\\n]+"), " ").take(100)
            val safeDetail = detail.replace(Regex("[\\r\\n]+"), " ").take(1200)
            val logFile = file(context)
            logFile.parentFile?.mkdirs()
            val sequence = (lastSequence ?: readLastSequence(logFile)) + 1L
            lastSequence = sequence
            val line = "${formatter.format(Date())} #$sequence $level $safeEvent${if (safeDetail.isBlank()) "" else " | $safeDetail"}\n"
            logFile.appendText(line)
            if (logFile.length() > MAX_BYTES) {
                val bytes = logFile.readBytes()
                var lineStart = (bytes.size - MAX_BYTES.toInt()).coerceAtLeast(0)
                while (lineStart < bytes.size && bytes[lineStart] != '\n'.code.toByte()) lineStart++
                if (lineStart < bytes.size) lineStart++
                logFile.writeBytes(if (lineStart < bytes.size) bytes.copyOfRange(lineStart, bytes.size) else byteArrayOf())
            }
        }
        when (level) {
            "ERROR" -> Log.e(TAG, "$event $detail")
            "WARN" -> Log.w(TAG, "$event $detail")
            else -> Log.i(TAG, "$event $detail")
        }
    }

    private fun readLastSequence(logFile: File): Long {
        if (!logFile.exists() || logFile.length() == 0L) return 0L
        return try {
            RandomAccessFile(logFile, "r").use { file ->
                val tailLength = minOf(file.length(), 8192L).toInt()
                val tail = ByteArray(tailLength)
                file.seek(file.length() - tailLength)
                file.readFully(tail)
                Regex("(?m)^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} #(\\d+) ").findAll(String(tail, Charsets.UTF_8)).lastOrNull()
                    ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private object BuildConfigVersion {
        fun value(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}
