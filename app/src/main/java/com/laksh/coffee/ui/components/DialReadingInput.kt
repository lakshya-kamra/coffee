package com.laksh.coffee.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.domain.grind.DialReading
import com.laksh.coffee.domain.grind.GrindScale
import com.laksh.coffee.ui.catalog.StepperRow
import com.laksh.coffee.ui.theme.CoffeeTheme
import kotlin.math.max
import kotlin.math.roundToInt

/** Which part of a dial a stepper moves. */
enum class DialPart { Turns, Number, Mark }

/**
 * The arithmetic behind [DialReadingInput], kept pure so it can be tested: a stored value split
 * into the dial's own parts, and a step on one part with the carry a real dial has (mark 9 plus
 * one is the next number, mark 0).
 */
object DialSteps {

    /** The stored value as the dial shows it. Plain dials carry the number in [DialReading.value]. */
    fun split(scale: GrindScale, value: Double?): DialReading {
        val v = (value ?: 0.0).roundToInt().coerceAtLeast(0)
        return when (val d = scale.dial) {
            DialFormat.Plain -> DialReading(value = value)
            is DialFormat.TurnsAndClicks -> DialReading(turns = v / d.clicksPerTurn, tick = v % d.clicksPerTurn)
            is DialFormat.TurnsDotClicks -> DialReading(turns = v / d.clicksPerTurn, tick = v % d.clicksPerTurn)
            is DialFormat.NumberTick -> {
                val cpr = d.clicksPerRotation
                val rest = if (cpr > 0) v % cpr else v
                DialReading(
                    turns = if (cpr > 0) v / cpr else 0,
                    number = rest / d.ticksPerNumber,
                    tick = rest % d.ticksPerNumber,
                )
            }
        }
    }

    /** How many stored steps one move of [part] is; 0 when this dial has no such part. */
    fun size(scale: GrindScale, part: DialPart): Int = when (val d = scale.dial) {
        DialFormat.Plain -> 0
        is DialFormat.TurnsAndClicks -> if (part == DialPart.Turns) d.clicksPerTurn else if (part == DialPart.Mark) 1 else 0
        is DialFormat.TurnsDotClicks -> if (part == DialPart.Turns) d.clicksPerTurn else if (part == DialPart.Mark) 1 else 0
        is DialFormat.NumberTick -> when (part) {
            DialPart.Turns -> d.clicksPerRotation
            DialPart.Number -> d.ticksPerNumber
            DialPart.Mark -> 1
        }
    }

    /**
     * The value after moving [part] by [by] (±1), held inside the grinder's range. Null when
     * nothing changes. A value already past the top of the range (75 clicks on a row saved with
     * 40 as its top) may stay there or come down, but a tap never pulls it back into the range.
     */
    fun step(scale: GrindScale, value: Double?, part: DialPart, by: Int): Double? {
        val size = size(scale, part)
        if (size <= 0) return null
        val lo = max(0.0, scale.min)
        val from = (value ?: lo).roundToInt()
        val hi = if (scale.max > lo) max(scale.max, from.toDouble()) else Double.MAX_VALUE
        val next = (from + by * size).toDouble().coerceIn(lo, hi)
        return if (value != null && next == value) null else next
    }

    /** Whole turns the range allows; 0 means the turns stepper is not worth showing. */
    fun maxTurns(scale: GrindScale): Int {
        val perTurn = size(scale, DialPart.Turns)
        return if (perTurn <= 0 || scale.max <= 0) 0 else (scale.max / perTurn).toInt()
    }
}

/**
 * Enter a grind the way the dial reads, not as a raw click count: steppers for turns, the printed
 * number and the mark after it, with a live line saying what the app stores ("= 75 clicks").
 * Plain dials get a number field. An impossible or out-of-range reading is refused inline.
 *
 * [value] is the stored value (total clicks from zero for click grinders).
 */
@Composable
fun DialReadingInput(
    scale: GrindScale,
    value: Double?,
    onValue: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    onCanvas: Boolean = false,
) {
    val colors = CoffeeTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val dial = scale.dial) {
            DialFormat.Plain -> PlainDialField(scale, value, onValue, onCanvas)
            else -> {
                val r = DialSteps.split(scale, value)
                val set: (DialPart, Int) -> Unit = { part, by -> DialSteps.step(scale, value, part, by)?.let(onValue) }
                if (DialSteps.maxTurns(scale) >= 1) {
                    StepperRow(
                        label = "FULL TURNS",
                        value = r.turns.toString(),
                        onMinus = { set(DialPart.Turns, -1) },
                        onPlus = { set(DialPart.Turns, 1) },
                        minusDescription = "One turn fewer",
                        plusDescription = "One turn more",
                        onCanvas = onCanvas,
                    )
                }
                if (dial is DialFormat.NumberTick) {
                    StepperRow(
                        label = "NUMBER ON THE DIAL",
                        value = r.number.toString(),
                        onMinus = { set(DialPart.Number, -1) },
                        onPlus = { set(DialPart.Number, 1) },
                        minusDescription = "One number lower",
                        plusDescription = "One number higher",
                        onCanvas = onCanvas,
                    )
                }
                StepperRow(
                    label = if (dial is DialFormat.NumberTick) "MARKS PAST IT" else "CLICKS PAST THE TURN",
                    value = r.tick.toString(),
                    onMinus = { set(DialPart.Mark, -1) },
                    onPlus = { set(DialPart.Mark, 1) },
                    minusDescription = "One click fewer",
                    plusDescription = "One click more",
                    onCanvas = onCanvas,
                )
                Text(
                    text = storedLine(scale, value),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.inkMid,
                )
            }
        }
    }
}

/** "Dial 7.5 = 75 clicks (what the app stores)". */
internal fun storedLine(scale: GrindScale, value: Double?): String {
    if (value == null) return "Step to your setting. The app stores total clicks from zero."
    val stored = "${scale.format(value)} ${scale.unit}".trim()
    val reading = scale.reading(value)
    val lead = when {
        reading == null -> ""
        scale.dial.showsDialNumber -> "Dial $reading "
        else -> "$reading "
    }
    return "$lead= $stored (what the app stores)"
}

@Composable
private fun PlainDialField(scale: GrindScale, value: Double?, onValue: (Double?) -> Unit, onCanvas: Boolean) {
    // Text of its own, so "14." survives while you are still typing the decimal.
    var text by remember { mutableStateOf(value?.let(scale::format).orEmpty()) }
    val parsed = scale.parseReading(text)
    // Follow a value set from outside (a "Use 70" pill) without fighting the keyboard.
    LaunchedEffect(value) {
        if (scale.parseReading(text) != value) text = value?.let(scale::format).orEmpty()
    }
    val outOfRange = parsed != null && scale.max > scale.min && (parsed < scale.min || parsed > scale.max)
    FieldWell(
        label = null,
        value = text,
        onChange = { input ->
            text = input.take(8)
            onValue(scale.parseReading(text))
        },
        suffix = scale.unit.ifBlank { null },
        numeric = true,
        onCanvas = onCanvas,
        error = if (text.isNotBlank() && parsed == null) "That is not a number this dial can show." else null,
        warning = if (outOfRange) {
            "This grinder's dial runs ${scale.format(scale.min)} to ${scale.format(scale.max)}."
        } else {
            null
        },
    )
}
