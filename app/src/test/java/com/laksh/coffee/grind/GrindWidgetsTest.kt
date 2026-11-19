package com.laksh.coffee.grind

import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.GrindRange
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.ui.components.DialPart
import com.laksh.coffee.ui.components.DialSteps
import com.laksh.coffee.ui.components.GrindRanges
import com.laksh.coffee.ui.components.GrindStart
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.storedLine
import com.laksh.coffee.ui.screens.gear.GrinderMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure halves of the grind widgets and of "pick your grinder from the list": dial steppers,
 * range wording, the brew-setup starting point, and how a catalog entry becomes the user's row.
 */
class GrindWidgetsTest {

    private val kUltraEntry = Catalog.grinder("1zpresso-k-ultra")!!

    /** A K-Ultra as the picker would save it. */
    private val kUltra = GrinderMapping.fill(GrinderEntity(brand = "", model = ""), kUltraEntry)

    /** The owner's 1.x row: 10 clicks a turn, linked to the catalog at startup, notice pending. */
    private val legacy = GrinderEntity(
        brand = "1Zpresso", model = "K-Ultra", settingStyle = SettingStyle.Clicks, clicksPerRotation = 10,
        maxSetting = 200.0, defaultFilterSetting = 75.0, catalogId = "1zpresso-k-ultra",
    )

    // ------------------------------------------------------------------ catalog fill

    @Test
    fun fillCopiesIdentityAndScaleButNeverYourUsualSettings() {
        assertEquals("1Zpresso", kUltra.brand)
        assertEquals("K-Ultra", kUltra.model)
        assertEquals(BurrType.Conical, kUltra.burrType)
        assertEquals("48", kUltra.burrSizeMm)
        assertEquals(SettingStyle.Clicks, kUltra.settingStyle)
        assertEquals(100, kUltra.clicksPerRotation)
        assertEquals(20.0, kUltra.micronsPerClick!!, 0.0)
        assertTrue(kUltra.lowerIsFiner)
        assertEquals("1zpresso-k-ultra", kUltra.catalogId)
        assertEquals("", kUltra.dialFormat)
        assertNull(kUltra.defaultFilterSetting)
        assertNull(kUltra.defaultEspressoSetting)
        // And the result reads the way the maker writes it: 75 clicks is "7.5".
        assertEquals(DialFormat.NumberTick(100, 10), kUltra.scale().dial)
        assertEquals("7.5", kUltra.scale().reading(75.0))

        val mine = legacy.copy(defaultFilterSetting = 75.0, notes = "burrs aligned", isDefault = true)
        val filled = GrinderMapping.fill(mine, kUltraEntry)
        assertEquals(75.0, filled.defaultFilterSetting!!, 0.0)
        assertEquals("burrs aligned", filled.notes)
        assertTrue(filled.isDefault)
    }

    @Test
    fun everyCatalogGrinderFillsToAUsableRow() {
        Catalog.grinders.forEach { c ->
            val row = GrinderMapping.fill(GrinderEntity(brand = "", model = ""), c)
            assertEquals(c.id, row.catalogId)
            assertTrue("${c.id} step", row.step > 0)
            assertTrue("${c.id} range", row.maxSetting >= row.minSetting)
            assertFalse("${c.id} reads as edited straight from the list", GrinderMapping.edited(row, c))
            // The starting ranges show up for a row that agrees with the list.
            assertEquals("${c.id} ranges", c.ranges.keys.mapNotNull { BrewMethod.ofOrNull(it) }.toSet(), row.scale().ranges.keys)
            assertTrue("${c.id} words", GrinderMapping.scaleWords(row).isNotBlank())
        }
    }

    @Test
    fun useTheseNumbersChangesOnlyTheScale() {
        assertTrue(GrinderMapping.edited(legacy, kUltraEntry))
        assertEquals("1Zpresso says 100 clicks a turn; yours says 10", GrinderMapping.noticeWords(legacy, kUltraEntry))
        // Before: the 1.x reading, and no ranges, because the turn size disagrees.
        assertEquals("7 turns + 5 · dial 7.5", legacy.scale().describe(75.0))
        assertTrue(legacy.scale().ranges.isEmpty())

        val after = GrinderMapping.withScale(legacy, kUltraEntry)
        // 75 stays 75: only how it is read out changes.
        assertEquals(75.0, after.defaultFilterSetting!!, 0.0)
        assertEquals(legacy.brand, after.brand)
        assertEquals(legacy.uid, after.uid)
        assertEquals(100, after.clicksPerRotation)
        assertEquals(20.0, after.micronsPerClick!!, 0.0)
        assertEquals("dial 7.5 · burr travel ≈ 1.5 mm", after.scale().describe(75.0))
        assertFalse(GrinderMapping.edited(after, kUltraEntry))
        assertNotNull(after.scale().rangeFor(BrewMethod.PourOver))
    }

    @Test
    fun scaleWordsReadLikeTheSpec() {
        assertEquals("clicks · fewer is finer · 100 a turn · 20 µm a click", GrinderMapping.scaleWords(kUltra))
        val dial = GrinderEntity(
            brand = "Test", model = "Dial", settingStyle = SettingStyle.Numbers, minSetting = 1.0, maxSetting = 40.0,
        )
        assertEquals("numbered dial · lower is finer · 1 to 40", GrinderMapping.scaleWords(dial))
    }

    @Test
    fun mostBrewedMethod() {
        assertNull(GrinderMapping.mostBrewedMethod(emptyList()))
        assertEquals(
            BrewMethod.AeroPress,
            GrinderMapping.mostBrewedMethod(listOf(BrewMethod.PourOver, BrewMethod.AeroPress, BrewMethod.AeroPress)),
        )
    }

    // ------------------------------------------------------------------- dial steppers

    @Test
    fun numberedDialSplitsIntoTurnsNumberAndMark() {
        val s = kUltra.scale()
        val r = DialSteps.split(s, 75.0)
        assertEquals(Triple(0, 7, 5), Triple(r.turns, r.number, r.tick))
        val past = DialSteps.split(s, 123.0)
        assertEquals(Triple(1, 2, 3), Triple(past.turns, past.number, past.tick))
        // The dial's own inverse agrees with the split.
        assertEquals(123.0, s.fromReading(past)!!, 0.0)
    }

    @Test
    fun steppingCarriesLikeARealDial() {
        val s = kUltra.scale()
        // Mark 9 plus one is the next number, mark 0.
        assertEquals(80.0, DialSteps.step(s, 79.0, DialPart.Mark, 1)!!, 0.0)
        assertEquals(85.0, DialSteps.step(s, 75.0, DialPart.Number, 1)!!, 0.0)
        assertEquals(175.0, DialSteps.step(s, 75.0, DialPart.Turns, 1)!!, 0.0)
        assertEquals(74.0, DialSteps.step(s, 75.0, DialPart.Mark, -1)!!, 0.0)
        // Held inside the grinder's range: nothing below zero, nothing past the top.
        assertNull(DialSteps.step(s, 0.0, DialPart.Mark, -1))
        assertEquals(0.0, DialSteps.step(s, 5.0, DialPart.Number, -1)!!, 0.0)
        assertNull(DialSteps.step(s, s.max, DialPart.Mark, 1))
        // Nothing chosen yet: the first tap starts from the bottom of the range.
        assertEquals(1.0, DialSteps.step(s, null, DialPart.Mark, 1)!!, 0.0)
        assertEquals(2, DialSteps.maxTurns(s))
    }

    @Test
    fun turnsAndClicksDialsHaveNoNumberPart() {
        val c40 = GrinderEntity(
            brand = "Test", model = "Clicky", settingStyle = SettingStyle.Clicks, clicksPerRotation = 12, maxSetting = 40.0,
        ).scale()
        assertEquals(DialFormat.TurnsAndClicks(12), c40.dial)
        assertEquals(0, DialSteps.size(c40, DialPart.Number))
        assertNull(DialSteps.step(c40, 20.0, DialPart.Number, 1))
        val r = DialSteps.split(c40, 26.0)
        assertEquals(2 to 2, r.turns to r.tick)
        assertEquals(27.0, DialSteps.step(c40, 26.0, DialPart.Mark, 1)!!, 0.0)
        assertEquals(38.0, DialSteps.step(c40, 26.0, DialPart.Turns, 1)!!, 0.0)
        assertEquals(3, DialSteps.maxTurns(c40))
    }

    @Test
    fun plainDialsHaveNoSteppers() {
        val plain = GrinderEntity(brand = "Test", model = "Dial", settingStyle = SettingStyle.Numbers, maxSetting = 40.0).scale()
        assertEquals(DialFormat.Plain, plain.dial)
        DialPart.entries.forEach { assertNull(DialSteps.step(plain, 14.0, it, 1)) }
        assertEquals(0, DialSteps.maxTurns(plain))
        assertEquals(14.5, DialSteps.split(plain, 14.5).value!!, 0.0)
    }

    @Test
    fun theStoredLineSaysWhatTheAppKeeps() {
        assertEquals("Dial 7.5 = 75 clicks (what the app stores)", storedLine(kUltra.scale(), 75.0))
        assertEquals("Dial 1 turn + 2.3 = 123 clicks (what the app stores)", storedLine(kUltra.scale(), 123.0))
        assertEquals("Dial 10.0 = 100 clicks (what the app stores)", storedLine(kUltra.scale(), 100.0))
        assertTrue(storedLine(kUltra.scale(), null).contains("total clicks from zero"))
    }

    // ---------------------------------------------------------------- starting ranges

    @Test
    fun rangeWordsCarryTheStoredUnitsAndTheDialReading() {
        val s = kUltra.scale()
        val pour = s.rangeFor(BrewMethod.PourOver)!!
        assertEquals("75–90 clicks · dial 7.5–9.0", GrindRanges.words(s, pour))
        assertEquals("Pour-over 75–90 clicks · dial 7.5–9.0", GrindRanges.line(s, BrewMethod.PourOver))
        // A range that ends exactly on a full turn reads the way the maker writes it.
        assertEquals("90–100 clicks · dial 9.0–10.0", GrindRanges.words(s, s.rangeFor(BrewMethod.FrenchPress)!!))
        // Past one turn the reading has words in it, so the two ends are joined with "to".
        assertEquals("90–120 clicks · dial 9.0 to 1 turn + 2.0", GrindRanges.words(s, GrindRange(90.0, 120.0)))
        // A range that starts on the turn and runs past it keeps both ends in turns.
        assertEquals("100–170 clicks · dial 1 turn + 0.0 to 1 turn + 7.0", GrindRanges.words(s, GrindRange(100.0, 170.0)))
        assertEquals("100 clicks · dial 10.0", GrindRanges.words(s, GrindRange(100.0, 100.0)))
        // The method being brewed comes first; the rest keep a fixed order.
        assertEquals(BrewMethod.AeroPress, GrindRanges.ordered(s, BrewMethod.AeroPress).first().first)
        assertEquals(s.ranges.size, GrindRanges.ordered(s, BrewMethod.AeroPress).size)
        // A method the list has no range for: the others still show, in their own order.
        assertEquals(s.ranges.size, GrindRanges.ordered(s, BrewMethod.ColdBrew).size)
    }

    @Test
    fun sourcesAreNamedOnceAndConfidenceAlwaysHasAWord() {
        val s = kUltra.scale()
        val who = GrindRanges.sources(s).map { it.who }
        assertEquals(who.distinct(), who)
        assertEquals("1Zpresso", who.first())
        assertTrue(GrindRanges.credit(s)!!.startsWith("1Zpresso"))
        assertEquals(Status.Good to "From the maker", GrindRanges.confidence("high"))
        assertEquals(Status.Attention to "Check your manual", GrindRanges.confidence("medium"))
        assertEquals(Status.Attention to "Unverified", GrindRanges.confidence("low"))
        assertNull(GrindRanges.confidence(null))
    }

    @Test
    fun aRowThatDisagreesWithTheListShowsNoRangesAtAll() {
        val s = legacy.scale()
        assertNull(GrindRanges.line(s, BrewMethod.PourOver))
        assertNull(GrindStart.of(s, BrewMethod.PourOver))
        assertNull(GrindRanges.credit(s))
    }

    @Test
    fun theStartingPointIsTheMiddleOfTheRangeOnARealStep() {
        val start = GrindStart.of(kUltra.scale(), BrewMethod.PourOver)!!
        // 75 to 90: the middle is 82.5, which rounds to a whole click inside the range.
        assertTrue(start.value in 75.0..90.0)
        assertEquals(start.value, Math.rint(start.value), 0.0)
        assertTrue(start.value == 82.0 || start.value == 83.0)
        assertEquals(start.value.toLong().toString(), start.label)
        assertTrue(start.detail.contains("75–90"))
        assertTrue(start.detail.contains("1Zpresso"))
        assertNull(GrindStart.of(kUltra.scale(), BrewMethod.ColdBrew))
        assertNull(GrindStart.of(kUltra.scale(), null))
    }

    @Test
    fun everyCatalogRangeGivesAStartInsideIt() {
        Catalog.grinders.forEach { c ->
            val s = GrinderMapping.fill(GrinderEntity(brand = "", model = ""), c).scale()
            s.ranges.forEach { (method, range) ->
                val start = GrindStart.of(s, method)
                assertNotNull("${c.id} $method", start)
                assertTrue("${c.id} $method start ${start!!.value}", start.value >= range.from && start.value <= range.to)
                assertTrue(GrindRanges.words(s, range).isNotBlank())
            }
        }
    }
}
