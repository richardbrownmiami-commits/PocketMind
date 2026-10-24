package com.pocketmind.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketmind.PocketMindApplication
import com.pocketmind.core.AgentReply
import com.pocketmind.core.GenerationService
import com.pocketmind.diagnostics.DiagnosticStatus
import com.pocketmind.diagnostics.SyntheticChatTestContract
import com.pocketmind.diagnostics.SyntheticFeatureRouteQa
import com.pocketmind.diagnostics.SyntheticModelQa
import com.pocketmind.tools.ToolValidator
import com.pocketmind.tools.PlanStep
import com.pocketmind.memory.AppDatabase
import com.pocketmind.memory.MessageStore
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as PocketMindApplication).agentController
    private lateinit var adapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var stop: Button
    private lateinit var testerButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var syntheticChatMode = false
    private var syntheticStopRequested = false
    private var syntheticFinished = false
    private var syntheticResultIntent: Intent? = null
    private var syntheticModelLoadedAtStart = false
    private var syntheticQaStatus = DiagnosticStatus.NOT_RUN
    private val syntheticRouteStatuses = linkedMapOf<String, DiagnosticStatus>()
    private val syntheticRoutePlans = linkedMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syntheticChatMode = intent.getBooleanExtra(SyntheticChatTestContract.EXTRA_RUN, false)
        buildView()
        status.text = controller.status()
        if (syntheticChatMode) {
            if (savedInstanceState?.getBoolean(STATE_SYNTHETIC_QA_ENTERED, false) == true) {
                restoreSyntheticChatSession(savedInstanceState)
            } else runSyntheticChatQa()
        } else loadHistory()
    }

    private fun loadHistory() {
        uiScope.launch {
            val history = withContext(Dispatchers.IO) {
                val helper = AppDatabase(this@MainActivity)
                try { MessageStore(helper).recent(60).map { ChatAdapter.Line(it.role, it.content) } }
                finally { helper.close() }
            }
            adapter.replace(history)
            scrollToBottom()
        }
    }

    private fun buildView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.WHITE)
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(Button(this).apply {
            text = "Models"
            isEnabled = !syntheticChatMode
            setOnClickListener { startActivity(Intent(this@MainActivity, ModelsActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(Button(this).apply {
            text = "Memory"
            isEnabled = !syntheticChatMode
            setOnClickListener { startActivity(Intent(this@MainActivity, MemoryActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(nav)
        testerButton = Button(this).apply {
            text = "Tester · Run all feature tests"
            contentDescription = "Open the all-feature PocketMind tester"
            setOnClickListener {
                if (syntheticChatMode) {
                    if (syntheticFinished) returnToTesterReport()
                    else {
                        syntheticStopRequested = true
                        controller.stop()
                        text = "Stopping synthetic QA…"
                        isEnabled = false
                    }
                } else startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java))
            }
        }
        root.addView(testerButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 6
        })
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(8, 0, 8, 8)
        }
        root.addView(status)

        adapter = ChatAdapter()
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            this.adapter = this@MainActivity.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        input = EditText(this).apply {
            hint = "Ask or type a command"
            minLines = 1
            maxLines = 4
            setPadding(12, 10, 12, 10)
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        send = Button(this).apply {
            text = "Send"
            setOnClickListener { submit() }
        }
        actions.addView(send)
        root.addView(actions)
        stop = Button(this).apply {
            text = "Stop generation"
            isEnabled = false
            setOnClickListener {
                if (syntheticChatMode) syntheticStopRequested = true
                controller.stop()
                status.text = "Stopping generation…"
            }
        }
        root.addView(stop)
        setContentView(root)
    }

    private fun runSyntheticChatQa() {
        input.visibility = View.GONE
        send.visibility = View.GONE
        send.isEnabled = false
        stop.text = "Stop synthetic QA"
        stop.isEnabled = true
        testerButton.text = "Stop and return to Tester"
        testerButton.contentDescription = "Stop the transient synthetic QA session"
        testerButton.isEnabled = true
        stop.setOnClickListener {
            syntheticStopRequested = true
            controller.stop()
            stop.isEnabled = false
            testerButton.isEnabled = false
            status.text = "Stopping the transient QA session… no device action is being run."
        }
        status.text = "Synthetic QA is being asked in Chat. These temporary turns are visible here but are not saved to history, memory, notes, or action logs. Feature actions are only routed; none are executed."
        syntheticModelLoadedAtStart = false
        syntheticQaStatus = DiagnosticStatus.NOT_RUN
        syntheticRouteStatuses.clear()
        syntheticRoutePlans.clear()
        var completed = false
        uiScope.launch {
            val syntheticTurns = mutableListOf<String>()
            var qaChecked = 0
            var qaFailed = false
            try {
                syntheticModelLoadedAtStart = controller.syntheticChatModelAvailable()
                val actionToolId = intent.getStringExtra(SyntheticChatTestContract.EXTRA_ACTION_ROUTE_TOOL)
                if (actionToolId == null) {
                    for (probe in SyntheticModelQa.probes) {
                        if (syntheticStopRequested) break
                        val answer = appendSyntheticChatTurn(probe.prompt, syntheticTurns)
                        if (syntheticStopRequested) break
                        if (answer.modelLoaded) {
                            qaChecked++
                            if (!SyntheticModelQa.responseContainsExpectedAnswer(answer.text, probe.expectedAnswer)) qaFailed = true
                        }
                    }
                    syntheticQaStatus = when {
                        !syntheticModelLoadedAtStart || syntheticStopRequested || qaChecked != SyntheticModelQa.probes.size -> DiagnosticStatus.NOT_RUN
                        qaFailed -> DiagnosticStatus.FAIL
                        else -> DiagnosticStatus.PASS
                    }
                } else {
                    syntheticQaStatus = DiagnosticStatus.NOT_RUN
                }
                val routeProbes = if (actionToolId == null) SyntheticFeatureRouteQa.probes
                    else SyntheticFeatureRouteQa.probes.filter { it.toolId == actionToolId }
                val actionRoutePrompt = intent.getStringExtra(SyntheticChatTestContract.EXTRA_ACTION_ROUTE_PROMPT)
                if (actionToolId != null && routeProbes.isEmpty()) syntheticRouteStatuses[actionToolId] = DiagnosticStatus.FAIL
                for (probe in routeProbes) {
                    if (syntheticStopRequested) break
                    val prompt = actionRoutePrompt?.takeIf { actionToolId == probe.toolId } ?: probe.prompt
                    val answer = appendSyntheticChatTurn(prompt, syntheticTurns)
                    if (syntheticStopRequested) {
                        syntheticRouteStatuses[probe.toolId] = DiagnosticStatus.NOT_RUN
                        break
                    }
                    val routeMatches = answer.plan != null && if (actionRoutePrompt != null && actionToolId == probe.toolId) {
                        answer.plan.singleOrNull()?.let { it.tool == probe.toolId && ToolValidator().validate(it) == null } == true
                    } else SyntheticFeatureRouteQa.matches(probe, answer.plan, ToolValidator())
                    syntheticRouteStatuses[probe.toolId] = when {
                        routeMatches -> DiagnosticStatus.PASS
                        answer.plan == null && !answer.modelLoaded -> DiagnosticStatus.NOT_RUN
                        else -> DiagnosticStatus.FAIL
                    }
                    if (routeMatches) syntheticRoutePlans[probe.toolId] = encodePlan(answer.plan.orEmpty())
                }
                completed = !syntheticStopRequested
            } catch (_: Exception) {
                completed = false
            } finally {
                syntheticResultIntent = buildSyntheticResultIntent(completed, interrupted = false)
                syntheticFinished = true
                stop.isEnabled = false
                testerButton.text = "Return to Tester report"
                testerButton.contentDescription = "Return to the all-feature QA report"
                testerButton.isEnabled = true
                testerButton.setOnClickListener { returnToTesterReport() }
                status.text = if (completed) "Synthetic Chat QA is complete. The displayed turns were temporary and not saved; tap Return to Tester report to review the results."
                else "Synthetic Chat QA stopped early. Completed checks are retained only as statuses; displayed turns were not saved. Tap Return to Tester report."
            }
        }
    }

    private fun buildSyntheticResultIntent(completed: Boolean, interrupted: Boolean): Intent {
        val routeIds = SyntheticFeatureRouteQa.probes.map { it.toolId }
        return Intent()
            .putExtra(SyntheticChatTestContract.EXTRA_STARTED, true)
            .putExtra(SyntheticChatTestContract.EXTRA_MODEL_LOADED, syntheticModelLoadedAtStart)
            .putExtra(SyntheticChatTestContract.EXTRA_QA_STATUS, syntheticQaStatus.name)
            .putExtra(SyntheticChatTestContract.EXTRA_COMPLETED, completed)
            .putExtra(SyntheticChatTestContract.EXTRA_INTERRUPTED, interrupted)
            .putStringArrayListExtra(SyntheticChatTestContract.EXTRA_ROUTE_IDS, ArrayList(routeIds))
            .putStringArrayListExtra(SyntheticChatTestContract.EXTRA_ROUTE_STATUSES,
                ArrayList(routeIds.map { (syntheticRouteStatuses[it] ?: DiagnosticStatus.NOT_RUN).name }))
            .putExtra(SyntheticChatTestContract.EXTRA_ACTION_ROUTE_TOOL,
                intent.getStringExtra(SyntheticChatTestContract.EXTRA_ACTION_ROUTE_TOOL))
            .putExtra(SyntheticChatTestContract.EXTRA_ACTION_ROUTE_PLAN_JSON,
                intent.getStringExtra(SyntheticChatTestContract.EXTRA_ACTION_ROUTE_TOOL)?.let(syntheticRoutePlans::get))
    }

    private fun encodePlan(steps: List<PlanStep>): String = JSONObject().put("steps", JSONArray().apply {
        steps.forEach { step ->
            val args = JSONObject()
            step.args.forEach { (key, value) -> args.put(key, value) }
            put(JSONObject().put("tool", step.tool).put("args", args))
        }
    }).toString()

    private fun restoreSyntheticChatSession(state: Bundle) {
        input.visibility = View.GONE
        send.visibility = View.GONE
        send.isEnabled = false
        stop.text = "Stop synthetic QA"
        stop.isEnabled = false
        syntheticFinished = true
        syntheticModelLoadedAtStart = state.getBoolean(STATE_MODEL_LOADED, false)
        syntheticQaStatus = runCatching {
            DiagnosticStatus.valueOf(state.getString(STATE_QA_STATUS) ?: DiagnosticStatus.NOT_RUN.name)
        }.getOrDefault(DiagnosticStatus.NOT_RUN)
        val ids = state.getStringArrayList(STATE_ROUTE_IDS).orEmpty()
        val statuses = state.getStringArrayList(STATE_ROUTE_STATUSES).orEmpty()
        syntheticRouteStatuses.clear()
        syntheticRoutePlans.clear()
        state.getString(STATE_ACTION_ROUTE_PLAN)?.let { json ->
            intent.getStringExtra(SyntheticChatTestContract.EXTRA_ACTION_ROUTE_TOOL)?.let { syntheticRoutePlans[it] = json }
        }
        ids.zip(statuses).forEach { (id, value) ->
            runCatching { syntheticRouteStatuses[id] = DiagnosticStatus.valueOf(value) }
        }
        val hadResult = state.getBoolean(STATE_HAS_RESULT, false)
        val completed = hadResult && state.getBoolean(STATE_RESULT_COMPLETED, false)
        val interrupted = !hadResult || state.getBoolean(STATE_RESULT_INTERRUPTED, false)
        syntheticResultIntent = buildSyntheticResultIntent(completed, interrupted)
        testerButton.text = "Return to Tester report"
        testerButton.contentDescription = "Return to the all-feature QA report"
        testerButton.setOnClickListener { returnToTesterReport() }
        status.text = when {
            completed -> "Synthetic QA completed before this screen was recreated. Sanitized statuses were restored; the temporary chat transcript was not saved. Tap Return to Tester report."
            interrupted -> "Chat was recreated while synthetic QA was in progress. It will not repeat prompts; completed statuses were restored and unfinished checks remain NOT_RUN. Tap Return to Tester report."
            else -> "Synthetic QA was stopped before this screen was recreated. Its transcript was not saved and the remaining checks will not be repeated. Tap Return to Tester report."
        }
    }

    private suspend fun appendSyntheticChatTurn(prompt: String, turns: MutableList<String>): com.pocketmind.diagnostics.SyntheticChatTurnResult {
        adapter.add("user", prompt)
        val answerRow = adapter.add("assistant", "")
        scrollToBottom()
        val result = controller.askSyntheticChat(prompt, turns)
        adapter.update(answerRow, result.text)
        turns += "user: ${prompt.take(1000)}"
        turns += "assistant: ${result.text.take(3000)}"
        scrollToBottom()
        return result
    }

    private fun returnToTesterReport() {
        setResult(RESULT_OK, syntheticResultIntent ?: Intent())
        finish()
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || !send.isEnabled) return
        input.setText("")
        adapter.add("user", text)
        val answerRow = adapter.add("assistant", "")
        scrollToBottom()
        send.isEnabled = false
        testerButton.isEnabled = false
        stop.isEnabled = true
        status.text = "Working on device…"
        try { ContextCompat.startForegroundService(this, Intent(this, GenerationService::class.java).setAction(GenerationService.ACTION_START)) }
        catch (_: Exception) { }
        val streamed = StringBuilder()
        uiScope.launch {
            try {
                val reply = controller.send(text) { chunk ->
                    handler.post { streamed.append(chunk); adapter.update(answerRow, streamed.toString()); scrollToBottom() }
                }
                adapter.update(answerRow, reply.text)
                status.text = controller.status()
                askForConfirmation(reply, answerRow)
            } catch (e: Exception) {
                adapter.update(answerRow, "PocketMind could not finish: ${e.message ?: "unexpected error"}")
                status.text = controller.status()
            } finally {
                send.isEnabled = true
                testerButton.isEnabled = true
                stop.isEnabled = false
                stopService(Intent(this@MainActivity, GenerationService::class.java))
                scrollToBottom()
            }
        }
    }

    private fun askForConfirmation(reply: AgentReply, row: Int) {
        val pending = reply.actions.filter { it.status.name == "PENDING_CONFIRMATION" }
        val confirmationId = reply.confirmationId ?: return
        if (pending.isEmpty()) return
        val names = pending.joinToString("\n") { "• ${it.tool}: ${it.args}" }
        AlertDialog.Builder(this)
            .setTitle("Confirm phone action")
            .setMessage("PocketMind wants to run:\n$names\n\nContinue?")
            .setPositiveButton("Run") { _, _ ->
                uiScope.launch {
                    val results = controller.confirmPending(confirmationId, true)
                    val text = if (results.isEmpty()) "This confirmation expired or was already used; nothing was run."
                    else results.joinToString("\n") { "${it.tool}: ${it.status} — ${it.message}" }
                    adapter.update(row, listOf(reply.text, text).filter { it.isNotBlank() }.joinToString("\n"))
                    status.text = controller.status()
                    scrollToBottom()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                uiScope.launch {
                    val results = controller.confirmPending(confirmationId, false)
                    val text = if (results.isEmpty()) "This confirmation expired or was already used; nothing was run."
                    else results.joinToString("\n") { "${it.tool}: ${it.status} — ${it.message}" }
                    adapter.update(row, listOf(reply.text, text).filter { it.isNotBlank() }.joinToString("\n"))
                    scrollToBottom()
                }
            }
            .show()
    }

    private fun scrollToBottom() {
        if (::adapter.isInitialized && adapter.itemCount > 0) list.post { list.scrollToPosition(adapter.itemCount - 1) }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && !syntheticChatMode) { status.text = controller.status(); loadHistory() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (syntheticChatMode) {
            outState.putBoolean(STATE_SYNTHETIC_QA_ENTERED, true)
            outState.putBoolean(STATE_MODEL_LOADED, syntheticModelLoadedAtStart)
            outState.putString(STATE_QA_STATUS, syntheticQaStatus.name)
            val routeIds = SyntheticFeatureRouteQa.probes.map { it.toolId }
            outState.putStringArrayList(STATE_ROUTE_IDS, ArrayList(routeIds))
            outState.putStringArrayList(STATE_ROUTE_STATUSES,
                ArrayList(routeIds.map { (syntheticRouteStatuses[it] ?: DiagnosticStatus.NOT_RUN).name }))
            intent.getStringExtra(SyntheticChatTestContract.EXTRA_ACTION_ROUTE_TOOL)?.let { toolId ->
                syntheticRoutePlans[toolId]?.let { outState.putString(STATE_ACTION_ROUTE_PLAN, it) }
            }
            val result = syntheticResultIntent
            outState.putBoolean(STATE_HAS_RESULT, result != null)
            if (result != null) {
                outState.putBoolean(STATE_RESULT_COMPLETED, result.getBooleanExtra(SyntheticChatTestContract.EXTRA_COMPLETED, false))
                outState.putBoolean(STATE_RESULT_INTERRUPTED, result.getBooleanExtra(SyntheticChatTestContract.EXTRA_INTERRUPTED, false))
            }
        }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android, kept to return the QA result to the Tester Activity")
    override fun onBackPressed() {
        if (!syntheticChatMode) {
            super.onBackPressed()
            return
        }
        if (syntheticFinished) returnToTesterReport()
        else {
            syntheticStopRequested = true
            controller.stop()
            status.text = "Stopping the transient QA session…"
        }
    }

    override fun onDestroy() {
        if (syntheticChatMode && !syntheticFinished) controller.stop()
        uiScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val STATE_SYNTHETIC_QA_ENTERED = "pocketmind.syntheticQa.entered"
        const val STATE_MODEL_LOADED = "pocketmind.syntheticQa.modelLoaded"
        const val STATE_QA_STATUS = "pocketmind.syntheticQa.qaStatus"
        const val STATE_ROUTE_IDS = "pocketmind.syntheticQa.routeIds"
        const val STATE_ROUTE_STATUSES = "pocketmind.syntheticQa.routeStatuses"
        const val STATE_HAS_RESULT = "pocketmind.syntheticQa.hasResult"
        const val STATE_RESULT_COMPLETED = "pocketmind.syntheticQa.resultCompleted"
        const val STATE_RESULT_INTERRUPTED = "pocketmind.syntheticQa.resultInterrupted"
        const val STATE_ACTION_ROUTE_PLAN = "pocketmind.syntheticQa.actionRoutePlan"
    }
}
