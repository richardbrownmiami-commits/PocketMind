package com.pocketmind.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteTestToolPolicyTest {
    @Test fun remoteRequestsOnlyAllowAppDataAndExistingReadOnlyOnlineTools() {
        listOf("clock.time", "clock.date", "math.calculate", "note.save", "note.list", "note.search", "action.history", "web.search", "api.discover", "api.get").forEach {
            assertTrue(it, RemoteTestToolPolicy.allows(it, true))
        }
        listOf("alarm.set", "timer.set", "flashlight", "volume", "brightness", "battery/status", "app.open", "app.search", "wifi.settings", "bluetooth.settings", "airplane.settings", "location.settings", "future.tool").forEach {
            assertFalse(it, RemoteTestToolPolicy.allows(it, true))
        }
    }

    @Test fun normalLocalRequestsKeepTheirExistingTools() {
        assertTrue(RemoteTestToolPolicy.allows("alarm.set", false))
        assertTrue(RemoteTestToolPolicy.allows("future.tool", false))
    }

    @Test fun delayedPlanCannotRunDeviceToolsAfterOwnerStopsSession() {
        val restrictedWhenRequestWasSubmitted = true
        val sessionActiveWhenDelayedPlanExecutes = false // Owner pressed Stop while planning was in progress.
        listOf("alarm.set", "timer.set", "flashlight", "volume", "brightness", "app.open", "wifi.settings").forEach {
            assertFalse("$it must stay blocked after Stop", RemoteTestToolPolicy.allows(
                it, restrictedWhenRequestWasSubmitted, sessionActiveWhenDelayedPlanExecutes
            ))
        }
        assertTrue("app-local calculation stays in scope", RemoteTestToolPolicy.allows(
            "math.calculate", restrictedWhenRequestWasSubmitted, sessionActiveWhenDelayedPlanExecutes
        ))
    }

    @Test fun localPlanCannotRunDeviceToolsIfSessionStartsBeforeExecution() {
        val restrictedWhenRequestWasSubmitted = false
        val sessionActiveWhenPlanExecutes = true
        assertFalse(RemoteTestToolPolicy.allows("alarm.set", restrictedWhenRequestWasSubmitted, sessionActiveWhenPlanExecutes))
        assertTrue(RemoteTestToolPolicy.allows("note.list", restrictedWhenRequestWasSubmitted, sessionActiveWhenPlanExecutes))
    }
}
