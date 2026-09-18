package com.scooter.slackwear.core.designsystem.component

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage

val EmojiShortcode = Regex(":([a-z0-9_+'\\-]+):", RegexOption.IGNORE_CASE)

fun customEmojiIn(text: String, customEmoji: Map<String, String>): List<String> =
    EmojiShortcode.findAll(text)
        .map { it.groupValues[1].lowercase() }
        .filter(customEmoji::containsKey)
        .distinct()
        .toList()

fun emojiInlineContent(
    names: Collection<String>,
    customEmoji: Map<String, String>,
    size: androidx.compose.ui.unit.TextUnit = 1.4.em,
): Map<String, InlineTextContent> = names.associateWith { name ->
    InlineTextContent(
        Placeholder(width = size, height = size, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
    ) {
        AsyncImage(model = customEmoji[name], contentDescription = name, modifier = Modifier)
    }
}

fun emojiAnnotated(text: String, names: Set<String>): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    EmojiShortcode.findAll(text).forEach { match ->
        val name = match.groupValues[1].lowercase()
        if (name !in names) return@forEach
        append(text.substring(cursor, match.range.first))
        appendInlineContent(name, match.value)
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
fun EmojiLabel(
    text: String,
    customEmoji: Map<String, String>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val present = remember(text, customEmoji) { customEmojiIn(text, customEmoji) }

    if (present.isEmpty()) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
        return
    }

    val annotated = remember(text, present) { emojiAnnotated(text, present.toSet()) }
    val inlineContent = remember(present, customEmoji) { emojiInlineContent(present, customEmoji) }

    Text(
        text = annotated,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent,
        modifier = modifier,
    )
}
