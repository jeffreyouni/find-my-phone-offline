package com.gujeff.findmyphone.wake

import com.gujeff.findmyphone.data.WakeLang
import com.gujeff.findmyphone.data.WakePhrase
import com.gujeff.findmyphone.data.WakePhrases
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Wake-word detection on top of Vosk (Kaldi) running fully on-device.
 *
 * English phrases use a closed grammar ("<phrase>", "[unk]") so the decoder can only emit the phrase or
 * "unknown", which is cheap and sharply cuts false positives. Chinese presets do the same with a
 * pre-segmented form; custom Chinese and imported models use free decoding plus text matching because
 * we cannot know their word segmentation / vocabulary.
 */
class WakeWordDetector(model: Model, private val phrase: WakePhrase, sensitivity: Int) : AutoCloseable {
    private val cjk = WakePhrases.containsCjk(phrase.text)
    private val threshold = PhraseMatcher.confidenceThreshold(sensitivity)
    private val grammarText = phrase.segmented ?: phrase.text
    private val phraseTokens = PhraseMatcher.normalize(grammarText, false).split(' ').toSet()
    val grammarMode = phrase.segmented != null || (phrase.lang == WakeLang.EN && !cjk)

    private val recognizer: Recognizer =
        (if (grammarMode) Recognizer(model, SAMPLE_RATE, PhraseMatcher.grammar(grammarText)) else Recognizer(model, SAMPLE_RATE))
            .also { it.setWords(true) }

    private var samplesSinceReset = 0

    /** Feeds PCM16 audio; returns true when the wake phrase was recognised. */
    fun feed(buf: ShortArray, len: Int): Boolean {
        samplesSinceReset += len
        if (recognizer.acceptWaveForm(buf, len)) {
            samplesSinceReset = 0
            return evaluate(recognizer.result)
        }
        // Continuous noise can keep the decoder from ever endpointing; check the partial hypothesis as a fallback.
        if (samplesSinceReset > SAMPLE_RATE * 3) {
            val partial = JSONObject(recognizer.partialResult).optString("partial")
            if (PhraseMatcher.matches(partial, phrase.text, cjk)) {
                recognizer.reset(); samplesSinceReset = 0
                return true
            }
        }
        return false
    }

    /** Called when the audio gate closes: force the decoder to finalise what it has heard. */
    fun flush(): Boolean {
        samplesSinceReset = 0
        return evaluate(recognizer.finalResult)
    }

    fun reset() { recognizer.reset(); samplesSinceReset = 0 }

    private fun evaluate(json: String): Boolean {
        val obj = JSONObject(json)
        val text = obj.optString("text")
        if (!PhraseMatcher.matches(text, phrase.text, cjk)) return false
        val words = obj.optJSONArray("result") ?: return true
        var sum = 0.0; var n = 0
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            val word = PhraseMatcher.normalize(w.optString("word"), cjk)
            if (grammarMode && word !in phraseTokens) continue
            sum += w.optDouble("conf", 1.0); n++
        }
        return n == 0 || sum / n >= threshold
    }

    override fun close() { recognizer.close() }

    companion object { const val SAMPLE_RATE = 16000f }
}
