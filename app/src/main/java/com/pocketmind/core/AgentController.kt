package com.pocketmind.core

import android.content.Context
import com.pocketmind.engine.LlamaEngine
import com.pocketmind.engine.ModelStore
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
import com.pocketmind.tools.ToolRegistry
import com.pocketmind.tools.ToolRuntime
import com.pocketmind.tools.ToolSpec
import com.pocketmind.tools.ToolValidator
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class AgentReply(
    val text: String,
    val actions: List<ActionResult> = emptyList()
)

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
    private data class PendingClarification(val tool: String, val label: String? = null)
    private var pendingClarification: PendingClarification? = null
    private val closed = AtomicBoolean(false)

    suspend fun send(text: String, onToken: (String) -> Unit = {}): AgentReply = withContext(dispatcher) {
        ensureOpen()
        val raw = text.trim()
        if (raw.isEmpty()) return@withContext AgentReply("Please enter a message.")

        val clarificationIntent = pendingClarification
        if (clarificationIntent != null) {
            val followUpPlan = RuleEngine.clarificationFollowUpPlan(clarificationIntent.tool, raw, clarificationIntent.label)
            if (followUpPlan != null) {
                pendingClarification = null
                val userId = messages.append("user", raw)
                indexRecall(userId, raw)
                val execution = executeValidatedPlan(listOf(followUpPlan))
                val answer = renderActions(execution.results)
                val assistantId = messages.append("assistant", answer)
                indexRecall(assistantId, answer)
                return@withContext AgentReply(answer, execution.results)
            }
            if (RuleEngine.looksLikeClarificationFollowUp(clarificationIntent.tool, raw)) {
                messages.append("user", raw)
                val answer = if (clarificationIntent.tool == "alarm.set") {
                    "That time is invalid or incomplete. Please reply with a valid time including AM/PM, for example 6:30 pm."
                } else {
                    "That volume level is invalid. Please reply with a number from 0 to 100 percent."
                }
                messages.append("assistant", answer)
                return@withContext AgentReply(answer)
            }
            // A message unrelated to the requested argument is a new request, not a reason to hijack chat.
            pendingClarification = null
        }

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
            val execution = executeValidatedPlan(rule)
            val answer = renderActions(execution.results)
            val assistantId = messages.append("assistant", answer)
            indexRecall(assistantId, answer)
            updateTopic(raw, execution.results)
            return@withContext AgentReply(answer, execution.results)
        }

        val clarification = RuleEngine.clarification(raw)
        if (clarification != null) {
            val clarificationTarget = RuleEngine.clarificationTarget(raw)
            if (clarificationTarget != null) {
                val label = Regex("(?i)\\b(?:called|labelled|labeled)\\s+(.+?)\\s*$").find(raw)?.groupValues?.get(1)?.take(80)
                pendingClarification = PendingClarification(clarificationTarget, label)
            }
            messages.append("user", raw)
            messages.append("assistant", clarification)
            return@withContext AgentReply(clarification)
        }

        val actionRequest = ActionRequestPolicy.looksLikeAction(raw)
        val modelPath = ModelStore(app).selected()?.absolutePath
        if (modelPath == null || loadedPath == null) {
            messages.append("user", raw)
            val answer = if (actionRequest) {
                ActionRequestPolicy.noPlanReply(true, null) ?: ActionRequestPolicy.NO_SAFE_PLAN_REPLY
            } else {
                "No model is loaded. Choose a compatible GGUF model in Models to chat or plan actions."
            }
            if (actionRequest) recordPlanFailure(answer)
            messages.append("assistant", answer)
            return@withContext AgentReply(answer)
        }
        val userId = messages.append("user", raw)
        indexRecall(userId, raw)

        val plan = createActionPlan(resolved, raw)
        if (plan != null) {
            val execution = executeValidatedPlan(plan)
            val answer = renderActions(execution.results)
            val assistantId = messages.append("assistant", answer)
            indexRecall(assistantId, answer)
            updateTopic(raw, execution.results)
            return@withContext AgentReply(answer, execution.results)
        }
        val noPlanReply = ActionRequestPolicy.noPlanReply(actionRequest, plan)
        if (noPlanReply != null) {
            recordPlanFailure(noPlanReply)
            val assistantId = messages.append("assistant", noPlanReply)
            indexRecall(assistantId, noPlanReply)
            return@withContext AgentReply(noPlanReply)
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

    private data class ActionExecution(val results: List<ActionResult>)

    private fun executeValidatedPlan(steps: List<PlanStep>): ActionExecution {
        val invalid = validator.validatePlan(steps)
        if (invalid != null) {
            steps.forEach { step ->
                try { actionLog.record(step.tool, step.tool, step.args.toString(), ActionStatus.FAILED.name, invalid) } catch (_: Exception) { }
            }
            return ActionExecution(steps.map { ActionResult(it.tool, ActionStatus.FAILED, invalid, it.args) })
        }
        val runtime = ToolRuntime(app, registry, validator)
        return try { ActionExecution(runtime.execute(steps)) } finally { runtime.close() }
    }

    private fun renderActions(results: List<ActionResult>): String {
        if (results.isEmpty()) return "No action was taken."
        return results.joinToString("\n") { result ->
            when (result.status) {
                ActionStatus.VERIFIED -> "${result.tool}: VERIFIED — ${result.message}"
                ActionStatus.HANDED_OFF -> "${result.tool}: ${result.message}"
                ActionStatus.FAILED -> "${result.tool}: FAILED — ${result.message}"
            }
        }
    }

    private fun recordPlanFailure(message: String) {
        try { actionLog.record("action.plan", "action.plan", "{}", ActionStatus.FAILED.name, message) } catch (_: Exception) { }
    }

    private fun plannerPrompt(question: String, tools: List<ToolSpec>) = buildString {
        append("Return only JSON matching {\"steps\":[{\"tool\":string,\"args\":object}]}. Make an action plan only if the request clearly asks for an action; otherwise return {\"steps\":[]}. Use at most 8 steps and only listed tool ids and arguments. Never explain or claim completion.\nAvailable tools:\n")
        tools.forEach { spec ->
            append(spec.id).append(": ").append(spec.description)
            append("; args=").append(spec.args.joinToString { "${it.name}:${it.type.name}${if (it.required) " required" else " optional"}" }).append('\n')
        }
        append("User request: ").append(question).append("\nJSON:")
    }

    private fun createActionPlan(question: String, actionIntentText: String = question): List<PlanStep>? {
        val relevantTools = registry.relevant(question, 5)
        if (relevantTools.isEmpty() || !ActionRequestPolicy.looksLikeAction(actionIntentText)) return null
        val planText = generateOrNull(plannerPrompt(question, relevantTools), 160, ACTION_GRAMMAR, {}) ?: return null
        val parsed = try { PlannerJson.parse(planText) } catch (_: Exception) { return null }
        return parsed.takeIf {
            it.isNotEmpty() && validator.validatePlan(it) == null &&
                it.all { step -> relevantTools.any { tool -> tool.id == step.tool } }
        }
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
    }

    private fun ensureOpen() = check(!closed.get()) { "Agent controller is closed" }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
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
