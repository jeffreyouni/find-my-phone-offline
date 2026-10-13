package com.gujeff.findmyphone.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gujeff.findmyphone.R
import com.gujeff.findmyphone.data.AlarmSound
import com.gujeff.findmyphone.data.WakePhrases
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel, batteryOk: Boolean, fullScreenOk: Boolean,
    openBattery: () -> Unit, openFullScreen: () -> Unit, onBack: () -> Unit,
) {
    val s by vm.settings.flow.collectAsState()
    val msg by vm.message.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.consumeMessage() } }

    val okText = stringResource(R.string.model_import_ok)
    val failFmt = stringResource(R.string.model_import_failed, "%s")
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri, okText) { failFmt.replace("%s", it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Section(R.string.section_phrase) {
                WakePhrases.presets.forEach { p ->
                    RadioRow(stringResource(p.label), s.phraseId == p.id) { vm.update { it.copy(phraseId = p.id) } }
                }
                RadioRow(stringResource(R.string.custom_phrase), s.phraseId == WakePhrases.CUSTOM_ID) { vm.update { it.copy(phraseId = WakePhrases.CUSTOM_ID) } }
                RadioRow(stringResource(R.string.phrase_imported), s.phraseId == WakePhrases.IMPORTED_ID) { vm.update { it.copy(phraseId = WakePhrases.IMPORTED_ID) } }
                if (s.phraseId == WakePhrases.CUSTOM_ID || s.phraseId == WakePhrases.IMPORTED_ID) {
                    OutlinedTextField(
                        value = s.customPhrase, onValueChange = { t -> vm.update { it.copy(customPhrase = t) } },
                        label = { Text(stringResource(R.string.custom_phrase_hint)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Section(R.string.section_detection) {
                Text(stringResource(R.string.sensitivity, s.sensitivity))
                Slider(value = s.sensitivity.toFloat(), onValueChange = { v -> vm.update { it.copy(sensitivity = v.roundToInt()) } },
                    valueRange = 1f..10f, steps = 8)
                Text(stringResource(R.string.sensitivity_help), style = MaterialTheme.typography.bodySmall)
            }

            Section(R.string.section_alarm) {
                Text(stringResource(R.string.alarm_sound))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(AlarmSound.SIREN to R.string.sound_siren, AlarmSound.BEEP to R.string.sound_beep, AlarmSound.SYSTEM to R.string.sound_system)
                        .forEach { (snd, label) ->
                            FilterChip(selected = s.sound == snd, onClick = { vm.update { it.copy(sound = snd) } }, label = { Text(stringResource(label)) })
                        }
                }
                Text(stringResource(R.string.alarm_duration, s.durationSec))
                Slider(value = s.durationSec.toFloat(), onValueChange = { v -> vm.update { it.copy(durationSec = (v / 5).roundToInt() * 5) } },
                    valueRange = 10f..120f)
                Text(stringResource(R.string.alarm_volume, s.volumePct))
                Slider(value = s.volumePct.toFloat(), onValueChange = { v -> vm.update { it.copy(volumePct = v.roundToInt()) } },
                    valueRange = 20f..100f)
                SwitchRow(stringResource(R.string.vibration), s.vibration) { c -> vm.update { it.copy(vibration = c) } }
                SwitchRow(stringResource(R.string.flashlight), s.flashlight) { c -> vm.update { it.copy(flashlight = c) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (fullScreenOk) R.string.fullscreen_ok else R.string.fullscreen_missing), Modifier.weight(1f))
                    if (!fullScreenOk) OutlinedButton(onClick = openFullScreen) { Text(stringResource(R.string.grant)) }
                }
            }

            Section(R.string.section_battery) {
                SwitchRow(stringResource(R.string.battery_saver), s.batterySaver, stringResource(R.string.battery_saver_help)) { c -> vm.update { it.copy(batterySaver = c) } }
                SwitchRow(stringResource(R.string.keep_cpu), s.keepCpuAwake, stringResource(R.string.keep_cpu_help)) { c -> vm.update { it.copy(keepCpuAwake = c) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (batteryOk) R.string.battery_unrestricted else R.string.battery_restricted), Modifier.weight(1f),
                        color = if (batteryOk) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = openBattery) { Text(stringResource(R.string.open_battery_settings)) }
                }
            }

            Section(R.string.section_samsung) {
                Text(stringResource(R.string.samsung_steps), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.mic_indicator), style = MaterialTheme.typography.bodySmall)
            }

            Section(R.string.section_model) {
                Text(stringResource(R.string.import_model_help), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(stringResource(R.string.import_model)) }
            }
            Text(stringResource(R.string.privacy_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, help: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
