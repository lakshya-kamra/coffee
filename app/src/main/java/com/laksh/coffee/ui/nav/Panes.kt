package com.laksh.coffee.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.laksh.coffee.ui.theme.CoffeeTheme

// ------------------------------------------------------------------ the rules (pure)

/** A detail that can sit in a tab's pane (v4 §2.3). */
sealed interface PaneDetail {
    /** The tab whose list it belongs beside. */
    val tab: AppTab

    /** The full-window route that shows the same thing on narrow windows. */
    val route: String

    data class Recipe(val id: String) : PaneDetail {
        override val tab: AppTab get() = AppTab.Recipes
        override val route: String get() = Routes.recipe(id, SharedKeys.FROM_RECIPES)
    }

    data class Brew(val logId: Long) : PaneDetail {
        override val tab: AppTab get() = AppTab.Log
        override val route: String get() = Routes.brewDetail(logId)
    }

    companion object {
        /** The pane detail a back-stack entry shows, from its route pattern and arguments. */
        fun of(routePattern: String?, id: String?, logId: Long?): PaneDetail? = when (routePattern) {
            Routes.RECIPE -> id?.takeIf { it.isNotBlank() }?.let(::Recipe)
            Routes.BREW_DETAIL -> logId?.takeIf { it > 0L }?.let(::Brew)
            else -> null
        }

        /** The pane detail a concrete route string opens ("recipe/v60?from=home", "brewDetail/12"). */
        fun fromRoute(route: String): PaneDetail? {
            val path = route.substringBefore('?')
            return when {
                path.startsWith("recipe/") -> path.removePrefix("recipe/")
                    .takeIf { it.isNotEmpty() && '/' !in it }
                    ?.let { Recipe(java.net.URLDecoder.decode(it, "UTF-8")) }
                path.startsWith("brewDetail/") -> path.removePrefix("brewDetail/").toLongOrNull()?.takeIf { it > 0L }?.let(::Brew)
                else -> null
            }
        }
    }
}

/**
 * What each tab's pane shows by the user's choice. Null means "nothing chosen": the pane shows its
 * default (the next-brew recipe, the newest brew), and back no longer belongs to the pane.
 * Saved at the app root, so rotation, resizes and process death keep it (§2.10).
 */
@Immutable
data class PaneSelections(val recipe: String? = null, val log: Long? = null) {
    fun forTab(tab: AppTab): PaneDetail? = when (tab) {
        AppTab.Recipes -> recipe?.let { PaneDetail.Recipe(it) }
        AppTab.Log -> log?.let { PaneDetail.Brew(it) }
        else -> null
    }

    fun with(detail: PaneDetail): PaneSelections = when (detail) {
        is PaneDetail.Recipe -> copy(recipe = detail.id)
        is PaneDetail.Brew -> copy(log = detail.logId)
    }

    fun cleared(tab: AppTab): PaneSelections = when (tab) {
        AppTab.Recipes -> copy(recipe = null)
        AppTab.Log -> copy(log = null)
        else -> this
    }

    companion object {
        val Saver: Saver<PaneSelections, Any> = listSaver(
            save = { listOf(it.recipe, it.log) },
            restore = { PaneSelections(it[0] as String?, it[1] as Long?) },
        )
    }
}

/** The narrow/widen rules, pure so they are unit-tested. */
object PaneRules {
    /**
     * Opening [detail] selects it in the pane instead of pushing a page: wide windows, own tab.
     * (The root also sends a brew opened from another tab to the Log's pane; see [widen].)
     */
    fun selectsInPane(usesPanes: Boolean, selectedTab: AppTab, detail: PaneDetail): Boolean =
        usesPanes && detail.tab == selectedTab

    /**
     * The window narrowed below expanded while [narrowedOn] was the tab on screen: once back at the
     * base, that tab's chosen detail becomes a pushed page, so it stays on screen, and its pane
     * selection is cleared. Null when there is nothing to do. Only at the base, so a brew opened
     * from a pane comes back to the same detail after it; and only while the same tab is still
     * the one on screen, so a choice left in another tab is never pushed later, when that tab is
     * tapped (it waits for the window to widen again).
     */
    fun narrow(
        usesPanes: Boolean,
        atBase: Boolean,
        selectedTab: AppTab,
        selections: PaneSelections,
        narrowedOn: AppTab = selectedTab,
    ): Pair<PaneDetail, PaneSelections>? {
        if (usesPanes || !atBase || selectedTab != narrowedOn) return null
        val detail = selections.forTab(selectedTab) ?: return null
        return detail to selections.cleared(selectedTab)
    }

    /**
     * The window widened (or something pushed a pane-able page while wide): a detail page of the
     * selected tab that sits directly on the tabs moves into the pane. A brew page moves into the
     * Log's pane from any tab (§3.1 E: Today's and Insights' brews open the Log with that brew
     * selected), so the caller switches to [PaneDetail.tab]. Returns the new selections, meaning
     * "pop that page", or null to leave the stack alone. A recipe opened from Today stays a page:
     * its header flies from Today's card.
     */
    fun widen(
        usesPanes: Boolean,
        stackDepth: Int,
        top: PaneDetail?,
        selectedTab: AppTab,
        selections: PaneSelections,
    ): PaneSelections? {
        if (!usesPanes || stackDepth != 1 || top == null) return null
        if (top.tab != selectedTab && top !is PaneDetail.Brew) return null
        return selections.with(top)
    }
}

/** Compare open in the Log pane: [other] beside the brew [forLog] it was opened from. */
@Immutable
data class LogCompare(val forLog: Long, val other: Long)

/**
 * The root's pane state: the saved [selections], the Log pane's [compare], and the predictive-back
 * progress of the detail pane (0..1, read only in its layer).
 */
@Stable
class PaneController(initial: PaneSelections = PaneSelections(), compare: LogCompare? = null) {
    var selections by mutableStateOf(initial)

    /**
     * Held here rather than in the Log tab, so back can close it first (§2.5). Choosing another
     * brew drops it: a compare belongs to the brew it was opened from.
     */
    var compare by mutableStateOf(compare)
    val backProgress = Animatable(0f, visibilityThreshold = 0.001f)

    fun select(detail: PaneDetail) {
        if (detail is PaneDetail.Brew && compare?.forLog != detail.logId) compare = null
        selections = selections.with(detail)
    }

    fun clear(tab: AppTab) {
        if (tab == AppTab.Log) compare = null
        selections = selections.cleared(tab)
    }

    /** True when back has something to close in [tab]'s pane. */
    fun hasBack(tab: AppTab): Boolean = (tab == AppTab.Log && compare != null) || selections.forTab(tab) != null

    /** One step of back in [tab]'s pane: a compare closes before the brew under it. */
    fun back(tab: AppTab) {
        if (tab == AppTab.Log && compare != null) compare = null else clear(tab)
    }

    companion object {
        val Saver: Saver<PaneController, Any> = listSaver(
            save = { listOf(it.selections.recipe, it.selections.log, it.compare?.forLog, it.compare?.other) },
            restore = {
                val forLog = it.getOrNull(2) as Long?
                val other = it.getOrNull(3) as Long?
                PaneController(
                    PaneSelections(it[0] as String?, it[1] as Long?),
                    if (forLog != null && other != null) LogCompare(forLog, other) else null,
                )
            },
        )
    }
}

// --------------------------------------------------------------- what screens read

/**
 * Where a detail screen is: [inPane] when it sits beside its list (no back arrow, no shared header
 * flight, its primary action pinned to the pane rather than the window); [close] returns the pane
 * to its default. Outside a pane it is the default value.
 */
@Immutable
data class PaneContext(
    val inPane: Boolean = false,
    val close: () -> Unit = {},
    /** False while the pane shows its default: there is nothing to close. */
    val canClose: Boolean = false,
)

val LocalPane = staticCompositionLocalOf { PaneContext() }

/** The selected tab's pane selection, for a list to mark its selected row (null when none). */
val LocalPaneSelection = compositionLocalOf<PaneDetail?> { null }

/** Gear's sections, in its segmented control's order. */
enum class GearSection(val label: String) { Beans("Beans"), Grinders("Grinders"), Settings("Settings") }

/**
 * Gear's selection, held at the root so the sidebar's Gear rows can switch the section and
 * Gear's list-detail (V4-AND3) can keep its chosen bean or grinder across resizes. Gear reads
 * [section] (an index in [GearSection] order) and writes it back when its own control changes.
 */
@Stable
class GearPaneState(section: Int = GearSection.Beans.ordinal, item: String? = null) {
    /** The section Gear should show; written by the sidebar and by Gear itself. */
    var section by mutableIntStateOf(section)

    /** The chosen bean or grinder id in the section's pane ("bean:12", "grinder:3"), or null. */
    var item by mutableStateOf(item)

    /**
     * Set by the root's back handler; Gear answers it by choosing nothing, which asks first when
     * the open editor has unsaved changes.
     */
    var backRequested by mutableStateOf(false)

    companion object {
        val Saver: Saver<GearPaneState, Any> = listSaver(
            save = { listOf(it.section, it.item) },
            restore = { GearPaneState(it[0] as Int, it[1] as String?) },
        )
    }
}

val LocalGearPane = staticCompositionLocalOf { GearPaneState() }

// ---------------------------------------------------------------------- layout

/**
 * A list beside a detail (v4 §2.3, §2.11). Without [showDetail] the list fills the tab, at the
 * same position in the tree, so its scroll position and state survive crossing a breakpoint.
 *
 * The detail swaps with [AnimatedContent]: the outgoing detail leaves fast while the incoming one
 * fades in and drifts 8dp (§2.9.7). Each detail gets its own ViewModel store, keyed by
 * [detailKey], so two recipes never share one screen's ViewModel and a left detail's flows stop.
 * [backProgress] (0..1, predictive back) scales and fades the detail in its layer.
 */
@Composable
fun <K : Any> ListDetailLayout(
    showDetail: Boolean,
    listWidth: Dp,
    detailKey: K,
    list: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    backProgress: () -> Float = { 0f },
    detail: @Composable (K) -> Unit,
) {
    val rule = CoffeeTheme.colors.hairlineOnBg
    val drift = with(LocalDensity.current) { 8.dp.roundToPx() }
    Row(modifier.fillMaxSize()) {
        Box(if (showDetail) Modifier.width(listWidth).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()) {
            list()
        }
        if (showDetail) {
            // One true pixel between the panes; both stay on the canvas so cards keep their lift.
            Spacer(
                Modifier
                    .fillMaxHeight()
                    .width((1f / LocalDensity.current.density).dp)
                    .drawBehind { drawRect(rule) },
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val p = backProgress()
                        val s = 1f - 0.06f * p
                        scaleX = s
                        scaleY = s
                        alpha = 1f - 0.3f * p
                    },
            ) {
                AnimatedContent(
                    targetState = detailKey,
                    transitionSpec = {
                        (fadeIn(tween(160)) + slideInHorizontally(tween(160)) { drift })
                            .togetherWith(fadeOut(tween(90)))
                    },
                    label = "paneDetail",
                    modifier = Modifier.fillMaxSize(),
                ) { key ->
                    // Inside a pane there is no route scope to fly from or to.
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides null) {
                        PaneViewModelScope(key) {
                            Box(Modifier.fillMaxSize()) { detail(key) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Gives [content] a ViewModel store of its own for as long as [key] is shown, cleared when it
 * leaves. Creation extras come from the parent (the activity), so the app's factories find the
 * Application as usual.
 */
@Composable
fun PaneViewModelScope(key: Any, content: @Composable () -> Unit) {
    val parent = LocalViewModelStoreOwner.current
    val owner = remember(key) { PaneViewModelStoreOwner(parent) }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

private class PaneViewModelStoreOwner(private val parent: ViewModelStoreOwner?) :
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory
            ?: ViewModelProvider.NewInstanceFactory()

    // No ViewModel in the app uses a SavedStateHandle, so the parent's extras (the Application
    // above all) are all a factory needs.
    override val defaultViewModelCreationExtras: CreationExtras
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty
}
