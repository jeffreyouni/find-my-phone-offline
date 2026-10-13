package com.gujeff.findmyphone.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { Root(vm) } }
    }
}

@Composable
private fun Root(vm: MainViewModel) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf("main") }
    var micDenied by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    var fullScreenOk by remember { mutableStateOf(canFullScreen(ctx)) }

    LifecycleResumeEffect(Unit) {
        batteryOk = isIgnoringBattery(ctx); fullScreenOk = canFullScreen(ctx)
        onPauseOrDispose { }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val mic = r[Manifest.permission.RECORD_AUDIO] ?: (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        micDenied = !mic
        if (mic) vm.startListening() // notifications being denied does not block listening, it only hides the notification
    }

    val onStart = {
        val need = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) { micDenied = false; vm.startListening() } else permLauncher.launch(need.toTypedArray())
    }

    val openAppSettings = {
        ctx.startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }
    val openBattery = {
        runCatching { ctx.startActivity(Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { openAppSettings() }
        Unit
    }
    val openFullScreen = {
        if (Build.VERSION.SDK_INT >= 34)
            runCatching { ctx.startActivity(Intent(SysSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))) }
        Unit
    }

    if (screen == "main") {
        MainScreen(vm, micDenied, onStart, openAppSettings) { screen = "settings" }
    } else {
        SettingsScreen(vm, batteryOk, fullScreenOk, openBattery, openFullScreen) { screen = "main" }
    }
}

private fun isIgnoringBattery(c: Context) =
    (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

private fun canFullScreen(c: Context) =
    Build.VERSION.SDK_INT < 34 || (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
