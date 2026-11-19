package com.laksh.coffee.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prices and labels come from shared/catalog/providers.json; nothing is hard-coded in the app. */
class ModelCatalogTest {

    private val glm = "z-ai/glm-5.3-flash"

    @Test
    fun formatsFractionsOfACent() {
        assertEquals("0.11¢", ModelCatalog.formatUsd(1107))
        assertEquals("12.3¢", ModelCatalog.formatUsd(123_456))
        assertEquals("\$2.50", ModelCatalog.formatUsd(2_500_000))
    }

    @Test
    fun costIsMicroDollarsPerToken() {
        assertEquals(1107L, ModelCatalog.costMicroUsd(Price(0.15, 0.5), 3180, 1260))
    }

    @Test
    fun priceComesFromTheCatalogForAnyProviderThatListsOne() {
        assertEquals(Price(0.15, 0.50), ModelCatalog.price(AiProviders.COMMANDCODE, glm))
        // The same model id is priced per provider, not globally.
        assertNotNull(ModelCatalog.price("openrouter", glm))
        assertNotNull(ModelCatalog.price("anthropic", "claude-haiku-5-5"))
        assertNull(ModelCatalog.price(AiProviders.COMMANDCODE, "some/other-model"))
        assertNull(ModelCatalog.price(AiProviders.CUSTOM, glm))
        assertNull(ModelCatalog.price(null, glm))
        assertNull(ModelCatalog.price("", glm))
    }

    @Test
    fun estimateAddsTheImageHeuristicToThePromptSide() {
        assertEquals(1107L, ModelCatalog.estimateMicroUsd(AiProviders.COMMANDCODE, glm, 3180, 1260))
        // Two photos at about 1,100 prompt tokens each (app heuristic).
        assertEquals(
            ModelCatalog.costMicroUsd(Price(0.15, 0.50), 3180 + 2 * ModelCatalog.TOKENS_PER_IMAGE, 1260),
            ModelCatalog.estimateMicroUsd(AiProviders.COMMANDCODE, glm, 3180, 1260, images = 2),
        )
        assertNull(ModelCatalog.estimateMicroUsd(AiProviders.CUSTOM, glm, 3180, 1260))
    }

    @Test
    fun labelIsTheCatalogsElseTheIdAfterTheLastSlash() {
        assertEquals("GLM-5.3 Flash", ModelCatalog.label(AiProviders.COMMANDCODE, glm))
        assertEquals("Claude Haiku 5.5", ModelCatalog.label("anthropic", "claude-haiku-5-5"))
        assertEquals("gpt-5-mini", ModelCatalog.label(AiProviders.CUSTOM, "openai/gpt-5-mini"))
        assertEquals("gpt-5-mini", ModelCatalog.label(null, "openai/gpt-5-mini"))
        assertEquals("3,180", ModelCatalog.formatTokens(3180))
    }

    @Test
    fun visionIsKnownOnlyForCatalogModels() {
        assertEquals(true, ModelCatalog.seesImages("anthropic", "claude-haiku-5-5"))
        assertEquals(false, ModelCatalog.seesImages("groq", "openai/gpt-oss-120b"))
        assertNull(ModelCatalog.seesImages("groq", "something-new"))
        assertNull(ModelCatalog.seesImages(AiProviders.CUSTOM, glm))
    }

    @Test
    fun pricesAlwaysShowAtLeastTwoDecimals() {
        assertEquals("\$0.30", ModelCatalog.usd(0.3))
        assertEquals("\$0.435", ModelCatalog.usd(0.435))
        assertEquals("\$2.00", ModelCatalog.usd(2.0))
        assertEquals("\$0.15 in · \$0.50 out per 1M", ModelCatalog.priceLine(Price(0.15, 0.5)))
    }

    @Test
    fun everyCatalogDefaultModelHasALabel() {
        AiProviders.all.filter { !it.isCustom }.forEach { p ->
            val m = p.defaultModel
            assertNotNull("${p.id} has no models", m)
            assertTrue(p.id, m!!.label.isNotBlank())
        }
    }
}
