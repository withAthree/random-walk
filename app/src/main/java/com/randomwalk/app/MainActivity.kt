package com.randomwalk.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.randomwalk.app.permission.LocationPermissionGate
import com.randomwalk.app.ui.ImmersiveMode
import com.randomwalk.app.ui.WalkSurface

class MainActivity : Activity() {

    private lateinit var messageView: TextView
    private lateinit var actionButton: Button
    private var permissionRequested = false
    private var walkSurface: WalkSurface? = null

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
            showWalkSurface()
            return
        }

        if (walkSurface != null) {
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
}
