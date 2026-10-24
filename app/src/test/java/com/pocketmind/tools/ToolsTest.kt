package com.pocketmind.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {
    @Test fun negationAndMath() {
        assertEquals(emptyList<PlanStep>(), RuleEngine.match("don't set an alarm"))
        assertEquals(14.0, SafeMath.evaluate("2 + 3 * 4"), 0.0)
        assertEquals("math.calculate", RuleEngine.match("calculate 2 + 3")!![0].tool)
        assertNull(runCatching { SafeMath.evaluate("1 / 0") }.getOrNull())
    }

    @Test fun alarmAndTimerCommandsAreParsedSafely() {
        val alarm = RuleEngine.match("Set alarm 6:30 pm")!!.single()
        assertEquals("alarm.set", alarm.tool)
        assertEquals(18, alarm.args["hour"])
        assertEquals(30, alarm.args["minute"])
        assertNull(RuleEngine.match("set alarm 28:90"))
        assertEquals(emptyList<PlanStep>(), RuleEngine.match("don't start a timer for 5 minutes"))
        assertEquals(300, RuleEngine.match("set timer for 5 minutes")!!.single().args["seconds"])
        assertEquals("note.save", RuleEngine.match("remember to call mom")!!.single().tool)
    }

    @Test fun volumeCommandsRouteToRelativeOrAbsoluteActionAndAskForMissingTargets() {
        assertEquals(PlanStep("volume", mapOf("step" to 1)), RuleEngine.match("Raise the media volume" )!!.single())
        assertEquals(PlanStep("volume", mapOf("step" to -1)), RuleEngine.match("Could you turn down the volume, please?")!!.single())
        assertEquals(PlanStep("volume", mapOf("level" to 40)), RuleEngine.match("Set volume to 40%")!!.single())
        assertNull(RuleEngine.match("Set media volume"))
        assertTrue(RuleEngine.clarification("Set media volume")!!.contains("0 to 100"))
    }

    @Test fun incompleteAlarmRequestsClarificationButInformationalQuestionsDoNot() {
        assertTrue(RuleEngine.clarification("Set an alarm")!!.contains("What time"))
        assertNull(RuleEngine.clarification("How do I set an alarm?"))
        assertNull(RuleEngine.clarification("Set an alarm for 6:30 pm"))
    }

    @Test fun strictPlannerRejectsGarbageAndUnknownFields() {
        assertEquals(listOf(PlanStep("clock.time", emptyMap())), PlannerJson.parse("{\"steps\":[{\"tool\":\"clock.time\",\"args\":{}}]}"))
        assertTrue(PlannerJson.parse("{\"steps\":[]}").isEmpty())
        listOf("{}", "{\"steps\":[],\"x\":1}", "{\"steps\":[] } garbage", "{\"steps\":[{\"tool\":\"x\",\"args\":{},\"x\":1}]}").forEach {
            assertTrue(runCatching { PlannerJson.parse(it) }.isFailure)
        }
    }

    @Test fun validatorChecksAllowlistRangesAndStepCap() {
        val validator = ToolValidator()
        assertNotNull(validator.validate(PlanStep("clock.time", mapOf("oops" to 1))))
        assertNotNull(validator.validate(PlanStep("math.calculate", mapOf("expression" to 2))))
        assertNotNull(validator.validatePlan(List(9) { PlanStep("clock.time", emptyMap()) }))
        assertNull(validator.validate(PlanStep("clock.time", emptyMap())))
        assertNull(validator.validate(PlanStep("alarm.set", mapOf("hour" to 6L, "minute" to 30L))))
        assertNull(validator.validate(PlanStep("volume", mapOf("level" to 50))))
        assertNull(validator.validate(PlanStep("volume", mapOf("step" to 1))))
        assertNotNull(validator.validate(PlanStep("volume", emptyMap())))
        assertNotNull(validator.validate(PlanStep("volume", mapOf("level" to 50, "step" to 1))))
        assertNotNull(validator.validate(PlanStep("volume", mapOf("step" to 0))))
    }
}
