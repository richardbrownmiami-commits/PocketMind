package com.pocketmind.tools

enum class RiskLevel { LOW, MEDIUM, HIGH }
enum class ActionStatus { VERIFIED, HANDED_OFF, FAILED }

data class ArgSpec(
    val name: String,
    val type: ArgType,
    val required: Boolean = true,
    val description: String = ""
)

enum class ArgType { STRING, NUMBER, INTEGER, BOOLEAN }

data class ToolSpec(
    val id: String,
    val description: String,
    val args: List<ArgSpec>,
    val risk: RiskLevel
)

/** One tool chosen by the model after it emits a structured decision. */
data class ToolInvocation(val tool: String, val args: Map<String, Any?>)

data class ActionResult(
    val tool: String,
    val status: ActionStatus,
    val message: String,
    val args: Map<String, Any?> = emptyMap()
)

class ToolRegistry(val all: List<ToolSpec> = defaults) {
    private val byId = all.associateBy { it.id }
    fun get(id: String): ToolSpec? = byId[id]

    companion object {
        val defaults = listOf(
            ToolSpec("clock.time", "Read the current local time.", emptyList(), RiskLevel.LOW),
            ToolSpec("clock.date", "Read today's local date.", emptyList(), RiskLevel.LOW),
            ToolSpec("math.calculate", "Evaluate a basic arithmetic expression.", listOf(ArgSpec("expression", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("note.save", "Save a note in PocketMind.", listOf(ArgSpec("text", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("note.list", "List saved PocketMind notes.", emptyList(), RiskLevel.LOW),
            ToolSpec("note.search", "Search saved PocketMind notes.", listOf(ArgSpec("query", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("fact.save", "Save a personal fact in local memory.", listOf(ArgSpec("text", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("fact.list", "Read saved personal facts.", emptyList(), RiskLevel.LOW),
            ToolSpec("fact.forget", "Forget a matching saved personal fact.", listOf(ArgSpec("query", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("action.history", "Read recent recorded action history.", listOf(ArgSpec("query", ArgType.STRING, false), ArgSpec("limit", ArgType.INTEGER, false), ArgSpec("source", ArgType.STRING, false)), RiskLevel.LOW),
            ToolSpec("api.get", "Read a public API using a read-only request; no credentials, custom headers, body, or redirects. JSON/YAML/text only.", listOf(ArgSpec("url", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("api.discover", "Search the public APIs.guru catalog for provider metadata and OpenAPI URLs.", listOf(ArgSpec("query", ArgType.STRING), ArgSpec("limit", ArgType.INTEGER, false)), RiskLevel.LOW),
            ToolSpec("web.search", "Search the live web. Returns untrusted titles, snippets, and HTTPS source URLs.", listOf(ArgSpec("query", ArgType.STRING), ArgSpec("limit", ArgType.INTEGER, false)), RiskLevel.LOW),
            ToolSpec("alarm.set", "Ask Android Clock to set an alarm at a local hour and minute.", listOf(ArgSpec("hour", ArgType.INTEGER), ArgSpec("minute", ArgType.INTEGER), ArgSpec("label", ArgType.STRING, false)), RiskLevel.HIGH),
            ToolSpec("timer.set", "Ask Android Clock to start a timer.", listOf(ArgSpec("seconds", ArgType.INTEGER)), RiskLevel.MEDIUM),
            ToolSpec("flashlight", "Turn the device flashlight on or off.", listOf(ArgSpec("enabled", ArgType.BOOLEAN)), RiskLevel.MEDIUM),
            ToolSpec("volume", "Set or adjust media volume.", listOf(ArgSpec("level", ArgType.INTEGER, false), ArgSpec("step", ArgType.INTEGER, false)), RiskLevel.MEDIUM),
            ToolSpec("brightness", "Set display brightness; may hand off to Android settings for permission.", listOf(ArgSpec("level", ArgType.INTEGER)), RiskLevel.HIGH),
            ToolSpec("battery/status", "Read battery, available RAM, and available storage.", emptyList(), RiskLevel.LOW),
            ToolSpec("app.open", "Open a launchable installed app; this cannot perform actions inside the app.", listOf(ArgSpec("package", ArgType.STRING)), RiskLevel.HIGH),
            ToolSpec("app.search", "Search installed launchable apps by visible name.", listOf(ArgSpec("query", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("wifi.settings", "Open Android Wi-Fi settings; the user changes the setting there.", emptyList(), RiskLevel.MEDIUM),
            ToolSpec("bluetooth.settings", "Open Android Bluetooth settings; the user changes the setting there.", emptyList(), RiskLevel.MEDIUM),
            ToolSpec("airplane.settings", "Open Android airplane-mode settings; the user changes the setting there.", emptyList(), RiskLevel.MEDIUM),
            ToolSpec("location.settings", "Open Android location settings; the user changes the setting there.", emptyList(), RiskLevel.MEDIUM)
        )
    }
}

/** Structural safety only: known tool, declared keys, required fields, and JSON primitive types. */
internal object ToolInvocationShape {
    fun structuralError(call: ToolInvocation, registry: ToolRegistry): String? {
        val spec = registry.get(call.tool) ?: return "Unknown tool."
        val definitions = spec.args.associateBy { it.name }
        if (call.args.keys.any { it !in definitions }) return "Unexpected tool argument."
        for (definition in spec.args) {
            if (!call.args.containsKey(definition.name)) {
                if (definition.required) return "Missing tool argument."
                continue
            }
            val value = call.args[definition.name]
            val validType = when (definition.type) {
                ArgType.STRING -> value is String
                ArgType.NUMBER -> value is Number && value.toDouble().isFinite()
                ArgType.INTEGER -> value is Byte || value is Short || value is Int || value is Long
                ArgType.BOOLEAN -> value is Boolean
            }
            if (!validType) return "Tool argument has the wrong JSON type."
        }
        return null
    }
}
