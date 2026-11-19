package com.laksh.coffee.domain.grind

/**
 * How a grinder's dial is read out loud. The stored grind never depends on it: click grinders
 * always store total clicks from zero (burr contact), so this only changes the words.
 */
sealed interface DialFormat {

    /** "14.5": what you type is what you see. */
    data object Plain : DialFormat

    /** "3 turns + 2": count whole turns, then clicks. */
    data class TurnsAndClicks(val clicksPerTurn: Int) : DialFormat

    /** "7.5" = 7 turns + 5 clicks. 1.x behaviour for rows saved at 10 clicks a turn; only for cpr ≤ 10. */
    data class TurnsDotClicks(val clicksPerTurn: Int) : DialFormat

    /**
     * A numbered dial with a number every [ticksPerNumber] clicks (1Zpresso-style rings): 75 clicks
     * reads "7.5", one full rotation "10.0", and past it "1 turn + 2.5". [clicksPerRotation] 0 = the dial never
     * wraps (or the wrap is unknown).
     */
    data class NumberTick(val clicksPerRotation: Int, val ticksPerNumber: Int = 10) : DialFormat

    /**
     * Dial words for a stored value: null for [Plain] (the number already is the reading) or a negative value.
     *
     * Exactly one full turn of a numbered ring reads as the count carried on, the way the maker
     * writes it: 100 clicks on a 100-click ring is "10.0", not "1 turn + 0.0". [inTurns] keeps the
     * turn form, for a range that starts on the turn and runs past it ("1 turn + 0.0 to 1 turn + 7.0").
     */
    fun read(value: Int, inTurns: Boolean = false): String? {
        if (value < 0) return null
        return when (this) {
            Plain -> null
            is TurnsAndClicks -> turnsPlus(value / clicksPerTurn, (value % clicksPerTurn).toString())
            is TurnsDotClicks -> "${value / clicksPerTurn}.${value % clicksPerTurn}"
            is NumberTick -> {
                val turns = if (clicksPerRotation > 0) value / clicksPerRotation else 0
                val rest = if (clicksPerRotation > 0) value % clicksPerRotation else value
                val onDial = "${rest / ticksPerNumber}.${rest % ticksPerNumber}"
                when {
                    turns == 0 -> onDial
                    turns == 1 && rest == 0 && !inTurns -> "${value / ticksPerNumber}.${value % ticksPerNumber}"
                    else -> turnsPlus(turns, onDial)
                }
            }
        }
    }

    /** Whole turns are a real unit on this dial, so "one full turn" is something a person can do. */
    val talksInTurns: Boolean get() = this is TurnsAndClicks || this is TurnsDotClicks

    /** The dial shows a number to read out ("dial 7.5"), not just a click count. */
    val showsDialNumber: Boolean get() = this is TurnsDotClicks || this is NumberTick

    fun encode(): String = when (this) {
        Plain -> PLAIN
        is TurnsAndClicks -> TURNS
        is TurnsDotClicks -> TURNS_DOT
        is NumberTick -> "$NUMBER_TICK$ticksPerNumber"
    }

    companion object {
        fun turnsPlus(turns: Int, rest: String): String = "$turns ${if (turns == 1) "turn" else "turns"} + $rest"

        const val PLAIN = "plain"
        const val TURNS = "turns"
        const val TURNS_DOT = "turns-dot"
        const val NUMBER_TICK = "number-tick:"

        /** Null for "" (auto) and for anything this build can't use with [cpr]. */
        fun decode(s: String?, cpr: Int?): DialFormat? {
            val t = s?.trim().orEmpty()
            val turn = cpr?.takeIf { it > 0 }
            return when {
                t.isEmpty() -> null
                t == PLAIN -> Plain
                t == TURNS -> turn?.let { TurnsAndClicks(it) }
                t == TURNS_DOT -> turn?.takeIf { it <= 10 }?.let { TurnsDotClicks(it) }
                t.startsWith(NUMBER_TICK) -> t.removePrefix(NUMBER_TICK).toIntOrNull()
                    ?.takeIf { it in 1..10 }
                    ?.let { NumberTick(turn ?: 0, it) }
                else -> null
            }
        }
    }
}

/** A reading in the dial's own terms. Which fields matter depends on the [DialFormat]. */
data class DialReading(val turns: Int = 0, val number: Int = 0, val tick: Int = 0, val value: Double? = null)

/**
 * Catalog grinders whose dialReading text says the ring prints a number every ten clicks.
 * Frozen: read from shared/catalog/grinders.json on 2026-11-17, never added on a guess. The
 * catalog's own `dialFormat` normally decides first; this set is the fallback for rows whose
 * clicks per turn match the catalog.
 */
object NumberedDials {
    val IDS: Set<String> = setOf(
        "1zpresso-k-ultra", "1zpresso-k-max", "1zpresso-k-plus", "1zpresso-zp6-special",
        "1zpresso-j-ultra", "1zpresso-j-max", "1zpresso-x-ultra", "1zpresso-x-pro",
        "timemore-chestnut-s3",
    )
}
