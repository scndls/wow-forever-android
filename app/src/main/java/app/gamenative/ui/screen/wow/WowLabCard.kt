package app.gamenative.ui.screen.wow

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val OnColor = Color(0xFF48BB78)

/**
 * The Advanced section (drivers, audio, benchmark). Every row says what the next launch will use;
 * "Standard" means the launcher's own choice for this device. Choices save immediately and apply on Play.
 */
@Composable
internal fun WowLabCard(gamePath: String, defaultDriver: String, defaultTuDebug: String, enabled: Boolean) {
    val context = LocalContext.current
    val uiPrefs = remember { context.getSharedPreferences("wow_forever_lab_ui", Context.MODE_PRIVATE) }
    var expanded by remember { mutableStateOf(uiPrefs.getBoolean("expanded", false)) }
    var settings by remember { mutableStateOf(WowLab.load(context)) }
    var drivers by remember { mutableStateOf(emptyList<String>()) }
    var protons by remember { mutableStateOf(emptyList<String>()) }
    var dxvks by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            drivers = WowLab.drivers(context)
            protons = WowLab.protons(context)
            dxvks = WowLab.dxvks(context)
        }
    }
    fun update(s: WowLab.Settings) {
        settings = s
        WowLab.save(context, s)
    }

    val gmemStandardOn = "sysmem" !in defaultTuDebug.split(',')
    val customGraphics = settings.driver != null || settings.gmem != WowLab.Gmem.AUTO ||
        settings.proton != null || settings.dxvk != null || settings.extraEnv.isNotBlank()
    val summary = listOf(
        "Graphics: " + if (customGraphics) "custom" else "standard",
        "Audio: " + if (settings.hqAudio) "high quality" else "standard",
        "FPS log: " + if (settings.bench) "on" else "off",
    ).joinToString("  ·  ")

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = {
            expanded = !expanded
            uiPrefs.edit().putBoolean("expanded", expanded).apply()
        }) {
            Column {
                Text(
                    (if (expanded) "▾ " else "▸ ") + "ADVANCED",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = WowGold,
                )
                Text(summary, fontSize = 11.sp, color = WowMuted)
            }
        }
        if (!expanded) return@Column

        SectionLabel("Graphics")
        ChoiceRow(
            title = "Graphics driver",
            value = settings.driver ?: "Standard ($defaultDriver)",
            isCustom = settings.driver != null,
            options = listOf<String?>(null) + drivers,
            optionLabel = { it ?: "Standard ($defaultDriver)" },
            enabled = enabled,
        ) { update(settings.copy(driver = it)) }
        ChoiceRow(
            title = "Fast GPU memory (GMEM)",
            value = when (settings.gmem) {
                WowLab.Gmem.AUTO -> "Standard (" + (if (gmemStandardOn) "on" else "off") + " on this phone)"
                WowLab.Gmem.ON -> "On"
                WowLab.Gmem.OFF -> "Off"
            },
            isCustom = settings.gmem != WowLab.Gmem.AUTO,
            options = WowLab.Gmem.entries,
            optionLabel = { if (it == WowLab.Gmem.AUTO) "Standard (" + (if (gmemStandardOn) "on" else "off") + ")" else it.label },
            enabled = enabled,
        ) { update(settings.copy(gmem = it)) }
        ChoiceRow(
            title = "Windows layer (Proton)",
            value = settings.proton ?: "Standard (built in)",
            isCustom = settings.proton != null,
            options = listOf<String?>(null) + protons,
            optionLabel = { it ?: "Standard (built in)" },
            enabled = enabled,
        ) { update(settings.copy(proton = it)) }
        ChoiceRow(
            title = "DirectX layer (DXVK)",
            value = settings.dxvk ?: "Standard (built in)",
            isCustom = settings.dxvk != null,
            options = listOf<String?>(null) + dxvks,
            optionLabel = { it ?: "Standard (built in)" },
            enabled = enabled,
        ) { update(settings.copy(dxvk = it)) }

        SectionLabel("Sound and measuring")
        SwitchRow("High-quality audio", settings.hqAudio, enabled) { update(settings.copy(hqAudio = it)) }
        SwitchRow("Log FPS and temperatures", settings.bench, enabled) { update(settings.copy(bench = it)) }

        SectionLabel("Launcher")
        SwitchRow("Start WoW when the app opens", settings.autoStart, enabled) { update(settings.copy(autoStart = it)) }
        Text(
            "Controller: click both sticks in together to open or close the keyboard.",
            fontSize = 11.sp,
            color = WowSubtle,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        SectionLabel("Expert")
        OutlinedTextField(
            value = settings.extraEnv,
            onValueChange = { update(settings.copy(extraEnv = it)) },
            label = { Text("Extra environment variables (KEY=value KEY2=value)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Changes apply the next time you press Play. New drivers (.zip) go in $gamePath/${WowLab.DIR}/drivers, " +
                "Proton and DXVK (.wcp) in ${WowLab.DIR}/proton and ${WowLab.DIR}/dxvk. FPS logs are saved in ${WowLab.DIR}/bench.",
            fontSize = 11.sp,
            color = WowSubtle,
        )
        TextButton(onClick = { update(WowLab.Settings()) }, enabled = enabled) {
            Text("Reset everything to standard", fontSize = 12.sp, color = WowMuted)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = WowSubtle,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
    )
}

/** One setting per line: the name on the left, what the next launch uses on the right (green when changed). */
@Composable
private fun <T> ChoiceRow(
    title: String,
    value: String,
    isCustom: Boolean,
    options: List<T>,
    optionLabel: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 13.sp, color = WowMuted, modifier = Modifier.weight(1f))
                Text(
                    "$value ▾",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCustom) OnColor else WowMuted,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = {
                    open = false
                    onSelect(option)
                })
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, color = WowMuted, modifier = Modifier.weight(1f))
        Text(
            if (checked) "On" else "Off",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (checked) OnColor else WowMuted,
            modifier = Modifier.padding(end = 8.dp),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
