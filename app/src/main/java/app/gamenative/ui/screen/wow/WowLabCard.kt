package app.gamenative.ui.screen.wow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The unlocked test menu. Choices are saved right away and apply on the next Play. */
@Composable
internal fun WowLabCard(gamePath: String, defaultDriver: String, defaultTuDebug: String, enabled: Boolean, onHide: () -> Unit) {
    val context = LocalContext.current
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

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("TEST MENU", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = WowGold)
        Text(
            "New drivers (.zip) go in $gamePath/${WowLab.DIR}/drivers, Proton and DXVK (.wcp) in " +
                "${WowLab.DIR}/proton and ${WowLab.DIR}/dxvk. They install on the next Play; changes apply then too.",
            fontSize = 11.sp,
            color = WowSubtle,
        )
        LabChoice("Driver", settings.driver ?: "Auto ($defaultDriver)", listOf<String?>(null) + drivers, { it ?: "Auto ($defaultDriver)" }, enabled) {
            update(settings.copy(driver = it))
        }
        LabChoice("GMEM", settings.gmem.label + if (settings.gmem == WowLab.Gmem.AUTO) " [$defaultTuDebug]" else "", WowLab.Gmem.entries, { it.label }, enabled) {
            update(settings.copy(gmem = it))
        }
        LabChoice("Proton", settings.proton ?: "Bundled", listOf<String?>(null) + protons, { it ?: "Bundled" }, enabled) {
            update(settings.copy(proton = it))
        }
        LabChoice("DXVK", settings.dxvk ?: "Bundled", listOf<String?>(null) + dxvks, { it ?: "Bundled" }, enabled) {
            update(settings.copy(dxvk = it))
        }
        OutlinedTextField(
            value = settings.extraEnv,
            onValueChange = { update(settings.copy(extraEnv = it)) },
            label = { Text("Extra environment variables (KEY=value KEY2=value)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = settings.hqAudio, onCheckedChange = { update(settings.copy(hqAudio = it)) }, enabled = enabled)
            Text("High-quality audio (48 kHz, better resampler)", fontSize = 12.sp, color = WowMuted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = settings.bench, onCheckedChange = { update(settings.copy(bench = it)) }, enabled = enabled)
            Text("Log FPS and temperatures to ${WowLab.DIR}/bench", fontSize = 12.sp, color = WowMuted)
        }
        Row {
            TextButton(onClick = { update(WowLab.Settings(unlocked = true)) }, enabled = enabled) {
                Text("Reset to defaults", fontSize = 12.sp, color = WowMuted)
            }
            TextButton(onClick = {
                update(settings.copy(unlocked = false))
                onHide()
            }, enabled = enabled) {
                Text("Hide test menu", fontSize = 12.sp, color = WowMuted)
            }
        }
    }
}

@Composable
private fun <T> LabChoice(title: String, current: String, options: List<T>, label: (T) -> String, enabled: Boolean, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text("$title: $current", fontSize = 12.sp, color = WowMuted, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = {
                    expanded = false
                    onSelect(option)
                })
            }
        }
    }
}
