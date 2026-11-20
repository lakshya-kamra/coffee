package com.laksh.coffee.domain

import kotlin.math.roundToInt

/**
 * What a recipe asks of an espresso shot (v4 §4.9): the band of seconds it states, else when its
 * pull ends, else [DEFAULT]. The one target shot mode, Review, the brew detail, Today and the
 * offline first read all measure against, so a 0:45 recipe whose last 10 s are "Stop and taste"
 * is never "about 35 s" in one place and 0:45 in another.
 */
data class ShotTarget(val lowSec: Int, val highSec: Int, val stated: Boolean) {
    /** "25–30 s" (an en dash, as the web writes it), or "about 35 s". */
    val label: String get() = if (stated) "$lowSec–$highSec s" else "about $highSec s"

    // "about 35 s" is one number, so it allows 2 s either side (the web's bandStatus does too).
    private val slack: Int get() = if (stated) 0 else 2

    fun contains(sec: Int): Boolean = sec in (lowSec - slack)..(highSec + slack)

    /** Seconds outside the band: 0 inside it, negative when the shot ran fast, positive when slow. */
    fun offBy(sec: Int): Int = when {
        sec < lowSec - slack -> sec - lowSec
        sec > highSec + slack -> sec - highSec
        else -> 0
    }

    /** The band edge a shot of [sec] is measured against: the low end below the band, else the high. */
    fun edge(sec: Int): Int = if (sec < lowSec) lowSec else highSec

    companion object {
        /** A shot with no time of its own: the usual 25 to 30 seconds. */
        val DEFAULT = ShotTarget(25, 30, stated = true)

        // "25 to 30 seconds", "25-30 s".
        private val BAND = Regex("""(\d{1,3})\s*(?:-|–|to)\s*(\d{1,3})\s*(?:s\b|sec|seconds)""", RegexOption.IGNORE_CASE)
        private val PRE_INFUSION = Regex("pre-?infus", RegexOption.IGNORE_CASE)

        /**
         * One rule on both platforms (web twin: shotBand in shotTarget.ts; parity cases "shotBand"
         * in shared/fixtures/domain-v4-cases.json):
         * 1. A band the recipe's words state, read from its steps' details, then their titles, then
         *    its tips, then its blurb, skipping pre-infusion steps. Every range in a text is tried
         *    in order; the first with 0 < low < high and 15 <= high <= 120 wins (a range ending
         *    under 15 s is first drops, not the shot).
         * 2. Else "about N s", N the latest end of a step that is not Serve (of any step when every
         *    step is Serve): "Stop and taste" after the pull is not the shot.
         * 3. Else, with no steps or no time at all, [DEFAULT].
         */
        fun of(recipe: Recipe): ShotTarget {
            val pull = recipe.steps.filterNot(::preinfusionStep)
            val texts = pull.map { it.detail } + pull.map { it.title } + recipe.tips + recipe.blurb
            for (t in texts) {
                for (m in BAND.findAll(t)) {
                    val lo = m.groupValues[1].toInt()
                    val hi = m.groupValues[2].toInt()
                    if (lo > 0 && lo < hi && hi in 15..120) return ShotTarget(lo, hi, stated = true)
                }
            }
            val pulling = recipe.steps.filter { it.kind != StepKind.Serve }.ifEmpty { recipe.steps }
            val end = pulling.maxOfOrNull { it.endSec } ?: 0
            return if (end > 0) ShotTarget(end, end, stated = false) else DEFAULT
        }

        /** "infus" in its title, or a first step whose detail says pre-infusion. */
        fun preinfusionStep(s: RecipeStep): Boolean =
            s.title.contains("infus", ignoreCase = true) || (s.startSec == 0 && PRE_INFUSION.containsMatchIn(s.detail))

        /** True for a recipe brewed in shot mode. */
        fun applies(recipe: Recipe?): Boolean = recipe?.brewer?.method == BrewMethod.Espresso

        /**
         * One number for the places that hold a single target (a signed delta, a "plan" line): the
         * band's middle, rounded, for espresso; the recipe's whole time for everything else.
         */
        fun targetSec(recipe: Recipe): Int =
            if (applies(recipe)) of(recipe).let { ((it.lowSec + it.highSec) / 2.0).roundToInt() } else recipe.totalSec
    }
}

/** The recipe's pre-infusion window in seconds, or null. */
fun Recipe.preinfusion(): IntRange? =
    steps.firstOrNull(ShotTarget::preinfusionStep)?.let { it.startSec until it.endSec.coerceAtLeast(it.startSec + 1) }
