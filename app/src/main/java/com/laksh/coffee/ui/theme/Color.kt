package com.laksh.coffee.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Every colour the app draws with. Screens read these through [CoffeeTheme.colors] and never
 * write hex literals, so dark and light stay two deliberate palettes rather than one palette
 * and its inversion.
 *
 * - Surfaces lift by shade only: bg (canvas) < surface1 (card) < surface2 (well) < surface3 (raised).
 * - Ink is a four-step ramp. inkFaint is for marks (tracks, future nodes), never for words.
 *   inkLow clears 4.5:1 on bg and surface2 in both modes, so small caps can use it.
 * - Crema is the one live accent: the brew in progress. It never marks a status. cremaInk is
 *   its text shade, dark enough for small words on the light brew washes.
 * - good / attention / fault always travel with a word.
 * - neutralMark is a 6 or 7 on a chart: the quietest scored mark in both modes, quieter than
 *   every status colour so the red, amber, green arc reads first, and still 3:1 on a card.
 */
@Immutable
data class CoffeeColors(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val bar: Color,
    val barBorder: Color,
    val hairline: Color,
    val hairlineOnBg: Color,
    val outlineStrong: Color,
    val inkHigh: Color,
    val inkMid: Color,
    val inkLow: Color,
    val inkFaint: Color,
    val inverse: Color,
    val onInverse: Color,
    val scrim: Color,
    val crema: Color,
    val cremaStroke: Color,
    val cremaInk: Color,
    val onCrema: Color,
    val cremaContainer: Color,
    val ringLiquid: Color,
    val good: Color,
    val goodContainer: Color,
    val attention: Color,
    val attentionContainer: Color,
    val fault: Color,
    val faultContainer: Color,
    val neutralMark: Color,
    val artShell: Color,
    val artWater: Color,
    val artBed: Color,
    val isDark: Boolean,
)

/** A warm near-black cafe. */
val DarkCoffee = CoffeeColors(
    bg = Color(0xFF0D0B09),
    surface1 = Color(0xFF1B1815),
    surface2 = Color(0xFF25211D),
    surface3 = Color(0xFF312C27),
    bar = Color(0xF01C1916),
    barBorder = Color.Transparent,
    hairline = Color(0xFF2C2723),
    hairlineOnBg = Color(0xFF231F1B),
    outlineStrong = Color(0xFF4A423A),
    inkHigh = Color(0xFFF4EEE7),
    inkMid = Color(0xFFB0A598),
    inkLow = Color(0xFF988B7E),
    inkFaint = Color(0xFF4F463E),
    inverse = Color(0xFFF4EEE7),
    onInverse = Color(0xFF0D0B09),
    scrim = Color(0x99000000),
    crema = Color(0xFFF29B50),
    cremaStroke = Color(0xFFF29B50),
    cremaInk = Color(0xFFF29B50),
    onCrema = Color(0xFF1A1109),
    cremaContainer = Color(0xFF3D2D1E),
    ringLiquid = Color(0x33F29B50),
    good = Color(0xFF52D18A),
    goodContainer = Color(0xFF243628),
    attention = Color(0xFFF5C343),
    attentionContainer = Color(0xFF3E331C),
    fault = Color(0xFFFF6B5B),
    faultContainer = Color(0xFF3F2520),
    // 3.4:1 on surface1, against 6.3:1 for fault, the dimmest status colour here.
    neutralMark = Color(0xFF766B60),
    artShell = Color(0xFFE9E1D6),
    artWater = Color(0xD9F29B50),
    artBed = Color(0xFF5A3A22),
    isDark = true,
)

/**
 * Warm paper. good, attention and fault are a shade darker than the first draft so their
 * 10sp chip text clears 4.5:1 on their own containers (it was 3.8 to 4.4:1).
 */
val LightCoffee = CoffeeColors(
    bg = Color(0xFFF3EEE7),
    surface1 = Color(0xFFFFFDFA),
    surface2 = Color(0xFFF5F0E9),
    surface3 = Color(0xFFEAE3D9),
    bar = Color(0xF0FFFDFA),
    barBorder = Color(0xFFE0D8CD),
    hairline = Color(0xFFECE6DD),
    hairlineOnBg = Color(0xFFE0D8CD),
    outlineStrong = Color(0xFFCDC4B8),
    inkHigh = Color(0xFF1B1611),
    inkMid = Color(0xFF5C5248),
    inkLow = Color(0xFF6F655B),
    inkFaint = Color(0xFFCDC4B8),
    inverse = Color(0xFF1B1611),
    onInverse = Color(0xFFFFFDFA),
    scrim = Color(0x521B1611),
    crema = Color(0xFFB85A14),
    cremaStroke = Color(0xFFD9782F),
    // Live words on the Bloom and Pouring washes: crema itself is only 3.4 to 4.0:1 there.
    cremaInk = Color(0xFF8F430C),
    onCrema = Color(0xFFFFFFFF),
    cremaContainer = Color(0xFFF8EDE2),
    ringLiquid = Color(0x40D9782F),
    good = Color(0xFF1A7A45),
    goodContainer = Color(0xFFE6F2E9),
    attention = Color(0xFF8C6000),
    attentionContainer = Color(0xFFFBF0D2),
    fault = Color(0xFFC0352A),
    faultContainer = Color(0xFFF9E7E3),
    // 3.1:1 on surface1, against 5.3:1 and up for the status colours: inkLow matched them
    // and, being grey, read as the heaviest bars on the chart.
    neutralMark = Color(0xFF9C9082),
    artShell = Color(0xFFE3D9CC),
    artWater = Color(0xD9D9782F),
    artBed = Color(0xFF6B4428),
    isDark = false,
)

/** The two blob colours and the base of the brew screen's mesh, for one phase in one mode. */
@Immutable
data class MoodColors(val a: Color, val b: Color, val base: Color)

/** Background wash keyed to where you are in a brew. Drives the mesh gradient. */
enum class BrewMood {
    Idle,
    Bloom,
    Pouring,
    Steeping,
    Drawdown,
    Done;

    /** Looked up from prebuilt tables, so the mesh can ask every frame without allocating. */
    fun colors(dark: Boolean): MoodColors = (if (dark) DarkMoods else LightMoods)[ordinal]
}

// Same order as the BrewMood entries.
private val DarkMoods = listOf(
    MoodColors(Color(0xFF1F1813), Color(0xFF2A1F17), Color(0xFF0D0B09)),
    MoodColors(Color(0xFF3A2412), Color(0xFF5C3617), Color(0xFF0D0B09)),
    MoodColors(Color(0xFF4A2C12), Color(0xFF70401A), Color(0xFF0D0B09)),
    MoodColors(Color(0xFF2A2217), Color(0xFF3A2E1C), Color(0xFF0D0B09)),
    MoodColors(Color(0xFF1E1812), Color(0xFF2A2019), Color(0xFF0D0B09)),
    MoodColors(Color(0xFF14301F), Color(0xFF1F4A30), Color(0xFF0D0B09)),
)

private val LightMoods = listOf(
    MoodColors(Color(0xFFEEE5D9), Color(0xFFF6EFE6), Color(0xFFF3EEE7)),
    MoodColors(Color(0xFFF5DEC5), Color(0xFFFBE9D6), Color(0xFFF3EEE7)),
    MoodColors(Color(0xFFF7D5B3), Color(0xFFFCE4CB), Color(0xFFF3EEE7)),
    MoodColors(Color(0xFFECE2D1), Color(0xFFF4ECDF), Color(0xFFF3EEE7)),
    MoodColors(Color(0xFFEAE2D7), Color(0xFFF1EBE3), Color(0xFFF3EEE7)),
    MoodColors(Color(0xFFD9EEDF), Color(0xFFEAF6EE), Color(0xFFF3EEE7)),
)
