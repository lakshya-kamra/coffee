@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)

package com.laksh.coffee.ui.nav

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.laksh.coffee.ui.ai.AiSettingsSheet
import com.laksh.coffee.ui.components.StatusBarScrim
import com.laksh.coffee.ui.onboarding.OnboardingScreen
import com.laksh.coffee.ui.recipes.EditorMode
import com.laksh.coffee.ui.recipes.RecipeEditorScreen
import com.laksh.coffee.ui.screens.brew.BrewScreen
import com.laksh.coffee.ui.screens.gear.GearScreen
import com.laksh.coffee.ui.screens.history.BrewDetailScreen
import com.laksh.coffee.ui.screens.history.HistoryScreen
import com.laksh.coffee.ui.screens.home.HomeScreen
import com.laksh.coffee.ui.screens.insights.InsightsScreen
import com.laksh.coffee.ui.screens.recipes.RecipeDetailScreen
import com.laksh.coffee.ui.screens.recipes.RecipeListScreen
import com.laksh.coffee.ui.screens.review.ReviewScreen
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.Motion
import kotlinx.coroutines.flow.first

/**
 * Route identities. The five tab routes are not NavHost destinations any more (the tabs live in
 * [TabsHost]), but they stay valid names: [CoffeeAppRoot]'s navigate() turns them into a tab
 * selection, so the debug start route, Review's "Ask" and Home's "See all" keep working.
 */
object Routes {
    /** The NavHost's resting destination: nothing on top of the tabs. */
    const val TABS = "tabs"

    const val HOME = "home"
    const val RECIPES = "recipes"
    // The origin list rides along so the detail header can fly back to the card it came from.
    const val RECIPE = "recipe/{id}?from={from}"
    const val BREW = "brew/{id}"
    const val REVIEW = "review/{logId}"
    const val HISTORY = "history"
    const val BREW_DETAIL = "brewDetail/{logId}"
    const val GEAR = "gear"
    const val ASK = "ask"

    /** Setup: first launch, Home's "Make it yours" card, and Gear › App › Run setup again. */
    const val ONBOARDING = "onboarding"

    // The recipe builder. No id = a new recipe; mode is new | edit | duplicate. "from" is the list
    // the page under it came from, so the saved recipe's page can fly back to the right card.
    const val RECIPE_EDIT = "recipeEdit?id={id}&mode={mode}&from={from}"

    fun recipe(id: String, from: String = SharedKeys.FROM_HOME) = "recipe/${segment(id)}?from=$from"
    fun brew(id: String) = "brew/${segment(id)}"
    fun review(logId: Long) = "review/$logId"
    fun brewDetail(logId: Long) = "brewDetail/$logId"

    fun recipeEdit(id: String?, mode: EditorMode, from: String = SharedKeys.FROM_RECIPES): String =
        "recipeEdit?mode=${mode.name.lowercase()}&from=$from" + (id?.let { "&id=${Uri.encode(it)}" } ?: "")

    /**
     * A recipe id as one path segment (navigation decodes it again). The app's own ids pass
     * through unchanged; an id from an imported file, "my/v60" or "iced#2", would otherwise
     * match no destination and crash the tap that opens it.
     */
    internal fun segment(id: String): String = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    fun editorMode(arg: String?): EditorMode =
        EditorMode.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) } ?: EditorMode.New
}

/** When a launch opens setup by itself. Pure, so the rule can be tested. */
object FirstLaunch {
    /**
     * Only an install with nothing in it, whose setup was never finished or skipped. Checking for
     * data as well as the flag means someone who already has brews is never sent to setup, even
     * on the first launch after an update, before the flag has been written for them.
     */
    fun shouldOnboard(onboardingDone: Boolean, hasData: Boolean): Boolean = !onboardingDone && !hasData
}

/**
 * The bottom-bar sections, in bar order. The fourth keeps the route string "ask": the debug
 * start route and the screenshot harness both open it by that name. [tag] is the test tag the
 * baseline-profile journeys tap.
 */
enum class AppTab(val route: String, val label: String, val icon: ImageVector) {
    Today(Routes.HOME, "Today", Icons.Rounded.Coffee),
    Recipes(Routes.RECIPES, "Recipes", Icons.AutoMirrored.Rounded.MenuBook),
    Log(Routes.HISTORY, "Log", Icons.Rounded.History),
    Insights(Routes.ASK, "Insights", Icons.Rounded.Insights),
    Gear(Routes.GEAR, "Gear", Icons.Rounded.Tune);

    val tag: String get() = "tab-" + name.lowercase()

    companion object {
        fun forRoute(route: String?): AppTab? = entries.firstOrNull { it.route == route }
    }
}

private val TABS = AppTab.entries

/**
 * App shell:
 *
 * ```
 * SharedTransitionLayout
 * ├─ TabsHost        five tab roots, kept composed; switched by layer properties only
 * ├─ NavHost         details only (recipe, recipe builder, brew, review, brewDetail, setup)
 * │                  over an empty "tabs" start
 * ├─ status-bar scrim
 * ├─ bottom bar + its one scrim
 * └─ first-launch cover (a fresh install only, for the few frames the gate takes)
 * ```
 *
 * The tabs sit under the NavHost, so pushing a detail never disposes them, and popping back
 * shows the exact list and scroll position with no recomposition.
 *
 * [shouldOnboard] is the first-launch gate: null when setup is already done (or adb is driving
 * the app), else a check that is true only for an install with nothing in it. While it runs the
 * canvas is covered, so a new user sees setup first and never a flash of an empty Today.
 */
@Composable
fun CoffeeAppRoot(startRoute: String? = null, shouldOnboard: (suspend () -> Boolean)? = null) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val colors = CoffeeTheme.colors

    var selected by rememberSaveable { mutableStateOf(AppTab.Today) }
    // The "tabs" destination's own visibility scope while it is composed: it is what tab cards
    // fly from (shared header), and null means a settled detail fully covers the tabs.
    var baseScope by remember { mutableStateOf<AnimatedVisibilityScope?>(null) }
    val atBase = currentRoute == null || currentRoute == Routes.TABS

    // Remembered, so the tab pages' callbacks never change identity and a switch can skip them.
    val go: (String) -> Unit = remember(nav) {
        { route ->
            val tab = AppTab.forRoute(route)
            if (tab != null) {
                if (nav.currentDestination?.route != Routes.TABS) nav.popBackStack(Routes.TABS, inclusive = false)
                selected = tab
            } else {
                nav.navigate(route)
            }
        }
    }

    // Only ever set by the debug adb hook; lets the emulator open any screen directly.
    LaunchedEffect(startRoute) {
        if (startRoute != null) go(startRoute)
    }

    // Saved, so a rotation in the middle of setup does not ask the question (and open it) again.
    var deciding by rememberSaveable { mutableStateOf(shouldOnboard != null) }
    LaunchedEffect(Unit) {
        if (!deciding) return@LaunchedEffect
        // A stuck disk must not leave the cover up: any failure reads as "do not onboard".
        val onboard = shouldOnboard != null && runCatching { shouldOnboard() }.getOrDefault(false)
        if (onboard) {
            nav.navigate(Routes.ONBOARDING) { launchSingleTop = true }
            // Lift the cover only once setup is the one thing on screen.
            nav.visibleEntries.first { shown -> shown.size == 1 && shown[0].destination.route == Routes.ONBOARDING }
        }
        deciding = false
    }

    // Back from another section returns to Today first, as it did when the tabs were a stack.
    BackHandler(enabled = atBase && selected != AppTab.Today) { selected = AppTab.Today }

    // Every screen scrolls under the status bar except the running brew and setup, whose mesh
    // owns the top.
    val topScrim by animateFloatAsState(
        if (currentRoute == Routes.BREW || currentRoute == Routes.ONBOARDING) 0f else 1f,
        Motion.EffectsDefault,
        label = "topScrim",
    )
    // 0 = bar shown, 1 = hidden below the screen. Float, moved in the layer, never re-laid out.
    val barHidden = animateFloatAsState(if (atBase) 0f else 1f, Motion.EffectsDefault, label = "barHidden")
    val cover = animateFloatAsState(if (deciding) 1f else 0f, Motion.EffectsFast, label = "launchCover")

    // CoffeeTheme already provides inkHigh as the content colour. Providing it again at the
    // app root means text on the bare canvas can never fall back to black, whatever wraps it.
    CompositionLocalProvider(LocalContentColor provides colors.inkHigh) {
        SharedTransitionLayout(
            Modifier
                .fillMaxSize()
                .background(colors.bg)
                // Lets UiAutomator find testTag()s by resource id (baseline profile, benchmarks).
                .semantics { testTagsAsResourceId = true },
        ) {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides baseScope) {
                        TabsHost(
                            tabs = TABS,
                            selected = selected,
                            // atBase covers the first frame (before the base entry's effect runs) and
                            // the first frame of a pop; baseScope covers a push until it settles and
                            // a predictive-back gesture that has not committed yet.
                            visible = baseScope != null || atBase,
                        ) { tab ->
                            TabContent(tab, nav, go)
                        }
                    }

                    DetailNavHost(
                        nav = nav,
                        go = go,
                        onBaseScope = { scope -> baseScope = scope },
                        onBaseGone = { scope -> if (baseScope === scope) baseScope = null },
                        onFinishedToToday = { selected = AppTab.Today },
                        // The first-launch setup is simply there when the cover lifts.
                        instantSetup = { deciding },
                    )

                    // Drawn once here rather than per screen, so no scrolling screen can forget it.
                    // The alpha is read in the layer, so the fade never recomposes the root.
                    StatusBarScrim(
                        Modifier
                            .align(Alignment.TopCenter)
                            .graphicsLayer { alpha = topScrim },
                    )

                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .graphicsLayer {
                                val h = barHidden.value
                                // Moved fully off screen, so a hidden bar takes no taps.
                                translationY = h * size.height
                                alpha = 1f - h
                            },
                    ) {
                        // The one bar scrim. Content fades out before it reaches the bar and never
                        // shows in the strip below it; lists add listBottomPadding() so their last
                        // row still scrolls fully clear.
                        BarScrim(Modifier.matchParentSize())
                        Column {
                            Spacer(Modifier.height(ScrimFade))
                            BottomBar(
                                selected = selected,
                                onSelect = { tab -> if (atBase) selected = tab },
                            )
                        }
                    }

                    // Composed only on a first launch, and gone as soon as the gate has answered.
                    if (deciding || cover.value > 0f) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = cover.value }
                                .background(colors.bg)
                                // Nothing under the cover can be tapped while it is up.
                                .pointerInput(Unit) {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent(tab: AppTab, nav: NavHostController, go: (String) -> Unit) {
    when (tab) {
        // Home passes its own shared-key prefix: the carousel and the next-brew pass can show the
        // same recipe, so they must not share a key.
        AppTab.Today -> HomeScreen(
            onOpenRecipe = { id, from -> nav.navigate(Routes.recipe(id, from)) },
            onBrew = { nav.navigate(Routes.brew(it)) },
            onOpenLog = { nav.navigate(Routes.brewDetail(it)) },
            onSeeAllRecipes = { go(Routes.RECIPES) },
            onSeeHistory = { go(Routes.HISTORY) },
            onPersonalise = { nav.navigate(Routes.ONBOARDING) { launchSingleTop = true } },
        )
        AppTab.Recipes -> RecipeListScreen(
            onOpenRecipe = { nav.navigate(Routes.recipe(it, SharedKeys.FROM_RECIPES)) },
            onNewRecipe = {
                nav.navigate(Routes.recipeEdit(null, EditorMode.New)) { launchSingleTop = true }
            },
        )
        AppTab.Log -> HistoryScreen(onOpenLog = { nav.navigate(Routes.brewDetail(it)) })
        AppTab.Insights -> InsightsScreen(onOpenLog = { nav.navigate(Routes.brewDetail(it)) })
        AppTab.Gear -> GearScreen(
            onRunSetup = { nav.navigate(Routes.ONBOARDING) { launchSingleTop = true } },
        )
    }
}

/**
 * True when Review sits directly on top of the same brew's detail, which is how Edit and
 * "Fix in log" open it. Leaving Review then pops back to that detail, so the Log stack
 * survives and the refreshed pass (or the analysis now running) is what you land on.
 */
private fun NavHostController.reviewOpenedFromDetail(logId: Long): Boolean {
    val prev = previousBackStackEntry ?: return false
    return prev.destination.route == Routes.BREW_DETAIL && prev.arguments?.getLong("logId") == logId
}

/**
 * A detail page: an opaque canvas, the route's own AnimatedContentScope for shared-element
 * participants below it, and an optional Float rise (6 % of the height) driven by the route's
 * transition, so predictive back seeks it and it never steps whole pixels.
 */
@Composable
private fun AnimatedContentScope.DetailPage(rise: Boolean = false, content: @Composable () -> Unit) {
    val scope = this
    val offset = if (rise) {
        transition.animateFloat(
            transitionSpec = { spring(Motion.SpatialDefault.dampingRatio, Motion.SpatialDefault.stiffness, 0.001f) },
            label = "rise",
        ) { if (it == EnterExitState.Visible) 0f else 1f }
    } else {
        null
    }
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (offset != null) {
                    Modifier.graphicsLayer { translationY = offset.value * size.height * 0.06f }
                } else {
                    Modifier
                },
            )
            .background(CoffeeTheme.colors.bg),
    ) {
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides scope, content = content)
    }
}

// Detail push: the page fades in and grows from 94 % over tabs that hold still. Scale is a
// Float layer property, so it is sub-pixel smooth; pop is the mirror and predictive back seeks it.
private val DetailEnter: EnterTransition =
    fadeIn(Motion.EffectsDefault) + scaleIn(Motion.SpatialDefault, initialScale = 0.94f)
private val DetailExit: ExitTransition =
    fadeOut(Motion.EffectsFast) + scaleOut(Motion.SpatialDefault, targetScale = 0.94f)

@Composable
private fun DetailNavHost(
    nav: NavHostController,
    go: (String) -> Unit,
    onBaseScope: (AnimatedVisibilityScope) -> Unit,
    onBaseGone: (AnimatedVisibilityScope) -> Unit,
    onFinishedToToday: () -> Unit,
    instantSetup: () -> Boolean,
) {
    NavHost(
        navController = nav,
        startDestination = Routes.TABS,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { DetailEnter },
        // Under another detail: hold still and opaque until the new page covers it.
        exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { DetailExit },
    ) {
        composable(
            Routes.TABS,
            // The tabs layer under the NavHost does the showing; this entry only marks when it may.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.KeepUntilTransitionsFinished },
        ) {
            val scope = this
            DisposableEffect(scope) {
                onBaseScope(scope)
                onDispose { onBaseGone(scope) }
            }
            // Deliberately empty and without pointer input: taps fall through to the tabs.
        }

        // Fades only: a container scale would land the body at a different scale from the
        // header flying in the overlay.
        composable(
            Routes.RECIPE,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("from") {
                    type = NavType.StringType
                    defaultValue = SharedKeys.FROM_HOME
                },
            ),
            enterTransition = { fadeIn(Motion.EffectsDefault) },
            popExitTransition = { fadeOut(Motion.EffectsDefault) },
        ) { entry ->
            val from = entry.arguments?.getString("from") ?: SharedKeys.FROM_HOME
            DetailPage {
                RecipeDetailScreen(
                    recipeId = entry.arguments?.getString("id").orEmpty(),
                    sharedKeyPrefix = from,
                    onBack = { nav.popBackStack() },
                    onBrew = { nav.navigate(Routes.brew(it)) },
                    onEdit = { id ->
                        nav.navigate(Routes.recipeEdit(id, EditorMode.Edit, from)) { launchSingleTop = true }
                    },
                    onDuplicate = { id ->
                        nav.navigate(Routes.recipeEdit(id, EditorMode.Duplicate, from)) { launchSingleTop = true }
                    },
                )
            }
        }

        // The recipe builder: a full page with the app's own push and predictive back.
        composable(
            Routes.RECIPE_EDIT,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("mode") {
                    type = NavType.StringType
                    defaultValue = "new"
                },
                navArgument("from") {
                    type = NavType.StringType
                    defaultValue = SharedKeys.FROM_RECIPES
                },
            ),
        ) { entry ->
            val recipeId = entry.arguments?.getString("id")?.takeIf { it.isNotBlank() }
            val mode = Routes.editorMode(entry.arguments?.getString("mode"))
            val from = entry.arguments?.getString("from") ?: SharedKeys.FROM_RECIPES
            DetailPage {
                RecipeEditorScreen(
                    recipeId = recipeId,
                    mode = mode,
                    onDone = { savedId ->
                        when {
                            // Deleted: the page under the builder has nothing left to show.
                            savedId == null -> {
                                if (nav.previousBackStackEntry?.destination?.route == Routes.RECIPE) {
                                    nav.popBackStack(Routes.RECIPE, inclusive = true)
                                } else {
                                    nav.popBackStack()
                                }
                            }
                            // Edited (or reset) in place: the page under it observes the recipe.
                            mode == EditorMode.Edit -> nav.popBackStack()
                            // New or copied: land on the recipe that was just saved.
                            else -> nav.navigate(Routes.recipe(savedId, from)) {
                                popUpTo(Routes.RECIPE_EDIT) { inclusive = true }
                            }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
        }

        // Setup. Its own back handler walks the steps; on the first step back leaves, like any page.
        composable(
            Routes.ONBOARDING,
            enterTransition = { if (instantSetup()) EnterTransition.None else DetailEnter },
        ) {
            var aiSheet by rememberSaveable { mutableStateOf(false) }
            DetailPage {
                OnboardingScreen(
                    // Wherever setup was opened from, finishing it lands on Today.
                    onFinished = { go(Routes.HOME) },
                    onOpenAiSettings = { aiSheet = true },
                )
                if (aiSheet) AiSettingsSheet(onDismiss = { aiSheet = false })
            }
        }

        composable(
            Routes.BREW,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            DetailPage {
                BrewScreen(
                    recipeId = entry.arguments?.getString("id").orEmpty(),
                    onExit = { nav.popBackStack() },
                    onFinished = { logId ->
                        // A finished brew lands on Today once Review is done, as it always has.
                        onFinishedToToday()
                        nav.navigate(Routes.review(logId)) {
                            popUpTo(Routes.TABS)
                        }
                    },
                )
            }
        }

        // Review rises like a sheet over the brew, which holds still under it (no 420 ms fade
        // over a live mesh any more), and leaves the same way.
        composable(
            Routes.REVIEW,
            arguments = listOf(navArgument("logId") { type = NavType.LongType }),
            enterTransition = { fadeIn(Motion.EffectsDefault) },
            popExitTransition = { fadeOut(Motion.EffectsDefault) },
        ) { entry ->
            val logId = entry.arguments?.getLong("logId") ?: 0L
            DetailPage(rise = true) {
                ReviewScreen(
                    logId = logId,
                    // After a fresh brew Review sits on the tabs; after Edit it sits on the brew's
                    // own detail, and leaving should land back there.
                    onDone = {
                        if (nav.reviewOpenedFromDetail(logId)) nav.popBackStack()
                        else nav.popBackStack(Routes.TABS, inclusive = false)
                    },
                    onAsk = { go(Routes.ASK) },
                    // The detail observes the log and the running analysis, so popping back to it
                    // shows the same state a fresh navigation would.
                    onOpenDetail = { id ->
                        if (nav.reviewOpenedFromDetail(id)) {
                            nav.popBackStack()
                        } else {
                            nav.navigate(Routes.brewDetail(id)) { popUpTo(Routes.TABS) }
                        }
                    },
                )
            }
        }

        composable(
            Routes.BREW_DETAIL,
            arguments = listOf(navArgument("logId") { type = NavType.LongType }),
        ) { entry ->
            DetailPage {
                BrewDetailScreen(
                    logId = entry.arguments?.getLong("logId") ?: 0L,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate(Routes.review(it)) },
                )
            }
        }
    }
}

/** How far above the bar the canvas starts fading in. */
private val ScrimFade = 36.dp

/**
 * A canvas-coloured fade behind the floating bar: clear at the top, 92 % where the bar starts,
 * solid below it. It has no pointer input, so rows under the fade stay tappable; the brush is
 * built once per size and colour change. The only bar scrim: pages no longer draw their own, so
 * a tab switch never fades two of them over each other.
 */
@Composable
private fun BarScrim(modifier: Modifier = Modifier) {
    val bg = CoffeeTheme.colors.bg
    Spacer(
        modifier.drawWithCache {
            val barTop = (ScrimFade.toPx() / size.height).coerceIn(0f, 1f)
            val brush = Brush.verticalGradient(
                0f to bg.copy(alpha = 0f),
                barTop to bg.copy(alpha = 0.92f),
                1f to bg,
            )
            onDrawBehind { drawRect(brush) }
        },
    )
}

/**
 * Every item is a fixed share of the bar, and the only thing that moves is one indicator pill.
 * The pill moves in Float translation inside its layer (sub-pixel, never a re-layout), and the
 * item colours cross-fade in draw, so a switch recomposes nothing in the bar.
 */
private val ItemHeight = Dimens.BottomBarItem

private val PillSpring = spring(
    dampingRatio = Motion.SpatialDefault.dampingRatio,
    stiffness = Motion.SpatialDefault.stiffness,
    // In item widths: a 70 dp item ends within about a tenth of a pixel.
    visibilityThreshold = 0.0005f,
)

@Composable
private fun BottomBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    val colors = CoffeeTheme.colors
    val pill = animateFloatAsState(selected.ordinal.toFloat(), PillSpring, label = "indicator")

    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 8.dp),
        color = colors.bar,
        contentColor = colors.inkHigh,
        shape = RoundedCornerShape(26.dp),
        // Light mode needs a 1px edge to lift the bar off the paper; dark lifts by shade alone.
        border = if (!colors.isDark) BorderStroke(Dp.Hairline, colors.barBorder) else null,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.padding(4.dp)) {
            // Neutral ink, never crema: the tab you are on is not a status and not the live brew.
            Box(
                Modifier
                    .fillMaxWidth(1f / TABS.size)
                    .height(ItemHeight)
                    .graphicsLayer { translationX = pill.value * size.width }
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.inkHigh.copy(alpha = if (colors.isDark) 0.10f else 0.06f)),
            )

            Row(Modifier.height(ItemHeight)) {
                TABS.forEach { tab ->
                    BarItem(
                        tab = tab,
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun BarItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    // 0..1 selection, read only in draw and layer lambdas.
    val sel = animateFloatAsState(if (selected) 1f else 0f, Motion.EffectsDefault, label = "tabSel")
    // graphicsLayer, not Modifier.scale on a sized icon: this never touches layout.
    val iconScale = animateFloatAsState(if (selected) 1.06f else 1f, Motion.bounce(), label = "tabScale")
    val low = colors.inkLow
    val high = colors.inkHigh

    Box(
        modifier
            .testTag(tab.tag)
            .semantics {
                contentDescription = tab.label
                this.selected = selected
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Two pre-tinted icons cross-faded in their layers instead of a tint animated in
            // composition.
            Box(
                Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = iconScale.value
                        scaleY = iconScale.value
                    },
            ) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    tint = low,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = 1f - sel.value },
                )
                Icon(
                    tab.icon,
                    contentDescription = null,
                    tint = high,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = sel.value },
                )
            }
            Spacer(Modifier.height(2.dp))
            BasicText(
                tab.label,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
                // A ColorProducer is read in draw, so the colour fade never recomposes the label.
                color = { lerp(low, high, sel.value) },
                maxLines = 1,
            )
        }
    }
}
