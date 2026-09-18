package com.scooter.slackwear.feature.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.wear.compose.material3.Text
import com.scooter.slackwear.core.designsystem.component.customEmojiIn
import com.scooter.slackwear.core.designsystem.component.emojiInlineContent

@Composable
fun EmojiText(
    text: String,
    customEmoji: Map<String, String>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val present = remember(text, customEmoji) { customEmojiIn(text, customEmoji) }

    val formatted = remember(text) { hasMarkup(text) }

    if (present.isEmpty() && !formatted) {
        Text(text = text, style = style, color = color, modifier = modifier)
        return
    }

    val annotated = remember(text, present) { buildRichText(text, present.toSet()) }
    val inlineContent = remember(present, customEmoji) { emojiInlineContent(present, customEmoji) }

    Text(
        text = annotated,
        style = style,
        color = color,
        inlineContent = inlineContent,
        modifier = modifier,
    )
}
