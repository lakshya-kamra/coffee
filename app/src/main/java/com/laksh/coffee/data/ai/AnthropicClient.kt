package com.laksh.coffee.data.ai

import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.AiJson
import com.laksh.coffee.domain.ai.AiResult
import com.laksh.coffee.domain.ai.AuthStyle
import com.laksh.coffee.domain.ai.BaseUrl
import com.laksh.coffee.domain.ai.Redact
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Anthropic's Messages format (POST {base}/messages). The key travels only in the x-api-key
 * header, next to the catalog's anthropic-version.
 *
 * No sampling parameter is ever sent: providers.json records that the API version the catalog
 * pins (2026-10-21) answers temperature, top_p or top_k with HTTP 400, and current Claude models
 * reject them on older versions too. Thinking is left to the model's default.
 */
class AnthropicClient(private val transport: Transport) : ChatClient {

    override suspend fun complete(e: Endpoint, apiKey: String, r: AiRequest, readTimeoutMs: Int): AiResult<ChatResult> {
        val base = when (val c = Http.checkBase(e.baseUrl, apiKey)) {
            is AiResult.Err -> return c
            is AiResult.Ok -> c.value
        }
        val url = BaseUrl.messages(base.normalized)
        val reply = when (val x = post(e, apiKey, url, base.host, body(r), readTimeoutMs)) {
            is AiResult.Err -> return x
            is AiResult.Ok -> x.value
        }
        if (!reply.ok) {
            return AiResult.Err(Http.statusError(reply, base.host, Http.pathOf(url), model = r.model, apiKey = apiKey))
        }
        val d = when (val decoded = decode(reply, base.host, apiKey)) {
            is AiResult.Err -> return decoded
            is AiResult.Ok -> decoded.value
        }
        // Checked before the text: a refusal or a cut-off answer can still carry a few words.
        return when (d.stopReason) {
            "max_tokens" -> AiResult.Err(AiError.Truncated(d.outputTokens, null))
            "refusal" -> AiResult.Err(AiError.Filtered(base.host))
            else ->
                if (d.text.isBlank()) {
                    AiResult.Err(AiError.EmptyReply)
                } else {
                    AiResult.Ok(
                        ChatResult(
                            content = d.text,
                            finishReason = d.stopReason,
                            promptTokens = d.inputTokens,
                            completionTokens = d.outputTokens,
                            servedModel = d.model,
                            latencyMs = reply.latencyMs,
                        ),
                    )
                }
        }
    }

    override suspend fun testConnection(e: Endpoint, apiKey: String, model: String): AiResult<TestResult> {
        val base = when (val c = Http.checkBase(e.baseUrl, apiKey)) {
            is AiResult.Err -> return c
            is AiResult.Ok -> c.value
        }
        val url = BaseUrl.messages(base.normalized)
        val request = AiRequest(
            model = model,
            system = OpenAiCompatClient.TEST_SYSTEM,
            user = OpenAiCompatClient.TEST_USER,
            maxTokens = 16,
        )
        val reply = when (val x = post(e, apiKey, url, base.host, body(request), Http.TEST_READ_TIMEOUT_MS)) {
            is AiResult.Err -> return x
            is AiResult.Ok -> x.value
        }
        if (!reply.ok) {
            return AiResult.Err(Http.statusError(reply, base.host, Http.pathOf(url), model = model, apiKey = apiKey))
        }
        val d = when (val decoded = decode(reply, base.host, apiKey)) {
            is AiResult.Err -> return decoded
            is AiResult.Ok -> decoded.value
        }
        return AiResult.Ok(TestResult(servedModel = d.model, latencyMs = reply.latencyMs, reply = d.text))
    }

    override suspend fun listModels(e: Endpoint, apiKey: String?): AiResult<List<RemoteModel>> {
        val key = apiKey?.takeIf { it.isNotBlank() && !e.modelsPublic }
        val base = when (val c = Http.checkBase(e.baseUrl, key)) {
            is AiResult.Err -> return c
            is AiResult.Ok -> c.value
        }
        val url = BaseUrl.models(base.normalized, e.modelsPath)
        val exchanged = transport.exchange(
            HttpRequest(
                url = url,
                host = base.host,
                headers = headers(e, key),
                body = null,
                readTimeoutMs = Http.MODELS_READ_TIMEOUT_MS,
            ),
        )
        val reply = when (exchanged) {
            is AiResult.Err -> return exchanged
            is AiResult.Ok -> exchanged.value
        }
        if (!reply.ok) {
            return AiResult.Err(Http.statusError(reply, base.host, Http.pathOf(url), model = null, apiKey = key))
        }
        if (reply.isHtml) return AiResult.Err(AiError.NotOpenAiFormat(base.host))
        // Same list shape as OpenAI's (data[] of {id, display_name, max_input_tokens, capabilities}).
        return OpenAiCompatClient.parseModels(reply.body, base.host, e.providerId)
    }

    private suspend fun post(
        e: Endpoint,
        apiKey: String,
        url: String,
        host: String,
        body: JsonObject,
        readTimeoutMs: Int,
    ): AiResult<HttpReply> = transport.exchange(
        HttpRequest(url = url, host = host, headers = headers(e, apiKey), body = body.toString(), readTimeoutMs = readTimeoutMs),
    )

    internal class Decoded(
        val text: String,
        val stopReason: String?,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val model: String?,
    )

    companion object {
        /** Sent only when the catalog entry carries no anthropic-version of its own. */
        const val FALLBACK_VERSION = "2023-06-01"

        /** x-api-key, plus the catalog's headers; anthropic-version always present. */
        fun headers(e: Endpoint, apiKey: String?): Map<String, String> =
            // The key goes in x-api-key whatever the catalog's auth says: this client only speaks to Messages endpoints.
            Http.headers(
                e.copy(auth = AuthStyle.XApiKey),
                apiKey,
                defaults = mapOf("anthropic-version" to FALLBACK_VERSION),
            )

        /**
         * The request body exactly as sent: max_tokens is required, the system prompt is top
         * level, and the one user message holds the text followed by any photos as base64 blocks.
         */
        fun body(r: AiRequest): JsonObject = buildJsonObject {
            put("model", r.model)
            put("max_tokens", r.maxTokens)
            put("system", r.system)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", r.user)
                                        },
                                    )
                                    r.images.forEach { image ->
                                        add(
                                            buildJsonObject {
                                                put("type", "image")
                                                put(
                                                    "source",
                                                    buildJsonObject {
                                                        put("type", "base64")
                                                        put("media_type", image.mediaType)
                                                        put("data", image.base64)
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
            )
        }

        /**
         * Reads a Messages reply: the text blocks joined in order. thinking and redacted_thinking
         * blocks (and anything else that isn't text) are never shown as the answer.
         */
        internal fun decode(reply: HttpReply, host: String, apiKey: String?): AiResult<Decoded> {
            if (reply.isHtml) return AiResult.Err(AiError.NotOpenAiFormat(host))
            val root = runCatching { AiJson.wire.parseToJsonElement(reply.body) }.getOrNull() as? JsonObject
                ?: return AiResult.Err(AiError.NotOpenAiFormat(host))
            val error = root["error"]
            if (root.string("type") == "error" || (error != null && error !is JsonNull)) {
                return AiResult.Err(AiError.BadRequest(reply.code, host, Redact.secrets(root.errorMessage(), apiKey)))
            }
            val content = root["content"] as? JsonArray ?: return AiResult.Err(AiError.NotOpenAiFormat(host))
            val text = content
                .mapNotNull { it as? JsonObject }
                .filter { it.string("type") == "text" }
                .mapNotNull { it.string("text") }
                .joinToString("")
            val usage = root["usage"] as? JsonObject
            fun tokens(key: String): Int? = (usage?.get(key) as? JsonPrimitive)?.intOrNull
            return AiResult.Ok(
                Decoded(
                    text = text,
                    stopReason = root.string("stop_reason"),
                    inputTokens = tokens("input_tokens"),
                    outputTokens = tokens("output_tokens"),
                    model = root.string("model"),
                ),
            )
        }
    }
}
