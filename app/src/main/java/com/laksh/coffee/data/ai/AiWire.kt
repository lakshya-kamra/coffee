package com.laksh.coffee.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// OpenAI-compatible wire shapes. Only what the app reads is declared: reasoning_content and
// reasoning are left out on purpose, so ignoreUnknownKeys drops them and they are never shown.
// Request bodies are built by hand in each client (OpenAiCompatClient.body, AnthropicClient.body),
// so the key order is fixed and optional fields are simply absent.

/** [content] is a plain string, or an array of text and image parts when photos are attached. */
@Serializable
data class ChatMessage(val role: String, val content: JsonElement)

@Serializable
internal data class ChatResponseDto(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
    val error: JsonElement? = null,
)

@Serializable
internal data class ChoiceDto(
    val index: Int = 0,
    val message: MessageDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

// content is a JsonElement because providers send either a string or an array of parts.
@Serializable
internal data class MessageDto(
    val role: String? = null,
    val content: JsonElement? = null,
)

@Serializable
internal data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
internal data class ModelsResponseDto(
    val data: List<ModelDto> = emptyList(),
)

// supportedEndpoints null means "not stated" (most providers), which is kept. display_name and
// max_input_tokens are Anthropic's spellings; pricing and architecture are OpenRouter's.
@Serializable
internal data class ModelDto(
    val id: String,
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
    @SerialName("max_input_tokens") val maxInputTokens: Int? = null,
    @SerialName("supported_endpoints") val supportedEndpoints: List<String>? = null,
    val pricing: JsonElement? = null,
    val architecture: JsonElement? = null,
    val capabilities: JsonElement? = null,
)

/** The reply text: a plain string, or the text parts of a content array joined together. */
internal fun JsonElement?.contentText(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull
    is JsonArray -> joinToString("") { it.textPart().orEmpty() }
    is JsonObject -> textPart()
}

/** The provider's error text from {error: {message}}, {error: "..."} or {message}. */
internal fun JsonElement?.errorMessage(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull
    is JsonArray -> firstNotNullOfOrNull { it.errorMessage() }
    is JsonObject -> when (val error = this["error"]) {
        is JsonObject -> error.string("message") ?: error.string("detail") ?: error.string("code")
        is JsonPrimitive -> error.contentOrNull
        else -> null
    } ?: string("message") ?: string("detail")
}

private fun JsonElement.textPart(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> {
        // Only visible answer parts; reasoning or thinking parts are never shown as the answer.
        val type = string("type")
        if (type == null || type == "text" || type == "output_text") string("text") else null
    }
    else -> null
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
