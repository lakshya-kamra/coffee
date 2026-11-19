package com.laksh.coffee.data.ai

import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.AiProviders
import com.laksh.coffee.domain.ai.AiResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Stands in for the network: records every request and answers from a queue. */
internal class FakeTransport(vararg replies: HttpReply) : Transport {
    private val queue = ArrayDeque(replies.toList())
    val requests = mutableListOf<HttpRequest>()

    override suspend fun exchange(request: HttpRequest): AiResult<HttpReply> {
        requests += request
        val next = queue.removeFirstOrNull() ?: return AiResult.Err(AiError.Unknown("NoReplyQueued"))
        return AiResult.Ok(next)
    }

    val last: HttpRequest get() = requests.last()

    fun body(index: Int = requests.lastIndex): JsonObject = Json.parseToJsonElement(requests[index].body!!) as JsonObject
}

internal fun json(code: Int, body: String, retryAfter: String? = null) =
    HttpReply(code = code, contentType = "application/json", body = body, retryAfter = retryAfter, latencyMs = 120)

internal fun endpointOf(providerId: String, baseUrl: String? = null): Endpoint {
    val p = AiProviders.byId(providerId)!!
    return Endpoint.of(p, baseUrl ?: p.baseUrl)
}

internal fun <T> AiResult<T>.ok(): T = when (this) {
    is AiResult.Ok -> value
    is AiResult.Err -> throw AssertionError("expected Ok, got ${error::class.simpleName}: ${error.title} / ${error.message}")
}

internal fun <T> AiResult<T>.err(): AiError = when (this) {
    is AiResult.Ok -> throw AssertionError("expected an error, got $value")
    is AiResult.Err -> error
}

/** A header lookup that ignores case, as HTTP does. */
internal fun HttpRequest.header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
