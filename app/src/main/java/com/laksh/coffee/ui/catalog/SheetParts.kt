package com.laksh.coffee.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.pressSquash
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.WellShape

// Small pieces every editor sheet shares, so the bean, grinder and recipe editors and onboarding
// read as one form.

@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = CoffeeTheme.colors.inkHigh,
        modifier = modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

/** A quiet 12sp line under a field or a group. */
@Composable
fun SheetNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        color = CoffeeTheme.colors.inkLow,
        modifier = modifier,
    )
}

/** An eyebrow, then chips that wrap. [note] is the selected chip's description. */
@Composable
fun ChipGroup(
    label: String?,
    modifier: Modifier = Modifier,
    note: String? = null,
    content: @Composable FlowRowScope.() -> Unit,
) {
    Column(modifier) {
        if (label != null) {
            EyebrowLabel(label)
            Spacer(Modifier.height(8.dp))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            SheetNote(note)
        }
    }
}

/**
 * A field that opens something instead of taking typing: the same well as FieldWell, the value
 * or a placeholder, and a chevron. [onClear] adds a small × when there is a value to drop.
 */
@Composable
fun PickerField(
    label: String?,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose",
    helper: String? = null,
    onClear: (() -> Unit)? = null,
    onCanvas: Boolean = false,
) {
    val colors = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Column(modifier) {
        if (label != null) {
            EyebrowLabel(label)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.Field)
                .pressSquash(interaction, 0.98f)
                .clip(WellShape)
                .background(if (onCanvas) colors.surface1 else colors.surface2)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value.ifBlank { placeholder },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = if (value.isBlank()) colors.inkLow else colors.inkHigh,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (onClear != null && value.isNotBlank()) {
                IconWell(
                    icon = Icons.Rounded.Close,
                    contentDescription = "Clear",
                    onClick = onClear,
                    size = 32.dp,
                    onCanvas = false,
                    iconSize = 16.dp,
                )
            } else {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = colors.inkLow,
                    modifier = Modifier.padding(end = 6.dp).size(20.dp),
                )
            }
        }
        if (!helper.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            SheetNote(helper)
        }
    }
}

/**
 * A number between two wells: − value +. The value is text the caller formats, so it can say
 * "0:45" or "93 °C". [onLongMinus]/[onLongPlus] are the fine steps (hold).
 */
@Composable
fun StepperRow(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    unit: String? = null,
    minusDescription: String = "Less",
    plusDescription: String = "More",
    onLongMinus: (() -> Unit)? = null,
    onLongPlus: (() -> Unit)? = null,
    valueWidth: Dp = 72.dp,
    onCanvas: Boolean = false,
) {
    val colors = CoffeeTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            EyebrowLabel(label, Modifier.weight(1f))
        }
        HoldWell(Icons.Rounded.Remove, minusDescription, onMinus, onLongMinus, onCanvas)
        Row(
            // A minimum: a long value at 200 % text widens the row instead of clipping.
            Modifier.widthIn(min = valueWidth),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(value, style = Num.Small, color = colors.inkHigh, textAlign = TextAlign.Center, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = Num.unit(Num.Small), color = colors.inkLow, maxLines = 1)
            }
        }
        HoldWell(Icons.Rounded.Add, plusDescription, onPlus, onLongPlus, onCanvas)
    }
}

/** A round well that steps on a tap and, when [onLong] is set, takes a finer step on a hold. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HoldWell(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    onLong: (() -> Unit)?,
    onCanvas: Boolean,
) {
    val colors = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(Dimens.StepperWell)
            .pressSquash(interaction, 0.92f)
            .clip(CircleShape)
            .background(if (onCanvas) colors.surface1 else colors.surface2)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onLongClick = onLong,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = colors.inkHigh, modifier = Modifier.size(20.dp))
    }
}

/** A section that opens under its own header row; the body is only composed while open. */
@Composable
fun Expandable(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(Motion.decel()) + expandVertically(Motion.size()),
        exit = fadeOut(Motion.decel(140)) + shrinkVertically(Motion.size()),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
