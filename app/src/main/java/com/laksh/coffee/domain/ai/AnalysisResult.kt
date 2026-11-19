@file:OptIn(ExperimentalSerializationApi::class)

package com.laksh.coffee.domain.ai

import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.domain.grind.MoveSizes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// --------------------------------------------------------------- the JSON contract
// Every field has a default so a partial reply still decodes.

@Serializable
data class Change(
    val variable: String = "",
    val instruction: String = "",
    val from: String = "",
    val to: String = "",
    val grindClicksFrom: Int? = null,
    val grindClicksTo: Int? = null,
    /** The same pair for numbered and stepless dials: the dial number, in the grinder's own unit. */
    val grindValueFrom: Double? = null,
    val grindValueTo: Double? = null,
)

@Serializable
data class BrewAnalysis(
    val verdict: String = "",
    val headline: String = "",
    val diagnosis: String = "",
    val confidence: String = "low",
    val evidence: List<String> = emptyList(),
    val change: Change = Change(),
    val expectIfRight: String = "",
    val ifWrong: String = "",
    val keep: String = "",
    val dataWarnings: List<String> = emptyList(),
)

@Serializable
data class Pattern(
    val title: String = "",
    val detail: String = "",
    val brewIds: List<Long> = emptyList(),
    val strength: String = "weak",
    val tone: String = "neutral",
)

@Serializable
data class ExperimentBrew(
    val label: String = "",
    val settings: String = "",
    val grindClicks: Int? = null,
    val expect: String = "",
)

@Serializable
data class Experiment(
    val goal: String = "",
    val brews: List<ExperimentBrew> = emptyList(),
    val decide: String = "",
)

@Serializable
data class CoffeeNote(val bean: String = "", val note: String = "")

@Serializable
data class TrendAnalysis(
    val trajectory: String = "",
    val headline: String = "",
    val summary: String = "",
    val confidence: String = "low",
    val patterns: List<Pattern> = emptyList(),
    val changedOften: List<String> = emptyList(),
    val neverChanged: List<String> = emptyList(),
    val experiment: Experiment = Experiment(),
    val perCoffee: List<CoffeeNote> = emptyList(),
    val dataWarnings: List<String> = emptyList(),
)

// ------------------------------------------------------------------- grind move

/**
 * The one grind change, re-derived on the phone. [from] comes from the log, never from the
 * model, and the direction comes from the grinder, so the hero number can't point the wrong way.
 * [dialFormat] is the grinder's DialFormat.encode() when the move was made; results stored
 * before it existed have "" and read as 1.x did (a ten-click turn reads turns.clicks).
 */
@Serializable
data class GrindMove(
    val from: Int?,
    val to: Int,
    val clicksPerTurn: Int,
    val lowerIsFiner: Boolean,
    val dialFormat: String = "",
) {

    val delta: Int? get() = from?.let { to - it }

    val finer: Boolean? get() = delta?.let { if (lowerIsFiner) it < 0 else it > 0 }

    /** Null for a 1.x result: [caption] and [dial] then keep the 1.x rules. */
    private val format: DialFormat? get() = DialFormat.decode(dialFormat, clicksPerTurn)

    /** "77 → 67", or "67" when there is no trusted starting point. */
    fun numbers(): String = if (from != null) "$from → $to" else "$to"

    /**
     * "10 clicks finer · one full turn · dial 7.7 → 6.7" on a 1.x ten-click row; "6 clicks finer ·
     * dial 7.5 → 6.9" on a numbered dial. Turn words appear only where a turn is a real unit.
     */
    fun caption(): String {
        val d = delta ?: return dial().orEmpty()
        if (d == 0) return ""
        val n = abs(d)
        val dir = if (finer == true) "finer" else "coarser"
        val cpt = clicksPerTurn
        val turnWords = format?.talksInTurns ?: (cpt > 0)
        val move = when {
            turnWords && cpt > 0 && n % cpt == 0 -> {
                val turns = n / cpt
                "${clicks(n)} $dir · " + if (turns == 1) "one full turn" else "$turns full turns"
            }
            turnWords && cpt > 0 && n > cpt -> {
                val turns = n / cpt
                "${clicks(n)} $dir · $turns ${if (turns == 1) "turn" else "turns"} + ${n % cpt}"
            }
            else -> "${clicks(n)} $dir"
        }
        return dial()?.let { "$move · $it" } ?: move
    }

    /** "dial 7.7 → 6.7" (or "dial 6.7" with no start) on dials that show a number, else null. */
    fun dial(): String? {
        if (to < 0) return null
        val f = format ?: if (clicksPerTurn == 10) DialFormat.TurnsDotClicks(10) else return null
        if (!f.showsDialNumber) return null
        val start = from?.takeIf { it >= 0 }
        val target = f.read(to) ?: return null
        return if (start != null) "dial ${f.read(start)} → $target" else "dial $target"
    }

    companion object {
        /** A 1.x ten-click dial reads turns.clicks: 77 clicks is 7.7. Kept for old prompts and results. */
        fun dialReading(clicks: Int): String = "${clicks / 10}.${clicks % 10}"

        private fun clicks(n: Int): String = if (n == 1) "1 click" else "$n clicks"

        /**
         * Builds the move from the model's change. [trustedFrom] is the log's own click count, or
         * the last trusted one; the model's grindClicksFrom is never used as the start, because
         * when the phone has no trusted value the model's number is a guess.
         */
        fun resolve(
            change: Change,
            grinder: GrinderEntity?,
            trustedFrom: Int?,
            method: BrewMethod? = null,
        ): Pair<GrindMove?, List<String>> {
            val g = grinder ?: return null to emptyList()
            if (!g.usesClicks) return null to emptyList()
            if (!change.variable.trim().equals("grind", ignoreCase = true)) return null to emptyList()
            val to = change.grindClicksTo ?: return null to emptyList()
            if (to <= 0) {
                return null to listOf("The suggested setting was zero or below, so it is not shown.")
            }
            val from = trustedFrom?.takeIf { it > 0 }
            if (to == from) return null to emptyList()

            val scale = g.scale()
            val cpt = g.clicksPerRotation.coerceAtLeast(0)
            val move = GrindMove(from, to, cpt, g.lowerIsFiner, scale.dial.encode())
            val warnings = mutableListOf<String>()
            val modelFrom = change.grindClicksFrom
            if (from != null && modelFrom != null && modelFrom > 0 && modelFrom != from) {
                warnings += "The model started from $modelFrom clicks, but your log says $from, so the " +
                    "move is shown from $from."
            }
            val finer = move.finer
            val delta = move.delta
            if (finer != null && delta != null) {
                val words = change.instruction.lowercase()
                if (("finer" in words && !finer) || ("coarser" in words && finer)) {
                    warnings += "The model's words and numbers disagree: $from → $to is " +
                        "${if (finer) "finer" else "coarser"} on this grinder (${g.finerDirection}). " +
                        "Check before turning the dial."
                }
                val moves = scale.moves(method)
                if (abs(delta) > moves.maxAtOnce) {
                    warnings += if (moves.basis == MoveSizes.Basis.LegacyTurn) {
                        "That is more than two full turns in one go. Consider going halfway first."
                    } else {
                        "That is more than ${scale.format(moves.maxAtOnce)} clicks in one go. Consider going halfway first."
                    }
                }
            }
            return move to warnings
        }
    }
}

// ------------------------------------------------------------- grind value move

/**
 * [GrindMove] for numbered and stepless dials: the dial number to go to. As there, [from] comes
 * from the log and the direction from the grinder, never from the model.
 */
@Serializable
data class GrindValueMove(
    val from: Double?,
    val to: Double,
    val lowerIsFiner: Boolean,
    val unit: String = "",
    val dialFormat: String = "",
) {
    val delta: Double? get() = from?.let { ((to - it) * 100).roundToLong() / 100.0 }

    val finer: Boolean? get() = delta?.takeIf { it != 0.0 }?.let { if (lowerIsFiner) it < 0 else it > 0 }

    /** "14 → 13", or "13" when there is no trusted starting point. */
    fun numbers(): String = if (from != null) "${format(from)} → ${format(to)}" else format(to)

    /** "finer by 1", "coarser by 0.5 µm"; "" with no start. */
    fun caption(): String {
        val d = delta ?: return ""
        val f = finer ?: return ""
        return "${if (f) "finer" else "coarser"} by ${format(abs(d))}" + if (unit.isBlank()) "" else " $unit"
    }

    companion object {
        /** "14", "14.5", "2.25": at most two decimals, none when whole. */
        fun format(v: Double): String {
            val r = (v * 100).roundToLong() / 100.0
            return if (r % 1.0 == 0.0) r.roundToLong().toString() else r.toString()
        }

        /** Mirrors [GrindMove.resolve]: the direction check and the "more than maxAtOnce" warning. */
        fun resolve(
            change: Change,
            grinder: GrinderEntity?,
            trustedFrom: Double?,
            method: BrewMethod? = null,
        ): Pair<GrindValueMove?, List<String>> {
            val g = grinder ?: return null to emptyList()
            if (g.usesClicks) return null to emptyList()
            if (!change.variable.trim().equals("grind", ignoreCase = true)) return null to emptyList()
            val to = change.grindValueTo?.takeIf { it.isFinite() } ?: return null to emptyList()
            if (to < 0) {
                return null to listOf("The suggested setting was below zero, so it is not shown.")
            }
            val from = trustedFrom?.takeIf { it > 0 }
            if (from != null && abs(to - from) < 1e-9) return null to emptyList()

            val scale = g.scale()
            val move = GrindValueMove(from, to, g.lowerIsFiner, scale.unit, scale.dial.encode())
            val warnings = mutableListOf<String>()
            val modelFrom = change.grindValueFrom
            if (from != null && modelFrom != null && modelFrom > 0 && abs(modelFrom - from) > 1e-9) {
                warnings += "The model started from ${format(modelFrom)}, but your log says ${format(from)}, so the " +
                    "move is shown from ${format(from)}."
            }
            val finer = move.finer
            val delta = move.delta
            if (from != null && finer != null && delta != null) {
                val words = change.instruction.lowercase()
                if (("finer" in words && !finer) || ("coarser" in words && finer)) {
                    warnings += "The model's words and numbers disagree: ${format(from)} → ${format(to)} is " +
                        "${if (finer) "finer" else "coarser"} on this grinder (${g.finerDirection}). " +
                        "Check before turning the dial."
                }
                val moves = scale.moves(method)
                if (abs(delta) > moves.maxAtOnce) {
                    warnings += "That is more than ${scale.format(moves.maxAtOnce)} in one go. Consider going halfway first."
                }
            }
            return move to warnings
        }
    }
}

// ------------------------------------------------------------------ value move

data class ValueMove(val from: String, val to: String, val unit: String)

object ChangeParse {

    private val VALUE = Regex("^\\s*([0-9]+(?:[.:][0-9]+)?)\\s*(.*)$")

    /** "95 °C" → "100 °C" becomes ("95", "100", "°C"), for a hero row like the grind one. */
    fun valueMove(change: Change): ValueMove? {
        val variable = change.variable.trim().lowercase()
        if (variable.isEmpty() || variable == "grind" || variable == "none") return null
        if (change.from.isBlank() || change.to.isBlank()) return null
        val a = VALUE.matchEntire(change.from.trim()) ?: return null
        val b = VALUE.matchEntire(change.to.trim()) ?: return null
        val unitA = a.groupValues[2].trim()
        val unitB = b.groupValues[2].trim()
        if (unitKey(unitA) != unitKey(unitB)) return null
        return ValueMove(a.groupValues[1], b.groupValues[1], unitA)
    }

    private fun unitKey(unit: String): String = unit.replace(" °", "°").lowercase()
}

// ------------------------------------------------------------- stored envelope

/** What analyses.resultJson holds. */
@Serializable
data class StoredResult(
    val brew: BrewAnalysis? = null,
    val trend: TrendAnalysis? = null,
    val grindMove: GrindMove? = null,
    val valueMove: GrindValueMove? = null,
    val localWarnings: List<String> = emptyList(),
    /**
     * Trend only: the uid of each brew the patterns name, keyed by the number the model was
     * shown (see [BrewRefs]). Null on a result stored before uids were kept. The web app
     * writes the same key, so a result reads the same on both.
     */
    val brewUids: Map<String, String>? = null,
)

/**
 * Which brews a stored trend points at. The model names brews by the row ids it was shown, and
 * row ids are this install's own: after a backup is restored the same brews have other ids. So
 * each stored result also keeps the uid behind every number, and the numbers are followed
 * through the uids.
 */
object BrewRefs {

    /** The table to store with [trend]: every brew number its patterns name that [uidOf] knows. */
    fun uidsFor(trend: TrendAnalysis, uidOf: (Long) -> String?): Map<String, String> =
        trend.patterns.flatMap { it.brewIds }.distinct()
            .mapNotNull { n -> uidOf(n)?.takeIf { it.isNotEmpty() }?.let { n.toString() to it } }
            .toMap()

    /**
     * The table to read a stored result with. A result with none was stored before uids were
     * kept. Made here, its numbers are this phone's row ids (null). Made by another app and
     * brought in, they are that install's row ids and are never followed (an empty table).
     * A result brought in from another phone running this same app can't be told apart from
     * one made here; every import since uids were kept marks it with an empty table instead.
     */
    fun stored(brewUids: Map<String, String>?, madeHere: Boolean): Map<String, String>? =
        brewUids ?: if (madeHere) null else emptyMap()

    /**
     * Brew number to the row id of that brew on this phone now. Null when the result has no
     * table (it was stored before uids were kept): its numbers are then read as row ids, which
     * is right as long as the rows are the ones it was made from.
     */
    fun rowIds(brewUids: Map<String, String>?, idOfUid: (String) -> Long?): Map<Long, Long>? {
        if (brewUids == null) return null
        return buildMap {
            for ((number, uid) in brewUids) {
                val n = number.toLongOrNull() ?: continue
                idOfUid(uid)?.let { put(n, it) }
            }
        }
    }

    /** The row ids behind a pattern's [numbers], each once; a brew that is gone is left out. */
    fun follow(numbers: List<Long>, rowIds: Map<Long, Long>?): List<Long> =
        if (rowIds == null) numbers.distinct() else numbers.mapNotNull { rowIds[it] }.distinct()
}

sealed interface Parsed<out T> {
    data class Ok<out T>(val value: T) : Parsed<T>
    data class Raw(val text: String, val reason: String) : Parsed<Nothing>
}

// ---------------------------------------------------------------------- parser

/**
 * Reads a model reply that is meant to be one JSON object but may arrive wrapped in thinking
 * tags, code fences, prose or a single wrapper key, with strings where lists belong. Anything
 * it can't read comes back as [Parsed.Raw] so the billed reply is still kept and shown.
 */
object AnalysisParser {

    private val BREW_ANCHORS = listOf("verdict", "headline", "diagnosis")
    private val TREND_ANCHORS = listOf("trajectory", "headline", "patterns")

    private val THINK_BLOCK = Regex("(?is)<think>.*?</think>")
    private val FENCE = Regex("```[A-Za-z]*")
    private val INT = Regex("-?\\d+")
    private val NUMBER = Regex("-?\\d+(?:\\.\\d+)?")

    private val VERDICTS = setOf("dialled_in", "close", "needs_change", "fault")
    private val TRAJECTORIES = setOf("improving", "steady", "stuck", "erratic")
    private val CONFIDENCES = setOf("low", "medium", "high")
    private val STRENGTHS = setOf("strong", "weak", "too_little_data")
    private val TONES = setOf("good", "attention", "fault", "neutral")

    private const val MAX_TRIES = 20

    fun extractJsonObject(raw: String, anchorKeys: List<String>): String? {
        val first = raw.indexOf('{')
        val last = raw.lastIndexOf('}')
        if (first < 0 || last <= first) return null
        val whole = raw.substring(first, last + 1)
        if (parseObject(whole) != null) return whole

        // Prose with stray braces around the answer: try each opening brace in turn and keep the
        // first candidate that parses and looks like the answer.
        var start = first
        var tries = 0
        while (start in 0 until last && tries < MAX_TRIES) {
            val candidates = buildList {
                val end = matchingBrace(raw, start)
                if (end > start) add(raw.substring(start, end + 1))
                add(raw.substring(start, last + 1))
            }.distinct()
            for (candidate in candidates) {
                val obj = parseObject(candidate) ?: continue
                if (anchorKeys.any { it in obj }) return candidate
            }
            start = raw.indexOf('{', start + 1)
            tries++
        }
        return null
    }

    fun parseBrew(raw: String): Parsed<BrewAnalysis> {
        val obj = locate(raw, BREW_ANCHORS) ?: return Parsed.Raw(raw, "No JSON object in the reply.")
        val value = runCatching {
            AiJson.reply.decodeFromJsonElement(BrewAnalysis.serializer(), normalizeBrew(obj))
        }.getOrElse { return Parsed.Raw(raw, "The JSON didn't match the brew shape.") }
        if (value.headline.isBlank() && value.diagnosis.isBlank()) {
            return Parsed.Raw(raw, "The reply had no headline or diagnosis.")
        }
        return Parsed.Ok(value)
    }

    fun parseTrend(raw: String): Parsed<TrendAnalysis> {
        val obj = locate(raw, TREND_ANCHORS) ?: return Parsed.Raw(raw, "No JSON object in the reply.")
        val value = runCatching {
            AiJson.reply.decodeFromJsonElement(TrendAnalysis.serializer(), normalizeTrend(obj))
        }.getOrElse { return Parsed.Raw(raw, "The JSON didn't match the patterns shape.") }
        if (value.headline.isBlank() && value.patterns.isEmpty()) {
            return Parsed.Raw(raw, "The reply had no headline or patterns.")
        }
        return Parsed.Ok(value)
    }

    // ---- locating the object

    private fun locate(raw: String, anchors: List<String>): JsonObject? {
        if (raw.isBlank()) return null
        val text = clean(raw)
        val json = extractJsonObject(text, anchors) ?: return null
        val obj = parseObject(json) ?: return null
        return unwrap(obj, anchors)
    }

    /** Drops thinking blocks and code fences. The caller never passes reasoning_content. */
    private fun clean(raw: String): String {
        var s = THINK_BLOCK.replace(raw, "")
        val open = s.indexOf("<think>", ignoreCase = true)
        if (open >= 0) {
            // An unclosed think tag: its text runs up to where the answer starts.
            val brace = s.indexOf('{', open)
            s = if (brace >= 0) s.substring(0, open) + s.substring(brace) else s.substring(0, open)
        }
        return FENCE.replace(s, "").trim()
    }

    /**
     * {"analysis": {...}} becomes the inner object, but only when the outer object has no anchor
     * key and nothing else, so a real reply is never swapped for its "change" or "experiment".
     */
    private fun unwrap(obj: JsonObject, anchors: List<String>): JsonObject {
        if (anchors.any { it in obj } || obj.size != 1) return obj
        return obj.values.first() as? JsonObject ?: obj
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { AiJson.reply.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** Index of the brace closing the one at [start], skipping braces inside strings; -1 if none. */
    private fun matchingBrace(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    // ---- normalising before decode

    private fun normalizeBrew(o: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in o) {
            when (k) {
                "verdict" -> put(k, JsonPrimitive(verdict(v)))
                "confidence" -> put(k, JsonPrimitive(oneOf(v, CONFIDENCES, "low")))
                "evidence", "dataWarnings" -> put(k, stringList(v))
                "change" -> put(k, normalizeChange(v))
                "headline", "diagnosis", "expectIfRight", "ifWrong", "keep" -> put(k, text(v))
                else -> put(k, v)
            }
        }
    }

    private fun normalizeChange(v: JsonElement): JsonElement {
        if (v is JsonPrimitive && v !is JsonNull) return buildJsonObject { put("instruction", text(v)) }
        if (v !is JsonObject) return JsonObject(emptyMap())
        return buildJsonObject {
            for ((k, value) in v) {
                when (k) {
                    "variable" -> put(k, JsonPrimitive(plain(value)?.trim()?.lowercase().orEmpty()))
                    "grindClicksFrom", "grindClicksTo" -> put(k, intOrNull(value))
                    "grindValueFrom", "grindValueTo" -> put(k, numberOrNull(value))
                    "instruction", "from", "to" -> put(k, text(value))
                    else -> put(k, value)
                }
            }
        }
    }

    private fun normalizeTrend(o: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in o) {
            when (k) {
                "trajectory" -> put(k, JsonPrimitive(oneOf(v, TRAJECTORIES, "")))
                "confidence" -> put(k, JsonPrimitive(oneOf(v, CONFIDENCES, "low")))
                "headline", "summary" -> put(k, text(v))
                "changedOften", "neverChanged", "dataWarnings" -> put(k, stringList(v))
                "patterns" -> put(k, JsonArray(asList(v).mapNotNull(::normalizePattern)))
                "experiment" -> put(k, normalizeExperiment(v))
                "perCoffee" -> put(k, normalizePerCoffee(v))
                else -> put(k, v)
            }
        }
    }

    private fun normalizePattern(v: JsonElement): JsonObject? = when (v) {
        is JsonObject -> buildJsonObject {
            for ((k, value) in v) {
                when (k) {
                    "title", "detail" -> put(k, text(value))
                    "brewIds" -> put(k, brewIds(value))
                    "strength" -> put(k, JsonPrimitive(oneOf(value, STRENGTHS, "weak")))
                    "tone" -> put(k, JsonPrimitive(oneOf(value, TONES, "neutral")))
                    else -> put(k, value)
                }
            }
        }
        is JsonPrimitive -> plain(v)?.takeIf { it.isNotBlank() }?.let { buildJsonObject { put("title", JsonPrimitive(it)) } }
        else -> null
    }

    private fun normalizeExperiment(v: JsonElement): JsonElement {
        if (v !is JsonObject) return JsonObject(emptyMap())
        return buildJsonObject {
            for ((k, value) in v) {
                when (k) {
                    "goal", "decide" -> put(k, text(value))
                    "brews" -> put(k, JsonArray(asList(value).mapNotNull(::normalizeExperimentBrew)))
                    else -> put(k, value)
                }
            }
        }
    }

    private fun normalizeExperimentBrew(v: JsonElement): JsonObject? = when (v) {
        is JsonObject -> buildJsonObject {
            for ((k, value) in v) {
                when (k) {
                    "grindClicks" -> put(k, intOrNull(value))
                    "label", "settings", "expect" -> put(k, text(value))
                    else -> put(k, value)
                }
            }
        }
        is JsonPrimitive -> plain(v)?.takeIf { it.isNotBlank() }?.let { buildJsonObject { put("settings", JsonPrimitive(it)) } }
        else -> null
    }

    private fun normalizePerCoffee(v: JsonElement): JsonArray = when (v) {
        // {"Roaster — Coffee": "one sentence"} is read as bean → note.
        is JsonObject -> JsonArray(
            v.map { (bean, note) ->
                buildJsonObject {
                    put("bean", JsonPrimitive(bean))
                    put("note", text(note))
                }
            },
        )
        else -> JsonArray(
            asList(v).mapNotNull { item ->
                when (item) {
                    is JsonObject -> buildJsonObject {
                        for ((k, value) in item) put(k, if (k == "bean" || k == "note") text(value) else value)
                    }
                    is JsonPrimitive -> plain(item)?.takeIf { it.isNotBlank() }?.let {
                        buildJsonObject { put("note", JsonPrimitive(it)) }
                    }
                    else -> null
                }
            },
        )
    }

    private fun verdict(v: JsonElement): String {
        val s = plain(v)?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_').orEmpty()
        val fixed = if (s == "dialed_in") "dialled_in" else s
        return if (fixed in VERDICTS) fixed else ""
    }

    private fun oneOf(v: JsonElement, allowed: Set<String>, fallback: String): String {
        val s = plain(v)?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_').orEmpty()
        return if (s in allowed) s else fallback
    }

    /** The primitive's content, or null for JsonNull and non-primitives. */
    private fun plain(v: JsonElement): String? = (v as? JsonPrimitive)?.contentOrNull

    /** A string field: arrays are joined, null stays null so the default applies. */
    private fun text(v: JsonElement): JsonElement = when (v) {
        is JsonNull -> JsonNull
        is JsonPrimitive -> JsonPrimitive(v.content)
        is JsonArray -> JsonPrimitive(v.mapNotNull { item -> itemText(item) }.joinToString(" "))
        is JsonObject -> JsonPrimitive(v.toString())
    }

    private fun itemText(v: JsonElement): String? = when (v) {
        is JsonNull -> null
        is JsonPrimitive -> v.content
        else -> v.toString()
    }

    private fun asList(v: JsonElement): List<JsonElement> = when (v) {
        is JsonArray -> v
        is JsonNull -> emptyList()
        else -> listOf(v)
    }

    /** A list-of-strings field given as one string becomes a one-element list. */
    private fun stringList(v: JsonElement): JsonArray =
        JsonArray(
            asList(v).mapNotNull { item -> itemText(item)?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } },
        )

    /** "67", "67 clicks" and 67.0 all become 67; anything else becomes null. */
    private fun intOrNull(v: JsonElement): JsonElement {
        val p = v as? JsonPrimitive ?: return JsonNull
        val s = p.contentOrNull?.trim() ?: return JsonNull
        s.toDoubleOrNull()?.takeIf { it.isFinite() && abs(it) < Int.MAX_VALUE }?.let { return JsonPrimitive(it.roundToInt()) }
        return INT.find(s)?.value?.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
    }

    /** 14, "14.5" and "14.5 on the dial" become numbers; anything else becomes null. */
    private fun numberOrNull(v: JsonElement): JsonElement {
        val p = v as? JsonPrimitive ?: return JsonNull
        val s = p.contentOrNull?.trim() ?: return JsonNull
        val n = s.replace(',', '.').toDoubleOrNull() ?: NUMBER.find(s)?.value?.toDoubleOrNull()
        return n?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) } ?: JsonNull
    }

    /** "#3", "3" and 3 become 3; "3, 6" becomes 3 and 6; anything unreadable is dropped. */
    private fun brewIds(v: JsonElement): JsonArray =
        JsonArray(
            asList(v).flatMap { item ->
                val s = (item as? JsonPrimitive)?.contentOrNull ?: return@flatMap emptyList<JsonPrimitive>()
                INT.findAll(s).mapNotNull { it.value.toLongOrNull() }.filter { it > 0 }.map { JsonPrimitive(it) }.toList()
            },
        )
}

// ------------------------------------------------------------------ Json configs

object AiJson {
    /** Requests and responses on the wire. */
    val wire: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Model replies: forgiving about everything a model gets slightly wrong. */
    val reply: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        allowTrailingComma = true
        allowComments = true
        explicitNulls = false
    }

    /** What goes into Room. */
    val stored: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Prompt blocks: compact, and nulls kept so the model sees which fields were withheld. */
    val prompt: Json = Json {
        prettyPrint = false
        explicitNulls = true
    }
}
