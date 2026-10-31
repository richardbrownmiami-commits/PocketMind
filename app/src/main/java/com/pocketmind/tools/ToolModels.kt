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

/** Exactly one model-selected invocation; the agent asks the model again after observing its result. */
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
            ToolSpec("math.calculate", "Evaluate basic arithmetic only.", listOf(ArgSpec("expression", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("note.save", "Save a note in PocketMind.", listOf(ArgSpec("text", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("note.list", "List saved PocketMind notes.", emptyList(), RiskLevel.LOW),
            ToolSpec("note.search", "Search saved PocketMind notes.", listOf(ArgSpec("query", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("fact.save", "Save an explicitly stated personal fact in local memory.", listOf(ArgSpec("text", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("fact.list", "Read personal facts the user has explicitly asked PocketMind to remember.", emptyList(), RiskLevel.LOW),
            ToolSpec("fact.forget", "Forget a matching saved personal fact.", listOf(ArgSpec("query", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("action.history", "Read recent recorded action history only when the user explicitly asks about past actions.", listOf(ArgSpec("query", ArgType.STRING, false), ArgSpec("limit", ArgType.INTEGER, false), ArgSpec("source", ArgType.STRING, false)), RiskLevel.LOW),
            ToolSpec("api.get", "Read-only public HTTPS API request; no credentials, custom headers, body, or redirects. JSON/YAML/text only, at most 32 KiB.", listOf(ArgSpec("url", ArgType.STRING, description = "Public HTTPS URL, at most 2048 characters.")), RiskLevel.LOW),
            ToolSpec("api.discover", "Search the public APIs.guru catalog for provider metadata and OpenAPI URLs; does not verify authentication or availability.", listOf(ArgSpec("query", ArgType.STRING), ArgSpec("limit", ArgType.INTEGER, false)), RiskLevel.LOW),
            ToolSpec("web.search", "Live web search only for an explicit search request or a request for current/latest information. Returns untrusted titles, snippets, and HTTPS source URLs.", listOf(ArgSpec("query", ArgType.STRING, description = "Search query, at most 240 characters."), ArgSpec("limit", ArgType.INTEGER, false)), RiskLevel.LOW),
            ToolSpec("alarm.set", "Ask Android Clock to set a local-time alarm. It cannot target a one-time calendar date or weekday.", listOf(ArgSpec("hour", ArgType.INTEGER), ArgSpec("minute", ArgType.INTEGER), ArgSpec("daysFromToday", ArgType.INTEGER, false), ArgSpec("date", ArgType.STRING, false, "ISO date; the current Clock intent cannot schedule a one-time date."), ArgSpec("label", ArgType.STRING, false)), RiskLevel.HIGH),
            ToolSpec("timer.set", "Ask Android Clock to start a timer, up to 24 hours.", listOf(ArgSpec("seconds", ArgType.INTEGER)), RiskLevel.MEDIUM),
            ToolSpec("flashlight", "Turn the device flashlight on or off.", listOf(ArgSpec("enabled", ArgType.BOOLEAN)), RiskLevel.MEDIUM),
            ToolSpec("volume", "Set media volume to a percentage or adjust it one step.", listOf(ArgSpec("level", ArgType.INTEGER, false), ArgSpec("step", ArgType.INTEGER, false)), RiskLevel.MEDIUM),
            ToolSpec("brightness", "Set display brightness from 0 to 255; may hand off to Android settings for permission.", listOf(ArgSpec("level", ArgType.INTEGER)), RiskLevel.HIGH),
            ToolSpec("battery/status", "Read battery, available RAM, and available storage.", emptyList(), RiskLevel.LOW),
            ToolSpec("app.open", "Open a launchable installed app only. Cannot send messages, type in apps, or complete in-app actions.", listOf(ArgSpec("package", ArgType.STRING)), RiskLevel.HIGH),
            ToolSpec("app.search", "Search installed launchable apps by visible name.", listOf(ArgSpec("query", ArgType.STRING)), RiskLevel.LOW),
            ToolSpec("wifi.settings", "Open Android Wi-Fi settings; user changes the setting there.", emptyList(), RiskLevel.MEDIUM),
            ToolSpec("bluetooth.settings", "Open Android Bluetooth settings; user changes the setting there.", emptyList(), RiskLevel.MEDIUM),
            ToolSpec("airplane.settings", "Open Android airplane-mode settings; user changes the setting there.", emptyList(), RiskLevel.MEDIUM),
            ToolSpec("location.settings", "Open Android location settings; user changes the setting there.", emptyList(), RiskLevel.MEDIUM)
        )
    }
}

/** Validates only model-produced call shape and tool-specific safety ranges; it never selects a tool. */
internal object ToolCallDecoder {
    fun error(call: ToolInvocation, registry: ToolRegistry): String? {
        val spec = registry.get(call.tool) ?: return "Unknown or unavailable tool."
        val definitions = spec.args.associateBy { it.name }
        call.args.keys.firstOrNull { it !in definitions }?.let { return "Argument not allowed: $it" }
        for (definition in spec.args) {
            if (!call.args.containsKey(definition.name)) {
                if (definition.required) return "Missing argument: ${definition.name}"
                continue
            }
            val value = call.args[definition.name]
            val valid = when (definition.type) {
                ArgType.STRING -> value is String && value.isNotBlank() && value.length <= 4000
                ArgType.NUMBER -> value is Number && value.toDouble().isFinite()
                ArgType.INTEGER -> value is Byte || value is Short || value is Int || value is Long
                ArgType.BOOLEAN -> value is Boolean
            }
            if (!valid) return "Invalid ${definition.type.name.lowercase()} argument: ${definition.name}"
        }

        fun integer(name: String): Long? = (call.args[name] as? Number)?.toLong()
        fun text(name: String): String? = call.args[name] as? String
        return when (call.tool) {
            "math.calculate" -> if (text("expression")!!.length > 500) "Expression is too long." else null
            "note.save" -> if (text("text")!!.length > 4000) "Note is too long." else null
            "note.search" -> if (text("query")!!.length > 200) "Note query is too long." else null
            "fact.save" -> if (text("text")!!.length > 240) "Fact text is too long." else null
            "fact.forget" -> if (text("query")!!.length > 200) "Fact query is too long." else null
            "alarm.set" -> when {
                integer("hour") !in 0L..23L -> "Alarm hour must be from 0 to 23."
                integer("minute") !in 0L..59L -> "Alarm minute must be from 0 to 59."
                integer("daysFromToday")?.let { it !in 0L..1L } == true -> "Alarm date is unsupported."
                text("label")?.length?.let { it > 80 } == true -> "Alarm label is too long."
                text("date")?.length?.let { it > 20 } == true -> "Alarm date is invalid."
                else -> null
            }
            "timer.set" -> if (integer("seconds") !in 1L..86_400L) "Timer length must be 1 to 86400 seconds." else null
            "action.history" -> when {
                integer("limit")?.let { it !in 1L..5L } == true -> "History limit must be from 1 to 5."
                text("source")?.lowercase()?.let { it !in setOf("all", "agent", "rules", "planner", "router", "legacy", "unknown") } == true -> "Unknown action-history source."
                text("query")?.length?.let { it > 200 } == true -> "History query is too long."
                else -> null
            }
            "api.get" -> when {
                text("url")!!.length > 2048 -> "API URL must be 2048 characters or fewer."
                !text("url")!!.startsWith("https://", ignoreCase = true) -> "API URL must use HTTPS."
                else -> null
            }
            "api.discover" -> when {
                text("query")!!.length > 160 -> "API discovery query must be 160 characters or fewer."
                integer("limit")?.let { it !in 1L..3L } == true -> "API discovery limit must be 1 to 3."
                else -> null
            }
            "web.search" -> when {
                text("query")!!.length > 240 -> "Search query must be 240 characters or fewer."
                integer("limit")?.let { it !in 1L..2L } == true -> "Search limit must be 1 or 2."
                else -> null
            }
            "volume" -> {
                val hasLevel = call.args.containsKey("level")
                val hasStep = call.args.containsKey("step")
                when {
                    hasLevel == hasStep -> "Provide exactly one of level or step."
                    hasLevel && integer("level") !in 0L..100L -> "Volume level must be from 0 to 100."
                    hasStep && integer("step") !in setOf(-1L, 1L) -> "Volume step must be -1 or 1."
                    else -> null
                }
            }
            "brightness" -> if (integer("level") !in 0L..255L) "Brightness must be from 0 to 255." else null
            "app.open" -> {
                val target = text("package")!!
                val isPackage = target.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
                val isAppName = target.matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} ._'-]{0,79}"))
                if (!isPackage && !isAppName) "Invalid installed-app name or package." else null
            }
            "app.search" -> if (text("query")!!.length > 120) "App search query is too long." else null
            else -> null
        }
    }
}

/** Narrow guard for calendar scheduling that Android's public alarm intent cannot represent. */
internal object AlarmCalendarDateGuard {
    private val unsupportedDate = Regex(
        listOf(
            "\\bday\\s+after\\s+tomorrow\\b",
            "\\btomorrow\\b",
            "\\b(?:next|this)\\s+(?:day|week|month|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            "\\b(?:on\\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            "\\b(?:in|after)\\s+(?:\\d+(?:\\.\\d+)?|[a-z]+(?:[-\\s]+[a-z]+)?)\\s+(?:days?|weeks?|months?)\\b",
            "\\b(?:on\\s+)?the\\s+\\d{1,2}(?:st|nd|rd|th)\\b",
            "\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b",
            "\\b\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?\\b",
            "\\b(?:on\\s+)?(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,\\s*\\d{4})?\\b",
            "\\b(?:on\\s+)?\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?(?:\\s+\\d{4})?\\b"
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )

    fun requestsUnsupportedDate(request: String, call: ToolInvocation): Boolean =
        !((call.args["date"] as? String).isNullOrBlank()) ||
            (call.args["daysFromToday"] as? Number)?.toInt()?.let { it > 0 } == true ||
            unsupportedDate.containsMatchIn(request)
}
