package com.laksh.coffee.data.ai

import com.laksh.coffee.domain.ai.AiProviders
import com.laksh.coffee.domain.ai.AuthStyle
import com.laksh.coffee.domain.ai.ProviderApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-off move from the 1.x AI settings (one address, one key) to one key per provider,
 * and how the new layout reads back. The key's ciphertext is only ever moved, never opened.
 */
class AiPrefsMigrationTest {

    private val commandCodeUrl = "https://api.commandcode.ai/provider/v1"
    private val glm = "z-ai/glm-5.3-flash"

    // ------------------------------------------------------------- migration

    @Test
    fun theOwnersCommandCodeKeyModelAndTestedStateSurvive() {
        // 1.x never stored the default address or model: only what the owner changed.
        val old = mapOf(
            "key_ct" to "v1:aXY=:Y2lwaGVy",
            "key_hint" to "9f2c",
            "key_host" to "api.commandcode.ai",
            "last_test_ok_at" to 1_789_000_000_000L,
            "last_test_sig" to "$commandCodeUrl|$glm|9f2c",
        )
        val out = AiPrefsMigration.migrateV1(old)

        assertEquals(2, out["ai_layout"])
        assertEquals("commandcode", out["provider"])
        assertEquals("v1:aXY=:Y2lwaGVy", out["p.commandcode.key_ct"])
        assertEquals("9f2c", out["p.commandcode.key_hint"])
        assertEquals("api.commandcode.ai", out["p.commandcode.key_host"])
        assertEquals(glm, out["p.commandcode.model"])
        assertEquals(1_789_000_000_000L, out["p.commandcode.test_ok_at"])
        // A preset never stores an address: it always uses the catalog's.
        assertFalse(out.containsKey("p.commandcode.base_url"))
        AiPrefs.V1_KEYS.forEach { assertFalse(it, out.containsKey(it)) }

        val s = AiSettingsStore.toSettings(out, AiProviders.all)
        assertEquals("commandcode", s.providerId)
        assertEquals("Command Code", s.providerName)
        assertEquals(commandCodeUrl, s.baseUrl)
        assertEquals(glm, s.model)
        assertTrue(s.hasKey)
        assertTrue(s.ready)
        assertTrue("the passed test must still count", s.testedOk)
        assertFalse(s.keyForOtherHost)
    }

    @Test
    fun aChosenModelAndAnExplicitAddressAreKept() {
        val old = mapOf(
            "base_url" to commandCodeUrl,
            "model" to "xiaomi/mimo-v2.6-pro",
            "key_ct" to "v1:a:b",
            "key_hint" to "abcd",
            "key_host" to "api.commandcode.ai",
        )
        val out = AiPrefsMigration.migrateV1(old)
        assertEquals("commandcode", out["provider"])
        assertEquals("xiaomi/mimo-v2.6-pro", out["p.commandcode.model"])
        assertFalse(out.containsKey("p.commandcode.test_ok_at"))
        assertFalse(AiSettingsStore.toSettings(out, AiProviders.all).testedOk)
    }

    @Test
    fun anOpenRouterAddressBecomesTheOpenRouterPreset() {
        val old = mapOf(
            "base_url" to "https://openrouter.ai/api/v1",
            "model" to "openai/gpt-6-luna",
            "key_ct" to "v1:a:b",
            "key_hint" to "wxyz",
            "key_host" to "openrouter.ai",
            "last_test_ok_at" to 5L,
            "last_test_sig" to "https://openrouter.ai/api/v1|openai/gpt-6-luna|wxyz",
        )
        val out = AiPrefsMigration.migrateV1(old)
        assertEquals("openrouter", out["provider"])
        assertEquals("v1:a:b", out["p.openrouter.key_ct"])
        assertEquals("openrouter.ai", out["p.openrouter.key_host"])
        val s = AiSettingsStore.toSettings(out, AiProviders.all)
        assertEquals("OpenRouter", s.providerName)
        assertTrue(s.ready)
        assertTrue(s.testedOk)
        // The key reached the provider it was pasted for and no other.
        assertEquals(setOf("openrouter"), s.keys.keys)
    }

    @Test
    fun anUnknownHostBecomesCustomAndKeepsItsAddress() {
        val url = "https://gateway.example.org/openai/v1"
        val old = mapOf(
            "base_url" to url,
            "model" to "my-model",
            "key_ct" to "v1:a:b",
            "key_hint" to "1234",
            "key_host" to "gateway.example.org",
            "last_test_ok_at" to 7L,
            "last_test_sig" to "$url|my-model|1234",
        )
        val out = AiPrefsMigration.migrateV1(old)
        assertEquals("custom", out["provider"])
        assertEquals(url, out["p.custom.base_url"])
        val s = AiSettingsStore.toSettings(out, AiProviders.all)
        assertTrue(s.isCustom)
        assertEquals(url, s.baseUrl)
        assertEquals("gateway.example.org", s.host)
        assertTrue(s.ready)
        assertTrue(s.testedOk)
        assertFalse(s.modelKnown)
        assertFalse(s.modelSeesImages)
    }

    @Test
    fun aKeySavedForAnotherHostStaysUnusable() {
        // 1.x: the address was changed after the key was pasted, so the key read as "no key".
        val old = mapOf(
            "base_url" to "https://openrouter.ai/api/v1",
            "key_ct" to "v1:a:b",
            "key_hint" to "9f2c",
            "key_host" to "api.commandcode.ai",
        )
        val s = AiSettingsStore.toSettings(AiPrefsMigration.migrateV1(old), AiProviders.all)
        assertEquals("openrouter", s.providerId)
        assertFalse(s.hasKey)
        assertFalse(s.ready)
        assertTrue(s.keyForOtherHost)
        assertEquals("api.commandcode.ai", s.keyHost)
    }

    @Test
    fun aStaleTestStaysStale() {
        val old = mapOf(
            "model" to glm,
            "key_ct" to "v1:a:b",
            "key_hint" to "9f2c",
            "key_host" to "api.commandcode.ai",
            "last_test_ok_at" to 9L,
            // Tested with another model, so 1.x already showed "not tested yet".
            "last_test_sig" to "$commandCodeUrl|some/other|9f2c",
        )
        val s = AiSettingsStore.toSettings(AiPrefsMigration.migrateV1(old), AiProviders.all)
        assertTrue(s.ready)
        assertFalse(s.testedOk)
    }

    @Test
    fun aFreshInstallHasNoProviderChosen() {
        val out = AiPrefsMigration.migrateV1(emptyMap(), upgradedInstall = false)
        assertEquals(mapOf("ai_layout" to 2, "provider" to ""), out)
        val s = AiSettingsStore.toSettings(out, AiProviders.all)
        assertFalse(s.providerChosen)
        assertFalse(s.ready)
        assertEquals("", s.host)
        assertTrue(s.sendPhotos)
    }

    @Test
    fun anUpgradedInstallThatNeverSavedAKeyKeepsCommandCode() {
        val out = AiPrefsMigration.migrateV1(emptyMap(), upgradedInstall = true)
        assertEquals("commandcode", out["provider"])
        assertEquals(glm, out["p.commandcode.model"])
        val s = AiSettingsStore.toSettings(out, AiProviders.all)
        assertTrue(s.isCommandCode)
        assertFalse(s.hasKey)
        assertEquals("api.commandcode.ai", s.host)
    }

    // ------------------------------------------------------- reading layout 2

    @Test
    fun eachProviderKeepsItsOwnKeyModelAndTest() {
        val prefs = mapOf<String, Any?>(
            "ai_layout" to 2,
            "provider" to "anthropic",
            "p.anthropic.key_ct" to "v1:a:b",
            "p.anthropic.key_hint" to "aaaa",
            "p.anthropic.key_host" to "api.anthropic.com",
            "p.commandcode.key_ct" to "v1:c:d",
            "p.commandcode.key_hint" to "cccc",
            "p.commandcode.key_host" to "api.commandcode.ai",
            "p.commandcode.model" to "xiaomi/mimo-v2.6-pro",
            "p.commandcode.test_ok_at" to 3L,
        )
        val a = AiSettingsStore.toSettings(prefs, AiProviders.all)
        assertEquals(ProviderApi.AnthropicMessages, a.api)
        assertEquals(AuthStyle.XApiKey, a.auth)
        assertEquals("aaaa", a.keyHint)
        // No model chosen yet: the catalog's default.
        assertEquals(AiProviders.byId("anthropic")!!.defaultModel!!.id, a.model)
        assertTrue(a.ready)
        assertNull(a.lastTestOkAt)
        assertTrue(a.modelSeesImages)
        assertEquals(setOf("anthropic", "commandcode"), a.keys.keys)

        val c = AiSettingsStore.toSettings(prefs + ("provider" to "commandcode"), AiProviders.all)
        assertEquals("cccc", c.keyHint)
        assertEquals("xiaomi/mimo-v2.6-pro", c.model)
        assertEquals(3L, c.lastTestOkAt)
        // Different provider, different signature: one provider's passed test never vouches for another.
        assertFalse(a.signature == c.signature)
    }

    @Test
    fun theVisionAnswerOnlyAppliesToModelsTheCatalogDoesNotKnow() {
        val base = mapOf<String, Any?>(
            "provider" to "groq",
            "p.groq.key_ct" to "v1:a:b",
            "p.groq.key_host" to "api.groq.com",
        )
        // The catalog says this Groq model is text only; "yes" cannot override it.
        val known = AiSettingsStore.toSettings(base + mapOf("p.groq.model" to "openai/gpt-oss-120b", "p.groq.vision" to "yes"), AiProviders.all)
        assertTrue(known.modelKnown)
        assertFalse(known.modelSeesImages)

        val unknownYes = AiSettingsStore.toSettings(base + mapOf("p.groq.model" to "new/model", "p.groq.vision" to "yes"), AiProviders.all)
        assertFalse(unknownYes.modelKnown)
        assertTrue(unknownYes.modelSeesImages)
        val unknownUnset = AiSettingsStore.toSettings(base + mapOf("p.groq.model" to "new/model"), AiProviders.all)
        assertFalse(unknownUnset.modelSeesImages)
        assertNull(unknownUnset.visionOverride)
    }

    @Test
    fun sendPhotosDefaultsOnAndCanBeTurnedOff() {
        assertTrue(AiSettingsStore.toSettings(mapOf("provider" to "openai"), AiProviders.all).sendPhotos)
        assertFalse(AiSettingsStore.toSettings(mapOf("provider" to "openai", "send_photos" to false), AiProviders.all).sendPhotos)
    }

    @Test
    fun anUnknownProviderIdReadsAsNoneChosen() {
        val s = AiSettingsStore.toSettings(mapOf("provider" to "ollama", "p.ollama.key_ct" to "v1:a:b"), AiProviders.all)
        assertFalse(s.providerChosen)
        assertFalse(s.ready)
        assertTrue(s.keys.isEmpty())
    }

    @Test
    fun customNeedsItsOwnAddressBeforeAKeyCanBelongToIt() {
        val noAddress = AiSettingsStore.toSettings(mapOf("provider" to "custom"), AiProviders.all)
        assertEquals("", noAddress.baseUrl)
        assertEquals("", noAddress.host)
        assertFalse(noAddress.ready)

        val moved = AiSettingsStore.toSettings(
            mapOf(
                "provider" to "custom",
                "p.custom.base_url" to "https://new.example.com/v1",
                "p.custom.model" to "m",
                "p.custom.key_ct" to "v1:a:b",
                "p.custom.key_host" to "old.example.com",
            ),
            AiProviders.all,
        )
        // The address moved after the key was saved: the key is never sent to the new host.
        assertFalse(moved.hasKey)
        assertTrue(moved.keyForOtherHost)
        assertEquals("https://new.example.com/v1", moved.customBaseUrl)
    }
}
