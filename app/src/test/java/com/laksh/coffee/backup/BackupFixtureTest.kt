package com.laksh.coffee.backup

import com.laksh.coffee.data.ai.AnalysisEntity
import com.laksh.coffee.data.backup.BackupMerge
import com.laksh.coffee.data.backup.StagedPhoto
import com.laksh.coffee.domain.ai.AiJson
import com.laksh.coffee.domain.ai.AnalysisPrompt
import com.laksh.coffee.domain.ai.BrewRefs
import com.laksh.coffee.domain.ai.StoredResult
import com.laksh.coffee.domain.backup.Backup
import com.laksh.coffee.domain.backup.BackupCodec
import com.laksh.coffee.domain.backup.BackupKind
import com.laksh.coffee.domain.backup.BackupTime
import com.laksh.coffee.domain.backup.BackupWriter
import com.laksh.coffee.domain.backup.Counts
import com.laksh.coffee.domain.backup.JpegBytes
import com.laksh.coffee.domain.backup.MergeMode
import com.laksh.coffee.domain.catalog.Catalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * shared/fixtures/backup-v2-sample.json is the file both apps must read the same way (the web
 * app's tests read it too). It was written by the web track; this app conforms to it.
 */
class BackupFixtureTest {

    private val text = Fixtures.text(Fixtures.V2)
    private val root = BackupCodec.parse(text) as JsonObject

    @Test
    fun theFixtureIsAVersionTwoBackup() {
        assertEquals(BackupKind.V2, BackupCodec.detect(text))
        val decoded = BackupCodec.decodeV2(root)
        assertEquals("nothing in the fixture is left out", emptyList<String>(), decoded.problems)
        assertEquals(Counts(brews = 2, beans = 1, grinders = 1, recipes = 1, analyses = 3, photos = 1), Counts.of(decoded.doc))
        assertEquals(Backup.VERSION, decoded.doc.version)
        assertEquals("android", decoded.doc.app.platform)
        assertEquals("com.laksh.coffee.debug", decoded.doc.app.packageName)
        assertEquals(4, decoded.doc.app.dbVersion)
        assertTrue(decoded.doc.options.includesPhotos)
        assertEquals(BackupTime.parse("2026-11-17T09:41:00Z"), BackupTime.parse(decoded.doc.exportedAt))
    }

    @Test
    fun everyRecordReadsAsWritten() {
        val doc = BackupCodec.decodeV2(root).doc

        val bean = doc.beans.single()
        assertEquals("Red Brick", bean.name)
        assertEquals("square-mile", bean.roasterId)
        assertNull(bean.originId)
        assertEquals("blend", bean.lotTypeId)
        assertEquals("washed", bean.processId)
        assertEquals("medium-light", bean.roastLevelId)
        assertEquals("2026-10-29", bean.roastedOn)
        assertEquals(350.0, bean.bagSizeG!!, 0.0)
        assertEquals(14.5, bean.priceAmount!!, 0.0)
        assertEquals("GBP", bean.currency)
        // Instants with and without milliseconds both read.
        assertEquals(BackupTime.parse("2026-11-01T10:00:00.000Z"), BackupTime.parse(bean.createdAt))
        assertEquals(java.time.Instant.parse("2026-11-03T08:30:00.250Z").toEpochMilli(), BackupTime.parse(bean.updatedAt))

        val grinder = doc.grinders.single()
        assertEquals("1zpresso-k-ultra", grinder.catalogId)
        assertEquals("conical", grinder.burr)
        assertEquals("clicks", grinder.setting.style)
        assertEquals(100, grinder.setting.clicksPerRotation)
        assertEquals(20.0, grinder.setting.micronsPerStep!!, 0.0)
        assertEquals("number-tick:10", grinder.setting.dialFormat)
        assertEquals(75.0, grinder.defaultFilterSetting!!, 0.0)
        assertTrue(grinder.isDefault)

        val (first, second) = doc.brews
        assertEquals("hoffmann-v60", first.recipeId)
        assertEquals("hario-v60-02", first.brewerId)
        assertEquals(bean.uid, first.beanUid)
        assertEquals(grinder.uid, first.grinderUid)
        assertEquals(75, first.grindClicks)
        assertNull(first.grindValue)
        assertEquals("on-target", first.flow)
        assertEquals("flat", first.bed.shape)
        assertEquals("light", first.bed.surface)
        assertEquals("none", first.bed.channelling)
        assertEquals(7, first.taste.sweetness)
        assertEquals(listOf("Stone fruit", "Chocolate"), first.descriptors)
        assertTrue(first.isFavourite)
        assertEquals("custom-1731398400000", second.recipeId)
        assertEquals("tww-classic-light-roast", second.waterId)
        assertNull(second.flow)
        assertNull(second.bed.shape)
        assertEquals(0, second.overall)

        val recipe = doc.recipes.single()
        assertEquals("custom-1731398400000", (recipe["id"] as kotlinx.serialization.json.JsonPrimitive).content)

        val (brewAnalysis, trend, trendWithFindings) = doc.analyses
        assertEquals("brew", brewAnalysis.kind)
        assertEquals(first.uid, brewAnalysis.brewUid)
        assertEquals("anthropic", brewAnalysis.providerId)
        assertEquals(9000L, brewAnalysis.costMicroUsd)
        assertTrue(brewAnalysis.result is JsonObject)
        assertEquals("trend", trend.kind)
        assertNull(trend.brewUid)
        assertNull(trend.result)
        assertNull(trend.promptTokens)
        // The first two carry what 2.0.0 stored; the third is a trend as both apps store it now.
        assertFalse(AnalysisPrompt.isCurrentHash(brewAnalysis.inputHash))
        assertTrue(AnalysisPrompt.isCurrentHash(trendWithFindings.inputHash))
        val stored = AiJson.stored.decodeFromString(StoredResult.serializer(), trendWithFindings.result.toString())
        assertEquals(listOf(3L, 7L), stored.trend!!.patterns.first().brewIds)
        assertEquals(mapOf("3" to first.uid, "7" to second.uid), stored.brewUids)

        val photo = doc.photos.single()
        assertEquals("bed", photo.kind)
        assertEquals(first.uid, photo.brewUid)
        assertNull(photo.beanUid)
        assertTrue(photo.sendToAi)
        assertTrue("the fixture's photo is a real JPEG", JpegBytes.isJpeg(Base64.getDecoder().decode(photo.data)))
    }

    @Test
    fun settingsAndAiChoicesReadAndCarryNoKey() {
        val doc = BackupCodec.decodeV2(root).doc
        val s = doc.settings!!
        assertEquals("c", s.tempUnit)
        assertEquals("Sam", s.name)
        assertEquals(listOf("hario-v60-02", "aeropress"), s.ownedBrewerIds)
        assertEquals(doc.beans.single().uid, s.defaultBeanUid)
        assertTrue(s.hasDefaultBean && s.hasDefaultGrinder)
        assertEquals(true, s.onboardingDone)

        val ai = doc.ai!!
        assertEquals("anthropic", ai.activeProvider)
        assertEquals(2, ai.providers.size)
        assertNull("only custom carries an address", ai.providers[0].baseUrl)
        assertEquals("https://llm.example.com/v1", ai.providers[1].baseUrl)
        assertEquals(false, ai.providers[1].vision)
        // The format has no field for a key, and the fixture holds none.
        assertFalse(text.contains("key", ignoreCase = true))
    }

    @Test
    fun everyIdInTheFixtureIsARealCatalogId() {
        val doc = BackupCodec.decodeV2(root).doc
        assertNotNull(Catalog.roaster(doc.beans.single().roasterId))
        assertNotNull(Catalog.grinder(doc.grinders.single().catalogId))
        doc.brews.forEach { assertNotNull(it.brewerId, Catalog.brewer(it.brewerId)) }
        assertNotNull(Catalog.water(doc.brews[1].waterId))
        assertNotNull(Catalog.recipe(doc.brews[0].recipeId))
        doc.settings!!.ownedBrewerIds!!.forEach { assertNotNull(it, Catalog.brewer(it)) }
    }

    /** The key order the web app writes is the order this app writes: one format, not two dialects. */
    @Test
    fun thisAppWritesTheSameKeysInTheSameOrder() {
        val doc = BackupCodec.decodeV2(root).doc
        val out = StringBuilder()
        BackupWriter.write(out, doc, doc.photos.asSequence())
        val mine = BackupCodec.parse(out.toString()) as JsonObject

        assertEquals(root.keys.toList(), mine.keys.toList())
        fun keysOf(e: JsonElement?): List<String> = (e as JsonObject).keys.toList()
        fun firstOf(o: JsonObject, name: String): JsonElement = (o[name] as JsonArray).first()
        for (table in listOf("beans", "grinders", "brews", "recipes", "analyses", "photos")) {
            assertEquals(table, keysOf(firstOf(root, table)), keysOf(firstOf(mine, table)))
        }
        assertEquals(keysOf(root["app"]), keysOf(mine["app"]))
        assertEquals(keysOf(root["options"]), keysOf(mine["options"]))
        assertEquals(keysOf(root["settings"]), keysOf(mine["settings"]))
        assertEquals(keysOf(root["ai"]), keysOf(mine["ai"]))
        assertEquals(
            keysOf((firstOf(root, "grinders") as JsonObject)["setting"]),
            keysOf((firstOf(mine, "grinders") as JsonObject)["setting"]),
        )
        val brew = firstOf(root, "brews") as JsonObject
        val myBrew = firstOf(mine, "brews") as JsonObject
        assertEquals(keysOf(brew["bed"]), keysOf(myBrew["bed"]))
        assertEquals(keysOf(brew["taste"]), keysOf(myBrew["taste"]))
        assertEquals(
            keysOf(((root["ai"] as JsonObject)["providers"] as JsonArray).first()),
            keysOf(((mine["ai"] as JsonObject)["providers"] as JsonArray).first()),
        )
    }

    /** The numbers in the fixture's trend are another install's row ids; its uids say which brews they are. */
    @Test
    fun theTrendsFindingsOpenTheFixturesBrewsWhateverRowIdsTheyGetHere() = runBlocking {
        val doc = BackupCodec.decodeV2(root).doc
        val phone = FakeStore()
        val staged = doc.photos.associate { it.uid to StagedPhoto("${it.uid}.jpg", 100, null, null) }
        BackupMerge.apply(phone, doc, MergeMode.Add, staged, now = 0L)

        val row = phone.analysisRows.single { it.kind == AnalysisEntity.KIND_TREND && it.resultJson != null }
        val stored = AiJson.stored.decodeFromString(StoredResult.serializer(), row.resultJson!!)
        val idByUid = phone.brewRows.associate { it.uid to it.id }
        assertFalse("no brew here has row id 3 or 7", phone.brewRows.any { it.id == 3L || it.id == 7L })
        val rowIds = BrewRefs.rowIds(stored.brewUids, idByUid::get)
        assertEquals(doc.brews.map { idByUid.getValue(it.uid) }, BrewRefs.follow(stored.trend!!.patterns[0].brewIds, rowIds))
        assertEquals(listOf(idByUid.getValue(doc.brews[0].uid)), BrewRefs.follow(stored.trend!!.patterns[1].brewIds, rowIds))
    }

    @Test
    fun writingAndReadingAgainChangesNothing() {
        val doc = BackupCodec.decodeV2(root).doc
        val streamed = StringBuilder().also { BackupWriter.write(it, doc, doc.photos.asSequence()) }.toString()
        assertEquals("the streaming writer and the plain encoder agree", BackupCodec.encode(doc), streamed)
        val again = BackupCodec.decodeV2(BackupCodec.parse(streamed) as JsonObject)
        assertEquals(emptyList<String>(), again.problems)
        assertEquals(doc, again.doc)
        // Photo data survives byte for byte.
        assertEquals(doc.photos.single().data, again.doc.photos.single().data)
    }

    @Test
    fun aNewerVersionOrAnotherFileIsRecognised() {
        assertEquals(BackupKind.TooNew, BackupCodec.detect("""{"format":"coffee-backup","version":3}"""))
        assertEquals(BackupKind.Unknown, BackupCodec.detect("""{"format":"coffee-backup","version":1}"""))
        assertEquals(BackupKind.Unknown, BackupCodec.detect("""{"format":"coffee-backup","version":"two"}"""))
        assertEquals(BackupKind.Unknown, BackupCodec.detect("""{"brews":[]}"""))
        assertEquals(BackupKind.Unknown, BackupCodec.detect("[1,2,3]"))
        assertEquals(BackupKind.Unknown, BackupCodec.detect("not json at all {"))
        assertEquals(BackupKind.Unknown, BackupCodec.detect(""))
        assertEquals(BackupKind.TooNew, BackupCodec.detect("""{"schema":"coffee-brew-log/2"}"""))
        assertEquals(BackupKind.Legacy1, BackupCodec.detect("""{"schema":"coffee-brew-log/1"}"""))
        // A byte-order mark in front is legal in a file.
        assertEquals(BackupKind.V2, BackupCodec.detect(0xFEFF.toChar() + text))
    }

    @Test
    fun aRecordThatCannotBeUsedIsLeftOutWithAReason() {
        val bad = """
            {"format":"coffee-backup","version":2,
             "beans":[{"uid":"a","name":"Fine"},{"name":"No uid"},{"uid":"b","name":"  "},7],
             "grinders":[{"brand":"x"}],
             "brews":[{"uid":"c"},{"uid":"d","startedAt":"2026-11-10T07:12:30Z","tempC":"95","doseG":"15.5","overall":99}],
             "recipes":[{"name":"no id"}],
             "analyses":[{"uid":"e","kind":"poem","createdAt":"2026-11-10T07:12:30Z"}],
             "photos":[{"uid":"f","takenAt":"2026-11-10T07:12:30Z"}]}
        """.trimIndent()
        val decoded = BackupCodec.decodeV2(BackupCodec.parse(bad) as JsonObject)
        assertEquals(
            listOf(
                "beans[1]: no uid", "beans[2]: no name", "beans[3]: not an object",
                "grinders[0]: no uid",
                "brews[0]: no startedAt",
                "recipes[0]: no id",
                "analyses[0]: unknown kind",
                "photos[0]: belongs to no brew or coffee",
            ),
            decoded.problems,
        )
        assertEquals(1, decoded.doc.beans.size)
        val brew = decoded.doc.brews.single()
        // Numbers written as text are read; an impossible score is brought into range.
        assertEquals(95, brew.tempC)
        assertEquals(15.5, brew.doseG, 0.0)
        assertEquals(10, brew.overall)
    }
}
