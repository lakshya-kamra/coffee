package com.laksh.coffee.data.ai

import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.AuthStyle
import com.laksh.coffee.domain.ai.Price
import com.laksh.coffee.domain.ai.ProviderApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The OpenAI-compatible wire format: exactly what is sent, and how replies are read. */
class OpenAiWireTest {

    private val key = "sk-test-1234567890abcdef"
    private val request = AiRequest(model = "z-ai/glm-5.3-flash", system = "SYSTEM", user = "USER", temperature = 0.3, maxTokens = 6000)
    private val photo = AiImage(label = "bed", base64 = "QUJD", uid = "u1")

    private fun okReply(content: String = "{\"verdict\":\"close\"}", finish: String = "stop") = json(
        200,
        """{"id":"x","model":"served-model","choices":[{"index":0,"message":{"role":"assistant","content":${JsonPrimitive(content)},"reasoning_content":"SECRET THINKING"},"finish_reason":"$finish"}],"usage":{"prompt_tokens":3180,"completion_tokens":1260,"total_tokens":4440}}""",
    )

    // ------------------------------------------------------------ the request

    @Test
    fun withoutPhotosTheUserMessageIsAPlainString() {
        val body = OpenAiCompatClient.body(request, OpenAiCompatClient.MAX_TOKENS, 0.3)
        assertEquals("z-ai/glm-5.3-flash", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("SYSTEM", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(messages[1].jsonObject["content"] is JsonPrimitive)
        assertEquals("USER", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)
        assertEquals("0.3", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("6000", body["max_tokens"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("max_completion_tokens"))
    }

    @Test
    fun withPhotosTheUserMessageIsTextThenImageUrlParts() {
        val withImages = AiRequest("m", "SYSTEM", "USER", images = listOf(photo, AiImage("cup", "image/png", "REVG")), maxTokens = 10)
        val content = OpenAiCompatClient.body(withImages, OpenAiCompatClient.MAX_TOKENS, null)["messages"]!!.jsonArray[1].jsonObject["content"]
        assertTrue(content is JsonArray)
        val parts = content!!.jsonArray
        assertEquals(3, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("USER", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,QUJD", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,REVG", parts[2].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        // The system message stays a plain string either way.
        assertTrue(OpenAiCompatClient.body(withImages, OpenAiCompatClient.MAX_TOKENS, null)["messages"]!!.jsonArray[0].jsonObject["content"] is JsonPrimitive)
    }

    @Test
    fun openAiGetsMaxCompletionTokensAndNoTemperature() = runBlocking {
        val t = FakeTransport(okReply())
        OpenAiCompatClient(t).complete(endpointOf("openai"), key, request).ok()
        val body = t.body()
        assertEquals("6000", body["max_completion_tokens"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("max_tokens"))
        assertFalse(body.containsKey("temperature"))
        assertEquals("https://api.openai.com/v1/chat/completions", t.last.url)
    }

    @Test
    fun everyoneElseGetsMaxTokensAndTheTemperature() = runBlocking {
        listOf("commandcode", "openrouter", "google-gemini", "deepseek", "custom").forEach { id ->
            val t = FakeTransport(okReply())
            val e = endpointOf(id, if (id == "custom") "https://gateway.example.org/v1" else null)
            OpenAiCompatClient(t).complete(e, key, request).ok()
            assertEquals(id, "6000", t.body()["max_tokens"]!!.jsonPrimitive.content)
            assertFalse(id, t.body().containsKey("max_completion_tokens"))
            assertEquals(id, "0.3", t.body()["temperature"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun theKeyTravelsOnlyInTheAuthorizationHeader() = runBlocking {
        val t = FakeTransport(okReply())
        OpenAiCompatClient(t).complete(endpointOf("commandcode"), key, request).ok()
        val r = t.last
        assertEquals("Bearer $key", r.header("Authorization"))
        assertNull(r.header("x-api-key"))
        assertFalse(r.url.contains(key))
        assertFalse(r.body!!.contains(key))
        assertEquals("api.commandcode.ai", r.host)
        assertEquals("https://api.commandcode.ai/provider/v1/chat/completions", r.url)
    }

    @Test
    fun aCatalogHeaderCanNeverReplaceTheKey() = runBlocking {
        val t = FakeTransport(okReply())
        val e = Endpoint(
            providerId = "custom",
            baseUrl = "https://gateway.example.org/v1",
            api = ProviderApi.OpenAiChat,
            auth = AuthStyle.Bearer,
            extraHeaders = mapOf("Authorization" to "Bearer attacker", "X-Client" to "coffee", "Cookie" to "a=b"),
        )
        OpenAiCompatClient(t).complete(e, key, request).ok()
        assertEquals("Bearer $key", t.last.header("Authorization"))
        assertEquals("coffee", t.last.header("X-Client"))
        assertNull(t.last.header("Cookie"))
    }

    @Test
    fun plainHttpADotlessHostAndAKeyInTheAddressNeverReachTheNetwork() = runBlocking {
        val t = FakeTransport(okReply(), okReply(), okReply())
        val client = OpenAiCompatClient(t)
        fun custom(url: String) = Endpoint("custom", url, ProviderApi.OpenAiChat, AuthStyle.Bearer)
        assertTrue(client.complete(custom("http://localhost:11434/v1"), key, request).err() is AiError.InvalidBaseUrl)
        assertTrue(client.complete(custom("https://intranet/v1"), key, request).err() is AiError.InvalidBaseUrl)
        assertTrue(client.complete(custom("https://gateway.example.org/$key"), key, request).err() is AiError.InvalidBaseUrl)
        assertTrue(client.testConnection(custom(""), key, "m").err() is AiError.InvalidBaseUrl)
        assertTrue(t.requests.isEmpty())
    }

    // ------------------------------------------------------------ free retries

    @Test
    fun aRejectedTemperatureIsRetriedOnceWithoutIt() = runBlocking {
        val t = FakeTransport(
            json(400, """{"error":{"message":"Unsupported value: 'temperature' does not support 0.3 with this model."}}"""),
            okReply(),
        )
        val result = OpenAiCompatClient(t).complete(endpointOf("commandcode"), key, request).ok()
        assertEquals(2, t.requests.size)
        assertTrue(t.body(0).containsKey("temperature"))
        assertFalse(t.body(1).containsKey("temperature"))
        assertEquals("6000", t.body(1)["max_tokens"]!!.jsonPrimitive.content)
        // Both round trips count towards the time shown.
        assertEquals(240L, result.latencyMs)
    }

    @Test
    fun aRejectedTokenFieldIsRetriedOnceWithTheOtherOne() = runBlocking {
        val t = FakeTransport(
            json(400, """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."}}"""),
            okReply(),
        )
        OpenAiCompatClient(t).complete(endpointOf("openrouter"), key, request).ok()
        assertEquals(2, t.requests.size)
        assertTrue(t.body(0).containsKey("max_tokens"))
        assertEquals("6000", t.body(1)["max_completion_tokens"]!!.jsonPrimitive.content)
        assertFalse(t.body(1).containsKey("max_tokens"))
    }

    @Test
    fun bothRetriesCanHappenButNeverMoreThanOnceEach() = runBlocking {
        val t = FakeTransport(
            json(400, """{"error":{"message":"temperature is not supported"}}"""),
            json(400, """{"error":{"message":"use max_completion_tokens"}}"""),
            json(400, """{"error":{"message":"temperature and max_tokens are both wrong"}}"""),
            okReply(),
        )
        val error = OpenAiCompatClient(t).complete(endpointOf("commandcode"), key, request).err()
        assertEquals(3, t.requests.size)
        assertTrue(error is AiError.BadRequest)
    }

    @Test
    fun anUnrelated400IsNotRetried() = runBlocking {
        val t = FakeTransport(json(400, """{"error":{"message":"messages: field required"}}"""), okReply())
        assertTrue(OpenAiCompatClient(t).complete(endpointOf("commandcode"), key, request).err() is AiError.BadRequest)
        assertEquals(1, t.requests.size)
    }

    // --------------------------------------------------------------- replies

    @Test
    fun theAnswerIsTheContentAndNeverTheReasoning() = runBlocking {
        val t = FakeTransport(okReply("{\"verdict\":\"close\"}"))
        val r = OpenAiCompatClient(t).complete(endpointOf("commandcode"), key, request).ok()
        assertEquals("{\"verdict\":\"close\"}", r.content)
        assertFalse(r.content.contains("SECRET"))
        assertEquals(3180, r.promptTokens)
        assertEquals(1260, r.completionTokens)
        assertEquals("served-model", r.servedModel)
    }

    @Test
    fun contentPartsAreJoinedAndThinkingPartsAreSkipped() = runBlocking {
        val body = """{"choices":[{"message":{"content":[{"type":"thinking","text":"hmm"},{"type":"text","text":"A"},{"type":"output_text","text":"B"}]},"finish_reason":"stop"}]}"""
        val r = OpenAiCompatClient(FakeTransport(json(200, body))).complete(endpointOf("commandcode"), key, request).ok()
        assertEquals("AB", r.content)
    }

    @Test
    fun runningOutOfTokensIsTruncatedWhateverTheSpelling() = runBlocking {
        listOf("length", "max_tokens", "MAX_OUTPUT_TOKENS").forEach { finish ->
            val e = OpenAiCompatClient(FakeTransport(okReply(finish = finish))).complete(endpointOf("commandcode"), key, request).err()
            assertTrue(finish, e is AiError.Truncated)
            assertEquals(1260, (e as AiError.Truncated).completionTokens)
        }
        val filtered = OpenAiCompatClient(FakeTransport(okReply(finish = "content_filter"))).complete(endpointOf("commandcode"), key, request).err()
        assertTrue(filtered is AiError.Filtered)
    }

    @Test
    fun emptyAndOddRepliesBecomeErrorsNotCrashes() = runBlocking {
        fun run(reply: HttpReply) = runBlocking { OpenAiCompatClient(FakeTransport(reply)).complete(endpointOf("commandcode"), key, request).err() }
        assertTrue(run(okReply(content = "  ")) is AiError.EmptyReply)
        assertTrue(run(json(200, """{"choices":[]}""")) is AiError.EmptyReply)
        assertTrue(run(json(200, """{"hello":"world"}""")) is AiError.NotOpenAiFormat)
        assertTrue(run(json(200, "not json")) is AiError.NotOpenAiFormat)
        assertTrue(run(HttpReply(200, "text/html; charset=utf-8", "<html>login</html>")) is AiError.NotOpenAiFormat)
        assertTrue(run(json(200, """{"error":{"message":"quota"}}""")) is AiError.BadRequest)
    }

    @Test
    fun statusCodesMapToTheRightErrorsAndNeverEchoTheKey() = runBlocking {
        fun run(reply: HttpReply) = runBlocking { OpenAiCompatClient(FakeTransport(reply)).complete(endpointOf("commandcode"), key, request).err() }
        val unauthorized = run(json(401, """{"error":{"message":"Invalid key $key provided"}}"""))
        assertTrue(unauthorized is AiError.Unauthorized)
        assertFalse(unauthorized.message.contains(key))
        assertTrue(run(json(402, "{}")) is AiError.PaymentRequired)
        assertTrue(run(json(404, """{"error":{"message":"model z-ai/glm-5.3-flash not found"}}""")) is AiError.ModelNotFound)
        assertTrue(run(json(404, "{}")) is AiError.EndpointNotFound)
        assertEquals(30, (run(json(429, "{}", retryAfter = "30")) as AiError.RateLimited).retryAfterSec)
        assertTrue(run(json(503, "{}")) is AiError.ServerError)
        val redirected = run(HttpReply(302, null, "", location = "https://elsewhere.example.com/v1"))
        assertEquals("elsewhere.example.com", (redirected as AiError.Redirected).locationHost)
    }

    // ------------------------------------------------------------- test + list

    @Test
    fun theConnectionTestIsASixteenTokenCompletion() = runBlocking {
        val t = FakeTransport(okReply("OK"))
        val r = OpenAiCompatClient(t).testConnection(endpointOf("openai"), key, "gpt-6-luna").ok()
        assertEquals("OK", r.reply)
        assertEquals("16", t.body()["max_completion_tokens"]!!.jsonPrimitive.content)
        assertFalse(t.body().containsKey("temperature"))
        assertEquals("gpt-6-luna", t.body()["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun anEmptyTestReplyStillCountsBecauseReasoningModelsSpendTheBudgetThinking() = runBlocking {
        val t = FakeTransport(okReply(content = "", finish = "length"))
        assertEquals("", OpenAiCompatClient(t).testConnection(endpointOf("commandcode"), key, "m").ok().reply)
    }

    @Test
    fun aPublicModelListGetsNoKey() = runBlocking {
        val t = FakeTransport(json(200, """{"data":[{"id":"z-ai/glm-5.3-flash"}]}"""))
        OpenAiCompatClient(t).listModels(endpointOf("commandcode"), key).ok()
        assertNull(t.last.header("Authorization"))
        assertNull(t.last.body)
        assertEquals("https://api.commandcode.ai/provider/v1/models", t.last.url)

        val t2 = FakeTransport(json(200, """{"data":[]}"""))
        OpenAiCompatClient(t2).listModels(endpointOf("openai"), key).ok()
        assertEquals("Bearer $key", t2.last.header("Authorization"))
    }

    @Test
    fun modelListsKeepOddEntriesOutAndPutSuggestionsFirst() {
        val body = """
            {"data":[
              {"id":"zzz/other","name":"Zed","context_length":128000},
              {"id":"anthropic/claude","supported_endpoints":["/v1/messages"]},
              {"id":"xiaomi/mimo-v2.6-pro","supported_endpoints":["/v1/chat/completions/"]},
              {"id":"z-ai/glm-5.3-flash","name":"GLM","pricing":{"prompt":"0.0000002","completion":"0.0000006"},"architecture":{"input_modalities":["text","image"]}},
              {"id":"aaa/text-only","architecture":{"input_modalities":["text"]}},
              {"id":""},
              {"name":"no id"},
              "garbage",
              {"id":"zzz/other"}
            ]}
        """.trimIndent()
        val models = OpenAiCompatClient.parseModels(body, "api.commandcode.ai", "commandcode").ok()
        assertEquals(listOf("z-ai/glm-5.3-flash", "xiaomi/mimo-v2.6-pro", "aaa/text-only", "zzz/other"), models.map { it.id })
        val glm = models[0]
        assertTrue(glm.suggested)
        // The live list price wins over the catalog's.
        assertEquals(Price(0.2, 0.6), glm.price)
        assertEquals(true, glm.vision)
        // No live price: the catalog's, for a model it knows.
        assertEquals(Price(0.435, 0.87), models[1].price)
        assertEquals(false, models[2].vision)
        assertNull(models[3].vision)
        assertNull(models[3].price)
        assertEquals(128000, models[3].contextLength)
        assertFalse(models[3].suggested)
    }

    @Test
    fun aBareArrayIsAModelListToo() {
        val models = OpenAiCompatClient.parseModels("""[{"id":"b"},{"id":"a","display_name":"Alpha","max_input_tokens":200000,"capabilities":{"image_input":{"supported":true}}}]""", "h.example", "together").ok()
        assertEquals(listOf("a", "b"), models.map { it.id })
        assertEquals("Alpha", models[0].name)
        assertEquals(200000, models[0].contextLength)
        assertEquals(true, models[0].vision)
        assertTrue(OpenAiCompatClient.parseModels("""{"models":[]}""", "h.example", "together").err() is AiError.NotOpenAiFormat)
    }

    @Test
    fun requestObjectsNeverPrintTheirHeaders() {
        val r = HttpRequest("https://a.example/v1", "a.example", mapOf("Authorization" to "Bearer $key"), null, 1000)
        assertFalse(r.toString().contains(key))
    }
}
