package lk.safeheaven.trip

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lk.safeheaven.core.PanicEffect
import lk.safeheaven.core.PanicEvent
import lk.safeheaven.core.PanicState
import lk.safeheaven.core.PanicStateMachine
import lk.safeheaven.core.TripWatcher
import lk.safeheaven.data.Prefs
import lk.safeheaven.net.SupabaseApi
import lk.safeheaven.safety.FakeShutdownActivity
import lk.safeheaven.safety.RouteMatcher
import lk.safeheaven.safety.SafetyCheckActivity

/**
 * Foreground service (types: location | microphone | connectedDevice) hosting the trip.
 *
 * Android 14 rule: a microphone-type foreground service cannot be started from the background, so
 * [start] must be called while the app is on screen at trip start. The notification is deliberately
 * bland ("Sync running") and never changes, including during panic.
 */
class TripService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val machine = PanicStateMachine()
    private lateinit var prefs: Prefs
    private lateinit var api: SupabaseApi
    private lateinit var panic: PanicController
    private var session: TripSession? = null
    private var watcher: TripWatcher? = null
    private var watchJob: Job? = null
    private var countdownJob: Job? = null
    private var tokenJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var triggeredByExpiry = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        api = SupabaseApi.get(this)
        panic = PanicController(this, scope)
        // Actor loop: every event is applied to the state machine one at a time.
        scope.launch {
            for (event in TripBus.events) handle(event)
        }
        scope.launch {
            for (updated in TripBus.sessionUpdates) {
                session = updated
                TripBus.setSession(updated)
                prefs.activeTripJson = updated.toJson()
                if (machine.state == PanicState.TRIP_ACTIVE) startWatching()
                TripBus.log("route updated (${updated.route.size} pts)")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        val s = session ?: prefs.activeTripJson?.let { runCatching { TripSession.fromJson(it) }.getOrNull() }
        if (s == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (session == null) {
            session = s
            TripBus.setSession(s)
            if (machine.state == PanicState.IDLE) TripBus.send(PanicEvent.TripStarted)
        }
        return START_STICKY
    }

    private fun enterForeground() {
        val n = Notifications.sync(this)
        if (Build.VERSION.SDK_INT >= 30) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(Notifications.ID_SYNC, n, type)
        } else {
            startForeground(
                Notifications.ID_SYNC, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }
    }

    // ───────────────────────── state machine driver ─────────────────────────

    private suspend fun handle(event: PanicEvent) {
        val t = machine.handle(event)
        if (!t.accepted) {
            TripBus.log("ignored $event in ${t.from}")
            return
        }
        if (event == PanicEvent.CountdownExpired) triggeredByExpiry = true
        TripBus.log("${t.from} -> ${t.to} on $event")
        TripBus.setState(t.to)
        prefs.panicStateName = t.to.name
        t.effects.forEach { run(it) }
        if (t.to == PanicState.IDLE) shutDown()
    }

    private fun run(effect: PanicEffect) {
        when (effect) {
            PanicEffect.StartTripWatching -> startWatching()
            PanicEffect.StopTripWatching -> { watchJob?.cancel(); tokenJob?.cancel() }
            is PanicEffect.ShowSafetyCheck -> showSafetyCheck(effect)
            PanicEffect.DismissSafetyCheck -> dismissSafetyCheck()
            PanicEffect.SendArrivedSafely -> sendArrived()
            PanicEffect.StartSilentPanic -> startSilentPanic()
            PanicEffect.NotifyHavensAndContacts -> panic.onAlertSent()
            PanicEffect.StartMesh -> panic.onEnterMesh()
            PanicEffect.StopMesh -> panic.stopMesh()
            PanicEffect.StopPanicActions -> stopPanic()
        }
    }

    // ───────────────────────── trip watching ─────────────────────────

    private fun startWatching() {
        val s = session ?: return
        val matcher = RouteMatcher(s.route)
        val w = TripWatcher(
            destination = s.destination,
            startTimeMs = s.startMs,
            etaSeconds = s.etaSeconds,
            isOnRoute = matcher::isOnRoute,
        )
        watcher = w
        watchJob?.cancel()
        watchJob = scope.launch {
            LocationHub.fixes(this@TripService).collect { fix ->
                TripBus.setFix(fix)
                if (machine.state != PanicState.TRIP_ACTIVE) return@collect
                when (val v = w.onFix(fix)) {
                    TripWatcher.Verdict.Ok -> Unit
                    TripWatcher.Verdict.Arrived -> TripBus.send(PanicEvent.ArrivedAtDestination)
                    is TripWatcher.Verdict.Anomaly -> TripBus.send(PanicEvent.AnomalyDetected(v.reason))
                }
            }
        }
        // Keep the Supabase session fresh so the panic POST never pays for a token refresh.
        tokenJob?.cancel()
        tokenJob = scope.launch {
            while (isActive) {
                if (api.configured && api.isOnline()) runCatching { api.token() }
                delay(10 * 60_000L)
            }
        }
    }

    // ───────────────────────── safety check ─────────────────────────

    private fun showSafetyCheck(e: PanicEffect.ShowSafetyCheck) {
        val deadline = System.currentTimeMillis() + e.countdownSeconds * 1000L
        TripBus.setCheck(deadline, e.reason)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(Notifications.ID_CHECK, Notifications.safetyCheck(this))
        // Screen on and app visible: full-screen intents only heads-up, so open the pad directly.
        if (TripBus.appVisible) {
            runCatching {
                startActivity(Intent(this, SafetyCheckActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (isActive && System.currentTimeMillis() < deadline) delay(250)
            if (isActive) TripBus.send(PanicEvent.CountdownExpired)
        }
    }

    private fun dismissSafetyCheck() {
        countdownJob?.cancel()
        TripBus.setCheck(0L, null)
        getSystemService(NotificationManager::class.java).cancel(Notifications.ID_CHECK)
        // Restart anomaly clocks so the same condition doesn't re-fire instantly.
        watcher?.resetAfterCheck(TripBus.lastFix.value)
    }

    // ───────────────────────── panic ─────────────────────────

    private fun startSilentPanic() {
        countdownJob?.cancel()
        val s = session ?: return
        TripBus.setCheck(0L, null)
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(Notifications.ID_CHECK) // cancelling is silent: no sound, no vibration

        wakeLock = (getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "safeheaven:panic")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        // Timeout path: nobody answered, so no PIN flow is driving the UI. Show the dead screen.
        // (If the PIN pad is on screen it plays the shutdown itself when it sees SILENT_PANIC.)
        if (triggeredByExpiry && !TripBus.safetyCheckVisible && !TripBus.fakeShutdownVisible &&
            !prefs.disguiseAlarmCancelled
        ) {
            if (TripBus.appVisible) {
                runCatching {
                    startActivity(Intent(this, FakeShutdownActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } else {
                nm.notify(Notifications.ID_QUIET, Notifications.quietShutdownLauncher(this))
            }
        }
        panic.onEnterSilentPanic(s)
    }

    private fun stopPanic() {
        panic.stopAll()
        val s = TripBus.session.value
        if (s?.alertId != null && api.configured) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    api.postAlert(
                        org.json.JSONObject().put("alert_id", s.alertId).put("trip_id", s.tripId).put("resolve", true),
                        8_000,
                    )
                }
            }
        }
    }

    private fun sendArrived() {
        val s = session ?: return
        scope.launch(Dispatchers.IO) {
            if (api.configured) runCatching {
                api.notifyContacts("arrived", s.plate, null, null)
                api.setTripStatus(s.tripId, "arrived")
            }
        }
        TripBus.log("Arrived safely sent to contacts")
    }

    private suspend fun shutDown() {
        delay(1500) // let in-flight network calls from the last effects start
        runCatching { wakeLock?.release() }
        prefs.activeTripJson = null
        prefs.panicStateName = null
        TripBus.setSession(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Call only while an activity is visible (Android 14 microphone-FGS rule). */
        fun start(context: Context, session: TripSession) {
            Prefs.get(context).activeTripJson = session.toJson()
            context.startForegroundService(Intent(context, TripService::class.java))
        }
    }
}
