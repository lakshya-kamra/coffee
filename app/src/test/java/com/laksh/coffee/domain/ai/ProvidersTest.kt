package com.laksh.coffee.domain.ai

import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogModel
import com.laksh.coffee.domain.catalog.CatalogProvider
import com.laksh.coffee.domain.catalog.ListModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The provider presets a phone can use, built from shared/catalog/providers.json. */
class ProvidersTest {

    @Test
    fun onlyHttpsProvidersAndCustomAreUsableOnAPhone() {
        val all = AiProviders.all
        assertTrue(all.size >= 10)
        all.filter { !it.isCustom }.forEach { p ->
            assertTrue("${p.id}: ${p.baseUrl}", p.baseUrl.startsWith("https://"))
            assertTrue(p.id, BaseUrl.check(p.baseUrl) is BaseUrlCheck.Valid)
            assertTrue(p.id, p.host.contains('.'))
        }
        // Ollama and LM Studio run on a computer at http://localhost: not offered, but named.
        assertNull(AiProviders.byId("ollama"))
        assertNull(AiProviders.byId("lm-studio"))
        assertEquals(2, AiProviders.leftOut.size)
        assertTrue(AiProviders.leftOut.any { it.startsWith("Ollama") })
        assertTrue(AiProviders.leftOut.any { it.startsWith("LM Studio") })
    }

    @Test
    fun catalogOrderIsKeptAndTheWellKnownProvidersAreThere() {
        val ids = AiProviders.all.map { it.id }
        assertEquals(Catalog.providers.map { it.id }.filter { it in ids }, ids)
        listOf("openai", "anthropic", "google-gemini", "openrouter", "deepseek", "xai", "groq", "mistral", "commandcode", "custom")
            .forEach { assertTrue(it, it in ids) }
    }

    @Test
    fun customIsPresentAndHasNoAddressOfItsOwn() {
        val custom = AiProviders.byId(AiProviders.CUSTOM)
        assertNotNull(custom)
        assertTrue(custom!!.isCustom)
        assertEquals("", custom.baseUrl)
        assertEquals(ProviderApi.OpenAiChat, custom.api)
        assertEquals(AuthStyle.Bearer, custom.auth)
    }

    @Test
    fun aBrokenCatalogStillLeavesCustom() {
        val (usable, leftOut) = AiProviders.from(emptyList())
        assertEquals(listOf(AiProviders.CUSTOM), usable.map { it.id })
        assertTrue(leftOut.isEmpty())
    }

    @Test
    fun anthropicSpeaksMessagesWithXApiKeyAndItsVersionHeader() {
        val a = AiProviders.byId("anthropic")!!
        assertEquals(ProviderApi.AnthropicMessages, a.api)
        assertEquals(AuthStyle.XApiKey, a.auth)
        assertTrue(a.extraHeaders.keys.any { it.equals("anthropic-version", ignoreCase = true) })
        assertEquals("api.anthropic.com", a.host)
        assertTrue(a.keyUrl.startsWith("https://"))
    }

    @Test
    fun publicModelListsAreMarkedSoNoKeyIsSentForThem() {
        assertTrue(AiProviders.byId("commandcode")!!.modelsPublic)
        assertTrue(AiProviders.byId("openrouter")!!.modelsPublic)
        assertFalse(AiProviders.byId("openai")!!.modelsPublic)
        assertFalse(AiProviders.byId("anthropic")!!.modelsPublic)
    }

    @Test
    fun headersThatCouldCarryOrReplaceACredentialAreNeverSent() {
        assertTrue(AiProviders.headerAllowed("anthropic-version", "2026-10-21"))
        assertTrue(AiProviders.headerAllowed("X-Title", "Coffee"))
        listOf("Authorization", "authorization", "X-Api-Key", "x-api-key", "Cookie", "Host", "Content-Length", "Content-Type")
            .forEach { assertFalse(it, AiProviders.headerAllowed(it, "x")) }
        assertFalse(AiProviders.headerAllowed("bad name", "x"))
        assertFalse(AiProviders.headerAllowed("bad:name", "x"))
        assertFalse(AiProviders.headerAllowed("", "x"))
        assertFalse(AiProviders.headerAllowed("a".repeat(65), "x"))
        // A header value can't smuggle a second header or anything non-ASCII.
        assertFalse(AiProviders.headerAllowed("X-Ok", "line\r\nAuthorization: Bearer stolen"))
        assertFalse(AiProviders.headerAllowed("X-Ok", "café"))
        assertFalse(AiProviders.headerAllowed("X-Ok", "v".repeat(257)))
    }

    @Test
    fun reservedCatalogHeadersAreDroppedWhenAProviderIsBuilt() {
        val entry = CatalogProvider(
            id = "example",
            name = "Example",
            baseUrl = "https://api.example.com/v1",
            extraHeaders = mapOf("Authorization" to "Bearer catalog", "X-Client" to "coffee", "Cookie" to "a=b"),
            listModels = ListModels(path = "/models?key=1", works = true, note = "Public: no key needed."),
            models = listOf(
                CatalogModel(id = "m-1", label = "", inputPerM = 1.0, outputPerM = null, vision = true, default = true),
                CatalogModel(id = "vendor/m-2", inputPerM = 0.5, outputPerM = 2.0),
            ),
        )
        val p = AiProviders.from(listOf(entry)).first.first()
        assertEquals(mapOf("X-Client" to "coffee"), p.extraHeaders)
        // A models path that is more than a plain path is not used.
        assertNull(p.modelsPath)
        assertTrue(p.modelsPublic)
        // One price alone is not a price; a blank label falls back to the id's last part.
        assertNull(p.models[0].price)
        assertEquals("m-1", p.models[0].label)
        assertEquals(Price(0.5, 2.0), p.models[1].price)
        assertEquals("m-2", p.models[1].label)
        assertEquals("m-1", p.defaultModel?.id)
    }

    @Test
    fun anUnknownWireFormatOrAPlainHttpAddressIsLeftOut() {
        val (usable, leftOut) = AiProviders.from(
            listOf(
                CatalogProvider(id = "future", name = "Future", baseUrl = "https://api.future.example/v1", api = "responses-v9"),
                CatalogProvider(id = "local", name = "Local (on this computer)", baseUrl = "http://localhost:1234/v1"),
                CatalogProvider(id = "ok", name = "OK", baseUrl = "https://api.ok.example/v1"),
            ),
        )
        assertEquals(listOf("ok", AiProviders.CUSTOM), usable.map { it.id })
        // Only the plain-http one is named in the note; an unknown format is simply absent.
        assertEquals(listOf("Local (on this computer)"), leftOut)
    }

    @Test
    fun aHostFindsItsPresetButNeverCustom() {
        assertEquals("commandcode", AiProviders.forHost("api.commandcode.ai")?.id)
        assertEquals("openrouter", AiProviders.forHost("OpenRouter.ai")?.id)
        assertEquals("anthropic", AiProviders.forHost("api.anthropic.com")?.id)
        assertNull(AiProviders.forHost("gateway.ai.cloudflare.com"))
        assertNull(AiProviders.forHost(""))
    }

    @Test
    fun noTwoPresetsShareAHost() {
        // forHost (and so the 1.x migration) relies on a host naming at most one preset.
        val hosts = AiProviders.all.filter { !it.isCustom }.map { it.host }
        assertEquals(hosts.size, hosts.toSet().size)
    }
}
