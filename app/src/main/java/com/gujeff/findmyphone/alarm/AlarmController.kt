package com.gujeff.findmyphone.alarm

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.gujeff.findmyphone.data.AlarmSound
import com.gujeff.findmyphone.data.Settings
import com.gujeff.findmyphone.service.AppState
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays the "here I am" alarm: sound on the ALARM audio stream (so it is audible in silent/vibrate
 * mode without touching system security), vibration and a blinking torch. Main-thread only.
 */
object AlarmController {
    private const val TAG = "AlarmController"
    private val main = Handler(Looper.getMainLooper())

    private var ctx: Context? = null
    private var track: AudioTrack? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null
    private var torchId: String? = null
    private var torchOn = false
    private var savedVolume: Int? = null
    private var onFinished: (() -> Unit)? = null

    val isRinging get() = ctx != null

    private val stopRunnable = Runnable { stop() }
    private val torchRunnable = object : Runnable {
        override fun run() {
            setTorch(!torchOn)
            main.postDelayed(this, 350)
        }
    }

    fun start(context: Context, v: Settings.Values, finished: (() -> Unit)? = null) {
        if (isRinging) return
        val app = context.applicationContext
        ctx = app
        onFinished = finished
        AppState.setAlarm(true)

        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // Use the alarm stream at the user's chosen level; restore it afterwards.
        runCatching {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * v.volumePct / 100f).toInt().coerceAtLeast(1), 0)
        }.onFailure { Log.w(TAG, "volume", it) }

        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs).build().also { runCatching { am.requestAudioFocus(it) } }

        runCatching { startSound(app, v.sound, attrs) }.onFailure {
            Log.e(TAG, "sound failed, falling back to siren", it)
            runCatching { startSiren(attrs, 0) }
        }
        if (v.vibration) runCatching { startVibration(app) }.onFailure { Log.w(TAG, "vibrate", it) }
        if (v.flashlight) runCatching { startTorch(app) }.onFailure { Log.w(TAG, "torch", it) }

        main.postDelayed(stopRunnable, v.durationSec * 1000L)
    }

    fun stop() {
        val c = ctx ?: return
        main.removeCallbacks(stopRunnable)
        main.removeCallbacks(torchRunnable)
        runCatching { track?.stop(); track?.release() }; track = null
        runCatching { player?.stop(); player?.release() }; player = null
        runCatching { vibrator?.cancel() }; vibrator = null
        setTorch(false); torchId = null
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedVolume?.let { runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }; savedVolume = null
        focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }; focus = null
        ctx = null
        AppState.setAlarm(false)
        onFinished?.invoke(); onFinished = null
    }

    // ---- sound -------------------------------------------------------------------------------

    private fun startSound(c: Context, sound: AlarmSound, attrs: AudioAttributes) = when (sound) {
        AlarmSound.SIREN -> startSiren(attrs, 0)
        AlarmSound.BEEP -> startSiren(attrs, 1)
        AlarmSound.SYSTEM -> {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(c, uri)
                isLooping = true
                prepare(); start()
            }
            Unit
        }
    }

    /** Synthesises a one-second looping waveform (sweeping siren or double beeps) — no audio asset needed. */
    private fun startSiren(attrs: AudioAttributes, kind: Int) {
        val rate = 22050
        val pcm = ShortArray(rate)
        var phase = 0.0
        for (i in pcm.indices) {
            val t = i.toDouble() / rate
            val sample = if (kind == 0) {
                val sweep = if (t < 0.5) t / 0.5 else (1 - t) / 0.5       // up then down, 1 Hz
                val f = 650 + 900 * sweep
                phase += 2 * PI * f / rate
                sin(phase)
            } else {
                val on = (t % 0.5) < 0.2                                    // two 0.2 s beeps per second
                if (on) sin(2 * PI * 1800 * t) else 0.0
            }
            pcm[i] = (sample * Short.MAX_VALUE * 0.95).toInt().toShort()
        }
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        t.play()
        track = t
    }

    // ---- vibration / torch ---------------------------------------------------------------------

    private fun startVibration(c: Context) {
        val vib = if (android.os.Build.VERSION.SDK_INT >= 31)
            (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vib.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600, 300), 0)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        vibrator = vib
    }

    private fun startTorch(c: Context) {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        torchId = cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return
        torchOn = false
        main.post(torchRunnable)
    }

    private fun setTorch(on: Boolean) {
        val id = torchId
        val c = ctx
        if (id == null || c == null) { torchOn = false; return }
        try {
            (c.getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, on)
            torchOn = on
        } catch (e: CameraAccessException) { Log.w(TAG, "torch busy", e) }
        catch (e: IllegalArgumentException) { Log.w(TAG, "torch", e) }
    }
}
