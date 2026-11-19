package com.laksh.coffee.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** "18" not "18.0", but "18.5" stays "18.5". Used everywhere grams are shown. */
fun grams(v: Double): String =
    if (abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString()
    else ((v * 10).roundToInt() / 10.0).toString()

fun gramsOrDash(v: Double?): String = v?.let { grams(it) } ?: "—"

/** "3:05". Built directly: it runs per row and per card, and String.format parses its pattern every call. */
fun clock(totalSec: Int): String {
    if (totalSec < 0) return "%d:%02d".format(totalSec / 60, totalSec % 60)
    val s = totalSec % 60
    return (totalSec / 60).toString() + (if (s < 10) ":0" else ":") + s
}

/**
 * One decimal place, pinned to Locale.US. These are measurements, and a ratio rendered
 * as "1:16,7" in a comma-decimal locale reads as a different number.
 */
fun oneDp(v: Double): String = String.format(Locale.US, "%.1f", v)

/** "day" beside a 1, "days" beside anything else: for a count shown as its own numeral or typed into a field. */
fun unitWord(n: Int, one: String, many: String = one + "s"): String = if (n == 1) one else many

/** "1 brew", "4 brews", "0 brews". */
fun counted(n: Int, one: String, many: String = one + "s"): String = "$n " + unitWord(n, one, many)

/**
 * Free text for a short line: whole words up to [max] characters, then "…", so a typed grind
 * reads "about 10…" and never "about 10 secon". One long word is still cut where it has to be.
 * Also trims, so a value saved with a trailing space never leaves a gap in a sentence.
 */
fun clipWords(text: String, max: Int): String {
    val t = text.trim()
    if (max <= 0 || t.length <= max) return t
    // One character past the limit, so a word that ends exactly at the limit is kept whole.
    val cut = t.take(max + 1).lastIndexOf(' ')
    val head = if (cut > 0) t.take(cut) else t.take(max)
    return head.trimEnd(' ', ',', ';', ':', '·', '(', '–', '-') + "…"
}

fun ratio(dose: Double, water: Double): String =
    if (dose > 0) "1:" + oneDp(water / dose) else "—"

private val DAY_FMT = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

fun dayLabel(epochMs: Long): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DAY_FMT.format(Instant.ofEpochMilli(epochMs))
    }
}

fun timeLabel(epochMs: Long): String = TIME_FMT.format(Instant.ofEpochMilli(epochMs))

fun greeting(): String {
    val hour = java.time.LocalTime.now().hour
    return when (hour) {
        in 0..4 -> "Still up"
        in 5..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..21 -> "Evening"
        else -> "Late one"
    }
}

fun daysOffRoastLabel(days: Long?): String = when {
    days == null -> "—"
    days <= 0L -> "Roasted today"
    days == 1L -> "1 day off roast"
    else -> "$days days off roast"
}
