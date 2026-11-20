package com.laksh.coffee.ui.screens.brew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.grams
import com.laksh.coffee.core.ratio
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Num

/**
 * Espresso shot mode (v4 §4.9): a big count-up of seconds, the recipe's target band, one big
 * button that starts and stops the shot, then "How much came out?" and Rate it.
 */
@Composable
internal fun ShotPane(
    state: BrewUiState,
    timer: BrewClock,
    anchor: TimerAnchor,
    onLeave: () -> Unit,
    onPrimary: () -> Unit,
    onAgain: () -> Unit,
    onRate: (yieldG: Double?) -> Unit,
) {
    val recipe = state.recipe ?: return
    val phase = ShotPhase.of(state.running, anchor.accumulatedMs, state.shotStopped)
    val target = remember(recipe) { ShotTarget.of(recipe) }
    val pre = remember(recipe) { recipe.preinfusion() }
    // The yield as typed, prefilled with what the recipe aims for.
    var yieldText by rememberSaveable(recipe.id) { mutableStateOf(grams(recipe.waterG)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        val short = maxHeight < 480.dp
        val heroDp = (if (short) maxHeight.value * 0.36f else (maxHeight.value * 0.18f)).coerceIn(72f, 200f).dp
        if (short) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    ShotClock(timer.elapsedMs, heroDp, target, pre, phase)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    ShotTop(recipe, onLeave)
                    Spacer(Modifier.height(8.dp))
                    ShotControls(phase, yieldText, { yieldText = it }, onPrimary, onAgain, onRate)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().widthIn(max = 560.dp).align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ShotTop(recipe, onLeave)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spacer(Modifier.height(24.dp))
                    ShotClock(timer.elapsedMs, heroDp, target, pre, phase)
                    Spacer(Modifier.height(24.dp))
                }
                ShotControls(phase, yieldText, { yieldText = it }, onPrimary, onAgain, onRate)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ShotTop(recipe: Recipe, onLeave: () -> Unit) {
    val colors = CoffeeTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconWell(icon = Icons.Rounded.Close, contentDescription = "Leave brew", onClick = onLeave, size = 48.dp, shortcut = "Esc")
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(recipe.name, style = MaterialTheme.typography.titleSmall, color = colors.inkMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${grams(recipe.doseG)} g in → ${grams(recipe.waterG)} g out · ${ratio(recipe.doseG, recipe.waterG)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkLow,
            )
        }
        Spacer(Modifier.width(48.dp))
    }
}

/** The hero: whole seconds, big, with the band and the pre-infusion window as words. */
@Composable
private fun ShotClock(elapsed: LongState, heroDp: Dp, target: ShotTarget, pre: IntRange?, phase: ShotPhase) {
    val colors = CoffeeTheme.colors
    val sec by remember { derivedStateOf { (elapsed.longValue / 1000L).toInt() } }
    val size = with(LocalDensity.current) { heroDp.toSp() }
    EyebrowLabel("SHOT TIME", Modifier.asHeading())
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$sec", style = Num.Clock.copy(fontSize = size, lineHeight = size), color = colors.inkHigh, maxLines = 1, softWrap = false)
        Text(" s", style = Num.Large, color = colors.inkLow, modifier = Modifier.padding(bottom = heroDp * 0.12f))
    }
    Spacer(Modifier.height(8.dp))
    val inPre = pre != null && phase == ShotPhase.Pulling && sec in pre
    when {
        inPre -> StatusLine(Status.Live, "Pre-infusion", detail = clock(sec), live = true)
        phase == ShotPhase.Ready -> StatusLine(Status.Neutral, "Aim for ${target.label}")
        target.contains(sec) -> StatusLine(Status.Good, "In the band", detail = target.label)
        sec > target.highSec -> StatusLine(Status.Attention, "Past ${target.highSec} s", detail = "aim was ${target.label}")
        else -> StatusLine(if (phase == ShotPhase.Pulling) Status.Live else Status.Neutral, "Aim for ${target.label}", live = phase == ShotPhase.Pulling)
    }
}

@Composable
private fun ColumnScope.ShotControls(
    phase: ShotPhase,
    yieldText: String,
    onYield: (String) -> Unit,
    onPrimary: () -> Unit,
    onAgain: () -> Unit,
    onRate: (Double?) -> Unit,
) {
    val colors = CoffeeTheme.colors
    val label = phase.primaryLabel
    if (label != null) {
        // Inverse to start; crema while the shot runs, the one live thing on screen.
        PrimaryButton(
            text = label,
            onClick = onPrimary,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            icon = if (phase == ShotPhase.Pulling) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            height = 72.dp,
            containerColor = if (phase == ShotPhase.Pulling) colors.crema else colors.inverse,
            contentColor = if (phase == ShotPhase.Pulling) colors.onCrema else colors.onInverse,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Space starts and stops it.",
            style = Micro,
            color = colors.inkLow,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
            FieldWell(
                label = "HOW MUCH CAME OUT?",
                value = yieldText,
                onChange = { v -> onYield(v.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5)) },
                suffix = "g",
                numeric = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                helper = "Weigh the cup, or leave the recipe's number.",
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = "Rate it",
                onClick = { onRate(yieldText.replace(',', '.').toDoubleOrNull()) },
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp,
            )
            QuietButton(text = "Pull another", onClick = onAgain, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** Space-bar map for the shot: start and stop only, never while the yield is being typed. */
internal fun shotKeyAllowed(phase: ShotPhase): Boolean = phase != ShotPhase.Stopped
