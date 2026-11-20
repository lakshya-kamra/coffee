package com.laksh.coffee.ui.screens.brew

import androidx.compose.ui.input.key.Key
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.grams
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.NoScale
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.RecipeStep
import com.laksh.coffee.domain.StepKind
import kotlin.math.roundToInt

// Pure rules behind the brew screen, kept out of the composables so they are unit-tested.

/** The three running layouts (v4 §3.5). */
enum class RunLayout { Portrait, Stage, Short }

object BrewLayouts {
    /**
     * Short when the window is under 480 tall (a phone on its side, split screen); stage when it is
     * at least 600 wide, wider than tall and at least 480 tall (tablets, laptops); else portrait.
     */
    fun of(widthDp: Float, heightDp: Float): RunLayout = when {
        heightDp < 480f -> RunLayout.Short
        widthDp >= 600f && widthDp > heightDp -> RunLayout.Stage
        else -> RunLayout.Portrait
    }

    /** Setup goes two columns on wide windows, wide landscape and short landscape (§3.4). */
    fun setupTwoColumns(widthDp: Float, heightDp: Float): Boolean =
        widthDp >= 840f || (widthDp >= 600f && widthDp > heightDp)

    /** CSS clamp(), in dp. */
    fun clamp(min: Float, preferred: Float, max: Float): Float = preferred.coerceIn(min, max)

    /** Stage: the ring at min(70vh, 45vw, 560). */
    fun stageRing(widthDp: Float, heightDp: Float): Float = minOf(heightDp * 0.70f, widthDp * 0.45f, 560f)

    /** Stage: the clock at clamp(96, 16vh, 200), the one hero. */
    fun stageClock(heightDp: Float): Float = clamp(96f, heightDp * 0.16f, 200f)

    /** Stage: poured / target at clamp(40, 7vh, 64). */
    fun stageGrams(heightDp: Float): Float = clamp(40f, heightDp * 0.07f, 64f)

    /** Stage: the step title at clamp(28, 4vh, 40). */
    fun stageTitle(heightDp: Float): Float = clamp(28f, heightDp * 0.04f, 40f)

    /** Stage: the right column at clamp(380, 34vw, 480). */
    fun stageColumn(widthDp: Float): Float = clamp(380f, widthDp * 0.34f, 480f)

    /** Short: the clock at clamp(48, 22vh, 96). */
    fun shortClock(heightDp: Float): Float = clamp(48f, heightDp * 0.22f, 96f)

    /** Short: the left square at min(45vw, 100vh - 16). */
    fun shortSquare(widthDp: Float, heightDp: Float): Float = minOf(widthDp * 0.45f, heightDp - 16f)

    /** The phone ring: what the dial never shrinks below. */
    const val PHONE_RING = 240f

    /** The most a phone's ring grows to (the 1.x phone dial was 264). */
    const val PHONE_RING_MAX = 280f

    /**
     * Portrait: min(width - 2 gutters, height - the fixed rows, 360), never under the phone ring
     * (or the width, on a very narrow window). The step card and, last, the page scroll instead.
     */
    fun portraitRing(widthDp: Float, heightDp: Float, gutterDp: Float, fixedRowsDp: Float): Float {
        val room = widthDp - 2 * gutterDp
        // Phones keep about today's ring so the step card keeps its words; 360 is for medium.
        val cap = if (widthDp < 600f) PHONE_RING_MAX else 360f
        val wanted = minOf(room, heightDp - fixedRowsDp, cap)
        return wanted.coerceAtLeast(minOf(PHONE_RING, room))
    }

    /** The big clock whenever the ring can hold it; a long brew's four digits need a wider ring. */
    fun portraitUsesBigClock(ringDp: Float, totalSec: Int): Boolean =
        ringDp >= PHONE_RING && (totalSec < 600 || ringDp >= 320f)
}

// --------------------------------------------------------------------------- keys

/** Which keys the brew's root acts on (v4 §2.7). */
object BrewKeys {
    // What a focused button or radio does with these: press it, or move focus.
    private val CONTROL_KEYS = setOf(
        Key.Spacebar, Key.Enter, Key.NumPadEnter, Key.DirectionCenter,
        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
    )

    /**
     * Letters (N, F, M, X, Esc) always reach the root. Space, Enter and the arrows only while the
     * root itself has focus: on a focused Next or Finish, Space must press it, not pause the brew.
     */
    fun reachesRoot(key: Key, rootFocused: Boolean): Boolean = rootFocused || key !in CONTROL_KEYS
}

// ------------------------------------------------------------------ announcements

/** The words a screen reader hears (v4 §5 A4). Exact strings: the web says the same. */
object BrewWords {
    /**
     * "Pour to 300 g", "Bloom to 50 g", "Drawdown", else the step's own title. Without a scale
     * ([hasScale] false) a pour is a jug reading, "Pour to about 300 ml", and a title's grams are
     * said as [NoScale.text] says them.
     */
    fun lead(step: RecipeStep, hasScale: Boolean = true): String {
        val target = step.targetWaterG
        val amount = target?.let { if (hasScale) "$it g" else "about ${NoScale.aboutMl(it)} ml" }
        return when {
            step.kind == StepKind.Bloom && amount != null -> "Bloom to $amount"
            step.kind == StepKind.Pour && amount != null -> "Pour to $amount"
            step.kind == StepKind.Drawdown -> "Drawdown"
            else -> prose(step.title, hasScale)
        }
    }

    /** A step's own words, with its grams in ml and spoons when there is no scale. */
    fun prose(text: String, hasScale: Boolean): String = if (hasScale) text else NoScale.text(text)

    /** "Step 2 of 6, Pour to 300 g. Steady spiral from the centre outward. Next at 1:15." */
    fun step(recipe: Recipe, index: Int, hasScale: Boolean = true): String {
        val s = recipe.steps.getOrNull(index) ?: return ""
        val next = recipe.steps.getOrNull(index + 1)
        val detail = prose(s.detail, hasScale).trim().let { d -> if (d.isEmpty()) "" else " " + (if (d.endsWith('.')) d else "$d.") }
        val tail = next?.let { " Next at ${clock(it.startSec)}." }.orEmpty()
        return "Step ${index + 1} of ${recipe.steps.size}, ${lead(s, hasScale)}.$detail$tail"
    }

    /** "Brew done, 3:32. Rate this brew." */
    fun done(elapsedSec: Int): String = "Brew done, ${clock(elapsedSec)}. Rate this brew."

    const val NEXT_STEP = "Time for the next step."
    const val TEN_LEFT = "10 seconds left"

    /** Said once, at 10 s left in a step that lasts more than 30 s. */
    fun saysTenLeft(step: RecipeStep, elapsedSec: Int): Boolean =
        step.durationSec > 30 && step.endSec - elapsedSec == 10

    /** The step-change banner: "NEXT STEP · Pour to 300 g" ("… about 300 ml" without a scale). */
    fun banner(step: RecipeStep, hasScale: Boolean = true): String = "NEXT STEP · ${lead(step, hasScale)}"
}

// --------------------------------------------------------------------- shot mode

/** Espresso shot mode (v4 §4.9): one big button that starts the shot, then stops it. */
enum class ShotPhase {
    Ready, Pulling, Stopped;

    /** The big button: Ready starts, Pulling stops; a stopped shot waits for its yield. */
    fun onPrimary(): ShotPhase = when (this) {
        Ready -> Pulling
        Pulling -> Stopped
        Stopped -> Stopped
    }

    val primaryLabel: String?
        get() = when (this) {
            Ready -> "Start shot"
            Pulling -> "Stop shot"
            Stopped -> null
        }

    companion object {
        fun of(running: Boolean, elapsedMs: Long, stopped: Boolean): ShotPhase = when {
            stopped -> Stopped
            running -> Pulling
            elapsedMs > 0 -> Pulling
            else -> Ready
        }
    }
}

// ------------------------------------------------------------------ grind choice

/** How the coffee is ground for this brew: the brew record's grindKind. */
enum class GrindChoice(val kind: String) {
    Preground("preground"), Blade("blade"), Dial("dial");

    companion object {
        /** A blade grinder is one whose setting is seconds. */
        fun of(preground: Boolean, grinder: GrinderEntity?): GrindChoice = when {
            preground -> Preground
            grinder?.settingUnit == "s" -> Blade
            else -> Dial
        }

        /**
         * The brew record's grindKind. No grinder chosen (and not pre-ground) is "", dial or
         * unknown, as the web writes it and backup v2.1 documents it, never "dial".
         */
        fun recordKind(preground: Boolean, grinder: GrinderEntity?): String {
            val c = of(preground, grinder)
            return if (c == Dial && grinder == null) "" else c.kind
        }

        /**
         * "Pre-ground (as bought)" starts selected when the coffee is ground, or when the person
         * buys ground coffee and has no grinder (§4.2).
         */
        fun startsPreground(bean: BeanEntity?, buysGround: Boolean, grinderCount: Int): Boolean =
            bean?.form == "ground" || (buysGround && grinderCount == 0)
    }
}

/** The blade grinder's seconds as the log writes them: "12 s". */
fun bladeLabel(sec: Int): String = "$sec s"

// --------------------------------------------------------------------- brew again

/**
 * "Brew again" (v4 §4.10): setup prefilled with that brew's coffee, grinder, grind and dose. A
 * coffee or grinder that has since gone is left out rather than guessed.
 */
data class AgainPrefill(
    val beanId: Long?,
    val grinderId: Long?,
    val preground: Boolean,
    val grindClicks: Int?,
    val grindSetting: String,
    val bladeSec: Int?,
    val doseG: Double,
    val waterG: Double,
    val tempC: Int?,
) {
    companion object {
        fun of(log: BrewLogEntity, beans: List<BeanEntity>, grinders: List<GrinderEntity>): AgainPrefill {
            val grinder = grinders.firstOrNull { it.id == log.grinderId }
            val preground = log.grindKind == "preground"
            val blade = !preground && (log.grindKind == "blade" || grinder?.settingUnit == "s")
            return AgainPrefill(
                beanId = beans.firstOrNull { it.id == log.beanId }?.id,
                grinderId = if (preground) null else grinder?.id,
                preground = preground,
                grindClicks = if (!preground && grinder?.usesClicks == true) log.grindClicks?.takeIf { it > 0 } else null,
                grindSetting = if (preground || blade || grinder?.usesClicks == true) "" else log.grindSetting.trim(),
                bladeSec = if (blade) (log.grindValue?.roundToInt() ?: log.grindSetting.filter(Char::isDigit).toIntOrNull()) else null,
                doseG = log.doseG,
                waterG = log.waterG,
                tempC = log.tempC.takeIf { it in 1..100 },
            )
        }

        /**
         * A recipe that was deleted since: a one-off called "Brew again" with the brew's own
         * numbers and a single "Brew" step for its original time.
         */
        fun fallbackRecipe(log: BrewLogEntity): Recipe {
            val brewer = Brewers.resolve(log.brewerId ?: log.brewer)
            val time = log.totalTimeSec.takeIf { it > 0 } ?: 180
            return Recipe(
                id = log.recipeId.ifBlank { "brew-again" },
                name = "Brew again",
                author = log.recipeName.ifBlank { "Your brew" },
                brewer = brewer,
                doseG = log.doseG.takeIf { it > 0 } ?: 15.0,
                waterG = log.waterG.takeIf { it > 0 } ?: 250.0,
                grind = log.grindSetting.ifBlank { "As last time" },
                tempC = log.tempC.takeIf { it in 1..100 } ?: 94,
                blurb = "",
                sourceNote = "Your own brew from the log.",
                prep = emptyList(),
                tips = emptyList(),
                steps = listOf(
                    RecipeStep(
                        kind = StepKind.Pour,
                        title = "Brew",
                        detail = "As you did last time: ${grams(log.doseG)} g of coffee to ${grams(log.waterG)} g of water.",
                        startSec = 0,
                        endSec = time,
                        targetWaterG = log.waterG.roundToInt().takeIf { it > 0 },
                    ),
                ),
                accent = 0xFF8A6A4FL,
                isBuiltIn = false,
                fidelity = "custom",
            )
        }
    }
}

/** The chime question is asked once, before the first brew with a step longer than a minute. */
fun asksChime(recipe: Recipe, soundCuesAsked: Boolean): Boolean =
    !soundCuesAsked && recipe.steps.any { it.durationSec > 60 }
