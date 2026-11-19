package com.laksh.coffee.recipes

import com.laksh.coffee.domain.RecipeValidation
import com.laksh.coffee.domain.StepKind
import com.laksh.coffee.domain.recipes.ExpertRecipes
import com.laksh.coffee.ui.recipes.RecipeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The recipe builder's draft: what New, Duplicate and Edit start from, and what Save writes. */
class RecipeDraftTest {

    private val hoffmann = ExpertRecipes.byId("hoffmann-v60")!!

    @Test
    fun aBuiltInSurvivesTheRoundTrip() {
        // Edit then Save with no change must not move a single number or second.
        ExpertRecipes.all.forEach { r ->
            val back = RecipeDraft.from(r).toRecipe()
            assertEquals(r.id, back.id)
            assertEquals(r.name, back.name)
            assertEquals(r.doseG, back.doseG, 0.05)
            assertEquals(r.waterG, back.waterG, 0.05)
            assertEquals(r.tempC, back.tempC)
            assertEquals("${r.id} total", r.totalSec, back.totalSec)
            assertEquals("${r.id} targets", r.steps.map { it.targetWaterG }, back.steps.map { it.targetWaterG })
            assertEquals("${r.id} durations", r.steps.map { it.durationSec }, back.steps.map { it.durationSec })
            assertEquals(r.steps.map { it.kind }, back.steps.map { it.kind })
            assertFalse(back.isBuiltIn)
        }
    }

    @Test
    fun blankIsAV60WithOneBloomStepAndNothingInvented() {
        val d = RecipeDraft.blank(author = " ", random = Random(1))
        assertTrue(Regex("^custom-[0-9a-f]{8}$").matches(d.id))
        assertEquals("You", d.author)
        assertEquals("hario-v60-02", d.brewer.id)
        assertEquals("", d.dose)
        assertEquals("", d.water)
        assertEquals(listOf(StepKind.Bloom), d.steps.map { it.kind })
        assertEquals("custom", d.fidelity)
        assertNull(d.basedOn)
        // Empty numbers are reported by validation, not silently filled in.
        val codes = RecipeValidation.check(d.toRecipe()).map { it.code }
        assertTrue(RecipeValidation.Code.Name in codes)
        assertTrue(RecipeValidation.Code.Dose in codes)
        assertTrue(RecipeValidation.Code.Water in codes)
    }

    @Test
    fun duplicateIsYoursAndSaysWhereItCameFrom() {
        val d = RecipeDraft.duplicate(hoffmann, author = "Lakshya", random = Random(7))
        assertTrue(Regex("^custom-[0-9a-f]{8}$").matches(d.id))
        assertNotEquals(hoffmann.id, d.id)
        assertEquals("${hoffmann.name} (mine)", d.name)
        assertEquals("Lakshya", d.author)
        assertEquals("custom", d.fidelity)
        assertEquals(hoffmann.id, d.basedOn)
        assertEquals(hoffmann.sources, d.sources)
        assertEquals("Based on ${hoffmann.author}'s ${hoffmann.name}.", d.sourceNote)
        // No name set in Settings: the copy is by "You".
        assertEquals("You", RecipeDraft.duplicate(hoffmann, author = "").author)
        // The copy keeps the numbers.
        val r = d.toRecipe()
        assertEquals(hoffmann.doseG, r.doseG, 0.0)
        assertEquals(hoffmann.waterG, r.waterG, 0.0)
        assertEquals(hoffmann.totalSec, r.totalSec)
        assertFalse(r.isBuiltIn)
    }

    @Test
    fun newIdsDiffer() {
        val ids = (1..200).map { RecipeDraft.newCustomId() }.toSet()
        assertTrue(ids.size > 190)
    }

    @Test
    fun timesAreDerivedCumulativelySoReorderingLeavesNoGap() {
        val d = RecipeDraft.from(hoffmann)
        val moved = d.moveStep(0, 1).toRecipe()
        var t = 0
        moved.steps.forEach { s ->
            assertEquals(t, s.startSec)
            t = s.endSec
        }
        assertEquals(hoffmann.totalSec, moved.totalSec)
        // Out of range moves change nothing.
        assertEquals(d, d.moveStep(0, -1))
        assertEquals(d, d.moveStep(d.steps.lastIndex, 1))
    }

    @Test
    fun addRemoveAndNudge() {
        val d = RecipeDraft.blank("Me")
        val two = d.addStep(StepKind.Pour)
        assertEquals(listOf(StepKind.Bloom, StepKind.Pour), two.steps.map { it.kind })
        assertEquals(30, two.steps[1].durationSec)
        assertEquals("Pour", two.steps[1].title)
        assertNotEquals(two.steps[0].key, two.steps[1].key)
        assertEquals(45 + 30, two.totalSec)

        assertEquals(35, two.nudgeDuration(1, 5).steps[1].durationSec)
        assertEquals(29, two.nudgeDuration(1, -1).steps[1].durationSec)
        // Never below one second, however long the minus button is held.
        assertEquals(1, two.nudgeDuration(1, -500).steps[1].durationSec)

        assertEquals(listOf(StepKind.Pour), two.removeStep(0).steps.map { it.kind })
    }

    @Test
    fun pourDeltaIsSinceThePreviousTarget() {
        val d = RecipeDraft.blank("Me")
            .updateStep(0) { it.copy(pourTo = "50") }
            .addStep(StepKind.Swirl)
            .addStep(StepKind.Pour)
            .updateStep(2) { it.copy(pourTo = "150") }
        assertEquals(50, d.pourDelta(0))
        assertNull(d.pourDelta(1))
        // Skips the swirl, which has no target.
        assertEquals(100, d.pourDelta(2))
        assertNull(d.pourDelta(9))
    }

    @Test
    fun theOneTapFixSetsTheLastTarget() {
        val d = RecipeDraft.blank("Me").copy(name = "Test", dose = "15", water = "250")
            .updateStep(0) { it.copy(pourTo = "50") }
            .addStep(StepKind.Pour)
            .updateStep(1) { it.copy(pourTo = "240") }
            .addStep(StepKind.Drawdown)
        val issue = RecipeValidation.check(d.toRecipe()).single()
        assertEquals(RecipeValidation.Code.LastPour, issue.code)
        val fixed = d.setLastPour(issue.fix!!.waterG)
        assertEquals("250", fixed.steps[1].pourTo)
        assertEquals(emptyList<RecipeValidation.Issue>(), RecipeValidation.check(fixed.toRecipe()))
    }

    @Test
    fun amountsParseAsTypedAndRejectJunk() {
        assertEquals(18.0, RecipeDraft.parseAmount("18")!!, 0.0)
        assertEquals(18.5, RecipeDraft.parseAmount(" 18.5 ")!!, 0.0)
        assertEquals(1.5, RecipeDraft.parseAmount("1,5")!!, 0.0)
        assertEquals(18.0, RecipeDraft.parseAmount("18.")!!, 0.0)
        assertEquals(0.5, RecipeDraft.parseAmount(".5")!!, 0.0)
        assertNull(RecipeDraft.parseAmount(""))
        assertNull(RecipeDraft.parseAmount("abc"))
        assertNull(RecipeDraft.parseAmount("-3"))
        assertNull(RecipeDraft.parseAmount("1e3"))
    }

    @Test
    fun aBlankStepTitleFallsBackToItsKind() {
        val r = RecipeDraft.blank("Me").updateStep(0) { it.copy(title = "  ") }.toRecipe()
        assertEquals("Bloom", r.steps[0].title)
    }

    @Test
    fun editingABuiltInSaysHowToGetTheOriginalBack() {
        val note = RecipeDraft.overrideNote(hoffmann)
        assertTrue(note.contains(hoffmann.name))
        assertTrue(note.contains("Reset to original"))
    }
}
