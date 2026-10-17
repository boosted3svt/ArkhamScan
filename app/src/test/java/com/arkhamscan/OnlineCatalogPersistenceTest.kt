package com.arkhamscan

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = UiTestApplication::class)
class OnlineCatalogPersistenceTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        assertTrue("Background operation completed", condition())
    }
    @Test fun publicDownloadPopulatesOfflineCatalogWithoutLocalFile() {
        val app = ApplicationProvider.getApplicationContext<UiTestApplication>()
        val vm = ScanViewModel(app)
        await { vm.databaseReady.value }
        vm.downloadArkhamDb(ArkhamDbCatalog { address ->
            if (address == ArkhamDbCatalog.PACKS_URL) """[{"code":"p","name":"Test pack"}]""".toByteArray()
            else """[{"code":"00001","name":"Test printing","position":1,"pack_code":"p"}]""".toByteArray()
        })
        await { !vm.downloadingCatalog.value }
        val saved = runBlocking { app.database.store().cards().first() }
        assertEquals("arkhamdb:00001", saved.single().id)
        assertEquals("Test printing", saved.single().title)
        assertTrue(vm.message.value.contains("1 ArkhamDB printings ready"))
    }
    @Test fun failedRefreshKeepsSavedCatalogAndOwnership() {
        val app = ApplicationProvider.getApplicationContext<UiTestApplication>()
        val vm = ScanViewModel(app)
        await { vm.databaseReady.value }
        val old = CatalogCard("arkhamdb:00001", "Previously saved", "1", "p", "Test pack", "", "", "test", "old-hash")
        runBlocking { app.database.store().importCards(listOf(old)); app.database.store().confirm(old, "copy", vm.sessionId) }
        vm.downloadArkhamDb(ArkhamDbCatalog { error("network unavailable") })
        await { !vm.downloadingCatalog.value }
        runBlocking {
            assertEquals(listOf(old), app.database.store().cards().first())
            assertEquals(1, app.database.store().quantity(old.id))
        }
        assertTrue(vm.message.value.contains("unchanged"))
    }
}
