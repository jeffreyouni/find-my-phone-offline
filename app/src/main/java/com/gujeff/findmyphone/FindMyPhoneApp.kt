package com.gujeff.findmyphone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.gujeff.findmyphone.data.Settings

class FindMyPhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    setSound(null, null) // the alarm sound is played by AlarmController on the alarm stream
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
        )
    }

    companion object {
        const val CHANNEL_LISTENING = "listening"
        const val CHANNEL_ALARM = "alarm"
    }
}
