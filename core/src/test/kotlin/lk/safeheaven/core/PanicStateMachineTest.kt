package lk.safeheaven.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanicStateMachineTest {
    private fun active() = PanicStateMachine().also { it.handle(PanicEvent.TripStarted) }
    private fun inCheck() = active().also { it.handle(PanicEvent.AnomalyDetected(AnomalyReason.OFF_ROUTE)) }
    private fun inPanic() = inCheck().also { it.handle(PanicEvent.CountdownExpired) }

    @Test fun `idle starts a trip`() {
        val m = PanicStateMachine()
        val t = m.handle(PanicEvent.TripStarted)
        assertEquals(PanicState.TRIP_ACTIVE, t.to)
        assertEquals(listOf(PanicEffect.StartTripWatching), t.effects)
    }

    @Test fun `idle ignores everything except trip start`() {
        listOf(PanicEvent.RealPinEntered, PanicEvent.DuressPinEntered, PanicEvent.CountdownExpired,
            PanicEvent.AlertDelivered, PanicEvent.ArrivedAtDestination).forEach {
            val m = PanicStateMachine()
            assertFalse(m.handle(it).accepted)
            assertEquals(PanicState.IDLE, m.state)
        }
    }

    @Test fun `arrival returns to idle and sends arrived safely`() {
        val m = active()
        val t = m.handle(PanicEvent.ArrivedAtDestination)
        assertEquals(PanicState.IDLE, m.state)
        assertTrue(PanicEffect.SendArrivedSafely in t.effects)
    }

    @Test fun `each anomaly reason opens a 30 second safety check`() {
        AnomalyReason.entries.forEach { r ->
            val m = active()
            val t = m.handle(PanicEvent.AnomalyDetected(r))
            assertEquals(PanicState.SAFETY_CHECK, t.to)
            assertEquals(listOf(PanicEffect.ShowSafetyCheck(r, 30)), t.effects)
        }
    }

    @Test fun `real pin cancels the check and resumes the trip`() {
        val m = inCheck()
        val t = m.handle(PanicEvent.RealPinEntered)
        assertEquals(PanicState.TRIP_ACTIVE, t.to)
        assertEquals(listOf(PanicEffect.DismissSafetyCheck), t.effects)
    }

    @Test fun `duress pin goes straight to silent panic`() {
        val m = inCheck()
        val t = m.handle(PanicEvent.DuressPinEntered)
        assertEquals(PanicState.SILENT_PANIC, t.to)
        assertEquals(listOf(PanicEffect.StartSilentPanic), t.effects)
    }

    @Test fun `countdown expiry is treated exactly like duress`() {
        val a = inCheck().handle(PanicEvent.CountdownExpired)
        val b = inCheck().handle(PanicEvent.DuressPinEntered)
        assertEquals(b.to, a.to)
        assertEquals(b.effects, a.effects)
    }

    @Test fun `server ack within 10 seconds reaches alert sent`() {
        val m = inPanic()
        val t = m.handle(PanicEvent.AlertDelivered)
        assertEquals(PanicState.ALERT_SENT, t.to)
        assertEquals(listOf(PanicEffect.NotifyHavensAndContacts), t.effects)
    }

    @Test fun `two failed posts go to mesh mode`() {
        val m = inPanic()
        val t = m.handle(PanicEvent.AlertFailedTwice)
        assertEquals(PanicState.MESH_MODE, t.to)
        assertEquals(listOf(PanicEffect.StartMesh), t.effects)
    }

    @Test fun `mesh mode recovers to alert sent when the network returns`() {
        val m = inPanic().also { it.handle(PanicEvent.AlertFailedTwice) }
        val t = m.handle(PanicEvent.AlertDelivered)
        assertEquals(PanicState.ALERT_SENT, t.to)
        assertTrue(PanicEffect.StopMesh in t.effects)
        assertTrue(PanicEffect.NotifyHavensAndContacts in t.effects)
    }

    @Test fun `pins and countdown are ignored once panic has started`() {
        val m = inPanic()
        listOf(PanicEvent.RealPinEntered, PanicEvent.DuressPinEntered, PanicEvent.CountdownExpired).forEach {
            assertFalse(m.handle(it).accepted)
        }
        assertEquals(PanicState.SILENT_PANIC, m.state)
    }

    @Test fun `resolve from any panic state returns to idle and stops everything`() {
        val states = listOf(
            { inPanic() },
            { inPanic().also { it.handle(PanicEvent.AlertDelivered) } },
            { inPanic().also { it.handle(PanicEvent.AlertFailedTwice) } },
        )
        states.forEach { make ->
            val m = make()
            val t = m.handle(PanicEvent.Resolved)
            assertEquals(PanicState.IDLE, m.state)
            assertTrue(PanicEffect.StopPanicActions in t.effects)
            assertTrue(PanicEffect.StopMesh in t.effects)
        }
    }

    @Test fun `exactly six states`() = assertEquals(6, PanicState.entries.size)
}
