package com.laksh.coffee.domain

import kotlin.math.roundToInt

/** What the spent bed looked like when you pulled the brewer apart. */
enum class BedShape(val label: String, val glyph: String, val reading: String) {
    Unset("Not recorded", "?", ""),
    Flat("Flat and even", "▬", "The good one. Water moved through the whole bed at the same speed."),
    Domed("Domed in the middle", "◠", "Gentle pouring, low agitation. Usually harmless, sometimes a touch under-extracted."),
    Crater("Cratered / dug out", "◡", "Your pour drilled into one spot. Water took a shortcut there."),
    HighAndDry("High and dry ring", "◌", "Grounds stranded up the paper never brewed. Swirl or stir earlier."),
    Sloped("Sloped to one side", "◤", "Uneven pour or an unlevel brewer. Half the bed got more water."),
    Sinkhole("Sinkholes / pits", "⁙", "Channelling. Water punched through instead of soaking through."),
    Muddy("Muddy and compacted", "▓", "Fines migrated down and choked the bed. Expect a slow, harsh cup."),
}

/** Silt sitting on top of the bed at the end. */
enum class Sludge(val label: String) {
    Unset("Not recorded"), None("Clean"), Light("A little silt"), Heavy("Thick muddy cap")
}

/** Evidence of water taking a shortcut. */
enum class Channeling(val label: String) {
    Unset("Not recorded"), None("None visible"), Suspected("Maybe"), Obvious("Obvious holes")
}

/** How the drawdown felt against the recipe target. */
enum class FlowFeel(val label: String) {
    Unset("Not recorded"),
    Stalled("Stalled / never finished"),
    Slow("Slower than target"),
    OnTarget("On target"),
    Fast("Faster than target"),
    Gusher("Fell straight through"),
}

/** 0..10 taste axes. 5 is neutral for balance-style axes, more-is-more for the rest. */
data class TasteProfile(
    val acidity: Int = 5,
    val sweetness: Int = 5,
    val bitterness: Int = 5,
    val body: Int = 5,
    val balance: Int = 5,
    val aftertaste: Int = 5,
    val clarity: Int = 5,
) {
    companion object {
        val AXES: List<Triple<String, String, (TasteProfile) -> Int>> = listOf(
            Triple("Acidity", "flat → electric", TasteProfile::acidity),
            Triple("Sweetness", "dry → syrupy", TasteProfile::sweetness),
            Triple("Bitterness", "none → harsh", TasteProfile::bitterness),
            Triple("Body", "watery → heavy", TasteProfile::body),
            Triple("Balance", "disjointed → seamless", TasteProfile::balance),
            Triple("Aftertaste", "vanishes → lingers", TasteProfile::aftertaste),
            Triple("Clarity", "muddled → crystalline", TasteProfile::clarity),
        )
    }

    fun with(axis: String, value: Int): TasteProfile = when (axis) {
        "Acidity" -> copy(acidity = value)
        "Sweetness" -> copy(sweetness = value)
        "Bitterness" -> copy(bitterness = value)
        "Body" -> copy(body = value)
        "Balance" -> copy(balance = value)
        "Aftertaste" -> copy(aftertaste = value)
        "Clarity" -> copy(clarity = value)
        else -> this
    }
}

object Descriptors {
    val POSITIVE = listOf(
        "Floral", "Citrus", "Stone fruit", "Berry", "Tropical", "Chocolate", "Caramel",
        "Nutty", "Tea-like", "Winey", "Honey", "Spice", "Malt", "Creamy", "Juicy",
    )
    val FAULTS = listOf(
        "Sour", "Bitter", "Astringent", "Drying", "Hollow", "Thin", "Muddy",
        "Papery", "Ashy", "Salty", "Vegetal", "Boozy / ferment-heavy",
    )
}

/** Everything the app can compute from a finished brew without asking anyone. */
object BrewMath {
    /** Extraction yield %. Needs beverage weight on the scale and a refractometer TDS reading. */
    fun extractionYield(doseG: Double, beverageG: Double?, tdsPercent: Double?): Double? {
        if (beverageG == null || tdsPercent == null || doseG <= 0.0) return null
        if (beverageG <= 0.0 || tdsPercent <= 0.0) return null
        return (beverageG * tdsPercent) / doseG
    }

    /** Water absorbed by the grounds, roughly 2 g per gram of coffee. */
    fun expectedRetentionG(doseG: Double): Double = doseG * 2.0

    fun expectedBeverageG(doseG: Double, waterG: Double): Double =
        (waterG - expectedRetentionG(doseG)).coerceAtLeast(0.0)

    fun daysOffRoast(roastEpochDay: Long?, nowEpochDay: Long): Long? =
        roastEpochDay?.let { nowEpochDay - it }

    fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
}
