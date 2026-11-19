package com.laksh.coffee.domain.ai

import com.laksh.coffee.core.grams
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.TasteProfile
import com.laksh.coffee.domain.asClock
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.domain.grind.GrindScale
import com.laksh.coffee.domain.grind.MoveSizes
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class FlagSeverity { Distrust, Note }

@Serializable
enum class FlagCode {
    GRIND_ZERO,
    /** 1.x rows at up to 40 clicks a turn: under one turn on a filter brew. Name kept: it is stored in flagsJson. */
    GRIND_UNDER_ONE_TURN,
    /** Finer than the catalog's espresso range on a filter brew. */
    GRIND_TOO_FINE_FOR_METHOD,
    GRIND_JUMP,
    GRIND_MISSING,
    BEVERAGE_OVER_WATER,
    RETENTION_LOW,
    RETENTION_HIGH,
    EY_IMPLAUSIBLE,
    DRAWDOWN_GE_TOTAL,
    DRAWDOWN_AFTER_END,
    DRAWDOWN_ZERO,
    TASTE_UNTOUCHED,
    TASTE_FAULT_CONFLICT,
    OVERALL_UNRATED,
    ROAST_DATE_MISSING,
    BEAN_MISSING,
    TIME_IMPLAUSIBLE,
    TEMP_IMPLAUSIBLE,
    RATIO_IMPLAUSIBLE,
}

/**
 * One finding about one brew. [message] is the user-facing copy and may quote the suspect
 * value or a likely correction. [modelMessage], when set, is the same finding without them:
 * the model is told a distrusted value is unusable, never what it was or might have been.
 */
@Serializable
data class DataFlag(
    val brewId: Long,
    val code: FlagCode,
    val field: String,
    val severity: FlagSeverity,
    val message: String,
    val modelMessage: String? = null,
) {
    // "this." is required: inside a getter a bare `field` means the backing field.
    val fieldLabel: String get() = Fields.label(this.field)

    /** The text the prompt sends. */
    val promptMessage: String get() = modelMessage ?: message
}

object Fields {
    const val GRIND = "grindClicks"
    const val BEVERAGE = "beverageG"
    const val EY = "extractionYield"
    const val DRAWDOWN = "drawdownSec"
    const val TASTE = "taste"
    const val OVERALL = "overall"
    const val ROAST = "daysOffRoast"
    const val BEAN = "bean"
    const val TIME = "totalTimeSec"
    const val TEMP = "tempC"
    const val RATIO = "doseWater"

    fun label(field: String): String = when (field) {
        GRIND -> "Grind"
        BEVERAGE -> "In the cup"
        EY -> "Extraction"
        DRAWDOWN -> "Drawdown"
        TASTE -> "Taste sliders"
        OVERALL -> "Overall"
        ROAST -> "Roast date"
        BEAN -> "Coffee"
        TIME -> "Total time"
        TEMP -> "Temperature"
        RATIO -> "Dose / water"
        else -> field
    }
}

/**
 * Free, offline checks for values no real brew can have. Distrust flags make the prompt send
 * the field (and everything worked out from it) as null; Note flags are context only. The same
 * flags drive the Log check card and the Review screen's field warnings.
 */
object DataQuality {
    const val RETENTION_LOW_G_PER_G = 1.0
    const val RETENTION_HIGH_G_PER_G = 3.3
    const val EY_MIN = 14.0
    const val EY_MAX = 26.0
    const val DRAWDOWN_SLACK_SEC = 20
    const val FILTER_RATIO_MIN = 8.0
    const val FILTER_RATIO_MAX = 25.0

    /** beans.json roast level ids where the first weeks off roast change a lot. */
    private val LIGHT_ROASTS = setOf("extra-light", "light", "medium-light")

    fun check(
        log: BrewLogEntity,
        recipe: Recipe?,
        grinder: GrinderEntity?,
        bean: BeanEntity?,
        peers: List<BrewLogEntity> = emptyList(),
        scale: GrindScale? = grinder?.scale(),
    ): List<DataFlag> {
        val flags = mutableListOf<DataFlag>()
        fun add(code: FlagCode, field: String, severity: FlagSeverity, message: String, modelMessage: String? = null) {
            flags += DataFlag(log.id, code, field, severity, message, modelMessage)
        }
        val distrust = FlagSeverity.Distrust
        val note = FlagSeverity.Note

        val brewer = brewerOf(log, recipe)
        val method = brewer?.method
        val filter = isFilterStyle(brewer)
        val cpr = grinder?.clicksPerRotation ?: 0
        val moves = scale?.moves(method)
        val total = log.totalTimeSec

        // ---- grind
        val clicks = log.grindClicks
        var grindTrusted = true
        if (clicks == 0) {
            grindTrusted = false
            val runFor = if (total > 0) " for ${total.asClock()}" else ""
            add(
                FlagCode.GRIND_ZERO, Fields.GRIND, distrust,
                "Grind logged as 0 clicks, which is burrs touching. Nothing brews$runFor at 0, so the grind " +
                    "was not recorded.",
                "Grind was logged as zero, which is burrs touching and cannot brew anything, so the grind " +
                    "was not recorded.",
            )
        } else if (clicks != null && filter && underOneLegacyTurn(clicks, scale, method)) {
            grindTrusted = false
            add(
                FlagCode.GRIND_UNDER_ONE_TURN, Fields.GRIND, distrust,
                "$clicks clicks is less than one full turn, finer than espresso. For a filter brew that is " +
                    "almost certainly the dial reading rather than total clicks; $clicks on the dial would be " +
                    "${clicks * cpr} clicks.",
                // No corrected number: the model must not be handed a guess to reason from.
                "The grind is logged as less than one full turn, finer than espresso, which can't be right " +
                    "for a filter brew. It was probably the dial reading rather than total clicks.",
            )
        } else if (clicks != null && filter && scale != null && finerThanEspresso(clicks, scale)) {
            grindTrusted = false
            val range = scale.rangeFor(BrewMethod.Espresso)!!
            val who = scale.rangeSources.firstOrNull()?.who?.let { ", $it" }.orEmpty()
            val stored = if (scale.dial is DialFormat.NumberTick) " On this grinder the dial reading 7.5 is stored as 75." else ""
            add(
                FlagCode.GRIND_TOO_FINE_FOR_METHOD, Fields.GRIND, distrust,
                "$clicks is finer than this grinder's espresso starting range (${scale.format(range.from)}–" +
                    "${scale.format(range.to)}$who). For a filter brew that is almost certainly the dial reading " +
                    "typed the wrong way.$stored",
                "The grind is logged finer than this grinder's espresso starting range, which can't be right for " +
                    "a filter brew. It was probably the dial reading rather than total clicks.",
            )
        }
        if (clicks != null && clicks > 0 && grindTrusted) {
            val usual = peers.filter {
                it.id != log.id &&
                    it.recipeId == log.recipeId &&
                    it.beanId == log.beanId &&
                    it.grinderId == log.grinderId &&
                    (it.grindClicks ?: 0) > 0
            }.mapNotNull { it.grindClicks }
            if (usual.isNotEmpty()) {
                val median = median(usual)
                val away = abs(clicks - median)
                val limit = moves?.jumpLimit ?: if (cpr > 0) 2.0 * cpr else 20.0
                if (away >= limit) {
                    add(
                        FlagCode.GRIND_JUMP, Fields.GRIND, note,
                        "$clicks clicks is ${grams(away)} away from your usual ${grams(median)} for this coffee " +
                            "and recipe. If that wasn't deliberate, it was probably mistyped.",
                    )
                }
            }
        }
        if (clicks == null && log.grindSetting.isBlank()) {
            add(FlagCode.GRIND_MISSING, Fields.GRIND, note, "No grind setting recorded.")
        }

        // ---- dose and water
        val dose = log.doseG
        val water = log.waterG
        var ratioTrusted = true
        if (dose <= 0.0 || water <= 0.0) {
            ratioTrusted = false
            add(FlagCode.RATIO_IMPLAUSIBLE, Fields.RATIO, distrust, "Dose or water is missing.")
        } else if (filter) {
            val r = water / dose
            if (r < FILTER_RATIO_MIN || r > FILTER_RATIO_MAX) {
                ratioTrusted = false
                add(
                    FlagCode.RATIO_IMPLAUSIBLE, Fields.RATIO, distrust,
                    "${grams(dose)} g to ${grams(water)} g is 1:${oneDp(r)}, far outside any filter recipe; " +
                        "dose or water is probably mistyped.",
                    "Dose and water give a ratio far outside any filter recipe; one of them is probably mistyped.",
                )
            }
        }

        // ---- temperature
        if (method != BrewMethod.ColdBrew && (log.tempC < 60 || log.tempC > 100)) {
            add(
                FlagCode.TEMP_IMPLAUSIBLE, Fields.TEMP, distrust,
                "${log.tempC} °C can't be right for a hot brew.",
                "The logged water temperature can't be right for a hot brew.",
            )
        }

        // ---- total time (checked before drawdown, which leans on it)
        var timeTrusted = true
        if (total <= 0) {
            timeTrusted = false
            add(FlagCode.TIME_IMPLAUSIBLE, Fields.TIME, distrust, "No total time recorded.")
        } else if (recipe != null && recipe.totalSec > 0 &&
            (total < 0.4 * recipe.totalSec || total > 2.5 * recipe.totalSec)
        ) {
            timeTrusted = false
            add(
                FlagCode.TIME_IMPLAUSIBLE, Fields.TIME, distrust,
                "Total time ${total.asClock()} against the recipe's ${recipe.totalSec.asClock()}: the timer was " +
                    "probably stopped early or left running.",
                "The total time is too far from the recipe's ${recipe.totalSec.asClock()} to be real: the timer " +
                    "was probably stopped early or left running.",
            )
        }

        // ---- drawdown
        val drawdown = log.drawdownSec
        val lastPourEnd = lastPourEndSec(recipe)
        if (drawdown != null) {
            if (total > 0 && drawdown >= total) {
                add(
                    FlagCode.DRAWDOWN_GE_TOTAL, Fields.DRAWDOWN, distrust,
                    "Drawdown $drawdown s is as long as the whole brew (${total.asClock()}). The total time was " +
                        "probably typed into the drawdown box.",
                    "Drawdown is as long as the whole brew, so the total time was probably typed into the " +
                        "drawdown box.",
                )
            } else if (lastPourEnd != null && timeTrusted &&
                drawdown > (total - lastPourEnd) + DRAWDOWN_SLACK_SEC
            ) {
                val max = (total - lastPourEnd).coerceAtLeast(0)
                add(
                    FlagCode.DRAWDOWN_AFTER_END, Fields.DRAWDOWN, distrust,
                    "Drawdown $drawdown s doesn't fit: the last pour ends at ${lastPourEnd.asClock()} and the brew " +
                        "stopped at ${total.asClock()}, which leaves at most $max s.",
                    "Drawdown doesn't fit: the last pour ends at ${lastPourEnd.asClock()} and the brew stopped at " +
                        "${total.asClock()}, which leaves at most $max s.",
                )
            } else if (drawdown == 0) {
                add(
                    FlagCode.DRAWDOWN_ZERO, Fields.DRAWDOWN, distrust,
                    "Drawdown 0 s: the bed-exposed button was tapped before the last pour finished.",
                    "Drawdown was logged as zero: the bed-exposed button was tapped before the last pour finished.",
                )
            }
        }

        // ---- cup weight and extraction
        val bev = log.beverageG?.takeIf { it > 0.0 }
        var beverageTrusted = true
        if (bev != null && filter && ratioTrusted) {
            val retained = retainedPerGram(log)
            if (bev >= water) {
                beverageTrusted = false
                add(
                    FlagCode.BEVERAGE_OVER_WATER, Fields.BEVERAGE, distrust,
                    "${grams(bev)} g in the cup from ${grams(water)} g of water is more than went in; the carafe " +
                        "or brewer was probably still on the scale.",
                    "The cup weight is more than the water that went in; the carafe or brewer was probably " +
                        "still on the scale.",
                )
            } else if (retained != null && retained < RETENTION_LOW_G_PER_G) {
                beverageTrusted = false
                add(
                    FlagCode.RETENTION_LOW, Fields.BEVERAGE, distrust,
                    "${grams(bev)} g in the cup leaves only ${grams(water - bev)} g in ${grams(dose)} g of coffee " +
                        "(${oneDp(retained)} g per g). Wet grounds hold about 2 g per g, so the cup weight is wrong.",
                    "The cup weight leaves far less water in the grounds than wet coffee holds (about 2 g per g), " +
                        "so the cup weight is wrong.",
                )
            } else if (retained != null && retained > RETENTION_HIGH_G_PER_G) {
                beverageTrusted = false
                add(
                    FlagCode.RETENTION_HIGH, Fields.BEVERAGE, distrust,
                    "${grams(bev)} g in the cup means ${grams(water - bev)} g stayed in ${grams(dose)} g of coffee " +
                        "(${oneDp(retained)} g per g). Wet grounds hold about 2 g per g, so the cup was probably " +
                        "weighed early or some was poured off.",
                    "The cup weight means far more water stayed in the grounds than wet coffee holds (about 2 g " +
                        "per g), so the cup was probably weighed early or some was poured off.",
                )
            }
        }
        val ey = log.extractionYield
        if (ey != null) {
            if (!beverageTrusted) {
                add(
                    FlagCode.EY_IMPLAUSIBLE, Fields.EY, distrust,
                    "Extraction yield ${oneDp(ey)}% was worked out from a cup weight that looks wrong.",
                    "The extraction yield was worked out from a cup weight that looks wrong.",
                )
            } else if (ey < EY_MIN || ey > EY_MAX) {
                val kind = if (filter) "any filter brew" else "any brew"
                add(
                    FlagCode.EY_IMPLAUSIBLE, Fields.EY, distrust,
                    "Extraction yield ${oneDp(ey)}% is outside the 14-26% $kind reaches.",
                    "The extraction yield is outside the 14-26% $kind reaches.",
                )
            }
        }

        // ---- taste and rating
        val taste = log.taste
        if (taste == TasteProfile()) {
            add(
                FlagCode.TASTE_UNTOUCHED, Fields.TASTE, distrust,
                "All seven taste sliders are still on the default 5, so they were never moved. The faults, " +
                    "overall score and notes are the real taste record.",
            )
        } else {
            if ("Sour" in log.faults && taste.acidity <= 3) {
                add(
                    FlagCode.TASTE_FAULT_CONFLICT, Fields.TASTE, note,
                    "Marked Sour but acidity is only ${taste.acidity}/10; the chip and the slider disagree.",
                )
            }
            if ("Bitter" in log.faults && taste.bitterness <= 2) {
                add(
                    FlagCode.TASTE_FAULT_CONFLICT, Fields.TASTE, note,
                    "Marked Bitter but bitterness is only ${taste.bitterness}/10; the chip and the slider disagree.",
                )
            }
        }
        if (log.overall == 0) {
            add(
                FlagCode.OVERALL_UNRATED, Fields.OVERALL, note,
                "No overall score, so this brew is left out of score comparisons.",
            )
        }

        // ---- coffee
        val beanRecorded = log.beanId != null || log.beanLabel.isNotBlank()
        if (beanRecorded && log.daysOffRoast == null) {
            // Trimmed: a name typed with a trailing space would read "Weather , so".
            val name = (bean?.display ?: log.beanLabel).trim().ifBlank { "this coffee" }
            val light = if (bean != null && bean.roastLevelId in LIGHT_ROASTS) {
                ", and on a light roast the first three weeks change a lot"
            } else {
                ""
            }
            add(
                FlagCode.ROAST_DATE_MISSING, Fields.ROAST, note,
                "No roast date for $name, so freshness can't be judged$light.",
            )
        }
        if (!beanRecorded) {
            add(FlagCode.BEAN_MISSING, Fields.BEAN, note, "No coffee recorded for this brew.")
        }

        return flags
    }

    fun distrustedFields(flags: List<DataFlag>, brewId: Long): Set<String> =
        flags.filter { it.brewId == brewId && it.severity == FlagSeverity.Distrust }.map { it.field }.toSet()

    fun brewerOf(log: BrewLogEntity, recipe: Recipe?): Brewer? =
        recipe?.brewer ?: Brewers.byId(log.brewerId) ?: Brewers.resolveOrNull(log.brewer)

    /** Anything that runs water through a bed. An unknown brewer is treated as filter. */
    fun isFilterStyle(brewer: Brewer?): Boolean = brewer == null || brewer.method.isFilterStyle

    /**
     * True when [clicks] on a filter brew looks like a dial reading typed in place of total
     * clicks: the two grind rules [check] distrusts. Brew setup uses it to skip such logs.
     */
    fun grindLooksTyped(clicks: Int, scale: GrindScale?, method: BrewMethod?): Boolean {
        if (method != null && !method.isFilterStyle) return false
        return underOneLegacyTurn(clicks, scale, method) || (scale != null && finerThanEspresso(clicks, scale))
    }

    // 1.x rule, only for rows whose moves are still measured in turns of at most 40 clicks.
    private fun underOneLegacyTurn(clicks: Int, scale: GrindScale?, method: BrewMethod?): Boolean {
        val s = scale ?: return false
        val cpr = s.clicksPerRotation ?: return false
        return s.usesClicks && s.moves(method).basis == MoveSizes.Basis.LegacyTurn && clicks in 1 until cpr
    }

    private fun finerThanEspresso(clicks: Int, scale: GrindScale): Boolean {
        val floor = scale.espressoFloor ?: return false
        return scale.usesClicks && clicks > 0 && clicks < floor
    }

    /** When the last pour ends: the same rule BrewViewModel uses to time the drawdown. */
    fun lastPourEndSec(recipe: Recipe?): Int? =
        recipe?.steps?.filter { it.targetWaterG != null }?.maxOfOrNull { it.endSec }

    /** Grams of water left in each gram of spent coffee. About 2 is normal. */
    fun retainedPerGram(log: BrewLogEntity): Double? {
        val bev = log.beverageG ?: return null
        if (log.doseG <= 0.0) return null
        return (log.waterG - bev) / log.doseG
    }

    /**
     * The newest earlier brew whose grind can anchor a click change: same grinder AND same
     * recipe (the same coffee preferred), with a grind that is not itself distrusted. Clicks on
     * another recipe are a different starting point, so none of those qualify: null makes the
     * model give a relative change and the hero show only the target.
     */
    fun lastTrustedClicks(
        target: BrewLogEntity,
        history: List<BrewLogEntity>,
        flagsById: Map<Long, List<DataFlag>>,
    ): BrewLogEntity? {
        val grinderId = target.grinderId ?: return null
        val candidates = history.filter { h ->
            h.id != target.id &&
                h.grinderId == grinderId &&
                sameRecipe(h, target) &&
                (h.grindClicks ?: 0) > 0 &&
                Fields.GRIND !in distrustedFields(flagsById[h.id].orEmpty(), h.id)
        }
        return candidates.firstOrNull { it.beanId == target.beanId } ?: candidates.firstOrNull()
    }

    /** [lastTrustedClicks] for numbered and stepless dials: the anchor is the logged dial number. */
    fun lastTrustedValue(
        target: BrewLogEntity,
        history: List<BrewLogEntity>,
        flagsById: Map<Long, List<DataFlag>>,
    ): BrewLogEntity? {
        val grinderId = target.grinderId ?: return null
        val candidates = history.filter { h ->
            h.id != target.id &&
                h.grinderId == grinderId &&
                sameRecipe(h, target) &&
                (h.grindValue ?: 0.0) > 0.0 &&
                Fields.GRIND !in distrustedFields(flagsById[h.id].orEmpty(), h.id)
        }
        return candidates.firstOrNull { it.beanId == target.beanId } ?: candidates.firstOrNull()
    }

    private fun sameRecipe(a: BrewLogEntity, b: BrewLogEntity): Boolean =
        if (b.recipeId.isNotBlank()) a.recipeId == b.recipeId else a.recipeId.isBlank() && a.recipeName == b.recipeName

    private fun median(values: List<Int>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
