package app.gamenative.ui.screen.wow

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writes into a copy of a real storage's shmem and .idx files when CASC_SAMPLE_DIR points at them (skipped otherwise),
 * leaving the result in CASC_SAMPLE_DIR/out for an external check.
 */
class CascLocalStorageWriteTest {
    @Test
    fun appendAndCommitOnARealStorageCopy() {
        val sample = System.getenv("CASC_SAMPLE_DIR")?.let(::File)?.takeIf { File(it, "shmem").isFile } ?: return
        val out = File(sample, "out").apply { deleteRecursively(); mkdirs() }
        sample.listFiles { f -> f.name == "shmem" || f.name.endsWith(".idx") }!!.forEach { it.copyTo(File(out, it.name)) }

        val storage = CascLocalStorage.open(out)
        // A minimal single-chunk BLTE blob: header size 0, then an 'N' chunk. Its EKey is the MD5 of the whole blob.
        val blob = "BLTE".toByteArray() + byteArrayOf(0, 0, 0, 0) + "Nhello casc".toByteArray()
        val ekey = MessageDigest.getInstance("MD5").digest(blob)
        assertFalse(storage.isPresent(ekey))
        storage.append(ekey, blob)
        storage.commit()
        storage.close()

        val reopened = CascLocalStorage.open(out)
        assertTrue(reopened.isPresent(ekey))
        File(out, "ekey.txt").writeText(ekey.joinToString("") { "%02x".format(it) })
    }
}
