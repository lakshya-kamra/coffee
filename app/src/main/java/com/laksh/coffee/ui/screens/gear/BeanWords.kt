package com.laksh.coffee.ui.screens.gear

import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.TermKind

/** Pure wording for a bag of coffee: the card's meta line, the rest status, the altitude text. */
object BeanWords {

    /**
     * Days until the rest the roaster suggested is over; null when there is no rest recorded, no
     * roast date, or the coffee is ready.
     */
    fun restDaysLeft(bean: BeanEntity, todayEpochDay: Long): Long? {
        val rest = bean.restDays ?: return null
        val roasted = bean.roastedOnEpochDay ?: return null
        val left = roasted + rest - todayEpochDay
        return left.takeIf { it > 0 }
    }

    /** "ready in 3 days", to follow the word "Resting". */
    fun restDetail(daysLeft: Long): String = if (daysLeft == 1L) "ready tomorrow" else "ready in $daysLeft days"

    /** "Ethiopia, Guji · Washed · Light": what the card says under the name. */
    fun meta(bean: BeanEntity): String = listOfNotNull(
        // Trimmed for the line only, so a value saved with a trailing space never reads "Ethiopia , Guji".
        listOf(bean.origin, bean.region).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ").takeIf { it.isNotEmpty() },
        Catalog.label(TermKind.Process, bean.processId),
        Catalog.label(TermKind.RoastLevel, bean.roastLevelId),
        "Decaf".takeIf { bean.isDecaf },
    ).joinToString(" · ")

    /** "1700" or "1700-1900" → the two numbers as typed text. */
    fun altitudeParts(masl: String): Pair<String, String> {
        val nums = Regex("\\d{2,5}").findAll(masl).map { it.value }.toList()
        return (nums.getOrNull(0).orEmpty()) to (nums.getOrNull(1).orEmpty())
    }

    /** Two typed numbers → "1700", "1700-1900" or "". The pair is stored low first. */
    fun altitudeText(low: String, high: String): String {
        val a = low.trim().toIntOrNull()
        val b = high.trim().toIntOrNull()
        return when {
            a != null && b != null && a != b -> "${minOf(a, b)}-${maxOf(a, b)}"
            a != null -> a.toString()
            b != null -> b.toString()
            else -> ""
        }
    }

    /** Custom varietal names typed in the picker, added to the free-text field once each. */
    fun appendVarietal(existing: String, name: String): String {
        val clean = name.trim()
        if (clean.isEmpty()) return existing
        val have = existing.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (have.any { it.equals(clean, ignoreCase = true) }) existing else (have + clean).joinToString(", ")
    }
}
