package com.laksh.coffee.backup

import com.laksh.coffee.data.ai.AnalysisEntity
import com.laksh.coffee.data.ai.AnalysisRepository
import com.laksh.coffee.data.ai.AnalysisTarget
import com.laksh.coffee.data.ai.StaleReason
import com.laksh.coffee.data.ai.StoredAnalysis
import com.laksh.coffee.data.ai.UsageLine
import com.laksh.coffee.data.backup.BackupMerge
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.TasteProfile
import com.laksh.coffee.domain.ai.AiJson
import com.laksh.coffee.domain.ai.AnalysisPrompt
import com.laksh.coffee.domain.ai.BrewAnalysis
import com.laksh.coffee.domain.ai.BrewPromptInput
import com.laksh.coffee.domain.ai.BrewRefs
import com.laksh.coffee.domain.ai.Pattern
import com.laksh.coffee.domain.ai.StoredResult
import com.laksh.coffee.domain.ai.TrendAnalysis
import com.laksh.coffee.domain.ai.TrendPromptInput
import com.laksh.coffee.domain.backup.BackupApp
import com.laksh.coffee.domain.backup.BackupDoc
import com.laksh.coffee.domain.backup.BackupMapping
import com.laksh.coffee.domain.backup.BackupSnapshot
import com.laksh.coffee.domain.backup.BackupWriter
import com.laksh.coffee.domain.backup.MergeMode
import com.laksh.coffee.domain.recipes.ExpertRecipes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restore gives every row a new id. A stored analysis must come through that unharmed: still
 * current when nothing was edited, and its trend still pointing at the brews it was about.
 */
class RestoredAnalysesTest {

    private val t0 = 1_793_000_000_000L
    private val day = 86_400_000L
    private val v60: Recipe = ExpertRecipes.byId("hoffmann-v60")!!
    private val recipes = mapOf(v60.id to v60)

    private val bean = BeanEntity(id = 7, name = "Chelbesa", roaster = "Tim Wendelboe", createdAt = t0, uid = "bean-uid-1", updatedAt = t0)

    private val grinder = GrinderEntity(
        id = 3, brand = "1Zpresso", model = "K-Ultra", burrType = BurrType.Conical, burrSizeMm = "48",
        settingStyle = SettingStyle.Clicks, clicksPerRotation = 100, micronsPerClick = 20.0, lowerIsFiner = true,
        maxSetting = 200.0, isDefault = true, uid = "grinder-uid-1", updatedAt = t0, catalogId = "1zpresso-k-ultra",
        dialFormat = "number-tick:10",
    )

    private fun brew(id: Long, uid: String, dayNo: Int, clicks: Int, overall: Int) = BrewLogEntity(
        id = id, startedAt = t0 + dayNo * day, recipeId = v60.id, recipeName = v60.name, recipeAuthor = v60.author,
        brewer = v60.brewer.label, brewerId = v60.brewer.id, beanId = bean.id, beanLabel = "Tim Wendelboe — Chelbesa",
        daysOffRoast = 10L + dayNo, grinderId = grinder.id, grinderLabel = "1Zpresso K-Ultra", grindSetting = "$clicks clicks",
        grindClicks = clicks, doseG = v60.doseG, waterG = v60.waterG, tempC = 95, totalTimeSec = 200 + clicks, drawdownSec = 45,
        bedShape = BedShape.Flat, taste = TasteProfile(5, 7, 3, 6, 7, 6, 7), overall = overall, uid = uid,
        updatedAt = t0 + dayNo * day,
    )

    // Row 13 was deleted long ago: this phone's ids have a gap, as a used phone's do.
    private val brews = listOf(
        brew(id = 11, uid = "brew-a", dayNo = 1, clicks = 0, overall = 4),
        brew(id = 12, uid = "brew-b", dayNo = 2, clicks = 78, overall = 6),
        brew(id = 14, uid = "brew-c", dayNo = 3, clicks = 76, overall = 7),
        brew(id = 15, uid = "brew-d", dayNo = 4, clicks = 0, overall = 8),
    )

    private fun brewHash(target: BrewLogEntity, all: List<BrewLogEntity>, beans: List<BeanEntity>, grinders: List<GrinderEntity>): String =
        AnalysisPrompt.brew(
            BrewPromptInput(
                log = target,
                recipe = recipes[target.recipeId],
                bean = beans.firstOrNull { it.id == target.beanId },
                grinder = grinders.firstOrNull { it.id == target.grinderId },
                history = all.filter { it.startedAt < target.startedAt }.sortedByDescending { it.startedAt },
                recipes = recipes,
                beans = beans.associateBy { it.id },
                grinders = grinders.associateBy { it.id },
            ),
        ).inputHash

    private fun trendHash(all: List<BrewLogEntity>, beans: List<BeanEntity>, grinders: List<GrinderEntity>): String =
        AnalysisPrompt.trend(
            TrendPromptInput(
                logs = all.sortedByDescending { it.startedAt },
                totalLogged = all.size,
                recipes = recipes,
                beans = beans.associateBy { it.id },
                grinders = grinders.associateBy { it.id },
            ),
        ).inputHash

    private val trendResult = TrendAnalysis(
        trajectory = "improving",
        headline = "A zero grind keeps turning up",
        patterns = listOf(
            Pattern(title = "No grind logged", brewIds = listOf(11, 15), strength = "weak", tone = "attention"),
            // 13 is the deleted brew: the model was never shown it, and nothing stands behind it.
            Pattern(title = "Finer scored higher", brewIds = listOf(12, 14, 13), strength = "weak", tone = "good"),
        ),
    )

    private fun analysisRow(id: Long, uid: String, kind: String, brewLogId: Long?, hash: String, result: StoredResult) = AnalysisEntity(
        id = id, kind = kind, brewLogId = brewLogId, createdAt = t0 + 5 * day, promptVersion = AnalysisPrompt.VERSION,
        inputHash = hash, brewCount = brews.size, newestBrewAt = brews.last().startedAt, model = "m", host = "api.example.com",
        rawText = "{}", resultJson = AiJson.stored.encodeToString(StoredResult.serializer(), result), flagsJson = "[]",
        promptTokens = 100, completionTokens = 50, costMicroUsd = null, latencyMs = 900, uid = uid, providerId = "custom",
        origin = AnalysisRepository.ORIGIN,
    )

    private val brewAnalysis = analysisRow(
        id = 5, uid = "analysis-brew", kind = AnalysisEntity.KIND_BREW, brewLogId = 15,
        hash = brewHash(brews.last(), brews, listOf(bean), listOf(grinder)),
        result = StoredResult(brew = BrewAnalysis(verdict = "close", headline = "Nearly there")),
    )

    private val trendAnalysis = analysisRow(
        id = 6, uid = "analysis-trend", kind = AnalysisEntity.KIND_TREND, brewLogId = null,
        hash = trendHash(brews, listOf(bean), listOf(grinder)),
        result = StoredResult(trend = trendResult, brewUids = BrewRefs.uidsFor(trendResult) { n -> brews.firstOrNull { it.id == n }?.uid }),
    )

    private fun snapshot(analyses: List<AnalysisEntity>) = BackupSnapshot(
        beans = listOf(bean), grinders = listOf(grinder), brews = brews, savedRecipes = emptyList(), analyses = analyses,
        photos = emptyList(), settings = Settings(), ai = null,
    )

    private fun document(analyses: List<AnalysisEntity> = listOf(brewAnalysis, trendAnalysis)): BackupDoc =
        BackupMapping.document(snapshot(analyses), exportedAt = t0 + 6 * day, app = BackupApp("android", "2.0.0", "com.laksh.coffee", 4), includesPhotos = false, includesAnalyses = true)

    /** The file as text and back, then into [store]. */
    private suspend fun restore(doc: BackupDoc, store: FakeStore, mode: MergeMode = MergeMode.Add) {
        val text = StringBuilder().also { BackupWriter.write(it, doc, emptySequence()) }.toString()
        val decoded = Fixtures.decode(text)
        assertEquals(emptyList<String>(), decoded.problems)
        val result = BackupMerge.apply(store, decoded.doc, mode, emptyMap(), now = 0L)
        assertEquals(emptyList<String>(), result.skipped)
    }

    private fun stored(row: AnalysisEntity): StoredAnalysis {
        val result = AiJson.stored.decodeFromString(StoredResult.serializer(), row.resultJson!!)
        return StoredAnalysis(
            id = row.id,
            target = row.brewLogId?.let { AnalysisTarget.Brew(it) } ?: AnalysisTarget.Trend,
            createdAt = row.createdAt, model = row.model, modelLabel = row.model, host = row.host,
            brew = result.brew, trend = result.trend, grindMove = null, valueMove = null, localWarnings = emptyList(),
            rawText = row.rawText, parsed = true, flags = emptyList(), usage = UsageLine(null, null, null, 0),
            inputHash = row.inputHash, brewCount = row.brewCount, newestBrewAt = row.newestBrewAt,
            promptVersion = row.promptVersion, providerId = row.providerId, providerName = "", photoCount = row.photoCount,
            origin = row.origin, brewUids = BrewRefs.stored(result.brewUids, madeHere = row.origin == AnalysisRepository.ORIGIN),
        )
    }

    /** The row ids a pattern's chips open on the phone that [store] is. */
    private fun chips(store: FakeStore, row: AnalysisEntity, pattern: Int): List<Long> {
        val s = stored(row)
        val idByUid = store.brewRows.associate { it.uid to it.id }
        return BrewRefs.follow(s.trend!!.patterns[pattern].brewIds, BrewRefs.rowIds(s.brewUids, idByUid::get))
    }

    private fun FakeStore.brewId(uid: String): Long = brewRows.single { it.uid == uid }.id

    // ------------------------------------------------------------- staleness

    @Test
    fun analysesAreStillCurrentAfterARestoreRenumbersEveryRow() = runBlocking {
        val phone = FakeStore()
        restore(document(), phone)
        assertFalse("the restore really did renumber", phone.brewRows.any { b -> brews.any { it.id == b.id } })
        assertFalse(phone.beanRows.single().id == bean.id || phone.grinderRows.single().id == grinder.id)

        val brewRow = phone.analysisRows.single { it.uid == brewAnalysis.uid }
        val target = phone.brewRows.single { it.uid == "brew-d" }
        assertEquals(target.id, brewRow.brewLogId)
        val brewNow = brewHash(target, phone.brewRows, phone.beanRows, phone.grinderRows)
        assertEquals("the hash travels as written", brewAnalysis.inputHash, brewRow.inputHash)
        assertEquals("and is what this phone works out for the same brew", brewRow.inputHash, brewNow)
        assertNull(stored(brewRow).staleReason(brewNow))

        val trendRow = phone.analysisRows.single { it.uid == trendAnalysis.uid }
        val trendNow = trendHash(phone.brewRows, phone.beanRows, phone.grinderRows)
        assertEquals(trendRow.inputHash, trendNow)
        assertNull(stored(trendRow).staleReason(trendNow))
    }

    @Test
    fun replaceEverythingOnAUsedPhoneKeepsThemCurrentToo() = runBlocking {
        val phone = FakeStore()
        // Someone else's log is here first, then wiped: the restored rows start from higher ids.
        val otherBean = phone.insertBean(BeanEntity(name = "Other", uid = "other-bean"))
        phone.insertBrew(brew(id = 0, uid = "other-brew", dayNo = 9, clicks = 70, overall = 5).copy(beanId = otherBean, grinderId = null))
        restore(document(), phone, MergeMode.Replace)
        assertEquals(brews.map { it.uid }.toSet(), phone.brewRows.map { it.uid }.toSet())

        val trendRow = phone.analysisRows.single { it.uid == trendAnalysis.uid }
        assertNull(stored(trendRow).staleReason(trendHash(phone.brewRows, phone.beanRows, phone.grinderRows)))
        val brewRow = phone.analysisRows.single { it.uid == brewAnalysis.uid }
        val target = phone.brewRows.single { it.uid == "brew-d" }
        assertNull(stored(brewRow).staleReason(brewHash(target, phone.brewRows, phone.beanRows, phone.grinderRows)))
    }

    @Test
    fun anEditAfterTheRestoreStillShowsAsAnEdit() = runBlocking {
        val phone = FakeStore()
        restore(document(), phone)
        val edited = phone.brewRows.map { if (it.uid == "brew-b") it.copy(overall = 2) else it }
        val trendRow = phone.analysisRows.single { it.uid == trendAnalysis.uid }
        assertEquals(StaleReason.Edited, stored(trendRow).staleReason(trendHash(edited, phone.beanRows, phone.grinderRows)))
    }

    @Test
    fun anAnalysisFromBeforeUidHashesReadsAsAnEarlierVersionNotAnEdit() = runBlocking {
        // 2.0.0 hashed row ids. Whatever this phone works out now, it cannot be compared with that.
        val old = trendAnalysis.copy(inputHash = "9f2c1a0b3d4e5f60")
        val phone = FakeStore()
        restore(document(listOf(old)), phone)
        val row = phone.analysisRows.single()
        assertEquals("the old hash is carried as written", "9f2c1a0b3d4e5f60", row.inputHash)
        val now = trendHash(phone.brewRows, phone.beanRows, phone.grinderRows)
        assertEquals(StaleReason.OlderPrompt, stored(row).staleReason(now))
        // The same on the phone it was made on, with no restore at all.
        assertEquals(StaleReason.OlderPrompt, stored(old).staleReason(trendHash(brews, listOf(bean), listOf(grinder))))
    }

    // ----------------------------------------------------------- trend chips

    @Test
    fun trendChipsOpenTheSameBrewsAfterARestore() = runBlocking {
        val phone = FakeStore()
        restore(document(), phone)
        val row = phone.analysisRows.single { it.uid == trendAnalysis.uid }

        assertEquals(listOf(phone.brewId("brew-a"), phone.brewId("brew-d")), chips(phone, row, pattern = 0))
        // The number nothing stood behind opens nothing.
        assertEquals(listOf(phone.brewId("brew-b"), phone.brewId("brew-c")), chips(phone, row, pattern = 1))
        // The model's own numbers are kept as it wrote them.
        assertEquals(listOf(11L, 15L), stored(row).trend!!.patterns[0].brewIds)
    }

    @Test
    fun aNumberNeverOpensWhateverBrewNowHasThatRowId() = runBlocking {
        // On this phone row ids 11 and 15 already belong to other brews.
        val phone = FakeStore()
        phone.brewRows += brew(id = 11, uid = "someone-else-1", dayNo = 20, clicks = 70, overall = 5).copy(beanId = null, grinderId = null)
        phone.brewRows += brew(id = 15, uid = "someone-else-2", dayNo = 21, clicks = 70, overall = 5).copy(beanId = null, grinderId = null)
        restore(document(), phone)
        val row = phone.analysisRows.single { it.uid == trendAnalysis.uid }
        val opened = chips(phone, row, pattern = 0)
        assertEquals(listOf("brew-a", "brew-d"), opened.map { id -> phone.brewRows.single { it.id == id }.uid })
    }

    @Test
    fun aTrendStoredBeforeUidsGetsThemWhenItIsBackedUp() = runBlocking {
        // Made by 2.0.0 on this phone: numbers only. They are still this phone's row ids.
        val legacy = trendAnalysis.copy(resultJson = AiJson.stored.encodeToString(StoredResult.serializer(), StoredResult(trend = trendResult)))
        assertNull(stored(legacy).brewUids)
        // In place, the numbers are followed as row ids, as they always were.
        assertEquals(listOf(11L, 15L), BrewRefs.follow(trendResult.patterns[0].brewIds, BrewRefs.rowIds(null) { null }))

        val doc = document(listOf(legacy))
        val written = doc.analyses.single().result as JsonObject
        assertEquals("{\"11\":\"brew-a\",\"15\":\"brew-d\",\"12\":\"brew-b\",\"14\":\"brew-c\"}", written["brewUids"].toString())

        val phone = FakeStore()
        restore(doc, phone)
        val row = phone.analysisRows.single()
        assertEquals(listOf(phone.brewId("brew-a"), phone.brewId("brew-d")), chips(phone, row, pattern = 0))
    }

    @Test
    fun aTrendAnotherAppMadeNeverHasItsNumbersReadAsThisPhonesRowIds() = runBlocking {
        // A web trend that 2.0.0 brought in: numbers only, and they are the browser's row ids.
        // Brews 11 to 15 on this phone are other brews entirely.
        val foreign = trendAnalysis.copy(
            origin = "web",
            resultJson = AiJson.stored.encodeToString(StoredResult.serializer(), StoredResult(trend = trendResult)),
        )
        val shown = stored(foreign)
        assertEquals(emptyMap<String, String>(), shown.brewUids)
        assertTrue(BrewRefs.follow(trendResult.patterns[0].brewIds, BrewRefs.rowIds(shown.brewUids) { 1L }).isEmpty())

        // A backup made now must not write this phone's uids against those numbers.
        val written = document(listOf(foreign)).analyses.single().result as JsonObject
        assertEquals("{}", written["brewUids"].toString())

        val phone = FakeStore()
        restore(document(listOf(foreign)), phone)
        val row = phone.analysisRows.single()
        assertTrue(chips(phone, row, pattern = 0).isEmpty())
        assertTrue(chips(phone, row, pattern = 1).isEmpty())
    }

    @Test
    fun aFileWithNumbersOnlyShowsNoChipsRatherThanTheWrongBrew() = runBlocking {
        // What a 2.0.0 backup holds: a trend whose numbers are another install's row ids.
        val doc = document().let { d ->
            d.copy(analyses = d.analyses.map { a -> a.copy(result = (a.result as? JsonObject)?.let { JsonObject(it - "brewUids") }) })
        }
        val phone = FakeStore()
        // A brew here happens to have row id 11.
        phone.brewRows += brew(id = 11, uid = "someone-else", dayNo = 20, clicks = 70, overall = 5).copy(beanId = null, grinderId = null)
        restore(doc, phone)
        val row = phone.analysisRows.single { it.uid == trendAnalysis.uid }
        assertNotNull("an empty table, so the numbers are not read as this phone's ids", stored(row).brewUids)
        assertTrue(chips(phone, row, pattern = 0).isEmpty())
        assertTrue(chips(phone, row, pattern = 1).isEmpty())
        // The brew analysis has no numbers to lose and is untouched.
        assertEquals(brewAnalysis.resultJson, phone.analysisRows.single { it.uid == brewAnalysis.uid }.resultJson)
    }
}
