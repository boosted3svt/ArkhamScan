package com.arkhamscan

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasContentDescription
import kotlinx.coroutines.runBlocking
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import org.junit.Before
import org.robolectric.Shadows
import android.os.Looper
import androidx.room.Room
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = UiTestApplication::class)
class NavigationTest {
    private lateinit var model: ScanViewModel
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun awaitDatabaseStartup() {
        lateinit var vm: ScanViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[ScanViewModel::class.java] }
        try { compose.waitUntil(10_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.databaseReady.value
        } }
        catch (e: Throwable) { throw AssertionError("Database startup: ${vm.message.value}", e) }
        model = vm
    }
    @org.junit.After fun closeDatabase() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        (compose.activity.application as ScanApplication).database.close()
    }
    @Test fun firstRunOffersOnlineCatalogWithoutPreparingAFile() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Download from ArkhamDB"))
        compose.onNodeWithText("Download from ArkhamDB").assertIsDisplayed()
        compose.onNodeWithText("Browse ArkhamDB website").assertExists()
    }
    @Test fun collectionDoesNotClaimUnverifiedCompleteness() {
        compose.onNodeWithText("Collection").performClick()
        compose.onNodeWithText("Bulk collection progress").assertIsDisplayed()
    }
    @Test fun scanningRequiresDeliberateCameraPermissionAndShowsWorkflow() {
        compose.onNodeWithText("Scan").performClick()
        compose.onNodeWithText("Rapid · uncertain cards to Re-sort").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Allow camera"))
        compose.onNodeWithText("Allow camera").assertIsDisplayed()
    }
}

/** UI tests isolate storage; StoreTest separately verifies real file persistence. */
class UiTestApplication : ScanApplication() {
    override val database: ScanDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, ScanDatabase::class.java).build()
    }
}
