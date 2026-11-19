package com.laksh.coffee.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.ui.theme.CardShape
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Eyebrow
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.PillShape
import com.laksh.coffee.ui.theme.WellShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Press feedback that lives in the layer phase: the spring is read only inside
 * graphicsLayer, so a squash never recomposes whatever is being pressed. Pass the same
 * interaction source to the clickable or Button so both see the same press.
 */
@Composable
fun Modifier.pressSquash(interaction: InteractionSource, down: Float = 0.97f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s = animateFloatAsState(if (pressed) down else 1f, Motion.quick(), label = "press")
    return graphicsLayer {
        scaleX = s.value
        scaleY = s.value
    }
}

/**
 * Upper-cases a label without mangling the micro sign. Kotlin maps µ (U+00B5) to the Greek
 * capital Mu, so "µm / click" would read as "MM / CLICK".
 */
fun String.toCaps(): String {
    if ('µ' !in this) return uppercase()
    return buildString(length) {
        for (ch in this@toCaps) {
            if (ch == 'µ') append(ch) else append(ch.uppercase())
        }
    }
}

// ------------------------------------------------------------------------ labels

/**
 * The label that sits ABOVE a value: 11sp, tracked, upper-cased, inkLow. Pass
 * [uppercase] = false for a label that is already cased the way it should read.
 */
@Composable
fun EyebrowLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    uppercase: Boolean = true,
) {
    Text(
        text = if (uppercase) text.toCaps() else text,
        style = Eyebrow,
        color = color.takeOrElse { CoffeeTheme.colors.inkLow },
        modifier = modifier,
    )
}

/**
 * A section eyebrow with an optional link on the right ("See log", "All 14"). It carries
 * the 24dp gap above a block and the 10dp gap to its card; callers add the side gutter.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailing: (() -> Unit)? = null,
    topGap: Dp = 24.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().padding(top = topGap, bottom = 10.dp)) {
        EyebrowLabel(text, Modifier.alignByBaseline().weight(1f))
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.titleSmall,
                color = CoffeeTheme.colors.inkMid,
                maxLines = 1,
                modifier = Modifier
                    .alignByBaseline()
                    .then(
                        if (onTrailing != null) {
                            Modifier.clickable(
                                interactionSource = interaction,
                                indication = null,
                                role = Role.Button,
                                onClick = onTrailing,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * Every tab root opens the same way: an eyebrow context line, the title in displaySmall,
 * an optional StatusLine under it, then 20dp to the content.
 */
@Composable
fun ScreenHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                EyebrowLabel(eyebrow)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = CoffeeTheme.colors.inkHigh,
                )
            }
            trailing?.invoke(this)
        }
        if (status != null) {
            Spacer(Modifier.height(8.dp))
            status()
        }
    }
}

/** Back well on the left, an optional quiet title, then the page's actions. */
@Composable
fun DetailTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconWell(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = CoffeeTheme.colors.inkMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/** How far below the status bar the canvas fades out. */
private val StatusScrimFade = 12.dp

/**
 * Keeps scrolled text from running under the clock and system icons: canvas at 94% over the
 * status bar, then a short fade. It has no pointer input, so rows under the fade stay tappable.
 * The app root draws it once over every screen except the running brew.
 */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
    val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    PinnedScrim(band = inset, fade = StatusScrimFade, modifier = modifier)
}

/**
 * What content scrolls under wherever something stays pinned: canvas at [edgeAlpha] over
 * [band], then an eased fade over [fade]. A scrim that starts mid-screen passes 1f for
 * [topAlpha] and [edgeAlpha] so its list's clipped edge never shows, and holds that for
 * [solidFraction] of the fade so text leaving the pinned control is hidden before it thins
 * out rather than sliced at the control's edge. It draws only, so taps pass through.
 */
@Composable
fun PinnedScrim(
    band: Dp,
    fade: Dp,
    modifier: Modifier = Modifier,
    topAlpha: Float = 0.94f,
    edgeAlpha: Float = 0.94f,
    solidFraction: Float = 0f,
) {
    val bg = CoffeeTheme.colors.bg
    Spacer(
        modifier
            .fillMaxWidth()
            .height(band + fade)
            .drawWithCache {
                val bandPx = band.toPx().coerceAtMost(size.height)
                val bandBrush = Brush.verticalGradient(
                    listOf(bg.copy(alpha = topAlpha), bg.copy(alpha = edgeAlpha)),
                    startY = 0f,
                    endY = bandPx,
                )
                // Eased rather than linear: half gone a third of the way into the ramp (4dp under
                // the status bar), so a header's eyebrow at rest is barely touched.
                val solid = solidFraction.coerceIn(0f, 0.9f)
                val fadeBrush = Brush.verticalGradient(
                    0f to bg.copy(alpha = edgeAlpha),
                    solid to bg.copy(alpha = edgeAlpha),
                    solid + (1f - solid) / 3f to bg.copy(alpha = edgeAlpha * 0.48f),
                    1f to bg.copy(alpha = 0f),
                    startY = bandPx,
                    endY = size.height,
                )
                onDrawBehind {
                    drawRect(bandBrush, size = Size(size.width, bandPx))
                    drawRect(fadeBrush, topLeft = Offset(0f, bandPx), size = Size(size.width, size.height - bandPx))
                }
            },
    )
}

// ------------------------------------------------------------------------- cards

/**
 * The app's one card: surface1, 22dp corners, no border and no shadow, lifted by shade only.
 * Presses squash it slightly, which is most of the tactility.
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = CoffeeTheme.colors.surface1,
    border: Color = Color.Transparent,
    shape: RoundedCornerShape = CardShape,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = CoffeeTheme.colors
    // Text on the card defaults to the ink that reads on its fill, never to black.
    val ink = when (color) {
        colors.inverse -> colors.onInverse
        colors.crema -> colors.onCrema
        else -> colors.inkHigh
    }

    Surface(
        modifier = modifier.then(
            // Static cards get neither a graphics layer nor a press collector.
            if (onClick != null) {
                Modifier
                    .pressSquash(interaction, 0.975f)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
            } else {
                Modifier
            },
        ),
        color = color,
        contentColor = ink,
        shape = shape,
        border = if (border.alpha == 0f) null else BorderStroke(1.dp, border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** A quiet card of inkMid text. Empty states and gentle notes. */
@Composable
fun QuietPanel(text: String, modifier: Modifier = Modifier) {
    SoftCard(modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = CoffeeTheme.colors.inkMid,
        )
    }
}

// --------------------------------------------------------------------- dividers

/** A true 1px rule, optionally inset from the start so it lines up with a text column. */
@Composable
fun HairLine(
    modifier: Modifier = Modifier,
    inset: Dp = 0.dp,
    color: Color = Color.Unspecified,
) {
    val onePx = (1f / LocalDensity.current.density).dp
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(onePx)
            .background(color.takeOrElse { CoffeeTheme.colors.hairline }),
    )
}

/** A 1px column rule with 4dp breathing room top and bottom. Needs a bounded height. */
@Composable
fun VerticalHairline(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val onePx = (1f / LocalDensity.current.density).dp
    Box(
        modifier
            .fillMaxHeight()
            .padding(vertical = 4.dp)
            .width(onePx)
            .background(color.takeOrElse { CoffeeTheme.colors.hairline }),
    )
}

/**
 * The boarding-pass tear line: a dashed rule with a half-circle bitten out of each card edge.
 * Place it edge to edge inside a SoftCard with zero content padding; the card's clip trims
 * the outer halves of the notches. Everything is built in the cache block.
 */
@Composable
fun PerforatedDivider(
    modifier: Modifier = Modifier,
    notchColor: Color = Color.Unspecified,
) {
    val colors = CoffeeTheme.colors
    val notch = notchColor.takeOrElse { colors.bg }
    // outlineStrong, not hairline: a hairline dash on surface1 vanishes in dark mode and the
    // two notches read as bites with nothing joining them.
    val line = colors.outlineStrong
    Spacer(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .drawWithCache {
                val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()), 0f)
                val r = 10.dp.toPx()
                val gap = 4.dp.toPx()
                val cy = size.height / 2f
                onDrawBehind {
                    drawLine(
                        color = line,
                        start = Offset(r + gap, cy),
                        end = Offset(size.width - r - gap, cy),
                        strokeWidth = 1f,
                        pathEffect = dash,
                    )
                    drawCircle(notch, radius = r, center = Offset(0f, cy))
                    drawCircle(notch, radius = r, center = Offset(size.width, cy))
                }
            },
    )
}

// ------------------------------------------------------------------------- chips

/**
 * A 34dp pill. Unselected is a quiet well; selected is inverse ink, or, when it carries a
 * [status], the status container with a 1dp status edge (faults, flow feel, bed surface).
 *
 * [leadingIcon] is drawn art, never a typed glyph; it gets the chip's ink as LocalContentColor.
 * Pass [onCanvas] when the chip sits on the bare canvas rather than a card: light surface2 on
 * paper is about 1.02:1, so there the chip takes surface1 and a 1px edge.
 *
 * @param accent ignored, kept for source compatibility.
 */
@Composable
fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,
    leadingIcon: (@Composable () -> Unit)? = null,
    status: Status? = null,
    enabled: Boolean = true,
    onCanvas: Boolean = false,
) {
    val colors = CoffeeTheme.colors
    val paperWell = onCanvas && !colors.isDark
    val targetBg = when {
        !selected && paperWell -> colors.surface1
        !selected -> colors.surface2
        status == null -> colors.inverse
        else -> status.container()
    }
    val targetFg = when {
        !selected -> colors.inkMid
        status == null -> colors.onInverse
        else -> status.color()
    }
    val edge = if (selected && status != null) status.color().copy(alpha = 0.4f) else Color.Transparent
    val paperEdge = if (!selected && paperWell) colors.hairlineOnBg else Color.Transparent

    // Background is drawn, not composed, so its colour frames stay off the composition path.
    val bg = animateColorAsState(targetBg, Motion.quick(), label = "chipBg")
    val fg by animateColorAsState(targetFg, Motion.quick(), label = "chipFg")
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .pressSquash(interaction, 0.94f)
            .height(34.dp)
            .clip(PillShape)
            .drawWithCache {
                val w = 1.dp.toPx()
                val stroke = Stroke(w)
                onDrawBehind {
                    drawRect(bg.value)
                    if (paperEdge.alpha > 0f) {
                        // A true 1px edge, inset half a pixel so the pill clip does not eat it.
                        drawRoundRect(
                            color = paperEdge,
                            topLeft = Offset(0.5f, 0.5f),
                            size = Size(size.width - 1f, size.height - 1f),
                            cornerRadius = CornerRadius((size.height - 1f) / 2f),
                            style = Stroke(1f),
                        )
                    }
                    if (edge.alpha > 0f) {
                        drawRoundRect(
                            color = edge,
                            topLeft = Offset(w / 2f, w / 2f),
                            size = Size(size.width - w, size.height - w),
                            cornerRadius = CornerRadius((size.height - w) / 2f),
                            style = stroke,
                        )
                    }
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            CompositionLocalProvider(LocalContentColor provides fg, content = leadingIcon)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = fg,
            maxLines = 1,
        )
    }
}

/**
 * A wrapping set of chips with 8dp gaps. [status] tints every selected chip.
 *
 * @param accent ignored, kept for source compatibility.
 */
@Composable
fun ChipFlow(
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,
    status: Status? = null,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { opt ->
            SelectableChip(
                text = opt,
                selected = opt in selected,
                onClick = { onToggle(opt) },
                status = status,
            )
        }
    }
}

private val SegmentHeight = 40.dp

/** A [SegmentedControl]'s full height: its segments plus the 4dp well around them. */
val SegmentedControlHeight = SegmentHeight + 8.dp

/**
 * Segmented control with a sliding indicator rather than a hard swap. Neutral on purpose:
 * a selected segment is a choice, not a status. Pass [onCard] inside a SoftCard: the dark
 * canvas well is surface1, the card's own colour, so its track would vanish there.
 *
 * @param accent ignored, kept for source compatibility.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,
    onCard: Boolean = false,
) {
    if (options.isEmpty()) return
    val colors = CoffeeTheme.colors
    val well = when {
        !colors.isDark -> colors.surface3
        onCard -> colors.surface2
        else -> colors.surface1
    }
    val indicator = if (colors.isDark) colors.surface3 else colors.surface1
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp)

    Box(
        modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(well)
            .padding(4.dp),
    ) {
        // Same trick as the bottom bar: the pill moves in Float translation inside its own layer
        // (never a re-layout, never a whole-pixel step), equal shares mean the labels never
        // reflow underneath it, and label colours cross-fade in draw.
        val pill = animateFloatAsState(selectedIndex.toFloat(), SegmentSpring, label = "segX")
        Box(Modifier.fillMaxWidth().height(SegmentHeight)) {
            Box(
                Modifier
                    .fillMaxWidth(1f / options.size)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = pill.value * size.width }
                    .clip(PillShape)
                    .background(indicator),
            )
            Row(Modifier.fillMaxHeight()) {
                options.forEachIndexed { i, label ->
                    val on = animateFloatAsState(
                        if (i == selectedIndex) 1f else 0f,
                        Motion.EffectsDefault,
                        label = "segFg",
                    )
                    val low = colors.inkMid
                    val high = colors.inkHigh
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                            ) { onSelect(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = label,
                            style = labelStyle,
                            color = { lerp(low, high, on.value) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** In segment widths: ends within a fraction of a pixel. */
private val SegmentSpring = spring(
    dampingRatio = Motion.SpatialDefault.dampingRatio,
    stiffness = Motion.SpatialDefault.stiffness,
    visibilityThreshold = 0.0005f,
)

/**
 * 0..10 taste axis with the ends labelled, because "7" alone means nothing. Monochrome:
 * a taste value is not a status. A 4dp track and a small round thumb, not the stock 16dp
 * bar: seven of these sit on Review and must stay quieter than the score.
 *
 * @param accent ignored, kept for source compatibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AxisSlider(
    label: String,
    hint: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,
) {
    val colors = CoffeeTheme.colors
    val scope = rememberCoroutineScope()
    // The thumb follows the finger and only settles onto the integer detent on release;
    // a stepped Slider would hop mid-drag with nothing on the track to explain it.
    var raw by remember { mutableFloatStateOf(value.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var settle by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(value) {
        if (!dragging && settle?.isActive != true) raw = value.toFloat()
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                color = colors.inkHigh,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value.toString(),
                style = Num.Inline.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = colors.inkHigh,
            )
        }
        val sliderColors = SliderDefaults.colors(
            thumbColor = colors.inkHigh,
            activeTrackColor = colors.inkHigh,
            inactiveTrackColor = colors.surface3,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        )
        Slider(
            value = raw,
            onValueChange = { v ->
                settle?.cancel()
                dragging = true
                raw = v
                val r = v.roundToInt()
                if (r != value) onChange(r)
            },
            onValueChangeFinished = {
                dragging = false
                settle = scope.launch {
                    animate(raw, raw.roundToInt().toFloat(), animationSpec = Motion.settle()) { v, _ ->
                        raw = v
                    }
                }
            },
            colors = sliderColors,
            steps = 0,
            thumb = {
                Box(
                    Modifier
                        .size(20.dp)
                        .background(colors.inkHigh, CircleShape),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(4.dp),
                    colors = sliderColors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            },
            valueRange = 0f..10f,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = colors.inkLow,
        )
    }
}

/** An unlit dot is 14 of the 20 dp it grows to; scale rather than size so the Row never re-lays out. */
private const val DotOff = 14f / 20f

/**
 * Big tappable dots, for the one rating that gets used most.
 *
 * @param accent ignored, kept for source compatibility.
 */
@Composable
fun DotRating(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    max: Int = 10,
    accent: Color = Color.Unspecified,
) {
    // The previous rating, so the cascade fans out from where the change happened.
    val from = remember { intArrayOf(value) }
    val haptics = LocalHapticFeedback.current
    val colors = CoffeeTheme.colors
    val off = colors.surface3
    val lit = colors.inkHigh
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..max).forEach { i ->
            val on = i <= value
            val target = if (on) 1f else DotOff
            val s = remember { Animatable(target) }
            val delayMs = Motion.staggerDelay(abs(i - from[0]), perItemMs = 18, maxMs = 110)
            LaunchedEffect(target) {
                if (s.targetValue != target) {
                    delay(delayMs.toLong())
                    s.animateTo(target, Motion.bounce())
                }
            }
            Box(
                Modifier
                    .size(26.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onChange(if (value == i) 0 else i)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            scaleX = s.value
                            scaleY = s.value
                        }
                        .drawBehind {
                            val f = ((s.value - DotOff) / (1f - DotOff)).coerceIn(0f, 1f)
                            drawCircle(lerp(off, lit, f), radius = size.minDimension / 2f)
                        },
                )
            }
        }
    }
    SideEffect { from[0] = value }
}

// -------------------------------------------------------------------------- rows

/** Compact meta only: a 13sp inkMid label and a tabular value. Not for primary numbers. */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMid,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = Num.Inline,
            color = colors.inkHigh,
        )
    }
}

/** A 56dp settings row with a monochrome switch: "on" is not a status. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
            ) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = colors.inkHigh,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.inkLow,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.bg,
                checkedTrackColor = colors.inkHigh,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = colors.inkLow,
                uncheckedTrackColor = colors.surface3,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/**
 * One choice in a list: title, optional badge and subtitle, an optional detail line, an
 * optional status note, and a drawn radio. The selected row gets a surface2 band.
 */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    note: String? = null,
    noteStatus: Status? = null,
    detail: String? = null,
) {
    val colors = CoffeeTheme.colors
    // Fades to a transparent surface2 rather than to Color.Transparent (transparent black),
    // so light mode never flashes grey mid-animation.
    val bgState = animateColorAsState(
        if (selected) colors.surface2 else colors.surface2.copy(alpha = 0f),
        Motion.quick(),
        label = "radioBg",
    )
    val interaction = remember { MutableInteractionSource() }
    val ring = colors.outlineStrong
    val dot = colors.inkHigh
    val centre = colors.bg

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressSquash(interaction, 0.98f)
            .clip(WellShape)
            .drawBehind { drawRect(bgState.value) }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .height(18.dp)
                            .clip(PillShape)
                            .background(colors.surface3)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(badge.toCaps(), style = Micro, color = colors.inkMid, maxLines = 1)
                    }
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.inkLow,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A line of its own, so a long subtitle can never push it into a ragged wrap.
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.inkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Its own line under the subtitle: as a column beside it, the note squeezed the
            // subtitle onto two lines and cut off the price the row exists to show.
            if (note != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = note.toCaps(),
                    style = Micro,
                    color = noteStatus?.color() ?: colors.inkMid,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Spacer(
            Modifier
                .size(20.dp)
                .drawBehind {
                    val r = size.minDimension / 2f
                    if (selected) {
                        drawCircle(dot, radius = r)
                        drawCircle(centre, radius = 4.dp.toPx())
                    } else {
                        val sw = 1.5.dp.toPx()
                        drawCircle(ring, radius = r - sw / 2f, style = Stroke(sw))
                    }
                },
        )
    }
}

// --------------------------------------------------------------------- loading

/** A placeholder bar that breathes. The pulse is read only in draw, so it never recomposes. */
@Composable
fun SkeletonBar(
    widthFraction: Float,
    height: Dp,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
) {
    val pulse = rememberLivePulse()
    val fill = CoffeeTheme.colors.surface2
    Box(
        modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .drawBehind {
                drawRoundRect(fill, alpha = pulse.value, cornerRadius = CornerRadius(radius.toPx()))
            },
    )
}

/** 0.45 to 0.9 and back over 900ms. Read the State only inside draw lambdas. */
@Composable
fun rememberLivePulse(): State<Float> =
    rememberLoop(900, from = 0.45f, to = 0.9f, reverse = true, easing = EaseInOutSine)

// ----------------------------------------------------------------- sheet and bar

/**
 * The one bottom sheet: surface1 with a 28dp top radius, no tonal tint, a quiet handle, and
 * content padded clear of the navigation bar. The sheet is its own window, so the activity's
 * FLAG_SECURE does not reach it: a sheet that can show a secret passes [properties] with
 * SecureFlagPolicy.SecureOn.
 */
@Composable
fun CoffeeSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CoffeeTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        properties = properties,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface1,
        contentColor = colors.inkHigh,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(36.dp, 4.dp)
                    .clip(PillShape)
                    .background(colors.inkFaint),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            content = content,
        )
    }
}

/**
 * A bottom action bar that fades the content out above it. Put it in a Box aligned to
 * BottomCenter, and give the scrolling content enough bottom padding to clear it.
 *
 * [color] is the fill the bar settles into (the canvas by default). A screen with a
 * coloured backdrop, like the brew mesh, passes the colour at the backdrop's bottom edge so
 * the bar has no visible seam. The fade eases in rather than ramping linearly, which also
 * hides the band a hard gradient end leaves.
 */
@Composable
fun StickyCtaBar(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val bg = color.takeOrElse { CoffeeTheme.colors.bg }
    val fade = remember(bg) {
        Brush.verticalGradient(
            0f to bg.copy(alpha = 0f),
            0.45f to bg.copy(alpha = 0.55f),
            1f to bg,
        )
    }
    Column(modifier.fillMaxWidth()) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(fade),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(bg)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

// ------------------------------------------------------------ legacy, to delete

@Deprecated("Use SectionLabel or ScreenHeader", level = DeprecationLevel.WARNING)
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = CoffeeTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.inkHigh)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.inkMid)
            }
        }
        trailing?.invoke()
    }
}

/** @param accent ignored, kept for source compatibility. */
@Deprecated("Use DataGrid", level = DeprecationLevel.WARNING)
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    accent: Color = Color.Unspecified,
) {
    val colors = CoffeeTheme.colors
    Column(modifier) {
        EyebrowLabel(label)
        Spacer(Modifier.height(4.dp))
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (fadeIn(Motion.decel(220)) + slideInVertically { it / 3 })
                    .togetherWith(fadeOut(Motion.decel(140)) + slideOutVertically { -it / 3 })
            },
            label = "stat",
        ) { v ->
            Text(v, style = Num.Medium, color = colors.inkHigh)
        }
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.inkMid)
        }
    }
}

/** Readable text colour on an arbitrary accent, using perceived luminance. */
@Deprecated("Screens use tokens; there is no arbitrary accent fill any more", level = DeprecationLevel.WARNING)
fun contrastOn(background: Color): Color {
    val l = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (l > 0.6f) Color(0xFF1A120B) else Color.White
}

/** @param accent ignored, kept for source compatibility. */
@Deprecated("Use StatusChip", level = DeprecationLevel.WARNING)
@Composable
fun AccentBadge(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    Box(
        modifier
            .height(22.dp)
            .clip(PillShape)
            .background(colors.surface2)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.toCaps(), style = Micro, color = colors.inkMid, maxLines = 1)
    }
}

@Deprecated("Use QuietPanel", level = DeprecationLevel.WARNING)
@Composable
fun DashedPlaceholder(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    SoftCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = CoffeeTheme.colors.inkMid,
        )
    }
}
