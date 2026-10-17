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
class BulkInventoryTest {
    private lateinit var db: ScanDatabase
    private lateinit var store: Store
    private val card = CatalogCard("test:a", "Test card", "1", "p", "Test set", "", "", "fixture", "v1", expectedQuantity=3)
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ScanDatabase::class.java).allowMainThreadQueries().build(); store=db.store() }
    @After fun close() { db.close() }
    @Test fun ownershipAccumulatesAcrossSessionsAndSortingDoesNotChangeReview(): Unit = runBlocking {
        store.importCards(listOf(card))
        store.confirm(card, "one", "session1"); store.confirm(card, "two", "session2")
        assertEquals(2, store.quantity(card.id))
        val revision=store.currentBulkInventory()!!.revision
        store.finishBulkInventory(revision)
        assertNotNull(store.currentBulkInventory()!!.finishedAt)
        store.newLiveRun(5, "PACK", "v1")
        assertNotNull(store.currentBulkInventory()!!.finishedAt)
        store.confirm(card, "three", "session3")
        assertEquals(3, store.quantity(card.id)); assertNull(store.currentBulkInventory()!!.finishedAt)
    }
    @Test fun staleFinishCannotHideConcurrentEditsAndEveryQuantityEditReopensReview(): Unit = runBlocking {
        store.importCards(listOf(card)); store.confirm(card, "one", "s")
        val old=store.currentBulkInventory()!!.revision
        store.adjustQuantity(card,1,"plus","s")
        try { store.finishBulkInventory(old); fail("Must review the changed inventory") } catch (_: IllegalArgumentException) {}
        store.finishBulkInventory(store.currentBulkInventory()!!.revision)
        store.undo("one")
        assertNull(store.currentBulkInventory()!!.finishedAt)
        assertEquals(1,store.quantity(card.id))
    }
    @Test fun cutoffAndCatalogRefreshReopenReviewWithoutClearingCopies(): Unit = runBlocking {
        store.importCards(listOf(card)); store.confirm(card,"one","s")
        store.setCutoff("2020-12-31")
        store.finishBulkInventory(store.currentBulkInventory()!!.revision)
        store.setCutoff("2021-12-31")
        assertNull(store.currentBulkInventory()!!.finishedAt)
        assertEquals(1,store.quantity(card.id))
        store.finishBulkInventory(store.currentBulkInventory()!!.revision)
        store.importCards(listOf(card.copy(version="v2")))
        assertNull(store.currentBulkInventory()!!.finishedAt)
        assertEquals("2021-12-31",store.currentBulkInventory()!!.cutoff)
        assertEquals(1,store.quantity(card.id))
    }
    @Test fun referenceIndexIncludesProductsWithoutDirectCardRecords(): Unit = runBlocking {
        val refs = listOf(ReferencePack("p","Test set","","","2020-01-01","[]","","fixture","v1"),
            ReferencePack("bundle","Test bundle","","","2021-01-01","[\"p\"]","player","fixture","v1"))
        store.importCards(listOf(card),refs)
        assertEquals(2,store.referencePacks().first().size)
        assertEquals(3,store.cards().first().single().expectedQuantity)
    }
    @Test fun newUnresolvedAttemptsReopenFinishedReviewWithoutCreatingOwnership(): Unit = runBlocking {
        store.importCards(listOf(card));store.finishBulkInventory(store.currentBulkInventory()!!.revision)
        store.addException(ExceptionRecord("attempt","s",1,"LOW_CONFIDENCE","[]"))
        assertNull(store.currentBulkInventory()!!.finishedAt);assertEquals(0,store.quantity(card.id))
    }
    @Test fun completedBulkProfileAndCopiesSurviveDatabaseReopen(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="bulk-profile-reopen.db";context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,ScanDatabase::class.java,name).allowMainThreadQueries().build()
        var disk=open()
        disk.store().importCards(listOf(card));disk.store().confirm(card,"one","earlier-session")
        disk.store().setCutoff("2020-01-01");disk.store().finishBulkInventory(disk.store().currentBulkInventory()!!.revision)
        disk.close();disk=open()
        try {
            assertEquals("2020-01-01",disk.store().currentBulkInventory()!!.cutoff)
            assertNotNull(disk.store().currentBulkInventory()!!.finishedAt)
            assertEquals(1,disk.store().quantity(card.id))
            assertEquals(3,disk.store().cards().first().single().expectedQuantity)
        } finally { disk.close();context.deleteDatabase(name) }
    }

}
