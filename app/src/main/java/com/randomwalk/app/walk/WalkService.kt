package com.randomwalk.app.walk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.randomwalk.app.MainActivity
import com.randomwalk.app.R
import com.randomwalk.app.location.LocationSample
import com.randomwalk.app.location.LocationSampler
import com.randomwalk.app.permission.LocationPermissionGate
import com.randomwalk.app.ui.WalkController
import java.util.concurrent.CopyOnWriteArrayList

interface WalkLocationSource {
    fun start()
    fun stop()
}

object WalkServiceDependencies {
    private val defaultLocationSourceFactory:
        (Context, (LocationSample) -> Unit) -> WalkLocationSource = { context, onAccepted ->
        val sampler = LocationSampler(context, onAccepted = onAccepted)
        object : WalkLocationSource {
            override fun start() = sampler.start()
            override fun stop() = sampler.stop()
        }
    }

    private val defaultGeneratorFactory: () -> LegGenerator = { RandomLegGenerator() }

    var locationSourceFactory: (Context, (LocationSample) -> Unit) -> WalkLocationSource =
        defaultLocationSourceFactory
    var generatorFactory: () -> LegGenerator = defaultGeneratorFactory

    fun reset() {
        locationSourceFactory = defaultLocationSourceFactory
        generatorFactory = defaultGeneratorFactory
    }
}

class WalkService : Service() {

    inner class LocalBinder : Binder(), WalkController {
        override fun currentState(): WalkState = engine?.state ?: WalkState()

        override fun addListener(listener: (WalkState?) -> Unit) {
            listeners += listener
            listener(currentState())
        }

        override fun removeListener(listener: (WalkState?) -> Unit) {
            listeners -= listener
        }

        override fun reroll() {
            engine?.reroll()
        }

        override fun stopWalk() {
            stopWalkAndSelf()
        }
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(WalkState?) -> Unit>()
    private var engine: WalkSession? = null
    private var locationSource: WalkLocationSource? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWalk()
            ACTION_STOP -> stopWalkAndSelf()
            ACTION_REROLL -> engine?.reroll()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startWalk() {
        if (engine != null) {
            startForegroundNotification(engine?.state ?: WalkState())
            return
        }
        if (!LocationPermissionGate.hasPreciseLocation(this)) {
            stopSelf()
            return
        }

        stopping = false
        startForegroundNotification(WalkState())

        val session = WalkSession(WalkServiceDependencies.generatorFactory()) { state ->
            publish(state)
        }
        engine = session

        val source = WalkServiceDependencies.locationSourceFactory(this) { sample ->
            session.onLocation(sample)
        }
        locationSource = source
        source.start()
        publish(session.state)
    }

    private fun publish(state: WalkState) {
        if (stopping) return
        mainHandler.post {
            if (stopping) return@post
            updateNotification(state)
            listeners.forEach { it(state) }
        }
    }

    private fun startForegroundNotification(state: WalkState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: WalkState) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: WalkState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WalkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (state.waitingForLocation) {
            getString(R.string.walk_waiting)
        } else {
            getString(R.string.walk_remaining_notification, state.remainingMeters ?: 0)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_walk_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_walk_notification),
                    getString(R.string.walk_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    private fun stopWalkAndSelf() {
        if (stopping) return
        stopping = true
        locationSource?.stop()
        locationSource = null
        engine?.stop()
        engine = null
        mainHandler.post {
            listeners.forEach { it(null) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopWalkAndSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopping = true
        locationSource?.stop()
        locationSource = null
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.walk_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.walk_notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.randomwalk.app.action.START_WALK"
        const val ACTION_STOP = "com.randomwalk.app.action.STOP_WALK"
        const val ACTION_REROLL = "com.randomwalk.app.action.REROLL"
        private const val CHANNEL_ID = "walk"
        private const val NOTIFICATION_ID = 1
    }
}
