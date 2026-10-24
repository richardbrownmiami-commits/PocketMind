package com.pocketmind.diagnostics

import com.pocketmind.tools.ToolRegistry
import com.pocketmind.tools.ToolApprovalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticActionTestPolicyTest {
    @Test fun everyRegisteredToolStartsAsNotRunUntilItsOwnButtonIsConfirmed() {
        val registry = ToolRegistry()
        val checks = DiagnosticActionTestPolicy.initialChecks(registry)

        assertEquals(registry.all.map { "Device action · ${it.id} test" }.toSet(), checks.map { it.title }.toSet())
        assertEquals(checks.size, checks.map { it.title }.toSet().size)
        assertTrue(checks.all { it.status == DiagnosticStatus.NOT_RUN })
        checks.filterNot { it.title.endsWith("alarm.set test") || it.title.endsWith("timer.set test") }
            .forEach { assertTrue(it.details, it.details.contains("Start did not execute")) }
    }

    @Test fun timerIsUserAssistedWhileAlarmHasAReadbackAndCleanupPath() {
        assertFalse(DiagnosticActionTestPolicy.requiresUserAssistedVerification("alarm.set"))
        assertTrue(DiagnosticActionTestPolicy.requiresUserAssistedVerification("timer.set"))
        assertFalse(DiagnosticActionTestPolicy.requiresUserAssistedVerification("flashlight"))
        assertTrue(DiagnosticActionTestPolicy.initialChecks().single { it.title.endsWith("alarm.set test") }.details.contains("separately confirmed label-scoped dismissal"))
        assertTrue(DiagnosticActionTestPolicy.userAssistedReason("timer.set").contains("timer state remains user-assisted/NOT_RUN"))
        assertTrue(DiagnosticActionTestPolicy.userAssistedReason("timer.set").contains("Clock > Timer"))
    }

    @Test fun approvalPolicyMatchesEveryRegisteredToolRisk() {
        ToolRegistry().all.forEach { tool ->
            assertEquals(tool.id, tool.risk != com.pocketmind.tools.RiskLevel.LOW, ToolApprovalPolicy.requiresApproval(tool.risk))
        }
    }
}
