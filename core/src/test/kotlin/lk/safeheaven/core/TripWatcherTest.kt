package lk.safeheaven.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TripWatcherTest {
    private val dest = LatLng(6.9000, 79.8600)
    private val t0 = 1_000_000L

    private fun fix(i: Int, lat: Double = 6.9271, lon: Double = 79.8612) =
        Fix(lat, lon + i * 0.0005, t0 + i * 5_000L)

    @Test fun `three consecutive off-route fixes trigger, two do not`() {
        val w = TripWatcher(dest, t0, 3600, { false })
        assertEquals(TripWatcher.Verdict.Ok, w.onFix(fix(1)))
        assertEquals(TripWatcher.Verdict.Ok, w.onFix(fix(2)))
        assertEquals(TripWatcher.Verdict.Anomaly(AnomalyReason.OFF_ROUTE), w.onFix(fix(3)))
    }

    @Test fun `an on-route fix resets the off-route streak`() {
        var on = false
        val w = TripWatcher(dest, t0, 3600, { on })
        w.onFix(fix(1)); w.onFix(fix(2))
        on = true; w.onFix(fix(3))
        on = false
        assertEquals(TripWatcher.Verdict.Ok, w.onFix(fix(4)))
        assertEquals(TripWatcher.Verdict.Ok, w.onFix(fix(5)))
        assertEquals(TripWatcher.Verdict.Anomaly(AnomalyReason.OFF_ROUTE), w.onFix(fix(6)))
    }

    @Test fun `elapsed beyond 150 percent of ETA is late`() {
        val w = TripWatcher(dest, t0, 600, { true })
        assertEquals(TripWatcher.Verdict.Ok, w.onFix(Fix(6.93, 79.86, t0 + 899_000)))
        assertEquals(TripWatcher.Verdict.Anomaly(AnomalyReason.LATE_ETA), w.onFix(Fix(6.931, 79.86, t0 + 901_000)))
    }

    @Test fun `five minutes without moving away from destination is stationary`() {
        val w = TripWatcher(dest, t0, 36_000, { true })
        var verdict: TripWatcher.Verdict = TripWatcher.Verdict.Ok
        var i = 0
        while (verdict == TripWatcher.Verdict.Ok && i < 100) {
            verdict = w.onFix(Fix(6.93, 79.86, t0 + i * 5_000L)); i++
        }
        assertEquals(TripWatcher.Verdict.Anomaly(AnomalyReason.STATIONARY), verdict)
        assertEquals(61, i) // 300 s / 5 s + the anchor fix
    }

    @Test fun `movement resets the stationary clock`() {
        val w = TripWatcher(dest, t0, 36_000, { true })
        for (i in 0 until 50) w.onFix(Fix(6.93, 79.86, t0 + i * 5_000L))
        w.onFix(Fix(6.9305, 79.86, t0 + 250_000L)) // ~55 m away
        for (i in 51 until 100) {
            assertEquals(TripWatcher.Verdict.Ok, w.onFix(Fix(6.9305, 79.86, t0 + i * 5_000L)))
        }
    }

    @Test fun `within 100 m of the destination is arrival`() {
        val w = TripWatcher(dest, t0, 600, { false })
        assertEquals(TripWatcher.Verdict.Arrived, w.onFix(Fix(6.9005, 79.8600, t0)))
    }

    @Test fun `stationary at the destination is arrival, not a stationary anomaly`() {
        val w = TripWatcher(dest, t0, 600, { true })
        assertEquals(TripWatcher.Verdict.Arrived, w.onFix(Fix(6.9001, 79.8600, t0 + 400_000L)))
    }
}
