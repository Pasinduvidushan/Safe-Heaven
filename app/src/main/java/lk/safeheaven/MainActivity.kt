package lk.safeheaven

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import lk.safeheaven.core.PanicState
import lk.safeheaven.data.Prefs
import lk.safeheaven.trip.TripBus
import lk.safeheaven.ui.screens.HomeScreen
import lk.safeheaven.ui.screens.OnboardingScreen
import lk.safeheaven.ui.screens.SettingsScreen
import lk.safeheaven.ui.screens.TripActiveScreen
import lk.safeheaven.ui.screens.TripSetupScreen
import lk.safeheaven.ui.theme.SafeHeavenTheme
import lk.safeheaven.ui.theme.Sh

private enum class Screen { ONBOARDING, HOME, TRIP_SETUP, TRIP_ACTIVE, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SafeHeavenTheme { Root() } }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    val tripState by TripBus.state.collectAsState()
    var screen by remember {
        mutableStateOf(
            when {
                !prefs.onboarded || prefs.realPin == null -> Screen.ONBOARDING
                TripBus.state.value != PanicState.IDLE -> Screen.TRIP_ACTIVE
                else -> Screen.HOME
            },
        )
    }
    LaunchedEffect(tripState) {
        if (tripState == PanicState.IDLE && screen == Screen.TRIP_ACTIVE) screen = Screen.HOME
    }
    Box(Modifier.fillMaxSize().background(Sh.Ink).statusBarsPadding()) {
        when (screen) {
            Screen.ONBOARDING -> OnboardingScreen(onDone = { screen = Screen.HOME })
            Screen.HOME -> HomeScreen(
                onStartTrip = { screen = Screen.TRIP_SETUP },
                onOpenTrip = { screen = Screen.TRIP_ACTIVE },
                onSettings = { screen = Screen.SETTINGS },
                onRedoSetup = { screen = Screen.ONBOARDING },
            )
            Screen.TRIP_SETUP -> TripSetupScreen(onStarted = { screen = Screen.TRIP_ACTIVE }, onCancel = { screen = Screen.HOME })
            Screen.TRIP_ACTIVE -> TripActiveScreen(onClosed = { screen = Screen.HOME })
            Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
        }
    }
}
