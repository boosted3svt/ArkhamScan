package com.arkhamscan

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LiveStoreTest {
    private lateinit var db: ScanDatabase
    private lateinit var store: Store
    private val card = CatalogCard("test:a", "Test printing", "1", "p1", "Test box", "", "", "fixture", "v1", "family:1", "Test campaign")
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ScanDatabase::class.java).allowMainThreadQueries().build(); store = db.store() }
    @After fun close() { db.close() }
    @Test fun confirmationCommitsCopyAndStablePileTogetherOnlyOnce(): Unit = runBlocking {
        val first = store.confirmAndPlace(card, "one", "s", 5, "PACK")!!
        assertTrue(first.added); assertEquals(1, first.group.pile)
        assertFalse(store.confirmAndPlace(card, "one", "s", 5, "PACK")!!.added)
        assertEquals(1, store.quantity(card.id)); assertEquals(1, store.livePlacements().first().size)
        val next = store.confirmAndPlace(card.copy(id="test:b"), "two", "s", 12, "CYCLE")!!
        assertEquals(first.run, next.run) // Changing settings cannot remap a live table.
        assertEquals(1, next.group.pile)
    }
    @Test fun cycleGroupingKeepsRelatedReferencePacksTogether(): Unit = runBlocking {
        val a = store.confirmAndPlace(card, "one", "s", 5, "CYCLE")!!
        val b = store.confirmAndPlace(card.copy(id="test:b", pack="p2", packName="Mythos pack"), "two", "s", 5, "CYCLE")!!
        assertEquals(a.group, b.group)
        assertEquals(2, store.livePlacements().first().size)
    }
    @Test fun boundedMixedPileAndResetRetainHistoricAssignments(): Unit = runBlocking {
        for (n in 1..6) store.confirmAndPlace(card.copy(id="test:$n", pack="p$n", packName="Box $n"), "copy$n", "s", 5, "PACK")
        val groups = store.liveGroups().first()
        assertEquals(listOf(1,2,3,4,4,4), groups.sortedBy { it.groupId }.map { it.pile })
        val old = store.activeLiveRun()!!
        val fresh = store.newLiveRun(8, "PACK", "v1")
        assertNotEquals(old.id, fresh.id)
        assertEquals(6, store.copies().first().count { it.status=="active" })
        assertEquals(groups, store.liveGroups().first())
        assertEquals("closed", store.liveRuns().first().single { it.id==old.id }.state)
    }
    @Test fun quantityEditsAreAuditedIdempotentAndCannotGoNegativeOrInventPlacements(): Unit = runBlocking {
        assertFalse(store.adjustQuantity(card, -1, "zero", "s"))
        assertTrue(store.adjustQuantity(card, 1, "plus", "s"))
        assertFalse(store.adjustQuantity(card, 1, "plus", "s"))
        assertEquals(1, store.quantity(card.id))
        assertTrue(store.adjustQuantity(card, -1, "minus", "s"))
        assertFalse(store.adjustQuantity(card, -1, "minus", "s"))
        assertEquals(0, store.quantity(card.id))
        assertTrue(store.livePlacements().first().isEmpty())
        assertEquals(2, store.allAudit().count { it.action=="ADJUST_QUANTITY" })
        assertTrue(store.adjustQuantity(card, 1, "restore", "s"))
        assertEquals(1, store.quantity(card.id))
    }
    @Test fun catalogChangesCannotSilentlyChangeAnExistingLiveMap(): Unit = runBlocking {
        store.confirmAndPlace(card, "one", "s", 5, "PACK")
        try { store.confirmAndPlace(card.copy(version="v2"), "two", "s", 5, "PACK"); fail("Must preserve frozen source rules") } catch (_: IllegalArgumentException) { }
        assertEquals(1, store.quantity(card.id)); assertEquals(1, store.livePlacements().first().size)
    }
}
