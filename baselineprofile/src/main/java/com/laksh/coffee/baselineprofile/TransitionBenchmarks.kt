package com.laksh.coffee.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing for the motion the owner notices: tab switches, and opening and closing a recipe
 * and a brew. Read frameDurationCpuMs and frameOverrunMs (API 31+) at P50/P90/P99; the targets
 * at 120 Hz are in docs/plan/jank-audit.md section 5.1 (tabs: P95 overrun <= 0, P90 CPU <= 6 ms).
 *
 *   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 *
 * On an emulator add -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
 * and treat the numbers as relative only.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TransitionBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** No AOT at all: what a fresh sideloaded install feels like before dexopt. */
    @Test
    fun tabsNoCompilation() = tabs(CompilationMode.None())

    /** With the shipped baseline profile (or plain JIT when none is committed yet). */
    @Test
    fun tabsBaselineProfile() = tabs(CompilationMode.Partial(BaselineProfileMode.UseIfAvailable))

    @Test
    fun recipeOpenClose() = run(CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)) { recipeDetailRoundTrip() }

    @Test
    fun brewDetailOpenClose() = run(CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)) { brewDetailRoundTrip() }

    private fun tabs(mode: CompilationMode) = run(mode) { walkTabs() }

    private fun run(mode: CompilationMode, block: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = targetAppId,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = { launchSeeded() },
        measureBlock = block,
    )
}
