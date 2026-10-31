package com.pocketmind.tools

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Bounded, device-local diagnostics that survive process restarts and can be exported. */
object AppLog {
    private const val TAG = "PocketMind"
    private const val MAX_BYTES = 1024L * 1024L
    private const val DEFAULT_VIEWER_LINES = 1500
    private const val MAX_DETAIL_CHARS = 1200
    private const val MAX_QUEUED_EVENTS = 512
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var lastSequence: Long? = null
    @Volatile private var appContext: Context? = null
    private val droppedEvents = AtomicLong(0)
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUED_EVENTS),
        ThreadFactory { task -> Thread(task, "PocketMind-AppLog").apply { isDaemon = true } }
    )

    private val urlPattern = Regex("(?i)\\b(?:https?|content|file)://[^\\s<>\\\"']+")
    private val secretAssignmentPattern = Regex("(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|authorization|credential|client[_-]?secret)\\b\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;&]+)")
    private val credentialPhrasePattern = Regex("""(?i)\b(password|passcode|pin|api[_-]?key|access[_-]?token|refresh[_-]?token|secret|authorization|credential|client[_-]?secret)\b\s+(?:is|are|to)\s+(?:"[^"]*"|'[^']*'|[^\s,;&]+)""")
    private val authSchemePattern = Regex("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]+")
    private val knownTokenPattern = Regex("(?i)\\b(?:gh[pousr]_[A-Za-z0-9_]{16,}|xox[baprs]-[A-Za-z0-9-]{10,}|AKIA[A-Z0-9]{16}|ASIA[A-Z0-9]{16}|sk-[A-Za-z0-9_-]{20,}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})\\b")
    private val paymentCardPattern = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")

    fun init(context: Context) {
        appContext = context.applicationContext
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
            }
        }
        write(context, "ERROR", event, suffix)
    }

    /** Context-free form for stores and background components; available after Application.onCreate. */
    fun info(event: String, detail: String = "") { appContext?.let { info(it, event, detail) } }
    fun warn(event: String, detail: String = "") { appContext?.let { warn(it, event, detail) } }
    fun error(event: String, throwable: Throwable? = null, detail: String = "") {
        appContext?.let { error(it, event, throwable, detail) }
    }

    /** Records one bounded, content-free database operation and preserves the original result or exception. */
    fun <T> databaseOperation(
        store: String,
        operation: String,
        resultDetail: (T) -> String = { "" },
        block: () -> T
    ): T {
        val metadata = "store=$store operation=$operation"
        val startedAt = System.nanoTime()
        info("DB_OPERATION_STARTED", metadata)
        return try {
            val result = block()
            val extra = try { resultDetail(result).take(160) } catch (_: Exception) { "" }
            val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
            info("DB_OPERATION_FINISHED", "$metadata outcome=success durationMs=$durationMs${if (extra.isBlank()) "" else " $extra"}")
            result
        } catch (e: CancellationException) {
            val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
            info("DB_OPERATION_FINISHED", "$metadata outcome=cancelled durationMs=$durationMs")
            throw e
        } catch (e: Exception) {
            val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
            error("DB_OPERATION_FAILED", e, "$metadata durationMs=$durationMs")
            throw e
        }
    }

    fun file(context: Context): File = File(context.filesDir, "pocketmind.log")

    /** Full bounded history used by the existing Export logs action. */
    fun exportText(context: Context): String {
        flushPendingWrites()
        return synchronized(lock) {
            val logFile = file(context)
            if (!logFile.exists()) "PocketMind log is empty.\n" else logFile.readText()
        }
    }

    /** Recent chronological history for the on-device viewer. */
    fun recentText(context: Context, maxLines: Int = DEFAULT_VIEWER_LINES): String {
        flushPendingWrites()
        return synchronized(lock) {
            val logFile = file(context)
            if (!logFile.exists()) return@synchronized "No debug events have been recorded yet."
            val lines = logFile.readLines().takeLast(maxLines.coerceAtLeast(1))
            lines.joinToString("\n").ifBlank { "No debug events have been recorded yet." }
        }
    }

    fun clear(context: Context) {
        flushPendingWrites()
        synchronized(lock) {
            val logFile = file(context)
            logFile.parentFile?.mkdirs()
            logFile.writeText("")
            lastSequence = 0L
        }
    }

    private fun write(
        context: Context,
        level: String,
        event: String,
        detail: String,
        detailLimit: Int = MAX_DETAIL_CHARS,
        includeInLogcat: Boolean = true
    ) {
        val app = context.applicationContext
        val safeEvent = sanitize(event).take(100)
        val safeDetail = sanitize(detail).take(detailLimit)
        try {
            writer.execute {
                val dropped = droppedEvents.getAndSet(0L)
                if (dropped > 0L) writeNow(app, "WARN", "APP_LOG_EVENTS_DROPPED", "count=$dropped")
                writeNow(app, level, safeEvent, safeDetail, includeInLogcat)
            }
        } catch (_: RejectedExecutionException) {
            droppedEvents.incrementAndGet()
        }
    }

    private fun sanitize(value: String): String = value
        .replace(urlPattern, "[url-redacted]")
        .replace(authSchemePattern, "[authorization-redacted]")
        .replace(secretAssignmentPattern) { "${it.groupValues[1]}=[redacted]" }
        .replace(credentialPhrasePattern, "[credential-redacted]")
        .replace(knownTokenPattern, "[token-redacted]")
        .replace(paymentCardPattern, "[number-redacted]")
        .replace(Regex("[\\r\\n\\t\\u0000-\\u001F]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun writeNow(context: Context, level: String, event: String, detail: String, includeInLogcat: Boolean = true) {
        synchronized(lock) {
            val logFile = file(context)
            logFile.parentFile?.mkdirs()
            val sequence = (lastSequence ?: readLastSequence(logFile)) + 1L
            lastSequence = sequence
            val line = "${formatter.format(Date())} #$sequence $level $event${if (detail.isBlank()) "" else " | $detail"}\n"
            logFile.appendText(line)
            if (logFile.length() > MAX_BYTES) {
                val bytes = logFile.readBytes()
                var lineStart = (bytes.size - MAX_BYTES.toInt()).coerceAtLeast(0)
                while (lineStart < bytes.size && bytes[lineStart] != '\n'.code.toByte()) lineStart++
                if (lineStart < bytes.size) lineStart++
                logFile.writeBytes(if (lineStart < bytes.size) bytes.copyOfRange(lineStart, bytes.size) else byteArrayOf())
            }
        }
        if (!includeInLogcat) return
        when (level) {
            "ERROR" -> Log.e(TAG, "$event $detail")
            "WARN" -> Log.w(TAG, "$event $detail")
            else -> Log.i(TAG, "$event $detail")
        }
    }

    private fun flushPendingWrites() {
        val completed = CountDownLatch(1)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (true) {
            try {
                writer.execute { completed.countDown() }
                break
            } catch (_: RejectedExecutionException) {
                if (System.nanoTime() >= deadline) return
                try { Thread.sleep(5) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
            }
        }
        try { completed.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
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
