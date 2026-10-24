package com.pocketmind.diagnostics

import com.pocketmind.tools.RuleEngine
import com.pocketmind.tools.ToolRegistry
import com.pocketmind.tools.ToolValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticFeatureRouteQaTest {
    @Test fun everyRegisteredFeatureHasOneSyntheticRouteProbe() {
        val toolIds = ToolRegistry().all.map { it.id }.toSet()
        assertEquals(toolIds, SyntheticFeatureRouteQa.probes.map { it.toolId }.toSet())
        assertEquals(SyntheticFeatureRouteQa.probes.size, SyntheticFeatureRouteQa.probes.map { it.toolId }.toSet().size)
    }

    @Test fun deterministicFeaturePromptsRouteToValidExpectedPlansWithoutExecutingThem() {
        val validator = ToolValidator()
        SyntheticFeatureRouteQa.probes.forEach { probe ->
            val steps = RuleEngine.match(probe.prompt)
            if (steps != null) assertTrue("${probe.toolId} route", SyntheticFeatureRouteQa.matches(probe, steps, validator))
        }
        val volume = SyntheticFeatureRouteQa.probes.single { it.toolId == "volume" }
        assertTrue(SyntheticFeatureRouteQa.matches(volume, RuleEngine.match(volume.prompt).orEmpty(), validator))
    }

    @Test fun everyModelDrivenProbeMakesItsExpectedToolAvailableToThePlanner() {
        val registry = ToolRegistry()
        SyntheticFeatureRouteQa.probes.filter { RuleEngine.match(it.prompt) == null }.forEach { probe ->
            assertTrue("${probe.toolId} must be in planner relevance", registry.relevant(probe.prompt, 5).any { it.id == probe.toolId })
        }
    }

    @Test fun routeMatcherRejectsWrongFeatureOrWrongArguments() {
        val volume = SyntheticFeatureRouteQa.probes.single { it.toolId == "volume" }
        assertFalse(SyntheticFeatureRouteQa.matches(volume, listOf(com.pocketmind.tools.PlanStep("brightness", mapOf("level" to 128)))))
        assertFalse(SyntheticFeatureRouteQa.matches(volume, listOf(com.pocketmind.tools.PlanStep("volume", mapOf("step" to -1)))))
        assertFalse(SyntheticFeatureRouteQa.matches(volume, listOf(com.pocketmind.tools.PlanStep("volume", mapOf("level" to 40)))))
    }
}
