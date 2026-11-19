package com.randomwalk.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.randomwalk.app.permission.LocationPermissionGate
import com.randomwalk.app.ui.ImmersiveMode
import com.randomwalk.app.ui.WalkController
import com.randomwalk.app.ui.WalkSurface
import com.randomwalk.app.walk.WalkService

class MainActivity : Activity() {

    private lateinit var messageView: TextView
    private lateinit var actionButton: Button
    private var permissionRequested = false
    private var notificationPermissionRequested = false
    private var walkSurface: WalkSurface? = null
    private var walkController: WalkController? = null
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            walkController = service as WalkService.LocalBinder
            walkSurface?.bind(walkController!!)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            walkController = null
            walkSurface?.unbind()
        }
    }

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

    override fun onStart() {
        super.onStart()
        if (LocationPermissionGate.hasPreciseLocation(this)) {
            startWalkService()
            bindWalkService()
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

    override fun onStop() {
        unbindWalkService()
        walkSurface?.stop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ImmersiveMode.enter(window)
        }
    }

    override fun onDestroy() {
        unbindWalkService()
        walkSurface?.release()
        walkSurface = null
        ImmersiveMode.exit(window)
        super.onDestroy()
    }

    override fun onBackPressed() {
        stopWalk()
        super.onBackPressed()
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
            if (LocationPermissionGate.hasPreciseLocation(this)) {
                maybeRequestNotificationPermission()
            }
            updatePermissionGate()
        }
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
            startWalkService()
            bindWalkService()
            return
        }

        stopWalk()
        walkSurface?.release()
        walkSurface = null
        setContentView(createPermissionGateView())

        if (permissionRequested) {
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
        surface.onWalkEnded = { if (!isFinishing) finish() }
        walkSurface = surface
        setContentView(surface)
        walkController?.let(surface::bind)
        surface.start()
    }

    private fun startWalkService() {
        if (!LocationPermissionGate.hasPreciseLocation(this)) return
        maybeRequestNotificationPermission()
        startForegroundService(
            Intent(this, WalkService::class.java).setAction(WalkService.ACTION_START),
        )
    }

    private fun bindWalkService() {
        if (serviceBound) return
        serviceBound = bindService(
            Intent(this, WalkService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun unbindWalkService() {
        if (!serviceBound) return
        unbindService(connection)
        serviceBound = false
        walkController = null
        walkSurface?.unbind()
    }

    private fun stopWalk() {
        walkController?.stopWalk()
        stopService(Intent(this, WalkService::class.java))
        walkController = null
        walkSurface?.unbind()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (notificationPermissionRequested) return
        notificationPermissionRequested = true
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
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
        private const val REQUEST_NOTIFICATIONS = 200
    }
}
