# Coffee 2.0 — Web implementation spec

Written 2026-11-17 by the web tech lead after a read-only pass over `the repo`
(Android source, `shared/catalog/*.json`) and the Android spec
(`scratchpad/w1/android-spec.md`, called **A§n** below) and jank audit (`scratchpad/w1/jank-audit.md`).
MUST = load-bearing; SHOULD = default unless there is a concrete reason; MAY = optional.
"Android source" means `app/src/main/java/com/laksh/coffee/`.

The web app is the same product as the Android app: same data, same recipes and catalogs, same
Flighty design language, same backup file. It is a static site (no server of ours), installable
as a PWA, works offline, and keeps everything in the browser (IndexedDB). It lives in the same
repo under `web/` and reads `shared/` directly.

---

## 0. Ground rules for every web package

- Stack is fixed: Vite + React 19 + TypeScript (strict) + Motion for React (`motion/react`) +
  Dexie (IndexedDB) + vite-plugin-pwa + Vitest. No other runtime dependencies without a written
  reason in the PR (no router library, no state library, no UI kit, no CSS framework, no chart
  library, no analytics, no error-reporting SDK, no web fonts, no CDN).
- Copy rules are Android's (A§0): plain, second person, British spelling ("colour", "favourite",
  "Personalise"), no exclamation marks, no emojis. A status colour always travels with a word.
  Crema only for the live brew and the one primary action on a screen.
- Never invent a coffee number, a person's recipe, a URL or a grinder figure. Numbers come from
  `shared/` JSON (which carries sources). App heuristics (move sizes, 1,100 tokens per image) are
  labelled as the app's own rule of thumb in comments and in any copy that shows them.
- Behaviour parity: when this spec says "port X", the Kotlin file is the truth for existing
  behaviour and the Android spec section is the truth for 2.0 features that may not have landed
  yet. If they disagree on a 2.0 feature, follow A§ and note it in the PR.
- Motion rules in §7 apply to every package. A PR that animates a layout property, re-renders
  React per frame, or replays an entrance on return is not done.
- Verify before handing over (Git Bash, from `web/`): `npm run typecheck && npm run lint && npm test && npm run build && npm run smoke`.
  The owner's machine runs Node 25.5.0; Vitest 5 declares `^22.12 || ^24 || >=26`. If Vitest
  misbehaves on 25, use Node 24 LTS for the run (do not set `engine-strict`).
- Do not commit, push, deploy or publish unless the orchestrator says so. Never commit an API key,
  a `.env` with secrets, or `.vercel/`.

---

## 1. Facts that drive this design

1. `shared/catalog/*.json` exists today (57 grinders, 404 roasters, 50 origins, 51 brewers,
   23 water, 15 providers, bean vocabulary). `shared/recipes.json` (A§2.6, Android WP-1),
   `shared/principles.json` (A§8.9, WP-3) and `shared/fixtures/*` (A§10.4, WP-5) do **not** exist
   yet. The web build MUST tolerate their absence (§3.3) and pick them up when they land.
2. `providers.json` carries a `browserCors` flag per provider with evidence captured from a
   Vercel origin on 2026-11-17: every hosted provider is `yes`; `anthropic` needs the
   `browserHeaders` entry `anthropic-dangerous-direct-browser-access: true` (preflight fails
   without it) plus its own `extraHeaders` `anthropic-version`; `ollama` and `lm-studio` are
   `http://localhost…` with `browserCors: "no"` (need OLLAMA_ORIGINS / "Enable CORS");
   `custom` is `unknown`.
3. Room is moving to v4 (A§4). Backup v2 (A§10.1) is the only bridge between phone and web, so
   the web codec MUST read and write exactly that document, and the Dexie rows mirror Room v4
   columns so domain code ports 1:1.
4. The owner's complaint is jitter "between sections" on a 120 Hz phone. The Android audit's
   causes map directly onto the web: remounting tab roots on switch, data landing mid-transition,
   two translucent pages sliding at once, integer-pixel spring tails, entrances replaying,
   per-frame state updates. §6 and §7 are built to make each of those impossible by construction.
5. Motion 14.14 hardware-accelerates (runs as WAAPI on the compositor) only `opacity`,
   `transform` (the whole property, not `x`/`scale` shorthands), `filter` and `clipPath`, and
   only when there is no `onUpdate`. Verified in `motion-dom@14.14.0`
   `animation/waapi/utils/accelerated-values.mjs` and `supports/waapi.mjs`. Springs are converted
   to `linear()` easing for WAAPI. Layout/`layoutId` animations are main-thread (projection).
6. Motion's `AnimatePresence mode="popLayout"` injects a `<style>` element and the `scope` API
   calls `setAttribute("style")` (`PopChild.mjs`, `scope/index.mjs`). Both are blocked by a CSP
   without `'unsafe-inline'` styles. React's `style` prop and Motion's normal writes go through
   CSSOM and are allowed.
7. React 19.3 ships `<Activity>`; its hidden mode applies `display:none` **and tears down
   effects**, so a `useLiveQuery` inside would unsubscribe and refetch on show — data landing
   mid-transition, the audit's rank-5 cause. Tab roots therefore do **not** use `<Activity>`.
8. Android's design tokens are in Kotlin (`ui/theme/Color.kt`, `Type.kt`, `Dimens.kt`,
   `Motion.kt`). PERF (another owner) may replace `Motion.kt` with the 120 Hz tokens from the
   audit §3.3; the web token file carries both sets and follows whatever `Motion.kt` ends up with.
9. Android's app icon is a 108×108 vector (`res/drawable/ic_launcher_foreground.xml`) on
   `#2A1C12`; its paths are valid SVG path data and become the PWA icon unchanged.

---

## 2. Stack and versions (checked with `npm view` on 2026-11-17)

`web/package.json` pins exact versions (no `^`) and commits `package-lock.json`:

| package | version | note |
|---|---|---|
| react, react-dom | 19.3.1 | published 2026-10-14 |
| motion | 14.14.0 | `motion/react`; depends on framer-motion/motion-dom 14.14.0 |
| dexie | 4.4.7 | |
| dexie-react-hooks | 4.4.0 | `useLiveQuery` for per-detail queries only (§8.3) |
| vite | 8.3.8 | Rolldown-based |
| @vitejs/plugin-react | 6.3.0 | needs Vite 8 |
| vite-plugin-pwa | 2.0.0 | peers: workbox-build 7.4.1, workbox-window 7.4.1 (install both) |
| typescript | **6.0.3** | not 7.0.2: typescript-eslint 8.73 peers `typescript <6.1.0` |
| vitest | 5.1.0 | |
| jsdom | 31.0.1 | Vitest environment for component/data tests |
| fake-indexeddb | 6.2.8 | `import "fake-indexeddb/auto"` in test setup |
| @testing-library/react / dom | 16.3.3 / 10.4.2 | |
| eslint / @eslint/js | 9.39.5 / 9.39.5 | not 10: eslint-plugin-jsx-a11y peers `eslint ≤9` |
| typescript-eslint | 8.73.0 | |
| eslint-plugin-react-hooks | 7.1.1 | |
| eslint-plugin-jsx-a11y | 6.10.2 | |
| globals | 17.14.0 | |
| @types/react, @types/react-dom | 19.3.0 | |
| @types/node | 24.19.2 | vite.config, scripts |
| playwright | 1.65.1 | smoke script only (devDependency; `npx playwright install chromium` once) |
| axe-core | 4.14.1 | smoke script a11y pass |
| @vite-pwa/assets-generator | 2.0.0 | `npm run icons` only, outputs committed (uses sharp; never runs on Vercel) |
| babel-plugin-react-compiler, @rolldown/plugin-babel, @babel/core | 1.0.0, 0.3.1, 7.29.13 | SHOULD: React Compiler via the plugin-react v6 Babel route (§4.1). Drop all three if the build breaks or takes > 60 s |

Browser support target: last two versions of Chrome/Edge/Firefox, Safari/iOS 17+. Features
newer than that (`content-visibility` before Safari 18, `AbortSignal.any` before Safari 17.4,
scroll-driven animations, View Transitions) are progressive: feature-detect, never required.

---

## 3. Repo layout

### 3.1 Top level (this spec adds `web/`, `vercel.json`, `.vercelignore`)

```
Coffee/
  app/ gradle/ build.gradle.kts …      Android (excluded from Vercel uploads)
  shared/                              catalogs, recipes, principles, schema docs, fixtures (read by both apps)
  web/                                 this spec
  vercel.json                          §16.1
  .vercelignore                        §16.2
  .gitignore                           already ignores web/node_modules, web/dist, web/.vite, .vercel/
```

### 3.2 `web/`

```
web/
  index.html                 §4.4
  package.json  package-lock.json  .nvmrc ("24")
  tsconfig.json              references app + node + worker configs
  tsconfig.app.json tsconfig.node.json tsconfig.worker.json
  vite.config.ts             §4.1 (Vitest config lives here under `test`)
  eslint.config.js           §4.3
  public/
    boot.js                  theme + motion preference before first paint (§5.6), CSP-safe external script
    icon.svg                 from the Android launcher vector (§4.5)
    icons/                   generated PNGs + favicon, committed
    robots.txt
  scripts/
    gen-tokens.mjs           Color.kt → src/design/tokens.gen.css (§5.1)
    vercel-sim.mjs           §16.3
    smoke.mjs                §19.2
  src/
    main.tsx  env.d.ts
    app/                     shell: boot, router, TabsHost, DetailHost, BottomBar, transitions, update toast (WEB-1)
    design/                  tokens, base CSS, motion tokens, primitive components, icons (WEB-1)
    viz/                     JourneyLine, RouteRow, TimelineCard, BrewRing, MeshGradient, RecipeCard (WEB-2)
    art/                     BrewerIcon, BedShapeIcon, CoffeeMug, Steam, Dripper, TumblingBeans (WEB-2)
    data/                    Dexie db, row types, hot store + hooks, repo, settings, storage (WEB-1)
    catalog/                 shared JSON loaders, registry, recipes merge (WEB-1)
    domain/
      core/                  brewer, legacyIds, recipe, evaluation, status, units, format, quickRead (WEB-1)
      grind/                 dialFormat, grindScale, numberedDials, moveSizes, grindLooksTyped (WEB-1)
      ai/                    dataQuality, analysisPrompt, analysisResult, aiError, baseUrl, modelCatalog, providers, principles (WEB-3)
      insights/              passport stats, chart models (WEB-3)
      report/                markdown brew report + history table (WEB-3)
      search/                catalog search (WEB-4)
      editor/                recipe validation + draft model (WEB-4)
      backup/                v2 model, codec, legacy reader, merge planner, sanitiser, catalog linker (WEB-5)
    features/
      today/ recipes/ brew/ review/ photos/            (WEB-2)
      log/ insights/ ai/                               (WEB-3)
      gear/ grind/ catalog-picker/ onboarding/ recipe-editor/   (WEB-4)
      backup/                                          (WEB-5)
    test/                    setup.ts, fixtures helpers (WEB-1)
```
Tests sit next to their code as `*.test.ts(x)`. Each `features/<name>/index.ts`, `viz/index.ts`
and `art/index.ts` is the package's public surface; nothing imports a feature's internals from
outside it.

### 3.3 Reading `../shared` (copy nothing by hand)

- Vite alias `@shared` → `path.resolve(__dirname, "../shared")`; TS `paths` `"@shared/*": ["../shared/*"]`.
- Dev server: `server.fs.allow: [path.resolve(__dirname, "..")]`.
- Every shared file is loaded through `import.meta.glob` so a missing file is an empty map, not
  a build error:
  ```ts
  // src/catalog/sources.ts
  const catalogFiles = import.meta.glob<{ default: unknown }>("@shared/catalog/*.json");
  const rootFiles = import.meta.glob<{ default: unknown }>(["@shared/recipes.json", "@shared/principles.json"]);
  ```
  Each importer is a separate lazily loaded chunk, precached by the service worker. Vite's JSON
  plugin stringifies large files (`JSON.parse("…")`), which parses faster than an object literal.
- `brewers.json` and `recipes.json` load before first render (Today needs them on frame one);
  the rest load right after first paint in `requestIdleCallback` (fallback `setTimeout(…, 200)`),
  and on demand if a screen needs one sooner.
- Tests import the same files through the same alias.

---

## 4. Build configuration (WEB-1)

### 4.1 `vite.config.ts`

```ts
/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react, { reactCompilerPreset } from "@vitejs/plugin-react";
import babel from "@rolldown/plugin-babel";          // SHOULD (React Compiler); remove with the preset if dropped
import { VitePWA } from "vite-plugin-pwa";
import path from "node:path";
import { readFileSync } from "node:fs";

const root = path.resolve(__dirname, "..");
const pkg = JSON.parse(readFileSync(path.join(__dirname, "package.json"), "utf8"));
/** Production headers from vercel.json, applied to `vite preview` so CSP problems show up locally. */
function prodHeaders(): Record<string, string> {
  const v = JSON.parse(readFileSync(path.join(root, "vercel.json"), "utf8"));
  const all = v.headers.find((h: { source: string }) => h.source === "/(.*)");
  return Object.fromEntries(all.headers.map((h: { key: string; value: string }) => [h.key, h.value]));
}

export default defineConfig({
  plugins: [
    react(),
    babel({ presets: [reactCompilerPreset()] }),
    VitePWA({ /* §17 */ }),
  ],
  resolve: { alias: { "@": path.resolve(__dirname, "src"), "@shared": path.join(root, "shared") } },
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },      // "2.0.0", same as Android versionName
  server: { fs: { allow: [root] } },
  preview: { headers: prodHeaders() },
  build: { target: "es2022", sourcemap: true, assetsInlineLimit: 0, chunkSizeWarningLimit: 400 },
  worker: { format: "es" },
  test: {
    environment: "node",                              // DOM/IndexedDB tests opt in per file: `// @vitest-environment jsdom`
    setupFiles: ["src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
```
`sourcemap: true` ships maps (the code is open source anyway; maps help the owner debug on the
phone). `assetsInlineLimit: 0` keeps every asset a same-origin file (no `data:` surprises).

### 4.2 TypeScript

`tsconfig.app.json`: `"strict": true, "noUncheckedIndexedAccess": true, "noImplicitOverride": true,
"noFallthroughCasesInSwitch": true, "verbatimModuleSyntax": true, "moduleResolution": "bundler",
"module": "ESNext", "target": "ES2022", "lib": ["ES2023", "DOM", "DOM.Iterable"], "jsx": "react-jsx",
"resolveJsonModule": true, "types": ["vite/client", "vite-plugin-pwa/react"], "paths": {"@/*": ["./src/*"], "@shared/*": ["../shared/*"]}`.
`tsconfig.worker.json` covers `src/**/*.worker.ts` with `"lib": ["ES2023", "WebWorker"]`.
`npm run typecheck` = `tsc -b --noEmit`; `npm run build` = `tsc -b --noEmit && vite build`.

### 4.3 Lint (`eslint.config.js`, flat config)

`@eslint/js` recommended + `typescript-eslint` recommendedTypeChecked + `react-hooks`
recommended (v7 includes the compiler rules) + `jsx-a11y` recommended, plus:
- `no-restricted-syntax`: JSX attribute `dangerouslySetInnerHTML`; `AnimatePresence` with
  `mode="popLayout"` (fact 6); member access `.innerHTML =`.
- `no-restricted-globals` / `no-restricted-properties`: `localStorage`, `sessionStorage` anywhere
  except `src/features/ai/keyStore.ts`, `src/app/prefsMirror.ts` and `public/boot.js`.
- `no-console`: error in `src/features/ai/**` and `src/domain/ai/**` (keys must never reach a
  log); warn elsewhere (allow `warn`, `error`).

### 4.4 `index.html`

```html
<!doctype html>
<html lang="en-GB" data-theme="dark">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content" />
    <title>Coffee</title>
    <meta name="description" content="A brew timer, brew log and grind notes for pour-over and espresso." />
    <meta name="color-scheme" content="dark light" />
    <meta name="theme-color" content="#0D0B09" media="(prefers-color-scheme: dark)" />
    <meta name="theme-color" content="#F3EEE7" media="(prefers-color-scheme: light)" />
    <link rel="icon" href="/icons/favicon.svg" type="image/svg+xml" />
    <link rel="apple-touch-icon" href="/icons/apple-touch-icon-180x180.png" />
    <script src="/boot.js"></script>
  </head>
  <body>
    <div id="root"></div>
    <noscript>Coffee needs JavaScript to run.</noscript>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```
No inline script or style anywhere (CSP, §16.1). `boot.js` is ~20 lines, render-blocking on
purpose, and precached.

### 4.5 Icons

`public/icon.svg`: `viewBox="0 0 108 108"`, a full-bleed `#2A1C12` rect, then the five paths of
`ic_launcher_foreground.xml` unchanged (fills `#F2C078`; the two steam strokes `#E28F5B`,
`stroke-width="3"`, `stroke-linecap="round"`, `fill="none"`). The artwork already sits inside
the maskable safe zone. `npm run icons` runs `pwa-assets-generator --preset minimal-2023 public/icon.svg`
into `public/icons/`; the PNGs are committed so builds are deterministic.

---

## 5. Design system (WEB-1, `src/design/`)

The web app MUST look like the Android app in both modes. Every value below is converted from
the Kotlin source; sp and dp map to CSS px, and type sizes are written in `rem` (÷16) so the
browser's text-size setting still scales them.

### 5.1 Colours — `tokens.gen.css`, generated, committed

`scripts/gen-tokens.mjs` parses `DarkCoffee` and `LightCoffee` in `Color.kt`
(`(\w+) = Color\(0x([0-9A-Fa-f]{8})\)`, plus `Color.Transparent`) and the `DarkMoods`/`LightMoods`
lists, and writes:
```css
:root[data-theme="dark"]  { --bg: #0D0B09; --surface1: #1B1815; …; --bar: rgb(28 25 22 / 0.941); … }
:root[data-theme="light"] { --bg: #F3EEE7; … }
:root[data-theme="dark"]  { --mood-idle-a: #1F1813; --mood-idle-b: #2A1F17; --mood-idle-base: #0D0B09; … }
```
Conversion: `0xAARRGGBB` → `#RRGGBB` when AA = FF, else `rgb(R G B / AA÷255 to 3 dp)`. Names are
the Kotlin field names in kebab case (`ink-high`, `crema-ink`, `ring-liquid`…). Expected spot
checks: dark `--bar: rgb(28 25 22 / 0.941)`, dark `--scrim: rgb(0 0 0 / 0.6)`, light
`--scrim: rgb(27 22 17 / 0.322)`, dark `--ring-liquid: rgb(242 155 80 / 0.2)`, light
`--crema-ink: #8F430C`. A Vitest test (`design/tokens.test.ts`) regenerates from `../app/…/Color.kt`
when that file exists and fails if the committed CSS differs; it skips when `app/` is absent.
`data-theme` is always the resolved mode (`dark`|`light`); "system" is resolved by `boot.js` and
kept live by `prefsMirror.ts` listening to `matchMedia("(prefers-color-scheme: dark)")`.
The `theme-color` meta follows the resolved `--bg` (and the brew mood base while brewing).

### 5.2 Type — `tokens.css`

One family: `system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif`
(Android uses the platform sans; no web font, no network). Weights 400/500/700 only.
`font-variant-numeric: tabular-nums` on `html`. Classes (size/line-height in px → rem; tracking in px):

| class | size/line | weight | tracking |
|---|---|---|---|
| `.t-display-l` / `-m` / `-s` | 52/56, 40/44, 32/38 | 700 | -1.6, -1.2, -0.8 |
| `.t-headline-l` / `-m` / `-s` | 26/32, 22/28, 19/25 | 700 | -0.5, -0.3, -0.2 |
| `.t-title-l` / `-m` / `-s` | 17/22, 15/20, 13/18 | 500 | -0.1, 0, 0.1 |
| `.t-body-l` / `-m` / `-s` | 16/23, 15/21, 13/18 | 400 | 0, 0, 0.1 |
| `.t-label-l` / `-m` / `-s` | 15/20, 12/16, 11/14 | 500 | 0.1, 0.4, 1.2 |
| `.num-clock` / `.num-clock-compact` | 88/88, 72/72 | 500 | -2.5, -2 |
| `.num-display` | 64/64 | 700 | -2 |
| `.num-large` / `.num-medium` / `.num-small` | 40/44, 26/30, 20/24 | 700 | -1, -0.5, -0.3 |
| `.num-inline` | 15/20 | 500 | 0 |
| `.num-unit` | max(13px, 0.42 × numeral) | 500 | 0 (`NumberWithUnit` computes it) |
| `.eyebrow` | 11/14, UPPERCASE, `--ink-low` | 500 | 1.2 |
| `.micro` | 10/12, UPPERCASE | 700 | 1.0 |
| `.primary-label` / `.step-title` / `.verdict` | 16/20 700 0; 24/30 700 -0.3; 19/26 500 -0.1 | | |

### 5.3 Dimensions and radii — `tokens.css`

`--gutter:16px --card-padding:18px --card-gap:12px --section-gap:24px --eyebrow-gap:10px
--cell-pad-v:12px --cell-pad-h:14px --primary-h:56px --primary-in-card:52px --secondary-h:48px
--quiet-h:40px --icon-well:40px --stepper-well:44px --play-button:64px --chip-h:34px
--status-chip-h:22px --field-h:52px --field-multiline-h:88px --settings-row-h:56px --log-row-h:68px
--bottom-bar-h:60px --bottom-bar-item:52px`;
`--list-bottom-pad: calc(112px + env(safe-area-inset-bottom))`.
Radii: `--r-bar:6px --r-skeleton:10px --r-well:14px --r-button:16px --r-primary:18px --r-card:22px --r-nav:26px --r-sheet:28px`, pill `999px`.
Hit areas are at least 44×44 CSS px (pad the box, not the visual).
Layout: one centred column, `max-width: 640px`, gutters 16px, on every width. The bottom bar
sits in the same column. No other breakpoint behaviour except §18 keyboard shortcuts and hover
states behind `@media (hover: hover)`.

### 5.4 Motion tokens — `src/design/motion.ts`

Compose `spring(dampingRatio ζ, stiffness k)` has mass 1, so Motion's equivalent is
`{ type: "spring", stiffness: k, damping: 2·ζ·√k, mass: 1 }`. The file keeps a table of (ζ, k)
copied from `Motion.kt` and derives `damping`; `motion.test.ts` checks the table against
`Motion.kt` when that file exists (skips otherwise), so a PERF change to `Motion.kt` fails the
test until the web table follows.

| token | ζ | k | damping | source / use |
|---|---|---|---|---|
| `settle` | 0.85 | 380 | 33.14 | Motion.kt `settle()`; small things settling (chips, toggles' knob) |
| `bounce` | 0.55 | 320 | 19.68 | Motion.kt `bounce()`; tab icon scale only |
| `heavy` | 0.90 | 180 | 24.15 | Motion.kt `heavy()`; ring pour-pulse amount, mesh intensity |
| `quick` | 1.00 | 900 | 60.00 | Motion.kt `quick()`; press-state springs when JS-driven |
| `spatialFast` | 0.90 | 1400 | 67.35 | audit §3.3; outgoing tab drift, sheet close |
| `spatialDefault` | 0.90 | 700 | 47.62 | audit §3.3; anything that crosses the screen: incoming tab, detail scale, bar pill, segmented indicator, sheet open |
| `spatialSlow` | 0.90 | 300 | 31.18 | audit §3.3; rare large moves |
| `effectsFast` | 1.00 | 3800 | 123.29 | audit §3.3; outgoing opacity |
| `effectsDefault` | 1.00 | 1600 | 80.00 | audit §3.3; incoming opacity, scrims |
| `effectsSlow` | 1.00 | 800 | 56.57 | audit §3.3; mood crossfades |
| `bounds` | 0.90 | 700 | 47.62 | shared-element flight (audit `Bounds`) |

`restDelta`: 0.5 for px-valued targets, 0.004 (≈1/255) for opacity and 0–1 progress, so
animations end. Easing (CSS and choreographed tweens only): `--ease-emphasized: cubic-bezier(0.2, 0, 0, 1)`,
`--ease-decel: cubic-bezier(0.05, 0.7, 0.1, 1)`, `--ease-accel: cubic-bezier(0.3, 0, 0.8, 0.15)`;
`emphasized(ms = 420)`, `decel(ms = 360)`; `staggerDelay(i, perItem = 45, max = 320)` ms.
Spatial animations always use ζ ≥ 0.9 (overshoot below a pixel); `bounce` is only for the tiny
tab icon, as on Android.

### 5.5 Primitive components (`src/design/components/`)

Ports of `ui/components/*.kt` with the same names and props, in TSX + CSS modules. Each one is
`memo`-safe (stable props), uses tokens only (no hex literals outside generated CSS), and puts
press feedback in CSS (`:active { transform: scale(0.97) }`, `transition: transform 120ms var(--ease-emphasized)`)
on pressable elements only.

- `Bits.kt` → `EyebrowLabel`, `SectionLabel`, `SectionHeader`, `ScreenHeader` (sticky; hairline
  appears via CSS scroll-driven animation when `@supports (animation-timeline: scroll())`, else an
  IntersectionObserver sentinel toggles a class — never a scroll listener), `DetailTopBar`,
  `SoftCard`, `QuietPanel`, `HairLine`, `VerticalHairline`, `PerforatedDivider`, `SelectableChip`,
  `ChipFlow`, `SegmentedControl` (one indicator sliding with `transform`, `spatialDefault`, like the
  bar), `AxisSlider` (native `<input type="range">` underneath for keyboard and screen readers),
  `DotRating`, `KeyValueRow`, `ToggleRow` (native checkbox, `role="switch"`), `RadioRow`,
  `SkeletonBar`, `CoffeeSheet` (§6.6), `StickyCtaBar`, `StatTile`, `AccentBadge`, `DashedPlaceholder`.
- `Buttons.kt` → `PrimaryButton` (crema), `SecondaryButton`, `QuietButton`, `IconWell`, `PillButton`.
- `Fields.kt` → `FieldWell` (label above, value, helper/error line; `inputmode="decimal"` for numbers).
- `Status.kt` → `Status` enum, `statusColor()`, `statusContainer()`, `StatusDot`, `StatusChip`, `StatusLine`
  (pure mappers live in `domain/core/status.ts`).
- `Numerals.kt` → `NumberWithUnit`, `TickingNumber` (changed digits roll with a CSS keyframe on
  `transform`/`opacity`, keyed by value; no JS per frame), `DataGrid`, `Cell`.
- `Grouped.kt` → `GroupRow`, `LogRow`, `ActionRow`, `GroupPosition`.
- `Icon` + `icons.ts`: the Material Rounded glyphs the Android app uses (Coffee, MenuBook, History,
  Insights, Tune, Add, Close, ChevronLeft, Edit, Delete, Share, Star, Camera, Image, Check, Pause,
  Play, SkipNext, Replay, Timer, Info) as inline SVG paths copied from Material Symbols Rounded
  (Apache-2.0; add `web/NOTICE`).

`viz/` (WEB-2) and `art/` (WEB-2) hold the composite drawings; WEB-1 ships stubs (§20.4).

### 5.6 Theme and motion preference at boot

`public/boot.js` reads `localStorage["coffee.theme"]` (`system`|`light`|`dark`) and
`localStorage["coffee.motion"]` (`system`|`reduced`) inside try/catch, resolves the theme, sets
`data-theme` and `data-motion` on `<html>`. `src/app/prefsMirror.ts` is the only writer of those
two keys (mirroring `settings.darkMode` / `settings.motion` from IndexedDB) and keeps
`data-theme` live with the system scheme.

---

## 6. App shell (WEB-1, `src/app/`)

### 6.1 Routes

| route kind | URL | screen (feature export) | owner |
|---|---|---|---|
| tab `today` | `/` | `TodayScreen` | WEB-2 |
| tab `recipes` | `/recipes` | `RecipesScreen` | WEB-2 |
| tab `log` | `/log` | `LogScreen` | WEB-3 |
| tab `insights` | `/insights` | `InsightsScreen` | WEB-3 |
| tab `gear` | `/gear`, `/gear/grinders`, `/gear/app` | `GearScreen({ tab })` | WEB-4 |
| `recipe` | `/recipes/:id` | `RecipeDetailScreen({ id, from })` | WEB-2 |
| `recipeEdit` | `/recipes/new`, `/recipes/:id/edit`, `/recipes/:id/duplicate` | `RecipeEditorScreen({ id, mode })` | WEB-4 |
| `brew` | `/brew/:recipeId` | `BrewScreen({ recipeId })` | WEB-2 |
| `review` | `/review/:brewUid` | `ReviewScreen({ brewUid })` | WEB-2 |
| `brewDetail` | `/log/:brewUid` | `BrewDetailScreen({ brewUid })` | WEB-3 |
| `onboarding` | `/welcome` | `OnboardingScreen()` | WEB-4 |

URLs carry uids and recipe ids, never numeric database ids. `new` is a reserved recipe id
(recipe validation rejects it). Unknown paths render Today and `replaceState("/")`.

### 6.2 Router (`router.ts`, no library)

A tiny store over the History API, read with `useSyncExternalStore`:
```ts
type TabId = "today" | "recipes" | "log" | "insights" | "gear";
type SharedFrom = "home" | "next" | "recipes";
type Route =
  | { kind: "tab"; tab: TabId; gearTab?: "beans" | "grinders" | "app" }
  | { kind: "recipe"; id: string } | { kind: "recipeEdit"; id: string | null; mode: "new" | "edit" | "duplicate" }
  | { kind: "brew"; recipeId: string } | { kind: "review"; brewUid: string }
  | { kind: "brewDetail"; brewUid: string } | { kind: "onboarding" };
interface HistoryState { v: 1; tab: TabId; tabDepth: 0 | 1; depth: number; from?: SharedFrom }
interface Nav {
  push(path: string, opts?: { from?: SharedFrom }): void;
  replace(path: string): void;
  back(): void;                         // history.back() when depth > 0, else replace with the parent tab
  popToRoot(tab?: TabId): void;         // history.go(-depth), then switch tab if asked
  switchTab(tab: TabId): void;
}
export function useRoute(): { tab: TabId; stack: Route[]; top: Route; from?: SharedFrom };
export function useNav(): Nav;          // stable object; never causes renders
```
Rules:
- Tabs mirror Android's bottom-nav back behaviour: Today ↔ other tab keeps at most one tab entry
  above Today (`tabDepth`), so Back from any tab returns to Today, then leaves the app. Switching
  between two non-Today tabs uses `replaceState`. Gear sub-tabs use `replaceState`.
- Details push entries (`depth + 1`). Cold load of a detail URL synthesises the stack:
  `replaceState(parentTab)` then `pushState(detail)`, so Back stays in the app.
- Finishing a brew: `nav.replace("/review/<uid>")` (a finished timer is never on the stack).
  Review "Done": `nav.back()` when the entry below is `/log/<uid>` of the same brew (opened from
  the detail's Edit), else `nav.popToRoot("today")`. Review "Open in log": `nav.replace("/log/<uid>")`.
  This is the web form of Android's `popUpTo(HOME)` (`CoffeeNav.kt` REVIEW/BREW).
- First-launch gate (in `boot.ts`): if `!settings.onboardingDone && await repo.isEmpty()` →
  `replaceState("/welcome")`. Existing data never sees onboarding (A§12).
- Re-tapping the active tab scrolls its layer to the top (smooth unless reduced motion).
- Keyboard: `1`–`5` switch tabs, `Escape` = back / close sheet (when focus is not in a field).

### 6.3 `TabsHost` — keep-alive tabs, zero render on switch

- One layer per tab: `<section class="tab-layer" data-tab="…">`, `position: fixed; inset: 0;
  overflow-y: auto; overscroll-behavior-y: contain; contain: layout paint;` with the column inside.
  The document body never scrolls (`html, body { overflow: hidden; overscroll-behavior: none }`),
  so viewport coordinates equal page coordinates for layout measurement and pull-to-refresh can
  never reload a running brew.
- The active tab mounts at boot. After first paint, idle callbacks mount the other four (and
  `import()` their chunks), hidden. From then on **all five stay mounted for the life of the
  page**: state, scroll position, chart state and live subscriptions persist.
- Inactive layers: `visibility: hidden; pointer-events: none;` plus `inert` and
  `aria-hidden="true"`. Not `display: none` (loses scroll position and layout) and not `<Activity>`
  (fact 7). Infinite CSS animations inside pause via `[data-active="false"] * { animation-play-state: paused }`.
- `TabLayer` is `memo` and receives only `id` and a ref; the tab's screen element is created once
  and never receives new props, so a switch re-renders **no** tab content. `active` is applied
  imperatively (attribute writes in the transition code), not through React state.
- The switch is imperative (`app/transitions.ts`), started in a `useLayoutEffect` on the route's
  tab, using Motion's `animate(element, keyframes, options)` with **whole `transform` strings and
  `opacity`** so Motion hands them to WAAPI (compositor; fact 5):
  ```ts
  // d = +1 moving right in the bar, -1 left
  outgoing: animate(out, { opacity: [null, 0], transform: [null, `translateX(${-8 * d}px)`] },
                   { opacity: effectsFast, transform: spatialFast })   // then: visibility hidden, clear styles
  incoming: visible first, then
            animate(inc, { opacity: [wasVisible ? null : 0, 1], transform: [wasVisible ? null : `translateX(${20 * d}px)`, "translateX(0px)"] },
                   { opacity: { ...effectsDefault, delay: 0.06 }, transform: { ...spatialDefault, delay: 0.06 } })
  ```
  This is the audit's fade-through: the outgoing page is nearly gone before the incoming one
  becomes visible, so only one page is translucent at a time, distances are 8–20 px (not 1/6 of
  the screen), all values are sub-pixel floats, and nothing overshoots. A tap mid-switch stops the
  running controls and starts from current values (`null` keyframes). Reduced motion: opacity only,
  `effectsFast`, no delay.
- `will-change: transform, opacity` is set on the two layers for the duration of the switch and
  removed on completion.

### 6.4 `DetailHost` — one opaque page at a time

- Renders the detail stack above the tabs inside `<AnimatePresence initial={false}>`, keyed by
  history entry. Each detail is a `position: fixed; inset: 0` opaque scroll layer (same CSS as a
  tab layer) with its own `--bg`.
- Choreography (Android `CoffeeNav.kt` + audit §3.4):
  - default push: `opacity 0→1` (`effectsFast`) + `transform: scale(0.94)→scale(1)` (`spatialDefault`);
    pop is the mirror (`spatialFast`). The tabs underneath do **not** animate; once the push settles
    the tabs container gets `visibility: hidden` (stops painting), and it is made visible again
    synchronously at the start of a pop, before the exit animation runs.
  - `recipe`: no container scale (Android: "a container scale would land the body at a different
    scale from the header flying"). Header flies via `layoutId` (§6.5); body `opacity 0→1` +
    `translateY(12px)→0`, delay 0.08 s.
  - `brew`: `opacity` + `scale(0.96)→1`; exit `scale(0.96)` + fade.
  - `review`: slides up opaque, `translateY(20%)→0` (`spatialDefault`) + fade; the brew below stops
    drawing (its rAF loop checks `document.visibilityState` and an `IntersectionObserver`).
  - `onboarding`, `recipeEdit`: default push.
- Heavy sections in a detail (analysis card, photos, full timeline, journey line) render behind
  **fixed-height placeholders** until the enter animation completes (`useTransitionSettled()` from
  `app/transitionState.ts`, the web form of audit §3.6). Nothing reflows when they arrive.
- `afterTransition(cb)`: non-urgent writes and work triggered by navigation (e.g. `settings.lastRecipeId`,
  prefetching the next chunk) run after the current transition settles, so a Dexie change can
  never re-render a list mid-flight.
- Prefetch: `onPointerDown` on anything that navigates calls the target's `prefetch()` (chunk +
  `repo.warm(uid)` for brew details: photos meta and latest analysis into a tiny cache).

### 6.5 Shared element: recipe card → recipe detail header

- `<LayoutGroup id="coffee">` wraps `TabsHost` and `DetailHost`; the app uses
  `<LazyMotion features={domMax} strict>` with `m.*` components.
- Keys come only from `app/sharedKeys.ts`, same strings as Android `SharedKeys`:
  `recipeHeader(prefix, id) = \`${prefix}/recipe-header/${id}\`` with prefixes `home`, `next`,
  `recipes`. Keep-alive means the Home carousel, the Home next-brew pass and the Recipes list are
  all mounted at once, so the prefix is required, exactly as on Android.
- `RecipeCard` header: `<m.div layoutId={key} layoutDependency={recipe.id} layoutCrossfade={false}
  transition={{ layout: bounds }} style={{ borderRadius: 22 }}>`. `layoutDependency` stops ordinary
  re-renders from measuring; `layoutCrossfade={false}` makes Motion hide the card while the detail
  header leads and promote it back (animating from the header's box) when the header unmounts
  (verified in `motion-dom` `projection/shared/stack.mjs`). `borderRadius` goes in `style` so
  Motion scale-corrects it. Text inside is scaled during the flight (Compose `scaleToBounds`, the
  audit's fix for rank 6).
- Tab and detail scroll containers are `m.div` with `layoutScroll`. Do not use `layoutRoot`.
- The detail header is the same component tree as the card header (`RecipeHeader` in `viz/`),
  sized for the detail, so the flight only scales and translates.
- Nothing else in the app uses `layout`/`layoutId` props except the brew screen's
  setup-drawing → dial morph (MAY, `layoutId="brew-hero"` inside the brew screen only).
- Fallback (MUST be kept working behind `SHARED_ELEMENT = false` in `sharedKeys.ts`): container
  transform without projection — the detail enters with `transform-origin` at the tapped card's
  centre (read once on `pointerdown`), `scale(0.94)→1` + fade. If the flight mis-measures on any
  target browser, flip the flag.
- Reduced motion: the flag is forced off and the detail fades.

### 6.6 Sheets (`CoffeeSheet`)

Native `<dialog>` opened with `showModal()` (focus trap, Escape, inert background for free). Inside:
a scrim `div` (`opacity`, `effectsDefault`) and the panel (`m.div`, `drag="y"`,
`dragConstraints={{ top: 0 }}`, `dragElastic={0.06}`, open `transform: translateY(100%)→0` with
`spatialDefault`; release with `velocity.y > 600` or offset > 30 % closes with `spatialFast`).
Radius `--r-sheet`; full-height variant for editors. The panel scrolls inside itself with
`overscroll-behavior: contain`; dragging starts only from the grabber/header or when the content
is scrolled to the top. Escape and the scrim close it. History: opening a full-height editor sheet
pushes a `#sheet` entry so the system Back closes it.

### 6.7 Bottom bar (`BottomBar.tsx`)

Port of `CoffeeNav.kt` `BottomBar`: floating, 16 px side gutter, 8 px above
`env(safe-area-inset-bottom)`, radius 26, `--bar` background (no `backdrop-filter`: too costly
while scrolling at 120 Hz), hairline border in light mode only, five fixed-width items (icon 22 px +
`.t-label-s` label with 0.2 px tracking). One indicator pill (neutral ink at 10 %/6 % alpha, never
crema) moves with `transform: translateX(px)` and `spatialDefault`; colours change by CSS
transition (paint only, five tiny elements); the selected icon scales to 1.06 with `bounce`. It is
a `<nav aria-label="Sections">` of real `<a href>` links with `aria-current="page"`. A canvas-coloured
fade (`BarScrim`) sits behind it as one fixed layer. Details cover the bar (z-order: tabs 0 <
bar 10 < details 20 < sheets/toasts top layer); the bar never animates out.

### 6.8 Boot sequence (`boot.ts`, `main.tsx`)

1. `boot.js` set theme (before CSS paints).
2. `main.tsx`: open Dexie; on failure (private mode quirks, storage disabled) render a plain page:
   "This browser won't let Coffee save anything here. Try a normal window, or another browser."
3. In parallel: `store.init()` (all tables `toArray()`, settings), `catalog.load(["brewers", "recipes"])`.
4. First-launch gate (§6.2), then `createRoot().render(<App/>)`. First paint has real data.
5. After first paint (idle): mount remaining tabs, load the other catalogs, `navigator.storage.persisted()`
   check, SW registration (§17), `startupTasks()` (catalog linking for imported rows — WEB-5's
   linker, called through a stub until it lands).
6. `db.on("versionchange")` → close and show "Coffee was updated in another tab · Reload".

---

## 7. MOTION AT 120 Hz — rules for every package

At 120 Hz a frame is 8.33 ms for script + style + layout + paint. The rules below are how the
web app stays inside that; reviewers reject code that breaks them.

1. **Only `transform` and `opacity` animate** for anything larger than a chip. Never animate
   `top/left/width/height/margin/padding/inset`, `max-height`, `grid-template-rows`, `box-shadow`,
   `filter: blur()` on a large area, `background-position`, or `stroke-dashoffset` on large SVGs.
   Colour may transition with CSS on small controls (chips, bar labels, toggles): paint only.
   Shadows: fade a pre-rendered shadow layer's opacity instead.
2. **Screen-level transitions run on the compositor**: Motion `animate()` / `m` components with
   `opacity` and whole `transform` strings, no `onUpdate`, no `x`/`y`/`scale` shorthands (fact 5).
   They keep moving even if the main thread hitches, and on browsers that cap `requestAnimationFrame`
   below the display rate (some iOS Safari versions have held rAF at 60 Hz on ProMotion screens —
   verify on the device) composited animations can still run at the panel's rate.
3. **Gesture-coupled motion uses motion values** (`useMotionValue`, `useTransform`, `drag`): sheet
   drag, photo pinch-zoom, the ring's seek glide. Motion writes styles directly; React does not
   render per frame.
4. **No React render per animation frame. Ever.** Timers, the ring, the clock digits, scroll
   effects and drags write to refs, motion values, canvas or CSS variables. React state changes
   only on discrete events (step change, pause, tap). Verify with the React Profiler (§19.3).
5. **Data is there before the motion starts.** Screens read the hot store (§8.3) synchronously;
   detail screens seed from it on the first render; nothing inside a transition awaits IndexedDB.
   Per-detail extras (photos, analyses) sit behind fixed-height placeholders until the transition
   settles (§6.4). Navigation-triggered writes go through `afterTransition`.
6. **Keep mounted, don't remount**: tabs (§6.3); details stay mounted while covered by a sheet;
   `key` changes that remount subtrees are banned for navigation.
7. **Entrances play once.** Stagger/entrance animations run on the first paint of the session
   only (module-level flag) and never on returning to a tab. Charts animate once per session.
8. **No layout thrash.** Read layout (`getBoundingClientRect`, `offsetHeight`, `scrollTop`) only in
   event handlers, `ResizeObserver` and `IntersectionObserver` callbacks, never after a style write
   in the same task. No scroll listeners; use IntersectionObserver or CSS scroll-driven animations.
   All listeners `{ passive: true }` unless they must `preventDefault`.
9. **Long lists**: rows get `content-visibility: auto; contain-intrinsic-size: auto <height>px`
   (Log rows 68, recipe cards 156, picker rows 56, Insights sections their placeholder height).
   Do not put it on an element that carries a `layoutId`. Over 1,500 brews, Log paginates by month
   ("Show earlier months").
10. **Infinite animations** (steam, mesh drift, ring wave and pulse) are CSS keyframes on
    `transform`/`opacity` or a canvas drawn only while visible; paused when their tab is inactive,
    when `document.hidden`, and absent under reduced motion.
11. **Work > 4 ms leaves the main thread or yields**: photo decode/encode (worker), backup
    parse/encode (worker), JSON over 1 MB (worker). Loops over all brews in derivations yield with
    `scheduler.yield()` (fallback `MessageChannel`) when over 4 ms, or move to a worker if the
    §19.3 trace shows them during a transition.
12. **Input stays urgent**: filtering and search results use `startTransition` / `useDeferredValue`;
    the query field itself updates synchronously.
13. **Reduced motion is honoured** three ways: `<MotionConfig reducedMotion={pref === "reduced" ? "always" : "user"}>`,
    the imperative transitions read the same preference (opacity-only), and CSS
    `@media (prefers-reduced-motion: reduce)` plus `[data-motion="reduced"]` disable keyframes.
    Gear › App › Appearance has "Motion: Match system / Reduce motion" (`settings.motion`).
14. **Don't** use longer durations to hide jank, `backdrop-filter` on scrolling surfaces,
    `will-change` permanently on more than a handful of elements (bar pill, ring canvas, mesh
    blobs), or `transform` on `<html>`/`<body>`.

---

## 8. Data layer (WEB-1, `src/data/`)

### 8.1 Dexie schema (`db.ts`) — Room v4 mirrored

Table names and columns mirror Room v4 (A§4.2) so domain code ports field for field. Two
deliberate differences, both because the web has no 1.x rows: enum-typed columns store the
**backup v2 id strings** (`flowFeel: "on-target"`, `bedShape: "high-and-dry"`, `null` = not
recorded) instead of Kotlin enum names; `taste` is a nested object instead of `taste_*` columns.

```ts
db.version(1).stores({
  beans:         "++id, &uid, updatedAt",
  grinders:      "++id, &uid, catalogId",
  brew_logs:     "++id, &uid, startedAt, beanId, grinderId, recipeId, updatedAt",
  saved_recipes: "id, updatedAt",
  analyses:      "++id, &uid, brewLogId, [kind+createdAt]",
  photos:        "++id, &uid, brewLogId, beanId",
  photo_blobs:   "uid",                 // web only: { uid, full: Blob, thumb: Blob }
  kv:            "key",                 // web only: settings, ai prefs, activeBrew, flags
});
```
Booleans are not valid IndexedDB keys, so `isArchived`, `isDefault`, `isFavourite` are filtered in
memory (tables are small).

Row types (`schema.ts`; every field present, defaults as Room):
- `BeanRow`: `id, uid, name, roaster, roasterId|null, origin, originId|null, region, farm, producer,
  washingStation, lotTypeId|null, speciesId|null, varietalIds: string[], varietal, processId ("" = not recorded),
  isDecaf, decafMethodId|null, roastLevelId (""), roastIntentId|null, roastedOnEpochDay|null,
  openedOnEpochDay|null, bagSizeG|null, price (1.x text), priceAmount|null, currency, altitudeMasl,
  harvest, restDays|null, tastingNotes, notes, isArchived, createdAt, updatedAt`.
- `GrinderRow`: `id, uid, brand, model, burrType ("conical"|"flat"|"ghost"|"blade"|null), burrSizeMm (string),
  settingStyle ("clicks"|"numbers"|"stepless"|"microns"), clicksPerRotation (0 = unknown),
  micronsPerClick|null, lowerIsFiner, minSetting, maxSetting, step, defaultFilterSetting|null,
  defaultEspressoSetting|null, notes, isDefault, isArchived, catalogId|null, dialFormat (""), updatedAt`.
- `BrewRow`: `id, uid, startedAt, updatedAt, recipeId, recipeName, recipeAuthor, brewer (label snapshot),
  brewerId|null, beanId|null, beanLabel, daysOffRoast|null, grinderId|null, grinderLabel, grindSetting,
  grindClicks|null, grindValue|null, doseG, waterG, tempC, waterId|null, totalTimeSec, drawdownSec|null,
  beverageG|null, tdsPercent|null, extractionYield|null, flowFeel|null, bedShape|null, sludge|null,
  channeling|null, bedNotes, taste: {acidity, sweetness, bitterness, body, balance, aftertaste, clarity} (default 5s),
  overall (0 = not rated), descriptors: string[], faults: string[], notes, nextTime, isFavourite, sharedAt|null`.
- `SavedRecipeRow`: `{ id, recipe: RecipeJson, updatedAt }` (Room stores a JSON string; IndexedDB
  stores the object; `recipe.isBuiltIn = false`, as Android's `saveRecipe`).
- `AnalysisRow`: `id, uid, kind ("brew"|"trend"), brewLogId|null, createdAt, promptVersion, inputHash,
  brewCount, newestBrewAt, model, host, rawText, result (parsed object|null), flags (DataFlag[]),
  promptTokens|null, completionTokens|null, costMicroUsd|null, latencyMs, providerId, photoCount,
  origin ("web" for rows made here)`.
- `PhotoRow`: `id, uid, brewLogId|null, beanId|null, kind ("bed"|"cup"|"bag"|"other"), mediaType ("image/jpeg"),
  widthPx, heightPx, bytes, takenAt, createdAt, caption, sendToAi`.

Dates: `*EpochDay` = days since 1970-01-01 of the **local calendar date**
(`Date.UTC(y, m, d) / 86_400_000`), matching Kotlin `LocalDate.toEpochDay()`. Timestamps are epoch ms.
Uids: `crypto.randomUUID()`.

### 8.2 Repository (`repo.ts`)

All writes go through `repo`; it stamps `updatedAt = Date.now()` on beans, grinders, brews and
saved recipes, and replaces Room's foreign-key cascades with explicit transactions:
`deleteBrew(id)` deletes the brew, its analyses, its photos and their `photo_blobs` in one
`db.transaction("rw", …)`; `deleteBean(id)` deletes the bean, its photos and blobs (brews keep
`beanId` and their `beanLabel` snapshot, as Room). API:
`insertBrew, updateBrew, deleteBrew, toggleFavourite, upsertBean, deleteBean, upsertGrinder,
setDefaultGrinder, archiveGrinder, saveRecipe, resetRecipe, deleteRecipe, addPhoto(meta, full, thumb),
updatePhoto, deletePhoto, photosForBrew, photosForBean, photoBlob(uid, "full"|"thumb"),
insertAnalysis, analysesForBrew, latestTrend, isEmpty, warm(brewUid), wipeAll()`.
Recipe merge = Android `CoffeeRepository.recipes`: built-ins in catalog order with a saved row of
the same id replacing the original, then saved recipes whose ids are not built-in.

### 8.3 Hot store (`store.ts`) — data before the switch

One app-scoped store holds immutable snapshots of `beans`, `grinders`, `brews` (newest first),
`savedRecipes`, the merged `recipes`, `settings` and `aiPrefs`. `store.init()` loads them before
first render; afterwards one Dexie `liveQuery` per table keeps them current (Dexie also
propagates changes made in other browser tabs). Hooks use `useSyncExternalStore` with selectors
and equality, so a component re-renders only when its slice changes:
`useSettings(), useBeans({ includeArchived }), useGrinders(…), useBrews(), useBrew(uid),
useRecipes(), useRecipe(id)` (synchronous; the detail's first frame has the recipe, as Android's
`RecipeDetailViewModel`), `useActiveBrew()`, `getSnapshot()`.
Derived models (`selectors.ts`) are memoised by input identity; formatters (`Intl.DateTimeFormat`,
`Intl.NumberFormat`) are module-level constants, never created during render (audit rank 10).
Per-detail data that is not in the store (photos for a brew, analyses) uses `useLiveQuery` inside
the placeholder-gated sections only.

### 8.4 Settings (`settings.ts`, kv key `settings`)

```ts
interface Settings {           // Android Settings + A§5, same defaults
  lastRecipeId: string; defaultBeanId: number; defaultGrinderId: number;     // local ids, 0 = none
  haptics: boolean; soundCues: boolean; keepScreenOn: boolean; autoAdvanceSteps: boolean;  // all true
  darkMode: "system" | "light" | "dark"; name: string;
  tempUnit: "c" | "f"; weightUnit: "g" | "oz";
  onboardingDone: boolean; onboardingAuto: boolean; personaliseDismissed: boolean;
  ownedBrewerIds: string[]; favouriteRoasterIds: string[]; homeArea: string; defaultWaterId: string;
  pendingNotices: string[]; startupTasksVersion: number;
  motion: "system" | "reduced";                 // web only (§7 rule 13)
}
```
`settings.set(key, value)`, `addNotice(id)`, `removeNotice(id)`. `darkMode` and `motion` are
mirrored to localStorage by `prefsMirror.ts` for `boot.js`.

### 8.5 Storage durability (`storage.ts`)

- After the first saved brew or first photo, call `navigator.storage.persist()` once (Chromium
  grants silently for installed/engaged sites; Firefox may prompt; Safari may ignore).
- `storageStatus()`: `navigator.storage.estimate()` + `persisted()` for Gear › App › YOUR DATA.
- Safari can delete all script-written storage for a site not opened for 7 days unless it is
  added to the Home Screen. Copy (shown on Safari when not standalone and not persisted):
  "Safari can clear this site's data if you don't open it for a week. Add Coffee to your Home
  Screen, or save a backup now and then."
- Backup nudge (Today, dismissible, once per 30 days) when there are ≥ 10 brews, no backup saved
  in 30 days, and storage is not persisted.

### 8.6 Dev seed

`data/devSeed.ts`, imported only under `import.meta.env.DEV`: "Seed 1,000 brews" and "Wipe" rows
in Gear › App (dev builds only) for performance work. Keep a 10-click K-Ultra fixture like
Android's `DebugTools`.

---

## 9. Catalog and recipes (WEB-1, `src/catalog/`)

- `types.ts`: TS types 1:1 with the schemas in the brief and A§2.3 DTOs (same field names and
  defaults, e.g. `popularity = 3`, `confidence = "low"`, `browserCors = "unknown"`).
- `normalise.ts`: each file's list is read entry by entry with hand-written guards; a bad entry is
  skipped and recorded in `catalog.problems` as `"catalog/grinders.json[12] (id=…): <reason>"`;
  a missing file yields an empty list plus a problem line. The app never crashes on catalog data.
- `catalog.ts`: `grinders, roasters, origins, beans, brewers, water, providers, recipes, principles,
  problems` plus `grinder(id), roaster(id), origin(id), brewer(id), water(id), provider(id),
  recipe(id), process(id), varietal(id), label(kind, id)` — the same API as Android's `Catalog`
  (A§2.3). Lists are frozen arrays; lookups are lazily built `Map`s.
- Brewers are domain `Brewer`s (`domain/core/brewer.ts`, port of A§2.5 with `BrewMethod`,
  `BrewerArt`, `shortName`, `family`, `Brewers.resolve` via `LegacyIds`, and the
  `FALLBACK_BREWERS` appended for any legacy id missing from the file).
- Recipes: `shared/recipes.json` → domain `Recipe` (brewer id resolved, `accent` "#RRGGBB" or
  "#AARRGGBB" or a legacy number accepted, `fidelity`, `sources`, `basedOn`). The 14
  `LEGACY_IDS` (A§2.6) resolve forever.
- `GrindScale` (`domain/grind/`) is a port of A§3.2–3.4 including `NumberedDials.IDS` (copy the
  final Kotlin set; never add an id on a guess) and the A§3.2 test vectors.

---

## 10. Domain ports (pure TypeScript, tested)

| web file | port of | owner | must-pass tests |
|---|---|---|---|
| `domain/core/format.ts` | `core/Format.kt` (`grams`, `oneDp` pinned to `.`, `ratio`, `clock`, `dayLabel`, `timeLabel`, `greeting`, `daysOffRoastLabel`) | WEB-1 | values for 18, 18.5, 16.666 ratio, dayLabel today/yesterday |
| `domain/core/brewer.ts`, `legacyIds.ts` | A§2.4–2.5 | WEB-1 | every legacy map value is a shipped brewer id (as Android `LegacyIdsTest`) |
| `domain/core/recipe.ts` | `domain/Recipe.kt` + `waterAtF`, `stepBoundaries` (`BrewViewModel.kt`) | WEB-1 | A§2.8 golden numbers (`hoffmann-v60` 30/500/100/210 s/6 steps; `kasuya-46` 20/300/92/210 s) when recipes.json exists; scaling; contiguous steps |
| `domain/core/evaluation.ts` | `domain/Evaluation.kt` (bed/sludge/channelling/flow with labels and readings, taste axes, descriptors, `BrewMath`) | WEB-1 | extraction yield, retention |
| `domain/core/status.ts` | pure mappers in `ui/components/Status.kt` | WEB-1 | `scoreStatus` bands, `timeStatus` ±15/±45 s |
| `domain/core/units.ts` | A§7.8 `Units` | WEB-1 | °F rounding, oz to 1 dp |
| `domain/core/quickRead.ts` | `BrewReport.quickRead` | WEB-1 | same lines as Kotlin for 3 fixtures |
| `domain/grind/*` | A§3.1–3.5 (`DialFormat`, `GrindScale`, `MoveSizes`, `grindLooksTyped`) | WEB-1 | the A§3.2 vector table verbatim; A§3.3 K-Ultra 2/6/13/25 |
| `domain/ai/*` | `domain/ai/*.kt` + A§8 (providers, AnalysisPrompt v2, parser incl. `grindValueFrom/To`, `GrindMove`, `GrindValueMove`, `DataQuality` incl. `GRIND_TOO_FINE_FOR_METHOD`, `AiError`, `BaseUrl`, `ModelCatalog` price/format) | WEB-3 | A§8.8 cases that apply to the web (wire shapes, prompt blocks, parser) |
| `domain/insights/*` | `ui/screens/insights/PassportStats.kt`, chart models from `InsightsCharts.kt` | WEB-3 | passport totals, streaks, best cup, grind series on a fixture |
| `domain/report/*` | `BrewReport.markdown`, trend table (A§8.7) | WEB-3 | snapshot of one report |
| `domain/search/*` | A§7.1 `CatalogSearch` | WEB-4 | every A§7.1 case whose target id is in the shipped catalog |
| `domain/editor/*` | A§7.7 `RecipeValidation` + draft ↔ recipe (cumulative start/end) | WEB-4 | each rule, the one-tap last-pour fix |
| `domain/backup/*` | A§10 | WEB-5 | §15.5 |

Prompt parity is semantic, not byte-for-byte (Kotlin and JS print numbers differently). An
analysis's `inputHash` is only compared with prompts built on the same platform; rows from the
other platform show the "Made on another device" stale reason (A§8.6, with `origin != "web"`).
The hash covers the data blocks only (not the principles, the °F line or the reply shape) and
names records by uid, so it is the same after a restore; trend chips are found through
`result.brewUids`, and a trend without that key reads its numbers as row ids only when its
origin is web. The checks read a deleted coffee or grinder as none, and the hash names the
brew's eligible photos whatever the send setting (A§8.6, `shared/schema/backup-v2.md`).

---

## 11. Screens — parity list

Every screen ports the named Android files' layout, copy and behaviour, in the same order of
sections. "New in 2.0" items follow the Android spec section given.

| screen | Android source | sections / behaviour | web notes | owner |
|---|---|---|---|---|
| **Today** | `screens/home/HomeScreen.kt`, `HomeViewModel.kt` | playful title (greeting + name), headline line (status dot + word), HomeMug (fills with today's brews, steam), NEXT BREW pass (last recipe → else first; prefer owned brewer families, A§7.8), stats strip (today / 7-day / last brew), START FROM carousel, recent brews (LogRows), empty state; Personalise card (A§7.8); "Brew in progress · 2:31" banner when `activeBrew` exists (§12.5); backup nudge (§8.5) | carousel is a horizontal scroller with `scroll-snap-type: x mandatory`; cards are shared-element sources with prefixes `home` and `next` | WEB-2 |
| **Recipes** | `screens/recipes/RecipeListScreen.kt`, `RecipesViewModel.kt`, `components/RecipeCard.kt` | header eyebrow, filter chips (Mine, My brewers default on when owned set non-empty, then brewers present), recipe cards, "+" → `/recipes/new` | filter changes in `startTransition`; prefix `recipes` | WEB-2 |
| **Recipe detail** | `screens/recipes/RecipeDetailScreen.kt` | shared header (accent, name, author, brewer art), numbers grid (dose, water, ratio, temp in units, grind words), journey preview, dose scaler, steps timeline, prep, tips, source note + `sources` links, fidelity word (`StatusChip(Neutral, …)`: "Published numbers" / "Adapted" / "Community" / "Yours"), `GrindRangeCard` for the default grinder and the recipe's method (A§12), actions Edit / Duplicate / Reset to original / Delete (custom), primary **Brew** (crema) | everything below the header is placeholder-gated until the flight lands (§6.4) | WEB-2 |
| **Brew** | `screens/brew/BrewScreen.kt`, `BrewViewModel.kt`, `components/BrewRing.kt`, `MeshGradient.kt`, `CoffeeArt.kt` (Dripper) | Setup pane: DoseCard (rescales water and pours), GrindCard (`ClickDial`: big digit, ±1, ±5 chips, caption `GrindScale.describe`; or `DialReadingInput` toggle; `GrindStartHint`), TempCard (units), CoffeeCard (bean + grinder), Start. Running pane: mesh by mood, BrewRing with clock, step card (title, detail, countdown, water readout), controls (play/pause, −5 s / +5 s, skip, "Bed exposed", Finish), overrun leaf. Finish writes the brew (A§2.5 `finish()` fields incl. `brewerId`, `waterId`, `grindValue`) and replaces the route with Review | §12 | WEB-2 |
| **Review** | `screens/review/ReviewScreen.kt`, `ReviewViewModel.kt` | numbers header, DRAWDOWN (time, beverage, flow chips, TDS → EY), THE BED (8 shape tiles with readings, surface, channelling, notes), **PHOTOS** (A§9.2: bed/cup, copy "A photo of the bed says more than any shape on this list."), TASTE (7 `AxisSlider`s, descriptors, faults), OVERALL, NOTES (notes, next time), FIRST READ · OFFLINE (`quickRead`), grind field with `DialReadingInput` toggle; autosave on change (debounced 400 ms, `afterTransition`) | | WEB-2 |
| **Log** | `screens/history/HistoryScreen.kt`, `HistoryViewModel.kt` | summary, day-grouped `LogRow`s, empty state | `content-visibility` rows; month pagination over 1,500 | WEB-3 |
| **Brew detail** | `screens/history/BrewDetailScreen.kt`, `TasteStrip.kt` | boarding pass, journey (planned vs actual), THE BREW cells, THE BED, photo strip (A§9.2; hidden when empty with "Add photo"), taste strip, notes, AI analysis section (§14), actions: Edit (→ Review), Share report (Web Share API, else copy), favourite, Delete (confirm) | heavy sections placeholder-gated | WEB-3 |
| **Insights** | `screens/insights/*` | BREW PASSPORT (totals, litres, grams, streaks, stamps), PERSONAL BESTS, SCORE · LAST N (bars), GRIND journey for the most-brewed recipe with best-scoring range (dial words via `GrindScale`), MOST BREWED recipes/coffees, AI pattern finder (`TrendAnalysisSection`), SHARE & EXPORT (Last brew report, History table, Save backup (JSON)), WHAT LEAVES THIS BROWSER (§14.8) | charts are inline SVG from pure models; bars `scaleY` from the baseline, line fades; once per session; `role="img"` + an `aria-label` summary + a visually hidden list of the values | WEB-3 |
| **Gear** | `screens/gear/*` | `SegmentedControl` Beans / Grinders / App. Beans: cards (roaster, origin + region, process, roast, rest status A§7.6). Grinders: cards with one-line `GrindRangeCard`, scale notice (A§7.4: "Check the scale" with Use these numbers / Keep mine). App: YOU (units, area, brewers owned, water, Run setup again), APPEARANCE (theme, motion), WHILE BREWING (vibration, sound cues, keep screen on, auto-advance — each with "Not supported in this browser" when the API is missing), AI ANALYSIS (`AiSettingsSection`), YOUR DATA (`BackupSection`), ABOUT THE RECIPES, ABOUT (version, source code link, "Get the Android app" link, Install Coffee) | editors are full-height sheets | WEB-4 |
| **Bean editor** | A§7.6 | six sections, "More details" disclosure, bag photo (WEB-2 `PhotoStrip`, kind bag) | disclosure: content fades, space opens instantly (rule 1) | WEB-4 |
| **Grinder editor** | A§7.4 | picker fill, scale summary + "Edit the scale", dial reading help, `GrindRangeCard`, usual filter/espresso with `DialReadingInput` | | WEB-4 |
| **Catalog picker** | A§7.2 | sheet, pinned search, sticky group headers, custom entry, multi-select | search debounced 80 ms, results via `useDeferredValue` | WEB-4 |
| **Onboarding** | A§7.3 | 5 steps, Skip, Back, progress dots, mesh background; each Next persists | step changes: `opacity` + `translateX(±24px)`, `spatialDefault` | WEB-4 |
| **Recipe builder** | A§7.7 | New / Edit / Duplicate, steps editor, validation, live journey preview | | WEB-4 |

Install entry (Gear › App › ABOUT): on Chromium, capture `beforeinstallprompt` and show
"Install Coffee"; on iOS Safari, show "Share › Add to Home Screen"; hide when
`matchMedia("(display-mode: standalone)")` matches.

---

## 12. Brew timer (WEB-2, `features/brew/`)

### 12.1 Engine (`engine.ts`, framework-free, fully unit-tested with a fake clock)

The web form of Android's `TimerAnchor` + `BrewViewModel`:
```ts
interface Anchor { accumulatedMs: number; startedAtPerf: number; startedAtWall: number; running: boolean }
const elapsedAt = (a: Anchor, nowPerf: number) => a.running ? a.accumulatedMs + Math.max(0, nowPerf - a.startedAtPerf) : a.accumulatedMs;
class BrewEngine {
  constructor(recipe: Recipe, clock: { perf(): number; wall(): number } = realClock);
  start(); pause(); resume(); toggle(); addSeconds(d: number); skipToNextStep(); markBedExposed(); reset();
  finish(): BrewDraft;                       // totalTimeSec, drawdownSec = bedExposedSec − lastPourEndSec (≥ 0)
  elapsedMs(): number;                       // read by the ring/clock loop; never stored in React state
  subscribe(fn: (s: EngineState) => void);   // fires only on discrete changes: running, stepIndex, done, bedExposed
}
```
- Elapsed is always derived from `performance.now()` anchors; nothing accumulates per tick.
- Step detection runs in the rAF loop while visible (a comparison per frame) and from a
  `setTimeout` scheduled at the next step boundary while hidden. Crossing a boundary updates
  `stepIndex` once and fires the cue (§12.3). `lastPourEndSec` follows Android's `onTick`.
- `performance.now()` may not advance while a device sleeps. On `visibilitychange` → visible and
  on `pageshow`, compare wall-clock elapsed (`Date.now() − startedAtWall + accumulatedAtStart`)
  with perf elapsed; if they differ by more than 1 s, re-anchor to the wall clock. This keeps a
  12-hour cold brew honest.
- `autoAdvanceSteps` off: the step card waits for a tap to advance (as Android).

### 12.2 Rendering

- `RunningPane` renders on `EngineState` changes only. The clock digits are a leaf component that
  writes `textContent` from the rAF loop when the shown second changes (`.num-clock`, or
  `.num-clock-compact` when the recipe is 10:00 or longer). `role="timer"`, `aria-live="off"`.
- `viz/BrewRing.tsx`: a `<canvas>` sized by `ResizeObserver` × `devicePixelRatio`, drawn in a rAF
  loop from `engine.elapsedMs()` — port of `BrewRing.kt`: track, step ticks that cross-fade as the
  head passes, progress arc from 12 o'clock, glowing head with pour pulse (pulse amount springs with
  `heavy`), liquid disc with a sine surface (`waterAtF`, min level 0.12, fades in over the first
  6 %). Paths/gradients are created once per size/colour change (the `drawWithCache` rule); no
  allocation per frame. Loop stops when hidden or off-screen. Reduced motion: no wave, no pulse.
- Seeks (±5 s, skip): the clock snaps; the ring glides through a seek offset motion value animated
  to 0 with `settle` and read by the draw loop (Android's `seekMs`).
- `viz/MeshGradient.tsx`: two large radial-gradient blobs on `--mood-*` colours; drift is a CSS
  keyframe on `transform` (compositor); a mood change cross-fades a second layer's `opacity`
  (`effectsSlow`). The page's `theme-color` follows the mood base.

### 12.3 Cues

- Vibration: `navigator.vibrate?.(30)` on a step change when `haptics` is on and the page is
  visible. Feature-detected; iOS has no Vibration API, so Gear shows "Not supported in this browser".
- Sound (`cues.ts`): when `soundCues` is on, an `AudioContext` created on the Start tap schedules a
  soft two-tone 80 ms cue at each future step boundary on the audio clock
  (`ctx.currentTime + secondsToBoundary`), so cues stay on time while the tab is in the background.
  Rescheduled on pause, resume and seek. Volume −18 dBFS. (Android has the setting without a sound;
  the web needs it because iOS cannot vibrate. Open question 9.)
- Step changes are also announced in an `aria-live="polite"` region ("Pour to 300 g").

### 12.4 Screen Wake Lock

`wakeLock.ts`: when `keepScreenOn`, request `navigator.wakeLock.request("screen")` on Start/Resume,
re-request on `visibilitychange` → visible (the lock is released when hidden), release on pause
over 60 s, finish, reset and leaving the screen. Feature-detected; failures are silent except a
one-line note in the setup pane: "Your browser may dim the screen during a brew."

### 12.5 Surviving a reload

On start/pause/resume/seek/step/bed-exposed, write kv `activeBrew` = `{ recipeId, startedAtWall,
accumulatedMs, running, setup: {doseG, beanId, grinderId, grindClicks, grindSetting, tempC},
stepIndex, lastPourEndSec, bedExposedSec }`. Opening `/brew/:id` with a matching `activeBrew`
resumes from the wall clock; opening another recipe asks "Finish the brew you started at 08:14
first?" (Resume / Discard). Today shows the in-progress banner. Cleared on finish and reset.

---

## 13. Photos (WEB-2, `features/photos/`)

- Capture: two hidden inputs — `<input type="file" accept="image/*" capture="environment">`
  ("Take photo") and `<input type="file" accept="image/*" multiple>` ("Choose from library");
  then kind chips Bed / Cup / Bag / Other (A§9.2). Desktop also accepts paste and drag-and-drop.
  No `getUserMedia`, so no camera permission (Permissions-Policy keeps `camera=()`).
- Pipeline (`photo.worker.ts`, module worker; main-thread fallback with `HTMLCanvasElement` when
  `OffscreenCanvas` is missing): refuse inputs over 40 MB; `createImageBitmap(file, { imageOrientation: "from-image" })`;
  long edge ≤ 1024 px (halve repeatedly while the ratio is over 2, then one final
  `drawImage` with `imageSmoothingQuality = "high"`); `convertToBlob({ type: "image/jpeg", quality: 0.82 })`;
  a 256 px thumbnail the same way; `bitmap.close()`. Re-encoding drops all EXIF (location,
  camera). `takenAt` = JPEG EXIF `DateTimeOriginal` read by `exifDate.ts` (a ~80-line APP1 reader,
  unit-tested) before re-encoding, else `file.lastModified`, else now. Decode failure (e.g. HEIC in
  desktop Chrome): "This photo's format can't be opened in this browser. Try a JPEG or PNG, or
  take the photo from here."
- Storage: `repo.addPhoto(meta, full, thumb)` (row + `photo_blobs` in one transaction). Limits as
  Android: 6 per brew, 3 per bean ("That's the most for one brew").
- Display: `urlCache.ts` LRU of object URLs (64 entries, revoked on eviction and unmount); `<img
  decoding="async" loading="lazy" width height>`; `alt` = "Bed photo, 08:14" (+ caption).
- `PhotoStrip({ brewId?, beanId?, kinds, editable })`: 72 px rounded tiles with kind label, add tile.
  `PhotoViewer`: full-screen `<dialog>`, pinch-zoom/pan with pointer events into motion values
  (`transform` only), kind chips, caption, "Send with analyses" toggle, Delete (confirm "Delete
  this photo? It can't be undone.").
- AI hook `photoAttachmentsForBrew(brewId): Promise<AiImage[]>` (A§9.3): bed/cup photos with
  `sendToAi`, newest 3, base64 of the stored full JPEG via `FileReader.readAsDataURL`; drop the
  oldest while total base64 exceeds 4 MB; label "bed, 08:14".

---

## 14. AI: "add your key" from the browser (WEB-3, `features/ai/`, `domain/ai/`)

### 14.1 Providers on the web

`domain/ai/providers.ts` builds `AiProvider`s from `providers.json` (A§8.1 shape) **including**
`ollama` and `lm-studio` (Android leaves them out). Each carries `browserCors`:
- `yes` — normal.
- `no` — row shows `StatusChip(Attention, "Needs setup")`; selecting it shows the steps:
  Ollama: "Start Ollama with OLLAMA_ORIGINS set to {location.origin}, then restart it."
  LM Studio: "Turn on Enable CORS in LM Studio's server settings." Both: "Your browser may ask to
  let this site reach devices on your local network. This works in Chrome and Edge on the same
  computer; Safari may block it." Links: the URLs in `corsEvidence` / `notes`.
- `unknown` (custom) — "Test connection" is required before Analyse is enabled; a CORS failure
  explains itself (§14.3).
Custom base URL: `https://` any host, or `http://localhost:<port>` / `http://127.0.0.1:<port>`;
anything else: "Browsers block plain http:// from this site. Use https://, or run the model on
this computer (localhost)." Otherwise `BaseUrl.check` rules from Kotlin (paste suffix stripping
incl. `/messages`).

### 14.2 Keys (`keyStore.ts`) — the only code that touches key storage

- Entry: `coffee.ai.key.<providerId>` = `{"v":1,"host":"api.anthropic.com","key":"…","hint":"…7f3a","savedAt":…}`.
- "Remember on this device" checkbox under the key field, **unticked by default**. Unticked →
  `sessionStorage` (gone when the tab closes); ticked → `localStorage`. Saving removes the entry
  from the other storage. "Remove key" deletes both.
- A key is bound to the host it was saved for; if the provider's (or custom) host changes, it is
  not sent and the row reads "Key for another address" (Android semantics).
- Never in IndexedDB, never in a backup, never in a URL, never in an error message, never
  logged. Provider error text is scrubbed of the exact key string before display. Lint enforces
  `no-console` here (§4.3).
- `aiPrefs` (kv `ai`, non-secret): `{ layout: 2, activeProvider: "", sendPhotos: true,
  providers: { [id]: { model, baseUrl?, vision?: boolean | null, testOkAt?, testSig? } } }`
  (A§8.2 minus the key fields). Fresh state: no provider chosen.

### 14.3 Transport (`client/transport.ts`)

`fetch(url, { method, headers, body, signal, mode: "cors", credentials: "omit",
referrerPolicy: "no-referrer", redirect: "error", cache: "no-store" })`.
- Headers: auth (`Authorization: Bearer <key>` or `x-api-key: <key>`), `content-type:
  application/json`, catalog `extraHeaders`, then `browserHeaders` (web only). Both maps pass the
  A§8.1 allow-list (name `^[A-Za-z0-9-]{1,64}$`, not `authorization|x-api-key|cookie|host|content-length|content-type`,
  printable ASCII value ≤ 256).
- Timeouts: `AbortSignal.any([userCancel, AbortSignal.timeout(90_000)])` (a 6-line fallback where
  `AbortSignal.any` is missing). Test connection 20 s.
- Reply capped at 1 MiB by reading the body stream; more → `AiError.TooLarge`.
- Errors mapped like Kotlin `AiError`/`statusError` (401/403/404/408/413/429/5xx, 529 →
  ServerError). A `TypeError` with no response is either offline or CORS: if `navigator.onLine`
  is false → "You're offline."; else "Couldn't reach {host}. If this is your own server, it has to
  allow requests from {location.origin} (CORS)."
- No custom `User-Agent` (browsers forbid it); the app adds nothing that identifies the user.

### 14.4 Clients

- `openaiChat.ts` — A§8.3 `OpenAiCompatClient`: content as a string without images, else parts
  `[{type:"text"}, {type:"image_url", image_url:{url:"data:image/jpeg;base64,…"}}]`;
  `max_completion_tokens` for provider `openai`, `max_tokens` otherwise, with the one free retry on
  a 400 naming the other field; the temperature retry; `supported_endpoints` filter and
  CommandCode's keyless `/models`; OpenRouter's public `/models` MAY show live prices.
- `anthropicMessages.ts` — A§8.3 `AnthropicClient`: `POST {base}/messages`, headers `x-api-key`,
  `content-type`, catalog `anthropic-version` (fallback `2023-06-01`) and the browser header;
  `system`, `max_tokens`, `messages[0].content` = text block + base64 image blocks; reply joins
  `text` blocks (skip `thinking`/`redacted_thinking`); `stop_reason` `max_tokens` → Truncated,
  `refusal` → Filtered; usage `input_tokens`/`output_tokens`; `/models` lists `data[].id`,
  `display_name`.
- Both implement `ChatClient` (`complete`, `testConnection`, `listModels`) exactly as A§8.3.

### 14.5 Prompt, parsing, storage

Port `AnalysisPrompt` v2 (A§8.5) with web inputs: brewer, water, photos meta, units, principles
(filtered to the method, ≤ 10, from `@shared/principles.json` when present), grinder block from
`GrindScale` (no `grindSetting` text for click grinders), coffee block with the new bean fields.
Parse with the ported `AnalysisParser`; store `AnalysisRow` with `origin: "web"`, `providerId`,
`photoCount`, cost from `ModelCatalog.price(providerId, model)` ("prices as of {pricesCheckedOn}").
Stale reasons as A§8.6 with the web wording: `Edited` → "Brew edited since · re-run for a fresh
read"; `OlderPrompt` → "Made by an older version of the analysis · re-run to use your grinder's
scale"; `OtherDevice` (`origin !== "web"`) → "Made on another device · re-run here for a fresh read".
Photos are attached only for a brew analysis, when `sendPhotos` and the model sees images
(catalog `vision`, else the user's override); estimate adds ≈ 1,100 prompt tokens per image (app
heuristic).

### 14.6 UI

- `AiSettingsSection()` (rendered by Gear › App) and `AiSettingsSheet({ onClose })` — A§8.4 layout:
  headline ("Ready · Claude Haiku 5.5 via Anthropic"), provider list with status words ("Key saved"
  Good, "Key for another address" Attention, "Needs setup" Attention, "No key" Neutral), selected
  provider: "Get a key" (`keyUrl`, new tab, `rel="noopener noreferrer"`), `keyHint`, key field
  (`type="password"`, `autocomplete="off"`, `spellcheck=false`), Remember checkbox, Save and test;
  model list (label, price per 1M, "Sees photos" chip, default badge, `why`), Browse all, "Use
  another model id", vision override toggle; "Send photos with analyses".
- `BrewAnalysisSection({ brewId })`, `FirstReadSection({ brewId })`, `TrendAnalysisSection()`,
  `AnalysisCard` — ports of `ui/ai/*.kt` (verdict headline, the one change as hero —
  "75 → 69 · finer" / "14 → 13 · finer", findings, flags, cost/latency/provider footer,
  "· 2 photos"), running state with the coffee drawing, cancel.

### 14.7 Key-panel copy (MUST stay literally true)

- Unticked: "Your key stays in this tab and is forgotten when you close it. It's sent only to
  {host}, in the request header, when you run an analysis, test the connection or list models."
- Ticked: "Your key is saved in this browser for this site until you remove it, and sent only to
  {host} in the request header. Anyone using this browser profile, and any extension allowed on
  this site, could read it. Don't tick this on a shared computer."
- Always: "Coffee has no server, so your key never comes to us. Keys are never put in backups."

### 14.8 WHAT LEAVES THIS BROWSER (Insights)

"Coffee on the web is a set of files served by Vercel. Once loaded it runs in your browser and
keeps everything here, in this browser's storage; Vercel sees requests for those files like any
website. Recipe pictures load from YouTube's image server, which sees those requests like any
website. Otherwise it goes online only to load or update itself, and when you tap a button that runs an
analysis, tests a connection or lists models — and then only to {provider} at {host}, the
service you chose in Gear › App › AI analysis. An analysis sends the brews it reads — recipe,
coffee details, grinder and its scale, brewer, water, times, bed, taste and your notes — and, if
the model can see images and you left them ticked, up to 3 photos of that brew. Photos are
re-saved without location or camera details and leave this browser only in an analysis or a
backup you save. Backups never include your key." Not-configured variant: drop the provider
sentence and say "No AI service is set up, so nothing you log leaves this browser."
No analytics, telemetry or error reporting exists to contradict this; adding any later requires
rewriting this copy first.

---

## 15. Backup v2 on the web (WEB-5, `domain/backup/`, `features/backup/`)

### 15.1 Format

Exactly A§10.1 (`shared/schema/backup-v2.md` once WP-5 writes it). The web writes
`"app": {"platform": "web", "appVersion": "2.0.0", "packageName": location.host, "dbVersion": 1}`
(Dexie version; readers never branch on `dbVersion` across platforms). Instants via
`toISOString()`; readers accept with or without milliseconds. Calendar dates "YYYY-MM-DD" ↔
`*EpochDay` (§8.1). Every key written, nulls included; unknown keys ignored on read.

### 15.2 Reading

- `sanitize.ts`: JSON.parse rejects the bare `NaN`/`Infinity`/`-Infinity` tokens a 1.x Android
  export can contain; a string-aware scanner replaces them with `null` before parsing.
- `codec.ts`: `detect(text) → "v2" | "legacy1" | "unknown" | "tooNew"`; lenient decode with
  defaults for every field; `encode(dto)`.
- `legacy.ts`: `coffee-brew-log/1` → v2 DTO with A§10.1's label tables (process/roast/bed/sludge/
  channelling/flow/burr/setting-style labels, plus Kotlin enum names), brewer labels via
  `LegacyIds`, `recipeId` by exact name + author against built-ins, grinder by `"<brand> <model>"`,
  `beanUid` from `beanId`; `uid = "legacy-" + first 16 hex of SHA-256("<exportedAt>|<type>|<id>")`
  via `crypto.subtle.digest` on the UTF-8 bytes.
- Runs in `backup.worker.ts`: copy-free `file.text()`, sanitise, parse, validate, decode photo
  base64 into `Blob`s (structured clone passes Blobs by reference), post back the DTO + preview.
  Refuse files over 64 MB: "Too big to import here. Save the backup without photos and try again."

### 15.3 Applying

`merge.ts` — the pure planner A§10.4 `MergeRulesTest` describes: *Add to this browser* (default):
same `uid` replaced only when incoming `updatedAt` is newer (records without `updatedAt` never
replace), new uids added, recipes by `id` the same way; *Replace everything*: wipe beans,
grinders, brews, analyses, photos, blobs and saved recipes, then add. Settings and the `ai` block
apply only when "Also bring settings" / "Also bring AI provider and model (keys never travel)" are
ticked. Uids remap to local ids (`beanUid → beanId`, `grinderUid → grinderId`,
`brewUid → brewLogId`, `defaultBeanUid → defaultBeanId`…). One Dexie `rw` transaction over every
table including `photo_blobs`: any failure aborts it and nothing changes. Afterwards: catalog
linking (`linker.ts`, port of A§6 `CatalogLinker`, adding `grinder-scale:<uid>` notices) and
`onboardingDone = true` if data arrived. Importing the same file twice changes nothing.

### 15.4 Writing and UI

- `exporter.ts`: snapshot all tables, build the document as `Blob` parts (envelope up to
  `"photos": [`, then one part per photo base64-encoded in the worker one at a time, then `]}`) so
  no single giant string exists. File `coffee-backup-YYYY-MM-DD.json`. Save via
  `showSaveFilePicker` where available, else `<a download>` with an object URL; on iOS standalone,
  `navigator.share({ files })` when `navigator.canShare` allows it. Size estimate shown first
  ("about 14 MB with photos").
- `BackupSection()` for Gear › App › YOUR DATA: "Back up everything" (+ "Include photos"),
  "Restore from a backup", storage line (`storage.ts`: "12 MB used · Kept safe" / "May be cleared
  by the browser" + "Keep my data safe" → `persist()`), the Safari note (§8.5), "Delete everything
  in this browser" (confirm: "Delete every brew, coffee, grinder, recipe, analysis and photo in
  this browser? This can't be undone. Save a backup first if you might want them."; also removes
  saved keys). Insights › SHARE & EXPORT "Save backup (JSON)" = same writer, photos off, analyses on.
- `ImportPreviewSheet`: "Found 52 brews, 4 coffees, 1 grinder, 3 recipes, 7 analyses, 12 photos ·
  made on Android (com.laksh.coffee.debug) on 17 Nov 2026" (web: "made on the web (host)");
  mode `SegmentedControl` (Add to this browser / Replace everything, the latter with
  `StatusChip(Fault, "Deletes")` and a confirm); the two toggles; Import; result card ("Added 52
  brews · 0 already here"). Plain-word errors: not JSON, not a Coffee backup, newer version
  ("This backup was made by a newer version of Coffee"), too big, storage full
  (`QuotaExceededError`: "This browser is out of space for Coffee.").

### 15.5 Tests

Round trip (rows → DTO → JSON → DTO → rows, ids remapped, enums, nulls); every key present on
write; the saved API key string never appears in an export (save a key, export, assert);
`shared/fixtures/backup-v2-sample.json` parses (skip with a warning until WP-5 writes it);
`shared/fixtures/coffee-brew-log-1-sample.json` imports with the label mapping, recipe resolution,
grinder link and deterministic uids (compare with `shared/fixtures/coffee-brew-log-1-expected.json`
if WP-5 provides it — open question 3); merge planner cases; NaN sanitiser incl. "NaN" inside a
string staying untouched; re-import is a no-op (fake-indexeddb).

---

## 16. Security and deployment files

### 16.1 `vercel.json` (repo root)

```json
{
  "$schema": "https://openapi.vercel.sh/vercel.json",
  "framework": null,
  "installCommand": "npm ci --prefix web --no-audit --no-fund",
  "buildCommand": "npm run build --prefix web",
  "outputDirectory": "web/dist",
  "trailingSlash": false,
  "rewrites": [
    { "source": "/((?!assets/).*)", "destination": "/index.html" }
  ],
  "headers": [
    {
      "source": "/(.*)",
      "headers": [
        { "key": "Content-Security-Policy", "value": "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data: https://i.ytimg.com; font-src 'self'; connect-src 'self' https: http://localhost:* http://127.0.0.1:*; worker-src 'self'; manifest-src 'self'; media-src 'self' blob:; frame-src 'none'; frame-ancestors 'none'; form-action 'none'; base-uri 'none'; object-src 'none'" },
        { "key": "Referrer-Policy", "value": "no-referrer" },
        { "key": "Permissions-Policy", "value": "camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), hid=(), bluetooth=(), midi=(), display-capture=(), browsing-topics=(), screen-wake-lock=(self), fullscreen=(self)" },
        { "key": "X-Content-Type-Options", "value": "nosniff" },
        { "key": "X-Frame-Options", "value": "DENY" },
        { "key": "Cross-Origin-Opener-Policy", "value": "same-origin" },
        { "key": "Cross-Origin-Resource-Policy", "value": "same-origin" },
        { "key": "Strict-Transport-Security", "value": "max-age=63072000; includeSubDomains" }
      ]
    },
    { "source": "/assets/(.*)", "headers": [{ "key": "Cache-Control", "value": "public, max-age=31536000, immutable" }] },
    { "source": "/(sw\\.js|index\\.html|manifest\\.webmanifest|boot\\.js)", "headers": [{ "key": "Cache-Control", "value": "no-cache" }] }
  ]
}
```
- **CSP.** No `'unsafe-inline'`, no `'unsafe-eval'`, no third-party code. The one third-party
  source is `img-src https://i.ytimg.com`: the cover pictures of the recipes' YouTube videos
  (shared/recipes.json `video`), plain images, never cached by the service worker. `connect-src` is `https:`
  (any AI provider the user chooses, from the browser) plus `http://localhost:*` and
  `http://127.0.0.1:*` for Ollama / LM Studio. The brief asked for https-only; the two loopback
  sources are a deliberate addition so "whatever LLM they're using" includes local models — they
  don't widen exfiltration (any https host is already allowed). To restore https-only, delete them
  and hide `browserCors: "no"` providers on the web (open question 1). `style-src 'self'` works
  because React and Motion write styles through CSSOM (fact 6); `popLayout` and Motion's `scope`
  API are banned. `img-src data:` is kept only as a guard for library-generated data URIs.
- **Rewrites.** Vercel serves an existing static file before applying rewrites, so `/sw.js`,
  `/manifest.webmanifest`, `/icons/*` and `/boot.js` are unaffected; `/assets/*` is excluded so a
  stale hashed chunk 404s (the app reloads once on `vite:preloadError`) instead of receiving HTML.
  Post-deploy check: `curl -sI https://<deployment>/sw.js` shows a JavaScript content type, and
  `/log/anything` returns the app.
- `Cross-Origin-Embedder-Policy` is not set (no need for isolation; it would complicate images).
- Node: set the Vercel project's Node.js version to 24.x (no root `package.json`; `web/.nvmrc`
  documents it for local use).

### 16.2 `.vercelignore` (allow-list, so Android files and anything added later never upload)

```
/*
!/web
!/shared
!/vercel.json
!/.vercelignore
/web/node_modules
/web/dist
/web/.vite
```

### 16.3 `web/scripts/vercel-sim.mjs`

Copies exactly the allow-listed paths (parsing `.vercelignore`) into a fresh temp directory,
runs `installCommand` and `buildCommand` from `vercel.json` there, and checks `web/dist/index.html`,
`sw.js` and `manifest.webmanifest` exist. Proves the build needs nothing outside the upload.

### 16.4 Other security rules

- No third-party scripts, styles, fonts, images or iframes; all links to providers use
  `rel="noopener noreferrer"`.
- No `eval`, `new Function`, `innerHTML`, `dangerouslySetInnerHTML`; recipe and AI text render as
  text. Markdown from analyses is rendered by a tiny whitelist renderer (paragraphs, lists, bold)
  that builds React elements.
- Imported backups and catalog JSON are data: validated, never executed; string lengths capped
  (names 200, notes 10,000) on import.
- `sourcemap: true` is fine (open source); no secrets exist in the bundle (no env keys are read).

---

## 17. PWA and offline (WEB-1)

`VitePWA({ strategies: "generateSW", registerType: "prompt", injectRegister: false,
manifest: { name: "Coffee", short_name: "Coffee", description: …, id: "/", start_url: "/", scope: "/",
display: "standalone", background_color: "#0D0B09", theme_color: "#0D0B09",
categories: ["food", "lifestyle"], icons: [192, 512, 512 maskable from public/icons] },
workbox: { globPatterns: ["**/*.{js,css,html,svg,png,json,webmanifest}"], navigateFallback: "/index.html",
navigateFallbackDenylist: [/^\/assets\//], cleanupOutdatedCaches: true, runtimeCaching: [] } })`.
- Everything (code, catalogs, recipes, icons) is precached: the whole app works offline except
  AI calls. No runtime caching of anything cross-origin; AI requests never touch the SW cache.
- Registration in `app/UpdateToast.tsx` via `useRegisterSW` from `virtual:pwa-register/react`
  (external file, CSP-safe). A waiting update shows "A new version is ready · Reload"; it is held
  back while a brew is running and offered on finish. Never auto-reload.
- `vite:preloadError` → reload once (guarded by a sessionStorage flag set through `prefsMirror`).

---

## 18. Accessibility (every package; WEB-6 audits)

- Semantic HTML: one `<h1>` per screen (the screen title), sections with `<h2>` eyebrows rendered
  as visible text, lists as `<ul>`, buttons as `<button>`, navigation as links.
- Status is never colour alone (already a design rule): chips and lines always carry their word.
- Contrast: tokens are Android's, tuned for ≥ 4.5:1 on small text in both modes; `inkFaint` is
  never used for words.
- Focus: visible `:focus-visible` ring (2 px `--ink-high`, 2 px offset) on everything focusable;
  never removed. Sheets and the photo viewer are `<dialog showModal>` (trap, Escape, restore focus).
  Inactive tab layers are `inert`.
- Keyboard: every action reachable; shortcuts in §6.2 plus brew: Space play/pause, ← / → ±5 s,
  N next step, B bed exposed (only on the brew screen, not while typing).
- Forms: labels tied to inputs; numeric fields `inputmode="decimal"`; errors announced via
  `aria-describedby`; sliders are native range inputs with `aria-valuetext` ("7 of 10, electric").
- Live regions: brew step changes (polite); analysis finished (polite); toasts (status).
- Charts: `role="img"` + summary label + hidden value list. Photos: meaningful `alt`.
- Motion: §7 rule 13. Text scaling: `rem` type, layouts survive 200 % zoom without horizontal
  scroll. Touch targets ≥ 44 px. `lang="en-GB"`.

---

## 19. Testing and verification

### 19.1 Vitest (logic first; `environment: "node"` unless a file opts into jsdom)

- WEB-1: catalog parse + cross-refs + kebab ids + whole-number click ranges (mirrors A§2.8
  `CatalogParseTest`, reading the same files), legacy ids, recipe ids/golden numbers/serialization
  (accent hex and legacy number, legacy brewer `"V60"` → `hario-v60-02`), format, units, status,
  quickRead, GrindScale vectors, MoveSizes, `grindLooksTyped`, tokens and motion parity tests,
  router URL ⇄ Route and deep-link stack synthesis, repo cascades and `updatedAt` stamping
  (jsdom + fake-indexeddb), store selectors don't re-emit on unrelated changes.
- WEB-2: engine (start/pause/resume/±5 s/skip/overrun/bed exposed/drawdown, wall-clock re-anchor,
  `autoAdvanceSteps` off), `waterAtF`, cue scheduling times, photo size maths, `exifDate`,
  attachment selection and 4 MB trimming.
- WEB-3: wire tests for both clients with a fake `fetch` (string vs parts, image blocks, browser
  header present only on the web, `max_completion_tokens` for openai, retries, stop reasons, 1 MiB
  cap, CORS-style `TypeError` → message), key store (session vs local, host binding, scrubbing,
  never in IndexedDB), prompt blocks (K-Ultra NumberTick `currentOnDial` "7.5", no `grindSetting`
  for clicks, principles filtered, °F line), parser, DataQuality, passport stats, report snapshot.
- WEB-4: search cases, validation rules, draft ⇄ recipe timings, onboarding step persistence.
- WEB-5: §15.5.

### 19.2 Smoke script (`npm run smoke`, Playwright + Chromium)

WEB-1 writes it; later packages add their flows; WEB-6 adds the performance pass.
1. `vite build` (the real production build; no test hooks exist in any build), then
   `vite preview` (production headers from `vercel.json`).
2. Fresh context: lands on `/welcome`; Skip → Today. Visit every route. Time is driven with
   Playwright's clock API (`page.clock.install()` / `fastForward`), which fakes `Date`, timers,
   `requestAnimationFrame` and `performance.now`, so a brew can be finished in seconds through
   the real UI. Data for deep links (`/log/<uid>`) comes from that flow or from importing a
   committed test backup (`web/src/test/fixtures/smoke-backup.json`) through the real restore UI.
3. Fails on any console error, any `securitypolicyviolation` event, any failed same-origin
   request, any uncaught rejection.
4. Flow: open a recipe, Brew, start, +5 s, fast-forward to the end, Finish, Review: set bed and
   score, Done; the brew is in Log; open it; export a backup, wipe, import it back, counts match.
5. Offline: after the SW is active, go offline, reload, Today renders.
6. Runs axe-core on each screen in both themes; fails on serious/critical violations.
7. Perf pass (WEB-6): CPU throttling 4× via CDP; for 10 tab switches and 3 recipe open/close
   cycles, record Long Animation Frames (`PerformanceObserver({ type: "long-animation-frame" })`)
   and a rAF frame-time trace; print p50/p95 frame times and the count of frames over 8.33 ms and
   16.7 ms; **fail** on any LoAF over 50 ms during a transition. (Headless Chrome runs rAF at 60 Hz,
   so 120 Hz itself is checked on the device, §19.3.)

### 19.3 On the owner's 120 Hz phone (WEB-6 checklist, recorded in the PR)

Android Chrome with `chrome://inspect` remote DevTools, seeded with 1,000 brews (dev build):
- Performance panel, tab switches: no dropped frames in the Frames track; no main-thread task over
  8 ms during the switch.
- Recipe card → detail → back: React Profiler shows one commit at the start, none during the
  flight, at most one at the end.
- Running brew: React Profiler shows commits only at step changes; ring draw < 1 ms per frame.
- Log scroll with 1,000 brews: no long animation frames.
- Lighthouse (mobile, production URL): Performance ≥ 95, Accessibility 100, Best Practices 100;
  installable.
- Budgets: initial JS ≤ 170 KB gzipped, CSS ≤ 25 KB; each feature chunk ≤ 80 KB.

---

## 20. Work packages

### 20.1 Order

```
WEB-1 Foundation ──┬── WEB-2 Brew flow ─────────────┐
                   ├── WEB-3 Log, Insights & AI ─────┤
                   ├── WEB-4 Gear & personalisation ─┼── WEB-6 Integration, 120 Hz pass, deploy
                   └── WEB-5 Backup & storage ───────┘
```
WEB-2..5 run in parallel in disjoint folders. External inputs: `shared/recipes.json` (Android
WP-1), `shared/principles.json` (Android WP-3), `shared/fixtures/*` and `shared/schema/backup-v2.md`
(Android WP-5). Each web package works without them (loaders tolerate absence, tests skip) and
picks them up when they land. Web packages never edit `shared/` or `app/`; a catalog problem is
reported to the orchestrator.

### 20.2 Packages

**WEB-1 Foundation: scaffold, design system, shell, data layer.** §2–§10 core rows, §16, §17.
Done when: `npm run typecheck lint test build smoke` pass; `vercel-sim` passes; the five tabs and
every route render (stubs) with the §6.3/§6.4 transitions and the shared-element fallback flag;
the bottom bar, sheets and tokens match Android in both themes (side-by-side screenshots with the
emulator in the PR); Dexie store, repo and settings work across two browser tabs; catalog problems
list is empty for today's files.

**WEB-2 Brew flow: Today, Recipes, Recipe detail, Brew, Review, Photos, viz, art.** §11 rows,
§12, §13. Done when: a full brew works end to end on a phone (wake lock holds, vibration on
Android, cues on iOS), the ring is canvas-drawn with zero React commits between steps, reload
mid-brew resumes, photos add from camera and library with EXIF stripped (verify a stored blob has
no APP1 segment), the shared header flies card → detail → card from Today and Recipes.

**WEB-3 Log, Insights & AI.** §11 rows, §14. Done when: Log/detail/Insights match Android, charts
animate once per session, every `browserCors: "yes"` provider completes a real test connection
from the preview URL with the owner's or a test key (keys typed by the owner, never by an agent),
Anthropic works with the browser header, Ollama setup copy is shown, photos are attached only for
vision models, the key never appears in IndexedDB, backups or console.

**WEB-4 Gear & personalisation.** §11 rows (Gear, editors, picker, onboarding, recipe builder),
`features/grind`. Done when: picking "commandante", "mahlkonigg", "k ultra", "ek43" finds the right
entries; grinder editor fills from the catalog and shows the scale notice; bean editor stores
every A§7.6 field; recipe builder validates and saves; onboarding persists each step; Gear App
settings round-trip through IndexedDB and `boot.js`.

**WEB-5 Backup & storage.** §15, §8.5 UI. Done when: §15.5 tests pass; a backup exported from the
Android debug build (once WP-5 lands) imports on the web with matching counts, and a web export
imports on Android; re-import adds nothing; replace mode works; storage status and persist
request work in Chrome and Safari.

**WEB-6 Integration, 120 Hz pass, deploy.** Replace any stub still in place, wire cross-feature
callbacks, run §19.2 full + §19.3 on the device and fix what it finds (may edit any web file, in
coordination), README "Web" section (run, build, deploy, privacy, browser support), a
`vercel.json` post-deploy check, and hand the orchestrator the deploy steps (§20.5).

### 20.3 File ownership

| path | owner(s) in order |
|---|---|
| `vercel.json`, `.vercelignore`, `web/*.json`, `web/*.ts` configs, `web/index.html`, `web/public/**`, `web/scripts/gen-tokens.mjs`, `vercel-sim.mjs` | WEB-1 → WEB-6 |
| `web/scripts/smoke.mjs` | WEB-1 → each package appends its flow → WEB-6 |
| `web/src/main.tsx`, `src/app/**` | WEB-1 → WEB-6 |
| `src/design/**` | WEB-1 (others request changes; WEB-6 may fix) |
| `src/data/**`, `src/catalog/**`, `src/domain/core/**`, `src/domain/grind/**`, `src/test/**` | WEB-1 |
| `src/viz/**`, `src/art/**`, `src/features/{today,recipes,brew,review,photos}/**` | WEB-1 (stubs) → WEB-2 |
| `src/features/{log,insights,ai}/**`, `src/domain/{ai,insights,report}/**` | WEB-1 (stubs) → WEB-3 |
| `src/features/{gear,grind,catalog-picker,onboarding,recipe-editor}/**`, `src/domain/{search,editor}/**` | WEB-1 (stubs) → WEB-4 |
| `src/features/backup/**`, `src/domain/backup/**` | WEB-1 (stubs) → WEB-5 |
| `README.md` (Web section only) | WEB-6 |
| `shared/**`, `app/**` | none (read-only for web packages) |

### 20.4 Stub contracts (WEB-1 writes these signatures; owners keep them)

```ts
// features/today | recipes | brew | review | log | insights | gear | onboarding | recipe-editor
export function TodayScreen(): JSX.Element;
export function RecipesScreen(): JSX.Element;
export function RecipeDetailScreen(p: { id: string; from: SharedFrom }): JSX.Element;
export function BrewScreen(p: { recipeId: string }): JSX.Element;
export function ReviewScreen(p: { brewUid: string }): JSX.Element;
export function LogScreen(): JSX.Element;
export function BrewDetailScreen(p: { brewUid: string }): JSX.Element;
export function InsightsScreen(): JSX.Element;
export function GearScreen(p: { tab: "beans" | "grinders" | "app" }): JSX.Element;
export function OnboardingScreen(): JSX.Element;
export function RecipeEditorScreen(p: { id: string | null; mode: "new" | "edit" | "duplicate" }): JSX.Element;
// every screen module also exports `prefetch(): Promise<unknown>` (chunk + data warm-up)

// features/photos (WEB-2)
export function PhotoStrip(p: { brewId?: number; beanId?: number; kinds: PhotoKind[]; editable?: boolean }): JSX.Element;
export function photoAttachmentsForBrew(brewId: number): Promise<AiImage[]>;
// features/ai (WEB-3)
export function AiSettingsSection(): JSX.Element;
export function AiSettingsSheet(p: { onClose(): void }): JSX.Element;
export function BrewAnalysisSection(p: { brewId: number }): JSX.Element;
export function FirstReadSection(p: { brewId: number }): JSX.Element;
export function TrendAnalysisSection(): JSX.Element;
// features/grind (WEB-4)
export function DialReadingInput(p: { scale: GrindScale; value: number | null; onValue(v: number | null): void }): JSX.Element;
export function GrindRangeCard(p: { scale: GrindScale; method: BrewMethod | null; collapsed?: boolean }): JSX.Element;
export function GrindStartHint(p: { scale: GrindScale; method: BrewMethod | null; onUse(v: number): void }): JSX.Element;
// features/catalog-picker (WEB-4)
export function CatalogPickerSheet(p: { kind: CatalogKind; title: string; multiSelect?: boolean; selected?: string[];
  allowCustom?: boolean; onPick(pick: { id: string } | { custom: string }): void; onDone?(ids: string[]): void; onClose(): void }): JSX.Element;
// features/backup (WEB-5)
export function BackupSection(): JSX.Element;
export function SaveBackupRow(p: { includePhotos: boolean; label: string; subtitle: string }): JSX.Element;
export function runStartupLinking(): Promise<void>;
// viz (WEB-2)
export function RecipeCard(p: { recipe: Recipe; sharedPrefix: SharedFrom; onOpen(): void }): JSX.Element;
export function RecipeHeader(p: { recipe: Recipe; size: "card" | "detail" }): JSX.Element;
export function JourneyLine(p: { nodes: JourneyNode[]; style: "live" | "historical" | "compact" }): JSX.Element;
export function RouteRow(p: RouteRowProps): JSX.Element;   // RouteRowProps mirrors Kotlin RouteRow's parameters (mode Planned|Done, endpoints, times)
export function TimelineCard(p: { rows: TimelineRow[] }): JSX.Element;
export function BrewRing(p: { engine: BrewEngineView; accent: string; boundaries: number[] }): JSX.Element;
export function MeshGradient(p: { mood: BrewMood }): JSX.Element;
// art (WEB-2)
export function BrewerIcon(p: { brewer: Brewer; size?: number }): JSX.Element;
export function BedShapeIcon(p: { shape: BedShape | null; size?: number }): JSX.Element;
export function CoffeeMug(p: { fill: number }): JSX.Element; export function Steam(): JSX.Element;
export function Dripper(): JSX.Element; export function TumblingBeans(): JSX.Element;
```
Stubs render a `DashedPlaceholder` with the component name at the final size, so layouts can be
built against them.

### 20.5 Publishing (orchestrator / owner, after WEB-6)

1. GitHub (the orchestrator's repo step): `web/` and `shared/` are committed with the Android app;
   no secrets, `.vercel/` ignored.
2. Vercel needs the owner's account: either import the GitHub repo in the Vercel dashboard (Root
   Directory: repo root; Framework: Other; settings come from `vercel.json`; Node 24.x), or
   `npx vercel@66.0.0 link` then `npx vercel@66.0.0 deploy --prod` from the repo root. Signing in is
   the owner's step.
3. After the first deploy: run the §16.1 post-deploy checks and Lighthouse on the production URL,
   then set `ANDROID_DOWNLOAD_URL` / `SOURCE_URL` in `src/app/links.ts` (open question 2).

---

## 21. Risks (also in the structured output)

- `layoutId` flights across keep-alive scroll layers may mis-measure on some browsers; the
  container-transform fallback flag (§6.5) exists for that.
- Safari's 7-day storage eviction for non-installed sites can wipe a user's log; mitigated by
  `persist()`, the install prompt, the Safari note and backup nudges, not eliminated.
- iOS Safari may cap `requestAnimationFrame` at 60 Hz; compositor-run transitions mitigate, the
  canvas ring and layout flights do not.
- Strict `style-src 'self'` breaks any code path that injects `<style>` or `style` attributes;
  this only shows under production headers, hence preview-with-headers + smoke failing on CSP
  violations.
- Provider CORS was observed once (2026-11-17, from one Vercel origin); it can change. Custom
  servers often lack CORS. Local models need OLLAMA_ORIGINS / Enable CORS, Chrome's local-network
  prompt, and may be blocked as mixed content in Safari.
- Keys remembered in localStorage are readable by any script on the origin or an extension with
  site access; strict CSP and zero third-party code reduce XSS risk, default is session-only.
- Domain logic exists twice (Kotlin, TS): grind scale, prompt, backup, legacy uid derivation can
  drift; shared fixtures and the copied A§ test vectors are the guard.
- Android files and shared data are being edited in parallel; the web tolerates missing shared
  files but cannot detect semantic changes until tests run against the new files.
- Big photo backups strain phone memory (base64 ~1.33×); 64 MB cap, worker parsing, Blob parts.
- HEIC from desktop file pickers cannot be decoded in Chromium.
- Node 25 on the owner's machine is outside Vitest 5's declared engines.

## 22. Open questions (also in the structured output)

1. CSP `connect-src`: keep the two loopback sources (local LLMs work) or https-only as briefed?
2. The GitHub owner/repo and Vercel project name, for "Get the Android app" (latest release APK),
   "Source code" links and README badges.
3. Will Android WP-5 add `shared/fixtures/coffee-brew-log-1-expected.json` (expected legacy uids
   and mapped values) so both apps prove the same derivation? If WP-5 is late, may WEB-5 write
   the shared fixtures?
4. Should Android also show web-made analyses as "Made on another device" (A§8.6 already keys on
   `origin != "android"`) — confirm Android imports `origin: "web"` rows unchanged.
5. React Compiler on by default (SHOULD here), given it adds Babel to a Rolldown build?
6. TypeScript 6.0.3 instead of 7.0.2 (typescript-eslint compatibility) — acceptable?
7. If PERF changes `Motion.kt` to the 120 Hz token set, the web table follows it; confirm PERF's
   final names so the parity test can match them.
8. Node 24.x in the Vercel project settings (owner action) vs adding a root `package.json` with
   `engines` (which this spec avoids).
9. Sound cues: Android keeps the setting without a sound; the web plays a soft cue when it is on
   (iOS has no vibration). Keep, or default it off on the web?
