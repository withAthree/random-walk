package com.randomwalk.app.preparation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.randomwalk.app.data.DataReadinessGate
import com.randomwalk.app.data.PreparationFailure
import com.randomwalk.app.data.PreparationResult
import com.randomwalk.app.data.StreetDatabaseProvider
import com.randomwalk.app.data.TileCache
import com.randomwalk.app.data.net.OnlineOsmFetcher
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.location.LocationSampler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the readiness gate on a single background worker. All UI state is posted
 * to the main thread and tagged with a generation so stale workers cannot post
 * or replace the ready provider after a cancel, retry, or close.
 */
class AndroidPreparationController(
    context: Context,
    private val fixedCenter: LatLon? = null,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
) : PreparationController {

    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var generation = 0

    @Volatile
    private var cancelled = false

    @Volatile
    private var cancelledPosted = false

    private var listener: ((PreparationUiState) -> Unit)? = null
    private var forceRefresh = false

    @Volatile
    private var gate: DataReadinessGate? = null

    @Volatile
    private var sampler: LocationSampler? = null

    @Volatile
    private var locationLatch: CountDownLatch? = null

    private var provider: StreetDatabaseProvider? = null

    override fun start(listener: (PreparationUiState) -> Unit) {
        val capture: Int
        val refresh: Boolean
        synchronized(lock) {
            generation++
            cancelled = false
            cancelledPosted = false
            this.listener = listener
            refresh = forceRefresh
            forceRefresh = false
            capture = generation
        }

        postState(capture) { PreparationUiState.Preparing(0, 0) }
        workerExecutor.submit { runWorker(capture, refresh) }
    }

    override fun cancel() {
        val capture: Int = synchronized(lock) {
            generation++
            cancelled = true
            generation
        }
        stopSampler()
        gate?.cancel()
        locationLatch?.countDown()
        if (markCancelledPosted(capture)) {
            postState(capture) { PreparationUiState.Cancelled }
        }
    }

    override fun retry() {
        val current = listener ?: return
        forceRefresh = true
        start(current)
    }

    override fun close() {
        val toClose: StreetDatabaseProvider? = synchronized(lock) {
            generation++
            cancelled = true
            val current = provider
            provider = null
            current
        }
        stopSampler()
        gate?.cancel()
        locationLatch?.countDown()
        workerExecutor.shutdown()
        listener = null
        toClose?.close()
    }

    private fun runWorker(capture: Int, forceRefresh: Boolean) {
        var workerProvider: StreetDatabaseProvider? = null
        try {
            if (capture != generation) return

            val center = obtainCenter(capture) ?: run {
                if (capture == generation) {
                    postState(capture) {
                        PreparationUiState.Failed(PreparationFailure.LOCATION_UNAVAILABLE)
                    }
                }
                return
            }

            if (capture != generation) return

            workerProvider = StreetDatabaseProvider.open(appContext)
            val createdGate = DataReadinessGate(
                tileEnsurer = TileCache(
                    coverage = workerProvider.repository,
                    store = workerProvider.cacheStore,
                    fetcher = OnlineOsmFetcher(),
                ),
                repository = workerProvider.repository,
            )
            gate = createdGate

            if (capture != generation) return

            val result = createdGate.prepare(
                center,
                forceRefresh,
                isCancelled = { cancelled || capture != generation },
                onProgress = { completed, total ->
                    postState(capture) { PreparationUiState.Preparing(completed, total) }
                },
            )

            if (capture != generation) return

            when (result) {
                is PreparationResult.Ready -> {
                    val keepOpen = synchronized(lock) {
                        if (capture == generation) {
                            provider = workerProvider
                            true
                        } else {
                            false
                        }
                    }
                    if (keepOpen) {
                        workerProvider = null
                        postState(capture) { PreparationUiState.Ready }
                    }
                }

                is PreparationResult.Failed -> {
                    postState(capture) { PreparationUiState.Failed(result.reason) }
                }

                is PreparationResult.Cancelled -> {
                    if (markCancelledPosted(capture)) {
                        postState(capture) { PreparationUiState.Cancelled }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "preparation failed", e)
            postState(capture) {
                PreparationUiState.Failed(PreparationFailure.SERVER_ERROR)
            }
        } finally {
            workerProvider?.close()
            gate = null
        }
    }

    private fun obtainCenter(capture: Int): LatLon? {
        val fixed = fixedCenter
        if (fixed != null) return fixed

        val latch = CountDownLatch(1)
        locationLatch = latch
        val fix = AtomicReference<LatLon?>()

        mainHandler.post {
            if (capture != generation) {
                latch.countDown()
                return@post
            }
            val created = LocationSampler(
                context = appContext,
                onAccepted = { sample ->
                    if (capture == generation &&
                        fix.compareAndSet(null, LatLon(sample.latitude, sample.longitude))
                    ) {
                        sampler?.stop()
                        latch.countDown()
                    }
                },
            )
            sampler = created
            created.start()
        }

        try {
            latch.await(LOCATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        mainHandler.post {
            sampler?.stop()
            sampler = null
        }

        return fix.get()
    }

    private fun postState(capture: Int, state: () -> PreparationUiState) {
        mainHandler.post {
            if (capture != generation) return@post
            listener?.invoke(state())
        }
    }

    private fun markCancelledPosted(capture: Int): Boolean = synchronized(lock) {
        if (capture != generation || cancelledPosted) {
            false
        } else {
            cancelledPosted = true
            true
        }
    }

    private fun stopSampler() {
        val running = sampler
        sampler = null
        if (running != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                running.stop()
            } else {
                mainHandler.post { running.stop() }
            }
        }
    }

    private companion object {
        const val TAG = "AndroidPreparationController"
        const val LOCATION_TIMEOUT_MILLIS = 30_000L
    }
}
