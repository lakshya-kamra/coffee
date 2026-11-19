package com.laksh.coffee.data.repo

import android.content.Context
import com.laksh.coffee.data.db.CoffeeDatabase
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.db.entity.SavedRecipeEntity
import com.laksh.coffee.data.photos.PhotoStore
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.recipes.ExpertRecipes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

/**
 * [scope] lives as long as the process. The lists every tab reads (recipes, logs, beans,
 * grinders) are observed once there and kept hot, instead of each screen starting its own Room
 * query when it opens: a tab's first frame can then be built from the cached value, and its data
 * never lands halfway through the switch animation.
 */
class CoffeeRepository(context: Context, private val scope: CoroutineScope) {

    private val db = CoffeeDatabase.get(context)
    private val beans = db.beanDao()
    private val grinders = db.grinderDao()
    private val logs = db.brewLogDao()
    private val savedRecipes = db.savedRecipeDao()
    private val photos = db.photoDao()
    private val photoStore = PhotoStore(context)

    private fun now(): Long = System.currentTimeMillis()

    /** Null until Room's first answer, so a reader can tell "not loaded" from "empty". */
    private fun <T> Flow<T>.hot(): StateFlow<T?> = stateIn(scope, SharingStarted.Eagerly, null)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // ------------------------------------------------------------- recipes

    /**
     * Built-in recipes, with any user edit of the same id replacing the original,
     * plus recipes the user wrote from scratch.
     */
    private val recipesCache: StateFlow<List<Recipe>?> = savedRecipes.observeAll().map { saved ->
        val overrides = saved.mapNotNull { row ->
            runCatching { json.decodeFromString(Recipe.serializer(), row.json) }.getOrNull()
        }
        val byId = overrides.associateBy { it.id }
        val builtIns = ExpertRecipes.all.map { byId[it.id] ?: it }
        val builtInIds = ExpertRecipes.all.map { it.id }.toSet()
        builtIns + overrides.filter { it.id !in builtInIds }
    }.flowOn(Dispatchers.Default) // JSON decoding stays off the main thread
        .hot()

    val recipes: Flow<List<Recipe>> = recipesCache.filterNotNull()

    /** The recipes as last seen, or null before the first answer. Never blocks. */
    val recipesNow: List<Recipe>? get() = recipesCache.value

    suspend fun saveRecipe(recipe: Recipe) {
        savedRecipes.upsert(
            SavedRecipeEntity(
                id = recipe.id,
                json = json.encodeToString(Recipe.serializer(), recipe.copy(isBuiltIn = false)),
            )
        )
    }

    /** Drops a user override so the original expert version comes back. */
    suspend fun resetRecipe(id: String) = savedRecipes.deleteById(id)

    suspend fun recipeById(id: String): Recipe? =
        savedRecipes.byId(id)?.let { row -> runCatching { json.decodeFromString(Recipe.serializer(), row.json) }.getOrNull() }
            ?: ExpertRecipes.byId(id)

    // --------------------------------------------------------------- beans

    private val activeBeansCache = beans.observeActive().hot()
    private val allBeansCache = beans.observeAll().hot()
    val activeBeans: Flow<List<BeanEntity>> = activeBeansCache.filterNotNull()
    val allBeans: Flow<List<BeanEntity>> = allBeansCache.filterNotNull()
    val activeBeansNow: List<BeanEntity>? get() = activeBeansCache.value
    val allBeansNow: List<BeanEntity>? get() = allBeansCache.value
    suspend fun bean(id: Long) = beans.byId(id)
    suspend fun upsertBean(bean: BeanEntity): Long {
        val b = bean.copy(updatedAt = now())
        return if (b.id == 0L) beans.insert(b) else { beans.update(b); b.id }
    }
    suspend fun deleteBean(bean: BeanEntity) {
        // The foreign key removes the photo rows with the bean; their files have to be named first.
        val files = photos.forBean(bean.id).map { it.fileName }
        beans.delete(bean)
        photoStore.delete(files)
    }

    // ------------------------------------------------------------ grinders

    private val activeGrindersCache = grinders.observeActive().hot()
    val activeGrinders: Flow<List<GrinderEntity>> = activeGrindersCache.filterNotNull()
    val activeGrindersNow: List<GrinderEntity>? get() = activeGrindersCache.value
    suspend fun grinder(id: Long) = grinders.byId(id)
    suspend fun upsertGrinder(grinder: GrinderEntity): Long {
        val g = grinder.copy(updatedAt = now())
        if (g.isDefault) grinders.clearDefaults()
        return if (g.id == 0L) grinders.insert(g) else { grinders.update(g); g.id }
    }
    suspend fun deleteGrinder(grinder: GrinderEntity) = grinders.delete(grinder)

    // ----------------------------------------------------------- brew logs

    private val allLogsCache = logs.observeAll().hot()

    /** Newest first. */
    val allLogs: Flow<List<BrewLogEntity>> = allLogsCache.filterNotNull()
    val allLogsNow: List<BrewLogEntity>? get() = allLogsCache.value

    /** The newest [limit] brews, cut from the one hot log query rather than a query of its own. */
    fun recentLogs(limit: Int): Flow<List<BrewLogEntity>> = allLogs.map { it.take(limit) }
    fun logsSince(epochMs: Long): Flow<List<BrewLogEntity>> = logs.observeSince(epochMs)
    fun logsForBean(beanId: Long): Flow<List<BrewLogEntity>> = logs.observeForBean(beanId)
    fun observeLog(id: Long): Flow<BrewLogEntity?> = logs.observeById(id)
    val brewCount: Flow<Int> = logs.observeCount()

    suspend fun log(id: Long) = logs.byId(id)
    suspend fun insertLog(log: BrewLogEntity): Long = logs.insert(log.copy(updatedAt = now()))
    suspend fun updateLog(log: BrewLogEntity) = logs.update(log.copy(updatedAt = now()))
    suspend fun deleteLog(log: BrewLogEntity) {
        // As for a bean: rows go by cascade, files by hand, and only after the row is really gone.
        val files = photos.forBrew(log.id).map { it.fileName }
        logs.delete(log)
        photoStore.delete(files)
    }

    // -------------------------------------------------------------- photos

    fun observePhotosForBrew(brewLogId: Long): Flow<List<PhotoEntity>> = photos.observeForBrew(brewLogId)
    fun observePhotosForBean(beanId: Long): Flow<List<PhotoEntity>> = photos.observeForBean(beanId)

    // ---------------------------------------------------- whole-log snapshots

    /** No beans, grinders or brews: a fresh install (or a wiped one). */
    suspend fun isEmpty(): Boolean =
        logs.countAll() == 0 && beans.all().isEmpty() && grinders.all().isEmpty()

    suspend fun allBeans(): List<BeanEntity> = beans.all()
    suspend fun allGrinders(): List<GrinderEntity> = grinders.all()
    suspend fun allLogs(): List<BrewLogEntity> = logs.all()
    suspend fun allSavedRecipes(): List<SavedRecipeEntity> = savedRecipes.all()

    /** Catalog linking only: writes ids without touching updatedAt or any user-entered value. */
    suspend fun linkGrinder(grinder: GrinderEntity) = grinders.update(grinder)
    suspend fun linkBean(bean: BeanEntity) = beans.update(bean)

}
