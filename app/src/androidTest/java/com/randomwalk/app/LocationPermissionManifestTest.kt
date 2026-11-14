package com.randomwalk.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationPermissionManifestTest {

    @Test
    fun manifestDeclaresRequiredPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = packageInfo.requestedPermissions?.toSet().orEmpty()

        assertTrue(
            "ACCESS_FINE_LOCATION missing from manifest",
            requested.contains(Manifest.permission.ACCESS_FINE_LOCATION),
        )
        assertTrue(
            "ACCESS_COARSE_LOCATION missing from manifest",
            requested.contains(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        assertTrue(
            "INTERNET missing from manifest",
            requested.contains(Manifest.permission.INTERNET),
        )
    }
}
