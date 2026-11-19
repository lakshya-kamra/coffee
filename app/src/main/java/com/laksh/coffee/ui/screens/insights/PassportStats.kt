package com.laksh.coffee.ui.screens.insights

import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.Recipe
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Lifetime totals for the Brew Passport. Every average and best ignores unrated brews. */
data class Passport(
    val brews: Int,
    val waterG: Double,
    val coffeeG: Double,
    val streak: Int,
    val bestStreak: Int,
    val avg: Double?,
    val best: Int?,
    val greatCups: Int,
    val firstAt: Long?,
    val spanDays: Int,
    /** One stamp per brewer used, most used first. */
    val stamps: List<Stamp> = emptyList(),
    /** Distinct recipes brewed at least once. */
    val recipesUsed: Int = 0,
    /** Distinct coffees brewed, by saved bean or by the label a brew kept. */
    val coffees: Int = 0,
)

/** A brewer in the passport. [brewer] is null for a label the app no longer knows. */
data class Stamp(val brewer: Brewer?, val label: String, val count: Int)

data class ScorePoint(val id: Long, val at: Long, val score: Int)

data class Ranked(val name: String, val sub: String?, val count: Int, val avg: Double?)

data class GrindPoint(val id: Long, val at: Long, val clicks: Int, val score: Int, val recipeId: String)

/**
 * One grinder's journey on ONE recipe. Clicks only mean the same thing within a recipe (a V60
 * and an AeroPress want very different grinds), so other recipes are counted in [otherBrews]
 * instead of being plotted on the same axis.
 */
data class GrindSeries(
    val grinderLabel: String,
    val points: List<GrindPoint>,
    val mainRecipeId: String?,
    val bestZone: IntRange?,
    val bestZoneAvg: Double?,
    val first: Int?,
    val latest: Int?,
    val lowerIsFiner: Boolean,
    val recipeName: String,
    val otherBrews: Int,
    val clicksPerTurn: Int,
    /** The grinder's DialFormat.encode(), so the screen reads clicks the way the dial does. */
    val dialFormat: String = "",
)

/** The best-scoring cup. Ties go to the most recent, because that is the one worth repeating. */
data class BestCup(
    val id: Long,
    val at: Long,
    val score: Int,
    val recipeName: String,
    val clicks: Int?,
    val grindSetting: String,
    /** 0 when the brew didn't record one. */
    val tempC: Int = 0,
)

data class StreakRun(val days: Int, val end: LocalDate, val current: Boolean)

data class PersonalBests(
    val bestCup: BestCup?,
    val longestStreak: StreakRun?,
    val bestCoffee: Ranked?,
)

/**
 * Pure functions over the log, so the Passport and both charts can be unit tested without
 * Compose or Room. Logs may arrive in any order; every function sorts what it needs.
 */
object PassportStats {

    fun passport(
        logs: List<BrewLogEntity>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Passport {
        val scored = logs.filter { it.overall > 0 }
        val (streak, bestStreak) = streaks(logs, today, zone)
        val firstAt = logs.minOfOrNull { it.startedAt }
        val spanDays = firstAt
            ?.let { ChronoUnit.DAYS.between(dateOf(it, zone), today).toInt() + 1 }
            ?.coerceAtLeast(1)
            ?: 0
        return Passport(
            brews = logs.size,
            waterG = logs.sumOf { it.waterG },
            coffeeG = logs.sumOf { it.doseG },
            streak = streak,
            bestStreak = bestStreak,
            avg = scored.averageScore(),
            best = scored.maxOfOrNull { it.overall },
            greatCups = logs.count { it.overall >= 8 },
            firstAt = firstAt,
            spanDays = spanDays,
            stamps = stamps(logs),
            recipesUsed = logs.map { it.recipeId }.filter { it.isNotBlank() }.distinct().size,
            coffees = logs.map { it.beanId?.toString() ?: it.beanLabel }.filter { it.isNotBlank() }.distinct().size,
        )
    }

    /**
     * One stamp per brewer, most used first. Grouped by the brewer itself, not by the words
     * stored with the brew: a 1.x brew says "Hario V60" and a later one "Hario V60 02", and both
     * are the same dripper.
     */
    fun stamps(logs: List<BrewLogEntity>): List<Stamp> =
        logs.filter { it.brewer.isNotBlank() || it.brewerId != null }
            .groupBy { log ->
                val b = Brewers.byId(log.brewerId) ?: Brewers.resolveOrNull(log.brewer)
                b?.id ?: log.brewer
            }
            .map { (_, l) ->
                val first = l.first()
                Stamp(Brewers.byId(first.brewerId) ?: Brewers.resolveOrNull(first.brewer), first.brewer, l.size)
            }
            .sortedByDescending { it.count }

    /**
     * (current, best) runs of consecutive brewing days. The current run ends today, or
     * yesterday when today has no brew yet, the same rule Home uses for its streak.
     */
    fun streaks(
        logs: List<BrewLogEntity>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<Int, Int> {
        if (logs.isEmpty()) return 0 to 0
        val days = logs.map { dateOf(it.startedAt, zone) }.toSortedSet()

        var cursor = today
        if (cursor !in days) cursor = cursor.minusDays(1)
        var current = 0
        while (cursor in days) {
            current++
            cursor = cursor.minusDays(1)
        }

        val best = runs(days).maxOfOrNull { it.second } ?: 0
        return current to maxOf(best, current)
    }

    /** The longest run of consecutive days, latest first on a tie, or null with no brews. */
    fun longestStreak(
        logs: List<BrewLogEntity>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): StreakRun? {
        if (logs.isEmpty()) return null
        val all = runs(logs.map { dateOf(it.startedAt, zone) }.toSortedSet())
        val longest = all.maxOfOrNull { it.second } ?: return null
        // runs() is chronological, so the last run of that length is the most recent one.
        val (end, length) = all.last { it.second == longest }
        return StreakRun(days = length, end = end, current = !end.isBefore(today.minusDays(1)))
    }

    /** The last [n] scored brews, oldest first, for the score chart. */
    fun scoreSeries(logs: List<BrewLogEntity>, n: Int = 10): List<ScorePoint> =
        logs.filter { it.overall > 0 }
            .sortedBy { it.startedAt }
            .takeLast(n)
            .map { ScorePoint(id = it.id, at = it.startedAt, score = it.overall) }

    fun lastFiveAvg(logs: List<BrewLogEntity>): Double? =
        newestScored(logs).take(5).averageScore()

    fun prevFiveAvg(logs: List<BrewLogEntity>): Double? =
        newestScored(logs).drop(5).take(5).averageScore()

    fun rankedRecipes(logs: List<BrewLogEntity>, recipes: List<Recipe>, max: Int = 3): List<Ranked> {
        val byId = recipes.associateBy { it.id }
        return logs.groupBy { it.recipeId }
            .map { (id, group) ->
                val recipe = byId[id]
                val sample = group.first()
                // Names are trimmed here, for display only: these lines join them with " · " and
                // into sentences, and a value saved with a trailing space would leave a gap.
                Ranked(
                    name = (recipe?.name ?: sample.recipeName).trim().ifBlank { "Brew" },
                    sub = (recipe?.author ?: sample.recipeAuthor).trim().takeIf { it.isNotBlank() },
                    count = group.size,
                    avg = group.filter { it.overall > 0 }.averageScore(),
                )
            }
            .sortedWith(rankOrder)
            .take(max)
    }

    fun rankedBeans(logs: List<BrewLogEntity>, beans: List<BeanEntity>, max: Int = 3): List<Ranked> =
        beanGroups(logs, beans).map { it.ranked }.sortedWith(rankOrder).take(max)

    /**
     * The most-used click grinder, on its most-brewed recipe only. Null until that recipe has
     * two brews with a real click count, because one point is not a journey.
     */
    fun grindSeries(logs: List<BrewLogEntity>, grinders: List<GrinderEntity>): GrindSeries? {
        val clickLogs = logs.filter { (it.grindClicks ?: 0) > 0 && it.grinderId != null }
        val grinderId = clickLogs.groupingBy { it.grinderId }.eachCount()
            .maxByOrNull { it.value }?.key
            ?: return null
        val onGrinder = clickLogs.filter { it.grinderId == grinderId }
        val mainRecipeId = onGrinder.groupingBy { it.recipeId }.eachCount()
            .maxByOrNull { it.value }?.key
            ?: return null
        val main = onGrinder.filter { it.recipeId == mainRecipeId }.sortedBy { it.startedAt }
        if (main.size < 2) return null

        val points = main.map {
            GrindPoint(
                id = it.id,
                at = it.startedAt,
                clicks = it.grindClicks ?: 0,
                score = it.overall,
                recipeId = it.recipeId,
            )
        }
        val good = points.filter { it.score >= 7 }
        val grinder = grinders.firstOrNull { it.id == grinderId }
        val latestLog = main.last()
        return GrindSeries(
            grinderLabel = grinder?.model?.trim()?.takeIf { it.isNotBlank() }
                ?: latestLog.grinderLabel.trim().ifBlank { "Grinder" },
            points = points,
            mainRecipeId = mainRecipeId,
            bestZone = if (good.isEmpty()) null else good.minOf { it.clicks }..good.maxOf { it.clicks },
            bestZoneAvg = if (good.isEmpty()) null else good.sumOf { it.score }.toDouble() / good.size,
            first = points.first().clicks,
            latest = points.last().clicks,
            lowerIsFiner = grinder?.lowerIsFiner ?: true,
            recipeName = latestLog.recipeName.trim().ifBlank { "This recipe" },
            otherBrews = onGrinder.size - main.size,
            clicksPerTurn = grinder?.clicksPerRotation ?: 0,
            dialFormat = grinder?.scale()?.dial?.encode().orEmpty(),
        )
    }

    /**
     * Records with context, not averages: the best cup and what made it, the longest run of
     * brewing days, and the coffee with the best average over at least two rated brews.
     */
    fun personalBests(
        logs: List<BrewLogEntity>,
        beans: List<BeanEntity>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): PersonalBests {
        val best = logs.filter { it.overall > 0 }
            .maxWithOrNull(compareBy<BrewLogEntity> { it.overall }.thenBy { it.startedAt })
        val bestCoffee = beanGroups(logs, beans)
            .filter { it.rated >= 2 }
            .map { it.ranked }
            .maxWithOrNull(compareBy<Ranked> { it.avg ?: 0.0 }.thenBy { it.count })
        return PersonalBests(
            bestCup = best?.let {
                BestCup(
                    id = it.id,
                    at = it.startedAt,
                    score = it.overall,
                    recipeName = it.recipeName.trim().ifBlank { "Brew" },
                    clicks = it.grindClicks?.takeIf { c -> c > 0 },
                    // A logged 0 on a click grinder is a slip, not a setting worth repeating.
                    // Typed grind text is trimmed for the line it is shown in; the log keeps it as typed.
                    grindSetting = if (it.grindClicks == 0) "" else it.grindSetting.trim(),
                    tempC = it.tempC,
                )
            },
            longestStreak = longestStreak(logs, today, zone),
            bestCoffee = bestCoffee,
        )
    }

    // ------------------------------------------------------------------ helpers

    private val rankOrder = compareByDescending<Ranked> { it.count }.thenByDescending { it.avg ?: -1.0 }

    private fun dateOf(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    private fun newestScored(logs: List<BrewLogEntity>): List<BrewLogEntity> =
        logs.filter { it.overall > 0 }.sortedByDescending { it.startedAt }

    private fun List<BrewLogEntity>.averageScore(): Double? =
        if (isEmpty()) null else sumOf { it.overall }.toDouble() / size

    /** Every run of consecutive dates as (last day, length), in chronological order. */
    private fun runs(days: Set<LocalDate>): List<Pair<LocalDate, Int>> {
        val out = mutableListOf<Pair<LocalDate, Int>>()
        var prev: LocalDate? = null
        var length = 0
        for (day in days.sorted()) {
            if (prev != null && prev.plusDays(1) == day) {
                length++
            } else {
                if (prev != null) out += prev to length
                length = 1
            }
            prev = day
        }
        if (prev != null) out += prev to length
        return out
    }

    private data class BeanKey(val id: Long?, val label: String)

    private fun beanKey(log: BrewLogEntity): BeanKey? = when {
        log.beanId != null -> BeanKey(log.beanId, "")
        log.beanLabel.isNotBlank() -> BeanKey(null, log.beanLabel)
        else -> null
    }

    private class BeanGroup(val ranked: Ranked, val rated: Int)

    private fun beanGroups(logs: List<BrewLogEntity>, beans: List<BeanEntity>): List<BeanGroup> {
        val byId = beans.associateBy { it.id }
        return logs.mapNotNull { log -> beanKey(log)?.let { it to log } }
            .groupBy({ it.first }, { it.second })
            .map { (key, group) ->
                val bean = key.id?.let(byId::get)
                // Labels are stored as "Roaster — Name"; split them when the bean row is gone.
                val label = group.first().beanLabel
                val rated = group.filter { it.overall > 0 }
                BeanGroup(
                    ranked = Ranked(
                        name = (bean?.name ?: label.substringAfter(" — ", label)).trim().ifBlank { "Coffee" },
                        sub = (bean?.roaster ?: label.substringBefore(" — ", "")).trim().takeIf { it.isNotBlank() },
                        count = group.size,
                        avg = rated.averageScore(),
                    ),
                    rated = rated.size,
                )
            }
    }
}
