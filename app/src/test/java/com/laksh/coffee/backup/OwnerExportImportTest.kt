package com.laksh.coffee.backup

import com.laksh.coffee.data.backup.BackupMerge
import com.laksh.coffee.data.backup.LegacyLinking
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.backup.BackupCodec
import com.laksh.coffee.domain.backup.BackupKind
import com.laksh.coffee.domain.backup.BackupSplitter
import com.laksh.coffee.domain.backup.MergeMode
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.grind.GrindScale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The importer run on a real 1.x export from a real phone, read in place.
 *
 * The file is private and is NOT in the repository. Its path comes from the environment
 * (COFFEE_OWNER_EXPORT, the same variable the web app's test reads), so no machine's folders are
 * written down here, and the test is skipped when the variable is unset or names no file:
 *
 *     COFFEE_OWNER_EXPORT=/path/to/export.json ./gradlew :app:testDebugUnitTest
 *
 * It expects an export whose grinder is a 1Zpresso K-Ultra saved the 1.x way (10 clicks a turn).
 * It asserts shapes and rules, counts everything from the file itself, and prints nothing from it.
 */
class OwnerExportImportTest {

    private val file: File? = System.getenv("COFFEE_OWNER_EXPORT")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

    private fun read(): Pair<String, com.laksh.coffee.domain.backup.Decoded> {
        assumeTrue("COFFEE_OWNER_EXPORT is not set to a file: real-export import tests skipped", file != null)
        // The same path the importer takes: stream, split off photos (there are none), decode.
        val body = file!!.reader(Charsets.UTF_8).use { reader -> BackupSplitter.split(reader) { error("a 1.x export has no photos") } }
        return body to Fixtures.decode(body)
    }

    private fun size(root: JsonObject, table: String): Int = (root[table] as JsonArray).size

    @Test
    fun itIsReadAsAOneXExportWithNothingLeftOut() {
        val (body, decoded) = read()
        assertEquals(BackupKind.Legacy1, BackupCodec.detect(body))
        assertEquals(emptyList<String>(), decoded.problems)
        val doc = decoded.doc
        // Counted from the file itself, so nothing about its contents is written down here.
        val raw = BackupCodec.parse(body) as JsonObject
        assertEquals(size(raw, "brews"), doc.brews.size)
        assertEquals(size(raw, "beans"), doc.beans.size)
        assertEquals(size(raw, "grinders"), doc.grinders.size)
        assertTrue("the export has brews in it", doc.brews.isNotEmpty())
        assertTrue(doc.brews.all { it.uid.startsWith("legacy-") && it.uid.length == 23 })
        val uids = doc.brews.map { it.uid } + doc.beans.map { it.uid } + doc.grinders.map { it.uid }
        assertEquals("every record has its own uid", uids.size, uids.toSet().size)
        // Every brew is the built-in it was brewed from, on a brewer the catalog knows.
        assertTrue(doc.brews.all { Catalog.recipe(it.recipeId) != null && Catalog.brewer(it.brewerId) != null })
        // Every brew points at a coffee in the file; one with a grinder named points at a grinder in the file.
        val beanUids = doc.beans.map { it.uid }.toSet()
        val grinderUids = doc.grinders.map { it.uid }.toSet()
        assertTrue(doc.brews.all { it.beanUid in beanUids })
        assertTrue(doc.brews.all { if (it.grinderLabel.isEmpty()) it.grinderUid == null else it.grinderUid in grinderUids })
    }

    @Test
    fun theLoggedGrindNumbersAndTheGrindersOwnScaleStayExactlyAsTheyWere() = runBlocking {
        val (body, decoded) = read()
        val store = FakeStore()
        val result = BackupMerge.apply(store, decoded.doc, MergeMode.Add, emptyMap(), now = 0L)
        assertEquals(decoded.doc.brews.size, result.added.brews)
        assertEquals(decoded.doc.beans.size, result.added.beans)
        assertEquals(decoded.doc.grinders.size, result.added.grinders)
        assertEquals(emptyList<String>(), result.skipped)

        // Lead decision 2: logged values are not rescaled. Each stored number is
        // compared with the number in the file, read here without going through the importer.
        val rawClicks = ((BackupCodec.parse(body) as JsonObject)["brews"] as JsonArray).map { brew ->
            ((brew as JsonObject)["grindClicks"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toInt()
        }
        assertEquals(rawClicks, store.brewRows.map { it.grindClicks })
        assertTrue("some of them are click counts", rawClicks.any { it != null })
        val grinder = store.grinderRows.first { it.brand == "1Zpresso" && it.model == "K-Ultra" }
        assertEquals(SettingStyle.Clicks, grinder.settingStyle)
        assertEquals(10, grinder.clicksPerRotation)
        assertNull(grinder.micronsPerClick)
        assertNull("not linked by the import itself", grinder.catalogId)

        // Then the catalog link: an id and a notice, never a number.
        val linked = LegacyLinking.link(store.grinderRows, store.beanRows, Catalog.grinders, Catalog.roasters, Catalog.origins)
        val linkedGrinder = linked.grinders.first { it.uid == grinder.uid }
        assertEquals("1zpresso-k-ultra", linkedGrinder.catalogId)
        assertEquals(grinder, linkedGrinder.copy(catalogId = null))
        assertTrue("grinder-scale:${grinder.uid}" in linked.notices)
        // The row still reads the 1.x way (7 turns + 5) until its owner accepts the catalog's scale.
        assertEquals("75 clicks (7 turns + 5)", linkedGrinder.describeClicks(75))
        assertNotNull(GrindScale.of(linkedGrinder))

        // Brews are joined to this phone's rows, not to the ids in the file.
        val beanIds = store.beanRows.map { it.id }.toSet()
        val grinderIds = store.grinderRows.map { it.id }.toSet()
        assertTrue(store.brewRows.all { it.beanId in beanIds })
        assertTrue(store.brewRows.all { if (it.grinderLabel.isEmpty()) it.grinderId == null else it.grinderId in grinderIds })
    }

    @Test
    fun importingItASecondTimeAddsNothing() = runBlocking {
        val (_, decoded) = read()
        val store = FakeStore()
        BackupMerge.apply(store, decoded.doc, MergeMode.Add, emptyMap(), now = 0L)
        val writes = store.writes
        val rows = store.brewRows.toList()

        val again = BackupMerge.apply(store, read().second.doc, MergeMode.Add, emptyMap(), now = 1L)
        assertEquals(0, again.added.total)
        assertEquals(0, again.replaced.total)
        assertEquals(decoded.doc.brews.size + decoded.doc.beans.size + decoded.doc.grinders.size, again.kept.total)
        assertEquals(writes, store.writes)
        assertEquals(rows, store.brewRows)
    }

    @Test
    fun aBackupMadeAfterwardsRoundTripsToAnotherPhone() = runBlocking {
        // 1.x export -> phone A -> backup v2 -> phone B: the path from the old debug app to 2.0.
        val (_, decoded) = read()
        val a = FakeStore()
        BackupMerge.apply(a, decoded.doc, MergeMode.Add, emptyMap(), now = 0L)
        val snapshot = com.laksh.coffee.domain.backup.BackupSnapshot(
            beans = a.beanRows, grinders = a.grinderRows, brews = a.brewRows, savedRecipes = emptyList(),
            analyses = emptyList(), photos = emptyList(), settings = com.laksh.coffee.data.prefs.Settings(), ai = null,
        )
        val doc = com.laksh.coffee.domain.backup.BackupMapping.document(
            snapshot, exportedAt = 1_800_000_000_000L,
            app = com.laksh.coffee.domain.backup.BackupApp("android", "2.0.0", "com.laksh.coffee.debug", 4),
            includesPhotos = false, includesAnalyses = true,
        )
        val text = BackupCodec.encode(doc)
        val b = FakeStore()
        val result = BackupMerge.apply(b, Fixtures.decode(text).doc, MergeMode.Add, emptyMap(), now = 0L)
        assertEquals(decoded.doc.brews.size + decoded.doc.beans.size + decoded.doc.grinders.size, result.added.total)
        // A backup lists brews oldest first, whatever order the 1.x file had them in.
        fun bare(store: FakeStore) = store.brewRows.sortedBy { it.uid }.map { it.copy(id = 0, beanId = null, grinderId = null) }
        assertEquals(bare(a), bare(b))
        assertEquals(a.grinderRows.map { it.copy(id = 0) }, b.grinderRows.map { it.copy(id = 0) })
        assertEquals(a.beanRows.map { it.copy(id = 0) }, b.beanRows.map { it.copy(id = 0) })
        // And bringing the original export to phone B afterwards still adds nothing.
        val late = BackupMerge.apply(b, decoded.doc, MergeMode.Add, emptyMap(), now = 0L)
        assertEquals(0, late.added.total + late.replaced.total)
    }
}
