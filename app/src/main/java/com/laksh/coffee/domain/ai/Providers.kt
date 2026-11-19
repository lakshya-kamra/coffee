package com.laksh.coffee.domain.ai

import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogProvider

/** The two wire formats the app speaks. Ids match `api` in shared/catalog/providers.json. */
enum class ProviderApi(val id: String) {
    OpenAiChat("openai-chat"),
    AnthropicMessages("anthropic-messages");

    companion object {
        fun ofOrNull(id: String?): ProviderApi? = entries.firstOrNull { it.id == id }
    }
}

/** Which header carries the key. Ids match `auth` in providers.json. */
enum class AuthStyle(val id: String) {
    Bearer("bearer"),
    XApiKey("x-api-key");

    companion object {
        fun of(id: String?): AuthStyle = entries.firstOrNull { it.id == id } ?: Bearer
    }
}

data class AiModel(
    val id: String,
    val label: String,
    /** Null unless the catalog lists both prices. */
    val price: Price?,
    val vision: Boolean,
    val isDefault: Boolean,
    val why: String,
)

/** One "add your key" preset, built from shared/catalog/providers.json. */
data class AiProvider(
    val id: String,
    val name: String,
    /** Normalised https root; "" for custom, whose address is the user's own. */
    val baseUrl: String,
    val api: ProviderApi,
    val auth: AuthStyle,
    /** Already through [AiProviders.headerAllowed]. */
    val extraHeaders: Map<String, String>,
    val keyUrl: String,
    val keyHint: String,
    val modelsPath: String?,
    val modelsListable: Boolean,
    /** The model list answers without a key (OpenRouter, Command Code), so none is sent. */
    val modelsPublic: Boolean,
    val models: List<AiModel>,
    val pricesCheckedOn: String,
    val isCustom: Boolean,
) {
    val host: String get() = BaseUrl.hostOf(baseUrl)

    val defaultModel: AiModel? get() = models.firstOrNull { it.isDefault } ?: models.firstOrNull()

    fun model(id: String): AiModel? = models.firstOrNull { it.id == id }
}

/**
 * The providers a phone can use: every catalog entry with an https address that passes
 * [BaseUrl.check], plus "custom". Ollama and LM Studio (http://localhost on a computer) are
 * left out on Android; [leftOut] names them so the settings can say why.
 */
object AiProviders {
    const val CUSTOM = "custom"
    const val COMMANDCODE = "commandcode"
    const val OPENAI = "openai"

    private val built: Pair<List<AiProvider>, List<String>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        from(Catalog.providers)
    }

    /** Catalog order. */
    val all: List<AiProvider> get() = built.first

    /** Names of catalog providers on a plain-http address (a model server on a computer), which a phone can't use. */
    val leftOut: List<String> get() = built.second

    private val byId: Map<String, AiProvider> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { all.associateBy { it.id } }

    fun byId(id: String?): AiProvider? = id?.let { byId[it] }

    /** The preset whose address is on [host]; null for none, or when two presets share the host. */
    fun forHost(host: String): AiProvider? = forHost(host, all)

    fun forHost(host: String, list: List<AiProvider>): AiProvider? {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return null
        return list.filter { !it.isCustom && it.host == h }.singleOrNull()
    }

    /** The usable presets and the names of the ones left out. Pure, for tests. */
    fun from(catalog: List<CatalogProvider>): Pair<List<AiProvider>, List<String>> {
        val usable = mutableListOf<AiProvider>()
        val skipped = mutableListOf<String>()
        for (p in catalog) {
            val provider = providerFrom(p)
            when {
                provider != null -> usable += provider
                // Only the ones a computer serves on plain http are named; anything else is just absent.
                p.baseUrl.startsWith("http://", ignoreCase = true) -> skipped += p.name
            }
        }
        // A broken or missing catalog must still leave a way to add a key.
        if (usable.none { it.isCustom }) usable += FALLBACK_CUSTOM
        return usable.distinctBy { it.id } to skipped
    }

    private fun providerFrom(p: CatalogProvider): AiProvider? {
        val api = ProviderApi.ofOrNull(p.api) ?: return null
        val isCustom = p.id == CUSTOM
        val baseUrl = if (isCustom) {
            ""
        } else {
            (BaseUrl.check(p.baseUrl) as? BaseUrlCheck.Valid)?.normalized ?: return null
        }
        return AiProvider(
            id = p.id,
            name = p.name,
            baseUrl = baseUrl,
            api = api,
            auth = AuthStyle.of(p.auth),
            extraHeaders = p.extraHeaders.filter { (k, v) -> headerAllowed(k, v) },
            keyUrl = p.keyUrl.takeIf { it.startsWith("https://") }.orEmpty(),
            keyHint = p.keyHint,
            modelsPath = p.listModels.path.takeIf { MODELS_PATH.matches(it) },
            modelsListable = p.listModels.works,
            modelsPublic = p.listModels.note.trim().startsWith("public", ignoreCase = true),
            models = p.models.filter { it.id.isNotBlank() }.map { m ->
                AiModel(
                    id = m.id,
                    label = m.label.ifBlank { ModelCatalog.shortId(m.id) },
                    price = if (m.inputPerM != null && m.outputPerM != null) Price(m.inputPerM, m.outputPerM) else null,
                    vision = m.vision,
                    isDefault = m.default,
                    why = m.why,
                )
            },
            pricesCheckedOn = p.pricesCheckedOn,
            isCustom = isCustom,
        )
    }

    private val HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")
    private val MODELS_PATH = Regex("^/[A-Za-z0-9/_.-]{1,64}$")
    private val RESERVED = setOf("authorization", "x-api-key", "cookie", "host", "content-length", "content-type")

    /**
     * A catalog header is sent only when it cannot carry or replace a credential: a plain name,
     * not one of the reserved headers, and a short printable-ASCII value.
     */
    fun headerAllowed(name: String, value: String): Boolean =
        HEADER_NAME.matches(name) &&
            name.lowercase() !in RESERVED &&
            value.length <= 256 &&
            value.all { it.code in 0x20..0x7E }

    private val FALLBACK_CUSTOM = AiProvider(
        id = CUSTOM,
        name = "Custom (OpenAI-compatible)",
        baseUrl = "",
        api = ProviderApi.OpenAiChat,
        auth = AuthStyle.Bearer,
        extraHeaders = emptyMap(),
        keyUrl = "",
        keyHint = "The key your service gives you. It is sent only to the address you enter.",
        modelsPath = "/models",
        modelsListable = false,
        modelsPublic = false,
        models = emptyList(),
        pricesCheckedOn = "",
        isCustom = true,
    )
}
