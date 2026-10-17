package com.arkhamscan

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import android.os.Looper
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LiveUiApplication::class)
class LiveUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: ScanViewModel
    @Before fun ready() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[ScanViewModel::class.java] }
        compose.waitUntil(10_000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); model.databaseReady.value }
    }
    @Test fun heldSuggestionSurvivesDropoutAndConfirmationShowsPileThenQuantityControlsWork() {
        compose.waitUntil(10_000) { compose.waitForIdle(); Shadows.shadowOf(Looper.getMainLooper()).idle(); model.cards.value.size == 1 }
        compose.onNodeWithText("Scan").performClick()
        compose.runOnIdle { model.recognizedText("Synthetic Card") }
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        compose.runOnIdle { model.recognizedText("Synthetic Card") }
        compose.waitUntil(10_000) { model.suggestions.value.isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Synthetic Card"))
        compose.onNodeWithText("Synthetic Card").assertIsDisplayed()
        compose.runOnIdle { model.recognizedText("") }
        compose.onNodeWithText("Synthetic Card").assertIsDisplayed().performClick()
        compose.onNodeWithText("New physical copy").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); Shadows.shadowOf(Looper.getMainLooper()).idle(); model.lastPile.value != null }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Pile 1 · Test box"))
        compose.onNodeWithText("Pile 1 · Test box").assertIsDisplayed()
        compose.onNodeWithText("Collection").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Increase Synthetic Card"))
        compose.onNodeWithContentDescription("Increase Synthetic Card").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); Shadows.shadowOf(Looper.getMainLooper()).idle(); model.copies.value.count { it.status=="active" } == 2 }
        compose.onNodeWithContentDescription("Decrease Synthetic Card").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); Shadows.shadowOf(Looper.getMainLooper()).idle(); model.copies.value.count { it.status=="active" } == 1 }
        org.junit.Assert.assertEquals(1, model.livePlacements.value.size)
    }
}

/** Seed before activity launch, rather than racing Room's initial flow subscription. */
class LiveUiApplication : ScanApplication() {
    override val database: ScanDatabase by lazy { Room.inMemoryDatabaseBuilder(this, ScanDatabase::class.java).build() }
    override fun onCreate() {
        super.onCreate()
        runBlocking { database.store().importCards(listOf(CatalogCard("test:a", "Synthetic Card", "1", "p", "Test box", "", "", "fixture", "v1"))) }
    }
}
