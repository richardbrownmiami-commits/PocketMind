package com.pocketmind.diagnostics

import com.pocketmind.tools.RiskLevel
import com.pocketmind.tools.ToolRegistry
import com.pocketmind.tools.ToolValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticToolCoverageTest {
    private val samples = mapOf(
        "clock.time" to emptyMap(),
        "clock.date" to emptyMap(),
        "math.calculate" to mapOf("expression" to "2 + 3 * 4"),
        "note.save" to mapOf("text" to "diagnostic sample"),
        "note.list" to emptyMap(),
        "note.search" to mapOf("query" to "diagnostic"),
        "alarm.set" to mapOf("hour" to 7, "minute" to 0, "label" to "diagnostic"),
        "timer.set" to mapOf("seconds" to 1),
        "flashlight" to mapOf("enabled" to true),
        "volume" to mapOf("level" to 50),
        "brightness" to mapOf("level" to 128),
        "battery/status" to emptyMap(),
        "app.open" to mapOf("package" to "com.pocketmind"),
        "app.search" to mapOf("query" to "PocketMind"),
        "wifi.settings" to emptyMap(),
        "bluetooth.settings" to emptyMap(),
        "airplane.settings" to emptyMap(),
        "location.settings" to emptyMap()
    )

    private val safeRuntimeTools = setOf(
        "clock.time", "clock.date", "math.calculate", "note.save", "note.list", "note.search",
        "battery/status", "app.search"
    )

    @Test fun everyRegisteredToolHasAnAcceptedQaInput() {
        val registry = ToolRegistry()
        assertEquals("Add an isolated QA fixture for every registered tool", registry.all.map { it.id }.toSet(), samples.keys)
        val validator = ToolValidator(registry)
        registry.all.forEach { spec ->
            assertNull("${spec.id} sample must pass its schema", validator.validate(com.pocketmind.tools.PlanStep(spec.id, samples.getValue(spec.id))))
        }
    }

    @Test fun allToolsHaveEitherAnIsolatedSafeRunOrAnApprovalGate() {
        val registry = ToolRegistry()
        val safe = registry.all.filter { it.risk == RiskLevel.LOW }.map { it.id }.toSet()
        val gated = registry.all.filter { it.risk != RiskLevel.LOW }.map { it.id }.toSet()
        assertEquals("Every low-risk tool gets a safe runtime test", safeRuntimeTools, safe)
        assertEquals("Every non-low-risk tool must stop at the explicit approval gate", registry.all.map { it.id }.toSet() - safeRuntimeTools, gated)
    }
}
