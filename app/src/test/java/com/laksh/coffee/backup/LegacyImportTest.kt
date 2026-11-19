package com.laksh.coffee.backup

import com.laksh.coffee.data.backup.BackupMerge
import com.laksh.coffee.data.backup.LegacyLinking
import com.laksh.coffee.domain.backup.BackupCodec
import com.laksh.coffee.domain.backup.BackupKind
import com.laksh.coffee.domain.backup.BackupTime
import com.laksh.coffee.domain.backup.BeanDto
import com.laksh.coffee.domain.backup.BrewDto
import com.laksh.coffee.domain.backup.GrinderDto
import com.laksh.coffee.domain.backup.LegacyExportReader
import com.laksh.coffee.domain.backup.MergeMode
import com.laksh.coffee.domain.catalog.Catalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 1.x export ("coffee-brew-log/1") read into backup v2. The expected values in
 * shared/fixtures/coffee-brew-log-1-expected.json were produced by an independent script, and
 * the web app is held to the same file, so both platforms give a 1.x record the same uid.
 */
class LegacyImportTest {

    private val sample = Fixtures.text(Fixtures.LEGACY)
    private val expected = BackupCodec.parse(Fixtures.text(Fixtures.LEGACY_EXPECTED)) as JsonObject
    private val decoded = Fixtures.decode(sample)
    private val doc = decoded.doc

    private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun num(o: JsonObject, key: String): Double? = str(o, key)?.toDouble()
    private fun rows(name: String): List<JsonObject> = (expected[name] as JsonArray).map { it as JsonObject }

    @Test
    fun theUidRecipeIsTheSharedOne() {
        // The vector from the web track's notes.
        assertEquals("legacy-bcf423451d822f64", LegacyExportReader.uid("2026-11-17T09:41:07.123456Z", "bean", "1"))
        assertEquals(str(expected, "exportedAtAsWritten"), "2026-11-17T09:41:07.123456Z")
    }

    @Test
    fun theSampleIsALegacyFileAndNothingIsLeftOut() {
        assertEquals(BackupKind.Legacy1, BackupCodec.detect(sample))
        assertEquals(emptyList<String>(), decoded.problems)
        assertEquals("1.x", doc.app.appVersion)
        // The file's own six-digit fraction is read down to milliseconds.
        assertEquals(java.time.Instant.parse("2026-11-17T09:41:07.123Z").toEpochMilli(), BackupTime.parse(doc.exportedAt))
        assertTrue(doc.recipes.isEmpty() && doc.analyses.isEmpty() && doc.photos.isEmpty())
        assertNull(doc.settings)
        assertNull(doc.ai)
    }

    @Test
    fun beansMatchTheExpectedFile() {
        val want = rows("beans")
        assertEquals(want.size, doc.beans.size)
        want.zip(doc.beans).forEach { (e, b: BeanDto) ->
            assertEquals(str(e, "uid"), b.uid)
            assertEquals(str(e, "name"), b.name)
            assertEquals(str(e, "roaster"), b.roaster)
            assertEquals(str(e, "origin"), b.origin)
            assertEquals(str(e, "varietal"), b.varietal)
            assertEquals(str(e, "processId"), b.processId)
            assertEquals(str(e, "roastLevelId"), b.roastLevelId)
            assertEquals(str(e, "roastedOn"), b.roastedOn)
            assertEquals(str(e, "tastingNotes"), b.tastingNotes)
            assertNull("1.x records carry no updatedAt", b.updatedAt)
        }
        // Text that merely says NaN is kept as typed.
        assertEquals("Says \"NaN\" on the bag", doc.beans[2].tastingNotes)
    }

    @Test
    fun grindersMatchTheExpectedFile() {
        val want = rows("grinders")
        assertEquals(want.size, doc.grinders.size)
        want.zip(doc.grinders).forEach { (e, g: GrinderDto) ->
            val s = e["setting"] as JsonObject
            assertEquals(str(e, "uid"), g.uid)
            assertEquals(str(e, "brand"), g.brand)
            assertEquals(str(e, "model"), g.model)
            assertEquals(str(e, "burr"), g.burr)
            assertEquals(str(s, "style"), g.setting.style)
            assertEquals(num(s, "min")!!, g.setting.min, 0.0)
            assertEquals(num(s, "max")!!, g.setting.max, 0.0)
            assertEquals(num(s, "step")!!, g.setting.step, 0.0)
            assertEquals(str(s, "lowerIsFiner"), g.setting.lowerIsFiner.toString())
            assertEquals(num(s, "clicksPerRotation")?.toInt(), g.setting.clicksPerRotation)
            assertEquals(num(s, "micronsPerStep"), g.setting.micronsPerStep)
            assertEquals(str(s, "dialFormat"), g.setting.dialFormat)
            assertNull(g.catalogId)
        }
        // Lead decision 2: the 1.x K-Ultra keeps 10 clicks a turn, exactly as it was stored.
        assertEquals(10, doc.grinders[0].setting.clicksPerRotation)
        // A bare NaN in the file reads as "not recorded".
        assertNull(doc.grinders[2].setting.micronsPerStep)
    }

    @Test
    fun brewsMatchTheExpectedFile() {
        val want = rows("brews")
        assertEquals(want.size, doc.brews.size)
        want.zip(doc.brews).forEach { (e, l: BrewDto) ->
            val bed = e["bed"] as JsonObject
            assertEquals(str(e, "uid"), l.uid)
            assertEquals(BackupTime.parse(str(e, "startedAt")), BackupTime.parse(l.startedAt))
            assertEquals(str(e, "recipeId"), l.recipeId)
            assertEquals(str(e, "brewerId"), l.brewerId)
            assertEquals(str(e, "brewerLabel"), l.brewerLabel)
            assertEquals(str(e, "beanUid"), l.beanUid)
            assertEquals(str(e, "grinderUid"), l.grinderUid)
            assertEquals(num(e, "grindClicks")?.toInt(), l.grindClicks)
            assertEquals(num(e, "grindValue"), l.grindValue)
            assertEquals(num(e, "doseG")!!, l.doseG, 0.0)
            assertEquals(num(e, "waterG")!!, l.waterG, 0.0)
            assertEquals(num(e, "tempC")!!.toInt(), l.tempC)
            assertEquals(num(e, "tdsPercent"), l.tdsPercent)
            assertEquals(num(e, "extractionYield"), l.extractionYield)
            assertEquals(str(e, "flow"), l.flow)
            assertEquals(str(bed, "shape"), l.bed.shape)
            assertEquals(str(bed, "surface"), l.bed.surface)
            assertEquals(str(bed, "channelling"), l.bed.channelling)
            assertEquals(str(bed, "notes"), l.bed.notes)
            assertEquals(num(e, "overall")!!.toInt(), l.overall)
            assertEquals(str(e, "updatedAt"), l.updatedAt)
            assertEquals(str(e, "isFavourite"), l.isFavourite.toString())
            str(e, "notes")?.let { assertEquals(it, l.notes) }
        }
        // NaN, Infinity and -Infinity as bare numbers are "not recorded"; in a note they are words.
        assertNull(doc.brews[1].tdsPercent)
        assertNull(doc.brews[1].extractionYield)
        assertNull(doc.brews[2].extractionYield)
        assertEquals("Note with NaN and Infinity typed in it", doc.brews[2].notes)
        // A brew whose bean was deleted keeps its label and links to nothing.
        assertNull(doc.brews[2].beanUid)
        assertEquals("A coffee I deleted", doc.brews[2].beanLabel)
    }

    @Test
    fun linkingAfterwardsGivesTheExpectedCatalogIdsAndTheScaleNotice() = runBlocking {
        val store = FakeStore()
        val result = BackupMerge.apply(store, doc, MergeMode.Add, emptyMap(), now = 0L)
        val linked = LegacyLinking.link(
            grinders = store.grinderRows.filter { it.uid in result.written },
            beans = store.beanRows.filter { it.uid in result.written },
            catalogGrinders = Catalog.grinders,
            roasters = Catalog.roasters,
            origins = Catalog.origins,
        )
        val after = expected["afterLinking"] as JsonObject

        val grinderIds = linked.grinders.associate { it.uid to it.catalogId }
        (after["grinders"] as JsonObject).forEach { (uid, id) ->
            assertEquals(uid, text(id), grinderIds[uid])
        }
        val beans = (store.beanRows.associateBy { it.uid } + linked.beans.associateBy { it.uid })
        (after["beans"] as JsonObject).forEach { (uid, ids) ->
            ids as JsonObject
            assertEquals(uid, str(ids, "roasterId"), beans.getValue(uid).roasterId)
            assertEquals(uid, str(ids, "originId"), beans.getValue(uid).originId)
        }
        assertEquals((after["pendingNotices"] as JsonArray).map { text(it) }, linked.notices)

        // Linking writes ids only: the K-Ultra's own numbers are untouched.
        val kUltra = linked.grinders.single { it.catalogId == "1zpresso-k-ultra" }
        assertEquals(10, kUltra.clicksPerRotation)
        assertNull(kUltra.micronsPerClick)
    }

    @Test
    fun importingTheSameExportTwiceChangesNothing() = runBlocking {
        val store = FakeStore()
        val first = BackupMerge.apply(store, doc, MergeMode.Add, emptyMap(), now = 0L)
        assertEquals(4, first.added.brews)
        assertEquals(3, first.added.beans)
        assertEquals(3, first.added.grinders)
        val writes = store.writes

        val second = BackupMerge.apply(store, Fixtures.decode(sample).doc, MergeMode.Add, emptyMap(), now = 999L)
        assertEquals(0, second.added.total)
        assertEquals(0, second.replaced.total)
        assertEquals(10, second.kept.total)
        assertEquals("the second import wrote nothing", writes, store.writes)
    }

    private fun text(e: JsonElement): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
