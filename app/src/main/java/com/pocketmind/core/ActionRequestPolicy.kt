package com.pocketmind.core

import com.pocketmind.tools.PlanStep

/** Keeps failed action planning from silently falling through to ordinary chat. */
internal object ActionRequestPolicy {
    private const val ACTION_VERBS = "(?:set|start|turn on|turn off|open|launch|change|save|create|adjust|enable|disable|switch|raise|lower|increase|decrease|turn up|turn down|search for|find|check|show|list)"

    const val NO_SAFE_PLAN_REPLY =
        "I couldn't safely create a valid action plan for that request. Nothing was run or saved. Please rephrase it with the action and any required details."

    fun looksLikeAction(text: String): Boolean {
        val clean = text.trim()
        if (Regex("(?i)^(how (?:do|can) i|what does|why did|explain|tell me how)\\b").containsMatchIn(clean)) return false
        return Regex("(?i)^(?:please\\s+)?$ACTION_VERBS\\b").containsMatchIn(clean) ||
            Regex("(?i)\\b(?:can you|could you|please)\\s+$ACTION_VERBS\\b").containsMatchIn(clean)
    }

    /** Returns a clear no-action result only when an action request failed to produce a plan. */
    fun noPlanReply(actionRequest: Boolean, plan: List<PlanStep>?): String? =
        if (actionRequest && plan == null) NO_SAFE_PLAN_REPLY else null
}
