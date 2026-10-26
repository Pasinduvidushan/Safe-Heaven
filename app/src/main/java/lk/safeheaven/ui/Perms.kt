package lk.safeheaven.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Runtime permission groups requested during onboarding. */
object Perms {
    val location = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val microphone = arrayOf(Manifest.permission.RECORD_AUDIO)
    val camera = arrayOf(Manifest.permission.CAMERA)
    val sms = arrayOf(Manifest.permission.SEND_SMS)
    val notifications: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    val bluetooth: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else emptyArray()

    fun granted(ctx: Context, perms: Array<String>): Boolean =
        perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun backgroundLocation(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** What a trip cannot start without. */
    fun tripReady(ctx: Context): Boolean =
        granted(ctx, location) && granted(ctx, microphone) && granted(ctx, camera) &&
            granted(ctx, notifications) && granted(ctx, bluetooth)
}
