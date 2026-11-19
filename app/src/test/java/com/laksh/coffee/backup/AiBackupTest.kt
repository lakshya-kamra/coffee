package com.laksh.coffee.backup

import com.laksh.coffee.data.ai.AiSettingsStore
import com.laksh.coffee.domain.ai.AiProviders
import com.laksh.coffee.domain.backup.AiDto
import com.laksh.coffee.domain.backup.AiProviderDto
import com.laksh.coffee.domain.backup.BackupCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which provider and model travel in a backup. The key, its hint and its host never do. */
class AiBackupTest {

    private val secretCipher = "v1:aXY=:c2VjcmV0LWNpcGhlcnRleHQ="

    /** The coffee_ai store of someone with two providers set up, one of them custom. */
    private val prefs: Map<String, Any?> = mapOf(
        "ai_layout" to 2,
        "provider" to "anthropic",
        "send_photos" to false,
        "p.anthropic.model" to "claude-haiku-5-5",
        "p.anthropic.key_ct" to secretCipher,
        "p.anthropic.key_hint" to "9f2c",
        "p.anthropic.key_host" to "api.anthropic.com",
        "p.anthropic.test_ok_at" to 1_789_000_000_000L,
        "p.anthropic.test_sig" to "anthropic|https://api.anthropic.com/v1|claude-haiku-5-5|9f2c",
        "p.custom.base_url" to "https://llm.example.com/v1",
        "p.custom.model" to "my-model",
        "p.custom.vision" to "no",
        "p.custom.key_ct" to secretCipher,
        "p.custom.key_hint" to "ab12",
        "p.custom.key_host" to "llm.example.com",
    )

    @Test
    fun theBackupHoldsChoicesOnly() {
        val dto = AiSettingsStore.toBackup(prefs, AiProviders.all)
        assertEquals("anthropic", dto.activeProvider)
        assertFalse(dto.sendPhotos)
        assertEquals(
            listOf(
                AiProviderDto("anthropic", null, "claude-haiku-5-5", null),
                AiProviderDto("custom", "https://llm.example.com/v1", "my-model", false),
            ),
            dto.providers.sortedBy { it.id },
        )
        // Written out, nothing of a key is in the text: not the ciphertext, the hint or its host binding.
        val json = BackupCodec.encode(AiDto.serializer(), dto)
        for (secret in listOf(secretCipher, "9f2c", "ab12", "key_ct", "key_hint", "key_host", "test_sig")) {
            assertFalse(secret, json.contains(secret))
        }
        assertFalse(json.contains("key", ignoreCase = true))
    }

    @Test
    fun aFreshInstallBacksUpNoProvider() {
        val dto = AiSettingsStore.toBackup(mapOf("ai_layout" to 2), AiProviders.all)
        assertNull(dto.activeProvider)
        assertTrue(dto.sendPhotos)
        assertTrue(dto.providers.isEmpty())
    }

    @Test
    fun aProviderInUseWithNoModelSavedTravelsWithItsDefaultModel() {
        val dto = AiSettingsStore.toBackup(mapOf("provider" to "commandcode"), AiProviders.all)
        val row = dto.providers.single()
        assertEquals("commandcode", row.id)
        assertEquals(AiProviders.byId("commandcode")!!.defaultModel?.id.orEmpty(), row.model)
    }

    @Test
    fun restoringBringsTheChoicesAndTouchesNoKey() {
        val dto = AiSettingsStore.toBackup(prefs, AiProviders.all)
        val edits = AiSettingsStore.restoreEdits(emptyMap(), dto, AiProviders.all)
        assertEquals("anthropic", edits.set["provider"])
        assertEquals(false, edits.set["send_photos"])
        assertEquals("claude-haiku-5-5", edits.set["p.anthropic.model"])
        assertEquals("https://llm.example.com/v1", edits.set["p.custom.base_url"])
        assertEquals("my-model", edits.set["p.custom.model"])
        assertEquals("no", edits.set["p.custom.vision"])
        (edits.set.keys + edits.remove).forEach { name ->
            assertFalse(name, name.endsWith(".key_ct") || name.endsWith(".key_hint") || name.endsWith(".key_host"))
        }
    }

    @Test
    fun aChangedCustomAddressForgetsItsPassedTest() {
        val here = mapOf<String, Any?>("p.custom.base_url" to "https://old.example.com/v1", "p.custom.test_ok_at" to 5L)
        val dto = AiDto("custom", true, listOf(AiProviderDto("custom", "https://llm.example.com/v1", "my-model", null)))
        val edits = AiSettingsStore.restoreEdits(here, dto, AiProviders.all)
        assertTrue("p.custom.test_ok_at" in edits.remove && "p.custom.test_sig" in edits.remove)

        // The same address again: the test still counts.
        val same = AiSettingsStore.restoreEdits(mapOf("p.custom.base_url" to "https://llm.example.com/v1"), dto, AiProviders.all)
        assertFalse("p.custom.test_ok_at" in same.remove)
    }

    @Test
    fun whatThisPhoneCannotUseIsSkipped() {
        val dto = AiDto(
            activeProvider = "ollama",
            sendPhotos = true,
            providers = listOf(
                // Runs on a computer: the web app offers it, the phone does not.
                AiProviderDto("ollama", null, "llama", null),
                AiProviderDto("no-such-provider", null, "x", true),
                // A custom address must pass the same checks as one typed here.
                AiProviderDto("custom", "http://192.168.1.5:11434/v1", "local", null),
                AiProviderDto("anthropic", "https://evil.example.com/v1", "   ", null),
            ),
        )
        val edits = AiSettingsStore.restoreEdits(mapOf("provider" to "commandcode"), dto, AiProviders.all)
        assertFalse("an unknown provider is never made active", edits.set.containsKey("provider"))
        assertFalse(edits.set.keys.any { it.startsWith("p.ollama.") || it.startsWith("p.no-such-provider.") })
        assertFalse("a plain-http address is refused", edits.set.containsKey("p.custom.base_url"))
        assertEquals("local", edits.set["p.custom.model"])
        // A preset's address always comes from the catalog, never from a file; a blank model is not a model.
        assertFalse(edits.set.keys.any { it == "p.anthropic.base_url" || it == "p.anthropic.model" })
    }

    @Test
    fun anAddressFromAFileNeverInheritsTheKeySavedForAnother() {
        // Same host, another account's path: the host binding alone would let the key through.
        val here = mapOf<String, Any?>(
            "p.custom.base_url" to "https://gateway.example.com/v1/mine/compat",
            "p.custom.key_ct" to secretCipher,
            "p.custom.key_hint" to "ab12",
            "p.custom.key_host" to "gateway.example.com",
        )
        val theirs = AiDto("custom", true, listOf(AiProviderDto("custom", "https://gateway.example.com/v1/theirs/compat", "m", null)))
        val edits = AiSettingsStore.restoreEdits(here, theirs, AiProviders.all)
        assertEquals("https://gateway.example.com/v1/theirs/compat", edits.set["p.custom.base_url"])
        assertTrue(edits.remove.containsAll(listOf("p.custom.key_ct", "p.custom.key_hint", "p.custom.key_host")))
        // Another port on the same host is another server too.
        val port = AiDto("custom", true, listOf(AiProviderDto("custom", "https://gateway.example.com:8443/v1/mine/compat", "m", null)))
        assertTrue("p.custom.key_ct" in AiSettingsStore.restoreEdits(here, port, AiProviders.all).remove)

        // The same address: the key stays where it is. Nothing is ever written to a key entry.
        val mine = AiDto("custom", true, listOf(AiProviderDto("custom", "https://gateway.example.com/v1/mine/compat", "m", null)))
        val same = AiSettingsStore.restoreEdits(here, mine, AiProviders.all)
        assertFalse(same.remove.any { it.contains(".key_") })
        listOf(edits, same).forEach { e -> assertFalse(e.set.keys.any { it.contains(".key_") }) }
        // A preset's key is never touched by a file: its address comes from the catalog.
        val preset = AiSettingsStore.restoreEdits(prefs, AiSettingsStore.toBackup(prefs, AiProviders.all), AiProviders.all)
        assertFalse(preset.remove.any { it.contains(".key_") })
    }

    @Test
    fun thePreviewCanSayWhichCustomAddressAFileWouldSet() {
        val dto = AiDto("custom", true, listOf(AiProviderDto("custom", "https://LLM.example.com/v1/", "m", null)))
        assertEquals(
            AiSettingsStore.restoreEdits(emptyMap(), dto, AiProviders.all).set["p.custom.base_url"],
            AiSettingsStore.customAddressIn(dto),
        )
        // One this phone would refuse is not announced, because it would not be set.
        assertNull(AiSettingsStore.customAddressIn(AiDto("custom", true, listOf(AiProviderDto("custom", "http://192.168.1.5:11434/v1", "m", null)))))
        assertNull(AiSettingsStore.customAddressIn(AiDto("anthropic", true, listOf(AiProviderDto("anthropic", null, "m", null)))))
    }
}
