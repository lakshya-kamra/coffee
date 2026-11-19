package com.laksh.coffee.data.backup

import com.laksh.coffee.data.ai.AnalysisEntity
import com.laksh.coffee.data.db.CoffeeDatabase
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.db.entity.SavedRecipeEntity

/** [BackupStore] over the app's database. The importer calls it inside one transaction. */
class RoomBackupStore(db: CoffeeDatabase) : BackupStore {

    private val beans = db.beanDao()
    private val grinders = db.grinderDao()
    private val brews = db.brewLogDao()
    private val recipes = db.savedRecipeDao()
    private val analyses = db.analysisDao()
    private val photos = db.photoDao()

    override suspend fun beans(): List<BeanEntity> = beans.all()
    override suspend fun grinders(): List<GrinderEntity> = grinders.all()
    override suspend fun brews(): List<BrewLogEntity> = brews.all()
    override suspend fun savedRecipes(): List<SavedRecipeEntity> = recipes.all()
    override suspend fun analyses(): List<AnalysisEntity> = analyses.all()
    override suspend fun photos(): List<PhotoEntity> = photos.all()

    override suspend fun deleteEverything() {
        // Children first, so nothing depends on the order the foreign keys cascade in.
        photos.deleteAll()
        analyses.deleteAll()
        brews.deleteAll()
        beans.deleteAll()
        grinders.deleteAll()
        recipes.deleteAll()
    }

    // New rows have id 0 and a uid the planner found nowhere on this phone, so the DAOs'
    // REPLACE strategy never has anything to replace here.
    override suspend fun insertBean(bean: BeanEntity): Long = beans.insert(bean.copy(id = 0))
    override suspend fun updateBean(bean: BeanEntity) = beans.update(bean)
    override suspend fun insertGrinder(grinder: GrinderEntity): Long = grinders.insert(grinder.copy(id = 0))
    override suspend fun updateGrinder(grinder: GrinderEntity) = grinders.update(grinder)
    override suspend fun insertBrew(brew: BrewLogEntity): Long = brews.insert(brew.copy(id = 0))
    override suspend fun updateBrew(brew: BrewLogEntity) = brews.update(brew)
    override suspend fun upsertRecipe(recipe: SavedRecipeEntity) = recipes.upsert(recipe)
    override suspend fun insertAnalysis(analysis: AnalysisEntity): Long = analyses.insert(analysis.copy(id = 0))
    override suspend fun insertPhoto(photo: PhotoEntity): Long = photos.insert(photo.copy(id = 0))
    override suspend fun updatePhoto(photo: PhotoEntity) = photos.update(photo)
}
