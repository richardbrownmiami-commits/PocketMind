package com.pocketmind.diagnostics

import com.pocketmind.tools.RuleEngine
import com.pocketmind.tools.ToolRegistry
import com.pocketmind.tools.ToolValidator
import com.pocketmind.tools.TesterAlarmLabel
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

    @Test fun userSelectedAlarmAndTimerValuesSurviveTheRuleFirstChatRoute() {
        val alarm = RuleEngine.match("Set alarm for 6:30 PM labeled pocketmind-qa-1234abcd")?.singleOrNull()
        assertEquals("alarm.set", alarm?.tool)
        assertEquals(18L, (alarm?.args?.get("hour") as? Number)?.toLong())
        assertEquals(30L, (alarm?.args?.get("minute") as? Number)?.toLong())
        assertEquals("pocketmind-qa-1234abcd", alarm?.args?.get("label"))
        assertEquals(null, alarm?.let(ToolValidator()::validate))

        val timer = RuleEngine.match("Set a timer for 17 minutes")?.singleOrNull()
        assertEquals("timer.set", timer?.tool)
        assertEquals(1020L, (timer?.args?.get("seconds") as? Number)?.toLong())
        assertEquals(null, timer?.let(ToolValidator()::validate))
    }

    @Test fun generatedTesterAlarmLabelsAreAcceptedByTheExactLabelCleanupGuard() {
        val label = TesterAlarmLabel.create()
        assertTrue(label.startsWith("pocketmind-qa-"))
        assertTrue(TesterAlarmLabel.isValid(label))
        assertFalse(TesterAlarmLabel.isValid("user alarm"))
    }
}
