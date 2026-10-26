package lk.safeheaven.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lk.safeheaven.core.LatLng
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Route(val polyline: String, val points: List<LatLng>, val etaSeconds: Long, val distanceM: Double)

/** Driving route + ETA from OSRM (keyless). The polyline is Google-encoded, precision 5. */
object RouteClient {
    private const val OSRM = "https://router.project-osrm.org"
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun fetch(from: LatLng, to: LatLng): Route = withContext(Dispatchers.IO) {
        val coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", from.lon, from.lat, to.lon, to.lat)
        val req = Request.Builder()
            .url("$OSRM/route/v1/driving/$coords?overview=full&geometries=polyline&alternatives=false&steps=false")
            .header("User-Agent", "SafeHeaven-prototype")
            .build()
        http.newCall(req).execute().use { res ->
            val body = JSONObject(res.body?.string().orEmpty())
            if (!res.isSuccessful || body.optString("code") != "Ok") {
                throw ApiException(res.code, "OSRM: ${body.optString("message", body.optString("code"))}")
            }
            val r = body.getJSONArray("routes").getJSONObject(0)
            val poly = r.getString("geometry")
            Route(
                polyline = poly,
                points = lk.safeheaven.core.Geo.decodePolyline(poly),
                etaSeconds = r.getDouble("duration").toLong().coerceAtLeast(60),
                distanceM = r.getDouble("distance"),
            )
        }
    }
}
