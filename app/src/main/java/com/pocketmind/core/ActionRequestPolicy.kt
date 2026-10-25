package com.pocketmind.core

import com.pocketmind.tools.PlanStep

/** Keeps failed action planning from silently falling through to ordinary chat. */
internal object ActionRequestPolicy {
    private const val ACTION_VERBS = "(?:set|start|run|turn on|turn off|turn|switch|toggle|open|launch|bring up|pull up|go to|take me to|change|adjust|put|save|store|write|create|add|take|enable|disable|activate|deactivate|raise|lower|increase|decrease|reduce|boost|mute|silence|dim|brighten|search for|search|find|look for|look up|check|list|show|display|read|tell me|get|remember to|calculate|compute|work out|solve|sum|subtract|multiply|divide)"
    private const val TOOL_TARGET = "(?:alarm|wake me|timer|countdown|flashlight|torch|(?:media|music|audio )?volume|(?:screen|display )?brightness|battery|ram|storage|device status|system status|app(?:lication)?s?|notes?|memos?|wi-?fi|wireless|bluetooth|airplane(?: mode)?|flight mode|location|gps|time|date|actions?|action history|action log|tool calls?)"

    const val NO_SAFE_PLAN_REPLY =
        "I couldn't safely create a valid action plan for that request. Nothing was run or changed. Please rephrase it with the action and any required details."

    private val historyRequestPatterns = listOf(
        Regex("""^(?:list|show|display|read|get|check)(?:\s+me)?\s+(?:(?:a\s+)?list\s+of\s+)?(?:(?:the|all|my|recent|previous|past|recorded)\s+)*(?:action\s+history|action\s+log|tool\s+calls?|actions?).*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:what|which)\s+actions?\s+(?:have you\s+(?:done|taken|run|performed)|did you\s+(?:do|take|run|perform)).*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:is|are|was|were)\s+(?:anything|something|some|any\s+actions?)\s+(?:actions?\s+)?(?:done|performed|executed)|(?:has|have)\s+(?:anything|something|some|any\s+actions?)\s+been\s+(?:done|performed|executed)).*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:search|find)\s+(?:my\s+)?(?:actions?|action\s+history|action\s+log|tool\s+calls?)\s+(?:for|about|matching)\s+.+$""", RegexOption.IGNORE_CASE),
        Regex("""^why\s+did\s+you\s+(?:do\s+that|set|start|turn\s+on|turn\s+off|open|change)\b.*$""", RegexOption.IGNORE_CASE)
    )

    /** History is a model-planned tool call only for an explicit request, never ambient chat context. */
    fun isActionHistoryRequest(text: String): Boolean {
        val clean = text.trim().trimEnd('.', '?', '!').trim()
        return clean.isNotEmpty() && historyRequestPatterns.any { it.matches(clean) }
    }

    private val liveFreshnessPattern = Regex(
        """\b(?:latest|newest|current(?:ly)?|right\s+now|now|today|tonight|tomorrow|this\s+(?:week|month|year|weekend)|next\s+(?:week|month|year|weekend|quarter)|recent(?:ly)?|trending|tranding|viral|upcoming|future|breaking|live|forecast|scheduled)\b""",
        RegexOption.IGNORE_CASE
    )
    private val liveLookupStartPattern = Regex(
        """^(?:(?:what|who|when|where|which|why|how|is|are|was|were|will|has|have|do|does|did|can|could)\b|(?:tell\s+me|show\s+me|find|search(?:\s+for)?|look\s+up|give\s+me|list|get|check|latest|newest|current|recent|upcoming|trending|tranding|future|forecast)\b)""",
        RegexOption.IGNORE_CASE
    )
    private val selfDirectedChatPattern = Regex(
        """^(?:how\s+are\s+you\b|are\s+you\s+(?:currently\s+)?(?:doing|feeling|there|available)\b|what\s+are\s+you\s+(?:doing|up\s+to)\b)""",
        RegexOption.IGNORE_CASE
    )

    /** Live web access requires an explicit freshness-oriented lookup, never ambient chat. */
    fun isLiveSearchRequest(text: String): Boolean {
        val clean = text.trim().trimEnd('.', '?', '!').trim()
        if (clean.isEmpty() || selfDirectedChatPattern.containsMatchIn(clean)) return false
        val asksForInformation = liveLookupStartPattern.containsMatchIn(clean) || text.trim().endsWith('?')
        return asksForInformation && liveFreshnessPattern.containsMatchIn(clean)
    }

    fun looksLikeAction(text: String, ruleMatched: Boolean = false): Boolean {
        if (ruleMatched) return false
        val clean = text.trim()
        if (Regex("(?i)^(?:how (?:do|can|would) i|what if|why did|why should(?:n't| not)|should i|can i|could i|is it possible|explain|tell me how|show me how)\\b").containsMatchIn(clean)) return false
        if (Regex("(?i)^\\s*(?:i\\s+(?:don't|do not|dont)\\s+(?:want|need)|(?:don't|do not|dont|never)|cancel)\\b").containsMatchIn(clean)) return false
        if (Regex("(?i)^\\s*(?:tell|show|get|read)\\s+(?:me\\s+)?(?:about|how|what|why|whether|if)\\b").containsMatchIn(clean)) return false
        if (Regex("(?i)^\\s*(?:set|change|adjust|dim|brighten)\\s+(?:the\\s+)?(?:screen|display)\\b").containsMatchIn(clean)) return true

        val direct = Regex("(?i)^\\s*(?:please\\s+)?$ACTION_VERBS\\b").containsMatchIn(clean) ||
            Regex("(?i)^\\s*(?:can|could|would|will) you\\s+(?:please\\s+)?$ACTION_VERBS\\b").containsMatchIn(clean) ||
            Regex("(?i)^\\s*(?:i want you to|i need you to|i'd like you to|i would like you to|i want to|i need to|i'd like to)\\s+$ACTION_VERBS\\b").containsMatchIn(clean)
        if (direct && Regex("(?i)\\b$TOOL_TARGET\\b").containsMatchIn(clean)) return true

        return Regex("(?i)^\\s*(?:$TOOL_TARGET)\\s+(?:on|off|up|down|louder|quieter|higher|lower|maximum|max|minimum|min|status|level|percent|percentage|settings?)\\b").matches(clean) ||
            Regex("(?i)^\\s*(?:what(?:'s| is) the time|what time(?: is it)?|current time|tell me (?:the )?time|check (?:the )?time|today's date|what(?:'s| is) the date|what day is it|tell me (?:today's |current )?date)[?.!]*\\s*$").matches(clean) ||
            Regex("(?i)^\\s*(?:how much (?:battery|ram|storage)|battery (?:level|status|percentage|percent|life)|(?:remaining|low|left) battery|what(?:'s| is) my battery|available ram|free storage|show (?:me )?(?:battery(?: status| percentage| percent| level)?|device status|system status|ram|storage)|check (?:my )?(?:battery|device status|system status|ram|storage)|what notes? (?:have i saved|did i save))\\b").containsMatchIn(clean) ||
            Regex("(?i)^\\s*(?:what is|what's|how much is)\\s+[-+]?\\d[\\d.()\\s+*/%×÷-]*(?:plus|minus|times|divided by|multiplied by)?[\\d.()\\s+*/%×÷-]*[?.!]*$").matches(clean) ||
            Regex("(?i)^\\s*(?:what does\\s+[-+]?\\d[\\d.()\\s+*/%×÷xX-]*(?:plus|minus|times|divided by|multiplied by)?[\\d.()\\s+*/%×÷xX-]*\\s+equal|(?:calculate|compute|work out|solve)\\b)").containsMatchIn(clean) ||
            Regex("(?i)^\\s*(?:add|sum|subtract|multiply|divide)\\s+(?:[-+]?\\d|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|twenty|thirty|forty|fifty|sixty)\\b").containsMatchIn(clean) ||
            Regex("(?i)^\\s*(?:wake me(?: up)?|remind me)\\b").containsMatchIn(clean) ||
            Regex("(?i)^\\s*(?:set|start|run)\\s+(?:(?:a|the|my)\\s+)?(?:timer|countdown)\\b|^\\s*(?:(?:a|the|my)\\s+)?(?:timer|countdown)\\b").containsMatchIn(clean) ||
            Regex("(?i)^\\s*(?:remember to|(?:save|store|write|create|add|take|jot down)\\s+(?:a\\s+)?(?:note|memo)|write down|note down)\\b").containsMatchIn(clean)
    }

    /** Returns a clear no-action result only when an action request failed to produce a plan. */
    fun noPlanReply(actionRequest: Boolean, plan: List<PlanStep>?): String? =
        if (actionRequest && plan == null) NO_SAFE_PLAN_REPLY else null
}
