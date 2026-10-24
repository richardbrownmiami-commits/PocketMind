package com.pocketmind.memory

/** Pure helpers shared by the SQLite stores and JVM tests. */
data class FactCandidate(val key: String, val value: String)

object MemoryRules {
    private val factPatterns = listOf(
        Regex("(?i)^\\s*remember\\s+(?:that\\s+)?(.+?)[.!?]*\\s*$"),
        Regex("(?i)^\\s*my name is\\s+(.+?)[.!?]*\\s*$"),
        Regex("(?i)^\\s*i like\\s+(.+?)[.!?]*\\s*$")
    )

    fun extractFact(text: String): FactCandidate? {
        val match = factPatterns.firstNotNullOfOrNull { it.find(text) } ?: return null
        val value = match.groupValues[1].trim().take(240)
        if (value.isBlank()) return null
        val key = when {
            text.trim().startsWith("my name", ignoreCase = true) -> "name"
            text.trim().startsWith("i like", ignoreCase = true) -> "likes"
            else -> value.lowercase().take(100)
        }
        return FactCandidate(key, value)
    }

    fun topicIsActive(topic: TopicState, nowMillis: Long, expiryMillis: Long = 30 * 60 * 1000L): Boolean =
        nowMillis >= topic.updated && nowMillis - topic.updated <= expiryMillis

    fun boundedSummary(text: String, maxWords: Int = 80): String =
        text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.takeLast(maxWords.coerceAtLeast(0)).joinToString(" ")

    fun capRecall(
        input: List<RecallSnippet>,
        currentMessageId: Long?,
        maxSnippets: Int = 2,
        maxChars: Int = 700
    ): List<RecallSnippet> {
        var remaining = maxChars.coerceAtLeast(0)
        return input.asSequence()
            .filter { currentMessageId == null || it.messageId != currentMessageId }
            .take(maxSnippets.coerceAtLeast(0))
            .mapNotNull { snippet ->
                val body = snippet.text.take(remaining)
                remaining -= body.length
                body.takeIf { it.isNotEmpty() }?.let { snippet.copy(text = it) }
            }
            .toList()
    }
}
