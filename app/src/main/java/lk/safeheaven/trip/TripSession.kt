package lk.safeheaven.trip

import lk.safeheaven.core.Geo
import lk.safeheaven.core.LatLng
import org.json.JSONObject

data class TripSession(
    val tripId: String,
    val plate: String,
    val photoPath: String,      // app-private JPEG, evidence item h0
    val h0Hex: String,
    val destination: LatLng,
    val destinationLabel: String,
    val polyline: String,
    val etaSeconds: Long,
    val startMs: Long,
    val alertId: String? = null,
    val panicStartMs: Long = 0L,
) {
    val route: List<LatLng> by lazy { Geo.decodePolyline(polyline) }

    fun toJson(): String = JSONObject()
        .put("tripId", tripId).put("plate", plate).put("photoPath", photoPath).put("h0", h0Hex)
        .put("destLat", destination.lat).put("destLon", destination.lon).put("destLabel", destinationLabel)
        .put("polyline", polyline).put("eta", etaSeconds).put("start", startMs)
        .put("alertId", alertId ?: JSONObject.NULL).put("panicStart", panicStartMs)
        .toString()

    companion object {
        fun fromJson(s: String): TripSession {
            val o = JSONObject(s)
            return TripSession(
                tripId = o.getString("tripId"),
                plate = o.getString("plate"),
                photoPath = o.getString("photoPath"),
                h0Hex = o.getString("h0"),
                destination = LatLng(o.getDouble("destLat"), o.getDouble("destLon")),
                destinationLabel = o.optString("destLabel"),
                polyline = o.getString("polyline"),
                etaSeconds = o.getLong("eta"),
                startMs = o.getLong("start"),
                alertId = if (o.isNull("alertId")) null else o.getString("alertId"),
                panicStartMs = o.optLong("panicStart"),
            )
        }
    }
}
