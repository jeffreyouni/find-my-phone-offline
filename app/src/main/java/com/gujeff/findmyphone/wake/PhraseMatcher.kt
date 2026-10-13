package com.gujeff.findmyphone.wake

/** Pure text matching, kept free of Android types so it is unit-testable on the JVM. */
object PhraseMatcher {
    fun normalize(s: String, cjk: Boolean): String {
        val lower = s.lowercase()
        return if (cjk) lower.filter { Character.isLetterOrDigit(it) }
        else lower.replace(Regex("[^\\p{L}\\p{N}']+"), " ").trim()
    }

    /** True if [transcript] contains [phrase] as a whole-word run (English) or substring (Chinese). */
    fun matches(transcript: String, phrase: String, cjk: Boolean): Boolean {
        val t = normalize(transcript, cjk)
        val p = normalize(phrase, cjk)
        if (t.isEmpty() || p.isEmpty()) return false
        return if (cjk) t.contains(p) else " $t ".contains(" $p ")
    }

    /** Grammar tokens for Vosk: "hey phone" -> ["hey phone", "[unk]"]. */
    fun grammar(phrase: String): String {
        val p = normalize(phrase, false)
        return "[\"$p\", \"[unk]\"]"
    }

    /** Sensitivity 1..10 -> minimum average word confidence. */
    fun confidenceThreshold(sensitivity: Int): Double = (0.95 - 0.06 * sensitivity.coerceIn(1, 10)).coerceAtLeast(0.3)
}
