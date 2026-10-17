package com.arkhamscan.core

import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test fun rapidDuplicatesAndUncertaintyNeverCommit() {
        val good = Evidence(setOf("a"), true, true, true)
        assertEquals(Route.COMMIT_FIRST, decide(Mode.RAPID_NEW_INVENTORY, good, 0))
        assertEquals(Route.RE_SORT, decide(Mode.RAPID_NEW_INVENTORY, good, 1))
        for (bad in listOf(good.copy(validatedMatch = false), good.copy(freshFrame = false),
            good.copy(retailOriginResolved = false), good.copy(candidates = setOf("a", "b")))) {
            assertEquals(Route.RE_SORT, decide(Mode.RAPID_NEW_INVENTORY, bad, 0))
        }
        assertEquals(Route.AWAIT_CONFIRMATION, decide(Mode.INTERACTIVE_RESOLUTION, good, 3))
        assertEquals(Route.ROUTE_EXISTING, decide(Mode.RESORT_EXISTING, good, 3))
    }
    @Test fun stationaryTextAndBriefDropoutsDoNotRearm() {
        val g = PresentationGate()
        assertFalse(g.observe(true, 0)); assertTrue(g.observe(true, 700))
        assertFalse(g.observe(true, 5000)); assertFalse(g.observe(false, 5100))
        assertFalse(g.observe(true, 5500)); assertFalse(g.observe(true, 6500))
        assertFalse(g.observe(false, 7000)); assertFalse(g.observe(false, 8000))
        assertFalse(g.observe(true, 8100)); assertTrue(g.observe(true, 8800))
    }
    @Test fun capacityBoundAndAllDestinationsSurviveForFiveFiftyAndFiveHundred() {
        for (n in listOf(5, 50, 500)) for (cap in listOf(3, 5, 8)) {
            val ds = (1..n).map { Destination("p$it", "Product $it", it % 7 + 1, "edition${it%3}") }
            val p = PilePlanner.plan(ds, cap)
            assertTrue(p.piles.size + 1 <= cap)
            assertEquals(ds.map { it.id }.toSet(), p.piles.flatMap { it.destinations }.map { it.id }.toSet())
            val sim = PilePlanner.simulate(ds, cap)
            assertTrue(sim.peakPiles <= cap)
            assertTrue(sim.passes > 0)
            assertEquals(sim.presentations - ds.sumOf { it.quantity }, sim.reScans)
            assertEquals(p, PilePlanner.plan(ds, cap))
            val ids = p.piles.take(2).map { it.destinations.first().id }.toSet()
            if (ids.size == 2) assertNull(p.pileFor(ids))
        }
    }
    @Test fun interruptedPassRestoresLedgerAndRejectsExcess() {
        val first = PassLedger(mapOf("a" to 2))
        assertTrue(first.route("event1", "a"))
        val resumed = PassLedger(mapOf("a" to 2), first.snapshot())
        assertFalse(resumed.route("event1", "a")); assertTrue(resumed.route("event2", "a"))
        assertEquals(2, resumed.routed["a"])
        try { resumed.route("event3", "a"); fail("Must reconcile exhausted source") } catch (_: IllegalArgumentException) { }
    }
    @Test fun overlappingProductsRequireExplicitAllocationAndVerifiedManifest() {
        val a = Manifest("old", mapOf("same-face" to 3), true)
        val b = Manifest("new", mapOf("same-face" to 2), true)
        assertEquals(1.0, completeness(a, mapOf("same-face" to 4)).fraction!!, 0.0)
        assertEquals(1, completeness(a, mapOf("same-face" to 4)).rows.single().excess)
        assertEquals(0.0, completeness(b, emptyMap()).fraction!!, 0.0)
        assertNull(completeness(b.copy(verified = false), emptyMap()).fraction)
    }
    @Test fun unknownCapacityUsesSmallEditableProposal() {
        assertEquals(5, PilePlanner.plan(listOf(Destination("a", "A"))).capacity)
    }
}
