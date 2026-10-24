package com.pocketmind.core

import android.content.Context
import com.pocketmind.engine.LlamaEngine
import com.pocketmind.engine.ModelStore
import com.pocketmind.diagnostics.DiagnosticStatus
import com.pocketmind.diagnostics.SyntheticFeatureRouteProbe
import com.pocketmind.diagnostics.SyntheticFeatureRouteQa
import com.pocketmind.diagnostics.SyntheticFeatureRouteResult
import com.pocketmind.diagnostics.SyntheticModelQa
import com.pocketmind.diagnostics.SyntheticChatTurnResult
import com.pocketmind.memory.ActionLogStore
import com.pocketmind.memory.AppDatabase
import com.pocketmind.memory.FactStore
import com.pocketmind.memory.FollowUpResolver
import com.pocketmind.memory.MessageStore
import com.pocketmind.memory.RecallStore
import com.pocketmind.memory.SummaryStore
import com.pocketmind.memory.TopicState
import com.pocketmind.memory.TopicStateStore
import com.pocketmind.tools.ActionResult
import com.pocketmind.tools.ActionStatus
import com.pocketmind.tools.PlanStep
import com.pocketmind.tools.PlannerJson
import com.pocketmind.tools.RuleEngine
import com.pocketmind.tools.RiskLevel
import com.pocketmind.tools.ToolRegistry
import com.pocketmind.tools.ToolRuntime
import com.pocketmind.tools.ToolSpec
import com.pocketmind.tools.ToolValidator
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class AgentReply(
    val text: String,
    val actions: List<ActionResult> = emptyList(),
    val confirmationId: String? = null
)

/** Opaque, one-use, expiring handle for the exact plan shown in a confirmation dialog. */
internal class ConfirmationLedger(private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private data class Entry(val steps: List<PlanStep>, val createdAt: Long)
    private val entries = linkedMapOf<String, Entry>()

    fun issue(steps: List<PlanStep>): String {
        prune()
        while (entries.size >= MAX_PENDING) entries.remove(entries.keys.first())
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(steps.toList(), elapsedMillis())
        return id
    }

    fun consume(id: String): List<PlanStep>? {
        val entry = entries.remove(id) ?: return null
        val age = elapsedMillis() - entry.createdAt
        return entry.steps.takeIf { age >= 0L && age <= TTL_MS }
    }

    fun clear() = entries.clear()

    private fun prune() {
        val now = elapsedMillis()
        entries.entries.removeAll {
            val age = now - it.value.createdAt
            age < 0L || age > TTL_MS
        }
    }

    private companion object {
        const val MAX_PENDING = 8
        const val TTL_MS = 5 * 60 * 1000L
    }
}

/** Deterministic native-token budgeter. It never truncates or drops the current question. */
object PromptBudget {
    data class Inputs(
        val question: String,
        val recall: List<String>,
        val summary: String?,
        val facts: List<String>,
        val turns: List<String>
    )
    data class Result(val prompt: String, val dropped: List<String>, val tokens: Int)

    fun fit(inputs: Inputs, contextTokens: Int, countTokens: (String) -> Int): Result {
        require(contextTokens > 0)
        var recall = inputs.recall.take(2).map { it.take(700) }
        var summary = inputs.summary
        var facts = inputs.facts.toList()
        var turns = inputs.turns.toList()
        val dropped = mutableListOf<String>()
        fun render() = buildString {
            append("You are PocketMind, a private offline assistant. Use only supplied context; if uncertain, say so.\n")
            if (facts.isNotEmpty()) append("Facts and topic: ").append(facts.joinToString("; ")).append('\n')
            if (summary != null) append("Conversation summary: ").append(summary).append('\n')
            if (recall.isNotEmpty()) append("Relevant past snippets: ").append(recall.joinToString(" | ")).append('\n')
            if (turns.isNotEmpty()) append("Recent conversation:\n").append(turns.joinToString("\n")).append('\n')
            append("User question: ").append(inputs.question).append("\nAssistant:")
        }
        var prompt = render()
        var tokens = countTokens(prompt)
        while (tokens > contextTokens && recall.isNotEmpty()) {
            recall = recall.drop(1); dropped += "recall"; prompt = render(); tokens = countTokens(prompt)
        }
        if (tokens > contextTokens && summary != null) {
            summary = null; dropped += "summary"; prompt = render(); tokens = countTokens(prompt)
        }
        while (tokens > contextTokens && facts.isNotEmpty()) {
            facts = facts.dropLast(1); dropped += "facts"; prompt = render(); tokens = countTokens(prompt)
        }
        while (tokens > contextTokens && turns.isNotEmpty()) {
            turns = turns.drop(1); dropped += "turns"; prompt = render(); tokens = countTokens(prompt)
        }
        require(tokens <= contextTokens) { "Question and required prompt instructions exceed the available model context" }
        return Result(prompt, dropped, tokens)
    }
}

/** Conservative entity/topic tracker. It does not infer an entity from arbitrary text. */
object TopicExtractor {
    private val command = Regex("(?i)^\\s*(search|open|launch|call|play|set|remind|timer|alarm|navigate|find|weather|calculate|send|message)\\b")
    private val followUp = Regex("(?i)\\b(he|she|it|they|him|her|this|that|those|these|what about|tell me more|and then|then what|more about|aur batao|uske baare mein|iske baare mein)\\b")

    fun shortFollowUp(text: String): Boolean {
        val clean = text.trim()
        return clean.isNotEmpty() && clean.length <= 100 && !command.containsMatchIn(clean) && followUp.containsMatchIn(clean)
    }

    fun extract(text: String, now: Long = System.currentTimeMillis()): TopicState? {
        val clean = text.trim().take(240)
        if (clean.isEmpty() || command.containsMatchIn(clean)) return null
        val match = Regex("(?i)\\b(?:about|who is|what is|tell me about)\\s+([A-Z][\\w'-]*(?:\\s+[A-Z][\\w'-]*){0,3})").find(clean)
            ?: return null
        val subject = match.groupValues[1].trim().take(100)
        val kind = when {
            Regex("(?i)\\b(book|movie|film|song)\\b").containsMatchIn(clean) -> "media"
            Regex("(?i)\\b(city|country|place)\\b").containsMatchIn(clean) -> "place"
            else -> ""
        }
        return subject.takeIf { it.isNotBlank() }?.let { TopicState(it, kind, clean, now) }
    }
}

class AgentController(context: Context) : Closeable {
    private val app = context.applicationContext
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PocketMind-model-queue")
    }.asCoroutineDispatcher()
    private val db = AppDatabase(app)
    private val messages = MessageStore(db)
    private val topics = TopicStateStore(db)
    private val summaries = SummaryStore(db)
    private val facts = FactStore(db)
    private val recall = RecallStore(db)
    private val actionLog = ActionLogStore(db)
    private val preferences = app.getSharedPreferences("privacy", Context.MODE_PRIVATE)
    private val registry = ToolRegistry()
    private val validator = ToolValidator(registry)
    private val engine = LlamaEngine()
    @Volatile private var loadedPath: String? = null
    private var contextSize = 512
    private val pending = ConfirmationLedger()
    private val closed = AtomicBoolean(false)

    suspend fun send(text: String, onToken: (String) -> Unit = {}): AgentReply = withContext(dispatcher) {
        ensureOpen()
        val raw = text.trim()
        if (raw.isEmpty()) return@withContext AgentReply("Please enter a message.")

        val forget = Regex("(?i)^\\s*(forget|delete|remove)\\s+(?:my\\s+)?(?:memory\\s+about\\s+)?(.+?)\\s*$").matchEntire(raw)
        if (forget != null) {
            val count = facts.forget(forget.groupValues[2])
            messages.append("user", raw)
            val answer = if (count > 0) "Forgot that saved information." else "I couldn't find a saved fact matching that."
            messages.append("assistant", answer)
            return@withContext AgentReply(answer)
        }
        if (preferences.getBoolean("facts_enabled", true) && !Regex("(?i)^\\s*remember\\s+to\\b").containsMatchIn(raw) && facts.capture(raw)) {
            messages.append("user", raw)
            val answer = "I’ll remember that."
            messages.append("assistant", answer)
            return@withContext AgentReply(answer)
        }
        if (Regex("(?i)\\b(what do you remember|what do you know about me|list my facts)\\b").containsMatchIn(raw)) {
            val saved = facts.all()
            val answer = if (!preferences.getBoolean("facts_enabled", true)) "Fact memory is turned off." else if (saved.isEmpty()) "I don't have any saved facts." else saved.joinToString("\n") { "${it.key}: ${it.value}" }
            messages.append("user", raw); messages.append("assistant", answer)
            return@withContext AgentReply(answer)
        }
        if (Regex("(?i)\\b(what is|what's|tell me) my name\\b").containsMatchIn(raw)) {
            val name = if (preferences.getBoolean("facts_enabled", true)) facts.all().firstOrNull { it.key.equals("name", true) }?.value else null
            val answer = if (!preferences.getBoolean("facts_enabled", true)) "Fact memory is turned off." else name?.let { "Your name is $it." } ?: "I don't have your name saved."
            messages.append("user", raw); messages.append("assistant", answer)
            return@withContext AgentReply(answer)
        }
        if (Regex("(?i)\\bwhy did you (?:do that|set|start|turn on|turn off|open|change)\\b").containsMatchIn(raw)) {
            val records = actionLog.recent(4)
            val answer = if (records.isEmpty()) "I don't have a recent recorded action to explain." else records.joinToString("\n") { "${it.tool}: ${it.message} (${it.outcome})" }
            messages.append("user", raw); messages.append("assistant", answer)
            return@withContext AgentReply(answer)
        }

        val now = System.currentTimeMillis()
        val topic = topics.current(now)
        val resolved = if (TopicExtractor.shortFollowUp(raw)) FollowUpResolver.resolve(raw, topic) ?: raw else raw
        val rule = RuleEngine.match(raw)
        if (rule != null) {
            val userId = messages.append("user", raw)
            indexRecall(userId, raw)
            if (rule.isEmpty()) {
                val answer = "Okay — I won’t take that action."
                messages.append("assistant", answer)
                return@withContext AgentReply(answer)
            }
            val execution = executeOrHold(rule)
            val answer = renderActions(execution.results)
            val assistantId = messages.append("assistant", answer)
            indexRecall(assistantId, answer)
            updateTopic(raw, execution.results)
            return@withContext AgentReply(answer, execution.results, execution.confirmationId)
        }

        val clarification = RuleEngine.clarification(raw)
        if (clarification != null) {
            messages.append("user", raw)
            messages.append("assistant", clarification)
            return@withContext AgentReply(clarification)
        }

        val modelPath = ModelStore(app).selected()?.absolutePath
        if (modelPath == null || loadedPath == null) {
            messages.append("user", raw)
            val answer = "No model is loaded. Choose a compatible GGUF model in Models to chat or plan actions."
            messages.append("assistant", answer)
            return@withContext AgentReply(answer)
        }
        val userId = messages.append("user", raw)
        indexRecall(userId, raw)

        val plan = createActionPlan(resolved, raw)
        if (plan != null) {
            val execution = executeOrHold(plan)
            val answer = renderActions(execution.results)
            val assistantId = messages.append("assistant", answer)
            indexRecall(assistantId, answer)
            updateTopic(raw, execution.results)
            return@withContext AgentReply(answer, execution.results, execution.confirmationId)
        }

        val currentRecall = if (preferences.getBoolean("recall_enabled", true)) recall.recall(resolved, userId).take(2).map { it.text }.let { capChars(it, 700) } else emptyList()
        val factContext = if (preferences.getBoolean("facts_enabled", true)) facts.relevant(resolved, 3).map { "${it.key}: ${it.value}" }.toMutableList() else mutableListOf()
        topic?.let { factContext.add("Current topic: ${it.entity}") }
        val turnWindow = messages.recent(9).filter { it.id != userId }.takeLast(8).map { "${it.role}: ${it.content}" }
        val prompt = try {
            PromptBudget.fit(
                PromptBudget.Inputs(resolved, currentRecall, summaries.get(), factContext, turnWindow),
                contextSize - ANSWER_TOKENS,
                engine::countTokens
            ).prompt
        } catch (_: Exception) {
            return@withContext AgentReply("That question is too long for the available model context. Please shorten it and try again.")
        }
        val response = generateOrNull(prompt, ANSWER_TOKENS, null, onToken) ?: "Generation was stopped or failed."
        val assistantId = messages.append("assistant", response)
        indexRecall(assistantId, response)
        rollSummary()
        updateTopic(raw, emptyList())
        AgentReply(response)
    }

    suspend fun confirmPending(confirmationId: String, approved: Boolean): List<ActionResult> = withContext(dispatcher) {
        ensureOpen()
        val steps = pending.consume(confirmationId) ?: return@withContext emptyList()
        if (!approved) {
            return@withContext steps.map { step ->
                val result = ActionResult(step.tool, ActionStatus.CANCELLED, "Not approved; nothing was run.", step.args)
                actionLog.record(step.tool, step.tool, step.args.toString(), result.status.name, result.message)
                result
            }
        }
        val runtime = ToolRuntime(app, registry, validator)
        try {
            runtime.execute(steps, steps.map { it.tool }.toSet())
        } finally {
            runtime.close()
        }
    }

    suspend fun loadSelectedModel(): String = withContext(dispatcher) {
        ensureOpen()
        val file = ModelStore(app).selected() ?: return@withContext "No model is selected. Import a GGUF file first."
        if (loadedPath == file.absolutePath) return@withContext "Model is already loaded."
        unloadInternal()
        val requestedContext = DEFAULT_CONTEXT
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4).coerceAtMost(DEFAULT_THREADS)
        return@withContext try {
            engine.load(file.absolutePath, nCtx = requestedContext, nBatch = minOf(256, requestedContext), nUbatch = 128, threads = threads)
            loadedPath = file.absolutePath
            contextSize = requestedContext
            "Model loaded (${requestedContext}-token context, $threads threads)."
        } catch (e: Throwable) {
            loadedPath = null
            "Could not load model: ${e.message?.take(180) ?: "native loader error"}"
        }
    }

    suspend fun unload(): String = withContext(dispatcher) { unloadInternal(); "Model unloaded." }
    fun stop() { try { engine.cancel() } catch (_: Throwable) { } }
    fun status(): String = if (loadedPath == null) "No model loaded" else "Model loaded · context $contextSize"

    /** Runs a tiny prompt on the existing model queue without storing a chat turn or changing tools/state. */
    suspend fun runLoadedModelDiagnostic(): String = withContext(dispatcher) {
        ensureOpen()
        if (loadedPath == null) return@withContext "NOT_RUN: No model is loaded. Load one in Models to test local text generation."
        try {
            val prompt = "You are checking local text generation. Reply with one short word only.\nUser question: Say READY.\nAssistant:"
            val generated = engine.generate(prompt, maxTokens = 8).trim()
            if (generated.isBlank()) "FAIL: The loaded model returned no text. Check the selected GGUF file and native runtime."
            else "PASS: The already-loaded model generated text. Nothing was added to chat history or sent off-device."
        } catch (e: Throwable) {
            "FAIL: Local inference threw ${e.javaClass.simpleName}: ${e.message?.replace(Regex("[\\r\\n]+"), " ")?.take(120).orEmpty()}. Android may still reclaim the app under system memory pressure; try a smaller model if the OS terminates it."
        }
    }

    /** Checks fixed synthetic answers on the existing model queue without storing or logging prompts/outputs. */
    suspend fun runLoadedModelQaDiagnostic(): String = withContext(dispatcher) {
        ensureOpen()
        if (loadedPath == null || ModelStore(app).selected() == null) return@withContext "NOT_RUN: No model is loaded or selected for chat. Load a compatible GGUF model in Models and rerun the synthetic Q&A check."
        try {
            val turns = mutableListOf<String>()
            var matches = 0
            for (probe in SyntheticModelQa.probes) {
                val response = syntheticChatTurnLocked(probe.prompt, turns, {}).text
                if (SyntheticModelQa.responseContainsExpectedAnswer(response, probe.expectedAnswer)) matches++
                turns += "user: ${probe.prompt}"
                turns += "assistant: $response"
            }
            if (matches == SyntheticModelQa.probes.size) {
                "PASS: Expected answer content matched for ${SyntheticModelQa.probes.size} fixed synthetic chat turns."
            } else {
                "FAIL: Expected answer content did not match for one or more fixed synthetic chat turns."
            }
        } catch (error: Throwable) {
            "FAIL: Local synthetic Q&A generation failed (${error.javaClass.simpleName})."
        }
    }

    /** One disposable chat request; only synthetic turns supplied by the caller can enter its in-memory context. */
    internal suspend fun askSyntheticChat(
        text: String,
        recentSyntheticTurns: List<String> = emptyList(),
        onToken: (String) -> Unit = {}
    ): SyntheticChatTurnResult = withContext(dispatcher) {
        ensureOpen()
        syntheticChatTurnLocked(text, recentSyntheticTurns, onToken)
    }

    internal suspend fun syntheticChatModelAvailable(): Boolean = withContext(dispatcher) {
        loadedPath != null && ModelStore(app).selected() != null
    }

    private fun syntheticChatTurnLocked(text: String, turns: List<String>, onToken: (String) -> Unit): SyntheticChatTurnResult {
        val raw = text.trim()
        if (raw.isEmpty()) return SyntheticChatTurnResult("Please enter a message.", null, loadedPath != null)
        val rule = RuleEngine.match(raw)
        if (rule != null) {
            val reply = if (rule.isEmpty()) "I would not take that action. No action was executed."
                else "Synthetic route: ${rule.joinToString { it.tool }}. No action was executed or saved."
            return SyntheticChatTurnResult(reply, rule, loadedPath != null)
        }
        RuleEngine.clarification(raw)?.let { answer ->
            return SyntheticChatTurnResult(answer, emptyList(), loadedPath != null)
        }
        if (ModelStore(app).selected() == null || loadedPath == null) {
            return SyntheticChatTurnResult("No model is loaded. This synthetic chat turn was not run.", null, false)
        }
        val actionRequest = looksLikeAction(raw)
        val plan = createActionPlan(raw)
        if (plan != null) {
            val reply = "Synthetic route: ${plan.joinToString { it.tool }}. No action was executed or saved."
            return SyntheticChatTurnResult(reply, plan, true)
        }
        if (actionRequest) {
            return SyntheticChatTurnResult("I couldn't create a safe action plan for this synthetic request. Nothing was executed.", emptyList(), true)
        }
        val prompt = try {
            PromptBudget.fit(
                PromptBudget.Inputs(raw, emptyList(), null, emptyList(), turns.takeLast(8)),
                contextSize - ANSWER_TOKENS,
                engine::countTokens
            ).prompt
        } catch (_: Exception) {
            return SyntheticChatTurnResult("That question is too long for the available model context.", null, true)
        }
        val response = generateOrNull(prompt, ANSWER_TOKENS, null, onToken)
        return SyntheticChatTurnResult(response ?: "Generation was stopped or failed.", null, true)
    }

    /** Uses the same rule-first and local planner path as chat, but never writes synthetic turns or executes a plan. */
    internal suspend fun runFeatureRouteDiagnostic(probe: SyntheticFeatureRouteProbe): SyntheticFeatureRouteResult = withContext(dispatcher) {
        ensureOpen()
        try {
            val chatTurn = syntheticChatTurnLocked(probe.prompt, emptyList(), {})
            val planned = chatTurn.plan ?: return@withContext SyntheticFeatureRouteResult(
                if (chatTurn.modelLoaded) DiagnosticStatus.FAIL else DiagnosticStatus.NOT_RUN
            )
            val passed = SyntheticFeatureRouteQa.matches(probe, planned, validator)
            SyntheticFeatureRouteResult(if (passed) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                planned.singleOrNull()?.takeIf { passed })
        } catch (_: Throwable) {
            SyntheticFeatureRouteResult(DiagnosticStatus.FAIL)
        }
    }

    /** Executes only a risk-bearing synthetic QA step after the caller's separate in-app confirmation. */
    internal suspend fun runConfirmedDiagnosticAction(step: PlanStep, userApproved: Boolean): ActionResult = withContext(dispatcher) {
        ensureOpen()
        val spec = registry.get(step.tool)
        if (spec == null || spec.risk == RiskLevel.LOW) {
            return@withContext ActionResult(step.tool, ActionStatus.FAILED, "This confirmed-action tester accepts only registered state-changing tools.", step.args)
        }
        val runtime = ToolRuntime(app, registry, validator)
        try {
            runtime.execute(listOf(step), if (userApproved) setOf(step.tool) else emptySet()).single()
        } finally {
            runtime.close()
        }
    }

    /** Exact volume restore is a separate action and therefore has its own explicit confirmation. */
    internal suspend fun restoreDiagnosticVolumeIndex(index: Int, userApproved: Boolean): ActionResult = withContext(dispatcher) {
        ensureOpen()
        val runtime = ToolRuntime(app, registry, validator)
        try {
            runtime.restoreMediaVolumeIndex(index, userApproved)
        } finally {
            runtime.close()
        }
    }

    private data class ActionExecution(val results: List<ActionResult>, val confirmationId: String? = null)

    private fun executeOrHold(steps: List<PlanStep>): ActionExecution {
        val invalid = validator.validatePlan(steps)
        if (invalid != null) return ActionExecution(steps.map { ActionResult(it.tool, ActionStatus.FAILED, invalid, it.args) })
        val lowRisk = steps.filter { registry.get(it.tool)?.risk == RiskLevel.LOW }
        val needsApproval = steps.filter { registry.get(it.tool)?.risk != RiskLevel.LOW }
        val immediate = if (lowRisk.isEmpty()) emptyList() else ToolRuntime(app, registry, validator).execute(lowRisk)
        if (needsApproval.isEmpty()) return ActionExecution(immediate)
        val confirmationId = pending.issue(needsApproval)
        val waiting = needsApproval.map { step ->
            actionLog.record(step.tool, step.tool, step.args.toString(), ActionStatus.PENDING_CONFIRMATION.name, "Waiting for explicit user approval.")
            ActionResult(step.tool, ActionStatus.PENDING_CONFIRMATION, "Confirm before PocketMind runs this action.", step.args)
        }
        return ActionExecution(immediate + waiting, confirmationId)
    }

    private fun renderActions(results: List<ActionResult>): String {
        if (results.isEmpty()) return "No action was taken."
        return results.joinToString("\n") { result ->
            when (result.status) {
                ActionStatus.VERIFIED -> "${result.tool}: VERIFIED — ${result.message}"
                ActionStatus.HANDED_OFF -> "${result.tool}: ${result.message}"
                ActionStatus.PENDING_CONFIRMATION -> "${result.tool}: ${result.message}"
                ActionStatus.CANCELLED -> "${result.tool}: cancelled; nothing was run."
                ActionStatus.FAILED -> "${result.tool}: FAILED — ${result.message}"
            }
        }
    }

    private fun plannerPrompt(question: String, tools: List<ToolSpec>) = buildString {
        append("Return only JSON matching {\"steps\":[{\"tool\":string,\"args\":object}]}. Make an action plan only if the request clearly asks for an action; otherwise return {\"steps\":[]}. Use at most 8 steps and only listed tool ids and arguments. Never explain or claim completion.\nAvailable tools:\n")
        tools.forEach { spec ->
            append(spec.id).append(" (risk=").append(spec.risk).append("): ").append(spec.description)
            append("; args=").append(spec.args.joinToString { "${it.name}:${it.type.name}${if (it.required) " required" else " optional"}" }).append('\n')
        }
        append("User request: ").append(question).append("\nJSON:")
    }

    private fun createActionPlan(question: String, actionIntentText: String = question): List<PlanStep>? {
        val relevantTools = registry.relevant(question, 5)
        if (relevantTools.isEmpty() || !looksLikeAction(actionIntentText)) return null
        val planText = generateOrNull(plannerPrompt(question, relevantTools), 160, ACTION_GRAMMAR, {}) ?: return null
        val parsed = try { PlannerJson.parse(planText) } catch (_: Exception) { return null }
        return parsed.takeIf {
            it.isNotEmpty() && validator.validatePlan(it) == null &&
                it.all { step -> relevantTools.any { tool -> tool.id == step.tool } }
        }
    }

    private fun looksLikeAction(text: String): Boolean {
        val clean = text.trim()
        if (Regex("(?i)^(how (?:do|can) i|what does|why did|explain|tell me how)\\b").containsMatchIn(clean)) return false
        val verb = "(?:set|start|turn on|turn off|open|launch|change|save|create|adjust|enable|disable|switch|raise|lower|increase|decrease|turn up|turn down|search for|find|check|show|list)"
        return Regex("(?i)^(?:please\\s+)?$verb\\b").containsMatchIn(clean) ||
            Regex("(?i)\\b(?:can you|could you|please)\\s+$verb\\b").containsMatchIn(clean)
    }

    private fun generateOrNull(prompt: String, maxTokens: Int, grammar: String?, sink: (String) -> Unit): String? {
        if (loadedPath == null) return null
        return try {
            engine.generate(prompt, maxTokens, grammar, sink).trim().takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun rollSummary() {
        val window = messages.recent(16)
        if (window.size < 10) return
        val older = window.dropLast(8).joinToString(" ") { "${it.role}: ${it.content}" }
        val bounded = (listOfNotNull(summaries.get(), older).joinToString(" "))
            .split(Regex("\\s+")).filter { it.isNotBlank() }.takeLast(80).joinToString(" ")
        summaries.update(bounded)
    }

    private fun updateTopic(question: String, actions: List<ActionResult>) {
        if (actions.isEmpty()) TopicExtractor.extract(question)?.let { topics.set(it.entity, it.kind, question, System.currentTimeMillis()) }
    }

    private fun indexRecall(id: Long, content: String) {
        if (preferences.getBoolean("recall_enabled", true)) recall.index(id, content)
    }

    private fun capChars(values: List<String>, max: Int): List<String> {
        var remaining = max
        return values.mapNotNull { value -> value.take(remaining).also { remaining -= it.length }.takeIf { it.isNotEmpty() } }
    }

    private fun unloadInternal() {
        if (loadedPath != null) try { engine.unload() } catch (_: Throwable) { }
        loadedPath = null
        pending.clear()
    }

    private fun ensureOpen() = check(!closed.get()) { "Agent controller is closed" }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            pending.clear()
            stop()
            dispatcher.close()
            db.close()
        }
    }

    companion object {
        private const val DEFAULT_CONTEXT = 512
        private const val DEFAULT_THREADS = 2
        private const val ANSWER_TOKENS = 128
        private const val ACTION_GRAMMAR = "root ::= \"{\\\"steps\\\":[\" (step (\",\" step){0,7})? \"]}\"\nstep ::= \"{\\\"tool\\\":\\\"\" tool \"\\\",\\\"args\\\":\" object \"}\"\ntool ::= [a-zA-Z0-9_./-]+\nobject ::= \"{}\" | \"{\\\"[a-zA-Z_]+\\\":\" value (\",\\\"[a-zA-Z_]+\\\":\" value)* \"}\"\nvalue ::= string | number | \"true\" | \"false\"\nstring ::= \"\\\"[^\\\"\\\\]*\\\"\"\nnumber ::= [0-9]+"
    }
}
