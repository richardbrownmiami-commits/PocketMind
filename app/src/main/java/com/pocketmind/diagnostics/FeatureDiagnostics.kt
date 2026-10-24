package com.pocketmind.diagnostics

import android.app.ActivityManager
import android.content.ComponentName
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
import android.util.Log
import com.pocketmind.core.AgentController
import com.pocketmind.core.PromptBudget
import com.pocketmind.core.TopicExtractor
import com.pocketmind.engine.ModelStore
import com.pocketmind.memory.ActionLogStore
import com.pocketmind.memory.AppDatabase
import com.pocketmind.memory.FactStore
import com.pocketmind.memory.FollowUpResolver
import com.pocketmind.memory.MemoryRules
import com.pocketmind.memory.MessageStore
import com.pocketmind.memory.NoteStore
import com.pocketmind.memory.RecallStore
import com.pocketmind.memory.SummaryStore
import com.pocketmind.memory.TopicState
import com.pocketmind.memory.TopicStateStore
import com.pocketmind.memory.clearMemory
import com.pocketmind.tools.PlanStep
import com.pocketmind.tools.PlannerJson
import com.pocketmind.tools.RuleEngine
import com.pocketmind.tools.SafeMath
import com.pocketmind.tools.ToolRegistry
import com.pocketmind.tools.ToolApprovalPolicy
import com.pocketmind.tools.ToolSpec
import com.pocketmind.tools.ToolValidator
import com.pocketmind.ui.DiagnosticsActivity
import com.pocketmind.ui.MainActivity
import com.pocketmind.ui.MemoryActivity
import com.pocketmind.ui.ModelsActivity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Results and logs contain diagnostic metadata only, never real chats, notes, or fact values. */
enum class DiagnosticStatus { PASS, WARN, FAIL, NOT_RUN }

data class DiagnosticCheck(
    val title: String,
    val status: DiagnosticStatus,
    val details: String,
    val elapsedMs: Long = 0L
)

data class DiagnosticProgress(
    val index: Int,
    val total: Int,
    val title: String,
    val status: DiagnosticStatus? = null,
    val details: String = ""
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
        failures > 0 -> "$failures issue(s) failed checks. Read each failed log for the likely cause and next step."
        warnings > 0 -> "No check failed; $warnings item(s) need attention or are preflight-only. $notRun check(s) remain not run; review the individual results."
        notRun > 0 -> "$notRun check(s) remain not run. Review model-dependent routes and any device-action tests awaiting confirmation or skipped."
        else -> "All checks passed."
    }

    fun asShareText(): String = buildString {
        appendLine("PocketMind diagnostic report · all-feature QA")
        appendLine("Created: ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(createdAt))}")
        appendLine("Device: $device")
        appendLine("Android: $android")
        appendLine("App version: $appVersion")
        appendLine()
        appendLine(summary())
        appendLine("Checks: ${checks.size} total · ${checks.count { it.status == DiagnosticStatus.PASS }} passed · $warnings warnings · $failures failed · $notRun not run")
        appendLine()
        checks.forEach { check ->
            appendLine("[${check.status}] ${check.title}${if (check.elapsedMs > 0) " (${check.elapsedMs} ms)" else ""}")
            appendLine(check.details)
            appendLine()
        }
        appendLine("Privacy: runs offline on this device. Synthetic prompts and generated text are not saved to chat, memory, notes, logs, or this report. Only explicitly confirmed device actions write their tool IDs, arguments, and results to local action history. Automatic checks use temporary databases; this report excludes chat, note, and fact contents and is not sent automatically.")
    }
}

/** Runs isolated checks in order; Start never invokes ToolRuntime or executes a tool action. */
object FeatureDiagnostics {
    private data class Task(val title: String, val execute: suspend () -> DiagnosticCheck)

    suspend fun run(
        context: Context,
        controller: AgentController,
        initialUiChecks: List<DiagnosticCheck> = emptyList(),
        syntheticChatResults: SyntheticChatTestResults? = null,
        onProgress: suspend (DiagnosticProgress) -> Unit = {}
    ): DiagnosticReport {
        val app = context.applicationContext
        val checks = mutableListOf<DiagnosticCheck>()
        val scratchDatabases = linkedSetOf<String>()
        val memoryDatabaseForFacts = scratchDatabaseName().also(scratchDatabases::add)
        val memoryDatabaseForPersistence = scratchDatabaseName().also(scratchDatabases::add)
        val memoryDatabaseForClear = scratchDatabaseName().also(scratchDatabases::add)
        val marker = "diag-${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val registry = ToolRegistry()
        val validator = ToolValidator(registry)
        val tasks = mutableListOf<Task>()

        initialUiChecks.forEach { result -> tasks += Task(result.title) { result } }
        tasks += Task("Platform · Android 11 and ARMv7") { checkPlatform() }
        tasks += Task("Privacy · installed app has no INTERNET permission") { checkOfflinePolicy(app) }
        tasks += Task("Engine · ARM native library loads") { checkNativeLibrary(app) }
        tasks += Task("Models · imported/selected model readiness") { checkModelState(app) }
        tasks += Task("Runtime · Android memory status is read-only") { checkMemoryPressure(app) }
        tasks += Task("UI · all four app screens are registered") { checkScreens(app) }
        tasks += Task("UI · synthetic QA turns shown in Chat") {
            val result = syntheticChatResults
            when {
                result?.completed == true -> DiagnosticCheck("UI · synthetic QA turns shown in Chat", DiagnosticStatus.PASS,
                    "The fixed QA questions and feature requests were shown as temporary user/assistant turns on the Chat screen. They were not saved.")
                result == null -> DiagnosticCheck("UI · synthetic QA turns shown in Chat", DiagnosticStatus.NOT_RUN,
                    "This tester invocation did not launch the synthetic Chat session; no prompts or responses were saved.")
                else -> DiagnosticCheck("UI · synthetic QA turns shown in Chat", DiagnosticStatus.NOT_RUN,
                    "The Chat-screen synthetic QA session was cancelled or stopped before completion. Its temporary turns were not saved.")
            }
        }

        tasks += Task("Rules · alarm parsing and 12-hour conversion") {
            val parsed = RuleEngine.match("Set alarm 6:30 pm")?.singleOrNull()
            outcome("Rules · alarm parsing and 12-hour conversion",
                parsed != null && parsed.tool == "alarm.set" && parsed.args["hour"] == 18 && parsed.args["minute"] == 30,
                "A 6:30 pm request becomes 18:30 without creating an alarm.",
                "Alarm parsing or AM/PM conversion returned unexpected arguments.")
        }
        tasks += Task("Rules · timer parsing and range checks") {
            val parsed = RuleEngine.match("set a timer for 5 minutes")?.singleOrNull()
            outcome("Rules · timer parsing and range checks",
                parsed != null && parsed.tool == "timer.set" && parsed.args["seconds"] == 300 && RuleEngine.match("set timer for 999999 hours") == null,
                "A five-minute request becomes 300 seconds; an out-of-range request is rejected. No timer is started.",
                "Timer parsing or range rejection returned unexpected results.")
        }
        tasks += Task("Rules · negation blocks phone actions") {
            val blocked = listOf("don't set an alarm", "do not start a timer", "never turn on flashlight")
                .all { RuleEngine.match(it).orEmpty().isEmpty() }
            outcome("Rules · negation blocks phone actions", blocked,
                "Alarm, timer, and flashlight commands preceded by negation produce no action steps.",
                "A negated request produced an action step.")
        }
        tasks += Task("Rules · note and clock command routing") {
            val okay = RuleEngine.match("what time is it")?.singleOrNull()?.tool == "clock.time" &&
                RuleEngine.match("what is the date")?.singleOrNull()?.tool == "clock.date" &&
                RuleEngine.match("list my notes")?.singleOrNull()?.tool == "note.list" &&
                RuleEngine.match("search notes for apples")?.singleOrNull()?.tool == "note.search" &&
                RuleEngine.match("remember to call mom")?.singleOrNull()?.tool == "note.save"
            outcome("Rules · note and clock command routing", okay,
                "Time, date, note list/search, and reminder-to-note commands map to their expected tools without running them.",
                "One or more simple commands did not map to the expected tool.")
        }
        tasks += Task("Logic · arithmetic parser and invalid input") {
            val valid = SafeMath.evaluate("2 + 3 * 4") == 14.0 && SafeMath.evaluate("-(8 - 3) * 2") == -10.0
            val invalidRejected = runCatching { SafeMath.evaluate("1 / 0") }.isFailure &&
                runCatching { SafeMath.evaluate("2 + shell") }.isFailure
            outcome("Logic · arithmetic parser and invalid input", valid && invalidRejected,
                "Precedence, parentheses, unary signs, division-by-zero rejection, and invalid-token rejection passed.",
                "Arithmetic result or invalid-expression rejection was incorrect.")
        }
        tasks += Task("Planner · valid JSON is parsed") {
            val parsed = PlannerJson.parse("{\"steps\":[{\"tool\":\"clock.time\",\"args\":{}}]}")
            outcome("Planner · valid JSON is parsed", parsed.singleOrNull()?.tool == "clock.time",
                "A valid one-step tool plan was parsed.", "A valid planner response did not produce the expected step.")
        }
        tasks += Task("Planner · malformed or extra JSON is rejected") {
            val invalid = listOf("garbage", "{}", "{\"steps\":[],\"extra\":true}", "{\"steps\":[]} trailing")
                .all { runCatching { PlannerJson.parse(it) }.isFailure }
            outcome("Planner · malformed or extra JSON is rejected", invalid,
                "Garbage, missing steps, unknown top-level fields, and trailing data are rejected before execution.",
                "At least one invalid planner output was accepted.")
        }
        tasks += Task("Memory logic · follow-up rewriting and command guard") {
            val topic = TopicState("Ada Lovelace", "person", "Who is Ada?", 1_000L)
            val rewritten = FollowUpResolver.resolve("Why did he buy Twitter?", topic)
            val commandSafe = FollowUpResolver.resolve("search for her biography", topic) == null
            outcome("Memory logic · follow-up rewriting and command guard",
                rewritten == "Why did Ada Lovelace buy Twitter?" && commandSafe,
                "A pronoun follow-up uses the active temporary topic, while an action command is not rewritten.",
                "Follow-up text was not rewritten safely or a command was rewritten.")
        }
        tasks += Task("Memory logic · topic extraction and 30-minute expiry") {
            val extracted = TopicExtractor.extract("Who is Ada Lovelace?")
            val state = TopicState("Ada", "person", "Who is Ada?", 1_000L)
            val expires = !MemoryRules.topicIsActive(state, 1_000L + 30 * 60 * 1000L + 1)
            outcome("Memory logic · topic extraction and 30-minute expiry",
                extracted?.entity == "Ada Lovelace" && expires,
                "A named topic is extracted and temporary topic state expires after 30 minutes.",
                "Topic extraction or topic expiration did not match the expected rule.")
        }
        tasks += Task("Memory logic · prompt budget preserves the question") {
            val fitted = runCatching {
                PromptBudget.fit(
                    PromptBudget.Inputs("keep this question", listOf("old recall ".repeat(12)), "old summary ".repeat(12),
                        listOf("old fact ".repeat(12)), (1..10).map { "old turn $it ".repeat(12) }),
                    contextTokens = 90,
                    countTokens = { text -> text.split(Regex("\\s+")).count { it.isNotBlank() } }
                )
            }.getOrNull()
            outcome("Memory logic · prompt budget preserves the question",
                fitted != null && fitted.tokens <= 90 && fitted.prompt.contains("keep this question"),
                "The prompt stays within its token budget and retains the current question.",
                "Prompt fitting overflowed the budget or dropped the current question.")
        }
        tasks += Task("Planner · relevant tools stay within top-five cap") {
            val result = registry.relevant("set a timer for five minutes", limit = 20)
            outcome("Planner · relevant tools stay within top-five cap", result.size <= 5,
                "Tool retrieval never returns more than five choices to the planner.",
                "Planner retrieval returned more than five tools.")
        }

        registry.all.forEach { spec ->
            tasks += Task("Tool schema · ${spec.id}") {
                val step = sampleStep(spec, marker, app.packageName)
                val invalid = validator.validate(step)
                outcome("Tool schema · ${spec.id}", invalid == null,
                    if (invalid == null) "The registered argument schema accepts a valid diagnostic example (risk ${spec.risk.name.lowercase(Locale.ROOT)})."
                    else "The example arguments failed validation: $invalid",
                    "The tool's registered schema rejected its known-good diagnostic example.")
            }
        }

        registry.all.filter { ToolApprovalPolicy.requiresApproval(it.risk) }.forEach { spec ->
            tasks += Task("Safety · ${spec.id} requires explicit approval") {
                val gated = ToolApprovalPolicy.requiresApproval(spec.risk)
                outcome("Safety · ${spec.id} requires explicit approval", gated,
                    "The pure risk policy requires explicit approval; Start did not invoke ToolRuntime or touch device state.",
                    "The tool's risk level did not require the expected approval gate.")
            }
        }
        DiagnosticActionTestPolicy.initialChecks(registry).forEach { check ->
            tasks += Task(check.title) { check }
        }

        tasks += Task("Memory · explicit fact capture, lookup, and forget") {
            checkFactStore(app, memoryDatabaseForFacts, marker)
        }
        tasks += Task("Memory · messages, notes, recall, topic, summary, and action log persist") {
            checkMemoryPersistence(app, memoryDatabaseForPersistence, marker)
        }
        tasks += Task("Memory · clear-memory removes derived data and preserves notes") {
            checkClearMemory(app, memoryDatabaseForClear, marker)
        }

        tasks += capabilityTask("Alarm handler", app, Intent(AlarmClock.ACTION_SET_ALARM), "alarm.set")
        tasks += capabilityTask("Timer handler", app, Intent(AlarmClock.ACTION_SET_TIMER), "timer.set")
        tasks += Task("Preflight · flashlight hardware availability") { checkFlashlight(app) }
        tasks += Task("Preflight · media volume service availability") { checkVolume(app) }
        tasks += Task("Preflight · brightness access status (no write)") { checkBrightness(app) }
        tasks += capabilityTask("App launch target resolution", app, app.packageManager.getLaunchIntentForPackage(app.packageName), "app.open")
        tasks += capabilityTask("Wi-Fi settings handler", app, Intent(Settings.ACTION_WIFI_SETTINGS), "wifi.settings")
        tasks += capabilityTask("Bluetooth settings handler", app, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "bluetooth.settings")
        tasks += capabilityTask("Airplane settings handler", app, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), "airplane.settings")
        tasks += capabilityTask("Location settings handler", app, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "location.settings")
        tasks += Task("Model · loaded local generation smoke test") { checkLoadedModel(controller) }
        tasks += Task("Model · loaded local synthetic Q&A correctness") {
            syntheticChatResults?.let(::checkSyntheticChatQa) ?: checkLoadedModelQa(controller)
        }
        SyntheticFeatureRouteQa.probes.forEach { probe ->
            tasks += Task("Feature routing · ${probe.toolId}") {
                syntheticChatResults?.let { checkSyntheticChatRoute(probe, it) } ?: checkFeatureRoute(controller, probe)
            }
        }
        tasks += Task("Input routing · incomplete alarm asks for a time") {
            val answer = RuleEngine.clarification("Set an alarm")
            outcome("Input routing · incomplete alarm asks for a time", answer?.contains("What time", ignoreCase = true) == true,
                "The assistant asks for the missing alarm time; no alarm is scheduled.",
                "The incomplete alarm request did not produce a time clarification; no alarm is scheduled.")
        }
        tasks += Task("Input routing · incomplete absolute volume asks for a level") {
            val answer = RuleEngine.clarification("Set media volume")
            outcome("Input routing · incomplete absolute volume asks for a level", answer?.contains("0 to 100", ignoreCase = true) == true,
                "The assistant asks for a media-volume target; no volume is changed.",
                "The incomplete absolute-volume request did not produce a target clarification; no volume is changed.")
        }

        try {
            val total = tasks.size
            tasks.forEachIndexed { index, task ->
                val number = index + 1
                onProgress(DiagnosticProgress(number, total, task.title, null, "Running"))
                Log.i("PocketMindTester", "[$number/$total] START ${task.title}")
                val start = System.nanoTime()
                val check = try {
                    task.execute()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    DiagnosticCheck(task.title, DiagnosticStatus.FAIL,
                        "The check crashed (${error.javaClass.simpleName}: ${shortReason(error)}). Inspect the named feature and rerun.")
                }
                val measured = check.copy(elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L))
                checks += measured
                Log.i("PocketMindTester", "[$number/$total] ${measured.status} ${measured.title} (${measured.elapsedMs} ms): ${measured.details}")
                onProgress(DiagnosticProgress(number, total, measured.title, measured.status, measured.details))
            }
        } finally {
            scratchDatabases.forEach { name -> runCatching { app.deleteDatabase(name) } }
        }

        return DiagnosticReport(
            createdAt = System.currentTimeMillis(),
            device = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Unknown device" },
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}; ${Build.SUPPORTED_ABIS.joinToString()})",
            appVersion = appVersion(app),
            checks = checks
        )
    }

    private fun checkPlatform(): DiagnosticCheck {
        val isSupported = Build.VERSION.SDK_INT >= 30 && !Process.is64Bit() && Build.SUPPORTED_ABIS.any { it.equals("armeabi-v7a", true) }
        return if (isSupported) DiagnosticCheck("Platform · Android 11 and ARMv7", DiagnosticStatus.PASS,
            "This process is 32-bit on Android API ${Build.VERSION.SDK_INT}; ARMv7 is present in the device ABI list.")
        else DiagnosticCheck("Platform · Android 11 and ARMv7", DiagnosticStatus.FAIL,
            "This build targets Android 11+ and armeabi-v7a. Detected API ${Build.VERSION.SDK_INT}, 64-bit process=${Process.is64Bit()}, ABIs=${Build.SUPPORTED_ABIS.joinToString()}.")
    }

    private fun checkOfflinePolicy(context: Context): DiagnosticCheck = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requestsInternet = info.requestedPermissions.orEmpty().contains("android.permission.INTERNET")
        if (requestsInternet) DiagnosticCheck("Privacy · installed app has no INTERNET permission", DiagnosticStatus.FAIL,
            "The installed app requests INTERNET. Remove that permission to preserve the offline-only policy.")
        else DiagnosticCheck("Privacy · installed app has no INTERNET permission", DiagnosticStatus.PASS,
            "The installed app does not request android.permission.INTERNET.")
    } catch (error: Exception) {
        DiagnosticCheck("Privacy · installed app has no INTERNET permission", DiagnosticStatus.WARN,
            "Could not inspect the installed permission list: ${shortReason(error)}")
    }

    private fun checkNativeLibrary(context: Context): DiagnosticCheck {
        val library = File(context.applicationInfo.nativeLibraryDir, "libpocketmind.so")
        if (!library.isFile) return DiagnosticCheck("Engine · ARM native library loads", DiagnosticStatus.FAIL,
            "libpocketmind.so is missing from the installed app. Reinstall the correct ARMv7 APK.")
        return try {
            System.loadLibrary("pocketmind")
            DiagnosticCheck("Engine · ARM native library loads", DiagnosticStatus.PASS,
                "The packaged native library exists and the Android linker loaded it. Inference is checked separately if a model is already loaded.")
        } catch (error: Throwable) {
            DiagnosticCheck("Engine · ARM native library loads", DiagnosticStatus.FAIL,
                "The native library could not be loaded (${shortReason(error)}); check ABI and native dependencies.")
        }
    }

    private fun checkModelState(context: Context): DiagnosticCheck = try {
        val store = ModelStore(context)
        val models = store.list()
        val selected = store.selected()
        when {
            selected == null -> DiagnosticCheck("Models · imported/selected model readiness", DiagnosticStatus.WARN,
                if (models.isEmpty()) "No GGUF model is imported. Rule-based commands work without a model; local chat and model planning require an imported model."
                else "Models are imported, but none is selected. Select a model from the Models screen.")
            !selected.isFile -> DiagnosticCheck("Models · imported/selected model readiness", DiagnosticStatus.FAIL,
                "The selected GGUF file is missing. Re-import a model from the Models screen.")
            selected.length() <= 0L -> DiagnosticCheck("Models · imported/selected model readiness", DiagnosticStatus.FAIL,
                "The selected model file is empty. Re-import the file.")
            selected.length() > ModelStore.MAX_MODEL_BYTES -> DiagnosticCheck("Models · imported/selected model readiness", DiagnosticStatus.FAIL,
                "The selected model exceeds the app's 1.2 GB size limit. Choose a smaller quantized model.")
            else -> DiagnosticCheck("Models · imported/selected model readiness", DiagnosticStatus.PASS,
                "A selected GGUF file exists and is within the size limit; successful import does not guarantee model compatibility.")
        }
    } catch (error: Exception) {
        DiagnosticCheck("Models · imported/selected model readiness", DiagnosticStatus.FAIL,
            "The model store could not be inspected: ${shortReason(error)}")
    }

    private fun checkMemoryPressure(context: Context): DiagnosticCheck = try {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val details = "Android lowMemory=${info.lowMemory}; available=${info.availMem / 1_048_576} MB of ${info.totalMem / 1_048_576} MB; OS threshold=${info.threshold / 1_048_576} MB. Read-only: PocketMind applies no RAM gate, does not stop generation, and does not unload a model from this reading."
        DiagnosticCheck("Runtime · Android memory status is read-only",
            if (info.lowMemory) DiagnosticStatus.WARN else DiagnosticStatus.PASS, details)
    } catch (error: Exception) {
        DiagnosticCheck("Runtime · Android memory status is read-only", DiagnosticStatus.WARN,
            "Android memory status could not be read: ${shortReason(error)}. No generation or model state was changed.")
    }

    private fun checkScreens(context: Context): DiagnosticCheck {
        val screens = listOf(MainActivity::class.java, ModelsActivity::class.java, MemoryActivity::class.java, DiagnosticsActivity::class.java)
        val missing = screens.filterNot { clazz ->
            runCatching { context.packageManager.getActivityInfo(ComponentName(context, clazz), 0) }.isSuccess
        }
        return if (missing.isEmpty()) DiagnosticCheck("UI · all four app screens are registered", DiagnosticStatus.PASS,
            "Chat, Models, Memory, and the dedicated Tester screen are present in the installed app. No user screen or user data was opened.")
        else DiagnosticCheck("UI · all four app screens are registered", DiagnosticStatus.FAIL,
            "Missing app screen registration: ${missing.joinToString { it.simpleName }}. Check AndroidManifest activity declarations.")
    }

    private fun checkFactStore(context: Context, databaseName: String, marker: String): DiagnosticCheck = withScratchDatabase(context, databaseName) { db ->
        val facts = FactStore(db)
        val captured = facts.capture("My name is $marker")
        val found = facts.all().any { it.key == "name" && it.value == marker } && facts.relevant(marker).any { it.value == marker }
        val forgotten = facts.forget(marker) == 1 && facts.all().isEmpty()
        if (captured && found && forgotten) DiagnosticCheck("Memory · explicit fact capture, lookup, and forget", DiagnosticStatus.PASS,
            "A synthetic explicit fact was captured, found by lookup, then forgotten from a disposable database.")
        else DiagnosticCheck("Memory · explicit fact capture, lookup, and forget", DiagnosticStatus.FAIL,
            "Synthetic fact capture, retrieval, or forget did not behave as expected; inspect FactStore and memory rules.")
    }

    private fun checkMemoryPersistence(context: Context, databaseName: String, marker: String): DiagnosticCheck {
        var helper: AppDatabase? = null
        return try {
            val first = AppDatabase(context, databaseName)
            helper = first
            val messageId = MessageStore(first).append("user", marker)
            NoteStore(first).save("diagnostic note $marker")
            TopicStateStore(first).set(marker, "test", "diagnostic topic", 1_000_000L)
            SummaryStore(first).update("diagnostic summary $marker")
            RecallStore(first).index(messageId, "diagnostic recall $marker")
            ActionLogStore(first).record("clock.time", "clock.time", "{}", "VERIFIED", "diagnostic action")
            first.close()

            val reopened = AppDatabase(context, databaseName)
            helper = reopened
            val persisted = MessageStore(reopened).recent(2).any { it.content == marker } &&
                NoteStore(reopened).list(5).any { it.content == "diagnostic note $marker" } &&
                TopicStateStore(reopened).current(1_000_000L)?.entity == marker &&
                SummaryStore(reopened).get()?.contains(marker) == true &&
                RecallStore(reopened).recall("diagnostic", null).any { it.text.contains(marker) } &&
                ActionLogStore(reopened).recent(5).any { it.tool == "clock.time" }
            if (persisted) DiagnosticCheck("Memory · messages, notes, recall, topic, summary, and action log persist", DiagnosticStatus.PASS,
                "Synthetic records in messages, notes, topic, summary, recall, and action log survived close/reopen in a disposable database.")
            else DiagnosticCheck("Memory · messages, notes, recall, topic, summary, and action log persist", DiagnosticStatus.FAIL,
                "One or more synthetic memory records failed to survive close/reopen; inspect the affected store and SQLite schema.")
        } catch (error: Exception) {
            DiagnosticCheck("Memory · messages, notes, recall, topic, summary, and action log persist", DiagnosticStatus.FAIL,
                "Isolated memory persistence check failed: ${shortReason(error)}. The user's database was not opened.")
        } finally {
            helper?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun checkClearMemory(context: Context, databaseName: String, marker: String): DiagnosticCheck {
        var helper: AppDatabase? = null
        return try {
            val db = AppDatabase(context, databaseName)
            helper = db
            val messageId = MessageStore(db).append("user", marker)
            NoteStore(db).save("diagnostic note $marker")
            FactStore(db).capture("My name is $marker")
            TopicStateStore(db).set(marker, "test", "temporary", 1_000_000L)
            SummaryStore(db).update("diagnostic summary $marker")
            RecallStore(db).index(messageId, "diagnostic recall $marker")
            ActionLogStore(db).record("clock.time", "clock.time", "{}", "VERIFIED", "diagnostic action")
            clearMemory(db)
            val cleared = MessageStore(db).all().isEmpty() && FactStore(db).all().isEmpty() &&
                TopicStateStore(db).current(1_000_000L) == null && SummaryStore(db).get() == null &&
                RecallStore(db).recall("diagnostic", null).isEmpty() && ActionLogStore(db).recent(5).isEmpty()
            val notePreserved = NoteStore(db).list(5).any { it.content == "diagnostic note $marker" }
            if (cleared && notePreserved) DiagnosticCheck("Memory · clear-memory removes derived data and preserves notes", DiagnosticStatus.PASS,
                "Clearing removed synthetic messages, facts, topic, summary, recall, and action history while retaining the synthetic note; disposable database only.")
            else DiagnosticCheck("Memory · clear-memory removes derived data and preserves notes", DiagnosticStatus.FAIL,
                "Clear-memory semantics differed from the expected policy; inspect clearMemory and memory stores.")
        } catch (error: Exception) {
            DiagnosticCheck("Memory · clear-memory removes derived data and preserves notes", DiagnosticStatus.FAIL,
                "Isolated clear-memory check failed: ${shortReason(error)}. The user's database was not opened.")
        } finally {
            helper?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun checkFlashlight(context: Context): DiagnosticCheck = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val hasFlash = manager.cameraIdList.any { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (hasFlash) DiagnosticCheck("Preflight · flashlight hardware availability", DiagnosticStatus.PASS,
            "Flash hardware is present. It was not switched on or off.")
        else DiagnosticCheck("Preflight · flashlight hardware availability", DiagnosticStatus.WARN,
            "No camera flash was reported. No flashlight state was changed.")
    } catch (error: Exception) {
        DiagnosticCheck("Preflight · flashlight hardware availability", DiagnosticStatus.WARN,
            "Flash capability could not be read: ${shortReason(error)}. No light was toggled.")
    }

    private fun checkVolume(context: Context): DiagnosticCheck = try {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) > 0) DiagnosticCheck("Preflight · media volume service availability", DiagnosticStatus.PASS,
            "The media volume service is readable. The volume level was not changed.")
        else DiagnosticCheck("Preflight · media volume service availability", DiagnosticStatus.WARN,
            "The media volume service returned no usable range. The level was not changed.")
    } catch (error: Exception) {
        DiagnosticCheck("Preflight · media volume service availability", DiagnosticStatus.WARN,
            "Media volume capability could not be read: ${shortReason(error)}. The level was not changed.")
    }

    private fun checkBrightness(context: Context): DiagnosticCheck = try {
        val canWrite = Settings.System.canWrite(context)
        DiagnosticCheck("Preflight · brightness access status (no write)",
            if (canWrite) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            if (canWrite) "Brightness write access is present; no brightness value was read or changed."
            else "Brightness special access is not granted. The tester will not open Settings or ask for it; no brightness value was read or changed.")
    } catch (error: Exception) {
        DiagnosticCheck("Preflight · brightness access status (no write)", DiagnosticStatus.WARN,
            "Brightness access could not be inspected: ${shortReason(error)}. No setting was changed.")
    }

    private fun capabilityTask(title: String, context: Context, intent: Intent?, toolId: String): Task = Task("Preflight · $title") {
        val available = intent != null && runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)
        DiagnosticCheck("Preflight · $title",
            if (available) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            if (available) "A handler for $toolId is installed. It was resolved only; nothing was opened or changed."
            else "No handler for $toolId was found, or the system could not resolve it. No screen was opened.")
    }

    private suspend fun checkLoadedModel(controller: AgentController): DiagnosticCheck {
        val outcome = controller.runLoadedModelDiagnostic()
        return when {
            outcome.startsWith("PASS:") -> DiagnosticCheck("Model · loaded local generation smoke test", DiagnosticStatus.PASS,
                "The already-loaded local model generated a short response; no chat turn or tool action was added.")
            outcome.startsWith("FAIL:") -> DiagnosticCheck("Model · loaded local generation smoke test", DiagnosticStatus.FAIL,
                "The already-loaded local model did not complete a short generation test. Check model compatibility and the local native runtime; no chat turn or tool action was added.")
            else -> DiagnosticCheck("Model · loaded local generation smoke test", DiagnosticStatus.NOT_RUN,
                "No model is loaded, so inference was not run. Load a compatible local GGUF model and rerun to test generation.")
        }
    }

    private suspend fun checkLoadedModelQa(controller: AgentController): DiagnosticCheck {
        return SyntheticModelQa.asDiagnosticCheck(controller.runLoadedModelQaDiagnostic())
    }

    private fun checkSyntheticChatQa(results: SyntheticChatTestResults): DiagnosticCheck {
        val status = results.qaStatus
        val details = when (status) {
            DiagnosticStatus.PASS -> "Both fixed natural-language questions were asked in the transient Chat screen and their answers matched. Synthetic turns were not saved."
            DiagnosticStatus.FAIL -> "One or more visible synthetic Chat answers missed the expected content. Prompts and responses were not saved or included in this report."
            DiagnosticStatus.NOT_RUN -> if (!results.sessionStarted)
                "The transient Chat QA session did not start or return a result; no prompts or responses were saved."
            else if (results.interrupted)
                "Chat was recreated before all synthetic questions completed. The tester did not repeat them; unfinished checks remain NOT_RUN and only prior statuses were retained."
            else if (!results.modelLoaded)
                "No loaded local model was available in Chat, so synthetic Q&A was not run. Load a compatible model and rerun."
            else "The Chat-screen QA session stopped before all fixed questions could be checked; unfinished prompts remain NOT_RUN."
            DiagnosticStatus.WARN -> "The synthetic Chat Q&A could not be fully verified; no prompt or response was retained."
        }
        return DiagnosticCheck("Model · loaded local synthetic Q&A correctness", status, details)
    }

    private fun checkSyntheticChatRoute(probe: SyntheticFeatureRouteProbe, results: SyntheticChatTestResults): DiagnosticCheck {
        val status = results.routeStatuses[probe.toolId] ?: DiagnosticStatus.NOT_RUN
        val details = when (status) {
            DiagnosticStatus.PASS -> "The fixed synthetic request and response were shown in the transient Chat screen; the expected feature and validated arguments matched. No action was executed or saved."
            DiagnosticStatus.FAIL -> "The Chat-screen request did not produce the expected feature and valid arguments. No action was executed, and prompt/response text was not retained."
            DiagnosticStatus.NOT_RUN -> if (!results.sessionStarted)
                "The transient Chat QA session did not start or return a result. No action was executed."
            else if (results.interrupted)
                "Chat was recreated before this feature route completed. The prompt was not repeated; this route remains NOT_RUN and no action was executed."
            else if (!results.modelLoaded)
                "No loaded local model was available for this Chat-screen planner route. Deterministic rules may still pass; no action was executed."
            else "The Chat-screen test stopped before this route was checked; no action was executed."
            DiagnosticStatus.WARN -> "The synthetic Chat route could not be fully verified. No action was executed, and prompt/response text was not retained."
        }
        return DiagnosticCheck("Feature routing · ${probe.toolId}", status, details)
    }

    private suspend fun checkFeatureRoute(controller: AgentController, probe: SyntheticFeatureRouteProbe): DiagnosticCheck {
        val title = "Feature routing · ${probe.toolId}"
        val result = controller.runFeatureRouteDiagnostic(probe)
        return when (result.status) {
            DiagnosticStatus.PASS -> DiagnosticCheck(title, DiagnosticStatus.PASS,
                "A fixed synthetic request reached the expected feature with valid arguments. The proposed plan was not executed, saved, or included in the report.")
            DiagnosticStatus.FAIL -> DiagnosticCheck(title, DiagnosticStatus.FAIL,
                "The rule-first/local-model route did not produce the expected feature and valid arguments. No action was executed and prompt/answer text was not retained.")
            DiagnosticStatus.NOT_RUN -> DiagnosticCheck(title, DiagnosticStatus.NOT_RUN,
                "A local model is required for this planner route and none is loaded. No action was executed and prompt/answer text was not retained.")
            DiagnosticStatus.WARN -> DiagnosticCheck(title, DiagnosticStatus.WARN,
                "The synthetic feature route could not be fully verified. No action was executed and prompt/answer text was not retained.")
        }
    }

    private fun sampleStep(spec: ToolSpec, marker: String, appPackage: String): PlanStep = PlanStep(spec.id, when (spec.id) {
        "math.calculate" -> mapOf("expression" to "2 + 3 * 4")
        "note.save" -> mapOf("text" to "diagnostic $marker")
        "note.search" -> mapOf("query" to marker)
        "alarm.set" -> mapOf("hour" to 7, "minute" to 0, "label" to "diagnostic")
        "timer.set" -> mapOf("seconds" to 1)
        "flashlight" -> mapOf("enabled" to true)
        "volume" -> mapOf("level" to 50)
        "brightness" -> mapOf("level" to 128)
        "app.open" -> mapOf("package" to appPackage)
        "app.search" -> mapOf("query" to "PocketMind")
        else -> emptyMap()
    })

    private fun outcome(title: String, passed: Boolean, success: String, failure: String) = DiagnosticCheck(
        title, if (passed) DiagnosticStatus.PASS else DiagnosticStatus.FAIL, if (passed) success else failure
    )

    private inline fun withScratchDatabase(context: Context, name: String, block: (AppDatabase) -> DiagnosticCheck): DiagnosticCheck {
        var db: AppDatabase? = null
        return try {
            AppDatabase(context, name).also { db = it }.let(block)
        } catch (error: Exception) {
            DiagnosticCheck("Memory · isolated test database", DiagnosticStatus.FAIL, "Temporary database check failed: ${shortReason(error)}")
        } finally {
            db?.close()
            context.deleteDatabase(name)
        }
    }

    private fun appVersion(context: Context): String = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName ?: "unknown"} (${info.longVersionCode})"
    } catch (_: Exception) { "unknown" }

    private fun scratchDatabaseName() = "diagnostics-${UUID.randomUUID()}.db"

    private fun shortReason(error: Throwable): String =
        (error.message?.replace(Regex("[\\r\\n]+"), " ")?.replace(Regex("/(?:[^/\\s]+/)+[^/\\s]+"), "[local path]")
            ?.take(160)?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
}
