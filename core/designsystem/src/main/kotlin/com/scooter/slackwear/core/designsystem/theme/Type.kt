package com.scooter.slackwear.core.designsystem.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Typography
import com.scooter.slackwear.core.designsystem.R

val Lato = FontFamily(
    Font(R.font.lato_regular, FontWeight.Normal),
    Font(R.font.lato_bold, FontWeight.Bold),
    Font(R.font.lato_black, FontWeight.Black),
)

val SlackWearTypography: Typography = Typography().let { base ->
    fun style(size: Int, weight: FontWeight, lineHeight: Int, spacing: Double = 0.0) = TextStyle(
        fontFamily = Lato,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = spacing.sp,
    )

    base.copy(

        titleLarge = style(18, FontWeight.Black, 22, -0.2),

        titleMedium = style(16, FontWeight.Bold, 20),
        titleSmall = style(14, FontWeight.Bold, 18),

        bodyLarge = style(14, FontWeight.Normal, 19),
        bodyMedium = style(13, FontWeight.Normal, 17),

        bodySmall = style(12, FontWeight.Normal, 15),

        labelMedium = style(12, FontWeight.Black, 14, 0.8),

        labelSmall = style(11, FontWeight.Black, 13, 0.4),
    )
}
