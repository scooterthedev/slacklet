package com.scooter.slackwear.feature.conversation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun emphasisIsStyledAndItsMarkersAreGone() {
        val bold = buildRichText("ship *it* now", emptySet())
        assertEquals("ship it now", bold.text)
        assertEquals(FontWeight.Bold, bold.styleAt("it").fontWeight)

        val italic = buildRichText("ship _it_ now", emptySet())
        assertEquals("ship it now", italic.text)
        assertEquals(FontStyle.Italic, italic.styleAt("it").fontStyle)

        val struck = buildRichText("ship ~it~ now", emptySet())
        assertEquals("ship it now", struck.text)
        assertEquals(TextDecoration.LineThrough, struck.styleAt("it").textDecoration)
    }

    @Test
    fun emphasisNests() {
        val nested = buildRichText("*bold and _also italic_*", emptySet())
        assertEquals("bold and also italic", nested.text)
        assertEquals(FontWeight.Bold, nested.styleAt("bold and").fontWeight)
        assertEquals(FontWeight.Bold, nested.styleAt("also italic").fontWeight)
        assertEquals(FontStyle.Italic, nested.styleAt("also italic").fontStyle)
    }

    @Test
    fun underscoresInsideWordsAreNotEmphasis() {
        val text = "see run_the_thing and msw_fast_lane"
        val rendered = buildRichText(text, emptySet())
        assertEquals(text, rendered.text)
        assertFalse(hasMarkup("plain sentence with nothing in it"))
    }

    @Test
    fun codeIsLiteralAndSpansAreNotReparsed() {
        val inline = buildRichText("run `git commit -m *nope*`", emptySet())
        assertEquals("run git commit -m *nope*", inline.text)
        assertEquals(FontFamily.Monospace, inline.styleAt("git commit").fontFamily)

        val block = buildRichText("before\n```\nval x = _y_\n```\nafter", emptySet())
        assertTrue(block.text.contains("val x = _y_"))
        assertEquals(FontFamily.Monospace, block.styleAt("val x").fontFamily)
    }

    @Test
    fun quotedLinesAreMarkedAndKeepTheirFormatting() {
        val quoted = buildRichText("> quoting *you*\nand replying", emptySet())
        assertTrue(quoted.text.startsWith("▏ quoting you"))
        assertTrue(quoted.text.endsWith("and replying"))
        assertEquals(FontWeight.Bold, quoted.styleAt("you").fontWeight)
    }

    @Test
    fun customEmojiStillBecomeInlineContentAlongsideFormatting() {
        val rendered = buildRichText("*look* :orpheus: here", setOf("orpheus"))
        assertEquals(FontWeight.Bold, rendered.styleAt("look").fontWeight)
        assertEquals(
            listOf("orpheus"),
            rendered.getStringAnnotations(0, rendered.length)
                .filter { it.tag == "androidx.compose.foundation.text.inlineContent" }
                .map { it.item },
        )
    }

    @Test
    fun onlyTextWithMarkupTakesTheParsingPath() {
        assertTrue(hasMarkup("*bold*"))
        assertTrue(hasMarkup("> quote"))
        assertTrue(hasMarkup("`code`"))
        assertFalse(hasMarkup("just a normal sentence, honestly"))
    }

    private fun AnnotatedString.styleAt(needle: String): SpanStyle {
        val at = text.indexOf(needle)
        return spanStyles.filter { it.start <= at && it.end > at }
            .fold(SpanStyle()) { merged, range -> merged.merge(range.item) }
    }
}
