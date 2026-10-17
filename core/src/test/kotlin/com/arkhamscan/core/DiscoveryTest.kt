package com.arkhamscan.core

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    private val a = IndexCard("a", "A", "old", 3)
    private val b = IndexCard("b", "B", "old", 2, encounter="set", encounterName="Test encounter")
    private val old = IndexPack("old", "Old product", "cycle", "Test cycle", "2019-01-01")
    @Test fun extraCopiesCannotReplaceDifferentMissingCards() {
        val result = DiscoveryEngine.build(listOf(a,b), listOf(old), mapOf("a" to 5))
        val scope = result.scopes.single { it.id=="pack:old" }
        assertEquals(5, scope.expectedCopies)
        assertEquals(3, scope.accounted)
        assertEquals(.6, scope.fraction!!, 0.0)
        assertEquals(2, scope.extras)
        assertEquals(2, scope.rows.single { it.id=="b" }.outstanding)
        assertFalse(scope.allListedAccounted)
    }
    @Test fun equivalentsUseOneLogicalDesignAndMaxQuantityWithoutSummingEditions() {
        val alias = a.copy(id="new-a", pack="new", canonicalId="a", quantity=5)
        val newer = old.copy(id="new", title="New edition", released="2024-01-01")
        val index = DiscoveryEngine.build(listOf(a,alias), listOf(old,newer), mapOf("a" to 1, "new-a" to 2))
        val cycle = index.scopes.single { it.id=="cycle" }
        assertEquals(5, cycle.expectedCopies); assertEquals(3, cycle.accounted)
        assertEquals(1, cycle.designsExpected)
        val oldScope = index.scopes.single { it.id=="pack:old" }
        assertEquals(1, oldScope.rows.single().exact)
        assertEquals(3, oldScope.rows.single().observed)
        assertTrue(oldScope.sourceNote.contains("not an independently verified"))
    }
    @Test fun repackagingUsesExplicitMembershipAndNeverGuessesBoxCopyQuantities() {
        val playerBox = old.copy(id="bundle-p", title="Player repack", members=setOf("old"), reprintType="player")
        val campaignBox = playerBox.copy(id="bundle-c", reprintType="campaign")
        val scopes = DiscoveryEngine.build(listOf(a,b), listOf(old,playerBox,campaignBox), mapOf("a" to 3)).scopes
        val player = scopes.single { it.id=="pack:bundle-p" }
        assertEquals(listOf("a"), player.rows.map { it.id })
        assertEquals(1, player.designsFound); assertNull(player.expectedCopies); assertNull(player.fraction)
        assertEquals(listOf("b"), scopes.single { it.id=="pack:bundle-c" }.rows.map { it.id })
    }
    @Test fun cutoffFiltersTargetsWithoutDeletingOrIgnoringOwnedEquivalents() {
        val alias = a.copy(id="new-a", pack="new", canonicalId="a", quantity=5)
        val newPack = old.copy(id="new", released="2024-01-01")
        val undated = old.copy(id="undated", released="")
        val result = DiscoveryEngine.build(listOf(a,alias), listOf(old,newPack,undated), mapOf("new-a" to 3, "unindexed" to 2), "2020-01-01")
        assertEquals(2, result.unindexedCopies)
        assertFalse(result.scopes.single { it.id=="pack:new" }.inTarget)
        assertFalse(result.scopes.single { it.id=="pack:undated" }.inTarget)
        assertEquals(3, result.scopes.single { it.id=="cycle" }.expectedCopies)
        assertEquals(3, result.scopes.single { it.id=="pack:old" }.accounted)
    }
    @Test fun absentQuantitiesAndAbsentReferencesCannotBecomeComplete() {
        val scopes = DiscoveryEngine.build(listOf(a.copy(quantity=null)), listOf(old, old.copy(id="empty")), emptyMap()).scopes
        assertNull(scopes.single { it.id=="pack:old" }.fraction)
        val empty = scopes.single { it.id=="pack:empty" }
        assertEquals(0, empty.designsExpected); assertNull(empty.fraction); assertFalse(empty.allListedAccounted)
    }
    @Test fun malformedAliasCyclesNeverMergeUnrelatedObservations() {
        val rows = DiscoveryEngine.build(listOf(a.copy(canonicalId="b"), b.copy(canonicalId="a")), listOf(old), mapOf("a" to 1)).scopes.single { it.id=="pack:old" }.rows
        assertEquals(0, rows.single { it.id=="b" }.observed)
    }
    @Test fun encounterCoverageDoesNotClaimScenarioDependenciesAreKnown() {
        val encounter = DiscoveryEngine.build(listOf(b), listOf(old), emptyMap()).scopes.single { it.kind=="ENCOUNTER" }
        assertEquals("Test encounter", encounter.title)
        assertTrue(encounter.sourceNote.contains("additional sets"))
    }
    @Test(expected = java.time.format.DateTimeParseException::class)
    fun invalidCutoffIsRejected() { DiscoveryEngine.build(listOf(a), listOf(old), emptyMap(), "not-a-date") }
}
