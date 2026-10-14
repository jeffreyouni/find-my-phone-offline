package com.gujeff.findmyphone.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gujeff.findmyphone.FindMyPhoneApp
import com.gujeff.findmyphone.R
import com.gujeff.findmyphone.alarm.AlarmController
import com.gujeff.findmyphone.data.Settings
import com.gujeff.findmyphone.data.WakePhrases
import com.gujeff.findmyphone.ui.AlarmActivity
import com.gujeff.findmyphone.ui.MainActivity
import com.gujeff.findmyphone.wake.AudioListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground service (type=microphone) that keeps the wake-word listener alive in the background. */
class ListenService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var listener: AudioListener
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    /** Whether the microphone is currently running (false while waiting for the screen to lock). */
    private var micActive = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshListening(restartIfActive = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        listener = AudioListener(
            this,
            onDetected = { main.post { onWakePhraseHeard() } },
            onState = { st, err -> main.post { onListenerState(st, err) } },
        )
        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_STOP_ALARM -> { AlarmController.stop(); return START_STICKY }
            ACTION_TEST_ALARM -> { if (started) triggerAlarm() else standaloneTestAlarm(); return if (started) START_STICKY else START_NOT_STICKY }
        }
        if (started) return START_STICKY
        // ACTION_START, or the system re-delivering after a process kill (intent == null).
        // The UI checks RECORD_AUDIO first; if it is missing, startForeground below throws and we report it.
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, listeningNotification(loading = true),
                if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // Android refuses to start a microphone service from the background (e.g. after a kill/reboot).
            fail("Could not start microphone service (permission missing, or Android blocked a background start) — open the app and tap Start Listening")
            if (intent == null) postStoppedNotification()
            return START_NOT_STICKY
        }
        started = true
        acquireWakeLockIfWanted()
        refreshListening(restartIfActive = true)
        // Restart detection when anything that affects it changes.
        scope.launch {
            Settings.get().flow
                .map { Triple(WakePhrases.resolve(it), it.sensitivity, Triple(it.batterySaver, it.keepCpuAwake, it.lockedOnly)) }
                .distinctUntilChanged().drop(1)
                .collect { acquireWakeLockIfWanted(); refreshListening(restartIfActive = true) }
        }
        return START_STICKY
    }

    private fun isKeyguardLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /** Starts or stops the microphone to honor "only listen when locked", without stopping the foreground service. */
    private fun refreshListening(restartIfActive: Boolean) {
        if (!started || AlarmController.isRinging) return
        val shouldListen = !Settings.get().value.lockedOnly || isKeyguardLocked()
        if (shouldListen) {
            if (!micActive || restartIfActive) { micActive = true; startListener() }
        } else {
            micActive = false
            listener.stop()
            postWaitingForLockNotification()
        }
    }

    private fun startListener() {
        val v = Settings.get().value
        val phrase = WakePhrases.resolve(v)
        AppState.set(ListenStatus.LOADING)
        listener.paused = AlarmController.isRinging
        listener.start(phrase, v.sensitivity, v.batterySaver)
        updateNotification(phrase.text, loading = true)
    }

    private fun onListenerState(status: ListenStatus, error: String?) {
        if (!started) return
        when (status) {
            ListenStatus.ERROR -> { fail(error ?: "Unknown error"); return }
            ListenStatus.LISTENING -> {
                if (!AlarmController.isRinging) AppState.set(ListenStatus.LISTENING)
                updateNotification(WakePhrases.resolve(Settings.get().value).text, loading = false)
            }
            else -> if (!AlarmController.isRinging) AppState.set(status)
        }
    }

    private fun onWakePhraseHeard() {
        if (started && !AlarmController.isRinging) triggerAlarm()
    }

    private fun triggerAlarm() {
        listener.paused = true
        AppState.set(ListenStatus.ALARM)
        postAlarmNotification()
        AlarmController.start(this, Settings.get().value) {
            // alarm finished or stopped by user
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ALARM_NOTIF_ID)
            listener.paused = false
            if (!started) AppState.set(ListenStatus.OFF)
            else if (!Settings.get().value.lockedOnly || isKeyguardLocked()) AppState.set(ListenStatus.LISTENING)
            else { micActive = false; listener.stop(); postWaitingForLockNotification() }
        }
    }

    /** "Test Alarm" while not listening: ring without starting the microphone. */
    private fun standaloneTestAlarm() {
        postAlarmNotification()
        AlarmController.start(this, Settings.get().value) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ALARM_NOTIF_ID)
            if (!started) stopSelf()
        }
    }

    // ---- notifications -----------------------------------------------------------------------

    private fun immutable() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun serviceIntent(action: String, code: Int) =
        PendingIntent.getService(this, code, Intent(this, ListenService::class.java).setAction(action), immutable())

    private fun listeningNotification(loading: Boolean, phrase: String = Settings.get().value.let { WakePhrases.resolve(it).text }): Notification =
        NotificationCompat.Builder(this, FindMyPhoneApp.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (loading) getString(R.string.notif_loading_title) else getString(R.string.notif_listening_title, phrase))
            .setContentText(getString(R.string.notif_listening_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), immutable()))
            .addAction(0, getString(R.string.notif_stop), serviceIntent(ACTION_STOP, 1))
            .build()

    private fun updateNotification(phrase: String, loading: Boolean) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, listeningNotification(loading, phrase))
    }

    private fun postWaitingForLockNotification() {
        AppState.set(ListenStatus.WAITING_FOR_LOCK)
        val n = NotificationCompat.Builder(this, FindMyPhoneApp.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_waiting_lock_title))
            .setContentText(getString(R.string.notif_waiting_lock_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), immutable()))
            .addAction(0, getString(R.string.notif_stop), serviceIntent(ACTION_STOP, 1))
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
    }

    private fun postAlarmNotification() {
        val full = PendingIntent.getActivity(this, 2, Intent(this, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION), immutable())
        val n = NotificationCompat.Builder(this, FindMyPhoneApp.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_alarm_title))
            .setContentText(getString(R.string.notif_alarm_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, getString(R.string.notif_stop_alarm), serviceIntent(ACTION_STOP_ALARM, 3))
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ALARM_NOTIF_ID, n)
    }

    private fun postStoppedNotification() {
        val n = NotificationCompat.Builder(this, FindMyPhoneApp.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_stopped_title))
            .setContentText(getString(R.string.notif_stopped_text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), immutable()))
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(STOPPED_NOTIF_ID, n)
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    private fun acquireWakeLockIfWanted() {
        val want = Settings.get().value.keepCpuAwake
        if (want && wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FindMyPhone:listen").apply { setReferenceCounted(false); acquire() }
        } else if (!want) releaseWakeLock()
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    private fun fail(message: String) {
        AppState.set(ListenStatus.ERROR, message)
        shutdown(keepError = true)
    }

    private fun shutdown(keepError: Boolean = false) {
        started = false
        micActive = false
        listener.stop()
        AlarmController.stop()
        releaseWakeLock()
        if (!keepError) AppState.set(ListenStatus.OFF)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        started = false
        micActive = false
        scope.cancel()
        runCatching { unregisterReceiver(screenStateReceiver) }
        listener.stop()
        AlarmController.stop()
        releaseWakeLock()
        if (AppState.state.value.status != ListenStatus.ERROR) AppState.set(ListenStatus.OFF)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gujeff.findmyphone.START"
        const val ACTION_STOP = "com.gujeff.findmyphone.STOP"
        const val ACTION_STOP_ALARM = "com.gujeff.findmyphone.STOP_ALARM"
        const val ACTION_TEST_ALARM = "com.gujeff.findmyphone.TEST_ALARM"
        private const val NOTIF_ID = 1
        private const val ALARM_NOTIF_ID = 2
        private const val STOPPED_NOTIF_ID = 3

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java).setAction(ACTION_START))

        fun stop(context: Context) =
            context.startService(Intent(context, ListenService::class.java).setAction(ACTION_STOP))

        fun testAlarm(context: Context) =
            context.startService(Intent(context, ListenService::class.java).setAction(ACTION_TEST_ALARM))

        fun stopAlarm(context: Context) = AlarmController.stop()
    }
}
