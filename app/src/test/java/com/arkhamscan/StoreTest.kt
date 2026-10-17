package com.arkhamscan

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StoreTest {
    private lateinit var db: ScanDatabase
    private lateinit var store: Store
    // Synthetic fixtures are test-only; never shipped as reference catalog data.
    private val card = CatalogCard("test:a", "Test face", "1", "test:old", "Old test product", "asset", "", "test-fixture", "v1")
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ScanDatabase::class.java).allowMainThreadQueries().build()
        store = db.store()
    }
    @After fun close() { db.close() }
    @Test fun confirmationAndUndoAreIdempotent(): Unit = runBlocking {
        assertTrue(store.confirm(card, "one", "session"))
        assertFalse(store.confirm(card, "one", "session"))
        assertEquals(1, store.quantity(card.id))
        assertTrue(store.confirm(card, "two", "session"))
        assertTrue(store.confirm(card, "three", "session"))
        assertTrue(store.confirm(card, "four", "session")) // legitimate extras are allowed
        store.undo("four"); store.undo("four")
        assertEquals(3, store.quantity(card.id))
        assertEquals(5, store.allAudit().size)
    }
    @Test fun exceptionsSurviveWithoutCreatingOwnershipAndResolveExactlyOnce(): Unit = runBlocking {
        store.addException(ExceptionRecord("e", "s", 1L, "DUPLICATE_CONFIRMATION", "[\"test:a\"]"))
        assertEquals(0, store.quantity(card.id))
        assertTrue(store.confirm(card, "choice", "s", "e"))
        assertFalse(store.confirm(card, "different-choice", "s", "e"))
        assertEquals(1, store.quantity(card.id))
        assertEquals("new_copy:choice", store.exception("e")!!.status)
    }
    @Test fun alreadyCountedDecisionNeverCreatesCopy(): Unit = runBlocking {
        store.addException(ExceptionRecord("e", "s", 1L, "LOW_CONFIDENCE", "[]"))
        store.dismiss("e", "already_counted"); store.dismiss("e", "already_counted")
        assertEquals(0, store.quantity(card.id))
        assertEquals(1, store.allAudit().size)
    }
    @Test fun catalogReplacementKeepsHistoricalCopyProvenance(): Unit = runBlocking {
        store.importCards(listOf(card)); store.confirm(card, "copy", "s")
        store.importCards(listOf(card.copy(title = "New name", version = "v2")))
        assertEquals("v1", store.copies().first().single().catalogVersion)
        assertEquals("Test face", store.copies().first().single().title)
        assertEquals("v2", store.cards().first().single().version)
    }
    @Test fun sortingUsesSourceCountsAndNeverAddsOwnership(): Unit = runBlocking {
        store.confirm(card, "copy", "s")
        val plan = """{"sourceCounts":{"test:a":1},"cardPacks":{"test:a":"test:old"},"piles":[{"number":1,"destinations":[{"id":"test:old"}]}]}"""
        store.savePass(SavedPass("pass", plan, 1))
        assertTrue(store.route(RouteRecord("r", "pass", card.id, 1, 2)))
        assertFalse(store.route(RouteRecord("r", "pass", card.id, 1, 2)))
        try { store.route(RouteRecord("extra", "pass", card.id, 1, 3)); fail("Excess must be rejected") } catch (_: IllegalArgumentException) { }
        assertEquals(1, store.quantity(card.id))
    }
    @Test fun concurrentRetriesCommitOnlyOnce(): Unit = runBlocking {
        val results = (1..12).map { async(Dispatchers.Default) { store.confirm(card, "same-event", "s") } }.awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(1, store.quantity(card.id))
    }
    @Test fun abandonedPassKeepsOwnershipAndRejectsFurtherRoutes(): Unit = runBlocking {
        store.confirm(card, "copy", "s")
        store.savePass(SavedPass("p", "{}", 1))
        store.abandonPass("p"); store.abandonPass("p")
        assertEquals("abandoned", store.pass("p")!!.status)
        assertEquals(1, store.quantity(card.id))
        try { store.route(RouteRecord("r", "p", card.id, 1, 2)); fail("Abandoned pass cannot route") } catch (_: IllegalArgumentException) { }
        assertEquals(2, store.allAudit().size)
    }
    @Test fun unknownExceptionCannotCommitCopy(): Unit = runBlocking {
        assertFalse(store.confirm(card, "copy", "s", "missing"))
        assertEquals(0, store.quantity(card.id))
    }
    @Test fun databaseReopenPreservesPendingDecisionsAndAudit(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "recovery-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ScanDatabase::class.java, name).allowMainThreadQueries().build()
        var disk = open()
        disk.store().addException(ExceptionRecord("e", "s", 1, "LOW_CONFIDENCE", "[]"))
        disk.store().confirm(card, "copy", "s")
        disk.close()
        disk = open()
        assertEquals("pending", disk.store().exception("e")!!.status)
        assertFalse(disk.store().confirm(card, "copy", "s"))
        assertEquals(1, disk.store().quantity(card.id))
        disk.close(); context.deleteDatabase(name)
    }
    @Test fun importerRejectsDuplicatePrintingIdentityAndUnsupportedSchema() {
        val record = """{"id":"a","title":"Test","pack":"p","packName":"Test pack"}"""
        val valid = """{"schemaVersion":1,"source":"test","cards":[$record]}"""
        assertEquals(1, CatalogImporter.parse(valid.toByteArray()).size)
        for (bad in listOf(valid.replace("[$record]", "[$record,$record]"), valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))) {
            try { CatalogImporter.parse(bad.toByteArray()); fail("Reject invalid catalog") } catch (_: IllegalArgumentException) { }
        }
    }
}
