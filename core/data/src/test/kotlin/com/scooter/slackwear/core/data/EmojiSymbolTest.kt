package com.scooter.slackwear.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiSymbolTest {

    @Test
    fun aSlackCodepointIsTurnedIntoTheEmojiItNames() {
        assertEquals("😭", Emoji.codepointGlyph("1f62d"))
        assertEquals("🇺🇸", Emoji.codepointGlyph("1f1fa-1f1f8"))
    }

    @Test
    fun aCustomEmojiNameIsNeverMistakenForACodepoint() {
        assertNull(Emoji.canonicalName("blobhaj"))
        assertNull(Emoji.canonicalName("loll"))
        assertNull(Emoji.canonicalName("mhm"))
    }

    @Test
    fun anAllHexCustomNameIsNotSilentlyRewrittenIntoSomeUnrelatedGlyph() {
        assertNull(Emoji.canonicalName("cafe"))
        assertNull(Emoji.canonicalName("bead"))
    }

    @Test
    fun aLiteralGlyphIsRecognisedAsAGlyphRatherThanAName() {
        assertEquals("😭", Emoji.glyphFor("😭"))
        assertNull(Emoji.glyphFor("blobhaj"))
    }
}
