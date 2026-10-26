package lk.safeheaven.trip

import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import lk.safeheaven.blackbox.AudioChunker
import lk.safeheaven.blackbox.EvidenceStore
import lk.safeheaven.core.EvidenceChain
import lk.safeheaven.core.MeshPacket
import lk.safeheaven.core.PanicEvent
import lk.safeheaven.data.Prefs
import lk.safeheaven.mesh.MeshBroadcaster
import lk.safeheaven.net.SupabaseApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes the panic side-effects for [TripService]: the two 5 s POST /alerts attempts, the GPS
 * trail, the evidence hash chain (audio chunks after the plate photo h0), the safe-haven/contact
 * notification, and — when the network fails twice — the BLE mesh broadcast with 15 s retries and
 * the SMS fallback.
 */
class PanicController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val prefs = Prefs.get(context)
    private val api = SupabaseApi.get(context)
    private val broadcaster = MeshBroadcaster(context)

    private var audio: AudioChunker? = null
    private var evidence: EvidenceStore? = null
    private var meshRetryJob: Job? = null
    private val prevHash = AtomicReference<String>()
    private val seqCounter = java.util.concurrent.atomic.AtomicInteger(1)
    private var session: TripSession? = null
    private var panicStartMs = 0L

    /** Entry point for SILENT_PANIC: begins recording + trail and fires the first alert <5 s. */
    fun onEnterSilentPanic(session: TripSession) {
        this.session = session
        panicStartMs = System.currentTimeMillis()
        val alertId = session.alertId ?: java.util.UUID.randomUUID().toString()
        TripBus.setSession(session.copy(alertId = alertId, panicStartMs = panicStartMs))
        prefs.activeTripJson = TripBus.session.value?.toJson()

        val store = EvidenceStore(context, alertId)
        evidence = store
        prevHash.set(session.h0Hex)

        // Seq 0 = plate photo captured at trip start (its SHA-256 is h0).
        scope.launch(Dispatchers.IO) {
            val photo = File(session.photoPath)
            val fix = TripBus.lastFix.value
            if (photo.exists()) {
                store.addPhoto(photo, session.startMs, fix?.lat ?: 0.0, fix?.lon ?: 0.0)
            }
            store.flush(api)
        }

        startAudio(store)
        startTrail()
        scope.launch { attemptAlerts(alertId) }
    }

    private fun startAudio(store: EvidenceStore) {
        val chunker = AudioChunker(context, File(context.filesDir, "evidence_raw/${session?.alertId}")) { file, startedAt ->
            // Chain each finalised chunk, then queue + flush.
            scope.launch(Dispatchers.IO) {
                val fix = TripBus.lastFix.value
                val seq = seqCounter.getAndIncrement()
                val bytes = file.readBytes()
                val prev = prevHash.get()
                val hash = EvidenceChain.hex(
                    EvidenceChain.next(
                        EvidenceChain.fromHex(prev), bytes, seq, startedAt,
                        fix?.lat ?: 0.0, fix?.lon ?: 0.0, session?.alertId ?: "",
                    ),
                )
                prevHash.set(hash)
                store.addAudioChunk(seq, bytes, startedAt, fix?.lat ?: 0.0, fix?.lon ?: 0.0, prev, hash)
                file.delete()
                store.flush(api)
            }
        }
        audio = chunker
        chunker.start()
    }

    private val trail = JSONArray()
    private var trailJob: Job? = null

    private fun startTrail() {
        trailJob = scope.launch {
            TripBus.lastFix.collect { fix ->
                if (fix == null) return@collect
                synchronized(trail) {
                    trail.put(
                        JSONObject().put("t", fix.timeMs).put("lat", fix.lat)
                            .put("lon", fix.lon).put("accuracy_m", fix.accuracyM),
                    )
                }
            }
        }
    }

    /** Two attempts, 5 s timeout each (10 s window). Emits AlertDelivered or AlertFailedTwice. */
    private suspend fun attemptAlerts(alertId: String) {
        val fix = TripBus.lastFix.value ?: LocationHub.lastKnown(context)
        for (attempt in 1..2) {
            val payload = JSONObject()
                .put("alert_id", alertId)
                .put("trip_id", session?.tripId)
                .put("source", "direct")
                .put("started_at", java.time.Instant.ofEpochMilli(panicStartMs).toString())
                .put("lat", fix?.lat ?: 0.0)
                .put("lon", fix?.lon ?: 0.0)
                .put("accuracy_m", fix?.accuracyM ?: 0f)
                .put("points", synchronized(trail) { JSONArray(trail.toString()) })
            val ok = withTimeoutOrNull(5_000) {
                runCatching { api.postAlert(payload, timeoutMs = 5_000) }.getOrNull()
            }
            if (ok != null) {
                TripBus.log("alert delivered (attempt $attempt): havens=${ok.optInt("havens_notified")}")
                TripBus.send(PanicEvent.AlertDelivered)
                return
            }
            TripBus.log("alert attempt $attempt failed/timed out")
        }
        TripBus.send(PanicEvent.AlertFailedTwice)
    }

    fun onAlertSent() {
        // Havens + contacts are paged server-side by the alerts function; keep flushing evidence.
        scope.launch(Dispatchers.IO) { evidence?.flush(api) }
    }

    /** MESH_MODE: broadcast, retry the network every 15 s, SMS fallback when a SIM is present. */
    fun onEnterMesh() {
        val s = session ?: return
        if (broadcaster.supported) {
            broadcaster.start(
                scope = scope,
                panicStartMs = panicStartMs,
                plate = s.plate,
                fix = { TripBus.lastFix.value },
                batteryPct = { batteryPct() },
                flags = { meshFlags() },
            )
        } else {
            TripBus.log("BLE advertising unsupported on this device; relying on SMS + retries")
        }
        sendSmsFallback()
        meshRetryJob = scope.launch {
            while (true) {
                delay(15_000)
                if (api.configured && api.isOnline()) {
                    val fix = TripBus.lastFix.value
                    val payload = JSONObject()
                        .put("alert_id", s.alertId)
                        .put("trip_id", s.tripId)
                        .put("source", "mesh")
                        .put("started_at", java.time.Instant.ofEpochMilli(panicStartMs).toString())
                        .put("lat", fix?.lat ?: 0.0).put("lon", fix?.lon ?: 0.0)
                        .put("accuracy_m", fix?.accuracyM ?: 0f)
                        .put("points", synchronized(trail) { JSONArray(trail.toString()) })
                    val ok = withTimeoutOrNull(5_000) {
                        runCatching { api.postAlert(payload, 5_000) }.getOrNull()
                    }
                    if (ok != null) {
                        TripBus.log("network returned; alert delivered from mesh mode")
                        evidence?.flush(api)
                        TripBus.send(PanicEvent.AlertDelivered)
                        return@launch
                    }
                }
            }
        }
    }

    private fun sendSmsFallback() {
        val s = session ?: return
        val fix = TripBus.lastFix.value
        val link = if (fix != null) "https://maps.google.com/?q=${fix.lat},${fix.lon}" else ""
        val text = "SAFE HEAVEN ALERT: ${prefs.name.ifBlank { "A contact" }} may be in danger. " +
            "Vehicle ${s.plate}. $link"
        SmsFallback.sendToContacts(context, prefs.contacts.map { it.phone }, text)
    }

    fun stopMesh() {
        meshRetryJob?.cancel()
        broadcaster.stop()
    }

    fun stopAll() {
        audio?.stop(); audio = null
        trailJob?.cancel()
        meshRetryJob?.cancel()
        broadcaster.stop()
        scope.launch(Dispatchers.IO) { evidence?.flush(api) }
    }

    private fun batteryPct(): Int {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return 0
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }

    private fun meshFlags(): Int {
        var f = MeshPacket.FLAG_PANIC or MeshPacket.FLAG_DURESS
        if (batteryPct() <= 15) f = f or MeshPacket.FLAG_LOW_BATTERY
        return f
    }
}
