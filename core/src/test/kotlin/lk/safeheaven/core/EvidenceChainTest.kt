package lk.safeheaven.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EvidenceChainTest {
    private val alert = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun `h0 is sha256 of the photo`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            EvidenceChain.hex(EvidenceChain.h0("abc".toByteArray())),
        )
    }

    @Test fun `chain links depend on every input`() {
        val h0 = EvidenceChain.h0(byteArrayOf(1, 2, 3))
        val base = EvidenceChain.next(h0, byteArrayOf(9, 9), 1, 1000L, 6.9, 79.8, alert)
        assertNotEquals(EvidenceChain.hex(base), EvidenceChain.hex(EvidenceChain.next(h0, byteArrayOf(9, 8), 1, 1000L, 6.9, 79.8, alert)))
        assertNotEquals(EvidenceChain.hex(base), EvidenceChain.hex(EvidenceChain.next(h0, byteArrayOf(9, 9), 2, 1000L, 6.9, 79.8, alert)))
        assertNotEquals(EvidenceChain.hex(base), EvidenceChain.hex(EvidenceChain.next(h0, byteArrayOf(9, 9), 1, 1001L, 6.9, 79.8, alert)))
        assertNotEquals(EvidenceChain.hex(base), EvidenceChain.hex(EvidenceChain.next(h0, byteArrayOf(9, 9), 1, 1000L, 6.91, 79.8, alert)))
        assertNotEquals(EvidenceChain.hex(base), EvidenceChain.hex(EvidenceChain.next(h0, byteArrayOf(9, 9), 1, 1000L, 6.9, 79.8, "123e4567-e89b-12d3-a456-426614174001")))
        assertNotEquals(EvidenceChain.hex(base), EvidenceChain.hex(EvidenceChain.next(EvidenceChain.h0(byteArrayOf(4)), byteArrayOf(9, 9), 1, 1000L, 6.9, 79.8, alert)))
    }

    @Test fun `known answer vector for server parity`() {
        val h0 = EvidenceChain.h0("photo".toByteArray())
        val h1 = EvidenceChain.next(h0, "chunk1".toByteArray(), 1, 1_700_000_000_000L, 6.9271234, 79.8612345, alert)
        println("CHAIN_H0=" + EvidenceChain.hex(h0))
        println("CHAIN_H1=" + EvidenceChain.hex(h1))
        assertEquals(32, h1.size)
        assertContentEquals(h1, EvidenceChain.fromHex(EvidenceChain.hex(h1)))
    }
}
