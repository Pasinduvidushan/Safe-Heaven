package lk.safeheaven.core

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 27-byte payload carried as manufacturer-specific data (test company ID 0xFFFF) in a legacy,
 * non-connectable advertisement:  31 (adv) - 2 (AD len/type) - 2 (company ID) = 27.
 *
 *  0        header: magic nibble 0xA | version(2b) | type(2b: 0=A location, 1=B plate)   plain
 *  1..4     sender_id (u32 BE)                                                          plain
 *  5..7     counter (u24 BE), monotonic, doubles as AES-CTR nonce                       plain
 *  8        TTL (starts at 5, relays decrement)                                         plain, not MACed
 *  9..22    14-byte body, AES-128-CTR                                                   encrypted
 *  23..26   first 4 bytes of HMAC-SHA256(hmacKey, bytes 0..7 || bytes 9..22)            plain
 *
 * AES-CTR IV (16 B) = sender_id(4) || counter(3) || 0x00 * 9, so every packet from one sender
 * encrypts under a unique counter block range. The server rebuilds the same IV from the plain fields.
 */
object MeshPacket {
    const val SIZE = 27
    const val COMPANY_ID = 0xFFFF
    const val MAGIC = 0xA
    const val VERSION = 1
    const val INITIAL_TTL = 5
    const val TYPE_LOCATION = 0
    const val TYPE_PLATE = 1
    const val FLAG_PANIC = 0x0001
    const val FLAG_DURESS = 0x0002
    const val FLAG_LOW_BATTERY = 0x0004
    const val FLAG_CHARGING = 0x0008
    private const val PLATE_ALPHABET = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-"

    class Keys(val aes: ByteArray, val hmac: ByteArray) {
        init {
            require(aes.size == 16) { "AES key must be 16 bytes" }
            require(hmac.size == 16) { "HMAC key must be 16 bytes" }
        }
    }

    sealed interface Body {
        data class Location(
            val lat: Double,
            val lon: Double,
            val secondsSincePanic: Int,
            val accuracyM: Int,
            val batteryPct: Int,
            val flags: Int,
        ) : Body

        data class Plate(val plate: String) : Body
    }

    /** Decoded header + still-encrypted body; enough to dedupe, rate-limit and route a packet. */
    class Header(
        val version: Int,
        val type: Int,
        val senderId: Long,
        val counter: Int,
        val ttl: Int,
    ) {
        val hops: Int get() = INITIAL_TTL - ttl
    }

    fun looksLikeMeshPacket(raw: ByteArray): Boolean =
        raw.size == SIZE && ((raw[0].toInt() and 0xF0) ushr 4) == MAGIC

    fun parseHeader(raw: ByteArray): Header? {
        if (!looksLikeMeshPacket(raw)) return null
        val h = raw[0].toInt() and 0xFF
        val bb = ByteBuffer.wrap(raw)
        val sender = bb.getInt(1).toLong() and 0xFFFFFFFFL
        val counter = ((raw[5].toInt() and 0xFF) shl 16) or ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        return Header((h shr 2) and 0x3, h and 0x3, sender, counter, raw[8].toInt() and 0xFF)
    }

    fun encode(
        keys: Keys,
        senderId: Long,
        counter: Int,
        ttl: Int,
        body: Body,
    ): ByteArray {
        require(counter in 0..0xFFFFFF) { "counter is 24 bit" }
        require(ttl in 0..255)
        val type = if (body is Body.Location) TYPE_LOCATION else TYPE_PLATE
        val plain = when (body) {
            is Body.Location -> encodeLocation(body)
            is Body.Plate -> encodePlate(body.plate)
        }
        val out = ByteArray(SIZE)
        out[0] = ((MAGIC shl 4) or (VERSION shl 2) or type).toByte()
        putU32(out, 1, senderId)
        out[5] = (counter shr 16).toByte()
        out[6] = (counter shr 8).toByte()
        out[7] = counter.toByte()
        out[8] = ttl.toByte()
        val enc = ctr(keys.aes, senderId, counter, plain)
        System.arraycopy(enc, 0, out, 9, 14)
        val mac = mac(keys.hmac, out)
        System.arraycopy(mac, 0, out, 23, 4)
        return out
    }

    /** Returns null when magic/version is wrong or the HMAC does not verify. */
    fun decode(keys: Keys, raw: ByteArray): Pair<Header, Body>? {
        val header = parseHeader(raw) ?: return null
        if (header.version != VERSION) return null
        val expected = mac(keys.hmac, raw)
        var diff = 0
        for (i in 0 until 4) diff = diff or (expected[i].toInt() xor raw[23 + i].toInt())
        if (diff != 0) return null
        val plain = ctr(keys.aes, header.senderId, header.counter, raw.copyOfRange(9, 23))
        val body = if (header.type == TYPE_LOCATION) decodeLocation(plain) else Body.Plate(decodePlate(plain))
        return header to body
    }

    /** Relay step: same bytes, TTL - 1. HMAC excludes TTL so it still verifies. Null when TTL is spent. */
    fun relayed(raw: ByteArray): ByteArray? {
        val ttl = raw[8].toInt() and 0xFF
        if (ttl <= 0) return null
        return raw.copyOf().also { it[8] = (ttl - 1).toByte() }
    }

    private fun encodeLocation(b: Body.Location): ByteArray {
        val bb = ByteBuffer.allocate(14)
        bb.putInt(Math.round(b.lat * 1e7).toInt())
        bb.putInt(Math.round(b.lon * 1e7).toInt())
        bb.putShort(b.secondsSincePanic.coerceIn(0, 0xFFFF).toShort())
        bb.put(b.accuracyM.coerceIn(0, 255).toByte())
        bb.put(b.batteryPct.coerceIn(0, 100).toByte())
        bb.putShort(b.flags.toShort())
        return bb.array()
    }

    private fun decodeLocation(p: ByteArray): Body.Location {
        val bb = ByteBuffer.wrap(p)
        return Body.Location(
            lat = bb.getInt() / 1e7,
            lon = bb.getInt() / 1e7,
            secondsSincePanic = bb.getShort().toInt() and 0xFFFF,
            accuracyM = bb.get().toInt() and 0xFF,
            batteryPct = bb.get().toInt() and 0xFF,
            flags = bb.getShort().toInt() and 0xFFFF,
        )
    }

    /** 6 bits per char, up to 10 chars = 60 bits in 8 bytes, then 6 bytes of zero padding. */
    fun encodePlate(plate: String): ByteArray {
        val chars = plate.uppercase().filter { PLATE_ALPHABET.indexOf(it) > 0 }.take(10)
        var acc = 0L
        for (i in 0 until 10) {
            val idx = if (i < chars.length) PLATE_ALPHABET.indexOf(chars[i]) else 0
            acc = (acc shl 6) or idx.toLong()
        }
        // 60 bits left-aligned into 64.
        acc = acc shl 4
        val out = ByteArray(14)
        for (i in 0 until 8) out[i] = (acc ushr (56 - 8 * i)).toByte()
        return out
    }

    fun decodePlate(p: ByteArray): String {
        var acc = 0L
        for (i in 0 until 8) acc = (acc shl 8) or (p[i].toLong() and 0xFF)
        acc = acc ushr 4
        val sb = StringBuilder()
        for (i in 0 until 10) {
            val idx = ((acc ushr (6 * (9 - i))) and 0x3F).toInt()
            if (idx in 1 until PLATE_ALPHABET.length) sb.append(PLATE_ALPHABET[idx])
        }
        return sb.toString()
    }

    private fun ctr(aesKey: ByteArray, senderId: Long, counter: Int, data: ByteArray): ByteArray {
        val iv = ByteArray(16)
        putU32(iv, 0, senderId)
        iv[4] = (counter shr 16).toByte()
        iv[5] = (counter shr 8).toByte()
        iv[6] = counter.toByte()
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun mac(hmacKey: ByteArray, packet: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        m.update(packet, 0, 8)      // header, sender_id, counter (TTL at index 8 excluded)
        m.update(packet, 9, 14)     // ciphertext
        return m.doFinal()
    }

    private fun putU32(a: ByteArray, off: Int, v: Long) {
        a[off] = (v shr 24).toByte()
        a[off + 1] = (v shr 16).toByte()
        a[off + 2] = (v shr 8).toByte()
        a[off + 3] = v.toByte()
    }
}
