package com.laksh.coffee.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates app/src/release/generated/baselineProfiles/baseline-prof.txt (and startup-prof.txt):
 *
 *   ./gradlew :app:generateReleaseBaselineProfile
 *
 * with an API 33+ emulator or phone connected. Commit the result; release builds compile it in.
 * Regenerate after any large change to the tab shell or the detail screens.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Start-up to a seeded Today: also orders the DEX for start-up. */
    @Test
    fun startup() = rule.collect(
        packageName = targetAppId,
        includeInStartupProfile = true,
        // Fewer passes than the default 15: enough for stable rules, and short enough that the
        // owner's low-memory emulator survives the run.
        maxIterations = 6,
        stableIterations = 2,
    ) {
        launchSeeded()
    }

    /** Every section and the main detail screens, so their first use is not interpreted. */
    @Test
    fun sectionsAndDetails() = rule.collect(
        packageName = targetAppId,
        includeInStartupProfile = false,
        maxIterations = 4,
        stableIterations = 2,
    ) {
        launchSeeded()
        walkTabs()
        recipeDetailRoundTrip()
        brewSetupRoundTrip()
        brewDetailRoundTrip()
        gearSubTabs()
        insightsScroll()
    }
}
