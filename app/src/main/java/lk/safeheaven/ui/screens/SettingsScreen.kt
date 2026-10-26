package lk.safeheaven.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import lk.safeheaven.data.Prefs
import lk.safeheaven.mesh.BluetoothSelfTest
import lk.safeheaven.trip.ReplayController
import lk.safeheaven.trip.TripBus
import lk.safeheaven.ui.components.GhostButton
import lk.safeheaven.ui.components.ShCard
import lk.safeheaven.ui.components.ToggleRow
import lk.safeheaven.ui.theme.Inter
import lk.safeheaven.ui.theme.Sh

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    var disguise by remember { mutableStateOf(prefs.disguiseAlarmCancelled) }
    var demo by remember { mutableStateOf(prefs.demoMode) }
    var gateway by remember { mutableStateOf(prefs.gatewayOnlyRelayed) }
    var replay by remember { mutableStateOf(ReplayController.enabled) }
    var scenario by remember { mutableStateOf(ReplayController.scenario) }
    var speed by remember { mutableStateOf(ReplayController.speed) }
    var gpxNote by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val log by TripBus.logLines.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            gpxNote = runCatching { "${ReplayController.loadCustom(ctx, uri)} points loaded" }.getOrElse { "Couldn't read GPX: ${it.message}" }
            scenario = ReplayController.scenario
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)

        ShCard {
            Text("Duress disguise", style = MaterialTheme.typography.titleMedium)
            ToggleRow(
                "“Alarm cancelled → Home”", "Instead of the fake shutdown, the Duress PIN shows “Alarm cancelled” and drops to the home screen.",
                disguise,
            ) { disguise = it; prefs.disguiseAlarmCancelled = it }
        }

        ShCard {
            Text("Demo mode", style = MaterialTheme.typography.titleMedium)
            ToggleRow("Demo mode", "Allows 9999 as Duress PIN, shows stage controls and telemetry.", demo) { demo = it; prefs.demoMode = it }
            ToggleRow(
                "Gateway phone (Phone C)", "Ignore direct packets (hops = 0); upload only relayed packets (hops ≥ 1).",
                gateway,
            ) { gateway = it; prefs.gatewayOnlyRelayed = it }
        }

        ShCard {
            Text("Route Replay (debug)", style = MaterialTheme.typography.titleMedium)
            Text("Feeds a GPX track through the same location flow as real GPS. The planned route is the bundled Galle Face → Bambalapitiya drive.", style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
            ToggleRow("Replay instead of GPS", "Applies to the next trip you start.", replay) { replay = it; ReplayController.enabled = it }
            ReplayController.Scenario.entries.forEach { sc ->
                FilterChip(selected = scenario == sc, onClick = {
                    if (sc == ReplayController.Scenario.CUSTOM) picker.launch(arrayOf("*/*"))
                    else { scenario = sc; ReplayController.scenario = sc }
                }, label = { Text(sc.label) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.0, 5.0, 10.0, 20.0).forEach { sp ->
                    FilterChip(selected = speed == sp, onClick = { speed = sp; ReplayController.speed = sp }, label = { Text("${sp.toInt()}×") })
                }
            }
            if (gpxNote.isNotBlank()) Text(gpxNote, style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
        }

        GhostButton("Re-run Bluetooth test") {
            // Result is logged to the telemetry panel below.
            scope.launch { TripBus.log("BT test: " + BluetoothSelfTest.run(ctx).message) }
        }

        ShCard {
            Text("Telemetry", style = MaterialTheme.typography.titleMedium)
            log.take(30).forEach { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Inter), color = Sh.Muted) }
            if (log.isEmpty()) Text("No events yet.", style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
        }
        GhostButton("Back", onClick = onBack)
    }
}
