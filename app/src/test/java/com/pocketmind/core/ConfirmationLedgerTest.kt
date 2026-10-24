package com.pocketmind.core

import com.pocketmind.tools.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmationLedgerTest {
    @Test fun overlappingRequestsRemainBoundToTheirDisplayedPlansAndAreSingleUse() {
        var now = 10_000L
        val ledger = ConfirmationLedger { now }
        val firstPlan = listOf(PlanStep("alarm.set", mapOf("hour" to 7, "minute" to 15)))
        val secondPlan = listOf(PlanStep("volume", mapOf("step" to 1)))
        val firstToken = ledger.issue(firstPlan)
        val secondToken = ledger.issue(secondPlan)

        assertEquals(firstPlan, ledger.consume(firstToken))
        assertEquals(secondPlan, ledger.consume(secondToken))
        assertNull(ledger.consume(firstToken))
    }

    @Test fun expiredConfirmationCannotBeConsumed() {
        var now = 1L
        val ledger = ConfirmationLedger { now }
        val token = ledger.issue(listOf(PlanStep("alarm.set", mapOf("hour" to 7, "minute" to 15))))
        now += 5 * 60 * 1000L + 1
        assertNull(ledger.consume(token))
    }

    @Test fun negativeElapsedAgeCannotKeepAConfirmationAlive() {
        var now = 100L
        val ledger = ConfirmationLedger { now }
        val token = ledger.issue(listOf(PlanStep("timer.set", mapOf("seconds" to 60))))
        now = 50L
        assertNull(ledger.consume(token))
    }
}
