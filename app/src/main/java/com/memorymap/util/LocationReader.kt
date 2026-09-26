package com.memorymap.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.memorymap.domain.model.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the device location without Google Play services.
 *
 * Location is read **only** when the user asks for it — pressing "I am here" or
 * picking a place for a memory. Nothing here keeps a listener alive, nothing runs
 * in the background, and no position is stored or uploaded.
 */
object LocationReader {

    /** How long a one-shot fix may take before the caller gives up. */
    const val DEFAULT_TIMEOUT_MS = 12_000L

    private val PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    )

    /** True when the device can report a position at all. */
    fun isAvailable(context: Context): Boolean = manager(context)?.let { manager ->
        PROVIDERS.any { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    } ?: false

    /**
     * The most recent position the system already has. It costs no radio time and
     * is usually enough to centre a map.
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): GeoPoint? = manager(context)?.let { manager ->
        (PROVIDERS + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(latitude = it.latitude, longitude = it.longitude) }
    }

    /**
     * A fresh fix, waiting at most [timeoutMs]. Returns null on timeout rather
     * than hanging the UI; the caller decides what to show.
     *
     * The listener is removed the moment a position arrives and when the call is
     * cancelled or times out, so a listener is never left registered.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): GeoPoint? {
        lastKnown(context)?.let { return it }

        val manager = manager(context) ?: return null
        val provider = PROVIDERS.firstOrNull {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        } ?: return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { manager.removeUpdates(this) }
                        if (continuation.isActive) {
                            continuation.resume(GeoPoint(location.latitude, location.longitude))
                        }
                    }
                }

                runCatching {
                    manager.requestLocationUpdates(
                        provider,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper(),
                    )
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }

                continuation.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
            }
        }
    }

    private fun manager(context: Context): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
}
