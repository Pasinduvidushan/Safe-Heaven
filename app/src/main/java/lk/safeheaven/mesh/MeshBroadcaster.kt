package lk.safeheaven.mesh

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lk.safeheaven.core.Fix
import lk.safeheaven.core.MeshPacket
import lk.safeheaven.data.KeyVault
import lk.safeheaven.data.Prefs
import lk.safeheaven.trip.TripBus

/**
 * Victim broadcaster used in MESH_MODE. Alternates Type A (location) and Type B (plate) packets
 * every second, each freshly encrypted under the next monotonic counter (= AES-CTR nonce).
 */
class MeshBroadcaster(private val context: Context) {
    private val prefs = Prefs.get(context)
    private val advertiser = MeshAdvertiser(context)
    private var job: Job? = null

    val supported get() = advertiser.supported

    fun start(
        scope: CoroutineScope,
        panicStartMs: Long,
        plate: String,
        fix: () -> Fix?,
        batteryPct: () -> Int,
        flags: () -> Int,
    ) {
        if (job?.isActive == true) return
        val keys = KeyVault.meshKeys(prefs)
        val senderId = prefs.meshSenderId.coerceAtLeast(0)
        job = scope.launch(Dispatchers.Default) {
            var sendPlate = false
            while (isActive) {
                val body = if (sendPlate) {
                    MeshPacket.Body.Plate(plate)
                } else {
                    val f = fix()
                    MeshPacket.Body.Location(
                        lat = f?.lat ?: 0.0,
                        lon = f?.lon ?: 0.0,
                        secondsSincePanic = ((System.currentTimeMillis() - panicStartMs) / 1000).toInt(),
                        accuracyM = (f?.accuracyM ?: 0f).toInt(),
                        batteryPct = batteryPct(),
                        flags = flags(),
                    )
                }
                val counter = prefs.nextMeshCounter()
                val packet = MeshPacket.encode(keys, senderId, counter, MeshPacket.INITIAL_TTL, body)
                advertiser.broadcast(packet)
                sendPlate = !sendPlate
                delay(1_000)
            }
        }
        TripBus.log("mesh broadcast started (Type A/B every 1 s)")
    }

    fun stop() {
        job?.cancel()
        job = null
        advertiser.stop()
        TripBus.log("mesh broadcast stopped")
    }
}
