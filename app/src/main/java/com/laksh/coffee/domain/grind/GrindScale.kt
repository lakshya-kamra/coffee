package com.laksh.coffee.domain.grind

import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogGrinder
import com.laksh.coffee.domain.catalog.GrindRange
import com.laksh.coffee.domain.catalog.SourceRef
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * How big a grind move is on one grinder. App heuristic, not attributed to anyone: the numbers
 * below are the app's own rule of thumb (android-spec §3.3).
 */
data class MoveSizes(
    val nudge: Double,
    val clearMiss: Double,
    val maxAtOnce: Double,
    val jumpLimit: Double,
    val basis: Basis,
) {
    enum class Basis { Microns, Range, LegacyTurn, Generic }
}

/**
 * Everything the app knows about one grinder's scale: the user's row first (it is what they saw
 * and accepted), the catalog for ranges, sources and help text.
 *
 * The stored grind is always the number in the grinder's own unit: total clicks from zero for
 * click grinders (a K-Ultra dial reading of 7.5 is stored as 75), the printed dial number for
 * numbered and stepless dials.
 */
data class GrindScale(
    val style: SettingStyle,
    val unit: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val lowerIsFiner: Boolean,
    val clicksPerRotation: Int?,
    val micronsPerStep: Double?,
    val dial: DialFormat,
    val ranges: Map<BrewMethod, GrindRange>,
    val rangeSources: List<SourceRef>,
    val confidence: String?,
    val dialReadingHelp: String,
    val entryHint: String,
) {
    val usesClicks: Boolean get() = style == SettingStyle.Clicks

    /** The words the prompt uses. */
    val finerDirection: String
        get() = when {
            usesClicks && lowerIsFiner -> "FEWER clicks is finer"
            usesClicks -> "MORE clicks is finer"
            lowerIsFiner -> "LOWER settings are finer"
            else -> "HIGHER settings are finer"
        }

    /** The same, as the UI says it: "fewer clicks is finer". */
    val finerWords: String get() = finerDirection.lowercase(Locale.US)

    /** Dial words for a stored value: null for a plain dial (the number already is the reading). */
    fun reading(value: Double, inTurns: Boolean = false): String? =
        if (value < 0 || !value.isFinite()) null else dial.read(value.roundToInt(), inTurns)

    /**
     * The caption under the big number, e.g. "dial 7.5 · burr travel ≈ 1.5 mm". "" when there is
     * nothing to add.
     *
     * Clicks x microns per click is how far the burrs have moved from zero, so it is named as
     * burr travel and given in mm: a bare "≈ 1,500 µm" reads as particle size, which it is not.
     * [travel] false leaves it out where the line has no room for it.
     */
    fun describe(value: Double?, travel: Boolean = true): String {
        if (value == null) return ""
        val parts = buildList {
            val r = reading(value)
            when (dial) {
                DialFormat.Plain -> Unit
                is DialFormat.TurnsAndClicks -> r?.let(::add)
                is DialFormat.TurnsDotClicks -> {
                    // Exactly the 1.x caption: "7 turns + 5 · dial 7.5".
                    val v = value.roundToInt()
                    val cpt = (dial as DialFormat.TurnsDotClicks).clicksPerTurn
                    add(DialFormat.turnsPlus(v / cpt, (v % cpt).toString()))
                    r?.let { add("dial $it") }
                }
                is DialFormat.NumberTick -> r?.let { add("dial $it") }
            }
            micronsPerStep?.takeIf { travel && it > 0 && usesClicks }?.let { um ->
                add("burr travel ≈ ${millimetres(um * value)} mm")
            }
        }
        if (parts.isEmpty()) return if (usesClicks) "clicks from zero" else ""
        return parts.joinToString(" · ")
    }

    /** Inverse of [reading]: the stored value for a dial reading, or null when impossible. */
    fun fromReading(r: DialReading): Double? = when (val d = dial) {
        DialFormat.Plain -> r.value
        is DialFormat.TurnsAndClicks ->
            if (r.tick < 0 || r.turns < 0 || r.tick >= d.clicksPerTurn) null else (r.turns * d.clicksPerTurn + r.tick).toDouble()
        is DialFormat.TurnsDotClicks ->
            if (r.tick < 0 || r.turns < 0 || r.tick >= d.clicksPerTurn) null else (r.turns * d.clicksPerTurn + r.tick).toDouble()
        is DialFormat.NumberTick -> {
            val tpn = d.ticksPerNumber
            val cpr = d.clicksPerRotation
            when {
                r.turns < 0 || r.number < 0 || r.tick < 0 -> null
                r.tick >= tpn -> null
                // "10.0" on a 100-click ring: exactly one full turn, the way [DialFormat.read] writes it.
                cpr > 0 && r.turns == 0 && r.tick == 0 && r.number * tpn == cpr -> cpr.toDouble()
                cpr > 0 && r.number * tpn >= cpr -> null
                cpr <= 0 && r.turns > 0 -> null
                else -> (r.turns * cpr + r.number * tpn + r.tick).toDouble()
            }
        }
    }

    /** "7.5", "1+2.5", "3 + 2" in this dial's terms → the stored value. */
    fun parseReading(text: String): Double? = when (dial) {
        DialFormat.Plain -> text.trim().replace(',', '.').toDoubleOrNull()
        is DialFormat.TurnsAndClicks, is DialFormat.TurnsDotClicks -> TURNS_RE.matchEntire(text)?.let { m ->
            fromReading(DialReading(turns = m.groupValues[1].toInt(), tick = m.groupValues[2].toInt()))
        }
        is DialFormat.NumberTick -> NUMBER_RE.matchEntire(text)?.let { m ->
            fromReading(
                DialReading(
                    turns = m.groupValues[1].ifEmpty { "0" }.toInt(),
                    number = m.groupValues[2].toInt(),
                    tick = m.groupValues[3].ifEmpty { "0" }.toInt(),
                ),
            )
        }
    }

    /** "75" for clicks, "14.5" or "18" for dials. */
    fun format(value: Double): String {
        if (usesClicks) return value.roundToLong().toString()
        val r = (value * 100).roundToLong() / 100.0
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }

    fun rangeFor(method: BrewMethod?): GrindRange? = method?.let { ranges[it] }

    /** Where this grinder's espresso range starts: anything finer is no filter grind. */
    val espressoFloor: Double? get() = ranges[BrewMethod.Espresso]?.from

    /** Move sizes for [method]; see [MoveSizes]. All values are whole steps, at least one step. */
    fun moves(method: BrewMethod?): MoveSizes {
        val s = step.takeIf { it > 0 } ?: 1.0
        fun r(v: Double): Double = max(s, (v / s).roundToLong() * s)
        val um = micronsPerStep?.takeIf { it > 0 }
        val range = rangeFor(method)?.takeIf { it.to > it.from }
        val cpr = clicksPerRotation ?: 0
        return when {
            um != null -> {
                val nudge = r(40 / um)
                val clear = r(max(2 * nudge, 120 / um))
                MoveSizes(nudge, clear, r(max(clear, 250 / um)), r(500 / um), MoveSizes.Basis.Microns)
            }
            range != null -> {
                val w = range.to - range.from
                MoveSizes(r(0.05 * w), r(0.15 * w), r(0.3 * w), r(0.5 * w), MoveSizes.Basis.Range)
            }
            // 1.x: "a nudge is 2-5 clicks, a clear miss is one full turn, never more than two turns".
            usesClicks && cpr in 1..40 ->
                MoveSizes(2.0, cpr.toDouble(), 2.0 * cpr, 2.0 * cpr, MoveSizes.Basis.LegacyTurn)
            else -> MoveSizes(r(2 * s), r(5 * s), r(10 * s), r(20 * s), MoveSizes.Basis.Generic)
        }
    }

    /** True when a turn is a real unit on this dial, so "one full turn" is something a person can do. */
    val talksInTurns: Boolean get() = dial.talksInTurns

    companion object {
        private val TURNS_RE = Regex("""^\s*(\d+)\s*[+.,]\s*(\d+)\s*$""")
        private val NUMBER_RE = Regex("""^\s*(?:(\d+)\s*\+\s*)?(\d+)(?:[.,](\d))?\s*$""")

        /** Microns as millimetres, two decimals at most: 1540 reads "1.54", 1500 "1.5", 300 "0.3". */
        fun millimetres(microns: Double): String =
            String.format(Locale.US, "%.2f", microns / 1000.0).trimEnd('0').trimEnd('.')

        /**
         * Resolution order for the dial words (first match wins): the row's own choice; the
         * catalog's dialFormat when the row's clicks per turn agree with it; a known numbered
         * dial; the 1.x reading for rows saved at 10 a turn (kept until the user accepts the
         * catalog's numbers); turns + clicks; plain.
         */
        fun of(g: GrinderEntity, catalog: CatalogGrinder? = Catalog.grinder(g.catalogId)): GrindScale {
            val clicks = g.settingStyle == SettingStyle.Clicks
            val cpr = g.clicksPerRotation.takeIf { it > 0 }
            val catCpr = catalog?.setting?.clicksPerRotation
            // A row whose turn size disagrees with its catalog entry has a scale notice pending:
            // nothing numeric from the catalog applies until the user accepts its numbers.
            val agrees = catalog != null && (catCpr == null || catCpr == g.clicksPerRotation)

            val dial: DialFormat = run {
                DialFormat.decode(g.dialFormat, cpr)?.let { return@run it }
                if (agrees) DialFormat.decode(catalog?.setting?.dialFormat, cpr)?.let { return@run it }
                if (catalog != null && catalog.id in NumberedDials.IDS && catCpr != null && catCpr == g.clicksPerRotation) {
                    return@run DialFormat.NumberTick(catCpr, 10)
                }
                if (clicks && g.clicksPerRotation == 10 && (catalog == null || catCpr != 10)) {
                    return@run DialFormat.TurnsDotClicks(10)
                }
                if (clicks && cpr != null) return@run DialFormat.TurnsAndClicks(cpr)
                DialFormat.Plain
            }.let { if (!clicks && it !is DialFormat.Plain) DialFormat.Plain else it }

            val ranges = if (agrees) {
                catalog!!.ranges.mapNotNull { (k, v) -> BrewMethod.ofOrNull(k)?.let { it to v } }.toMap()
            } else {
                emptyMap()
            }
            val unit = when (g.settingStyle) {
                SettingStyle.Clicks -> "clicks"
                SettingStyle.Microns -> "µm"
                else -> catalog?.setting?.unit?.takeIf { agrees && it != "clicks" }.orEmpty()
            }
            return GrindScale(
                style = g.settingStyle,
                unit = unit,
                min = g.minSetting,
                max = g.maxSetting,
                step = g.step.takeIf { it > 0 } ?: 1.0,
                lowerIsFiner = g.lowerIsFiner,
                clicksPerRotation = cpr,
                micronsPerStep = g.micronsPerClick?.takeIf { it > 0 },
                dial = dial,
                ranges = ranges,
                rangeSources = if (agrees) catalog!!.rangeSources else emptyList(),
                confidence = catalog?.confidence?.takeIf { agrees },
                // The catalog's words describe the catalog's turn ("100 clicks per full turn"). On a
                // row that still counts another turn size they would contradict the row's own scale.
                dialReadingHelp = if (agrees) catalog!!.setting.dialReading else "",
                entryHint = if (agrees) catalog!!.setting.entryHint else "",
            )
        }
    }
}
