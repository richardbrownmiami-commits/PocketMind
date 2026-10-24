package com.pocketmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetTest {
    @Test fun budgetNeverExceedsContextAndPreservesCompleteQuestion() {
        val question = "What happened to it after the second meeting?"
        val count = { text: String -> text.split(Regex("\\s+")).count { it.isNotBlank() } }
        val result = PromptBudget.fit(
            PromptBudget.Inputs(question, listOf("old recall words ".repeat(20), "second recall"), "summary ".repeat(20),
                listOf("fact one ".repeat(10), "fact two"), (1..12).map { "turn$it content ".repeat(5) }),
            35, count
        )
        assertTrue(result.tokens <= 35)
        assertTrue(result.prompt.contains("User question: $question"))
        assertEquals(count(result.prompt), result.tokens)
        assertFalse(result.prompt.contains("turn1 content"))
    }

    @Test fun dropsMemoryInRequestedOrderAndKeepsRequiredPrompt() {
        val count = { text: String -> text.length }
        val result = PromptBudget.fit(PromptBudget.Inputs("Question?", listOf("recall"), "summary", listOf("fact"), listOf("turn")), 150, count)
        assertTrue(result.dropped.indexOf("recall") < result.dropped.indexOf("summary"))
        assertTrue(result.tokens <= 150)
        assertTrue(result.prompt.endsWith("User question: Question?\nAssistant:"))
    }

    @Test fun shortFollowUpDoesNotRewriteToolCommands() {
        assertTrue(TopicExtractor.shortFollowUp("What about it?"))
        assertFalse(TopicExtractor.shortFollowUp("set a timer for 5 minutes"))
        assertFalse(TopicExtractor.shortFollowUp("A completely unrelated sentence"))
        assertEquals("Ada Lovelace", TopicExtractor.extract("Tell me about Ada Lovelace")?.entity)
        assertNull(TopicExtractor.extract("open Settings"))
    }
}
