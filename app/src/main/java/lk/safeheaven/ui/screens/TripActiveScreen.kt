package lk.safeheaven.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import lk.safeheaven.core.AnomalyReason
import lk.safeheaven.core.Geo
import lk.safeheaven.core.PanicEvent
import lk.safeheaven.core.PanicState
import lk.safeheaven.data.Prefs
import lk.safeheaven.trip.TripBus
import lk.safeheaven.ui.components.GhostButton
import lk.safeheaven.ui.components.ShCard
import lk.safeheaven.ui.components.StatusPill
import lk.safeheaven.ui.theme.Inter
import lk.safeheaven.ui.theme.Montserrat
import lk.safeheaven.ui.theme.Sh

@Composable
fun TripActiveScreen(onClosed: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = Prefs.get(ctx)
    val state by TripBus.state.collectAsState()
    val session by TripBus.session.collectAsState()
    val fix by TripBus.lastFix.collectAsState()
    val log by TripBus.logLines.collectAsState()
    var now by androidx.compose.runtime.remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    // The service needs a moment to leave IDLE after start; only close once we've seen it active.
    var seenActive by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state != PanicState.IDLE) seenActive = true else if (seenActive) onClosed()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val s = session
        // The operator screen must not reveal panic to someone glancing at it: states are neutral.
        val shown = when (state) {
            PanicState.TRIP_ACTIVE -> "Trip in progress"
            PanicState.SAFETY_CHECK -> "Checking in…"
            else -> "Syncing…"
        }
        Text(shown, style = MaterialTheme.typography.displayMedium.copy(fontFamily = Montserrat))
        if (s != null) {
            ShCard {
                Text("VEHICLE", style = MaterialTheme.typography.labelMedium, color = Sh.Muted)
                Text(s.plate, style = MaterialTheme.typography.headlineMedium)
                Text("TO", style = MaterialTheme.typography.labelMedium, color = Sh.Muted)
                Text(s.destinationLabel.ifBlank { "%.5f, %.5f".format(s.destination.lat, s.destination.lon) }, style = MaterialTheme.typography.bodyLarge)
                val f = fix
                val remainM = f?.let { Geo.distanceM(it.lat, it.lon, s.destination.lat, s.destination.lon) }
                val elapsedS = ((f?.timeMs ?: now) - s.startMs) / 1000
                Text(
                    "Remaining ${remainM?.let { "%.1f km".format(it / 1000) } ?: "—"}  ·  Elapsed ${elapsedS / 60}m ${elapsedS % 60}s  ·  ETA ${s.etaSeconds / 60}m",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Inter), color = Sh.Muted,
                )
                Text(
                    f?.let { "GPS %.5f, %.5f  ±%.0fm".format(it.lat, it.lon, it.accuracyM) } ?: "Waiting for GPS…",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Inter), color = Sh.Muted,
                )
            }
        }
        StatusPill("Contacts notified at start", Sh.Teal)
        GhostButton("End trip") { TripBus.send(PanicEvent.TripCancelled) }

        if (prefs.demoMode) {
            ShCard {
                Text("Stage controls", style = MaterialTheme.typography.titleMedium, color = Sh.Amber)
                Text("State machine: $state", style = MaterialTheme.typography.bodySmall.copy(fontFamily = Inter), color = Sh.Muted)
                GhostButton("Trigger safety check now") { TripBus.send(PanicEvent.AnomalyDetected(AnomalyReason.OFF_ROUTE)) }
                log.take(14).forEach { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Inter), color = Sh.Muted) }
            }
        }
    }
}
