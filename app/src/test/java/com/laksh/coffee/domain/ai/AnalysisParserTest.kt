package com.laksh.coffee.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisParserTest {

    private val brewJson = """
        {
          "verdict": "needs_change",
          "headline": "Fast and harsh: the grind is still too coarse.",
          "diagnosis": "It ran 41 s fast and tasted bitter and drying.",
          "confidence": "medium",
          "evidence": ["2:49 against 3:30"],
          "change": {
            "variable": "grind",
            "instruction": "77 -> 67 clicks, one full turn finer",
            "from": "77 clicks",
            "to": "67 clicks",
            "grindClicksFrom": 77,
            "grindClicksTo": 67
          },
          "expectIfRight": "Around 3:30 and sweeter.",
          "ifWrong": "Heavier and more bitter: go back 4 clicks.",
          "keep": "Same beans and pours.",
          "dataWarnings": []
        }
    """.trimIndent()

    private fun brew(raw: String): BrewAnalysis {
        val parsed = AnalysisParser.parseBrew(raw)
        assertTrue("expected Ok, got $parsed", parsed is Parsed.Ok)
        return (parsed as Parsed.Ok).value
    }

    private fun trend(raw: String): TrendAnalysis {
        val parsed = AnalysisParser.parseTrend(raw)
        assertTrue("expected Ok, got $parsed", parsed is Parsed.Ok)
        return (parsed as Parsed.Ok).value
    }

    @Test
    fun plainReplyParses() {
        val a = brew(brewJson)
        assertEquals("needs_change", a.verdict)
        assertEquals(67, a.change.grindClicksTo)
        assertEquals(listOf("2:49 against 3:30"), a.evidence)
    }

    @Test
    fun fencedReplyParses() {
        val a = brew("```json\n$brewJson\n```")
        assertEquals("Fast and harsh: the grind is still too coarse.", a.headline)
    }

    @Test
    fun thinkingPrefixIsIgnored() {
        val a = brew("<think>The user brewed fast. {maybe} Let me think about {grind}.</think>\n$brewJson")
        assertEquals("grind", a.change.variable)
    }

    @Test
    fun unclosedThinkingPrefixIsIgnored() {
        val a = brew("<think>Reasoning that never closes\n$brewJson")
        assertEquals("medium", a.confidence)
    }

    @Test
    fun proseWithBracesAroundTheAnswerStillParses() {
        val a = brew("Here you go {as asked}:\n$brewJson\nHope that helps {really}.")
        assertEquals("needs_change", a.verdict)
    }

    @Test
    fun verdictIsNormalised() {
        assertEquals("needs_change", brew("""{"verdict": "Needs change", "headline": "h"}""").verdict)
        assertEquals("dialled_in", brew("""{"verdict": "dialed-in", "headline": "h"}""").verdict)
        assertEquals("", brew("""{"verdict": "great", "headline": "h"}""").verdict)
    }

    @Test
    fun grindClicksWithUnitsBecomeNumbers() {
        val a = brew("""{"headline": "h", "change": {"variable": "Grind", "grindClicksFrom": "77", "grindClicksTo": "67 clicks"}}""")
        assertEquals(77, a.change.grindClicksFrom)
        assertEquals(67, a.change.grindClicksTo)
        assertEquals("grind", a.change.variable)
    }

    @Test
    fun loosenessIsTolerated() {
        val a = brew(
            """
            {
              // a comment
              "headline": "Close.",
              "confidence": "High",
              "evidence": "one fact",
              "keep": null,
            }
            """.trimIndent(),
        )
        assertEquals("high", a.confidence)
        assertEquals(listOf("one fact"), a.evidence)
        assertEquals("", a.keep)
    }

    @Test
    fun unknownConfidenceFallsBackToLow() {
        assertEquals("low", brew("""{"headline": "h", "confidence": "very sure"}""").confidence)
    }

    @Test
    fun proseOnlyStaysRaw() {
        val raw = "The grind is too coarse. Go one full turn finer next time."
        val parsed = AnalysisParser.parseBrew(raw)
        assertTrue(parsed is Parsed.Raw)
        assertEquals(raw, (parsed as Parsed.Raw).text)
    }

    @Test
    fun blankReplyStaysRaw() {
        assertTrue(AnalysisParser.parseBrew("   ") is Parsed.Raw)
        assertTrue(AnalysisParser.parseTrend("") is Parsed.Raw)
    }

    @Test
    fun brewWithoutAHeadlineIsNotUnwrappedIntoItsChange() {
        val a = brew("""{"diagnosis": "Ran fast.", "change": {"variable": "grind", "grindClicksTo": 67}}""")
        assertEquals("Ran fast.", a.diagnosis)
        assertEquals(67, a.change.grindClicksTo)
    }

    @Test
    fun trendWithoutAHeadlineIsNotUnwrappedIntoItsExperiment() {
        val t = trend(
            """{"patterns": [{"title": "Fast brews score low", "brewIds": ["#3", "6", "x"]}], "experiment": {"goal": "g"}}""",
        )
        assertEquals("Fast brews score low", t.patterns.single().title)
        assertEquals(listOf(3L, 6L), t.patterns.single().brewIds)
        assertEquals("g", t.experiment.goal)
    }

    @Test
    fun singleWrapperKeyIsUnwrapped() {
        val a = brew("""{"analysis": $brewJson}""")
        assertEquals("needs_change", a.verdict)
    }

    @Test
    fun trendFieldsAreNormalised() {
        val t = trend(
            """
            {
              "trajectory": "Improving",
              "headline": "Fast brews score low.",
              "patterns": {"title": "One pattern", "strength": "Too little data", "tone": "ATTENTION"},
              "changedOften": "grind",
              "experiment": {"brews": [{"label": "Brew 1", "grindClicks": "66 clicks"}]},
              "perCoffee": {"Tidewater — Harbour Blend": "Dialled at 66."}
            }
            """.trimIndent(),
        )
        assertEquals("improving", t.trajectory)
        assertEquals("too_little_data", t.patterns.single().strength)
        assertEquals("attention", t.patterns.single().tone)
        assertEquals(listOf("grind"), t.changedOften)
        assertEquals(66, t.experiment.brews.single().grindClicks)
        assertEquals(CoffeeNote("Tidewater — Harbour Blend", "Dialled at 66."), t.perCoffee.single())
    }

    @Test
    fun unknownToneFallsBackToNeutral() {
        val t = trend("""{"headline": "h", "patterns": [{"title": "p", "tone": "purple"}]}""")
        assertEquals("neutral", t.patterns.single().tone)
        assertEquals("weak", t.patterns.single().strength)
    }

    @Test
    fun extractFindsTheAnchoredObject() {
        val found = AnalysisParser.extractJsonObject("note {x} then {\"headline\": \"h\"} end", listOf("headline"))
        assertNotNull(found)
        assertEquals("{\"headline\": \"h\"}", found)
        assertNull(AnalysisParser.extractJsonObject("no braces here", listOf("headline")))
    }

    @Test
    fun grindValuesForNumberedDialsAreReadAsNumbers() {
        val raw = """
            {"verdict":"close","headline":"A touch coarse.","diagnosis":"Ran 12 s fast.","confidence":"medium",
             "change":{"variable":"grind","instruction":"14 -> 13 on the dial, one step finer",
                       "grindClicksFrom":null,"grindClicksTo":null,"grindValueFrom":"14","grindValueTo":"13.5 on the dial"}}
        """.trimIndent()
        val change = brew(raw).change
        assertNull(change.grindClicksTo)
        assertEquals(14.0, change.grindValueFrom!!, 0.0)
        assertEquals(13.5, change.grindValueTo!!, 0.0)
    }

    @Test
    fun grindValuesThatAreNotNumbersBecomeNull() {
        val raw = """
            {"verdict":"close","headline":"A touch coarse.","diagnosis":"x",
             "change":{"variable":"grind","instruction":"a bit finer","grindValueFrom":"unknown","grindValueTo":[]}}
        """.trimIndent()
        val change = brew(raw).change
        assertNull(change.grindValueFrom)
        assertNull(change.grindValueTo)
    }
}
