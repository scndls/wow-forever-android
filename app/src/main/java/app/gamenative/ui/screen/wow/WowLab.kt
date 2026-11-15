package app.gamenative.ui.screen.wow

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import app.gamenative.ui.widget.SystemMetricsSources
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.zip.ZipFile
import kotlin.concurrent.fixedRateTimer
import kotlin.math.abs
import org.json.JSONObject
import timber.log.Timber

/**
 * Test menu ("lab") for trying graphics drivers, Proton and DXVK builds and driver flags on a device
 * without rebuilding the app, and for logging frame rate and temperatures so setups can be compared.
 *
 * New components are dropped into `<game folder>/lab/drivers` (AdrenoTools .zip with meta.json at the
 * root), `lab/proton` and `lab/dxvk` (.wcp) and install on the next Play. With every graphics choice on
 * its default, launches use the stock components; only the audio fix is on by default.
 */
internal object WowLab {
    const val DIR = "lab"
    private const val PREFS = "wow_forever_lab"
    private const val PREFS_IMPORTED = "wow_forever_lab_imported"
    private const val BENCH_INTERVAL_MS = 2000L

    enum class Gmem(val label: String) {
        AUTO("Auto (device default)"),
        ON("On: tiled GMEM rendering"),
        OFF("Off: sysmem rendering"),
    }

    data class Settings(
        val driver: String? = null,
        val gmem: Gmem = Gmem.AUTO,
        val proton: String? = null,
        val dxvk: String? = null,
        val extraEnv: String = "",
        val bench: Boolean = false,
        val hqAudio: Boolean = true,
    )

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            driver = p.getString("driver", null),
            gmem = runCatching { Gmem.valueOf(p.getString("gmem", null) ?: "") }.getOrDefault(Gmem.AUTO),
            proton = p.getString("proton", null),
            dxvk = p.getString("dxvk", null),
            extraEnv = p.getString("extraEnv", "") ?: "",
            bench = p.getBoolean("bench", false),
            hqAudio = p.getBoolean("hqAudio", true),
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("driver", s.driver)
            .putString("gmem", s.gmem.name)
            .putString("proton", s.proton)
            .putString("dxvk", s.dxvk)
            .putString("extraEnv", s.extraEnv)
            .putBoolean("bench", s.bench)
            .putBoolean("hqAudio", s.hqAudio)
            .apply()
    }

    // --- Components ---------------------------------------------------------------------------------

    /** Installed AdrenoTools drivers, plus "System": the phone's own Vulkan driver. */
    fun drivers(context: Context): List<String> =
        File(context.filesDir, "contents/adrenotools").listFiles()
            ?.filter { File(it, "meta.json").isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty() + SYSTEM_DRIVER

    const val SYSTEM_DRIVER = "System"

    /** Container identifiers ("<versionName>-<versionCode>") of the installed Proton builds. */
    fun protons(context: Context): List<String> = contentIds(context, ContentProfile.ContentType.CONTENT_TYPE_PROTON)

    /** Identifiers ("<versionName>-<versionCode>") of the installed DXVK builds. */
    fun dxvks(context: Context): List<String> = contentIds(context, ContentProfile.ContentType.CONTENT_TYPE_DXVK)

    private fun contentIds(context: Context, type: ContentProfile.ContentType): List<String> =
        ContentsManager(context).run {
            syncContents()
            getProfiles(type).orEmpty().map { "${it.verName}-${it.verCode}" }.sorted()
        }

    /** Installs components from the lab folders that are new or changed since they were last installed. */
    fun importComponents(context: Context, gameRoot: File, onStatus: (String) -> Unit) {
        val lab = File(gameRoot, DIR)
        val driverDir = File(lab, "drivers")
        val wcpDirs = listOf(File(lab, "proton"), File(lab, "dxvk"))
        (wcpDirs + driverDir + File(lab, "bench")).forEach { it.mkdirs() }

        val seen = context.getSharedPreferences(PREFS_IMPORTED, Context.MODE_PRIVATE)
        fun stamp(f: File) = "${f.length()}:${f.lastModified()}"
        fun isNew(f: File) = seen.getString(f.absolutePath, null) != stamp(f)
        fun markSeen(f: File) = seen.edit().putString(f.absolutePath, stamp(f)).apply()

        driverDir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }.orEmpty().filter(::isNew).forEach { zip ->
            onStatus("Test menu: installing driver ${zip.name}...")
            if (installDriverZip(context, zip)) markSeen(zip)
        }
        val contents = ContentsManager(context)
        wcpDirs.flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".wcp", ignoreCase = true) }.orEmpty().toList() }
            .filter(::isNew)
            .forEach { wcp ->
                onStatus("Test menu: installing ${wcp.name} (Proton can take a minute)...")
                if (installWcp(contents, wcp)) markSeen(wcp)
            }
        contents.syncContents()
    }

    private fun installDriverZip(context: Context, zip: File): Boolean = runCatching {
        ZipFile(zip).use { z ->
            val meta = z.getEntry("meta.json") ?: error("meta.json must be at the root of the zip")
            val metaName = JSONObject(z.getInputStream(meta).bufferedReader().use { it.readText() }).getString("name")
            // The folder name becomes the driver id inside space-separated env strings, so keep it to safe characters.
            val name = metaName.trim().replace(Regex("[^A-Za-z0-9._+-]+"), "-").trim('-', '.')
            require(name.isNotEmpty()) { "bad driver name '$metaName'" }
            val dir = File(context.filesDir, "contents/adrenotools/$name")
            dir.deleteRecursively()
            dir.mkdirs()
            val root = dir.canonicalPath + File.separator
            z.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val out = File(dir, entry.name).canonicalFile
                require(out.path.startsWith(root)) { "zip entry escapes the driver folder: ${entry.name}" }
                out.parentFile?.mkdirs()
                z.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        true
    }.getOrElse {
        Timber.e(it, "Test menu: driver ${zip.name} failed to install")
        false
    }

    /** Installs a .wcp through GameNative's content importer, replacing an installed build of the same name. */
    private fun installWcp(contents: ContentsManager, wcp: File): Boolean {
        var ok = false
        val finish = object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                Timber.e(e, "Test menu: ${wcp.name} failed to install: $reason")
            }

            override fun onSucceed(profile: ContentProfile) {
                ok = true
            }
        }
        contents.extraContentFile(Uri.parse(wcp.absolutePath), object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) = finish.onFailed(reason, e)

            override fun onSucceed(profile: ContentProfile) {
                contents.syncContents()
                contents.getProfiles(profile.type).orEmpty()
                    .firstOrNull { it.verName == profile.verName }
                    ?.let { contents.removeContent(it) }
                contents.finishInstallContent(profile, finish)
            }
        })
        return ok
    }

    // --- Container settings -------------------------------------------------------------------------

    /** Applies the GMEM choice to a TU_DEBUG flag list ("sysmem" forces Turnip off tiled GMEM rendering). */
    fun tuDebug(base: String, gmem: Gmem): String {
        val flags = base.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        when (gmem) {
            Gmem.AUTO -> Unit
            Gmem.ON -> flags.remove("sysmem")
            Gmem.OFF -> if ("sysmem" !in flags) flags += "sysmem"
        }
        return flags.joinToString(",").ifEmpty { "noconform" }
    }

    /** Each Proton build gets its own container, so a prefix is never shared between Wine versions. */
    fun containerId(base: String, s: Settings): String =
        s.proton?.let { base + "_" + it.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_') } ?: base

    // --- Bench logging ------------------------------------------------------------------------------

    private class PendingBench(val dir: File, val header: List<String>)

    @Volatile private var pending: PendingBench? = null
    private var timer: Timer? = null

    /** Called at launch: the next game session logs to [dir] (or nothing when [dir] is null). */
    fun armBench(dir: File?, header: List<String>) {
        pending = dir?.let { PendingBench(it, header) }
    }

    /** Called once the game's frame counter exists. Samples every two seconds until [stopBench]. */
    fun startBench(context: Context, fps: () -> Float, frameTimeP99Ms: () -> Int) {
        val bench = pending ?: return
        pending = null
        stopBench()
        val app = context.applicationContext
        val file = runCatching {
            bench.dir.mkdirs()
            File(bench.dir, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv").also { f ->
                FileWriter(f).use { w ->
                    bench.header.forEach { w.write("# $it\n") }
                    w.write("t_s,fps,frame_p99_ms,cpu_c,gpu_c,battery_c,power_w,thermal_status,headroom\n")
                }
            }
        }.getOrElse {
            Timber.e(it, "Test menu: can't create a bench log in ${bench.dir}")
            return
        }
        val start = System.currentTimeMillis()
        timer = fixedRateTimer("wow-lab-bench", daemon = true, initialDelay = BENCH_INTERVAL_MS, period = BENCH_INTERVAL_MS) {
            runCatching {
                val row = listOf(
                    "%.0f".format(Locale.US, (System.currentTimeMillis() - start) / 1000.0),
                    "%.1f".format(Locale.US, fps()),
                    frameTimeP99Ms().toString(),
                    SystemMetricsSources.readTemperatureC(SystemMetricsSources.cpuTempPaths())?.toString().orEmpty(),
                    SystemMetricsSources.readTemperatureC(SystemMetricsSources.gpuTempPaths())?.toString().orEmpty(),
                ) + batteryAndThermal(app)
                FileWriter(file, true).use { it.write(row.joinToString(",") + "\n") }
            }.onFailure { Timber.w(it, "Test menu: bench sample failed") }
        }
    }

    fun stopBench() {
        timer?.cancel()
        timer = null
    }

    private fun batteryAndThermal(context: Context): List<String> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val millivolts = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val microamps = (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
        val watts = abs(microamps) * millivolts / 1e9
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus?.toString().orEmpty() else ""
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            power?.getThermalHeadroom(10)?.takeIf { !it.isNaN() }?.let { "%.2f".format(Locale.US, it) }.orEmpty()
        } else {
            ""
        }
        return listOf(tempC?.let { "%.1f".format(Locale.US, it) }.orEmpty(), "%.2f".format(Locale.US, watts), status, headroom)
    }
}
