package com.laksh.coffee.ui

import com.laksh.coffee.core.clock
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.ui.screens.history.HistoryState
import com.laksh.coffee.ui.screens.home.HomeUiState
import com.laksh.coffee.ui.screens.insights.InsightsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * PERF moved the screens' derived values from getters (re-run on every read in composition) to
 * fields built once with the state, and made "today" part of the state. These pin the numbers
 * and the midnight behaviour: the tabs stay composed, so a new day must rebuild them.
 */
class PrecomputedStateTest {

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 17)

    private fun log(id: Long, daysAgo: Long, score: Int = 0, recipe: String = "r") = BrewLogEntity(
        id = id,
        // Noon, so the local date is the same whatever zone the test machine is in.
        startedAt = today.minusDays(daysAgo).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
        recipeId = recipe,
        recipeName = recipe,
        overall = score,
    )

    @Test
    fun clockMatchesTheOldFormat() {
        for (s in listOf(0, 5, 59, 60, 65, 185, 600, 3599, 3600, 36_005)) {
            assertEquals("%d:%02d".format(s / 60, s % 60), clock(s))
        }
        assertEquals("0:00", clock(0))
        assertEquals("3:05", clock(185))
        assertEquals("%d:%02d".format(-65 / 60, -65 % 60), clock(-65))
    }

    @Test
    fun homeCountsFromTheDayItIsGiven() {
        // Newest first: two today, one yesterday, one two days ago, then a gap.
        val logs = listOf(log(5, 0, 8), log(4, 0, 9), log(3, 1, 7), log(2, 2), log(1, 5, 6))
        val state = HomeUiState(recentLogs = logs, today = today)
        assertEquals(2, state.brewsToday)
        assertEquals(5, state.brewsThisWeek)
        assertEquals(3, state.streakDays)
        assertEquals(7.5, state.lastFiveAvg!!, 1e-9)
        assertEquals("Dialled in", state.headline.lead)
    }

    @Test
    fun homeRebuildsWhenTheDayChanges() {
        val logs = listOf(log(2, 0, 8), log(1, 1, 7))
        val before = HomeUiState(recentLogs = logs, today = today)
        val after = HomeUiState(recentLogs = logs, today = today.plusDays(1))
        // Not equal, so the StateFlow emits and the tab recomposes after midnight.
        assertNotEquals(before, after)
        assertEquals(1, before.brewsToday)
        assertEquals(0, after.brewsToday)
        // Yesterday still carries the streak...
        assertEquals(2, after.streakDays)
        // ...and two days without a brew ends it.
        assertEquals(0, HomeUiState(recentLogs = logs, today = today.plusDays(2)).streakDays)
    }

    @Test
    fun historyHeaderNumbers() {
        val logs = listOf(log(4, 0, 8, "a"), log(3, 0, 0, "b"), log(2, 3, 6, "a"), log(1, 9, 7, "c"))
        val state = HistoryState(logs = logs)
        assertEquals(listOf(today, today.minusDays(3), today.minusDays(9)), state.byDay.map { it.first })
        assertEquals(listOf(2, 1, 1), state.byDay.map { it.second.size })
        assertEquals(3, state.recipeCount)
        assertEquals(7.0, state.avgScore!!, 1e-9)
        // Ten days from first to last brew: two weeks, a partial one counted whole.
        assertEquals(2, state.spanWeeks)
        assertEquals(0, HistoryState().spanWeeks)
    }

    @Test
    fun insightsStreakFollowsTheDay() {
        val logs = listOf(log(3, 0, 8), log(2, 1, 7), log(1, 2, 9))
        val now = InsightsState(logs = logs, today = today).also { it.warm() }
        val later = InsightsState(logs = logs, today = today.plusDays(3)).also { it.warm() }
        assertNotEquals(now, later)
        assertEquals(3, now.passport.streak)
        assertEquals(0, later.passport.streak)
    }
}
