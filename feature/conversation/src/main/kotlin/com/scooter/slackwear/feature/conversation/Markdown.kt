package com.scooter.slackwear.feature.conversation

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.scooter.slackwear.core.designsystem.theme.SlackTokens
import com.scooter.slackwear.core.designsystem.component.EmojiShortcode

private val FENCE = Regex("```([\\s\\S]*?)```")

private val INLINE = Regex(
    "`([^`\\n]+?)`" +
        "|\\*([^*\\n]+?)\\*" +
        "|~([^~\\n]+?)~" +
        "|(?<![\\w`])_([^_\\n]+?)_(?![\\w`])",
)

private val CodeStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = SlackTokens.RaspberryRed)
private val QuoteBarStyle = SpanStyle(color = SlackTokens.ContentTertiary, fontWeight = FontWeight.Black)
private val QuoteTextStyle = SpanStyle(color = SlackTokens.ContentSecondary)

internal fun hasMarkup(text: String): Boolean =
    text.any { it == '*' || it == '_' || it == '~' || it == '`' } || text.lineSequence().any { it.startsWith(">") }

internal fun buildRichText(text: String, emoji: Set<String>): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (fence in FENCE.findAll(text)) {
        appendLines(text.substring(cursor, fence.range.first), emoji)

        withStyle(CodeStyle) { append(fence.groupValues[1].trim('\n')) }
        cursor = fence.range.last + 1
    }
    appendLines(text.substring(cursor), emoji)
}

private fun AnnotatedString.Builder.appendLines(text: String, emoji: Set<String>) {
    if (text.isEmpty()) return
    text.split("\n").forEachIndexed { index, line ->
        if (index > 0) append("\n")
        if (line.startsWith(">")) {

            withStyle(QuoteBarStyle) { append("▏ ") }
            withStyle(QuoteTextStyle) { appendInline(line.removePrefix(">").removePrefix(" "), emoji) }
        } else {
            appendInline(line, emoji)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(text: String, emoji: Set<String>) {
    var cursor = 0
    for (match in INLINE.findAll(text)) {
        if (match.range.first < cursor) continue
        appendEmoji(text.substring(cursor, match.range.first), emoji)

        val code = match.groups[1]?.value
        val bold = match.groups[2]?.value
        val struck = match.groups[3]?.value
        val italic = match.groups[4]?.value
        when {
            code != null -> withStyle(CodeStyle) { append(code) }

            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(bold, emoji) }
            struck != null ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(struck, emoji) }
            italic != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(italic, emoji) }
        }
        cursor = match.range.last + 1
    }
    appendEmoji(text.substring(cursor), emoji)
}

private fun AnnotatedString.Builder.appendEmoji(text: String, emoji: Set<String>) {
    if (text.isEmpty()) return
    if (emoji.isEmpty()) {
        append(text)
        return
    }

    var cursor = 0
    for (match in EmojiShortcode.findAll(text)) {
        val name = match.groupValues[1].lowercase()
        if (name !in emoji) continue
        append(text.substring(cursor, match.range.first))
        appendInlineContent(name, match.value)
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
