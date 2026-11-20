package com.laksh.coffee

import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.ui.components.LocalToday
import com.laksh.coffee.ui.nav.CoffeeAppRoot
import com.laksh.coffee.ui.nav.FirstLaunch
import com.laksh.coffee.ui.nav.KeyboardShortcuts
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Units
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as CoffeeApp
        // Debug builds only: adb extras to seed data and open a screen. No-ops in release.
        val startRoute = DebugTools.startRoute(intent)
        lifecycleScope.launch { DebugTools.seedIfRequested(intent, app) }

        // The saved theme before the first frame. Starting from the default and flipping on frame
        // two recomposed the whole tree (the colours are a static local). DataStore answers in a
        // few milliseconds; the cap only guards a stuck disk.
        val initial = app.settingsState.value
            ?: runBlocking { withTimeoutOrNull(250) { app.settingsState.filterNotNull().first() } }
            ?: Settings()

        // The first-launch gate, asked once by the app root. Reading emptiness as well as the flag
        // means someone who already has brews is never sent to setup, even before StartupTasks
        // has marked them as set up. adb-driven debug launches go where they were told.
        val shouldOnboard: (suspend () -> Boolean)? =
            if (initial.onboardingDone || DebugTools.isDriven(intent)) {
                null
            } else {
                {
                    FirstLaunch.shouldOnboard(
                        onboardingDone = app.settings.settings.first().onboardingDone,
                        hasData = !app.repository.isEmpty(),
                    )
                }
            }

        setContent {
            val settings by app.settingsState.collectAsStateWithLifecycle()
            val today by app.today.collectAsStateWithLifecycle()
            val current = settings ?: initial
            CoffeeTheme(darkMode = current.darkMode, units = Units.of(current)) {
                CompositionLocalProvider(LocalToday provides today) {
                    CoffeeAppRoot(
                        startRoute = startRoute,
                        shouldOnboard = shouldOnboard,
                        sidebarCollapsed = current.sidebarCollapsed,
                        // Per device, never in a backup. App-scoped so a quick second press still lands.
                        onSidebarCollapsedChange = { collapsed ->
                            app.appScope.launch { app.settings.setSidebarCollapsed(collapsed) }
                        },
                    )
                }
            }
        }
    }

    /**
     * Meta+/ (the system keyboard shortcut helper) lists the app's shortcuts, from the same list
     * the in-app `?` dialog shows.
     */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        KeyboardShortcuts.groups.forEach { group ->
            data += KeyboardShortcutGroup(
                group.title,
                group.items.map { KeyboardShortcutInfo(it.label, it.keyCode, it.modifiers) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // A process that lived through midnight picks the new day up here.
        (application as CoffeeApp).refreshToday()
        requestHighestRefreshRate()
    }

    /**
     * Asks for the display's fastest mode at the current resolution. Some OEM skins hold
     * third-party apps at 60 Hz unless the window asks; the system can still lower it for
     * power or heat. Same resolution only, so the request never triggers a resolution switch.
     */
    private fun requestHighestRefreshRate() {
        val display: Display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else null)
            ?: @Suppress("DEPRECATION") windowManager.defaultDisplay
            ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: return
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != best.modeId) {
            attrs.preferredDisplayModeId = best.modeId
            window.attributes = attrs
        }
    }
}
