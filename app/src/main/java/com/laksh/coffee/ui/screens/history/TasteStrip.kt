package com.laksh.coffee.ui.screens.history

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.domain.TasteProfile
import com.laksh.coffee.ui.nav.rememberTransitionSettled
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import kotlinx.coroutines.delay

// The taste strip for the log screens. The bed diagram is BedShapeIcon in CoffeeArt.

/** Short labels in TasteProfile.AXES order; seven columns leave no room for full words. */
private val AXIS_SHORT = listOf("ACID", "SWEET", "BITTER", "BODY", "BAL", "AFTER", "CLAR")

/**
 * Seven monochrome columns, one per taste axis. Taste is data, not status, so there is no
 * colour here. Each fill grows once from 0, staggered, and is read only in the draw phase.
 */
@Composable
internal fun TasteStrip(taste: TasteProfile, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        TasteProfile.AXES.forEachIndexed { i, (name, _, get) ->
            TasteColumn(
                value = get(taste),
                label = AXIS_SHORT.getOrElse(i) { name.take(5).uppercase() },
                index = i,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TasteColumn(value: Int, label: String, index: Int, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    val target = value.coerceIn(0, 10) / 10f
    // Grows once per value, after the page has settled; saved, so coming back to this brew
    // (from Review, say) shows the bars standing instead of regrowing them.
    var played by rememberSaveable(value) { mutableStateOf(false) }
    val fill = remember { Animatable(if (played) target else 0f) }
    val settled = rememberTransitionSettled()
    LaunchedEffect(value, settled) {
        if (played) {
            fill.snapTo(target)
            return@LaunchedEffect
        }
        if (!settled) return@LaunchedEffect
        played = true
        delay(Motion.staggerDelay(index, perItemMs = 50).toLong())
        fill.animateTo(target, Motion.heavy())
    }

    // A minimum, so the value and label grow at 200 % text instead of clipping (v4 A7).
    Column(modifier.heightIn(min = 108.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$value",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = c.inkHigh,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(10.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(c.surface2)
                .drawBehind {
                    val h = size.height * fill.value
                    if (h > 0f) {
                        drawRoundRect(
                            color = c.inkHigh,
                            topLeft = Offset(0f, size.height - h),
                            size = Size(size.width, h),
                            cornerRadius = CornerRadius(5.dp.toPx()),
                        )
                    }
                },
        )
        Spacer(Modifier.height(6.dp))
        // Visible overflow: on a narrow phone 'BITTER' may run a hair past its column, which beats clipping it.
        Text(
            label,
            style = Micro.copy(letterSpacing = 0.8.sp),
            color = c.inkLow,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}
