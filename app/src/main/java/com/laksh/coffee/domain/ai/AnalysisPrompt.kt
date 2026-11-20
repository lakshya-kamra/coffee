package com.laksh.coffee.domain.ai

import com.laksh.coffee.core.oneDp
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.Channeling
import com.laksh.coffee.domain.FlowFeel
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.Sludge
import com.laksh.coffee.domain.TasteProfile
import com.laksh.coffee.domain.asClock
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogWater
import com.laksh.coffee.domain.catalog.Principle
import com.laksh.coffee.domain.catalog.TermKind
import com.laksh.coffee.domain.grind.GrindScale
import com.laksh.coffee.domain.grind.MoveSizes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One attached photo, as the prompt names it. Uid and size make a changed photo change the hash. */
data class PhotoMeta(
    val uid: String,
    /** bed | cup | bag | other */
    val kind: String,
    val takenAt: Long,
    val bytes: Long,
)

data class BrewPromptInput(
    val log: BrewLogEntity,
    val recipe: Recipe?,
    val bean: BeanEntity?,
    val grinder: GrinderEntity?,
    /** Older brews, newest first, at most [AnalysisPrompt.HISTORY_FOR_BREW]. */
    val history: List<BrewLogEntity>,
    val recipes: Map<String, Recipe>,
    val beans: Map<Long, BeanEntity>,
    val grinders: Map<Long, GrinderEntity>,
    /** Null = worked out from the recipe or the log. */
    val brewer: Brewer? = null,
    /** Null = looked up from the log's waterId. */
    val water: CatalogWater? = null,
    /** The photos that will be attached (already filtered: kind, ticked, vision model, at most 3). */
    val photos: List<PhotoMeta> = emptyList(),
    /**
     * The photos the brew has that could be attached, whatever the settings say. The hash is
     * made from these: whether photos are sent is this install's setting, and a restore does
     * not bring it. Null = the same as [photos].
     */
    val brewPhotos: List<PhotoMeta>? = null,
    /** "c" or "f": the unit the person reads temperatures in. The data is always °C. */
    val units: String = "c",
    val principles: List<Principle> = emptyList(),
)

data class TrendPromptInput(
    /** Newest first, already capped at [AnalysisPrompt.TREND_MAX_BREWS]. */
    val logs: List<BrewLogEntity>,
    val totalLogged: Int,
    val recipes: Map<String, Recipe>,
    val beans: Map<Long, BeanEntity>,
    val grinders: Map<Long, GrinderEntity>,
    val units: String = "c",
    val principles: List<Principle> = emptyList(),
)

data class BuiltPrompt(
    val system: String,
    val user: String,
    val flags: List<DataFlag>,
    val inputHash: String,
    val brewCount: Int,
    val ratedCount: Int,
    val newestBrewAt: Long,
    val approxPromptTokens: Int,
    val maxTokens: Int,
    val expectedCompletionTokens: Int,
    /**
     * The one starting grind in clicks: the prompt tells the model to start from it, and the
     * hero checks the model's "from" against the same number. Null on a trend.
     */
    val grindAnchor: Int? = null,
    /** The same for numbered and stepless dials: the dial number the move starts from. */
    val valueAnchor: Double? = null,
    val photoCount: Int = 0,
)

/**
 * Builds the two prompts from Room data.
 *
 * Deterministic on purpose: absolute dates in UTC, fixed key order, and no value derived from
 * the current time or the phone's time zone (days off roast is the log's own snapshot), so the
 * same data always gives the same [BuiltPrompt.inputHash] and a stored analysis only goes stale
 * when the data changes, not after a trip abroad.
 * Every field DataQuality distrusts is sent as null, together with every value worked out
 * from it.
 *
 * VERSION 2: the grinder's own scale (how the dial reads, move sizes, starting range), the full
 * coffee, the brewer, the water, attached photos, the person's temperature unit, and a short
 * list of sourced principles.
 */
object AnalysisPrompt {
    const val VERSION = 2
    const val HISTORY_FOR_BREW = 8
    const val TREND_MAX_BREWS = 60
    const val MIN_RATED_FOR_TREND = 3

    private val DEGREES_C = Regex("""(\d+) °C""")

    /**
     * A step title as the prompt (and so the staleness hash) has it: temperatures in the 2.0 form
     * ("70 C"). v4 retitled three built-in recipes' steps to "70 °C"; without this every stored
     * analysis of a brew of them would read "Brew edited since" though nothing was edited. Web
     * twin: the steps line of the recipe block in analysisPrompt.ts.
     */
    internal fun promptTitle(title: String): String = DEGREES_C.replace(title, "$1 C")
    const val MAX_PRINCIPLES = 10
    const val MAX_PHOTOS = 3

    /**
     * Starts every [BuiltPrompt.inputHash] made from uids. A hash without it was made from row
     * ids, which change when a backup is restored, so it can no longer be compared.
     */
    const val HASH_SCHEME = "s2:"

    /** False for a hash an earlier build stored: it can't say whether the brew was edited. */
    fun isCurrentHash(hash: String): Boolean = hash.startsWith(HASH_SCHEME)

    // completion_tokens includes reasoning tokens on these models, so the caps leave room for
    // thinking; a cut-off reply is billed and shows nothing.
    const val BREW_MAX_TOKENS = 6000
    const val TREND_MAX_TOKENS = 8000

    // What a reply usually costs, for the estimate shown before the user taps.
    const val BREW_EXPECTED_OUT = 1500
    const val TREND_EXPECTED_OUT = 2000
    const val TEMPERATURE = 0.3

    val SYSTEM_BREW: String = """
        You are an experienced specialty-coffee coach reading one brew from a home brewer's log. The log comes from a phone app, and the app has already checked it for impossible values.

        How to read the data:
        - DATA QUALITY lists fields the app has proven unreliable. Those values are sent as null. Never reason from them, never guess what they really were, and mention each one briefly in dataWarnings.
        - Taste axes are 0-10. High bitterness is a fault. High acidity is not a fault on a light roast unless the brewer called it sour. overall 0 means not rated.
        - Compare what happened with what was planned: totalTimeSec against recipe.targetTotalSec, drawdownSec against recipe.expectedDrawdownSec (the time the recipe leaves after the last pour ends at recipe.lastPourEndsAtSec), and temperature and ratio against the recipe.
        - Bed shape, faults and timing are strong evidence. One slider is weak evidence. Earlier brews are context: use them to see what has already been tried, not to diagnose them.
        - Grind numbers belong to one grinder and one recipe. Never compare grind numbers across grinders or across recipes.
        - grinder.scale.micronsPerStep, when present, is how far the burrs move per click or step, so clicks times it is burr travel from zero. It is not the size of the grounds: never state or imply a particle size from it.
        - PRINCIPLES are from the people whose methods these are. Use them to reason and name the person when you lean on one. Never quote a principle that isn't listed.
        - Photos, when attached, are evidence of the bed and the cup. Mention what you see only if it changes the diagnosis; never read a grind setting off a photo.

        What to give back:
        - A verdict: dialled_in when the cup was good and nothing or almost nothing should change, close when one small nudge is enough, needs_change when it was a clear miss that one setting fixes, fault when something went wrong that a setting alone will not fix (channelling, a stalled bed, a log that cannot be trusted).
        - Exactly ONE change for the next brew. Not a list.
        - If the change is grind: give exact numbers in this grinder's own units — grindClicksFrom/To for click grinders, grindValueFrom/To for numbered or stepless dials. Follow grinder.scale.finerDirection exactly; grinder.example shows which way is finer. Size the move with grinder.moveSizes: nudge for a small change, clearMiss for a clear miss, never more than maxAtOnce at once. Phrase the instruction the way this person reads the dial (grinder.currentOnDial, grinder.scale.howTheDialReads), e.g. '75 -> 69 clicks (dial 7.5 -> 6.9)'. grinder.startingRange is a sanity check, not a target. If the current grind is null, start from grinder.lastTrustedClicks or grinder.lastTrustedSetting. If that is null too, the current grind is unknown: leave the grind numbers null and give the change relative to the last brew.
        - If the brew was good, the change can be 'none'; then say what to keep.
        - Say how the next cup should taste and run if you are right, and how it would taste if you are wrong.
        - If the log cannot support a confident answer, say so and set confidence to 'low'. Do not invent measurements.

        Style: plain, direct English in the second person, with units (g, s, °C, clicks). No markdown, no emojis, no preamble.
        Output: one JSON object exactly matching REPLY SHAPE at the end of the message. No code fences, nothing before or after it.
    """.trimIndent()

    val SYSTEM_TREND: String = """
        You are an experienced specialty-coffee coach reviewing a home brewer's whole log to find what goes wrong repeatedly. The log comes from a phone app, and the app has already checked it for impossible values.

        How to read the data:
        - DATA QUALITY lists fields the app has proven unreliable. They are sent as null. Never reason from them; mention them briefly in dataWarnings.
        - Taste axes are 0-10. overall 0 means not rated: leave those brews out of any score comparison.
        - Look across brews, not at one: which variables change between brews and which never move; what tracks with low and high overall scores (grind, temperature, ratio, total time against the recipe target, drawdown, days off roast, bed shape, coffee, recipe, brewer, water); whether one coffee or recipe keeps failing.
        - Count your evidence and name the brew ids behind each pattern. A pattern seen in fewer than 3 brews is 'weak'; if the log cannot show it at all, use 'too_little_data'. 'Only 4 brews' is better than a confident story built on noise.
        - Grind numbers belong to one grinder. Obey each grinder's scale.finerDirection, size any grind move with that grinder's moveSizes, and never compare grind numbers across grinders.
        - A grinder's scale.micronsPerStep, when present, is how far the burrs move per click or step (burr travel from zero), not the size of the grounds: never state or imply a particle size from it.
        - PRINCIPLES are from the people whose methods these are. Use them to reason and name the person when you lean on one. Never quote a principle that isn't listed.

        What to give back:
        - A trajectory for the log as a whole: improving, steady, stuck or erratic.
        - Up to 4 patterns, strongest first, each with a tone: good for something that is working, attention for a recurring problem, fault for something broken, neutral otherwise.
        - One three-brew experiment that settles the biggest open question. Change one variable across the three brews, give exact settings for each (the grind in the named grinder's own units where grind is involved), what to taste for, and how to read the result.
        - One sentence per coffee.

        Style: plain, direct English in the second person, with units. No markdown, no emojis, no preamble.
        Output: one JSON object exactly matching REPLY SHAPE at the end of the message. No code fences, nothing before or after it.
    """.trimIndent()

    private val BREW_REPLY_SHAPE: String = """
        REPLY SHAPE
        {
          "verdict": "dialled_in | close | needs_change | fault",
          "headline": "one sentence, at most 90 characters: what this cup most likely was",
          "diagnosis": "2-4 sentences: the most likely explanation, quoting the logged numbers",
          "confidence": "low | medium | high",
          "evidence": ["2-4 short facts from the log behind the diagnosis"],
          "change": {
            "variable": "grind | temperature | ratio | dose | pour | agitation | bloom | beans | none",
            "instruction": "the single change, exact, e.g. 75 -> 69 clicks (dial 7.5 -> 6.9)",
            "from": "current value with unit, or empty",
            "to": "new value with unit, or empty",
            "grindClicksFrom": null,
            "grindClicksTo": null,
            "grindValueFrom": null,
            "grindValueTo": null
          },
          "expectIfRight": "at most 120 characters: how the next cup should taste and run if you are right",
          "ifWrong": "at most 120 characters: how it will taste if you are wrong, and what that would tell you",
          "keep": "what to keep exactly the same",
          "dataWarnings": ["one short plain sentence per unreliable field; empty list if none"]
        }
        grindClicksFrom and grindClicksTo are integers, required when change.variable is grind, grinder.scale.adjustsBy is clicks and the current grind is known; otherwise null.
        grindValueFrom and grindValueTo are numbers, required when change.variable is grind on a numbered or stepless dial and the current setting is known; otherwise null.
        Reply with that JSON object only.
    """.trimIndent()

    private val TREND_REPLY_SHAPE: String = """
        REPLY SHAPE
        {
          "trajectory": "improving | steady | stuck | erratic",
          "headline": "one sentence, at most 90 characters: the main recurring problem or strength",
          "summary": "2-4 sentences",
          "confidence": "low | medium | high",
          "patterns": [
            {"title": "short", "detail": "1-3 sentences with numbers", "brewIds": [3, 6], "strength": "strong | weak | too_little_data", "tone": "good | attention | fault | neutral"}
          ],
          "changedOften": ["variables you keep moving"],
          "neverChanged": ["variables you have never moved"],
          "experiment": {
            "goal": "the open question this settles",
            "brews": [{"label": "Brew 1", "settings": "exact settings", "grindClicks": null, "expect": "what to taste for"}],
            "decide": "how to read the three results"
          },
          "perCoffee": [{"bean": "name", "note": "one sentence"}],
          "dataWarnings": ["one short sentence per unreliable field; empty list if none"]
        }
        patterns: 1 to 4, strongest first. experiment.brews: exactly 3. grindClicks is an integer on the named click grinder or null; for a numbered or stepless dial put the setting in "settings".
        Reply with that JSON object only.
    """.trimIndent()

    private const val DATA_QUALITY_TITLE = "DATA QUALITY (computed by the app; this overrides the raw log)"
    private const val FAHRENHEIT_BLOCK = "USER UNITS\nWrite temperatures in °F for this person; the data is in °C."

    // UTC, not the phone's zone: these strings feed the input hash, and a zone change would
    // otherwise mark every stored analysis out of date. The Z tells the model which clock it is.
    private val DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'", Locale.US).withZone(ZoneOffset.UTC)
    private val DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(ZoneOffset.UTC)

    // ---------------------------------------------------------------- redaction

    // Prompt keys, spelled once.
    private const val K_DOSE = "doseG"
    private const val K_WATER = "waterG"
    private const val K_RATIO = "ratio"
    private const val K_TEMP = "tempC"
    private const val K_GRIND_CLICKS = "grindClicks"
    private const val K_GRIND_SETTING = "grindSetting"
    private const val K_GRIND_VALUE = "grindValue"
    private const val K_CURRENT_CLICKS = "currentClicks"
    private const val K_CURRENT_SETTING = "currentSetting"
    private const val K_CURRENT_DIAL = "currentOnDial"
    private const val K_TOTAL = "totalTimeSec"
    private const val K_VS_RECIPE = "vsRecipeSec"
    private const val K_DRAWDOWN = "drawdownSec"
    private const val K_BEVERAGE = "beverageG"
    private const val K_RETAINED = "retainedGPerGCoffee"
    private const val K_TDS = "tdsPercent"
    private const val K_EY = "extractionYieldPct"
    private const val K_TASTE = "taste"
    private const val K_OVERALL = "overall"
    private const val K_DAYS_OFF_ROAST = "daysOffRoast"

    /** Every prompt key worked out from each checked field. A distrusted field hides all of them. */
    private val DERIVED: Map<String, Set<String>> = mapOf(
        Fields.GRIND to setOf(K_GRIND_CLICKS, K_GRIND_SETTING, K_GRIND_VALUE, K_CURRENT_CLICKS, K_CURRENT_SETTING, K_CURRENT_DIAL),
        Fields.BEVERAGE to setOf(K_BEVERAGE, K_RETAINED, K_EY, K_TDS),
        Fields.EY to setOf(K_EY, K_TDS),
        Fields.DRAWDOWN to setOf(K_DRAWDOWN),
        Fields.TASTE to setOf(K_TASTE),
        Fields.OVERALL to setOf(K_OVERALL),
        Fields.ROAST to setOf(K_DAYS_OFF_ROAST),
        Fields.TIME to setOf(K_TOTAL, K_VS_RECIPE),
        Fields.TEMP to setOf(K_TEMP),
        Fields.RATIO to setOf(K_DOSE, K_WATER, K_RATIO, K_RETAINED, K_EY),
    )

    /** The prompt keys to send as null for a brew whose [distrusted] fields are known. */
    internal fun redactedKeys(distrusted: Set<String>): Set<String> =
        distrusted.flatMapTo(mutableSetOf()) { DERIVED[it].orEmpty() }

    /**
     * A deleted coffee or grinder leaves its id on the brews that used it, and a restore brings
     * those brews back with no id at all. The checks group brews by these ids, so the prompt
     * reads such a brew as a restore would: with none.
     */
    private fun BrewLogEntity.savedOnly(beans: Map<Long, BeanEntity>, grinders: Map<Long, GrinderEntity>): BrewLogEntity {
        val bean = beanId?.takeIf { it in beans }
        val grinder = grinderId?.takeIf { it in grinders }
        return if (bean == beanId && grinder == grinderId) this else copy(beanId = bean, grinderId = grinder)
    }

    // -------------------------------------------------------------------- brew

    fun brew(input: BrewPromptInput): BuiltPrompt {
        // The target's own coffee and grinder count as saved even when the maps leave them out.
        val beans = input.bean?.let { input.beans + (it.id to it) } ?: input.beans
        val grinders = input.grinder?.let { input.grinders + (it.id to it) } ?: input.grinders
        val log = input.log.savedOnly(beans, grinders)
        val history = input.history.filter { it.id != log.id }.map { it.savedOnly(beans, grinders) }

        // The target is checked against its history; each context brew against the ones before it.
        val targetFlags = DataQuality.check(log, input.recipe, input.grinder, input.bean, history)
        val contextFlags = history.flatMapIndexed { i, h ->
            DataQuality.check(
                log = h,
                recipe = input.recipes[h.recipeId],
                grinder = h.grinderId?.let { input.grinders[it] },
                bean = h.beanId?.let { input.beans[it] },
                peers = history.drop(i + 1),
            )
        }
        val flags = targetFlags + contextFlags
        val flagsById = flags.groupBy { it.brewId }
        val distrusted = DataQuality.distrustedFields(flags, log.id)
        val hidden = redactedKeys(distrusted)

        val brewer = input.brewer ?: DataQuality.brewerOf(log, input.recipe)
        val method = brewer?.method
        val water = input.water ?: Catalog.water(log.waterId)
        // No saved grinder: the log's click count, if any, is all there is.
        val clicksGrinder = input.grinder?.usesClicks ?: true
        // A distrusted grind is anchored on the last trusted brew of the same recipe instead.
        val currentClicks = log.grindClicks?.takeIf { clicksGrinder && it > 0 && K_CURRENT_CLICKS !in hidden }
        val currentValue = log.grindValue?.takeIf { !clicksGrinder && it > 0 && K_CURRENT_SETTING !in hidden }
        val anchor = when {
            clicksGrinder && currentClicks == null -> DataQuality.lastTrustedClicks(log, history, flagsById)
            !clicksGrinder && currentValue == null -> DataQuality.lastTrustedValue(log, history, flagsById)
            else -> null
        }
        val grindAnchor = if (clicksGrinder) currentClicks ?: anchor?.grindClicks else null
        val valueAnchor = if (clicksGrinder) null else currentValue ?: anchor?.grindValue

        fun earlier(names: Names): String = if (history.isEmpty()) {
            "none"
        } else {
            jsonRows(
                history.map { h ->
                    row(h, input.recipes, input.beans, input.grinders, flagsById[h.id].orEmpty(), trend = false, names = names)
                },
            )
        }
        val photos = input.photos.take(MAX_PHOTOS)
        val brewPhotos = input.brewPhotos?.take(MAX_PHOTOS) ?: photos
        val principles = principlesFor(
            all = input.principles,
            methods = setOfNotNull(method),
            author = input.recipe?.author ?: log.recipeAuthor,
            topics = topicsFor(log, method, water),
        )

        // What the analysis reads. The hash covers exactly this, so only a change to the brew,
        // its coffee, its gear or its photos marks a stored analysis as edited.
        fun data(names: Names, photos: List<PhotoMeta>): List<String> = listOf(
            "TASK: Diagnose brew #${names.brewTag(log.id)} and give one change for the next brew.",
            grinderBlock(log, input.grinder, method, hidden, anchor, grindAnchor, valueAnchor, names),
            recipeBlock(log, input.recipe),
            brewerBlock(brewer, log),
            waterBlock(water),
            coffeeBlock(log, input.bean, hidden),
            "THIS BREW (what happened)\n" + thisBrew(log, input.recipe, input.grinder, distrusted, hidden, names),
            photosBlock(photos),
            dataQualityBlock(flags, names),
            "EARLIER BREWS (newest first, context only)\n${earlier(names)}",
        )
        // How to answer. Reference material and the person's units are not the brew's data.
        val guidance = listOfNotNull(
            principlesBlock(principles),
            FAHRENHEIT_BLOCK.takeIf { input.units == "f" },
            BREW_REPLY_SHAPE,
        )

        return built(
            system = SYSTEM_BREW,
            data = data(Names.ROW_IDS, photos),
            hashed = data(Names.uids(history + log, input.beans, input.grinders), brewPhotos),
            guidance = guidance,
            flags = flags,
            brewCount = 1 + history.size,
            ratedCount = (listOf(log) + history).count { it.overall > 0 },
            newestBrewAt = log.startedAt,
            maxTokens = BREW_MAX_TOKENS,
            expected = BREW_EXPECTED_OUT,
            grindAnchor = grindAnchor,
            valueAnchor = valueAnchor,
            photoCount = photos.size,
        )
    }

    // ------------------------------------------------------------------- trend

    fun trend(input: TrendPromptInput): BuiltPrompt {
        val logs = input.logs
        // Only the checks: the model is still told which brews shared a coffee since deleted.
        val checked = logs.map { it.savedOnly(input.beans, input.grinders) }
        val flags = checked.flatMapIndexed { i, l ->
            DataQuality.check(
                log = l,
                recipe = input.recipes[l.recipeId],
                grinder = l.grinderId?.let { input.grinders[it] },
                bean = l.beanId?.let { input.beans[it] },
                peers = checked.drop(i + 1),
            )
        }
        val flagsById = flags.groupBy { it.brewId }
        val rated = logs.count { it.overall > 0 }

        val task = buildString {
            append("TASK: Find the patterns across these ${logs.size} brews ($rated rated), newest first.")
            if (input.totalLogged > logs.size) {
                append(" These are the latest ${logs.size} of ${input.totalLogged} brews.")
            }
        }

        fun grinders(names: Names) = names.grinderOrder(logs.mapNotNull { it.grinderId }.distinct()).map { id ->
            val g = input.grinders[id]
            buildJsonObject {
                put("id", names.grinder(id))
                put("name", str(g?.display ?: logs.firstOrNull { it.grinderId == id }?.grinderLabel))
                if (g == null) {
                    // No longer saved: which way is finer is unknown, and so is everything else.
                    put("scale", JsonNull)
                    put("moveSizes", JsonNull)
                } else {
                    val scale = g.scale()
                    put("scale", scaleObject(g, scale))
                    put("moveSizes", moveSizes(scale, null))
                }
            }
        }
        fun coffees(names: Names) = names.beanOrder(logs.mapNotNull { it.beanId }.distinct()).map { id ->
            val b = input.beans[id]
            buildJsonObject {
                put("id", names.bean(id))
                put("name", str(b?.display ?: logs.firstOrNull { it.beanId == id }?.beanLabel))
                put("origin", str(b?.let { originOf(it) }))
                put("process", str(b?.let { processLabel(it) }))
                put("roastLevel", str(b?.let { roastLabel(it) }))
                put("decaf", if (b == null) JsonNull else JsonPrimitive(b.isDecaf))
                put("roastDateKnown", if (b == null) JsonNull else JsonPrimitive(b.roastedOnEpochDay != null))
            }
        }
        val recipes = logs.map { it.recipeId }.filter { it.isNotBlank() }.distinct().sorted().map { id ->
            val r = input.recipes[id]
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("name", str(r?.name ?: logs.firstOrNull { it.recipeId == id }?.recipeName))
                put("brewer", str(r?.brewer?.label))
                put("doseG", dbl(r?.doseG?.takeIf { it > 0 }))
                put("waterG", dbl(r?.waterG?.takeIf { it > 0 }))
                put("ratio", str(r?.takeIf { it.doseG > 0 && it.waterG > 0 }?.ratioLabel))
                put("tempC", int(r?.tempC))
                put("lastPourEndsAtSec", int(DataQuality.lastPourEndSec(r)))
                put("targetTotalSec", int(r?.totalSec?.takeIf { it > 0 }))
            }
        }
        fun rows(names: Names) = logs.map { l ->
            row(l, input.recipes, input.beans, input.grinders, flagsById[l.id].orEmpty(), trend = true, names = names)
        }
        val methods = logs.mapNotNullTo(mutableSetOf()) { DataQuality.brewerOf(it, input.recipes[it.recipeId])?.method }

        val recipesBlock = "RECIPES\n" + jsonRows(recipes)
        fun data(names: Names): List<String> = listOf(
            task,
            "GRINDERS\n" + jsonRows(grinders(names)),
            "COFFEES\n" + jsonRows(coffees(names)),
            recipesBlock,
            "BREWS\n" + jsonRows(rows(names)),
            dataQualityBlock(flags, names),
        )
        val guidance = listOfNotNull(
            principlesBlock(principlesFor(input.principles, methods, author = "", topics = TREND_TOPICS)),
            FAHRENHEIT_BLOCK.takeIf { input.units == "f" },
            TREND_REPLY_SHAPE,
        )

        return built(
            system = SYSTEM_TREND,
            data = data(Names.ROW_IDS),
            hashed = data(Names.uids(logs, input.beans, input.grinders)),
            guidance = guidance,
            flags = flags,
            brewCount = logs.size,
            ratedCount = rated,
            // Total even on an empty log, so a preview can never crash on first().
            newestBrewAt = logs.firstOrNull()?.startedAt ?: 0L,
            maxTokens = TREND_MAX_TOKENS,
            expected = TREND_EXPECTED_OUT,
        )
    }

    /** First 16 lowercase hex characters of SHA-256 over the UTF-8 bytes of [text]. */
    fun sha256Hex16(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }.take(16)
    }

    // -------------------------------------------------------------- principles

    private val TREND_TOPICS = listOf("extraction", "grind", "agitation", "bed", "flow", "temperature", "ratio", "freshness", "water")

    /**
     * Which topics this brew's evidence points at, most telling first. App heuristic for picking
     * which sourced principles to send; it never changes what a principle says.
     */
    internal fun topicsFor(log: BrewLogEntity, method: BrewMethod?, water: CatalogWater?): List<String> = buildList {
        if (method == BrewMethod.Espresso) add("espresso")
        val bedTrouble = (log.bedShape != BedShape.Unset && log.bedShape != BedShape.Flat) ||
            log.channeling == Channeling.Obvious
        if (bedTrouble) {
            add("bed")
            add("agitation")
            add("flow")
        }
        add("extraction")
        add("grind")
        if (!bedTrouble) {
            add("agitation")
            add("flow")
            add("bed")
        }
        add("temperature")
        if (log.daysOffRoast != null) add("freshness")
        if (water != null) add("water")
        add("ratio")
        add("recipe")
    }

    /**
     * The principles to send, at most [MAX_PRINCIPLES]: only those for one of [methods] (or for
     * every method); the recipe author's own first; then one per topic in the order of [topics],
     * then a second per topic, and so on, so the list covers the brew instead of dwelling on one
     * subject. Within a topic, file order decides.
     */
    internal fun principlesFor(
        all: List<Principle>,
        methods: Set<BrewMethod>,
        author: String,
        topics: List<String>,
    ): List<Principle> {
        val ids = methods.map { it.id }.toSet()
        val applicable = all.filter { p -> p.appliesTo.isEmpty() || p.appliesTo.any { it in ids } }
        val authorWords = author.lowercase()
        // "Tetsu Kasuya" and "Matt Perger (Barista Hustle)" both match on the surname.
        fun surname(p: Principle): String = p.who.substringBefore(" (").trim().substringAfterLast(' ').lowercase()
        val (own, others) = applicable.partition { p -> surname(p).let { it.length >= 3 && it in authorWords } }

        // Topics the caller didn't rank still get a turn, after the ones it did.
        val order = (topics + others.map { it.topic }).distinct()
        val byTopic = order.map { topic -> ArrayDeque(others.filter { it.topic == topic }) }
        val picked = own.toMutableList()
        while (picked.size < MAX_PRINCIPLES && byTopic.any { it.isNotEmpty() }) {
            byTopic.forEach { queue -> queue.removeFirstOrNull()?.let { picked += it } }
        }
        return picked.take(MAX_PRINCIPLES)
    }

    private fun principlesBlock(list: List<Principle>): String? {
        if (list.isEmpty()) return null
        val rows = list.map { p ->
            buildJsonObject {
                put("who", JsonPrimitive(p.who))
                put("topic", JsonPrimitive(p.topic))
                put("text", JsonPrimitive(p.text))
            }
        }
        return "PRINCIPLES (from the people whose methods these are)\n" + jsonRows(rows)
    }

    // ------------------------------------------------------------------ blocks

    /**
     * What the text calls a brew, a coffee and a grinder. The model reads short row ids
     * ([ROW_IDS]) and answers with them. The input hash reads the same text with uids instead,
     * because row ids are this install's own and change when a backup is restored.
     */
    private class Names private constructor(
        private val brews: Map<Long, String>?,
        private val beans: Map<Long, String>,
        private val grinders: Map<Long, String>,
    ) {
        private val rowIds: Boolean get() = brews == null

        /** A brew after a '#'. */
        fun brewTag(id: Long): String = if (brews == null) id.toString() else brews[id].orEmpty()

        fun brew(id: Long): JsonElement = if (brews == null) JsonPrimitive(id) else str(brews[id])

        fun bean(id: Long?): JsonElement = if (rowIds) long(id) else str(id?.let { beans[it] })

        fun grinder(id: Long?): JsonElement = if (rowIds) long(id) else str(id?.let { grinders[it] })

        // By uid, the order is the same on every install. A coffee or grinder that was deleted
        // has no uid and no row in a backup, so the hash leaves it out here as a restore would.
        fun beanOrder(ids: List<Long>): List<Long> =
            if (rowIds) ids.sorted() else ids.filter { it in beans }.sortedBy { beans.getValue(it) }

        fun grinderOrder(ids: List<Long>): List<Long> =
            if (rowIds) ids.sorted() else ids.filter { it in grinders }.sortedBy { grinders.getValue(it) }

        companion object {
            val ROW_IDS = Names(null, emptyMap(), emptyMap())

            fun uids(brews: List<BrewLogEntity>, beans: Map<Long, BeanEntity>, grinders: Map<Long, GrinderEntity>) = Names(
                brews = brews.associate { it.id to it.uid },
                beans = beans.mapValues { it.value.uid },
                grinders = grinders.mapValues { it.value.uid },
            )
        }
    }

    private fun built(
        system: String,
        data: List<String>,
        /** [data] again with each row id written as the record's uid: what the hash is made from. */
        hashed: List<String>,
        guidance: List<String>,
        flags: List<DataFlag>,
        brewCount: Int,
        ratedCount: Int,
        newestBrewAt: Long,
        maxTokens: Int,
        expected: Int,
        grindAnchor: Int? = null,
        valueAnchor: Double? = null,
        photoCount: Int = 0,
    ): BuiltPrompt {
        val user = (data + guidance).joinToString("\n\n")
        return BuiltPrompt(
            system = system,
            user = user,
            flags = flags,
            // The data only: VERSION already stands for the wording, and a new principle or a
            // switch to °F must not make an untouched brew read as "edited since". Uids, not row
            // ids: the same brew restored from a backup under new row ids is still untouched.
            inputHash = HASH_SCHEME + sha256Hex16("$VERSION\n" + hashed.joinToString("\n\n")),
            brewCount = brewCount,
            ratedCount = ratedCount,
            newestBrewAt = newestBrewAt,
            approxPromptTokens = ((system.length + user.length) / 3.5).toInt(),
            maxTokens = maxTokens,
            expectedCompletionTokens = expected,
            grindAnchor = grindAnchor,
            valueAnchor = valueAnchor,
            photoCount = photoCount,
        )
    }

    private fun grinderBlock(
        log: BrewLogEntity,
        grinder: GrinderEntity?,
        method: BrewMethod?,
        hidden: Set<String>,
        anchor: BrewLogEntity?,
        clicksFrom: Int?,
        valueFrom: Double?,
        names: Names,
    ): String {
        if (grinder == null) {
            return if (log.grinderLabel.isBlank()) {
                "GRINDER\nnone recorded"
            } else {
                "GRINDER\nno longer saved in the app (logged as \"${log.grinderLabel}\"), so which way is finer is unknown"
            }
        }
        val scale = grinder.scale()
        val moves = scale.moves(method)
        val obj = buildJsonObject {
            put("name", JsonPrimitive(grinder.display))
            put("catalogId", str(grinder.catalogId))
            put("burrs", str(burrs(grinder)))
            put("scale", scaleObject(grinder, scale))
            if (scale.usesClicks) {
                field(K_CURRENT_CLICKS, int(log.grindClicks), hidden)
                // inTurns: the prompt keeps the long form for a full turn ("1 turn + 0.0"), byte for
                // byte what the web app sends, so the same brew gives the same inputHash on both.
                field(K_CURRENT_DIAL, str(log.grindClicks?.let { scale.reading(it.toDouble(), inTurns = true) }), hidden)
                put("lastTrustedClicks", int(anchor?.grindClicks))
            } else {
                // The dial number, else the text the person typed.
                val setting = log.grindValue?.let { dbl(it, places = 2) } ?: str(log.grindSetting.ifBlank { null })
                field(K_CURRENT_SETTING, setting, hidden)
                put("lastTrustedSetting", dbl(anchor?.grindValue, places = 2))
            }
            put(
                "lastTrustedFrom",
                str(anchor?.let { "brew #${names.brewTag(it.id)}, ${DATE.format(Instant.ofEpochMilli(it.startedAt))}, ${it.recipeName}" }),
            )
            put("moveSizes", moveSizes(scale, method))
            put("startingRange", startingRange(scale, method))
            val example = if (scale.usesClicks) {
                clicksFrom?.let { clickExample(it, scale, moves.clearMiss) }
            } else {
                valueFrom?.let { valueExample(it, scale, moves.clearMiss) }
            }
            put("example", str(example))
        }
        return "GRINDER\n$obj"
    }

    /** How this grinder's dial works, in its own units. */
    private fun scaleObject(g: GrinderEntity, scale: GrindScale): JsonObject = buildJsonObject {
        put("adjustsBy", JsonPrimitive(adjustsBy(g)))
        put("unit", str(scale.unit.ifBlank { null }))
        put("finerDirection", JsonPrimitive(scale.finerDirection))
        put("clicksPerTurn", int(scale.clicksPerRotation.takeIf { scale.usesClicks }))
        put("micronsPerStep", dbl(scale.micronsPerStep, places = 2))
        put("min", dbl(scale.min, places = 2))
        put("max", dbl(scale.max, places = 2))
        put("howTheDialReads", str(scale.dialReadingHelp.ifBlank { null }))
        put("whatTheyType", str(scale.entryHint.ifBlank { null }))
        put("dialFormat", JsonPrimitive(scale.dial.encode()))
    }

    /** The app's own rule of thumb for how big a move is on this grinder, and what it rests on. */
    private fun moveSizes(scale: GrindScale, method: BrewMethod?): JsonObject {
        val m = scale.moves(method)
        val basis = when (m.basis) {
            MoveSizes.Basis.Microns ->
                "${num(scale.micronsPerStep ?: 0.0)} µm per ${if (scale.usesClicks) "click" else "step"} (app rule of thumb)"
            MoveSizes.Basis.Range ->
                "a share of this grinder's ${method?.label?.lowercase(Locale.US)?.plus(" ").orEmpty()}starting range (app rule of thumb)"
            MoveSizes.Basis.LegacyTurn ->
                "a full turn of ${scale.clicksPerRotation} clicks is a clear miss (app rule of thumb)"
            MoveSizes.Basis.Generic -> "steps of ${scale.format(scale.step)} (app rule of thumb)"
        }
        return buildJsonObject {
            put("nudge", dbl(m.nudge, places = 2))
            put("clearMiss", dbl(m.clearMiss, places = 2))
            put("maxAtOnce", dbl(m.maxAtOnce, places = 2))
            put("basis", JsonPrimitive(basis))
        }
    }

    /** The catalog's starting range for this method, with who it comes from. A sanity check, never a target. */
    private fun startingRange(scale: GrindScale, method: BrewMethod?): JsonElement {
        val range = scale.rangeFor(method) ?: return JsonNull
        return buildJsonObject {
            put("method", JsonPrimitive(method?.id.orEmpty()))
            put("from", dbl(range.from, places = 2))
            put("to", dbl(range.to, places = 2))
            put("confidence", str(scale.confidence))
            put("from_who", strings(scale.rangeSources.map { it.who }.filter { it.isNotBlank() }.distinct()))
        }
    }

    /**
     * A worked example on this grinder from a trusted setting, so the direction of "finer" and
     * the size of a clear miss are shown with real numbers rather than left to the model.
     */
    private fun clickExample(from: Int, scale: GrindScale, clearMiss: Double): String? {
        fun finerBy(step: Int): Int = if (scale.lowerIsFiner) from - step else from + step
        val full = clearMiss.roundToInt().coerceAtLeast(1)
        val (step, to) = when {
            finerBy(full) > 0 -> full to finerBy(full)
            finerBy(2) > 0 -> 2 to finerBy(2)
            else -> return null
        }
        val words = when {
            scale.talksInTurns && step == scale.clicksPerRotation -> "one full turn finer"
            else -> "$step clicks finer"
        }
        val readFrom = scale.reading(from.toDouble(), inTurns = true)
        val readTo = scale.reading(to.toDouble(), inTurns = true)
        return if (scale.dial.showsDialNumber && readFrom != null && readTo != null) {
            "$words from $from (dial $readFrom) is $to (dial $readTo)"
        } else {
            "$words from $from is $to clicks"
        }
    }

    private fun valueExample(from: Double, scale: GrindScale, clearMiss: Double): String? {
        val to = if (scale.lowerIsFiner) from - clearMiss else from + clearMiss
        if (to < scale.min || (scale.max > scale.min && to > scale.max)) return null
        val unit = if (scale.unit.isBlank()) "" else " ${scale.unit}"
        return "${scale.format(clearMiss)}$unit finer from ${scale.format(from)} is ${scale.format(to)}"
    }

    private fun recipeBlock(log: BrewLogEntity, recipe: Recipe?): String {
        if (recipe == null) {
            return if (log.recipeName.isBlank()) {
                "RECIPE\nnone, brewed without a saved recipe"
            } else {
                "RECIPE\nnone, brewed as \"${log.recipeName}\" without a saved recipe"
            }
        }
        val lastPour = DataQuality.lastPourEndSec(recipe)
        val target = recipe.totalSec.takeIf { it > 0 }
        val obj = buildJsonObject {
            put("name", JsonPrimitive(recipe.name))
            put("author", JsonPrimitive(recipe.author))
            put("brewer", JsonPrimitive(recipe.brewer.label))
            put("doseG", dbl(recipe.doseG.takeIf { it > 0 }))
            put("waterG", dbl(recipe.waterG.takeIf { it > 0 }))
            put("ratio", str(recipe.takeIf { it.doseG > 0 && it.waterG > 0 }?.ratioLabel))
            put("tempC", JsonPrimitive(recipe.tempC))
            put("grindAdvice", JsonPrimitive(recipe.grind))
            put("lastPourEndsAtSec", int(lastPour))
            put("targetTotalSec", int(target))
            put("expectedDrawdownSec", int(if (lastPour != null && target != null) (target - lastPour).takeIf { it >= 0 } else null))
            put(
                "steps",
                JsonArray(recipe.steps.map { JsonPrimitive("${it.startSec.asClock()}-${it.endSec.asClock()} ${promptTitle(it.title)}") }),
            )
        }
        return "RECIPE (what was planned)\n$obj"
    }

    private fun brewerBlock(brewer: Brewer?, log: BrewLogEntity): String {
        if (brewer == null && log.brewer.isBlank()) return "BREWER\nnot recorded"
        val obj = buildJsonObject {
            put("name", JsonPrimitive(brewer?.label ?: log.brewer))
            put("category", str(brewer?.category))
            put("method", str(brewer?.method?.id))
            put("filter", str(brewer?.filter))
        }
        return "BREWER\n$obj"
    }

    private fun waterBlock(water: CatalogWater?): String {
        if (water == null) return "WATER\nnot recorded"
        val obj = buildJsonObject {
            put("label", JsonPrimitive(water.label))
            put("kind", JsonPrimitive(water.kind))
            put("ghPpm", dbl(water.ghPpm))
            put("khPpm", dbl(water.khPpm))
        }
        return "WATER\n$obj"
    }

    private fun coffeeBlock(log: BrewLogEntity, bean: BeanEntity?, hidden: Set<String>): String {
        if (bean == null && log.beanLabel.isBlank()) return "COFFEE\nnone recorded"
        val roaster = Catalog.roaster(bean?.roasterId)
        val process = Catalog.process(bean?.processId)
        val obj = buildJsonObject {
            put("name", str(bean?.display ?: log.beanLabel))
            put("roaster", str(bean?.roaster?.ifBlank { null } ?: roaster?.name))
            put("roasterCountry", str(roaster?.country?.ifBlank { null }))
            put("origin", str(bean?.let { originOf(it) }))
            put("growingRegion", str(bean?.region?.ifBlank { null }))
            put("farm", str(bean?.farm?.ifBlank { null }))
            put("producer", str(bean?.producer?.ifBlank { null }))
            put("washingStation", str(bean?.washingStation?.ifBlank { null }))
            put("lotType", str(Catalog.label(TermKind.LotType, bean?.lotTypeId)))
            put("species", str(Catalog.label(TermKind.Species, bean?.speciesId)))
            put("varietals", bean?.let { varietals(it) }?.let { strings(it) } ?: JsonNull)
            put("process", str(bean?.let { processLabel(it) }))
            put("processGroup", str(process?.group?.ifBlank { null }))
            put(
                "decaf",
                str(bean?.takeIf { it.isDecaf }?.let { Catalog.label(TermKind.Decaf, it.decafMethodId) ?: "yes, method not recorded" }),
            )
            put("roastLevel", str(bean?.let { roastLabel(it) }))
            put("roastIntent", str(Catalog.label(TermKind.RoastIntent, bean?.roastIntentId)))
            put("altitudeM", str(bean?.altitudeMasl?.ifBlank { null }))
            put("harvest", str(bean?.harvest?.ifBlank { null }))
            // The log's snapshot from brew day. Today's age would rewrite history and change the
            // hash every day, marking every stored analysis stale.
            field(K_DAYS_OFF_ROAST, long(log.daysOffRoast), hidden)
            put("roastDateKnown", JsonPrimitive(log.daysOffRoast != null))
            put("restDaysSuggested", int(bean?.restDays))
            put("roasterNotes", str(bean?.tastingNotes?.ifBlank { null }))
            put("bagNotes", str(bean?.notes?.ifBlank { null }))
        }
        return "COFFEE\n$obj"
    }

    /**
     * Click grinders send clicks only: their grindSetting is a text snapshot of the dial words
     * ("75 clicks (7 turns + 5)") that a corrected scale would contradict.
     */
    private fun sendsGrindText(log: BrewLogEntity, grinder: GrinderEntity?): Boolean =
        if (grinder != null) !grinder.usesClicks else log.grindClicks == null

    private fun thisBrew(
        log: BrewLogEntity,
        recipe: Recipe?,
        grinder: GrinderEntity?,
        distrusted: Set<String>,
        hidden: Set<String>,
        names: Names,
    ): JsonObject = buildJsonObject {
        put("id", names.brew(log.id))
        put("date", JsonPrimitive(DATE_TIME.format(Instant.ofEpochMilli(log.startedAt))))
        field(K_DOSE, dbl(log.doseG.takeIf { it > 0 }), hidden)
        field(K_WATER, dbl(log.waterG.takeIf { it > 0 }), hidden)
        field(K_RATIO, str(ratioOf(log)), hidden)
        field(K_TEMP, JsonPrimitive(log.tempC), hidden)
        field(K_GRIND_CLICKS, int(log.grindClicks), hidden)
        if (sendsGrindText(log, grinder)) {
            field(K_GRIND_VALUE, dbl(log.grindValue, places = 2), hidden)
            field(K_GRIND_SETTING, str(log.grindSetting.ifBlank { null }), hidden)
        }
        field(K_TOTAL, int(log.totalTimeSec.takeIf { it > 0 }), hidden)
        field(K_VS_RECIPE, int(vsRecipe(log, recipe)), hidden)
        field(K_DRAWDOWN, int(log.drawdownSec), hidden)
        field(K_BEVERAGE, dbl(log.beverageG), hidden)
        field(K_RETAINED, dbl(DataQuality.retainedPerGram(log)), hidden)
        // TDS lives in the second decimal, so it keeps two.
        field(K_TDS, dbl(log.tdsPercent, places = 2), hidden)
        field(K_EY, dbl(log.extractionYield), hidden)
        put("flow", JsonPrimitive(flowLabel(log.flowFeel)))
        put(
            "bed",
            buildJsonObject {
                put("shape", JsonPrimitive(bedLabel(log.bedShape)))
                put("surface", JsonPrimitive(if (log.sludge == Sludge.Unset) "not recorded" else log.sludge.label))
                put("channelling", JsonPrimitive(if (log.channeling == Channeling.Unset) "not recorded" else log.channeling.label))
                put("notes", JsonPrimitive(log.bedNotes))
            },
        )
        field(K_TASTE, tasteObject(log.taste), hidden)
        put(K_OVERALL, JsonPrimitive(log.overall))
        put("tastedOf", strings(log.descriptors))
        put("faults", strings(log.faults))
        put("notes", JsonPrimitive(log.notes))
        put("planForNextTime", JsonPrimitive(log.nextTime))
        put("distrusted", strings(distrusted.sorted()))
    }

    /** One compact line per earlier brew (brew prompt) or per brew (trend prompt). */
    private fun row(
        h: BrewLogEntity,
        recipes: Map<String, Recipe>,
        beans: Map<Long, BeanEntity>,
        grinders: Map<Long, GrinderEntity>,
        flags: List<DataFlag>,
        trend: Boolean,
        names: Names,
    ): JsonObject {
        val distrusted = DataQuality.distrustedFields(flags, h.id)
        val hidden = redactedKeys(distrusted)
        val recipe = recipes[h.recipeId]
        val bean = h.beanId?.let { beans[it] }
        val grinder = h.grinderId?.let { grinders[it] }
        return buildJsonObject {
            put("id", names.brew(h.id))
            put("date", JsonPrimitive(DATE.format(Instant.ofEpochMilli(h.startedAt))))
            if (trend) put("recipeId", str(h.recipeId.ifBlank { null }))
            put("recipe", str(h.recipeName.ifBlank { null }))
            if (trend) put("coffeeId", names.bean(h.beanId))
            put("coffee", str(bean?.display ?: h.beanLabel.ifBlank { null }))
            if (trend) put("grinderId", names.grinder(h.grinderId))
            put("grinder", str(grinder?.display ?: h.grinderLabel.ifBlank { null }))
            if (trend) put("brewer", str((DataQuality.brewerOf(h, recipe)?.label ?: h.brewer).ifBlank { null }))
            field(K_GRIND_CLICKS, int(h.grindClicks), hidden)
            if (sendsGrindText(h, grinder)) {
                field(K_GRIND_VALUE, dbl(h.grindValue, places = 2), hidden)
                field(K_GRIND_SETTING, str(h.grindSetting.ifBlank { null }), hidden)
            }
            field(K_TEMP, JsonPrimitive(h.tempC), hidden)
            field(K_RATIO, str(ratioOf(h)), hidden)
            field(K_TOTAL, int(h.totalTimeSec.takeIf { it > 0 }), hidden)
            field(K_VS_RECIPE, int(vsRecipe(h, recipe)), hidden)
            field(K_DRAWDOWN, int(h.drawdownSec), hidden)
            field(K_RETAINED, dbl(DataQuality.retainedPerGram(h)), hidden)
            if (trend) {
                field(K_DAYS_OFF_ROAST, long(h.daysOffRoast), hidden)
                put("water", str(h.waterId?.takeIf { it.isNotBlank() }?.let { Catalog.water(it)?.label ?: it }))
            }
            put("flow", JsonPrimitive(flowLabel(h.flowFeel)))
            put("bed", JsonPrimitive(bedLabel(h.bedShape)))
            put(K_OVERALL, JsonPrimitive(h.overall))
            put("faults", strings(h.faults))
            put("tastedOf", strings(h.descriptors))
            field(K_TASTE, JsonPrimitive(tasteLine(h.taste)), hidden)
            put("distrusted", strings(distrusted.sorted()))
        }
    }

    /** "2 attached, in this order: #1 bed (2026-11-17 08:14Z, 1a2b3c4d, 212 KB), #2 cup (…)" or "none". */
    private fun photosBlock(photos: List<PhotoMeta>): String {
        if (photos.isEmpty()) return "PHOTOS\nnone"
        val parts = photos.mapIndexed { i, p ->
            val kb = ((p.bytes + 512) / 1024).coerceAtLeast(1)
            "#${i + 1} ${p.kind} (${DATE_TIME.format(Instant.ofEpochMilli(p.takenAt))}, ${p.uid.take(8)}, $kb KB)"
        }
        return "PHOTOS\n${photos.size} attached, in this order: ${parts.joinToString(", ")}"
    }

    private fun dataQualityBlock(flags: List<DataFlag>, names: Names): String {
        val distrust = flags.filter { it.severity == FlagSeverity.Distrust }
        val notes = flags.filter { it.severity == FlagSeverity.Note }
        fun lines(list: List<DataFlag>): String =
            if (list.isEmpty()) "No problems found." else list.joinToString("\n") { "- #${names.brewTag(it.brewId)} ${it.fieldLabel}: ${it.promptMessage}" }
        return "$DATA_QUALITY_TITLE\nDistrusted, sent as null, do not use:\n${lines(distrust)}\nContext notes:\n${lines(notes)}"
    }

    // ----------------------------------------------------------------- helpers

    /** Distrusted keys go out as null; everything else as given. */
    private fun JsonObjectBuilder.field(key: String, value: JsonElement, hidden: Set<String>) {
        put(key, if (key in hidden) JsonNull else value)
    }

    /** One JSON value per line, so long lists stay readable and the whole block stays valid JSON. */
    private fun jsonRows(items: List<JsonObject>): String =
        if (items.isEmpty()) "[]" else items.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]")

    private fun str(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    private fun int(v: Int?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    private fun long(v: Long?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    /** Whole numbers as integers, everything else rounded to [places] decimals. */
    private fun dbl(v: Double?, places: Int = 1): JsonElement {
        if (v == null || !v.isFinite()) return JsonNull
        val scale = if (places == 2) 100.0 else 10.0
        val r = (v * scale).roundToLong() / scale
        return if (r % 1.0 == 0.0) JsonPrimitive(r.roundToLong()) else JsonPrimitive(r)
    }

    /** "20" or "12.5": a number inside a sentence. */
    private fun num(v: Double): String {
        val r = (v * 100).roundToLong() / 100.0
        return if (r % 1.0 == 0.0) r.roundToLong().toString() else r.toString()
    }

    private fun strings(items: List<String>): JsonArray = JsonArray(items.map { JsonPrimitive(it) })

    private fun ratioOf(log: BrewLogEntity): String? =
        if (log.doseG > 0 && log.waterG > 0) "1:" + oneDp(log.waterG / log.doseG) else null

    private fun vsRecipe(log: BrewLogEntity, recipe: Recipe?): Int? {
        val target = recipe?.totalSec?.takeIf { it > 0 } ?: return null
        return if (log.totalTimeSec > 0) log.totalTimeSec - target else null
    }

    private fun flowLabel(flow: FlowFeel): String = if (flow == FlowFeel.Unset) "not recorded" else flow.label

    private fun bedLabel(shape: BedShape): String = if (shape == BedShape.Unset) "not recorded" else shape.label

    private fun tasteObject(t: TasteProfile): JsonObject = buildJsonObject {
        put("acidity", JsonPrimitive(t.acidity))
        put("sweetness", JsonPrimitive(t.sweetness))
        put("bitterness", JsonPrimitive(t.bitterness))
        put("body", JsonPrimitive(t.body))
        put("balance", JsonPrimitive(t.balance))
        put("aftertaste", JsonPrimitive(t.aftertaste))
        put("clarity", JsonPrimitive(t.clarity))
    }

    private fun tasteLine(t: TasteProfile): String =
        "acidity ${t.acidity}, sweetness ${t.sweetness}, bitterness ${t.bitterness}, body ${t.body}, " +
            "balance ${t.balance}, aftertaste ${t.aftertaste}, clarity ${t.clarity}"

    private fun burrs(g: GrinderEntity): String? {
        val size = g.burrSizeMm.trim().removeSuffix("mm").trim()
        val parts = listOfNotNull(
            g.burrType.takeIf { it != BurrType.Unknown }?.label,
            size.takeIf { it.isNotEmpty() }?.let { "$it mm" },
        )
        return parts.joinToString(", ").ifEmpty { null }
    }

    private fun adjustsBy(g: GrinderEntity): String = when (g.settingStyle) {
        SettingStyle.Clicks -> "clicks"
        SettingStyle.Numbers -> "numbered dial"
        SettingStyle.Microns -> "microns"
        SettingStyle.Stepless -> "stepless"
    }

    /** The country as typed, else the catalog's. */
    private fun originOf(b: BeanEntity): String? = b.origin.ifBlank { null } ?: Catalog.origin(b.originId)?.country

    /** Catalog varietal labels first, then anything typed by hand that isn't one of them. */
    private fun varietals(b: BeanEntity): List<String>? {
        val labels = b.varietalIds.map { Catalog.label(TermKind.Varietal, it) ?: it }
        val typed = b.varietal.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val all = labels + typed.filter { t -> labels.none { it.equals(t, ignoreCase = true) } }
        return all.ifEmpty { null }
    }

    private fun processLabel(b: BeanEntity): String = Catalog.label(TermKind.Process, b.processId) ?: "Unknown"

    private fun roastLabel(b: BeanEntity): String = Catalog.label(TermKind.RoastLevel, b.roastLevelId) ?: "Unknown"
}
