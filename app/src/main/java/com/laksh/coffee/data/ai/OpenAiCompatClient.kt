package com.laksh.coffee.data.ai

import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.AiJson
import com.laksh.coffee.domain.ai.AiProviders
import com.laksh.coffee.domain.ai.AiResult
import com.laksh.coffee.domain.ai.BaseUrl
import com.laksh.coffee.domain.ai.Price
import com.laksh.coffee.domain.ai.Redact
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToLong

/**
 * The OpenAI-compatible chat format (POST {base}/chat/completions), which every catalog
 * provider but Anthropic speaks. The key travels only in the Authorization header.
 */
class OpenAiCompatClient(private val transport: Transport) : ChatClient {

    override suspend fun listModels(e: Endpoint, apiKey: String?): AiResult<List<RemoteModel>> {
        // A public list never gets a key it doesn't need.
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
                headers = Http.headers(e, key),
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
        return parseModels(reply.body, base.host, e.providerId)
    }

    override suspend fun complete(e: Endpoint, apiKey: String, r: AiRequest, readTimeoutMs: Int): AiResult<ChatResult> {
        val base = when (val c = Http.checkBase(e.baseUrl, apiKey)) {
            is AiResult.Err -> return c
            is AiResult.Ok -> c.value
        }
        val url = BaseUrl.chatCompletions(base.normalized)
        var tokenField = tokenFieldFor(e.providerId)
        var temperature = temperatureFor(e.providerId, r.temperature)
        val first = post(e, apiKey, url, base.host, body(r, tokenField, temperature), readTimeoutMs)
        var reply = when (first) {
            is AiResult.Err -> return first
            is AiResult.Ok -> first.value
        }
        var latencyMs = reply.latencyMs

        // A 400 is not billed, so a retry that drops or renames the rejected parameter is free.
        // At most one of each: some models reject a temperature, some want the other token field.
        var retriedTemperature = false
        var retriedTokens = false
        repeat(2) {
            if (reply.code != 400) return@repeat
            val said = Http.providerText(reply.body).orEmpty()
            when {
                !retriedTemperature && temperature != null && said.contains("temperature", ignoreCase = true) -> {
                    retriedTemperature = true
                    temperature = null
                }
                !retriedTokens && TOKEN_FIELD_WORDS.containsMatchIn(said) -> {
                    retriedTokens = true
                    tokenField = if (tokenField == MAX_TOKENS) MAX_COMPLETION_TOKENS else MAX_TOKENS
                }
                else -> return@repeat
            }
            val retry = post(e, apiKey, url, base.host, body(r, tokenField, temperature), readTimeoutMs)
            reply = when (retry) {
                is AiResult.Err -> return retry
                is AiResult.Ok -> retry.value
            }
            latencyMs += reply.latencyMs
        }

        if (!reply.ok) {
            return AiResult.Err(Http.statusError(reply, base.host, Http.pathOf(url), model = r.model, apiKey = apiKey))
        }
        val dto = when (val d = decodeChat(reply, base.host, apiKey)) {
            is AiResult.Err -> return d
            is AiResult.Ok -> d.value
        }
        val choice = dto.choices.firstOrNull() ?: return AiResult.Err(AiError.EmptyReply)
        val content = choice.message?.content.contentText()
        return when (choice.finishReason?.lowercase()) {
            // The repository adds the cost label; it knows the price. Gateways in front of other
            // vendors pass their own spelling of "ran out of tokens" through, so all of them count.
            "length", "max_tokens", "max_output_tokens" ->
                AiResult.Err(AiError.Truncated(dto.usage?.completionTokens, null))
            "content_filter" -> AiResult.Err(AiError.Filtered(base.host))
            else ->
                if (content.isNullOrBlank()) {
                    AiResult.Err(AiError.EmptyReply)
                } else {
                    AiResult.Ok(
                        ChatResult(
                            content = content,
                            finishReason = choice.finishReason,
                            promptTokens = dto.usage?.promptTokens,
                            completionTokens = dto.usage?.completionTokens,
                            servedModel = dto.model,
                            latencyMs = latencyMs,
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
        val url = BaseUrl.chatCompletions(base.normalized)
        val request = AiRequest(model = model, system = TEST_SYSTEM, user = TEST_USER, maxTokens = 16)
        var tokenField = tokenFieldFor(e.providerId)
        var exchanged = post(e, apiKey, url, base.host, body(request, tokenField, null), Http.TEST_READ_TIMEOUT_MS)
        var reply = when (exchanged) {
            is AiResult.Err -> return exchanged
            is AiResult.Ok -> exchanged.value
        }
        if (reply.code == 400 && TOKEN_FIELD_WORDS.containsMatchIn(Http.providerText(reply.body).orEmpty())) {
            tokenField = if (tokenField == MAX_TOKENS) MAX_COMPLETION_TOKENS else MAX_TOKENS
            exchanged = post(e, apiKey, url, base.host, body(request, tokenField, null), Http.TEST_READ_TIMEOUT_MS)
            reply = when (exchanged) {
                is AiResult.Err -> return exchanged
                is AiResult.Ok -> exchanged.value
            }
        }
        if (!reply.ok) {
            return AiResult.Err(Http.statusError(reply, base.host, Http.pathOf(url), model = model, apiKey = apiKey))
        }
        val dto = when (val d = decodeChat(reply, base.host, apiKey)) {
            is AiResult.Err -> return d
            is AiResult.Ok -> d.value
        }
        val content = dto.choices.firstOrNull()?.message?.content.contentText()
        return AiResult.Ok(
            TestResult(servedModel = dto.model, latencyMs = reply.latencyMs, reply = content.orEmpty()),
        )
    }

    private suspend fun post(
        e: Endpoint,
        apiKey: String,
        url: String,
        host: String,
        body: JsonObject,
        readTimeoutMs: Int,
    ): AiResult<HttpReply> = transport.exchange(
        HttpRequest(
            url = url,
            host = host,
            headers = Http.headers(e, apiKey),
            body = body.toString(),
            readTimeoutMs = readTimeoutMs,
        ),
    )

    private fun decodeChat(reply: HttpReply, host: String, apiKey: String?): AiResult<ChatResponseDto> {
        if (reply.isHtml) return AiResult.Err(AiError.NotOpenAiFormat(host))
        val root = runCatching { AiJson.wire.parseToJsonElement(reply.body) }.getOrNull() as? JsonObject
            ?: return AiResult.Err(AiError.NotOpenAiFormat(host))
        val error = root["error"]
        if (error != null && error !is JsonNull) {
            return AiResult.Err(AiError.BadRequest(reply.code, host, Redact.secrets(error.errorMessage(), apiKey)))
        }
        if ("choices" !in root) return AiResult.Err(AiError.NotOpenAiFormat(host))
        val dto = runCatching { AiJson.wire.decodeFromJsonElement(ChatResponseDto.serializer(), root) }.getOrNull()
            ?: return AiResult.Err(AiError.NotOpenAiFormat(host))
        return AiResult.Ok(dto)
    }

    companion object {
        const val MAX_TOKENS = "max_tokens"
        const val MAX_COMPLETION_TOKENS = "max_completion_tokens"

        internal const val TEST_SYSTEM = "Reply with the single word OK."
        internal const val TEST_USER = "OK?"

        private val TOKEN_FIELD_WORDS = Regex("max_(completion_)?tokens", RegexOption.IGNORE_CASE)

        /**
         * OpenAI's current models are reasoning models: they take max_completion_tokens and
         * reject max_tokens (providers.json notes). Everyone else still reads max_tokens.
         */
        fun tokenFieldFor(providerId: String): String =
            if (providerId == AiProviders.OPENAI) MAX_COMPLETION_TOKENS else MAX_TOKENS

        /** OpenAI's reasoning models reject a temperature, so none is sent there. */
        fun temperatureFor(providerId: String, wanted: Double?): Double? =
            if (providerId == AiProviders.OPENAI) null else wanted

        /**
         * The request body exactly as sent. The user message is a plain string without photos,
         * and text plus image_url parts (data: URLs) with them.
         */
        fun body(r: AiRequest, tokenField: String, temperature: Double?): JsonObject = buildJsonObject {
            put("model", r.model)
            put(
                "messages",
                buildJsonArray {
                    add(message("system", JsonPrimitive(r.system)))
                    add(message("user", userContent(r)))
                },
            )
            put("stream", false)
            if (temperature != null) put("temperature", temperature)
            put(tokenField, r.maxTokens)
        }

        private fun message(role: String, content: JsonElement): JsonElement =
            AiJson.wire.encodeToJsonElement(ChatMessage.serializer(), ChatMessage(role, content))

        private fun userContent(r: AiRequest): JsonElement {
            if (r.images.isEmpty()) return JsonPrimitive(r.user)
            return buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", r.user)
                    },
                )
                r.images.forEach { image ->
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", "data:${image.mediaType};base64,${image.base64}") })
                        },
                    )
                }
            }
        }

        /** data[] or a bare array. Decoded one by one, so a single odd entry doesn't hide the whole list. */
        fun parseModels(body: String, host: String, providerId: String): AiResult<List<RemoteModel>> {
            val root = runCatching { AiJson.wire.parseToJsonElement(body) }.getOrNull()
            val entries = when (root) {
                is JsonObject -> root["data"] as? JsonArray
                is JsonArray -> root
                else -> null
            } ?: return AiResult.Err(AiError.NotOpenAiFormat(host))

            val provider = AiProviders.byId(providerId)
            val models = entries
                .mapNotNull { runCatching { AiJson.wire.decodeFromJsonElement(ModelDto.serializer(), it) }.getOrNull() }
                .filter { it.id.isNotBlank() }
                // Drops models that only speak another API (Claude's /messages on Command Code).
                .filter { dto -> dto.supportedEndpoints?.any { it.trimEnd('/').endsWith("chat/completions") } ?: true }
                .distinctBy { it.id }
                .map { dto ->
                    val known = provider?.model(dto.id)
                    RemoteModel(
                        id = dto.id,
                        name = dto.name?.takeIf { it.isNotBlank() } ?: dto.displayName?.takeIf { it.isNotBlank() } ?: dto.id,
                        ownedBy = dto.ownedBy,
                        contextLength = dto.contextLength ?: dto.maxInputTokens,
                        price = livePrice(dto.pricing) ?: known?.price,
                        vision = takesImages(dto) ?: known?.vision,
                        suggested = known != null,
                    )
                }
            // The catalog's suggestions first, in catalog order; the rest by name.
            val order = provider?.models?.map { it.id }.orEmpty()
            return AiResult.Ok(
                models.sortedWith(
                    compareBy<RemoteModel>(
                        { m -> order.indexOf(m.id).let { if (it < 0) Int.MAX_VALUE else it } },
                        { m -> m.name.lowercase() },
                    ),
                ),
            )
        }

        /** OpenRouter lists USD per token as strings; the app shows USD per million. */
        private fun livePrice(pricing: JsonElement?): Price? {
            val o = pricing as? JsonObject ?: return null
            val input = (o["prompt"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
            val output = (o["completion"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
            if (!input.isFinite() || !output.isFinite() || input < 0 || output < 0) return null
            fun perMillion(v: Double): Double = (v * 1_000_000 * 1000).roundToLong() / 1000.0
            return Price(perMillion(input), perMillion(output))
        }

        /** OpenRouter: architecture.input_modalities. Anthropic: capabilities.image_input.supported. */
        internal fun takesImages(dto: ModelDto): Boolean? {
            ((dto.architecture as? JsonObject)?.get("input_modalities") as? JsonArray)?.let { list ->
                return list.any { (it as? JsonPrimitive)?.contentOrNull == "image" }
            }
            val supported = ((dto.capabilities as? JsonObject)?.get("image_input") as? JsonObject)?.get("supported")
            return (supported as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        }
    }
}
