package lk.safeheaven.safety

import com.google.android.gms.maps.model.LatLng as GmsLatLng
import com.google.maps.android.PolyUtil
import lk.safeheaven.core.Fix
import lk.safeheaven.core.LatLng

/**
 * "Is this fix within 150 m of the route?" via android-maps-utils (geodesic, 150 m tolerance).
 * PolyUtil.isLocationOnEdge() treats its list as a closed polygon and would also accept points near
 * the imaginary last→first segment, which is wrong for a route, so this uses the open-path variant
 * PolyUtil.isLocationOnPath() with the same arguments.
 */
class RouteMatcher(route: List<LatLng>) {
    private val gms = route.map { GmsLatLng(it.lat, it.lon) }

    fun isOnRoute(fix: Fix): Boolean =
        gms.size >= 2 && PolyUtil.isLocationOnPath(GmsLatLng(fix.lat, fix.lon), gms, true, TOLERANCE_M)

    companion object {
        const val TOLERANCE_M = 150.0
    }
}
