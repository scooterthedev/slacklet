package com.scooter.slackwear.feature.conversation

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.scooter.slackwear.core.data.Emoji

@Composable
fun EmojiGlyph(
    name: String,
    customEmoji: Map<String, String>,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val glyph = Emoji.glyphFor(name)
    val imageUrl = customEmoji[name]
    val codepoint = if (glyph == null && imageUrl == null) Emoji.codepointGlyph(name) else null

    when {
        glyph != null -> Text(
            text = glyph,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )

        imageUrl != null -> AsyncImage(
            model = imageUrl,
            contentDescription = name,
            modifier = modifier.size(size),
        )

        codepoint != null -> Text(
            text = codepoint,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )

        else -> Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}
