package lk.safeheaven.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import lk.safeheaven.core.MeshPacket
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's 16 B AES + 16 B HMAC mesh keys are generated on-device, sealed under a
 * non-exportable Android Keystore AES-GCM master key, and stored only in sealed form.
 * The same 32 bytes are sent once (TLS) at registration so the server can verify relayed packets;
 * the server keeps them encrypted at rest (users.mesh_key_encrypted).
 */
object KeyVault {
    private const val ALIAS = "safe_heaven_master_v1"

    private fun master(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, master())
        return Prefs.b64(c.iv + c.doFinal(plain))
    }

    fun open(sealed: String): ByteArray {
        val all = Prefs.unb64(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, master(), GCMParameterSpec(128, all, 0, 12))
        return c.doFinal(all, 12, all.size - 12)
    }

    /** 32 bytes: AES key || HMAC key. Created on first use. */
    @Synchronized
    fun meshKeyMaterial(prefs: Prefs): ByteArray {
        prefs.meshKeysWrapped?.let { return open(it) }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.meshKeysWrapped = seal(raw)
        return raw
    }

    fun meshKeys(prefs: Prefs): MeshPacket.Keys {
        val raw = meshKeyMaterial(prefs)
        return MeshPacket.Keys(raw.copyOfRange(0, 16), raw.copyOfRange(16, 32))
    }
}
