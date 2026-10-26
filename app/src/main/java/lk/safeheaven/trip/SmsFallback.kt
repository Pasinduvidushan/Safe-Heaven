package lk.safeheaven.trip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import lk.safeheaven.data.Prefs

/** SMS fallback for MESH_MODE; only attempted when a SIM is present and SEND_SMS is granted. */
object SmsFallback {
    fun hasSim(context: Context): Boolean {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
        return tm.simState == TelephonyManager.SIM_STATE_READY
    }

    fun sendToContacts(context: Context, phones: List<String>, text: String) {
        if (!hasSim(context)) { TripBus.log("SMS fallback skipped: no SIM"); return }
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            TripBus.log("SMS fallback skipped: SEND_SMS not granted"); return
        }
        val sms = if (android.os.Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        for (p in phones) {
            runCatching {
                val parts = sms.divideMessage(text)
                sms.sendMultipartTextMessage(p, null, parts, null, null)
                TripBus.log("SMS sent to $p")
            }.onFailure { TripBus.log("SMS to $p failed: ${it.message}") }
        }
    }
}
