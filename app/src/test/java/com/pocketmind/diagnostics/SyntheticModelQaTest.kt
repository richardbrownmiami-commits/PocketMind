package com.pocketmind.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticModelQaTest {
    @Test fun probeSetIsSmallAndSynthetic() {
        assertEquals(2, SyntheticModelQa.probes.size)
        assertTrue(SyntheticModelQa.probes.all { it.prompt.isNotBlank() && it.prompt.length < 100 })
        assertTrue(SyntheticModelQa.probes.any { it.expectedAnswer.equals("green", true) })
    }

    @Test fun expectedAnswerMustAppearAsAWholeToken() {
        assertTrue(SyntheticModelQa.responseContainsExpectedAnswer("42", "42"))
        assertTrue(SyntheticModelQa.responseContainsExpectedAnswer("The answer is 42.", "42"))
        assertFalse(SyntheticModelQa.responseContainsExpectedAnswer("The answer is 142.", "42"))
        assertTrue(SyntheticModelQa.responseContainsExpectedAnswer("Paris, France", "Paris"))
        assertTrue(SyntheticModelQa.responseContainsExpectedAnswer("Grass is green.", "green"))
        assertFalse(SyntheticModelQa.responseContainsExpectedAnswer("The capital is London.", "Paris"))
        assertFalse(SyntheticModelQa.responseContainsExpectedAnswer("  ", "Paris"))
    }

    @Test fun missingModelIsNotRunAndSyntheticContentStaysOutOfReport() {
        val notRun = SyntheticModelQa.asDiagnosticCheck("NOT_RUN: No model is loaded.")
        assertEquals(DiagnosticStatus.NOT_RUN, notRun.status)
        assertTrue(notRun.details.contains("No model is loaded"))
        assertFalse(notRun.details.contains("42"))
        assertFalse(notRun.details.contains("Paris"))

        val passed = SyntheticModelQa.asDiagnosticCheck("PASS: Expected content matched.")
        assertEquals(DiagnosticStatus.PASS, passed.status)
        assertFalse(passed.details.contains("42"))
        assertFalse(passed.details.contains("Paris"))
    }
}
