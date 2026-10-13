package com.gujeff.findmyphone.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gujeff.findmyphone.R
import com.gujeff.findmyphone.alarm.AlarmController
import com.gujeff.findmyphone.service.AppState

/** Full-screen, lock-screen-visible stop button shown while the alarm rings. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        setContent {
            AppTheme {
                val ui by AppState.state.collectAsState()
                LaunchedEffect(ui.alarmRinging) { if (!ui.alarmRinging) finish() }
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(stringResource(R.string.alarm_screen_title), style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(vertical = 24.dp))
                        Button(
                            onClick = { AlarmController.stop() },
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text(stringResource(R.string.stop_alarm), style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
        }
    }
}
