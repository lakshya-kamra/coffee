package com.laksh.coffee.backup

import com.laksh.coffee.data.backup.BackupMerge
import com.laksh.coffee.data.backup.LegacyLinking
import com.laksh.coffee.data.backup.LegacyMatch
import com.laksh.coffee.data.backup.StagedPhoto
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.backup.AnalysisDto
import com.laksh.coffee.domain.backup.BackupCodec
import com.laksh.coffee.domain.backup.BackupDoc
import com.laksh.coffee.domain.backup.BackupMapping
import com.laksh.coffee.domain.backup.BackupTime
import com.laksh.coffee.domain.backup.BeanDto
import com.laksh.coffee.domain.backup.BrewDto
import com.laksh.coffee.domain.backup.MergeMode
import com.laksh.coffee.domain.backup.PhotoBudget
import com.laksh.coffee.domain.backup.TextLimits
import com.laksh.coffee.domain.catalog.Catalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Already here" when the uid alone cannot say so: a 1.x export against an upgraded phone, the
 * grinder picked in setup against the one in a backup, and a photo whose row came across in a
 * device transfer without its image. Plus the limits an import keeps to.
 */
class ImportMatchingTest {

    private val legacyText = Fixtures.text(Fixtures.LEGACY)
    private val legacy: BackupDoc = Fixtures.decode(legacyText).doc
    private val v2: BackupDoc = BackupCodec.decodeV2(BackupCodec.parse(Fixtures.text(Fixtures.V2)) as JsonObject).doc
    private val photoUid = v2.photos.single().uid
    private val staged = mapOf(photoUid to StagedPhoto("$photoUid.jpg", 135, 1, 1))

    /** A phone that was upgraded from 1.x: the rows of the export, under uids the upgrade made up. */
    private fun upgradedPhone(): FakeStore = runBlocking {
        val store = FakeStore()
        BackupMerge.apply(store, legacy, MergeMode.Add, emptyMap(), now = 0L)
        store.beanRows.replaceAll { it.copy(uid = "migrated-bean-${it.id}") }
        store.grinderRows.replaceAll { it.copy(uid = "migrated-grinder-${it.id}") }
        store.brewRows.replaceAll { it.copy(uid = "migrated-brew-${it.id}") }
        store
    }

    // ------------------------------------------------------------------ 1.x exports

    @Test
    fun aOneXExportAddsNothingToAPhoneThatWasUpgradedFromIt() = runBlocking {
        val store = upgradedPhone()
        val rows = listOf(store.beanRows.toList(), store.grinderRows.toList(), store.brewRows.toList())
        val writes = store.writes

        val r = BackupMerge.apply(store, legacy, MergeMode.Add, emptyMap(), now = 5L, legacy = true)
        assertEquals(0, r.added.total)
        assertEquals(0, r.replaced.total)
        assertEquals(10, r.kept.total)
        assertEquals(writes, store.writes)
        assertEquals(rows, listOf(store.beanRows.toList(), store.grinderRows.toList(), store.brewRows.toList()))
        assertTrue("nothing was written, so nothing is linked or asked about", r.written.isEmpty())
    }

    @Test
    fun anExportSavedOnAnotherDayIsTheSameBrews() = runBlocking {
        // The uid of a 1.x record is made from the file's exportedAt, so a second export of the
        // same brews has other uids throughout.
        val later = Fixtures.decode(legacyText.replace("2026-11-17T09:41:07.123456Z", "2026-11-18T10:00:00.000001Z")).doc
        assertTrue(later.brews.map { it.uid }.intersect(legacy.brews.map { it.uid }.toSet()).isEmpty())

        val store = FakeStore()
        BackupMerge.apply(store, legacy, MergeMode.Add, emptyMap(), now = 0L, legacy = true)
        val writes = store.writes
        val r = BackupMerge.apply(store, later, MergeMode.Add, emptyMap(), now = 0L, legacy = true)
        assertEquals(0, r.added.total)
        assertEquals(writes, store.writes)
    }

    @Test
    fun aBrewThatIsNotHereIsAddedAndJoinsTheRowsThatAre() = runBlocking {
        val store = upgradedPhone()
        val gone = store.brewRows.first { it.beanId != null && it.grinderId != null }
        store.brewRows.remove(gone)

        val r = BackupMerge.apply(store, legacy, MergeMode.Add, emptyMap(), now = 0L, legacy = true)
        assertEquals(1, r.added.brews)
        assertEquals(1, r.added.total)
        val back = store.brewRows.single { it.startedAt == gone.startedAt }
        // Its coffee and grinder are the rows already here, not second copies.
        assertEquals(gone.beanId, back.beanId)
        assertEquals(gone.grinderId, back.grinderId)
        assertEquals(3, store.beanRows.size)
        assertEquals(3, store.grinderRows.size)
    }

    @Test
    fun matchingIsOneToOneAndOnlyByWhatIdentifiesARecord() {
        val twoBags = BackupDoc(
            beans = listOf(BeanDto("legacy-a", "Red Brick", roaster = "Square Mile"), BeanDto("legacy-b", "red  brick", roaster = "SQUARE MILE")),
            brews = listOf(
                BrewDto("legacy-1", "2026-11-10T07:12:30.500Z", recipeName = "The Ultimate V60 Technique", beanUid = "legacy-b"),
                BrewDto("legacy-2", "2026-11-10T07:12:30.500Z", recipeName = "Another recipe"),
            ),
        )
        val here = BeanEntity(id = 1, name = "Red Brick", roaster = "Square Mile", uid = "here-bean")
        val startedAt = BackupTime.parse("2026-11-10T07:12:30.500Z")!!
        val brew = BrewLogEntity(id = 2, startedAt = startedAt, recipeName = "The Ultimate V60 Technique", uid = "here-brew")

        val out = LegacyMatch.onto(twoBags, listOf(here), emptyList(), listOf(brew))
        // One bag here stands for one bag in the file; the second stays its own record.
        assertEquals(listOf("here-bean", "legacy-b"), out.beans.map { it.uid })
        // Same start time and recipe: the same brew. Same time, another recipe: not.
        assertEquals(listOf("here-brew", "legacy-2"), out.brews.map { it.uid })
        assertEquals("legacy-b", out.brews[0].beanUid)

        // A record that is here by its own uid is left alone, and its row is not lent to another.
        val again = LegacyMatch.onto(twoBags, listOf(here.copy(uid = "legacy-b")), emptyList(), emptyList())
        assertEquals(listOf("legacy-a", "legacy-b"), again.beans.map { it.uid })
        assertTrue(LegacyMatch.sameGrinder("1Zpresso", "K-Ultra", "1zpresso", "k ultra"))
        assertFalse(LegacyMatch.sameGrinder("", "", "", ""))
    }

    @Test
    fun aV2BackupIsMatchedByUidOnly() = runBlocking {
        val store = upgradedPhone()
        // Without the 1.x flag nothing is matched by content: a v2 uid is the record's own.
        val r = BackupMerge.apply(store, legacy, MergeMode.Add, emptyMap(), now = 0L)
        assertEquals(4, r.added.brews)
    }

    // ------------------------------------------------------------------ the grinder from setup

    private val setupGrinder = GrinderEntity(
        brand = "1Zpresso", model = "K-Ultra", clicksPerRotation = 100, micronsPerClick = 20.0, maxSetting = 200.0,
        isDefault = true, uid = "made-in-setup", updatedAt = 1, catalogId = "1zpresso-k-ultra",
    )

    @Test
    fun theGrinderPickedInSetupBecomesTheBackupsGrinder() = runBlocking {
        val store = FakeStore()
        val localId = store.insertGrinder(setupGrinder)

        val r = BackupMerge.apply(store, v2, MergeMode.Add, staged, now = 0L)
        val grinder = store.grinderRows.single()
        assertEquals("the same row, so the default-grinder setting still points at it", localId, grinder.id)
        assertEquals(v2.grinders.single().uid, grinder.uid)
        assertTrue(grinder.isDefault)
        assertTrue("the history is on the one grinder", store.brewRows.all { it.grinderId == localId })
        assertEquals(0, r.added.grinders)
        assertEquals(1, r.replaced.grinders)
        assertEquals(localId, r.grinderIds[grinder.uid])
        assertNull(r.grinderIds["made-in-setup"])
        assertTrue(grinder.uid in r.written)

        val writes = store.writes
        val again = BackupMerge.apply(store, v2, MergeMode.Add, staged, now = 0L)
        assertEquals(0, again.added.total + again.replaced.total)
        assertEquals(writes, store.writes)
    }

    @Test
    fun foldingKeepsTheCatalogLinkSetupMade() = runBlocking {
        // A backup whose grinder was never linked (same brand and model) keeps the link setup made,
        // so the scale question can still be asked about its 1.x numbers.
        val unlinked = v2.grinders.single().let { it.copy(catalogId = null, setting = it.setting.copy(clicksPerRotation = 10)) }
        val linkedHere = FakeStore()
        linkedHere.insertGrinder(setupGrinder)
        val folded = BackupMerge.apply(linkedHere, v2.copy(grinders = listOf(unlinked)), MergeMode.Add, staged, now = 0L)
        val row = linkedHere.grinderRows.single()
        assertEquals("1zpresso-k-ultra", row.catalogId)
        assertEquals(10, row.clicksPerRotation)
        assertEquals(listOf("grinder-scale:${row.uid}"), LegacyLinking.scaleNotices(linkedHere.grinderRows.filter { it.uid in folded.written }, Catalog::grinder))
    }

    @Test
    fun aGrinderWithBrewsOfItsOwnIsNeverFolded() = runBlocking {
        val store = FakeStore()
        val localId = store.insertGrinder(setupGrinder)
        store.insertBrew(BrewLogEntity(startedAt = 5, grinderId = localId, uid = "brewed-here"))

        val r = BackupMerge.apply(store, v2, MergeMode.Add, staged, now = 0L)
        assertEquals(2, store.grinderRows.size)
        assertEquals(1, r.added.grinders)
        assertEquals(listOf("made-in-setup"), store.grinderRows.filter { it.isDefault }.map { it.uid })

        // Nor one that is simply another grinder.
        val other = FakeStore()
        other.insertGrinder(GrinderEntity(brand = "Comandante", model = "C40", uid = "c40", updatedAt = 1))
        BackupMerge.apply(other, v2, MergeMode.Add, staged, now = 0L)
        assertEquals(2, other.grinderRows.size)
    }

    // ------------------------------------------------------------------ a row without its image

    @Test
    fun aPhotoRowWithoutItsImageTakesTheImageFromTheBackup() = runBlocking {
        // After a device transfer: the database came across, the photo folder did not.
        val store = FakeStore()
        BackupMerge.apply(store, v2, MergeMode.Add, staged, now = 0L)
        val row = store.photoRows.single()

        val fresh = mapOf(photoUid to StagedPhoto("restored.jpg", 200, 2, 3))
        val r = BackupMerge.apply(store, v2, MergeMode.Add, fresh, now = 0L, photosWithoutFile = setOf(photoUid))
        val after = store.photoRows.single()
        assertEquals(row.copy(fileName = "restored.jpg", bytes = 200, widthPx = 2, heightPx = 3), after)
        assertEquals(setOf("restored.jpg"), r.usedPhotoFiles)
        assertEquals(1, r.added.photos)
        assertEquals(0, r.kept.photos)
        assertEquals(8, r.kept.total)

        // A backup saved without photos has no image to give: the row stays as it is.
        val r2 = BackupMerge.apply(store, v2, MergeMode.Add, emptyMap(), now = 0L, photosWithoutFile = setOf(photoUid))
        assertEquals(0, r2.added.photos)
        assertEquals(after, store.photoRows.single())
        assertTrue(r2.usedPhotoFiles.isEmpty())
    }

    // ------------------------------------------------------------------ the scale question

    @Test
    fun aLinkedGrinderThatCountsItsDialDifferentlyIsAskedAboutAfterAnyImport() = runBlocking {
        // The owner's K-Ultra as a v2 backup carries it: linked on the old phone, still at 10 a turn.
        val oneX = v2.grinders.single().let { it.copy(setting = it.setting.copy(clicksPerRotation = 10, micronsPerStep = null, dialFormat = "")) }
        val store = FakeStore()
        val r = BackupMerge.apply(store, v2.copy(grinders = listOf(oneX)), MergeMode.Add, staged, now = 0L)
        val arrived = store.grinderRows.filter { it.uid in r.written }
        assertEquals(listOf("grinder-scale:${oneX.uid}"), LegacyLinking.scaleNotices(arrived, Catalog::grinder))

        // The fixture's own row agrees with the catalog, and an unlinked row has nothing to compare with.
        val agreeing = FakeStore()
        BackupMerge.apply(agreeing, v2, MergeMode.Add, staged, now = 0L)
        assertEquals(emptyList<String>(), LegacyLinking.scaleNotices(agreeing.grinderRows, Catalog::grinder))
        assertEquals(emptyList<String>(), LegacyLinking.scaleNotices(listOf(setupGrinder.copy(catalogId = null, clicksPerRotation = 10)), Catalog::grinder))
    }

    // ------------------------------------------------------------------ limits

    @Test
    fun textFromAFileIsCutToWhatARowCanHold() {
        val huge = "x".repeat(3_000_000)
        val brew = BackupMapping.brew(
            BrewDto("b", "2026-11-10T07:12:30Z", notes = huge, nextTime = huge, recipeName = huge, descriptors = List(500) { "d".repeat(1000) }),
            beanId = null, grinderId = null,
        )
        assertEquals(TextLimits.NOTE, brew.notes.length)
        assertEquals(TextLimits.NOTE, brew.nextTime.length)
        assertEquals(TextLimits.LABEL, brew.recipeName.length)
        assertEquals(TextLimits.LIST, brew.descriptors.size)
        assertTrue(brew.descriptors.all { it.length == TextLimits.LABEL })

        val bean = BackupMapping.bean(BeanDto("n", "Bean", notes = huge, varietalIds = List(100) { "v$it" }), fallbackMs = 0)
        assertEquals(TextLimits.NOTE, bean.notes.length)
        assertEquals(TextLimits.LIST, bean.varietalIds.size)

        val analysis = BackupMapping.analysis(
            AnalysisDto("a", "trend", createdAt = "2026-11-10T07:12:30Z", rawText = huge, result = JsonPrimitive(huge), flags = listOf(JsonPrimitive(huge))),
            brewLogId = null,
        )
        assertEquals(TextLimits.REPLY, analysis.rawText.length)
        assertNull("cut JSON would not be JSON", analysis.resultJson)
        assertEquals("[]", analysis.flagsJson)

        val recipe = (v2.recipes.single())
        assertNotNull(BackupMapping.savedRecipe(recipe, fallbackMs = 0))
        assertNull(BackupMapping.savedRecipe(JsonObject(recipe + ("name" to JsonPrimitive(huge))), fallbackMs = 0))

        // Ordinary text is untouched, and a cut never leaves half of a two-part character.
        assertEquals("Bright and sweet", TextLimits.note("Bright and sweet"))
        assertEquals("ab", TextLimits.clip("ab😀", 3))
        assertEquals(listOf("juicy"), TextLimits.words(listOf("juicy")))
    }

    @Test
    fun whenThePhotosDoNotAllFitTheNewestThatDoAreKept() {
        assertEquals(0L, PhotoBudget.base64Size(0))
        assertEquals(4L, PhotoBudget.base64Size(1))
        assertEquals(4L, PhotoBudget.base64Size(3))
        assertEquals(8L, PhotoBudget.base64Size(4))

        val photos = listOf(
            PhotoBudget.Sized("oldest-small", 1, 10),
            PhotoBudget.Sized("old-big", 2, 60),
            PhotoBudget.Sized("newest", 3, 50),
        )
        assertEquals(setOf("oldest-small", "old-big", "newest"), PhotoBudget.thatFit(photos, 120))
        // The big older one is passed over so the small one behind it still goes in.
        assertEquals(setOf("newest", "oldest-small"), PhotoBudget.thatFit(photos, 100))
        assertEquals(emptySet<String>(), PhotoBudget.thatFit(photos, 5))
        assertEquals(emptySet<String>(), PhotoBudget.thatFit(photos, -1))
    }
}
