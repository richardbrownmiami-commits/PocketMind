package com.pocketmind.diagnostics

import com.pocketmind.tools.ToolRegistry

/** Keeps device-action execution opt-in and labels Android actions that cannot be safely rolled back. */
internal object DiagnosticActionTestPolicy {
    private val userAssistedReasons = mapOf(
        "timer.set" to "After the user confirms a real ACTION_SET_TIMER request, Android 11 exposes no reliable running-state read-back or unique cancellation handle to PocketMind. The action may have started, but its timer state remains user-assisted/NOT_RUN; check Clock > Timer and tap Stop/Cancel if it is running."
    )

    fun requiresUserAssistedVerification(toolId: String): Boolean = toolId in userAssistedReasons

    fun initialChecks(registry: ToolRegistry = ToolRegistry()): List<DiagnosticCheck> = registry.all.map { spec ->
        val details = when (spec.id) {
            "alarm.set" -> "NOT_RUN: Start did not execute this tool. Its optional real test asks for a user-selected future time, previews the exact labeled alarm, requires confirmation, reads AlarmManager's next-alarm time, and offers separately confirmed label-scoped dismissal; no alarm is created automatically."
            "timer.set" -> "NOT_RUN: Start did not execute this tool. Its optional real test asks for a user-selected duration and warns that the timer starts immediately; after confirmation it reports Android's lack of timer-state read-back and gives the exact Clock > Timer cleanup step."
            else -> "NOT_RUN: Start did not execute this tool. Tap its test button to ask the fixed synthetic request in Chat, review the exact tool and arguments, then explicitly confirm before any real runtime call."
        }
        DiagnosticCheck("Device action · ${spec.id} test", DiagnosticStatus.NOT_RUN, details)
    }

    fun userAssistedReason(toolId: String): String = userAssistedReasons[toolId]
        ?: "Android requires user-assisted verification for this portion."
}
