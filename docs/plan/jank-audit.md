# Coffee (Android): jank audit of section switches and detail transitions

Read-only audit, 2026-11-17. No source was edited and no Gradle or emulator run happened, so
every number below is either arithmetic from the code or a sourced figure; nothing here was
measured on the owner's phone. Section 5 says how to measure it.

Files read in full or in the relevant parts: `ui/nav/CoffeeNav.kt`, `ui/nav/SharedTransitions.kt`,
`ui/theme/Motion.kt`, `ui/theme/{Theme,Type,Dimens}.kt`, every screen under `ui/screens/**`, every
component under `ui/components/**`, `ui/ai/{TrendAnalysisSection,TrendAnalysisViewModel,AnalysisCard,AiSettingsViewModel}.kt`,
`MainActivity.kt`, `CoffeeApp.kt`, `data/repo/CoffeeRepository.kt`, `data/db/CoffeeDatabase.kt`,
`core/Format.kt`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `gradle/libs.versions.toml`,
`settings.gradle.kts`, `AndroidManifest.xml`, `src/debug` and `src/release` `DebugTools.kt`.
All paths below are relative to `app\src\main\java\com\laksh\coffee\`
unless they start with `app/` or `gradle/`.

---

## TL;DR

"Jittery" is two different problems, and the app has both.

1. **Jank (dropped frames).** The main thread blows the 8.33 ms budget of a 120 Hz frame when a
   section opens: the incoming tab is composed from scratch on the first frame, its ViewModel
   and Room queries start then, its data lands 1–3 frames into the motion and re-lays out the
   list, and Insights composes its whole page (charts, AI card, export rows) eagerly. The
   installed **debug** APK multiplies all of that: no R8, no AOT from any baseline profile
   (debuggable apps ignore them), and Compose itself is a library that runs interpreted/JIT.
2. **Judder (uneven motion even when frames arrive).** Two half-transparent full pages slide
   in the same direction at different distances and speeds; the slides animate an `IntOffset`, so
   the last ~120 ms of each spring moves in whole-pixel steps and ends with a ~1 px overshoot
   twitch; the recipe header flies with `RemeasureToBounds`, which re-measures and re-flows its
   text (and re-subcomposes `RouteRow`'s `BoxWithConstraints`) every frame; content pops in
   mid-slide; chart entrances replay every time you come back.

Fix order (biggest win first):

1. Test and ship a **release build**: R8 on, a real signing config, `androidx.profileinstaller`,
   a generated **Baseline Profile** (`androidx.baselineprofile` plugin + `:baselineprofile`
   module whose generator walks every tab and every detail screen), and delete the
   `-keepclassmembers class com.laksh.coffee.** { *; }` rule that switches R8 off for app code.
2. **Stop composing on tab switch.** Keep the five tab roots alive in a `SaveableStateHolder`
   host and switch them with `graphicsLayer` only; NavHost only for the four detail routes.
3. **Re-choreograph** tabs as a Material fade-through with a small float translation, on
   ζ ≥ 0.9 springs (M3 Standard motion tokens), so only one page is translucent at a time.
4. **Have the data before the switch**: shared hot flows in the repository, derived UI models
   computed off the main thread, detail ViewModels seeded synchronously from the cache.
5. Shared header: `scaleToBounds()` / `skipToLookaheadSize()`, and replace `RouteRow`'s
   `BoxWithConstraints`.
6. The medium items (Section 2, ranks 7–15).

---

## 0. The budget, in numbers

| Refresh | Frame period | What "smooth" requires |
|---|---|---|
| 60 Hz | 16.67 ms | UI-thread work + RenderThread work each fit in ~16 ms |
| 90 Hz | 11.11 ms | ~11 ms |
| 120 Hz | 8.33 ms | ~8 ms; any frame over 8.33 ms on either thread misses a vsync |

The same 25 ms of main-thread work drops one frame at 60 Hz but two at 120 Hz, which is why
the owner notices it more on a 120 Hz phone.

**When the cost lands decides what you see** (this matters for the fixes):

- Work done in the frame where the back stack changes (composing and measuring the incoming
  destination) runs *before* the transition's clock starts, because Compose animations take
  their start time from the first frame callback after they are launched. It shows up as
  tap-to-motion latency and a frozen frame.
- Work that lands *after* motion has started (Room data arriving, items inserted with
  `animateItem`, loader→content `AnimatedContent` swaps, `animateContentSize`, first-time
  draw-phase text measurement, lazily computed stats, JIT kicking in) makes the time-based
  animation jump ahead, so you see a skip in the middle of the slide. Section 2 is mostly about
  removing this second kind.

**Spring tail arithmetic for the current tab slide** (`Motion.offset()`: k = 380, ζ = 0.85,
`CoffeeNav.kt:225/239`, distance `width/6` ≈ 180 px on a 1080 px-wide screen):

- ω₀ = √380 = 19.5 rad/s, decay σ = ζω₀ = 16.6 s⁻¹, damped ω_d = ω₀√(1−ζ²) = 10.3 rad/s.
- Peak speed bound ≈ 180 × (ω₀²/ω_d) × e^(−σt) ≈ 6 660·e^(−16.6t) px/s. It drops under
  120 px/s (1 px per 120 Hz frame) at t ≈ 0.24 s.
- Overshoot = e^(−σπ/ω_d) = e^(−5.07) ≈ 0.63 % → **≈1.1 px**, peaking at t = π/ω_d ≈ 0.31 s.
- `slideInHorizontally` animates an `IntOffset`, so from ~0.24 s until it settles at
  ~0.35–0.39 s (12–18 frames at 120 Hz) the page moves in 0/1 px steps and finishes with a
  1 px overshoot-and-return. That is a visible twitch on text, every switch.
- The fades use `EmphasizedDecel` (0.05, 0.7, 0.1, 1). Evaluating the curve: the outgoing page
  (180 ms) is still ~38 % opaque at 18 ms while the incoming one (260 ms) is already ~52 %
  opaque, so for the first ~4–8 frames two different pages of text are both clearly visible,
  moving in the same direction at different speeds (180 px vs 135 px, `CoffeeNav.kt:225/230`).
  That reads as ghosting and shear, not as a slide.
- The bottom-bar pill (`CoffeeNav.kt:434-445`) uses the same ζ = 0.85 spring over one tab
  (~70 dp ≈ 190 px) and `roundToPx()`, so it also overshoots ~1 px and steps back.

---

## 1. Why the debug APK is a different, slower app

The owner installed `app-debug.apk` (`README.md:19-26` tells you to). Four things differ from a
release build, and all four hurt Compose in particular.

1. **Baseline Profiles are ignored on debuggable apps.** The profileinstaller release notes say
   profile rules "will have no effect on debuggable applications". That includes the profile
   Compose itself ships inside its AAR. Google's figure for what a profile is worth is "about 30%
   from the first launch by avoiding interpretation and just-in-time (JIT) compilation steps for
   included code paths".
2. **Compose is a library, not platform code.** The Compose baseline-profile page says
   libraries "need to be loaded when the app launches and interpreted JIT when needed. This can
   slow the app on startup and when it uses a library feature for the first time." Views live in
   the boot image and are already compiled; Compose, Navigation, Room and kotlinx in this APK are
   not. Each code path runs in the interpreter until ART notices it is hot and JIT-compiles it,
   and JIT code is thrown away when the process dies, so every cold start pays again. A
   secondary write-up also reports that debuggable processes on Android 14 fell back to the
   slower "switch" interpreter (later fixed). Treat that as context, not a measured fact for this
   phone.
3. **No R8.** Debug builds skip inlining, class merging, devirtualisation and dead-code removal.
   Compose leans on them heavily: compose-runtime ships R8 rules such as
   `-assumenosideeffects class androidx.compose.runtime.ComposerKt { sourceInformation…;
   sourceInformationMarkerStart…; sourceInformationMarkerEnd… }` and
   `-assumevalues … ComposeStackTraceMode { isMinified return true; }`. Those only apply when R8
   runs. AGP's own notes add ~15 % from R8 rewriting profile rules (AGP 8.2) and ~15 % more
   startup from a startup profile / DEX layout.
4. **Debuggable runtime overhead in general.** Android Studio's profiling guide says a debuggable
   app "comes with some performance costs", and Google's Compose performance page says "Build in
   Release Mode with R8 … Debug mode … imposes a performance cost". It also says Baseline Profiles
   matter beyond startup.

**Illustrative only (not measured).** Say Insights costs 25 ms of main-thread work on the frame
it opens. At 120 Hz that is 3 missed vsyncs before motion starts. A 30 % profile win gives
17.5 ms, which is still 2 misses. So the release build alone is necessary but **not enough**.
The structural fixes in Section 3 move that work off the switch frame entirely; that is what
"PixelPlayer smooth" needs. PixelPlayer itself does this: its 0.5.0-beta notes list
"Perf/baseline prof integration" and an extended `BaselineProfileGenerator`.

**The current release config would also hold back performance, or fail outright:**

- `app/build.gradle.kts:24-28` has no `signingConfig`, so `assembleRelease` produces an unsigned
  APK that cannot be installed. That is probably why the debug APK was used.
- `app/proguard-rules.pro:3` `-keepclassmembers class com.laksh.coffee.** { *; }` keeps every
  member of every app class, which stops R8 inlining, removing or reshaping any app code,
  composable lambdas included. kotlinx.serialization and Room ship their own consumer rules.
  Narrow this rule to what actually needs reflection (nothing obvious in this codebase) or
  remove it, then run the release build and fix only what breaks.
- `app/build.gradle.kts:29-31` gives debug `applicationIdSuffix = ".debug"`. **The release
  install is a different app with an empty database.** There is a JSON *export*
  (`InsightsScreen.kt:98-108`) but no import, so the owner will lose their log in the release app
  unless an import is added first. Flag this to the owner before they switch.
- `MainActivity.kt:23-24` calls `DebugTools`, which only exists in `src/debug` and `src/release`.
  The Baseline Profile plugin adds the `nonMinifiedRelease` and `benchmarkRelease` build types,
  which see neither source set, so **the build fails** until those build types get a source-set
  mapping (Section 4).

---

## 2. Ranked causes, with exact locations and fixes

Confidence: **High** = mechanism certain from the code and docs; **Medium** = mechanism certain,
size depends on the device; **Low** = plausible, verify with a trace.

### Rank 1: Debug APK, plus R8 neutered in release (High)
- **Where:** `app/build.gradle.kts:23-32` (debug installed; release unsigned),
  `app/proguard-rules.pro:3`, `gradle/libs.versions.toml` (no `profileinstaller`, no
  `androidx.baselineprofile`, no benchmark deps), `README.md:19-26`.
- **Fix:** see Section 4: release signing, profileinstaller 1.4.1, baselineprofile plugin plus
  `:baselineprofile` module (benchmark 1.5.0), keep rule removed, README switched to
  `assembleRelease`.

### Rank 2: Every tab switch composes the whole destination; first visits also build the VM and start Room (High)
- **Where:** `ui/nav/CoffeeNav.kt:219-376` (all five tabs are NavHost destinations);
  `CoffeeNav.kt:176-180` (`popUpTo(start){saveState=true}; restoreState=true`). Saving state keeps
  `rememberSaveable` values and ViewModels, but **the composition is disposed** and rebuilt on
  return. The VMs are created inside composition: `HomeScreen.kt:91`, `RecipeListScreen.kt:47`,
  `HistoryScreen.kt:76`, `InsightsScreen.kt:87` (+ `TrendAnalysisSection.kt:86`),
  `GearScreen.kt:112` (+ `AiSettingsSection.kt:100` on the App sub-tab).
- **Mechanism:** frame 0 of every switch composes, measures and draws a full tab root (LazyColumn
  items, `RecipeCard`s each with a `BoxWithConstraints` subcomposition, 4 text measurements and an
  offscreen `JourneyLine` layer, `DataGrid` intrinsics, a `BasicTextField` on Recipes), while the
  outgoing page is still composed and animating. The first visit also builds the VM, which
  launches `combine` of 4 Room flows, and the data lands a few frames later (rank 5).
- **Fix:** TabsHost (Section 3.2). The roots stay composed after the first visit and are
  pre-warmed one per frame after the first draw. A switch then only changes `graphicsLayer`
  properties: zero composition and zero measurement on the switch frame.

### Rank 3: Tab choreography produces judder even at full frame rate (High, from the arithmetic in Section 0)
- **Where:** `CoffeeNav.kt:223-250` (slide `IntOffset` in and out at `it/6` vs `it/8`, plus fadeIn
  260 ms / fadeOut 180 ms, `EmphasizedDecel`); `ui/theme/Motion.kt:55-59` (`offset()` ζ 0.85 with
  a 1 px threshold); `Motion.kt:21-24` (`settle()` ζ 0.85 used for the pill, `CoffeeNav.kt:434-438`).
- **Mechanism:** two translucent full pages are visible together for ~4–8 frames, moving at
  different speeds. Integer-pixel stepping covers the last ~120 ms, then a ~1.1 px overshoot. Both
  pages fade with `graphicsLayer` alpha < 1, which with `CompositingStrategy.Auto` "creates a
  compositing layer … to first render the contents fully opaque, then draw this offscreen buffer"
  (Compose docs). That is two full-screen intermediate buffers per frame. Tab pages also have no
  opaque background (`HomeScreen.kt:99`, `RecipeListScreen.kt:53`, …), so both blend over
  `colors.bg`.
- **Fix:** Material **fade-through** for tab switches, which per Material is the pattern for bottom
  navigation: outgoing elements completely fade out before incoming elements fade in, and the
  incoming page scales from 92 % to 100 %. Here: outgoing alpha 1→0 over the first 35 % of
  progress; incoming alpha 0→1 over the remaining 65 % with a **float** `translationX` of 16–24 dp
  in the tab direction (or scale 0.96→1). Drive it from one `Animatable<Float>` on ζ ≥ 0.9 springs
  (Section 3.3), applied in `graphicsLayer` so movement is sub-pixel. Give each page an opaque
  background and, for the pill, use a ζ ≥ 0.9 spring and `graphicsLayer { translationX }` instead
  of `offset { roundToPx }`.

### Rank 4: Insights composes and lays out its entire page eagerly, and computes its stats on the main thread during that composition (High)
- **Where:** `ui/screens/insights/InsightsScreen.kt:113-237` (non-lazy `Column` +
  `verticalScroll`): Passport (`DataGrid` 6 cells with `IntrinsicSize.Min` rows,
  `Numerals.kt:193`), PersonalBests (`InsightsScreen.kt:403` `IntrinsicSize.Min`), ScoreBars,
  GrindCard + `GrindJourneyChart`, RankedList, `TrendAnalysisSection` (its own VM), 3 ActionRows,
  and a long privacy paragraph. Stats come from `InsightsViewModel.kt:37-44` (`by lazy` → first
  read happens in composition on the main thread: `PassportStats.passport/personalBests/
  scoreSeries/grindSeries` over every log, with `ZoneId.systemDefault()` per log). Charts measure
  text inside `drawWithCache` on first draw (`InsightsCharts.kt:117-121`, `194-251`).
  `TrendAnalysisSection.kt:92-134` starts in `LoadingLocal` (skeletons with infinite pulses,
  `Bits.kt:1056-1076`), then flips to Ready via `AnimatedContent` inside a card with
  `animateContentSize` (`ui/ai/AnalysisCard.kt:216`), which is a layout-phase animation inside a
  non-lazy page, landing mid-transition.
- **Fix:** `LazyColumn` with one item per card and `contentType`. Build an `InsightsUi` model in
  the VM with `combine(...).map { build() }.flowOn(Dispatchers.Default)`, so composition only reads
  precomputed values (dates already formatted, chart points already scaled). Gate the charts and
  `TrendAnalysisSection` behind "transition settled" (Section 3.6) with fixed-height placeholders.
  Seed `TrendAnalysisUi` from the repository cache so it never starts in `LoadingLocal` when data
  exists. Drop `animateContentSize` on the AI panel for the loading→ready swap (keep it only for
  user-initiated expansion).

### Rank 5: Content arrives after the transition has started (High)
- **Where:**
  - Empty initial states: `HomeViewModel.kt:140-147` (+ the `loaded` gate `HomeScreen.kt:96,124`),
    `RecipesViewModel.kt:48-51` (initial `RecipeListState()` → 0 cards; then 14 cards are
    "inserted" and fade in with `animateItem`, `RecipeListScreen.kt:153-166`),
    `HistoryViewModel.kt:71-83`, `GearViewModel.kt:50-57`, `InsightsViewModel.kt:52-59`.
  - Five separate Room observers of the same `allLogs` (History, Insights, Gear, TrendAnalysis,
    RecipeDetail; `CoffeeRepository.kt:82`), each with `WhileSubscribed(5_000)`.
  - Loader→content swaps inside the detail transition: `BrewDetailScreen.kt:166-191` with
    `HistoryViewModel.kt:112` (`BrewDetailState()` starts `loading = true`; then three sequential
    suspend lookups, `:118-135`); `ReviewScreen.kt:141-154`. Both show `TumblingBeans` (infinite
    animation), then cross-fade the full page in while the page is still sliding.
  - `CoffeeRepository.kt:35-43` decodes saved-recipe JSON inside `map{}`, which runs in the
    collector's context (`viewModelScope` = Main), not on Room's executor.
- **Fix:** move the hot data to the repository as app-scoped shared flows
  (`stateIn(appScope, SharingStarted.Eagerly, …)` for `recipes`, recent/all logs, active beans and
  grinders, settings). Add `.flowOn(Dispatchers.Default)` to the decode and derivation steps.
  VMs take `.value` as their **initial** state, so the first composition already has content.
  Seed `BrewDetailViewModel`/`ReviewViewModel` synchronously from the cached log (the same trick
  `RecipeDetailViewModel` already uses with `ExpertRecipes.byId`, `RecipesViewModel.kt:88-92`).
  Keep the `AnimatedContent` only for the genuinely missing case.

### Rank 6: Shared recipe header flies with `RemeasureToBounds` over text and a `BoxWithConstraints` (High)
- **Where:** `ui/nav/SharedTransitions.kt:55` (default `ResizeMode.RemeasureToBounds`), used by
  `ui/components/RecipeCard.kt:72-86`, `ui/screens/home/HomeScreen.kt:303-313` (NextBrewPass) and
  `ui/screens/recipes/RecipeDetailScreen.kt:129-141`. The detail header contains `RouteRow`
  (`RecipeDetailScreen.kt:188-199`), whose `BoxWithConstraints` (`ui/components/JourneyLine.kt:369`)
  re-subcomposes on every constraint change and re-measures 4 strings keyed on `maxW`
  (`JourneyLine.kt:371-391`).
- **Mechanism:** Google's shared-elements guide says `RemeasureToBounds` "re-measures and
  re-layouts the child layout … The re-measurement is triggered by the bounds size change, which
  could potentially be every frame", and recommends `scaleToBounds()` for `Text` "as it avoids
  relayout and reflowing of text onto different lines". Here the card title (17 sp, 2 lines,
  232 dp wide) and the detail title (26 sp, 3 lines, full width) re-flow line breaks mid-flight,
  so words jump between lines. On top of that, `RouteRow` recomposes and measures text on every
  frame of the flight.
- **Fix:** `resizeMode = ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopStart)`
  for the header pair, or keep `RemeasureToBounds` on the container but put
  `Modifier.skipToLookaheadSize()` on the inner text column so text lays out once at its final
  size. Replace `RouteRow`'s `BoxWithConstraints` with a plain `Layout` that picks the numeral
  style inside `measure` (or `BasicText(autoSize = TextAutoSize.StepBased(…))`). Use
  `SharedContentConfig.isEnabled` to enable header matching only for list→detail, never during a
  tab switch.

### Rank 7: Animated values read in composition, so whole scopes recompose every frame (High that it happens; impact Low–Medium)
- **Where:** `CoffeeNav.kt:474-478` (`val fg by animateColorAsState`, read at `:498` and `:507`):
  two `BarItem`s recompose ~40 times per switch at 120 Hz. `ui/components/Bits.kt:660-664`
  (SegmentedControl labels), `Bits.kt:519` (SelectableChip fg), `ReviewScreen.kt:686-688`.
  `BrewScreen.kt:1033-1051` (`ctaBg.value`/`ctaFg.value` read inside `RunningPane`'s
  `BoxWithConstraints` content) recomposes the whole running pane every frame for the ~1 s heavy
  spring when the brew completes.
- **Fix:** keep the `State` and read it in a lambda: `BasicText(text, style, color = { fg.value })`
  (`ColorProducer`); draw icon tint through `Modifier.paint(…, colorFilter)` inside
  `drawWithCache`, or cross-fade two pre-tinted icons with `graphicsLayer { alpha }`. For the Brew
  CTA, move it into its own leaf composable that takes `() -> Color`.

### Rank 8: Staggered entrance animations replay on every return (High)
- **Where:** `InsightsCharts.kt:103-111` (ScoreBars regrow), `InsightsCharts.kt:180-188` (grind
  points), `ui/screens/history/TasteStrip.kt:62-66`, `Bits.kt:810-817` (DotRating). These live in
  `remember`, and the NavHost disposes the composition on every switch, so they restart from 0.
- **Fix:** with TabsHost (Section 3.2) the composition survives, so the problem disappears for tabs.
  For details, store an "already played" flag in `rememberSaveable(dataKey)` or the VM. Never
  start entrances while the screen transition is running; start them on "settled" (Section 3.6).

### Rank 9: The whole app runs inside one `SharedTransitionLayout` (a `LookaheadScope`), and every card is a shared participant (Medium)
- **Where:** `CoffeeNav.kt:217-219`; participants in `RecipeCard.kt:72-83` (every card in the Home
  carousel and on Recipes), `HomeScreen.kt:305-310`, `RecipeDetailScreen.kt:133-137`,
  `BrewScreen.kt:868-875`.
- **Mechanism:** `SharedTransitionScope` is built on `LookaheadScope`, so layout passes under it
  include lookahead work. That covers LazyColumn scroll measure, every `animateItem` placement and
  `animateContentSize`. During a tab switch there is never a matching key (different prefixes), but
  each card still carries `rememberSharedContentState` and the `sharedBounds` modifier chain. The
  size of this cost is not documented; measure it before and after (Section 5).
- **Fix:** in the target architecture the tab pages are not inside the NavHost's `AnimatedContent`,
  so tab switches never involve shared bounds. Use `SharedContentConfig.isEnabled` so card
  participants are active only while a list→detail navigation is in flight. Keep the
  `SharedTransitionLayout` at the root, because it must enclose both sides (Section 3.5).

### Rank 10: Main-thread derivations, formatting and locale data inside composition (Medium)
- **Where:**
  - `HomeViewModel.kt:42-124`: `brewsToday`, `brewsThisWeek`, `streakDays`, `headline`,
    `lastFiveAvg`, `prevFiveAvg` are **getters**, re-run on every read in composition
    (`HomeScreen.kt:106-117`, `:147-158`, `:398-402`). Each walks up to 40 logs with
    `Instant.atZone(ZoneId.systemDefault())` (`:129-130`).
  - `HistoryViewModel.kt:36-60` (`byDay` groupBy/sort, `spanWeeks`, counts, read at
    `HistoryScreen.kt:80, 88-101`).
  - `RecipesViewModel.kt:30-39` (`brewers`, `authors.sorted()`, `filtered` with `listOf()` and
    `query.trim()` per recipe, all re-run per read).
  - `TrendAnalysisViewModel.kt:86-93` (`brewDates` formatting for every log inside `mapLatest` on
    Main).
  - `BrewDetailScreen.kt:214-219` (`DataQuality.check` in composition).
  - `core/Format.kt:18,24`: `String.format` for every `clock()`/`oneDp()`, called per row and per
    card.
  - First use of text-field `DateTimeFormatter`s (EEE/MMM) loads locale data on the main thread in
    the first composition of each tab: `HomeScreen.kt:82,108`, `HistoryScreen.kt:71,361`,
    `InsightsScreen.kt:79-82`, `InsightsCharts.kt:57-63`, `Format.kt:29-30` (and
    `GearScreen.kt:109`, which is used only when the bean sheet opens).
- **Fix:** compute immutable UI models in the VM on `Dispatchers.Default` (`combine{…}.flowOn`).
  Composables read fields; they never derive. Pre-format every date and score string in that
  model. Replace `String.format` with direct string building (`"$m:" + (if (s < 10) "0" else "") + s`).
  Warm `java.time` once off the main thread in `CoffeeApp.onCreate`, or simply never format on Main.

### Rank 11: Lazy lists without `contentType`; per-item coroutines and animations; intrinsic measurement (Medium)
- **Where:**
  - No `contentType`: `HomeScreen.kt:105-243` (header, pass, stats, label, rows and carousel all
    share one reuse pool), `RecipeListScreen.kt:58-166`, `HistoryScreen.kt:88-159`,
    `GearScreen.kt:312-346, 438-466`. Keys are present everywhere, which is good.
  - Per-element `collectIsPressedAsState()` (one coroutine each) + `animate*AsState`: `pressSquash`
    (`Bits.kt:124-131`, used by every `RecipeCard`, clickable `SoftCard`, `SelectableChip`,
    `RadioRow`) and `GroupRow` (`ui/components/Grouped.kt:91-98`, every log row). A Recipes page
    starts ~20 coroutines and animation states in its first frame.
  - `DataGrid` rows use `height(IntrinsicSize.Min)` (`Numerals.kt:193`), so every `Text` in a grid
    is measured twice. Grids appear in Home (×2), every bean and grinder card, Passport and
    BrewDetail.
- **Fix:** add `contentType` per item kind. Move press feedback into one `IndicationNodeFactory`
  whose node animates a scale or background with an `Animatable` and applies it in `draw()`
  (`withTransform({ scale(s, s, center) }) { drawContent() }`). That costs no composition, no
  per-item coroutine at rest, and is shared app-wide. Give `DataGrid` a custom `Layout` that sizes
  the vertical rules to the tallest cell after one measure pass.

### Rank 12: Infinite animations and full-screen fills (Medium)
- **Where:**
  - Home header mug, always running while Home is composed, including during the exit
    transition: `HomeScreen.kt:263-282` → `CoffeeArt.kt:66-72` (Steam, 66 `drawLine`s/frame) and
    `CoffeeArt.kt:140-145` (ripple path). The default tab therefore never lets the app idle at
    120 Hz.
  - Loaders: `TumblingBeans` (`CoffeeArt.kt:402-427`) and skeletons (`Bits.kt:1056-1076`).
  - Brew: `MeshGradient.kt:67-73, 129-140` draws a base rect, 3 large radial-gradient circles, a
    `BlendMode.Multiply` vignette and a floor gradient, roughly six full-screen fills per frame at
    the display rate. `BrewRing.kt:69-80` adds two more infinite values. Brew's exit to Review is a
    420 ms full-screen `fadeOut` (`CoffeeNav.kt:304`) over that animating mesh, so the fade layer
    can never be cached.
  - `JourneyLine.kt:138` gives every `RouteRow` (every card) a `CompositingStrategy.Offscreen`
    layer.
- **Fix:** gate every infinite animation on a `LocalTabActive`/lifecycle-RESUMED check (it is
  required once tabs stay composed, Section 3.2), and stop decorative ones while a transition
  runs. Render the mesh into a quarter-resolution layer: a child sized w/4 × h/4 with
  `graphicsLayer { scaleX = 4f; scaleY = 4f; transformOrigin = TransformOrigin(0f, 0f) }`, which is
  1/16 of the fill cost. A blur-soft gradient loses nothing. On API 33+ an AGSL `RuntimeShader` can
  do the blobs and vignette in one pass. For compact `RouteRow`s on cards, draw the node "holes"
  in the known card colour instead of `BlendMode.Clear`, and drop the offscreen layer.

### Rank 13: Stacked translucent scrims and alpha layers (Medium–Low)
- **Where:** root `BarScrim` (`CoffeeNav.kt:170, 390-402`) and `StatusBarScrim`
  (`CoffeeNav.kt:154-158`). Each tab page also draws its own bottom scrim, ~150 dp tall:
  `HomeScreen.kt:247, 467-486`, `RecipeListScreen.kt:170`, `HistoryScreen.kt:162, 338-354`,
  `GearScreen.kt:202, 249-266`. During a switch that is 2 page scrims + the bar scrim + 2
  translucent page layers over the bottom of the screen. Alpha layers on content:
  `GearScreen.kt:344` (archived cards 0.55), `Grouped.kt:293`, `Bits.kt:524`; plus whole-page
  fades in `GearScreen.kt:154-162` (sub-tab `AnimatedContent`) and the detail-load
  `AnimatedContent`s.
- **Fix:** one bar scrim owned by the TabsHost; pages draw an opaque `colors.bg`; Gear sub-tabs use
  the same fade-through primitive; archived cards use pre-blended colours instead of
  `Modifier.alpha`.

### Rank 14: No refresh-rate request (Low–Medium; depends on the device)
- **Where:** `MainActivity.kt` (nothing sets a frame-rate preference).
- **Mechanism:** the system picks the refresh rate. Some OEM skins keep third-party apps at 60 Hz;
  Xiaomi's own support FAQ says "not all third-party applications support high refresh rates".
  If the owner's phone does that, no Compose fix will look like 120 Hz.
- **Fix:** verify with Developer options → "Show refresh rate" during a switch. Then request a
  high rate **only while motion runs** (Section 3.8).

### Rank 15: Startup theme flip and a non-lifecycle collector (Low)
- **Where:** `MainActivity.kt:27-28`: `collectAsState(initial = Settings())`. The first frame
  renders the "system" theme. If the saved mode is dark or light and differs from the system, the
  whole tree recomposes on frame 2 (`LocalCoffeeColors` is static, `ui/theme/Theme.kt:128`).
- **Fix:** `collectAsStateWithLifecycle`. Read the theme mode once before `setContent`
  (`runBlocking` on a tiny DataStore read is acceptable at startup, or hold the splash screen with
  `installSplashScreen().setKeepOnScreenCondition`). Pass it as the initial value.

### Smaller notes (fold into the work above)
- Missing `remember` that defeats `drawWithCache`: `InsightsCharts.kt:97` (`barColors`), `:176`
  (`pointColors`) and `JourneyLine.kt:129` (`statusInks`) are rebuilt per recomposition and
  captured by the cache lambda, so text is re-measured in draw. Wrap them in `remember(points, colors)`.
- `RecipeListScreen.kt:89-90` builds new `KeyboardOptions`/`KeyboardActions` per recomposition;
  hoist them.
- Unstable parameters: with strong skipping (on by default in Kotlin 2.2's Compose compiler),
  `BrewLogEntity` (has `List` fields) is compared by identity. Every Room re-emission therefore
  recomposes every visible row even when nothing changed. Add a stability config
  (`composeCompiler { stabilityConfigurationFiles.add(…) }`) declaring the Room entities and domain
  models stable (they are immutable `data class`es). Verify with compiler reports.
- `ReviewScreen.kt:328-331, 465-468, 592-595, 778-781, 797-805`: `expandVertically`/`animateContentSize`
  inside a non-lazy `verticalScroll` form remeasure the whole form column each frame. Acceptable
  for user-initiated toggles; make sure none fire during the screen's enter transition.
- `Status.kt:197-219` live dot and `BrewRing` already read animated values in draw only. Good
  pattern; keep it.

---

## 3. Target architecture for very smooth section switches

### 3.1 Shape

```
Activity
└─ CoffeeTheme
   └─ SharedTransitionLayout                      (root; encloses both sides of list→detail)
      └─ Box(fillMaxSize, background = bg)
         ├─ TabsHost(selected)                    (always composed; 5 roots kept alive after first visit)
         │    ├─ Today   ─┐  SaveableStateProvider("today")   + TabLifecycle(active)
         │    ├─ Recipes  │  …                                (inactive tabs: not measured, not placed,
         │    ├─ Log      │                                    lifecycle capped at CREATED,
         │    ├─ Insights │                                    infinite animations paused)
         │    └─ Gear    ─┘
         ├─ BottomBar + single BarScrim           (owned by TabsHost level, hides when a detail is up)
         └─ DetailNavHost(start = "none")         (only recipe/{id}, brew/{id}, review/{logId}, brewDetail/{logId})
```

Why this shape:

- A tab switch becomes a pure layer-property animation on content that is already composed,
  measured and recorded: zero composition and zero layout on the switch frame. This is the single
  biggest change.
- The NavHost no longer owns the tabs. Pushing a detail does not dispose the tabs (the NavHost
  would dispose its own exiting destination). Popping back shows the exact list, scroll position
  and chart state already on screen, with no recomposition.
- Details keep NavHost features: arguments, back stack, `SavedStateHandle`, and **predictive back**.
  Navigation 2.8+ seeks the pop transitions with the gesture; `enableOnBackInvokedCallback="true"`
  is already set (`AndroidManifest.xml:12`).
- Navigation 3 (stable 1.2.1) is an alternative (back stack as state, `NavDisplay`). It is not
  needed for this; do not migrate as part of the jank fix.

### 3.2 TabsHost (sketch; compile and adjust)

```kotlin
enum class Tab { Today, Recipes, Log, Insights, Gear }

/** True only for the tab the user is on (and the incoming one mid-switch). Infinite animations read it. */
val LocalTabActive = staticCompositionLocalOf { true }

@Composable
fun TabsHost(selected: Tab, modifier: Modifier = Modifier, content: @Composable (Tab) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val alive = rememberSaveable(
        saver = listSaver(save = { it.map(Tab::ordinal) }, restore = { it.map { o -> Tab.entries[o] }.toMutableStateList() }),
    ) { mutableStateListOf(selected) }
    if (selected !in alive) alive += selected

    // Pre-warm: compose the remaining tabs one per frame after the first draw, hidden.
    LaunchedEffect(Unit) {
        withFrameNanos { }                                  // after the first frame is out
        Tab.entries.filter { it !in alive }.forEach { t -> withFrameNanos { }; alive += t }
    }

    var incoming by remember { mutableStateOf(selected) }
    var outgoing by remember { mutableStateOf<Tab?>(null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected == incoming) return@LaunchedEffect
        outgoing = incoming; incoming = selected        // an interrupted switch fades out whatever was coming in
        progress.snapTo(0f)
        progress.animateTo(1f, Motion120.TabSwitch)
        outgoing = null
    }
    val dir = if (outgoing == null) 0f else sign(incoming.ordinal - outgoing!!.ordinal.toFloat())

    Box(modifier.fillMaxSize()) {
        alive.forEach { tab ->
            key(tab) {
                val role = when (tab) { incoming -> 1; outgoing -> -1; else -> 0 }
                TabLifecycle(active = role == 1) {
                    CompositionLocalProvider(LocalTabActive provides (role != 0)) {
                        holder.SaveableStateProvider(tab.name) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .hiddenUnless(role != 0)          // not measured, not placed, not drawn
                                    .background(CoffeeTheme.colors.bg)
                                    .graphicsLayer {
                                        val p = progress.value        // read in the layer phase only
                                        when (role) {
                                            -1 -> alpha = 1f - (p / 0.35f).coerceIn(0f, 1f)
                                            1 -> if (outgoing != null) {
                                                val f = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                                alpha = f
                                                translationX = (1f - f) * dir * 20.dp.toPx()
                                            }
                                        }
                                    },
                            ) { content(tab) }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.hiddenUnless(visible: Boolean) = layout { m, c ->
    if (!visible) layout(c.maxWidth, c.maxHeight) {}   // skip measure and placement entirely
    else m.measure(c).let { p -> layout(p.width, p.height) { p.place(0, 0) } }
}
```

Required companions:

- **Lifecycle cap per tab** (`TabLifecycle`): a small `LifecycleOwner` backed by a
  `LifecycleRegistry` whose state is `min(parent, if (active) RESUMED else CREATED)`, provided
  through `androidx.lifecycle.compose.LocalLifecycleOwner`. Hidden tabs then stop
  `collectAsStateWithLifecycle` collection, and they resume instantly from the StateFlow's cached
  value. Guard the dispose path: only move to DESTROYED from at least CREATED.
- **Infinite animations** (`Steam`, `CoffeeMug`, `Dripper`, `TumblingBeans`, `SkeletonBar`, live
  `StatusDot`, `BrewRing`, mesh) must not run in hidden tabs. A composed but unplaced
  `rememberInfiniteTransition` still asks for a frame every vsync. Read `LocalTabActive` (and
  lifecycle RESUMED) and skip the transition when inactive.
- **ViewModels** created inside TabsHost resolve to the Activity's `ViewModelStoreOwner`, which is
  fine because each tab has one VM. One side effect: the Gear → App sub-tab `AiSettingsSection`
  and the Insights → AI settings sheet (`AiSettingsSection.kt:216`) would then share one
  `AiSettingsViewModel` and its drafts. Give the sheet its own key
  (`viewModel(key = "ai-settings-sheet")`) if they should stay separate.
- **Scroll-to-top on re-tap** of the selected tab is now easy and expected: hoist each tab's
  `LazyListState` and `animateScrollToItem(0)`.
- **Memory:** five small screens stay composed, which is fine for this app. Pre-warming costs one
  composition per tab spread over idle frames after start-up. Check with the Macrobenchmark that it
  does not land on startup frames (StartupTimingMetric); if it does, start pre-warming after
  `ReportDrawn`.

### 3.3 Motion tokens tuned for 120 Hz (replace `ui/theme/Motion.kt`)

A spring's curve does not depend on frame rate, but 120 Hz shows *more frames of the tail*, so
three things matter: (a) animate positions as **Float** in `graphicsLayer` (sub-pixel), never
`IntOffset` placement; (b) damping **≥ 0.9** for anything spatial, so overshoot stays below a
pixel; (c) finite visibility thresholds, so animations end and the app can idle.

The numbers below are Material 3's **Standard** motion-scheme tokens, copied from androidx source
(`StandardMotionTokens.kt`). The Expressive set (`ExpressiveMotionTokens.kt`) is for deliberate
bounce: spatial ζ 0.6–0.8.

```kotlin
object Motion120 {
    // Spatial (position, scale, bounds). ζ 0.9: overshoot e^(-πζ/√(1-ζ²)) ≈ 0.15 %.
    val SpatialFast    = spring<Float>(dampingRatio = 0.9f, stiffness = 1400f, visibilityThreshold = 0.5f) // px
    val SpatialDefault = spring<Float>(dampingRatio = 0.9f, stiffness = 700f,  visibilityThreshold = 0.5f)
    val SpatialSlow    = spring<Float>(dampingRatio = 0.9f, stiffness = 300f,  visibilityThreshold = 0.5f)
    // Effects (alpha, colour, progress 0..1). Critically damped: never overshoots.
    val EffectsFast    = spring<Float>(dampingRatio = 1f, stiffness = 3800f, visibilityThreshold = 1f / 255f)
    val EffectsDefault = spring<Float>(dampingRatio = 1f, stiffness = 1600f, visibilityThreshold = 1f / 255f)
    val EffectsSlow    = spring<Float>(dampingRatio = 1f, stiffness = 800f,  visibilityThreshold = 1f / 255f)

    /** Tab fade-through progress 0→1: critically damped, ~250 ms, interruptible with velocity. */
    val TabSwitch = spring<Float>(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = 0.002f)
    val Bounds = spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = Rect.VisibilityThreshold)
}
```

Worked comparison with the current tab slide (Section 0):

| | Current `offset()` k 380 ζ 0.85, 180 px, IntOffset | Proposed `SpatialDefault` k 700 ζ 0.9, 20 dp ≈ 55 px, Float |
|---|---|---|
| Overshoot | ≈ 1.1 px (shows as a 1 px twitch) | ≈ 0.08 px (invisible) |
| Settle to 0.5 px | ≈ 0.39 s | ≈ ln(55/0.5·0.436⁻¹)/23.8 ≈ 0.23 s |
| Tail stepping | 12–18 frames of 0/1 px steps | none (sub-pixel) |

Apply the same rule to the pill (`CoffeeNav.kt:434-445`): `SpatialDefault` on a `Float` in px,
applied with `graphicsLayer { translationX = … }`. Bar icon scale may keep a bounce, since it is
deliberate expression on a tiny element. Keep `tween` easing only for choreographed sequences that
cannot be interrupted.

### 3.4 Details on the NavHost, with predictive back

- Push: the detail page has an opaque background. Use `fadeIn(EffectsFast-ish tween 150 ms) +
  scaleIn(initialScale = 0.94f, SpatialDefault)` (scale is a float layer property, so it is
  sub-pixel smooth), or a shared-axis X using float translation. The TabsHost underneath does
  **not** animate out; it just stays put, and once the push settles it stops being placed.
  That leaves one moving, translucent page instead of two.
- Pop / predictive back: popExit is the mirror (scaleOut to 0.94 + fadeOut). Navigation 2.9.8
  seeks it with the back gesture. The tabs layer must be visible (placed) as soon as a pop starts.
  Drive that from the NavHost's transition state, not from `currentBackStackEntry`, which only
  changes when the pop commits.
- Brew → Review: replace the 420 ms full-screen `fadeOut` over the live mesh (`CoffeeNav.kt:304`)
  with Review sliding up opaque over a Brew that simply stops drawing once Review covers it.

### 3.5 Shared elements under the new shape

- Keep the root `SharedTransitionLayout` (it must enclose both the card in a tab and the header in
  the detail).
- Detail side: unchanged `sharedBounds(…, animatedVisibilityScope = NavHost's scope)`.
- Tab side: cards are no longer inside an `AnimatedVisibilityScope`. Use
  `Modifier.sharedElementWithCallerManagedVisibility(rememberSharedContentState(key), visible =
  openRecipeKey != key)`, where `openRecipeKey` comes from the detail back stack. Alternatively,
  enable matching only while a list→detail navigation is in flight via `SharedContentConfig.isEnabled`.
  Verify the exact API shape against the Compose animation version in BOM 2026.06.01; this part is
  the riskiest to implement and has a cheap fallback (no shared header, container-transform-style
  scale+fade).
- Resize mode: `scaleToBounds()` for text-bearing headers (Rank 6).

### 3.6 Defer heavy sections until the transition settles

```kotlin
/** False during this destination's enter transition, true from the first settled frame on. */
@Composable
fun rememberTransitionSettled(): Boolean {
    val scope = LocalNavAnimatedVisibilityScope.current ?: return true
    val running = scope.transition.isRunning
    var settled by rememberSaveable { mutableStateOf(!running) }
    LaunchedEffect(running) { if (!running) settled = true }
    return settled
}
```

Use it to gate, behind **fixed-height** placeholders so nothing reflows when they arrive:

- BrewDetail: `BrewAnalysisSection` (`BrewDetailScreen.kt:337-343`), `TheBrew`, `TasteStrip`.
- RecipeDetail: everything below the header card (`RecipeDetailScreen.kt:203-313`, especially the
  full `TimelineCard`).
- Review: `FirstReadSection`.
- Insights: the charts and `TrendAnalysisSection`, for its first composition only.

Pair it with `ReportDrawnWhen { settled && dataReady }` so Macrobenchmark and the profile generator
capture the full path.

### 3.7 Data model rules (what keeps frame 0 cheap)

- Repository exposes app-scoped `StateFlow`s (`Eagerly` or `WhileSubscribed(5_000)` on `appScope`)
  for logs, recipes, beans, grinders and settings: one Room observer each, not five.
- Every VM maps them to an **immutable, precomputed UI model** with `.flowOn(Dispatchers.Default)`
  and uses the repository's current value to build its initial state synchronously.
- Composables never call `ZoneId`, `DateTimeFormatter`, `String.format`, `groupBy`, `sorted`,
  `filter` over logs, or JSON. They read strings and numbers.
- Detail VMs are seeded from the cache, so the first frame is the real page, never a loader.

### 3.8 Refresh rate: what to request, and why not more

1. **Measure first.** Developer options → "Show refresh rate", then switch tabs. If it already
   reads 120 during motion, requesting changes nothing; the jank is CPU work.
2. If the OEM holds the app at 60 Hz, request a high rate **only while a transition runs**:
   - API 35+ (and Compose UI 1.10+, which BOM 2026.06.01 includes):
     `Modifier.preferredFrameRate(FrameRateCategory.High)` on the TabsHost/detail container while
     `transitionRunning`. The docs note the request "is valid as long as the Composable is drawn",
     so attach the modifier only during motion.
   - API 30–34: set `window.attributes.preferredRefreshRate` to the highest rate among
     `display.supportedModes` with the current resolution at transition start, and back to `0f`
     ~300 ms after it settles. Do not toggle per frame.
   - Android's guidance: prefer `setFrameRate()`/`preferredRefreshRate` over
     `preferredDisplayModeId`, and use the latter only when changing resolution or when a heavy
     mode switch is acceptable. Never pin max refresh for the whole session (battery, heat → thermal
     throttling → *more* jank).
3. A higher rate halves the budget. Do it after Ranks 1–5, otherwise 120 Hz just drops twice as
   many frames.

### 3.9 What not to do

- Do not "fix" it with longer durations. Long, slow tails at 120 Hz are where the 1 px stepping
  shows.
- Do not put `CompositingStrategy.Offscreen` on whole pages "for speed". Android's own guidance is
  that avoiding compositing layers is typically faster; the fix is not fading two pages at once.
- Do not use `HorizontalPager` for bottom-nav tabs. It invites swipe semantics the design does not
  have, and composes neighbours anyway.

---

## 4. Build changes: release, R8, profileinstaller, Baseline Profile

Versions verified 2026-11-17, all at least two weeks old: **androidx.benchmark 1.5.0** (stable,
2026-09-09; 1.5.0-alpha01 removed the `newDsl=false` requirement for AGP 9), **profileinstaller
1.4.1**, **uiautomator 2.4.0** (2026-07-01; 2.4.1 is only 13 days old), **androidx.test.ext:junit
1.3.0**. The `androidx.baselineprofile` Gradle plugin ships in the benchmark release train; use
`1.5.0` and confirm on Google Maven (`androidx.benchmark:benchmark-baseline-profile-gradle-plugin`).

`gradle/libs.versions.toml` (add):

```toml
[versions]
benchmark = "1.5.0"
profileinstaller = "1.4.1"
androidxTestExtJunit = "1.3.0"
uiautomator = "2.4.0"

[libraries]
androidx-profileinstaller = { group = "androidx.profileinstaller", name = "profileinstaller", version.ref = "profileinstaller" }
androidx-benchmark-macro-junit4 = { group = "androidx.benchmark", name = "benchmark-macro-junit4", version.ref = "benchmark" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }
androidx-test-uiautomator = { group = "androidx.test.uiautomator", name = "uiautomator", version.ref = "uiautomator" }

[plugins]
android-test = { id = "com.android.test", version.ref = "agp" }
androidx-baselineprofile = { id = "androidx.baselineprofile", version.ref = "benchmark" }
```

Root `build.gradle.kts`: add `alias(libs.plugins.android.test) apply false` and
`alias(libs.plugins.androidx.baselineprofile) apply false`. `settings.gradle.kts`:
`include(":baselineprofile")`. As the README already notes for AGP 9, do **not** apply
`org.jetbrains.kotlin.android` to the new module; built-in Kotlin covers it.

`app/build.gradle.kts` (changes only):

```kotlin
plugins {
    // …existing…
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    signingConfigs {
        create("release") {
            // From GitHub Actions secrets or ~/.gradle/gradle.properties. Never committed.
            val ks = providers.environmentVariable("COFFEE_KEYSTORE").orElse(providers.gradleProperty("coffee.keystore"))
            if (ks.isPresent) {
                storeFile = file(ks.get())
                storePassword = providers.environmentVariable("COFFEE_KEYSTORE_PASSWORD").orElse(providers.gradleProperty("coffee.keystorePassword")).get()
                keyAlias = providers.environmentVariable("COFFEE_KEY_ALIAS").orElse(providers.gradleProperty("coffee.keyAlias")).get()
                keyPassword = providers.environmentVariable("COFFEE_KEY_PASSWORD").orElse(providers.gradleProperty("coffee.keyPassword")).get()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        // Created by the baselineprofile plugin; we only customise what it allows.
        create("benchmarkRelease") {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".bench"     // never touches the owner's real install or data
        }
        create("nonMinifiedRelease") {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".bench"
        }
    }
    sourceSets {
        // DebugTools exists only in src/debug and src/release. Benchmark variants get the debug
        // twin so the generator can seed realistic data (--ez seed true). Release stays a no-op.
        getByName("benchmarkRelease").java.srcDir("src/debug/java")
        getByName("nonMinifiedRelease").java.srcDir("src/debug/java")
    }
}

baselineProfile {
    saveInSrc = true                        // commit src/release/generated/baselineProfiles/
    automaticGenerationDuringBuild = false  // CI has no device; generate explicitly, commit the result
    dexLayoutOptimization = true            // startup profile → DEX layout (needs R8)
}

dependencies {
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    // …existing…
}
```

`app/proguard-rules.pro`: delete line 3 (`-keepclassmembers class com.laksh.coffee.** { *; }`).
Build release, run it, and add narrow `-keep` rules only for classes that actually fail
(kotlinx.serialization 1.9 and Room 2.8 ship their own consumer rules).

Also add test tags the journeys can find. On the root:
`Modifier.semantics { testTagsAsResourceId = true }` (may need
`@OptIn(ExperimentalComposeUiApi::class)` depending on the Compose UI version). Then
`testTag("tab-today")` … `testTag("tab-gear")` on `BarItem`, `testTag("recipe-card")` on
`RecipeCard`, `testTag("log-row")` on `LogEntryRow`/`LogRow`, `testTag("recipe-list")` /
`"log-list"` on the LazyColumns.

`baselineprofile/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.laksh.coffee.baselineprofile"
    compileSdk = 36
    defaultConfig {
        minSdk = 28                     // profile capture without root needs a device on API 33+
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

baselineProfile { useConnectedDevices = true }   // or a GMD with systemImageSource = "aosp"

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}

// Passes the tested app's real applicationId (".bench" suffix included) to the tests.
androidComponents {
    onVariants { v ->
        val loader = v.artifacts.getBuiltArtifactsLoader()
        v.instrumentationRunnerArguments.put("targetAppId", v.testedApks.map { loader.load(it)?.applicationId ?: "" })
    }
}
```

`baselineprofile/src/main/java/com/laksh/coffee/baselineprofile/Journeys.kt` (shared by generator
and benchmarks):

```kotlin
val targetAppId: String get() = InstrumentationRegistry.getArguments().getString("targetAppId")!!

fun MacrobenchmarkScope.launchSeeded() {
    pressHome()
    startActivityAndWait(Intent(Intent.ACTION_MAIN).apply {
        setClassName(targetAppId, "com.laksh.coffee.MainActivity")
        putExtra("seed", true)                       // debug DebugTools, compiled into the .bench variants only
    })
    device.wait(Until.hasObject(By.res("tab-today")), 5_000)
}

private fun MacrobenchmarkScope.tap(res: String) {
    device.wait(Until.hasObject(By.res(res)), 5_000)
    device.findObject(By.res(res)).click()
    device.waitForIdle()
}

val TABS = listOf("tab-today", "tab-recipes", "tab-log", "tab-insights", "tab-gear")

fun MacrobenchmarkScope.walkTabs() { (TABS + TABS.reversed()).forEach(::tap) }

fun MacrobenchmarkScope.scrollAndFling(listRes: String) {
    device.findObject(By.res(listRes))?.let { it.fling(Direction.DOWN); it.fling(Direction.UP) }
}

fun MacrobenchmarkScope.recipeDetailRoundTrip() {
    tap("tab-recipes"); scrollAndFling("recipe-list")
    tap("recipe-card"); device.wait(Until.hasObject(By.text("Brew this")), 5_000)
    device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
    device.pressBack(); device.waitForIdle()
}

fun MacrobenchmarkScope.brewSetupRoundTrip() {
    tap("tab-today"); device.findObject(By.text("Start brew"))?.click()
    device.wait(Until.hasObject(By.desc("Abandon brew")), 5_000)   // adjust to the setup pane's own label
    device.pressBack(); device.waitForIdle()
}

fun MacrobenchmarkScope.brewDetailRoundTrip() {
    tap("tab-log"); scrollAndFling("log-list")
    tap("log-row"); device.wait(Until.hasObject(By.text("COFFEE & GEAR")), 5_000)
    device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
    device.pressBack(); device.waitForIdle()
}

fun MacrobenchmarkScope.gearSubTabs() {
    tap("tab-gear")
    listOf("Grinders", "App", "Beans").forEach { device.findObject(By.text(it))?.click(); device.waitForIdle() }
}
```

`BaselineProfileGenerator.kt`:

```kotlin
@RunWith(AndroidJUnit4::class) @LargeTest
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun startup() = rule.collect(packageName = targetAppId, includeInStartupProfile = true) {
        launchSeeded()
    }

    @Test fun sectionsAndDetails() = rule.collect(packageName = targetAppId, includeInStartupProfile = false) {
        launchSeeded()
        walkTabs()
        recipeDetailRoundTrip()
        brewSetupRoundTrip()
        brewDetailRoundTrip()
        gearSubTabs()
        tap("tab-insights"); device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
    }
}
```

Generate on an API 33+ phone or emulator: `./gradlew :app:generateReleaseBaselineProfile`, then
commit `app/src/release/generated/baselineProfiles/baseline-prof.txt` (and `startup-prof.txt`).
The owner's emulator has crashed under low RAM before, so a phone is safer. Check the APK contains
`assets/dexopt/baseline.prof`, below 1.5 MB compiled.

**GitHub-downloaded APKs.** Google notes that non-Play channels "might not support using Baseline
Profiles at installation … until background dexopt runs". With `profileinstaller` in the app, the
profile is written on first launch and ART compiles it at the next idle dexopt. Testers who want it
immediately can run `adb shell cmd package compile -f -m speed-profile com.laksh.coffee`. AGP 8.4+
installs the profile automatically for local installs of non-debuggable builds.

---

## 5. How to measure (and the bar to hit)

### 5.1 Macrobenchmark (repeatable; run before and after every phase)

```kotlin
@RunWith(AndroidJUnit4::class) @LargeTest
class TransitionBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun tabsNoProfile()  = tabs(CompilationMode.None())
    @Test fun tabsProfile()    = tabs(CompilationMode.Partial(BaselineProfileMode.Require))
    @Test fun tabsFullAot()    = tabs(CompilationMode.Full())                 // upper bound
    @Test fun recipeDetail()   = run(CompilationMode.Partial(BaselineProfileMode.Require)) { recipeDetailRoundTrip() }
    @Test fun brewDetail()     = run(CompilationMode.Partial(BaselineProfileMode.Require)) { brewDetailRoundTrip() }

    private fun tabs(mode: CompilationMode) = run(mode) { walkTabs() }

    private fun run(mode: CompilationMode, block: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = targetAppId,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = { launchSeeded() },
        measureBlock = block,
    )
}
```

Read `frameDurationCpuMs` (UI thread + RenderThread CPU per frame) and `frameOverrunMs` (API 31+;
positive = missed deadline) at P50/P90/P95/P99. Optionally add
`TraceSectionMetric("%Compose:recompose%", Mode.Sum)` with composition tracing on, to count
recompositions per journey. Run on a physical device with battery saver off, fixed brightness and
the screen kept awake.

**Targets at 120 Hz** (pass bar for "very smooth"):

| Journey | frameOverrunMs P95 | frameOverrunMs P99 | frameDurationCpuMs P90 |
|---|---|---|---|
| Tab switches (walkTabs) | ≤ 0 | ≤ +2 ms | ≤ 6 ms |
| Recipe detail open/close | ≤ 0 | ≤ +4 ms | ≤ 7 ms |
| Brew detail open/close | ≤ 0 | ≤ +4 ms | ≤ 7 ms |

Record the same table for `CompilationMode.None()` to show the owner what the profile buys.

### 5.2 `adb dumpsys gfxinfo` (quick, no code)

```bash
PKG=com.laksh.coffee            # release; com.laksh.coffee.debug for the current install
adb shell cmd package compile -f -m verify       $PKG   # "fresh install, no profile" (interpreter + JIT)
# or: -m speed-profile (uses the installed baseline profile), -m speed (full AOT, upper bound)
adb shell am force-stop $PKG && adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
adb shell dumpsys gfxinfo $PKG reset
#   …do exactly 20 tab switches by hand, same order every run…
adb shell dumpsys gfxinfo $PKG > gfx_$(date +%s).txt
```

Compare runs on: `Janky frames`, the 50/90/95/99th percentile frame times, `Number Missed Vsync`,
`Number Slow UI thread`, `Number Frame deadline missed`, and the GPU percentiles. For per-frame
detail, run `adb shell dumpsys gfxinfo $PKG framestats` **within one second of a single switch**:
it keeps only the last 120 frames, which at 120 Hz is one second. The header row names the columns
on that Android version. Per frame, compute `FrameCompleted − IntendedVsync` (total),
`SyncStart − HandleInputStart` (UI thread: input + animation + measure/layout/record), and compare
against `FrameDeadline − IntendedVsync` (≈ 8.33 ms at 120 Hz). Expect, in order of size:
debug ≫ release `verify` > release `speed-profile` ≈ `speed`. The remaining gap after
`speed-profile` is what Section 3 removes.

### 5.3 Traces, when a number moves the wrong way

Use a release (profileable) build with `androidx.compose.runtime:runtime-tracing` and Android Studio
→ Profiler → "Capture System Activities", or Perfetto. Look at the switch frame for
`Choreographer#doFrame` → `Recomposer:recompose` (should be ~0 with TabsHost), `measure`/`layout`
(lookahead passes, Rank 9), `draw`. On RenderThread, look at `DrawFrame`, and at `saveLayer` /
offscreen buffers during fades (Rank 3/13). The refresh-rate overlay confirms Rank 14.

---

## 6. Notes for the orchestrator

- Ship order: (1) release signing + keep-rule removal + profileinstaller (one PR, testable in an
  hour); (2) TabsHost + new tab choreography + Motion120; (3) repository hot flows and off-main UI
  models; (4) shared-header resize mode + `RouteRow` layout; (5) baseline profile module, generated
  **after** (2)–(4) so it covers the new code paths; (6) the medium items.
- Owner-visible risk: the release app id has no `.debug` suffix, so it **starts with an empty log**.
  Add a JSON import (Gear → App) before asking the owner to switch, or warn them clearly.
- Benchmark/profile build types need a signing config and a `DebugTools` source set, or the build
  breaks (Section 4).
- None of this changes the K-Ultra click maths, the AI client, or the shared catalog work.

---

## Sources

- Compose performance, "Build in Release Mode with R8" and Baseline Profiles: https://developer.android.com/develop/ui/compose/performance
- Compose and Baseline Profiles (library code "interpreted JIT when needed"): https://developer.android.com/develop/ui/compose/performance/baseline-profiles
- Baseline Profiles overview (≈30 %, non-Play channels, AGP 8.2/8.4 notes): https://developer.android.com/topic/performance/baselineprofiles/overview
- Create Baseline Profiles (plugin, module, tasks): https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- Configure Baseline Profile generation (benchmarkRelease / nonMinifiedRelease, saveInSrc, automaticGenerationDuringBuild): https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles
- ProfileInstaller releases (1.4.1; "no effect on debuggable applications" in 1.0.0-beta01 notes): https://developer.android.com/jetpack/androidx/releases/profileinstaller
- Benchmark releases (1.5.0 stable 2026-09-09; AGP 9 `newDsl` note): https://developer.android.com/jetpack/androidx/releases/benchmark
- UiAutomator releases (2.4.0 / 2.4.1): https://developer.android.com/jetpack/androidx/releases/test-uiautomator
- AndroidX Test releases (ext-junit 1.3.0): https://developer.android.com/jetpack/androidx/releases/test
- Macrobenchmark metrics (FrameTimingMetric, frameOverrunMs API 31+): https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics
- Profileable vs debuggable ("comes with some performance costs"): https://developer.android.com/studio/profile
- Compose runtime R8 rules (`-assumenosideeffects` sourceInformation): https://android.googlesource.com/platform/frameworks/support/+/2435c8283887ff3b5fe818c44d03f7fc079e2c87/compose/runtime/runtime/proguard-rules.pro
- Debuggable runtime on Android 14 falling back to the switch interpreter (secondary, unverified): https://www.besthub.dev/articles/performance-regression-in-android-14-debuggable-runtime-caused-by-interpreter-switch-and-its-fixes-9c410e07659f
- CompositingStrategy (Auto → offscreen buffer when alpha < 1; ModulateAlpha): https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/CompositingStrategy
- RenderNode.setUseCompositingLayer ("typically faster to avoid using compositing layers"): https://developer.android.com/reference/android/graphics/RenderNode
- Shared elements: resize modes, `skipToLookaheadSize`, `SharedContentConfig.isEnabled`: https://developer.android.com/develop/ui/compose/animation/shared-elements/customize
- M3 Standard motion tokens: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/StandardMotionTokens.kt
- M3 Expressive motion tokens: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ExpressiveMotionTokens.kt
- Material motion system, fade through for bottom navigation: https://material.io/design/motion/the-motion-system.html
- `Modifier.preferredFrameRate` (Compose UI 1.9/1.10): https://developer.android.com/reference/kotlin/androidx/compose/ui/preferredFrameRate.modifier
- Frame rate API, setFrameRate vs preferredRefreshRate vs preferredDisplayModeId: https://developer.android.com/media/optimize/performance/frame-rate
- Xiaomi FAQ, third-party apps and 120 Hz (OEM evidence): https://www.mi.com/global/support/faq/details/KA-483281/
- Navigation 3 releases (1.2.1 stable): https://developer.android.com/jetpack/androidx/releases/navigation3
- PixelPlayer 0.5.0-beta release notes (baseline profile work): https://newreleases.io/project/github/PixelPlayerHQ/PixelPlayer/release/0.5.0-beta
