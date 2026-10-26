package lk.safeheaven.blackbox

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lk.safeheaven.core.EvidenceChain
import lk.safeheaven.data.Prefs
import lk.safeheaven.net.SupabaseApi
import lk.safeheaven.trip.TripBus
import org.json.JSONObject
import java.io.File

/**
 * Offline evidence queue in app-private storage. Item seq 0 is the plate photo captured at trip
 * start, whose SHA-256 is the chain root h0. Items are flushed strictly in sequence to the
 * `evidence` Edge Function, which recomputes the chain and returns a receipt that is stored next
 * to the item together with the device id.
 */
class EvidenceStore(private val context: Context, private val alertId: String) {
    private val dir = File(context.filesDir, "evidence/$alertId").apply { mkdirs() }
    private val lock = Mutex()
    private val prefs = Prefs.get(context)

    private fun meta(seq: Int) = File(dir, "%06d.json".format(seq))

    /** Registers the plate photo as seq 0 (idempotent). */
    suspend fun addPhoto(photo: File, takenAtMs: Long, lat: Double, lon: Double) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (meta(0).exists()) return@withContext
            val bytes = photo.readBytes()
            val data = File(dir, "000000.jpg")
            data.writeBytes(bytes)
            val h0 = EvidenceChain.hex(EvidenceChain.h0(bytes))
            meta(0).writeText(
                JSONObject()
                    .put("seq", 0).put("kind", "photo").put("file", data.name)
                    .put("ts", takenAtMs).put("lat", lat).put("lon", lon)
                    .put("sha256", h0).put("prev", JSONObject.NULL)
                    .put("device_id", prefs.deviceId)
                    .put("uploaded", false)
                    .toString(),
            )
        }
    }

    /** Queues one 5 s audio chunk that has already been linked into the hash chain. */
    suspend fun addAudioChunk(
        seq: Int, bytes: ByteArray, tsMs: Long, lat: Double, lon: Double, prevHex: String, hashHex: String,
    ) = lock.withLock {
        withContext(Dispatchers.IO) {
            val data = File(dir, "%06d.m4a".format(seq))
            data.writeBytes(bytes)
            meta(seq).writeText(
                JSONObject()
                    .put("seq", seq).put("kind", "audio").put("file", data.name)
                    .put("ts", tsMs).put("lat", lat).put("lon", lon)
                    .put("sha256", hashHex).put("prev", prevHex)
                    .put("device_id", prefs.deviceId)
                    .put("uploaded", false)
                    .toString(),
            )
        }
    }

    /** Uploads pending items in order; stops at the first failure so the chain stays gap-free. */
    suspend fun flush(api: SupabaseApi): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!api.configured || !api.isOnline()) return@withContext 0
            var sent = 0
            val metas = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
            for (m in metas) {
                val o = JSONObject(m.readText())
                if (o.optBoolean("uploaded")) continue
                val headers = buildMap {
                    put("X-Alert-Id", alertId)
                    put("X-Seq", o.getInt("seq").toString())
                    put("X-Kind", o.getString("kind"))
                    put("X-Sha256", o.getString("sha256"))
                    if (!o.isNull("prev")) put("X-Prev-Hash", o.getString("prev"))
                    put("X-Client-Ts", o.getLong("ts").toString())
                    put("X-Lat", o.getDouble("lat").toString())
                    put("X-Lon", o.getDouble("lon").toString())
                    put("X-Device-Id", o.getString("device_id"))
                }
                val receipt = runCatching {
                    api.uploadEvidence(headers, File(dir, o.getString("file")).readBytes())
                }.getOrElse {
                    TripBus.log("evidence seq ${o.getInt("seq")} queued: ${it.message}")
                    return@withContext sent
                }
                o.put("uploaded", true).put("receipt", receipt)
                m.writeText(o.toString())
                sent++
                TripBus.log("evidence seq ${o.getInt("seq")} stored, server ${receipt.optString("server_received_at")}")
            }
            sent
        }
    }
}
