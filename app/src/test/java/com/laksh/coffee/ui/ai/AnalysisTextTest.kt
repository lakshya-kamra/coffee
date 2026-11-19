package com.laksh.coffee.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The display-only rewrites the analysis cards apply to model text. */
class AnalysisTextTest {

    @Test
    fun `a verdict opening the headline is dropped, since the chip says it`() {
        assertEquals("Sweet, clean and on time. Change nothing.", headlineWithoutVerdict("Dialled in: sweet, clean and on time. Change nothing."))
        assertEquals("Grind finer.", headlineWithoutVerdict("Needs a tweak — grind finer."))
        assertEquals("Channelling.", headlineWithoutVerdict("Something’s wrong: channelling."))
        assertEquals("Keep it", headlineWithoutVerdict("Dialed in - keep it"))
        assertEquals("", headlineWithoutVerdict("Dialled in."))
    }

    @Test
    fun `a verdict word inside a sentence is left alone`() {
        assertEquals("Close, but one click finer.", headlineWithoutVerdict("Close, but one click finer."))
        assertEquals("Fault-free and sweet.", headlineWithoutVerdict("Fault-free and sweet."))
        assertEquals("Closer than last time.", headlineWithoutVerdict("Closer than last time."))
        assertEquals("Fast and harsh: too coarse.", headlineWithoutVerdict("Fast and harsh: too coarse."))
    }

    @Test
    fun `arrows are real and never start a line`() {
        assertEquals("go 2 clicks finer, 66 → 64.", "go 2 clicks finer, 66 -> 64.".arrows())
        assertEquals("66 → 64", "66 → 64".arrows())
    }

    @Test
    fun `the tested value leaves the title when it is a list item of its own`() {
        assertEquals("66 clicks, 30 g to 500 g", withoutTested("66 clicks, 100 °C, 30 g to 500 g", "100", "°C"))
        assertEquals("66 clicks", withoutTested("66 clicks, 94 °C", "94", "°C"))
        assertEquals("100 °C", withoutTested("64 clicks · 100 °C", "64", "clk"))
    }

    @Test
    fun `the tested value stays put when cutting it would strand words`() {
        assertNull(withoutTested("66 clicks at 94 °C", "94", "°C"))
        assertNull(withoutTested("94 °C", "94", "°C"))
        assertNull(withoutTested("66 clicks, 30 g", "94", "°C"))
    }
}
