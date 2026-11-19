package com.laksh.coffee.domain.catalog

import kotlinx.serialization.Serializable

// DTOs 1:1 with shared/catalog/*.json. Every field the files may leave out has a default, so a
// newer file with extra or missing optional keys still decodes.

@Serializable
data class SourceRef(val who: String, val url: String = "", val note: String = "")

@Serializable
data class CatalogGrinder(
    val id: String,
    val brand: String,
    val model: String,
    /** hand | electric */
    val kind: String = "hand",
    /** conical | flat | ghost | blade */
    val burr: String = "",
    val burrSizeMm: Double? = null,
    /** 1 = very common. */
    val popularity: Int = 3,
    val setting: GrinderSetting,
    /** Key = BrewMethod.id; values in the setting's own unit (total clicks for click grinders). */
    val ranges: Map<String, GrindRange> = emptyMap(),
    val rangeSources: List<SourceRef> = emptyList(),
    /** high | medium | low */
    val confidence: String = "low",
    val notes: String = "",
) {
    val display: String get() = "$brand $model".trim()
}

@Serializable
data class GrinderSetting(
    /** clicks | numbers | stepless */
    val style: String,
    val unit: String = "",
    val min: Double,
    val max: Double,
    val step: Double,
    val lowerIsFiner: Boolean = true,
    val clicksPerRotation: Int? = null,
    val micronsPerStep: Double? = null,
    val dialReading: String = "",
    val entryHint: String = "",
    /** "plain" | "turns" | "turns-dot" | "number-tick:<n>"; see domain/grind/DialFormat. */
    val dialFormat: String? = null,
)

@Serializable
data class GrindRange(val from: Double, val to: Double)

@Serializable
data class CatalogRoaster(
    val id: String,
    val name: String,
    val country: String = "",
    val city: String = "",
    val area: String = "",
    val notable: String = "",
)

@Serializable
data class CatalogOrigin(
    val id: String,
    val country: String,
    val iso2: String = "",
    val area: String = "",
    val growingRegions: List<String> = emptyList(),
    val commonProcesses: List<String> = emptyList(),
    val commonVarietals: List<String> = emptyList(),
)

@Serializable
data class Term(val id: String, val label: String)

@Serializable
data class DescribedTerm(val id: String, val label: String, val description: String = "")

@Serializable
data class ProcessTerm(val id: String, val label: String, val group: String = "other", val description: String = "")

@Serializable
data class VarietalTerm(val id: String, val label: String, val aliases: List<String> = emptyList(), val species: String = "")

data class BeanVocabulary(
    val species: List<Term> = emptyList(),
    val processes: List<ProcessTerm> = emptyList(),
    val varietals: List<VarietalTerm> = emptyList(),
    val lotTypes: List<DescribedTerm> = emptyList(),
    val decafMethods: List<Term> = emptyList(),
    val roastLevels: List<DescribedTerm> = emptyList(),
    val roastIntents: List<Term> = emptyList(),
)

@Serializable
data class CatalogBrewer(
    val id: String,
    val name: String,
    val brand: String = "",
    val category: String = "other",
    val method: String = "pourover",
    val filter: String = "paper",
    val notes: String = "",
)

@Serializable
data class CatalogWater(
    val id: String,
    val label: String,
    val kind: String = "other",
    val description: String = "",
    val ghPpm: Double? = null,
    val khPpm: Double? = null,
)

@Serializable
data class ListModels(val path: String = "/models", val works: Boolean = false, val note: String = "")

@Serializable
data class CatalogModel(
    val id: String,
    val label: String = "",
    val inputPerM: Double? = null,
    val outputPerM: Double? = null,
    val vision: Boolean = false,
    val default: Boolean = false,
    val why: String = "",
)

@Serializable
data class CatalogProvider(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val api: String = "openai-chat",
    val auth: String = "bearer",
    val extraHeaders: Map<String, String> = emptyMap(),
    val browserHeaders: Map<String, String> = emptyMap(),
    val keyUrl: String = "",
    val keyHint: String = "",
    val browserCors: String = "unknown",
    val corsEvidence: String = "",
    val listModels: ListModels = ListModels(),
    val models: List<CatalogModel> = emptyList(),
    val pricesCheckedOn: String = "",
    val notes: String = "",
)

/** One sourced statement from shared/principles.json, sent to the model as background for its advice. */
@Serializable
data class Principle(
    val id: String,
    /** extraction | grind | agitation | flow | bed | temperature | ratio | freshness | water | espresso | recipe */
    val topic: String = "",
    /** BrewMethod ids; empty = every method. */
    val appliesTo: List<String> = emptyList(),
    val text: String,
    val who: String = "",
    val url: String = "",
    val note: String = "",
)

enum class TermKind { Species, Process, Varietal, LotType, Decaf, RoastLevel, RoastIntent }
