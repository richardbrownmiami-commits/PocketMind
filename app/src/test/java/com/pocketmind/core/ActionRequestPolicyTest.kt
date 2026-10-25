package com.pocketmind.core

import com.pocketmind.tools.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRequestPolicyTest {
    @Test fun recognizesDirectActionRequestsAndPoliteCommands() {
        assertTrue(ActionRequestPolicy.looksLikeAction("Set display brightness to 128"))
        assertTrue(ActionRequestPolicy.looksLikeAction("Could you turn on the flashlight?"))
        assertTrue(ActionRequestPolicy.looksLikeAction("Please save this note"))
    }

    @Test fun doesNotClassifyHowToOrExplanationQuestionsAsCommands() {
        assertFalse(ActionRequestPolicy.looksLikeAction("How do I turn on the flashlight?"))
        assertFalse(ActionRequestPolicy.looksLikeAction("Why did you set an alarm?"))
        assertFalse(ActionRequestPolicy.looksLikeAction("Tell me how to save a note"))
    }

    @Test fun anUnplannableActionGetsAnExplicitNoActionReply() {
        assertEquals(
            ActionRequestPolicy.NO_SAFE_PLAN_REPLY,
            ActionRequestPolicy.noPlanReply(actionRequest = true, plan = null)
        )
    }

    @Test fun aValidPlanOrOrdinaryChatStillUsesItsExistingPath() {
        assertNull(ActionRequestPolicy.noPlanReply(
            actionRequest = true,
            plan = listOf(PlanStep("brightness", mapOf("level" to 128)))
        ))
        assertNull(ActionRequestPolicy.noPlanReply(actionRequest = false, plan = null))
    }
}
