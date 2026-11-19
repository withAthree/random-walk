package com.randomwalk.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.randomwalk.app.walk.WalkService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationPermissionManifestTest {

    @Test
    fun manifestDeclaresRequiredPermissionsAndForegroundService() {
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
        assertFalse(
            "INTERNET must not be requested",
            requested.contains(Manifest.permission.INTERNET),
        )
        assertTrue(
            "FOREGROUND_SERVICE missing from manifest",
            requested.contains(Manifest.permission.FOREGROUND_SERVICE),
        )
        assertTrue(
            "FOREGROUND_SERVICE_LOCATION missing from manifest",
            requested.contains(Manifest.permission.FOREGROUND_SERVICE_LOCATION),
        )
        assertTrue(
            "POST_NOTIFICATIONS missing from manifest",
            requested.contains(Manifest.permission.POST_NOTIFICATIONS),
        )
        assertFalse(
            "ACCESS_BACKGROUND_LOCATION must not be requested",
            requested.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        )

        val service = context.packageManager.getServiceInfo(
            ComponentName(context, WalkService::class.java),
            0,
        )
        assertTrue(
            "WalkService must stop with the task",
            service.flags and ServiceInfo.FLAG_STOP_WITH_TASK != 0,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            assertTrue(
                "WalkService must run as a location foreground service",
                service.foregroundServiceType and
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0,
            )
        }
    }
}
