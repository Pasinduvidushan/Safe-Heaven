package lk.safeheaven.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double)

/** One location sample from either the real GPS or the GPX replay; same type, same flow. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val accuracyM: Float = 5f,
    val speedMps: Float = 0f,
)

object Geo {
    private const val EARTH_R = 6_371_008.8

    fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dp = p2 - p1
        val dl = Math.toRadians(bLon - aLon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_R * atan2(sqrt(h), sqrt(1 - h))
    }

    fun distanceM(a: LatLng, b: LatLng) = distanceM(a.lat, a.lon, b.lat, b.lon)

    /** Google encoded polyline (precision 5 by default, 6 for OSRM `polyline6`). */
    fun decodePolyline(encoded: String, precision: Int = 5): List<LatLng> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out += LatLng(lat / factor, lon / factor)
        }
        return out
    }

    fun encodePolyline(points: List<LatLng>, precision: Int = 5): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var pLat = 0
        var pLon = 0
        fun put(v: Int) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) {
                sb.append((((x and 0x1f) or 0x20) + 63).toChar())
                x = x shr 5
            }
            sb.append((x + 63).toChar())
        }
        for (p in points) {
            val la = Math.round(p.lat * factor).toInt()
            val lo = Math.round(p.lon * factor).toInt()
            put(la - pLat)
            put(lo - pLon)
            pLat = la
            pLon = lo
        }
        return sb.toString()
    }
}
