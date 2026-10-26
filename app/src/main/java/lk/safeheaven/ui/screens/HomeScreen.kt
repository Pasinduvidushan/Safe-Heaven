package lk.safeheaven.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import lk.safeheaven.core.PanicState
import lk.safeheaven.data.Prefs
import lk.safeheaven.trip.Notifications
import lk.safeheaven.trip.ReplayController
import lk.safeheaven.trip.TripBus
import lk.safeheaven.ui.Perms
import lk.safeheaven.ui.components.GhostButton
import lk.safeheaven.ui.components.PrimaryButton
import lk.safeheaven.ui.components.ShCard
import lk.safeheaven.ui.components.StatusPill
import lk.safeheaven.ui.theme.Montserrat
import lk.safeheaven.ui.theme.Outfit
import lk.safeheaven.ui.theme.Sh

@Composable
fun HomeScreen(onStartTrip: () -> Unit, onOpenTrip: () -> Unit, onSettings: () -> Unit, onRedoSetup: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = Prefs.get(ctx)
    val state by TripBus.state.collectAsState()
    val ready = Perms.tripReady(ctx)
    val fsi = Notifications.canUseFullScreenIntent(ctx)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Safe Heaven", style = MaterialTheme.typography.headlineLarge.copy(fontFamily = Outfit), color = Sh.Teal)
            if (prefs.demoMode) StatusPill("DEMO", Sh.Amber)
        }
        Spacer(Modifier.height(8.dp))
        ShCard {
            Text("STATUS", style = MaterialTheme.typography.labelMedium, color = Sh.Muted)
            Text(
                if (state == PanicState.IDLE) "No trip running" else "Trip active",
                style = MaterialTheme.typography.displayMedium.copy(fontFamily = Montserrat), color = Sh.Bone,
            )
            Text("Relay scanner: on · every phone running Safe Heaven can pass an SOS along.", style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
        }
        if (state == PanicState.IDLE) {
            PrimaryButton("Start a trip", enabled = ready, onClick = onStartTrip)
            if (!ready) Text("Some permissions are missing. Re-run setup to grant them.", color = Sh.Amber, style = MaterialTheme.typography.bodySmall)
            if (ReplayController.enabled) Text("Route Replay is ON (${ReplayController.scenario.label}, ${ReplayController.speed.toInt()}×)", color = Sh.Amber, style = MaterialTheme.typography.bodySmall)
        } else {
            PrimaryButton("Open current trip", onClick = onOpenTrip)
        }
        if (!fsi) {
            ShCard {
                Text("Lock-screen alerts are off", style = MaterialTheme.typography.titleMedium, color = Sh.Amber)
                Text("Allow full-screen notifications so a Safety Check can appear over the lock screen.", style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
                GhostButton("Open setting") {
                    ctx.startActivity(Notifications.fullScreenIntentSettings(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        GhostButton("Settings & demo tools", onClick = onSettings)
        GhostButton("Re-run setup", onClick = onRedoSetup)
    }
}
