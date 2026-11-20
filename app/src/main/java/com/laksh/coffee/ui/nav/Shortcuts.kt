package com.laksh.coffee.ui.nav

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import com.laksh.coffee.ui.components.CoffeeSheet
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.SheetRole
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Num

// ---------------------------------------------------------------- the one list

/** One shortcut as people read it ([keys]) and as Android's shortcut helper wants it. */
@Immutable
data class Shortcut(val keys: String, val label: String, val keyCode: Int, val modifiers: Int = 0)

@Immutable
data class ShortcutGroup(val title: String, val items: List<Shortcut>)

/**
 * Every keyboard shortcut, in groups. The `?` dialog and Meta+/ (the system shortcut helper,
 * MainActivity.onProvideKeyboardShortcuts) both read this list, so they can never disagree.
 * Brewing keys are handled by the brew screen.
 */
object KeyboardShortcuts {
    val groups: List<ShortcutGroup> = listOf(
        ShortcutGroup(
            "Navigation",
            listOf(
                Shortcut("1", "Today", AndroidKeyEvent.KEYCODE_1),
                Shortcut("2", "Recipes", AndroidKeyEvent.KEYCODE_2),
                Shortcut("3", "Log", AndroidKeyEvent.KEYCODE_3),
                Shortcut("4", "Insights", AndroidKeyEvent.KEYCODE_4),
                Shortcut("5", "Gear", AndroidKeyEvent.KEYCODE_5),
                Shortcut("[", "Fold or unfold the sidebar", AndroidKeyEvent.KEYCODE_LEFT_BRACKET),
                Shortcut("?", "Keyboard shortcuts", AndroidKeyEvent.KEYCODE_SLASH, AndroidKeyEvent.META_SHIFT_ON),
                Shortcut("Esc", "Back", AndroidKeyEvent.KEYCODE_ESCAPE),
            ),
        ),
        ShortcutGroup(
            "Brewing",
            listOf(
                Shortcut("B", "Start the next brew", AndroidKeyEvent.KEYCODE_B),
                Shortcut("Space", "Pause or resume", AndroidKeyEvent.KEYCODE_SPACE),
                Shortcut("Left / Right", "Back or forward 5 seconds", AndroidKeyEvent.KEYCODE_DPAD_RIGHT),
                Shortcut("N", "Next step", AndroidKeyEvent.KEYCODE_N),
                Shortcut("F", "Finish", AndroidKeyEvent.KEYCODE_F),
                Shortcut("M", "Chime on or off", AndroidKeyEvent.KEYCODE_M),
            ),
        ),
        ShortcutGroup(
            "Lists",
            listOf(
                Shortcut("/", "Search the list", AndroidKeyEvent.KEYCODE_SLASH),
                Shortcut("Ctrl F", "Search the list", AndroidKeyEvent.KEYCODE_F, AndroidKeyEvent.META_CTRL_ON),
                Shortcut("Up / Down", "Move between rows", AndroidKeyEvent.KEYCODE_DPAD_DOWN),
                Shortcut("J / K", "Next or previous brew in the log", AndroidKeyEvent.KEYCODE_J),
                Shortcut("Enter", "Open", AndroidKeyEvent.KEYCODE_ENTER),
                Shortcut("B", "Brew the selected brew again (Log)", AndroidKeyEvent.KEYCODE_B),
                Shortcut("Delete", "Delete the selected brew (asks first)", AndroidKeyEvent.KEYCODE_FORWARD_DEL),
                Shortcut("N", "Add a coffee or grinder (Gear)", AndroidKeyEvent.KEYCODE_N),
                Shortcut("Ctrl S", "Save the open editor", AndroidKeyEvent.KEYCODE_S, AndroidKeyEvent.META_CTRL_ON),
            ),
        ),
    )
}

// ----------------------------------------------------------- what the root does

/** What a key press at the app root asks for. Pure mapping, so it can be tested. */
sealed interface ShortcutAction {
    data class SwitchTab(val tab: AppTab) : ShortcutAction
    data object FocusSearch : ShortcutAction
    data object ShowShortcuts : ShortcutAction
    data object ToggleSidebar : ShortcutAction
    data object StartBrew : ShortcutAction
}

object ShortcutKeys {
    /**
     * Keys with Ctrl (or Cmd): these work even while a text field has focus, so the root reads
     * them before the field does.
     */
    fun chord(e: KeyEvent): ShortcutAction? {
        if (e.type != KeyEventType.KeyDown || !(e.isCtrlPressed || e.isMetaPressed) || e.isAltPressed) return null
        return when (e.key) {
            Key.F -> ShortcutAction.FocusSearch
            Key.Backslash -> ShortcutAction.ToggleSidebar
            else -> null
        }
    }

    /**
     * Plain keys. The root reads them after the focused element, so a text field that takes the
     * character (typing "3" in a dose) keeps it and nothing switches tabs. Read by character, not
     * key position, so `?` and `[` work on any keyboard layout.
     */
    fun plain(e: KeyEvent): ShortcutAction? {
        if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return null
        return plain(e.utf16CodePoint.toChar())
    }

    fun plain(ch: Char): ShortcutAction? = when (ch) {
        in '1'..'5' -> ShortcutAction.SwitchTab(AppTab.entries[ch - '1'])
        '/' -> ShortcutAction.FocusSearch
        '?' -> ShortcutAction.ShowShortcuts
        '[' -> ShortcutAction.ToggleSidebar
        'b', 'B' -> ShortcutAction.StartBrew
        else -> null
    }
}

// ------------------------------------------------------- list search focus hook

/**
 * Where `/` and Ctrl+F send focus: the search field of the tab on screen. A list screen opts in
 * by adding [listSearchTarget] to its search field; tabs without one ignore the keys.
 */
@Stable
class ListSearchFocus {
    private val targets = mutableMapOf<AppTab, FocusRequester>()

    fun register(tab: AppTab, requester: FocusRequester) {
        targets[tab] = requester
    }

    fun unregister(tab: AppTab, requester: FocusRequester) {
        if (targets[tab] === requester) targets.remove(tab)
    }

    /** True when the tab had a search field and it took focus. */
    fun focus(tab: AppTab): Boolean = targets[tab]?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
}

val LocalListSearchFocus = staticCompositionLocalOf { ListSearchFocus() }

/** The tab a composable sits in (null outside the tabs). */
val LocalCurrentTab = staticCompositionLocalOf<AppTab?> { null }

/**
 * Keys a tab's own screen handles (Log's J/K, Enter, B, Delete; Gear's N). The root holds focus
 * whenever nothing else does, so a screen never sees a key on its own: the root asks the tab on
 * screen first, then falls back to the global shortcuts.
 */
@Stable
class TabKeyHandlers {
    private val handlers = mutableMapOf<AppTab, (KeyEvent) -> Boolean>()

    fun register(tab: AppTab, handler: (KeyEvent) -> Boolean) {
        handlers[tab] = handler
    }

    fun unregister(tab: AppTab, handler: (KeyEvent) -> Boolean) {
        if (handlers[tab] === handler) handlers.remove(tab)
    }

    fun handle(tab: AppTab, e: KeyEvent): Boolean = handlers[tab]?.invoke(e) ?: false
}

val LocalTabKeyHandlers = staticCompositionLocalOf { TabKeyHandlers() }

/** Registers [handler] for the tab this composable sits in, for as long as it is composed. */
@Composable
fun TabKeys(handler: (KeyEvent) -> Boolean) {
    val registry = LocalTabKeyHandlers.current
    val tab = LocalCurrentTab.current ?: return
    val latest = androidx.compose.runtime.rememberUpdatedState(handler)
    val stable = remember { { e: KeyEvent -> latest.value(e) } }
    DisposableEffect(registry, tab, stable) {
        registry.register(tab, stable)
        onDispose { registry.unregister(tab, stable) }
    }
}

/** Makes this text field the target of `/` and Ctrl+F for the tab it sits in. */
@Composable
fun Modifier.listSearchTarget(): Modifier {
    val registry = LocalListSearchFocus.current
    val tab = LocalCurrentTab.current ?: return this
    val requester = remember { FocusRequester() }
    DisposableEffect(registry, tab, requester) {
        registry.register(tab, requester)
        onDispose { registry.unregister(tab, requester) }
    }
    return focusRequester(requester)
}

// ------------------------------------------------------------------- the dialog

/** The `?` dialog: every shortcut, grouped (content role: a sheet on phones, a dialog when wide). */
@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    val colors = CoffeeTheme.colors
    CoffeeSheet(onDismissRequest = onDismiss, role = SheetRole.Content) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                "Keyboard shortcuts",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.inkHigh,
                modifier = Modifier.asHeading(),
            )
            KeyboardShortcuts.groups.forEach { group ->
                SectionLabel(group.title, topGap = 20.dp)
                group.items.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            s.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkHigh,
                            modifier = Modifier.weight(1f),
                        )
                        Kbd(s.keys)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // Inset so the keyboard focus ring is not clipped by the scrolling column.
            SecondaryButton("Done", onDismiss, modifier = Modifier.fillMaxWidth().padding(4.dp))
        }
    }
}

private val KbdShape = RoundedCornerShape(6.dp)

/** A key cap: tabular ink on surface2. */
@Composable
fun Kbd(keys: String, modifier: Modifier = Modifier) {
    val colors = CoffeeTheme.colors
    Box(
        modifier
            .heightIn(min = 24.dp)
            .background(colors.surface2, KbdShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(keys, style = Num.Inline, color = colors.inkMid, maxLines = 1)
    }
}
