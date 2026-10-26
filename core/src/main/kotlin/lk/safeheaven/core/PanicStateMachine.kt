package lk.safeheaven.core

enum class PanicState { IDLE, TRIP_ACTIVE, SAFETY_CHECK, SILENT_PANIC, ALERT_SENT, MESH_MODE }

enum class AnomalyReason { OFF_ROUTE, LATE_ETA, STATIONARY }

sealed interface PanicEvent {
    data object TripStarted : PanicEvent
    data object ArrivedAtDestination : PanicEvent
    data class AnomalyDetected(val reason: AnomalyReason) : PanicEvent
    data object RealPinEntered : PanicEvent
    data object DuressPinEntered : PanicEvent
    data object CountdownExpired : PanicEvent
    /** Server acknowledged POST /alerts, or a BLE relay phone reported it for us. */
    data object AlertDelivered : PanicEvent
    /** Both POST /alerts attempts failed (5 s timeout x 2). */
    data object AlertFailedTwice : PanicEvent
    /** Duress screen exited with the Real PIN, or the user ended the trip. */
    data object Resolved : PanicEvent
    data object TripCancelled : PanicEvent
}

sealed interface PanicEffect {
    data class ShowSafetyCheck(val reason: AnomalyReason, val countdownSeconds: Int) : PanicEffect
    data object DismissSafetyCheck : PanicEffect
    data object SendArrivedSafely : PanicEffect
    data object StartTripWatching : PanicEffect
    data object StopTripWatching : PanicEffect
    /** Fake shutdown screen, 5 s audio chunks, GPS trail, POST /alerts. */
    data object StartSilentPanic : PanicEffect
    /** Ping safe havens + trusted contacts; BlackBox keeps uploading. */
    data object NotifyHavensAndContacts : PanicEffect
    /** Alternating Type A / B BLE packets, 15 s network retries, SMS fallback. */
    data object StartMesh : PanicEffect
    data object StopMesh : PanicEffect
    data object StopPanicActions : PanicEffect
}

data class Transition(
    val from: PanicState,
    val to: PanicState,
    val effects: List<PanicEffect>,
    /** False when the event is not valid in [from] and was ignored. */
    val accepted: Boolean,
)

/**
 * Pure 6-state machine. It owns no timers, I/O or Android types: the service feeds it events and
 * executes the returned effects, which keeps every transition unit-testable on the JVM.
 */
class PanicStateMachine(
    initial: PanicState = PanicState.IDLE,
    private val countdownSeconds: Int = SAFETY_CHECK_COUNTDOWN_S,
) {
    var state: PanicState = initial
        private set

    /** Reason of the safety check currently shown (or last shown). */
    var lastReason: AnomalyReason? = null
        private set

    fun handle(event: PanicEvent): Transition {
        val from = state
        val (to, effects) = next(from, event) ?: return Transition(from, from, emptyList(), false)
        state = to
        return Transition(from, to, effects, true)
    }

    private fun next(s: PanicState, e: PanicEvent): Pair<PanicState, List<PanicEffect>>? = when (s) {
        PanicState.IDLE -> when (e) {
            PanicEvent.TripStarted -> PanicState.TRIP_ACTIVE to listOf(PanicEffect.StartTripWatching)
            else -> null
        }

        PanicState.TRIP_ACTIVE -> when (e) {
            PanicEvent.ArrivedAtDestination ->
                PanicState.IDLE to listOf(PanicEffect.SendArrivedSafely, PanicEffect.StopTripWatching)
            PanicEvent.TripCancelled -> PanicState.IDLE to listOf(PanicEffect.StopTripWatching)
            is PanicEvent.AnomalyDetected -> {
                lastReason = e.reason
                PanicState.SAFETY_CHECK to listOf(PanicEffect.ShowSafetyCheck(e.reason, countdownSeconds))
            }
            else -> null
        }

        PanicState.SAFETY_CHECK -> when (e) {
            PanicEvent.RealPinEntered ->
                PanicState.TRIP_ACTIVE to listOf(PanicEffect.DismissSafetyCheck)
            PanicEvent.DuressPinEntered, PanicEvent.CountdownExpired ->
                PanicState.SILENT_PANIC to listOf(PanicEffect.StartSilentPanic)
            else -> null
        }

        PanicState.SILENT_PANIC -> when (e) {
            PanicEvent.AlertDelivered ->
                PanicState.ALERT_SENT to listOf(PanicEffect.NotifyHavensAndContacts)
            PanicEvent.AlertFailedTwice -> PanicState.MESH_MODE to listOf(PanicEffect.StartMesh)
            PanicEvent.Resolved -> resolved()
            else -> null
        }

        PanicState.ALERT_SENT -> when (e) {
            PanicEvent.Resolved -> resolved()
            else -> null
        }

        PanicState.MESH_MODE -> when (e) {
            PanicEvent.AlertDelivered ->
                PanicState.ALERT_SENT to listOf(PanicEffect.StopMesh, PanicEffect.NotifyHavensAndContacts)
            PanicEvent.Resolved -> resolved()
            else -> null
        }
    }

    private fun resolved() = PanicState.IDLE to listOf(
        PanicEffect.StopMesh,
        PanicEffect.StopPanicActions,
        PanicEffect.StopTripWatching,
    )

    companion object {
        const val SAFETY_CHECK_COUNTDOWN_S = 30
    }
}
