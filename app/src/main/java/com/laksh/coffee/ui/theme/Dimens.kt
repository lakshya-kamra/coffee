package com.laksh.coffee.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
 * the floating bottom bar and the gesture area below it.
 */
@Composable
fun listBottomPadding(): Dp =
    Dimens.ListBottomInset + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
