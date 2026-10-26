package com.pocketmind.memory

/** Pure helpers shared by the SQLite stores and app logic. */
data class FactCandidate(val key: String, val value: String)
data class MemoryNeeds(val facts: Boolean, val recall: Boolean)

object MemoryRules {
    private val factPatterns = listOf(
        Regex("(?i)^\\s*remember\\s+(?:that\\s+)?(.+?)[.!?]*\\s*$"),
        Regex("(?i)^\\s*my name is\\s+(.+?)[.!?]*\\s*$"),
        Regex("(?i)^\\s*i like\\s+(.+?)[.!?]*\\s*$")
    )
    private val factQuestion = Regex("(?i)\\b(?:what do you know about me|what do you remember about me|what is my|what's my|what are my|what do i like|what do i prefer|do you remember my|my name|my preferences|my likes|do i (?:like|prefer|have)|how old am i|where do i live|who am i|(?:what|which|who|where|when|how)\\b.{0,36}\\b(?:my|mine|our|i (?:like|prefer|have|am|live|work|want))\\b)\\b")
    private val recallQuestion = Regex("(?i)\\b(?:earlier|previous(?:ly)?|last time|before|what did i (?:say|ask|tell you)|what have i (?:said|asked|told you)|did i (?:say|ask|tell you)|we (?:discussed|talked about)|in our (?:chat|conversation)|from our (?:chat|conversation)|(?:when |do you remember )?i mentioned|you said|i said)\\b")

    /** Use explicit question intent to avoid searching long-term SQLite memory for every turn. */
    fun memoryNeeds(question: String): MemoryNeeds {
        val asksAboutFacts = factQuestion.containsMatchIn(question)
        return MemoryNeeds(facts = asksAboutFacts, recall = recallQuestion.containsMatchIn(question))
    }

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
