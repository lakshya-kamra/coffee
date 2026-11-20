package com.laksh.coffee.ui

import com.laksh.coffee.ui.components.SheetRole
import com.laksh.coffee.ui.components.presentsAsDialog
import com.laksh.coffee.ui.nav.AppTab
import com.laksh.coffee.ui.nav.PaneDetail
import com.laksh.coffee.ui.nav.PaneRules
import com.laksh.coffee.ui.nav.PaneSelections
import com.laksh.coffee.ui.nav.Routes
import com.laksh.coffee.ui.nav.ShortcutAction
import com.laksh.coffee.ui.nav.ShortcutKeys
import com.laksh.coffee.ui.theme.NavKind
import com.laksh.coffee.ui.theme.WidthClass
import com.laksh.coffee.ui.theme.WindowClasses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4 §1.1 window classes, §2.2 navigation, §2.3 pane rules, §2.4 sheet roles, §2.7 keys. */
class AdaptiveShellTest {

    // ------------------------------------------------------------ window classes

    @Test
    fun widthBreakpointsMatchTheWebApp() {
        assertEquals(WidthClass.Compact, WindowClasses.widthOf(0f))
        assertEquals(WidthClass.Compact, WindowClasses.widthOf(599.9f))
        assertEquals(WidthClass.Medium, WindowClasses.widthOf(600f))
        assertEquals(WidthClass.Medium, WindowClasses.widthOf(839.9f))
        assertEquals(WidthClass.Expanded, WindowClasses.widthOf(840f))
        assertEquals(WidthClass.Expanded, WindowClasses.widthOf(1199.9f))
        assertEquals(WidthClass.Large, WindowClasses.widthOf(1200f))
        assertEquals(WidthClass.Large, WindowClasses.widthOf(1599.9f))
        assertEquals(WidthClass.ExtraLarge, WindowClasses.widthOf(1600f))
        assertEquals(WidthClass.ExtraLarge, WindowClasses.widthOf(2560f))
    }

    @Test
    fun realDevicesLandWhereTheSpecSays() {
        // Pixel 8 portrait / landscape.
        WindowClasses.of(412f, 915f).let {
            assertEquals(WidthClass.Compact, it.width)
            assertFalse(it.isShort)
            assertFalse(it.isLandscape)
            assertEquals(NavKind.Bar, it.nav(false))
            assertFalse(it.usesPanes)
        }
        WindowClasses.of(915f, 412f).let {
            assertEquals(WidthClass.Expanded, it.width)
            assertTrue(it.isShort)
            assertTrue(it.isLandscape)
            assertEquals(NavKind.Rail, it.nav(false))
        }
        // Pixel Tablet landscape (1280 x 800 dp) and portrait.
        WindowClasses.of(1280f, 800f).let {
            assertEquals(WidthClass.Large, it.width)
            assertEquals(NavKind.Sidebar, it.nav(false))
            assertEquals(NavKind.Rail, it.nav(true))
            assertTrue(it.usesPanes)
        }
        WindowClasses.of(800f, 1280f).let {
            assertEquals(WidthClass.Medium, it.width)
            assertEquals(NavKind.Rail, it.nav(false))
            assertFalse(it.usesPanes)
        }
        // A 640 x 360 phone in landscape: short, rail without labels.
        WindowClasses.of(640f, 360f).let {
            assertEquals(WidthClass.Medium, it.width)
            assertTrue(it.isShort)
            assertEquals(NavKind.Rail, it.nav(false))
        }
    }

    @Test
    fun shortLargeWindowsGetTheRailNotTheSidebar() {
        assertEquals(NavKind.Rail, WindowClasses.of(1400f, 420f).nav(false))
        assertEquals(NavKind.Sidebar, WindowClasses.of(1400f, 480f).nav(false))
        assertEquals(NavKind.Bar, WindowClasses.of(500f, 300f).nav(true))
    }

    @Test
    fun tokensFollowTheClass() {
        assertEquals(16f, WindowClasses.of(390f, 844f).gutter.value)
        assertEquals(24f, WindowClasses.of(768f, 1024f).gutter.value)
        assertEquals(24f, WindowClasses.of(1024f, 768f).gutter.value)
        assertEquals(32f, WindowClasses.of(1440f, 900f).gutter.value)
        assertEquals(32f, WindowClasses.of(1920f, 1080f).gutter.value)
        assertEquals(0f, WindowClasses.of(768f, 1024f).listPaneWidth.value)
        assertEquals(360f, WindowClasses.of(1024f, 768f).listPaneWidth.value)
        assertEquals(400f, WindowClasses.of(1440f, 900f).listPaneWidth.value)
        assertEquals(420f, WindowClasses.of(1920f, 1080f).listPaneWidth.value)
    }

    // ------------------------------------------------------------ pane rules

    @Test
    fun openingSelectsInThePaneOnlyWhenWideAndOnItsOwnTab() {
        val recipe = PaneDetail.Recipe("hoffmann-v60")
        assertTrue(PaneRules.selectsInPane(true, AppTab.Recipes, recipe))
        assertFalse(PaneRules.selectsInPane(false, AppTab.Recipes, recipe))
        // From Today a recipe is still a page over the tabs.
        assertFalse(PaneRules.selectsInPane(true, AppTab.Today, recipe))
        assertTrue(PaneRules.selectsInPane(true, AppTab.Log, PaneDetail.Brew(7)))
        assertFalse(PaneRules.selectsInPane(true, AppTab.Insights, PaneDetail.Brew(7)))
    }

    @Test
    fun narrowingPushesTheChosenDetailAndClearsIt() {
        val sel = PaneSelections(recipe = "r1", log = 9)
        val (detail, next) = PaneRules.narrow(usesPanes = false, atBase = true, selectedTab = AppTab.Recipes, selections = sel)!!
        assertEquals(PaneDetail.Recipe("r1"), detail)
        assertEquals(Routes.recipe("r1", "recipes"), detail.route)
        // The other tab keeps its choice for when the window widens again.
        assertEquals(PaneSelections(recipe = null, log = 9), next)
    }

    @Test
    fun narrowingWaitsForTheBaseAndNeedsAChoice() {
        val sel = PaneSelections(recipe = "r1")
        assertNull(PaneRules.narrow(usesPanes = true, atBase = true, selectedTab = AppTab.Recipes, selections = sel))
        // A brew opened from the pane is on top: wait until it closes.
        assertNull(PaneRules.narrow(usesPanes = false, atBase = false, selectedTab = AppTab.Recipes, selections = sel))
        // The Log has nothing chosen.
        assertNull(PaneRules.narrow(usesPanes = false, atBase = true, selectedTab = AppTab.Log, selections = sel))
        assertNull(PaneRules.narrow(usesPanes = false, atBase = true, selectedTab = AppTab.Today, selections = sel))
    }

    @Test
    fun wideningMovesAPushedDetailOfThisTabIntoThePane() {
        val next = PaneRules.widen(true, stackDepth = 1, top = PaneDetail.Brew(12), selectedTab = AppTab.Log, selections = PaneSelections())
        assertEquals(PaneSelections(log = 12), next)
        val replaced = PaneRules.widen(true, 1, PaneDetail.Recipe("b"), AppTab.Recipes, PaneSelections(recipe = "a"))
        assertEquals("b", replaced?.recipe)
    }

    @Test
    fun wideningLeavesOtherStacksAlone() {
        val empty = PaneSelections()
        assertNull(PaneRules.widen(false, 1, PaneDetail.Brew(12), AppTab.Log, empty))
        // Something above the detail (Review over it): not at index 1.
        assertNull(PaneRules.widen(true, 2, PaneDetail.Brew(12), AppTab.Log, empty))
        // Opened from Today or Insights: it stays a page.
        assertNull(PaneRules.widen(true, 1, PaneDetail.Recipe("r"), AppTab.Today, empty))
        assertNull(PaneRules.widen(true, 1, PaneDetail.Brew(3), AppTab.Insights, empty))
        assertNull(PaneRules.widen(true, 1, null, AppTab.Log, empty))
    }

    @Test
    fun narrowThenWidenRoundTripsTheSelection() {
        val start = PaneSelections(recipe = "kalita-wave")
        val (detail, cleared) = PaneRules.narrow(false, true, AppTab.Recipes, start)!!
        val back = PaneRules.widen(true, 1, PaneDetail.fromRoute(detail.route), AppTab.Recipes, cleared)
        assertEquals(start, back)
    }

    @Test
    fun clearingReturnsThePaneToItsDefault() {
        val sel = PaneSelections(recipe = "r", log = 4)
        assertEquals(PaneSelections(log = 4), sel.cleared(AppTab.Recipes))
        assertEquals(PaneSelections(recipe = "r"), sel.cleared(AppTab.Log))
        assertEquals(sel, sel.cleared(AppTab.Gear))
        assertNull(PaneSelections().forTab(AppTab.Recipes))
        assertNull(sel.forTab(AppTab.Today))
    }

    @Test
    fun routesParseBackToPaneDetails() {
        assertEquals(PaneDetail.Recipe("hoffmann-v60"), PaneDetail.fromRoute(Routes.recipe("hoffmann-v60", "home")))
        // Imported ids with characters a path cannot hold survive the round trip.
        assertEquals(PaneDetail.Recipe("my/v60 #2"), PaneDetail.fromRoute(Routes.recipe("my/v60 #2")))
        assertEquals(PaneDetail.Brew(42), PaneDetail.fromRoute(Routes.brewDetail(42)))
        assertNull(PaneDetail.fromRoute(Routes.brew("hoffmann-v60")))
        assertNull(PaneDetail.fromRoute(Routes.review(42)))
        assertNull(PaneDetail.fromRoute("brewDetail/0"))
        assertEquals(PaneDetail.Brew(5), PaneDetail.of(Routes.BREW_DETAIL, null, 5))
        assertEquals(PaneDetail.Recipe("x"), PaneDetail.of(Routes.RECIPE, "x", 0))
        assertNull(PaneDetail.of(Routes.BREW, "x", 0))
    }

    // ------------------------------------------------------------ sheets

    @Test
    fun sheetRolesPresentByWindowClass() {
        val phone = WindowClasses.of(390f, 844f)
        val tabletPortrait = WindowClasses.of(800f, 1280f)
        val tabletLandscape = WindowClasses.of(1280f, 800f)
        val phoneLandscape = WindowClasses.of(844f, 390f)
        SheetRole.entries.forEach { assertFalse("$it on a phone", it.presentsAsDialog(phone)) }
        assertTrue(SheetRole.Confirm.presentsAsDialog(tabletPortrait))
        assertFalse(SheetRole.Editor.presentsAsDialog(tabletPortrait))
        assertFalse(SheetRole.Picker.presentsAsDialog(tabletPortrait))
        SheetRole.entries.forEach { assertTrue("$it on a tablet", it.presentsAsDialog(tabletLandscape)) }
        SheetRole.entries.forEach { assertTrue("$it in short landscape", it.presentsAsDialog(phoneLandscape)) }
    }

    // ------------------------------------------------------------ keys

    @Test
    fun plainKeysMapToShellActions() {
        assertEquals(ShortcutAction.SwitchTab(AppTab.Today), ShortcutKeys.plain('1'))
        assertEquals(ShortcutAction.SwitchTab(AppTab.Gear), ShortcutKeys.plain('5'))
        assertNull(ShortcutKeys.plain('6'))
        assertEquals(ShortcutAction.FocusSearch, ShortcutKeys.plain('/'))
        assertEquals(ShortcutAction.ShowShortcuts, ShortcutKeys.plain('?'))
        assertEquals(ShortcutAction.ToggleSidebar, ShortcutKeys.plain('['))
        assertEquals(ShortcutAction.StartBrew, ShortcutKeys.plain('b'))
        assertNull(ShortcutKeys.plain('x'))
    }
}
