package lk.safeheaven.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 hashes for the Real and Duress PIN. PINs never leave the device. */
object PinVault {
    const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    class Stored(val salt: ByteArray, val hash: ByteArray)

    enum class Match { REAL, DURESS, NONE }

    fun hash(pin: String, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): Stored {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Stored(salt, h)
    }

    /**
     * Always derives BOTH hashes and compares both in constant time, whatever the input, so the
     * Real and Duress paths cost the same CPU time and are indistinguishable from the outside.
     */
    fun verify(pin: String, real: Stored, duress: Stored): Match {
        val r = hash(pin, real.salt).hash
        val d = hash(pin, duress.salt).hash
        val isReal = MessageDigest.isEqual(r, real.hash)
        val isDuress = MessageDigest.isEqual(d, duress.hash)
        return when {
            isReal -> Match.REAL
            isDuress -> Match.DURESS
            else -> Match.NONE
        }
    }

    sealed interface Check {
        data object Ok : Check
        data class Rejected(val reason: String) : Check
    }

    private val WEAK_DURESS = setOf("0000", "1234", "9999")

    fun validatePair(real: String, duress: String, demoMode: Boolean): Check {
        if (!real.matches(Regex("\\d{4,6}"))) return Check.Rejected("Real PIN must be 4–6 digits")
        if (!duress.matches(Regex("\\d{4,6}"))) return Check.Rejected("Duress PIN must be 4–6 digits")
        // Same length so the keypad auto-submits identically for both (zero-tell UI).
        if (real.length != duress.length) return Check.Rejected("Both PINs must have the same number of digits")
        if (real == duress) return Check.Rejected("Duress PIN must differ from the Real PIN")
        if (duress == real.reversed()) return Check.Rejected("Duress PIN cannot be the Real PIN reversed")
        if (duress in WEAK_DURESS && !(demoMode && duress == "9999")) {
            return Check.Rejected("That Duress PIN is too easy to guess")
        }
        if (real in setOf("0000", "1234")) return Check.Rejected("Real PIN is too easy to guess")
        return Check.Ok
    }
}
