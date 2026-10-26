package lk.safeheaven.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lk.safeheaven.BuildConfig
import lk.safeheaven.data.KeyVault
import lk.safeheaven.data.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/**
 * Thin Supabase client (Auth, PostgREST, Storage, Edge Functions) over OkHttp. Kept dependency-free
 * so the panic path has predictable timeouts: every call states its own deadline.
 */
class SupabaseApi private constructor(private val context: Context) {
    private val prefs = Prefs.get(context)
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anon = BuildConfig.SUPABASE_ANON_KEY
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    val configured: Boolean get() = base.startsWith("https://") && anon.isNotBlank()

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ───────────── auth (anonymous sign-in) ─────────────

    private fun storeSession(o: JSONObject) {
        prefs.accessToken = o.getString("access_token")
        prefs.refreshToken = o.optString("refresh_token")
        prefs.tokenExpiresAtMs = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000
        o.optJSONObject("user")?.optString("id")?.let { prefs.userId = it }
    }

    /** Returns a valid access token, signing in anonymously or refreshing when needed. */
    suspend fun token(): String = withContext(Dispatchers.IO) {
        val current = prefs.accessToken
        if (current != null && prefs.tokenExpiresAtMs - System.currentTimeMillis() > 120_000) return@withContext current
        val refresh = prefs.refreshToken
        if (!refresh.isNullOrBlank()) {
            runCatching {
                storeSession(
                    exec(
                        Request.Builder().url("$base/auth/v1/token?grant_type=refresh_token")
                            .header("apikey", anon)
                            .post(JSONObject().put("refresh_token", refresh).toString().toRequestBody(jsonType))
                            .build(),
                    ),
                )
                return@withContext prefs.accessToken!!
            }
        }
        storeSession(
            exec(
                Request.Builder().url("$base/auth/v1/signup")
                    .header("apikey", anon)
                    .post("{}".toRequestBody(jsonType))
                    .build(),
            ),
        )
        prefs.accessToken!!
    }

    private fun exec(req: Request, client: OkHttpClient = http): JSONObject {
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(res.code, "${req.url.encodedPath} ${res.code}: $body")
            return if (body.isBlank()) JSONObject() else if (body.trimStart().startsWith("[")) {
                JSONObject().put("items", JSONArray(body))
            } else JSONObject(body)
        }
    }

    private suspend fun authed(builder: Request.Builder): Request.Builder =
        builder.header("apikey", anon).header("Authorization", "Bearer ${token()}")

    private suspend fun function(name: String, payload: JSONObject, timeoutMs: Long = 20_000): JSONObject =
        withContext(Dispatchers.IO) {
            val client = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            exec(
                authed(Request.Builder().url("$base/functions/v1/$name"))
                    .post(payload.toString().toRequestBody(jsonType)).build(),
                client,
            )
        }

    // ───────────── registration ─────────────

    suspend fun register(): Long {
        val key = KeyVault.meshKeyMaterial(prefs)
        val contacts = JSONArray()
        prefs.contacts.forEachIndexed { i, c ->
            contacts.put(JSONObject().put("name", c.name).put("phone", c.phone).put("priority", i + 1))
        }
        val payload = JSONObject()
            .put("name", prefs.name)
            .put("phone", prefs.phone)
            .put("mesh_key", Prefs.b64(key))
            .put("contacts", contacts)
        if (prefs.meshSenderId >= 0) payload.put("mesh_sender_id", prefs.meshSenderId)
        val res = function("register", payload)
        val id = res.getLong("mesh_sender_id")
        prefs.meshSenderId = id
        prefs.registered = true
        return id
    }

    suspend fun ensureRegistered() {
        if (configured && !prefs.registered) register()
    }

    // ───────────── trips ─────────────

    suspend fun uploadPlatePhoto(tripId: String, jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        val uid = prefs.userId ?: token().let { prefs.userId!! }
        val path = "$uid/$tripId.jpg"
        exec(
            authed(Request.Builder().url("$base/storage/v1/object/plate-photos/$path"))
                .post(jpeg.toRequestBody("image/jpeg".toMediaType())).build(),
        )
        path
    }

    suspend fun insertTrip(
        tripId: String,
        plate: String,
        photoPath: String?,
        destLat: Double,
        destLon: Double,
        polyline: String,
        etaIso: String,
    ) = withContext(Dispatchers.IO) {
        val row = JSONObject()
            .put("id", tripId)
            .put("user_id", prefs.userId)
            .put("plate", plate)
            .put("plate_photo_path", photoPath ?: JSONObject.NULL)
            .put("destination", "SRID=4326;POINT($destLon $destLat)")
            .put("route_polyline", polyline)
            .put("eta", etaIso)
            .put("status", "active")
        exec(
            authed(Request.Builder().url("$base/rest/v1/trips"))
                .header("Prefer", "return=minimal")
                .post(row.toString().toRequestBody(jsonType)).build(),
        )
    }

    suspend fun setTripStatus(tripId: String, status: String) = withContext(Dispatchers.IO) {
        exec(
            authed(Request.Builder().url("$base/rest/v1/trips?id=eq.$tripId"))
                .header("Prefer", "return=minimal")
                .patch(JSONObject().put("status", status).toString().toRequestBody(jsonType)).build(),
        )
    }

    suspend fun notifyContacts(type: String, plate: String?, lat: Double?, lon: Double?) {
        val p = JSONObject().put("type", type)
        plate?.let { p.put("plate", it) }
        if (lat != null && lon != null) p.put("lat", lat).put("lon", lon)
        function("notify", p)
    }

    // ───────────── panic ─────────────

    /** One POST /alerts attempt bounded by [timeoutMs] (the panic path uses 5 s x 2). */
    suspend fun postAlert(payload: JSONObject, timeoutMs: Long): JSONObject =
        function("alerts", payload, timeoutMs)

    suspend fun uploadEvidence(headers: Map<String, String>, bytes: ByteArray): JSONObject =
        withContext(Dispatchers.IO) {
            val b = authed(Request.Builder().url("$base/functions/v1/evidence"))
            headers.forEach { (k, v) -> b.header(k, v) }
            exec(b.post(bytes.toRequestBody("application/octet-stream".toMediaType())).build())
        }

    /** Relay path: no user session needed, the packet's HMAC authenticates it. */
    suspend fun meshRelay(packet: ByteArray, timeoutMs: Long = 4_000): JSONObject = withContext(Dispatchers.IO) {
        val client = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        exec(
            Request.Builder().url("$base/functions/v1/mesh-relay")
                .header("apikey", anon)
                .header("Authorization", "Bearer $anon")
                .header("X-Device-Id", prefs.deviceId)
                .post(packet.toRequestBody("application/octet-stream".toMediaType())).build(),
            client,
        )
    }

    companion object {
        @Volatile private var instance: SupabaseApi? = null
        fun get(context: Context): SupabaseApi =
            instance ?: synchronized(this) {
                instance ?: SupabaseApi(context.applicationContext).also { instance = it }
            }
    }
}
