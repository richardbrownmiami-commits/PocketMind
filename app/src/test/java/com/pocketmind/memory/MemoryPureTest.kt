package com.pocketmind.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPureTest {
    private val topic = TopicState("Ada Lovelace", "person", "Who was Ada?", 1_000L)

    @Test fun rewritesPronounsAgainstActiveTopic() {
        assertEquals("Who was Ada Lovelace?", FollowUpResolver.resolve("Who was she?", topic))
        assertTrue(FollowUpResolver.resolve("Tell me more about her", topic)!!.contains("Ada Lovelace"))
        assertEquals("Why did Ada Lovelace buy Twitter?", FollowUpResolver.resolve("Why did he buy Twitter?", topic))
    }

    @Test fun leavesCommandsAndUncertainTextAlone() {
        assertNull(FollowUpResolver.resolve("search for her biography", topic))
        assertNull(FollowUpResolver.resolve("How is the weather today?", topic))
        assertNull(FollowUpResolver.resolve("What is 2 + 2?", null))
    }

    @Test fun topicExpiresAfterThirtyMinutes() {
        assertTrue(MemoryRules.topicIsActive(topic, topic.updated + 30 * 60 * 1000L))
        assertFalse(MemoryRules.topicIsActive(topic, topic.updated + 30 * 60 * 1000L + 1))
        assertFalse(MemoryRules.topicIsActive(topic, topic.updated - 1))
    }

    @Test fun factsAreExtractedOnlyFromExplicitTriggers() {
        assertEquals(FactCandidate("name", "Ravi"), MemoryRules.extractFact("My name is Ravi"))
        assertEquals(FactCandidate("likes", "mangoes"), MemoryRules.extractFact("I like mangoes."))
        assertEquals(FactCandidate("to call mom", "to call mom"), MemoryRules.extractFact("Remember to call mom"))
        assertNull(MemoryRules.extractFact("Ravi enjoys mangoes"))
    }

    @Test fun summaryIsBoundedAndRecallExcludesCurrentTurnAndCapsOutput() {
        val summary = MemoryRules.boundedSummary((1..150).joinToString(" ") { "word$it" }, 80)
        assertEquals(80, summary.split(Regex("\\s+")).size)
        val candidates = listOf(
            RecallSnippet(1, 7, "current " + "x".repeat(800), 1),
            RecallSnippet(2, 6, "first " + "a".repeat(400), 2),
            RecallSnippet(3, 5, "second " + "b".repeat(400), 3)
        )
        val capped = MemoryRules.capRecall(candidates, currentMessageId = 7)
        assertEquals(2, capped.size)
        assertTrue(capped.none { it.messageId == 7L })
        assertTrue(capped.sumOf { it.text.length } <= 700)
    }
}
