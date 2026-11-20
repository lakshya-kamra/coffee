package com.laksh.coffee.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
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

fun greeting(): String = greeting(java.time.LocalTime.now().hour)

fun greeting(hour: Int): String {
    return when (hour) {
        in 0..4 -> "Still up"
        in 5..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..21 -> "Evening"
        else -> "Late one"
    }
}

// ------------------------------------------------------------------------ v4

/** "3:30" under an hour; "12 h 5 min" (or "12 h") from an hour up, for cold brew (v4 §4.9). */
fun formatDuration(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    if (s < 3600) return clock(s)
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (m == 0) "$h h" else "$h h $m min"
}

/** "1 part coffee to 16.7 parts water" ("…parts espresso" for espresso); "" without a dose. */
fun ratioPhrase(doseG: Double, waterG: Double, method: String = ""): String {
    if (doseG <= 0) return ""
    val r = oneDp(waterG / doseG).removeSuffix(".0")
    return "1 part coffee to $r parts ${if (method == "espresso") "espresso" else "water"}"
}

/** 6 → "6", 2.5 → "2½": spoons and mugs come in halves. */
fun halves(v: Double): String {
    val whole = v.toInt()
    return if (v - whole >= 0.5) (if (whole == 0) "½" else "$whole½") else whole.toString()
}

// Screen dates and times follow the runtime locale (v4 §4.15); 12/24 h follows the locale's
// convention. Exports and AI prompts keep the pinned dayLabel / timeLabel above.

/** "8:14 PM" in en-US, "20:14" in en-GB. */
fun screenTime(epochMs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(epochMs))

/** The locale's medium date pattern without its year: "MMM d" in en-US, "d MMM" in en-GB. */
private fun dayMonthPattern(locale: Locale): String {
    val full = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.MEDIUM, null, IsoChronology.INSTANCE, locale)
    return full.replace(Regex("[\\s,./-]*y+[\\s,./-]*"), " ").trim().trim(',', '.', '/', '-').trim()
        .ifEmpty { "d MMM" }
}

/** "Nov 19" in en-US, "19 Nov" in en-GB. */
fun screenDate(epochMs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern(dayMonthPattern(locale), locale).withZone(zone).format(Instant.ofEpochMilli(epochMs))

/** "Today", "Yesterday", else screenDate. */
fun screenDayLabel(
    epochMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> screenDate(epochMs, locale, zone)
    }
}

/** Today's eyebrow: "EVENING · THU, NOV 19" in en-US. */
fun todayEyebrow(nowMs: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(nowMs).atZone(zone)
    val date = DateTimeFormatter.ofPattern("EEE, " + dayMonthPattern(locale), locale).format(at)
    return "${greeting(at.hour).uppercase(Locale.ROOT)} · ${date.uppercase(locale)}"
}

fun daysOffRoastLabel(days: Long?): String = when {
    days == null -> "—"
    days <= 0L -> "Roasted today"
    days == 1L -> "1 day off roast"
    else -> "$days days off roast"
}
