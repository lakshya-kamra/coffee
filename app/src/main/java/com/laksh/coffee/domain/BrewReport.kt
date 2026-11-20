package com.laksh.coffee.domain

import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.core.counted
import com.laksh.coffee.core.formatDuration
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.TermKind
import com.laksh.coffee.domain.grind.GrindScale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Turns a brew (or a run of brews) into something worth handing to a person or to Claude.
 *
 * This object never touches the network. [markdown] produces text you share, copy or export;
 * in-app analysis is domain/ai + data/ai and runs only when you tap a button that asks for it
 * (Analyse, Save & analyse, Find patterns, Re-run, Try again, Save and test, Test connection
 * or Browse models). Where the text you share ends up is your choice.
 */
object BrewReport {

    private val dateFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault())

    fun when_(epochMs: Long): String = dateFmt.format(Instant.ofEpochMilli(epochMs))

    // ------------------------------------------------------------------ local read

    /** What quickRead may know beyond the brew and its recipe. */
    data class QuickReadOptions(
        /** The brewer; defaults to the recipe's. Null or unknown reads as a filter brew. */
        val brewer: Brewer? = null,
        /** false: "+1 tbsp" instead of "+2 g". */
        val hasScale: Boolean = true,
        val fahrenheit: Boolean = false,
        /** Data quality already distrusted the total time: no advice about time. */
        val timeIgnored: Boolean = false,
    )

    private fun timeUsable(actual: Int, target: Int): Boolean =
        actual > 0 && target > 0 && actual >= 0.5 * target && actual <= 2.5 * target

    /**
     * A cheap, offline first opinion (v2, v4 §4.4). Deliberately hedged: these are the patterns
     * that usually explain what you described, not a verdict. Advice depends on how the brew works
     * and on whether the grind can change, so pre-ground coffee never hears "go finer". The web
     * app says exactly the same lines (shared/fixtures/domain-v4-cases.json).
     */
    fun quickRead(log: BrewLogEntity, recipe: Recipe?, opts: QuickReadOptions = QuickReadOptions()): List<String> {
        val out = mutableListOf<String>()
        val t = log.taste
        val brewer = opts.brewer ?: recipe?.brewer
        val style = BrewStyle.of(brewer)
        val espresso = style == BrewStyle.Espresso
        val adjustable = log.grindKind != "preground"
        val dose = if (log.doseG > 0) log.doseG else recipe?.doseG ?: 0.0

        val sour = "Sour" in log.faults
        val bitter = "Bitter" in log.faults
        val weak = "Too weak" in log.faults
        val strong = "Too strong" in log.faults
        val hollow = "Hollow" in log.faults || "Thin" in log.faults
        val under = sour || weak || hollow || (t.acidity >= 7 && t.sweetness <= 4 && t.bitterness <= 3)

        // Extraction direction, by method and by whether the grind can change.
        if (under) {
            out += when {
                espresso -> {
                    val aim = if (dose > 0) "aim for about ${(dose * 2.5).roundToInt()} g" else ""
                    when {
                        adjustable && aim.isNotEmpty() -> "Let more out: $aim, or grind finer."
                        adjustable -> "Let more out, or grind finer."
                        aim.isNotEmpty() -> "Let more out: $aim."
                        else -> "Let more out."
                    }
                }
                style == BrewStyle.Immersion ->
                    if (adjustable) "Steep 30 seconds longer, or grind a little finer." else "Steep 1 minute longer."
                else -> if (adjustable) "Grind a little finer next time." else "Pour more slowly, and use water just off the boil."
            }
        }
        if (weak) {
            out += if (espresso) "Use a little more coffee: +1 g." else "Or use a little more coffee: ${if (opts.hasScale) "+2 g" else "+1 tbsp"}."
        }
        if (bitter) {
            out += when {
                espresso -> if (adjustable) "Stop the shot sooner, or grind coarser." else "Stop the shot sooner."
                style == BrewStyle.Immersion ->
                    if (adjustable) "Steep 30 seconds less, or grind a little coarser." else "Steep 30 seconds less."
                else -> if (adjustable) "Grind a little coarser next time." else "Use water a little cooler, about ${tempWords(90, opts.fahrenheit)}."
            }
        }
        if (strong) {
            out += if (espresso) "Add hot water for an Americano, or let a little more out." else "Use a little less coffee, or add a splash of hot water to the cup."
        }

        // The bed, only where there is one to look at.
        if (ReviewSections.of(brewer).bed) {
            when (log.bedShape) {
                BedShape.HighAndDry ->
                    out += "Grounds stranded up the wall never brewed at all — that is dose you paid " +
                        "for and did not extract. Swirl right after the bloom, or pour more centrally."
                BedShape.Crater ->
                    out += "A cratered bed means the stream dug into one spot. Pour from lower, " +
                        "slower, and keep the circles small."
                BedShape.Sinkhole ->
                    out += "Pits in the bed are channelling. Water found a shortcut, so part of the " +
                        "bed is over-extracted and part never got touched."
                BedShape.Muddy ->
                    out += "A compacted, muddy bed points at fines migration choking the filter. " +
                        "Coarser grind, or less agitation late in the brew."
                BedShape.Sloped ->
                    out += "A sloped bed usually means the brewer was not level, or one side got " +
                        "most of the water."
                BedShape.Flat ->
                    out += "Flat bed. Whatever you did not like about this cup, distribution was not it."
                else -> Unit
            }
            if (log.channeling == Channeling.Obvious) {
                out += "You called obvious channelling — fix that before reading anything else into " +
                    "the taste. Channelled brews taste under and over extracted at the same time."
            }
        }

        // Time against the recipe: drawdown, steep or shot time. Printed as clocks, never "598s".
        val actual = log.totalTimeSec
        // A shot is measured from the nearer edge of its band (A4), the one shot mode showed: a
        // 0:45 recipe whose last 10 s are "Stop and taste" aims for about 35 s, not 0:45.
        val shot = if (espresso && recipe != null) ShotTarget.of(recipe) else null
        val target = shot?.highSec ?: recipe?.totalSec ?: 0
        if (!opts.timeIgnored && timeUsable(actual, target)) {
            val delta = shot?.offBy(actual) ?: (actual - target)
            // "0:35" for a shot that ends at a time, "25–30 s" for a band the recipe states.
            val aim = if (shot?.stated == true) shot.label else formatDuration(target)
            when {
                espresso -> when {
                    delta > 5 -> out += "Your shot ran ${formatDuration(actual)}, longer than the recipe's $aim. " +
                        if (adjustable) "If it tasted bitter, grind a little coarser." else "If it tasted bitter, stop it sooner."
                    delta < -5 -> out += "Your shot ran ${formatDuration(actual)}, quicker than the recipe's $aim. " +
                        if (adjustable) "If it tasted sour, grind a little finer." else "If it tasted sour, let a little more out."
                }
                style == BrewStyle.Immersion -> when {
                    delta > 60 -> out += "You steeped ${formatDuration(delta)} longer than the recipe's ${formatDuration(target)}. " +
                        "If it tasted heavy or bitter, stick to the recipe's time."
                    delta < -45 -> out += "You stopped ${formatDuration(-delta)} before the recipe's ${formatDuration(target)}. " +
                        "If it tasted thin or sour, give it the full time."
                }
                delta > 60 -> out += "You ran ${formatDuration(delta)} past the recipe's ${formatDuration(target)}. " +
                    if (adjustable) "That is a lot of extra contact — grind coarser if the cup came out heavy."
                    else "Pre-ground coffee often drains slowly; if the cup came out heavy, use a little less coffee next time."
                delta < -45 -> out += "You finished ${formatDuration(-delta)} early against ${formatDuration(target)}. " +
                    if (adjustable) "A fast drawdown with a thin cup means go finer." else "If the cup was thin, pour more slowly next time."
            }
        }

        // Freshness
        log.daysOffRoast?.let { d ->
            when {
                d <= 3 -> {
                    // Only a percolation brew has a drawdown to speed up.
                    val expect = when (style) {
                        BrewStyle.Espresso -> "a lot of crema"
                        BrewStyle.Immersion -> "a lively bloom"
                        BrewStyle.Percolation -> "an aggressive bloom and a faster-than-usual drawdown"
                    }
                    out += "Only ${counted(d.toInt(), "day")} off roast. Expect $expect from all that CO2. Not a fault."
                }
                d >= 35 -> out += "$d days off roast. Flat and papery at this age is the beans, " +
                    "not your technique."
            }
        }

        if (log.extractionYield != null) {
            val ey = BrewMath.round1(log.extractionYield)
            out += if (espresso) {
                "Measured extraction yield $ey%. Taste beats the number."
            } else {
                "Measured extraction yield $ey%. For filter, most people land somewhere " +
                    "between 18 and 22 — but taste beats the number."
            }
        }

        if (out.isEmpty()) {
            out += "Nothing obviously wrong in what you logged. If the cup still is not right, " +
                "the next useful move is changing one variable and brewing it again."
        }
        return out
    }

    /** "90 °C", or "194 °F". */
    private fun tempWords(c: Int, fahrenheit: Boolean): String =
        if (fahrenheit) "${Temperatures.cToF(c.toDouble())} °F" else "$c °C"

    // ------------------------------------------------------------------- markdown

    /** A single brew, framed as a question. This is what the share sheet sends. */
    fun markdown(
        log: BrewLogEntity,
        recipe: Recipe?,
        bean: BeanEntity?,
        grinder: GrinderEntity?,
        history: List<BrewLogEntity> = emptyList(),
        /** Photos of this brew kept in the app. Text can't carry them, so the report only says they exist. */
        photoCount: Int = 0,
    ): String = buildString {
        val brewer = recipe?.brewer ?: Brewers.byId(log.brewerId) ?: Brewers.resolveOrNull(log.brewer)
        val scale = grinder?.scale()
        appendLine("# Brew log — ${when_(log.startedAt)}")
        appendLine()
        appendLine("I brewed this and I want help working out what to change next time.")
        appendLine("Please read the bed analysis and the taste notes together before answering,")
        appendLine("and give me **one** change to make on the next brew, not five.")
        appendLine()

        appendLine("## Recipe")
        appendLine("- Following: **${log.recipeName.ifBlank { "own recipe" }}**" +
            if (log.recipeAuthor.isNotBlank()) " (${log.recipeAuthor})" else "")
        appendLine(
            "- Brewer: ${brewer?.label ?: log.brewer.ifBlank { "unspecified" }}" +
                (brewer?.let { " (${it.method.label.lowercase()}, ${it.filter} filter)" } ?: ""),
        )
        appendLine("- Dose: ${trim(log.doseG)} g")
        appendLine("- Water: ${trim(log.waterG)} g" + ratioSuffix(log.doseG, log.waterG))
        appendLine("- Temperature: ${log.tempC} C")
        recipe?.let { appendLine("- Recipe target time: ${it.totalSec.asClock()}") }
        Catalog.water(log.waterId)?.let { w ->
            val hardness = listOfNotNull(
                w.ghPpm?.let { "GH ${trim(it)} ppm" },
                w.khPpm?.let { "KH ${trim(it)} ppm" },
            ).joinToString(", ")
            appendLine("- Water source: ${w.label}" + if (hardness.isEmpty()) "" else " ($hardness)")
        }
        appendLine()

        appendLine("## Coffee")
        if (bean != null) {
            appendLine("- ${bean.display}")
            val roaster = Catalog.roaster(bean.roasterId)
            roaster?.country?.takeIf { it.isNotBlank() }?.let { appendLine("- Roaster based in: $it") }
            val origin = bean.origin.ifBlank { Catalog.origin(bean.originId)?.country.orEmpty() }
            if (origin.isNotBlank()) appendLine("- Origin: $origin ${bean.region}".trim())
            if (bean.farm.isNotBlank()) appendLine("- Farm or estate: ${bean.farm}")
            if (bean.producer.isNotBlank()) appendLine("- Producer: ${bean.producer}")
            if (bean.washingStation.isNotBlank()) appendLine("- Washing station: ${bean.washingStation}")
            Catalog.label(TermKind.LotType, bean.lotTypeId)?.let { appendLine("- Lot: $it") }
            Catalog.label(TermKind.Species, bean.speciesId)?.let { appendLine("- Species: $it") }
            val varietals = bean.varietalIds.map { Catalog.label(TermKind.Varietal, it) ?: it } +
                bean.varietal.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (varietals.isNotEmpty()) appendLine("- Varietal: ${varietals.distinct().joinToString(", ")}")
            appendLine(
                "- Process: ${processLabel(bean)}" +
                    (Catalog.process(bean.processId)?.group?.takeIf { it.isNotBlank() && it != "other" }?.let { " ($it family)" } ?: ""),
            )
            if (bean.isDecaf) {
                appendLine("- Decaf: ${Catalog.label(TermKind.Decaf, bean.decafMethodId) ?: "yes, method not recorded"}")
            }
            appendLine("- Roast level: ${roastLabel(bean)}")
            Catalog.label(TermKind.RoastIntent, bean.roastIntentId)?.let { appendLine("- Roasted for: $it") }
            if (bean.altitudeMasl.isNotBlank()) appendLine("- Altitude: ${bean.altitudeMasl} masl")
            if (bean.harvest.isNotBlank()) appendLine("- Harvest: ${bean.harvest}")
            log.daysOffRoast?.let { appendLine("- Days off roast: $it") }
            bean.restDays?.let { appendLine("- Roaster suggests resting: ${counted(it, "day")}") }
            if (bean.tastingNotes.isNotBlank()) appendLine("- Roaster's notes: ${bean.tastingNotes}")
            if (bean.notes.isNotBlank()) appendLine("- My notes on the bag: ${bean.notes}")
        } else {
            appendLine("- ${log.beanLabel.ifBlank { "not recorded" }}")
        }
        appendLine()

        appendLine("## Grind")
        appendLine("- Grinder: ${log.grinderLabel.ifBlank { "not recorded" }}")
        grinder?.let {
            appendLine("- Burrs: ${it.burrType.label}" + if (it.burrSizeMm.isNotBlank()) " ${it.burrSizeMm} mm" else "")
        }
        val clicks = log.grindClicks
        val dialValue = log.grindValue
        if (clicks != null) {
            appendLine("- Setting used: **${counted(clicks, "click")}** out from zero (burrs touching)")
            grinder?.let { g ->
                val scale = g.scale()
                if (g.clicksPerRotation > 0 && scale.talksInTurns) {
                    appendLine(
                        "- Dial: ${g.clicksPerRotation} clicks per full turn, so $clicks is " +
                            "${counted(clicks / g.clicksPerRotation, "turn")} plus ${clicks % g.clicksPerRotation}"
                    )
                } else if (scale.dial.showsDialNumber) {
                    // A numbered dial: 75 total clicks reads 7.5 on the ring, not 7 turns.
                    val perTurn = if (g.clicksPerRotation > 0) "${g.clicksPerRotation} clicks per full turn; " else ""
                    appendLine("- Dial: $perTurn$clicks reads ${scale.reading(clicks.toDouble())} on the dial")
                }
                appendLine("- Direction: ${g.finerDirection}")
                g.micronsPerClick?.let { m ->
                    // Clicks x microns per click is how far the burrs have moved apart from zero.
                    // Said in those words so no reader, person or model, takes it for particle size.
                    appendLine(
                        "- Burr travel from zero: about ${GrindScale.millimetres(m * clicks)} mm " +
                            "(${oneDp(m)} microns per click). This is how far the burrs have moved, not the size of the grounds."
                    )
                }
            }
        } else if (dialValue != null && scale != null) {
            val unit = if (scale.unit.isBlank()) "" else " ${scale.unit}"
            appendLine("- Setting used: **${scale.format(dialValue)}$unit** on the dial")
            appendLine("- Direction: ${scale.finerWords}")
            scale.micronsPerStep?.let { m -> appendLine("- Burr travel: roughly ${oneDp(m)} microns per step (not the size of the grounds)") }
        } else {
            appendLine("- Setting: ${log.grindSetting.ifBlank { "not recorded" }}")
        }
        scale?.let { rangeLine(it, brewer?.method) }?.let { appendLine(it) }
        if (scale != null && scale.dialReadingHelp.isNotBlank()) appendLine("- How this dial reads: ${scale.dialReadingHelp}")
        recipe?.let { appendLine("- Recipe calls for: ${it.grind}") }
        appendLine()

        appendLine("## What happened")
        appendLine("- Total time: ${log.totalTimeSec.asClock()}")
        log.drawdownSec?.let { appendLine("- Drawdown (last pour to exposed bed): ${it}s") }
        if (log.flowFeel != FlowFeel.Unset) appendLine("- Flow: ${log.flowFeel.label}")
        log.beverageG?.let { appendLine("- In the cup: ${trim(it)} g") }
        log.tdsPercent?.let { appendLine("- TDS: $it%") }
        log.extractionYield?.let { appendLine("- Extraction yield: ${BrewMath.round1(it)}%") }
        appendLine()

        appendLine("## Bed analysis")
        appendLine("- Shape: ${log.bedShape.label}")
        if (log.sludge != Sludge.Unset) appendLine("- Surface: ${log.sludge.label}")
        if (log.channeling != Channeling.Unset) appendLine("- Channelling: ${log.channeling.label}")
        if (log.bedNotes.isNotBlank()) appendLine("- Notes: ${log.bedNotes}")
        appendLine()

        appendLine("## Taste")
        appendLine("| Axis | 0-10 |")
        appendLine("| --- | --- |")
        TasteProfile.AXES.forEach { (name, _, get) -> appendLine("| $name | ${get(log.taste)} |") }
        appendLine()
        appendLine("- Overall: ${log.overall}/10")
        if (log.descriptors.isNotEmpty()) appendLine("- Tasted like: ${log.descriptors.joinToString(", ")}")
        if (log.faults.isNotEmpty()) appendLine("- Problems: ${log.faults.joinToString(", ")}")
        if (log.notes.isNotBlank()) {
            appendLine()
            appendLine("> ${log.notes}")
        }
        if (log.nextTime.isNotBlank()) {
            appendLine()
            appendLine("**My own plan for next time:** ${log.nextTime}")
        }
        appendLine()

        if (history.isNotEmpty()) {
            appendLine("## Recent brews for context")
            appendLine()
            appendLine("| When | Recipe | Grind | Time | Overall | Bed |")
            appendLine("| --- | --- | --- | --- | --- | --- |")
            history.take(8).forEach { h ->
                appendLine(
                    "| ${when_(h.startedAt)} | ${h.recipeName.ifBlank { "-" }} | " +
                        "${h.grindClicks?.toString() ?: h.grindSetting.ifBlank { "-" }} | ${h.totalTimeSec.asClock()} | " +
                        "${h.overall}/10 | ${h.bedShape.label} |"
                )
            }
            appendLine()
        }

        if (photoCount > 0) {
            appendLine(
                (if (photoCount == 1) "1 photo of this brew is" else "$photoCount photos of this brew are") +
                    " in the app (not attached).",
            )
            appendLine()
        }

        appendLine("## What I want back")
        appendLine("1. What most likely explains this cup?")
        if (log.grindClicks != null || (log.grindValue != null && grinder != null)) {
            appendLine(
                "2. The single change to make next brew. If it is a grind change, give it in this " +
                    "grinder's units, the way the dial reads, and say which way to turn, not just " +
                    "\"finer\" or \"coarser\"."
            )
        } else {
            appendLine("2. The single change I should make next brew, and roughly how big a change.")
        }
        appendLine("3. What I should expect to taste if that change worked.")
    }

    /** "- Catalog starting range for pour-over: 75 to 90 clicks (from 1Zpresso; medium confidence). A sanity check, not a target." */
    private fun rangeLine(scale: GrindScale, method: BrewMethod?): String? {
        val range = scale.rangeFor(method) ?: return null
        val unit = if (scale.unit.isBlank()) "" else " ${scale.unit}"
        val who = scale.rangeSources.map { it.who }.filter { it.isNotBlank() }.distinct()
        val source = listOfNotNull(
            who.takeIf { it.isNotEmpty() }?.let { "from ${it.joinToString(", ")}" },
            scale.confidence?.let { "$it confidence" },
        ).joinToString("; ")
        return "- Catalog starting range for ${method?.label?.lowercase()}: " +
            "${scale.format(range.from)} to ${scale.format(range.to)}$unit" +
            (if (source.isEmpty()) "" else " ($source)") + ". A sanity check, not a target."
    }

    /** A run of brews, for asking about trends rather than one cup. */
    fun trendMarkdown(logs: List<BrewLogEntity>, beans: List<BeanEntity>): String = buildString {
        appendLine("# Brew history — last ${counted(logs.size, "brew")}")
        appendLine()
        appendLine("Please look for patterns across these rather than diagnosing any single one.")
        appendLine("I am interested in what my consistent mistakes are.")
        appendLine()
        appendLine("| When | Coffee | Recipe | Dose | Water | Grind | Time | Bed | Overall | Problems |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        logs.forEach { l ->
            val beanName = beans.firstOrNull { it.id == l.beanId }?.display ?: l.beanLabel.ifBlank { "-" }
            appendLine(
                "| ${when_(l.startedAt)} | $beanName | ${l.recipeName.ifBlank { "-" }} | " +
                    "${trim(l.doseG)} | ${trim(l.waterG)} | ${l.grindClicks?.toString() ?: l.grindSetting.ifBlank { "-" }} | " +
                    "${l.totalTimeSec.asClock()} | ${l.bedShape.label} | ${l.overall}/10 | " +
                    "${l.faults.joinToString(", ").ifBlank { "-" }} |"
            )
        }
        appendLine()
        appendLine("## What I want back")
        appendLine("1. Which variable am I changing too often, and which am I never changing?")
        appendLine("2. Is there a bed-shape or timing pattern that tracks with my low scores?")
        appendLine("3. Give me one experiment to run over the next three brews.")
    }

    // ---------------------------------------------------------------------- utils

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else BrewMath.round1(v).toString()

    private fun ratioSuffix(dose: Double, water: Double): String =
        if (dose > 0) "  (1:" + oneDp(water / dose) + ")" else ""

    private fun processLabel(b: BeanEntity): String = Catalog.label(TermKind.Process, b.processId) ?: "Unknown"

    private fun roastLabel(b: BeanEntity): String = Catalog.label(TermKind.RoastLevel, b.roastLevelId) ?: "Unknown"
}
