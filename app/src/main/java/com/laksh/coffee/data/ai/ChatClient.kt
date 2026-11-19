package com.laksh.coffee.data.ai

import com.laksh.coffee.domain.ai.AiProvider
import com.laksh.coffee.domain.ai.AiResult
import com.laksh.coffee.domain.ai.AuthStyle
import com.laksh.coffee.domain.ai.Price
import com.laksh.coffee.domain.ai.ProviderApi

/** Where a request goes and how it authenticates. Built from the chosen provider; never holds the key. */
data class Endpoint(
    val providerId: String,
    val baseUrl: String,
    val api: ProviderApi,
    val auth: AuthStyle,
    val extraHeaders: Map<String, String> = emptyMap(),
    val modelsPath: String? = null,
    /** The model list answers without a key, so none is sent with it. */
    val modelsPublic: Boolean = false,
) {
    companion object {
        /** [baseUrl] is the provider's own for a preset and the user's saved address for custom. */
        fun of(provider: AiProvider, baseUrl: String): Endpoint = Endpoint(
            providerId = provider.id,
            baseUrl = baseUrl,
            api = provider.api,
            auth = provider.auth,
            extraHeaders = provider.extraHeaders,
            modelsPath = provider.modelsPath,
            modelsPublic = provider.modelsPublic,
        )
    }
}

/** One attached photo: JPEG bytes as Base64, with no data: prefix. [uid] is the photo row's, "" in tests. */
class AiImage(val label: String, val mediaType: String = "image/jpeg", val base64: String, val uid: String = "")

class AiRequest(
    val model: String,
    val system: String,
    val user: String,
    val images: List<AiImage> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int,
)

data class RemoteModel(
    val id: String,
    val name: String,
    val ownedBy: String? = null,
    val contextLength: Int? = null,
    /** Live price where the list carries one (OpenRouter), else the catalog's. */
    val price: Price? = null,
    /** Whether the model takes images, when the list or the catalog says; null = unknown. */
    val vision: Boolean? = null,
    /** One of the catalog's suggested models for this provider. */
    val suggested: Boolean = false,
)

data class ChatResult(
    val content: String,
    val finishReason: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val servedModel: String?,
    val latencyMs: Long,
)

data class TestResult(val servedModel: String?, val latencyMs: Long, val reply: String)

/** One wire format. Every failure comes back as an AiError; nothing is ever logged. */
interface ChatClient {
    /** One analysis request. The caller supplies the whole prompt. */
    suspend fun complete(e: Endpoint, apiKey: String, r: AiRequest, readTimeoutMs: Int = 90_000): AiResult<ChatResult>

    /**
     * A real 16-token completion, because only a completion proves the key, the plan and the
     * model together. Any HTTP 200 in the right format counts, even an empty or cut-off reply:
     * reasoning models can spend all 16 tokens thinking.
     */
    suspend fun testConnection(e: Endpoint, apiKey: String, model: String): AiResult<TestResult>

    /** The provider's model list. Only called when the user taps Browse. */
    suspend fun listModels(e: Endpoint, apiKey: String?): AiResult<List<RemoteModel>>
}

/** Picks the client for a provider's wire format. */
class ChatClients(transport: Transport) {
    private val openAi = OpenAiCompatClient(transport)
    private val anthropic = AnthropicClient(transport)

    fun forApi(api: ProviderApi): ChatClient = when (api) {
        ProviderApi.OpenAiChat -> openAi
        ProviderApi.AnthropicMessages -> anthropic
    }
}
