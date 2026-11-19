package com.laksh.coffee.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import com.laksh.coffee.core.oneDp
import kotlin.math.roundToInt

enum class StepKind(val label: String) {
    Prep("Prep"),
    Bloom("Bloom"),
    Pour("Pour"),
    Swirl("Swirl"),
    Stir("Stir"),
    Steep("Steep"),
    Press("Press"),
    Drawdown("Drawdown"),
    Serve("Serve");
}

/**
 * One instruction in a brew. [startSec]/[endSec] are offsets from the start of the timer.
 * [targetWaterG] is the CUMULATIVE water on the scale by the end of the step (null = no pour).
 */
@Serializable
data class RecipeStep(
    val kind: StepKind,
    val title: String,
    val detail: String,
    val startSec: Int,
    val endSec: Int,
    val targetWaterG: Int? = null,
) {
    val durationSec: Int get() = (endSec - startSec).coerceAtLeast(0)
}

@Serializable
data class RecipeSource(val who: String, val url: String = "", val note: String = "")

/**
 * A recipe. Built-ins come from shared/recipes.json (the same file the web app reads); user
 * recipes and edits are stored as this class's JSON. [brewer] serializes as its catalog id.
 */
@Serializable
data class Recipe(
    val id: String,
    val name: String,
    val author: String,
    /** Catalog-backed; serialized as the brewers.json id, and 1.x enum names still decode. */
    val brewer: Brewer,
    val doseG: Double,
    val waterG: Double,
    val grind: String,
    val tempC: Int,
    val blurb: String,
    /** Where these numbers come from, and how much to trust them. Shown in the UI. */
    val sourceNote: String,
    val prep: List<String>,
    val tips: List<String>,
    val steps: List<RecipeStep>,
    /** ARGB. Written as "#RRGGBB"; 1.x saved JSON wrote a number, which still reads. */
    @Serializable(with = ArgbHexSerializer::class) val accent: Long,
    val isBuiltIn: Boolean = true,
    /** published | adapted | community | custom */
    val fidelity: String = "",
    val sources: List<RecipeSource> = emptyList(),
    /** The recipe id this one was duplicated from. */
    val basedOn: String? = null,
) {
    val ratio: Double get() = if (doseG > 0) waterG / doseG else 0.0
    val ratioLabel: String get() = "1:" + oneDp(ratio)
    val totalSec: Int get() = steps.maxOfOrNull { it.endSec } ?: 0

    /** Proportionally rescale the whole recipe to a new dose, keeping timings intact. */
    fun scaledToDose(newDose: Double): Recipe {
        if (newDose <= 0.0 || doseG <= 0.0) return this
        val k = newDose / doseG
        return copy(
            doseG = (newDose * 10).roundToInt() / 10.0,
            waterG = (waterG * k).roundToInt().toDouble(),
            steps = steps.map { s -> s.copy(targetWaterG = s.targetWaterG?.let { (it * k).roundToInt() }) },
        )
    }

    /** Proportionally rescale to a target beverage/water weight. */
    fun scaledToWater(newWater: Double): Recipe =
        if (waterG <= 0.0) this else scaledToDose(doseG * (newWater / waterG))
}

fun Int.asClock(): String = "%d:%02d".format(this / 60, this % 60)

/** Writes "#RRGGBB" (or "#AARRGGBB" when not opaque); reads that or a plain ARGB number. */
object ArgbHexSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.laksh.coffee.ArgbHex", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) {
        val argb = value and 0xFFFFFFFFL
        val text = if (argb ushr 24 == 0xFFL) {
            "#%06X".format(argb and 0xFFFFFFL)
        } else {
            "#%08X".format(argb)
        }
        encoder.encodeString(text)
    }

    override fun deserialize(decoder: Decoder): Long {
        if (decoder is JsonDecoder) {
            val p = decoder.decodeJsonElement() as? JsonPrimitive ?: throw SerializationException("accent must be a colour")
            return if (p.isString) parse(p.content) else p.long
        }
        return parse(decoder.decodeString())
    }

    private fun parse(text: String): Long {
        val hex = text.trim().removePrefix("#")
        val v = hex.toLongOrNull(16) ?: throw SerializationException("Not a colour: $text")
        return when (hex.length) {
            6 -> 0xFF000000L or v
            8 -> v
            else -> throw SerializationException("Not a colour: $text")
        }
    }
}
