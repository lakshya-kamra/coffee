package com.laksh.coffee.ui.theme

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Window width classes (v4 §1.1). The same breakpoints as the web app, in dp = CSS px. */
enum class WidthClass {
    /** < 600: phones. */
    Compact,

    /** 600-839: tablet portrait, unfolded foldables, small windows. */
    Medium,

    /** 840-1199: tablet landscape, small laptops. */
    Expanded,

    /** 1200-1599: laptops and desktops. */
    Large,

    /** >= 1600. */
    ExtraLarge;

    fun atLeast(other: WidthClass): Boolean = ordinal >= other.ordinal
}

/** What carries the five sections at a window class. */
enum class NavKind { Bar, Rail, Sidebar }

/**
 * The window class: computed once at the app root and read through [LocalWindowClass], so a resize
 * recomposes the shell and the few screens that read it, never every row.
 */
@Immutable
data class WindowClass(
    val width: WidthClass,
    /** Height < 480: landscape phones. The brew timer and the rail have their own short layouts. */
    val isShort: Boolean,
    val isLandscape: Boolean,
) {
    /** Recipes, Log and Gear show a list beside a detail pane. */
    val usesPanes: Boolean get() = width.atLeast(WidthClass.Expanded)

    /**
     * Large and up get the labelled sidebar unless it was folded to the rail with `[`. A short
     * window gets the rail without labels (or the bar, below 600 wide): a sidebar has no room.
     */
    fun nav(sidebarCollapsed: Boolean): NavKind = when {
        width == WidthClass.Compact -> NavKind.Bar
        width.atLeast(WidthClass.Large) && !sidebarCollapsed && !isShort -> NavKind.Sidebar
        else -> NavKind.Rail
    }

    /** Tooltips on icon-only buttons: pointer-sized windows only. */
    val showsTooltips: Boolean get() = usesPanes

    /** Side gutter for screen content (§1.2). */
    val gutter: Dp
        get() = when {
            isShort -> 16.dp
            width == WidthClass.Compact -> 16.dp
            width.atLeast(WidthClass.Large) -> 32.dp
            else -> 24.dp
        }

    /** Width of the list pane beside a detail (§1.2); 0 when there are no panes. */
    val listPaneWidth: Dp
        get() = when (width) {
            WidthClass.Compact, WidthClass.Medium -> 0.dp
            WidthClass.Expanded -> 360.dp
            WidthClass.Large -> 400.dp
            WidthClass.ExtraLarge -> 420.dp
        }

    companion object {
        val Phone = WindowClass(WidthClass.Compact, isShort = false, isLandscape = false)
    }
}

/** Pure, so the mapping is unit-tested. */
object WindowClasses {
    const val MEDIUM_DP = 600f
    const val EXPANDED_DP = 840f
    const val LARGE_DP = 1200f
    const val EXTRA_LARGE_DP = 1600f
    const val SHORT_DP = 480f

    fun widthOf(widthDp: Float): WidthClass = when {
        widthDp >= EXTRA_LARGE_DP -> WidthClass.ExtraLarge
        widthDp >= LARGE_DP -> WidthClass.Large
        widthDp >= EXPANDED_DP -> WidthClass.Expanded
        widthDp >= MEDIUM_DP -> WidthClass.Medium
        else -> WidthClass.Compact
    }

    fun of(widthDp: Float, heightDp: Float): WindowClass = WindowClass(
        width = widthOf(widthDp),
        isShort = heightDp < SHORT_DP,
        isLandscape = widthDp > heightDp,
    )
}

/** Static: only the root provides it, and a class change should redraw the shell anyway. */
val LocalWindowClass = staticCompositionLocalOf { WindowClass.Phone }

/** The large-window sidebar folded to the rail (DataStore `sidebar_collapsed`, per device). */
val LocalSidebarCollapsed = staticCompositionLocalOf { false }

/**
 * The current window's class. The adaptive library reads the window's own size, so split screen,
 * freeform windows and folds are all covered; only a class change makes a new value.
 */
// currentWindowDpSize is marked experimental in adaptive 1.2.0; it is the plain window size.
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberWindowClass(): WindowClass {
    val size = currentWindowDpSize()
    val w = size.width.value
    val h = size.height.value
    val width = WindowClasses.widthOf(w)
    val short = h < WindowClasses.SHORT_DP
    val landscape = w > h
    return remember(width, short, landscape) { WindowClass(width, short, landscape) }
}
