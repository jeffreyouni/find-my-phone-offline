package com.gujeff.findmyphone.data

import androidx.annotation.StringRes
import com.gujeff.findmyphone.R

enum class WakeLang(val modelId: String) { EN("en"), ZH("cn"), IMPORTED("import") }

data class WakePhrase(val id: String, val text: String, val lang: WakeLang, @StringRes val label: Int = 0,
                      /** Word-segmented form for Chinese presets, enabling the cheap closed-grammar decoder. */
                      val segmented: String? = null)

object WakePhrases {
    const val CUSTOM_ID = "custom"
    const val IMPORTED_ID = "imported"

    val presets = listOf(
        WakePhrase("hey_phone", "hey phone", WakeLang.EN, R.string.phrase_hey_phone),
        WakePhrase("where_are_you", "where are you", WakeLang.EN, R.string.phrase_where_are_you),
        WakePhrase("find_my_phone", "find my phone", WakeLang.EN, R.string.phrase_find_my_phone),
        WakePhrase("hello_phone", "hello phone", WakeLang.EN, R.string.phrase_hello_phone),
        WakePhrase("zh_where", "手机在哪里", WakeLang.ZH, R.string.phrase_zh_where, "手机 在 哪里"),
        WakePhrase("zh_where2", "手机你在哪", WakeLang.ZH, R.string.phrase_zh_where2, "手机 你 在 哪"),
    )

    fun containsCjk(s: String) = s.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    fun resolve(v: Settings.Values): WakePhrase {
        presets.firstOrNull { it.id == v.phraseId }?.let { return it }
        val text = v.customPhrase.trim().ifEmpty { "hey phone" }
        val lang = when {
            v.phraseId == IMPORTED_ID -> WakeLang.IMPORTED
            containsCjk(text) -> WakeLang.ZH
            else -> WakeLang.EN
        }
        return WakePhrase(v.phraseId, text, lang)
    }
}
