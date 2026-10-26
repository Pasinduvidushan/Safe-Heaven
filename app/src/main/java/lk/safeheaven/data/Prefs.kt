package lk.safeheaven.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import lk.safeheaven.core.PinVault
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Contact(val name: String, val phone: String)

/** App-private settings. PINs are stored only as salted PBKDF2 hashes. */
class Prefs private constructor(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("safe_heaven", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit().putString("name", v).apply()

    var phone: String
        get() = sp.getString("phone", "") ?: ""
        set(v) = sp.edit().putString("phone", v).apply()

    var contacts: List<Contact>
        get() {
            val arr = JSONArray(sp.getString("contacts", "[]"))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Contact(o.getString("name"), o.getString("phone"))
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("name", it.name).put("phone", it.phone)) }
            sp.edit().putString("contacts", arr.toString()).apply()
        }

    /** Demo Mode: allows 9999 as Duress PIN and exposes the stage tools. */
    var demoMode: Boolean
        get() = sp.getBoolean("demo_mode", false)
        set(v) = sp.edit().putBoolean("demo_mode", v).apply()

    /** Phone C on stage: ignore direct packets (hops == 0), upload only relayed ones. */
    var gatewayOnlyRelayed: Boolean
        get() = sp.getBoolean("gateway_only_relayed", false)
        set(v) = sp.edit().putBoolean("gateway_only_relayed", v).apply()

    /** Duress disguise: false = fake shutdown (default), true = "Alarm cancelled" then Home. */
    var disguiseAlarmCancelled: Boolean
        get() = sp.getBoolean("disguise_alarm_cancelled", false)
        set(v) = sp.edit().putBoolean("disguise_alarm_cancelled", v).apply()

    var bluetoothTestPassed: Boolean
        get() = sp.getBoolean("bt_test_passed", false)
        set(v) = sp.edit().putBoolean("bt_test_passed", v).apply()

    val deviceId: String
        get() = sp.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("device_id", it).apply()
        }

    // ── PINs ──
    fun setPins(real: PinVault.Stored, duress: PinVault.Stored, length: Int) {
        sp.edit()
            .putString("pin_real", encode(real))
            .putString("pin_duress", encode(duress))
            .putInt("pin_length", length)
            .apply()
    }

    val realPin: PinVault.Stored? get() = sp.getString("pin_real", null)?.let(::decode)
    val duressPin: PinVault.Stored? get() = sp.getString("pin_duress", null)?.let(::decode)
    val pinLength: Int get() = sp.getInt("pin_length", 4)

    private fun encode(s: PinVault.Stored) =
        b64(s.salt) + ":" + b64(s.hash)

    private fun decode(s: String): PinVault.Stored {
        val (salt, hash) = s.split(":")
        return PinVault.Stored(unb64(salt), unb64(hash))
    }

    // ── mesh identity ──
    var meshSenderId: Long
        get() = sp.getLong("mesh_sender_id", -1L)
        set(v) = sp.edit().putLong("mesh_sender_id", v).apply()

    var meshKeysWrapped: String?
        get() = sp.getString("mesh_keys_wrapped", null)
        set(v) = sp.edit().putString("mesh_keys_wrapped", v).apply()

    var registered: Boolean
        get() = sp.getBoolean("registered", false)
        set(v) = sp.edit().putBoolean("registered", v).apply()

    /** 24-bit monotonic counter; doubles as the AES-CTR nonce, so it must never repeat. */
    @Synchronized
    fun nextMeshCounter(): Int {
        val next = (sp.getInt("mesh_counter", 0) + 1) and 0xFFFFFF
        sp.edit().putInt("mesh_counter", next).commit()
        return next
    }

    // ── Supabase session ──
    var accessToken: String?
        get() = sp.getString("access_token", null)
        set(v) = sp.edit().putString("access_token", v).apply()
    var refreshToken: String?
        get() = sp.getString("refresh_token", null)
        set(v) = sp.edit().putString("refresh_token", v).apply()
    var tokenExpiresAtMs: Long
        get() = sp.getLong("token_expires_at", 0L)
        set(v) = sp.edit().putLong("token_expires_at", v).apply()
    var userId: String?
        get() = sp.getString("user_id", null)
        set(v) = sp.edit().putString("user_id", v).apply()

    // ── active trip / panic persistence (survives a process restart) ──
    var activeTripJson: String?
        get() = sp.getString("active_trip", null)
        set(v) = sp.edit().putString("active_trip", v).commit().let { }
    var panicStateName: String?
        get() = sp.getString("panic_state", null)
        set(v) = sp.edit().putString("panic_state", v).commit().let { }

    companion object {
        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }

        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    }
}
