package com.laksh.coffee.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.core.clipWords
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.timeLabel
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.BrewerArt
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.recipes.ExpertRecipes
import com.laksh.coffee.ui.theme.CardShape
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num

/** Where a row sits in an inset-grouped card, which decides its corners and top rule. */
enum class GroupPosition {
    First,
    Middle,
    Last,
    Single;

    companion object {
        fun of(index: Int, size: Int): GroupPosition = when {
            size == 1 -> Single
            index == 0 -> First
            index == size - 1 -> Last
            else -> Middle
        }
    }
}

private val FirstShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
private val LastShape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)

/**
 * One row of an inset-grouped card. Each row draws its own surface1 background with rounded
 * outer corners only, plus a 1px rule on top of every row but the first. Lazy lists keep
 * one item per row, so animateItem still works per row while the rows read as one card.
 */
@Composable
fun GroupRow(
    position: GroupPosition,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    hairlineInset: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val colors = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Read only in drawBehind, so a press repaints the row without recomposing its content.
    val bg = animateColorAsState(
        if (pressed) colors.surface2 else colors.surface1,
        Motion.quick(),
        label = "groupRowBg",
    )
    val shape = when (position) {
        GroupPosition.First -> FirstShape
        GroupPosition.Last -> LastShape
        GroupPosition.Single -> CardShape
        GroupPosition.Middle -> RectangleShape
    }
    val topRule = position == GroupPosition.Middle || position == GroupPosition.Last
    val rule = colors.hairline

    Box(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.focusRing(interaction, shape) else Modifier)
            .clip(shape)
            .drawBehind {
                drawRect(bg.value)
                if (topRule) {
                    drawLine(rule, Offset(hairlineInset.toPx(), 0.5f), Offset(size.width, 0.5f), 1f)
                }
            }
            .then(
                if (onClick != null) {
                    // One shade up under the pointer, the same lift a press gives.
                    Modifier
                        .hoverTint(interaction, colors.surface2)
                        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

private val LogTimeColumn = 68.dp

/**
 * A brew in a grouped list, 68dp tall: time and brewer, recipe and meta, then the score in
 * its status colour. The meta line carries the time against the recipe target in its time
 * status colour ("−0:41" amber), the way a flight list colours its delay.
 *
 * @param targetSec the recipe's planned total; defaults to the built-in recipe's, if any.
 */
@Composable
fun LogRow(
    log: BrewLogEntity,
    position: GroupPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    analysed: Boolean = false,
    targetSec: Int? = null,
) {
    val colors = CoffeeTheme.colors
    val builtInTarget = remember(log.recipeId) { ExpertRecipes.byId(log.recipeId)?.totalSec }
    val target = targetSec ?: builtInTarget
    // Same rule as the log: on time says so in words, and only a miss shows the signed delta.
    val delta = if (target != null && target > 0 && log.totalTimeSec > 0) {
        val status = timeStatus(log.totalTimeSec, target).first
        status to if (status == Status.Good) "on time" else signedClock(log.totalTimeSec - target)
    } else {
        null
    }
    val deltaInk = delta?.first?.color()
    val metaInk = colors.inkMid
    val meta = remember(log, delta, deltaInk, metaInk) {
        val grind = log.grindClicks?.let { "$it clk" }
            // Typed grind text: trimmed, and cut at a whole word ("about 10…", never "about 10 secon").
            ?: log.grindSetting.takeIf { it.isNotBlank() }?.let { clipWords(it, 14) }
        val bean = beanShortName(log.beanLabel).takeIf { it.isNotBlank() }
        buildAnnotatedString {
            withStyle(SpanStyle(color = metaInk)) {
                var first = true
                fun sep() {
                    if (!first) append(" · ")
                    first = false
                }
                if (grind != null) {
                    sep()
                    append(grind)
                }
                if (log.totalTimeSec > 0) {
                    sep()
                    append(clock(log.totalTimeSec))
                    if (delta != null && deltaInk != null) {
                        append(" ")
                        withStyle(SpanStyle(color = deltaInk)) { append(delta.second) }
                    }
                }
                if (bean != null) {
                    sep()
                    append(bean)
                }
            }
        }
    }
    val brewerTag = remember(log.brewerId, log.brewer) {
        val b = log.brewerId?.let(Brewers::byId) ?: Brewers.resolveOrNull(log.brewer)
        (b?.rowTag() ?: log.brewer).toCaps()
    }

    GroupRow(position, modifier, onClick, hairlineInset = 96.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(LogTimeColumn)) {
                Text(
                    text = timeLabel(log.startedAt),
                    style = Num.Inline,
                    color = colors.inkHigh,
                    maxLines = 1,
                )
                Text(
                    text = brewerTag,
                    style = Micro.copy(letterSpacing = 0.6.sp),
                    color = colors.inkLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = log.recipeName.ifBlank { "Brew" },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (log.overall > 0) "${log.overall}" else "—",
                    style = Num.Small.copy(fontSize = 22.sp, lineHeight = 26.sp),
                    color = if (log.overall > 0) scoreStatus(log.overall).color() else colors.inkLow,
                    maxLines = 1,
                )
                val showBed = log.bedShape != BedShape.Unset
                val showFault = log.faults.isNotEmpty()
                if (analysed || showBed || showFault) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (analysed) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = "Analysed",
                                tint = colors.inkLow,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        if (showBed) BedShapeIcon(log.bedShape, size = 14.dp, tint = colors.inkLow)
                        if (showFault) StatusDot(Status.Fault, size = 6.dp)
                    }
                }
            }
        }
    }
}

/**
 * A 60dp row that does something: export, share, open settings. A trailing copy well when
 * [onTrailing] is set, otherwise a chevron.
 */
@Composable
fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    position: GroupPosition = GroupPosition.Single,
    trailingIcon: ImageVector? = null,
    onTrailing: (() -> Unit)? = null,
    trailingDescription: String? = null,
    enabled: Boolean = true,
) {
    val colors = CoffeeTheme.colors
    GroupRow(
        position = position,
        modifier = modifier,
        onClick = if (enabled) onClick else null,
        hairlineInset = 64.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .alpha(if (enabled) 1f else 0.4f)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.inkHigh, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = colors.inkMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onTrailing != null) {
                Spacer(Modifier.width(8.dp))
                IconWell(
                    icon = trailingIcon ?: Icons.Rounded.ContentCopy,
                    contentDescription = trailingDescription,
                    onClick = onTrailing,
                    size = 36.dp,
                    onCanvas = false,
                    iconSize = 18.dp,
                    enabled = enabled,
                )
            } else {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colors.inkLow,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ label helpers

/** A brew log stores the brewer's label ("Hario V60") or id; this maps it back, or null. */
fun brewerFromLabel(label: String): Brewer? = Brewers.resolveOrNull(label)

/** The narrowest honest tag for a log row's 68dp column. */
private fun Brewer.rowTag(): String = if (art == BrewerArt.FrenchPress) "Press" else shortName

/** "Square Mile — Red Brick" becomes "Red Brick": the roaster is noise in a one-line meta. */
fun beanShortName(label: String): String = label.substringAfter(" — ", label).trim()
