package lk.safeheaven.safety

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lk.safeheaven.MainActivity
import lk.safeheaven.core.PanicEvent
import lk.safeheaven.core.PinVault
import lk.safeheaven.data.Prefs
import lk.safeheaven.trip.Notifications
import lk.safeheaven.trip.TripBus
import lk.safeheaven.ui.components.PinPad

/**
 * The "dead phone". Immersive black screen at minimum brightness; every touch and the Back button
 * are swallowed. setShowWhenLocked(true) means that pressing the power button and waking the phone
 * lands on this same black screen instead of the lock screen.
 *
 * Exit: hold Volume Up + Volume Down together for 5 s, then enter the Real PIN. (The Duress PIN or
 * any other code just returns to black.)
 */
class FakeShutdownActivity : ComponentActivity() {
    private var showPad by mutableStateOf(false)
    private var padEpoch by mutableIntStateOf(0)
    private var volUp = false
    private var volDown = false
    private var holdJob: Job? = null
    private var padIdleJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawableResource(android.R.color.black)
        window.attributes = window.attributes.apply {
            screenBrightness = 0f // forced to minimum
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        // No sound, no vibration, no leftover notification from the silent launcher.
        getSystemService(NotificationManager::class.java).apply {
            cancel(Notifications.ID_QUIET)
            cancel(Notifications.ID_CHECK)
        }
        onBackPressedDispatcher.addCallback(this) { /* swallowed */ }

        val prefs = Prefs.get(this)
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (showPad) {
                    PinPad(prefs.pinLength, enabled = true, dim = true, resetKey = padEpoch) { pin ->
                        lifecycleScope.launch { onPinEntered(pin) }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); TripBus.fakeShutdownVisible = true }
    override fun onStop() { super.onStop(); TripBus.fakeShutdownVisible = false }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Swallow all touches while the pad is hidden (so the screen is truly inert). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        if (showPad) super.dispatchTouchEvent(ev) else true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val down = event.action == KeyEvent.ACTION_DOWN
            if (event.repeatCount == 0 || !down) {
                if (code == KeyEvent.KEYCODE_VOLUME_UP) volUp = down else volDown = down
                if (volUp && volDown) {
                    if (holdJob?.isActive != true) {
                        holdJob = lifecycleScope.launch {
                            delay(HOLD_MS)
                            revealPad()
                        }
                    }
                } else {
                    holdJob?.cancel()
                }
            }
            return true // also keeps the system volume UI from appearing
        }
        if (code == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    private fun revealPad() {
        showPad = true
        padEpoch++
        padIdleJob?.cancel()
        padIdleJob = lifecycleScope.launch { delay(PAD_IDLE_MS); showPad = false }
    }

    private suspend fun onPinEntered(pin: String) {
        when (PinVerifier.verify(this, pin)) {
            PinVault.Match.REAL -> {
                TripBus.send(PanicEvent.Resolved) // user is safe: stop recording/mesh, resolve alert
                finishAndRemoveTask()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> {
                // Duress PIN or wrong code: stay dead.
                showPad = false
            }
        }
    }

    companion object {
        const val HOLD_MS = 5_000L
        const val PAD_IDLE_MS = 20_000L
    }
}
