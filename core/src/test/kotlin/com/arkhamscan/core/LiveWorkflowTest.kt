package com.arkhamscan.core

import org.junit.Assert.*
import org.junit.Test

class LiveWorkflowTest {
    @Test fun dropoutsAndOtherFramesNeverRemoveOrReorderHeldButtons() {
        val latch = CandidateLatch()
        assertTrue(latch.observe(listOf("a", "b"), 0).isEmpty())
        assertEquals(listOf("a", "b"), latch.observe(listOf("a", "b"), 250))
        assertEquals(listOf("a", "b"), latch.observe(emptyList(), 500))
        assertEquals(listOf("a", "b"), latch.observe(listOf("b"), 750))
        assertEquals(listOf("a", "b"), latch.observe(listOf("c"), 2000))
        assertEquals(listOf("a", "b"), latch.observe(listOf("c"), 2500))
        latch.next()
        assertTrue(latch.observe(listOf("c"), 2600).isEmpty())
        assertEquals(listOf("c"), latch.observe(listOf("c"), 2900))
    }
    @Test fun confirmationSuppressesStationaryCardUntilRemovalOrChangedIdentity() {
        val latch = CandidateLatch()
        latch.observe(listOf("a"), 0); latch.observe(listOf("a"), 250); latch.consume()
        assertTrue(latch.waitingForRemoval)
        assertTrue(latch.observe(listOf("a"), 500).isEmpty())
        assertTrue(latch.observe(listOf("a"), 1500).isEmpty())
        latch.observe(emptyList(), 1600); latch.observe(emptyList(), 2600)
        latch.observe(listOf("a"), 2700)
        assertEquals(listOf("a"), latch.observe(listOf("a"), 3000))
        latch.consume(); latch.observe(listOf("b"), 3100)
        assertEquals(listOf("b"), latch.observe(listOf("b"), 3400))
    }
    @Test fun transientMatchCannotReplaceHeldListOrBecomeAStableCandidate() {
        val latch = CandidateLatch()
        latch.observe(listOf("wrong"), 0); latch.observe(emptyList(), 100)
        assertTrue(latch.observe(listOf("right"), 250).isEmpty())
        assertEquals(listOf("right"), latch.observe(listOf("right"), 500))
    }
    @Test fun growingMapNeverRenumbersEarlierGroupsOrExceedsCapacity() {
        for (capacity in listOf(3, 5, 12)) {
            val groups = mutableListOf<LiveGroup>()
            for (i in 1..500) {
                val before = groups.toList()
                groups += GrowingPilePlanner.assign(groups, "group$i", "Product $i", capacity)
                assertEquals(before, groups.dropLast(1))
                assertTrue(groups.all { it.pile in 1 until capacity })
            }
            assertEquals(capacity - 1, groups.map { it.pile }.distinct().size)
            assertEquals(groups[0], GrowingPilePlanner.assign(groups, groups[0].id, "Renamed externally", capacity))
        }
    }
}
