package com.pocketmind.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test fun reportSummaryPrioritizesFailuresThenWarningsAndUnrunActions() {
        val failure = DiagnosticReport(0, "test device", "Android API 30", "test", listOf(
            DiagnosticCheck("failed", DiagnosticStatus.FAIL, "why"),
            DiagnosticCheck("warned", DiagnosticStatus.WARN, "why"),
            DiagnosticCheck("not run", DiagnosticStatus.NOT_RUN, "safe")
        ))
        assertEquals(1, failure.failures)
        assertEquals(1, failure.warnings)
        assertEquals(1, failure.notRun)
        assertTrue(failure.summary().startsWith("1 issue(s) failed"))

        val noFailures = failure.copy(checks = listOf(DiagnosticCheck("safe", DiagnosticStatus.PASS, "ok"),
            DiagnosticCheck("manual", DiagnosticStatus.NOT_RUN, "safe")))
        assertTrue(noFailures.summary().contains("1 check(s) remain not run"))
    }

    @Test fun shareTextHasDeviceChecksAndPrivacyNotice() {
        val report = DiagnosticReport(1_798_000_000_000L, "Redmi 9", "Android 11 (API 30)", "0.1.0", listOf(
            DiagnosticCheck("Offline permission policy", DiagnosticStatus.PASS, "No INTERNET permission.")
        ))
        val text = report.asShareText()
        assertTrue(text.contains("PocketMind diagnostic report"))
        assertTrue(text.contains("Redmi 9"))
        assertTrue(text.contains("[PASS] Offline permission policy"))
        assertTrue(text.contains("not sent automatically"))
        assertTrue(text.contains("Only explicitly confirmed device actions"))
        assertFalse(text.contains("conversation transcript"))
    }
}
