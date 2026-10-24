package com.pocketmind.ui

import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
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
import com.pocketmind.diagnostics.FeatureDiagnostics
import com.pocketmind.diagnostics.SyntheticChatTestContract
import com.pocketmind.diagnostics.SyntheticChatTestResults
import com.pocketmind.diagnostics.SyntheticFeatureRouteProbe
import com.pocketmind.diagnostics.SyntheticFeatureRouteQa
import com.pocketmind.tools.ActionStatus
import com.pocketmind.tools.PlanStep
import com.pocketmind.tools.RiskLevel
import com.pocketmind.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    private var report: DiagnosticReport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syntheticChatLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            continueTesterRun(syntheticChatResults(result), uiChecksForRun)
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
            text = "Run every feature check one by one. The tester briefly opens Chat and shows fixed synthetic questions and feature requests as temporary turns; they are not saved. Real device-action tests are separate and require confirmation before they can run; each can be skipped. Volume restoration needs a second confirmation. Alarm tests require a time you enter at least two hours ahead, and the tester will not claim an alarm was cleared."
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
            interrupted = data.getBooleanExtra(SyntheticChatTestContract.EXTRA_INTERRUPTED, false)
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

    private data class VolumeSnapshot(val index: Int, val maximum: Int)

    private fun addDeviceActionTestControls() {
        val header = TextView(this).apply {
            text = "Optional real-action tests"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 18, 0, 4)
        }
        reportBody.addView(header)
        reportBody.addView(TextView(this).apply {
            text = "Each button asks the rule-first/local-model route for a synthetic action plan, shows the exact action, then waits for Confirm or Skip. Confirmed actions are recorded in PocketMind's action log. Alarm tests are not automatically cleared."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 8)
        })
        ToolRegistry().all.filter { it.risk != RiskLevel.LOW }.forEach { spec ->
            val button = Button(this).apply {
                text = "Test ${spec.id} action"
                contentDescription = "Test ${spec.id}; requires explicit confirmation"
                setOnClickListener { startActionTest(spec.id, this) }
            }
            actionButtons[spec.id] = button
            reportBody.addView(button)
        }
    }

    private fun startActionTest(toolId: String, button: Button) {
        if (toolId in completedActionTests || !runningActionTests.add(toolId)) return
        button.isEnabled = false
        if (toolId == "alarm.set") requestAlarmTestTime(toolId, button)
        else prepareAndConfirmAction(toolId, button, null, null)
    }

    private fun requestAlarmTestTime(toolId: String, button: Button) {
        val input = EditText(this).apply {
            hint = "HH:MM (24-hour local time)"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            isSingleLine = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 28, 0)
            addView(TextView(this@DiagnosticsActivity).apply {
                text = "Choose a specific local time at least two hours from now. No alarm is set until the next screen, where you will see the date, time, and label before confirming. The tester will not automatically clear the alarm."
                textSize = 14f
                setTextColor(Color.DKGRAY)
            })
            addView(input)
        }
        var handled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose the alarm test time")
            .setView(content)
            .setNegativeButton("Skip") { _, _ ->
                handled = true
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Skipped before choosing a time; no alarm was set.")
                finishActionTest(toolId, button, completed = false)
            }
            .setPositiveButton("Review time", null)
            .create()
        dialog.setOnCancelListener {
            if (!handled) {
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Cancelled before choosing a time; no alarm was set.")
                finishActionTest(toolId, button, completed = false)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                val selected = if (Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(value)) {
                    runCatching { LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
                } else null
                if (selected == null) {
                    input.error = "Enter a valid time as HH:MM"
                    return@setOnClickListener
                }
                val now = LocalDateTime.now()
                var scheduledAt = now.toLocalDate().atTime(selected)
                if (!scheduledAt.isAfter(now)) scheduledAt = scheduledAt.plusDays(1)
                if (Duration.between(now, scheduledAt).toMinutes() < 120) {
                    input.error = "Choose a time at least two hours ahead"
                    return@setOnClickListener
                }
                handled = true
                dialog.dismiss()
                prepareAndConfirmAction(toolId, button, selected, scheduledAt)
            }
        }
        dialog.show()
    }

    private fun manualActionProbe(toolId: String, alarmTime: LocalTime?): SyntheticFeatureRouteProbe? = when (toolId) {
        "alarm.set" -> alarmTime?.let { time ->
            val prompt = "Set an alarm for ${time.format(DateTimeFormatter.ofPattern("HH:mm"))} labeled PocketMind QA diagnostic"
            SyntheticFeatureRouteProbe(toolId, prompt, mapOf("hour" to time.hour, "minute" to time.minute, "label" to "PocketMind QA diagnostic"))
        }
        "timer.set" -> SyntheticFeatureRouteProbe(toolId, "Set a timer for 60 seconds", mapOf("seconds" to 60))
        "flashlight" -> SyntheticFeatureRouteProbe(toolId, "Turn on the flashlight", mapOf("enabled" to true))
        "volume" -> SyntheticFeatureRouteProbe(toolId, "Raise the media volume", mapOf("step" to 1))
        "brightness" -> SyntheticFeatureRouteProbe(toolId, "Set display brightness to 128", mapOf("level" to 128))
        "app.open" -> SyntheticFeatureRouteProbe(toolId, "Open the app with package com.android.settings", mapOf("package" to "com.android.settings"))
        "wifi.settings" -> SyntheticFeatureRouteProbe(toolId, "Open Wi-Fi settings")
        "bluetooth.settings" -> SyntheticFeatureRouteProbe(toolId, "Open Bluetooth settings")
        "airplane.settings" -> SyntheticFeatureRouteProbe(toolId, "Open airplane mode settings")
        "location.settings" -> SyntheticFeatureRouteProbe(toolId, "Open location settings")
        else -> null
    }

    private fun prepareAndConfirmAction(toolId: String, button: Button, alarmTime: LocalTime?, scheduledAt: LocalDateTime?) {
        uiScope.launch {
            try {
                state.text = "Preparing a synthetic ${toolId} route; no action has been executed."
                val probe = manualActionProbe(toolId, alarmTime)
                if (probe == null) {
                    updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "No safe synthetic action fixture exists; no action was executed.")
                    finishActionTest(toolId, button, completed = false)
                    return@launch
                }
                val controller = (application as PocketMindApplication).agentController
                val routed = withContext(Dispatchers.IO) { controller.runFeatureRouteDiagnostic(probe) }
                val step = routed.plan
                if (routed.status != DiagnosticStatus.PASS || step == null) {
                    val details = if (routed.status == DiagnosticStatus.NOT_RUN)
                        "A local model is needed for this route and none is loaded; no action was executed."
                    else "The synthetic request did not produce the expected validated tool plan; no action was executed."
                    updateManualCheck(toolId, routed.status, details)
                    finishActionTest(toolId, button, completed = false)
                    return@launch
                }
                val volumeSnapshot = if (toolId == "volume") {
                    val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                    VolumeSnapshot(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                } else null
                if (volumeSnapshot != null && volumeSnapshot.index >= volumeSnapshot.maximum) {
                    updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Media volume is already at maximum, so the one-step raise was not run.")
                    finishActionTest(toolId, button, completed = false)
                    return@launch
                }
                val previousAlarm = if (toolId == "alarm.set") {
                    (getSystemService(ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime
                } else null
                showActionConfirmation(toolId, step, button, volumeSnapshot, alarmTime, scheduledAt, previousAlarm)
            } catch (_: Exception) {
                updateManualCheck(toolId, DiagnosticStatus.FAIL, "The action preview could not be prepared; no action was executed.")
                finishActionTest(toolId, button, completed = false)
            }
        }
    }

    private fun actionPreview(toolId: String, step: PlanStep, volume: VolumeSnapshot?, scheduledAt: LocalDateTime?): String = when (toolId) {
        "alarm.set" -> "Set a real Android alarm for ${scheduledAt?.format(DateTimeFormatter.ofPattern("EEE, d MMM 'at' HH:mm"))} labeled ‘PocketMind QA diagnostic’. It may remain scheduled; the tester will not automatically clear it or claim it was cleared."
        "timer.set" -> "Start a real 60-second timer in the Clock app. The tester cannot silently cancel it."
        "flashlight" -> "Turn the phone flashlight on. The state will be read back; it may remain on until you turn it off."
        "volume" -> "Raise media volume by one device step from ${volume?.index}/${volume?.maximum} to ${(volume?.index ?: 0) + 1}/${volume?.maximum}. The tester will read it back, then ask separately before restoring the original level."
        "brightness" -> "Set display brightness to level ${step.args["level"]} of 255. This is a real screen change and will not run until you confirm."
        "app.open" -> "Open Android Settings (${step.args["package"]}). This will leave the tester screen until you return."
        "wifi.settings" -> "Open Android Wi-Fi settings. No Wi-Fi setting will be changed automatically."
        "bluetooth.settings" -> "Open Android Bluetooth settings. No Bluetooth setting will be changed automatically."
        "airplane.settings" -> "Open Android airplane-mode settings. Airplane mode will not be toggled automatically."
        "location.settings" -> "Open Android location settings. Location will not be toggled automatically."
        else -> "Run the proposed ${step.tool} action."
    }

    private fun showActionConfirmation(
        toolId: String,
        step: PlanStep,
        button: Button,
        volume: VolumeSnapshot?,
        alarmTime: LocalTime?,
        scheduledAt: LocalDateTime?,
        previousAlarm: Long?
    ) {
        var handled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Confirm real action test?")
            .setMessage(actionPreview(toolId, step, volume, scheduledAt))
            .setNegativeButton("Skip") { _, _ ->
                handled = true
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Skipped at the action preview; no device action was taken.")
                finishActionTest(toolId, button, completed = false)
            }
            .setPositiveButton("Confirm & run") { _, _ ->
                handled = true
                executeConfirmedAction(toolId, step, button, volume, alarmTime, scheduledAt, previousAlarm)
            }
            .create()
        dialog.setOnCancelListener {
            if (!handled) {
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Cancelled at the action preview; no device action was taken.")
                finishActionTest(toolId, button, completed = false)
            }
        }
        dialog.show()
    }

    private fun executeConfirmedAction(
        toolId: String,
        step: PlanStep,
        button: Button,
        volume: VolumeSnapshot?,
        alarmTime: LocalTime?,
        scheduledAt: LocalDateTime?,
        previousAlarm: Long?
    ) {
        if (toolId == "volume" && volume != null) {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != volume.index) {
                updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "Media volume changed while the preview was open; no action was run to avoid overwriting it. Rerun the test.")
                finishActionTest(toolId, button, completed = false)
                return
            }
        }
        if (toolId == "alarm.set" && scheduledAt != null && Duration.between(LocalDateTime.now(), scheduledAt).toMinutes() < 120) {
            updateManualCheck(toolId, DiagnosticStatus.NOT_RUN, "The chosen time is now less than two hours away; no alarm was set. Choose a later time and rerun.")
            finishActionTest(toolId, button, completed = false)
            return
        }
        uiScope.launch {
            try {
                state.text = "Running the user-confirmed ${toolId} action test…"
                val controller = (application as PocketMindApplication).agentController
                val result = withContext(Dispatchers.IO) { controller.runConfirmedDiagnosticAction(step, userApproved = true) }
                if (toolId == "volume" && volume != null) {
                    handleVolumeTestResult(button, volume, result)
                } else {
                    val (status, details) = when (toolId) {
                        "alarm.set" -> alarmResult(result.status, scheduledAt, previousAlarm)
                        "timer.set" -> when (result.status) {
                            ActionStatus.HANDED_OFF -> DiagnosticStatus.WARN to "The Clock app received the confirmed 60-second timer request, but running state was handed off and not verified. The action was logged."
                            ActionStatus.VERIFIED -> DiagnosticStatus.PASS to "The timer action was verified and logged."
                            ActionStatus.FAILED -> DiagnosticStatus.FAIL to "The confirmed timer action failed. The result was logged."
                            else -> DiagnosticStatus.NOT_RUN to "The timer action was not completed."
                        }
                        else -> when (result.status) {
                            ActionStatus.VERIFIED -> DiagnosticStatus.PASS to "The confirmed ${toolId} action completed with runtime read-back and was logged."
                            ActionStatus.HANDED_OFF -> DiagnosticStatus.WARN to "Android received the confirmed ${toolId} request, but final state was handed off and not verified. The action was logged."
                            ActionStatus.FAILED -> DiagnosticStatus.FAIL to "The confirmed ${toolId} action failed. The result was logged."
                            else -> DiagnosticStatus.NOT_RUN to "The action did not complete."
                        }
                    }
                    updateManualCheck(toolId, status, details)
                    finishActionTest(toolId, button, completed = true)
                }
            } catch (_: Exception) {
                updateManualCheck(toolId, DiagnosticStatus.FAIL, "The confirmed action did not return a verifiable result. Check the phone state and action log before retrying.")
                finishActionTest(toolId, button, completed = true)
            }
        }
    }

    private fun alarmResult(status: ActionStatus, scheduledAt: LocalDateTime?, previousAlarm: Long?): Pair<DiagnosticStatus, String> {
        val target = scheduledAt?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        val preexistingMatch = target != null && previousAlarm != null && kotlin.math.abs(previousAlarm - target) <= 90_000L
        return when {
            status == ActionStatus.VERIFIED && preexistingMatch -> DiagnosticStatus.WARN to "Android reports an alarm at the selected time, but one was already present before this test, so creation could not be distinguished. The action was logged; the tester did not claim it was cleared."
            status == ActionStatus.VERIFIED -> DiagnosticStatus.PASS to "Android's next-alarm state matched the user-selected test time. The action was logged. The alarm remains scheduled; the tester did not attempt or claim removal."
            status == ActionStatus.HANDED_OFF -> DiagnosticStatus.WARN to "Android received the user-confirmed alarm request, but did not expose a matching next alarm for verification. The action was logged; check the Clock app. No clearing is claimed."
            status == ActionStatus.FAILED -> DiagnosticStatus.FAIL to "The confirmed alarm request failed or could not be verified. The result was logged; no removal is claimed."
            else -> DiagnosticStatus.NOT_RUN to "The alarm action did not complete; no removal is claimed."
        }
    }

    private fun handleVolumeTestResult(button: Button, original: VolumeSnapshot, result: com.pocketmind.tools.ActionResult) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val observed = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val expected = (original.index + 1).coerceAtMost(original.maximum)
        val raisedAndVerified = result.status == ActionStatus.VERIFIED && observed == expected && observed > original.index
        if (observed != original.index) {
            showVolumeRestoreConfirmation(button, original, observed, raisedAndVerified)
        } else {
            val status = if (raisedAndVerified) DiagnosticStatus.PASS else if (result.status == ActionStatus.FAILED) DiagnosticStatus.FAIL else DiagnosticStatus.WARN
            updateManualCheck("volume", status, if (status == DiagnosticStatus.PASS)
                "The one-step media-volume raise was read back and logged." else "The media-volume change was not verified; no restore was needed because the original level is still present. The result was logged.")
            finishActionTest("volume", button, completed = true)
        }
    }

    private fun showVolumeRestoreConfirmation(button: Button, original: VolumeSnapshot, observed: Int, raisedAndVerified: Boolean) {
        var handled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Restore the original media volume?")
            .setMessage("The original level was ${original.index}/${original.maximum}; read-back after the test is $observed/${original.maximum}. This is a second device change. Confirm to restore exactly, or skip and leave the current level.")
            .setNegativeButton("Skip restore") { _, _ ->
                handled = true
                finishVolumeTest(button, raisedAndVerified, restored = false, restoreVerified = false)
            }
            .setPositiveButton("Restore original") { _, _ ->
                handled = true
                restoreOriginalVolume(button, original, observed, raisedAndVerified)
            }
            .create()
        dialog.setOnCancelListener {
            if (!handled) finishVolumeTest(button, raisedAndVerified, restored = false, restoreVerified = false)
        }
        dialog.show()
    }

    private fun restoreOriginalVolume(button: Button, original: VolumeSnapshot, observed: Int, raisedAndVerified: Boolean) {
        uiScope.launch {
            try {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != observed) {
                    finishVolumeTest(button, raisedAndVerified, restored = false, restoreVerified = false, externallyChanged = true)
                    return@launch
                }
                val controller = (application as PocketMindApplication).agentController
                val result = withContext(Dispatchers.IO) { controller.restoreDiagnosticVolumeIndex(original.index, userApproved = true) }
                val verified = result.status == ActionStatus.VERIFIED && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == original.index
                finishVolumeTest(button, raisedAndVerified, restored = true, restoreVerified = verified)
            } catch (_: Exception) {
                finishVolumeTest(button, raisedAndVerified, restored = true, restoreVerified = false)
            }
        }
    }

    private fun finishVolumeTest(
        button: Button,
        raisedAndVerified: Boolean,
        restored: Boolean,
        restoreVerified: Boolean,
        externallyChanged: Boolean = false
    ) {
        val (status, details) = when {
            externallyChanged -> DiagnosticStatus.WARN to "The media volume changed after the restore prompt, so the tester did not overwrite it. Review the current level; action log entries are retained."
            raisedAndVerified && restored && restoreVerified -> DiagnosticStatus.PASS to "The one-step media-volume increase was read back, then the original level was restored and read back after separate confirmation. Both actions were logged."
            raisedAndVerified && restored -> DiagnosticStatus.WARN to "The one-step increase was read back, but exact restoration could not be verified. Check the current media volume; both actions were logged."
            raisedAndVerified -> DiagnosticStatus.WARN to "The one-step increase was read back, but restore was skipped; the media volume remains changed. The action was logged."
            restored && restoreVerified -> DiagnosticStatus.FAIL to "The expected one-step increase was not verified; the original volume was restored and read back. Actions were logged."
            else -> DiagnosticStatus.WARN to "The increase and/or restoration could not be verified. Check the current media volume; the tester does not claim restoration. Actions were logged."
        }
        updateManualCheck("volume", status, details)
        finishActionTest("volume", button, completed = true)
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
        if (completed) completedActionTests.add(toolId)
        button.isEnabled = !completed
        if (completed) button.text = "${toolId} test completed"
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

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
