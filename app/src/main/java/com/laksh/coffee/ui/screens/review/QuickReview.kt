package com.laksh.coffee.ui.screens.review

/**
 * The quick review's "ANYTHING OFF?" toggles (v4 §4.4) and the faults they write. The faults are
 * the same vocabulary the detailed review uses, so a quick review reads like any other brew to the
 * first read, the log and the analysis. Web twin: the same four pairs.
 */
object QuickReview {
    /** Toggle label to the fault it stands for, in the order they show. */
    val TOGGLES: List<Pair<String, String>> = listOf(
        "Too sour" to "Sour",
        "Too bitter" to "Bitter",
        "Too weak" to "Too weak",
        "Too strong" to "Too strong",
    )

    fun faultFor(toggle: String): String? = TOGGLES.firstOrNull { it.first == toggle }?.second

    fun isOn(toggle: String, faults: List<String>): Boolean = faultFor(toggle)?.let { it in faults } ?: false

    /** The faults after flipping one toggle; any other fault already chosen stays. */
    fun toggle(faults: List<String>, toggle: String): List<String> {
        val fault = faultFor(toggle) ?: return faults
        return if (fault in faults) faults - fault else faults + fault
    }

    /** Quick unless the person chose detailed (a fresh install is quick, §6.1). */
    fun isQuick(reviewMode: String): Boolean = reviewMode != "detailed"
}
