package app.gamenative.ui.screen.wow

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * Downloads every game file the client would otherwise stream while playing, like the Battle.net app's full install.
 *
 * The build's download manifest lists all files with their install tags. Files matching the install's tags (platform,
 * architecture, region, locale from .build.info) are kept, the optional HighRes and Alternate content (which the phone
 * never loads) is skipped, and whatever the local storage already holds is left out. The rest is located in the CDN
 * archives through the archive indexes in Data/indices, fetched in merged range requests, checked against its EKey and
 * written into Data/data with [CascLocalStorage], committed every [COMMIT_EVERY] bytes so progress survives interruption.
 */
internal object WowFullDownload {
    const val TEST_BATCH_BYTES = 300L shl 20
    private const val THREADS = 6
    private const val MAX_RANGE = 8L shl 20
    private const val MAX_GAP = 256L shl 10
    private const val COMMIT_EVERY = 256L shl 20
    private val OPTIONAL_CONTENT = setOf("HighRes", "Alternate")

    /** Files still missing, in download-priority order. */
    class Plan(
        internal val build: WowClientDownloader.InstalledBuild,
        internal val ekeys: ByteArray,
        internal val sizes: LongArray,
        val files: Int,
        val missingBytes: Long,
        val presentFiles: Int,
    )

    class Progress(val phase: String, val doneBytes: Long = 0, val totalBytes: Long = 0)

    class Result(val bytes: Long, val files: Int, val failedFiles: Int, val remainingBytes: Long, val cancelled: Boolean)

    fun plan(gameRoot: File, onStatus: (String) -> Unit): Plan {
        onStatus("Reading the game's file list...")
        val build = WowClientDownloader.installedBuild(gameRoot)
        val manifest = WowClientDownloader.decodeBlte(build.cdn.require("data", build.buildConfig.getValue("download")[1]))
        onStatus("Checking which files are already on the phone...")
        val storage = CascLocalStorage.open(File(gameRoot, "Data/data"))
        return parseManifest(manifest, build, storage)
    }

    private fun parseManifest(d: ByteArray, build: WowClientDownloader.InstalledBuild, storage: CascLocalStorage): Plan {
        check(d[0] == 'D'.code.toByte() && d[1] == 'L'.code.toByte()) { "Unexpected download manifest" }
        val buf = ByteBuffer.wrap(d)
        val version = d[2].toInt()
        val ekeySize = d[3].toInt()
        val hasChecksum = d[4].toInt() != 0
        val count = buf.getInt(5)
        val tagCount = buf.getShort(9).toInt() and 0xFFFF
        var pos = 11
        var flagSize = 0
        if (version >= 2) flagSize = d[pos++].toInt()
        if (version >= 3) pos += 4
        val entriesStart = pos
        val entrySize = ekeySize + 5 + 1 + (if (hasChecksum) 4 else 0) + flagSize
        pos = entriesStart + count * entrySize
        val maskSize = (count + 7) / 8

        class Tag(val name: String, val type: Int, val maskOffset: Int)
        val tags = ArrayList<Tag>(tagCount)
        repeat(tagCount) {
            var end = pos
            while (d[end].toInt() != 0) end++
            val name = String(d, pos, end - pos)
            val type = ((d[end + 1].toInt() and 0xFF) shl 8) or (d[end + 2].toInt() and 0xFF)
            tags += Tag(name, type, end + 3)
            pos = end + 3 + maskSize
        }
        fun has(tag: Tag, i: Int) = d[tag.maskOffset + i / 8].toInt() and (0x80 ushr (i % 8)) != 0

        // Platform, architecture, locale and region (types 1-4) must match one of the install's tags of that type.
        val required = tags.filter { it.type in 1..4 && it.name in build.tags }.groupBy { it.type }.values
        val optional = tags.filter { it.name in OPTIONAL_CONTENT }

        val keys = ByteArrayOutputStream()
        var sizes = LongArray(1 shl 16)
        var prios = ByteArray(1 shl 16)
        var n = 0
        var present = 0
        var missingBytes = 0L
        for (i in 0 until count) {
            if (required.any { group -> group.none { has(it, i) } } || optional.any { has(it, i) }) continue
            val at = entriesStart + i * entrySize
            val ekey = d.copyOfRange(at, at + 16)
            if (storage.isPresent(ekey)) {
                present++
                continue
            }
            if (n == sizes.size) {
                sizes = sizes.copyOf(n * 2)
                prios = prios.copyOf(n * 2)
            }
            var size = 0L
            for (k in 0 until 5) size = (size shl 8) or (d[at + ekeySize + k].toLong() and 0xFF)
            keys.write(ekey)
            sizes[n] = size
            prios[n] = d[at + ekeySize + 5]
            missingBytes += size
            n++
        }
        // Most important first, as the Battle.net app would: lower priority values are needed sooner.
        val order = (0 until n).sortedBy { prios[it].toInt() }
        val raw = keys.toByteArray()
        val ekeys = ByteArray(n * 16)
        val sorted = LongArray(n)
        order.forEachIndexed { j, i ->
            System.arraycopy(raw, i * 16, ekeys, j * 16, 16)
            sorted[j] = sizes[i]
        }
        Timber.i("Full download plan: %d files (%d bytes) missing, %d already present", n, missingBytes, present)
        return Plan(build, ekeys, sorted, n, missingBytes, present)
    }

    fun download(gameRoot: File, plan: Plan, limitBytes: Long?, isCancelled: () -> Boolean, onProgress: (Progress) -> Unit): Result {
        // Files of the batch: the plan's prefix up to the limit.
        var m = 0
        var batchBytes = 0L
        while (m < plan.files && (limitBytes == null || batchBytes + plan.sizes[m] <= limitBytes || m == 0)) {
            batchBytes += plan.sizes[m]
            m++
        }
        val ekey = { i: Int -> plan.ekeys.copyOfRange(i * 16, i * 16 + 16) }

        // Locate every file in the CDN archives.
        val archives = plan.build.cdnConfig["archives"].orEmpty()
        val prefixes = LongArray(m) { CascLocalStorage.prefix64(plan.ekeys, it * 16) }
        val byPrefix = (0 until m).sortedBy { prefixes[it] }.toIntArray()
        val sortedPrefixes = LongArray(m) { prefixes[byPrefix[it]] }
        val archiveOf = IntArray(m) { -1 }
        val offsetOf = LongArray(m)
        var located = 0
        for ((a, name) in archives.withIndex()) {
            if (located == m || isCancelled()) break
            if (a % 50 == 0) onProgress(Progress("Finding files in Blizzard's archives (${a * 100 / archives.size.coerceAtLeast(1)}%)..."))
            val local = File(gameRoot, "Data/indices/$name.index")
            val index = if (local.isFile) local.readBytes() else plan.build.cdn.fetch("data", "$name.index") ?: continue
            val ib = ByteBuffer.wrap(index)
            for (block in 0 until index.size / 4096) {
                var pos = block * 4096
                val end = pos + 4096 - 24
                while (pos <= end) {
                    val prefix = CascLocalStorage.prefix64(index, pos)
                    if (prefix == 0L) break
                    val hit = sortedPrefixes.binarySearch(prefix)
                    if (hit >= 0) {
                        // Neighbouring entries can share a 64-bit prefix only in theory; check the full key.
                        var h = hit
                        while (h > 0 && sortedPrefixes[h - 1] == prefix) h--
                        while (h < m && sortedPrefixes[h] == prefix) {
                            val i = byPrefix[h]
                            if (archiveOf[i] < 0 && (0 until 16).all { index[pos + it] == plan.ekeys[i * 16 + it] }) {
                                archiveOf[i] = a
                                offsetOf[i] = ib.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                                located++
                            }
                            h++
                        }
                    }
                    pos += 24
                }
            }
        }
        Timber.i("Full download: located %d of %d files in archives", located, m)

        // Merge neighbouring files of one archive into range requests; files outside the archives are fetched loose.
        class Job(val archive: Int, val start: Long, val end: Long, val items: IntArray)
        val jobs = ArrayList<Job>()
        val inArchives = (0 until m).filter { archiveOf[it] >= 0 }.sortedWith(compareBy({ archiveOf[it] }, { offsetOf[it] }))
        var group = ArrayList<Int>()
        fun flush() {
            if (group.isEmpty()) return
            val first = group.first()
            val last = group.last()
            jobs += Job(archiveOf[first], offsetOf[first], offsetOf[last] + plan.sizes[last], group.toIntArray())
            group = ArrayList()
        }
        for (i in inArchives) {
            if (group.isNotEmpty()) {
                val first = group.first()
                val last = group.last()
                val gapOk = archiveOf[i] == archiveOf[last] && offsetOf[i] - (offsetOf[last] + plan.sizes[last]) in 0..MAX_GAP
                if (!gapOk || offsetOf[i] + plan.sizes[i] - offsetOf[first] > MAX_RANGE) flush()
            }
            group += i
        }
        flush()
        (0 until m).filter { archiveOf[it] < 0 }.forEach { jobs += Job(-1, 0, plan.sizes[it], intArrayOf(it)) }

        val storage = CascLocalStorage.open(File(gameRoot, "Data/data"))
        val written = AtomicLong()
        val files = AtomicInteger()
        val failed = AtomicInteger()
        var sinceCommit = 0L
        val lock = Any()
        val inFlight = Semaphore(THREADS * 2)
        val pool = Executors.newFixedThreadPool(THREADS)
        val md5 = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }
        onProgress(Progress("Downloading...", 0, batchBytes))
        try {
            for (job in jobs) {
                if (isCancelled()) break
                inFlight.acquire()
                pool.execute {
                    try {
                        if (isCancelled()) return@execute
                        val bytes = fetchWithRetry(plan, archives, job.archive, job.start, job.end, ekey(job.items[0]))
                        for (i in job.items) {
                            val start = (offsetOf[i] - job.start).toInt().takeIf { job.archive >= 0 } ?: 0
                            val blob = bytes.copyOfRange(start, start + plan.sizes[i].toInt())
                            val key = ekey(i)
                            if (!matchesEkey(md5.get()!!, blob, key)) {
                                failed.incrementAndGet()
                                Timber.w("Full download: checksum mismatch, skipped")
                                continue
                            }
                            synchronized(lock) {
                                storage.append(key, blob)
                                sinceCommit += blob.size
                                if (sinceCommit >= COMMIT_EVERY) {
                                    storage.commit()
                                    sinceCommit = 0
                                }
                            }
                            written.addAndGet(blob.size.toLong())
                            files.incrementAndGet()
                        }
                        onProgress(Progress("Downloading...", written.get(), batchBytes))
                    } catch (e: Exception) {
                        failed.addAndGet(job.items.size)
                        Timber.w(e, "Full download: a range failed")
                    } finally {
                        inFlight.release()
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.DAYS)
        } finally {
            pool.shutdownNow()
            synchronized(lock) {
                onProgress(Progress("Saving...", written.get(), batchBytes))
                storage.commit()
                storage.close()
            }
        }
        return Result(written.get(), files.get(), failed.get(), plan.missingBytes - written.get(), isCancelled())
    }

    private fun fetchWithRetry(plan: Plan, archives: List<String>, archive: Int, start: Long, end: Long, looseKey: ByteArray): ByteArray {
        var last: IOException? = null
        repeat(3) {
            try {
                return if (archive >= 0) {
                    plan.build.cdn.fetch("data", archives[archive], start until end) ?: throw IOException("Archive range missing")
                } else {
                    plan.build.cdn.fetch("data", looseKey.joinToString("") { "%02x".format(it) }) ?: throw IOException("File missing on CDN")
                }
            } catch (e: IOException) {
                last = e
                Thread.sleep(1000L * (it + 1))
            }
        }
        throw last ?: IOException("Download failed")
    }

    /** An EKey is the MD5 of the BLTE header (or of the whole blob when it has a single, header-less chunk). */
    private fun matchesEkey(md5: MessageDigest, blob: ByteArray, ekey: ByteArray): Boolean {
        if (blob.size < 8 || blob[0] != 'B'.code.toByte() || blob[1] != 'L'.code.toByte()) return false
        val headerSize = ByteBuffer.wrap(blob).getInt(4)
        md5.reset()
        md5.update(blob, 0, if (headerSize > 0) headerSize else blob.size)
        return md5.digest().contentEquals(ekey)
    }
}
