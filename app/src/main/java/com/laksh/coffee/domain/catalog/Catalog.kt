package com.laksh.coffee.domain.catalog

import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.Recipe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The process-wide registry of shared/ data: grinders, roasters, origins, bean vocabulary,
 * brewers, water, AI providers and the built-in recipes.
 *
 * Every list is parsed once, lazily, entry by entry: a bad entry is skipped and reported in
 * [problems], a missing file gives an empty list plus a problem line. Catalog data can never
 * crash the app. CoffeeApp preloads everything on a background thread at startup, so screens
 * normally find it ready; a main-thread read while preload runs waits on the same lazy.
 */
object Catalog {

    @Volatile private var source: CatalogSource = ClasspathCatalogSource()

    /** CoffeeApp.onCreate, before anything reads the catalog. JVM tests use the classpath default. */
    fun install(source: CatalogSource) {
        this.source = source
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val problemList = mutableListOf<String>()
    val problems: List<String> get() = synchronized(problemList) { problemList.toList() }

    private fun problem(line: String) {
        synchronized(problemList) { problemList += line }
    }

    // ---------------------------------------------------------------- lists

    val grinders: List<CatalogGrinder> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        entries(GRINDERS, "grinders", CatalogGrinder.serializer())
    }

    val roasters: List<CatalogRoaster> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        entries(ROASTERS, "roasters", CatalogRoaster.serializer())
    }

    val origins: List<CatalogOrigin> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        entries(ORIGINS, "origins", CatalogOrigin.serializer())
    }

    val beans: BeanVocabulary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val root = file(BEANS)
        BeanVocabulary(
            species = entries(BEANS, "species", Term.serializer(), root),
            processes = entries(BEANS, "processes", ProcessTerm.serializer(), root),
            varietals = entries(BEANS, "varietals", VarietalTerm.serializer(), root),
            lotTypes = entries(BEANS, "lotTypes", DescribedTerm.serializer(), root),
            decafMethods = entries(BEANS, "decafMethods", Term.serializer(), root),
            roastLevels = entries(BEANS, "roastLevels", DescribedTerm.serializer(), root),
            roastIntents = entries(BEANS, "roastIntents", Term.serializer(), root),
        )
    }

    val brewers: List<Brewer> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val fromFile = entries(BREWERS, "brewers", CatalogBrewer.serializer()).map {
            Brewer(
                id = it.id,
                label = it.name,
                brand = it.brand,
                category = it.category,
                method = BrewMethod.of(it.method),
                filter = it.filter,
                notes = it.notes,
            )
        }
        val ids = fromFile.map { it.id }.toSet()
        fromFile + LegacyIds.FALLBACK_BREWERS.filter { it.id !in ids }
    }

    val water: List<CatalogWater> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        entries(WATER, "water", CatalogWater.serializer())
    }

    val providers: List<CatalogProvider> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        entries(PROVIDERS, "providers", CatalogProvider.serializer())
    }

    /** Sourced brewing principles for the analysis prompt (WP-3). Empty when the file is absent. */
    val principles: List<Principle> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        entries(PRINCIPLES, "principles", Principle.serializer())
    }

    /** The built-in recipes, in file order. Brewers resolve through [brewers]. */
    val recipes: List<Recipe> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        entries(RECIPES, "recipes", Recipe.serializer()).map { it.copy(isBuiltIn = true) }
    }

    // --------------------------------------------------------------- lookups

    private val grinderById by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { grinders.associateBy { it.id } }
    private val roasterById by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { roasters.associateBy { it.id } }
    private val originById by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { origins.associateBy { it.id } }
    private val brewerById by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { brewers.associateBy { it.id } }
    private val waterById by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { water.associateBy { it.id } }
    private val providerById by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { providers.associateBy { it.id } }
    private val recipeById by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { recipes.associateBy { it.id } }
    private val processById by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { beans.processes.associateBy { it.id } }
    private val varietalById by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { beans.varietals.associateBy { it.id } }

    fun grinder(id: String?): CatalogGrinder? = id?.let { grinderById[it] }
    fun roaster(id: String?): CatalogRoaster? = id?.let { roasterById[it] }
    fun origin(id: String?): CatalogOrigin? = id?.let { originById[it] }
    fun brewer(id: String?): Brewer? = id?.let { brewerById[it] }
    fun water(id: String?): CatalogWater? = id?.let { waterById[it] }
    fun provider(id: String?): CatalogProvider? = id?.let { providerById[it] }
    fun recipe(id: String?): Recipe? = id?.let { recipeById[it] }
    fun process(id: String?): ProcessTerm? = id?.let { processById[it] }
    fun varietal(id: String?): VarietalTerm? = id?.let { varietalById[it] }

    /** The catalog label for a bean term, falling back to the 1.x label for legacy ids, then null. */
    fun label(kind: TermKind, id: String?): String? {
        if (id.isNullOrBlank()) return null
        val v = beans
        val found = when (kind) {
            TermKind.Species -> v.species.firstOrNull { it.id == id }?.label
            TermKind.Process -> process(id)?.label
            TermKind.Varietal -> varietal(id)?.label
            TermKind.LotType -> v.lotTypes.firstOrNull { it.id == id }?.label
            TermKind.Decaf -> v.decafMethods.firstOrNull { it.id == id }?.label
            TermKind.RoastLevel -> v.roastLevels.firstOrNull { it.id == id }?.label
            TermKind.RoastIntent -> v.roastIntents.firstOrNull { it.id == id }?.label
        }
        if (found != null) return found
        val legacy = when (kind) {
            TermKind.Process -> LegacyIds.PROCESS_LABEL
            TermKind.RoastLevel -> LegacyIds.ROAST_LEVEL_LABEL
            else -> return null
        }
        return legacy.entries.firstOrNull { it.value == id }?.key
    }

    /** Parses everything, brewers and recipes first because Home needs them on frame one. */
    fun preloadAll() {
        brewers; recipes; beans; grinders; roasters; origins; water; providers; principles
        recipeById; brewerById; grinderById; processById
    }

    // ---------------------------------------------------------------- parsing

    private const val GRINDERS = "catalog/grinders.json"
    private const val ROASTERS = "catalog/roasters.json"
    private const val ORIGINS = "catalog/origins.json"
    private const val BEANS = "catalog/beans.json"
    private const val BREWERS = "catalog/brewers.json"
    private const val WATER = "catalog/water.json"
    private const val PROVIDERS = "catalog/providers.json"
    private const val RECIPES = "recipes.json"
    private const val PRINCIPLES = "principles.json"

    private fun file(path: String): JsonObject? {
        val text = runCatching {
            source.open(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse { e ->
            problem("$path: unreadable (${e.message})")
            return null
        }
        if (text == null) {
            problem("$path: missing")
            return null
        }
        return runCatching { json.parseToJsonElement(text) as JsonObject }.getOrElse { e ->
            problem("$path: not a JSON object (${e.message?.take(120)})")
            null
        }
    }

    private fun <T> entries(path: String, key: String, serializer: KSerializer<T>, root: JsonObject? = file(path)): List<T> {
        if (root == null) return emptyList()
        val array = root[key] as? JsonArray ?: run {
            problem("$path: no \"$key\" list")
            return emptyList()
        }
        return array.mapIndexedNotNull { i, el ->
            runCatching { json.decodeFromJsonElement(serializer, el) }.getOrElse { e ->
                problem("$path[$i] (id=${idOf(el)}): ${e.message?.lineSequence()?.firstOrNull()?.take(160)}")
                null
            }
        }
    }

    private fun idOf(el: JsonElement): String =
        ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.content ?: "?"
}
