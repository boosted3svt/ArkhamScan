package com.arkhamscan

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = DiscoveryUiApplication::class)
class DiscoveryUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var vm: ScanViewModel
    private fun await(condition:()->Boolean) { compose.waitUntil(15_000) { compose.waitForIdle();Shadows.shadowOf(Looper.getMainLooper()).idle();condition() } }
    @Before fun ready() {
        compose.runOnIdle { vm=ViewModelProvider(compose.activity)[ScanViewModel::class.java] }
        org.junit.Assert.assertSame(compose.activity.application, vm.getApplication<android.app.Application>())
        try { await { vm.databaseReady.value && vm.discovery.value.scopes.firstOrNull { it.id == "pack:p" }?.accounted == 82 && vm.referencePacks.value.size == 2 } }
        catch (e: Throwable) { throw AssertionError("Discovery startup: app=${compose.activity.application.javaClass.name}; cards=${vm.cards.value}; copies=${vm.copies.value.size}; packs=${vm.referencePacks.value}; index=${vm.discovery.value}; state=${vm.bulkInventory.value}; message=${vm.message.value}", e) }
    }
    private fun scroll(text:String) { compose.onNode(hasScrollAction()).performScrollToNode(hasText(text)) }
    @org.junit.After fun closeDatabase() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        (compose.activity.application as ScanApplication).database.close()
    }
    @Test fun sourceProgressShowsEightyTwoOfOneHundredAndChangesFromOutstandingToPotentiallyMissing() {
        compose.onNodeWithText("Collection").performClick()
        compose.onNodeWithText("Sets & cycles").performClick()
        scroll("Test box");compose.onNodeWithText("Test box").performClick()
        scroll("82% · 82/100 source-listed copies")
        compose.onNodeWithText("82% · 82/100 source-listed copies").assertIsDisplayed()
        scroll("18 not yet accounted for")
        compose.onNodeWithText("18 not yet accounted for").assertIsDisplayed()
        scroll("Mark bins fully inventoried");compose.onNodeWithText("Mark bins fully inventoried").performClick()
        compose.onNodeWithText("Finish inventory review").performClick()
        await { vm.bulkInventory.value.finishedAt != null }
        scroll("18 potentially missing");compose.onNodeWithText("18 potentially missing").assertIsDisplayed()
        compose.runOnIdle { vm.adjustQuantity(DiscoveryUiApplication.b,1,"later-correction") }
        await { vm.bulkInventory.value.finishedAt == null && vm.discovery.value.scopes.firstOrNull { it.id=="pack:p" }?.accounted==83 }
        scroll("17 not yet accounted for");compose.onNodeWithText("17 not yet accounted for").assertIsDisplayed()
        org.junit.Assert.assertFalse(vm.discoveryCsv(outstandingOnly=true).contains("potentially_missing"))
    }
    @Test fun cutoffChangesReferenceTargetsWithoutChangingOwnership() {
        compose.onNodeWithText("Collection").performClick();compose.onNodeWithText("Sets & cycles").performClick()
        scroll("Reference cutoff: YYYY-MM-DD (optional)")
        compose.onNodeWithText("Reference cutoff: YYYY-MM-DD (optional)").performTextReplacement("2020-12-31")
        scroll("Apply reference cutoff");compose.onNodeWithText("Apply reference cutoff").performClick()
        await { vm.bulkInventory.value.cutoff=="2020-12-31" && vm.discovery.value.scopes.firstOrNull { it.id=="pack:q" }?.inTarget==false }
        org.junit.Assert.assertEquals(82,vm.copies.value.count { it.status=="active" })
        org.junit.Assert.assertTrue(vm.discovery.value.scopes.first { it.id=="pack:p" }.inTarget)
    }
}

/** Synthetic test-only reference data, seeded before Room observers start. */
class DiscoveryUiApplication: ScanApplication() {
    override val database:ScanDatabase by lazy { Room.inMemoryDatabaseBuilder(this,ScanDatabase::class.java).build() }
    override fun onCreate() {
        super.onCreate()
        runBlocking {
            val future=a.copy(id="test:c",title="Future card",pack="q",packName="Future box",expectedQuantity=1)
            database.store().importCards(listOf(a,b,future),listOf(
                ReferencePack("p","Test box","cycle","Test cycle","2019-01-01","[]","","fixture","v1"),
                ReferencePack("q","Future box","cycle","Test cycle","2024-01-01","[]","","fixture","v1")))
            repeat(80) { database.store().confirm(a,"a$it","earlier-session") }
            repeat(2) { database.store().confirm(b,"b$it","later-session") }
        }
    }
    companion object {
        val a=CatalogCard("test:a","Card A","1","p","Test box","asset","","fixture","v1",expectedQuantity=80)
        val b=a.copy(id="test:b",title="Card B",number="2",expectedQuantity=20)
    }
}
