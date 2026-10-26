package lk.safeheaven.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import lk.safeheaven.core.PinVault
import lk.safeheaven.data.Contact
import lk.safeheaven.data.KeyVault
import lk.safeheaven.data.Prefs
import lk.safeheaven.mesh.BluetoothSelfTest
import lk.safeheaven.mesh.MeshScanner
import lk.safeheaven.net.SupabaseApi
import lk.safeheaven.trip.Notifications
import lk.safeheaven.ui.Perms
import lk.safeheaven.ui.components.GhostButton
import lk.safeheaven.ui.components.PrimaryButton
import lk.safeheaven.ui.components.ShCard
import lk.safeheaven.ui.components.StatusPill
import lk.safeheaven.ui.components.ToggleRow
import lk.safeheaven.ui.theme.Sh

private const val STEPS = 6

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    var step by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Safe Heaven", style = MaterialTheme.typography.displayMedium, color = Sh.Teal)
        Text("Step ${step + 1} of $STEPS", style = MaterialTheme.typography.labelMedium, color = Sh.Muted)
        when (step) {
            0 -> WelcomeStep { step++ }
            1 -> ProfileStep(prefs) { step++ }
            2 -> ContactsStep(prefs) { step++ }
            3 -> PinStep(prefs) { step++ }
            4 -> PermissionsStep { step++ }
            5 -> BluetoothStep(prefs) {
                prefs.onboarded = true
                MeshScanner(ctx).start()
                onDone()
            }
        }
    }
}

@Composable
private fun WelcomeStep(next: () -> Unit) {
    Text("Your safety net for every ride.", style = MaterialTheme.typography.headlineLarge)
    Text(
        "Before your first trip we set up: who we tell, two secret PINs, the permissions that make alerts " +
            "work over a locked screen, and Bluetooth so your SOS can hop from phone to phone when there is no signal.",
        style = MaterialTheme.typography.bodyLarge, color = Sh.Muted,
    )
    PrimaryButton("Get started", onClick = next)
}

@Composable
private fun field(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Sh.Teal, unfocusedBorderColor = Sh.Outline, focusedLabelColor = Sh.Teal,
        ),
    )
}

@Composable
private fun ProfileStep(prefs: Prefs, next: () -> Unit) {
    var name by remember { mutableStateOf(prefs.name) }
    var phone by remember { mutableStateOf(prefs.phone) }
    Text("About you", style = MaterialTheme.typography.headlineMedium)
    Text("Your contacts see your name in alerts.", color = Sh.Muted, style = MaterialTheme.typography.bodyMedium)
    field("Your name", name, { name = it })
    field("Your phone number", phone, { phone = it }, KeyboardType.Phone)
    PrimaryButton("Continue", enabled = name.isNotBlank()) {
        prefs.name = name.trim(); prefs.phone = phone.trim(); next()
    }
}

@Composable
private fun ContactsStep(prefs: Prefs, next: () -> Unit) {
    val existing = prefs.contacts
    val names = remember { mutableStateOf(List(3) { existing.getOrNull(it)?.name ?: "" }) }
    val phones = remember { mutableStateOf(List(3) { existing.getOrNull(it)?.phone ?: "" }) }
    Text("Trusted contacts", style = MaterialTheme.typography.headlineMedium)
    Text("Texted when you start a trip, arrive safely, or trigger an alert. First is highest priority.",
        color = Sh.Muted, style = MaterialTheme.typography.bodyMedium)
    repeat(3) { i ->
        ShCard {
            Text("Contact ${i + 1}", style = MaterialTheme.typography.titleMedium)
            field("Name", names.value[i], { v -> names.value = names.value.toMutableList().also { it[i] = v } })
            field("Phone", phones.value[i], { v -> phones.value = phones.value.toMutableList().also { it[i] = v } }, KeyboardType.Phone)
        }
    }
    val valid = (0..2).any { names.value[it].isNotBlank() && phones.value[it].isNotBlank() }
    PrimaryButton("Continue", enabled = valid) {
        prefs.contacts = (0..2).filter { names.value[it].isNotBlank() && phones.value[it].isNotBlank() }
            .map { Contact(names.value[it].trim(), phones.value[it].trim()) }
        next()
    }
}

@Composable
private fun PinStep(prefs: Prefs, next: () -> Unit) {
    var real by remember { mutableStateOf("") }
    var duress by remember { mutableStateOf("") }
    var demo by remember { mutableStateOf(prefs.demoMode) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("Two PINs", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Real PIN cancels a safety check. Duress PIN looks identical but silently raises the alarm while your " +
            "screen plays a power-off. Both must have the same number of digits. They are stored only as salted hashes on this phone.",
        color = Sh.Muted, style = MaterialTheme.typography.bodyMedium,
    )
    field("Real PIN (4–6 digits)", real, { real = it.filter(Char::isDigit).take(6) }, KeyboardType.NumberPassword, secret = true)
    field("Duress PIN", duress, { duress = it.filter(Char::isDigit).take(6) }, KeyboardType.NumberPassword, secret = true)
    ToggleRow("Demo Mode", "Allows 9999 as Duress PIN and shows stage tools", demo) { demo = it }
    error?.let { Text(it, color = Sh.Danger, style = MaterialTheme.typography.bodyMedium) }
    PrimaryButton("Save PINs", enabled = !busy && real.length >= 4 && duress.length >= 4) {
        when (val c = PinVault.validatePair(real, duress, demo)) {
            is PinVault.Check.Rejected -> error = c.reason
            PinVault.Check.Ok -> {
                busy = true
                scope.launch {
                    val (r, d) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        PinVault.hash(real) to PinVault.hash(duress)
                    }
                    prefs.setPins(r, d, real.length)
                    prefs.demoMode = demo
                    next()
                }
            }
        }
    }
}

@Composable
private fun PermissionsStep(next: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    @Composable
    fun row(title: String, why: String, ok: Boolean, action: () -> Unit) {
        ShCard {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(why, style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
            if (ok) StatusPill("Granted", Sh.Teal) else PrimaryButton("Grant", onClick = action)
        }
    }
    val recomposeOnResume = tick // forces recomposition after returning from system settings

    Text("Permissions", style = MaterialTheme.typography.headlineMedium)
    row("Location", "Route watching and your position in alerts.", Perms.granted(ctx, Perms.location)) { launcher.launch(Perms.location) }
    row("Location · all the time", "Lets the relay scanner and trip keep working with the screen off. Choose “Allow all the time”.",
        Perms.backgroundLocation(ctx)) {
        if (Perms.granted(ctx, Perms.location)) bgLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else launcher.launch(Perms.location)
    }
    row("Microphone", "Evidence audio, only after a safety check fails or the Duress PIN is used.", Perms.granted(ctx, Perms.microphone)) { launcher.launch(Perms.microphone) }
    row("Camera", "Scan the vehicle plate at trip start.", Perms.granted(ctx, Perms.camera)) { launcher.launch(Perms.camera) }
    if (Perms.notifications.isNotEmpty()) {
        row("Notifications", "Shows the “Sync running” status and safety checks.", Perms.granted(ctx, Perms.notifications)) { launcher.launch(Perms.notifications) }
    }
    if (Perms.bluetooth.isNotEmpty()) {
        row("Nearby devices (Bluetooth)", "Broadcast and relay SOS packets.", Perms.granted(ctx, Perms.bluetooth)) { launcher.launch(Perms.bluetooth) }
    }
    row("SMS (optional)", "Last-resort text to your contacts when there is no data connection.", Perms.granted(ctx, Perms.sms)) { launcher.launch(Perms.sms) }

    val fsi = Notifications.canUseFullScreenIntent(ctx)
    ShCard {
        Text("Show over the lock screen", style = MaterialTheme.typography.titleMedium)
        Text(
            if (Build.VERSION.SDK_INT >= 34)
                "Android 14+ keeps full-screen alerts OFF until you allow them. Without this, a Safety Check cannot appear over a locked screen. Open the setting and switch “Allow full-screen notifications” on for Safe Heaven."
            else "Allowed by default on this Android version.",
            style = MaterialTheme.typography.bodySmall, color = Sh.Muted,
        )
        if (fsi) StatusPill("Allowed", Sh.Teal) else PrimaryButton("Open setting") {
            ctx.startActivity(Notifications.fullScreenIntentSettings(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    ShCard {
        Text("Battery (recommended)", style = MaterialTheme.typography.titleMedium)
        Text("Set Safe Heaven to “Unrestricted” battery use so the OS never pauses a trip.", style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
        GhostButton("Open app settings") {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    val ready = Perms.tripReady(ctx)
    PrimaryButton(if (ready) "Continue" else "Grant the required permissions first", enabled = ready, onClick = next)
}

@Composable
private fun BluetoothStep(prefs: Prefs, finish: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<BluetoothSelfTest.Result?>(null) }
    var running by remember { mutableStateOf(false) }
    var registering by remember { mutableStateOf(false) }
    var regNote by remember { mutableStateOf<String?>(null) }
    val api = remember { SupabaseApi.get(ctx) }

    Text("Bluetooth & Airplane Mode", style = MaterialTheme.typography.headlineMedium)
    Text(
        "When there is no signal your phone shouts for help over Bluetooth LE. Airplane Mode normally switches " +
            "Bluetooth off, and since Android 13 apps are not allowed to turn it back on. So: keep Bluetooth ON " +
            "yourself, even in Airplane Mode (switch Airplane Mode on, then tap Bluetooth back on in Quick Settings).",
        style = MaterialTheme.typography.bodyMedium, color = Sh.Muted,
    )
    GhostButton("Open Bluetooth settings") {
        ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    PrimaryButton(if (running) "Testing…" else "Run one-time test", enabled = !running) {
        running = true
        scope.launch {
            val r = BluetoothSelfTest.run(ctx)
            result = r
            if (r.ok) prefs.bluetoothTestPassed = true
            running = false
        }
    }
    result?.let { r ->
        ShCard {
            StatusPill(if (r.ok) "Passed" else "Failed", if (r.ok) Sh.Teal else Sh.Danger)
            Text(r.message, style = MaterialTheme.typography.bodyMedium)
            Text("Airplane Mode is currently ${if (r.airplaneMode) "ON" else "OFF"}.", style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
        }
    }
    regNote?.let { Text(it, color = Sh.Amber, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(4.dp))
    PrimaryButton(
        if (registering) "Setting up…" else if (prefs.bluetoothTestPassed) "Finish setup" else "Finish without passing the test",
        enabled = !registering,
    ) {
        registering = true
        scope.launch {
            // Mesh keys are created on-device (Keystore-sealed) and shared once with the server.
            runCatching { KeyVault.meshKeyMaterial(prefs) }
            if (api.configured) {
                runCatching { api.register() }.onFailure {
                    regNote = "Couldn't reach the server (${it.message}). The app works offline; it will register on your first trip."
                }
            } else {
                regNote = "No Supabase URL/key was built into this app, so it runs local-only."
            }
            registering = false
            finish()
        }
    }
}
