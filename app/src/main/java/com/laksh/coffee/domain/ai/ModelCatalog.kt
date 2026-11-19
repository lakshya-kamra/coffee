package com.laksh.coffee.domain.ai

import java.math.BigDecimal
import java.util.Locale
import kotlin.math.roundToLong

/** USD per million tokens. */
data class Price(val inputPerMTokUsd: Double, val outputPerMTokUsd: Double)

/**
 * Prices and labels for the models in shared/catalog/providers.json. Nothing is hard-coded
 * here: a model the catalog doesn't list shows token counts without a cost.
 */
object ModelCatalog {
    const val COMMANDCODE_HOST = "api.commandcode.ai"

    /** App heuristic, not a provider figure: an attached photo costs about this many prompt tokens. */
    const val TOKENS_PER_IMAGE = 1100

    fun model(providerId: String?, modelId: String): AiModel? = AiProviders.byId(providerId)?.model(modelId)

    /** The catalog price, when it lists both the input and the output price. */
    fun price(providerId: String?, modelId: String): Price? = model(providerId, modelId)?.price

    /** The catalog label, else the id after the last "/". */
    fun label(providerId: String?, modelId: String): String = model(providerId, modelId)?.label ?: shortId(modelId)

    fun shortId(modelId: String): String = modelId.substringAfterLast('/')

    /** True or false when the catalog knows the model, null when it doesn't. */
    fun seesImages(providerId: String?, modelId: String): Boolean? = model(providerId, modelId)?.vision

    /** $1 per million tokens is exactly one micro-dollar per token. */
    fun costMicroUsd(price: Price, promptTokens: Int, completionTokens: Int): Long =
        (promptTokens * price.inputPerMTokUsd + completionTokens * price.outputPerMTokUsd).roundToLong()

    fun estimateMicroUsd(
        providerId: String?,
        modelId: String,
        promptTokens: Int,
        expectedCompletionTokens: Int,
        images: Int = 0,
    ): Long? = price(providerId, modelId)?.let {
        costMicroUsd(it, promptTokens + images * TOKENS_PER_IMAGE, expectedCompletionTokens)
    }

    /** "0.11¢", "1.4¢" or "$2.50". Always Locale.US, so a comma never turns into a decimal point. */
    fun formatUsd(microUsd: Long): String = when {
        microUsd < 10_000 -> String.format(Locale.US, "%.2f¢", microUsd / 10_000.0)
        microUsd < 1_000_000 -> String.format(Locale.US, "%.1f¢", microUsd / 10_000.0)
        else -> String.format(Locale.US, "\$%.2f", microUsd / 1_000_000.0)
    }

    fun formatTokens(n: Int): String = String.format(Locale.US, "%,d", n)

    /** "$0.30", "$0.435": at least two decimals, so prices line up and never read as "$0.3". */
    fun usd(v: Double): String {
        val d = BigDecimal.valueOf(v).stripTrailingZeros()
        return "\$" + (if (d.scale() < 2) d.setScale(2) else d).toPlainString()
    }

    /** "$0.15 in · $0.50 out per 1M". */
    fun priceLine(price: Price): String = "${usd(price.inputPerMTokUsd)} in · ${usd(price.outputPerMTokUsd)} out per 1M"
}
