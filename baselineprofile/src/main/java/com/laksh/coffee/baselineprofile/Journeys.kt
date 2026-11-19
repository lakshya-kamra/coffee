package com.laksh.coffee.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/**
 * The user journeys the baseline profile is generated from and the benchmarks time. Shared so
 * the profile covers exactly what is measured. Elements are found by Compose test tags, which
 * the app exposes as resource ids (testTagsAsResourceId on its root).
 */

/** com.laksh.coffee.bench: the benchmark variants' own id, set in build.gradle.kts. */
val targetAppId: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId") ?: "com.laksh.coffee.bench"

val TABS = listOf("tab-today", "tab-recipes", "tab-log", "tab-insights", "tab-gear")

private const val WAIT_MS = 5_000L

/** Cold or warm start into Today, seeding sample brews on a fresh install (DebugTools). */
fun MacrobenchmarkScope.launchSeeded() {
    pressHome()
    startActivityAndWait(
        Intent(Intent.ACTION_MAIN).apply {
            setClassName(targetAppId, "com.laksh.coffee.MainActivity")
            putExtra("seed", true)
        },
    )
    device.wait(Until.hasObject(By.res("tab-today")), WAIT_MS)
    // Seeding is asynchronous on the very first launch; the log rows prove it has landed.
    device.wait(Until.hasObject(By.res("home-list")), WAIT_MS)
    device.waitForIdle()
}

fun MacrobenchmarkScope.tap(res: String) {
    device.wait(Until.hasObject(By.res(res)), WAIT_MS)
    device.findObject(By.res(res))?.click()
    device.waitForIdle()
}

/** Every tab, there and back: 8 switches. */
fun MacrobenchmarkScope.walkTabs() {
    (TABS + TABS.reversed().drop(1)).forEach { tap(it) }
}

fun MacrobenchmarkScope.scrollAndFling(listRes: String) {
    device.wait(Until.hasObject(By.res(listRes)), WAIT_MS)
    device.findObject(By.res(listRes))?.let {
        // Keep clear of the gesture-navigation edge.
        it.setGestureMargin(device.displayWidth / 5)
        it.fling(Direction.DOWN)
        device.waitForIdle()
        it.fling(Direction.UP)
        device.waitForIdle()
    }
}

/** Recipes tab, open a recipe (shared header flight), scroll it, back. */
fun MacrobenchmarkScope.recipeDetailRoundTrip() {
    tap("tab-recipes")
    tap("recipe-card")
    device.wait(Until.hasObject(By.text("Brew this")), WAIT_MS)
    device.findObject(By.scrollable(true))?.let {
        it.setGestureMargin(device.displayWidth / 5)
        it.fling(Direction.DOWN)
    }
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}

/** Today's Start brew into the brew setup, and back. */
fun MacrobenchmarkScope.brewSetupRoundTrip() {
    tap("tab-today")
    device.wait(Until.hasObject(By.text("Start brew")), WAIT_MS)
    device.findObject(By.text("Start brew"))?.click()
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}

/** Log tab, fling the list, open a brew, scroll it, back. */
fun MacrobenchmarkScope.brewDetailRoundTrip() {
    tap("tab-log")
    scrollAndFling("log-list")
    tap("log-row")
    device.wait(Until.hasObject(By.text("COFFEE & GEAR")), WAIT_MS)
    device.findObject(By.scrollable(true))?.let {
        it.setGestureMargin(device.displayWidth / 5)
        it.fling(Direction.DOWN)
    }
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}

/** Gear and its three sub-tabs. */
fun MacrobenchmarkScope.gearSubTabs() {
    tap("tab-gear")
    listOf("Grinders", "App", "Beans").forEach {
        device.findObject(By.text(it))?.click()
        device.waitForIdle()
    }
}

/** Insights, scrolled through its charts. */
fun MacrobenchmarkScope.insightsScroll() {
    tap("tab-insights")
    scrollAndFling("insights-list")
}
