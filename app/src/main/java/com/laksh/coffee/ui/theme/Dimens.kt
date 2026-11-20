package com.laksh.coffee.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing and sizes on a 4-pt grid. Screens use these names instead of re-deriving numbers. */
object Dimens {
    val Gutter = 16.dp
    val CardPadding = 18.dp
    val CardGap = 12.dp
    val SectionGap = 24.dp
    val EyebrowGap = 10.dp

    /** Clears the floating bottom bar; add the navigation-bar inset via [listBottomPadding]. */
    val ListBottomInset = 112.dp

    val CellPadV = 12.dp
    val CellPadH = 14.dp

    val PrimaryHeight = 56.dp
    val PrimaryInCard = 52.dp
    val SecondaryHeight = 48.dp
    val QuietHeight = 40.dp
    val IconWell = 40.dp
    val StepperWell = 44.dp
    val PlayButton = 64.dp

    val Chip = 34.dp
    val StatusChipHeight = 22.dp
    val Field = 52.dp
    val FieldMultiline = 88.dp

    val SettingsRow = 56.dp
    val LogRowHeight = 68.dp
    val BottomBarHeight = 60.dp
    val BottomBarItem = 52.dp

    /** The rail (medium and expanded, and large with the sidebar folded). */
    val Rail = 80.dp

    /** The labelled sidebar (large and up). */
    val Sidebar = 248.dp

    /** Clears nothing but the last row's breathing room: no bar floats over rail layouts. */
    val ListBottomInsetNoBar = 32.dp

    // The window-class tokens (v4 §1.2). Composable, so the constants above stay usable anywhere.

    /** Width of the navigation beside the content: 0 with the bottom bar. */
    val navWidth: Dp
        @Composable
        @ReadOnlyComposable
        get() = when (LocalWindowClass.current.nav(LocalSidebarCollapsed.current)) {
            NavKind.Bar -> 0.dp
            NavKind.Rail -> Rail
            NavKind.Sidebar -> Sidebar
        }

    /** Side gutter: 16 on phones, 24 at medium and expanded, 32 at large and up. */
    val gutter: Dp
        @Composable
        @ReadOnlyComposable
        get() = LocalWindowClass.current.gutter

    /** What a scrolling tab root leaves below its last row, before the navigation-bar inset. */
    val listBottomInset: Dp
        @Composable
        @ReadOnlyComposable
        get() = if (LocalWindowClass.current.nav(LocalSidebarCollapsed.current) == NavKind.Bar) {
            ListBottomInset
        } else {
            ListBottomInsetNoBar
        }
}

/** Corner radii. Card (22) is also the clip for every shared element. */
object Radii {
    val Bar = 6.dp
    val Skeleton = 10.dp
    val Well = 14.dp
    val Button = 16.dp
    val Primary = 18.dp
    val Card = 22.dp
    val Nav = 26.dp
    val Sheet = 28.dp
}

val CardShape: RoundedCornerShape = RoundedCornerShape(Radii.Card)
val WellShape: RoundedCornerShape = RoundedCornerShape(Radii.Well)
val PillShape: RoundedCornerShape = RoundedCornerShape(50)

/**
 * Bottom content padding for any scrolling tab root, so the last item scrolls fully clear of
 * the floating bottom bar (phones) and the gesture area below it. With a rail or sidebar there
 * is no bar, so only breathing room is left.
 */
@Composable
fun listBottomPadding(): Dp =
    Dimens.listBottomInset + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
