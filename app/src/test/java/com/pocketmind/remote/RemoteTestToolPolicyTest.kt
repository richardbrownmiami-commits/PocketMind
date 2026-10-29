package com.pocketmind.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTestToolPolicyTest {
    @Test fun liveRemoteSessionAllowsPocketMindNormalActionToolSet() {
        val normalPocketMindTools = listOf(
            "alarm.set", "timer.set", "flashlight", "volume", "brightness", "app.open", "app.search",
            "wifi.settings", "bluetooth.settings", "airplane.settings", "location.settings", "note.save", "web.search"
        )
        normalPocketMindTools.forEach { tool ->
            assertTrue("$tool must remain available through PocketMind's normal runtime", RemoteTestToolPolicy.allows(true, true))
        }
    }

    @Test fun delayedRemotePlanCannotRunAfterOwnerStopsSession() {
        assertFalse(RemoteTestToolPolicy.allows(requestStartedInRemoteSession = true, sessionActiveAtExecution = false))
    }

    @Test fun offSessionRequestsRetainNormalBehavior() {
        assertTrue(RemoteTestToolPolicy.allows(requestStartedInRemoteSession = false, sessionActiveAtExecution = false))
        assertTrue(RemoteTestToolPolicy.allows(requestStartedInRemoteSession = false, sessionActiveAtExecution = true))
    }

    @Test fun normalPocketMindRouterRecognizesRepresentativeRemoteCommands() {
        val commands = mapOf(
            "set an alarm for 7:30 pm" to "alarm.set",
            "set volume to 50 percent" to "volume",
            "turn on flashlight" to "flashlight",
            "open Calculator" to "app.open"
        )
        commands.forEach { (request, expectedTool) ->
            val plan = com.pocketmind.tools.RuleEngine.match(request)
            assertEquals(request, expectedTool, plan?.singleOrNull()?.tool)
            assertTrue("$request may execute during its live remote session", RemoteTestToolPolicy.allows(true, true))
        }
    }
}
