package com.laksh.coffee.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.core.ratio
import com.laksh.coffee.core.unitWord
import com.laksh.coffee.data.ai.AiSettings
import com.laksh.coffee.data.ai.AiSettingsStore
import com.laksh.coffee.data.ai.AnalysisPreview
import com.laksh.coffee.data.ai.AnalysisRepository
import com.laksh.coffee.data.ai.AnalysisTarget
import com.laksh.coffee.data.ai.StaleReason
import com.laksh.coffee.data.ai.StoredAnalysis
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.photos.PhotoPick
import com.laksh.coffee.data.photos.PhotoRepository
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.domain.BrewReport
import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.DataFlag
import com.laksh.coffee.domain.ai.GrindValueMove
import com.laksh.coffee.domain.ai.ModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class BrewAnalysisUi(
    val loading: Boolean = true,
    val configured: Boolean = false,
    val host: String = "",
    val modelLabel: String = "",
    /** The chosen provider's name, "" when none is chosen. */
    val providerName: String = "",
    /** Local data-quality flags for this brew. Free, and shown whether or not a key is set. */
    val flags: List<DataFlag> = emptyList(),
    /** How many earlier brews go along with this one as context. */
    val contextBrews: Int = 0,
    val approxTokens: Int = 0,
    val stored: StoredAnalysis? = null,
    /** Why the stored analysis may no longer fit: the brew was edited, the prompt is older, or it came from another device. */
    val staleReason: StaleReason? = null,
    /** Photos of this brew that would go with a read (ticked, and only for a model that sees images). */
    val photoCount: Int = 0,
    /** What the card says about this brew's photos before a read; null when it has no bed or cup photo. */
    val photoLine: PhotoLine? = null,
    val runningSinceMs: Long? = null,
    val error: AiError? = null,
    val estimateLabel: String? = null,
    val blocker: AiError? = null,
    val quickRead: List<String> = emptyList(),
    /** Upper-cased grinder model for the change label, e.g. "K-ULTRA". */
    val grinderShort: String? = null,
    /** The host the saved key belongs to ("" when none), so the no-key copy can name it. */
    val keyHost: String = "",
    /** What this brew did, for the KEEP numbers when the answer is "change nothing". */
    val keep: KeepNumbers? = null,
) {
    val running: Boolean get() = runningSinceMs != null
    val stale: Boolean get() = staleReason != null
}

/** Why none of a brew's photos would go with a read. */
enum class PhotoHold { ModelBlind, SwitchedOff, NoneTicked }

/** The photos a read would send, in order, or the reason none go. Exactly one of the two is set. */
data class PhotoLine(val sending: List<PhotoEntity>, val hold: PhotoHold?)

/** A brew's bed and cup photos, and which of them an analysis would be offered. */
private data class PhotoFacts(val onBrew: Int = 0, val chosen: List<PhotoEntity> = emptyList())

/**
 * [onBrew] is how many bed and cup photos the brew has, [chosen] the ticked ones that are on the
 * phone (newest three). The reasons are checked in the order a person could act on them.
 */
internal fun photoLine(
    onBrew: Int,
    chosen: List<PhotoEntity>,
    sendPhotos: Boolean,
    modelSeesImages: Boolean,
): PhotoLine? = when {
    onBrew <= 0 -> null
    !modelSeesImages -> PhotoLine(emptyList(), PhotoHold.ModelBlind)
    !sendPhotos -> PhotoLine(emptyList(), PhotoHold.SwitchedOff)
    chosen.isEmpty() -> PhotoLine(emptyList(), PhotoHold.NoneTicked)
    else -> PhotoLine(chosen, null)
}

/** The settings to keep when the verdict is "change nothing", as label-above-value numbers. */
data class KeepNumbers(
    val grind: String?,
    val grindUnit: String?,
    val tempC: Int?,
    val ratio: String?,
)

/** What the brew log itself contributes: the offline read, the grinder's short name, the numbers. */
private data class BrewDetails(
    val quickRead: List<String> = emptyList(),
    val grinderShort: String? = null,
    val keep: KeepNumbers? = null,
)

/** The repository's live state for this one brew. */
private data class LiveRun(
    val settings: AiSettings,
    val stored: StoredAnalysis?,
    val runningSinceMs: Long?,
    val error: AiError?,
)

/**
 * Per-brew analysis state for Brew detail. It never starts a request on its own: only
 * [analyse], from a tap, does. Runs live in the app-scoped repository, so leaving the screen
 * and coming back shows the same run, and the result lands even if the screen is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrewAnalysisViewModel(
    private val analysis: AnalysisRepository,
    ai: AiSettingsStore,
    private val coffee: CoffeeRepository,
    photos: PhotoRepository,
    logId: Long,
) : ViewModel() {

    private val target = AnalysisTarget.Brew(logId)

    // Re-read on every log change, so an edit in Review refreshes the log check and staleness,
    // when photos start or stop going along (the switch, or a model that can or can't see), and
    // when a photo of this brew is added, removed, ticked or renamed.
    private val preview: Flow<AnalysisPreview?> = combine(
        coffee.allLogs,
        ai.settings.map { it.sendPhotos && it.modelSeesImages }.catch { emit(false) }.distinctUntilChanged(),
        photos.observeForBrew(logId).catch { emit(emptyList()) },
    ) { _, _, _ -> Unit }
        .mapLatest { previewOrNull() }
        .catch { emit(null) }
        .distinctUntilChanged { a, b ->
            a?.inputHash == b?.inputHash && a?.flags == b?.flags && a?.blocker == b?.blocker && a?.photoCount == b?.photoCount
        }

    private val details: Flow<BrewDetails> = coffee.observeLog(logId)
        .mapLatest { log ->
            if (log == null) return@mapLatest BrewDetails()
            try {
                val recipe = coffee.recipeById(log.recipeId)
                val grinder = log.grinderId?.let { coffee.grinder(it) }
                BrewDetails(
                    quickRead = BrewReport.quickRead(log, recipe),
                    grinderShort = grinder?.model?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
                        ?: log.grinderLabel.trim().substringAfterLast(' ').takeIf { it.isNotEmpty() }?.uppercase(),
                    keep = KeepNumbers(
                        grind = log.grindClicks?.takeIf { it > 0 }?.toString()
                            ?: log.grindValue?.takeIf { it > 0 }?.let { GrindValueMove.format(it) }
                            ?: log.grindSetting.trim().ifEmpty { null },
                        grindUnit = log.grindClicks?.takeIf { it > 0 }?.let { unitWord(it, "click") },
                        tempC = log.tempC.takeIf { it > 0 },
                        ratio = if (log.doseG > 0 && log.waterG > 0) ratio(log.doseG, log.waterG) else null,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BrewDetails()
            }
        }
        .catch { emit(BrewDetails()) }

    private val photoFacts: Flow<PhotoFacts> = combine(
        photos.observeForBrew(logId),
        photos.observeForAnalysis(logId),
    ) { all, chosen -> PhotoFacts(onBrew = all.count { it.kind in PhotoPick.KINDS }, chosen = chosen) }
        // The card says less about photos; it must not fail because of them.
        .catch { emit(PhotoFacts()) }

    private val live: Flow<LiveRun> = combine(
        ai.settings.catch { emit(AiSettings()) },
        analysis.latest(target).catch { emit(null) },
        analysis.running,
        analysis.failures,
    ) { settings, stored, running, failures ->
        LiveRun(settings, stored, running[target], failures[target])
    }

    val state: StateFlow<BrewAnalysisUi> = combine(live, preview, details, photoFacts) { l, p, d, f ->
        build(l, p, d, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrewAnalysisUi())

    fun analyse() {
        analysis.clearFailure(target)
        analysis.start(target)
    }

    fun cancel() {
        analysis.cancel(target)
    }

    fun dismissError() {
        analysis.clearFailure(target)
    }

    // A broken preview must never take Brew detail down with it; the card just shows less.
    private suspend fun previewOrNull(): AnalysisPreview? = try {
        analysis.preview(target)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun build(l: LiveRun, p: AnalysisPreview?, d: BrewDetails, f: PhotoFacts): BrewAnalysisUi {
        val s = l.settings
        val stored = l.stored
        val usable = p?.takeIf { it.blocker == null }
        return BrewAnalysisUi(
            loading = false,
            configured = s.ready,
            host = s.host,
            modelLabel = s.modelLabel,
            providerName = s.providerName,
            flags = p?.flags.orEmpty(),
            contextBrews = ((usable?.brewCount ?: 1) - 1).coerceAtLeast(0),
            approxTokens = usable?.approxPromptTokens ?: 0,
            stored = stored,
            // Only a real preview can say the brew moved on; a failed or blocked one says nothing.
            staleReason = stored?.staleReason(usable?.inputHash),
            photoCount = usable?.photoCount ?: 0,
            photoLine = photoLine(f.onBrew, f.chosen, s.sendPhotos, s.modelSeesImages),
            runningSinceMs = l.runningSinceMs,
            error = l.error,
            estimateLabel = usable?.let {
                ModelCatalog.estimateMicroUsd(
                    providerId = s.providerId,
                    modelId = s.model,
                    promptTokens = it.approxPromptTokens,
                    expectedCompletionTokens = it.expectedCompletionTokens,
                    images = it.photoCount,
                )
            }?.let { "≈" + ModelCatalog.formatUsd(it) },
            blocker = p?.blocker,
            quickRead = d.quickRead,
            grinderShort = d.grinderShort,
            keyHost = s.keyHost,
            keep = d.keep,
        )
    }

    companion object {
        fun factory(logId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BrewAnalysisViewModel(this.app.analysis, this.app.aiSettings, this.app.repository, this.app.photos, logId)
            }
        }
    }
}
