package com.laksh.coffee.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.laksh.coffee.core.greeting
import java.time.LocalDate

/**
 * The calendar day, provided at the root from CoffeeApp.today (re-read on every resume). The tabs
 * stay composed between visits, so a screen that asked LocalDate.now() while composing would keep
 * yesterday's answer; reading this instead recomposes it when the day changes. Static: it changes
 * once a day.
 */
val LocalToday = staticCompositionLocalOf<LocalDate> { LocalDate.now() }

/**
 * "Morning", "Afternoon"... re-read each time this screen resumes (its tab is selected again, or
 * the app comes back), and recomposing only when the word actually changes.
 */
@Composable
fun rememberGreeting(): String {
    var word by remember { mutableStateOf(greeting()) }
    LifecycleResumeEffect(Unit) {
        word = greeting()
        onPauseOrDispose { }
    }
    return word
}
