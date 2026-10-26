package lk.safeheaven.trip

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import lk.safeheaven.core.AnomalyReason
import lk.safeheaven.core.Fix
import lk.safeheaven.core.PanicEvent
import lk.safeheaven.core.PanicState

/** In-process link between the UI/activities and [TripService]. */
object TripBus {
    val events = Channel<PanicEvent>(Channel.UNLIMITED)

    /** "I changed my route": the UI pushes a session carrying the refreshed polyline. */
    val sessionUpdates = Channel<TripSession>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(PanicState.IDLE)
    val state: StateFlow<PanicState> = _state

    private val _session = MutableStateFlow<TripSession?>(null)
    val session: StateFlow<TripSession?> = _session

    private val _lastFix = MutableStateFlow<Fix?>(null)
    val lastFix: StateFlow<Fix?> = _lastFix

    /** Epoch ms when the current safety check expires (0 when none). */
    private val _checkDeadline = MutableStateFlow(0L)
    val checkDeadline: StateFlow<Long> = _checkDeadline

    private val _checkReason = MutableStateFlow<AnomalyReason?>(null)
    val checkReason: StateFlow<AnomalyReason?> = _checkReason

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _log

    @Volatile var safetyCheckVisible = false
    @Volatile var fakeShutdownVisible = false
    @Volatile var startedActivities = 0
    val appVisible: Boolean get() = startedActivities > 0

    fun send(e: PanicEvent) { events.trySend(e) }

    internal fun setState(s: PanicState) { _state.value = s }
    internal fun setSession(s: TripSession?) { _session.value = s }
    internal fun setFix(f: Fix) { _lastFix.value = f }
    internal fun setCheck(deadline: Long, reason: AnomalyReason?) {
        _checkDeadline.value = deadline
        _checkReason.value = reason
    }

    /** Telemetry for the debug screen; never shown in the duress UI. */
    fun log(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _log.value = (listOf("$stamp  $line") + _log.value).take(80)
    }
}
