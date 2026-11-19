package com.laksh.coffee.domain

import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.catalog.LegacyIds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** How a brewer makes coffee. Ids match `method` in shared/catalog/brewers.json and the grinder range keys. */
enum class BrewMethod(val id: String, val label: String) {
    Espresso("espresso", "Espresso"),
    Moka("moka", "Moka pot"),
    AeroPress("aeropress", "AeroPress"),
    PourOver("pourover", "Pour-over"),
    FrenchPress("frenchPress", "French press"),
    ColdBrew("coldBrew", "Cold brew"),
    Siphon("siphon", "Siphon"),
    Turkish("turkish", "Turkish / cezve");

    /** Runs water through a bed at a filter ratio. Same set the 1.x data-quality rules used. */
    val isFilterStyle: Boolean get() = this != Espresso && this != Moka && this != ColdBrew && this != Turkish

    companion object {
        fun of(id: String?): BrewMethod = entries.firstOrNull { it.id == id } ?: PourOver
        fun ofOrNull(id: String?): BrewMethod? = entries.firstOrNull { it.id == id }
    }
}

/** The nine drawings BrewerIcon knows. Names match the 1.x enum so CoffeeArt changes only its `when` subject. */
enum class BrewerArt { V60, Chemex, Kalita, Switch, AeroPress, FrenchPress, Moka, Espresso, ColdBrew }

/**
 * A brewer from shared/catalog/brewers.json. Logs and saved recipes store its [id]; 1.x data
 * stored the enum name or label, which [Brewers.resolve] still understands.
 */
@Serializable(with = BrewerSerializer::class)
data class Brewer(
    val id: String,
    val label: String,
    val brand: String = "",
    val category: String = "other",
    val method: BrewMethod = BrewMethod.PourOver,
    val filter: String = "paper",
    val notes: String = "",
) {
    val art: BrewerArt get() = BrewerArts.of(this)

    /** "V60", "Kalita", "French press"… : 1.x short names for legacy ids, else label minus a leading brand, at most 14 chars. */
    val shortName: String
        get() = LEGACY_SHORT[id] ?: run {
            val bare = if (brand.isNotBlank() && label.startsWith(brand, ignoreCase = true)) {
                label.substring(brand.length).trim().ifBlank { label }
            } else {
                label
            }
            if (bare.length <= 14) bare else bare.take(13).trimEnd() + "…"
        }

    /** Brewers that take the same recipes: art for pour-over (V60-01/02/03 are one family), method otherwise. */
    val family: String get() = if (method == BrewMethod.PourOver) "pourover:${art.name}" else method.id

    companion object {
        fun unknown(raw: String): Brewer = Brewer(id = "other", label = raw.ifBlank { "Other" })

        private val LEGACY_SHORT = mapOf(
            "hario-v60-02" to "V60", "chemex" to "Chemex", "kalita-wave-185" to "Kalita",
            "hario-switch" to "Switch", "aeropress" to "AeroPress", "french-press" to "French press",
            "moka-pot" to "Moka", "espresso-machine" to "Espresso", "cold-brew-jar" to "Cold brew",
        )
    }
}

object BrewerArts {
    fun of(b: Brewer): BrewerArt = when {
        b.id == "chemex" -> BrewerArt.Chemex
        b.method == BrewMethod.Moka || b.method == BrewMethod.Turkish -> BrewerArt.Moka
        b.method == BrewMethod.Espresso -> BrewerArt.Espresso
        b.method == BrewMethod.ColdBrew -> BrewerArt.ColdBrew
        b.method == BrewMethod.AeroPress -> BrewerArt.AeroPress
        b.method == BrewMethod.FrenchPress -> BrewerArt.FrenchPress
        b.method == BrewMethod.Siphon -> BrewerArt.Chemex
        b.category == "flat-bottom" -> BrewerArt.Kalita
        b.category == "hybrid" || b.category == "immersion" -> BrewerArt.Switch
        else -> BrewerArt.V60
    }
}

object Brewers {
    val all: List<Brewer> get() = Catalog.brewers

    fun byId(id: String?): Brewer? = Catalog.brewer(id)

    /** id → legacy enum name/label → case-insensitive label → maker + label → null. Never throws. */
    fun resolveOrNull(raw: String?): Brewer? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        Catalog.brewer(s)?.let { return it }
        val legacy = LegacyIds.BREWER[s]
            ?: LegacyIds.BREWER.entries.firstOrNull { it.key.equals(s, ignoreCase = true) }?.value
        legacy?.let { id -> Catalog.brewer(id)?.let { return it } }
        return Catalog.brewers.firstOrNull { it.label.equals(s, ignoreCase = true) }
            // The words a 2.0 brew is saved with: maker and name.
            ?: Catalog.brewers.firstOrNull { CatalogDocs.brewerTitle(it).equals(s, ignoreCase = true) }
    }

    fun resolve(raw: String): Brewer = resolveOrNull(raw) ?: Brewer.unknown(raw)
}

/** Encodes the id string; decodes ids, 1.x enum names and labels alike. */
object BrewerSerializer : KSerializer<Brewer> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.laksh.coffee.Brewer", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Brewer) = encoder.encodeString(value.id)
    override fun deserialize(decoder: Decoder): Brewer = Brewers.resolve(decoder.decodeString())
}
