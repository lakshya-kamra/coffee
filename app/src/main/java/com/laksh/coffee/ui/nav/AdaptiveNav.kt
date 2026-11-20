package com.laksh.coffee.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.ui.components.CoffeeTooltip
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.components.focusRing
import com.laksh.coffee.ui.components.hoverTint
import com.laksh.coffee.ui.theme.CoffeeColors
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Above this text scale the nav labels cannot fit their items (Recipes and Insights clip at 200 %):
 * the rail drops them as short windows do, and the bar shows its icons. Each item's content
 * description still names it (A7).
 */
internal const val NavLabelMaxFontScale = 1.5f

/** The navigation's own surface: half a shade between the canvas and a card, never a border. */
internal fun CoffeeColors.navSurface(): Color = lerp(bg, surface1, 0.5f)

// ------------------------------------------------- Forge 2.0's nav feel, shared by bar and rail

/** The liquid pill stretches |v| x this per item a second along its slide, at most [StretchMax]. */
private val StretchPerItem = 7.dp
private val StretchMax = 30.dp

/** ...and gives up this share of the stretch across, so it reads as squashed, not just longer. */
private const val Squash = 0.12f

/** A held nav item shrinks to this (Forge's pressScale). */
private const val NavPressScale = 0.9f

/** The selected nav icon pops to this. */
private const val NavIconScale = 1.1f

/**
 * The nav pill's position in items, on [PillSpring]. A tap mid-slide retargets the same spring
 * from where the pill is, keeping its speed; reduced motion jumps.
 */
@Composable
internal fun rememberNavPill(index: Int, reduceMotion: Boolean): Animatable<Float, AnimationVector1D> {
    val pill = remember { Animatable(index.toFloat(), visibilityThreshold = 0.0005f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(index, reduceMotion) {
        // Run in the composition's scope, not this effect's: a new tap cancels the old effect
        // before the new one starts, and a cancelled animateTo zeroes the velocity, so the pill
        // would lose its stretch for a frame. A direct animateTo reads the velocity first and
        // only then lets the Animatable's own mutex cancel the old run.
        scope.launch {
            if (reduceMotion) pill.snapTo(index.toFloat()) else pill.animateTo(index.toFloat(), PillSpring)
        }
    }
    return pill
}

/**
 * Forge's liquid pill, drawn behind [count] equal items laid along the draw area (across it, or
 * down it when [vertical]). At rest it is [length] along the slide by [thickness] across, centred
 * in its item; while it moves it grows along the slide by its speed and thins across, so a jump
 * over three items reads as one motion. Draw phase only: the pill's value invalidates this draw
 * and nothing else.
 */
internal fun DrawScope.drawLiquidPill(
    pill: Animatable<Float, AnimationVector1D>,
    count: Int,
    vertical: Boolean,
    length: Float,
    thickness: Float,
    radius: Float,
    color: Color,
) {
    val slot = (if (vertical) size.height else size.width) / count
    // isRunning is state, so the frame the spring ends redraws the pill without a hair of stretch.
    val stretch = if (pill.isRunning) min(abs(pill.velocity) * StretchPerItem.toPx(), StretchMax.toPx()) else 0f
    val along = length + stretch
    val across = (thickness - stretch * Squash).coerceAtLeast(0f)
    // A right-to-left Row puts the first item on the right; DrawScope never mirrors on its own.
    val fromStart = slot * (pill.value + 0.5f)
    val centre = if (!vertical && layoutDirection == LayoutDirection.Rtl) size.width - fromStart else fromStart
    val cross = (if (vertical) size.width else size.height) / 2f
    drawRoundRect(
        color = color,
        topLeft = if (vertical) Offset(cross - across / 2f, centre - along / 2f) else Offset(centre - along / 2f, cross - across / 2f),
        size = if (vertical) Size(across, along) else Size(along, across),
        cornerRadius = CornerRadius(min(radius, min(along, across) / 2f)),
    )
}

/**
 * Forge's press: the item shrinks to 0.9 while held and springs back, in its layer. Collected
 * from the interactions rather than read as state, so a press recomposes nothing.
 */
@Composable
internal fun Modifier.navPress(interaction: InteractionSource): Modifier {
    val scale = remember { Animatable(1f, visibilityThreshold = 0.001f) }
    LaunchedEffect(interaction) {
        var held = 0
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> held++
                is PressInteraction.Release, is PressInteraction.Cancel -> held = (held - 1).coerceAtLeast(0)
                else -> return@collect
            }
            launch { scale.animateTo(if (held > 0) NavPressScale else 1f, Motion.quick()) }
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** The selected icon's pop to 1.1 on [Motion.NavIconBounce]; reduced motion settles without the bounce. */
@Composable
internal fun rememberNavIconScale(selected: Boolean, reduceMotion: Boolean): State<Float> =
    animateFloatAsState(
        if (selected) NavIconScale else 1f,
        if (reduceMotion) Motion.EffectsFast else Motion.NavIconBounce,
        label = "navIconScale",
    )

// ----------------------------------------------------------------------- rail

/**
 * The rail (medium, expanded, and large with the sidebar folded): 80dp on the start edge.
 * Start brew on top, the five sections with labels, and the bar's liquid pill sliding down
 * between them (drawn behind the items, the bar's spring, stretching down the column as it
 * moves). Short windows (and very large text) drop the labels and tighten the items.
 */
@Composable
internal fun NavRail(
    selected: AppTab,
    short: Boolean,
    tooltips: Boolean,
    reduceMotion: Boolean,
    onSelect: (AppTab) -> Unit,
    onStartBrew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    val itemH = if (short) 48.dp else 64.dp
    val pill = rememberNavPill(selected.ordinal, reduceMotion)
    // Neutral ink, never crema: the section you are in is not a status.
    val pillColor = colors.inkHigh.copy(alpha = if (colors.isDark) 0.10f else 0.06f)

    Column(
        modifier
            .fillMaxHeight()
            .background(colors.navSurface())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .width(Dimens.Rail)
            .padding(vertical = if (short) 8.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StartBrewWell(size = if (short) 40.dp else 56.dp, tooltips = tooltips, onClick = onStartBrew)
        Spacer(Modifier.height(if (short) 8.dp else 20.dp))
        Box(
            Modifier
                .width(Dimens.Rail)
                .height(itemH * AppTab.entries.size)
                .drawBehind {
                    drawLiquidPill(
                        pill = pill,
                        count = AppTab.entries.size,
                        vertical = true,
                        length = (itemH - 8.dp).toPx(),
                        thickness = 64.dp.toPx(),
                        radius = 18.dp.toPx(),
                        color = pillColor,
                    )
                },
        ) {
            Column(Modifier.selectableGroup()) {
                AppTab.entries.forEach { tab ->
                    RailItem(
                        tab = tab,
                        selected = tab == selected,
                        showLabel = !short,
                        tooltips = tooltips,
                        reduceMotion = reduceMotion,
                        onClick = { onSelect(tab) },
                        modifier = Modifier.size(Dimens.Rail, itemH),
                    )
                }
            }
        }
    }
}

@Composable
private fun StartBrewWell(size: Dp, tooltips: Boolean, onClick: () -> Unit) {
    val colors = CoffeeTheme.colors
    // Inverse ink, never crema: starting is an action, not something live yet.
    IconWell(
        icon = Icons.Rounded.PlayArrow,
        contentDescription = "Start brew",
        onClick = onClick,
        size = size,
        fill = colors.inverse,
        tint = colors.onInverse,
        iconSize = if (size > 48.dp) 28.dp else 22.dp,
        shortcut = if (tooltips) "B" else null,
        modifier = Modifier.testTag("rail-start-brew"),
    )
}

@Composable
private fun RailItem(
    tab: AppTab,
    selected: Boolean,
    showLabel: Boolean,
    tooltips: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item: @Composable () -> Unit = {
        val interaction = remember { MutableInteractionSource() }
        val haptics = LocalHapticFeedback.current
        Box(
            modifier
                .testTag(tab.tag)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .focusRing(interaction, ItemShape)
                .clip(ItemShape)
                .hoverTint(interaction, CoffeeTheme.colors.inkHigh.copy(alpha = 0.05f))
                .semantics { if (!showLabel) contentDescription = tab.label }
                .selectable(
                    selected = selected,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Tab,
                    onClick = {
                        if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onClick()
                    },
                )
                .navPress(interaction),
            contentAlignment = Alignment.Center,
        ) {
            NavGlyph(tab, selected, showLabel, iconSize = 24.dp, reduceMotion = reduceMotion)
        }
    }
    if (tooltips) {
        CoffeeTooltip("${tab.label}  ${tab.ordinal + 1}", position = TooltipAnchorPosition.End, content = item)
    } else {
        item()
    }
}

private val ItemShape = RoundedCornerShape(18.dp)

/**
 * Icon over label, the selected one in high ink. Two pre-tinted icons cross-fade in their layers
 * and the label colour is a ColorProducer, so a switch recomposes nothing here.
 */
@Composable
private fun NavGlyph(tab: AppTab, selected: Boolean, showLabel: Boolean, iconSize: Dp, reduceMotion: Boolean) {
    val colors = CoffeeTheme.colors
    val sel = animateFloatAsState(if (selected) 1f else 0f, Motion.EffectsDefault, label = "navSel")
    val iconScale = rememberNavIconScale(selected, reduceMotion)
    val low = colors.inkLow
    val high = colors.inkHigh
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = iconScale.value
                    scaleY = iconScale.value
                },
        ) {
            Icon(tab.icon, null, tint = low, modifier = Modifier.matchParentSize().graphicsLayer { alpha = 1f - sel.value })
            Icon(tab.icon, null, tint = high, modifier = Modifier.matchParentSize().graphicsLayer { alpha = sel.value })
        }
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            BasicText(
                tab.label,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
                color = { lerp(low, high, sel.value) },
                maxLines = 1,
            )
        }
    }
}

// -------------------------------------------------------------------- sidebar

/**
 * The labelled sidebar (large and up): the app's name and the fold button, Start brew, the five
 * sections with Gear's three sections always shown under it, and a quiet way to the shortcuts.
 * The selected row is a surface2 band; only its colour animates. Every row is at least 48dp (A12).
 */
@Composable
internal fun NavSidebar(
    selected: AppTab,
    gearSection: Int,
    onSelect: (AppTab) -> Unit,
    onGearSection: (GearSection) -> Unit,
    onStartBrew: () -> Unit,
    onCollapse: () -> Unit,
    onShortcuts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .background(colors.navSurface())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .width(Dimens.Sidebar)
            .padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Coffee",
                style = MaterialTheme.typography.titleLarge,
                color = colors.inkHigh,
                modifier = Modifier.weight(1f).asHeading(),
            )
            IconWell(
                icon = Icons.AutoMirrored.Rounded.ViewSidebar,
                contentDescription = "Fold the sidebar",
                onClick = onCollapse,
                fill = Color.Transparent,
                tint = colors.inkMid,
                shortcut = "[",
            )
        }
        PrimaryButton(
            text = "Start brew",
            onClick = onStartBrew,
            icon = Icons.Rounded.PlayArrow,
            height = 48.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("sidebar-start-brew"),
        )
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            AppTab.entries.forEach { tab ->
                SidebarRow(
                    label = tab.label,
                    icon = { NavIcon(tab, tab == selected) },
                    selected = tab == selected,
                    hint = "${tab.ordinal + 1}",
                    height = 48.dp,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.testTag(tab.tag),
                )
                if (tab == AppTab.Gear) {
                    GearSection.entries.forEach { section ->
                        SidebarRow(
                            label = section.label,
                            icon = null,
                            selected = selected == AppTab.Gear && gearSection == section.ordinal,
                            hint = null,
                            height = 48.dp,
                            onClick = { onGearSection(section) },
                            indent = 40.dp,
                            role = Role.Button,
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(vertical = 8.dp),
        ) {
            // At 200 % text the sidebar is too narrow for one line: it wraps rather than clip to "Keyboard".
            QuietButton("Keyboard shortcuts  ?", onShortcuts, maxLines = 3)
        }
    }
}

@Composable
private fun NavIcon(tab: AppTab, selected: Boolean) {
    val colors = CoffeeTheme.colors
    Icon(tab.icon, null, tint = if (selected) colors.inkHigh else colors.inkMid, modifier = Modifier.size(20.dp))
}

@Composable
private fun SidebarRow(
    label: String,
    icon: (@Composable () -> Unit)?,
    selected: Boolean,
    hint: String?,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indent: Dp = 12.dp,
    role: Role = Role.Tab,
) {
    val colors = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val band = animateColorAsState(
        if (selected) colors.surface2 else colors.surface2.copy(alpha = 0f),
        tween(120),
        label = "sidebarBand",
    )
    // The digit hint shows on hover or keyboard focus only.
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .focusRing(interaction, RowShape)
            .clip(RowShape)
            .drawBehind { drawRect(band.value) }
            .hoverTint(interaction, colors.inkHigh.copy(alpha = 0.05f))
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = role,
                onClick = onClick,
            )
            .padding(start = indent, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(12.dp))
        }
        Text(
            label,
            style = if (icon != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.inkHigh else colors.inkMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hint != null && (hovered || focused)) {
            Text(hint, style = Num.Inline, color = colors.inkLow, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

private val RowShape = RoundedCornerShape(12.dp)
