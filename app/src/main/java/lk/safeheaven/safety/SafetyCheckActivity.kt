package lk.safeheaven.safety

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.addCallback
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lk.safeheaven.core.AnomalyReason
import lk.safeheaven.core.LatLng
import lk.safeheaven.core.PanicEvent
import lk.safeheaven.core.PanicState
import lk.safeheaven.core.PinVault
import lk.safeheaven.data.Prefs
import lk.safeheaven.net.RouteClient
import lk.safeheaven.trip.LocationHub
import lk.safeheaven.trip.Notifications
import lk.safeheaven.trip.ReplayController
import lk.safeheaven.trip.TripBus
import lk.safeheaven.ui.components.PinPad
import lk.safeheaven.ui.theme.Outfit
import lk.safeheaven.ui.theme.SafeHeavenTheme
import lk.safeheaven.ui.theme.Sh

/**
 * Launched over the lock screen by the full-screen-intent notification ("Are you OK? Enter PIN").
 * 30-second countdown. Real and Duress PINs run through the identical code path and the identical
 * "Cancelling…" animation (CANCEL_MS); only after that fixed delay does the UI diverge.
 */
class SafetyCheckActivity : ComponentActivity() {
    private enum class Phase { ENTRY, CANCELLING, REAL_DONE, DURESS_SHUTDOWN, DURESS_DISGUISE, WRONG }

    private var phase by mutableStateOf(Phase.ENTRY)
    private var wrongCounter by mutableIntStateOf(0)
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(KeyguardManager::class.java) // keep lock screen in place; pad shows above it
        onBackPressedDispatcher.addCallback(this) { /* swallow: the check must be answered */ }

        if (TripBus.state.value != PanicState.SAFETY_CHECK) {
            finish()
            return
        }
        val prefs = Prefs.get(this)

        // Timeout while the pad is on screen: nobody answered, treat exactly like a Duress PIN.
        lifecycleScope.launch {
            TripBus.state.collect { s ->
                if (s == PanicState.SILENT_PANIC && phase == Phase.ENTRY) runDuressUi(prefs.disguiseAlarmCancelled)
            }
        }

        setContent {
            SafeHeavenTheme {
                val deadline by TripBus.checkDeadline.collectAsState()
                val reason by TripBus.checkReason.collectAsState()
                CheckScreen(prefs.pinLength, deadline, reason)
            }
        }
    }

    override fun onStart() { super.onStart(); TripBus.safetyCheckVisible = true }
    override fun onStop() { super.onStop(); TripBus.safetyCheckVisible = false }

    private fun submit(pin: String) {
        if (phase != Phase.ENTRY) return
        phase = Phase.CANCELLING
        val prefs = Prefs.get(this)
        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            val match = PinVerifier.verify(this@SafetyCheckActivity, pin)
            // Fire the state-machine event immediately (first alert goes out within 5 s) —
            // before any UI divergence, so timing on screen is identical for both PINs.
            when (match) {
                PinVault.Match.REAL -> TripBus.send(PanicEvent.RealPinEntered)
                PinVault.Match.DURESS -> TripBus.send(PanicEvent.DuressPinEntered)
                PinVault.Match.NONE -> Unit
            }
            val remaining = CANCEL_MS - (System.currentTimeMillis() - t0)
            if (remaining > 0) delay(remaining)
            when (match) {
                PinVault.Match.REAL -> {
                    phase = Phase.REAL_DONE
                    getSystemService(NotificationManager::class.java).cancel(Notifications.ID_CHECK)
                }
                PinVault.Match.DURESS -> runDuressUi(prefs.disguiseAlarmCancelled)
                PinVault.Match.NONE -> {
                    wrongCounter++
                    phase = Phase.WRONG
                    delay(900)
                    if (TripBus.state.value == PanicState.SAFETY_CHECK) phase = Phase.ENTRY
                }
            }
        }
    }

    private fun runDuressUi(disguise: Boolean) {
        if (phase == Phase.DURESS_SHUTDOWN || phase == Phase.DURESS_DISGUISE) return
        lifecycleScope.launch {
            if (disguise) {
                phase = Phase.DURESS_DISGUISE        // looks exactly like "Alarm cancelled"
                delay(DISGUISE_MS)
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                phase = Phase.DURESS_SHUTDOWN        // power-off animation
                delay(SHUTDOWN_ANIM_MS)
                startActivity(Intent(this@SafetyCheckActivity, FakeShutdownActivity::class.java))
            }
            finishAndRemoveTask()
        }
    }

    // ─────────────────────────── UI ───────────────────────────

    @Composable
    private fun CheckScreen(pinLength: Int, deadline: Long, reason: AnomalyReason?) {
        var secondsLeft by remember { mutableIntStateOf(30) }
        LaunchedEffect(deadline) {
            while (true) {
                secondsLeft = ((deadline - System.currentTimeMillis() + 999) / 1000).toInt().coerceIn(0, 30)
                delay(100)
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
            when (phase) {
                Phase.ENTRY, Phase.WRONG -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Are you OK?", style = MaterialTheme.typography.displayMedium, color = Sh.Bone)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (reason) {
                            AnomalyReason.OFF_ROUTE -> "You left the planned route"
                            AnomalyReason.LATE_ETA -> "The trip is taking much longer than expected"
                            AnomalyReason.STATIONARY -> "You haven't moved for a while"
                            null -> "Safety check"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = Sh.Muted, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "$secondsLeft",
                        style = MaterialTheme.typography.displayLarge.copy(fontFamily = Outfit),
                        color = if (secondsLeft <= 10) Sh.Danger else Sh.Teal,
                    )
                    Text("seconds to enter your PIN", style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
                    Spacer(Modifier.height(20.dp))
                    PinPad(pinLength, enabled = phase == Phase.ENTRY, resetKey = wrongCounter) { submit(it) }
                    if (phase == Phase.WRONG) {
                        Text("Incorrect PIN", color = Sh.Danger, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Phase.CANCELLING -> Cancelling()

                Phase.REAL_DONE, Phase.DURESS_DISGUISE -> AlarmCancelled(real = phase == Phase.REAL_DONE)

                Phase.DURESS_SHUTDOWN -> PowerOffAnimation()
            }
        }
    }

    @Composable
    private fun Cancelling() = Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Sh.Teal, modifier = Modifier.size(46.dp))
        Spacer(Modifier.height(18.dp))
        Text("Cancelling…", style = MaterialTheme.typography.titleLarge, color = Sh.Bone)
    }

    @Composable
    private fun AlarmCancelled(real: Boolean) {
        val scope = rememberCoroutineScope()
        var updating by remember { mutableStateOf(false) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("✓", color = Sh.Teal, style = MaterialTheme.typography.displayLarge)
            Text("Alarm cancelled", style = MaterialTheme.typography.headlineLarge, color = Sh.Bone)
            Text(
                if (updating) "Updating route…" else "Your trip continues.",
                style = MaterialTheme.typography.bodyMedium, color = Sh.Muted,
            )
            // Same buttons on both branches so the disguise is faithful; only the real branch acts.
            OutlinedButton(onClick = {
                updating = true
                if (real) scope.launch { rerouteFromHere() }
            }) { Text("I changed my route") }
            Button(
                onClick = { if (real) finishAndRemoveTask() },
                colors = ButtonDefaults.buttonColors(containerColor = Sh.Teal, contentColor = Sh.Ink),
            ) { Text("Done") }
        }
        if (real) LaunchedEffect(Unit) { delay(8_000); if (!updating) finishAndRemoveTask() }
    }

    @Composable
    private fun PowerOffAnimation() {
        val t = rememberInfiniteTransition(label = "off")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Sh.Bone, modifier = Modifier.size(46.dp).alpha(a))
            Spacer(Modifier.height(18.dp))
            Text("Shutting down…", style = MaterialTheme.typography.titleLarge, color = Sh.Bone, modifier = Modifier.alpha(a))
        }
    }

    /** "I changed my route": fetch a fresh polyline from the current position to the destination. */
    private suspend fun rerouteFromHere() {
        val s = TripBus.session.value ?: return finishAndRemoveTask()
        runCatching {
            if (ReplayController.enabled) return@runCatching null
            val here = LocationHub.lastKnown(this) ?: return@runCatching null
            RouteClient.fetch(LatLng(here.lat, here.lon), s.destination)
        }.getOrNull()?.let { route ->
            TripBus.sessionUpdates.trySend(s.copy(polyline = route.polyline, etaSeconds = route.etaSeconds, startMs = System.currentTimeMillis()))
        }
        finishAndRemoveTask()
    }

    companion object {
        /** Identical for Real, Duress and wrong PINs. */
        const val CANCEL_MS = 1800L
        const val SHUTDOWN_ANIM_MS = 1600L
        const val DISGUISE_MS = 2600L
    }
}
