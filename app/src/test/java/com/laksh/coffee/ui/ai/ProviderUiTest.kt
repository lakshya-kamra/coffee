package com.laksh.coffee.ui.ai

import com.laksh.coffee.data.ai.AiSettings
import com.laksh.coffee.data.ai.AnalysisRepository
import com.laksh.coffee.data.ai.AnalysisTarget
import com.laksh.coffee.data.ai.KeyBadge
import com.laksh.coffee.data.ai.StaleReason
import com.laksh.coffee.data.ai.StoredAnalysis
import com.laksh.coffee.data.ai.UsageLine
import com.laksh.coffee.domain.ai.AiProviders
import com.laksh.coffee.domain.ai.AnalysisPrompt
import com.laksh.coffee.ui.components.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words the provider list, the headline and the stale line use. A colour never appears without one. */
class ProviderUiTest {

    // ----------------------------------------------------------- provider list

    @Test
    fun `each provider row says in a word whether a key is saved for it`() {
        val settings = AiSettings(
            providerId = "anthropic",
            keys = mapOf(
                "anthropic" to KeyBadge("aaaa", "api.anthropic.com"),
                // Saved while this preset pointed somewhere else: never sent to today's address.
                "openrouter" to KeyBadge("bbbb", "old.openrouter.example"),
                "custom" to KeyBadge("cccc", "gateway.example.org"),
            ),
            customBaseUrl = "https://gateway.example.org/v1",
        )
        val rows = providerRows(settings, AiProviders.all).associateBy { it.id }
        assertEquals(AiProviders.all.map { it.id }, providerRows(settings, AiProviders.all).map { it.id })

        assertEquals("Key saved", rows.getValue("anthropic").word)
        assertEquals(Status.Good, rows.getValue("anthropic").status)
        assertTrue(rows.getValue("anthropic").selected)

        assertEquals("Key for another address", rows.getValue("openrouter").word)
        assertEquals(Status.Attention, rows.getValue("openrouter").status)

        assertEquals("No key", rows.getValue("openai").word)
        assertEquals(Status.Neutral, rows.getValue("openai").status)
        assertFalse(rows.getValue("openai").selected)

        assertEquals("Key saved", rows.getValue("custom").word)
        // Move the custom address and its key no longer belongs there.
        val moved = providerRows(settings.copy(customBaseUrl = "https://elsewhere.example.org/v1"), AiProviders.all)
        assertEquals("Key for another address", moved.first { it.id == "custom" }.word)
    }

    @Test
    fun `a fresh install has nothing selected and asks for a provider`() {
        val ui = AiSettingsUi(settings = AiSettings(), providers = providerRows(AiSettings(), AiProviders.all))
        assertTrue(ui.providers.none { it.selected })
        assertEquals("Choose a provider", ui.headline.lead)
        assertEquals(Status.Neutral, ui.headline.status)
        assertFalse(ui.canBrowse)
        assertEquals("", ui.keyTargetHost)
    }

    @Test
    fun `the headline names the model and the provider once a key is saved`() {
        val anthropic = AiProviders.byId("anthropic")!!
        val model = anthropic.defaultModel!!
        val saved = AiSettings(
            providerId = anthropic.id,
            providerName = anthropic.name,
            api = anthropic.api,
            auth = anthropic.auth,
            baseUrl = anthropic.baseUrl,
            model = model.id,
            hasKey = true,
            keyHint = "aaaa",
            keyHost = anthropic.host,
        )
        val untested = AiSettingsUi(settings = saved, provider = anthropic)
        assertEquals("Key saved", untested.headline.lead)
        assertEquals("not tested yet · ${model.label} via ${anthropic.name}", untested.headline.detail)
        assertNull(untested.testedNote)

        val tested = AiSettingsUi(settings = saved.copy(lastTestOkAt = 1L, lastTestSignature = saved.signature), provider = anthropic)
        assertEquals("Ready", tested.headline.lead)
        assertEquals(Status.Good, tested.headline.status)
        assertEquals("${model.label} via ${anthropic.name}", tested.headline.detail)
        assertTrue(tested.testedNote!!.startsWith("Tested"))
        assertTrue(tested.canBrowse)

        // Another model was picked since: the old test no longer vouches for it.
        val changed = AiSettingsUi(settings = saved.copy(model = "claude-sonnet-5-5", lastTestOkAt = 1L, lastTestSignature = saved.signature), provider = anthropic)
        assertEquals("Key saved", changed.headline.lead)
    }

    @Test
    fun `custom asks for its address before anything else`() {
        val custom = AiProviders.byId(AiProviders.CUSTOM)!!
        val ui = AiSettingsUi(settings = AiSettings(providerId = custom.id, providerName = custom.name), provider = custom)
        assertEquals("Needs an address", ui.headline.lead)
        assertTrue(ui.canBrowse)
        // A valid address being typed is the host a key pasted now would be bound to.
        val typing = ui.copy(baseUrlDraft = "gateway.example.org/v1", baseUrlEdited = true)
        assertEquals("gateway.example.org", typing.keyTargetHost)
        assertEquals("", ui.copy(baseUrlDraft = "http://plain.example.org", baseUrlEdited = true).keyTargetHost)
    }

    @Test
    fun `the settings state never prints a key`() {
        val ui = AiSettingsUi(keyDraft = "sk-live-secret-123456", revealedKey = "sk-live-secret-654321")
        assertFalse(ui.toString().contains("secret"))
    }

    @Test
    fun `providers a phone cannot reach get one plain line`() {
        assertEquals(
            "Ollama and LM Studio run on a computer, not on this phone. To reach one, use Custom with an https URL.",
            leftOutNote(listOf("Ollama (on this computer)", "LM Studio (on this computer)")),
        )
        assertEquals(
            "Ollama runs on a computer, not on this phone. To reach one, use Custom with an https URL.",
            leftOutNote(listOf("Ollama (on this computer)")),
        )
        assertEquals("", leftOutNote(emptyList()))
        // The real catalog.
        assertTrue(leftOutNote(AiProviders.leftOut).startsWith("Ollama and LM Studio run on a computer"))
    }

    // --------------------------------------------------------------- no key

    @Test
    fun `the no-key copy names the provider or asks for one`() {
        assertEquals("Choose a provider, add your own key", noKeyLead(host = "", keyHost = ""))
        assertEquals("Add your Anthropic key", noKeyLead("api.anthropic.com", "", "Anthropic (Claude)"))
        assertEquals("Add your Command Code key", noKeyLead("api.commandcode.ai", "", "Command Code"))
        assertEquals("Add your key for gateway.example.org", noKeyLead("gateway.example.org", ""))
        assertEquals(
            "Your key is saved for old.example.org. Paste one for new.example.org",
            noKeyLead("new.example.org", "old.example.org", "Custom (OpenAI-compatible)"),
        )
    }

    // ---------------------------------------------------------------- stale

    private fun stored(promptVersion: Int = AnalysisPrompt.VERSION, origin: String = AnalysisRepository.ORIGIN, hash: String = "s2:abc") =
        StoredAnalysis(
            id = 1,
            target = AnalysisTarget.Brew(1),
            createdAt = 0,
            model = "m",
            modelLabel = "M",
            host = "h.example",
            brew = null,
            trend = null,
            grindMove = null,
            valueMove = null,
            localWarnings = emptyList(),
            rawText = "",
            parsed = false,
            flags = emptyList(),
            usage = UsageLine(null, null, null, 0),
            inputHash = hash,
            brewCount = 1,
            newestBrewAt = 0,
            promptVersion = promptVersion,
            providerId = "custom",
            providerName = "",
            photoCount = 0,
            origin = origin,
        )

    @Test
    fun `an answer is current only when prompt, device and data all match`() {
        assertNull(stored().staleReason("s2:abc"))
        assertEquals(StaleReason.Edited, stored().staleReason("s2:changed"))
        // A blocked or failed preview says nothing about the data.
        assertNull(stored().staleReason(null))
    }

    @Test
    fun `an older prompt is never called an edit`() {
        // Every analysis made before version 2 hashes differently; "brew edited since" would be false.
        assertEquals(StaleReason.OlderPrompt, stored(promptVersion = 1).staleReason("changed"))
        assertEquals(StaleReason.OlderPrompt, stored(promptVersion = 1).staleReason(null))
        assertEquals(StaleReason.OlderPrompt, stored(promptVersion = 1).staleReason("s2:abc"))
    }

    @Test
    fun `a hash made from row ids reads as an earlier version, never as an edit`() {
        // What 2.0.0 stored: the right prompt version, but a hash that changes on every restore.
        val old = stored(hash = "9f2c1a0b3d4e5f60")
        assertEquals(StaleReason.OlderPrompt, old.staleReason("s2:abc"))
        assertEquals(StaleReason.OlderPrompt, old.staleReason(null))
        // Even if today's hash were somehow the same text, the two were not made the same way.
        assertEquals(StaleReason.OlderPrompt, old.staleReason("9f2c1a0b3d4e5f60"))
    }

    @Test
    fun `an answer from the web app says so before anything else`() {
        assertEquals(StaleReason.OtherDevice, stored(origin = "web").staleReason("s2:abc"))
        assertEquals(StaleReason.OtherDevice, stored(origin = "web", promptVersion = 1).staleReason(null))
    }

    @Test
    fun `each reason has its own words`() {
        val edited = "Brew edited since · re-run for a fresh read"
        assertEquals(edited, staleText(StaleReason.Edited, edited))
        assertEquals(
            "Made by an older version of the analysis · re-run to use your grinder's scale",
            staleText(StaleReason.OlderPrompt, edited),
        )
        assertEquals("Made on another device · re-run here for a fresh read", staleText(StaleReason.OtherDevice, edited))
    }
}
