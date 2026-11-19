package com.laksh.coffee

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import com.laksh.coffee.data.ai.AnalysisRepository
import com.laksh.coffee.data.ai.AnalysisTarget
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.data.photos.AddPhotoResult
import com.laksh.coffee.data.photos.PhotoKinds
import com.laksh.coffee.data.photos.PhotoOwner
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Channeling
import com.laksh.coffee.domain.FlowFeel
import com.laksh.coffee.domain.Sludge
import com.laksh.coffee.domain.TasteProfile
import com.laksh.coffee.domain.ai.AiJson
import com.laksh.coffee.domain.ai.BrewAnalysis
import com.laksh.coffee.domain.ai.Change
import com.laksh.coffee.domain.ai.CoffeeNote
import com.laksh.coffee.domain.ai.Experiment
import com.laksh.coffee.domain.ai.ExperimentBrew
import com.laksh.coffee.domain.ai.GrindMove
import com.laksh.coffee.domain.ai.Pattern
import com.laksh.coffee.domain.ai.StoredResult
import com.laksh.coffee.domain.ai.TrendAnalysis
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.LegacyIds
import com.laksh.coffee.domain.recipes.ExpertRecipes
import com.laksh.coffee.ui.onboarding.Onboarding
import com.laksh.coffee.ui.recipes.RecipeDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId

/**
 * Debug-build-only hooks for driving the app from adb, so screens can be screenshotted on an
 * emulator with realistic data. The release source set has a no-op twin with the same API.
 *
 *   adb shell am start -S -n com.laksh.coffee.debug/com.laksh.coffee.MainActivity \
 *       --ez seed true --es route history
 */
object DebugTools {

    fun startRoute(intent: Intent?): String? = intent?.getStringExtra("route")

    /**
     * True when adb is driving this launch (a seed or a route was asked for). Such a launch goes
     * where it was told and is never sent to first-launch setup, which would otherwise open on
     * the still-empty database before the seed has landed.
     */
    fun isDriven(intent: Intent?): Boolean =
        intent?.hasExtra("seed") == true || intent?.hasExtra("route") == true

    /**
     * Seeds the app as someone who has finished setup would have it, but only into an empty log:
     * a grinder taken from the catalog, two coffees (one with every detail filled in), a recipe
     * of their own, three weeks of brews, one drawn sample photo and three labelled sample
     * analyses.
     */
    suspend fun seedIfRequested(intent: Intent?, app: CoffeeApp) {
        if (intent?.getBooleanExtra("seed", false) != true) return
        val repo = app.repository
        if (repo.brewCount.first() > 0) {
            // A log that is already seeded keeps its brews. Its samples are written again from
            // the text below, so a copy edit here shows on the next screenshots without clearing
            // the brews. Any other log only has its samples re-stamped, so a prompt change does
            // not show them as out of date.
            try {
                val logs = repo.allLogs.first().sortedBy { it.startedAt }
                if (logs.size == SEED_BREWS && app.analysis.deleteSamples() > 0) {
                    seedSampleAnalyses(app, logs.map { it.id })
                } else {
                    app.analysis.restampSamples()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sample analyses were not refreshed", e)
            }
            return
        }

        val today = LocalDate.now()
        // A coffee picked from the roaster list: linked to the catalog, little else known.
        val onyx = BeanEntity(
            name = "Southern Weather",
            roaster = "Onyx Coffee Lab",
            roasterId = "onyx-coffee-lab",
            lotTypeId = "blend",
            roastLevelId = "light",
            roastedOnEpochDay = today.minusDays(30).toEpochDay(),
        )
        // A made-up lot from a made-up roaster with every field of the bean editor filled in, so
        // the card, the editor and the prompt can be seen with a full bag. Not a real coffee.
        val kenya = BeanEntity(
            name = "Kenya AA",
            roaster = "Local",
            origin = "Kenya",
            originId = "kenya",
            region = "Nyeri",
            lotTypeId = "washing-station",
            washingStation = "Example Factory",
            producer = "Smallholder cooperative",
            speciesId = "arabica",
            varietalIds = listOf("sl28", "sl34"),
            processId = "washed",
            roastLevelId = "light",
            roastIntentId = "filter",
            altitudeMasl = "1700-1900",
            harvest = "2025/26",
            tastingNotes = "Blackcurrant, grapefruit, brown sugar",
            bagSizeG = 250.0,
            restDays = 10,
            roastedOnEpochDay = today.minusDays(12).toEpochDay(),
            openedOnEpochDay = today.minusDays(8).toEpochDay(),
        )
        val onyxId = repo.upsertBean(onyx)
        val kenyaId = repo.upsertBean(kenya)

        // The grinder as setup saves it when it is picked from the list: the catalog's own scale
        // (100 clicks a turn, 20 µm a click, a numbered dial), so captions read "dial 7.6", the
        // range card has 1Zpresso's ranges and brew setup offers a starting point. The hand-typed
        // row is only the fallback for a catalog that failed to load.
        val grinder = (
            Catalog.grinder(K_ULTRA)?.let { Onboarding.rowFor(it, emptyList()) }
                ?: GrinderEntity(
                    brand = "1Zpresso",
                    model = "K-Ultra",
                    burrType = BurrType.Conical,
                    burrSizeMm = "48",
                    settingStyle = SettingStyle.Clicks,
                    clicksPerRotation = 100,
                    micronsPerClick = 20.0,
                    lowerIsFiner = true,
                    maxSetting = 200.0,
                    isDefault = true,
                )
            ).copy(defaultFilterSetting = 76.0)
        val grinderId = repo.upsertGrinder(grinder)

        // A recipe of their own: the builder's "your copy" of a built-in, scaled to one cup.
        runCatching {
            ExpertRecipes.byId(V60)?.let { base ->
                val mine = RecipeDraft.duplicate(base.scaledToDose(15.0), author = "").toRecipe()
                repo.saveRecipe(mine.copy(id = CUSTOM_RECIPE, name = "One-cup V60 (mine)"))
            }
        }

        data class B(
            val daysAgo: Long,
            val recipe: String,
            val kenya: Boolean,
            val clicks: Int,
            val tempC: Int,
            val total: Int,
            val drawdown: Int?,
            val cup: Double?,
            val taste: TasteProfile,
            val overall: Int,
            val faults: List<String> = emptyList(),
            val descriptors: List<String> = emptyList(),
            val bed: BedShape = BedShape.Flat,
            val flow: FlowFeel = FlowFeel.OnTarget,
            val notes: String = "",
            val nextTime: String = "",
            val favourite: Boolean = false,
        )

        // An arc worth charting: fast and sour, ten clicks (one number on the dial) finer, then
        // dialled in. Settings are total clicks from zero, so 76 is a dial reading of 7.6, inside
        // the pour-over range 1Zpresso publishes for this grinder.
        val brews = listOf(
            B(21, V60, false, 85, 95, 184, null, 432.0, TasteProfile(7, 3, 5, 5, 3, 4, 3), 4,
                faults = listOf("Bitter", "Sour"), flow = FlowFeel.Fast),
            // Deliberately messy like the owner's real log: 370 g in the cup from 500 g of water
            // leaves 130 g in 30 g of coffee, which the log check flags and the model must ignore.
            B(17, V60, false, 90, 95, 168, 63, 370.0, TasteProfile(7, 3, 6, 4, 4, 3, 4), 3,
                faults = listOf("Sour", "Bitter", "Hollow"), flow = FlowFeel.Fast),
            // Deliberately messy: untouched sliders, as real logs are.
            B(13, V60, false, 87, 95, 169, 64, null, TasteProfile(), 2,
                faults = listOf("Bitter", "Astringent", "Drying"), flow = FlowFeel.Unset),
            B(9, V60, false, 77, 95, 206, 101, 438.0, TasteProfile(5, 5, 4, 6, 6, 5, 6), 6,
                descriptors = listOf("Caramel", "Stone fruit"),
                notes = "Ten clicks finer, 87 to 77. Sweeter, finally."),
            // The 4:6 last pour ends at 3:15, so totals of 3:34 and 3:29 leave at most 19 and
            // 14 s of drawdown. The old 98 and 95 s were impossible, and the log check said so.
            B(7, KASUYA, true, 82, 92, 214, 19, 262.0, TasteProfile(6, 6, 3, 5, 7, 6, 7), 7,
                descriptors = listOf("Citrus", "Floral", "Juicy")),
            B(5, V60, false, 75, 100, 221, 112, 437.0, TasteProfile(5, 6, 4, 6, 7, 6, 6), 7,
                descriptors = listOf("Caramel", "Chocolate"), bed = BedShape.Domed),
            B(4, AEROPRESS, true, 55, 100, 200, null, 178.0, TasteProfile(6, 6, 4, 6, 6, 5, 5), 6,
                descriptors = listOf("Berry"), bed = BedShape.Unset, flow = FlowFeel.Unset),
            B(2, KASUYA, true, 80, 92, 209, 14, 259.0, TasteProfile(6, 7, 3, 5, 7, 7, 8), 8,
                descriptors = listOf("Floral", "Citrus", "Tea-like")),
            B(1, V60, false, 76, 100, 212, 105, 440.0, TasteProfile(5, 7, 3, 6, 7, 7, 7), 8,
                descriptors = listOf("Caramel", "Stone fruit", "Juicy"),
                nextTime = "Same again, it is dialled."),
            B(0, V60, false, 76, 100, 208, 102, 439.0, TasteProfile(5, 7, 3, 6, 8, 7, 7), 8,
                descriptors = listOf("Caramel", "Honey"), favourite = true),
        )

        // On a fresh install these are 1..10: ids[1] is the 370 g cup, ids[2] the 87-click 2/10
        // brew and ids[9] the latest.
        val ids = brews.mapIndexed { i, b ->
            val r = RECIPES.getValue(b.recipe)
            val bean = if (b.kenya) kenya else onyx
            val beanId = if (b.kenya) kenyaId else onyxId
            val day = today.minusDays(b.daysAgo)
            repo.insertLog(
                BrewLogEntity(
                    startedAt = day.atTime(8, 10 + i).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    recipeId = b.recipe,
                    recipeName = r.name,
                    recipeAuthor = r.author,
                    brewer = r.brewer,
                    brewerId = LegacyIds.BREWER[r.brewer],
                    beanId = beanId,
                    beanLabel = bean.display,
                    daysOffRoast = bean.roastedOnEpochDay?.let { day.toEpochDay() - it },
                    grinderId = grinderId,
                    grinderLabel = grinder.display,
                    // The same snapshot a real brew stores: "76 clicks (dial 7.6)".
                    grindSetting = grinder.describeClicks(b.clicks),
                    grindClicks = b.clicks,
                    doseG = r.dose,
                    waterG = r.water,
                    tempC = b.tempC,
                    totalTimeSec = b.total,
                    drawdownSec = b.drawdown,
                    beverageG = b.cup,
                    flowFeel = b.flow,
                    bedShape = b.bed,
                    sludge = if (b.bed == BedShape.Unset) Sludge.Unset else Sludge.Light,
                    channeling = if (b.bed == BedShape.Unset) Channeling.Unset else Channeling.None,
                    taste = b.taste,
                    overall = b.overall,
                    descriptors = b.descriptors,
                    faults = b.faults,
                    notes = b.notes,
                    nextTime = b.nextTime,
                    isFavourite = b.favourite,
                )
            )
        }

        app.settings.setLastRecipe(V60)
        app.settings.setDefaultBean(onyxId)
        app.settings.setDefaultGrinder(grinderId)
        // What setup would have saved: the brewers owned, a favourite roaster, the water used.
        app.settings.setOwnedBrewerIds(setOf("hario-v60-02", "aeropress"))
        app.settings.setFavouriteRoasterIds(setOf("onyx-coffee-lab"))
        app.settings.setDefaultWaterId("tap-filtered")
        // Seeded data stands for a finished setup, so Home shows no "Make it yours" card.
        app.settings.setOnboardingDone(true)
        app.settings.setOnboardingAuto(false)

        // Before the samples: a brew's photos are part of what an analysis is stamped with.
        seedSamplePhoto(app, ids.last())
        seedSampleAnalyses(app, ids)
    }

    /**
     * One drawn stand-in for a bed photo on the latest brew, pushed through the real import
     * (decode, scale, re-encode), so the strip, the viewer and the backup have a file to show.
     * It says what it is in its caption and is never ticked for sending to a model.
     */
    private suspend fun seedSamplePhoto(app: CoffeeApp, brewId: Long) {
        try {
            val source = File(app.cacheDir, "seed-photo.jpg")
            try {
                withContext(Dispatchers.IO) {
                    val bitmap = createBitmap(1024, 768)
                    val canvas = Canvas(bitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    // A flat bed from above: worktop, filter paper, wet grounds, a darker centre.
                    canvas.drawColor(0xFFD9D2C7.toInt())
                    paint.color = 0xFFF6F1E8.toInt()
                    canvas.drawCircle(512f, 384f, 330f, paint)
                    paint.color = 0xFF5B3A27.toInt()
                    canvas.drawCircle(512f, 384f, 262f, paint)
                    paint.color = 0xFF4A2E1F.toInt()
                    canvas.drawCircle(512f, 384f, 150f, paint)
                    paint.color = 0xFF3A3530.toInt()
                    paint.textSize = 34f
                    canvas.drawText("Sample photo", 32f, 60f, paint)
                    FileOutputStream(source).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bitmap.recycle()
                }
                val added = app.photos.addFromLibrary(Uri.fromFile(source), PhotoKinds.BED, PhotoOwner.Brew(brewId))
                if (added is AddPhotoResult.Added) {
                    app.photos.setCaption(added.photo.uid, "Sample photo for screenshots: a drawing, not a real bed.")
                    app.photos.setSendToAi(added.photo.uid, false)
                }
            } finally {
                withContext(Dispatchers.IO) { source.delete() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Debug only: a missing sample photo must not take the seeded brews down with it.
            Log.w(TAG, "Sample photo was not stored", e)
        }
    }

    /**
     * Three stored analyses, so screenshots show the result states on an emulator with no key.
     * They are written with host = SAMPLE_HOST, and the UI labels every one of them
     * "Sample for screenshots · not a model reply". Release builds never contain this code.
     */
    private suspend fun seedSampleAnalyses(app: CoffeeApp, ids: List<Long>) {
        // The move is drawn with the seeded grinder's own scale, as a real result's would be.
        val grinder = app.repository.allGrinders().let { all -> all.firstOrNull { it.isDefault } ?: all.firstOrNull() }

        // A: the 87-click, 2/10 brew. The one change is what the next brew in the seed did.
        val fastAndHarsh = BrewAnalysis(
            verdict = "needs_change",
            headline = "Fast and harsh: the grind is still too coarse.",
            diagnosis = "Three V60 brews in a row finished 26–42 s early (2:48–3:04 against 3:30) and each " +
                "was marked sour or bitter with a thin, drying finish. Fast and harsh together means water " +
                "raced through: most of the bed under-extracted while the fines gave up bitterness. At 85–90 " +
                "clicks you have been circling the same coarse zone.",
            confidence = "medium",
            evidence = listOf(
                "2:49 total against the recipe's 3:30, 41 s fast",
                "Drawdown 64 s against about 105 s the recipe leaves after the last pour",
                "Bitter, astringent and drying all ticked, overall 2/10",
                "The 85- and 90-click brews were also fast and sour",
            ),
            change = Change(
                variable = "grind",
                instruction = "87 → 77 clicks, one number finer on the dial",
                from = "87 clicks",
                to = "77 clicks",
                grindClicksFrom = 87,
                grindClicksTo = 77,
            ),
            expectIfRight = "Total time near 3:20–3:35, drawdown around 100 s, and a sweeter cup with the " +
                "drying finish gone.",
            ifWrong = "If it runs past 3:45 and tastes heavier and more bitter, you overshot: go back 4 clicks to 81.",
            keep = "Same beans, 30 g to 500 g, 95 °C and the same pours, so grind is the only thing that moved.",
            dataWarnings = listOf(
                "Taste sliders were never moved on this brew, so only the fault chips and the 2/10 were used.",
            ),
        )
        insertSample(
            app = app,
            target = AnalysisTarget.Brew(ids[2]),
            result = StoredResult(
                brew = fastAndHarsh,
                grindMove = GrindMove(
                    from = 87,
                    to = 77,
                    clicksPerTurn = grinder?.clicksPerRotation ?: 100,
                    lowerIsFiner = grinder?.lowerIsFiner ?: true,
                    dialFormat = grinder?.scale()?.dial?.encode() ?: "number-tick:10",
                ),
            ),
            rawText = AiJson.stored.encodeToString(BrewAnalysis.serializer(), fastAndHarsh),
            promptTokens = 3180,
            completionTokens = 1260,
        )

        // B: the latest brew, where the honest answer is "change nothing".
        val dialledIn = BrewAnalysis(
            verdict = "dialled_in",
            headline = "Sweet, clean and on time.",
            diagnosis = "3:28 against the recipe's 3:30 with a 102 s drawdown, a flat bed and no faults. " +
                "Sweetness 7 and balance 8 say the extraction landed; there is nothing here to fix.",
            confidence = "high",
            evidence = listOf(
                "3:28 against the recipe's 3:30",
                "Drawdown 102 s, right where the recipe puts it",
                "Sweetness 7, balance 8, no faults",
            ),
            change = Change(
                variable = "none",
                instruction = "Brew it exactly the same",
                from = "",
                to = "",
                grindClicksFrom = null,
                grindClicksTo = null,
            ),
            expectIfRight = "Another 8: caramel and honey, around 3:30.",
            ifWrong = "If it drifts sour as the bag passes five weeks off roast, go 2 clicks finer, 76 → 74.",
            keep = "76 clicks, 100 °C, 30 g to 500 g, same pours.",
            dataWarnings = emptyList(),
        )
        insertSample(
            app = app,
            target = AnalysisTarget.Brew(ids[9]),
            result = StoredResult(brew = dialledIn, grindMove = null),
            rawText = AiJson.stored.encodeToString(BrewAnalysis.serializer(), dialledIn),
            promptTokens = 3050,
            completionTokens = 980,
        )

        // C: the whole log.
        val patterns = TrendAnalysis(
            trajectory = "improving",
            headline = "Your low scores all came from brewing too fast at 85–90 clicks.",
            summary = "Every brew under 3:10 scored 4 or less; every brew over 3:20 scored 6 or more. Ten " +
                "clicks finer fixed Southern Weather, and the Kenya on the 4:6 method has been good from the start.",
            confidence = "medium",
            patterns = listOf(
                Pattern(
                    title = "Fast brews score low",
                    detail = "Brews at 2:48–3:04 averaged 3/10; brews at 3:26–3:41 averaged 7.3/10.",
                    brewIds = listOf(ids[0], ids[1], ids[2], ids[3], ids[5], ids[8], ids[9]),
                    strength = "strong",
                    tone = "attention",
                ),
                Pattern(
                    title = "Temperature moved with the grind",
                    detail = "You went from 95 °C to 100 °C in the same run as the grind change, so the gain " +
                        "can't be pinned on either one alone.",
                    brewIds = listOf(ids[3], ids[5]),
                    strength = "weak",
                    tone = "neutral",
                ),
                Pattern(
                    title = "The bed isn't the problem",
                    detail = "All 9 recorded beds were flat or domed, including the worst brews.",
                    // ids[6] is the AeroPress brew, which has no bed recorded.
                    brewIds = ids.filterIndexed { i, _ -> i != 6 },
                    strength = "strong",
                    tone = "good",
                ),
            ),
            changedOften = listOf("grind", "temperature"),
            neverChanged = listOf("dose", "ratio", "pour pattern"),
            experiment = Experiment(
                goal = "Is 100 °C doing any of the work, or was it all grind?",
                brews = listOf(
                    ExperimentBrew(
                        label = "Brew 1",
                        settings = "76 clicks, 100 °C, 30 g to 500 g",
                        grindClicks = 76,
                        expect = "Your current best: sweet, caramel, about 3:30",
                    ),
                    ExperimentBrew(
                        label = "Brew 2",
                        settings = "76 clicks, 94 °C",
                        grindClicks = 76,
                        expect = "If temperature matters: flatter and less sweet",
                    ),
                    ExperimentBrew(
                        label = "Brew 3",
                        settings = "76 clicks, 97 °C",
                        grindClicks = 76,
                        expect = "Should land between the two",
                    ),
                ),
                decide = "If brew 2 tastes like brew 1, temperature isn't doing much and 94–96 °C is fine " +
                    "for this coffee.",
            ),
            perCoffee = listOf(
                CoffeeNote(
                    bean = "Onyx Coffee Lab — Southern Weather",
                    note = "Dialled at 75–76 clicks and 100 °C; stop moving it.",
                ),
                CoffeeNote(bean = "Local — Kenya AA", note = "Good on the 4:6 at 80–82 clicks; no change needed."),
            ),
            dataWarnings = listOf(
                "One brew's taste sliders were never moved and one cup weight (370 g) implies 130 g stayed in " +
                    "the grounds; both were ignored.",
            ),
        )
        insertSample(
            app = app,
            target = AnalysisTarget.Trend,
            result = StoredResult(trend = patterns),
            rawText = AiJson.stored.encodeToString(TrendAnalysis.serializer(), patterns),
            promptTokens = 4120,
            completionTokens = 1880,
        )
    }

    private suspend fun insertSample(
        app: CoffeeApp,
        target: AnalysisTarget,
        result: StoredResult,
        rawText: String,
        promptTokens: Int,
        completionTokens: Int,
    ) {
        try {
            app.analysis.insertSample(
                target = target,
                result = result,
                rawText = rawText,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                model = AnalysisRepository.SAMPLE_MODEL,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Debug only: a broken sample must not take the seeded brews down with it.
            Log.w(TAG, "Sample analysis for $target was not stored", e)
        }
    }

    private const val TAG = "CoffeeSeed"

    // How many brews the seed below writes; a log of any other size was not written by it.
    private const val SEED_BREWS = 10
    private const val V60 = "hoffmann-v60"
    private const val KASUYA = "kasuya-46"
    private const val AEROPRESS = "hoffmann-aeropress"
    private const val K_ULTRA = "1zpresso-k-ultra"

    // Fixed, so seeding twice can never leave two copies of the sample recipe.
    private const val CUSTOM_RECIPE = "custom-5eed0001"

    private data class R(val name: String, val author: String, val brewer: String, val dose: Double, val water: Double)

    private val RECIPES = mapOf(
        V60 to R("The Ultimate V60 Technique", "James Hoffmann", "Hario V60", 30.0, 500.0),
        KASUYA to R("The 4:6 Method", "Tetsu Kasuya", "Hario V60", 20.0, 300.0),
        AEROPRESS to R("The Ultimate AeroPress", "James Hoffmann", "AeroPress", 11.0, 200.0),
    )
}
