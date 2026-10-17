package com.arkhamscan

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = UiTestApplication::class)
class LivePassSourceTest {
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime()+10_000_000_000L
        while(!condition() && System.nanoTime()<end) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("Operation completed", condition())
    }
    @Test fun splittingMixedPileSnapshotsOnlyItsPlacedCopiesAndPreservesOwnership() {
        val app = ApplicationProvider.getApplicationContext<UiTestApplication>()
        val store = app.database.store()
        runBlocking {
            for (n in 1..5) store.confirmAndPlace(CatalogCard("test:$n", "Test $n", "$n", "p$n", "Box $n", "", "", "fixture", "v1"), "copy$n", "s", 5, "PACK")
        }
        val run = runBlocking { store.activeLiveRun()!! }
        val vm = ScanViewModel(app)
        await { vm.databaseReady.value && vm.copies.value.size==5 && vm.livePlacements.value.size==5 }
        val job = vm.makePlan("live source / Pile 4", liveSource = run.id to 4)
        await { job.isCompleted }
        runBlocking {
            val pass = store.passes().first().single()
            val source = JSONObject(pass.json).getJSONObject("sourceCounts")
            assertEquals(setOf("test:4", "test:5"), source.keys().asSequence().toSet())
            assertEquals(1, source.getInt("test:4"))
            assertEquals(5, store.copies().first().count { it.status=="active" })
            assertEquals(5, store.livePlacements().first().size)
            assertNull(store.activeLiveRun())
            store.completePass(pass.id)
            try {
                store.confirmAndPlace(CatalogCard("test:new", "New", "6", "new", "New box", "", "", "fixture", "v1"), "new-copy", "s", 5, "PACK")
                fail("Must explicitly clear/reset the table after re-sorting")
            } catch (_: IllegalArgumentException) { }
            assertEquals(5, store.copies().first().count { it.status=="active" })
        }
    }
}
