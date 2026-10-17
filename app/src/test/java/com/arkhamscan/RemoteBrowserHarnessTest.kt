package com.arkhamscan

import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/** Optional actual HTTP + desktop-browser integration; invoked by tools/test-remote-browser.sh. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=RemoteTestApplication::class)
class RemoteBrowserHarnessTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun desktopBrowserControlsTheActualPhoneSession() {
        lateinit var vm: ScanViewModel
        compose.runOnIdle { vm=ViewModelProvider(compose.activity)[ScanViewModel::class.java] }
        compose.waitUntil(15_000) { Shadows.shadowOf(Looper.getMainLooper()).idle();vm.databaseReady.value && vm.cards.value.size==2 }
        val dir=File(requireNotNull(System.getProperty("arkhamscan.browserHarnessDir")))
        val server=RemoteHttpServer(compose.activity.assets,vm.remote,0);server.startServer()
        try {
            File(dir,"ready.json").writeText(JSONObject().put("url","http://127.0.0.1:${server.listeningPort}").put("code",server.pairCode).toString())
            var injected=false
            compose.waitUntil(90_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (!injected && File(dir,"inject").exists()) {
                    vm.recognizedText("Synthetic Card")
                    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
                    vm.recognizedText("Synthetic Card");injected=true
                }
                File(dir,"done").exists()
            }
            Assert.assertEquals("pass",File(dir,"done").readText())
        } finally {
            server.stop();compose.runOnIdle { compose.activity.viewModelStore.clear() };(compose.activity.application as ScanApplication).database.close()
        }
    }
}
