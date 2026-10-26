package lk.safeheaven.trip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import lk.safeheaven.R
import lk.safeheaven.safety.FakeShutdownActivity
import lk.safeheaven.safety.SafetyCheckActivity

object Notifications {
    const val CH_SYNC = "sync"
    const val CH_CHECK = "check"
    const val CH_QUIET_FSI = "quiet_fsi"
    const val ID_SYNC = 1001
    const val ID_CHECK = 1002
    const val ID_QUIET = 1003

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SYNC, "Sync", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CHECK, "Safety checks", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        // High importance (required for full-screen intents) but zero sound/vibration.
        nm.createNotificationChannel(
            NotificationChannel(CH_QUIET_FSI, "System", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    /** Bland, constant foreground notification. Never changes during panic. */
    fun sync(ctx: Context): Notification =
        Notification.Builder(ctx, CH_SYNC)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(ctx.getString(R.string.sync_title))
            .setContentText(ctx.getString(R.string.sync_text))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    fun safetyCheck(ctx: Context): Notification {
        val pi = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, SafetyCheckActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(ctx, CH_CHECK)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("Are you OK? Enter PIN")
            .setContentText("Open now. This check closes in 30 seconds.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setTimeoutAfter(35_000)
            .build()
    }

    /** Silent full-screen launcher for the fake shutdown when no app window is on screen. */
    fun quietShutdownLauncher(ctx: Context): Notification {
        val pi = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, FakeShutdownActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(ctx, CH_QUIET_FSI)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(ctx.getString(R.string.sync_title))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setTimeoutAfter(3_000)
            .setFullScreenIntent(pi, true)
            .build()
    }

    fun canUseFullScreenIntent(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 34) return true
        return ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    /** Android 14+: USE_FULL_SCREEN_INTENT is a special access the user must grant in Settings. */
    fun fullScreenIntentSettings(ctx: Context): Intent =
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        }
}
