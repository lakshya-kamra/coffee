# Coffee v4: adaptive layouts and "usable by everyone"

Lead spec, 2026-11-19. One document for every v4 package. Sections are referenced as §N.M.

Inputs: the three persona audits (beginner/international, wide-screen, accessibility), the espresso
depth captures in `scratchpad/v4/audit-depth/`, `scratchpad/v4/adaptive-research.md` (Android
library facts, reference apps), `docs/plan/web-spec.md`, `docs/plan/android-spec.md`,
`docs/plan/jank-audit.md`, and the current code.

The owner's ask, restated as the bar this spec has to clear:
1. **Great and usable by everyone**: someone with pre-ground coffee, a drip machine and no scale
   must be able to make a good first cup without reading anything twice. Someone with an espresso
   machine, a refractometer or a South Indian filter must also feel at home. Everything must be
   usable with a keyboard, a screen reader, 200% text and Windows High Contrast.
2. **Much, much better desktop/tablet landscape**: at 1024-1920 wide nothing may look like a phone
   in a box. Navigation moves to a rail or sidebar, lists become list-detail, dashboards become
   grids, the brew timer becomes readable from a metre away, and keyboard and mouse users get
   hover states, shortcuts and a command palette.

Design language stays: Flighty-inspired, one hero number per surface (a pane is a surface), labels
above values, status colour always with a word, crema only for live brewing, surfaces lifted by
shade, dark and light, 120 Hz motion (transform and opacity only, keep-alive tabs).

---

## 0. The big decisions (read this first)

| # | Decision | Why |
|---|---|---|
| D1 | Breakpoints by window width: compact <600, medium 600-839, expanded 840-1199, large 1200-1599, extra-large >=1600. "Short" = height <480. Same on both platforms (dp = CSS px). | Owner's spec; M3 + WindowManager 1.5. |
| D2 | Navigation: compact = existing floating bar. Medium and expanded = an 80-wide **rail** (icon + label). Large and XL = a 248-wide **labelled sidebar**, collapsible to the rail with `[`. | At expanded (1024 iPad landscape) a 248 sidebar + 380 list leaves only ~400 for a detail pane; the rail leaves ~560. |
| D3 | List-detail at expanded+ for Recipes, Log and Gear (Beans, Grinders, Settings). URL (web) / saved state (Android) is the selection. The detail pane is never empty: it shows a sensible default. | M3 canonical list-detail; Things/Linear. |
| D4 | Focus modes cover everything including the rail: brew timer, review, onboarding, recipe editor. Everything else lives beside the navigation. | Brewing is the one moment that deserves the whole screen. |
| D5 | The brew timer has three layouts: portrait (today's, fixed so it never shrinks), landscape-stage (height >=480, width >=600, wider than tall) and short (height <480). Clock and Pause are always visible without scrolling at >=640x360. | Audits: controls unreachable at 844x390 and at 200% zoom; 72 px clock on iPad. |
| D6 | Sheets present by role: confirm, editor, picker, content. Bottom sheets only on compact (and medium for editors/pickers). Centred dialogs, a right side sheet and anchored popovers at expanded+. | Wide audit: 640x178 confirm strip at the bottom of a 1440 screen. |
| D7 | Android uses `androidx.compose.material3.adaptive:adaptive` **1.2.0 (BOM-pinned) for window size classes only**. No `ListDetailPaneScaffold`, no stock `NavigationSuiteScaffold` visuals: panes and rail/sidebar are hand-rolled to keep TabsHost, the DetailNavHost and shared transitions. If the AAR's minCompileSdk turns out >36, fall back to `LocalWindowInfo.current.containerSize` (same API surface, §2.11). | research §2.1. |
| D8 | "Simple mode" is not a separate app. One onboarding question ("How do you make coffee now?") sets four independent settings: `hasScale`, `reviewMode`, `soundCues`, and hints. Each can be changed alone in Gear > Settings. | Personal without forking every screen. |
| D9 | Pre-ground and blade grinders become first-class. Pre-ground is a property of the **coffee** (bean `form`), not a fake grinder. A blade grinder is a grinder whose setting is seconds. | Beginner blocker 1. |
| D10 | No-scale brewing is a rendering mode over the same gram data: grams stay the stored truth; spoons, ml and mugs are derived. | Keeps backups, analysis and recipes untouched. |
| D11 | Every stored-data change is additive inside backup v2 (new optional keys, new fault labels). Room goes v4 -> v5 with ADD COLUMN only. Dexie needs no version bump (no new indexes). | Owner rule: backup v2 stays compatible both ways. |
| D12 | Web gets a command palette (Ctrl/Cmd+K). Android gets the same keyboard shortcuts but no palette this round (deferred, §9). | Web desktop is where keyboard-first users are. |

---

## 1. Window classes and tokens

### 1.1 Classes

| Class | Width | Nav | Gutter | Pane gap | Default content max |
|---|---|---|---|---|---|
| compact | <600 | bottom bar | 16 | n/a | 640 (unchanged) |
| medium | 600-839 | rail 80 | 24 | 24 | 720 |
| expanded | 840-1199 | rail 80 | 24 | 24 | per screen (§3) |
| large | 1200-1599 | sidebar 248 (or rail 80 when collapsed) | 32 | 24 | per screen |
| extra-large | >=1600 | sidebar 248 | 32 | 32 | per screen, centred |
| short (modifier) | height <480 | rail without labels if width >=600, else the bar | 16 | 16 | n/a |

`landscape` = width > height. `wide-landscape` = width >=600 && width > height && height >=480.

Web media queries are written literally (no custom-media plugin): compact `(max-width: 599.98px)`,
medium `(min-width: 600px)`, expanded `(min-width: 840px)`, large `(min-width: 1200px)`, XL
`(min-width: 1600px)`, short `(max-height: 479.98px)`. JS reads the same values from
`src/app/windowClass.ts` (§2.1). Layout **inside** panes and cards uses container queries
(`container: pane / inline-size` on every pane and tab layer), never viewport queries, so a card
in a 380-wide list pane on a 1440 screen looks like the phone card.

Android: `ui/theme/Adaptive.kt` exposes `WindowClass(width: WidthClass, isShort: Boolean,
isLandscape: Boolean)` through a `staticCompositionLocalOf`, computed once at `CoffeeAppRoot`.

### 1.2 Tokens (both platforms, same names)

| Token | compact | medium | expanded | large | XL |
|---|---|---|---|---|---|
| `--nav-w` / `Dimens.navWidth` | 0 | 80 | 80 | 248 (80 collapsed) | 248 |
| `--gutter` | 16 | 24 | 24 | 32 | 32 |
| `--list-bottom-pad` | 112 + safe area | 32 + safe area | 32 | 32 | 32 |
| `--list-pane-w` | n/a | n/a | 360 | 400 | 420 |
| `--primary-max` (primary buttons) | 100% | 420 | 420 | 420 | 420 |
| `--measure` (body text) | none | 72ch | 72ch | 72ch | 72ch |

`Dimens.ListBottomInset` becomes a function of the window class (bar present or not).

---

## 2. The adaptive shell

### 2.1 Window class plumbing
- Web `src/app/windowClass.ts`: five `matchMedia` listeners plus `(max-height: 479.98px)` and
  `(orientation: landscape)`, exposed with `useSyncExternalStore` as `useWindowClass(): { width:
  "compact"|"medium"|"expanded"|"large"|"xl"; short: boolean; landscape: boolean }`. It also sets
  `data-wc="<width>"` and `data-short` on `<html>` for shell CSS only. Only the shell, the router
  glue and the brew screen read it in JS; everything else is CSS.
- Android: §2.11.

### 2.2 Navigation

**Compact**: the current floating bar, unchanged except: labels may wrap to 2 lines at large text
(no `TodayRecipes` collision), and the bar is `inert` whenever a detail layer covers it (A3, §5).

**Rail (medium, expanded, and large when collapsed)**: fixed to the left edge, full height,
`--surface0` on `--bg` (lift by shade, no border, no shadow).
- Top: a 48 round **search button** (icon `Search`, tooltip "Search or jump  Ctrl K"), then a 56
  round **Start brew** button (icon `PlayArrow`, inverse ink, never crema; tooltip "Start brew  B").
  Start brew runs the `brew.next` command (§2.8); if none is registered it opens Recipes.
- Items: Today, Recipes, Log, Insights, Gear. Each 64 tall: icon 24 + `t-label-s` label below.
  Selected item: the same neutral pill as the bar, sliding vertically (transform only, the bar's
  spring). Tooltip on hover/focus: "Today  1" ... "Gear  5".
- Bottom: the **live-brew pill** when a brew is running and the brew screen is not on screen:
  crema dot + clock (`num-inline`), click resumes the brew. This is the only crema outside the
  timer (research §1.2).
- Short height: labels hidden, items 48 tall, search and Start brew 40.

**Sidebar (large, XL)**: 248 wide, `--surface0`.
- Header row: "Coffee" (`t-title-l`) and a collapse button (icon `ViewSidebar`, tooltip
  "Collapse sidebar  [").
- A search field-like button: "Search or jump" with a `kbd` "Ctrl K" (Mac: "⌘K", from
  `navigator.platform`/`userAgentData`). Opens the palette.
- Primary button "Start brew" (full sidebar width minus 32, 48 tall, inverse).
- Items (44 tall rows, icon 20 + label `t-title-m`, digit hint "1".."5" in `ink-low` on
  hover/focus only): Today, Recipes, Log, Insights, Gear. Under Gear, always shown, indented
  second-level rows (36 tall): Beans, Grinders, Settings (`/gear/beans|grinders|app`).
- Live-brew row (crema dot, "Brewing", clock, current step title) above the footer.
- Footer: "Keyboard shortcuts  ?" (quiet link).
- Selected row: `--surface2` background (no pill animation needed; colour transition 120 ms).

Collapse state is per device (web `localStorage` key `coffee.sidebar`, try/catch; Android
DataStore key `sidebar_collapsed`), never in backups. Crossing a breakpoint snaps; it never animates
width.

**Focus modes** (D4): brew, review, onboarding, recipe editor render full-window over the nav; the
nav is `inert`/hidden from accessibility while covered.

### 2.3 Panes (list-detail)

Pane-able routes and their home tab:

| Route | Tab | Default detail when nothing is selected |
|---|---|---|
| `recipe` `/recipes/:id` | recipes | the Next-brew recipe (Today's pick) |
| `brewDetail` `/log/:uid` | log | the newest brew; if none, the Log empty state |
| `compare` `/log/:uid/compare/:other` (new) | log | n/a |
| Gear beans / grinders | gear | the default bean / default grinder; if none, the add-first empty state |
| Gear settings sections | gear | "You" section |

Rules:
1. At >=expanded, a pane-able route **at stack index 1 above its own tab** renders inside that tab's
   detail pane instead of as a full-window layer, **only if that tab currently has a `ListDetail`
   mounted** (so a tab that has not adopted panes yet keeps today's behaviour; no broken
   intermediate states). Anything above it (brew, review, editor) still renders as a focus layer.
2. Selecting in the list: if the tab shows its default (depth 0), `push`; if a pane route is already
   selected, `replace`. So Back/Esc from any selection returns to the default in one step.
3. Narrowing from >=expanded to <expanded with a selection: nothing to do on web (the URL already
   has the detail, DetailHost now renders it as a layer, instantly, no animation). Android pushes
   the detail route (§2.11).
4. Widening with a pushed pane-able detail: it moves into the pane, the list scrolls the selected
   row into view (`block: "nearest"`), no animation.
5. Selected row: `aria-current="true"` + `--surface2`. The list is a roving-tabindex list (§2.6).
6. Each pane is its own scroll container (`overflow-y:auto; overscroll-behavior:contain;
   container: pane / inline-size`), so scroll positions survive selection changes and resizes.
7. Detail screens learn where they are from `usePane(): { inPane: boolean; close(): void }`. In a
   pane they hide their own Back button (or show a quiet close "x" at large+ that calls `close`),
   do not draw the shared-element header flight, and do not pin their primary button to the
   window bottom (they pin it to the pane bottom or put it in the header, §3).
8. Widths: list pane `--list-pane-w`; detail pane fills the rest; detail content is centred and
   capped at 960 (1080 at XL) unless the screen says otherwise.

Web: `src/app/panes.tsx` exports `ListDetail` (`{tab, list, listLabel, defaultDetail}`),
`usePane`, `useSelectInPane(tab)` (implements rule 2) and a `PaneHost` that renders the route
through the existing `DetailScreen` registry. `DetailHost` consults `paneHosts` (a Set of tabs with
a mounted `ListDetail`) plus `useWindowClass()` to decide rule 1, and `tabs[data-covered]` is only
set when a real layer covers the tabs.

### 2.4 Presentation of sheets and dialogs

`CoffeeSheet`/`ConfirmSheet` (web `design/components/Sheet.tsx`, Android `ui/components/Bits.kt`)
take a `role`:

| role | compact | medium | expanded+ | short (>=600 wide) |
|---|---|---|---|---|
| `confirm` (delete, leave brew, discard) | bottom sheet | centred dialog 400 | centred dialog 420 | centred dialog |
| `content` (import preview, shortcut sheet, glossary entry, AI report) | bottom sheet | bottom sheet max 640 | centred dialog 640 x max 80vh | dialog max 92vh |
| `editor` (bean, grinder, AI provider) | full-height sheet | sheet max 640 | right **side sheet** 560 wide, full height | side sheet |
| `picker` (catalog, brewer filter, grind) | sheet | sheet max 640 | **popover anchored** to the field, 480 x max 560, scroll inside; centred dialog 520 if no anchor | dialog |

- Dialogs and side sheets have no drag handle. Side sheet: sticky header with title, "Cancel",
  "Save" (Ctrl/Cmd+S saves, Esc = Cancel). Its form may go two columns inside (container query
  >=520).
- **Confirm initial focus is always the safe action** ("Keep it going", "Cancel", "Keep editing"),
  never the destructive one. Enter activates the focused button; Esc cancels.
- Scrim click on an editor with unsaved changes asks `confirm`: title "Discard changes?", body
  "You've changed this coffee and haven't saved it.", actions "Keep editing" (safe, focused) /
  "Discard".
- Stacked editor + picker at expanded+: the picker becomes a popover over the side sheet, so the
  stacking is visible (fixes the invisible 640-over-640 stack).
- Motion: dialog opacity 0->1 + `scale(0.96)->scale(1)` (effectsFast / spatialFast); side sheet
  `translateX(24px)->0` + opacity; popover opacity + `translateY(-4px)->0`. Reduced motion:
  opacity only.
- Android: confirm = `Dialog` with our card styling; editor at >=expanded = a full-height `Dialog`
  (`usePlatformDefaultWidth = false`) aligned end, 560 dp, animated by graphicsLayer
  translationX + alpha; picker = centred `Dialog` 480 x 560 (no anchoring on Android this round).

### 2.5 Grids and widths (shared vocabulary for the screen packages)
- Card grids: `grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr)); gap: 16px`
  (Compose: `LazyVerticalGrid(GridCells.Adaptive(320.dp))`).
- Dashboards (Today, Insights): 12-column grid, gap 16 (24 at large+), max 1200 (large) / 1360 (XL),
  centred. Tiles never stretch past their content height; rows align to the tallest tile.
- Forms: max 720, two columns at container >=640 (label above value always).
- Primary buttons: max `--primary-max` (420) at >=medium, right-aligned in pane headers or at the
  end of the content, never full window width.
- No horizontal scrolling rows on pointer devices: chip rows **wrap** at >=medium (and on any
  `(pointer: fine)` device). Carousels become grids at >=medium.

### 2.6 Hover, focus, pointer, context menus
- `@media (hover: hover) and (pointer: fine)`: every card and row that opens something gets
  `cursor: pointer` and a one-shade lift (`--surface1 -> --surface2`, or `--bg -> --surface1` for
  bare rows), 120 ms background-colour transition (paint only, a single element). Titles go
  `--ink-high`. Links get an underline on hover.
- Focus ring on **every** interactive element including cards and rows: 2 px `--ink-high` outline,
  2 px offset, radius matching the element. Never removed.
- Tooltips for every icon-only button after 500 ms hover or on keyboard focus: label + shortcut
  ("Pause  Space"). Web: shell `Tooltip` primitive (no `title` attribute duplicates when the
  tooltip exists). Android: `TooltipBox` + `PlainTooltip` on `IconWell` at >=expanded.
- Lists use roving tabindex: Tab enters the list once, Arrow Up/Down and J/K move, Home/End jump,
  Enter opens; at >=expanded moving the selection updates the pane after 120 ms of rest
  (`replace`, rule 2).
- Context menu (web only this round): right-click or the "..." button that appears on row hover
  opens a menu at the pointer with the row's actions (§3 per screen). Shell primitive
  `ContextMenu` (a popover with `role="menu"`, arrow keys, Esc).
- Skip link "Skip to content" as the first Tab stop; it focuses the active pane's `h1`.

### 2.7 Keyboard shortcuts

Never while typing in a field (except Ctrl/Cmd combos and Esc); suppressed while a dialog is open
except Esc and the dialog's own keys.

| Keys | Action | Where |
|---|---|---|
| Ctrl/Cmd+K | Command palette | everywhere (web) |
| `/` | Focus the current list's search (Recipes, Log, Gear); else open the palette | web, Android (Ctrl+F also) |
| 1-5 | Switch tab (existing) | depth 0 |
| G then T/R/L/I/S | Today/Recipes/Log/Insights/Gear Settings (1 s chord window) | web |
| `[` or Ctrl/Cmd+\ | Collapse/expand sidebar | large+ |
| `?` | Keyboard shortcuts dialog (`role: content`) | everywhere |
| J/K, Up/Down, Home/End, Enter | Move in list, open | lists |
| B | Brew the selected recipe / Today's next brew | Recipes, Today, Log ("Brew again") |
| N | New: recipe (Recipes), coffee (Gear Beans), grinder (Gear Grinders) | |
| E / D / Delete | Edit / duplicate / delete the selection (delete asks, then undo toast) | Recipes, Log, Gear |
| Esc | Close top popover/dialog -> clear search -> leave the detail (pane: back to default) | |
| Ctrl/Cmd+S, Ctrl/Cmd+Enter | Save | editors, Review |
| Brew: Space | Play/pause | brew running |
| Brew: Left / Right | -5 s / +5 s | |
| Brew: N or Down | Next step | |
| Brew: X | Bed exposed (during drawdown only) | |
| Brew: F | Finish (asks first when early, "Finish now?") | |
| Brew: M | Chime on/off | |
| Brew: Esc | "Leave this brew?" confirm (safe action focused) | |
| Review: 1-9, 0 | Overall score 1-10 (when no field is focused) | |
| Photo viewer: Left/Right, Esc | Previous/next, close | |

Web: `src/app/shortcuts.ts` exports `useShortcuts(scope, bindings, enabled)`; bindings carry
`{keys, label, group}` so the `?` dialog lists exactly what is live. Android: one
`onPreviewKeyEvent` at the root for global keys plus per-screen handlers; override
`Activity.onProvideKeyboardShortcuts` so Meta+/ lists them.

### 2.8 Command palette (web)
- Opens with Ctrl/Cmd+K, the rail/sidebar search button, or a search icon in the compact headers of
  Today and Recipes (touch users get it too). `role: content` dialog 640 wide, top at 18vh;
  compact = full-height sheet with the field on top.
- Field placeholder: "Search recipes, coffees, brews, or type a command".
- Groups in rank order: **Actions**, **Recipes**, **Coffees**, **Grinders**, **Brews**, **Go to**.
  Empty query shows "Recent" (last 5 run, per device in localStorage) then Actions.
- Built-in actions (shell): Go to each tab and Gear section; "Keyboard shortcuts"; "Switch to light
  theme"/"dark"; "Use °F"/"Use °C"; "Use ounces"/"grams"; "Collapse sidebar". Screen packages
  register more via `registerCommands(source)` (e.g. `brew.next` "Brew <recipe>" from Today, "New
  recipe", "Add a coffee", "Add a grinder", "Rate your last brew", "Export a backup", "Import a
  backup", "Set up AI analysis").
- Matching reuses `domain/search` (diacritic-insensitive, prefix + token). Row: icon, title, dim
  subtitle ("James Hoffmann · V60 · 30 g -> 500 g"), shortcut on the right. Up/Down move, Enter
  runs, Ctrl/Cmd+Enter runs the secondary action (recipes: brew instead of open), Esc closes.
- All local. No network.

### 2.9 Motion rules for adaptive (adds to web-spec §7 / jank-audit)
1. Pane content change: the outgoing detail is removed at once; the incoming fades 0->1 with
   `translateX(8px)->0`, effectsFast (about 160 ms). Never two heavy detail trees at once (no exit
   overlap). Reduced motion: opacity only, 100 ms.
2. Crossing a breakpoint never animates (no width, no position tweens). Resizing must never trigger
   a JS layout loop: the only resize work is the `matchMedia` callbacks.
3. Nav indicator: transform only (translateY in the rail, translateX in the bar). Sidebar
   selection: background colour on one row.
4. Hover lifts: background colour on one element, 120 ms, no transform (no layout, no stacking
   context churn). Optional 1 px `translateY(-1px)` only on recipe grid cards.
5. Dialog/side sheet/popover per §2.4. The palette list re-renders at most once per keystroke and is
   virtualised past 50 rows.
6. Keep-alive tabs stay: pane mode lives inside the tab layer, so tab switches still re-render
   nothing.
7. Android: pane changes use `AnimatedContent` with `fadeIn(tween 160) + slideInHorizontally { 8 dp }`
   and `fadeOut(tween 90)`; predictive back between panes is `graphicsLayer` scale/alpha from the
   back progress (§2.11). Window-class changes swap layouts without animation.

### 2.10 State continuity (Tier 3 config changes; Android 16 ignores orientation locks on sw>=600)
Crossing any breakpoint (rotation, split screen, window resize, fold/unfold) must keep: the running
brew, the selected recipe/brew/bean, scroll position per pane, typed text in editors and Review, an
open dialog, and the onboarding step. Opening the brew timer from a pane keeps that pane's
selection, so leaving the timer returns to the same place. Web: selection is the URL; Android:
`rememberSaveable`/`SavedStateHandle`.

### 2.11 Android implementation notes
- Add to `gradle/libs.versions.toml` + `app/build.gradle.kts` (versionless, BOM 2026.06.01 pins
  1.2.0): `androidx.compose.material3.adaptive:adaptive`. Then run `./gradlew
  :app:checkDebugAarMetadata`. If it fails on minCompileSdk, remove it and compute the class from
  `LocalWindowInfo.current.containerSize` with `LocalDensity` (same `WindowClass` API). Never let
  anything pull `material3 1.5.0-alpha*` (minCompileSdk 37).
- `CoffeeAppRoot`: content `Row { NavRail/NavSidebar; Box(weight 1f) { TabsHost; ... } }` at
  >=medium; the `DetailNavHost` for focus routes still fills the whole window (above the rail).
- Pane selection per tab: `rememberSaveable` at the root (`recipesPane: String?`, `logPane: Long?`,
  `gearPane`), passed down through a `CompositionLocal`. `go(Routes.recipe(id))` at >=expanded sets
  the selection instead of navigating. On narrowing with a user selection, navigate to the detail
  route and clear the selection. On widening with a pane-able detail on top, `popBackStack()` and
  set the selection.
- `ListDetailLayout(list, detail, listWidth)`: `Row { list(Modifier.width(listWidth)); detail(
  Modifier.weight(1f)) }`, the detail inside `AnimatedContent(targetState = selection)` (§2.9.7).
  Detail screens get `inPane: Boolean` (hide back arrow, no shared-element header).
- Predictive back between panes: at >=expanded, when a tab has a user selection,
  `PredictiveBackHandler` drives the detail pane's `graphicsLayer { scaleX = scaleY = 1 - 0.06f*p;
  alpha = 1 - 0.3f*p }`; commit clears the selection (pane shows its default), cancel springs back.
  With no user selection back falls through to the existing rule (other tab -> Today).
- Keyboard: root `onPreviewKeyEvent` for 1-5, `/`, Ctrl+F, `?`; screen handlers for brew and lists.
  `MainActivity.onProvideKeyboardShortcuts` lists them in groups "Navigation", "Brewing", "Lists".
- Pointer: `Modifier.pointerHoverIcon(PointerIcon.Hand)` on clickable cards/rows; hover tint from
  `interactionSource.collectIsHoveredAsState()` read in `drawBehind` (draw phase only); focus ring
  via `onFocusChanged` + `drawWithContent` 2 dp `inkHigh` outline on custom cards.
- `AndroidManifest`: no orientation lock (keep). Do not add `configChanges`; recreation is the
  tested path.

---

## 3. Per-screen layouts

Notation: C compact, M medium, E expanded, L large, XL extra-large, S short (height <480).
"Same as C" means today's layout with the §5 fixes.

### 3.1 Today
- **C**: as now, plus the first-brew card (§4.6) and empty-state actions (§4.12). Phone landscape
  S: the rail replaces the bar (width >=600), and the Next-brew card puts Start brew on the numbers
  row.
- **M**: column 720. Stats strip full width; Recent and "Your coffee" (bean on deck: days off roast
  with status word + colour) side by side; "Start from" becomes a 2-up grid (no carousel).
- **E**: 12-col dashboard, max 1200. Left span 7: greeting, live-brew banner (if any), Next brew
  pass (the one hero: dose -> water) with Start brew right-aligned max 420. Right span 5: stats tiles
  (Streak, This week, Last 5, 3 small tiles in a row), then Recent (5-8 rows, hover, click opens Log
  with that brew selected, "Brew again" on hover). Below full width: "Start from" 3-up grid.
- **L/XL**: same grid at 1200/1360, adds tiles: "Your coffee" (bean on deck) and "Last grind"
  (grinder + setting last used for the next recipe). "Start from" 4-up.
- Shortcut B = brew the next recipe. Registers command `brew.next`.

### 3.2 Recipes list
- **C**: as now; chips still scroll on touch-only compact. Fix the "My brewers" state (§4.13).
- **M**: card grid (§2.5, 2 columns); chip rows wrap; detail still pushed full screen.
- **E/L/XL**: list-detail. List pane: sticky header with search field ("Search recipes, people,
  brewers"), wrapped filter chips (All, Mine, My brewers, then brewers), a sort control ("Sort:
  Suggested / A-Z / Most brewed / Best rated"), then **compact rows** (~76 px: person dot + name,
  brewer short name, dose -> water, time; selected row surface2). Detail pane per §3.3.
- XL: the list pane may show two columns of rows when the pane is >=600 (container query); no
  three-pane filter rail this round.
- Keyboard: `/` search, J/K, Enter, B brew, E edit (own recipes) or D duplicate, N new.
  Context menu: Brew, Duplicate, Edit (own), Delete (own).

### 3.3 Recipe detail
- **C/M**: as now (pushed), grind tile wraps to 2 lines (never truncates the grind words), the
  glossary and no-scale rendering (§4.3, §4.5).
- **In a pane at E**: single column, max 720, with a header row: brewer art, name, author, and
  "Brew this" (primary, max 360) right-aligned in the header.
- **L/XL (pane >=880 by container query)**: two columns. Left (sticky, 340): the numbers card (dose
  -> water hero, ratio phrase, temp, time, grind with reference), "Make more or less" (with the
  servings presets §4.5), "Your last / best grind", "What you'll need" (§4.6 needs), "Brew this".
  Right: blurb, the route (journey line), steps timeline, Set up (prep, or prepNoScale), Worth
  knowing (tips), the grinder table, sources and fidelity.

### 3.4 Brew setup (before Start)
- **C**: as now plus: grinder picker gets "Pre-ground (as bought)" first and "Blade grinder"
  handling (§4.2), chime prompt (§4.8), no-scale amounts (§4.3).
- **E+ / wide-landscape**: two columns. Left: brewer art, recipe name, dose stepper (hero dose ->
  water), servings presets. Right: grind (dial input or pre-ground/blade), water temp, coffee
  picker, notes, chime prompt. "Start brew" right-aligned at the bottom of the right column, max 420.
- **S**: art + title + dose left; everything else right, Start pinned to the right column's bottom.

### 3.5 Brew running (the timer) - three layouts

Choose by window, recomputed live (state is in the engine, so switching layout loses nothing):
`short` if height <480; else `stage` if width >=600 and width > height; else `portrait`.

**portrait** (phones, tablet portrait, foldables unfolded portrait):
- As now, but the ring is sized from `min(width - 2*gutter, height - fixedRows, 360)` with a floor of
  the phone size (never smaller than today's phone: 240 on phones, up to 360 on medium). Clock uses
  `num-clock` (88) whenever the ring is >=240 (was: whenever window height >870). The step card is
  `min-height: auto` and scrolls internally; nothing is clipped at 200% text.
- The whole running pane may scroll as a last resort, but the transport row is `position: sticky;
  bottom: 0` (web) / outside the scroll (Android), so Pause is always reachable.

**stage** (tablet landscape, laptops, desktops; the "counter at a metre" persona):
- Grid: `minmax(0, 1fr) clamp(380px, 34vw, 480px)`, full height, no page scroll.
- Left stage: the ring at `min(70vh, 45vw, 560px)`; inside it the **clock** at
  `clamp(96px, 16vh, 200px)` (the one hero), "OF 3:30" eyebrow, and **poured / target** at
  `clamp(40px, 7vh, 64px)` ("300 g / 500", or "300 ml / 500" with no scale). The journey line runs
  under the ring at stage width.
- Right column, top to bottom: top row (Leave "x", recipe name, "Step 2 of 6"); the current step card
  (kind chip that opens the glossary, title at `clamp(28px, 4vh, 40px)`, instruction `t-body-l`,
  no-scale hint); then the **full step list** as a vertical timeline (past steps dimmed with "Done",
  current on `--surface2`, upcoming with start times; scrolls inside if long); pinned at the bottom:
  transport (-5 s, Play/Pause 72, +5 s, Next step) and "Finish" (secondary, column width, 48).
  "Bed exposed" replaces Next step during a Drawdown step.
- At 1024x768 the clock is about 123 px and the grams about 54 px: readable from a metre.

**short** (phone landscape, split screen, 200% browser zoom on a laptop):
- Two columns, **no scrolling container**: left `min(45vw, 100vh - 16px)` square with the ring and,
  inside it, the clock at `clamp(48px, 22vh, 96px)` and the grams; right column: 40 tall top row,
  step card (title max 2 lines, instruction max 3 lines then the card scrolls inside), "NEXT · 0:45
  · Up to 300 g" on one line, transport row 56 tall (-5 s, Play 56, +5 s, Next) and Finish as a 44
  pill at the row's end. The journey line becomes a 4 px progress strip along the top edge.
- Must fit with clock, Pause and the current step title visible at 640x360, 844x390, 915x412 and
  720x450 (1440x900 at 200% zoom).

Also on every layout:
- Keyboard map §2.7; icon buttons have tooltips with the key.
- Screen-reader announcements (§5 A4).
- Step-change cue: a full-window crema flash (overlay opacity 0 -> 0.16 -> 0 over 600 ms, compositor)
  plus a banner sliding in for 3 s: "NEXT STEP · Pour to 300 g". Reduced motion: no flash, banner
  fades. This is visible across a kitchen and works on iOS where vibration does not.
- Espresso uses the shot layout (§4.9).

### 3.6 Review (after a brew)
- **C/M**: one column, Quick or Detailed (§4.4). Save is sticky at the bottom.
- **E+**: two columns inside the focus layer, max 1200. Left (read-only, "what happened"): the
  summary strip (dose, water/yield, grind, time vs plan), the journey line actual vs planned,
  coffee, photos strip (drop zone, §4.14). Right: Overall, the quick toggles, then (Detailed or
  "More detail") taste sliders, descriptors, faults, flow/drawdown/bed when the method has them,
  Advanced (TDS, beverage weight), notes, next time. Sticky action bar at the bottom of the right
  column: "Save brew" (primary) and "Save and analyse" (when AI is set up). Ctrl/Cmd+Enter saves;
  Esc asks to discard; 1-9/0 set the score.
- **S**: the same two columns, smaller; photos collapse to a count chip.

### 3.7 Log
- **C**: as now plus search and filters (§4.11) in a header that collapses on scroll.
- **M**: wider rows (time, recipe, coffee, grind, ratio, score as a status word + colour).
- **E/L**: list-detail; list pane = search + filter chips + day-grouped rows; detail = brew detail
  (§3.8). Keyboard J/K, Enter, B (brew again), Delete (confirm + undo).
- **XL**: a "List / Table" toggle (per device). Table: Date, Recipe, Coffee, Grinder + setting,
  Dose, Ratio, Time vs plan, Score, Faults; sortable headers (`aria-sort`), arrow-key row selection.
  In table view the table takes the list pane's place at full width minus a 480 detail pane on the
  right; clicking or Enter on a row selects it into that pane (same URL rules, §2.3).
- Context menu on rows: Brew again, Compare with..., Favourite, Delete.

### 3.8 Brew detail (+ compare)
- **C**: as now, plus "Brew again" (§4.10) and "Compare with..." in the header overflow.
- **Pane at E**: one column, max 760.
- **L/XL (pane >=880)**: two columns: main (boarding pass, journey actual vs plan, taste strip,
  notes, first read, log check) and a 340 supporting column (photos at >=160 px thumbnails, AI
  analysis card, "Brew again").
- **Compare** (`/log/:uid/compare/:other`): a two-column table of the two brews: Date, Recipe,
  Coffee (days off roast), Grind, Dose, Water/Yield, Ratio, Temp, Time vs plan, Overall, Faults,
  Notes. Each changed row has a word in the right column ("finer by 5 clicks", "+0:20", "2 °C
  hotter", "+1"). Picker for the other brew: same recipe first, then same coffee, then the rest
  (`role: picker`). Works on C as a pushed screen (columns stay two, narrow).

### 3.9 Insights
- **C**: as now with chart x-axis date ticks and readable axis labels (`t-label-m`, not larger than
  other labels).
- **M**: 2-col grid of cards with a KPI row on top.
- **E+**: dashboard (§2.5), max 1280 (L) / 1440 (XL). Row 1: Passport (span 8) + Personal bests
  (span 4). Row 2: Score chart (span 6) + Grind drift (span 6). Row 3: Most brewed, Coffees,
  Patterns (span 4 each). At L/XL the AI trend analysis sits in a supporting column (span 4) beside
  rows 2-3 (span 8). Share/export and privacy copy in a footer row.
- Charts: pointer hover shows a crosshair + tooltip (date, recipe, score/setting); click opens that
  brew in Log; keyboard: focus the chart, Left/Right move the point, Enter opens. Tooltip copy:
  "Thu 12 Nov · Hoffmann V60 · 8/10".

### 3.10 Gear
- **Beans / Grinders, C/M**: as now with action-carrying empty states and the reordered bean editor
  (§4.7); M uses a 2-col card grid.
- **E+**: list-detail. List: the cards as compact rows (bean: name, roaster, days off roast with
  status word: "Resting", "Peak", "Fading"; grinder: name, setting style). Detail pane = **the
  editor inline** (no sheet) with a header "Save" / "Revert" (Ctrl/Cmd+S), plus, for a bean, "Brewed
  with this coffee" (count, average score, best grind, last 5 brews, photos). N adds.
- **Settings (Gear > App), E+**: a sub-list on the left (You, Units and measuring, Brewing, Coffee and
  water, AI analysis, Your data, About) and the section on the right, max 720; scroll-spy is not
  needed because each section is its own pane. **C/M**: one scroll with a sticky jump row of the same
  section names (wraps on M).

### 3.11 Onboarding (`/welcome`, focus mode)
- **C**: as now with the new steps (§4.1).
- **M**: a centred card, max 560, progress dots above.
- **E+**: a centred 960 frame split 40/60: left explains why we ask (art + 2 sentences per step),
  right holds the step's form. Catalog lists show 2 columns of rows at >=L. Enter = Next, Esc = Back,
  "Skip for now" always visible.
- **S**: the same split without art.

### 3.12 Recipe editor (focus mode)
- C/M as now. E+: two columns: left name, brewer, dose/water/temp/grind, level and needs (§6.4);
  right the steps editor with a live journey-line preview. Sticky Save top-right (Ctrl/Cmd+S), Esc
  asks to discard when dirty.

### 3.13 Photo viewer
- C/M: full screen as now, plus prev/next buttons and "1 of 2".
- E+: lightbox: image centred (max 90vh), prev/next arrows on hover and always focusable, a 320
  side panel with kind, date, caption field and Delete. Left/Right, Esc. Ctrl+wheel or +/- zoom.

### 3.14 Toasts
- C: above the bar (as now). M+: bottom-centre of the content area (left offset `--nav-w`), 24 above
  the bottom edge, max 480 wide.

---

## 4. Usable by everyone

Each item lists exact copy where wording matters. "Both" = web and Android.

### 4.1 Onboarding: who are you, and do you have a scale (both)
New first step "you" before "measure" (steps become: you, measure, grinder, brewers, coffee, done).
- Eyebrow "STEP 1 OF 6". Title: **"How do you make coffee now?"**
- Three large choice cards (radio group):
  - **"I'm new to this"** - "Keep it simple. Explain the words. No scale needed."
  - **"I follow recipes"** - "Show me good recipes and time each step."
  - **"I tinker"** - "Give me every number: grind, flow, TDS."
- Sets `experience` and pre-sets (each still editable later): new -> `reviewMode "quick"`,
  `soundCues true`, `hasScale` asked next with "No" preselected; recipes -> `reviewMode "quick"`;
  tinker -> `reviewMode "detailed"`.
- Measure step gains, above the units: **"Do you have a kitchen scale?"** options **"Yes"** and
  **"No, I'll use spoons and a jug"**, helper: "You can change this any time in Gear › Settings."
- Units default by region (§4.15).
- Water (in the coffee step) shows 4 plain choices first: **"Tap water"**, **"Filtered tap water"**,
  **"Bottled spring water"**, **"Not sure"**, then "More waters" (disclosure with the full catalog).
  Map the first three to existing water catalog ids where they exist; core adds missing generic ids.
- Brewers step "Most common" (order): Automatic drip machine, French press, Pour-over cone (V60),
  AeroPress, Moka pot, Espresso machine, Drip bag (single-serve), South Indian filter (new catalog
  id, §6.5), Phin, Cold brew jar.
- Done step: replaced by **"Your first brew"** (§4.6) with "Start my first brew" (primary) and "Go to
  Today" (quiet).

### 4.2 Pre-ground and blade grinders (both; data in CORE)
- Onboarding grinder step, above the search, two big options:
  - **"I buy ground coffee"** - "We'll skip grind settings." Sets `buysGround = true`.
  - **"Blade grinder"** - "The kind with a spinning blade. We'll time it in seconds." Creates a
    grinder from the generic catalog entry `blade-grinder` (core adds) with `settingUnit "s"`.
  - Then the existing "Burr grinder (hand or electric)" search.
- Bean editor: **"Whole beans / Ground"** segmented field right under Roaster (`form`). When Ground
  is chosen, a chip row appears: **"Ground for: Filter / Press / Espresso / Moka"** (`groundFor`,
  §6.2).
- Brew setup grinder picker: first row **"Pre-ground (as bought)"**, auto-selected when the chosen
  coffee is Ground or `buysGround` is on and no grinder exists. Then grinders. A blade grinder shows
  a seconds stepper: label "GRIND FOR", value "12 s", helper "Pulse in short bursts and shake the
  grinder between them."
- Brew record: `grindKind` "preground" | "blade" | "dial"; `grindSetting` "As bought" for
  pre-ground, "12 s" for blade (`grindValue` 12).
- Log check: no "Grind: No grind setting recorded." for pre-ground; blade records seconds.
- Advice: never "go finer/coarser" when the grind cannot change (§4.4 table).

### 4.3 No-scale brewing (both; conversions in CORE)
`hasScale = false` turns on spoon-and-jug rendering everywhere a gram amount is shown for brewing
(recipe detail, brew setup, running ring, Today's next brew). Grams remain stored and shown small
in `ink-low` as "(30 g)".
- Coffee: **level tablespoons** at **5 g per level tablespoon of ground coffee** (constant
  `GROUND_COFFEE_G_PER_LEVEL_TBSP = 5`, documented in `shared/schema/units.md` with the SCA
  "2 tablespoons ≈ 10 g" scoop reference; core must cite a source it verifies), rounded to the
  nearest half, min 1: "6 level tablespoons". Whole beans for a blade grinder: same spoons, measured
  before grinding (copy "6 level tablespoons of beans").
- Water: ml (1 g = 1 ml), plus mugs when >=200 ml at 250 ml per mug: "500 ml (2 mugs)".
- Espresso and moka: no spoons. Espresso: "Fill the basket level, then tamp." Moka: "Fill the basket
  level; don't press it down. Water to just below the valve."
- Prep lines that mention a scale or zeroing are replaced by `prepNoScale` when the recipe has it,
  otherwise by the generic: **"Measure {water} of water in a jug first."** and **"Measure {coffee}
  of coffee."**
- Running ring: the counter reads "about 60 ml / 500" for pour steps; immersion steps whose target
  is all the water read **"Pour all {water}"**. Step titles keep grams; below the title a
  `t-body-m` line "About {ml} ml from your jug" appears when the step has a target.
- "Make more or less" gains servings presets: **"1 mug"**, **"2 mugs"**, **"3 mugs"** (250 ml water
  each, dose from the recipe's ratio), next to the existing gram stepper.
- Settings: Gear › Settings › Units and measuring: switch **"I have a kitchen scale"** with helper
  "Off: amounts show in tablespoons and ml."

### 4.4 Review: quick and detailed, method-aware (both)
- `reviewMode` "quick" (default for new installs, §6.1) shows: Overall (10 dots), **"ANYTHING
  OFF?"** with four toggle chips **"Too sour"**, **"Too bitter"**, **"Too weak"**, **"Too strong"**,
  Notes, photos, and a disclosure **"More detail"** ("Taste, flow, the coffee bed") that reveals the
  detailed sections in place. "Detailed" shows everything open, as today.
- Toggles map to faults: Too sour -> "Sour", Too bitter -> "Bitter", Too weak -> "Too weak" (new
  label), Too strong -> "Too strong" (new label). Taste sliders are untouched in quick mode.
- Sections by method (core function `reviewSections(brewer)`):
  - Drawdown (time + In cup + Flow): pour-over cones, flat-bottoms, hybrids/Clever only.
  - The bed (shape, surface, channelling): same set plus drip machine.
  - Neither for French press, AeroPress, phin, cold brew, moka, cezve, siphon, espresso, drip bag.
  - TDS / beverage weight / extraction: always under an **"Advanced"** disclosure ("TDS and
    extraction, if you have a refractometer").
  - "Retained by the bed" only for percolation methods.
- Slider ends get plain words in quick-reveal: Clarity "muddy -> clean", Balance "one thing sticks out
  -> all fits together" (core updates `TASTE_AXES` ranges on both platforms).
- Advice (first read, `quickRead`) by method and whether the grind can change (`adjustable` = a dial
  or blade grinder; not pre-ground):

| Signal | Percolation, adjustable | Percolation, fixed | Immersion, adjustable | Immersion, fixed | Espresso |
|---|---|---|---|---|---|
| Sour / Too weak | "Grind a little finer next time." | "Pour more slowly, and use water just off the boil." | "Steep 30 seconds longer, or grind a little finer." | "Steep 1 minute longer." | "Let more out: aim for about {2.5x dose} g, or grind finer." |
| Bitter | "Grind a little coarser next time." | "Use water a little cooler, about {90 °C}." | "Steep 30 seconds less, or grind a little coarser." | "Steep 30 seconds less." | "Stop the shot sooner, or grind coarser." |
| Too weak (also) | add "Or use a little more coffee: {+1 tbsp / +2 g}." | same | same | same | "Use a little more coffee: +1 g." |
| Too strong | "Use a little less coffee, or add a splash of hot water to the cup." | same | same | same | "Add hot water for an Americano, or let a little more out." |
| Time rules | drawdown advice | drawdown advice | steep-time advice | steep-time advice | shot-time advice |

  Time rules are skipped when data quality already ignored total time (e.g. <50% of target or
  "Finish early" in the first step). Android prints times with `clock()` ("9:58", never "598s").
  `{90 °C}` and amounts render in the user's units.

### 4.5 Words explained where they appear (both; data in CORE)
- `shared/glossary.json` (schema `shared/schema/glossary.md`): `{version, terms: [{id, term,
  short, more?, aliases[], sources[]}]}`. Initial terms and their `short` copy (core may tighten
  wording, must keep it plain and correct, and cite sources it verifies, reusing
  `principles.json`/`recipes.json` sources where they fit; never invent a URL):
  - ratio - "How much water for each gram of coffee. 1:16 means 16 g (about 16 ml) of water for every
    1 g of coffee. More water tastes lighter; less tastes stronger."
  - dose - "How much dry coffee you use."
  - yield - "How much espresso ends up in the cup, by weight. A common double is 18 g of coffee in
    and about 36 g out."
  - bloom - "A small first pour that just wets the coffee. Fresh coffee bubbles as gas escapes;
    waiting 30-45 seconds lets the rest of the water soak in evenly."
  - pour - "Add water up to the amount shown, steadily rather than all at once."
  - swirl - "Gently spin the brewer so the coffee settles flat and no dry clumps are left."
  - stir - "Stir gently with a spoon to wet all the coffee or knock grounds off the sides."
  - steep - "Leave the coffee sitting in the water. Longer steeping pulls out more flavour."
  - press - "Push the plunger down slowly and steadily."
  - drawdown - "The time for the last of the water to drain through the coffee. Much faster than the
    recipe usually means the grind is too coarse; much slower, too fine."
  - bed - "The layer of wet grounds left in the filter. A flat bed usually means the water went
    through evenly."
  - channelling - "Water finding an easy path through the coffee and skipping the rest. It can make a
    cup taste sour and bitter at the same time."
  - tds - "Total dissolved solids: how strong the coffee is, measured with a refractometer. Without
    one, skip it."
  - extraction - "How much of the coffee dissolved into your cup. Too little tastes sour and thin;
    too much tastes bitter and dry."
  - grind - "How fine the coffee is ground. Finer gives up flavour faster; coarser, slower. Sour
    usually means go finer, bitter coarser."
  - clicks - "Hand grinders turn in steps you can feel. We count from fully closed, so the same
    number means the same grind on any grinder of that model."
  - days-off-roast - "Days since the coffee was roasted. Most coffee tastes best about 1 to 4 weeks
    after roasting."
  - immersion - "All the water sits with the coffee the whole time, like a French press. Steep time
    matters most."
  - percolation - "Water flows through the coffee and out, like a pour-over or drip machine. How fast
    it drains matters most."
  - gooseneck - "A kettle with a thin spout for a slow, steady pour. Nice for pour-over, but a normal
    kettle works if you pour gently."
  - preinfusion - "Wetting the espresso puck gently for a few seconds before full pressure, so water
    flows evenly."
  - shot-time - "Seconds from starting the pump to stopping it. Many shots take 25-35 seconds."
  - bypass - "Hot water added after brewing, straight into the cup, to make it lighter."
  - zero-the-scale - "Press the scale's tare button with the brewer on it so it reads 0. Then it
    shows only the water you add."
  - published / adapted / community - the three fidelity meanings (copy from `recipes.md`, plain).
- Grind references (core `grindReference(word)`; shown after the grind word, e.g. "Medium-fine ·
  like table salt"): extra-fine "like flour", fine "like fine salt", medium-fine "like table salt",
  medium "like sand", medium-coarse "like coarse sand", coarse "like sea salt", extra-coarse "like
  cracked pepper".
- Ratio is rendered once per surface as "1 part coffee to 16.7 parts water" under the "RATIO 1:16.7"
  value (detail and setup; cards keep the short form).
- UI: a `Term` primitive (web shell `design/components/Term.tsx`; Android `ui/components/Term.kt`):
  a dotted-underline label or chip that opens a small popover (E+, anchored) / sheet (C/M,
  `role: content`) with the term, `short`, `more`, and sources. Used on: step-kind chips, RATIO,
  GRIND, BLOOM/DRAWDOWN titles, review section headers (Drawdown, The bed, Channelling, TDS),
  "Published numbers", "Bed exposed", "Days off roast". In `experience = new`, the first occurrence
  per screen shows a small "?" affordance; otherwise the dotted underline is enough.

### 4.6 A guided first brew (both)
- Recipes gain `level` and `needs` (§6.4). Core's `firstBrew(recipes, settings, grinders)` picks:
  owned family first (never another family's recipe, §4.13), then fewest unmet needs (scale unmet
  if `!hasScale`; grinder unmet if `buysGround` or no grinder; gooseneck/thermometer counted as
  unmet for `experience = new`), then `level` easy < medium < advanced, then catalog order.
- Today shows a **"YOUR FIRST BREW"** card when there are no brews (it replaces "FIRST BREW"):
  recipe name and author; "You'll need" chips (e.g. "French press", "Kettle", "6 level tablespoons
  of coffee", "500 ml water", "A mug"); a 3-line checklist: "1 Boil the kettle, then let it sit for
  30 seconds." / "2 Measure the coffee and the water." / "3 Press Start as you pour. We'll tell you
  each step." Button **"Start my first brew"**.
- When an owned brewer has no recipe: "We don't have a tested recipe for your {Cezve} yet." with
  "See all recipes" (no borrowed recipe).
- In the first brew (no brews yet), the brew screen shows the glossary `short` for each step's kind
  under the instruction, once.

### 4.7 Bean editor order and supermarket coffee (both; catalog in CORE)
- Order: Name, Roaster, **Whole beans / Ground**, Roasted on (date, or "days ago" stepper: "Roasted
  about {n} days ago"), Roast level, Bag size, then disclosure **"More details"** ("Origin, farm,
  process, lot") containing Lot, Origin, Region, Farm, Producer, Washing station, Altitude (m or ft
  toggle; stored as metres string), Process, Varietal, Harvest, Price, Notes.
- Roaster catalog gains mainstream brands tagged `mainstream: true`, region-tagged, only where core
  verifies the brand sells whole bean or ground coffee there (e.g. Folgers, Maxwell House, Café
  Bustelo, Starbucks (retail), Dunkin', Peet's, Lavazza, illy, Segafredo, Kimbo, Jacobs, Tchibo,
  Douwe Egberts, Bru (filter), Tata Coffee Grand (filter), Key Coffee, UCC). Picker sorts the user's
  region and favourites first, then mainstream, then specialty A-Z.

### 4.8 Chime and step cues (both)
- On the first Start brew of a recipe with any step longer than 60 s while `soundCuesAsked` is
  false, an inline card above Start: **"Chime when it's time for the next step?"** buttons **"Yes,
  chime"** / **"No sound"**. Sets `soundCues`, sets `soundCuesAsked` (device-local).
- Android: actually play a short tone on step change when `soundCues` is on (ToneGenerator
  `TONE_PROP_BEEP` on `STREAM_NOTIFICATION`, or SoundPool with a bundled short click) and add the
  "Sound on each step" switch to Gear › Settings › Brewing (the setting exists but nothing plays and
  there is no toggle today).
- Step-change flash + banner (§3.5) on both platforms.

### 4.9 Espresso, moka and cold brew done properly (both)
- Labels by method (core `methodLabels`): espresso: WATER -> **"YIELD"** ("in the cup"), TIME ->
  **"SHOT TIME"**, ratio "1:2.5"; moka: WATER -> "WATER IN THE BASE"; cold brew: times in hours
  ("12 h 5 min").
- Espresso brew screen = **shot mode**: a big count-up seconds display (hero), a target band from
  the recipe ("25-30 s" when the recipe states it; else the recipe's end time), one big button
  "Start shot" -> "Stop shot", then an inline **"How much came out?"** grams field (yield,
  prefilled with the recipe's target) and "Rate it". Pre-infusion appears as a label during its
  window ("Pre-infusion · 0:08") when the recipe has it. Keyboard: Space start/stop.
- Review for espresso: summary shows DOSE, YIELD, RATIO, SHOT TIME; no drawdown/bed; TDS under
  Advanced; quick toggles as §4.4.
- If core can verify a source, add a basic espresso recipe ("A classic double": 18 g in, 36 g out,
  25-30 s) so espresso owners get more than one recipe.

### 4.10 Brew again (both)
- Log rows (hover/context menu), brew detail header, Today's Recent rows: **"Brew again"** (subtitle
  in menus: "Same coffee, grind and dose"). Opens brew setup for that brew's recipe prefilled with
  that brew's coffee, grinder, grind, dose and water. Web route `/brew/:recipeId/again/:brewUid`
  (new); Android `Routes.brew(id, againLogId)`.
- A deleted recipe falls back to a custom one-off setup with the brew's numbers (title "Brew
  again", steps from the original if stored, else a single "Brew" step for the original time).

### 4.11 Log search, filter, compare (both)
- Search field: placeholder **"Search coffee, recipe, notes"** (matches recipe name/author,
  coffee name/roaster, grinder, notes, next time, descriptors; diacritic-insensitive).
- Filter chips (wrap): **"Favourites"**, **"With photos"**, **"Rated 8+"**, **"Coffee ▾"**,
  **"Recipe ▾"**, **"Brewer ▾"**. Sort: "Newest" / "Best rated". A result line: "12 brews".
  No results: "No brews match. Clear filters" (button).
- Compare per §3.8.

### 4.12 Empty states with a next step (both)
- Log: "No brews yet" / "Brews you finish and rate show up here." + primary **"Brew your first
  cup"** (opens the first-brew recipe).
- Today Recent: "Brews you finish show up here." + quiet "See recipes".
- Gear Beans: "Roast date is the one field that pays for itself." + primary **"Add the coffee
  you're drinking"**.
- Gear Grinders: "Add your grinder so grind settings make sense." + **"Add a grinder"** and **"I buy
  ground coffee"** (sets `buysGround`, then shows "You're set: brews will use the coffee as
  bought.").
- Insights (fewer than 3 brews): "Insights start after 3 brews. {n} to go." + **"Brew now"**.
- Recipes "Mine": "Your own recipes live here." + **"Make your own"** and "Duplicate one you like".
- Empty-state art = the user's first owned brewer's art (core adds arts for drip machine, cezve,
  siphon, phin; §6.5), else the mug.

### 4.13 Brewer families (both; CORE)
- `brewerFamily`: drip-machine, drip-bag, phin, south-indian-filter, melodrip ("accessory": matches
  nothing), other ("other": matches nothing) get their **own** family. Never fall back to V60 or
  AeroPress recipes. Cezve ("turkish") and siphon already have their own method family.
- Recipes for those families only where core verifies a source (targets: SCA batch-brew ratio for
  drip machines with a scoop equivalent; a drip-bag maker's instructions; a phin guide from a
  Vietnamese roaster; a cezve guide; a siphon maker's guide; a South Indian filter guide). Each new
  recipe obeys `recipes.md` rules (full numbers, URL source). Unsourced families get the §4.6
  "no tested recipe yet" copy.
- Web bug: Recipes "My brewers" must derive on every render: `myBrewers = hasOwned && (choice ??
  true)` with only the explicit `choice: boolean | null` in state (mirror Android).

### 4.14 Photos on desktop (web)
- Drag and drop image files onto any photo strip (Review, brew detail, bean editor). While dragging
  over the strip's screen, the strip shows a dashed drop zone "Drop photos here"; files go through
  the existing pipeline (`features/photos/pipeline.ts`), same limits and messages.
- Paste: Ctrl/Cmd+V with an image on the clipboard adds it when that screen is active and no text
  field is focused.
- Lightbox per §3.13.

### 4.15 Units, dates, numbers by locale (both; CORE)
- New installs: `tempUnit` defaults to "f" when the locale region is US, PR, GU, VI, AS, MP, UM, LR,
  BZ, BS, KY, PW, FM or MH (same region parse as `localCurrency`); otherwise "c". Existing stored
  values never change.
- `homeArea` is prefilled from the locale region / timezone only if it maps to an existing
  homeArea value.
- Recipe prose: normalise every temperature in `shared/recipes.json` to the form "70 °C" (wording
  change, allowed by `recipes.md`), e.g. Onyx "205 F (96 C)" -> "96 °C"; renderers convert `N °C`
  in prose to °F (rounded to whole degrees) when the user uses °F. Both platforms share the regex in
  the parity fixture (§6.7).
- Dates/times on screen use the runtime locale: web `Intl.DateTimeFormat(undefined, ...)`, Android
  `DateTimeFormatter.ofLocalizedDate/Time` (12/24 h follows the system). Exports and AI prompts keep
  the pinned formats. Today's eyebrow becomes e.g. "EVENING · THU, NOV 19" in en-US.
- Durations >=60 min: "12 h 5 min" (both platforms), on cards, Today and Recipes.
- `<html lang>` follows `navigator.language` for the date formats, but copy stays English (string
  extraction deferred, §9).

### 4.16 Smaller fixes (both unless noted)
- Hoffmann French press "Skim" step: kind `Serve` -> `Stir` (title stays "Skim").
- Recipe card grind tile wraps to 2 lines; never ellipsizes numbers.
- Insights export rows get plain labels: "Markdown with the 8 brews before it" -> **"Copy as text
  for an AI chat (this brew and the 8 before it)"**; "Save backup (JSON)" -> **"Save a backup file"**.
- "Bed exposed" button gets the glossary Term and helper "Tap when you first see the coffee bed
  through the water".

---

## 5. Accessibility (both)

| # | Fix | Owner |
|---|---|---|
| A1 | Forced colors (web): `@media (forced-colors: active)`: rating dots drawn with `1px solid CanvasText` border, "on" dots `background: Highlight` with `forced-color-adjust: none`; selected segments, chips and nav items get `Highlight`/`HighlightText` or a 2 px border; controls get `1px solid ButtonBorder`. | web shell (design) + each screen for its own custom controls |
| A2 | Segmented controls are not tabs: `role="radiogroup"` + `role="radio"` with roving tabindex, Arrow/Home/End. Real Gear section tabs keep `role="tab"` with `aria-controls` -> panel. Rating dots: radiogroup with arrows. | web shell |
| A3 | Hidden nav: `inert` on the bar/rail when a layer covers it. Detail `aria-label`s become human: "Brew", "Brew details", "Rate this brew", "Set up", "Recipe", "Welcome". | web shell |
| A4 | Brew announcements: polite live region, cleared before each write: at start and every step change "Step 2 of 6, Pour to 300 g. Steady spiral from the centre outward. Next at 1:15."; at the end "Brew done, 3:32. Rate this brew."; with auto-advance off "Time for the next step."; once at 10 s left in a step longer than 30 s "10 seconds left". Android: same strings via a `liveRegion = Polite` text + `paneTitle` on brew, review and sheet roots. | web brew, Android brew |
| A5 | Route changes (web): `document.title = "<screen> · Coffee"`, focus the new screen's `h1` (`tabindex=-1`), restore focus to the trigger on Back/Esc (per layer stack), `<main>` around the active layer/pane, skip link. | web shell (mechanism) + screens (an `h1` each) |
| A6 | Leave-brew and every destructive confirm focus the safe action (§2.4). | web shell + Android A1 |
| A7 | 200% text: no fixed heights on text containers; numbers never ellipsize; stat grids wrap 3 -> 2 -> 1 by container query; bar labels may wrap. | each package for its screens |
| A8 | Android semantics: `SelectableChip` -> `Modifier.toggleable(role = Role.Checkbox)`; segments and `RadioRow` -> `Modifier.selectable(role = Role.Tab/RadioButton)` with `selectableGroup()`; `SettingSwitch` -> `toggleable(role = Role.Switch)` on the Row and `onCheckedChange = null` on the inner Switch; `heading()` on section titles; `.height(x)` -> `.heightIn(min = x)` on chips (34), segments (40), quiet buttons (40), step-kind pill and DateChip (22), brewer pill (28). | Android A1 (components) + A2/A3 (screens) |
| A9 | Android brew step card scrolls instead of `maxLines` + ellipsis. | Android A2 |
| A10 | Every icon-only button has an accessible name including its shortcut in the tooltip, not in the name. | all |
| A11 | axe: zero serious/critical in both themes at 390 and 1440 on Today, Recipes, recipe detail, brew setup, brew running, review, Log, brew detail, Insights, Gear (3 sections), Welcome. | web integration |
| A12 | Touch targets >=44 (48 on Android) at every class; rail items 64x80. | all |

---

## 6. Data and domain changes (CORE package only)

All additive; backup stays `version: 2`. Update `shared/schema/backup-v2.md`, add fixtures, and
make both apps read old and new files.

### 6.1 Settings (`settings` block, both apps)
New optional keys: `experience` ("" | "new" | "recipes" | "tinker", default ""), `hasScale`
(boolean, default true), `reviewMode` ("quick" | "detailed"; default "quick" on a fresh install;
an existing install with any brew and no stored value reads "detailed"), `buysGround` (boolean,
default false). Device-local only (never in backups): `soundCuesAsked`, `sidebarCollapsed`,
`logView`. `tempUnit` default per §4.15 for fresh installs only.

### 6.2 Records
- beans: `form` ("" | "whole" | "ground", "" = unknown, treated as whole), `groundFor` ("" | "filter"
  | "press" | "espresso" | "moka").
- grinders: `settingUnit` ("" | "s"; "s" = seconds of grinding, a blade grinder; `setting.style`
  stays "stepless" so 2.0.0 readers still open it).
- brews: `grindKind` ("" | "dial" | "blade" | "preground"; "" = dial/unknown).
- faults vocabulary: append "Too weak", "Too strong" (both apps' FAULTS lists; older apps show them
  as the stored strings).
- Room v4 -> v5: `ALTER TABLE beans ADD COLUMN form TEXT NOT NULL DEFAULT ''`, `groundFor` same,
  `grinders.settingUnit`, `brews.grindKind` (use the real table names); exported schema 5 json;
  `MigrationTest` 4 -> 5. Dexie: no version bump (no new index); repo/backup readers default missing
  fields.

### 6.3 Fixtures and docs
- Keep `shared/fixtures/backup-v2-sample.json` unchanged (it proves 2.0.0 files still read).
- Add `shared/fixtures/backup-v2.1-sample.json`: every new key populated (a ground bean, a blade
  grinder, a pre-ground brew with fault "Too weak", settings with the four new keys).
- Both apps' backup tests: read both samples; round-trip the 2.1 sample losslessly; a 2.1 file read
  by the 2.0 rules (ignore unknown keys) loses only the new keys.
- `backup-v2.md`: document the keys under each section with a "Added in 2.1" note.

### 6.4 Recipes (`shared/recipes.json` + `schema/recipes.md` + both apps' recipe models and the
user-recipe editor round trip)
- Optional `level` ("easy" | "medium" | "advanced"), `needs` (subset of "scale", "gooseneck",
  "grinder", "thermometer"), `prepNoScale` (string[]). Fill `level` and `needs` for every built-in
  (core judges from the steps: e.g. Hoffmann French press easy, needs grinder; WAC AeroPress
  advanced, needs scale+grinder+thermometer). `prepNoScale` for at least every easy recipe that
  mentions a scale.
- Temperatures normalised (§4.15). Skim step kind (§4.16).
- New recipes per §4.9 and §4.13 only with verified sources. Update `RecipeIdsTest` golden data and
  the web recipe tests.

### 6.5 Catalog
- brewers.json: add `south-indian-filter` (category "other", method "pourover", filter "metal",
  note in the house style). Catalog brewer methods stay; families are code (§4.13).
- grinders.json: add `blade-grinder` ("Blade grinder", brand "Any", burr "blade", style
  "stepless", min 0, max 60, step 1, `settingUnit` "s").
- water.json: generic ids for "Tap water", "Filtered tap water", "Bottled spring water" if missing.
- roasters.json: mainstream brands (§4.7) with `mainstream: true`.
- `BrewerArt` gains `Drip`, `Cezve`, `Siphon`, `Phin`; core maps them in `brewerArt` and adds
  **placeholder** drawings (reuse the nearest existing art) in `web/src/art/BrewerIcon.tsx` and
  Android `CoffeeArt.kt`; the screen packages draw the real ones (web Recipes package, Android A2).

### 6.6 Domain functions (both platforms, same results)
`familyOf`/`brewerFamily` (§4.13), `firstBrew` (§4.6), `noScaleAmounts(doseG, waterG, method,
form)`, `noScalePrep(recipe, units)`, `servingsPreset(recipe, mugs)`, `renderTemps(text, unit)`,
`formatDuration(sec)` (h/min), `ratioPhrase`, `grindReference(word)`, `reviewSections(brewer)`,
`methodLabels(method)`, `quickRead` v2 (§4.4 table), data-quality `GRIND_MISSING` skip,
`defaultTempUnit(locale)`, localized date formatters (screen formats only), glossary loader +
`termForStepKind(kind)`, log search matcher `matchesLogQuery(brew, query)` and filters.

### 6.7 Parity fixture
`shared/fixtures/domain-v4-cases.json`: input/expected pairs for `noScaleAmounts`, `renderTemps`,
`formatDuration`, `firstBrew` (small recipe list), `quickRead` v2 (one per table cell), `brewerFamily`,
`reviewSections`, `defaultTempUnit`, `matchesLogQuery`. Web Vitest and Android unit tests both run
every case.

---

## 7. Packages, order and ownership

Order: **CORE** -> (**WEB-SHELL** -> 4 parallel **WEB-SCREENS** -> **WEB-INT**) and, in sequence,
**AND-1 -> AND-2 -> AND-3**. The Android chain and the web chain are independent after CORE (the
orchestrator decides whether they overlap; only one Gradle build may run at a time).

| Tag | Package | Owns (edits only these) |
|---|---|---|
| V4-CORE | core data/domain | `shared/**`, `web/src/domain/**`, `web/src/data/**`, `web/src/catalog/**`, `web/src/art/BrewerIcon.tsx` (placeholder mapping only), Android `domain/**`, `data/**`, `core/**`, `ui/components/CoffeeArt.kt` (placeholder only), Room schema json, unit tests for these |
| V4-WSHELL | web shell | `web/src/app/**`, `web/src/design/**`, `web/index.html`, `web/src/main.tsx`, stub files listed in its prompt |
| V4-WBREW | brew + review | `web/src/features/brew/**`, `web/src/features/review/**`, `web/src/features/grind/**` |
| V4-WRECIPES | today + recipes | `web/src/features/today/**`, `web/src/features/recipes/**`, `web/src/features/recipe-editor/**`, `web/src/art/**` |
| V4-WLOG | log + insights + photos + ai | `web/src/features/log/**`, `web/src/features/insights/**`, `web/src/features/photos/**`, `web/src/features/ai/**` |
| V4-WGEAR | gear + onboarding | `web/src/features/gear/**`, `web/src/features/onboarding/**`, `web/src/features/backup/**`, `web/src/features/catalog-picker/**` |
| V4-WINT | web integration | anything for glue, `web/scripts/**`, `docs/plan/v4-spec.md` |
| V4-AND1 | Android shell | `ui/nav/**`, `ui/theme/**`, `ui/components/**` (except CoffeeArt.kt art drawings), `MainActivity.kt`, `CoffeeApp.kt`, build files, manifest |
| V4-AND2 | Android brew/review/today/recipes | `ui/screens/brew/**`, `ui/screens/review/**`, `ui/screens/home/**`, `ui/screens/recipes/**`, `ui/recipes/**`, `ui/components/CoffeeArt.kt` |
| V4-AND3 | Android log/insights/gear/onboarding | `ui/screens/history/**`, `ui/screens/insights/**`, `ui/screens/gear/**`, `ui/onboarding/**`, `ui/photos/**`, `ui/ai/**`, `ui/backup/**`, `ui/catalog/**` |

A screen package that needs a pure helper writes it in its own folder. A needed change in another
package's folder is reported for WEB-INT (or the next Android package), not made.

---

## 8. Verification

### 8.1 Web viewports (Playwright, system Chrome, both themes)
360x800, 390x844, 412x915 (compact); 640x360, 844x390, 915x412, 720x450 (short); 600x960,
768x1024, 820x1180 (medium); 1024x600, 1024x768, 1180x820 (expanded); 1280x800, 1440x900 (large);
1600x900, 1920x1080, 2560x1440 (XL). Live resize 1440 -> 400 -> 1440 with a recipe selected and a
brew running. Forced colors emulated. 200% text (`html { font-size: 32px }`) at 390 and 1440.

### 8.2 Android
AVD `coffee` (phone) portrait + landscape; `coffee-tablet` (create once: Pixel Tablet / 2560x1600,
about 1280x800 dp, `-gpu host`, the smallest RAM that boots) landscape + portrait; font scale 2.0 on
the phone; `adb shell wm size` to simulate 1024x640 dp and 840x... where the tablet AVD cannot be
started for RAM reasons (say so).

### 8.3 Performance (no Lighthouse)
Playwright + CDP on the production build: record `requestAnimationFrame` deltas and Long Animation
Frames during (a) tab switch, (b) recipe pane selection change x10, (c) opening/closing a dialog,
side sheet and the palette, (d) resize across 840 and 1200. Pass: no LoAF > 50 ms during (a)-(c),
p95 frame delta <= 17 ms headless; CSS animations/transitions only on transform, opacity,
background-color (grep check). Entry chunk gzip within +15% of the pre-v4 build (record both).

---

## 9. Deferred (not in v4), with reasons
1. Full UI localisation (string extraction, ja/de/hi). Large, needs translators; v4 does
   locale-aware dates/units only.
2. Android command palette and Android context menus (secondary click). Web desktop is where they
   pay off; Android gets shortcuts, hover and tooltips first.
3. Android Log table view and Insights hover crosshair. Desktop-web-first; Android tablets get the
   grid and list-detail.
4. XL three-pane Recipes (filter rail + list + detail). Two panes plus wrapped chips cover it; revisit
   with usage.
5. PWA `window-controls-overlay`. Cosmetic, risk with the CSP and title area.
6. Foldable posture/hinge-aware layouts (tabletop mode). No device to verify; window classes cover
   the unfolded case.
7. Android drag-and-drop photos. Needs DnD permission handling across apps; low use.
8. Real-iOS Safari sound and wake-lock verification. No device; the flash + banner cue covers the
   silent case.
9. Volume-only recipes for every recipe text (rewriting all prose into spoons). v4 renders spoons and
   ml from grams and replaces scale prep lines; prose keeps grams with an ml hint.
10. Gooseneck/thermometer ownership questions in onboarding. One question too many; needs are shown
    per recipe instead.
11. Bean-level "days ago" auto-ageing reminders and notifications. Not asked for; needs notification
    permission design.

---

## Deviations (what shipped differently, and why)

Written by V4-WINT after the web packages landed (2026-11-19), from the package reports, the
commits and the code. The Android packages (V4-AND1..3) report their own; only cross-platform
points are noted here. The audits and research named at the top lived in the lead's scratch space
and are not in the repo.

### V4-WSHELL (web shell)
- `web/eslint.config.js` gained `jsx-a11y/aria-role: ["error", { ignoreNonDOM: true }]`: the
  spec's `role` prop on `CoffeeSheet`/`ConfirmSheet` (§2.4) otherwise fails lint at every call.
- `viz/ConfirmSheet.tsx` is a one-line re-export of `design/components/ConfirmSheet.tsx`;
  V4-WINT deleted the orphaned `viz/confirm.module.css`.
- Gear's beans/grinders/settings selection has no route (§2.3 lists URL selection for Recipes and
  Log only): `ListDetail` takes a `detail` + `detailKey` instead. Switching tabs pops a tab to its
  root, so a pane selection is dropped and the tab comes back to its default detail.
- The palette caps each group (6-10 rows, under 50 in all) instead of virtualising past 50 rows
  (§2.9 rule 5): the list never gets long enough to need it.

### V4-WBREW (brew, review)
- Delivered as specified (§3.4-§3.6, §4.2-§4.4, §4.8, §4.9, A4). `BrewScreen` takes `againUid`
  and binds Escape to the leave confirm.
- V4-WINT fix: the seek buttons were named "Back 5 seconds"/"Forward 5 seconds" while showing
  "−5 s"/"+5 s" (axe `label-content-name-mismatch`, serious). Their names are now "Back 5 s" /
  "Forward 5 s"; the tooltip still says "Back 5 seconds  ←".

### V4-WRECIPES (Today, Recipes, recipe editor, art)
- `brew.next` is registered by both Today and Recipes, so the rail/sidebar Start brew works
  whichever tab mounted first.
- V4-WINT fixes: recipe card titles (`viz/RecipeCard`) and Recipes list-row names no longer clamp
  to 2 lines (at 200% text long names were cut off; A7). Today's Recent rows are named by their
  own text plus a hidden ", scored 8 of 10" (their `aria-label` left out the visible details:
  axe `label-content-name-mismatch`). The dose stepper's − / + buttons got tooltips (A10).
- V4-WINT: `brew.next` ("Brew <recipe>") is found by its keywords only (`keywordsOnly`, new on
  `Command`). With the title searchable, typing a recipe's name ranked the Actions group first and
  Enter started a brew instead of opening the recipe (§2.8: Enter opens, Ctrl/Cmd+Enter brews).

### V4-WLOG (Log, brew detail, compare, Insights, photos, AI)
- Delivered as specified, including the XL List/Table toggle, compare, chart hover/keyboard and
  the lightbox.
- V4-WINT fix: the AI settings sheet (`AiSettingsSheet`) is now `role="editor"`, so it is the
  560 px right side sheet at expanded+ (§2.4 lists "AI provider" as an editor); it was a
  full-window content sheet.

### V4-WGEAR (Gear, Settings, onboarding, backup, catalog picker)
- At expanded+ the bean and grinder editors are inline in the detail pane (§3.10), so the editor
  side sheet (§2.4) only appears for the AI settings sheet. Below expanded they are full-height
  sheets.
- The chosen Settings section is in-memory state (`settingsSections.ts`), not in the URL; a reload
  returns to "You".
- Onboarding's skip is labelled "Skip for now" (§3.11 wording); the final step's buttons are
  "Start my first brew" / "Go to Today".
- V4-WINT fixes: "Brewed with this coffee" split "75 clicks (7.5 on the dial)" into the value and
  a sub line (the value was clipped in a third of the pane at 844-1280 wide); `DataGrid` sub lines
  now wrap instead of ellipsizing. `PickRow` is named by its content (a visually hidden label plus
  the value and any trailing chip such as "Edited") instead of an `aria-label` that left the chip
  out (axe `label-content-name-mismatch`).

### V4-WINT (integration)
- Palette actions "New recipe" and "Rate your last brew" (§2.8) were registered by no screen; they
  are store-only, so they live in the shell's `dataCommands`.
- Tooltips now render inside an open modal `<dialog>` (top layer) instead of under it.
- Hidden tab layers pause only elements marked `data-loop` (the infinite art/mesh/live-dot
  animations) instead of `.layer[data-hidden] *`, which restyled every element of a tab on each
  show and hide.
- Performance (§8.3): the 1× desktop pass (tab switch, 10 pane changes, dialog, side sheet,
  palette) has no long animation frame and p95 frame time ≈ 6 ms headless. The older 4×-throttled
  phone check in `npm run smoke` allows a tab switch up to 60 ms (was 50): the tab layer is now a
  `pane` size container (§1.1), and flipping its inherited `visibility`/`inert` re-lays out the
  whole tab (≈2.3k elements on Recipes: 48-56 ms at 4×, 12-14 ms at 1×). Moving the container to
  an inner box, dropping `inert`, and an opacity-only hide were each measured and did not remove
  it; the alternatives (no container queries in tabs, or `content-visibility` on the scroller) cost
  more than they save. Recipe open/close keeps the 50 ms rule.
- Entry chunk: the pre-v4 build (1085e3a) loaded 167,873 B gzip on first load (entry 134,507 B);
  v4 loads 188,346 B (+12.2%, inside +15%). The entry file alone shrank to 85,415 B because
  Rolldown now splits shared modules into preloaded chunks, so first-load totals are compared.
