package lk.safeheaven.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * BlackBox hash chain:
 *   h0 = SHA256(plate_photo_bytes)
 *   hn = SHA256(h(n-1) || chunk_n || n || t_n || GPS_n || alertId)
 *
 * Canonical encodings (mirrored byte-for-byte by supabase/functions/_shared/chain.ts):
 *   n        u32 big-endian
 *   t_n      i64 big-endian, epoch milliseconds
 *   GPS_n    lat i32 BE (deg * 1e7) || lon i32 BE (deg * 1e7)
 *   alertId  16 raw UUID bytes
 */
object EvidenceChain {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    fun h0(photoBytes: ByteArray): ByteArray = sha256(photoBytes)

    fun next(
        prev: ByteArray,
        chunk: ByteArray,
        n: Int,
        timeMs: Long,
        lat: Double,
        lon: Double,
        alertId: String,
    ): ByteArray {
        val meta = ByteBuffer.allocate(4 + 8 + 4 + 4)
        meta.putInt(n)
        meta.putLong(timeMs)
        meta.putInt(Math.round(lat * 1e7).toInt())
        meta.putInt(Math.round(lon * 1e7).toInt())
        return sha256(prev, chunk, meta.array(), uuidBytes(alertId))
    }

    fun uuidBytes(id: String): ByteArray {
        val u = UUID.fromString(id)
        return ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun fromHex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
