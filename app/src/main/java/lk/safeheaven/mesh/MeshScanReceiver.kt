package lk.safeheaven.mesh

import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lk.safeheaven.core.MeshPacket
import lk.safeheaven.data.Prefs
import lk.safeheaven.net.SupabaseApi
import lk.safeheaven.trip.TripBus

/**
 * Receives filtered scan results (wakes even with the screen off). For each fresh packet:
 *  - online  -> POST the raw bytes to the mesh-relay Edge Function within ~1 s
 *  - offline and TTL > 0 -> decrement TTL and re-advertise the packet for 60 s
 *
 * The Demo Gateway toggle makes this instance ignore direct packets (hops == 0) and act purely as
 * an uploader of relayed packets (hops >= 1).
 */
class MeshScanReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MeshScanner.ACTION_SCAN_RESULT) return
        val results = extractResults(intent)
        if (results.isEmpty()) return
        val prefs = Prefs.get(context)
        val api = SupabaseApi.get(context)
        val pending = goAsync()
        scope.launch {
            try {
                for (result in results) {
                    val payload = MeshScanner.manufacturerData(result.scanRecord?.bytes) ?: continue
                    handlePacket(context, prefs, api, payload)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handlePacket(context: Context, prefs: Prefs, api: SupabaseApi, packet: ByteArray) {
        val header = MeshPacket.parseHeader(packet) ?: return
        if (header.senderId == prefs.meshSenderId) return // ignore our own broadcast
        if (prefs.gatewayOnlyRelayed && header.hops == 0) {
            TripBus.log("gateway: ignoring direct packet from ${header.senderId}")
            return
        }
        if (!SeenCache.firstTime(header.senderId, header.counter)) return
        TripBus.log("mesh rx sender=${header.senderId} ctr=${header.counter} hops=${header.hops} ttl=${header.ttl}")

        if (api.configured && api.isOnline()) {
            runCatching { api.meshRelay(packet, timeoutMs = 1_000) }
                .onSuccess { TripBus.log("relayed to server: ${it.optString("type", "ok")}") }
                .onFailure { reAdvertise(context, packet) }
        } else {
            reAdvertise(context, packet)
        }
    }

    /** Offline forward: TTL-1, re-broadcast for 60 s, then stop. */
    private fun reAdvertise(context: Context, packet: ByteArray) {
        val forwarded = MeshPacket.relayed(packet) ?: run {
            TripBus.log("mesh TTL spent, not forwarding")
            return
        }
        val adv = MeshAdvertiser(context)
        if (!adv.supported) return
        adv.broadcast(forwarded)
        TripBus.log("offline relay: re-advertising for 60 s (ttl now ${forwarded[8].toInt()})")
        scope.launch {
            kotlinx.coroutines.delay(60_000)
            adv.stop()
        }
    }

    private fun extractResults(intent: Intent): List<ScanResult> {
        val code = intent.getIntExtra(android.bluetooth.le.BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        if (code != -1) {
            TripBus.log("mesh scan error extra: $code")
            return emptyList()
        }
        val list = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(
                android.bluetooth.le.BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(android.bluetooth.le.BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        }
        return list ?: emptyList()
    }
}
