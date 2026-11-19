package com.laksh.coffee.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.core.dayLabel
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.core.timeLabel
import com.laksh.coffee.data.ai.AiSettings
import com.laksh.coffee.data.ai.AiSettingsStore
import com.laksh.coffee.data.ai.ChatClients
import com.laksh.coffee.data.ai.Endpoint
import com.laksh.coffee.data.ai.KeyCheck
import com.laksh.coffee.data.ai.KeyVaultException
import com.laksh.coffee.data.ai.RemoteModel
import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.AiProvider
import com.laksh.coffee.domain.ai.AiProviders
import com.laksh.coffee.domain.ai.AiResult
import com.laksh.coffee.domain.ai.BaseUrl
import com.laksh.coffee.domain.ai.BaseUrlCheck
import com.laksh.coffee.ui.components.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** The full model list. It loads only when you tap Browse, never on its own. */
sealed interface ModelsUi {
    data object Idle : ModelsUi
    data object Loading : ModelsUi
    data class Loaded(val models: List<RemoteModel>) : ModelsUi
    data class Failed(val error: AiError) : ModelsUi
}

sealed interface TestUi {
    data object Idle : TestUi
    data class Running(val sinceMs: Long) : TestUi
    data class Ok(val modelLabel: String, val seconds: String) : TestUi
    data class Failed(val error: AiError) : TestUi
}

/** The one line at the top of the settings card that says whether analysis will work. */
data class SettingsHeadline(
    val status: Status,
    val lead: String,
    val detail: String?,
    val live: Boolean = false,
)

/** One row of the provider list: the name, and whether a key is saved for it, as a word and a colour. */
data class ProviderRow(
    val id: String,
    val name: String,
    val status: Status,
    val word: String,
    val selected: Boolean,
)

data class AiSettingsUi(
    val settings: AiSettings = AiSettings(),
    /** The chosen provider's catalog entry; null until one is chosen. */
    val provider: AiProvider? = null,
    val providers: List<ProviderRow> = emptyList(),
    /** Catalog providers a phone can't reach (Ollama, LM Studio), for the one-line note. */
    val leftOut: List<String> = emptyList(),
    val baseUrlDraft: String = "",
    /** True once the base URL box has been typed in, so saved settings stop overwriting it. */
    val baseUrlEdited: Boolean = false,
    val baseUrlError: String? = null,
    val keyDraft: String = "",
    val keyError: String? = null,
    val keyVisible: Boolean = false,
    val revealedKey: String? = null,
    val keyUnreadable: Boolean = false,
    val replacingKey: Boolean = false,
    val customModelDraft: String = "",
    val customOpen: Boolean = false,
    val modelQuery: String = "",
    val models: ModelsUi = ModelsUi.Idle,
    val test: TestUi = TestUi.Idle,
) {
    /** The paste field shows whenever there is no usable key for the address set now. */
    val showKeyField: Boolean
        get() = !settings.hasKey || replacingKey || keyUnreadable || settings.keyForOtherHost

    /**
     * The host a key pasted now would be saved for: the custom address being typed when it is a
     * valid one (Save and test commits it first), else the provider's address. Shown under the
     * key field.
     */
    val keyTargetHost: String
        get() = if (settings.isCustom && baseUrlEdited) {
            (BaseUrl.check(baseUrlDraft) as? BaseUrlCheck.Valid)?.host ?: settings.host
        } else {
            settings.host
        }

    /** "Browse all" is offered where the provider is known to answer, and always for custom. */
    val canBrowse: Boolean get() = provider != null && (provider.modelsListable || provider.isCustom)

    val headline: SettingsHeadline
        get() = when {
            !settings.providerChosen ->
                SettingsHeadline(Status.Neutral, "Choose a provider", "nothing is sent until you add a key")
            keyUnreadable -> SettingsHeadline(Status.Fault, "Key can't be unlocked", "paste it again")
            test is TestUi.Running -> SettingsHeadline(
                status = Status.Live,
                lead = "Testing",
                detail = settings.modelLabel,
                live = true,
            )
            test is TestUi.Failed -> SettingsHeadline(Status.Fault, test.error.title, null)
            test is TestUi.Ok -> SettingsHeadline(
                status = Status.Good,
                lead = "Connected",
                detail = "${test.modelLabel} answered in ${test.seconds} s",
            )
            settings.isCustom && settings.baseUrl.isBlank() ->
                SettingsHeadline(Status.Neutral, "Needs an address", "enter your service's https address")
            // The saved key belongs to another server, so it stays unsent until one is pasted here.
            settings.keyForOtherHost -> SettingsHeadline(
                status = Status.Attention,
                lead = "Key saved for ${settings.keyHost}",
                detail = "paste a key for ${settings.host}",
            )
            !settings.hasKey -> SettingsHeadline(Status.Neutral, "Needs an API key", "nothing is sent yet")
            settings.model.isBlank() -> SettingsHeadline(Status.Neutral, "Pick a model", "the key is saved")
            settings.testedOk -> SettingsHeadline(Status.Good, "Ready", via(settings))
            else -> SettingsHeadline(Status.Neutral, "Key saved", "not tested yet · " + via(settings))
        }

    /** "Tested 08:14" under the headline once a test has passed for exactly this setup. */
    val testedNote: String? get() = if (settings.testedOk) testedLabel(settings.lastTestOkAt) else null

    val filteredModels: List<RemoteModel>
        get() {
            val all = (models as? ModelsUi.Loaded)?.models ?: return emptyList()
            val q = modelQuery.trim()
            if (q.isEmpty()) return all
            return all.filter { it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) }
        }

    // The generated toString would print the pasted and the revealed key. Nothing logs this
    // state today; this keeps a future debug print or crash breadcrumb from leaking either.
    override fun toString(): String =
        "AiSettingsUi(settings=$settings, baseUrlDraft=$baseUrlDraft, baseUrlEdited=$baseUrlEdited, " +
            "keyDraft=${masked(keyDraft)}, revealedKey=${masked(revealedKey)}, keyVisible=$keyVisible, " +
            "replacingKey=$replacingKey, models=$models, test=$test)"
}

private fun masked(secret: String?): String = if (secret.isNullOrEmpty()) "none" else "•••"

/** "Claude Haiku 5.5 via Anthropic (Claude)". */
private fun via(s: AiSettings): String = "${s.modelLabel} via ${s.providerName}"

private fun testedLabel(at: Long?): String {
    if (at == null) return "Tested"
    return when (val day = dayLabel(at)) {
        "Today" -> "Tested ${timeLabel(at)}"
        "Yesterday" -> "Tested yesterday"
        else -> "Tested $day"
    }
}

/** The provider list with each provider's key status. Pure, so the wording can be tested. */
internal fun providerRows(settings: AiSettings, providers: List<AiProvider>): List<ProviderRow> =
    providers.map { p ->
        val badge = settings.keys[p.id]
        // Custom's address is the user's own; a preset's is the catalog's.
        val host = if (p.isCustom) BaseUrl.hostOf(settings.customBaseUrl) else p.host
        val (status, word) = when {
            badge == null -> Status.Neutral to "No key"
            badge.host.isNotEmpty() && badge.host == host -> Status.Good to "Key saved"
            else -> Status.Attention to "Key for another address"
        }
        ProviderRow(id = p.id, name = p.name, status = status, word = word, selected = p.id == settings.providerId)
    }

/**
 * State for the AI settings card. The network is touched only by [testConnection] (and
 * [saveKeyAndTest], which ends in it) and [loadModels], and only from a tap. The pasted key
 * lives in memory until it is saved; the saved key is decrypted only to test, browse or reveal.
 * Nothing here logs, and the key never goes into a URL or an error message.
 */
class AiSettingsViewModel(
    private val store: AiSettingsStore,
    private val clients: ChatClients,
) : ViewModel() {

    private val draft = MutableStateFlow(AiSettingsUi())

    /**
     * The text in each box, as Compose state the fields read directly. A field has to get its own
     * text back at once; [state] is combined on a background thread and arrives a moment later,
     * and a field fed from it drops or reorders characters when someone types fast.
     */
    class Typed {
        var key by mutableStateOf("")
            internal set
        var customModel by mutableStateOf("")
            internal set
        var modelQuery by mutableStateOf("")
            internal set

        /** Null until the address box is typed in: it then shows the saved address. */
        var baseUrl by mutableStateOf<String?>(null)
            internal set
    }

    val typed = Typed()

    /** Every change to the draft goes through here, so [typed] can never fall out of step with it. */
    private fun change(block: (AiSettingsUi) -> AiSettingsUi) {
        val now = draft.updateAndGet(block)
        typed.key = now.keyDraft
        typed.customModel = now.customModelDraft
        typed.modelQuery = now.modelQuery
        typed.baseUrl = now.baseUrlDraft.takeIf { now.baseUrlEdited }
    }
    private var revealJob: Job? = null
    private var testJob: Job? = null
    private var modelsJob: Job? = null

    val state: StateFlow<AiSettingsUi> = combine(
        store.settings.catch { emit(AiSettings()) },
        draft,
    ) { settings, d ->
        d.copy(
            settings = settings,
            provider = AiProviders.byId(settings.providerId),
            providers = providerRows(settings, AiProviders.all),
            leftOut = AiProviders.leftOut,
            // Until the user types, the box follows what is saved (normalised).
            baseUrlDraft = if (d.baseUrlEdited) d.baseUrlDraft else settings.customBaseUrl,
        )
    }
        // The first emission parses providers.json; the settings card must not wait for it on the main thread.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUi())

    // ------------------------------------------------------------------ provider

    /** Switches provider. Each has its own key, model and test result, so nothing is carried over. */
    fun selectProvider(id: String) {
        revealJob?.cancel()
        testJob?.cancel()
        modelsJob?.cancel()
        launchSafely {
            store.setProvider(id)
            change {
                it.copy(
                    baseUrlEdited = false,
                    baseUrlError = null,
                    keyDraft = "",
                    keyError = null,
                    keyVisible = false,
                    revealedKey = null,
                    keyUnreadable = false,
                    replacingKey = false,
                    customModelDraft = "",
                    customOpen = false,
                    modelQuery = "",
                    models = ModelsUi.Idle,
                    test = TestUi.Idle,
                )
            }
        }
    }

    // ------------------------------------------------------------------ base URL

    fun setBaseUrlDraft(v: String) {
        change { it.copy(baseUrlDraft = v, baseUrlEdited = true, baseUrlError = null) }
    }

    /**
     * Saves the custom address. Called only from IME Done or the Save tap, never on focus loss,
     * so a key pasted into this box by mistake is rejected before anything is written.
     */
    fun commitBaseUrl() {
        val d = draft.value
        if (!d.baseUrlEdited) return
        launchSafely {
            when (val check = store.setBaseUrl(d.baseUrlDraft)) {
                is BaseUrlCheck.Valid -> change {
                    it.copy(baseUrlEdited = false, baseUrlError = null, test = TestUi.Idle, models = ModelsUi.Idle)
                }
                is BaseUrlCheck.Invalid -> change { it.copy(baseUrlError = check.error.title) }
            }
        }
    }

    // ----------------------------------------------------------------------- key

    fun setKeyDraft(v: String) {
        change { it.copy(keyDraft = v, keyError = null) }
    }

    fun toggleKeyVisible() {
        change { it.copy(keyVisible = !it.keyVisible) }
    }

    /** Clipboard text arrives only from the Paste tap; nothing reads the clipboard on its own. */
    fun pasteKey(clip: String?) {
        val v = clip?.trim() ?: return
        change { it.copy(keyDraft = v, keyError = null) }
    }

    fun saveKeyAndTest() {
        val ui = state.value
        val raw = draft.value.keyDraft
        if (raw.isBlank()) return
        // The provider and host the screen says the key is for. The store refuses the save if
        // that provider's address has moved elsewhere by the time it writes.
        val providerId = ui.settings.providerId
        val host = ui.keyTargetHost
        launchSafely(onError = { change { it.copy(keyError = "Couldn't save the key on this phone. Try again.") } }) {
            // A custom address typed but not yet saved is saved first. Otherwise the key would be
            // bound to the old host, and the test that follows would send it there.
            val d = draft.value
            if (ui.settings.isCustom && d.baseUrlEdited) {
                when (val check = store.setBaseUrl(d.baseUrlDraft)) {
                    is BaseUrlCheck.Invalid -> {
                        change { it.copy(baseUrlError = check.error.title, keyError = "Save the address first") }
                        return@launchSafely
                    }
                    is BaseUrlCheck.Valid -> change {
                        it.copy(baseUrlEdited = false, baseUrlError = null, test = TestUi.Idle, models = ModelsUi.Idle)
                    }
                }
            }
            when (val r = store.saveKey(raw, providerId = providerId, forHost = host)) {
                is KeyCheck.Rejected -> change { it.copy(keyError = r.message) }
                is KeyCheck.Saved -> {
                    change {
                        it.copy(
                            keyDraft = "",
                            keyVisible = false,
                            keyError = null,
                            replacingKey = false,
                            keyUnreadable = false,
                            revealedKey = null,
                        )
                    }
                    testConnection()
                }
            }
        }
    }

    fun startReplace() {
        revealJob?.cancel()
        change { it.copy(replacingKey = true, revealedKey = null) }
    }

    fun cancelReplace() {
        change { it.copy(replacingKey = false, keyDraft = "", keyVisible = false, keyError = null) }
    }

    /** Forgets the chosen provider's key. Keys saved for other providers stay. */
    fun removeKey() {
        revealJob?.cancel()
        testJob?.cancel()
        launchSafely {
            store.clearKey(store.snapshot().providerId)
            change {
                it.copy(
                    revealedKey = null,
                    test = TestUi.Idle,
                    keyUnreadable = false,
                    replacingKey = false,
                    keyDraft = "",
                    keyError = null,
                )
            }
        }
    }

    /** Shows the saved key for 30 s, then hides it again on its own. */
    fun toggleRevealSaved() {
        if (draft.value.revealedKey != null) {
            hideKey()
            return
        }
        revealJob?.cancel()
        revealJob = launchSafely {
            val s = store.snapshot()
            val key: String? = try {
                store.readKey(providerId = s.providerId, forHost = s.host)
            } catch (e: KeyVaultException) {
                change { it.copy(keyUnreadable = true) }
                null
            }
            if (key == null) return@launchSafely
            change { it.copy(revealedKey = key) }
            delay(REVEAL_MS)
            change { it.copy(revealedKey = null) }
        }
    }

    /** Called when the app leaves the screen, so a key never lands in the Recents thumbnail. */
    fun hideKey() {
        revealJob?.cancel()
        revealJob = null
        change { it.copy(revealedKey = null, keyVisible = false) }
    }

    // --------------------------------------------------------------------- model

    /**
     * [seesImages] is what a browsed list said about the model, when it said anything. It is
     * only kept for models the catalog doesn't know; the catalog's word always wins.
     */
    fun selectModel(id: String, seesImages: Boolean? = null) {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return
        launchSafely {
            store.setModel(trimmed)
            val s = store.snapshot()
            if (seesImages != null && !s.modelKnown) store.setVisionOverride(s.providerId, seesImages)
        }
        change { it.copy(test = TestUi.Idle, customOpen = false) }
    }

    fun openCustom() {
        val s = state.value.settings
        change {
            it.copy(
                customOpen = !it.customOpen,
                customModelDraft = if (!s.modelKnown) s.model else it.customModelDraft,
            )
        }
    }

    fun setCustomModelDraft(v: String) {
        change { it.copy(customModelDraft = v) }
    }

    fun commitCustomModel() {
        val id = draft.value.customModelDraft.trim()
        if (id.isNotEmpty()) selectModel(id)
    }

    fun setModelQuery(v: String) {
        change { it.copy(modelQuery = v) }
    }

    /** "This model can see images", for a model the catalog doesn't list. */
    fun setSeesImages(on: Boolean) {
        launchSafely { store.setVisionOverride(store.snapshot().providerId, on) }
    }

    fun setSendPhotos(on: Boolean) {
        launchSafely { store.setSendPhotos(on) }
    }

    /** Only ever called from the Browse tap. */
    fun loadModels() {
        modelsJob?.cancel()
        modelsJob = launchSafely(onError = { e -> change { it.copy(models = ModelsUi.Failed(unknown(e))) } }) {
            change { it.copy(models = ModelsUi.Loading) }
            val s = store.snapshot()
            val provider = AiProviders.byId(s.providerId)
            if (provider == null) {
                change { it.copy(models = ModelsUi.Failed(AiError.NotConfigured(missingKey = true, missingModel = false))) }
                return@launchSafely
            }
            // A public list gets no key. Elsewhere the key goes only to the host it was saved for.
            val key = if (s.hasKey && !provider.modelsPublic) readKeyOrNull(s.providerId, s.host) else null
            val result = clients.forApi(provider.api).listModels(Endpoint.of(provider, s.baseUrl), key)
            change {
                it.copy(
                    models = when (result) {
                        is AiResult.Ok -> ModelsUi.Loaded(result.value)
                        is AiResult.Err -> ModelsUi.Failed(result.error)
                    },
                )
            }
        }
    }

    // ---------------------------------------------------------------------- test

    fun testConnection() {
        if (testJob?.isActive == true) return
        testJob = launchSafely(onError = { e -> change { it.copy(test = TestUi.Failed(unknown(e))) } }) {
            // Fresh from the store, not the UI copy, so a key saved a moment ago is seen.
            val s = store.snapshot()
            val provider = AiProviders.byId(s.providerId)
            if (!s.ready || provider == null) {
                change { it.copy(test = TestUi.Idle) }
                return@launchSafely
            }
            val since = System.currentTimeMillis()
            change { it.copy(test = TestUi.Running(since)) }
            // Read for the provider and host of the snapshot the request uses, so they cannot disagree.
            val key = try {
                store.readKey(providerId = s.providerId, forHost = s.host)
            } catch (e: KeyVaultException) {
                change { it.copy(keyUnreadable = true, test = TestUi.Failed(AiError.KeyUnreadable)) }
                return@launchSafely
            }
            if (key == null) {
                val error = AiError.NotConfigured(missingKey = true, missingModel = false, host = s.host, keyHost = s.keyHost)
                change { it.copy(test = TestUi.Failed(error)) }
                return@launchSafely
            }
            val client = clients.forApi(provider.api)
            val outcome = when (val r = client.testConnection(Endpoint.of(provider, s.baseUrl), key, s.model)) {
                is AiResult.Ok -> {
                    store.markTested(s.providerId, s.signature, System.currentTimeMillis())
                    TestUi.Ok(s.modelLabel, oneDp(r.value.latencyMs / 1000.0))
                }
                is AiResult.Err -> TestUi.Failed(r.error)
            }
            change { it.copy(test = outcome) }
        }
    }

    override fun onCleared() {
        revealJob?.cancel()
        change { it.copy(revealedKey = null, keyDraft = "") }
        super.onCleared()
    }

    // ------------------------------------------------------------------- helpers

    /** A store or vault failure must never crash the settings screen. */
    private fun launchSafely(
        onError: (Exception) -> Unit = {},
        block: suspend CoroutineScope.() -> Unit,
    ): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    private suspend fun readKeyOrNull(providerId: String, forHost: String): String? = try {
        store.readKey(providerId, forHost)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // Only the class name, never the message, which could echo something sensitive.
    private fun unknown(e: Exception): AiError = AiError.Unknown(e::class.simpleName ?: "Exception")

    companion object {
        private const val REVEAL_MS = 30_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { AiSettingsViewModel(this.app.aiSettings, this.app.aiClients) }
        }
    }
}
