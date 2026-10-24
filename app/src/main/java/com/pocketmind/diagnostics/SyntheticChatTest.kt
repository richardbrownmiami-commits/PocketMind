package com.pocketmind.diagnostics

import com.pocketmind.tools.PlanStep

/** One transient chat response. The prompt and text live only in the chat-test Activity's memory. */
internal data class SyntheticChatTurnResult(
    val text: String,
    val plan: List<PlanStep>?,
    val modelLoaded: Boolean
)

/** Sanitized summary passed back to the Tester screen; it contains statuses and tool IDs only. */
data class SyntheticChatTestResults(
    val sessionStarted: Boolean,
    val modelLoaded: Boolean,
    val qaStatus: DiagnosticStatus,
    val routeStatuses: Map<String, DiagnosticStatus>,
    val completed: Boolean,
    val interrupted: Boolean = false,
    val actionRoutePlanJson: String? = null
) {
    companion object {
        fun notRun(toolIds: List<String>) = SyntheticChatTestResults(
            sessionStarted = false,
            modelLoaded = false,
            qaStatus = DiagnosticStatus.NOT_RUN,
            routeStatuses = toolIds.associateWith { DiagnosticStatus.NOT_RUN },
            completed = false
        )
    }
}

/** Intent contract between the Tester and the transient MainActivity chat session. */
internal object SyntheticChatTestContract {
    const val EXTRA_RUN = "com.pocketmind.qa.RUN_SYNTHETIC_CHAT"
    const val EXTRA_MODEL_LOADED = "com.pocketmind.qa.SYNTHETIC_CHAT_MODEL_LOADED"
    const val EXTRA_STARTED = "com.pocketmind.qa.SYNTHETIC_CHAT_STARTED"
    const val EXTRA_QA_STATUS = "com.pocketmind.qa.SYNTHETIC_CHAT_QA_STATUS"
    const val EXTRA_ROUTE_IDS = "com.pocketmind.qa.SYNTHETIC_CHAT_ROUTE_IDS"
    const val EXTRA_ROUTE_STATUSES = "com.pocketmind.qa.SYNTHETIC_CHAT_ROUTE_STATUSES"
    const val EXTRA_COMPLETED = "com.pocketmind.qa.SYNTHETIC_CHAT_COMPLETED"
    const val EXTRA_INTERRUPTED = "com.pocketmind.qa.SYNTHETIC_CHAT_INTERRUPTED"
    const val EXTRA_ACTION_ROUTE_TOOL = "com.pocketmind.qa.ACTION_ROUTE_TOOL"
    const val EXTRA_ACTION_ROUTE_PROMPT = "com.pocketmind.qa.ACTION_ROUTE_PROMPT"
    const val EXTRA_ACTION_ROUTE_PLAN_JSON = "com.pocketmind.qa.ACTION_ROUTE_PLAN_JSON"
}
