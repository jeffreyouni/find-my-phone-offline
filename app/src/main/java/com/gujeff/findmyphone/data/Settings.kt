package com.gujeff.findmyphone.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AlarmSound { SIREN, BEEP, SYSTEM }

/** Small SharedPreferences-backed store exposed as a StateFlow so the UI and the service stay in sync. */
class Settings private constructor(context: Context) {
    data class Values(
        val phraseId: String = "hey_phone",
        val customPhrase: String = "",
        val sensitivity: Int = 6,          // 1..10
        val sound: AlarmSound = AlarmSound.SIREN,
        val durationSec: Int = 30,
        val volumePct: Int = 100,
        val vibration: Boolean = true,
        val flashlight: Boolean = true,
        val batterySaver: Boolean = false,
        val keepCpuAwake: Boolean = true,
    )

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<Values> = _flow

    val value: Values get() = _flow.value

    fun update(block: (Values) -> Values) {
        val v = block(_flow.value)
        prefs.edit()
            .putString("phraseId", v.phraseId)
            .putString("customPhrase", v.customPhrase)
            .putInt("sensitivity", v.sensitivity)
            .putString("sound", v.sound.name)
            .putInt("durationSec", v.durationSec)
            .putInt("volumePct", v.volumePct)
            .putBoolean("vibration", v.vibration)
            .putBoolean("flashlight", v.flashlight)
            .putBoolean("batterySaver", v.batterySaver)
            .putBoolean("keepCpuAwake", v.keepCpuAwake)
            .apply()
        _flow.value = v
    }

    private fun read(): Values {
        val d = Values()
        return Values(
            phraseId = prefs.getString("phraseId", d.phraseId) ?: d.phraseId,
            customPhrase = prefs.getString("customPhrase", d.customPhrase) ?: "",
            sensitivity = prefs.getInt("sensitivity", d.sensitivity).coerceIn(1, 10),
            sound = runCatching { AlarmSound.valueOf(prefs.getString("sound", d.sound.name)!!) }.getOrDefault(d.sound),
            durationSec = prefs.getInt("durationSec", d.durationSec).coerceIn(5, 300),
            volumePct = prefs.getInt("volumePct", d.volumePct).coerceIn(10, 100),
            vibration = prefs.getBoolean("vibration", d.vibration),
            flashlight = prefs.getBoolean("flashlight", d.flashlight),
            batterySaver = prefs.getBoolean("batterySaver", d.batterySaver),
            keepCpuAwake = prefs.getBoolean("keepCpuAwake", d.keepCpuAwake),
        )
    }

    companion object {
        @Volatile private var instance: Settings? = null
        fun init(context: Context) { instance = Settings(context) }
        fun get(): Settings = instance ?: error("Settings.init not called")
    }
}
