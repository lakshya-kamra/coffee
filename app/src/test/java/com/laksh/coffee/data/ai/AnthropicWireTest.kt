package com.laksh.coffee.data.ai

import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.AiProviders
import com.laksh.coffee.domain.ai.AuthStyle
import com.laksh.coffee.domain.ai.ProviderApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anthropic's Messages wire format: exactly what is sent, and how replies are read. */
class AnthropicWireTest {

    private val key = "sk-ant-test-1234567890abcdef"
    private val request = AiRequest(
        model = "claude-haiku-5-5",
        system = "SYSTEM",
        user = "USER",
        images = listOf(AiImage(label = "bed", base64 = "QUJD", uid = "u1")),
        temperature = 0.3,
        maxTokens = 6000,
    )

    private fun reply(content: String, stop: String = "end_turn") = json(
        200,
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5","content":$content,"stop_reason":"$stop","usage":{"input_tokens":3200,"output_tokens":900}}""",
    )

    private val textReply = reply("""[{"type":"text","text":"{\"verdict\":\"close\"}"}]""")

    // ------------------------------------------------------------ the request

    @Test
    fun theBodyIsModelMaxTokensSystemAndOneUserMessage() {
        val body = AnthropicClient.body(request)
        assertEquals(listOf("model", "max_tokens", "system", "messages"), body.keys.toList())
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("6000", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("SYSTEM", body["system"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun aPhotoIsABase64ImageBlockAfterTheText() {
        val content = AnthropicClient.body(request)["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("USER", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        val image = content[1].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        val source = image["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        // The bare Base64, with no data: prefix.
        assertEquals("QUJD", source["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun noSamplingParameterIsEverSent() {
        // The API version the catalog pins answers temperature, top_p or top_k with HTTP 400.
        val body = AnthropicClient.body(request)
        listOf("temperature", "top_p", "top_k", "stream", "thinking").forEach { assertFalse(it, body.containsKey(it)) }
    }

    @Test
    fun theKeyGoesInXApiKeyWithTheCatalogsVersionAndNowhereElse() = runBlocking {
        val t = FakeTransport(textReply)
        AnthropicClient(t).complete(endpointOf("anthropic"), key, request).ok()
        val r = t.last
        assertEquals("https://api.anthropic.com/v1/messages", r.url)
        assertEquals("api.anthropic.com", r.host)
        assertEquals(key, r.header("x-api-key"))
        assertNull(r.header("Authorization"))
        val catalogVersion = AiProviders.byId("anthropic")!!.extraHeaders.entries.first { it.key.equals("anthropic-version", true) }.value
        assertEquals(catalogVersion, r.header("anthropic-version"))
        // Exactly one version header, whatever its capitalisation.
        assertEquals(1, r.headers.keys.count { it.equals("anthropic-version", ignoreCase = true) })
        assertFalse(r.url.contains(key))
        assertFalse(r.body!!.contains(key))
    }

    @Test
    fun withoutACatalogVersionTheFallbackIsSent() {
        val e = Endpoint("x", "https://api.example.com/v1", ProviderApi.AnthropicMessages, AuthStyle.XApiKey)
        assertEquals(AnthropicClient.FALLBACK_VERSION, AnthropicClient.headers(e, key)["anthropic-version"])
        // Even if a catalog entry said "bearer", this client only ever uses x-api-key.
        val bearer = e.copy(auth = AuthStyle.Bearer, extraHeaders = mapOf("Anthropic-Version" to "2026-10-21", "x-api-key" to "attacker"))
        val headers = AnthropicClient.headers(bearer, key)
        assertEquals(key, headers["x-api-key"])
        assertFalse(headers.containsKey("Authorization"))
        assertEquals("2026-10-21", headers["Anthropic-Version"])
        assertFalse(headers.containsKey("anthropic-version"))
    }

    // --------------------------------------------------------------- replies

    @Test
    fun textBlocksAreJoinedAndThinkingIsNeverShown() = runBlocking {
        val content = """[
            {"type":"thinking","thinking":"SECRET REASONING","signature":"abc"},
            {"type":"redacted_thinking","data":"zzz"},
            {"type":"text","text":"{\"verdict\":"},
            {"type":"tool_use","id":"t","name":"n","input":{}},
            {"type":"text","text":"\"close\"}"}
        ]"""
        val r = AnthropicClient(FakeTransport(reply(content))).complete(endpointOf("anthropic"), key, request).ok()
        assertEquals("{\"verdict\":\"close\"}", r.content)
        assertFalse(r.content.contains("SECRET"))
        assertEquals(3200, r.promptTokens)
        assertEquals(900, r.completionTokens)
        assertEquals("claude-haiku-5-5", r.servedModel)
        assertEquals("end_turn", r.finishReason)
        assertEquals(120L, r.latencyMs)
    }

    @Test
    fun stopReasonsDecideBeforeTheTextDoes() = runBlocking {
        fun run(r: HttpReply) = runBlocking { AnthropicClient(FakeTransport(r)).complete(endpointOf("anthropic"), key, request).err() }
        val cut = run(reply("""[{"type":"text","text":"{\"verdict\":\"clo"}]""", stop = "max_tokens"))
        assertTrue(cut is AiError.Truncated)
        assertEquals(900, (cut as AiError.Truncated).completionTokens)
        // A refusal is HTTP 200 and may carry a few words; it is still not an answer.
        val refused = run(reply("""[{"type":"text","text":"I can't help with that."}]""", stop = "refusal"))
        assertTrue(refused is AiError.Filtered)
        assertTrue(run(reply("""[{"type":"thinking","thinking":"only thinking"}]""")) is AiError.EmptyReply)
        assertTrue(run(reply("[]")) is AiError.EmptyReply)
    }

    @Test
    fun errorBodiesAndStatusCodesMapLikeTheOpenAiClient() = runBlocking {
        fun run(r: HttpReply) = runBlocking { AnthropicClient(FakeTransport(r)).complete(endpointOf("anthropic"), key, request).err() }
        val unauthorized = run(json(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key $key"},"request_id":"req_1"}"""))
        assertTrue(unauthorized is AiError.Unauthorized)
        assertFalse(unauthorized.message.contains(key))
        assertTrue(unauthorized.title, unauthorized.title.startsWith("Anthropic"))
        // 529 is Anthropic's "overloaded": their side, like any 5xx.
        val overloaded = run(json(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        assertEquals(529, (overloaded as AiError.ServerError).code)
        assertTrue(run(json(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""")) is AiError.RateLimited)
        assertTrue(run(json(404, """{"type":"error","error":{"type":"not_found_error","message":"model: claude-haiku-5-5"}}""")) is AiError.ModelNotFound)
        val bad = run(json(400, """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: must be positive"}}"""))
        assertTrue(bad is AiError.BadRequest)
        assertTrue(bad.message, bad.message.contains("max_tokens: must be positive"))
        // An error object on a 200 is still an error.
        assertTrue(run(json(200, """{"type":"error","error":{"type":"api_error","message":"boom"}}""")) is AiError.BadRequest)
        assertTrue(run(json(200, """{"choices":[{"message":{"content":"openai shape"}}]}""")) is AiError.NotOpenAiFormat)
        assertTrue(run(HttpReply(200, "text/html", "<html></html>")) is AiError.NotOpenAiFormat)
    }

    @Test
    fun aRejectedRequestIsNotRetried() = runBlocking {
        val t = FakeTransport(json(400, """{"type":"error","error":{"type":"invalid_request_error","message":"temperature: unexpected"}}"""), textReply)
        assertTrue(AnthropicClient(t).complete(endpointOf("anthropic"), key, request).err() is AiError.BadRequest)
        assertEquals(1, t.requests.size)
    }

    // ------------------------------------------------------------- test + list

    @Test
    fun theConnectionTestIsASixteenTokenMessageAndAnyReplyInTheFormatCounts() = runBlocking {
        // A thinking model can spend all 16 tokens before it says anything.
        val t = FakeTransport(reply("""[{"type":"thinking","thinking":""}]""", stop = "max_tokens"))
        val r = AnthropicClient(t).testConnection(endpointOf("anthropic"), key, "claude-haiku-5-5").ok()
        assertEquals("", r.reply)
        assertEquals("claude-haiku-5-5", r.servedModel)
        assertEquals("16", t.body()["max_tokens"]!!.jsonPrimitive.content)
        assertEquals(1, t.body()["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray.size)
        assertEquals(key, t.last.header("x-api-key"))
    }

    @Test
    fun modelsAreListedWithTheSameHeadersAndReadFromDisplayName() = runBlocking {
        val t = FakeTransport(
            json(
                200,
                """{"data":[
                    {"id":"claude-opus-5-5","display_name":"Claude Opus 5.5","max_input_tokens":1000000,"capabilities":{"image_input":{"supported":true}}},
                    {"id":"claude-haiku-5-5","display_name":"Claude Haiku 5.5","max_input_tokens":1000000},
                    {"id":"claude-next","display_name":"Claude Next","capabilities":{"image_input":{"supported":false}}}
                ],"has_more":false}""",
            ),
        )
        val models = AnthropicClient(t).listModels(endpointOf("anthropic"), key).ok()
        assertEquals("https://api.anthropic.com/v1/models", t.last.url)
        assertNull(t.last.body)
        assertEquals(key, t.last.header("x-api-key"))
        assertTrue(t.last.header("anthropic-version") != null)
        // The catalog's suggestions first, in its order (Haiku is the default), then the rest by name.
        assertEquals(listOf("claude-haiku-5-5", "claude-opus-5-5", "claude-next"), models.map { it.id })
        assertEquals("Claude Haiku 5.5", models[0].name)
        assertEquals(1_000_000, models[0].contextLength)
        // Not in the list's capabilities, so the catalog's word is used.
        assertEquals(true, models[0].vision)
        assertEquals(false, models[2].vision)
        assertFalse(models[2].suggested)
    }

    @Test
    fun theClientsAreChosenByWireFormat() {
        val clients = ChatClients(FakeTransport())
        assertTrue(clients.forApi(ProviderApi.AnthropicMessages) is AnthropicClient)
        assertTrue(clients.forApi(ProviderApi.OpenAiChat) is OpenAiCompatClient)
    }
}
