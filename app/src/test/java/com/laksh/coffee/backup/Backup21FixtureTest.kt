package com.laksh.coffee.backup

import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.domain.backup.BackupCodec
import com.laksh.coffee.domain.backup.BackupKind
import com.laksh.coffee.domain.backup.BackupMapping
import com.laksh.coffee.domain.backup.BackupWriter
import com.laksh.coffee.domain.backup.Counts
import com.laksh.coffee.domain.catalog.Catalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * shared/fixtures/backup-v2.1-sample.json: a version 2 file with every key added in 2.1 (v4).
 * The web app's tests read the same file. backup-v2-sample.json stays as 2.0.0 wrote it.
 */
class Backup21FixtureTest {

    /** [e] with every key named in [keys] removed, at any depth. */
    private fun without(e: JsonElement, keys: Set<String>): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it !in keys }.mapValues { (_, v) -> without(v, keys) })
        is JsonArray -> JsonArray(e.map { without(it, keys) })
        else -> e
    }

    private fun without(o: JsonObject, keys: Set<String>): JsonObject = without(o as JsonElement, keys) as JsonObject

    private val text = Fixtures.text(Fixtures.V21)
    private val root = BackupCodec.parse(text) as JsonObject

    @Test
    fun bothSamplesRead() {
        assertEquals(BackupKind.V2, BackupCodec.detect(text))
        val new = BackupCodec.decodeV2(root)
        assertEquals(emptyList<String>(), new.problems)
        assertEquals(Counts(brews = 3, beans = 2, grinders = 2, recipes = 1, analyses = 3, photos = 1), Counts.of(new.doc))
        val old = BackupCodec.decodeV2(BackupCodec.parse(Fixtures.text(Fixtures.V2)) as JsonObject)
        assertEquals(emptyList<String>(), old.problems)
        // A 2.0.0 file has none of the new keys: everything reads as "not recorded".
        assertEquals(listOf(""), old.doc.beans.map { it.form })
        assertEquals(listOf(""), old.doc.grinders.map { it.setting.settingUnit })
        assertEquals(listOf("", ""), old.doc.brews.map { it.grindKind })
        assertEquals(null, old.doc.settings!!.reviewMode)
    }

    @Test
    fun everyNewKeyReads() {
        val doc = BackupCodec.decodeV2(root).doc
        val s = doc.settings!!
        assertEquals(listOf("new", false, "quick", true), listOf(s.experience, s.hasScale, s.reviewMode, s.buysGround))
        assertEquals(listOf("whole" to "", "ground" to "filter"), doc.beans.map { it.form to it.groundFor })
        assertEquals(listOf("", "s"), doc.grinders.map { it.setting.settingUnit })
        assertEquals(listOf("dial", "", "preground"), doc.brews.map { it.grindKind })
        assertEquals(listOf("Too weak"), doc.brews[2].faults)
        // Every catalog id the new records use is real.
        assertNotNull(Catalog.roaster(doc.beans[1].roasterId))
        assertNotNull(Catalog.grinder(doc.grinders[1].catalogId))
        assertNotNull(Catalog.brewer(doc.brews[2].brewerId))
        assertNotNull(Catalog.recipe(doc.brews[2].recipeId))
    }

    @Test
    fun roundTripsLosslessly() {
        val doc = BackupCodec.decodeV2(root).doc
        val written = StringBuilder().also { BackupWriter.write(it, doc, doc.photos.asSequence()) }.toString()
        val again = BackupCodec.decodeV2(BackupCodec.parse(written) as JsonObject)
        assertEquals(emptyList<String>(), again.problems)
        assertEquals(doc, again.doc)
        // Through the rows and back: nothing new is dropped on the way.
        val bean = doc.beans[1]
        assertEquals(bean, BackupMapping.bean(BackupMapping.bean(bean, 0L, id = 9)))
        val grinder = doc.grinders[1]
        assertEquals(grinder, BackupMapping.grinder(BackupMapping.grinder(grinder, 0L, id = 9)))
        val brew = doc.brews[2]
        assertEquals(brew, BackupMapping.brew(BackupMapping.brew(brew, beanId = 9, grinderId = null, id = 9), { brew.beanUid }, { null }))
        val settings = BackupMapping.settings(Settings(), doc.settings!!, { 0L }, { 0L })
        assertEquals(listOf("new", false, "quick", true), listOf(settings.experience, settings.hasScale, settings.reviewMode, settings.buysGround))
        val back = BackupMapping.settings(settings, { null }, { null })
        assertEquals(listOf("new", false, "quick", true), listOf(back.experience, back.hasScale, back.reviewMode, back.buysGround))
    }

    @Test
    fun aFileReadByTheTwoPointZeroRulesLosesOnlyTheNewKeys() {
        // What a 2.0.0 reader sees: the same file with the 2.1 keys ignored.
        val stripped = without(root, setOf("experience", "hasScale", "reviewMode", "buysGround", "form", "groundFor", "settingUnit", "grindKind"))
        val now = BackupCodec.decodeV2(root).doc
        val then = BackupCodec.decodeV2(stripped).doc
        assertEquals(now.settings!!.copy(experience = null, hasScale = null, reviewMode = null, buysGround = null), then.settings)
        assertEquals(now.beans.map { it.copy(form = "", groundFor = "") }, then.beans)
        assertEquals(now.grinders.map { it.copy(setting = it.setting.copy(settingUnit = "")) }, then.grinders)
        assertEquals(now.brews.map { it.copy(grindKind = "") }, then.brews)
        assertEquals(now.analyses, then.analyses)
        assertEquals(now.photos, then.photos)
    }
}
