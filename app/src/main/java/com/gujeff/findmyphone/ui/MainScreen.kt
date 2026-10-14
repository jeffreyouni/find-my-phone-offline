package com.gujeff.findmyphone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gujeff.findmyphone.R
import com.gujeff.findmyphone.data.WakePhrases
import com.gujeff.findmyphone.service.ListenStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, micDenied: Boolean, onStart: () -> Unit, openAppSettings: () -> Unit, openSettings: () -> Unit) {
    val ui by vm.state.collectAsState()
    val s by vm.settings.flow.collectAsState()
    val phrase = WakePhrases.resolve(s)
    val listening = ui.status == ListenStatus.LISTENING || ui.status == ListenStatus.LOADING ||
        ui.status == ListenStatus.ALARM || ui.status == ListenStatus.WAITING_FOR_LOCK

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            actions = { IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val active = ui.status == ListenStatus.LISTENING || ui.status == ListenStatus.ALARM
            Surface(
                shape = CircleShape,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(160.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(if (listening) R.drawable.ic_mic else R.drawable.ic_mic_off), null,
                        Modifier.size(88.dp),
                        tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                when (ui.status) {
                    ListenStatus.OFF -> stringResource(R.string.status_off)
                    ListenStatus.LOADING -> stringResource(R.string.status_loading)
                    ListenStatus.LISTENING -> stringResource(R.string.status_listening)
                    ListenStatus.WAITING_FOR_LOCK -> stringResource(R.string.status_waiting_lock)
                    ListenStatus.ALARM -> stringResource(R.string.status_alarm)
                    ListenStatus.ERROR -> stringResource(R.string.status_error, ui.error ?: "")
                },
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                color = if (ui.status == ListenStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.say_phrase, phrase.text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.wake_phrase) + ": “${phrase.text}”", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)

            if (micDenied) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.mic_denied), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                TextButton(onClick = openAppSettings) { Text(stringResource(R.string.open_app_settings)) }
            }

            Spacer(Modifier.height(28.dp))
            if (ui.alarmRinging) {
                Button(
                    onClick = vm::stopAlarm, modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.stop_alarm)) }
            } else if (!listening) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.start_listening)) }
            } else {
                OutlinedButton(onClick = vm::stopListening, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.stop_listening)) }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = vm::testAlarm, enabled = !ui.alarmRinging, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.test_alarm))
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.privacy_note), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
