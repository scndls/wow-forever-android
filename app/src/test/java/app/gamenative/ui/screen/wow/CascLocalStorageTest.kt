package app.gamenative.ui.screen.wow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reference values come from a live WoW Forever install (data.000 headers, a bucket-0 .idx file) and lookup3. */
class CascLocalStorageTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun hashlittle2MatchesLookup3() {
        assertEquals(0xdeadbeef.toInt() to 0xdeadbeef.toInt(), Jenkins.hashlittle2(ByteArray(0), 0, 0, 0, 0))
        val a = "a".toByteArray()
        assertEquals(0x58d68708 to 0x582647ac, Jenkins.hashlittle2(a, 0, 1, 0, 0))
        val twelve = "abcdefghijkl".toByteArray()
        assertEquals(0x4012f87b to 0x75b50ec0, Jenkins.hashlittle2(twelve, 0, 12, 0, 0))
        val thirteen = "abcdefghijklm".toByteArray()
        assertEquals(0x928128f9.toInt() to 0x0f04ab68, Jenkins.hashlittle2(thirteen, 0, 13, 0, 0))
        val ramp = ByteArray(37) { it.toByte() }
        assertEquals(0x12926a31 to 0xb28b69dc.toInt(), Jenkins.hashlittle2(ramp, 0, 37, 0, 0))
    }

    @Test
    fun idxHeaderAndChainedEntriesHash() {
        val header = hex("070000000405091e000000c0ff000000")
        assertEquals(0x1bc0046b, Jenkins.hashlittle2(header, 0, 16, 0, 0).first)
        val entries = hex(
            "0001002cfc99e6740800400000001e000000" + "000180d18404adcac000f401d835b91b0000" + "00038294810beadecd029e230422347a0000",
        )
        var pc = 0
        var pb = 0
        for (pos in 0 until 54 step 18) {
            val r = Jenkins.hashlittle2(entries, pos, 18, pc, pb)
            pc = r.first
            pb = r.second
        }
        assertEquals(0x13736b9c, pc)
    }

    @Test
    fun dataHeaderChecksumsMatchTheClient() {
        val first = hex("ddad41502c6b660874e699fc2c0000011e00000001001a169139d1660367")
        assertEquals(0x670366d1, CascLocalStorage.checksumB(first, 0, 0))
        val second = hex("ddad41502c6b660874e699fc2c00000e1e000000010033744412cc950415")
        assertEquals(0x150495cc, CascLocalStorage.checksumB(second, 0, 30))
    }

    @Test
    fun dataHeaderRoundTrip() {
        // The real header above belongs to a 0-byte flagged cross-link entry; rebuild one with the same key and offset.
        val reversedKey = hex("ddad41502c6b660874e699fc2c000001")
        val ekey = reversedKey.reversedArray()
        val built = CascLocalStorage.dataHeader(ekey, 0, 0, 0)
        assertArrayEquals(reversedKey, built.copyOfRange(0, 16))
        assertEquals(30, built[16].toInt())
        // Flags differ (the original is a cross-link with flags 1), so only check the checksums are self-consistent.
        val expectedA = Jenkins.hashlittle2(built, 0, 0x16, 0x3D6BE971, 0).first
        val a = (built[0x16].toInt() and 0xFF) or ((built[0x17].toInt() and 0xFF) shl 8) or
            ((built[0x18].toInt() and 0xFF) shl 16) or ((built[0x19].toInt() and 0xFF) shl 24)
        assertEquals(expectedA, a)
    }

    @Test
    fun bucketOfKey() {
        // This key sits in the bucket-0 file only as a cross-link entry: its own bucket is 15, and (15 + 1) % 16 = 0.
        assertEquals(15, CascLocalStorage.bucketOf(hex("0001002cfc99e67408")))
        assertEquals(0, CascLocalStorage.bucketOf(hex("000000000000000000")))
    }
}
