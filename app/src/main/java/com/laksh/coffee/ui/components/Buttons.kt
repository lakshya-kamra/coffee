package com.laksh.coffee.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalWindowClass
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.PillShape
import com.laksh.coffee.ui.theme.PrimaryLabel

private val PrimaryShape = RoundedCornerShape(18.dp)
private val SecondaryShape = RoundedCornerShape(16.dp)
private val QuietShape = RoundedCornerShape(12.dp)

/**
 * The one primary action on a screen (a sheet counts as its own screen).
 *
 * It fills with inverse ink by default, not crema. Crema is reserved for things that are live
 * (the brew in progress), and a crema button next to an amber "needs a tweak" chip reads as
 * the same colour in a small screenshot. The live brew controls pass [containerColor]
 * explicitly. It never forces a width: pass fillMaxWidth() or weight().
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 56.dp,
    enabled: Boolean = true,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val colors = CoffeeTheme.colors
    val fill = containerColor.takeOrElse { if (enabled) colors.inverse else colors.surface2 }
    val fg = contentColor.takeOrElse { if (enabled) colors.onInverse else colors.inkLow }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = height)
            .focusRing(interaction, PrimaryShape)
            .pressSquash(interaction, 0.97f)
            .clip(PrimaryShape)
            .drawBehind { drawRect(fill) }
            .hoverTint(interaction, fg.copy(alpha = 0.08f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = PrimaryLabel,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A quiet filled button: surface2 inside a card, surface1 on the bare canvas. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
    enabled: Boolean = true,
    onCanvas: Boolean = false,
) {
    val colors = CoffeeTheme.colors
    val fill = if (onCanvas) colors.surface1 else colors.surface2
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = height)
            .focusRing(interaction, SecondaryShape)
            .pressSquash(interaction, 0.97f)
            .clip(SecondaryShape)
            .drawBehind { drawRect(fill) }
            .hoverTint(interaction, colors.inkHigh.copy(alpha = 0.06f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.inkHigh, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = colors.inkHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Text only, no fill. Presses dim it to 60% in the layer phase. Destructive: color = fault. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    val tint = color.takeOrElse { CoffeeTheme.colors.inkMid }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha = animateFloatAsState(if (pressed) 0.6f else 1f, Motion.quick(), label = "quietPress")
    val base = if (enabled) 1f else 0.4f
    Row(
        modifier
            .heightIn(min = 40.dp)
            .focusRing(interaction, QuietShape)
            .graphicsLayer { alpha = pressAlpha.value * base }
            .clip(QuietShape)
            .hoverTint(interaction, tint.copy(alpha = 0.08f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * A round icon button. surface1 on the canvas, surface2 inside a card. Destructive: tint =
 * fault. Play/pause: size 64.dp, fill inverse, tint onInverse, iconSize 30.dp.
 *
 * At expanded widths and up it gets a tooltip after a hover or on keyboard focus: the
 * [contentDescription] plus the [shortcut] ("Pause  Space"). The shortcut is in the tooltip only,
 * never in the accessible name (§5 A10).
 */
@Composable
fun IconWell(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    onCanvas: Boolean = true,
    tint: Color = Color.Unspecified,
    fill: Color = Color.Unspecified,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    shortcut: String? = null,
) {
    val colors = CoffeeTheme.colors
    val bg = fill.takeOrElse { if (onCanvas) colors.surface1 else colors.surface2 }
    val fg = tint.takeOrElse { colors.inkHigh }
    val interaction = remember { MutableInteractionSource() }
    val well: @Composable () -> Unit = {
        Box(
            modifier
                .alpha(if (enabled) 1f else 0.4f)
                .size(size)
                .focusRing(interaction, CircleShape)
                .pressSquash(interaction, 0.92f)
                .clip(CircleShape)
                .background(bg)
                .hoverTint(interaction, fg.copy(alpha = 0.08f))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(iconSize))
        }
    }
    if (contentDescription != null && LocalWindowClass.current.showsTooltips) {
        CoffeeTooltip(if (shortcut != null) "$contentDescription  $shortcut" else contentDescription, content = well)
    } else {
        well()
    }
}

/**
 * The plain tooltip used on pointer-sized windows: above the anchor, inverse ink. Hover and
 * keyboard focus show it after the platform delay; a long press still shows it on touch.
 */
@Composable
fun CoffeeTooltip(
    text: String,
    modifier: Modifier = Modifier,
    position: TooltipAnchorPosition = TooltipAnchorPosition.Above,
    content: @Composable () -> Unit,
) {
    val colors = CoffeeTheme.colors
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(position),
        tooltip = {
            PlainTooltip(containerColor = colors.inverse, contentColor = colors.onInverse) {
                Text(text, style = MaterialTheme.typography.labelMedium)
            }
        },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}

/** A small pill with a tabular label: steppers ("−5", "+1") and transport ("+5s"). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    onCanvas: Boolean = false,
) {
    val colors = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = height)
            .focusRing(interaction, PillShape)
            .pressSquash(interaction, 0.94f)
            .clip(PillShape)
            .background(if (onCanvas) colors.surface1 else colors.surface2)
            .hoverTint(interaction, colors.inkHigh.copy(alpha = 0.06f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = Num.Inline.copy(fontSize = 14.sp),
            color = colors.inkHigh,
            maxLines = 1,
        )
    }
}
