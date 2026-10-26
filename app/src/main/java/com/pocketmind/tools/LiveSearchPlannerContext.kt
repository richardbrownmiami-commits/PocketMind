package com.pocketmind.tools

/**
 * Carries an explicit web-search request from the deterministic router to the
 * planner parser. This removes a fragile dependency on the local model emitting
 * valid tool JSON for a request that is already unambiguous.
 */
internal object LiveSearchPlannerContext {
    private var pendingQuery: String? = null

    @Synchronized
    fun setExplicitRequest(request: String) {
        pendingQuery = extractQuery(request)
    }

    @Synchronized
    fun takeQuery(): String? = pendingQuery.also { pendingQuery = null }

    private fun extractQuery(request: String): String {
        val clean = request.trim().trimEnd('.', '?', '!').trim()
        val afterFor = Regex("(?i)\\bfor\\s+(.+)$").find(clean)?.groupValues?.get(1)
        if (!afterFor.isNullOrBlank()) return afterFor.trim().take(240)

        return clean
            .replace(
                Regex("(?i)^\\s*(?:please\\s+)?(?:can|could|would)?\\s*(?:you\\s+)?(?:search|find|look\\s+up|look\\s+for)\\s+(?:(?:the|on|in)\\s+)?(?:web|internet|online)\\b"),
                ""
            )
            .replace(
                Regex("(?i)^\\s*(?:please\\s+)?(?:can|could|would)?\\s*(?:you\\s+)?(?:use|do)\\s+(?:the\\s+)?(?:web|internet|online)\\s+search\\b"),
                ""
            )
            .replace(Regex("(?i)^\\s*(?:web|internet|online)\\s+search(?:\\s+for)?\\b"), "")
            .trim()
            .take(240)
    }
}
