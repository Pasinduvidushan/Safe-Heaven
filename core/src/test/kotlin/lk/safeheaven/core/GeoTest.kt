package lk.safeheaven.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GeoTest {
    @Test fun `google reference polyline decodes`() {
        val pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5); assertEquals(-120.2, pts[0].lon, 1e-5)
        assertEquals(40.7, pts[1].lat, 1e-5); assertEquals(-120.95, pts[1].lon, 1e-5)
        assertEquals(43.252, pts[2].lat, 1e-5); assertEquals(-126.453, pts[2].lon, 1e-5)
    }

    @Test fun `encode is the inverse of decode`() {
        val s = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        assertEquals(s, Geo.encodePolyline(Geo.decodePolyline(s)))
    }

    @Test fun `haversine sanity`() {
        // Colombo Fort to Galle Face ~ 1.4 km
        val d = Geo.distanceM(6.9344, 79.8428, 6.9271, 79.8451)
        assertEquals(850.0, d, 100.0)
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 200.0)
    }
}
