package lk.safeheaven.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinVaultTest {
    private val real = PinVault.hash("2580")
    private val duress = PinVault.hash("1357")

    @Test fun `real, duress and wrong pins are distinguished`() {
        assertEquals(PinVault.Match.REAL, PinVault.verify("2580", real, duress))
        assertEquals(PinVault.Match.DURESS, PinVault.verify("1357", real, duress))
        assertEquals(PinVault.Match.NONE, PinVault.verify("0001", real, duress))
    }

    @Test fun `salts differ so equal pins hash differently`() {
        val a = PinVault.hash("2580"); val b = PinVault.hash("2580")
        assertTrue(!a.hash.contentEquals(b.hash))
    }

    @Test fun `real and duress verification take comparable time`() {
        repeat(2) { PinVault.verify("2580", real, duress) } // warm up
        fun time(pin: String): Long { val t = System.nanoTime(); PinVault.verify(pin, real, duress); return System.nanoTime() - t }
        val r = (1..5).map { time("2580") }.sorted()[2]
        val d = (1..5).map { time("1357") }.sorted()[2]
        val ratio = r.toDouble() / d
        assertTrue(ratio in 0.6..1.6, "ratio=$ratio")
    }

    @Test fun `weak duress pins are blocked`() {
        for (d in listOf("0000", "1234", "9999")) {
            assertTrue(PinVault.validatePair("2580", d, demoMode = false) is PinVault.Check.Rejected, d)
        }
    }

    @Test fun `reversed real pin is blocked as duress`() =
        assertTrue(PinVault.validatePair("2580", "0852", false) is PinVault.Check.Rejected)

    @Test fun `identical pins are blocked`() =
        assertTrue(PinVault.validatePair("2580", "2580", false) is PinVault.Check.Rejected)

    @Test fun `9999 is allowed in demo mode only`() {
        assertEquals(PinVault.Check.Ok, PinVault.validatePair("2580", "9999", demoMode = true))
        assertTrue(PinVault.validatePair("2580", "0000", demoMode = true) is PinVault.Check.Rejected)
    }

    @Test fun `pins of different length are blocked`() =
        assertTrue(PinVault.validatePair("2580", "13579", false) is PinVault.Check.Rejected)

    @Test fun `good pair accepted`() = assertEquals(PinVault.Check.Ok, PinVault.validatePair("2580", "1357", false))
}
