package app.gamenative.ui.screen.wow

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * Writes files into the game's local CASC storage (`Data/data`: data.### files, 16 bucket .idx files and shmem) the
 * same way the client does, so content downloaded by the launcher is found by the game as if it had streamed it.
 *
 * Layouts were checked against a live WoW Forever install: .idx v7 (header hash over 0x10 bytes, entries hash chained
 * per 18-byte entry), data headers (reversed EKey, size, flags, ChecksumA/B) and the shmem free-space block (a 0x20-byte
 * header at the offset its directory entry gives, then the two 5450-byte tables). New entries go into the free space shmem lists (the tail of
 * the last data file, then whole future data files), which is where the client would put them.
 *
 * Not thread-safe: callers serialise [append] and [commit]. The game must not be running.
 */
internal class CascLocalStorage private constructor(private val dir: File) {
    private class Bucket(var version: Int, val header: ByteArray, var entries: ByteArray, var fileSize: Int) {
        val added = ArrayList<ByteArray>()
    }

    private class Free(var count: Long, val dataNumber: Int, var offset: Long)

    private val shmem: ByteArray = File(dir, "shmem").readBytes()
    private val freeBlockOffset: Int
    private val versionsOffset: Int
    private val buckets: Array<Bucket>
    private val free = ArrayList<Free>()
    private val allocatedFiles: Int
    private val initialUsed: Long
    private val initialFreeInFiles: Long
    private var consumed = 0L
    private var cursor = 0
    private var maxDataNumber = -1
    private val openFiles = HashMap<Int, RandomAccessFile>()
    private var backedUp = false

    /** First 8 bytes of every EKey in the storage, sorted (a 64-bit prefix is unique enough to tell what's present). */
    val presentKeys: LongArray

    init {
        val sm = ByteBuffer.wrap(shmem).order(ByteOrder.LITTLE_ENDIAN)
        val nextBlock = sm.getInt(4)
        val directoryCount = (nextBlock - 264 - BUCKETS * 4) / 8
        check(directoryCount >= 1) { "Unexpected shmem layout (next block $nextBlock)" }
        freeBlockOffset = sm.getInt(264 + 4) // directory entry: (size, offset) of the free-space block
        versionsOffset = 264 + directoryCount * 8
        check(sm.getInt(freeBlockOffset) == 1) { "Unexpected shmem free-space block type" }
        val versions = IntArray(BUCKETS) { sm.getInt(versionsOffset + it * 4) }

        buckets = Array(BUCKETS) { b ->
            val file = idxFile(b, versions[b])
            val data = file.readBytes()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            check(buf.getShort(8).toInt() == 7 && data[10].toInt() == b) { "Unexpected index header in ${file.name}" }
            check(data[12].toInt() == 4 && data[13].toInt() == 5 && data[14].toInt() == 9 && data[15].toInt() == 30) { "Unexpected index spec in ${file.name}" }
            val entriesSize = buf.getInt(0x20)
            Bucket(versions[b], data.copyOfRange(8, 0x18), data.copyOfRange(0x28, 0x28 + entriesSize), data.size)
        }

        val fb = ByteBuffer.wrap(shmem).order(ByteOrder.LITTLE_ENDIAN)
        val freeCount = fb.getInt(freeBlockOffset + 4)
        val used = fb.getLong(freeBlockOffset + 0x10)
        val freeInFiles = fb.getLong(freeBlockOffset + 0x18)
        allocatedFiles = ((used + freeInFiles) / GIB).toInt()
        initialUsed = used
        initialFreeInFiles = freeInFiles
        val counts = freeBlockOffset + 0x20
        val offsets = counts + FREE_PART_SIZE
        for (i in 0 until freeCount) {
            val count = read40(shmem, counts + i * 5)
            val packed = read40(shmem, offsets + i * 5)
            free += Free(count, (packed ushr 30).toInt(), packed and OFFSET_MASK)
        }

        var n = 0
        buckets.forEach { n += it.entries.size / ENTRY_SIZE }
        presentKeys = LongArray(n)
        var i = 0
        buckets.forEach { bucket ->
            for (pos in 0 until bucket.entries.size step ENTRY_SIZE) presentKeys[i++] = prefix64(bucket.entries, pos)
        }
        presentKeys.sort()
    }

    fun isPresent(ekey: ByteArray): Boolean = presentKeys.binarySearch(prefix64(ekey, 0)) >= 0

    /** Bytes still free in the files the free-space table lists. */
    fun freeBytes(): Long = free.sumOf { it.count }

    /** Writes one BLTE-encoded file under its 16-byte [ekey]. Takes effect for the game after [commit]. */
    fun append(ekey: ByteArray, blob: ByteArray) {
        if (!backedUp) backup()
        val need = HEADER_SIZE + blob.size.toLong()
        while (cursor < free.size && free[cursor].count < need) cursor++
        if (cursor >= free.size) throw IOException("The game storage has no free space left for ${need} bytes")
        val slot = free[cursor]
        val header = dataHeader(ekey, blob.size, slot.dataNumber, slot.offset)
        val file = openFiles.getOrPut(slot.dataNumber) { RandomAccessFile(File(dir, "data.%03d".format(slot.dataNumber)), "rw") }
        file.seek(slot.offset)
        file.write(header)
        file.write(blob)

        val entry = ByteArray(ENTRY_SIZE)
        System.arraycopy(ekey, 0, entry, 0, 9)
        val packed = (slot.dataNumber.toLong() shl 30) or slot.offset
        for (k in 0 until 5) entry[9 + k] = (packed ushr (8 * (4 - k))).toByte()
        ByteBuffer.wrap(entry, 14, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(need.toInt())
        buckets[bucketOf(ekey)].added += entry

        slot.offset += need
        slot.count -= need
        consumed += need
        if (slot.dataNumber > maxDataNumber) maxDataNumber = slot.dataNumber
    }

    /**
     * Makes everything appended so far visible to the game: data is flushed, every changed bucket gets a new .idx
     * version, then shmem (versions and free space) is replaced and the old .idx files are deleted. If this is cut
     * short before shmem is replaced, the game keeps using the previous state and the new data is simply unused.
     */
    fun commit() {
        if (buckets.none { it.added.isNotEmpty() }) return
        openFiles.values.forEach { it.fd.sync() }
        val oldFiles = ArrayList<File>()
        for ((b, bucket) in buckets.withIndex()) {
            if (bucket.added.isEmpty()) continue
            val merged = (entryList(bucket.entries) + bucket.added).sortedWith(KEY_ORDER)
            val entries = ByteArray(merged.size * ENTRY_SIZE)
            merged.forEachIndexed { i, e -> System.arraycopy(e, 0, entries, i * ENTRY_SIZE, ENTRY_SIZE) }
            val newVersion = bucket.version + 1
            val size = maxOf(bucket.fileSize, alignUp(0x28 + entries.size, 0x10000))
            val out = ByteArray(size)
            val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0, 0x10)
            System.arraycopy(bucket.header, 0, out, 8, 0x10)
            buf.putInt(4, Jenkins.hashlittle2(out, 8, 0x10, 0, 0).first)
            buf.putInt(0x20, entries.size)
            var pc = 0
            var pb = 0
            for (pos in entries.indices step ENTRY_SIZE) {
                val r = Jenkins.hashlittle2(entries, pos, ENTRY_SIZE, pc, pb)
                pc = r.first
                pb = r.second
            }
            buf.putInt(0x24, pc)
            System.arraycopy(entries, 0, out, 0x28, entries.size)
            writeSynced(idxFile(b, newVersion), out)
            oldFiles += idxFile(b, bucket.version)
            bucket.version = newVersion
            bucket.entries = entries
            bucket.fileSize = size
            bucket.added.clear()
        }

        val sm = ByteBuffer.wrap(shmem).order(ByteOrder.LITTLE_ENDIAN)
        buckets.forEachIndexed { b, bucket -> sm.putInt(versionsOffset + b * 4, bucket.version) }
        free.removeAll { it.count <= 0 }
        cursor = cursor.coerceAtMost(free.size)
        val counts = freeBlockOffset + 0x20
        val offsets = counts + FREE_PART_SIZE
        java.util.Arrays.fill(shmem, counts, offsets + FREE_PART_SIZE, 0)
        free.forEachIndexed { i, f ->
            write40(shmem, counts + i * 5, f.count)
            write40(shmem, offsets + i * 5, (f.dataNumber.toLong() shl 30) or f.offset)
        }
        // The header totals cover the allocated data files: bytes used, and bytes free inside them (which includes
        // space the table doesn't list). Each newly started data file adds 1 GiB of capacity; written bytes move
        // from free to used. Updating by delta keeps the client's own accounting intact.
        val newFiles = (maxDataNumber - (allocatedFiles - 1)).coerceAtLeast(0)
        sm.putInt(freeBlockOffset + 4, free.size)
        sm.putLong(freeBlockOffset + 0x10, initialUsed + consumed)
        sm.putLong(freeBlockOffset + 0x18, initialFreeInFiles + newFiles * GIB - consumed)
        val tmp = File(dir, "shmem.tmp")
        writeSynced(tmp, shmem)
        if (!tmp.renameTo(File(dir, "shmem"))) throw IOException("Couldn't replace shmem")
        oldFiles.forEach { it.delete() }
    }

    fun close() {
        openFiles.values.forEach { runCatching { it.close() } }
        openFiles.clear()
    }

    /** Copies shmem and the .idx files to Data/data-backup-<time> before the first write. */
    private fun backup() {
        val target = File(dir.parentFile, "data-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()))
        target.mkdirs()
        File(dir, "shmem").copyTo(File(target, "shmem"))
        buckets.forEachIndexed { b, bucket -> idxFile(b, bucket.version).let { it.copyTo(File(target, it.name)) } }
        Timber.i("Full download: storage index backed up to %s", target)
        backedUp = true
    }

    private fun idxFile(bucket: Int, version: Int) = File(dir, "%02x%08x.idx".format(bucket, version))

    private fun entryList(entries: ByteArray): List<ByteArray> =
        (0 until entries.size / ENTRY_SIZE).map { entries.copyOfRange(it * ENTRY_SIZE, (it + 1) * ENTRY_SIZE) }

    companion object {
        const val HEADER_SIZE = 30
        private const val BUCKETS = 16
        private const val ENTRY_SIZE = 18
        private const val FREE_PART_SIZE = 5450
        private const val GIB = 1L shl 30
        private const val OFFSET_MASK = (1L shl 30) - 1

        fun open(dataDir: File): CascLocalStorage = CascLocalStorage(dataDir)

        private val KEY_ORDER = Comparator<ByteArray> { a, b ->
            for (i in 0 until 9) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return@Comparator d
            }
            0
        }

        fun bucketOf(key: ByteArray): Int {
            var i = 0
            for (k in 0 until 9) i = i xor (key[k].toInt() and 0xFF)
            return (i and 0xF) xor (i ushr 4)
        }

        fun prefix64(bytes: ByteArray, offset: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (bytes[offset + k].toLong() and 0xFF)
            return v
        }

        /** The 30-byte header in front of each file in data.###. */
        fun dataHeader(ekey: ByteArray, blobSize: Int, dataNumber: Int, offset: Long): ByteArray {
            val h = ByteArray(HEADER_SIZE)
            for (k in 0 until 16) h[k] = ekey[15 - k]
            val buf = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0x10, HEADER_SIZE + blobSize)
            buf.putInt(0x16, Jenkins.hashlittle2(h, 0, 0x16, 0x3D6BE971, 0).first)
            buf.putInt(0x1A, checksumB(h, dataNumber, offset))
            return h
        }

        private val TABLE = intArrayOf(
            0x049396b8, 0x72a82a9b, 0xee626cca.toInt(), 0x9917754f.toInt(), 0x15de40b1, 0xf5a8a9b6.toInt(), 0x421eac7e, 0xa9d55c9a.toInt(),
            0x317fd40c, 0x04faf80d, 0x3d6be971, 0x52933cfd, 0x27f64b7d, 0xc6f5c11b.toInt(), 0xd5757e3a.toInt(), 0x6c388745,
        )

        fun checksumB(header: ByteArray, dataNumber: Int, offset: Long): Int {
            val off = ((offset and OFFSET_MASK) or ((dataNumber.toLong() and 3) shl 30)).toInt()
            val encoded = TABLE[(off + 0x1E) and 0xF] xor (off + 0x1E)
            val hashed = ByteArray(4)
            for (i in 0 until 0x1A) hashed[(i + off) and 3] = (hashed[(i + off) and 3].toInt() xor header[i].toInt()).toByte()
            var out = 0
            for (j in 0 until 4) {
                val i = j + 0x1A + off
                val byte = (hashed[i and 3].toInt() xor (encoded ushr (8 * (i and 3)))) and 0xFF
                out = out or (byte shl (8 * j))
            }
            return out
        }

        private fun read40(b: ByteArray, at: Int): Long {
            var v = 0L
            for (k in 0 until 5) v = (v shl 8) or (b[at + k].toLong() and 0xFF)
            return v
        }

        private fun write40(b: ByteArray, at: Int, value: Long) {
            for (k in 0 until 5) b[at + k] = (value ushr (8 * (4 - k))).toByte()
        }

        private fun alignUp(v: Int, a: Int) = (v + a - 1) / a * a

        private fun writeSynced(file: File, bytes: ByteArray) {
            RandomAccessFile(file, "rw").use {
                it.setLength(0)
                it.write(bytes)
                it.fd.sync()
            }
        }
    }
}

/** Bob Jenkins' lookup3 hashlittle2, as CASC uses it. Returns (pc, pb). */
internal object Jenkins {
    fun hashlittle2(data: ByteArray, offset: Int, length: Int, pcIn: Int, pbIn: Int): Pair<Int, Int> {
        var a = 0xdeadbeef.toInt() + length + pcIn
        var b = a
        var c = a + pbIn
        var i = offset
        var remaining = length
        while (remaining > 12) {
            a += le32(data, i)
            b += le32(data, i + 4)
            c += le32(data, i + 8)
            a -= c; a = a xor Integer.rotateLeft(c, 4); c += b
            b -= a; b = b xor Integer.rotateLeft(a, 6); a += c
            c -= b; c = c xor Integer.rotateLeft(b, 8); b += a
            a -= c; a = a xor Integer.rotateLeft(c, 16); c += b
            b -= a; b = b xor Integer.rotateLeft(a, 19); a += c
            c -= b; c = c xor Integer.rotateLeft(b, 4); b += a
            remaining -= 12
            i += 12
        }
        if (remaining == 0) return c to b
        val tail = ByteArray(12)
        System.arraycopy(data, i, tail, 0, remaining)
        a += le32(tail, 0)
        b += le32(tail, 4)
        c += le32(tail, 8)
        c = c xor b; c -= Integer.rotateLeft(b, 14)
        a = a xor c; a -= Integer.rotateLeft(c, 11)
        b = b xor a; b -= Integer.rotateLeft(a, 25)
        c = c xor b; c -= Integer.rotateLeft(b, 16)
        a = a xor c; a -= Integer.rotateLeft(c, 4)
        b = b xor a; b -= Integer.rotateLeft(a, 14)
        c = c xor b; c -= Integer.rotateLeft(b, 24)
        return c to b
    }

    private fun le32(d: ByteArray, i: Int): Int =
        (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8) or ((d[i + 2].toInt() and 0xFF) shl 16) or ((d[i + 3].toInt() and 0xFF) shl 24)
}
