package com.pocketmind.tools

import android.content.Context

enum class RiskLevel { LOW, MEDIUM, HIGH }
enum class ActionStatus { VERIFIED, HANDED_OFF, FAILED }

data class ArgSpec(val name: String, val type: ArgType, val required: Boolean = true, val description: String = "")
enum class ArgType { STRING, NUMBER, INTEGER, BOOLEAN }
data class ToolSpec(val id: String, val description: String, val args: List<ArgSpec>, val risk: RiskLevel)
data class PlanStep(val tool: String, val args: Map<String, Any?>)
data class ActionResult(val tool: String, val status: ActionStatus, val message: String, val args: Map<String, Any?> = emptyMap())

class ToolRegistry(val all: List<ToolSpec> = defaults) {
    private val byId = all.associateBy { it.id }
    fun get(id: String): ToolSpec? = byId[id]
    fun relevant(query: String, limit: Int = 5): List<ToolSpec> {
        if (limit <= 0) return emptyList()
        val words = query.lowercase().split(Regex("[^a-z0-9]+") ).filter { it.length > 1 }.toSet()
        val ranked: List<Triple<Int, Int, ToolSpec>> = all.mapIndexedNotNull { index, spec ->
            val corpus = (spec.id.replace('.', ' ') + " " + spec.description + " " + spec.args.joinToString(" ") { it.name }).lowercase()
            val toks = corpus.split(Regex("[^a-z0-9]+") ).filter { it.length > 1 }.toSet()
            val score = words.fold(0) { total, w -> total + when { w in toks -> 3; toks.any { it.startsWith(w) || w.startsWith(it) } -> 1; else -> 0 } }
            if (score > 0) Triple(score, index, spec) else null
        }
        return ranked.sortedWith(compareByDescending<Triple<Int, Int, ToolSpec>> { it.first }.thenBy { it.second }).take(minOf(5, limit)).map { it.third }
    }
    companion object {
        val defaults = listOf(
            ToolSpec("clock.time", "Read the current local time", emptyList(), RiskLevel.LOW), ToolSpec("clock.date", "Read today's local date", emptyList(), RiskLevel.LOW),
            ToolSpec("math.calculate", "Evaluate basic arithmetic", listOf(ArgSpec("expression", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("note.save", "Save a note", listOf(ArgSpec("text", ArgType.STRING)), RiskLevel.LOW), ToolSpec("note.list", "List saved notes", emptyList(), RiskLevel.LOW), ToolSpec("note.search", "Search saved notes", listOf(ArgSpec("query", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("alarm.set", "Set an alarm", listOf(ArgSpec("hour", ArgType.INTEGER), ArgSpec("minute", ArgType.INTEGER), ArgSpec("daysFromToday", ArgType.INTEGER, false), ArgSpec("label", ArgType.STRING, false)), RiskLevel.HIGH),
            ToolSpec("timer.set", "Start a timer", listOf(ArgSpec("seconds", ArgType.INTEGER)), RiskLevel.MEDIUM),
            ToolSpec("flashlight", "Turn flashlight on or off", listOf(ArgSpec("enabled", ArgType.BOOLEAN)), RiskLevel.MEDIUM),
            ToolSpec("volume", "Set media volume to a percentage or adjust it one step", listOf(ArgSpec("level", ArgType.INTEGER, false), ArgSpec("step", ArgType.INTEGER, false)), RiskLevel.MEDIUM), ToolSpec("brightness", "Change display brightness", listOf(ArgSpec("level", ArgType.INTEGER)), RiskLevel.HIGH),
            ToolSpec("battery/status", "Read battery, RAM and storage status", emptyList(), RiskLevel.LOW), ToolSpec("app.open", "Open an installed application", listOf(ArgSpec("package", ArgType.STRING)), RiskLevel.HIGH), ToolSpec("app.search", "Search installed applications", listOf(ArgSpec("query", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("wifi.settings", "Open Wi-Fi settings", emptyList(), RiskLevel.MEDIUM), ToolSpec("bluetooth.settings", "Open Bluetooth settings", emptyList(), RiskLevel.MEDIUM), ToolSpec("airplane.settings", "Open airplane mode settings", emptyList(), RiskLevel.MEDIUM), ToolSpec("location.settings", "Open location settings", emptyList(), RiskLevel.MEDIUM)
        )
    }
}

class ToolValidator(private val registry: ToolRegistry = ToolRegistry()) {
    fun validate(step: PlanStep): String? {
        val spec = registry.get(step.tool) ?: return "Unknown tool: ${step.tool}"
        val byName = spec.args.associateBy { it.name }
        val extra = step.args.keys.firstOrNull { it !in byName }
        if (extra != null) return "Argument not allowed: $extra"
        for (arg in spec.args) {
            val v = step.args[arg.name]
            if (v == null) { if (arg.required) return "Missing argument: ${arg.name}"; continue }
            val ok = when (arg.type) {
                ArgType.STRING -> v is String && v.isNotBlank()
                ArgType.NUMBER -> v is Number && v.toDouble().isFinite()
                ArgType.INTEGER -> v is Byte || v is Short || v is Int || v is Long
                ArgType.BOOLEAN -> v is Boolean
            }
            if (!ok) return "Invalid ${arg.type.name.lowercase()} argument: ${arg.name}"
            if (v is String && v.length > 4000) return "Argument is too long: ${arg.name}"
        }
        if (step.tool == "alarm.set" && (((step.args["hour"] as Number).toLong() !in 0L..23L) || ((step.args["minute"] as Number).toLong() !in 0L..59L))) return "Alarm time out of range"
        if (step.tool == "alarm.set" && step.args["daysFromToday"] != null && (step.args["daysFromToday"] as Number).toLong() !in 0L..1L) return "Alarm date must be today or tomorrow"
        if (step.tool == "timer.set" && ((step.args["seconds"] as Number).toLong() !in 1L..86400L)) return "Timer seconds out of range"
        if (step.tool == "volume") {
            val hasLevel = step.args.containsKey("level")
            val hasStep = step.args.containsKey("step")
            if (hasLevel == hasStep) return "Provide exactly one of level or step"
            if (hasLevel && ((step.args["level"] as Number).toLong() !in 0L..100L)) return "Volume level out of range"
            if (hasStep && ((step.args["step"] as Number).toLong() !in -1L..1L || (step.args["step"] as Number).toLong() == 0L)) return "Volume step must be -1 or 1"
        }
        if (step.tool == "brightness" && ((step.args["level"] as Number).toLong() !in 0L..255L)) return "Brightness level out of range"
        if (step.tool == "app.open") {
            val target = step.args["package"] as String
            val isPackage = target.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
            val isAppName = target.matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} ._'-]{0,79}"))
            if (!isPackage && !isAppName) return "Invalid installed-app name or package"
        }
        return null
    }
    fun validatePlan(steps: List<PlanStep>): String? {
        if (steps.size > 8) return "Plan exceeds 8 steps"
        steps.forEach { validate(it)?.let { e -> return e } }
        return null
    }
}
