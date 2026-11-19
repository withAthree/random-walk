package com.randomwalk.app

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.location.LocationSample
import com.randomwalk.app.walk.LegGenerator
import com.randomwalk.app.walk.WalkLeg
import com.randomwalk.app.walk.WalkLocationSource
import com.randomwalk.app.walk.WalkService
import com.randomwalk.app.walk.WalkServiceDependencies
import com.randomwalk.app.walk.WalkState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WalkServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val origin = LatLon(48.8566, 2.3522)

    @get:Rule
    val permissions: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) {
            GrantPermissionRule.grant(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            GrantPermissionRule.grant(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    private class FakeSource : WalkLocationSource {
        var starts = 0
        var stops = 0
        override fun start() { starts++ }
        override fun stop() { stops++ }
    }

    private class FixedGenerator(private val leg: WalkLeg) : LegGenerator {
        var calls = 0
            private set

        override fun next(from: LatLon, excludedBearings: List<Float>): WalkLeg {
            calls++
            return leg
        }
    }

    private lateinit var source: FakeSource
    private lateinit var generator: FixedGenerator
    private var emit: ((LocationSample) -> Unit)? = null
    private var controller: WalkService.LocalBinder? = null
    private var bound = false
    private var connection: ServiceConnection? = null
    private val states = mutableListOf<WalkState?>()
    private val stopLatch = CountDownLatch(1)

    @Before
    fun setUp() {
        source = FakeSource()
        generator = FixedGenerator(
            WalkLeg(
                target = GeoMath.offsetMeters(origin, 0.0, 80.0),
                bearingDegrees = 0f,
                distanceMeters = 80,
            ),
        )
        WalkServiceDependencies.locationSourceFactory = { _, onAccepted ->
            emit = onAccepted
            source
        }
        WalkServiceDependencies.generatorFactory = { generator }
        states.clear()
        controller = null
        bound = false
    }

    @After
    fun tearDown() {
        connection?.let { if (bound) context.unbindService(it) }
        context.stopService(Intent(context, WalkService::class.java))
        WalkServiceDependencies.reset()
    }

    private fun startAndBind() {
        context.startForegroundService(
            Intent(context, WalkService::class.java).setAction(WalkService.ACTION_START),
        )
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as WalkService.LocalBinder
                controller = binder
                binder.addListener { state ->
                    synchronized(states) { states += state }
                    if (state == null) stopLatch.countDown()
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                controller = null
            }
        }
        connection = conn
        bound = context.bindService(
            Intent(context, WalkService::class.java),
            conn,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    private fun awaitState(predicate: (WalkState?) -> Boolean): WalkState? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val snapshot = synchronized(states) { states.toList() }
            snapshot.lastOrNull(predicate)?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    private fun activeNotificationId(): Int? =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications
            .firstOrNull { it.packageName == context.packageName }
            ?.id

    @Test
    fun startShowsWaitingAndNotification_thenFirstFixStartsALeg() {
        startAndBind()

        assertTrue(controller!!.currentState().waitingForLocation)
        assertEquals(0, generator.calls)
        assertEquals(1, source.starts)
        assertTrue(activeNotificationId() != null)

        emit?.invoke(LocationSample(origin.latitude, origin.longitude, 5.0, 0L))

        val state = awaitState { it?.waitingForLocation == false }
        assertEquals(80, state!!.remainingMeters)
        assertEquals(1, generator.calls)
        assertFalse(state.waitingForLocation)
    }

    @Test
    fun rerollGeneratesANewLeg() {
        startAndBind()
        emit?.invoke(LocationSample(origin.latitude, origin.longitude, 5.0, 0L))
        awaitState { it?.waitingForLocation == false }

        controller!!.reroll()

        assertEquals(2, generator.calls)
    }

    @Test
    fun stopStopsTheSourceAndEndsTheService() {
        startAndBind()
        emit?.invoke(LocationSample(origin.latitude, origin.longitude, 5.0, 0L))
        awaitState { it?.waitingForLocation == false }

        controller!!.stopWalk()

        assertTrue(stopLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, source.stops)
    }
}
