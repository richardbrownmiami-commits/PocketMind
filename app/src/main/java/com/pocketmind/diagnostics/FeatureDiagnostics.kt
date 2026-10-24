package com.pocketmind.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.AlarmClock
import android.provider.Settings
import com.pocketmind.core.AgentController
import com.pocketmind.engine.ModelStore
import com.pocketmind.memory.ActionLogStore
import com.pocketmind.memory.AppDatabase
import com.pocketmind.memory.FactStore
import com.pocketmind.memory.FollowUpResolver
import com.pocketmind.memory.MessageStore
import com.pocketmind.memory.NoteStore
import com.pocketmind.memory.RecallStore
import com.pocketmind.memory.SummaryStore
import com.pocketmind.memory.TopicState
import com.pocketmind.memory.TopicStateStore
import com.pocketmind.memory.clearMemory
import com.pocketmind.tools.ActionStatus
import com.pocketmind.tools.PlanStep
import com.pocketmind.tools.PlannerJson
import com.pocketmind.tools.RuleEngine
import com.pocketmind.tools.SafeMath
import com.pocketmind.tools.ToolRegistry
import com.pocketmind.tools.ToolRuntime
import com.pocketmind.tools.ToolValidator
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Results contain diagnostic metadata only; never include chats, notes, or user-entered text. */
enum class DiagnosticStatus { PASS, WARN, FAIL, NOT_RUN }

data class DiagnosticCheck(
    val title: String,
    val status: DiagnosticStatus,
    val details: String
)

data class DiagnosticReport(
    val createdAt: Long,
    val device: String,
    val android: String,
    val appVersion: String,
    val checks: List<DiagnosticCheck>
) {
    val failures: Int get() = checks.count { it.status == DiagnosticStatus.FAIL }
    val warnings: Int get() = checks.count { it.status == DiagnosticStatus.WARN }
    val notRun: Int get() = checks.count { it.status == DiagnosticStatus.NOT_RUN }

    fun summary(): String = when {
        failures > 0 -> "$failures issue(s) failed checks. See the report for likely causes and next steps."
        warnings > 0 -> "No safe check failed; $warnings item(s) need attention or manual verification."
        notRun > 0 -> "Safe checks passed; $notRun action(s) were intentionally not run."
        else -> "All checks passed."
    }

    fun asShareText(): String = buildString {
        appendLine("PocketMind diagnostic report")
        appendLine("Created: ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(createdAt))}")
        appendLine("Device: $device")
        appendLine("Android: $android")
        appendLine("App version: $appVersion")
        appendLine()
        appendLine(summary())
        appendLine()
        checks.forEach { check ->
            appendLine("[${check.status}] ${check.title}")
            appendLine(check.details)
            appendLine()
        }
        appendLine("Privacy: generated on this device. This report contains no chat or note contents and is not sent automatically.")
    }
}

/** Runs offline, non-destructive diagnostics for the app's implemented features. */
object FeatureDiagnostics {
    suspend fun run(context: Context, controller: AgentController): DiagnosticReport {
        val app = context.applicationContext
        val checks = mutableListOf<DiagnosticCheck>()
        checks += checkPlatform(app)
        checks += checkOfflinePolicy(app)
        checks += checkNativeLibrary(app)
        checks += checkModelState(app)
        checks += checkMemoryPressure(app)
        checks += checkCoreLogic()
        checks += checkSafeRuntimeTools(app)
        checks += checkMemoryStores(app)
        checks += checkDeviceCapabilities(app)
        checks += checkLoadedModel(controller)
        checks += DiagnosticCheck(
            "State-changing phone actions",
            DiagnosticStatus.NOT_RUN,
            "Alarm and timer creation, flashlight/volume/brightness changes, app launches, and settings changes were not triggered. These need a manual on-device check so the tester cannot disturb your phone."
        )
        return DiagnosticReport(
            createdAt = System.currentTimeMillis(),
            device = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Unknown device" },
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}; ${Build.SUPPORTED_ABIS.joinToString()})",
            appVersion = appVersion(app),
            checks = checks
        )
    }

    private fun checkPlatform(context: Context): DiagnosticCheck {
        val isSupported = Build.VERSION.SDK_INT >= 30 &&
            !Process.is64Bit() && Build.SUPPORTED_ABIS.any { it.equals("armeabi-v7a", true) }
        return if (isSupported) {
            DiagnosticCheck("Android and ARMv7 compatibility", DiagnosticStatus.PASS,
                "This process runs as 32-bit on Android API ${Build.VERSION.SDK_INT}; ARMv7 is present in the device ABI list.")
        } else {
            DiagnosticCheck("Android and ARMv7 compatibility", DiagnosticStatus.FAIL,
                "This build targets Android 11+ and armeabi-v7a. Detected API ${Build.VERSION.SDK_INT}, 64-bit process=${Process.is64Bit()}, ABIs=${Build.SUPPORTED_ABIS.joinToString()}.")
        }
    }

    private fun checkOfflinePolicy(context: Context): DiagnosticCheck = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requestsInternet = info.requestedPermissions.orEmpty().contains("android.permission.INTERNET")
        if (requestsInternet) DiagnosticCheck("Offline permission policy", DiagnosticStatus.FAIL,
            "The installed app requests INTERNET. Remove that permission from the manifest to keep the app offline.")
        else DiagnosticCheck("Offline permission policy", DiagnosticStatus.PASS,
            "The installed app does not request android.permission.INTERNET.")
    } catch (e: Exception) {
        DiagnosticCheck("Offline permission policy", DiagnosticStatus.WARN,
            "Could not inspect the installed permission list: ${shortReason(e)}")
    }

    private fun checkNativeLibrary(context: Context): DiagnosticCheck {
        val library = File(context.applicationInfo.nativeLibraryDir, "libpocketmind.so")
        if (!library.isFile) return DiagnosticCheck("Native inference library", DiagnosticStatus.FAIL,
            "libpocketmind.so is missing from the installed app. Reinstall the correct ARMv7 APK.")
        return try {
            System.loadLibrary("pocketmind")
            DiagnosticCheck("Native inference library", DiagnosticStatus.PASS,
                "The ARM native library exists and the Android linker loaded it. Model inference itself is checked separately when a model is already loaded.")
        } catch (e: Throwable) {
            DiagnosticCheck("Native inference library", DiagnosticStatus.FAIL,
                "The native library could not be loaded (${shortReason(e)}). This can mean an ABI mismatch or a missing native dependency; reinstall the ARMv7 APK.")
        }
    }

    private fun checkModelState(context: Context): DiagnosticCheck = try {
        val store = ModelStore(context)
        val models = store.list()
        val selected = store.selected()
        when {
            selected == null -> DiagnosticCheck("Local model readiness", DiagnosticStatus.WARN,
                if (models.isEmpty()) "No GGUF model is imported. Rule-based commands can still work, but local chat and model planning need a compatible model imported on the Models screen."
                else "Models are imported, but none is selected. Select a GGUF model on the Models screen before using local chat or model planning.")
            !selected.isFile -> DiagnosticCheck("Local model readiness", DiagnosticStatus.FAIL,
                "The selected GGUF file is missing. Re-import a model from the Models screen.")
            selected.length() <= 0L -> DiagnosticCheck("Local model readiness", DiagnosticStatus.FAIL,
                "The selected model file is empty. Re-import the file.")
            selected.length() > ModelStore.MAX_MODEL_BYTES -> DiagnosticCheck("Local model readiness", DiagnosticStatus.FAIL,
                "The selected model exceeds the app's 1.2 GB limit. Choose a smaller quantized model.")
            else -> DiagnosticCheck("Local model readiness", DiagnosticStatus.PASS,
                "A selected GGUF file is present and within the 1.2 GB file-size limit. Import and file checks passed; model compatibility is not guaranteed until loaded.")
        }
    } catch (e: Exception) {
        DiagnosticCheck("Local model readiness", DiagnosticStatus.FAIL,
            "The model store could not be inspected: ${shortReason(e)}")
    }

    private fun checkCoreLogic(): DiagnosticCheck = try {
        val validator = ToolValidator()
        val alarm = RuleEngine.match("Set alarm 6:30 pm")?.singleOrNull()
        val negation = RuleEngine.match("don't set an alarm")
        val parsed = PlannerJson.parse("{\"steps\":[{\"tool\":\"clock.time\",\"args\":{}}]}")
        val invalidJsonRejected = runCatching { PlannerJson.parse("garbage") }.isFailure
        val validTool = validator.validate(PlanStep("alarm.set", mapOf("hour" to 6, "minute" to 30))) == null
        val invalidRangeRejected = validator.validate(PlanStep("alarm.set", mapOf("hour" to 25, "minute" to 30))) != null
        val followup = FollowUpResolver.resolve("Why did he buy Twitter?", TopicState("Ada Lovelace", "person", "Who is Ada?", 1_000))
        val checks = listOf(
            (alarm != null && alarm.tool == "alarm.set" && alarm.args["hour"] == 18 && alarm.args["minute"] == 30) to "alarm rule parsing",
            (negation != null && negation.isEmpty()) to "negation handling",
            (SafeMath.evaluate("2 + 3 * 4") == 14.0) to "safe arithmetic",
            (parsed.singleOrNull()?.tool == "clock.time" && invalidJsonRejected) to "strict planner JSON parsing",
            (validTool && invalidRangeRejected) to "tool argument and range validation",
            (followup == "Why did Ada Lovelace buy Twitter?") to "memory follow-up resolution",
            (ToolRegistry().relevant("set a timer for five minutes").size <= 5) to "planner tool-list cap"
        )
        val failed = checks.filterNot { it.first }.map { it.second }
        if (failed.isEmpty()) DiagnosticCheck("Rules, planner, and validation", DiagnosticStatus.PASS,
            "Rule parsing, negation, arithmetic, strict JSON rejection, argument bounds, follow-up rewriting, and the top-five planner tool limit passed.")
        else DiagnosticCheck("Rules, planner, and validation", DiagnosticStatus.FAIL,
            "Failed subchecks: ${failed.joinToString()}. The failure is in the named deterministic code path; update/fix that component before relying on it.")
    } catch (e: Exception) {
        DiagnosticCheck("Rules, planner, and validation", DiagnosticStatus.FAIL,
            "A deterministic logic self-test threw an error: ${shortReason(e)}")
    }

    private fun checkSafeRuntimeTools(context: Context): DiagnosticCheck {
        val databaseName = scratchDatabaseName()
        val runtime = ToolRuntime(context, databaseName = databaseName)
        val marker = "diag${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val steps = listOf(
            PlanStep("clock.time", emptyMap()),
            PlanStep("clock.date", emptyMap()),
            PlanStep("math.calculate", mapOf("expression" to "2 + 3 * 4")),
            PlanStep("note.save", mapOf("text" to "diagnostic safe-save $marker")),
            PlanStep("note.list", emptyMap()),
            PlanStep("note.search", mapOf("query" to marker)),
            PlanStep("app.search", mapOf("query" to marker)),
            PlanStep("battery/status", emptyMap())
        )
        return try {
            val results = runtime.execute(steps)
            val failures = results.zip(steps).filter { (result, _) ->
                if (result.status != ActionStatus.VERIFIED) return@filter true
                when (result.tool) {
                    "clock.time", "clock.date" -> result.message.isBlank()
                    "math.calculate" -> result.message != "14.0"
                    "note.list", "note.search" -> !result.message.contains(marker)
                    "battery/status" -> {
                        val values = Regex("Battery (-?\\d+)%; RAM available (\\d+) MB; storage available (\\d+) MB").matchEntire(result.message)
                        if (values == null) true else {
                            val percent = values.groupValues[1].toIntOrNull()
                            percent == null || percent !in 0..100 ||
                                values.groupValues[2].toLongOrNull() == null || values.groupValues[3].toLongOrNull() == null
                        }
                    }
                    else -> false
                }
            }.map { (result, _) ->
                "${result.tool}=${if (result.status == ActionStatus.VERIFIED) "unexpected read-back" else result.status.name}"
            }
            if (results.size == steps.size && failures.isEmpty()) DiagnosticCheck("Safe tool runtime", DiagnosticStatus.PASS,
                "Clock time/date, arithmetic, isolated note save/list/search, read-only app search, and battery/RAM/storage read passed through the real tool runtime and verifier. Test notes were removed afterward.")
            else DiagnosticCheck("Safe tool runtime", DiagnosticStatus.FAIL,
                "${(failures.ifEmpty { listOf("The runtime returned an unexpected number of results") }).joinToString("; ")}. The named tool or its verifier needs investigation.")
        } catch (e: Exception) {
            DiagnosticCheck("Safe tool runtime", DiagnosticStatus.FAIL,
                "The safe runtime self-test stopped: ${shortReason(e)}. The issue is in tool execution, the system service, or local storage.")
        } finally {
            runtime.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun checkMemoryStores(context: Context): DiagnosticCheck {
        val databaseName = scratchDatabaseName()
        val marker = "diagnostic${UUID.randomUUID().toString().replace("-", "").take(16)}"
        var helper: AppDatabase? = null
        return try {
            val first = AppDatabase(context, databaseName)
            helper = first
            val messageId = MessageStore(first).append("user", marker)
            val notes = NoteStore(first)
            notes.save(marker)
            FactStore(first).capture("My name is $marker")
            TopicStateStore(first).set(marker, "test", "diagnostic topic", 1_000_000L)
            SummaryStore(first).update("diagnostic summary $marker")
            RecallStore(first).index(messageId, "diagnostic recall $marker")
            ActionLogStore(first).record("clock.time", "clock.time", "{}", "VERIFIED", "diagnostic action")
            first.close()

            val reopened = AppDatabase(context, databaseName)
            helper = reopened
            val persisted = MessageStore(reopened).recent(2).any { it.content == marker } &&
                NoteStore(reopened).list(5).any { it.content == marker } &&
                FactStore(reopened).all().any { it.key == "name" && it.value == marker } &&
                TopicStateStore(reopened).current(1_000_000L)?.entity == marker &&
                SummaryStore(reopened).get()?.contains(marker) == true &&
                RecallStore(reopened).recall("diagnostic", null).any { it.text.contains(marker) } &&
                ActionLogStore(reopened).recent(5).any { it.tool == "clock.time" }
            if (!persisted) throw IllegalStateException("A saved memory component did not survive database reopen")

            clearMemory(reopened)
            val cleared = MessageStore(reopened).all().isEmpty() && FactStore(reopened).all().isEmpty() &&
                TopicStateStore(reopened).current(1_000_000L) == null && SummaryStore(reopened).get() == null &&
                RecallStore(reopened).recall("diagnostic", null).isEmpty() && ActionLogStore(reopened).recent(5).isEmpty()
            val notesPreserved = NoteStore(reopened).list(5).any { it.content == marker }
            if (!cleared || !notesPreserved) throw IllegalStateException("Clear-memory semantics did not match the expected behavior")
            DiagnosticCheck("Memory database and clear-memory", DiagnosticStatus.PASS,
                "Messages, notes, facts, topic, summary, recall, and action log survived a close/reopen cycle. Clearing memory removed recall/chat-derived records and preserved the note. All data was in a temporary database that has been deleted.")
        } catch (e: Exception) {
            DiagnosticCheck("Memory database and clear-memory", DiagnosticStatus.FAIL,
                "An isolated save/read/reopen/clear check failed: ${shortReason(e)}. Check SQLite schema/store code; the user's database was not used.")
        } finally {
            helper?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun checkDeviceCapabilities(context: Context): DiagnosticCheck {
        val observations = mutableListOf<String>()
        val problems = mutableListOf<String>()
        fun intentCheck(title: String, intent: Intent) {
            val available = runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)
            observations += "$title ${if (available) "available" else "no handler found"}"
            if (!available) problems += title
        }
        intentCheck("Alarm intent", Intent(AlarmClock.ACTION_SET_ALARM))
        intentCheck("Timer intent", Intent(AlarmClock.ACTION_SET_TIMER))
        intentCheck("Wi-Fi settings", Intent(Settings.ACTION_WIFI_SETTINGS))
        intentCheck("Bluetooth settings", Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        intentCheck("Airplane settings", Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
        intentCheck("Location settings", Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        val hasFlash = runCatching {
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.any { id ->
                (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                    .getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrDefault(false)
        observations += if (hasFlash) "flash hardware present (not toggled)" else "no camera flash reported"
        if (!hasFlash) problems += "flash hardware"
        val volumeMax = runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(0)
        observations += if (volumeMax > 0) "media-volume service readable" else "media-volume service unavailable"
        if (volumeMax <= 0) problems += "media-volume service"
        val canWriteBrightness = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)
        observations += if (canWriteBrightness) "brightness special access granted" else "brightness needs user-granted special access"
        val details = observations.joinToString("; ") + ". These are availability checks only; no alarm, timer, light, volume, brightness, or settings value was changed."
        return when {
            problems.isNotEmpty() -> DiagnosticCheck("Phone feature capability preflight", DiagnosticStatus.WARN,
                "Unavailable or not granted: ${problems.joinToString()}. $details")
            !canWriteBrightness -> DiagnosticCheck("Phone feature capability preflight", DiagnosticStatus.WARN,
                "Brightness access is not granted. Grant the app's special brightness access only if you want that feature. $details")
            else -> DiagnosticCheck("Phone feature capability preflight", DiagnosticStatus.PASS, details)
        }
    }

    private suspend fun checkLoadedModel(controller: AgentController): DiagnosticCheck {
        val outcome = controller.runLoadedModelDiagnostic()
        return when {
            outcome.startsWith("PASS:") -> DiagnosticCheck("Loaded model generation", DiagnosticStatus.PASS, outcome.removePrefix("PASS: "))
            outcome.startsWith("FAIL:") -> DiagnosticCheck("Loaded model generation", DiagnosticStatus.FAIL, outcome.removePrefix("FAIL: "))
            else -> DiagnosticCheck("Loaded model generation", DiagnosticStatus.NOT_RUN, outcome.removePrefix("NOT_RUN: ").ifBlank { outcome })
        }
    }

    private fun checkMemoryPressure(context: Context): DiagnosticCheck = try {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val availableMb = info.availMem / (1024 * 1024)
        val totalMb = info.totalMem / (1024 * 1024)
        val thresholdMb = info.threshold / (1024 * 1024)
        val details = "Android lowMemory=${info.lowMemory}; available=${availableMb} MB of ${totalMb} MB; OS low-memory threshold=${thresholdMb} MB. This is read-only reporting; the tester does not stop generation or unload the model."
        if (info.lowMemory) DiagnosticCheck("Memory pressure (Android report)", DiagnosticStatus.WARN, details)
        else DiagnosticCheck("Memory pressure (Android report)", DiagnosticStatus.PASS, details)
    } catch (e: Exception) {
        DiagnosticCheck("Memory pressure (Android report)", DiagnosticStatus.WARN,
            "Android memory status could not be read: ${shortReason(e)}. The tester did not alter generation or model state.")
    }

    private fun appVersion(context: Context): String = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName ?: "unknown"} (${info.longVersionCode})"
    } catch (_: Exception) { "unknown" }

    private fun scratchDatabaseName() = "diagnostics-${UUID.randomUUID()}.db"

    private fun shortReason(error: Throwable): String =
        (error.message?.replace(Regex("[\\r\\n]+"), " ")?.take(180)?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
}
