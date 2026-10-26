package lk.safeheaven.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import lk.safeheaven.data.Prefs

/** Re-arms the relay scanner after a reboot or app update so every instance keeps relaying. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.get(context).onboarded) MeshScanner(context).start()
    }
}
