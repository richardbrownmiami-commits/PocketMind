package com.pocketmind.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolExecutionRetryPolicyTest {
    @Test fun stateChangingAndMutatingActionsAreNeverAutomaticallyRetried() {
        listOf(
            "alarm.set", "timer.set", "flashlight", "volume", "brightness", "app.open",
            "wifi.settings", "bluetooth.settings", "airplane.settings", "location.settings", "note.save"
        ).forEach { tool ->
            assertEquals("$tool must be one-shot", 1, ToolExecutionRetryPolicy.maximumAttempts(PlanStep(tool, emptyMap())))
        }
    }

    @Test fun readOnlyIdempotentToolsKeepBoundedRetries() {
        listOf("clock.time", "clock.date", "math.calculate", "note.list", "note.search", "battery/status", "app.search")
            .forEach { tool ->
                assertEquals("$tool may retry safely", 3, ToolExecutionRetryPolicy.maximumAttempts(PlanStep(tool, emptyMap())))
            }
    }
}
