package com.arkhamscan

import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.lifecycle.ViewModelProvider
import android.os.Looper
import org.robolectric.Shadows
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityTest {
    @Test fun appLaunchesWithoutCatalogOrCameraPermission() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val vm = ViewModelProvider(controller.get())[ScanViewModel::class.java]
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!vm.databaseReady.value && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Database startup completed", vm.databaseReady.value)
        val content = controller.get().findViewById<ViewGroup>(android.R.id.content)
        assertTrue(content.childCount > 0)
        controller.pause().stop().destroy()
    }
    @Test fun mergedManifestAllowsCatalogDownloadsButBlocksCleartext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions ?: emptyArray()
        assertTrue(permissions.contains(android.Manifest.permission.INTERNET))
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
    }
}
