package com.gujeff.findmyphone

import com.gujeff.findmyphone.wake.PhraseMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseMatcherTest {
    @Test fun englishExactAndEmbedded() {
        assertTrue(PhraseMatcher.matches("hey phone", "Hey Phone", false))
        assertTrue(PhraseMatcher.matches("ok so hey phone please", "hey phone", false))
    }
    @Test fun englishNeedsWholeWords() {
        assertFalse(PhraseMatcher.matches("they phones", "hey phone", false))
        assertFalse(PhraseMatcher.matches("hey", "hey phone", false))
        assertFalse(PhraseMatcher.matches("", "hey phone", false))
    }
    @Test fun chineseIgnoresSegmentationSpaces() {
        assertTrue(PhraseMatcher.matches("手机 在 哪里", "手机在哪里", true))
        assertFalse(PhraseMatcher.matches("手机 在 这里", "手机在哪里", true))
    }
    @Test fun grammarFormat() {
        assertEquals("[\"hey phone\", \"[unk]\"]", PhraseMatcher.grammar("Hey, Phone!"))
    }
    @Test fun sensitivityMonotonic() {
        assertTrue(PhraseMatcher.confidenceThreshold(10) < PhraseMatcher.confidenceThreshold(1))
    }
}
