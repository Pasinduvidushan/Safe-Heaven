package lk.safeheaven.trip

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import lk.safeheaven.core.Fix

/**
 * Single source of location fixes for the whole app. Real GPS (FusedLocationProviderClient,
 * 5 s cadence) and the debug GPX replay produce the same [Fix] type into the same flow, so the
 * route watcher, panic trail and mesh packets cannot tell them apart.
 */
object LocationHub {
    const val INTERVAL_MS = 5_000L

    fun fixes(context: Context): Flow<Fix> =
        if (ReplayController.enabled) replay() else gps(context.applicationContext)

    @SuppressLint("MissingPermission")
    private fun gps(context: Context): Flow<Fix> = callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    trySend(Fix(it.latitude, it.longitude, System.currentTimeMillis(), it.accuracy, it.speed))
                }
            }
        }
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(cb) }
    }

    /** Virtual timestamps advance at GPX pace; wall-clock delay is divided by the replay speed. */
    private fun replay(): Flow<Fix> = flow {
        val track = ReplayController.driveTrack()
        val start = System.currentTimeMillis()
        var prev = 0L
        for (p in track) {
            val wait = ((p.offsetMs - prev) / ReplayController.speed).coerceAtLeast(0)
            if (wait > 0) delay(wait)
            prev = p.offsetMs
            emit(Fix(p.lat, p.lon, start + p.offsetMs, 5f, 10f))
        }
        // Hold the last point so the stationary rule and panic trail keep receiving fixes.
        var t = prev
        while (true) {
            delay((INTERVAL_MS / ReplayController.speed).coerceAtLeast(200))
            t += INTERVAL_MS
            val last = track.last()
            emit(Fix(last.lat, last.lon, start + t, 5f, 0f))
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun lastKnown(context: Context): Fix? {
        if (ReplayController.enabled) {
            val p = ReplayController.driveTrack().first()
            return Fix(p.lat, p.lon, System.currentTimeMillis())
        }
        val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        val loc = runCatching {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                ?: client.lastLocation.await()
        }.getOrNull() ?: return null
        return Fix(loc.latitude, loc.longitude, System.currentTimeMillis(), loc.accuracy, loc.speed)
    }
}
