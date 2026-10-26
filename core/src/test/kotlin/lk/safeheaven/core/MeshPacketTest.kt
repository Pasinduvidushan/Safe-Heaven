package lk.safeheaven.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshPacketTest {
    private val keys = MeshPacket.Keys(ByteArray(16) { it.toByte() }, ByteArray(16) { (0xA0 + it).toByte() })
    private val loc = MeshPacket.Body.Location(6.9271234, 79.8612345, 42, 12, 87, MeshPacket.FLAG_PANIC or MeshPacket.FLAG_DURESS)

    @Test fun `packet is exactly 27 bytes and fits the 31 byte adv budget`() {
        val p = MeshPacket.encode(keys, 0xDEADBEEFL, 1, 5, loc)
        assertEquals(27, p.size)
        assertEquals(31, p.size + 2 + 2)
    }

    @Test fun `plain header fields sit at documented offsets`() {
        val p = MeshPacket.encode(keys, 0x01020304L, 0x0A0B0C, 5, loc)
        assertEquals(0xA, (p[0].toInt() and 0xF0) ushr 4)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), p.copyOfRange(1, 5))
        assertContentEquals(byteArrayOf(0x0A, 0x0B, 0x0C), p.copyOfRange(5, 8))
        assertEquals(5, p[8].toInt())
    }

    @Test fun `type A round trips`() {
        val p = MeshPacket.encode(keys, 77, 9, 5, loc)
        val (h, b) = assertNotNull(MeshPacket.decode(keys, p))
        assertEquals(MeshPacket.TYPE_LOCATION, h.type)
        assertEquals(77, h.senderId)
        assertEquals(9, h.counter)
        val l = b as MeshPacket.Body.Location
        assertEquals(6.9271234, l.lat, 1e-7)
        assertEquals(79.8612345, l.lon, 1e-7)
        assertEquals(42, l.secondsSincePanic)
        assertEquals(12, l.accuracyM)
        assertEquals(87, l.batteryPct)
        assertEquals(loc.flags, l.flags)
    }

    @Test fun `negative coordinates round trip`() {
        val b = MeshPacket.Body.Location(-33.8688197, -151.2092955, 0, 0, 0, 0)
        val l = MeshPacket.decode(keys, MeshPacket.encode(keys, 1, 1, 5, b))!!.second as MeshPacket.Body.Location
        assertEquals(-33.8688197, l.lat, 1e-7)
        assertEquals(-151.2092955, l.lon, 1e-7)
    }

    @Test fun `type B plate round trips for every length`() {
        for (plate in listOf("WPCAB1234", "2511234", "CAB1234", "SPKA1234", "WPCAB-1234", "A")) {
            val p = MeshPacket.encode(keys, 5, 2, 5, MeshPacket.Body.Plate(plate))
            val (h, b) = MeshPacket.decode(keys, p)!!
            assertEquals(MeshPacket.TYPE_PLATE, h.type)
            assertEquals(plate, (b as MeshPacket.Body.Plate).plate)
        }
    }

    @Test fun `ten character plate uses exactly 8 bytes then zero padding`() {
        val enc = MeshPacket.encodePlate("ABCDEFGHIJ")
        assertEquals(14, enc.size)
        assertTrue(enc.copyOfRange(8, 14).all { it == 0.toByte() })
        assertEquals("ABCDEFGHIJ", MeshPacket.decodePlate(enc))
    }

    @Test fun `body is actually encrypted`() {
        val p = MeshPacket.encode(keys, 77, 9, 5, loc)
        val clearLat = java.nio.ByteBuffer.allocate(4).putInt(Math.round(loc.lat * 1e7).toInt()).array()
        assertTrue(!p.copyOfRange(9, 13).contentEquals(clearLat))
    }

    @Test fun `same body with different counters gives different ciphertext`() {
        val a = MeshPacket.encode(keys, 77, 1, 5, loc)
        val b = MeshPacket.encode(keys, 77, 2, 5, loc)
        assertTrue(!a.copyOfRange(9, 23).contentEquals(b.copyOfRange(9, 23)))
    }

    @Test fun `tampering with any mac covered byte is rejected`() {
        val p = MeshPacket.encode(keys, 77, 9, 5, loc)
        for (i in (0..7) + (9..22)) {
            val t = p.copyOf(); t[i] = (t[i].toInt() xor 0x01).toByte()
            assertNull(MeshPacket.decode(keys, t), "byte $i")
        }
    }

    @Test fun `wrong key is rejected`() {
        val p = MeshPacket.encode(keys, 77, 9, 5, loc)
        val other = MeshPacket.Keys(ByteArray(16) { 9 }, ByteArray(16) { 8 })
        assertNull(MeshPacket.decode(other, p))
    }

    @Test fun `ttl is not covered by the mac so relays can decrement it`() {
        var p = MeshPacket.encode(keys, 77, 9, 5, loc)
        repeat(5) {
            p = assertNotNull(MeshPacket.relayed(p))
            assertNotNull(MeshPacket.decode(keys, p))
        }
        assertEquals(0, p[8].toInt())
        assertNull(MeshPacket.relayed(p))
        assertEquals(5, MeshPacket.parseHeader(p)!!.hops)
    }

    @Test fun `hops derived from ttl`() {
        val p = MeshPacket.encode(keys, 77, 9, 5, loc)
        assertEquals(0, MeshPacket.parseHeader(p)!!.hops)
        assertEquals(1, MeshPacket.parseHeader(MeshPacket.relayed(p)!!)!!.hops)
    }

    @Test fun `known answer vector for server parity`() {
        val k = MeshPacket.Keys(ByteArray(16) { it.toByte() }, ByteArray(16) { (0xA0 + it).toByte() })
        val p = MeshPacket.encode(k, 0x11223344L, 0x000001, 5,
            MeshPacket.Body.Location(6.9271234, 79.8612345, 42, 12, 87, 3))
        println("MESH_VECTOR=" + EvidenceChain.hex(p))
        assertEquals(27, p.size)
    }
}
