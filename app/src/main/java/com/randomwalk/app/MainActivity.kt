package com.randomwalk.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.randomwalk.app.debug.LocationDebugProbe
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.permission.LocationPermissionGate
import com.randomwalk.app.preparation.AndroidPreparationController
import com.randomwalk.app.preparation.PreparationController
import com.randomwalk.app.ui.ImmersiveMode
import com.randomwalk.app.ui.PreparationScreen
import com.randomwalk.app.ui.WalkSurface

class MainActivity : Activity() {

    private lateinit var messageView: TextView
    private lateinit var actionButton: Button
    private var permissionRequested = false
    private var walkSurface: WalkSurface? = null
    private var locationDebugProbe: LocationDebugProbe? = null
    private var preparationScreen: PreparationScreen? = null
    private var preparationController: PreparationController? = null
    private var startPlaceholderShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createPermissionGateView())
        ImmersiveMode.enter(window)

        if (savedInstanceState == null && !LocationPermissionGate.hasPreciseLocation(this)) {
            requestLocationPermission()
        } else {
            updatePermissionGate()
        }
    }

    private fun createPermissionGateView(): LinearLayout {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()

        messageView = TextView(this).apply {
            text = getString(R.string.permission_requesting)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }

        actionButton = Button(this).apply {
            text = getString(R.string.permission_retry)
            visibility = View.GONE
            setOnClickListener { onPermissionActionClicked() }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            addView(messageView)
            addView(
                actionButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (16 * density).toInt()
                },
            )
        }
    }

    private fun requestLocationPermission() {
        permissionRequested = true
        messageView.setText(R.string.permission_requesting)
        actionButton.visibility = View.GONE
        requestPermissions(
            LocationPermissionGate.REQUIRED_PERMISSIONS,
            LocationPermissionGate.REQUEST_CODE,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LocationPermissionGate.REQUEST_CODE) {
            permissionRequested = false
            updatePermissionGate()
        }
    }

    override fun onResume() {
        super.onResume()
        ImmersiveMode.enter(window)
        updatePermissionGate()
        walkSurface?.start()
    }

    override fun onPause() {
        stopDebugProbe()
        walkSurface?.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ImmersiveMode.enter(window)
        }
    }

    override fun onDestroy() {
        preparationScreen?.stop()
        preparationScreen = null
        preparationController?.close()
        preparationController = null
        stopDebugProbe()
        walkSurface?.stop()
        ImmersiveMode.exit(window)
        super.onDestroy()
    }

    private fun onPermissionActionClicked() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestLocationPermission()
        } else {
            openAppSettings()
        }
    }

    private fun updatePermissionGate() {
        if (!::messageView.isInitialized) return

        if (LocationPermissionGate.hasPreciseLocation(this)) {
            when {
                shouldSkipPreparation() -> {
                    showWalkSurface()
                    startDebugProbeIfNeeded()
                }

                walkSurface != null -> Unit

                preparationScreen != null || startPlaceholderShown -> Unit

                else -> showPreparation()
            }
            return
        }

        stopDebugProbe()
        preparationScreen?.stop()
        val hadPreparation = preparationScreen != null || startPlaceholderShown
        preparationScreen = null
        startPlaceholderShown = false
        preparationController?.close()
        preparationController = null
        if (walkSurface != null || hadPreparation) {
            walkSurface?.stop()
            walkSurface = null
            setContentView(createPermissionGateView())
        }

        if (permissionRequested && !LocationPermissionGate.hasPreciseLocation(this)) {
            messageView.setText(R.string.permission_requesting)
            actionButton.visibility = View.GONE
            return
        }

        when {
            LocationPermissionGate.hasApproximateLocation(this) -> {
                messageView.setText(R.string.permission_approximate_only)
                actionButton.visibility = View.VISIBLE
                actionButton.setText(actionLabel())
            }

            else -> {
                messageView.setText(R.string.permission_required)
                actionButton.visibility = View.VISIBLE
                actionButton.setText(actionLabel())
            }
        }
    }

    private fun showWalkSurface() {
        if (walkSurface != null) return
        val surface = WalkSurface(this)
        walkSurface = surface
        setContentView(surface)
        surface.start()
    }

    private fun showPreparation() {
        walkSurface?.stop()
        walkSurface = null
        startPlaceholderShown = false
        stopDebugProbe()

        if (preparationController == null) {
            preparationController = AndroidPreparationController(
                context = this,
                fixedCenter = readFixedCenter(),
            )
        }

        val screen = PreparationScreen(
            context = this,
            controller = preparationController!!,
            onReady = ::onPreparationReady,
            onCancelled = ::onPreparationCancelled,
        )
        preparationScreen = screen
        setContentView(screen)
        screen.start()
    }

    private fun onPreparationReady() {
        preparationScreen = null
        startPlaceholderShown = false
        showWalkSurface()
        startDebugProbeIfNeeded()
    }

    private fun onPreparationCancelled() {
        preparationScreen = null
        showStartPlaceholder()
    }

    private fun showStartPlaceholder() {
        startPlaceholderShown = true
        setContentView(createStartPlaceholderView())
    }

    private fun createStartPlaceholderView(): LinearLayout {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()

        val title = TextView(this).apply {
            text = getString(R.string.start_placeholder_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }

        val action = Button(this).apply {
            id = R.id.start_placeholder_action
            text = getString(R.string.start_placeholder_action)
            setOnClickListener { showPreparation() }
        }

        return LinearLayout(this).apply {
            id = R.id.start_placeholder
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(padding, padding, padding, padding)
            addView(title)
            addView(
                action,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (16 * density).toInt()
                },
            )
        }
    }

    private fun shouldSkipPreparation(): Boolean {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return debuggable && intent.getBooleanExtra(EXTRA_SKIP_DATA_PREPARATION, false)
    }

    private fun readFixedCenter(): LatLon? {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return null
        val lat = intent.getDoubleExtra(EXTRA_TEST_CENTER_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_TEST_CENTER_LON, Double.NaN)
        if (!lat.isFinite() || !lon.isFinite()) return null
        return try {
            LatLon(lat, lon)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun startDebugProbeIfNeeded() {
        if (locationDebugProbe != null) return
        if (!LocationPermissionGate.hasPreciseLocation(this)) return
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        locationDebugProbe = LocationDebugProbe(this).also { it.start() }
    }

    private fun stopDebugProbe() {
        locationDebugProbe?.stop()
        locationDebugProbe = null
    }

    private fun actionLabel(): Int =
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            R.string.permission_retry
        } else {
            R.string.permission_open_settings
        }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    companion object {
        const val EXTRA_SKIP_DATA_PREPARATION = "com.randomwalk.app.extra.SKIP_DATA_PREPARATION"
        const val EXTRA_TEST_CENTER_LAT = "com.randomwalk.app.extra.TEST_CENTER_LAT"
        const val EXTRA_TEST_CENTER_LON = "com.randomwalk.app.extra.TEST_CENTER_LON"
    }
}
