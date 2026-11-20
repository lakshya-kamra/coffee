package com.laksh.coffee.ui.screens.brew

import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.PillShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** True when the system's animator scale is off ("Remove animations"): no flash, fades only. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/**
 * A short beep on the notification stream for step changes (v4 §4.8). Created once with the
 * screen and released when it leaves; a device without a tone generator plays nothing.
 */
@Composable
fun rememberStepTone(): () -> Unit {
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    return remember(tone) { { runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150) } } }
}

/**
 * The step-change cue seen across a kitchen (v4 §3.5): a full-window crema flash (0 -> 0.16 -> 0
 * over 600 ms, drawn in the draw phase only) and a banner that slides in for 3 s. With reduced
 * motion there is no flash and the banner only fades.
 */
class StepFlash {
    val flash = Animatable(0f)
    val banner = Animatable(0f)
    var text by mutableStateOf("")
}

@Composable
fun rememberStepFlash(): StepFlash = remember { StepFlash() }

suspend fun StepFlash.play(text: String, reduced: Boolean) {
    this.text = text
    kotlinx.coroutines.coroutineScope {
        if (!reduced) {
            launch {
                flash.snapTo(0f)
                flash.animateTo(0.16f, tween(150))
                flash.animateTo(0f, tween(450))
            }
        }
        banner.animateTo(1f, tween(if (reduced) 120 else 260))
        delay(3000)
        banner.animateTo(0f, tween(220))
    }
}

/** The flash and the banner over the whole brew screen. Neither takes touches. */
@Composable
fun StepFlashOverlay(flash: StepFlash, reduced: Boolean) {
    val colors = CoffeeTheme.colors
    Box(Modifier.fillMaxSize().drawBehind { val a = flash.flash.value; if (a > 0f) drawRect(colors.crema, alpha = a) })
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .widthIn(max = 480.dp)
                .graphicsLayer {
                    val v = flash.banner.value
                    alpha = v
                    if (!reduced) translationY = (1f - v) * -size.height
                }
                .clip(PillShape)
                .background(colors.inverse)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(flash.text, style = Micro, color = colors.onInverse, maxLines = 2)
        }
    }
}

/**
 * What TalkBack hears (v4 §5 A4): a polite live region, cleared a frame before each write so the
 * same words twice are still read. Step changes, the end, "10 seconds left" once, and with
 * auto-advance off "Time for the next step." when the clock passes the step ([waiting]).
 */
@Composable
fun BrewAnnouncer(recipe: Recipe, stepIndex: Int, done: Boolean, live: Boolean, elapsed: LongState, waiting: Boolean = false) {
    var said by remember { mutableStateOf("") }
    suspend fun say(text: String) {
        said = ""
        withFrameNanos { }
        said = text
    }
    LaunchedEffect(recipe, stepIndex, done, live) {
        if (!live) return@LaunchedEffect
        say(if (done) BrewWords.done((elapsed.longValue / 1000L).toInt()) else BrewWords.step(recipe, stepIndex))
    }
    val tenLeft by remember(recipe, stepIndex) {
        derivedStateOf {
            val s = recipe.steps.getOrNull(stepIndex)
            s != null && BrewWords.saysTenLeft(s, (elapsed.longValue / 1000L).toInt())
        }
    }
    LaunchedEffect(tenLeft, live) { if (tenLeft && live) say(BrewWords.TEN_LEFT) }
    LaunchedEffect(waiting, live) { if (waiting && live) say(BrewWords.NEXT_STEP) }
    Box(
        Modifier
            .size(1.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = said
            },
    )
}
