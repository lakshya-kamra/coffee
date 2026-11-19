package com.laksh.coffee.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

private val Sans = FontFamily.SansSerif

/**
 * One text style. Only 400, 500 and 700 are used: SemiBold resolves to 500 or 700 depending
 * on the OEM font. Every style asks for tabular digits, so numbers never jiggle as they change.
 */
private fun ts(size: Int, line: Int, weight: FontWeight, tracking: Float): TextStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = "tnum",
)

val CoffeeTypography = Typography(
    displayLarge = ts(52, 56, FontWeight.Bold, -1.6f),
    displayMedium = ts(40, 44, FontWeight.Bold, -1.2f),
    displaySmall = ts(32, 38, FontWeight.Bold, -0.8f),
    headlineLarge = ts(26, 32, FontWeight.Bold, -0.5f),
    headlineMedium = ts(22, 28, FontWeight.Bold, -0.3f),
    headlineSmall = ts(19, 25, FontWeight.Bold, -0.2f),
    titleLarge = ts(17, 22, FontWeight.Medium, -0.1f),
    titleMedium = ts(15, 20, FontWeight.Medium, 0f),
    titleSmall = ts(13, 18, FontWeight.Medium, 0.1f),
    bodyLarge = ts(16, 23, FontWeight.Normal, 0f),
    bodyMedium = ts(15, 21, FontWeight.Normal, 0f),
    bodySmall = ts(13, 18, FontWeight.Normal, 0.1f),
    labelLarge = ts(15, 20, FontWeight.Medium, 0.1f),
    labelMedium = ts(12, 16, FontWeight.Medium, 0.4f),
    labelSmall = ts(11, 14, FontWeight.Medium, 1.2f),
)

/**
 * Hero numerals. One per surface is big; everything else is small.
 *
 * FontWeight is written out in full in here because `Medium` alone would resolve to
 * [Num.Medium], the TextStyle.
 */
object Num {
    /** The running clock only. */
    val Clock = ts(88, 88, FontWeight.Medium, -2.5f)

    /** The running clock for recipes of 10:00 or longer, so "12:30" still fits. */
    val ClockCompact = ts(72, 72, FontWeight.Medium, -2f)

    /** Setup grind and dose, review score, pass score, passport total. */
    val Display = ts(64, 64, FontWeight.Bold, -2f)

    /** Routes, the one change, temperature, trend average. */
    val Large = ts(40, 44, FontWeight.Bold, -1f)

    /** Data-grid values. */
    val Medium = ts(26, 30, FontWeight.Bold, -0.5f)

    /** Log-row scores, journey endpoints, rank numerals. */
    val Small = ts(20, 24, FontWeight.Bold, -0.3f)

    /** Numbers inside rows. */
    val Inline = ts(15, 20, FontWeight.Medium, 0f)

    /** The unit that sits after a numeral: 0.42x its size (never under 13sp), 500, no tracking. */
    fun unit(numeral: TextStyle): TextStyle = numeral.copy(
        fontSize = if (numeral.fontSize.isSpecified) {
            maxOf(13f, numeral.fontSize.value * 0.42f).sp
        } else {
            13.sp
        },
        lineHeight = TextUnit.Unspecified,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )
}

/** Section and field labels. UPPERCASE, inkLow, always ABOVE the value they name. */
val Eyebrow = ts(11, 14, FontWeight.Medium, 1.2f)

/** UPPERCASE status chips, node labels, axis ticks and grid sub-labels. */
val Micro = ts(10, 12, FontWeight.Bold, 1.0f)

/** The primary button's label. */
val PrimaryLabel = ts(16, 20, FontWeight.Bold, 0f)

/** The current step's title while brewing. */
val StepTitle = ts(24, 30, FontWeight.Bold, -0.3f)

/** The analysis headline. */
val VerdictHeadline = ts(19, 26, FontWeight.Medium, -0.1f)

/** Kept so an older BrewScreen still compiles during the merge. */
@Deprecated("Use Num.Clock")
val TimerNumerals: TextStyle = Num.Clock.copy(textAlign = TextAlign.Center)

val CoffeeShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
