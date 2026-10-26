package lk.safeheaven.trip

import android.util.Xml
import lk.safeheaven.core.LatLng
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant

data class GpxPoint(val lat: Double, val lon: Double, val offsetMs: Long)

object Gpx {
    /** Parses <trkpt>/<rtept>; points without <time> are spaced 5 s apart. */
    fun parse(input: InputStream): List<GpxPoint> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val raw = ArrayList<Triple<Double, Double, Long?>>()
        var lat = 0.0
        var lon = 0.0
        var time: Long? = null
        var inPoint = false
        var inTime = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trkpt", "rtept" -> {
                        inPoint = true
                        lat = parser.getAttributeValue(null, "lat").toDouble()
                        lon = parser.getAttributeValue(null, "lon").toDouble()
                        time = null
                    }
                    "time" -> inTime = inPoint
                }
                XmlPullParser.TEXT -> if (inTime) {
                    time = runCatching { Instant.parse(parser.text.trim()).toEpochMilli() }.getOrNull()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "time" -> inTime = false
                    "trkpt", "rtept" -> {
                        raw += Triple(lat, lon, time)
                        inPoint = false
                    }
                }
            }
            parser.next()
        }
        val t0 = raw.firstOrNull()?.third
        return raw.mapIndexed { i, (la, lo, t) ->
            GpxPoint(la, lo, if (t != null && t0 != null) t - t0 else i * 5_000L)
        }
    }

    fun toLatLng(points: List<GpxPoint>) = points.map { LatLng(it.lat, it.lon) }
}
