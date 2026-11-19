package com.laksh.coffee.data.ai

import android.database.sqlite.SQLiteConstraintException
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.AiJson
import com.laksh.coffee.domain.ai.AiProvider
import com.laksh.coffee.domain.ai.AiProviders
import com.laksh.coffee.domain.ai.AiResult
import com.laksh.coffee.domain.ai.AnalysisParser
import com.laksh.coffee.domain.ai.AnalysisPrompt
import com.laksh.coffee.domain.ai.BaseUrl
import com.laksh.coffee.domain.ai.BaseUrlCheck
import com.laksh.coffee.domain.ai.BrewRefs
import com.laksh.coffee.domain.ai.BrewAnalysis
import com.laksh.coffee.domain.ai.BrewPromptInput
import com.laksh.coffee.domain.ai.BuiltPrompt
import com.laksh.coffee.domain.ai.DataFlag
import com.laksh.coffee.domain.ai.DataQuality
import com.laksh.coffee.domain.ai.GrindMove
import com.laksh.coffee.domain.ai.GrindValueMove
import com.laksh.coffee.domain.ai.ModelCatalog
import com.laksh.coffee.domain.ai.Parsed
import com.laksh.coffee.domain.ai.PhotoMeta
import com.laksh.coffee.domain.ai.StoredResult
import com.laksh.coffee.domain.ai.TrendAnalysis
import com.laksh.coffee.domain.ai.TrendPromptInput
import com.laksh.coffee.domain.catalog.Catalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlin.math.roundToInt

sealed interface AnalysisTarget {
    data class Brew(val brewId: Long) : AnalysisTarget
    data object Trend : AnalysisTarget
}

data class UsageLine(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val costMicroUsd: Long?,
    val latencyMs: Long,
) {
    /** "3,180 in · 1,260 out · ≈0.11¢ · 14 s", leaving out whatever isn't known. */
    fun label(): String = listOfNotNull(
        promptTokens?.let { "${ModelCatalog.formatTokens(it)} in" },
        completionTokens?.let { "${ModelCatalog.formatTokens(it)} out" },
        costMicroUsd?.let { "≈" + ModelCatalog.formatUsd(it) },
        latencyMs.takeIf { it > 0 }?.let { "${(it / 1000.0).roundToInt()} s" },
    ).joinToString(" · ")
}

data class StoredAnalysis(
    val id: Long,
    val target: AnalysisTarget,
    val createdAt: Long,
    val model: String,
    val modelLabel: String,
    val host: String,
    val brew: BrewAnalysis?,
    val trend: TrendAnalysis?,
    val grindMove: GrindMove?,
    /** The same hero for numbered and stepless dials. */
    val valueMove: GrindValueMove?,
    val localWarnings: List<String>,
    val rawText: String,
    /** True only when the result decoded AND holds the answer for its kind, so brew/trend is non-null. */
    val parsed: Boolean,
    val flags: List<DataFlag>,
    val usage: UsageLine,
    val inputHash: String,
    val brewCount: Int,
    val newestBrewAt: Long,
    /** The AnalysisPrompt.VERSION the request was built with. */
    val promptVersion: Int,
    /** providers.json id, "custom", or "" for rows written before providers existed. */
    val providerId: String,
    /** The provider's name for the footer; "" when unknown. */
    val providerName: String,
    val photoCount: Int,
    /** Where the analysis ran: android | web. */
    val origin: String,
    /** A trend's brew numbers to brew uids (BrewRefs); null when the result was stored without them. */
    val brewUids: Map<String, String>? = null,
) {
    /** Written by the debug seed, not by a model: the UI must say so. */
    val isSample: Boolean get() = host == AnalysisRepository.SAMPLE_HOST

    /**
     * Why this answer may no longer fit, or null when it is current. [currentHash] is the hash
     * of the prompt the app would send now; null when that can't be known (a blocked preview),
     * which never counts as edited.
     */
    fun staleReason(currentHash: String?): StaleReason? = when {
        // Another platform builds its prompt differently, so its hash can't be compared here.
        origin != AnalysisRepository.ORIGIN -> StaleReason.OtherDevice
        // An older prompt always hashes differently; saying "edited" would be false.
        promptVersion < AnalysisPrompt.VERSION -> StaleReason.OlderPrompt
        // So does a hash an earlier build made from row ids: it differs after any restore,
        // whether or not the brew was touched.
        !AnalysisPrompt.isCurrentHash(inputHash) -> StaleReason.OlderPrompt
        currentHash != null && inputHash != currentHash -> StaleReason.Edited
        else -> null
    }
}

/** Why a stored analysis is out of date. The words for each are the UI's (ui/ai/AnalysisCard). */
enum class StaleReason { Edited, OlderPrompt, OtherDevice }

data class AnalysisPreview(
    /** Brew: flags on the target brew. Trend: flags on every included brew. */
    val flags: List<DataFlag>,
    /** Brew: flags on the earlier brews sent as context. Trend: empty. */
    val contextFlags: List<DataFlag>,
    val inputHash: String,
    val brewCount: Int,
    val ratedCount: Int,
    val newestBrewAt: Long,
    val approxPromptTokens: Int,
    val expectedCompletionTokens: Int,
    /** Why an analysis can't run right now (NotEnoughData, BrewMissing), or null. */
    val blocker: AiError?,
    /** Photos that would go with the request. Each adds about ModelCatalog.TOKENS_PER_IMAGE tokens. */
    val photoCount: Int = 0,
)

/**
 * The brew's photos for an analysis: the newest ticked bed and cup photos, at most
 * [AnalysisPrompt.MAX_PHOTOS]. WP-4 supplies the real one; until then nothing is attached.
 */
interface PhotoAttachmentSource {
    /** What would be attached, without reading any file. Feeds the PHOTOS block and the estimate. */
    suspend fun metaForBrew(brewId: Long): List<PhotoMeta>

    /** The same photos, encoded, each with the uid of its [PhotoMeta]. A photo whose file is gone is left out. */
    suspend fun forBrew(brewId: Long): List<AiImage>
}

object NoPhotos : PhotoAttachmentSource {
    override suspend fun metaForBrew(brewId: Long): List<PhotoMeta> = emptyList()
    override suspend fun forBrew(brewId: Long): List<AiImage> = emptyList()
}

/**
 * Runs, stores and serves analyses.
 *
 * Runs live in the app-wide [scope], not a ViewModel, so leaving the screen never throws away a
 * reply that has already been paid for. [start] is idempotent per target, so a double tap can't
 * bill twice. Nothing here runs by itself: every network call starts from a tap.
 *
 * Failures and cancels are only held in memory ([failures]); they are never written to Room,
 * and a failed re-run never hides the previous result. Nothing is ever logged.
 */
class AnalysisRepository(
    private val coffee: CoffeeRepository,
    private val dao: AnalysisDao,
    private val settings: AiSettingsStore,
    private val clients: ChatClients,
    private val scope: CoroutineScope,
    private val photos: PhotoAttachmentSource = NoPhotos,
    /** "c" or "f": the unit the person reads temperatures in. */
    private val tempUnit: suspend () -> String = { "c" },
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _running = MutableStateFlow<Map<AnalysisTarget, Long>>(emptyMap())
    private val _failures = MutableStateFlow<Map<AnalysisTarget, AiError>>(emptyMap())
    private val jobs = mutableMapOf<AnalysisTarget, Job>()
    private val lock = Any()

    /** Target to the time its run started. */
    val running: StateFlow<Map<AnalysisTarget, Long>> = _running.asStateFlow()
    val failures: StateFlow<Map<AnalysisTarget, AiError>> = _failures.asStateFlow()

    val analysedBrewIds: Flow<Set<Long>> = dao.observeAnalysedBrewIds().map { it.toSet() }

    fun latest(target: AnalysisTarget): Flow<StoredAnalysis?> {
        val rows = when (target) {
            is AnalysisTarget.Brew -> dao.observeLatestForBrew(target.brewId)
            AnalysisTarget.Trend -> dao.observeLatestTrend()
        }
        return rows.map { it?.toStored() }.flowOn(Dispatchers.Default)
    }

    /** Local only, never the network: flags, the input hash for staleness, and the estimate. */
    suspend fun preview(target: AnalysisTarget): AnalysisPreview = try {
        when (val b = build(target)) {
            is Build.Blocked -> blockedPreview(b.error, b.brewCount, b.ratedCount)
            is Build.Ready -> {
                val built = b.built
                val (own, context) = when (target) {
                    is AnalysisTarget.Brew -> built.flags.partition { it.brewId == target.brewId }
                    AnalysisTarget.Trend -> built.flags to emptyList<DataFlag>()
                }
                AnalysisPreview(
                    flags = own,
                    contextFlags = context,
                    inputHash = built.inputHash,
                    brewCount = built.brewCount,
                    ratedCount = built.ratedCount,
                    newestBrewAt = built.newestBrewAt,
                    approxPromptTokens = built.approxPromptTokens,
                    expectedCompletionTokens = built.expectedCompletionTokens,
                    blocker = null,
                    photoCount = built.photoCount,
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A broken row or a builder bug must show as a disabled card, not crash the screen.
        blockedPreview(AiError.Unknown(e.javaClass.simpleName.ifBlank { "Exception" }), 0, 0)
    }

    /** Starts a run unless one is already going for this target. Returns false if it was. */
    fun start(target: AnalysisTarget): Boolean {
        synchronized(lock) {
            if (target in jobs) return false
            _failures.update { it - target }
            _running.update { it + (target to now()) }
            // Lazy, so the job is registered and its completion hooked before it can finish.
            val job = scope.launch(start = CoroutineStart.LAZY) { perform(target) }
            jobs[target] = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (jobs[target] === job) {
                        jobs.remove(target)
                        _running.update { it - target }
                    }
                }
            }
            job.start()
        }
        return true
    }

    /** Stops waiting. Records no failure; the provider may still bill what it already did. */
    fun cancel(target: AnalysisTarget) {
        synchronized(lock) { jobs[target] }?.cancel()
    }

    fun clearFailure(target: AnalysisTarget) {
        _failures.update { it - target }
    }

    suspend fun delete(analysisId: Long) = dao.deleteById(analysisId)

    /** Debug seed only: stores a sample so screenshots show the result UI without a key. */
    suspend fun insertSample(
        target: AnalysisTarget,
        result: StoredResult,
        rawText: String,
        promptTokens: Int,
        completionTokens: Int,
        model: String = SAMPLE_MODEL,
    ): Long {
        val p = preview(target)
        val row = AnalysisEntity(
            kind = kindOf(target),
            brewLogId = (target as? AnalysisTarget.Brew)?.brewId,
            createdAt = now(),
            promptVersion = AnalysisPrompt.VERSION,
            // The real hash of today's prompt, so a sample doesn't show as out of date.
            inputHash = p.inputHash,
            brewCount = p.brewCount,
            newestBrewAt = p.newestBrewAt,
            model = model,
            host = SAMPLE_HOST,
            rawText = rawText,
            resultJson = AiJson.stored.encodeToString(StoredResult.serializer(), withBrewUids(result, coffee.allLogs.first())),
            flagsJson = encodeFlags(p.flags + p.contextFlags),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            costMicroUsd = ModelCatalog.price(AiProviders.COMMANDCODE, model)?.let {
                ModelCatalog.costMicroUsd(it, promptTokens, completionTokens)
            },
            latencyMs = 0,
            providerId = "sample",
        )
        val id = dao.insert(row)
        prune(target)
        return id
    }

    /** Debug seed only: removes every sample so the seed can write them again. Returns how many. */
    suspend fun deleteSamples(): Int {
        val rows = dao.byHost(SAMPLE_HOST)
        rows.forEach { dao.deleteById(it.id) }
        return rows.size
    }

    /**
     * Debug seed only: gives every sample its current input hash again. A prompt change (such
     * as dates moving to UTC) would otherwise show the seeded samples as out of date, which a
     * real analysis rightly would be but a screenshot fixture should not.
     */
    suspend fun restampSamples() {
        dao.byHost(SAMPLE_HOST).forEach { row ->
            val target = if (row.kind == AnalysisEntity.KIND_TREND) {
                AnalysisTarget.Trend
            } else {
                AnalysisTarget.Brew(row.brewLogId ?: return@forEach)
            }
            val p = preview(target)
            if (p.blocker == null && p.inputHash != row.inputHash) dao.setInputHash(row.id, p.inputHash)
        }
    }

    // ------------------------------------------------------------------- the run

    private suspend fun perform(target: AnalysisTarget) {
        try {
            val s = settings.snapshot()
            val provider = AiProviders.byId(s.providerId)
                ?: return fail(target, AiError.NotConfigured(missingKey = true, missingModel = false))
            val base = when (val c = BaseUrl.check(s.baseUrl)) {
                is BaseUrlCheck.Invalid -> return fail(target, c.error)
                is BaseUrlCheck.Valid -> c
            }
            if (!s.hasKey || s.model.isBlank()) {
                return fail(
                    target,
                    AiError.NotConfigured(
                        missingKey = !s.hasKey,
                        missingModel = s.model.isBlank(),
                        host = base.host,
                        keyHost = s.keyHost,
                    ),
                )
            }
            // Bound to the provider and the host this request is about to go to, checked at the moment of use.
            val key = try {
                settings.readKey(providerId = provider.id, forHost = base.host)
            } catch (e: KeyVaultException) {
                return fail(target, AiError.KeyUnreadable)
            } ?: return fail(
                target,
                AiError.NotConfigured(missingKey = true, missingModel = false, host = base.host, keyHost = s.keyHost),
            )

            // The photos are read first: the prompt names exactly the ones that are attached.
            val images = imagesFor(target, s)
            val ready = when (val b = build(target, s, attached = images.map { it.uid })) {
                is Build.Blocked -> return fail(target, b.error)
                is Build.Ready -> b
            }
            val built = ready.built
            val request = AiRequest(
                model = s.model,
                system = built.system,
                user = built.user,
                images = images,
                temperature = AnalysisPrompt.TEMPERATURE,
                maxTokens = built.maxTokens,
            )
            val endpoint = Endpoint.of(provider, base.normalized)
            val reply = when (val r = clients.forApi(provider.api).complete(endpoint, key, request)) {
                is AiResult.Err -> return fail(target, withCostLabel(error = r.error, provider = provider, model = s.model, built = built))
                is AiResult.Ok -> r.value
            }

            val result = parse(ready, reply.content)
            // Whatever the finish reason said, a reply that spent the whole budget and does not
            // parse was cut off. It surfaces as Truncated rather than being stored as a raw read.
            val spent = reply.completionTokens
            if (result == null && spent != null && spent >= built.maxTokens) {
                return fail(
                    target,
                    withCostLabel(error = AiError.Truncated(spent, null), provider = provider, model = s.model, built = built),
                )
            }
            val promptTokens = reply.promptTokens
            val completionTokens = reply.completionTokens
            val cost = if (promptTokens != null && completionTokens != null) {
                ModelCatalog.price(provider.id, s.model)?.let {
                    ModelCatalog.costMicroUsd(it, promptTokens, completionTokens)
                }
            } else {
                null
            }
            val row = AnalysisEntity(
                kind = kindOf(target),
                brewLogId = ready.log?.id,
                createdAt = now(),
                promptVersion = AnalysisPrompt.VERSION,
                inputHash = built.inputHash,
                brewCount = built.brewCount,
                newestBrewAt = built.newestBrewAt,
                model = s.model,
                host = base.host,
                rawText = reply.content,
                resultJson = result?.let { AiJson.stored.encodeToString(StoredResult.serializer(), it) },
                flagsJson = encodeFlags(built.flags),
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                costMicroUsd = cost,
                latencyMs = reply.latencyMs,
                providerId = provider.id,
                photoCount = images.size,
                origin = ORIGIN,
            )
            // The brew may have been deleted while the model was thinking. The check covers the
            // usual case; the foreign key catches the race.
            if (ready.log != null && coffee.log(ready.log.id) == null) {
                return fail(target, AiError.BrewMissing)
            }
            try {
                dao.insert(row)
            } catch (e: SQLiteConstraintException) {
                return fail(target, AiError.BrewMissing)
            }
            prune(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Anything unexpected becomes an error card; it must never take the app down.
            fail(target, AiError.Unknown(e.javaClass.simpleName.ifBlank { "Exception" }))
        }
    }

    /** Photos go only with a brew, only when the person left the switch on, and only to a model that sees them. */
    private fun sendsPhotos(target: AnalysisTarget, s: AiSettings): Boolean =
        target is AnalysisTarget.Brew && s.sendPhotos && s.modelSeesImages

    private suspend fun imagesFor(target: AnalysisTarget, s: AiSettings): List<AiImage> {
        if (target !is AnalysisTarget.Brew || !sendsPhotos(target, s)) return emptyList()
        return photos.forBrew(target.brewId).take(AnalysisPrompt.MAX_PHOTOS)
    }

    /** A reply that isn't the JSON contract is still stored (resultJson null) and shown raw. */
    private fun parse(ready: Build.Ready, content: String): StoredResult? {
        val log = ready.log
        if (log == null) {
            return when (val p = AnalysisParser.parseTrend(content)) {
                is Parsed.Ok -> withBrewUids(StoredResult(trend = p.value), ready.history)
                is Parsed.Raw -> null
            }
        }
        return when (val p = AnalysisParser.parseBrew(content)) {
            is Parsed.Ok -> {
                // The hero number starts from a value the app trusts, never the model's guess, and
                // from the same anchor the prompt told the model to start from.
                val method = DataQuality.brewerOf(log, ready.recipe)?.method
                val (move, warnings) = GrindMove.resolve(p.value.change, ready.grinder, ready.built.grindAnchor, method)
                val (valueMove, valueWarnings) =
                    GrindValueMove.resolve(p.value.change, ready.grinder, ready.built.valueAnchor, method)
                StoredResult(brew = p.value, grindMove = move, valueMove = valueMove, localWarnings = warnings + valueWarnings)
            }
            is Parsed.Raw -> null
        }
    }

    /** A trend keeps the uid of every brew its patterns name: the numbers alone don't survive a restore. */
    private fun withBrewUids(result: StoredResult, brews: List<BrewLogEntity>): StoredResult {
        val trend = result.trend ?: return result
        if (result.brewUids != null) return result
        val uids = brews.associate { it.id to it.uid }
        return result.copy(brewUids = BrewRefs.uidsFor(trend, uids::get))
    }

    private fun withCostLabel(error: AiError, provider: AiProvider, model: String, built: BuiltPrompt): AiError {
        if (error !is AiError.Truncated || error.costLabel != null) return error
        val completion = error.completionTokens ?: return error
        val price = ModelCatalog.price(provider.id, model) ?: return error
        // The prompt side is the local estimate: a cut-off reply carries no usage we can trust.
        val promptTokens = built.approxPromptTokens + built.photoCount * ModelCatalog.TOKENS_PER_IMAGE
        val micro = ModelCatalog.costMicroUsd(price, promptTokens, completion)
        return error.copy(costLabel = "≈" + ModelCatalog.formatUsd(micro))
    }

    private fun fail(target: AnalysisTarget, error: AiError) {
        _failures.update { it + (target to error) }
    }

    private suspend fun prune(target: AnalysisTarget) {
        when (target) {
            is AnalysisTarget.Brew -> dao.pruneBrew(target.brewId, KEEP_PER_TARGET)
            AnalysisTarget.Trend -> dao.pruneTrend(KEEP_PER_TARGET)
        }
    }

    // ------------------------------------------------------------ prompt building

    private sealed interface Build {
        data class Ready(
            val built: BuiltPrompt,
            /** The target brew; null for a trend. */
            val log: BrewLogEntity?,
            /** The earlier brews sent as context; for a trend, every brew sent. */
            val history: List<BrewLogEntity>,
            val grinder: GrinderEntity?,
            val recipe: Recipe? = null,
        ) : Build

        data class Blocked(val error: AiError, val brewCount: Int = 0, val ratedCount: Int = 0) : Build
    }

    private class Loaded(
        val recipes: Map<String, Recipe>,
        val beans: Map<Long, BeanEntity>,
        val grinders: Map<Long, GrinderEntity>,
        /** Newest first. */
        val logs: List<BrewLogEntity>,
    )

    private suspend fun load(): Loaded {
        val logs = coffee.allLogs.first()
        val recipes = coffee.recipes.first().associateBy { it.id }
        val beans = coffee.allBeans.first().associateBy { it.id }
        val grinders = coffee.activeGrinders.first().associateBy { it.id }.toMutableMap()
        // Archived grinders aren't in the active list, but old brews still name them.
        logs.mapNotNull { it.grinderId }.distinct().filter { it !in grinders }.forEach { id ->
            coffee.grinder(id)?.let { grinders[id] = it }
        }
        return Loaded(recipes, beans, grinders, logs)
    }

    /**
     * [settings] decides whether photos go along; [attached] is the uids of the photos that
     * really were read for a run (null for a preview, which assumes every one can be).
     */
    private suspend fun build(
        target: AnalysisTarget,
        settings: AiSettings? = null,
        attached: List<String>? = null,
    ): Build = withContext(Dispatchers.Default) {
        val ctx = load()
        val units = runCatching { tempUnit() }.getOrDefault("c")
        val principles = Catalog.principles
        when (target) {
            is AnalysisTarget.Brew -> {
                val log = coffee.log(target.brewId) ?: return@withContext Build.Blocked(AiError.BrewMissing)
                val history = ctx.logs
                    .filter { it.startedAt < log.startedAt }
                    .sortedByDescending { it.startedAt }
                    .take(AnalysisPrompt.HISTORY_FOR_BREW)
                val grinder = log.grinderId?.let { ctx.grinders[it] }
                val recipe = ctx.recipes[log.recipeId]
                val s = settings ?: this@AnalysisRepository.settings.snapshot()
                val all = photos.metaForBrew(log.id).take(AnalysisPrompt.MAX_PHOTOS)
                val metas = when {
                    !sendsPhotos(target, s) -> emptyList()
                    attached == null -> all
                    // On a run, name the photos in the order they are attached and no others.
                    else -> attached.mapNotNull { uid -> all.firstOrNull { it.uid == uid } }
                }
                val built = AnalysisPrompt.brew(
                    BrewPromptInput(
                        log = log,
                        recipe = recipe,
                        bean = log.beanId?.let { ctx.beans[it] },
                        grinder = grinder,
                        history = history,
                        recipes = ctx.recipes,
                        beans = ctx.beans,
                        grinders = ctx.grinders,
                        photos = metas,
                        brewPhotos = all,
                        units = units,
                        principles = principles,
                    ),
                )
                Build.Ready(built = built, log = log, history = history, grinder = grinder, recipe = recipe)
            }
            AnalysisTarget.Trend -> {
                val capped = ctx.logs.take(AnalysisPrompt.TREND_MAX_BREWS)
                val rated = capped.count { it.overall > 0 }
                // Checked before the builder runs, so an empty log never reaches it.
                if (rated < AnalysisPrompt.MIN_RATED_FOR_TREND) {
                    return@withContext Build.Blocked(
                        error = AiError.NotEnoughData(AnalysisPrompt.MIN_RATED_FOR_TREND, rated),
                        brewCount = capped.size,
                        ratedCount = rated,
                    )
                }
                val built = AnalysisPrompt.trend(
                    TrendPromptInput(
                        logs = capped,
                        totalLogged = ctx.logs.size,
                        recipes = ctx.recipes,
                        beans = ctx.beans,
                        grinders = ctx.grinders,
                        units = units,
                        principles = principles,
                    ),
                )
                Build.Ready(built = built, log = null, history = capped, grinder = null)
            }
        }
    }

    // ------------------------------------------------------------------- mapping

    private fun AnalysisEntity.toStored(): StoredAnalysis {
        val result = resultJson?.let {
            runCatching { AiJson.stored.decodeFromString(StoredResult.serializer(), it) }.getOrNull()
        }
        val flags = runCatching {
            AiJson.stored.decodeFromString(ListSerializer(DataFlag.serializer()), flagsJson)
        }.getOrDefault(emptyList())
        val isTrend = kind == AnalysisEntity.KIND_TREND
        val target = if (isTrend) AnalysisTarget.Trend else AnalysisTarget.Brew(brewLogId ?: 0L)
        // '{}' decodes fine but holds no answer, so it counts as unparsed and shows the raw reply.
        val parsed = result != null && (if (isTrend) result.trend != null else result.brew != null)
        // Rows from before providers existed carry no id; their host still says who answered.
        val provider = AiProviders.byId(providerId.ifBlank { null })
            ?: AiProviders.forHost(host)?.takeIf { providerId.isBlank() }
            ?: AiProviders.byId(AiProviders.COMMANDCODE).takeIf { host == SAMPLE_HOST }
        return StoredAnalysis(
            id = id,
            target = target,
            createdAt = createdAt,
            model = model,
            modelLabel = ModelCatalog.label(provider?.id, model),
            host = host,
            brew = result?.brew,
            trend = result?.trend,
            grindMove = result?.grindMove,
            valueMove = result?.valueMove,
            localWarnings = result?.localWarnings.orEmpty(),
            rawText = rawText,
            parsed = parsed,
            flags = flags,
            usage = UsageLine(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                costMicroUsd = costMicroUsd,
                latencyMs = latencyMs,
            ),
            inputHash = inputHash,
            brewCount = brewCount,
            newestBrewAt = newestBrewAt,
            promptVersion = promptVersion,
            providerId = providerId,
            providerName = provider?.name?.takeIf { host != SAMPLE_HOST }.orEmpty(),
            photoCount = photoCount,
            origin = origin,
            brewUids = BrewRefs.stored(result?.brewUids, madeHere = origin == ORIGIN),
        )
    }

    private fun blockedPreview(error: AiError, brewCount: Int, ratedCount: Int) = AnalysisPreview(
        flags = emptyList(),
        contextFlags = emptyList(),
        inputHash = "",
        brewCount = brewCount,
        ratedCount = ratedCount,
        newestBrewAt = 0L,
        approxPromptTokens = 0,
        expectedCompletionTokens = 0,
        blocker = error,
    )

    private fun encodeFlags(flags: List<DataFlag>): String =
        AiJson.stored.encodeToString(ListSerializer(DataFlag.serializer()), flags)

    private fun kindOf(target: AnalysisTarget): String = when (target) {
        is AnalysisTarget.Brew -> AnalysisEntity.KIND_BREW
        AnalysisTarget.Trend -> AnalysisEntity.KIND_TREND
    }

    companion object {
        const val SAMPLE_HOST = "sample.local"

        /** The model the debug samples say they came from (Command Code's default). */
        const val SAMPLE_MODEL = "z-ai/glm-5.3-flash"

        /** What this app writes in analyses.origin. */
        const val ORIGIN = "android"
        const val KEEP_PER_TARGET = 5
    }
}
