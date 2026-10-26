package lk.safeheaven.core

/**
 * Evaluates every location fix (5 s cadence) against the three anomaly rules.
 *
 * Time comes from [Fix.timeMs], not the wall clock, so a GPX replay (which stamps virtual time)
 * exercises exactly the same code path as live GPS.
 *
 * @param isOnRoute abstraction over `PolyUtil.isLocationOnEdge(point, route, true, 150.0)` so the
 *        rules are testable on the JVM; the app injects the real PolyUtil call.
 */
class TripWatcher(
    private val destination: LatLng,
    private val startTimeMs: Long,
    private val etaSeconds: Long,
    private val isOnRoute: (Fix) -> Boolean,
    private val offRouteFixesRequired: Int = 3,
    private val lateFactor: Double = 1.5,
    private val stationaryMs: Long = 5 * 60_000L,
    private val stationaryRadiusM: Double = 25.0,
    private val arrivalRadiusM: Double = 100.0,
) {
    sealed interface Verdict {
        data object Ok : Verdict
        data object Arrived : Verdict
        data class Anomaly(val reason: AnomalyReason) : Verdict
    }

    private var offRouteStreak = 0
    private var anchor: Fix? = null

    fun onFix(fix: Fix): Verdict {
        if (Geo.distanceM(fix.lat, fix.lon, destination.lat, destination.lon) <= arrivalRadiusM) {
            return Verdict.Arrived
        }

        if (isOnRoute(fix)) offRouteStreak = 0 else offRouteStreak++
        if (offRouteStreak >= offRouteFixesRequired) {
            offRouteStreak = 0
            return Verdict.Anomaly(AnomalyReason.OFF_ROUTE)
        }

        if (fix.timeMs - startTimeMs > etaSeconds * 1000L * lateFactor) {
            return Verdict.Anomaly(AnomalyReason.LATE_ETA)
        }

        val a = anchor
        if (a == null || Geo.distanceM(a.lat, a.lon, fix.lat, fix.lon) > stationaryRadiusM) {
            anchor = fix
        } else if (fix.timeMs - a.timeMs >= stationaryMs) {
            anchor = fix
            return Verdict.Anomaly(AnomalyReason.STATIONARY)
        }
        return Verdict.Ok
    }

    /** After the user answers a check with the Real PIN, restart the stationary/off-route clocks. */
    fun resetAfterCheck(lastFix: Fix?) {
        offRouteStreak = 0
        anchor = lastFix
    }
}
