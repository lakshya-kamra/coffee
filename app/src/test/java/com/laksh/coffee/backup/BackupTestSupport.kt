package com.laksh.coffee.backup

import com.laksh.coffee.data.ai.AnalysisEntity
import com.laksh.coffee.data.backup.BackupStore
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.db.entity.SavedRecipeEntity
import com.laksh.coffee.domain.backup.BackupCodec
import com.laksh.coffee.domain.backup.BackupKind
import com.laksh.coffee.domain.backup.Decoded
import com.laksh.coffee.domain.backup.LegacyExportReader
import com.laksh.coffee.domain.catalog.Catalog
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertNotNull

/** The shared fixtures are on the test classpath (app/build.gradle.kts adds shared/ as resources). */
object Fixtures {
    const val V2 = "fixtures/backup-v2-sample.json"
    const val LEGACY = "fixtures/coffee-brew-log-1-sample.json"
    const val LEGACY_EXPECTED = "fixtures/coffee-brew-log-1-expected.json"

    fun text(path: String): String {
        val stream = Fixtures::class.java.classLoader?.getResourceAsStream(path)
        assertNotNull("$path is not on the test classpath", stream)
        return stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Reads a file the way the importer does, minus the photo streaming. */
    fun decode(text: String): Decoded {
        val root = BackupCodec.parse(text) as JsonObject
        return when (BackupCodec.detect(root)) {
            BackupKind.V2 -> BackupCodec.decodeV2(root)
            BackupKind.Legacy1 -> LegacyExportReader.read(root, Catalog.recipes)
            else -> error("not a backup")
        }
    }
}

/** A phone's database as plain lists, with Room's behaviour where the merge depends on it. */
class FakeStore : BackupStore {
    val beanRows = ArrayList<BeanEntity>()
    val grinderRows = ArrayList<GrinderEntity>()
    val brewRows = ArrayList<BrewLogEntity>()
    val recipeRows = ArrayList<SavedRecipeEntity>()
    val analysisRows = ArrayList<AnalysisEntity>()
    val photoRows = ArrayList<PhotoEntity>()
    private var nextId = 100L

    /** Every write, so a test can say "the second import wrote nothing". */
    var writes = 0
        private set

    override suspend fun beans() = beanRows.toList()
    override suspend fun grinders() = grinderRows.toList()
    override suspend fun brews() = brewRows.toList()
    override suspend fun savedRecipes() = recipeRows.toList()
    override suspend fun analyses() = analysisRows.toList()
    override suspend fun photos() = photoRows.toList()

    override suspend fun deleteEverything() {
        writes++
        beanRows.clear(); grinderRows.clear(); brewRows.clear(); recipeRows.clear(); analysisRows.clear(); photoRows.clear()
    }

    private fun <T> unique(rows: List<T>, uid: (T) -> String, value: String) =
        check(rows.none { uid(it) == value }) { "uid $value is already here (the unique index would refuse it)" }

    override suspend fun insertBean(bean: BeanEntity): Long {
        unique(beanRows, { it.uid }, bean.uid)
        writes++
        return (nextId++).also { beanRows += bean.copy(id = it) }
    }

    override suspend fun updateBean(bean: BeanEntity) {
        writes++
        beanRows[beanRows.indexOfFirst { it.id == bean.id }.also { check(it >= 0) }] = bean
    }

    override suspend fun insertGrinder(grinder: GrinderEntity): Long {
        unique(grinderRows, { it.uid }, grinder.uid)
        writes++
        return (nextId++).also { grinderRows += grinder.copy(id = it) }
    }

    override suspend fun updateGrinder(grinder: GrinderEntity) {
        // The unique index: a row may take a new uid only when no other row has it.
        check(grinderRows.none { it.uid == grinder.uid && it.id != grinder.id })
        writes++
        grinderRows[grinderRows.indexOfFirst { it.id == grinder.id }.also { check(it >= 0) }] = grinder
    }

    override suspend fun insertBrew(brew: BrewLogEntity): Long {
        unique(brewRows, { it.uid }, brew.uid)
        // The foreign keys Room would enforce.
        check(brew.beanId == null || beanRows.any { it.id == brew.beanId })
        check(brew.grinderId == null || grinderRows.any { it.id == brew.grinderId })
        writes++
        return (nextId++).also { brewRows += brew.copy(id = it) }
    }

    override suspend fun updateBrew(brew: BrewLogEntity) {
        writes++
        brewRows[brewRows.indexOfFirst { it.id == brew.id }.also { check(it >= 0) }] = brew
    }

    override suspend fun upsertRecipe(recipe: SavedRecipeEntity) {
        writes++
        recipeRows.removeAll { it.id == recipe.id }
        recipeRows += recipe
    }

    override suspend fun insertAnalysis(analysis: AnalysisEntity): Long {
        unique(analysisRows, { it.uid }, analysis.uid)
        check(analysis.brewLogId == null || brewRows.any { it.id == analysis.brewLogId })
        writes++
        return (nextId++).also { analysisRows += analysis.copy(id = it) }
    }

    override suspend fun insertPhoto(photo: PhotoEntity): Long {
        unique(photoRows, { it.uid }, photo.uid)
        check(photo.brewLogId == null || brewRows.any { it.id == photo.brewLogId })
        check(photo.beanId == null || beanRows.any { it.id == photo.beanId })
        writes++
        return (nextId++).also { photoRows += photo.copy(id = it) }
    }

    override suspend fun updatePhoto(photo: PhotoEntity) {
        writes++
        photoRows[photoRows.indexOfFirst { it.id == photo.id }.also { check(it >= 0) }] = photo
    }
}
