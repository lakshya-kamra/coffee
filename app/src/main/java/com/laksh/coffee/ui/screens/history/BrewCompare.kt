package com.laksh.coffee.ui.screens.history

import com.laksh.coffee.core.formatDuration
import com.laksh.coffee.core.grams
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.core.ratio
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.LogQuery
import com.laksh.coffee.domain.MethodLabels
import com.laksh.coffee.ui.components.beanShortName
import com.laksh.coffee.ui.components.signedClock
import com.laksh.coffee.ui.theme.Units
import kotlin.math.abs
import kotlin.math.roundToInt

/** One line of the compare table: the first brew, the second, and what changed in words. */
data class CompareRow(val label: String, val a: String, val b: String, val diff: String? = null)

/**
 * Two brews side by side (v4 §3.8). The words in [CompareRow.diff] always describe the second
 * brew against the first ("finer by 5 clicks", "+0:20", "2 °C hotter", "+1"). Pure.
 */
object BrewCompare {

    /**
     * The order of the "Compare with…" picker: the same recipe first, then the same coffee, then
     * everything else, each newest first. The brew itself is never offered.
     */
    fun pickerOrder(base: BrewLogEntity, all: List<BrewLogEntity>): List<BrewLogEntity> {
        val recipe = LogQuery.recipeKey(base)
        val coffee = base.beanLabel.trim()
        fun rank(l: BrewLogEntity): Int = when {
            recipe.isNotEmpty() && LogQuery.recipeKey(l) == recipe -> 0
            coffee.isNotEmpty() && l.beanLabel.trim() == coffee -> 1
            else -> 2
        }
        return all.filter { it.id != base.id }.sortedWith(compareBy<BrewLogEntity> { rank(it) }.thenByDescending { it.startedAt })
    }

    /** The picker's group heading for a brew in [pickerOrder]. */
    fun pickerGroup(base: BrewLogEntity, l: BrewLogEntity): String = when {
        LogQuery.recipeKey(base).isNotEmpty() && LogQuery.recipeKey(l) == LogQuery.recipeKey(base) -> "Same recipe"
        base.beanLabel.isNotBlank() && l.beanLabel.trim() == base.beanLabel.trim() -> "Same coffee"
        else -> "Other brews"
    }

    /**
     * The table. [date] formats a time for the screen (locale-aware, injected so tests are fixed),
     * [plannedSec] gives a recipe's planned total, [lowerIsFiner] says which way the brew's grinder
     * turns (true for every hand grinder), [labels] names water and time for the first brew's method.
     */
    fun rows(
        a: BrewLogEntity,
        b: BrewLogEntity,
        units: Units = Units(),
        labels: MethodLabels = MethodLabels("WATER", "", "TIME", false),
        plannedSec: (BrewLogEntity) -> Int? = { null },
        lowerIsFiner: Boolean = true,
        date: (Long) -> String = { it.toString() },
    ): List<CompareRow> = buildList {
        add(CompareRow("DATE", date(a.startedAt), date(b.startedAt), dayGap(a.startedAt, b.startedAt)))
        add(
            CompareRow(
                "RECIPE",
                a.recipeName.ifBlank { "Brew" },
                b.recipeName.ifBlank { "Brew" },
                if (LogQuery.recipeKey(a) != LogQuery.recipeKey(b)) "another recipe" else null,
            ),
        )
        add(coffeeRow(a, b))
        add(grindRow(a, b, lowerIsFiner))
        add(gramsRow("DOSE", a.doseG, b.doseG))
        add(gramsRow(labels.water.ifBlank { "WATER" }, a.waterG, b.waterG))
        add(ratioRow(a, b))
        add(tempRow(a, b, units))
        add(timeRow(labels.time.ifBlank { "TIME" }, a, b, plannedSec))
        add(
            CompareRow(
                "OVERALL",
                a.overall.takeIf { it > 0 }?.let { "$it/10" } ?: "—",
                b.overall.takeIf { it > 0 }?.let { "$it/10" } ?: "—",
                if (a.overall > 0 && b.overall > 0 && a.overall != b.overall) signed(b.overall - a.overall) else null,
            ),
        )
        add(faultRow(a, b))
        add(CompareRow("NOTES", a.notes.ifBlank { "—" }, b.notes.ifBlank { "—" }))
    }

    private fun dayGap(a: Long, b: Long): String? {
        val days = ((b - a) / 86_400_000L).toInt()
        return when {
            days == 0 -> null
            days > 0 -> if (days == 1) "1 day later" else "$days days later"
            else -> if (days == -1) "1 day earlier" else "${-days} days earlier"
        }
    }

    private fun coffeeRow(a: BrewLogEntity, b: BrewLogEntity): CompareRow {
        fun text(l: BrewLogEntity): String {
            val name = beanShortName(l.beanLabel).ifBlank { l.beanLabel }.ifBlank { "—" }
            return l.daysOffRoast?.let { "$name · $it d off roast" } ?: name
        }
        val same = a.beanLabel.trim() == b.beanLabel.trim()
        val diff = when {
            !same -> "another coffee"
            a.daysOffRoast != null && b.daysOffRoast != null && a.daysOffRoast != b.daysOffRoast -> {
                val d = (b.daysOffRoast - a.daysOffRoast).toInt()
                "${if (d > 0) "+" else "−"}${abs(d)} ${if (abs(d) == 1) "day" else "days"} off roast"
            }
            else -> null
        }
        return CompareRow("COFFEE", text(a), text(b), diff)
    }

    private fun grindText(l: BrewLogEntity): String = when {
        l.grindKind == "preground" -> "As bought"
        l.grindClicks != null -> "${l.grindClicks} ${if (l.grindClicks == 1) "click" else "clicks"}"
        l.grindKind == "blade" && l.grindValue != null -> "${l.grindValue.roundToInt()} s"
        else -> l.grindSetting.ifBlank { "—" }
    }

    private fun grindRow(a: BrewLogEntity, b: BrewLogEntity, lowerIsFiner: Boolean): CompareRow {
        val sameGrinder = a.grinderId == b.grinderId && a.grinderLabel.trim() == b.grinderLabel.trim()
        val diff = when {
            a.grindKind == "preground" || b.grindKind == "preground" ->
                if (a.grindKind != b.grindKind) "another grind" else null
            !sameGrinder && (a.grinderLabel.isNotBlank() || b.grinderLabel.isNotBlank()) -> "another grinder"
            a.grindClicks != null && b.grindClicks != null && a.grindClicks != b.grindClicks -> {
                val d = b.grindClicks - a.grindClicks
                val finer = if (lowerIsFiner) d < 0 else d > 0
                "${if (finer) "finer" else "coarser"} by ${abs(d)} ${if (abs(d) == 1) "click" else "clicks"}"
            }
            a.grindKind == "blade" && b.grindKind == "blade" && a.grindValue != null && b.grindValue != null && a.grindValue != b.grindValue -> {
                val d = (b.grindValue - a.grindValue).roundToInt()
                // Longer in a blade grinder is finer.
                "${if (d > 0) "finer" else "coarser"}, ${signed(d)} s"
            }
            a.grindValue != null && b.grindValue != null && a.grindValue != b.grindValue -> {
                val d = b.grindValue - a.grindValue
                val finer = if (lowerIsFiner) d < 0 else d > 0
                "${if (finer) "finer" else "coarser"} by ${oneDp(abs(d))}"
            }
            else -> null
        }
        return CompareRow("GRIND", grindText(a), grindText(b), diff)
    }

    private fun gramsRow(label: String, a: Double, b: Double): CompareRow {
        val d = b - a
        val diff = if (a > 0 && b > 0 && abs(d) >= 0.05) {
            "${if (d > 0) "+" else "−"}${grams(abs(d))} g"
        } else {
            null
        }
        return CompareRow(label, if (a > 0) "${grams(a)} g" else "—", if (b > 0) "${grams(b)} g" else "—", diff)
    }

    private fun ratioRow(a: BrewLogEntity, b: BrewLogEntity): CompareRow {
        fun r(l: BrewLogEntity): Double? = if (l.doseG > 0 && l.waterG > 0) l.waterG / l.doseG else null
        val ra = r(a)
        val rb = r(b)
        val diff = if (ra != null && rb != null && abs(rb - ra) >= 0.05) {
            // Less water per gram is a stronger cup.
            if (rb < ra) "stronger" else "lighter"
        } else {
            null
        }
        return CompareRow(
            "RATIO",
            ra?.let { ratio(a.doseG, a.waterG) } ?: "—",
            rb?.let { ratio(b.doseG, b.waterG) } ?: "—",
            diff,
        )
    }

    private fun tempRow(a: BrewLogEntity, b: BrewLogEntity, units: Units): CompareRow {
        val diff = if (a.tempC > 0 && b.tempC > 0 && a.tempC != b.tempC) {
            val dc = b.tempC - a.tempC
            // The gap in the user's own degrees: 2 °C is about 4 °F.
            val shown = if (units.fahrenheit) (abs(dc) * 9.0 / 5.0).roundToInt() else abs(dc)
            "$shown ${units.tempUnit} ${if (dc > 0) "hotter" else "cooler"}"
        } else {
            null
        }
        return CompareRow(
            "TEMP",
            a.tempC.takeIf { it > 0 }?.let(units::tempWithUnit) ?: "—",
            b.tempC.takeIf { it > 0 }?.let(units::tempWithUnit) ?: "—",
            diff,
        )
    }

    private fun timeRow(label: String, a: BrewLogEntity, b: BrewLogEntity, plannedSec: (BrewLogEntity) -> Int?): CompareRow {
        fun text(l: BrewLogEntity): String {
            if (l.totalTimeSec <= 0) return "—"
            val plan = plannedSec(l)?.takeIf { it > 0 }
            return if (plan == null) formatDuration(l.totalTimeSec) else "${formatDuration(l.totalTimeSec)} (plan ${formatDuration(plan)})"
        }
        val diff = if (a.totalTimeSec > 0 && b.totalTimeSec > 0 && a.totalTimeSec != b.totalTimeSec) {
            signedClock(b.totalTimeSec - a.totalTimeSec)
        } else {
            null
        }
        return CompareRow("$label VS PLAN", text(a), text(b), diff)
    }

    private fun faultRow(a: BrewLogEntity, b: BrewLogEntity): CompareRow {
        val fa = a.faults.toSet()
        val fb = b.faults.toSet()
        val diff = when {
            fa == fb -> null
            fb.isEmpty() -> "no faults"
            fb.containsAll(fa) -> "more faults"
            fa.containsAll(fb) -> "fewer faults"
            else -> "other faults"
        }
        return CompareRow("FAULTS", a.faults.joinToString(", ").ifBlank { "None" }, b.faults.joinToString(", ").ifBlank { "None" }, diff)
    }

    /** "+1", "−2" (a true minus), "0". */
    fun signed(n: Int): String = when {
        n > 0 -> "+$n"
        n < 0 -> "−${-n}"
        else -> "0"
    }
}
