# v4 domain API (V4-CORE)

What the v4 screen packages call, by name, on both platforms. Every function here is pure unless
it says otherwise, and both apps give the same results for the cases in
`shared/fixtures/domain-v4-cases.json` (web: `src/domain/core/v4parity.test.ts`; Android:
`DomainV4ParityTest`). Spec sections are `scratchpad/v4/v4-spec.md` §N.

## Settings (§6.1)

| | Web `src/data/settings.ts` (`Settings`) | Android `data/prefs/SettingsStore.kt` (`Settings`) |
|---|---|---|
| `experience` | `"" \| "new" \| "recipes" \| "tinker"` | `String` |
| `hasScale` | `boolean` (default true) | `Boolean` |
| `reviewMode` | `"quick" \| "detailed"` | `String` |
| `buysGround` | `boolean` (default false) | `Boolean` |
| `soundCuesAsked` (device only) | `boolean` | `Boolean` |
| `sidebarCollapsed` (device only) | `boolean` | `Boolean` |
| `logView` (device only) | `"list" \| "table"` | `String` |

- Web writes with `settings.set(key, value)` / `settings.update(patch)`. Android:
  `setExperience`, `setHasScale`, `setReviewMode`, `setBuysGround`, `setSoundCuesAsked`,
  `setSidebarCollapsed`, `setLogView`.
- Fresh installs: `tempUnit` from the region (`defaultTempUnit`), `reviewMode` "quick". An install
  that already had brews and never stored `reviewMode` reads "detailed": web `store.init()` stores it
  (`initialReviewMode(raw, brewCount)`), Android `StartupTasks` (`SettingsStore.pinV4Defaults`),
  which also pins °C for an install that was already in use.
- Backups carry the first four (backup 2.1, `schema/backup-v2.md`), never the device-only three.

## Records (§6.2)

| | Web row (`src/data/schema.ts`) | Android entity (Room v5) | Values |
|---|---|---|---|
| bean `form` | `BeanRow.form: BeanFormId` | `BeanEntity.form: String` | `"" \| "whole" \| "ground"` ("" read as whole) |
| bean `groundFor` | `BeanRow.groundFor: GroundFor` | `BeanEntity.groundFor` | `"" \| "filter" \| "press" \| "espresso" \| "moka"` |
| grinder `settingUnit` | `GrinderRow.settingUnit: SettingUnit` | `GrinderEntity.settingUnit` | `"" \| "s"` (seconds: a blade grinder, style stays "stepless") |
| brew `grindKind` | `BrewRow.grindKind: GrindKind` | `BrewLogEntity.grindKind` | `"" \| "dial" \| "blade" \| "preground"` |

- Pre-ground brew: `grindKind "preground"`, `grindSetting "As bought"`, no clicks or value. Blade:
  `grindKind "blade"`, `grindSetting "12 s"`, `grindValue 12`.
- Web: no Dexie version bump. Rows written before v4 lack the fields until written again; every
  read path fills them (`withBeanDefaults`, `withGrinderDefaults`, `withBrewDefaults` in
  `schema.ts`, applied by the store and `repo.brewByUid`). Write new rows with `newBean` /
  `newGrinder` / `newBrew`, which set them.
- Android: `MIGRATION_4_5` (ADD COLUMN only), `schemas/…/5.json`.
- Faults: `Descriptors.FAULTS` ends with "Too weak", "Too strong" on both.
- Taste axes: Balance "one thing sticks out → all fits together", Clarity "muddy → clean".

## Recipes (§6.4)

`Recipe.level` (web `RecipeLevel`, Kotlin `String`; `RecipeLevels.ALL`, `RecipeLevels.rank`),
`Recipe.needs` (web `RecipeNeed[]`, Kotlin `List<String>`; `RecipeNeeds.SCALE…`), and
`Recipe.prepNoScale: string[]`. They round-trip through saved recipes, backups and the editor
draft (web `RecipeDraft.level/needs/prepNoScale`, Android `RecipeDraft` with defaults).

## Brewers (§4.9, §4.13)

| Web (`domain/core/brewer.ts`) | Kotlin (`domain/Brewer.kt`) |
|---|---|
| `type BrewerArt` adds `"Drip" \| "Cezve" \| "Siphon" \| "Phin"` | `enum class BrewerArt` adds `Drip, Cezve, Siphon, Phin` |
| `brewerArt(b: Brewer): BrewerArt` | `Brewer.art` / `BrewerArts.of(b)` |
| `brewerFamily(b: Brewer): string` | `Brewer.family` / `BrewerFamilies.of(b)` |
| `NO_MATCH_FAMILIES: ReadonlySet<string>` | `BrewerFamilies.NO_MATCH` |
| `ownedFamilies(ownedBrewerIds, byId): Set<string>` | `BrewerFamilies.owned(ownedBrewerIds, byId = Brewers::byId)` |
| `brewStyle(b: Brewer \| null): "percolation" \| "immersion" \| "espresso"` | `BrewStyle.of(b)` (`.id` is the web string) |
| `reviewSections(b: Brewer \| null): ReviewSections` `{drawdown, bed, retained, advanced: true, style}` | `ReviewSections.of(b)` |
| `methodLabels(m: BrewMethod): MethodLabels` `{water, waterNote, time, longTimes}` | `MethodLabels.of(m)` |

Families: drip-machine, drip-bag, phin and south-indian-filter are their own; melodrip is
"accessory" and other is "other", both in the no-match set. Recipe filters must build the owned set
with `ownedFamilies` / `BrewerFamilies.owned` so an owned Melodrip never shows V60 recipes.
Placeholder art: Drip draws the V60, Siphon the Chemex, Phin the AeroPress, Cezve the moka pot
(web `src/art/BrewerIcon.tsx`, Android `CoffeeArt.kt`) until the screen packages draw them.

## First brew (§4.6)

- Web `firstBrew(recipes, settings: FirstBrewSettings, grinders: {isArchived}[], brewerById): FirstBrewPick`
  (`domain/core/firstBrew.ts`); `unmetNeeds(needs, settings, grinderCount)`.
- Kotlin `FirstBrew.pick(recipes, FirstBrewSettings(ownedBrewerIds, hasScale, buysGround, experience), grinderCount, byId = Brewers::byId): FirstBrewPick`;
  `FirstBrew.unmetNeeds(...)`.
- `FirstBrewPick { recipe: Recipe | null; noRecipeFor: string[] }`: `noRecipeFor` holds the owned
  brewer ids with no recipe in their family, for "We don't have a tested recipe for your {Cezve} yet."

## No-scale amounts (§4.3)

- Web (`domain/core/noScale.ts`): `GROUND_COFFEE_G_PER_LEVEL_TBSP`, `ML_PER_MUG`,
  `noScaleAmounts(doseG, waterG, method, form?): NoScaleAmounts`,
  `noScalePrep(recipe, units: Units, form?): string[]`, `servingsPreset(recipe, mugs): Recipe`,
  `SCALE_LINE`, `GRAMS_LINE`.
- Kotlin (`domain/NoScale.kt`): `NoScale.amounts(doseG, waterG, method, form = "")`,
  `NoScale.prep(recipe, tempUnit: "c"|"f", form = "")`, `NoScale.servings(recipe, mugs)`,
  `NoScale.SCALE_LINE`, `NoScale.GRAMS_LINE`, `NoScale.GROUND_COFFEE_G_PER_LEVEL_TBSP`,
  `NoScale.ML_PER_MUG`.
- Prep: a line that mentions a scale or gives grams is replaced (where the first stood) by
  `prepNoScale`, the basket line or the generic lines; a prep with no such line gets them first.
- Water targets without a scale are the grams read as ml, unrounded, everywhere: step prose
  (`noScaleText`), the lead (`stepLead` / `BrewWords.lead`, "Pour to 352 ml"), the NEXT line, the
  readout and its denominator, the step list. A jug step's "About N ml from your jug" is the
  difference of two such targets, so the jug steps add up to the recipe's water. (2026-11-20:
  was "about" rounded to 10 ml in the lead, which put "about 350 ml" beside "352 ml".)

## Shot target (§4.9)

- Web `shotBand(recipe)` (`domain/core/shotTarget.ts`), Kotlin `ShotTarget.of(recipe)`; parity
  cases `shotBand`. One rule: a band stated in the words of the non-pre-infusion steps' details,
  then their titles, then the tips, then the blurb (every "N to M s" in a text tried in order; the
  first with 0 < N < M and 15 <= M <= 120 wins); else "about E s", E the latest end of a step
  that is not Serve; else 25-30 s. A pre-infusion step has "infus" in its title, or starts at 0
  with "pre-infus" / "preinfus" in its detail. The label is "25–30 s" (en dash) or "about 35 s".
- Every place that judges a shot uses the band: in it is good ("In the band", "in band" in the
  lists), outside it is attention, and a delta is the seconds from the nearer edge (0 inside; a
  single-number band allows 2 s either side). Compare's single plan number is the band's middle.
- `NoScaleAmounts { coffeeTbsp, coffee, coffeeLine, waterMl, mugs, water, basket }`; see
  `schema/units.md`. `form "whole"` = beans measured before a blade grinder ("… of beans").

## Text (§4.5, §4.15)

| Web | Kotlin | |
|---|---|---|
| `renderTemps(text, unit)` (`units.ts`) | `Temperatures.render(text, unit)` | "70 °C" → "158 °F" for "f" |
| `PROSE_TEMP` | `Temperatures.PROSE_TEMP` | the shared pattern |
| `defaultTempUnit(locale: string)`, `localeRegion`, `FAHRENHEIT_REGIONS` | `Temperatures.defaultUnit(tag)`, `.defaultUnit(Locale)`, `.region(tag)` | |
| `formatDuration(sec)` (`format.ts`) | `formatDuration(sec)` (`core/Format.kt`) | "3:30", "12 h 5 min" |
| `ratioPhrase(doseG, waterG, method?)` | `ratioPhrase(doseG, waterG, method = "")` | "1 part coffee to 16.7 parts water" |
| `halves(v)` | `halves(v)` | "4½" |
| `screenTime(ms, locale?)`, `screenDate(ms, locale?)`, `screenDayLabel(ms, now?, locale?)`, `todayEyebrow(now?, locale?)` | `screenTime(ms, locale, zone)`, `screenDate(...)`, `screenDayLabel(...)`, `todayEyebrow(...)` | locale-aware, screens only; `dayLabel` / `timeLabel` stay pinned for exports and prompts |
| `grindReference(word)` (`glossary.ts`) | `Glossary.grindReference(word)` | "Medium-fine" → "like table salt" |
| `termForStepKind(kind)`, `findTerm(terms, key)` | `Glossary.termForStepKind(kind)`, `Glossary.find(terms, key)` | |
| `catalog.glossary`, `catalog.term(id)` | `Catalog.glossary`, `Catalog.term(id)` | shared/glossary.json (`schema/glossary.md`) |

## Review advice (§4.4)

- Web `quickRead(log: QuickReadInput, recipe, opts?: QuickReadOptions): string[]`;
  `QuickReadInput` gains optional `doseG` and `grindKind` (a `BrewRow` fits);
  `QuickReadOptions { brewer?, hasScale?, fahrenheit?, timeIgnored? }`.
- Kotlin `BrewReport.quickRead(log, recipe, opts: BrewReport.QuickReadOptions = QuickReadOptions())`.
- The brewer defaults to the recipe's. `adjustable` = `grindKind != "preground"`. Time advice is
  skipped when `timeIgnored` (pass true when data quality distrusted the total time) or when the
  time is under half or over 2.5× the recipe's. Times print with `formatDuration`.

## Data quality

- `GRIND_MISSING` is not raised for `grindKind "preground"`; a blade brew's seconds in `grindValue`
  count as recorded (both apps now also accept `grindValue` alone, as the web did).
- `TEMP_IMPLAUSIBLE` is not raised for a cezve (it starts from cold water), as for cold brew.

## Log search and filters (§4.11)

- Web (`domain/core/logQuery.ts`): `matchesLogQuery(brew: LogSearchable, query)`, `foldForSearch`,
  `LogFilter`, `EMPTY_LOG_FILTER`, `filterLog(brews, filter, photoBrewIds?)`, `recipeKey`,
  `brewerKey`, `isFilterActive`, `logCountLabel(n)`.
- Kotlin (`domain/LogQuery.kt`): `LogQuery.matches(log, query)` (and the field-by-field overload),
  `LogQuery.fold`, `LogFilter(...)` with `.isActive`, `LogSort`, `LogQuery.filter(brews, filter, photoBrewIds)`,
  `LogQuery.recipeKey`, `LogQuery.brewerKey`, `LogQuery.countLabel(n)`.
- A query matches when every word appears (accents ignored) in the recipe name or author, coffee,
  grinder, brewer, notes, next time or descriptors. Sort "best" is overall then newest.

## Catalog (§6.5)

- brewers.json: `south-indian-filter` (category other, method pourover, filter metal).
- grinders.json: `blade-grinder` ("Any" "Blade grinder", burr blade, stepless 0-60 step 1,
  `setting.settingUnit "s"`). Both catalog readers expose `GrinderSetting.settingUnit`.
- water.json: the generic waters onboarding offers first already exist: `tap-unfiltered` ("Tap
  water"), `tap-filtered`, `bottled-spring`. "Not sure" is `defaultWaterId ""`.
- roasters.json: `mainstream: true` and `regions` (`area` values where the brand is sold) on
  supermarket brands. Both readers expose `mainstream` and `regions`. Added 2026-11-19 after
  checking that each sells whole-bean or ground coffee in the region named:
  Folgers, Maxwell House, Café Bustelo (North America: US grocery listings), Starbucks (North
  America; Southeast Asia: starbucksathome.com/sg), Jacobs (Europe: REWE, Albert Heijn), Douwe
  Egberts (Europe: Ocado), Bru and Tata Coffee Grand (South Asia: BigBasket; both filter powders
  are coffee-chicory blends), Key Coffee (East Asia: Yodobashi). Flagged without a new check
  (they were already in the catalog as "mainstream", region = their home `area`): Dunkin', Peet's,
  Starbucks Reserve, Tchibo, Dallmayr, Segafredo, Kimbo, illy, Lavazza, UCC.
- roasters.json: optional `website`, an https URL of the roaster's own homepage (for a brand
  without its own site, its owner's page for the brand, e.g. Bru on hul.co.in). Omitted when we
  could not confirm the official site; readers treat a missing or non-https value as no link and
  show the link only when present. Added 2026-11-20 for 373 of 413 roasters (all 19 mainstream
  brands, all 25 in India, 16 of 18 in Japan, 9 of 10 in South Korea). Each was confirmed by
  loading the homepage and finding the roaster's name on it, or by search results naming it as
  the official site. Domains that were parked, for sale, password-protected or belonged to
  someone else were left out. Check a link again before changing it; never guess one.
