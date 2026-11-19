package com.laksh.coffee.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.data.ai.AiSettings
import com.laksh.coffee.data.ai.AiSettingsStore
import com.laksh.coffee.data.ai.AnalysisPreview
import com.laksh.coffee.data.ai.AnalysisRepository
import com.laksh.coffee.data.ai.AnalysisTarget
import com.laksh.coffee.data.ai.StaleReason
import com.laksh.coffee.data.ai.StoredAnalysis
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.AnalysisPrompt
import com.laksh.coffee.domain.ai.BrewRefs
import com.laksh.coffee.domain.ai.FlagSeverity
import com.laksh.coffee.domain.ai.ModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TrendAnalysisUi(
    val loading: Boolean = true,
    val configured: Boolean = false,
    val host: String = "",
    val modelLabel: String = "",
    val providerName: String = "",
    /** Brews a read sends, capped at the prompt's limit. */
    val brewCount: Int = 0,
    /** Every brew in the log, which can be more than [brewCount]. */
    val totalLogged: Int = 0,
    val ratedCount: Int = 0,
    /** Brews with at least one field the analysis will ignore. */
    val flaggedBrews: Int = 0,
    val approxTokens: Int = 0,
    val stored: StoredAnalysis? = null,
    val newBrewsSince: Int = 0,
    /** Why the stored read may no longer fit: the log was edited, the prompt is older, or it came from another device. */
    val staleReason: StaleReason? = null,
    val runningSinceMs: Long? = null,
    val error: AiError? = null,
    val estimateLabel: String? = null,
    val blocker: AiError? = null,
    /** Brew id to "14 SEP", for the tappable date chips under each pattern. */
    val brewDates: Map<Long, String> = emptyMap(),
    /**
     * The stored read's brew numbers to this phone's brew ids (BrewRefs.rowIds). Null when the
     * read was stored without uids: its numbers are then brew ids already.
     */
    val brewRowIds: Map<Long, Long>? = null,
    /** The host the saved key belongs to ("" when none), so the no-key copy can name it. */
    val keyHost: String = "",
) {
    val running: Boolean get() = runningSinceMs != null
    val stale: Boolean get() = staleReason != null
}

/** The log and everything derived from it once per log change, not once per emission. */
private data class TrendLogSnapshot(
    val logs: List<BrewLogEntity> = emptyList(),
    val preview: AnalysisPreview? = null,
    val brewDates: Map<Long, String> = emptyMap(),
    val idByUid: Map<String, Long> = emptyMap(),
)

private val CHIP_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).withZone(ZoneId.systemDefault())

/**
 * Whole-log pattern state for Insights. Like the brew card it never starts a request on its
 * own, and the run lives in the app-scoped repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrendAnalysisViewModel(
    private val analysis: AnalysisRepository,
    ai: AiSettingsStore,
    coffee: CoffeeRepository,
) : ViewModel() {

    private val target = AnalysisTarget.Trend

    private val snapshot: Flow<TrendLogSnapshot> = coffee.allLogs
        .mapLatest { logs ->
            TrendLogSnapshot(
                logs = logs,
                preview = previewOrNull(),
                brewDates = logs.associate { it.id to CHIP_DATE.format(Instant.ofEpochMilli(it.startedAt)).uppercase() },
                idByUid = logs.associate { it.uid to it.id },
            )
        }
        .catch { emit(TrendLogSnapshot()) }

    val state: StateFlow<TrendAnalysisUi> = combine(
        ai.settings.catch { emit(AiSettings()) },
        analysis.latest(target).catch { emit(null) },
        analysis.running,
        analysis.failures,
        snapshot,
    ) { settings, stored, running, failures, snap ->
        build(settings, stored, running[target], failures[target], snap)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendAnalysisUi())

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

    // An empty or odd log must never crash Insights; the card just shows less.
    private suspend fun previewOrNull(): AnalysisPreview? = try {
        analysis.preview(target)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun build(
        settings: AiSettings,
        stored: StoredAnalysis?,
        runningSinceMs: Long?,
        error: AiError?,
        snap: TrendLogSnapshot,
    ): TrendAnalysisUi {
        val p = snap.preview
        val usable = p?.takeIf { it.blocker == null }
        val capped = snap.logs.take(AnalysisPrompt.TREND_MAX_BREWS)
        val blocker = p?.blocker
            ?: if (snap.logs.isEmpty()) AiError.NotEnoughData(AnalysisPrompt.MIN_RATED_FOR_TREND, 0) else null
        // A blocked preview carries zero counts, so the rated count comes from the error or the log.
        val rated = when {
            blocker is AiError.NotEnoughData -> blocker.have
            usable != null -> usable.ratedCount
            else -> capped.count { it.overall > 0 }
        }
        return TrendAnalysisUi(
            loading = false,
            configured = settings.ready,
            host = settings.host,
            modelLabel = settings.modelLabel,
            providerName = settings.providerName,
            brewCount = usable?.brewCount ?: capped.size,
            totalLogged = snap.logs.size,
            ratedCount = rated,
            flaggedBrews = p?.flags.orEmpty()
                .filter { it.severity == FlagSeverity.Distrust }
                .map { it.brewId }
                .distinct()
                .size,
            approxTokens = usable?.approxPromptTokens ?: 0,
            stored = stored,
            newBrewsSince = stored?.let { s -> snap.logs.count { it.startedAt > s.newestBrewAt } } ?: 0,
            staleReason = stored?.staleReason(usable?.inputHash),
            runningSinceMs = runningSinceMs,
            error = error,
            estimateLabel = usable?.let {
                ModelCatalog.estimateMicroUsd(
                    providerId = settings.providerId,
                    modelId = settings.model,
                    promptTokens = it.approxPromptTokens,
                    expectedCompletionTokens = it.expectedCompletionTokens,
                )
            }?.let { "≈" + ModelCatalog.formatUsd(it) },
            blocker = blocker,
            brewDates = snap.brewDates,
            brewRowIds = BrewRefs.rowIds(stored?.brewUids, snap.idByUid::get),
            keyHost = settings.keyHost,
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TrendAnalysisViewModel(this.app.analysis, this.app.aiSettings, this.app.repository)
            }
        }
    }
}
