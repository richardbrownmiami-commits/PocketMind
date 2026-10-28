package com.pocketmind.tools

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small bounded diagnostic log that survives process restarts and can be exported. */
object AppLog {
    private const val TAG = "PocketMind"
    private const val MAX_BYTES = 1024L * 1024L
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        log(context, "APP", "start version=${BuildConfigVersion.value(context)} abi=${Build.SUPPORTED_ABIS.joinToString()}")
    }

    fun info(context: Context, event: String, detail: String = "") = write(context, "INFO", event, detail)
    fun warn(context: Context, event: String, detail: String = "") = write(context, "WARN", event, detail)
    fun error(context: Context, event: String, throwable: Throwable? = null, detail: String = "") {
        val suffix = buildString {
            if (detail.isNotBlank()) append(detail)
            if (throwable != null) {
                if (isNotEmpty()) append(' ')
                append(throwable::class.java.simpleName).append(": ").append(throwable.message ?: "")
                Log.e(TAG, "$event $detail", throwable)
            }
        }
        write(context, "ERROR", event, suffix)
    }

    fun file(context: Context): File = File(context.filesDir, "pocketmind.log")

    fun exportText(context: Context): String = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return@synchronized "PocketMind log is empty.\n"
        f.readText().takeLast(MAX_BYTES.toInt())
    }

    private fun log(context: Context, event: String, detail: String) = write(context, "INFO", event, detail)

    private fun write(context: Context, level: String, event: String, detail: String) {
        val line = synchronized(lock) {
            "${formatter.format(Date())} $level $event${if (detail.isBlank()) "" else " | $detail"}\n"
        }
        synchronized(lock) {
            val f = file(context)
            f.parentFile?.mkdirs()
            f.appendText(line)
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.takeLast(MAX_BYTES.toInt()))
            }
        }
        when (level) {
            "ERROR" -> Log.e(TAG, "$event $detail")
            "WARN" -> Log.w(TAG, "$event $detail")
            else -> Log.i(TAG, "$event $detail")
        }
    }

    private object BuildConfigVersion {
        fun value(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}
