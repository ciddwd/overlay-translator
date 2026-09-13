package com.gameocr.app.capture

import org.junit.Assert.*
import org.junit.Test

class SettledPagePolicyTest {
    @Test fun unavailableSamplesPauseSettlingWithoutClearingOrResubmittingVisibleResult() {
        for (alreadySubmitted in listOf(false, true)) {
            val policy = SettledPagePolicy()
            val page = policy.observe(false, true, 0, 500)
            policy.observe(true, true, 500, 500)
            if (alreadySubmitted) assertTrue(policy.claim(page.revision))
            policy.pauseObservation(600)
            assertTrue(policy.accepts(page.revision))
            assertFalse(policy.claim(page.revision))
            // Time hidden or without screenshots must not count toward stability.
            val resumed = policy.observe(true, true, 10_000, 500)
            assertFalse(resumed.invalidatePrevious)
            assertFalse(resumed.replaceAnchor)
            assertFalse(resumed.ready)
            assertEquals(!alreadySubmitted, policy.observe(true, true, 10_500, 500).ready)
            assertEquals(!alreadySubmitted, policy.claim(page.revision))
        }
    }

    @Test fun cannotClaimBeforeStableOrAfterAChange() {
        val policy = SettledPagePolicy()
        assertFalse(policy.claim(0))
        val a = policy.observe(false, true, 0, 500)
        assertFalse(policy.claim(a.revision))
        policy.observe(true, true, 499, 500)
        assertFalse(policy.claim(a.revision))
        assertTrue(policy.observe(true, true, 500, 500).ready)
        val b = policy.observe(false, true, 501, 500)
        assertFalse(policy.claim(a.revision))
        assertFalse(policy.claim(b.revision))
    }
    @Test fun observationContinuesDuringWorkAndOnlyLatestPageCanBeClaimed() {
        data class Case(val name: String, val same: Boolean, val now: Long, val invalid: Boolean, val ready: Boolean)
        val cases = listOf(
            Case("first A", false, 0, false, false),
            Case("A still settling", true, 499, false, false),
            Case("A ready", true, 500, false, true),
            Case("B while A translating", false, 600, true, false),
            Case("C before B ready", false, 700, true, false),
            Case("C settling while old work exits", true, 1199, false, false),
            Case("C ready", true, 1200, false, true),
        )
        val policy = SettledPagePolicy()
        var firstRevision = -1L
        var latestRevision = -1L
        cases.forEach { case ->
            val decision = policy.observe(case.same, true, case.now, 500)
            assertEquals(case.name, case.invalid, decision.invalidatePrevious)
            assertEquals(case.name, case.ready, decision.ready)
            latestRevision = decision.revision
            if (case.name == "A ready") {
                firstRevision = decision.revision
                assertTrue(policy.claim(firstRevision))
            }
        }
        assertFalse(policy.accepts(firstRevision))
        assertFalse(policy.claim(firstRevision))
        assertTrue(policy.claim(latestRevision))
        assertFalse(policy.claim(latestRevision))
        assertFalse(policy.observe(true, true, 5000, 500).ready)
    }

    @Test fun contextAndClockDiscontinuitiesInvalidateEvenWithIdenticalPixels() {
        data class Case(val name: String, val context: Boolean, val time: Long)
        listOf(
            Case("rotation or region or settings", false, 1100),
            Case("clock moves backward", true, 800),
        ).forEach { case ->
            val policy = SettledPagePolicy()
            val first = policy.observe(false, true, 900, 100)
            assertTrue(policy.observe(true, true, 1000, 100).ready)
            assertTrue(policy.claim(first.revision))
            val next = policy.observe(true, case.context, case.time, 100)
            assertTrue(case.name, next.invalidatePrevious)
            assertTrue(case.name, next.replaceAnchor)
            assertFalse(case.name, next.ready)
            assertFalse(case.name, policy.accepts(first.revision))
        }
    }

    @Test fun resetPreventsLateCompletionAndRequiresFreshSettling() {
        listOf("stop", "screen off", "backend lost", "source obscured", "mode changed").forEach { reason ->
            val policy = SettledPagePolicy()
            val first = policy.observe(false, true, 0, 500)
            policy.observe(true, true, 500, 500)
            assertTrue(reason, policy.claim(first.revision))
            policy.reset()
            assertFalse(reason, policy.accepts(first.revision))
            assertFalse(reason, policy.claim(first.revision))
            assertFalse(reason, policy.observe(true, true, 1000, 500).ready)
            assertTrue(reason, policy.observe(true, true, 1500, 500).ready)
        }
    }

    @Test fun readinessIsNotConsumedUntilWorkerActuallyClaimsIt() {
        val policy = SettledPagePolicy()
        policy.observe(false, true, 0, 200)
        for (time in listOf(200L, 400L, 900L)) {
            assertTrue(policy.observe(true, true, time, 200).ready)
        }
        val next = policy.observe(false, true, 1000, 200)
        assertFalse(next.ready)
        assertTrue(policy.observe(true, true, 1200, 200).ready)
        assertTrue(policy.claim(next.revision))
    }
}
