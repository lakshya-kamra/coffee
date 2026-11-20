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

/**
 * The drawings BrewerIcon knows. The first nine names match the 1.x enum; Drip, Cezve, Siphon and
 * Phin (v4) start as placeholders that reuse the nearest drawing until the screen packages draw them.
 */
enum class BrewerArt { V60, Chemex, Kalita, Switch, AeroPress, FrenchPress, Moka, Espresso, ColdBrew, Drip, Cezve, Siphon, Phin }

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

    /**
     * "V60", "Kalita", "French press"… : 1.x short names for legacy ids, a written short name where
     * the label would be cut mid-word ("Drip machine", not "Automatic dri…"), else the label minus
     * a leading brand, at most 14 chars. Web twin: brewerShortName.
     */
    val shortName: String
        get() = LEGACY_SHORT[id] ?: SHORT[id] ?: run {
            val bare = if (brand.isNotBlank() && label.startsWith(brand, ignoreCase = true)) {
                label.substring(brand.length).trim().ifBlank { label }
            } else {
                label
            }
            if (bare.length <= 14) bare else bare.take(13).trimEnd() + "…"
        }

    /** Brewers that take the same recipes: see [BrewerFamilies.of]. */
    val family: String get() = BrewerFamilies.of(this)

    companion object {
        fun unknown(raw: String): Brewer = Brewer(id = "other", label = raw.ifBlank { "Other" })

        private val LEGACY_SHORT = mapOf(
            "hario-v60-02" to "V60", "chemex" to "Chemex", "kalita-wave-185" to "Kalita",
            "hario-switch" to "Switch", "aeropress" to "AeroPress", "french-press" to "French press",
            "moka-pot" to "Moka", "espresso-machine" to "Espresso", "cold-brew-jar" to "Cold brew",
        )

        /** v4 brewers whose label does not fit 14 chars whole: the same words on the web. */
        private val SHORT = mapOf("drip-machine" to "Drip machine")
    }
}

object BrewerArts {
    fun of(b: Brewer): BrewerArt = when {
        b.id == "chemex" -> BrewerArt.Chemex
        b.id == "drip-machine" -> BrewerArt.Drip
        // Both are stacked metal gravity filters; the catalog files the phin under the AeroPress method.
        b.id == "phin" || b.id == "south-indian-filter" -> BrewerArt.Phin
        b.method == BrewMethod.Moka -> BrewerArt.Moka
        b.method == BrewMethod.Turkish -> BrewerArt.Cezve
        b.method == BrewMethod.Espresso -> BrewerArt.Espresso
        b.method == BrewMethod.ColdBrew -> BrewerArt.ColdBrew
        b.method == BrewMethod.AeroPress -> BrewerArt.AeroPress
        b.method == BrewMethod.FrenchPress -> BrewerArt.FrenchPress
        b.method == BrewMethod.Siphon -> BrewerArt.Siphon
        b.category == "flat-bottom" -> BrewerArt.Kalita
        b.category == "hybrid" || b.category == "immersion" -> BrewerArt.Switch
        else -> BrewerArt.V60
    }
}

/**
 * Which recipes a brewer takes (v4 §4.13). Drip machines, drip bags, phins and South Indian
 * filters have their own families and never borrow a V60 or AeroPress recipe; the Melodrip (an
 * accessory) and "other" get families that match nothing. Web twin: brewerFamily in brewer.ts.
 */
object BrewerFamilies {
    private val OWN = mapOf(
        "drip-machine" to "drip-machine",
        "drip-bag" to "drip-bag",
        "phin" to "phin",
        "south-indian-filter" to "south-indian-filter",
        "melodrip" to "accessory",
        "other" to "other",
    )

    /** Families that never match a recipe: [owned] leaves them out. */
    val NO_MATCH: Set<String> = setOf("accessory", "other")

    fun of(b: Brewer): String = OWN[b.id] ?: if (b.method == BrewMethod.PourOver) "pourover:${b.art.name}" else b.method.id

    internal fun hasOwnFamily(b: Brewer): Boolean = b.id in OWN

    /**
     * The machine pours, not you: an automatic drip machine (its own family). A running brew then
     * shows no pour readout, jug amount or "Pour to" target, only "The machine is brewing".
     * Web twin: machinePours in brewer.ts.
     */
    fun machinePours(b: Brewer?): Boolean = b != null && of(b) == "drip-machine"

    /** The families of the brewers someone owns, without the ones that match nothing. */
    fun owned(ownedBrewerIds: Collection<String>, byId: (String) -> Brewer? = Brewers::byId): Set<String> =
        ownedBrewerIds.mapNotNull { byId(it)?.let(::of) }.filterNot { it in NO_MATCH }.toSet()
}

/** How the water meets the coffee: it decides which advice and which review sections apply. */
enum class BrewStyle(val id: String) {
    Percolation("percolation"), Immersion("immersion"), Espresso("espresso");

    companion object {
        /** An unknown brewer is treated as percolation (a filter brew), as data quality does. */
        fun of(b: Brewer?): BrewStyle = when {
            b == null -> Percolation
            b.id == "phin" || b.id == "south-indian-filter" -> Percolation
            else -> when (b.method) {
                BrewMethod.Espresso -> Espresso
                BrewMethod.FrenchPress, BrewMethod.AeroPress, BrewMethod.ColdBrew, BrewMethod.Turkish, BrewMethod.Siphon -> Immersion
                BrewMethod.PourOver -> if (b.category == "immersion") Immersion else Percolation
                BrewMethod.Moka -> Percolation
            }
        }
    }
}

/** Which Review sections a brew gets (v4 §4.4). TDS and extraction always sit under "Advanced". */
data class ReviewSections(
    /** Drawdown time, In cup and Flow. */
    val drawdown: Boolean,
    /** Shape, surface, channelling. */
    val bed: Boolean,
    /** "Retained by the bed". */
    val retained: Boolean,
    val style: BrewStyle,
) {
    val advanced: Boolean get() = true

    companion object {
        private val DRAWDOWN_CATEGORIES = setOf("cone", "flat-bottom", "hybrid", "immersion")

        fun of(b: Brewer?): ReviewSections {
            val style = BrewStyle.of(b)
            // An unknown brewer keeps every section, as before v4.
            val pourOver = b == null ||
                (b.method == BrewMethod.PourOver && b.category in DRAWDOWN_CATEGORIES && !BrewerFamilies.hasOwnFamily(b))
            return ReviewSections(
                drawdown = pourOver,
                bed = pourOver || b?.id == "drip-machine",
                retained = style == BrewStyle.Percolation && b?.method != BrewMethod.Moka,
                style = style,
            )
        }
    }
}

/** Field labels that change by method (v4 §4.9). */
data class MethodLabels(
    /** "WATER", "YIELD" or "WATER IN THE BASE". */
    val water: String,
    /** A short helper under the water label: "in the cup" for espresso, else "". */
    val waterNote: String,
    /** "TIME" or "SHOT TIME". */
    val time: String,
    /** Cold brew: durations read in hours ("12 h 5 min"), see formatDuration. */
    val longTimes: Boolean,
) {
    companion object {
        fun of(method: BrewMethod): MethodLabels = when (method) {
            BrewMethod.Espresso -> MethodLabels("YIELD", "in the cup", "SHOT TIME", false)
            BrewMethod.Moka -> MethodLabels("WATER IN THE BASE", "", "TIME", false)
            BrewMethod.ColdBrew -> MethodLabels("WATER", "", "TIME", true)
            else -> MethodLabels("WATER", "", "TIME", false)
        }
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
