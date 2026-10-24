package com.pocketmind.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pocketmind.PocketMindApplication
import com.pocketmind.diagnostics.DiagnosticCheck
import com.pocketmind.diagnostics.DiagnosticProgress
import com.pocketmind.diagnostics.DiagnosticReport
import com.pocketmind.diagnostics.DiagnosticStatus
import com.pocketmind.diagnostics.DeviceStateReader
import com.pocketmind.diagnostics.DiagnosticActionTestPolicy
import com.pocketmind.diagnostics.FeatureDiagnostics
import com.pocketmind.diagnostics.SyntheticChatTestContract
import com.pocketmind.diagnostics.SyntheticChatTestResults
import com.pocketmind.diagnostics.SyntheticFeatureRouteQa
import com.pocketmind.tools.ActionStatus
import com.pocketmind.tools.ActionResult
import com.pocketmind.tools.PlanStep
import com.pocketmind.tools.PlannerJson
import com.pocketmind.tools.ToolRegistry
import com.pocketmind.tools.ToolValidator
import com.pocketmind.tools.TesterAlarmLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

class DiagnosticsActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var progress: TextView
    private lateinit var reportBody: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var runButton: Button
    private lateinit var shareButton: Button
    private lateinit var syntheticChatLauncher: ActivityResultLauncher<Intent>
    private var uiChecksForRun: List<DiagnosticCheck> = emptyList()
    private val resultRows = mutableMapOf<Int, Pair<TextView, TextView>>()
    private val actionButtons = mutableMapOf<String, Button>()
    private val runningActionTests = mutableSetOf<String>()
    private val completedActionTests = mutableSetOf<String>()
    private var activeDeviceActionToolId: String? = null
    private var activeRoutePrompt: String? = null
    private var activeExpectedRouteArgs: Map<String, Any?> = emptyMap()
    private var actionSnapshot: DeviceActionSnapshot? = null
    private var pendingExternalAction: PendingExternalAction? = null
    private var report: DiagnosticReport? = null

    private data class DeviceActionSnapshot(
        val volumeIndex: Int? = null,
        val volumeMaximum: Int? = null,
        val brightness: Int? = null,
        val flashlightEnabled: Boolean? = null,
        val settingsState: String? = null,
        val previousNextAlarmMillis: Long? = null,
        val targetAlarmMillis: Long? = null,
        val alarmLabel: String? = null
    )

    private data class PendingExternalAction(
        val toolId: String,
        val step: PlanStep,
        val button: Button,
        val snapshot: DeviceActionSnapshot,
        val beforeState: String,
        var activityPaused: Boolean = false,
        var returnedToTester: Boolean = false,
        var result: ActionResult? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syntheticChatLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (activeDeviceActionToolId != null) handleManualChatRoute(result)
            else continueTesterRun(syntheticChatResults(result), uiChecksForRun)
        }
        buildView()
    }

    private fun buildView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 12)
            setBackgroundColor(Color.WHITE)
        }
        title = TextView(this).apply {
            text = "PocketMind QA tester"
            textSize = 24f
            setTextColor(Color.BLACK)
            contentDescription = "PocketMind all-feature tester"
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "Start runs private synthetic Chat questions, routing checks, and isolated diagnostics only; it does not execute tool actions. Each tool has a separate test button and asks Chat first. Every real runtime call shows the exact action and requires confirmation. Reversible changes require a second confirmation to restore. Alarm tests ask for your time, set a labeled real alarm, check Android's next-alarm read-back, and offer separate confirmed cleanup. Timer tests ask for a duration and start a real timer; Android 11 does not expose reliable timer read-back or a unique cancel handle, so that result stays user-assisted/NOT_RUN."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 10)
        })
        state = TextView(this).apply {
            text = "Ready to test PocketMind features."
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 4)
        }
        root.addView(state)
        progress = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 8)
        }
        root.addView(progress)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        runButton = Button(this).apply {
            text = "Start all tests"
            contentDescription = "Start all PocketMind feature tests"
            setOnClickListener { runTests() }
        }
        buttons.addView(runButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shareButton = Button(this).apply {
            text = "Share report"
            contentDescription = "Share the completed PocketMind QA report"
            isEnabled = false
            setOnClickListener { shareReport() }
        }
        buttons.addView(shareButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        logScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            contentDescription = "Scrollable test log; swipe to review results"
        }
        reportBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 16)
        }
        logScroll.addView(reportBody)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun testerUiChecks(): List<DiagnosticCheck> {
        val wired = title.text == "PocketMind QA tester" &&
            runButton.text == "Start all tests" && runButton.hasOnClickListeners() &&
            shareButton.text == "Share report" && shareButton.hasOnClickListeners() && !shareButton.isEnabled &&
            logScroll.childCount == 1 && reportBody.orientation == LinearLayout.VERTICAL
        return listOf(
            DiagnosticCheck(
                "UI · dedicated Tester screen and buttons",
                if (wired) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                if (wired) "The dedicated QA screen is open; Start all tests initiated this run, Share report is correctly disabled until completion, and the test log is vertically scrollable."
                else "A Tester screen title, button callback/state, or scrollable log is missing. Check DiagnosticsActivity view construction."
            )
        )
    }

    private fun runTests() {
        runButton.isEnabled = false
        shareButton.isEnabled = false
        report = null
        progress.text = "Preparing the isolated test run…"
        resultRows.clear()
        actionButtons.clear()
        runningActionTests.clear()
        completedActionTests.clear()
        activeDeviceActionToolId = null
        activeRoutePrompt = null
        activeExpectedRouteArgs = emptyMap()
        actionSnapshot = null
        pendingExternalAction = null
        reportBody.removeAllViews()
        state.text = "Testing all registered features…"
        uiChecksForRun = testerUiChecks()
        state.text = "Opening Chat to ask the fixed synthetic QA questions; no test turns will be saved…"
        try {
            syntheticChatLauncher.launch(Intent(this, MainActivity::class.java)
                .putExtra(SyntheticChatTestContract.EXTRA_RUN, true))
        } catch (_: Exception) {
            continueTesterRun(SyntheticChatTestResults.notRun(SyntheticFeatureRouteQa.probes.map { it.toolId }), uiChecksForRun)
        }
    }

    private fun syntheticChatResults(result: ActivityResult): SyntheticChatTestResults {
        val tools = SyntheticFeatureRouteQa.probes.map { it.toolId }
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return SyntheticChatTestResults.notRun(tools)
        val ids = data.getStringArrayListExtra(SyntheticChatTestContract.EXTRA_ROUTE_IDS).orEmpty()
        val statuses = data.getStringArrayListExtra(SyntheticChatTestContract.EXTRA_ROUTE_STATUSES).orEmpty()
        val routes = ids.zip(statuses).mapNotNull { (id, value) ->
            runCatching { id to DiagnosticStatus.valueOf(value) }.getOrNull()
        }.toMap()
        val qaStatus = runCatching {
            DiagnosticStatus.valueOf(data.getStringExtra(SyntheticChatTestContract.EXTRA_QA_STATUS) ?: "NOT_RUN")
        }.getOrDefault(DiagnosticStatus.NOT_RUN)
        return SyntheticChatTestResults(
            sessionStarted = data.getBooleanExtra(SyntheticChatTestContract.EXTRA_STARTED, false),
            modelLoaded = data.getBooleanExtra(SyntheticChatTestContract.EXTRA_MODEL_LOADED, false),
            qaStatus = qaStatus,
            routeStatuses = routes,
            completed = data.getBooleanExtra(SyntheticChatTestContract.EXTRA_COMPLETED, false),
            interrupted = data.getBooleanExtra(SyntheticChatTestContract.EXTRA_INTERRUPTED, false),
            actionRoutePlanJson = data.getStringExtra(SyntheticChatTestContract.EXTRA_ACTION_ROUTE_PLAN_JSON)
        )
    }

    private fun continueTesterRun(chatResults: SyntheticChatTestResults, uiChecks: List<DiagnosticCheck>) {
        uiScope.launch {
            try {
                var completed = withContext(Dispatchers.IO) {
                    FeatureDiagnostics.run(
                        this@DiagnosticsActivity,
                        (application as PocketMindApplication).agentController,
                        initialUiChecks = uiChecks,
                        syntheticChatResults = chatResults
                    ) { event ->
                        withContext(Dispatchers.Main.immediate) { showProgress(event) }
                    }
                }
                val swipeCheck = runSwipeSmokeTest()
                completed = completed.copy(checks = completed.checks + swipeCheck)
                report = completed
                addFinishedCard(swipeCheck)
                addDeviceActionTestControls()
                state.text = completed.summary()
                progress.text = "${completed.checks.size} checks completed · ${completed.failures} failed · ${completed.warnings} warnings · ${completed.notRun} intentionally not run"
                shareButton.isEnabled = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.text = "Test run stopped: ${error.message?.replace(Regex("[\\r\\n]+"), " ")?.take(140) ?: "unexpected error"}. Rerun to try again."
                progress.text = ""
            } finally {
                runButton.isEnabled = true
            }
        }
    }

    private fun showProgress(event: DiagnosticProgress) {
        progress.text = "Test ${event.index}/${event.total} · ${event.title}"
        if (event.status == null) {
            val card = createCard(event.title, "Running…", null)
            resultRows[event.index] = card
            reportBody.addView(card.first.tag as View)
        } else {
            val row = resultRows[event.index]
            if (row != null) {
                row.first.text = "${event.status} · ${event.title}"
                row.first.setTextColor(statusColor(event.status))
                row.second.text = event.details
            }
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun createCard(titleText: String, detailText: String, status: DiagnosticStatus?): Pair<TextView, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.rgb(246, 247, 249))
        }
        val heading = TextView(this).apply {
            text = if (status == null) "RUNNING · $titleText" else "$status · $titleText"
            textSize = 15f
            setTextColor(status?.let(::statusColor) ?: Color.DKGRAY)
        }
        val details = TextView(this).apply {
            text = detailText
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 4, 0, 0)
        }
        card.addView(heading)
        card.addView(details)
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8
        }
        heading.tag = card
        return heading to details
    }

    private fun addFinishedCard(check: DiagnosticCheck) {
        val row = createCard(check.title, check.details, check.status)
        row.first.text = "${check.status} · ${check.title}"
        row.second.text = check.details
        reportBody.addView(row.first.tag as View)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addDeviceActionTestControls() {
        val header = TextView(this).apply {
            text = "Optional per-tool real verification"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 18, 0, 4)
        }
        reportBody.addView(header)
        reportBody.addView(TextView(this).apply {
            text = "Each test first asks its synthetic request in transient Chat; alarm/time and timer/duration values come from you, not a guessed default. No prompt or response is saved. The exact tool and arguments are shown before confirmation. Flashlight, volume, brightness, and the alarm are read back; restoration/clearing needs a separate confirmation. Timers start immediately after confirmation but Android 11 offers no reliable timer-state read-back or unique cancel handle, so check Clock > Timer and stop it there if running. Radio/location tests open Android settings without silently toggling them."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 8)
        })
        ToolRegistry().all.forEach { spec ->
            val button = Button(this).apply {
                text = "Test ${spec.id}"
                contentDescription = "Ask Chat to route ${spec.id}, then require confirmation before runtime execution"
                setOnClickListener { startActionTest(spec.id, this) }
            }
            actionButtons[spec.id] = button
            reportBody.addView(button)
        }
    }

    private fun startActionTest(toolId: String, button: Button) {
        if (toolId in completedActionTests || !runningActionTests.add(toolId)) return
        if (activeDeviceActionToolId != null) {
            runningActionTests.remove(toolId)
            state.text = "Finish the current ${activeDeviceActionToolId} test before starting another tool."
            return
        }
        activeDeviceActionToolId = toolId
        button.isEnabled = false
        runButton.isEnabled = false
        shareButton.isEnabled = false
        uiScope.launch {
            try {
                actionSnapshot = withContext(Dispatchers.IO) { captureActionSnapshot(toolId) }
                if (SyntheticFeatureRouteQa.probes.none { it.toolId == toolId }) {
                    updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "No fixed synthetic Chat request is registered for this tool; no action was run.")
                    finishActionTest(toolId, button, completed = false)
                    return@launch
                }
                when (toolId) {
                    "alarm.set" -> showAlarmTimeInput(button)
                    "timer.set" -> showTimerDurationInput(button)
                    else -> launchManualChatProbe(toolId)
                }
            } catch (_: Exception) {
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "The transient Chat route could not be opened; no device action was run.")
                finishActionTest(toolId, button, completed = false)
            }
        }
    }

    private fun showAlarmTimeInput(button: Button) {
        val input = EditText(this).apply {
            hint = "HH:MM (24-hour)"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            setSingleLine(true)
            contentDescription = "User-selected test alarm time in 24-hour HH:MM format"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose a test alarm time")
            .setMessage("Enter the next occurrence you want. It must be at least two hours away. The tester will create a real alarm labeled for cleanup; it will remain scheduled unless you separately confirm removal.")
            .setView(input)
            .setNegativeButton("Cancel") { _, _ ->
                updateManualCheck("alarm.set", DiagnosticStatus.NOT_RUN, "Cancelled before choosing a time; no alarm action or Chat route was run.")
                finishActionTest("alarm.set", button, completed = false)
            }
            .setPositiveButton("Continue to Chat", null)
            .create()
        dialog.setOnCancelListener {
            updateManualCheck("alarm.set", DiagnosticStatus.NOT_RUN, "Time selection was cancelled; no alarm action or Chat route was run.")
            finishActionTest("alarm.set", button, completed = false)
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val match = Regex("^(?:([01]\\d|2[0-3])):([0-5]\\d)$").matchEntire(input.text.toString().trim())
                if (match == null) {
                    input.error = "Enter an exact time such as 18:30"
                    return@setOnClickListener
                }
                val hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                val now = LocalDateTime.now()
                var target = LocalDate.now().atTime(hour, minute)
                if (!target.isAfter(now)) target = target.plusDays(1)
                if (Duration.between(now, target) < Duration.ofHours(2)) {
                    input.error = "The next occurrence is less than two hours away; choose a later time"
                    return@setOnClickListener
                }
                val timestamp = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val existing = actionSnapshot?.previousNextAlarmMillis
                if (existing != null && kotlin.math.abs(existing - timestamp) <= 90_000L) {
                    input.error = "A next alarm already exists at that time; choose a different time"
                    return@setOnClickListener
                }
                val label = TesterAlarmLabel.create()
                val displayTime = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(target)
                val displayDate = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()).format(target)
                actionSnapshot = (actionSnapshot ?: DeviceActionSnapshot()).copy(
                    targetAlarmMillis = timestamp,
                    alarmLabel = label
                )
                activeExpectedRouteArgs = mapOf("hour" to hour, "minute" to minute)
                activeRoutePrompt = "Set alarm for $displayTime labeled $label"
                dialog.dismiss()
                launchManualChatProbe("alarm.set", activeRoutePrompt)
                state.text = "Chat is routing the alarm request for $displayDate $displayTime; no alarm has been set yet."
            }
        }
        dialog.show()
    }

    private fun showTimerDurationInput(button: Button) {
        val input = EditText(this).apply {
            hint = "Minutes (1–1440)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            contentDescription = "User-selected real test timer duration in minutes"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose a real test timer duration")
            .setMessage("Enter a duration in minutes. After the later action confirmation, Android will start this timer immediately and it may ring. PocketMind cannot read its running state or safely cancel it; you may need to stop it in Clock > Timer.")
            .setView(input)
            .setNegativeButton("Cancel") { _, _ ->
                updateManualCheck("timer.set", DiagnosticStatus.NOT_RUN, "Cancelled before choosing a duration; no timer action or Chat route was run.")
                finishActionTest("timer.set", button, completed = false)
            }
            .setPositiveButton("Continue to Chat", null)
            .create()
        dialog.setOnCancelListener {
            updateManualCheck("timer.set", DiagnosticStatus.NOT_RUN, "Duration selection was cancelled; no timer action or Chat route was run.")
            finishActionTest("timer.set", button, completed = false)
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = input.text.toString().trim().toIntOrNull()
                if (minutes == null || minutes !in 1..1440) {
                    input.error = "Enter a whole duration from 1 to 1440 minutes"
                    return@setOnClickListener
                }
                val seconds = minutes * 60
                activeExpectedRouteArgs = mapOf("seconds" to seconds)
                activeRoutePrompt = "Set a timer for $minutes minutes"
                dialog.dismiss()
                launchManualChatProbe("timer.set", activeRoutePrompt)
                state.text = "Chat is routing the $minutes-minute timer request; no timer has started yet."
            }
        }
        dialog.show()
    }

    private fun launchManualChatProbe(toolId: String, prompt: String? = null) {
        state.text = "Opening transient Chat to ask the ${toolId} request; no real action will run yet."
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(SyntheticChatTestContract.EXTRA_RUN, true)
            .putExtra(SyntheticChatTestContract.EXTRA_ACTION_ROUTE_TOOL, toolId)
        prompt?.let { intent.putExtra(SyntheticChatTestContract.EXTRA_ACTION_ROUTE_PROMPT, it) }
        syntheticChatLauncher.launch(intent)
    }

    private fun captureActionSnapshot(toolId: String): DeviceActionSnapshot = when (toolId) {
        "volume" -> runCatching {
            val (index, maximum) = DeviceStateReader.mediaVolume(this)
            DeviceActionSnapshot(volumeIndex = index, volumeMaximum = maximum)
        }.getOrDefault(DeviceActionSnapshot())
        "brightness" -> DeviceActionSnapshot(brightness = DeviceStateReader.brightness(this))
        "flashlight" -> DeviceActionSnapshot(flashlightEnabled = DeviceStateReader.flashlightEnabled(this))
        "alarm.set" -> DeviceActionSnapshot(previousNextAlarmMillis = DeviceStateReader.nextAlarmClockTriggerMillis(this))
        "wifi.settings", "bluetooth.settings", "airplane.settings", "location.settings" ->
            DeviceActionSnapshot(settingsState = DeviceStateReader.settingsState(this, toolId))
        else -> DeviceActionSnapshot()
    }

    private fun handleManualChatRoute(activityResult: ActivityResult) {
        val toolId = activeDeviceActionToolId ?: return
        val button = actionButtons[toolId] ?: return
        if (activityResult.resultCode != RESULT_OK || activityResult.data == null) {
            updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "The synthetic Chat route was cancelled or did not return; no real tool was executed.")
            finishActionTest(toolId, button, completed = false)
            return
        }
        val chat = syntheticChatResults(activityResult)
        val routeStatus = chat.routeStatuses[toolId] ?: DiagnosticStatus.NOT_RUN
        if (!chat.completed || routeStatus != DiagnosticStatus.PASS) {
            val reason = when {
                routeStatus == DiagnosticStatus.FAIL -> "The fixed request shown in Chat did not produce the expected validated tool plan; no real action was run."
                !chat.sessionStarted -> "The synthetic Chat route did not start or return; no real action was run."
                !chat.modelLoaded && routeStatus == DiagnosticStatus.NOT_RUN -> "No local model was loaded for this planner route; no real action was run."
                chat.interrupted -> "The synthetic Chat route was interrupted; it was not repeated and no real action was run."
                else -> "The Chat route did not finish with a validated plan; no real action was run."
            }
            updateManualCheck(toolId, routeStatus, reason)
            finishActionTest(toolId, button, completed = false)
            return
        }
        var step = runCatching { PlannerJson.parse(chat.actionRoutePlanJson.orEmpty()).singleOrNull() }.getOrNull()
        val validation = step?.let(ToolValidator()::validate)
        if (step == null || step.tool != toolId || validation != null) {
            updateManualCheck(toolId, DiagnosticStatus.FAIL, "Chat returned no single validated plan for this tool; the tester rejected it and ran nothing.")
            finishActionTest(toolId, button, completed = false)
            return
        }
        if (activeExpectedRouteArgs.isNotEmpty() && !matchesChosenActionArguments(step)) {
            updateManualCheck(toolId, DiagnosticStatus.FAIL, "The Chat route did not preserve the exact time/duration you entered; the plan was rejected and no action was run.")
            finishActionTest(toolId, button, completed = false)
            return
        }
        if (toolId == "alarm.set") {
            val label = actionSnapshot?.alarmLabel
            if (label == null || actionSnapshot?.targetAlarmMillis == null) {
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "No user-selected alarm time and unique label are available; no alarm was set.")
                finishActionTest(toolId, button, completed = false)
                return
            }
            step = step.copy(args = step.args + ("label" to label))
            if (ToolValidator().validate(step) != null) {
                updateManualCheck(toolId, DiagnosticStatus.FAIL, "The exact labeled alarm plan did not pass schema validation; no action was run.")
                finishActionTest(toolId, button, completed = false)
                return
            }
        }
        val snapshot = actionSnapshot ?: DeviceActionSnapshot()
        when (toolId) {
            "volume" -> {
                val index = snapshot.volumeIndex
                val maximum = snapshot.volumeMaximum
                if (index == null || maximum == null || maximum <= 0) {
                    updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Android did not provide a usable media-volume range; no volume change was attempted.")
                    finishActionTest(toolId, button, completed = false)
                    return
                }
                if ((step.args["step"] as? Number)?.toInt() != 1) {
                    updateManualCheck(toolId, DiagnosticStatus.FAIL, "Chat did not return the exact one-step volume increase fixture; nothing was run.")
                    finishActionTest(toolId, button, completed = false)
                    return
                }
                if (index >= maximum) {
                    updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Media volume is already at its maximum; the routed one-step increase could not change state, so it was not run.")
                    finishActionTest(toolId, button, completed = false)
                    return
                }
            }
            "alarm.set" -> if (snapshot.targetAlarmMillis == null || snapshot.alarmLabel == null) {
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "A user-selected alarm time is missing; no alarm was set.")
                finishActionTest(toolId, button, completed = false)
                return
            }
            "timer.set" -> if (step.args["seconds"] !is Number || (step.args["seconds"] as Number).toInt() !in 60..86_400) {
                updateManualCheck(toolId, DiagnosticStatus.FAIL, "The Chat route did not return a valid user-selected timer duration; no timer was started.")
                finishActionTest(toolId, button, completed = false)
                return
            }
            "brightness" -> if (snapshot.brightness == null || !DeviceStateReader.brightnessWriteGranted(this)) {
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Android 11 requires the user to grant WRITE_SETTINGS special access before PocketMind can change or read brightness. Access is not granted, so no setting screen was opened and brightness was not changed. Grant it in Android Settings, then rerun.")
                finishActionTest(toolId, button, completed = false)
                return
            }
            "flashlight" -> if (snapshot.flashlightEnabled == null) {
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "No flash or no authoritative CameraManager torch-state callback was available; the flashlight was not changed.")
                finishActionTest(toolId, button, completed = false)
                return
            }
            "wifi.settings", "bluetooth.settings", "airplane.settings", "location.settings" -> if (snapshot.settingsState.isNullOrBlank() || snapshot.settingsState.contains("unavailable", true)) {
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Android did not expose the current setting state for before/after verification (${snapshot.settingsState ?: "no read-back"}); no settings screen was opened.")
                finishActionTest(toolId, button, completed = false)
                return
            }
        }
        showActionConfirmation(toolId, step, button, snapshot)
    }

    private fun matchesChosenActionArguments(step: PlanStep): Boolean = activeExpectedRouteArgs.all { (key, expected) ->
        val actual = step.args[key] ?: return@all false
        if (expected is Number && actual is Number) expected.toDouble() == actual.toDouble() else actual == expected
    }

    private fun actionPreview(toolId: String, step: PlanStep, snapshot: DeviceActionSnapshot): String {
        val action = when (toolId) {
            "clock.time" -> "Read the current local time; no setting or saved data will change."
            "clock.date" -> "Read today's local date; no setting or saved data will change."
            "math.calculate" -> "Evaluate the synthetic arithmetic expression in an isolated tool call."
            "note.save" -> "Save only the synthetic note to a disposable QA database, read it back, then delete that database."
            "note.list" -> "List notes from an empty disposable QA database; your saved notes will not be opened."
            "note.search" -> "Search an empty disposable QA database; your saved notes will not be opened."
            "alarm.set" -> "Set a real one-time Clock alarm for ${formatAlarmTarget(snapshot.targetAlarmMillis)} with label '${snapshot.alarmLabel}'. It may ring and remains scheduled until dismissed. Android AlarmManager next-alarm read-back will be checked after return. Clearing is a separate confirmation; if verification fails, remove this exact label in Clock > Alarms."
            "timer.set" -> "Start a real ${((step.args["seconds"] as Number).toInt() / 60)}-minute Clock timer now. It may ring. Android 11 gives PocketMind no reliable timer-running read-back or unique cancel handle, so this result will remain user-assisted/NOT_RUN; check Clock > Timer and tap Stop/Cancel if it is running."
            "battery/status" -> "Read battery, available RAM, and storage values; no runtime gate, stop, or unload is applied."
            "app.search" -> "Search launcher entries for the synthetic query; no app will be launched and app names are not copied to the report."
            "flashlight" -> "Turn the phone flashlight on. Before state: ${snapshot.flashlightEnabled}. The state will be read back; if it changes, restoring it requires a separate confirmation."
            "volume" -> "Raise media volume one step from ${snapshot.volumeIndex}/${snapshot.volumeMaximum} to ${(snapshot.volumeIndex ?: 0) + 1}/${snapshot.volumeMaximum}. The result will be read back; restoring the original index requires a separate confirmation."
            "brightness" -> "Set screen brightness to ${step.args["level"]} of 255 (before: ${snapshot.brightness}). The value will be read back; restoring the original value requires a separate confirmation."
            "app.open" -> "Open the exact package ${step.args["package"]} (Android Settings). This leaves the tester until you return; only the launch handoff can be checked."
            "wifi.settings" -> "Open Android Wi-Fi settings. PocketMind will not toggle Wi-Fi; its Boolean state is recorded before and after you return."
            "bluetooth.settings" -> "Open Android Bluetooth settings. PocketMind will not toggle Bluetooth; its Boolean state is recorded before and after you return."
            "airplane.settings" -> "Open Android airplane-mode settings. PocketMind will not toggle airplane mode; its Boolean state is recorded before and after you return."
            "location.settings" -> "Open Android location settings. PocketMind will not toggle location; its Boolean state is recorded before and after you return."
            else -> "Run this validated synthetic tool plan after confirmation."
        }
        val args = step.args.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=$value" }
        val before = when (toolId) {
            "volume" -> "${snapshot.volumeIndex}/${snapshot.volumeMaximum}"
            "brightness" -> snapshot.brightness?.toString() ?: "unavailable"
            "flashlight" -> snapshot.flashlightEnabled?.toString() ?: "unavailable"
            "alarm.set" -> if (snapshot.previousNextAlarmMillis == null) "Android reported no existing next alarm" else "Android reported an existing next alarm (time omitted)"
            "wifi.settings", "bluetooth.settings", "airplane.settings", "location.settings" -> snapshot.settingsState ?: "unavailable"
            else -> "read-only / no persistent state"
        }
        return "$action\n\nExact validated Chat plan: ${step.tool} $args\nCaptured before: $before\n\nConfirm runs only this plan. Skip or Back runs nothing."
    }

    private fun formatAlarmTarget(timestamp: Long?): String = timestamp?.let {
        DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a z", Locale.getDefault())
            .withZone(ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(it))
    } ?: "unavailable"

    private fun showActionConfirmation(toolId: String, step: PlanStep, button: Button, snapshot: DeviceActionSnapshot) {
        var handled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Confirm real tool test?")
            .setMessage(actionPreview(toolId, step, snapshot))
            .setNegativeButton("Skip — do nothing") { _, _ ->
                handled = true
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Skipped at the exact-plan confirmation; no real tool call or device change was made.")
                finishActionTest(toolId, button, completed = false)
            }
            .setPositiveButton("Confirm exact plan") { _, _ ->
                handled = true
                executeConfirmedAction(toolId, step, button, snapshot)
            }
            .create()
        dialog.setOnCancelListener {
            if (!handled) {
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "The exact-plan confirmation was cancelled; no real tool call or device change was made.")
                finishActionTest(toolId, button, completed = false)
            }
        }
        dialog.show()
    }

    private fun changedSincePreview(toolId: String, snapshot: DeviceActionSnapshot): String? = when (toolId) {
        "volume" -> if (DeviceStateReader.mediaVolume(this).first != snapshot.volumeIndex) "Media volume changed while Chat or the preview was open; it was not overwritten. Rerun to capture a fresh value." else null
        "brightness" -> if (!DeviceStateReader.brightnessWriteGranted(this) || DeviceStateReader.brightness(this) != snapshot.brightness) "Brightness access or value changed while the preview was open; nothing was written. Grant access or recapture the value, then rerun." else null
        "flashlight" -> if (DeviceStateReader.flashlightEnabled(this) != snapshot.flashlightEnabled) "Flashlight state changed while Chat or the preview was open; it was not overwritten. Rerun to capture a fresh value." else null
        "alarm.set" -> alarmPreviewChangeReason(snapshot)
        "wifi.settings", "bluetooth.settings", "airplane.settings", "location.settings" -> if (DeviceStateReader.settingsState(this, toolId) != snapshot.settingsState) "The system state changed while Chat or the preview was open; no settings screen was opened. Rerun to capture a fresh value." else null
        else -> null
    }

    private fun alarmPreviewChangeReason(snapshot: DeviceActionSnapshot): String? {
        val targetMillis = snapshot.targetAlarmMillis
            ?: return "No user-selected alarm time is available; no alarm was set."
        val target = java.time.Instant.ofEpochMilli(targetMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        if (Duration.between(LocalDateTime.now(), target) < Duration.ofHours(2)) {
            return "The selected alarm time is now less than two hours away; no alarm was set. Rerun and choose a later time."
        }
        if (DeviceStateReader.nextAlarmClockTriggerMillis(this) != snapshot.previousNextAlarmMillis) {
            return "The system's next-alarm state changed while Chat or the preview was open; no new alarm was set. Rerun to capture a fresh state."
        }
        return null
    }

    private fun executeConfirmedAction(toolId: String, step: PlanStep, button: Button, snapshot: DeviceActionSnapshot) {
        changedSincePreview(toolId, snapshot)?.let { reason ->
            updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, reason)
            finishActionTest(toolId, button, completed = false)
            return
        }
        val isExternal = toolId == "app.open" || toolId.endsWith(".settings") || toolId == "alarm.set" || toolId == "timer.set"
        if (isExternal) pendingExternalAction = PendingExternalAction(toolId, step, button, snapshot, snapshot.settingsState ?: "not applicable")
        uiScope.launch {
            try {
                state.text = "Running the explicitly confirmed ${toolId} tool test…"
                val controller = (application as PocketMindApplication).agentController
                val result = withContext(Dispatchers.IO) { controller.runConfirmedDiagnosticAction(step, userApproved = true) }
                if (isExternal) {
                    pendingExternalAction?.let { pending ->
                        if (pending.toolId == toolId) {
                            pending.result = result
                            if (pending.returnedToTester) completeExternalAction(pending)
                            else window.decorView.postDelayed({
                                if (pendingExternalAction === pending && !pending.activityPaused) {
                                    when (toolId) {
                                        "alarm.set" -> completeAlarmSet(pending)
                                        "timer.set" -> completeTimerAction(pending, returnedToTester = false)
                                        else -> {
                                            pendingExternalAction = null
                                            updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Android returned ${result.status.name}, but the Tester never observed an external app/screen open. No state change is claimed.")
                                            finishActionTest(toolId, button, completed = false)
                                        }
                                    }
                                }
                            }, 900L)
                        }
                    }
                } else handleConfirmedResult(toolId, step, button, snapshot, result)
            } catch (_: Exception) {
                pendingExternalAction = null
                if (toolId == "alarm.set") {
                    updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "The confirmed Clock alarm request did not return. It may still have been created; open Clock > Alarms and remove any alarm labeled '${snapshot.alarmLabel}'. Do not retry until you check.")
                } else if (toolId == "timer.set") {
                    updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "The confirmed Clock timer request did not return. It may still be running; open Clock > Timer and tap Stop/Cancel if it is. Do not retry until you check.")
                } else {
                    updateManualCheck(toolId, DiagnosticStatus.FAIL, "The explicitly confirmed tool call did not return a verifiable result. Check the device state before retrying.")
                }
                finishActionTest(toolId, button, completed = toolId == "alarm.set" || toolId == "timer.set")
            }
        }
    }

    private fun handleConfirmedResult(toolId: String, step: PlanStep, button: Button, snapshot: DeviceActionSnapshot, result: ActionResult) {
        when (toolId) {
            "volume" -> handleVolumeResult(button, snapshot, result)
            "brightness" -> handleBrightnessResult(button, step, snapshot, result)
            "flashlight" -> handleFlashlightResult(button, snapshot, result)
            else -> {
                val (status, details) = when (result.status) {
                    ActionStatus.VERIFIED -> DiagnosticStatus.PASS to when (toolId) {
                        "note.save" -> "The synthetic note was written and read back in a disposable database, then that database was deleted. User notes were not read or changed."
                        "note.list", "note.search" -> "The real note tool ran against an empty disposable database; user notes were not read or changed."
                        "app.search" -> "The real launcher search completed without opening an app; returned app names were not placed in the report."
                        "battery/status" -> "The real read-only battery/RAM/storage tool returned values; no memory gate, generation stop, or model unload was applied."
                        else -> "The explicitly confirmed ${toolId} runtime returned a verified result. Synthetic outputs were not copied into the report."
                    }
                    ActionStatus.FAILED -> DiagnosticStatus.FAIL to "The explicitly confirmed ${toolId} runtime failed: ${result.message.take(160)}"
                    ActionStatus.HANDED_OFF -> DiagnosticStatus.NOT_RUN to "The tool handed control to Android without a read-back; no completion is claimed."
                    else -> DiagnosticStatus.NOT_RUN to "The tool did not run (${result.status}); no device action is claimed."
                }
                updateManualCheck(toolId, status, details)
                finishActionTest(toolId, button, completed = status == DiagnosticStatus.PASS || status == DiagnosticStatus.FAIL)
            }
        }
    }

    private fun handleVolumeResult(button: Button, snapshot: DeviceActionSnapshot, result: ActionResult) {
        val original = snapshot.volumeIndex ?: run {
            updateManualCheck("volume", DiagnosticStatus.NOT_RUN, "The original media-volume level was unavailable; no restore is claimed.")
            finishActionTest("volume", button, completed = false)
            return
        }
        val maximum = snapshot.volumeMaximum ?: 0
        val observed = runCatching { DeviceStateReader.mediaVolume(this).first }.getOrNull()
        val expected = (original + 1).coerceAtMost(maximum)
        val verified = result.status == ActionStatus.VERIFIED && observed == expected && observed > original
        if (observed != null && observed != original) showVolumeRestoreConfirmation(button, original, maximum, observed, verified)
        else {
            val status = if (verified) DiagnosticStatus.PASS else if (result.status == ActionStatus.FAILED) DiagnosticStatus.FAIL else DiagnosticStatus.WARN
            updateManualCheck("volume", status, if (verified) "Media volume changed from $original/$maximum to $observed/$maximum and matched read-back." else "Expected one-step increase to $expected/$maximum was not observed (read-back: ${observed ?: "unavailable"}/$maximum); the value did not change, so no restoration was needed.")
            finishActionTest("volume", button, completed = status != DiagnosticStatus.WARN)
        }
    }

    private fun showVolumeRestoreConfirmation(button: Button, original: Int, maximum: Int, observed: Int, raisedAndVerified: Boolean) {
        var handled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Restore original media volume?")
            .setMessage("Before: $original/$maximum. After read-back: $observed/$maximum. Restoring is a separate device change. Confirm to restore exactly; otherwise the current level is left as-is.")
            .setNegativeButton("Leave current level") { _, _ ->
                handled = true
                finishVolumeTest(button, original, maximum, observed, raisedAndVerified, restored = false)
            }
            .setPositiveButton("Confirm restore") { _, _ ->
                handled = true
                restoreOriginalVolume(button, original, maximum, observed, raisedAndVerified)
            }
            .create()
        dialog.setOnCancelListener { if (!handled) finishVolumeTest(button, original, maximum, observed, raisedAndVerified, restored = false) }
        dialog.show()
    }

    private fun restoreOriginalVolume(button: Button, original: Int, maximum: Int, observed: Int, raisedAndVerified: Boolean) {
        uiScope.launch {
            try {
                if (DeviceStateReader.mediaVolume(this@DiagnosticsActivity).first != observed) {
                    finishVolumeTest(button, original, maximum, observed, raisedAndVerified, restored = false, externalChange = true)
                    return@launch
                }
                val controller = (application as PocketMindApplication).agentController
                val result = withContext(Dispatchers.IO) { controller.restoreDiagnosticVolumeIndex(original, userApproved = true) }
                val actual = DeviceStateReader.mediaVolume(this@DiagnosticsActivity).first
                finishVolumeTest(button, original, maximum, observed, raisedAndVerified, restored = true, restoreVerified = result.status == ActionStatus.VERIFIED && actual == original, externalChange = actual != original && result.status != ActionStatus.FAILED)
            } catch (_: Exception) {
                finishVolumeTest(button, original, maximum, observed, raisedAndVerified, restored = true, restoreVerified = false)
            }
        }
    }

    private fun finishVolumeTest(button: Button, original: Int, maximum: Int, observed: Int, raisedAndVerified: Boolean, restored: Boolean, restoreVerified: Boolean = false, externalChange: Boolean = false) {
        val finalValue = runCatching { DeviceStateReader.mediaVolume(this).first }.getOrNull()
        val (status, details) = when {
            externalChange -> DiagnosticStatus.WARN to "Media volume changed during restoration; PocketMind did not overwrite the new value. Before=$original/$maximum, action read-back=$observed/$maximum, final read-back=${finalValue ?: "unavailable"}/$maximum."
            raisedAndVerified && restored && restoreVerified -> DiagnosticStatus.PASS to "Media volume before=$original/$maximum, after=$observed/$maximum, restored=$finalValue/$maximum; both action and separate restore were read back and confirmed."
            raisedAndVerified && restored -> DiagnosticStatus.WARN to "Increase was read back ($observed/$maximum), but original level $original/$maximum could not be verified after the separate restore. Check the current level."
            raisedAndVerified -> DiagnosticStatus.WARN to "Increase was read back ($observed/$maximum), but restore was skipped; current level is ${finalValue ?: "unavailable"}/$maximum."
            restored && restoreVerified -> DiagnosticStatus.FAIL to "Expected one-step increase was not verified; original volume $original/$maximum was separately restored and read back."
            else -> DiagnosticStatus.WARN to "The expected increase was not verified. Before=$original/$maximum, after=$observed/$maximum, final=${finalValue ?: "unavailable"}/$maximum; no restore success is claimed."
        }
        updateManualCheck("volume", status, details)
        finishActionTest("volume", button, completed = true)
    }

    private fun handleBrightnessResult(button: Button, step: PlanStep, snapshot: DeviceActionSnapshot, result: ActionResult) {
        val original = snapshot.brightness ?: run {
            updateManualCheck("brightness", DiagnosticStatus.NOT_RUN, "The original brightness value or WRITE_SETTINGS access was unavailable; no restoration is claimed.")
            finishActionTest("brightness", button, completed = false)
            return
        }
        val target = (step.args["level"] as Number).toInt()
        val observed = DeviceStateReader.brightness(this)
        val verified = result.status == ActionStatus.VERIFIED && observed == target
        if (observed != null && observed != original) showBrightnessRestoreConfirmation(button, original, observed, verified)
        else {
            val status = if (verified) DiagnosticStatus.PASS else if (result.status == ActionStatus.FAILED) DiagnosticStatus.FAIL else DiagnosticStatus.WARN
            updateManualCheck("brightness", status, "Brightness before=$original, requested=$target, read-back=${observed ?: "unavailable"}; no restoration was necessary because the value did not change, or no restoration is claimed.")
            finishActionTest("brightness", button, completed = status != DiagnosticStatus.WARN)
        }
    }

    private fun showBrightnessRestoreConfirmation(button: Button, original: Int, observed: Int, verified: Boolean) {
        var handled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Restore original brightness?")
            .setMessage("Before: $original/255. After read-back: $observed/255. Restoring is a separate display change. Confirm to restore exactly; otherwise brightness remains at its current value.")
            .setNegativeButton("Leave current brightness") { _, _ ->
                handled = true
                finishBrightnessTest(button, original, observed, verified, restored = false)
            }
            .setPositiveButton("Confirm restore") { _, _ ->
                handled = true
                restoreBrightness(button, original, observed, verified)
            }
            .create()
        dialog.setOnCancelListener { if (!handled) finishBrightnessTest(button, original, observed, verified, restored = false) }
        dialog.show()
    }

    private fun restoreBrightness(button: Button, original: Int, observed: Int, verified: Boolean) {
        uiScope.launch {
            if (!DeviceStateReader.brightnessWriteGranted(this@DiagnosticsActivity) || DeviceStateReader.brightness(this@DiagnosticsActivity) != observed) {
                finishBrightnessTest(button, original, observed, verified, restored = false, externalChange = true)
                return@launch
            }
            try {
                val controller = (application as PocketMindApplication).agentController
                val result = withContext(Dispatchers.IO) {
                    controller.runConfirmedDiagnosticAction(PlanStep("brightness", mapOf("level" to original)), userApproved = true)
                }
                val actual = DeviceStateReader.brightness(this@DiagnosticsActivity)
                finishBrightnessTest(button, original, observed, verified, restored = true, restoreVerified = result.status == ActionStatus.VERIFIED && actual == original)
            } catch (_: Exception) {
                finishBrightnessTest(button, original, observed, verified, restored = true, restoreVerified = false)
            }
        }
    }

    private fun finishBrightnessTest(button: Button, original: Int, observed: Int, verified: Boolean, restored: Boolean, restoreVerified: Boolean = false, externalChange: Boolean = false) {
        val finalValue = DeviceStateReader.brightness(this)
        val (status, details) = when {
            externalChange -> DiagnosticStatus.WARN to "Brightness changed externally or WRITE_SETTINGS access was revoked; PocketMind did not overwrite it. Before=$original, action read-back=$observed, final=${finalValue ?: "unavailable"}."
            verified && restored && restoreVerified -> DiagnosticStatus.PASS to "Brightness before=$original/255, after=$observed/255, restored=${finalValue ?: "unavailable"}/255; both writes were read back after separate confirmation."
            verified && restored -> DiagnosticStatus.WARN to "Brightness change was read back ($observed/255), but restoration to $original/255 was not verified. Check the display setting."
            verified -> DiagnosticStatus.WARN to "Brightness change was read back ($observed/255), but restore was skipped; current value is ${finalValue ?: "unavailable"}/255."
            restored && restoreVerified -> DiagnosticStatus.FAIL to "The requested brightness change did not verify; the original value $original/255 was separately restored and read back."
            else -> DiagnosticStatus.WARN to "Brightness change was not verified. Before=$original, after=$observed, final=${finalValue ?: "unavailable"}; no restore success is claimed."
        }
        updateManualCheck("brightness", status, details)
        finishActionTest("brightness", button, completed = true)
    }

    private fun handleFlashlightResult(button: Button, snapshot: DeviceActionSnapshot, result: ActionResult) {
        val original = snapshot.flashlightEnabled ?: run {
            updateManualCheck("flashlight", DiagnosticStatus.NOT_RUN, "The initial torch state was not observable; no restoration is claimed.")
            finishActionTest("flashlight", button, completed = false)
            return
        }
        val observed = DeviceStateReader.flashlightEnabled(this)
        val target = true
        val verified = result.status == ActionStatus.VERIFIED && observed == target
        if (observed != null && observed != original) showFlashlightRestoreConfirmation(button, original, observed, verified)
        else {
            val status = if (verified) DiagnosticStatus.PASS else if (result.status == ActionStatus.FAILED) DiagnosticStatus.FAIL else DiagnosticStatus.WARN
            updateManualCheck("flashlight", status, "Flashlight before=$original, requested=$target, torch-callback read-back=${observed ?: "unavailable"}; it was already at the original state or no change is claimed.")
            finishActionTest("flashlight", button, completed = status != DiagnosticStatus.WARN)
        }
    }

    private fun showFlashlightRestoreConfirmation(button: Button, original: Boolean, observed: Boolean, verified: Boolean) {
        var handled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Restore original flashlight state?")
            .setMessage("Before: ${if (original) "on" else "off"}. After callback read-back: ${if (observed) "on" else "off"}. Confirm to set it back; otherwise the current light state remains.")
            .setNegativeButton("Leave current state") { _, _ ->
                handled = true
                finishFlashlightTest(button, original, observed, verified, restored = false)
            }
            .setPositiveButton("Confirm restore") { _, _ ->
                handled = true
                restoreFlashlight(button, original, observed, verified)
            }
            .create()
        dialog.setOnCancelListener { if (!handled) finishFlashlightTest(button, original, observed, verified, restored = false) }
        dialog.show()
    }

    private fun restoreFlashlight(button: Button, original: Boolean, observed: Boolean, verified: Boolean) {
        uiScope.launch {
            if (DeviceStateReader.flashlightEnabled(this@DiagnosticsActivity) != observed) {
                finishFlashlightTest(button, original, observed, verified, restored = false, externalChange = true)
                return@launch
            }
            try {
                val controller = (application as PocketMindApplication).agentController
                val result = withContext(Dispatchers.IO) {
                    controller.runConfirmedDiagnosticAction(PlanStep("flashlight", mapOf("enabled" to original)), userApproved = true)
                }
                val actual = DeviceStateReader.flashlightEnabled(this@DiagnosticsActivity)
                finishFlashlightTest(button, original, observed, verified, restored = true, restoreVerified = result.status == ActionStatus.VERIFIED && actual == original)
            } catch (_: Exception) {
                finishFlashlightTest(button, original, observed, verified, restored = true, restoreVerified = false)
            }
        }
    }

    private fun finishFlashlightTest(button: Button, original: Boolean, observed: Boolean, verified: Boolean, restored: Boolean, restoreVerified: Boolean = false, externalChange: Boolean = false) {
        val finalValue = DeviceStateReader.flashlightEnabled(this)
        val (status, details) = when {
            externalChange -> DiagnosticStatus.WARN to "Torch state changed externally; PocketMind did not overwrite it. Before=$original, action callback=$observed, final=${finalValue ?: "unavailable"}."
            verified && restored && restoreVerified -> DiagnosticStatus.PASS to "Flashlight before=$original, after=$observed, restored=$finalValue; the tool callback and separate restore callback were verified."
            verified && restored -> DiagnosticStatus.WARN to "Flashlight request was read back ($observed), but restoration to $original was not verified. Check the light state."
            verified -> DiagnosticStatus.WARN to "Flashlight request was read back ($observed), but restore was skipped; current state is ${finalValue ?: "unavailable"}."
            restored && restoreVerified -> DiagnosticStatus.FAIL to "The requested flashlight state did not verify; the original state $original was separately restored and read back."
            else -> DiagnosticStatus.WARN to "Flashlight state was not verified. Before=$original, after=$observed, final=${finalValue ?: "unavailable"}; no restore success is claimed."
        }
        updateManualCheck("flashlight", status, details)
        finishActionTest("flashlight", button, completed = true)
    }

    private fun completeExternalActionIfReady() {
        val pending = pendingExternalAction ?: return
        if (pending.activityPaused) pending.returnedToTester = true
        if (pending.returnedToTester && pending.result != null) completeExternalAction(pending)
    }

    private fun completeExternalAction(pending: PendingExternalAction) {
        if (pendingExternalAction !== pending) return
        pendingExternalAction = null
        val result = pending.result ?: return
        val toolId = pending.toolId
        val button = pending.button
        when (toolId) {
            "alarm.set" -> completeAlarmSet(pending)
            "timer.set" -> completeTimerAction(pending, returnedToTester = true)
            "alarm.dismiss" -> completeAlarmDismissal(pending)
            else -> completeOrdinaryExternalAction(pending, result)
        }
    }

    private fun completeOrdinaryExternalAction(pending: PendingExternalAction, result: ActionResult) {
        val toolId = pending.toolId
        val button = pending.button
        if (result.status == ActionStatus.HANDED_OFF && pending.returnedToTester) {
            val after = if (toolId.endsWith(".settings")) DeviceStateReader.settingsState(this, toolId) else "tester resumed after opening ${pending.step.args["package"]}"
            val details = if (toolId.endsWith(".settings")) {
                "Opened the Android ${toolId.removeSuffix(".settings")} settings screen and returned. State before=${pending.beforeState}; after=$after. PocketMind did not toggle or automatically restore anything; any change while the panel was open was user-controlled."
            } else "The confirmed app.open request for ${pending.step.args["package"]} handed off and Tester resumed. Launch handoff was verified; the target app's internal behavior was not tested."
            updateManualCheck(toolId, DiagnosticStatus.PASS, details)
            finishActionTest(toolId, button, completed = true)
        } else {
            val status = if (result.status == ActionStatus.FAILED) DiagnosticStatus.FAIL else DiagnosticStatus.NOT_RUN
            updateManualCheck(toolId, status, "Android did not provide a verified external-screen return (tool status ${result.status}); no change or app behavior is claimed.")
            finishActionTest(toolId, button, completed = status == DiagnosticStatus.FAIL)
        }
    }

    private fun completeAlarmSet(pending: PendingExternalAction) {
        if (pendingExternalAction === pending) pendingExternalAction = null
        val result = pending.result ?: return
        val snapshot = pending.snapshot
        val expected = snapshot.targetAlarmMillis
        val observed = DeviceStateReader.nextAlarmClockTriggerMillis(this)
        val matches = expected != null && observed != null && kotlin.math.abs(observed - expected) <= 90_000L && observed != snapshot.previousNextAlarmMillis
        val noClockHandler = result.status == ActionStatus.FAILED && result.message.contains("No Clock app", true)
        if (!noClockHandler && matches) {
            showAlarmCleanupConfirmation(pending.button, snapshot, observed!!)
            return
        }
        val status = if (noClockHandler) DiagnosticStatus.FAIL else DiagnosticStatus.NOT_RUN
        val details = if (status == DiagnosticStatus.FAIL) {
            "The confirmed request could not find a Clock handler, so Android did not receive the alarm. No alarm state is claimed."
        } else {
            "A real alarm request was sent after confirmation, but AlarmManager did not expose the selected time as a new next-alarm timestamp (status=${result.status}, read-back=${observed?.let(::formatAlarmTarget) ?: "none"}). This remains NOT_RUN; the alarm may still exist behind an earlier alarm. AlarmManager does not expose the label or ownership. Check Clock > Alarms for the requested label '${snapshot.alarmLabel}' and delete it if present. PocketMind did not claim cleanup."
        }
        updateManualCheck("alarm.set", status, details)
        finishActionTest("alarm.set", pending.button, completed = true)
    }

    private fun showAlarmCleanupConfirmation(button: Button, snapshot: DeviceActionSnapshot, observed: Long) {
        var handled = false
        val label = snapshot.alarmLabel ?: "unknown test label"
        val message = "AlarmManager's next-alarm timestamp matches ${formatAlarmTarget(observed)} after the labeled request. Android exposes the trigger time, not the alarm's label or ownership. The request included the unique label '$label'; the alarm remains scheduled and may ring. Clear by that label now? This is a separate action; if Android cannot verify the prior timestamp was restored, check Clock > Alarms and delete '$label' manually."
        val dialog = AlertDialog.Builder(this)
            .setTitle("Alarm set and read back")
            .setMessage(message)
            .setNegativeButton("Leave it scheduled") { _, _ ->
                handled = true
                updateManualCheck("alarm.set", DiagnosticStatus.WARN, "PASS for timestamp read-back: AlarmManager reports the next alarm at ${formatAlarmTarget(observed)} after the request with label '$label'. The API does not expose label/ownership. Cleanup was not approved; the alarm remains scheduled and may ring. To remove it, open Clock > Alarms and delete '$label'.")
                finishActionTest("alarm.set", button, completed = true)
            }
            .setPositiveButton("Confirm clear test alarm") { _, _ ->
                handled = true
                dismissConfirmedTestAlarm(button, snapshot)
            }
            .create()
        dialog.setOnCancelListener {
            if (!handled) {
                updateManualCheck("alarm.set", DiagnosticStatus.WARN, "PASS for timestamp read-back: AlarmManager reports the next alarm at ${formatAlarmTarget(observed)} after the request with label '$label'. The API does not expose label/ownership. Cleanup confirmation was cancelled; the alarm remains scheduled. Open Clock > Alarms and delete '$label'.")
                finishActionTest("alarm.set", button, completed = true)
            }
        }
        dialog.show()
    }

    private fun dismissConfirmedTestAlarm(button: Button, snapshot: DeviceActionSnapshot) {
        val expected = snapshot.targetAlarmMillis
        val label = snapshot.alarmLabel ?: ""
        val current = DeviceStateReader.nextAlarmClockTriggerMillis(this)
        if (expected == null || current == null || kotlin.math.abs(current - expected) > 90_000L) {
            updateManualCheck("alarm.set", DiagnosticStatus.WARN, "The next-alarm read-back changed before cleanup, so PocketMind did not send a dismissal request. Check Clock > Alarms and delete '$label' if it is still present; no cleanup is claimed.")
            finishActionTest("alarm.set", button, completed = true)
            return
        }
        val step = PlanStep("alarm.dismiss", mapOf("label" to label))
        val pending = PendingExternalAction("alarm.dismiss", step, button, snapshot, "before=${snapshot.previousNextAlarmMillis ?: "no next alarm"}")
        pendingExternalAction = pending
        uiScope.launch {
            try {
                val controller = (application as PocketMindApplication).agentController
                val result = withContext(Dispatchers.IO) { controller.dismissDiagnosticAlarmByLabel(label, userApproved = true) }
                pending.result = result
                if (pending.returnedToTester) completeExternalAction(pending)
                else window.decorView.postDelayed({
                    if (pendingExternalAction === pending && !pending.activityPaused) completeExternalAction(pending)
                }, 900L)
            } catch (_: Exception) {
                pendingExternalAction = null
                updateManualCheck("alarm.set", DiagnosticStatus.WARN, "The separately confirmed label-scoped dismissal did not return a verifiable result. The alarm may remain; open Clock > Alarms and delete '$label'.")
                finishActionTest("alarm.set", button, completed = true)
            }
        }
    }

    private fun completeAlarmDismissal(pending: PendingExternalAction) {
        val result = pending.result ?: return
        val snapshot = pending.snapshot
        val observed = DeviceStateReader.nextAlarmClockTriggerMillis(this)
        val restored = observed == snapshot.previousNextAlarmMillis
        val label = snapshot.alarmLabel ?: "unknown test label"
        val verified = result.status == ActionStatus.HANDED_OFF && restored
        val details = if (verified) {
            "PASS for timestamp restoration: after the separately confirmed label-scoped dismissal request, AlarmManager's next-alarm timestamp returned to the captured prior state (${if (observed == null) "no next alarm" else "prior next-alarm timestamp restored"}). Android does not expose alarm labels/ownership in this read-back; if the label '$label' still appears in Clock > Alarms, remove it there."
        } else {
            "The label-scoped dismissal was requested (${result.status}), but AlarmManager did not verify the previous next-alarm state (read-back=${observed?.let(::formatAlarmTarget) ?: "none"}). Cleanup is not claimed. Open Clock > Alarms and delete '$label' manually."
        }
        updateManualCheck("alarm.set", if (verified) DiagnosticStatus.PASS else DiagnosticStatus.WARN, details)
        finishActionTest("alarm.set", pending.button, completed = true)
    }

    private fun completeTimerAction(pending: PendingExternalAction, returnedToTester: Boolean) {
        if (pendingExternalAction === pending) pendingExternalAction = null
        val result = pending.result ?: return
        val seconds = (pending.step.args["seconds"] as? Number)?.toInt()
        val failure = result.status == ActionStatus.FAILED && result.message.contains("No Clock app", true)
        val status = if (failure) DiagnosticStatus.FAIL else DiagnosticStatus.NOT_RUN
        val details = if (failure) {
            "The confirmed request could not find a Clock handler; Android did not receive the timer."
        } else {
            "A real ${seconds ?: "unknown"}-second ACTION_SET_TIMER request was sent after confirmation${if (returnedToTester) " and Tester resumed" else ""}. Android's contract starts the timer, but PocketMind cannot read the running countdown or identify this timer for safe cancellation on API 30; verification is therefore user-assisted/NOT_RUN, not PASS. Check Clock > Timer; if this timer is running, tap Stop/Cancel. It may ring if left running."
        }
        updateManualCheck("timer.set", status, details)
        finishActionTest("timer.set", pending.button, completed = true)
    }

    private fun updateManualCheck(toolId: String, status: DiagnosticStatus, details: String) {
        val current = report ?: return
        val checkTitle = "Device action · $toolId test"
        val index = current.checks.indexOfFirst { it.title == checkTitle }
        if (index < 0) return
        val checks = current.checks.toMutableList()
        checks[index] = checks[index].copy(status = status, details = details)
        val updated = current.copy(checks = checks)
        report = updated
        resultRows[index + 1]?.let { row ->
            row.first.text = "$status · $checkTitle"
            row.first.setTextColor(statusColor(status))
            row.second.text = details
        }
        state.text = updated.summary()
        progress.text = "${updated.checks.size} checks · ${updated.failures} failed · ${updated.warnings} warnings · ${updated.notRun} still not run"
    }

    private fun finishActionTest(toolId: String, button: Button, completed: Boolean) {
        runningActionTests.remove(toolId)
        if (activeDeviceActionToolId == toolId) {
            activeDeviceActionToolId = null
            activeRoutePrompt = null
            activeExpectedRouteArgs = emptyMap()
            actionSnapshot = null
        }
        if (completed) completedActionTests.add(toolId)
        button.isEnabled = !completed
        if (completed) button.text = "${toolId} test completed"
        if (report != null) {
            runButton.isEnabled = true
            shareButton.isEnabled = true
        }
    }

    private fun statusColor(status: DiagnosticStatus): Int = when (status) {
        DiagnosticStatus.PASS -> Color.rgb(24, 112, 62)
        DiagnosticStatus.WARN -> Color.rgb(151, 91, 0)
        DiagnosticStatus.FAIL -> Color.rgb(174, 35, 35)
        DiagnosticStatus.NOT_RUN -> Color.DKGRAY
    }

    private suspend fun runSwipeSmokeTest(): DiagnosticCheck = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            logScroll.post {
                if (!continuation.isActive) return@post
                val contentHeight = reportBody.height
                val viewport = logScroll.height
                if (viewport <= 0 || contentHeight <= viewport + 8) {
                    continuation.resume(DiagnosticCheck("UI · swipe scrolls the test log", DiagnosticStatus.WARN,
                        "The completed log did not exceed the viewport, so a scroll gesture was not needed. Review the visible results directly."))
                    return@post
                }
                logScroll.scrollTo(0, 0)
                dispatchSwipe(fromY = viewport * 0.82f, toY = viewport * 0.18f)
                val movedDown = logScroll.scrollY > 0
                val bottom = logScroll.scrollY
                dispatchSwipe(fromY = viewport * 0.18f, toY = viewport * 0.82f)
                val movedUp = logScroll.scrollY < bottom
                logScroll.scrollTo(0, 0)
                val passed = movedDown && movedUp
                val check = DiagnosticCheck("UI · swipe scrolls the test log", if (passed) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                    if (passed) "Synthetic finger swipes moved down through the real test cards and back up; the complete log remains reviewable."
                    else "The test cards were scrollable by layout but a synthetic swipe did not move in both directions. Check ScrollView touch handling.")
                continuation.resume(check)
            }
        }
    }

    private fun dispatchSwipe(fromY: Float, toY: Float) {
        val x = (logScroll.width / 2f).coerceAtLeast(1f)
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float, offset: Long) {
            MotionEvent.obtain(start, start + offset, action, x, y, 0).also { event ->
                logScroll.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, fromY, 0)
        val steps = 8
        for (step in 1..steps) {
            val fraction = step.toFloat() / steps
            send(MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * fraction, step * 16L)
        }
        send(MotionEvent.ACTION_UP, toY, (steps + 1) * 16L)
    }

    private fun shareReport() {
        val text = report?.asShareText() ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PocketMind all-feature QA report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(share, "Share QA report"))
    }

    override fun onPause() {
        pendingExternalAction?.activityPaused = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (pendingExternalAction?.activityPaused == true) {
            pendingExternalAction?.returnedToTester = true
            completeExternalActionIfReady()
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
