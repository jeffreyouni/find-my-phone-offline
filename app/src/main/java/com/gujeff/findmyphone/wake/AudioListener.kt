package com.gujeff.findmyphone.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.gujeff.findmyphone.data.WakePhrase
import com.gujeff.findmyphone.service.ListenStatus
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Owns the microphone loop. Audio is read in short blocks, passed through an adaptive energy gate so
 * the speech decoder only runs while something louder than the room is happening, and discarded
 * immediately afterwards. Nothing is written to disk.
 */
class AudioListener(
    private val context: Context,
    private val onDetected: () -> Unit,
    private val onState: (ListenStatus, String?) -> Unit,
) {
    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null

    fun start(phrase: WakePhrase, sensitivity: Int, batterySaver: Boolean) {
        stop()
        running = true
        thread = Thread({ run(phrase, sensitivity, batterySaver) }, "wake-listener").also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() } // unblocks a pending read()
        thread?.let { if (it !== Thread.currentThread()) it.join(3000) }
        thread = null
    }

    private fun run(phrase: WakePhrase, sensitivity: Int, batterySaver: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        onState(ListenStatus.LOADING, null)
        var model: org.vosk.Model? = null
        var detector: WakeWordDetector? = null
        try {
            model = ModelManager.open(context, phrase.lang.modelId)
            detector = WakeWordDetector(model, phrase, sensitivity)
        } catch (t: Throwable) {
            Log.e(TAG, "model load failed", t)
            onState(ListenStatus.ERROR, "Speech model: ${t.message}")
            running = false
            return
        }
        var failures = 0
        try {
            while (running) {
                try {
                    loop(detector, sensitivity, batterySaver) { failures = 0 }
                } catch (e: Exception) {
                    if (!running) break
                    failures++
                    Log.w(TAG, "audio loop failed ($failures)", e)
                    if (failures >= MAX_FAILURES) {
                        onState(ListenStatus.ERROR, "Microphone unavailable: ${e.message}")
                        running = false
                        break
                    }
                    onState(ListenStatus.LOADING, null)
                    Thread.sleep(1000L * failures) // 1s, 2s, 3s, 4s back-off before re-opening the mic
                }
            }
        } finally {
            detector.close()
            model.close()
        }
    }

    @SuppressLint("MissingPermission") // checked by the service before starting
    private fun loop(detector: WakeWordDetector, sensitivity: Int, batterySaver: Boolean, onHealthy: () -> Unit) {
        val rate = WakeWordDetector.SAMPLE_RATE.toInt()
        val chunk = if (batterySaver) rate / 5 else rate / 10          // 200 ms vs 100 ms
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, chunk * 2 * 4),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        record = rec
        try {
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Mic is in use by another app" }
            onState(ListenStatus.LISTENING, null)

            val minRms = (240 - 14 * sensitivity) * (if (batterySaver) 1.4 else 1.0)
            val mult = (3.2 - 0.12 * sensitivity) * (if (batterySaver) 1.3 else 1.0)
            val hangChunks = ((if (batterySaver) 500 else 700) * rate / 1000) / chunk + 1
            val prerollN = 3
            val preroll = Array(prerollN) { ShortArray(chunk) }
            var prerollHead = 0; var prerollCount = 0
            val buf = ShortArray(chunk)
            var floor = 40.0
            var gateOpen = false
            var hang = 0
            var zeroChunks = 0
            var wasPaused = false

            while (running) {
                val n = rec.read(buf, 0, chunk)
                if (n < 0) throw IllegalStateException("AudioRecord.read error $n")
                if (n == 0) continue

                var sumSq = 0.0; var peak = 0
                for (i in 0 until n) { val s = buf[i].toInt(); sumSq += s.toDouble() * s; if (kotlin.math.abs(s) > peak) peak = kotlin.math.abs(s) }
                val rms = sqrt(sumSq / n)

                // Android silences capture for apps it considers backgrounded: all-zero audio for seconds => re-open.
                if (peak == 0) { if (++zeroChunks > 8000 / (chunk * 1000 / rate)) throw IllegalStateException("Audio capture silenced by system") }
                else { zeroChunks = 0; onHealthy() }

                if (paused) { // alarm is ringing: do not listen to ourselves
                    wasPaused = true; gateOpen = false; prerollCount = 0
                    continue
                }
                if (wasPaused) { detector.reset(); wasPaused = false }

                val threshold = max(minRms, floor * mult)
                var detected = false
                if (!gateOpen) {
                    floor = max(5.0, if (rms < floor) floor * 0.8 + rms * 0.2 else floor * 0.995 + rms * 0.005)
                    if (rms > threshold) {
                        gateOpen = true; hang = hangChunks
                        // replay the last few blocks so the first syllable is not clipped
                        for (k in 0 until prerollCount) {
                            val idx = (prerollHead - prerollCount + k + prerollN * 2) % prerollN
                            if (detector.feed(preroll[idx], chunk)) detected = true
                        }
                        prerollCount = 0
                        if (!detected && detector.feed(buf, n)) detected = true
                    } else {
                        System.arraycopy(buf, 0, preroll[prerollHead], 0, n)
                        prerollHead = (prerollHead + 1) % prerollN
                        if (prerollCount < prerollN) prerollCount++
                    }
                } else {
                    if (detector.feed(buf, n)) detected = true
                    hang = if (rms > threshold * 0.7) hangChunks else hang - 1
                    if (!detected && hang <= 0) { gateOpen = false; if (detector.flush()) detected = true }
                }
                if (detected) {
                    gateOpen = false; prerollCount = 0
                    detector.reset()
                    onDetected()
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            record = null
        }
    }

    companion object {
        private const val TAG = "AudioListener"
        private const val MAX_FAILURES = 5
    }
}
