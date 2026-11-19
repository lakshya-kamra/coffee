package com.laksh.coffee.backup

import com.laksh.coffee.data.ai.AnalysisEntity
import com.laksh.coffee.data.backup.BackupMerge
import com.laksh.coffee.data.backup.StagedPhoto
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.db.entity.SavedRecipeEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Channeling
import com.laksh.coffee.domain.FlowFeel
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.Sludge
import com.laksh.coffee.domain.TasteProfile
import com.laksh.coffee.domain.backup.AiDto
import com.laksh.coffee.domain.backup.AiProviderDto
import com.laksh.coffee.domain.backup.BackupApp
import com.laksh.coffee.domain.backup.BackupCodec
import com.laksh.coffee.domain.backup.BackupIds
import com.laksh.coffee.domain.backup.BackupMapping
import com.laksh.coffee.domain.backup.BackupSnapshot
import com.laksh.coffee.domain.backup.BackupWriter
import com.laksh.coffee.domain.backup.MergeMode
import com.laksh.coffee.domain.catalog.Catalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rows → records → JSON → records → rows on another phone: equal, with only the local ids changed. */
class BackupRoundTripTest {

    private val t0 = 1_793_000_000_000L

    private val bean = BeanEntity(
        id = 7, name = "Chelbesa", roaster = "Tim Wendelboe", origin = "Ethiopia", region = "Gedeb",
        varietal = "Local landraces", processId = "washed", roastLevelId = "light",
        roastedOnEpochDay = 20_750, openedOnEpochDay = 20_757, bagSizeG = 250.0, price = "about 180 kr",
        altitudeMasl = "1950-2200", tastingNotes = "Jasmine, peach", notes = "Second bag", isArchived = false,
        createdAt = t0, uid = "bean-uid-1", updatedAt = t0 + 5_000, roasterId = "tim-wendelboe", originId = "ethiopia",
        farm = "Chelbesa station", producer = "SNAP", washingStation = "Chelbesa", lotTypeId = "single-origin",
        speciesId = "arabica", varietalIds = listOf("ethiopian-landrace"), isDecaf = false, decafMethodId = null,
        roastIntentId = "filter", harvest = "2025/26", restDays = 10, priceAmount = 18.5, currency = "EUR",
    )

    private val grinder = GrinderEntity(
        id = 3, brand = "1Zpresso", model = "K-Ultra", burrType = BurrType.Conical, burrSizeMm = "48",
        settingStyle = SettingStyle.Clicks, clicksPerRotation = 100, micronsPerClick = 20.0, lowerIsFiner = true,
        minSetting = 0.0, maxSetting = 200.0, step = 1.0, defaultFilterSetting = 75.0, defaultEspressoSetting = null,
        notes = "Zeroed in May", isDefault = true, isArchived = false, uid = "grinder-uid-1", updatedAt = t0 + 9_000,
        catalogId = "1zpresso-k-ultra", dialFormat = "number-tick:10",
    )

    private val brew = BrewLogEntity(
        id = 41, startedAt = t0 + 60_000, recipeId = "hoffmann-v60", recipeName = "The Ultimate V60 Technique",
        recipeAuthor = "James Hoffmann", brewer = "Hario V60", beanId = 7, beanLabel = "Tim Wendelboe — Chelbesa",
        daysOffRoast = 12, grinderId = 3, grinderLabel = "1Zpresso K-Ultra", grindSetting = "75 clicks (dial 7.5)",
        grindClicks = 75, doseG = 30.0, waterG = 500.0, tempC = 95, totalTimeSec = 212, drawdownSec = 41,
        beverageG = 452.5, tdsPercent = 1.38, extractionYield = 20.8, flowFeel = FlowFeel.OnTarget,
        bedShape = BedShape.HighAndDry, sludge = Sludge.Light, channeling = Channeling.Suspected, bedNotes = "Ring at 2 cm",
        taste = TasteProfile(6, 7, 3, 5, 7, 6, 8), overall = 8, descriptors = listOf("Stone fruit", "Tea-like"),
        faults = listOf("Boozy / ferment-heavy"), notes = "Line one\nLine \"two\"", nextTime = "Swirl sooner",
        isFavourite = true, sharedAt = t0 + 120_000, uid = "brew-uid-1", updatedAt = t0 + 90_000,
        brewerId = "hario-v60-02", grindValue = null, waterId = "tww-classic-light-roast",
    )

    /** A numbered-dial brew with nothing optional filled in: every "not recorded" at once. */
    private val bareBrew = BrewLogEntity(
        id = 42, startedAt = t0 + 3_600_000, brewer = "", grindValue = 18.5, uid = "brew-uid-2", updatedAt = t0 + 3_600_000,
    )

    private val recipeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val custom: Recipe = Catalog.recipe("hoffmann-v60")!!.copy(
        id = "custom-1700000000000", name = "Sunday V60", author = "Sam", isBuiltIn = false, fidelity = "custom",
        basedOn = "hoffmann-v60",
    )
    private val saved = SavedRecipeEntity(custom.id, recipeJson.encodeToString(Recipe.serializer(), custom), updatedAt = t0 + 1_000)

    private val analysis = AnalysisEntity(
        id = 5, kind = AnalysisEntity.KIND_BREW, brewLogId = 41, createdAt = t0 + 200_000, promptVersion = 2,
        inputHash = "9f2c1a", brewCount = 1, newestBrewAt = t0 + 60_000, model = "claude-haiku-5-5", host = "api.anthropic.com",
        rawText = "{\"verdict\":\"balanced\"}", resultJson = "{\"brew\":{\"verdict\":\"balanced\"},\"localWarnings\":[]}",
        flagsJson = "[{\"code\":\"GRIND_JUMP\"}]", promptTokens = 1800, completionTokens = 240, costMicroUsd = 9_000L,
        latencyMs = 5_400, uid = "analysis-uid-1", providerId = "anthropic", photoCount = 1, origin = "android",
    )
    private val trend = analysis.copy(
        id = 6, kind = AnalysisEntity.KIND_TREND, brewLogId = null, uid = "analysis-uid-2", resultJson = null, flagsJson = "[]",
        promptTokens = null, completionTokens = null, costMicroUsd = null, origin = "web", photoCount = 0,
    )

    private val photo = PhotoEntity(
        id = 9, uid = "photo-uid-1", brewLogId = 41, beanId = null, kind = "bed", fileName = "photo-uid-1.jpg",
        widthPx = 1024, heightPx = 768, bytes = 123_456, takenAt = t0 + 70_000, createdAt = t0 + 71_000,
        caption = "Flat bed", sendToAi = false,
    )
    private val bagPhoto = photo.copy(id = 10, uid = "photo-uid-2", brewLogId = null, beanId = 7, kind = "bag", fileName = "photo-uid-2.jpg")

    private val settings = Settings(
        lastRecipeId = "hoffmann-v60", defaultBeanId = 7, defaultGrinderId = 3, haptics = false, soundCues = false,
        keepScreenOn = true, autoAdvanceSteps = false, darkMode = "dark", name = "Sam", tempUnit = "f", weightUnit = "oz",
        onboardingDone = true, ownedBrewerIds = setOf("hario-v60-02", "aeropress"), favouriteRoasterIds = setOf("tim-wendelboe"),
        homeArea = "Europe", defaultWaterId = "tww-classic-light-roast",
    )
    private val ai = AiDto("anthropic", sendPhotos = false, providers = listOf(AiProviderDto("anthropic", null, "claude-haiku-5-5", null)))

    private val snapshot = BackupSnapshot(
        beans = listOf(bean), grinders = listOf(grinder), brews = listOf(bareBrew, brew), savedRecipes = listOf(saved),
        analyses = listOf(analysis, trend), photos = listOf(photo, bagPhoto), settings = settings, ai = ai,
    )
    private val app = BackupApp("android", "2.0.0", "com.laksh.coffee", 4)

    private fun document() = BackupMapping.document(snapshot, exportedAt = t0 + 500_000, app = app, includesPhotos = true, includesAnalyses = true)

    private fun throughJson(): String = StringBuilder().also { out ->
        val doc = document()
        BackupWriter.write(out, doc, doc.photos.asSequence().map { it.copy(data = "/9j/AAAA") })
    }.toString()

    @Test
    fun everythingComesBackEqualOnAnotherPhone() = runBlocking {
        val decoded = Fixtures.decode(throughJson())
        assertEquals(emptyList<String>(), decoded.problems)

        val store = FakeStore()
        val staged = mapOf(
            "photo-uid-1" to StagedPhoto("photo-uid-1.jpg", 123_456, null, null),
            "photo-uid-2" to StagedPhoto("photo-uid-2.jpg", 123_456, null, null),
        )
        val result = BackupMerge.apply(store, decoded.doc, MergeMode.Add, staged, now = 0L)
        assertEquals(emptyList<String>(), result.skipped)

        // Ids are this phone's own now; everything else is the row that was exported.
        val newBean = store.beanRows.single()
        val newGrinder = store.grinderRows.single()
        assertEquals(bean, newBean.copy(id = bean.id))
        assertEquals(grinder, newGrinder.copy(id = grinder.id))
        assertFalse("ids are not carried over", newBean.id == bean.id || newGrinder.id == grinder.id)

        val newBrew = store.brewRows.single { it.uid == brew.uid }
        assertEquals(newBean.id, newBrew.beanId)
        assertEquals(newGrinder.id, newBrew.grinderId)
        assertEquals(brew, newBrew.copy(id = brew.id, beanId = brew.beanId, grinderId = brew.grinderId))
        val newBare = store.brewRows.single { it.uid == bareBrew.uid }
        assertEquals(bareBrew, newBare.copy(id = bareBrew.id))

        val newSaved = store.recipeRows.single()
        assertEquals(saved.id, newSaved.id)
        assertEquals(saved.updatedAt, newSaved.updatedAt)
        assertEquals(custom, recipeJson.decodeFromString(Recipe.serializer(), newSaved.json))

        val newAnalysis = store.analysisRows.single { it.uid == analysis.uid }
        assertEquals(newBrew.id, newAnalysis.brewLogId)
        assertEquals(analysis, newAnalysis.copy(id = analysis.id, brewLogId = analysis.brewLogId))
        val newTrend = store.analysisRows.single { it.uid == trend.uid }
        assertEquals(trend, newTrend.copy(id = trend.id))
        assertEquals("an analysis made elsewhere keeps saying so", "web", newTrend.origin)

        val newPhoto = store.photoRows.single { it.uid == photo.uid }
        assertEquals(photo, newPhoto.copy(id = photo.id, brewLogId = photo.brewLogId))
        assertEquals(newBrew.id, newPhoto.brewLogId)
        val newBag = store.photoRows.single { it.uid == bagPhoto.uid }
        assertEquals(newBean.id, newBag.beanId)
        assertNull(newBag.brewLogId)

        // Settings: the two defaults point at this phone's rows.
        val restored = BackupMapping.settings(Settings(), decoded.doc.settings!!, result.beanIds::get, result.grinderIds::get)
        assertEquals(settings.copy(defaultBeanId = newBean.id, defaultGrinderId = newGrinder.id), restored)
        assertEquals(ai, decoded.doc.ai)
    }

    @Test
    fun brewsAreWrittenOldestFirstAndPhotoDataIsOnlyInTheFile() {
        val doc = document()
        assertEquals(listOf(brew.uid, bareBrew.uid), doc.brews.map { it.uid })
        assertTrue(doc.photos.all { it.data == null })
        val text = throughJson()
        assertTrue(text.contains("\"data\": \"/9j/AAAA\""))
        // Local ids never appear: the file links by uid.
        val root = BackupCodec.parse(text) as JsonObject
        fun keys(table: String) = (root[table] as kotlinx.serialization.json.JsonArray).flatMap { (it as JsonObject).keys }.toSet()
        listOf("beans", "grinders", "brews", "analyses", "photos").forEach { table ->
            assertFalse(table, keys(table).any { it in setOf("id", "beanId", "grinderId", "brewLogId", "fileName") })
        }
    }

    @Test
    fun aBackupSavedWithoutAnalysesHasNone() {
        val doc = BackupMapping.document(snapshot, t0, app, includesPhotos = false, includesAnalyses = false)
        assertTrue(doc.analyses.isEmpty())
        assertFalse(doc.options.includesAnalyses)
        assertFalse(doc.options.includesPhotos)
    }

    @Test
    fun everyEnumValueSurvivesAndNotRecordedIsNull() {
        BedShape.entries.forEach { assertEquals(it, BackupIds.bed(BackupIds.bed(it))) }
        Sludge.entries.forEach { assertEquals(it, BackupIds.sludge(BackupIds.sludge(it))) }
        Channeling.entries.forEach { assertEquals(it, BackupIds.channelling(BackupIds.channelling(it))) }
        FlowFeel.entries.forEach { assertEquals(it, BackupIds.flow(BackupIds.flow(it))) }
        BurrType.entries.forEach { assertEquals(it, BackupIds.burr(BackupIds.burr(it))) }
        SettingStyle.entries.forEach { assertEquals(it, BackupIds.style(BackupIds.style(it))) }
        assertNull(BackupIds.bed(BedShape.Unset))
        assertNull(BackupIds.flow(FlowFeel.Unset))
        assertNull(BackupIds.burr(BurrType.Unknown))
        // The documented ids, exactly.
        assertEquals("high-and-dry", BackupIds.bed(BedShape.HighAndDry))
        assertEquals("on-target", BackupIds.flow(FlowFeel.OnTarget))
        assertEquals("suspected", BackupIds.channelling(Channeling.Suspected))
        assertEquals("ghost", BackupIds.burr(BurrType.Ghost))
        assertEquals("numbers", BackupIds.style(SettingStyle.Numbers))
        // 1.x labels and Kotlin enum names read as the same thing.
        assertEquals(BedShape.Crater, BackupIds.bed("Cratered / dug out"))
        assertEquals(BedShape.HighAndDry, BackupIds.bed("HighAndDry"))
        assertEquals(Sludge.None, BackupIds.sludge("Clean"))
        assertEquals(Channeling.Obvious, BackupIds.channelling("Obvious holes"))
        assertEquals(FlowFeel.Gusher, BackupIds.flow("Fell straight through"))
        assertEquals(BurrType.Ghost, BackupIds.burr("Ghost burr"))
        assertEquals(SettingStyle.Stepless, BackupIds.style("Stepless / degrees"))
        assertEquals(BedShape.Unset, BackupIds.bed("Not recorded"))
        assertEquals(BedShape.Unset, BackupIds.bed("something new"))
    }

    @Test
    fun numbersThatAreNotNumbersNeverReachTheFileOrTheDatabase() {
        // A row holding NaN (1.x could store one) is written as null, not as a token JSON forbids.
        val odd = brew.copy(tdsPercent = Double.NaN, extractionYield = Double.POSITIVE_INFINITY, beverageG = Double.NEGATIVE_INFINITY)
        val dto = BackupMapping.brew(odd, { "b" }, { "g" })
        assertNull(dto.tdsPercent)
        assertNull(dto.extractionYield)
        assertNull(dto.beverageG)
        // And a file that does hold the bare tokens reads them as "not recorded".
        val text = """{"format":"coffee-backup","version":2,"brews":[
            {"uid":"x","startedAt":"2026-11-10T07:12:30Z","doseG":NaN,"tdsPercent":NaN,"extractionYield":-Infinity,
             "beverageG":Infinity,"notes":"NaN and Infinity are just words here"}]}"""
        val read = Fixtures.decode(text).doc.brews.single()
        assertEquals(0.0, read.doseG, 0.0)
        assertNull(read.tdsPercent)
        assertNull(read.extractionYield)
        assertNull(read.beverageG)
        assertEquals("NaN and Infinity are just words here", read.notes)
    }

    @Test
    fun aRecipeFromTheWebWithDecimalWholeNumbersStillOpens() {
        val entry = BackupMapping.recipe(saved)!!
        val asJs = BackupCodec.parse(entry.toString().replace("\"tempC\":", "\"tempC\":94.0, \"was\":")) as JsonObject
        val row = BackupMapping.savedRecipe(asJs, fallbackMs = 1L)!!
        assertEquals(94, recipeJson.decodeFromString(Recipe.serializer(), row.json).tempC)
        // Not a recipe at all: left out, never stored.
        assertNull(BackupMapping.savedRecipe(BackupCodec.parse("""{"id":"custom-9","name":"Half a recipe"}""") as JsonObject, 1L))
    }
}
