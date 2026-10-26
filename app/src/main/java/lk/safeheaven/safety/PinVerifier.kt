package lk.safeheaven.safety

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lk.safeheaven.core.PinVault
import lk.safeheaven.data.Prefs

/** Local-only PIN check (PBKDF2 for BOTH PINs on every attempt). Nothing is sent to the server. */
object PinVerifier {
    suspend fun verify(context: Context, pin: String): PinVault.Match = withContext(Dispatchers.Default) {
        val p = Prefs.get(context)
        val real = p.realPin
        val duress = p.duressPin
        if (real == null || duress == null) PinVault.Match.NONE else PinVault.verify(pin, real, duress)
    }
}
