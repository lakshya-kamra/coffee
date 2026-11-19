package com.laksh.coffee.backup

import com.laksh.coffee.data.backup.BackupMerge
import com.laksh.coffee.data.backup.StagedPhoto
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.backup.BackupCodec
import com.laksh.coffee.domain.backup.BackupDoc
import com.laksh.coffee.domain.backup.BackupTime
import com.laksh.coffee.domain.backup.Existing
import com.laksh.coffee.domain.backup.MergeMode
import com.laksh.coffee.domain.backup.MergePlanner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The merge rules of shared/schema/backup-v2.md ("Importing"), first as a pure plan, then applied. */
class MergeRulesTest {

    private data class Rec(val key: String, val at: Long?, val tag: String = "")

    private fun plan(incoming: List<Rec>, here: Map<String, Existing<Long>>, mode: MergeMode = MergeMode.Add) =
        MergePlanner.plan(incoming, { it.key }, { it.at }, here, mode)

    // ------------------------------------------------------------------ the planner

    @Test
    fun anUnknownKeyIsAdded() {
        val p = plan(listOf(Rec("a", 10)), emptyMap())
        assertEquals(listOf(Rec("a", 10)), p.add)
        assertTrue(p.replace.isEmpty() && p.keep.isEmpty())
    }

    @Test
    fun aNewerCopyReplacesAndKeepsTheLocalId() {
        val p = plan(listOf(Rec("a", 20)), mapOf("a" to Existing(7L, 10L)))
        assertEquals(7L, p.replace.single().id)
        assertTrue(p.add.isEmpty() && p.keep.isEmpty())
    }

    @Test
    fun anEqualOrOlderCopyIsKept() {
        assertEquals(1, plan(listOf(Rec("a", 10)), mapOf("a" to Existing(7L, 10L))).keep.size)
        assertEquals(1, plan(listOf(Rec("a", 9)), mapOf("a" to Existing(7L, 10L))).keep.size)
    }

    @Test
    fun aRecordWithoutUpdatedAtNeverReplaces() {
        val p = plan(listOf(Rec("a", null)), mapOf("a" to Existing(7L, 10L)))
        assertEquals(1, p.keep.size)
        // Not even a local row that has no stamp of its own.
        assertEquals(1, plan(listOf(Rec("a", null)), mapOf("a" to Existing(7L, null))).keep.size)
    }

    @Test
    fun aStampedCopyReplacesALocalRowThatHasNone() {
        assertEquals(1, plan(listOf(Rec("a", 1)), mapOf("a" to Existing(7L, null))).replace.size)
    }

    @Test
    fun replaceModePlansAgainstAnEmptyPhone() {
        val p = plan(listOf(Rec("a", 1), Rec("b", null)), mapOf("a" to Existing(7L, 99L)), MergeMode.Replace)
        assertEquals(2, p.add.size)
        assertTrue(p.replace.isEmpty() && p.keep.isEmpty())
    }

    @Test
    fun aKeyThatAppearsTwiceIsPlannedOnceFromItsNewestCopy() {
        val p = plan(listOf(Rec("a", 1, "old"), Rec("a", 5, "new"), Rec("a", 3, "middle"), Rec("b", null, "first"), Rec("b", null, "second")), emptyMap())
        assertEquals(listOf("new", "first"), p.add.map { it.tag })
    }

    // ------------------------------------------------------------------ applied

    private val fixture: BackupDoc = BackupCodec.decodeV2(BackupCodec.parse(Fixtures.text(Fixtures.V2)) as JsonObject).doc
    private val photoUid = fixture.photos.single().uid
    private val staged = mapOf(photoUid to StagedPhoto("$photoUid.jpg", 135, 1, 1))

    @Test
    fun theSameFileTwiceChangesNothingTheSecondTime() = runBlocking {
        val store = FakeStore()
        val first = BackupMerge.apply(store, fixture, MergeMode.Add, staged, now = 0L)
        assertEquals(9, first.added.total)
        assertEquals(0, first.kept.total)
        assertEquals(emptyList<String>(), first.skipped)
        assertTrue(first.arrived)
        assertEquals(setOf("$photoUid.jpg"), first.usedPhotoFiles)
        val snapshot = listOf(store.beanRows.toList(), store.grinderRows.toList(), store.brewRows.toList(), store.recipeRows.toList(), store.analysisRows.toList(), store.photoRows.toList())
        val writes = store.writes

        val second = BackupMerge.apply(store, fixture, MergeMode.Add, staged, now = 5L)
        assertEquals(0, second.added.total)
        assertEquals(0, second.replaced.total)
        assertEquals(9, second.kept.total)
        assertFalse(second.arrived)
        assertTrue("a kept photo's staged file is not used", second.usedPhotoFiles.isEmpty())
        assertEquals(writes, store.writes)
        assertEquals(snapshot, listOf(store.beanRows.toList(), store.grinderRows.toList(), store.brewRows.toList(), store.recipeRows.toList(), store.analysisRows.toList(), store.photoRows.toList()))
    }

    @Test
    fun rowsKeepTheFilesOwnTimestamps() = runBlocking {
        val store = FakeStore()
        BackupMerge.apply(store, fixture, MergeMode.Add, staged, now = 123L)
        assertEquals(BackupTime.parse(fixture.beans.single().updatedAt), store.beanRows.single().updatedAt)
        assertEquals(BackupTime.parse(fixture.brews[0].updatedAt), store.brewRows[0].updatedAt)
        assertEquals(BackupTime.parse(fixture.brews[0].startedAt), store.brewRows[0].startedAt)
        assertEquals(BackupTime.parse("2026-11-12T07:55:00Z"), store.recipeRows.single().updatedAt)
    }

    @Test
    fun aNewerCopyUpdatesTheRowInPlaceAndAnOlderOneIsIgnored() = runBlocking {
        val store = FakeStore()
        BackupMerge.apply(store, fixture, MergeMode.Add, staged, now = 0L)
        val brewId = store.brewRows[0].id
        val photoRowsBefore = store.photoRows.toList()

        val newer = fixture.copy(
            brews = listOf(fixture.brews[0].copy(notes = "Edited on the web", updatedAt = "2026-11-11T00:00:00Z")),
            beans = listOf(fixture.beans.single().copy(name = "Older name", updatedAt = "2026-01-01T00:00:00Z")),
            grinders = emptyList(), recipes = emptyList(), analyses = emptyList(), photos = emptyList(),
        )
        val r = BackupMerge.apply(store, newer, MergeMode.Add, emptyMap(), now = 0L)
        assertEquals(1, r.replaced.brews)
        assertEquals(1, r.kept.beans)
        assertEquals("Edited on the web", store.brewRows.single { it.id == brewId }.notes)
        assertEquals("Red Brick", store.beanRows.single().name)
        // Updated, not re-inserted: same id, so its photo and analysis rows still belong to it.
        assertEquals(photoRowsBefore, store.photoRows)
        assertTrue(store.analysisRows.any { it.brewLogId == brewId })
        assertEquals(2, store.brewRows.size)
    }

    @Test
    fun replaceEverythingDeletesFirstAndReportsTheOldPhotoFiles() = runBlocking {
        val store = FakeStore()
        BackupMerge.apply(store, fixture, MergeMode.Add, staged, now = 0L)
        // Something that is only on this phone.
        store.insertGrinder(GrinderEntity(brand = "Local", model = "Only", uid = "local-grinder", updatedAt = 1))
        val oldFile = store.photoRows.single().fileName

        val onlyBeans = fixture.copy(grinders = emptyList(), brews = emptyList(), recipes = emptyList(), analyses = emptyList(), photos = emptyList())
        val r = BackupMerge.apply(store, onlyBeans, MergeMode.Replace, emptyMap(), now = 0L)
        assertEquals(1, r.added.beans)
        assertEquals(1, r.added.total)
        assertEquals(0, r.kept.total)
        assertEquals(setOf(oldFile), r.removedPhotoFiles)
        assertTrue(store.grinderRows.isEmpty() && store.brewRows.isEmpty() && store.recipeRows.isEmpty())
        assertTrue(store.analysisRows.isEmpty() && store.photoRows.isEmpty())
        assertEquals(1, store.beanRows.size)
    }

    @Test
    fun theGrinderAlreadyDefaultHereStaysTheOnlyDefault() = runBlocking {
        val store = FakeStore()
        store.insertGrinder(GrinderEntity(brand = "Local", model = "Default", isDefault = true, uid = "local-grinder", updatedAt = 1))
        BackupMerge.apply(store, fixture, MergeMode.Add, staged, now = 0L)
        assertEquals(listOf("local-grinder"), store.grinderRows.filter { it.isDefault }.map { it.uid })

        // With no default here, the file's default becomes it.
        val empty = FakeStore()
        BackupMerge.apply(empty, fixture, MergeMode.Add, staged, now = 0L)
        assertEquals(1, empty.grinderRows.count { it.isDefault })
    }

    @Test
    fun whatCannotBePlacedIsLeftOutAndSaidSo() = runBlocking {
        val store = FakeStore()
        // No brews in the file: the brew analysis and the brew's photo have nothing to belong to.
        val orphaned = fixture.copy(brews = emptyList())
        val r = BackupMerge.apply(store, orphaned, MergeMode.Add, staged, now = 0L)
        assertEquals(2, r.added.analyses) // the whole-log ones
        assertEquals(0, r.added.photos)
        assertEquals(
            listOf("1 analysis is of a brew that isn't in the backup", "1 photo is of a brew or coffee that isn't in the backup"),
            r.skipped,
        )
        assertTrue(store.analysisRows.all { it.brewLogId == null })

        // A backup saved without photos: the photo is listed but there is no image to store.
        val noImages = FakeStore()
        val r2 = BackupMerge.apply(noImages, fixture, MergeMode.Add, emptyMap(), now = 0L)
        assertEquals(0, r2.added.photos)
        assertEquals(listOf("1 photo isn't in the file"), r2.skipped)
        assertTrue(noImages.photoRows.isEmpty())
    }

    @Test
    fun aBrewWhoseBeanIsNotInTheFileIsStillImportedWithItsLabel() = runBlocking {
        val store = FakeStore()
        val noBeans = fixture.copy(beans = emptyList(), photos = emptyList())
        BackupMerge.apply(store, noBeans, MergeMode.Add, emptyMap(), now = 0L)
        assertEquals(2, store.brewRows.size)
        assertTrue(store.brewRows.all { it.beanId == null && it.beanLabel.contains("Red Brick") })
    }
}
