package com.pocketmind.core

import com.pocketmind.tools.PlanStep

/** Keeps failed action planning from silently falling through to ordinary chat. */
internal object ActionRequestPolicy {
    private const val ACTION_VERBS = "(?:set|start|run|turn on|turn off|turn|switch|open|launch|change|save|create|add|adjust|enable|disable|raise|lower|increase|decrease|mute|dim|brighten|search for|search|find|check|show|list|go to|take me to|remember to|calculate|compute)"

    const val NO_SAFE_PLAN_REPLY =
        "I couldn't safely create a valid action plan for that request. Nothing was run or changed. Please rephrase it with the action and any required details."

    fun looksLikeAction(text: String): Boolean {
        val clean = text.trim()
        if (Regex("(?i)^(how (?:do|can) i|what does|why did|explain|tell me how)\\b").containsMatchIn(clean)) return false
        if (Regex("(?i)^(?:please\\s+)?$ACTION_VERBS\\b").containsMatchIn(clean)) return true
        if (Regex("(?i)\\b(?:can you|could you|please)\\s+$ACTION_VERBS\\b").containsMatchIn(clean)) return true

        return Regex("(?i)^\\s*(?:please\\s+)?(?:turn|switch)\\s+(?:the\\s+)?[\\p{L}\\p{N} -]+\\s+(?:on|off)[?.!]*$").matches(clean) ||
            Regex("(?i)^\\s*(?:please\\s+)?make\\s+(?:the\\s+)?(?:(?:media|music)\\s+)?volume\\s+(?:louder|quieter|higher|lower)[?.!]*$").matches(clean) ||
            Regex("(?i)^\\s*(?:wake me(?: up)?|remind me)\\b").containsMatchIn(clean) ||
            Regex("(?i)\\b(?:i want you to|i need you to|i'd like you to|i want to|i need to|i'd like to|would you)\\s+$ACTION_VERBS\\b").containsMatchIn(clean) ||
            Regex("(?i)^\\s*(?:what(?:'s| is) the time|what time(?: is it)?|current time|tell me the time|today's date|what(?:'s| is) the date|what day is it)(?:\\b|[?.!])").containsMatchIn(clean) ||
            Regex("(?i)^\\s*(?:how much (?:battery|ram|storage)|battery (?:level|status|percentage)|what(?:'s| is) my battery|available ram|free storage|what notes? (?:have i saved|did i save))\\b").containsMatchIn(clean) ||
            Regex("(?i)^\\s*(?:what is|what's)\\s+[-+]?\\d[\\d.()\\s+*/%]*(?:plus|minus|times|divided by|multiplied by)?[\\d.()\\s+*/%]*[?.!]*$").matches(clean) ||
            Regex("(?i)^\\s*(?:set|start)\\s+(?:a\\s+)?timer\\b|^\\s*timer\\s+for\\b").containsMatchIn(clean) ||
            Regex("(?i)^\\s*(?:make|save|create)\\s+(?:a\\s+)?note\\b|^\\s*remember to\\b").containsMatchIn(clean)
    }

    /** Returns a clear no-action result only when an action request failed to produce a plan. */
    fun noPlanReply(actionRequest: Boolean, plan: List<PlanStep>?): String? =
        if (actionRequest && plan == null) NO_SAFE_PLAN_REPLY else null
}
