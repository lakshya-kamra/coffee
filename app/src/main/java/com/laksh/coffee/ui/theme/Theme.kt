package com.laksh.coffee.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Every slot is set, including the five surfaceContainer* roles and the twelve Fixed roles.
// M3 1.4 reads those for sheets, dialogs, menus and switches; any slot left out falls back
// to the baseline purple and teal.
private val DarkScheme = darkColorScheme(
    primary = DarkCoffee.crema,
    onPrimary = DarkCoffee.onCrema,
    primaryContainer = DarkCoffee.cremaContainer,
    onPrimaryContainer = DarkCoffee.crema,
    inversePrimary = DarkCoffee.crema,
    secondary = DarkCoffee.inkMid,
    onSecondary = DarkCoffee.bg,
    secondaryContainer = DarkCoffee.surface3,
    onSecondaryContainer = DarkCoffee.inkHigh,
    tertiary = DarkCoffee.good,
    onTertiary = DarkCoffee.bg,
    tertiaryContainer = DarkCoffee.goodContainer,
    onTertiaryContainer = DarkCoffee.good,
    background = DarkCoffee.bg,
    onBackground = DarkCoffee.inkHigh,
    surface = DarkCoffee.surface1,
    onSurface = DarkCoffee.inkHigh,
    surfaceVariant = DarkCoffee.surface2,
    onSurfaceVariant = DarkCoffee.inkMid,
    surfaceTint = DarkCoffee.surface1,
    inverseSurface = DarkCoffee.inkHigh,
    inverseOnSurface = DarkCoffee.bg,
    error = DarkCoffee.fault,
    onError = DarkCoffee.bg,
    errorContainer = DarkCoffee.faultContainer,
    onErrorContainer = DarkCoffee.fault,
    outline = DarkCoffee.outlineStrong,
    outlineVariant = DarkCoffee.hairline,
    scrim = DarkCoffee.scrim,
    surfaceBright = DarkCoffee.surface3,
    surfaceContainer = DarkCoffee.surface1,
    surfaceContainerHigh = DarkCoffee.surface2,
    surfaceContainerHighest = DarkCoffee.surface3,
    surfaceContainerLow = DarkCoffee.surface1,
    surfaceContainerLowest = DarkCoffee.bg,
    surfaceDim = DarkCoffee.bg,
    primaryFixed = DarkCoffee.crema,
    primaryFixedDim = DarkCoffee.cremaContainer,
    onPrimaryFixed = DarkCoffee.onCrema,
    onPrimaryFixedVariant = DarkCoffee.crema,
    secondaryFixed = DarkCoffee.surface3,
    secondaryFixedDim = DarkCoffee.surface2,
    onSecondaryFixed = DarkCoffee.inkHigh,
    onSecondaryFixedVariant = DarkCoffee.inkMid,
    tertiaryFixed = DarkCoffee.good,
    tertiaryFixedDim = DarkCoffee.goodContainer,
    onTertiaryFixed = DarkCoffee.bg,
    onTertiaryFixedVariant = DarkCoffee.good,
)

private val LightScheme = lightColorScheme(
    primary = LightCoffee.crema,
    onPrimary = LightCoffee.onCrema,
    primaryContainer = LightCoffee.cremaContainer,
    onPrimaryContainer = LightCoffee.crema,
    inversePrimary = LightCoffee.crema,
    secondary = LightCoffee.inkMid,
    onSecondary = LightCoffee.bg,
    secondaryContainer = LightCoffee.surface3,
    onSecondaryContainer = LightCoffee.inkHigh,
    tertiary = LightCoffee.good,
    onTertiary = LightCoffee.bg,
    tertiaryContainer = LightCoffee.goodContainer,
    onTertiaryContainer = LightCoffee.good,
    background = LightCoffee.bg,
    onBackground = LightCoffee.inkHigh,
    surface = LightCoffee.surface1,
    onSurface = LightCoffee.inkHigh,
    surfaceVariant = LightCoffee.surface2,
    onSurfaceVariant = LightCoffee.inkMid,
    surfaceTint = LightCoffee.surface1,
    inverseSurface = LightCoffee.inkHigh,
    inverseOnSurface = LightCoffee.bg,
    error = LightCoffee.fault,
    onError = Color(0xFFFFFFFF),
    errorContainer = LightCoffee.faultContainer,
    onErrorContainer = LightCoffee.fault,
    outline = LightCoffee.outlineStrong,
    outlineVariant = LightCoffee.hairline,
    scrim = LightCoffee.scrim,
    surfaceBright = LightCoffee.surface3,
    surfaceContainer = LightCoffee.surface1,
    surfaceContainerHigh = LightCoffee.surface2,
    surfaceContainerHighest = LightCoffee.surface3,
    surfaceContainerLow = LightCoffee.surface1,
    surfaceContainerLowest = LightCoffee.bg,
    surfaceDim = LightCoffee.bg,
    primaryFixed = LightCoffee.crema,
    primaryFixedDim = LightCoffee.cremaContainer,
    onPrimaryFixed = LightCoffee.onCrema,
    onPrimaryFixedVariant = LightCoffee.crema,
    secondaryFixed = LightCoffee.surface3,
    secondaryFixedDim = LightCoffee.surface2,
    onSecondaryFixed = LightCoffee.inkHigh,
    onSecondaryFixedVariant = LightCoffee.inkMid,
    tertiaryFixed = LightCoffee.good,
    tertiaryFixedDim = LightCoffee.goodContainer,
    onTertiaryFixed = LightCoffee.bg,
    onTertiaryFixedVariant = LightCoffee.good,
)

/**
 * Static because the palette only changes with the theme mode, and then everything below
 * it should redraw anyway.
 */
val LocalCoffeeColors: ProvidableCompositionLocal<CoffeeColors> = staticCompositionLocalOf { DarkCoffee }

/**
 * `CoffeeTheme.colors.inkHigh` and friends. Shares its name with the [CoffeeTheme] function,
 * exactly as MaterialTheme does.
 */
object CoffeeTheme {
    val colors: CoffeeColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCoffeeColors.current
}

@Composable
fun CoffeeTheme(
    darkMode: String = "system",
    units: Units = Units(),
    content: @Composable () -> Unit,
) {
    val dark = when (darkMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkCoffee else LightCoffee
    val scheme = if (dark) DarkScheme else LightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    // MaterialExpressiveTheme and MotionScheme are internal in material3 1.4.0, so the stock
    // M3 components keep the standard scheme; our own motion lives in Motion.kt.
    MaterialTheme(
        colorScheme = scheme,
        shapes = CoffeeShapes,
        typography = CoffeeTypography,
    ) {
        // LocalContentColor defaults to black, so any Text outside a Surface (every page
        // title on the bare canvas) rendered black on black in dark mode. Fixed here once.
        CompositionLocalProvider(
            LocalCoffeeColors provides colors,
            LocalContentColor provides colors.inkHigh,
            LocalUnits provides units,
            content = content,
        )
    }
}
