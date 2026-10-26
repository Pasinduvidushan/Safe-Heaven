package lk.safeheaven.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlateNormalizerTest {
    private fun plate(raw: String) = PlateNormalizer.normalize(raw).plate

    @Test fun `province plate with spaces and dash`() = assertEquals("WPCAB1234", plate("WP CAB-1234"))
    @Test fun `lowercase and symbols are stripped`() = assertEquals("WPCAB1234", plate("wp · cab – 1234"))
    @Test fun `O and zero swap in digit block`() = assertEquals("WPCAB1024", plate("WP CAB-1O24"))
    @Test fun `zero in letter block becomes O`() = assertEquals("WPCOB1234", plate("WP C0B-1234"))
    @Test fun `I and one swap in digits`() = assertEquals("WPCAB1114", plate("WP CAB-1I14"))
    @Test fun `B and eight swap in digits`() = assertEquals("WPCAB8888", plate("WP CAB-B888"))
    @Test fun `eight in letter block becomes B`() = assertEquals("WPCBB1234", plate("WP C8B-1234"))
    @Test fun `no province three letters`() = assertEquals("CAB1234", plate("CAB-1234"))
    @Test fun `older numeric format`() = assertEquals("2511234", plate("251-1234"))
    @Test fun `older numeric format with letter swaps`() = assertEquals("2511234", plate("25I-I234"))
    @Test fun `sinhala prefix is ignored`() = assertEquals("WPCAB1234", plate("ශ්‍රී WP CAB-1234"))
    @Test fun `noise lines are ignored`() = assertEquals("SPKA1234", plate("SRI LANKA\nSP KA-1234\nfoo"))
    @Test fun `garbage yields null`() = assertNull(plate("HELLO"))
    @Test fun `too short yields null`() = assertNull(plate("AB12"))

    @Test fun `display formats`() {
        assertEquals("WP CAB-1234", PlateNormalizer.display("WPCAB1234"))
        assertEquals("251-1234", PlateNormalizer.display("2511234"))
        assertEquals("CAB-1234", PlateNormalizer.display("CAB1234"))
    }
}
