# Coffee 2.0 — Android implementation spec

Written 2026-11-17 by the Android tech lead after a read-only pass over `the repo`.
Source root below means `app/src/main/java/com/laksh/coffee`. MUST = load-bearing; SHOULD = default
unless there is a concrete reason; MAY = optional.

This spec covers: shared data, personalisation, "add your key", photos, backup v2, Room v4, release
signing, and the split into work packages (§13). The jank/perf work (nav architecture, baseline
profile module, release build tuning) is a separate package called **PERF**, owned by another agent.
PERF owns `ui/nav/CoffeeNav.kt`, `MainActivity.kt`, `ui/nav/SharedTransitions.kt`, `ui/theme/Motion.kt`
and any baseline-profile module. This spec edits `CoffeeNav.kt` and `MainActivity.kt` only in WP-6,
after PERF has landed (§12), and says why each edit cannot be avoided.

---

## 0. Ground rules for every package

- Kotlin 2.2.10, AGP 9.4 built-in Kotlin, Compose BOM 2026.06.01, Material 3 1.4.0, Room 2.8.4,
  kotlinx-serialization 1.9.0, Navigation 2.9.8, minSdk 26, compile/target 36. Do not bump these;
  README explains why each is pinned.
- No DI framework. Singletons hang off `CoffeeApp`; ViewModels use `viewModelFactory { initializer { … this.app … } }`.
- Strings are inline English in Kotlin (no strings.xml translation layer). Copy is plain, second
  person, no exclamation marks, no emojis.
- Design: Flighty rules. One hero number per surface; a status colour always comes with a word
  (`StatusChip(Status.Attention, "Unverified")`, never colour alone); crema only for the live brew
  and the one primary action on a screen. Reuse `SoftCard`, `FieldWell`, `SelectableChip`,
  `SegmentedControl`, `ToggleRow`, `StatusChip`, `StatusLine`, `EyebrowLabel`, `SectionLabel`,
  `CoffeeSheet`, `PrimaryButton`, `QuietButton`, `IconWell`, `DataGrid`/`Cell`.
- Motion: only the vocabulary in `ui/theme/Motion.kt` (`Motion.offset()`, `Motion.decel(ms)`,
  `Motion.settle()`, `Motion.quick()`, `Motion.size()`, `Motion.bounce()`). Animate placement,
  layer and draw (`graphicsLayer`, `offset {}`), never layout every frame. Every `LazyColumn` /
  `LazyRow` item gets a stable `key` and `contentType`, and `Modifier.animateItem()`. No JSON parse,
  search, image decode or disk read on the main thread: parse/search on `Dispatchers.Default`,
  disk on `Dispatchers.IO`. New screens must hold 120 Hz on a mid-range phone (PERF will profile).
- Never invent a coffee number, a person's recipe, a URL or a grinder figure. Expert numbers come
  only from `shared/` JSON that carries sources. App heuristics (e.g. move sizes in §3.3) are
  labelled as the app's own rule of thumb in code comments and in any copy that shows them.
- Every JSON file written under `shared/`: UTF-8, 2-space indent, top level
  `{"version": 1, "updated": "2026-11-17", …}`, ids lowercase-kebab and stable
  (`^[a-z0-9]+(-[a-z0-9]+)*$`). Validate each with
  `python -c "import json;json.load(open(r'<path>',encoding='utf-8'))"`.
- Verify before handing over (Git Bash, repo root):
  `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`; WP-1 also
  `./gradlew :app:connectedDebugAndroidTest` on the emulator (memory note: launch it detached
  from PowerShell; it dies under low RAM).
- Do not commit, push or publish unless the orchestrator tells you to. Never commit a keystore,
  `keystore.properties`, `local.properties` or an API key.

---

## 1. Facts in today's code that drive this design

1. `brew_logs.brewer` stores the brewer **label** (`"Hario V60"`), written by
   `BrewViewModel.finish` (`recipe.brewer.label`) and `DebugTools`. `saved_recipes.json` stores
   `Recipe` with the **enum name** (`"brewer":"V60"`) and `accent` as a JSON number.
2. `enum class Brewer` (9 values, in `domain/Recipe.kt`) is switched on in `ui/components/CoffeeArt.kt`
   (`BrewerIcon`), `ui/components/Grouped.kt` (`brewerFromLabel`, `shortName`, `rowTag`),
   `ui/screens/brew/BrewScreen.kt` (`POUR_OVER`), `ui/screens/brew/BrewViewModel.kt` (`NOT_FILTER`,
   espresso preference), `domain/ai/DataQuality.kt` (`NOT_FILTER`, cold-brew temp rule, `brewerOf`),
   `ui/screens/recipes/RecipesViewModel.kt` + `RecipeListScreen.kt` (filter chips, `key = { it.name }`).
3. The unverified "K-Ultra = 10 clicks per turn" assumption is baked into: `GearScreen` K-Ultra
   preset and `dialWords`, `BrewScreen.describeClicks`, `ReviewScreen.dialReading`,
   `InsightsScreen.dialReading`, `PassportStats.GrindSeries.clicksPerTurn`, `GrindMove.dial()` /
   `GrindMove.dialReading`, `AnalysisPrompt` (`K_CURRENT_DIAL` only when cpr == 10, `example()`,
   system text "a clear miss is about one full turn"), `DataQuality` (`GRIND_UNDER_ONE_TURN` uses
   `clicks < cpr`; `GRIND_JUMP` uses `2 * cpr`), `BrewViewModel.lastTrustedClicks` (`c < cpr`),
   `DebugTools` seed, and tests `DataQualityTest`, `GrindMoveTest`.
   If the catalog says the K-Ultra is ~100 clicks per turn, the owner's filter brews at 60–85
   clicks would all be **distrusted** by `GRIND_UNDER_ONE_TURN`. The rule change in §3.4 MUST
   land before (or with) any per-turn correction.
4. For click grinders `grindSetting` is a text snapshot like `"75 clicks (7 turns + 5)"`, and
   `AnalysisPrompt.thisBrew` sends it to the model next to `grindClicks`. A corrected scale would
   contradict it; WP-3 stops sending it for click grinders.
5. Room is at version 3 with `exportSchema = false`; migrations are `private` in the companion;
   no schema JSON exists. MIGRATION_2_3 was verified on an emulator, 1_2 only by reading.
6. The JSON export (`BrewReport.json`, schema `"coffee-brew-log/1"`) is string-built, uses
   **labels** for enums (`"Medium-light"`, `"Flat and even"`), and lacks recipeId, grinderId,
   isFavourite, saved recipes, analyses and most bean/grinder fields.
7. AI settings live in DataStore file `coffee_ai` (keys `base_url`, `model`, `key_ct`, `key_hint`,
   `key_host`, `last_test_ok_at`, `last_test_sig`), key encrypted by `KeyVault`
   (alias `coffee_ai_api_key_v1`) and bound to the host it was pasted for. `ChatMessage.content`
   is a `String`. `ModelCatalog` pins three CommandCode models with prices and DeepSeek off-peak
   logic; cost is only known on the CommandCode host.
8. Analysis staleness is `stored.inputHash != current.inputHash`, worded "Brew edited since". Any
   prompt text change flips every stored analysis to that wording, which would then be false.
9. `RecipeDetailViewModel` seeds its state **synchronously** from `ExpertRecipes.byId` so the shared
   header exists on frame one. The new loader MUST keep a synchronous `byId`.
10. Android backup rules include only `coffee.db` and shared prefs. Files under `files/` (where
    photos will live) are therefore not in Android backup — keep it that way.
11. `CoffeeRepository.recipes` decodes saved-recipe JSON in the collector's context (main thread
    via `stateIn(viewModelScope)`). Add `.flowOn(Dispatchers.Default)`.
12. Catalog files being researched in parallel (observed 2026-11-17, still moving): `brewers.json`
    has `hario-v60-01/02/03`, `chemex`, `kalita-wave-155/185`, `hario-switch`, `aeropress`,
    `french-press`, `moka-pot`, `espresso-machine`, `cold-brew-jar`, `other`…; `beans.json`
    processes include `washed`, `natural`, `honey`, `anaerobic`, `carbonic-maceration`,
    `wet-hulled`, `unknown`; roast levels `extra-light`, `light`, `medium-light`, `medium`,
    `medium-dark`, `dark`, `omni`; `providers.json` ids `openai`, `anthropic`, `google-gemini`,
    `openrouter`, `deepseek`, `xai`, `groq`, `mistral`, `together`, `fireworks`, `perplexity`,
    `commandcode`, `ollama` (http://localhost), `lm-studio` (http://localhost), `custom`
    (empty baseUrl). Anthropic's `extraHeaders` carries its own `anthropic-version`.
    `grinders.json` is not written yet. The legacy maps in §2.4 are aligned to these ids and are
    guarded by a test, so a rename fails the build instead of silently orphaning old data.

---

## 2. Shared data (WP-1)

### 2.1 Repo layout

```
shared/
  catalog/grinders.json roasters.json origins.json beans.json brewers.json water.json providers.json
  recipes.json            (WP-1, from today's ExpertRecipes; research merged later, §2.7)
  principles.json         (WP-3, §8.9)
  schema/recipes.md       (WP-1)  backup-v2.md (WP-5, content in §10)  principles.md (WP-3)
  fixtures/backup-v2-sample.json  coffee-brew-log-1-sample.json   (WP-5)
```
`shared/` holds only `*.json` and `*.md`, total under 2 MB. A unit test enforces that (§2.8).

### 2.2 Build edits (WP-1, first commit, before any other package edits build files)

`gradle/libs.versions.toml` — add (use the newest stable that resolves with compileSdk 36; the
floors below are known to exist):
```toml
[versions]
exifinterface = "1.3.7"          # floor
androidxTestRunner = "1.6.2"     # floor
androidxTestExtJunit = "1.2.1"   # floor
androidxTestCore = "1.6.1"       # floor

[libraries]
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
androidx-exifinterface = { group = "androidx.exifinterface", name = "exifinterface", version.ref = "exifinterface" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }

[plugins]
room = { id = "androidx.room", version.ref = "room" }
```
Root `build.gradle.kts`: `alias(libs.plugins.room) apply false`.

`app/build.gradle.kts` (keep every existing line and comment; add):
```kotlin
import java.io.StringReader
import java.util.Properties

plugins { /* existing */ alias(libs.plugins.room) }

// Release signing lives OUTSIDE the repo. Point Gradle at a keystore.properties with
//   -Pcoffee.signing=C:/Users/<you>/.coffee/keystore.properties  (or that line in ~/.gradle/gradle.properties)
//   or the COFFEE_SIGNING environment variable (CI).
// It holds storeFile, storePassword, keyAlias, keyPassword. Without it assembleRelease still
// builds, unsigned, so other machines and CI never break.
val signingPropsPath: String? = providers.gradleProperty("coffee.signing").orNull
    ?: providers.environmentVariable("COFFEE_SIGNING").orNull
val signingProps: Properties? = signingPropsPath?.let { path ->
    val f = file(path)
    if (!f.isFile) null else Properties().apply {
        // fileContents keeps the configuration cache aware of the file.
        load(StringReader(providers.fileContents(layout.projectDirectory.file(f.absolutePath)).asText.get()))
    }
}

android {
    defaultConfig {
        versionCode = 2
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }   // BuildConfig.VERSION_NAME for backups + User-Agent
    sourceSets {
        getByName("main") { assets.srcDir(rootProject.file("shared")) }
        getByName("test") { resources.srcDir(rootProject.file("shared")) }   // JVM tests read the same JSON
    }
    // Schema docs stay out of the APK. If AGP 9 rejects this property, delete the line: the docs are a few KB.
    androidResources { ignoreAssetsPatterns += "!*.md" }
    signingConfigs {
        if (signingProps != null) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            /* existing minify/shrink lines */
            if (signingProps != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    /* existing */
    implementation(libs.androidx.exifinterface)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
```
Fallback if the Room Gradle plugin does not apply under AGP 9 built-in Kotlin: drop the plugin and
use `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` plus
`sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")`. Record which one was
used in the README "Two things AGP 9 needed" section.

Asset paths inside the APK and on the test classpath: `catalog/grinders.json`, …,
`recipes.json`, `principles.json`.

Lint: the existing comment "There are no test sources" becomes false; keep the settings, fix the
comment.

### 2.3 Kotlin catalog layer

Files (all new):
- `domain/catalog/CatalogModels.kt` — `@Serializable` DTOs, 1:1 with the schemas.
- `domain/catalog/CatalogSource.kt` — `interface CatalogSource { fun open(path: String): java.io.InputStream? }`
  and `class ClasspathCatalogSource : CatalogSource` (reads `javaClass.classLoader.getResourceAsStream(path)`).
- `domain/catalog/Catalog.kt` — the process-wide registry.
- `domain/catalog/LegacyIds.kt` — frozen legacy maps (§2.4).
- `data/catalog/AssetCatalogSource.kt` — `AssetManager.open(path)`; returns null on `FileNotFoundException`.
- `data/catalog/CatalogRepository.kt` — suspend wrappers on `Dispatchers.IO`, plus `search` (WP-2 fills, §7.1).

DTOs (exact field names; every field the schema marks optional gets a default):
```kotlin
@Serializable data class SourceRef(val who: String, val url: String = "", val note: String = "")

@Serializable data class GrindersFile(val version: Int, val updated: String, val grinders: List<JsonElement> = emptyList())
@Serializable data class CatalogGrinder(
    val id: String, val brand: String, val model: String,
    val kind: String = "hand",                 // hand | electric
    val burr: String = "",                     // conical | flat | ghost | blade
    val burrSizeMm: Double? = null,
    val popularity: Int = 3,                   // 1 = very common
    val setting: GrinderSetting,
    val ranges: Map<String, GrindRange> = emptyMap(),   // key = BrewMethod.id
    val rangeSources: List<SourceRef> = emptyList(),
    val confidence: String = "low",            // high | medium | low
    val notes: String = "",
) { val display: String get() = "$brand $model".trim() }
@Serializable data class GrinderSetting(
    val style: String,                         // clicks | numbers | stepless
    val unit: String = "",
    val min: Double, val max: Double, val step: Double,
    val lowerIsFiner: Boolean = true,
    val clicksPerRotation: Int? = null,
    val micronsPerStep: Double? = null,
    val dialReading: String = "",
    val entryHint: String = "",
    val dialFormat: String? = null,            // OPTIONAL extension, see §3.2 / open question 1
)
@Serializable data class GrindRange(val from: Double, val to: Double)

@Serializable data class CatalogRoaster(val id: String, val name: String, val country: String = "",
    val city: String = "", val area: String = "", val notable: String = "")
@Serializable data class CatalogOrigin(val id: String, val country: String, val iso2: String = "",
    val area: String = "", val growingRegions: List<String> = emptyList(),
    val commonProcesses: List<String> = emptyList(), val commonVarietals: List<String> = emptyList())
@Serializable data class Term(val id: String, val label: String)
@Serializable data class DescribedTerm(val id: String, val label: String, val description: String = "")
@Serializable data class ProcessTerm(val id: String, val label: String, val group: String = "other", val description: String = "")
@Serializable data class VarietalTerm(val id: String, val label: String, val aliases: List<String> = emptyList(), val species: String = "")
data class BeanVocabulary(val species: List<Term>, val processes: List<ProcessTerm>, val varietals: List<VarietalTerm>,
    val lotTypes: List<DescribedTerm>, val decafMethods: List<Term>, val roastLevels: List<DescribedTerm>, val roastIntents: List<Term>)
@Serializable data class CatalogBrewer(val id: String, val name: String, val brand: String = "",
    val category: String = "other", val method: String = "pourover", val filter: String = "paper", val notes: String = "")
@Serializable data class CatalogWater(val id: String, val label: String, val kind: String = "other",
    val description: String = "", val ghPpm: Double? = null, val khPpm: Double? = null)
@Serializable data class ListModels(val path: String = "/models", val works: Boolean = false, val note: String = "")
@Serializable data class CatalogModel(val id: String, val label: String = "", val inputPerM: Double? = null,
    val outputPerM: Double? = null, val vision: Boolean = false, val default: Boolean = false, val why: String = "")
@Serializable data class CatalogProvider(val id: String, val name: String, val baseUrl: String = "",
    val api: String = "openai-chat", val auth: String = "bearer",
    val extraHeaders: Map<String, String> = emptyMap(), val browserHeaders: Map<String, String> = emptyMap(),
    val keyUrl: String = "", val keyHint: String = "", val browserCors: String = "unknown", val corsEvidence: String = "",
    val listModels: ListModels = ListModels(), val models: List<CatalogModel> = emptyList(),
    val pricesCheckedOn: String = "", val notes: String = "")
```
Decoding: `Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }`.
Each file's list is read as `JsonArray` and decoded **entry by entry** with `runCatching`; a bad
entry is skipped and recorded in `Catalog.problems` as `"catalog/grinders.json[12] (id=…): <reason>"`.
One bad entry never hides a file; a missing file yields an empty list plus a problem line. The app
never crashes on catalog data.

`Catalog` API (exact; all lists are `by lazy(LazyThreadSafetyMode.SYNCHRONIZED)`):
```kotlin
object Catalog {
    fun install(source: CatalogSource)          // CoffeeApp.onCreate, first line. Default if never called: ClasspathCatalogSource (JVM tests).
    val grinders: List<CatalogGrinder>
    val roasters: List<CatalogRoaster>
    val origins: List<CatalogOrigin>
    val beans: BeanVocabulary
    val brewers: List<Brewer>                   // domain Brewer (§2.5); LegacyIds.FALLBACK_BREWERS appended for any legacy id missing from the file
    val water: List<CatalogWater>
    val providers: List<CatalogProvider>
    val recipes: List<Recipe>                   // from recipes.json, file order
    val principles: List<Principle>             // WP-3 adds the DTO; empty if the file is absent
    val problems: List<String>
    fun grinder(id: String?): CatalogGrinder?   // map lookups built lazily from the lists
    fun roaster(id: String?): CatalogRoaster?
    fun origin(id: String?): CatalogOrigin?
    fun brewer(id: String?): Brewer?
    fun water(id: String?): CatalogWater?
    fun provider(id: String?): CatalogProvider?
    fun recipe(id: String?): Recipe?
    fun process(id: String?): ProcessTerm?
    fun varietal(id: String?): VarietalTerm?
    fun label(kind: TermKind, id: String?): String?   // TermKind = Species, Process, Varietal, LotType, Decaf, RoastLevel, RoastIntent; falls back to LegacyIds labels, then null
    fun preloadAll()                            // touches lazies in this order: brewers, recipes, beans, grinders, roasters, origins, water, providers, principles
}
```
`CoffeeApp.onCreate()` (WP-1 edits): `Catalog.install(AssetCatalogSource(assets))` first, then
`appScope.launch(Dispatchers.IO) { Catalog.preloadAll(); StartupTasks.run(this@CoffeeApp) }`.
A main-thread access while preload runs blocks on the same lazy instead of parsing twice; brewers
and recipes go first because Home needs them on frame one.

`CatalogRepository(private val io: CoroutineDispatcher = Dispatchers.IO)`:
`suspend fun grinders()`, `roasters()`, `origins()`, `beans()`, `brewers()`, `water()`,
`providers()` — each `withContext(io) { Catalog.x }`. Held as `CoffeeApp.catalog`.

### 2.4 Legacy id maps (frozen; WP-1)

`domain/catalog/LegacyIds.kt` — **never edit after release**; MIGRATION_3_4 builds its SQL from these.
```kotlin
object LegacyIds {
    /** Brewer enum names AND labels written by 1.x → brewers.json ids. */
    val BREWER: Map<String, String> = linkedMapOf(
        "Hario V60" to "hario-v60-02", "V60" to "hario-v60-02",
        "Chemex" to "chemex",
        "Kalita Wave" to "kalita-wave-185", "Kalita" to "kalita-wave-185",
        "Hario Switch" to "hario-switch", "Switch" to "hario-switch",
        "AeroPress" to "aeropress",
        "French Press" to "french-press", "FrenchPress" to "french-press",
        "Moka Pot" to "moka-pot", "Moka" to "moka-pot",
        "Espresso" to "espresso-machine",
        "Cold Brew" to "cold-brew-jar", "ColdBrew" to "cold-brew-jar",
    )
    /** 1.x Process enum NAMES (Room) → beans.json process ids. "" = not recorded. */
    val PROCESS: Map<String, String> = linkedMapOf("Washed" to "washed", "Natural" to "natural", "Honey" to "honey",
        "Anaerobic" to "anaerobic", "CarbonicMaceration" to "carbonic-maceration", "Wet" to "wet-hulled",
        "Other" to "unknown", "Unknown" to "")
    /** 1.x Process LABELS (old JSON export) → ids. */
    val PROCESS_LABEL: Map<String, String> = linkedMapOf("Washed" to "washed", "Natural" to "natural", "Honey" to "honey",
        "Anaerobic" to "anaerobic", "Carbonic maceration" to "carbonic-maceration", "Wet-hulled" to "wet-hulled",
        "Other" to "unknown", "Unknown" to "")
    val ROAST_LEVEL: Map<String, String> = linkedMapOf("Light" to "light", "MediumLight" to "medium-light",
        "Medium" to "medium", "MediumDark" to "medium-dark", "Dark" to "dark", "Unknown" to "")
    val ROAST_LEVEL_LABEL: Map<String, String> = linkedMapOf("Light" to "light", "Medium-light" to "medium-light",
        "Medium" to "medium", "Medium-dark" to "medium-dark", "Dark" to "dark", "Unknown" to "")
    /** Used only when brewers.json lacks a legacy id, so 1.x data always resolves. Labels are 1.x labels. */
    val FALLBACK_BREWERS: List<Brewer>   // 9 entries: hario-v60-02 "Hario V60" Hario cone pourover paper; chemex "Chemex" Chemex cone pourover paper;
                                         // kalita-wave-185 "Kalita Wave" Kalita flat-bottom pourover paper; hario-switch "Hario Switch" Hario hybrid pourover paper;
                                         // aeropress "AeroPress" AeroPress hybrid aeropress paper; french-press "French Press" "" immersion frenchPress metal;
                                         // moka-pot "Moka Pot" "" pressure moka metal; espresso-machine "Espresso" "" espresso espresso metal; cold-brew-jar "Cold Brew" "" cold coldBrew paper
}
```
The SQL `CASE` expressions in §4.3 are generated from these maps (`LegacyIds.caseSql(column, map)`
returns `CASE \`col\` WHEN 'k' THEN 'v' … ELSE <else> END`, single quotes doubled).

### 2.5 Brewer: enum → catalog-backed value (WP-1)

New `domain/Brewer.kt`; delete the enum from `domain/Recipe.kt`.
```kotlin
enum class BrewMethod(val id: String, val label: String) {
    Espresso("espresso", "Espresso"), Moka("moka", "Moka pot"), AeroPress("aeropress", "AeroPress"),
    PourOver("pourover", "Pour-over"), FrenchPress("frenchPress", "French press"), ColdBrew("coldBrew", "Cold brew"),
    Siphon("siphon", "Siphon"), Turkish("turkish", "Turkish / cezve");
    /** Runs water through a bed at a filter ratio. Same set the 1.x data-quality rules used. */
    val isFilterStyle: Boolean get() = this != Espresso && this != Moka && this != ColdBrew && this != Turkish
    companion object { fun of(id: String?): BrewMethod = entries.firstOrNull { it.id == id } ?: PourOver }
}

/** The nine drawings BrewerIcon knows. Names match the 1.x enum so CoffeeArt changes only its `when` subject. */
enum class BrewerArt { V60, Chemex, Kalita, Switch, AeroPress, FrenchPress, Moka, Espresso, ColdBrew }

@Serializable(with = BrewerSerializer::class)
data class Brewer(
    val id: String, val label: String, val brand: String = "", val category: String = "other",
    val method: BrewMethod = BrewMethod.PourOver, val filter: String = "paper", val notes: String = "",
) {
    val art: BrewerArt get() = BrewerArts.of(this)
    /** "V60", "Kalita", "French press"… : 1.x short names for legacy ids, else label minus a leading brand, ≤ 14 chars. */
    val shortName: String get() = …
    /** Brewers that take the same recipes: art for pour-over (V60-01/02/03 are one family), method otherwise. */
    val family: String get() = if (method == BrewMethod.PourOver) "pourover:${art.name}" else method.id
    companion object { fun unknown(raw: String): Brewer = Brewer(id = "other", label = raw.ifBlank { "Other" }) }
}

object Brewers {
    val all: List<Brewer> get() = Catalog.brewers
    fun byId(id: String?): Brewer? = Catalog.brewer(id)
    /** id → legacy enum name/label → case-insensitive label → null. Never throws. */
    fun resolveOrNull(raw: String?): Brewer?
    fun resolve(raw: String): Brewer = resolveOrNull(raw) ?: Brewer.unknown(raw)
}

object BrewerSerializer : KSerializer<Brewer> {   // encodes the id string; decodes via Brewers.resolve
    override val descriptor = PrimitiveSerialDescriptor("com.laksh.coffee.Brewer", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Brewer) = encoder.encodeString(value.id)
    override fun deserialize(decoder: Decoder): Brewer = Brewers.resolve(decoder.decodeString())
}
```
`BrewerArts.of(b)` (exact precedence): id `chemex` → Chemex; method Moka/Turkish → Moka;
Espresso → Espresso; ColdBrew → ColdBrew; AeroPress → AeroPress; FrenchPress → FrenchPress;
Siphon → Chemex; PourOver: category `flat-bottom` → Kalita, `hybrid`/`immersion` → Switch, else V60.
Catalog → domain mapping: `CatalogBrewer(name→label, method→BrewMethod.of)`.

Call-site edits (WP-1, mechanical, ≤ 10 lines per file):
- `CoffeeArt.kt`: `BrewerIcon(brewer: Brewer, …)` keeps its signature; `when (brewer)` →
  `when (brewer.art)` and `Brewer.X ->` → `BrewerArt.X ->`; `contentDescription = brewer.label`.
- `Grouped.kt`: delete `brewerFromLabel` body → `Brewers.resolveOrNull(label)`; delete the
  `Brewer.shortName` extension (now a property); `rowTag()` → `if (art == BrewerArt.FrenchPress) "Press" else shortName`.
  `BrewRow` resolves `log.brewerId?.let(Brewers::byId) ?: Brewers.resolveOrNull(log.brewer)`.
- `BrewScreen.kt`: `POUR_OVER` → `setOf(BrewerArt.V60, BrewerArt.Chemex, BrewerArt.Kalita, BrewerArt.Switch)` and the check `recipe.brewer.art in POUR_OVER`.
- `BrewViewModel.kt`: `NOT_FILTER` → `brewer.method.isFilterStyle`; espresso preference →
  `recipe.brewer.method == BrewMethod.Espresso`; `lastTrustedClicks` same-brewer match →
  `log.brewerId == b.id || log.brewer == b.label`; `finish()` also writes `brewerId = recipe.brewer.id`,
  `waterId = settings.defaultWaterId.ifBlank { null }`, and for non-click grinders
  `grindValue = st.grindSetting.trim().replace(',', '.').toDoubleOrNull()`.
- `DataQuality.kt`: see §3.4.
- `RecipesViewModel.kt` / `RecipeListScreen.kt`: `items(state.brewers, key = { it.id })`; equality works on the data class.
- `HistoryScreen.kt`: brewer tag via `Brewers.resolveOrNull`.
- `src/debug/.../DebugTools.kt`: seed `brewerId` with the legacy map, `processId`/`roastLevelId`
  ids instead of the deleted enums, and keep the 10-click K-Ultra row exactly as today (it is the
  fixture that proves 1.x behaviour survives); add a second, catalog-linked K-Ultra only if useful.
- Enum removal fallout (§4.2 deletes `Process`/`RoastLevel`): `GearScreen.BeanSheet` process and
  roast chips loop over `Catalog.beans.processes` / `roastLevels` (WP-2 replaces the sheet later);
  `BrewReport.markdown`/`json` and `AnalysisPrompt` use
  `Catalog.label(TermKind.Process, bean.processId) ?: "Unknown"` (same for roast); `DataQuality`'s
  `LIGHT_ROASTS` becomes the id set `{"extra-light", "light", "medium-light"}`; `Converters` loses
  the two enum converters. Catalog labels may differ slightly from 1.x labels, which changes prompt
  bytes and shows stored analyses as "edited since" until WP-3's §8.6 wording lands — expected in
  dev builds, never shipped.

### 2.6 `Recipe` serialization and `ExpertRecipes` loader (WP-1)

`domain/Recipe.kt` changes (additive defaults keep every saved JSON decodable):
```kotlin
@Serializable data class RecipeSource(val who: String, val url: String = "", val note: String = "")

@Serializable data class Recipe(
    /* id … steps unchanged */
    val brewer: Brewer,                                         // now catalog-backed, serialized as id
    @Serializable(with = ArgbHexSerializer::class) val accent: Long,   // writes "#RRGGBB" (or "#AARRGGBB" if alpha ≠ FF); reads number OR string
    val isBuiltIn: Boolean = true,
    val fidelity: String = "",                                  // published | adapted | community | custom
    val sources: List<RecipeSource> = emptyList(),
    val basedOn: String? = null,                                // id this was duplicated from
)
```
`RecipeStep` unchanged (`kind` serializes as the `StepKind` enum name: Prep, Bloom, Pour, Swirl,
Stir, Steep, Press, Drawdown, Serve — the web app uses the same strings).

`shared/recipes.json` format (also written to `shared/schema/recipes.md`):
```json
{
  "version": 1,
  "updated": "2026-11-17",
  "recipes": [
    {
      "id": "hoffmann-v60", "name": "The Ultimate V60 Technique", "author": "James Hoffmann",
      "brewer": "hario-v60-02", "doseG": 30.0, "waterG": 500.0, "grind": "Medium-fine", "tempC": 100,
      "blurb": "…", "sourceNote": "…", "fidelity": "published", "sources": [],
      "prep": ["…"], "tips": ["…"],
      "steps": [{"kind": "Bloom", "title": "Bloom to 60 g", "detail": "…", "startSec": 0, "endSec": 45, "targetWaterG": 60}],
      "accent": "#E9A855"
    }
  ]
}
```
`isBuiltIn` is omitted in the file (default true). `targetWaterG` is cumulative grams on the scale.

How WP-1 produces the file without hand-copying 500 lines:
1. Before touching `Recipe.kt`, add a throwaway JVM test that encodes `ExpertRecipes.all` to a
   `JsonElement`, rewrites `brewer` with `LegacyIds.BREWER`, `accent` to hex, adds
   `fidelity` from the table below and `"sources": []`, wraps it in the envelope and writes
   `../shared/recipes.json` with `prettyPrintIndent = "  "`. Run it, validate with python, delete the test.
2. Fidelity: `hoffmann-v60`, `kasuya-46`, `hoffmann-french-press`, `hoffmann-aeropress` →
   `published`; `switch-hybrid` → `community`; the other nine (`hoffmann-moka`, `hedrick-aeropress`,
   `rao-v60`, `hedrick-espresso`, `perger-v60`, `gagne-v60`, `wendelboe-chemex`, `onyx-kalita`,
   `eckroth-v60`) → `adapted`. `sourceNote` text stays exactly as today (the UI shows it).

`domain/recipes/ExpertRecipes.kt` becomes:
```kotlin
object ExpertRecipes {
    val all: List<Recipe> get() = Catalog.recipes
    fun byId(id: String): Recipe? = Catalog.recipe(id)        // synchronous: RecipeDetailViewModel relies on it
    val authors: List<String> get() = all.map { it.author }.distinct().sorted()
    /** The 14 ids shipped in 1.x. Old logs point at them; they must resolve forever. */
    val LEGACY_IDS = listOf("hoffmann-v60", "kasuya-46", "hoffmann-french-press", "hoffmann-aeropress",
        "hedrick-aeropress", "rao-v60", "hedrick-espresso", "perger-v60", "gagne-v60", "wendelboe-chemex",
        "onyx-kalita", "eckroth-v60", "switch-hybrid", "hoffmann-moka")
}
```
`CoffeeRepository.recipes`: add `.flowOn(Dispatchers.Default)`; built-in ids come from
`ExpertRecipes.all`. Saved JSON keeps decoding (`"brewer":"V60"` resolves through `LegacyIds`).

### 2.7 Merging recipe research later (rules for whoever does it — WP-1 if the research output exists when it runs, else WP-6)

- Existing ids keep their ids. For an existing id, only `sources`, `fidelity`, `sourceNote` and
  wording may change — **except** when research shows a recipe marked `published` misquotes its
  author's published numbers; then fix the numbers and say so in `sourceNote`
  ("Corrected 2026-11 to match …"). Old logs compare against the recipe they point at, so number
  changes to `adapted` recipes are not allowed; publish a new id instead.
- New recipes need complete numbers (dose, water, temp, every step's timing and cumulative target)
  **from a cited source**, at least one `sources[]` entry with a URL, and a `fidelity`. A recipe
  whose numbers are not all sourced is either `adapted` (and says which numbers are ours) or left out.
- New ids: `<author-surname>-<brewer-or-name>` lowercase-kebab, never starting with `custom-`.
- Order: the 14 legacy recipes first, then new ones grouped by author.

### 2.8 WP-1 tests (JVM, `app/src/test/...`)

- `catalog/CatalogParseTest`: every file parses; `Catalog.problems` is empty; `version == 1`;
  ids unique per file and match the kebab regex; cross refs hold (origin `commonProcesses` ⊂
  process ids, `commonVarietals` ⊂ varietal ids, varietal `species` ⊂ species ids, brewer `method`
  and grinder `ranges` keys ∈ `BrewMethod` ids, grinder `burr` ∈ {conical, flat, ghost, blade, ""},
  `style` ∈ {clicks, numbers, stepless}); for `style == "clicks"`, `min`, `max`, `step` and every
  range bound are whole numbers and `step == 1.0` (stored unit is total clicks, §3.1); every
  provider with an `https` baseUrl passes `BaseUrl.check`; each provider with models has exactly
  one `default`.
- `catalog/LegacyIdsTest`: every value in `LegacyIds.BREWER/PROCESS/ROAST_LEVEL` (non-empty) is an
  id in the shipped catalog (so `FALLBACK_BREWERS` is never actually used in a release).
- `catalog/SharedDirHygieneTest`: `../shared` contains only `.json`/`.md`, total < 2 MB.
- `recipes/RecipeIdsTest`: `ExpertRecipes.LEGACY_IDS` all resolve, in that order at the head of
  `all`; ids unique; no id starts with `custom-`; every built-in has contiguous steps
  (`steps[i].startSec == steps[i-1].endSec`), non-decreasing `targetWaterG`, and
  `max(targetWaterG) == waterG`; golden numbers: `hoffmann-v60` 30 g / 500 g / 100 °C / totalSec 210 /
  6 steps; `kasuya-46` 20 g / 300 g / 92 °C / totalSec 210.
- `recipes/RecipeSerializationTest`: a 1.x saved JSON string (enum brewer `"V60"`, numeric accent
  `4293503061`) decodes to brewer id `hario-v60-02` and accent `0xFFE9A855`; re-encoding writes
  `"brewer":"hario-v60-02"` and `"accent":"#E9A855"`.
- Update `DataQualityTest`, `GrindMoveTest` only where §3 changes behaviour on purpose; their
  10-click K-Ultra fixtures stay valid (legacy rows keep 1.x behaviour, §3.2).

---

## 3. Grinder scale semantics (WP-1)

### 3.1 The stored number

The stored grind is always **the number in the grinder's own setting unit**:
- `style == clicks`: total clicks from burr contact (zero), an integer, in `brew_logs.grindClicks`.
  For the owner's K-Ultra that is exactly what they have been typing (dial 7.5 → 75), so no logged
  brew is ever rescaled.
- `style == numbers` or `stepless`: the dial number as printed (e.g. Niche 14.5, Encore 18), a
  double, in the new `brew_logs.grindValue` (§4). `grindSetting` keeps the free text.
- Legacy `SettingStyle.Microns` is treated as `numbers` with unit "µm".

### 3.2 `domain/grind/DialFormat.kt` and `GrindScale.kt`

```kotlin
sealed interface DialFormat {
    data object Plain : DialFormat                                         // "14.5" — what you type is what you see
    data class TurnsAndClicks(val clicksPerTurn: Int) : DialFormat         // "3 turns + 2"
    data class TurnsDotClicks(val clicksPerTurn: Int) : DialFormat         // "7.5" = 7 turns + 5 clicks; 1.x behaviour, only for cpr ≤ 10
    data class NumberTick(val clicksPerRotation: Int, val ticksPerNumber: Int = 10) : DialFormat  // numbered dial, a number every N clicks
    fun encode(): String     // "plain" | "turns" | "turns-dot" | "number-tick:<n>"
    companion object { fun decode(s: String, cpr: Int?): DialFormat? }    // null for "" (auto) or garbage
}
```
Resolution (`GrindScale.of(grinder, catalog = Catalog.grinder(grinder.catalogId))`), first match wins:
1. `grinder.dialFormat` non-empty and decodable → that (the user chose it in the editor).
2. `catalog?.setting?.dialFormat` decodable → that (optional schema extension, open question 1).
3. `catalog?.id in NumberedDials.IDS` **and** the row's `clicksPerRotation` equals the catalog's
   (i.e. the row was created from the catalog or the user accepted its numbers) → `NumberTick(cpr, 10)`.
   `NumberedDials.IDS` is a small frozen set in `domain/grind/NumberedDials.kt`; WP-1 fills it by
   reading each grinders.json entry's `dialReading` text for dials that print a number every ten
   clicks (1Zpresso-style external dials). It MUST include the K-Ultra if its `dialReading` says
   so; never add an id on a guess.
4. Legacy: `settingStyle == Clicks && clicksPerRotation == 10` and (`catalogId == null` or the
   catalog's cpr differs — a linked row whose scale notice is still pending) → `TurnsDotClicks(10)`
   (exactly today's output: 75 → "7 turns + 5 · dial 7.5"). This keeps the owner's captions
   unchanged until they tap "Use these numbers" (§7.4).
5. `style == clicks && cpr > 0` → `TurnsAndClicks(cpr)`.
6. else `Plain`.

```kotlin
data class GrindScale(
    val style: SettingStyle, val unit: String, val min: Double, val max: Double, val step: Double,
    val lowerIsFiner: Boolean, val clicksPerRotation: Int?, val micronsPerStep: Double?,
    val dial: DialFormat, val ranges: Map<BrewMethod, GrindRange>, val rangeSources: List<SourceRef>,
    val confidence: String?, val dialReadingHelp: String, val entryHint: String,
) {
    val usesClicks: Boolean
    val finerDirection: String          // "FEWER clicks is finer" | "MORE clicks is finer" | "LOWER settings are finer" | "HIGHER settings are finer"
    val finerWords: String              // lowercase version used in UI ("fewer clicks is finer")
    fun reading(value: Double): String? // dial words: Plain → null; TurnsAndClicks → "3 turns + 2"; TurnsDot → "7.5"; NumberTick → "7.5" (first rotation) or "1 turn + 2.5"
    fun describe(value: Double?): String   // the caption under the big number; see vectors
    fun fromReading(r: DialReading): Double?                 // inverse of reading()
    fun parseReading(text: String): Double?                  // "7.5", "1+2.5", "3 + 2" per format
    fun format(value: Double): String                        // "75" for clicks, "14.5" for numbers
    fun moves(method: BrewMethod?): MoveSizes                // §3.3
    fun rangeFor(method: BrewMethod?): GrindRange?
    val espressoFloor: Double?                               // ranges[Espresso]?.from
    companion object { fun of(g: GrinderEntity, catalog: CatalogGrinder? = Catalog.grinder(g.catalogId)): GrindScale }
}
data class DialReading(val turns: Int = 0, val number: Int = 0, val tick: Int = 0, val value: Double? = null)
```
`GrinderEntity` gets `fun scale(): GrindScale = GrindScale.of(this)`; `describeClicks()` and
`finerDirection` delegate to it. Values from the entity row win over the catalog for
cpr/microns/min/max/step/lowerIsFiner (the row is what the user saw and accepted); the catalog
supplies ranges, sources, confidence, `dialReading`, `entryHint`.

Test vectors (`grind/GrindScaleTest`, MUST pass):
| scale | value | `reading` | `describe` |
|---|---|---|---|
| legacy cpr 10, no catalog | 75 | "7.5" | "7 turns + 5 · dial 7.5" (+ " · ≈ N µm" when microns set — today's BrewScreen string) |
| NumberTick(100,10), 20 µm | 75 | "7.5" | "dial 7.5 · ≈ 1,500 µm" |
| NumberTick(100,10) | 125 | "1 turn + 2.5" | "dial 1 turn + 2.5" |
| NumberTick(90,10) | 75 | "7.5" | "dial 7.5" |
| TurnsAndClicks(12) | 25 | "2 turns + 1" | "2 turns + 1" |
| Plain numbers | 14.5 | null | "" |
`fromReading`: NumberTick → `turns*cpr + number*tpn + tick` (reject `tick ≥ tpn` or
`number*tpn ≥ cpr`); TurnsAndClicks/TurnsDot → `turns*cpr + tick` (reject `tick ≥ cpr`).
`parseReading`: NumberTick `^\s*(?:(\d+)\s*\+\s*)?(\d+)(?:[.,](\d))?\s*$` (turns optional);
TurnsAndClicks `^\s*(\d+)\s*[+.,]\s*(\d+)\s*$`; Plain `toDouble` after `,`→`.`.

### 3.3 Move sizes (app heuristic, not attributed to anyone)

`data class MoveSizes(val nudge: Double, val clearMiss: Double, val maxAtOnce: Double, val jumpLimit: Double, val basis: Basis)`
with `enum Basis { Microns, Range, LegacyTurn, Generic }`. All values rounded to `step`, ≥ `step`.
- `micronsPerStep = µ > 0`: nudge = 40/µ, clearMiss = max(2·nudge, 120/µ), maxAtOnce = max(clearMiss, 250/µ), jumpLimit = 500/µ.
  (K-Ultra at 20 µm → 2 / 6 / 13 / 25 clicks.)
- else a range for the method with width w: nudge = 0.05w, clearMiss = 0.15w, maxAtOnce = 0.3w, jumpLimit = 0.5w.
- else clicks with `cpr in 1..40`: nudge = 2, clearMiss = cpr, maxAtOnce = 2·cpr, jumpLimit = 2·cpr
  — identical to 1.x ("a nudge is 2-5 clicks, a clear miss is one full turn, never more than two turns").
- else nudge = 2·step, clearMiss = 5·step, maxAtOnce = 10·step, jumpLimit = 20·step.

### 3.4 `DataQuality` changes (WP-1)

- `check(...)` gains `scale: GrindScale? = grinder?.scale()` and uses `brewerOf(log, recipe)`:
  `recipe?.brewer ?: Brewers.byId(log.brewerId) ?: Brewers.resolveOrNull(log.brewer)`;
  `isFilterStyle(b) = b == null || b.method.isFilterStyle`; temp rule skips `BrewMethod.ColdBrew`.
- `GRIND_UNDER_ONE_TURN` (code name kept: it is stored in `flagsJson`) applies **only** when
  `scale.moves(...).basis == LegacyTurn` (cpr 1..40 and no microns), exactly as today.
- New `FlagCode.GRIND_TOO_FINE_FOR_METHOD` (Distrust, field GRIND): filter-style brew and
  `scale.espressoFloor != null` and `clicks < espressoFloor`. Message: "`$clicks` is finer than
  this grinder's espresso starting range (`from–to`, `<who>`). For a filter brew that is almost
  certainly the dial reading typed the wrong way." For `NumberTick` add "On this grinder the dial
  reading 7.5 is stored as 75." `modelMessage` omits the numbers (as today's pattern).
- `GRIND_JUMP` limit → `scale.moves(method).jumpLimit` (legacy rows: `2·cpr`, unchanged).
- `BrewViewModel.lastTrustedClicks` filter `c < cpr` → "not distrusted by the same two rules"
  (call a shared `DataQuality.grindLooksTyped(clicks, scale, method)`).

### 3.5 `GrindMove` (WP-1, `domain/ai/AnalysisResult.kt`)

Add `val dialFormat: String = ""` (stored results without it decode as before). `dial()` shows
"dial A → B" for `TurnsDotClicks` and `NumberTick` (decode `dialFormat`; empty + `clicksPerTurn == 10`
= legacy turns-dot). Turn words ("one full turn") only for `TurnsAndClicks`/`TurnsDotClicks`.
`resolve(...)` sets `dialFormat = g.scale().dial.encode()` and replaces the ">2 full turns" warning
with `abs(delta) > moves.maxAtOnce`: keep the exact 1.x text when `basis == LegacyTurn`, else
"That is more than $maxAtOnce clicks in one go. Consider going halfway first."
Keep `GrindMove.dialReading(clicks)` (legacy) for `AnalysisPrompt` until WP-3 replaces it.

Replace the private dial helpers with `GrindScale`: `GearScreen.dialWords`, `BrewScreen.describeClicks`
(body becomes `g.scale().describe(clicks?.toDouble())`, "Set your grind" when null),
`ReviewScreen.dialReading`, `InsightsScreen.dialReading` (`GrindSeries` gets `dialFormat: String`
and `scale` is rebuilt from it), `PassportStats.grindSeries`.

---

## 4. Room v3 → v4 (WP-1)

### 4.1 Procedure (order matters)

1. With entities **unchanged**, add the Room plugin + `room { schemaDirectory }`, set
   `exportSchema = true`, build (`./gradlew :app:kspDebugKotlin`), and commit
   `app/schemas/com.laksh.coffee.data.db.CoffeeDatabase/3.json`. Without this file
   `MigrationTestHelper` cannot create a v3 database.
2. Move migrations to `data/db/Migrations.kt` as `internal val MIGRATION_1_2`, `MIGRATION_2_3`
   (bodies byte-identical), add `MIGRATION_3_4`, register all three.
3. Change entities (§4.2), bump `version = 4`, build, commit `4.json`. Copy the `photos` CREATE and
   index statements from `4.json` into `MIGRATION_3_4` verbatim (§4.3 lists the expected text).
4. Add the migration test (§4.5). Never `fallbackToDestructiveMigration`.

### 4.2 Entity changes

Helper: `fun newUid(): String = java.util.UUID.randomUUID().toString()` in `data/db/entity/Uid.kt`.
Every new NOT NULL column has `@ColumnInfo(defaultValue = …)` so a fresh install and a migrated
database have identical schemas.

`BeanEntity` (add `indices = [Index(value = ["uid"], unique = true)]`):
```kotlin
@ColumnInfo(defaultValue = "") val uid: String = newUid(),
@ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
val roasterId: String? = null,                         // roasters.json id; `roaster` stays the display/custom name
val originId: String? = null,                          // origins.json id; `origin` stays the display/custom country
// `region` (existing) = growing region
@ColumnInfo(defaultValue = "") val farm: String = "",  // farm / estate
@ColumnInfo(defaultValue = "") val producer: String = "",
@ColumnInfo(defaultValue = "") val washingStation: String = "",
val lotTypeId: String? = null,
val speciesId: String? = null,
@ColumnInfo(defaultValue = "") val varietalIds: List<String> = emptyList(),   // Converters list (0x1F-joined); `varietal` stays free text for custom names
@ColumnInfo(name = "process") val processId: String = "",     // WAS enum Process; migrated to ids; "" = not recorded
@ColumnInfo(defaultValue = "0") val isDecaf: Boolean = false,
val decafMethodId: String? = null,
@ColumnInfo(name = "roastLevel") val roastLevelId: String = "", // WAS enum RoastLevel; migrated to ids
val roastIntentId: String? = null,
// altitudeMasl (existing TEXT) now holds "1700" or "1700-1900" metres; the form edits two numbers
@ColumnInfo(defaultValue = "") val harvest: String = "",       // free text, e.g. "2025/26", "Oct 2025"
val restDays: Int? = null,                                     // the rest the roaster suggests before opening
val priceAmount: Double? = null,                               // `price` TEXT stays for 1.x values
@ColumnInfo(defaultValue = "") val currency: String = "",      // ISO 4217, e.g. "GBP"
```
Delete `enum Process`, `enum RoastLevel` and their converters (labels now come from the catalog;
`Catalog.label(TermKind.Process, id)`). `display` unchanged.

`GrinderEntity` (add `indices = [Index(value = ["uid"], unique = true)]`):
```kotlin
@ColumnInfo(defaultValue = "") val uid: String = newUid(),
@ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
val catalogId: String? = null,
@ColumnInfo(defaultValue = "") val dialFormat: String = "",   // "" = auto (§3.2)
```
`BrewLogEntity` (add `Index(value = ["uid"], unique = true)` to its indices):
```kotlin
@ColumnInfo(defaultValue = "") val uid: String = newUid(),
@ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
val brewerId: String? = null,        // brewers.json id; `brewer` stays the label snapshot (same pattern as recipeId/recipeName)
val grindValue: Double? = null,      // numbers/stepless grinders (§3.1)
val waterId: String? = null,         // water.json id snapshotted from settings at brew time
```
`AnalysisEntity` (add `Index(value = ["uid"], unique = true)`):
```kotlin
@ColumnInfo(defaultValue = "") val uid: String = newUid(),
@ColumnInfo(defaultValue = "") val providerId: String = "",
@ColumnInfo(defaultValue = "0") val photoCount: Int = 0,
@ColumnInfo(defaultValue = "android") val origin: String = "android",   // android | web — where the analysis ran
```
New `data/db/entity/PhotoEntity.kt`:
```kotlin
@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(entity = BrewLogEntity::class, parentColumns = ["id"], childColumns = ["brewLogId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BeanEntity::class, parentColumns = ["id"], childColumns = ["beanId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["uid"], unique = true), Index("brewLogId"), Index("beanId")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = newUid(),
    val brewLogId: Long? = null,
    val beanId: Long? = null,
    val kind: String,                     // bed | cup | bag | other
    val fileName: String,                 // relative to filesDir/photos, "<uid>.jpg"
    val widthPx: Int, val heightPx: Int, val bytes: Long,
    val takenAt: Long, val createdAt: Long,
    @ColumnInfo(defaultValue = "") val caption: String = "",
    @ColumnInfo(defaultValue = "1") val sendToAi: Boolean = true,
)
```
`CoffeeDatabase`: add `PhotoEntity::class`, `abstract fun photoDao(): PhotoDao`, `version = 4`,
`exportSchema = true`, `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`.

DAO additions (`data/db/dao/Daos.kt`, `data/ai/AnalysisDao.kt`, new `PhotoDao`):
- `BeanDao`: `suspend fun all(): List<BeanEntity>`, `byUid(uid: String): BeanEntity?`.
- `GrinderDao`: `suspend fun all(): List<GrinderEntity>` (archived too), `byUid`.
- `BrewLogDao`: `suspend fun all(): List<BrewLogEntity>`, `byUid`, `suspend fun countAll(): Int`.
- `SavedRecipeDao`: `suspend fun all(): List<SavedRecipeEntity>`.
- `AnalysisDao`: `suspend fun all(): List<AnalysisEntity>`, `byUid`.
- `PhotoDao`: `observeForBrew(brewLogId): Flow<List<PhotoEntity>>` (ORDER BY takenAt),
  `observeForBean(beanId)`, `suspend forBrew(id)`, `forBean(id)`, `byUid`, `all()`, `insert(p): Long`,
  `update(p)`, `deleteById(id)`, `countForBrew(id): Int`.

### 4.3 `MIGRATION_3_4` — exact SQL (Room wraps it in a transaction)

```sql
-- beans
ALTER TABLE `beans` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''
ALTER TABLE `beans` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0
ALTER TABLE `beans` ADD COLUMN `roasterId` TEXT
ALTER TABLE `beans` ADD COLUMN `originId` TEXT
ALTER TABLE `beans` ADD COLUMN `farm` TEXT NOT NULL DEFAULT ''
ALTER TABLE `beans` ADD COLUMN `producer` TEXT NOT NULL DEFAULT ''
ALTER TABLE `beans` ADD COLUMN `washingStation` TEXT NOT NULL DEFAULT ''
ALTER TABLE `beans` ADD COLUMN `lotTypeId` TEXT
ALTER TABLE `beans` ADD COLUMN `speciesId` TEXT
ALTER TABLE `beans` ADD COLUMN `varietalIds` TEXT NOT NULL DEFAULT ''
ALTER TABLE `beans` ADD COLUMN `isDecaf` INTEGER NOT NULL DEFAULT 0
ALTER TABLE `beans` ADD COLUMN `decafMethodId` TEXT
ALTER TABLE `beans` ADD COLUMN `roastIntentId` TEXT
ALTER TABLE `beans` ADD COLUMN `harvest` TEXT NOT NULL DEFAULT ''
ALTER TABLE `beans` ADD COLUMN `restDays` INTEGER
ALTER TABLE `beans` ADD COLUMN `priceAmount` REAL
ALTER TABLE `beans` ADD COLUMN `currency` TEXT NOT NULL DEFAULT ''
UPDATE `beans` SET `uid` = lower(hex(randomblob(16))) WHERE `uid` = ''
UPDATE `beans` SET `updatedAt` = `createdAt`
UPDATE `beans` SET `process` = CASE `process` WHEN 'Washed' THEN 'washed' WHEN 'Natural' THEN 'natural' WHEN 'Honey' THEN 'honey' WHEN 'Anaerobic' THEN 'anaerobic' WHEN 'CarbonicMaceration' THEN 'carbonic-maceration' WHEN 'Wet' THEN 'wet-hulled' WHEN 'Other' THEN 'unknown' ELSE '' END
UPDATE `beans` SET `roastLevel` = CASE `roastLevel` WHEN 'Light' THEN 'light' WHEN 'MediumLight' THEN 'medium-light' WHEN 'Medium' THEN 'medium' WHEN 'MediumDark' THEN 'medium-dark' WHEN 'Dark' THEN 'dark' ELSE '' END
CREATE UNIQUE INDEX IF NOT EXISTS `index_beans_uid` ON `beans` (`uid`)

-- grinders (the K-Ultra per-turn correction is NOT done here: it needs the catalog and the user's OK, §6)
ALTER TABLE `grinders` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''
ALTER TABLE `grinders` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0
ALTER TABLE `grinders` ADD COLUMN `catalogId` TEXT
ALTER TABLE `grinders` ADD COLUMN `dialFormat` TEXT NOT NULL DEFAULT ''
UPDATE `grinders` SET `uid` = lower(hex(randomblob(16))) WHERE `uid` = ''
UPDATE `grinders` SET `updatedAt` = CAST(strftime('%s','now') AS INTEGER) * 1000
CREATE UNIQUE INDEX IF NOT EXISTS `index_grinders_uid` ON `grinders` (`uid`)

-- brew_logs (grindClicks, grindSetting and every 1.x value are left exactly as they are)
ALTER TABLE `brew_logs` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''
ALTER TABLE `brew_logs` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0
ALTER TABLE `brew_logs` ADD COLUMN `brewerId` TEXT
ALTER TABLE `brew_logs` ADD COLUMN `grindValue` REAL
ALTER TABLE `brew_logs` ADD COLUMN `waterId` TEXT
UPDATE `brew_logs` SET `uid` = lower(hex(randomblob(16))) WHERE `uid` = ''
UPDATE `brew_logs` SET `updatedAt` = `startedAt`
UPDATE `brew_logs` SET `brewerId` = <LegacyIds.caseSql("brewer", LegacyIds.BREWER, else = "NULL")>
CREATE UNIQUE INDEX IF NOT EXISTS `index_brew_logs_uid` ON `brew_logs` (`uid`)

-- analyses
ALTER TABLE `analyses` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''
ALTER TABLE `analyses` ADD COLUMN `providerId` TEXT NOT NULL DEFAULT ''
ALTER TABLE `analyses` ADD COLUMN `photoCount` INTEGER NOT NULL DEFAULT 0
ALTER TABLE `analyses` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'android'
UPDATE `analyses` SET `uid` = lower(hex(randomblob(16))) WHERE `uid` = ''
UPDATE `analyses` SET `providerId` = CASE WHEN `host` = 'api.commandcode.ai' THEN 'commandcode' WHEN `host` = 'sample.local' THEN 'sample' ELSE 'custom' END
CREATE UNIQUE INDEX IF NOT EXISTS `index_analyses_uid` ON `analyses` (`uid`)

-- photos (copy from 4.json; expected text:)
CREATE TABLE IF NOT EXISTS `photos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL, `brewLogId` INTEGER, `beanId` INTEGER, `kind` TEXT NOT NULL, `fileName` TEXT NOT NULL, `widthPx` INTEGER NOT NULL, `heightPx` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, `takenAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `caption` TEXT NOT NULL DEFAULT '', `sendToAi` INTEGER NOT NULL DEFAULT 1, FOREIGN KEY(`brewLogId`) REFERENCES `brew_logs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`beanId`) REFERENCES `beans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
CREATE UNIQUE INDEX IF NOT EXISTS `index_photos_uid` ON `photos` (`uid`)
CREATE INDEX IF NOT EXISTS `index_photos_brewLogId` ON `photos` (`brewLogId`)
CREATE INDEX IF NOT EXISTS `index_photos_beanId` ON `photos` (`beanId`)
```
The brewer CASE expands to `CASE \`brewer\` WHEN 'Hario V60' THEN 'hario-v60-02' WHEN 'V60' THEN 'hario-v60-02' … WHEN 'ColdBrew' THEN 'cold-brew-jar' ELSE NULL END`.

### 4.4 Repository (WP-1)

`CoffeeRepository` stamps `updatedAt = now()` in `upsertBean`, `upsertGrinder`, `insertLog`,
`updateLog`; exposes `photos: PhotoDao`-backed functions `observePhotosForBrew(id)`,
`observePhotosForBean(id)` (WP-4 adds writes); adds `suspend fun isEmpty(): Boolean`
(no beans, grinders or brews) and suspend `allBeans()/allGrinders()/allLogs()/allSavedRecipes()`
snapshots for backup; `recipes` gets `.flowOn(Dispatchers.Default)`.
`AnalysisRepository` writes `providerId` (WP-3 fills the real value; WP-1 writes
`if (host == COMMANDCODE_HOST) "commandcode" else "custom"`).

### 4.5 `app/src/androidTest/java/com/laksh/coffee/data/db/MigrationTest.kt` (WP-1)

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CoffeeDatabase::class.java)
    @Test fun migrate3To4_keepsEveryRowAndMapsLegacyValues() { … }
    @Test fun openWithAllMigrations_fromVersion3() { … }   // create v3 via helper, close, open with Room.databaseBuilder(...).addMigrations(all three).build(), read every DAO
}
```
`migrate3To4` seeds v3 with raw SQL: a bean (`process='Washed'`, `roastLevel='MediumLight'`,
`createdAt=1000`), a bean with `process='Other'`, a K-Ultra grinder (`clicksPerRotation=10`), brews
with `brewer` = 'Hario V60', 'Cold Brew', 'V60' and 'Something else' (one with `grindClicks=75`),
a saved recipe row, an analysis with `host='api.commandcode.ai'` for brew 1. Then
`helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4)` and assert: process `washed` /
`unknown`, roastLevel `medium-light`, every `uid` non-empty and distinct, bean `updatedAt == 1000`,
`brewerId` `hario-v60-02` / `cold-brew-jar` / `hario-v60-02` / NULL, `grindClicks` still 75,
grinder `clicksPerRotation` still 10, analysis `providerId='commandcode'`, `origin='android'`,
`photos` exists and is empty, `PRAGMA foreign_key_check` returns no rows,
`PRAGMA integrity_check` returns `ok`. README's "Room schemas are not exported" bullet becomes
"exported; MigrationTest covers 3→4".

---

## 5. Settings additions (WP-1, `data/prefs/SettingsStore.kt`)

Add to `Settings` and the store (keys in quotes; sets use `stringSetPreferencesKey`):
| field | key | default |
|---|---|---|
| `tempUnit: String` ("c" / "f") | "temp_unit" | "c" |
| `weightUnit: String` ("g" / "oz") | "weight_unit" | "g" |
| `onboardingDone: Boolean` | "onboarding_done" | false |
| `onboardingAuto: Boolean` (existing user, skipped automatically) | "onboarding_auto" | false |
| `personaliseDismissed: Boolean` | "personalise_dismissed" | false |
| `ownedBrewerIds: Set<String>` | "owned_brewers" | empty |
| `favouriteRoasterIds: Set<String>` | "fav_roasters" | empty |
| `homeArea: String` (an `area` value from roasters/origins) | "home_area" | "" |
| `defaultWaterId: String` | "default_water" | "" |
| `pendingNotices: Set<String>` | "pending_notices" | empty |
| `startupTasksVersion: Int` | "startup_tasks_version" | 0 |
One setter per field; `addNotice(id)`, `removeNotice(id)`. `Settings` stays the single object the UI collects.

---

## 6. Startup tasks and catalog linking (WP-1)

`data/StartupTasks.kt` — `suspend fun run(app: CoffeeApp)`, called once per process from the
`CoffeeApp` preload coroutine. Idempotent. Steps:
1. If `startupTasksVersion < 1`:
   a. `CatalogLinker.linkAll()`; b. if `!onboardingDone && !repository.isEmpty()` → set
   `onboardingDone = true`, `onboardingAuto = true` (existing users are never pushed into onboarding;
   Home shows a dismissible "Personalise" card instead, §7.8); c. set `startupTasksVersion = 1`.
2. (WP-4 appends) `PhotoStore.sweep()`.

`data/catalog/CatalogLinker.kt`:
- Normalise: lowercase, NFD + strip combining marks, `ö→o`, drop everything but `[a-z0-9]`.
- Grinders with `catalogId == null`: if `norm(brand + model)` equals exactly one catalog entry's
  `norm(brand + model)`, set `catalogId` (no other field changes). If the row's
  `clicksPerRotation` (0 = unknown), `micronsPerClick`, `lowerIsFiner` or style differ from the
  catalog **and** the catalog `confidence` is `high` or `medium`, add notice
  `"grinder-scale:<uid>"` to `pendingNotices`. Numbers are never changed without the user (§7.4).
- Beans with `roasterId == null`: exact normalised match of `roaster` against catalog names, also
  after removing a trailing word in {coffee, roasters, roastery, roasting, co, company} from both
  sides → set `roasterId`. `originId == null`: `origin` equals a catalog `country` or `iso2`
  case-insensitively → set `originId`. Never overwrite text fields.

---

## 7. Personalisation UI (WP-2)

### 7.1 Catalog search (`domain/catalog/CatalogSearch.kt`, pure, tested)

`fun search(items: List<SearchDoc>, query: String, limit: Int = 60): List<SearchHit>` where
`SearchDoc(id, title, subtitle, group, keys: List<String>, popularity: Int)` (keys = brand, model,
"brand model", aliases, country, city…). Normalise as §6 plus `oe→o`, `ue→u`, `ae→a`.
Score per doc = best over keys: exact 100; prefix 80; any token prefix 60; substring 40; fuzzy 20
when every query token ≥ 4 chars matches some key token within Damerau-Levenshtein ≤ 1
(≤ 2 when the token is ≥ 8 chars). Ties: popularity asc, then title. Empty query → all docs in
group order, popularity first. Test cases MUST pass: "commandante" → Comandante C40 first;
"mahlkonigg" and "mahlkoenig" → Mahlkönig entries; "k ultra" and "kultra" → 1Zpresso K-Ultra;
"ek43" → Mahlkönig EK43; "gesha" and "geisha" → Gesha varietal (alias); "square mile" → Square Mile.
(Assert only against ids actually present in the shipped catalog; skip a case whose target is absent.)

`CatalogRepository.search(kind: CatalogKind, query: String): List<SearchHit>` runs on
`Dispatchers.Default`; docs per kind built once and cached.
`enum CatalogKind { Grinder, Roaster, Origin, Varietal, Brewer }`.

### 7.2 `ui/catalog/CatalogPickerSheet.kt` (reused for grinders, roasters, origins, varietals, brewers)

```kotlin
sealed interface CatalogPick { data class Entry(val id: String) : CatalogPick; data class Custom(val text: String) : CatalogPick }
@Composable fun CatalogPickerSheet(
    kind: CatalogKind, title: String,
    multiSelect: Boolean = false, selected: Set<String> = emptySet(),
    allowCustom: Boolean = true, customLabel: (String) -> String = { "Use “$it”" },
    onPick: (CatalogPick) -> Unit,                 // single-select: picks and closes
    onDone: (Set<String>) -> Unit = {},            // multi-select "Done"
    onDismiss: () -> Unit,
)
```
`CoffeeSheet` with `skipPartiallyExpanded = true`, a pinned search `FieldWell` (auto-focus only
when the list is long: grinders, roasters), `LazyColumn` with sticky group headers
(grinders: brand; roasters: area with `homeArea` first, favourites at the very top; origins: area;
varietals: species; brewers: category). Row: title, subtitle (grinders "Hand · Conical 48 mm";
roasters "City, Country · notable"; varietals aliases), trailing check for selected. Debounce
query 80 ms in `CatalogPickerViewModel(kind)`; results via `StateFlow`. Last row when the query is
non-empty and `allowCustom`: `customLabel(query)`. Empty state copy: "Nothing called “x”. Use it as
your own entry." Back gesture closes the sheet.

### 7.3 Onboarding (`ui/onboarding/OnboardingScreen.kt`, `OnboardingViewModel.kt`)

`@Composable fun OnboardingScreen(onFinished: () -> Unit, onOpenAiSettings: () -> Unit = {}, vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory))`.
Full screen, mesh background as the Brew screen uses, progress dots, Back/Next, "Skip" on every
step. Steps (`AnimatedContent`, `Motion.offset()` slide + fade; `BackHandler` goes back a step):
1. **How you measure** — °C/°F and g/oz `SegmentedControl`s, and "Where do you buy coffee?"
   (area chips; sorts roasters). Note under weight: "Brew targets stay in grams — that's what
   coffee scales read. Bag sizes and totals follow your choice."
2. **Your grinder** — inline `CatalogPickerSheet` content (or the sheet). On pick, a summary card:
   name, burr, `StatusChip` by confidence (high: Good "From the maker"; medium: Attention "Check
   your manual"; low: Attention "Unverified"), scale words ("clicks · fewer is finer · 100 a turn ·
   20 µm a click"), `dialReading` and `entryHint` text, and a live `DialReadingInput` demo. "Not
   listed? Add it yourself" → custom grinder fields (as today's sheet). Saves a `GrinderEntity`
   with `isDefault = true` (fields mapped per §7.4). On "Run setup again", picking a grinder whose
   `catalogId` (or brand + model) already exists makes that row the default instead of inserting
   a duplicate.
3. **Brewers you own** — chips grouped by category from `Catalog.brewers` (most common first: the
   nine legacy ids, then the rest A–Z), multi-select → `ownedBrewerIds`.
4. **Your coffee (optional)** — favourite roasters (multi-select picker) and "What water do you
   brew with?" (water chips, `description` under the selected one) → `favouriteRoasterIds`,
   `defaultWaterId`.
5. **Done** — "Add an AI key (optional)" → `onOpenAiSettings()`; "Start brewing" → finish.
Each Next persists that step (partial progress survives). Finish sets `onboardingDone = true`.

### 7.4 Grinder editor (`ui/screens/gear/GrinderEditorSheet.kt`, replaces `GrinderSheet`)

- Top row "Which grinder?" opens the picker; selection fills: `brand`, `model`, `burrType`
  (conical/flat/ghost/blade → enum, else Unknown), `burrSizeMm` (`48.0` → "48"), `settingStyle`
  (clicks → Clicks, numbers → Numbers, stepless → Stepless), `clicksPerRotation` (null → 0),
  `micronsPerClick = micronsPerStep`, `lowerIsFiner`, `minSetting/maxSetting/step`, `catalogId`,
  `dialFormat = ""`. Defaults (usual filter/espresso) are not prefilled.
- Read-only scale summary with "Edit the scale" (expands today's fields plus a "How your dial
  reads" chip row: Auto / Plain number / Turns + clicks / Numbered dial (10 marks a number)). A row
  whose values differ from its catalog entry shows `StatusChip(Attention, "Edited")`.
- "How to read your dial" (`dialReading`) and "What to type" (`entryHint`) as `SheetNote`s.
- `GrindRangeCard` (§7.5). "Usual filter" / "Usual espresso" fields get a `DialReadingInput` toggle.
- Remove the hard-coded K-Ultra preset chip; the catalog replaces it.

Grinders tab card: shows the `grinder-scale:<uid>` notice when pending:
`StatusLine(Status.Attention, "Check the scale", "<Maker/source> says 100 clicks a turn; yours says 10")`
with two buttons — **Use these numbers** (copies cpr, microns, lowerIsFiner, style, min/max/step
from the catalog; removes the notice) and **Keep mine** (removes the notice). Copy under it:
"Your logged settings keep their numbers. 75 stays 75 — only how it's read out changes."

### 7.5 Shared grind widgets (`ui/components/`, WP-2)

- `DialReadingInput(scale: GrindScale, value: Double?, onValue: (Double?) -> Unit, modifier)`:
  steppers in the dial's own terms (NumberTick: turns 0–3, number, mark; TurnsAndClicks: turns,
  clicks; Plain: a numeric field) and a live line "= 75 clicks (what the app stores)". Rejects
  impossible readings inline (e.g. mark ≥ 10).
- `GrindRangeCard(scale: GrindScale, method: BrewMethod?, modifier)`: "STARTING POINTS" with one
  row per catalog range (method label, `from–to` in stored units plus dial words, e.g.
  "60–80 clicks · dial 6.0–8.0"), the chosen method first and highlighted, then "From: <who>"
  links (`rangeSources`, tap opens the URL with `ACTION_VIEW`), confidence chip, and the note
  "A starting point. Taste decides." Hidden when there are no ranges.
- `GrindStartHint(scale, method, onUse: (Double) -> Unit)`: one line for brew setup:
  "Start around 70 · <who>'s range 60–80" with a "Use 70" pill (midpoint rounded to `step`).
  Wired into BrewScreen by WP-6.

### 7.6 Bean editor (`ui/screens/gear/BeanEditorSheet.kt`, replaces `BeanSheet`)

Full-height `CoffeeSheet`, sections as separate composables (each reads only its fields, so a
keystroke recomposes one section). Required: name only. "More details" expands sections 3–6.
1. **The coffee** — Name; Roaster (picker → `roasterId` + `roaster = name`; custom → `roasterId = null`,
   `roaster = text`); Lot type chips (`lotTypeId`, description under the selected chip).
2. **Origin** — Country (picker → `originId` + `origin = country`); Growing region chips from the
   origin's `growingRegions` plus "Other…" (→ `region`); Farm / estate; Producer; Washing station;
   Altitude low/high metres (→ `altitudeMasl` "1700" or "1700-1900"); Harvest.
3. **Plant** — Species chips (`speciesId`); Varietals (multi-select picker → `varietalIds`;
   custom names → appended to `varietal` text, comma-joined); the origin's `commonVarietals`
   shown as quick chips first.
4. **Process** — chips grouped by `group` (classic, honey, fermentation, other) with the selected
   one's `description`; the origin's `commonProcesses` first. Decaf toggle → decaf method chips.
5. **Roast** — level chips with description, intent chips (filter/espresso/omni), roast date
   (Material date picker, plus today's "days ago" field), rest days stepper (copy: "The rest your
   roaster suggests before opening, if any"), opened date.
6. **The bag** — weight (unit per settings), price + currency (default from the phone locale),
   the bag's tasting notes, your notes, (WP-4: bag photo), "Finished this bag" toggle, Delete.
Home "next brew" and the bean card show "Resting · ready in 3 days" when
`roastedOn + restDays > today` (only if `restDays` is set).

### 7.7 Recipe builder (`ui/recipes/RecipeEditorScreen.kt`, `RecipeEditorViewModel.kt`, `domain/RecipeValidation.kt`)

`enum class EditorMode { New, Edit, Duplicate }`;
`@Composable fun RecipeEditorScreen(recipeId: String?, mode: EditorMode, onDone: (savedId: String?) -> Unit, onBack: () -> Unit)`.
- New: blank V60-style template (one Bloom step) or "Start from…" any recipe (= Duplicate).
- Duplicate: id `custom-` + 8 lowercase hex, name "<name> (mine)", author `settings.name.ifBlank { "You" }`,
  `fidelity = "custom"`, `basedOn = original.id`, `sources` copied, `sourceNote = "Based on <author>'s <name>."`, `isBuiltIn = false`.
- Edit: custom → same id; built-in → saves an override with the same id (existing mechanism) and
  shows "Reset to original" (`repo.resetRecipe`). Custom recipes get "Delete" (same DAO call).
- Fields: name, author, brewer (picker; owned brewers first), dose, water (ratio shown live), temp
  (units), grind words, blurb, prep list, tips list, steps.
- Steps editor: one card per step — kind chips, title, detail, **duration** stepper (±5 s, long
  press ±1 s), and for pour kinds (Bloom, Pour) "pour to __ g" (cumulative) with "+N g this pour"
  shown. Start/end seconds are derived cumulatively on save. Reorder with up/down buttons, add,
  delete. A live journey preview (existing `JourneyLine`) at the top.
- `RecipeValidation.check(recipe): List<Issue>` (pure, tested): name 1–60 chars; dose 1–100 g;
  water 10–2000 g; temp 0–100 °C; 1–30 steps; each duration ≥ 1 s; total ≤ 3600 s, or ≤ 86 400 s
  when `brewer.method == ColdBrew`; pour targets non-decreasing; last target == water (issue with a
  one-tap fix "Set the last pour to 500 g"). Save disabled while any blocking issue remains.
- Saves through `repo.saveRecipe`. Logs keep `recipeName` snapshots, so deleting a custom recipe
  never breaks history.

### 7.8 Units, Gear App tab, Home, recipe list (WP-2)

- `ui/theme/Units.kt`: `@Immutable data class Units(val fahrenheit: Boolean = false, val ounces: Boolean = false)`
  with `temp(c: Int): String`, `tempUnit`, `bigWeight(g: Double): Pair<String, String>` (bags,
  totals; oz to 1 dp), `val LocalUnits = staticCompositionLocalOf { Units() }`.
  `CoffeeTheme(darkMode: String, units: Units = Units(), content)` provides it (MainActivity passes
  it in WP-6). Brew steppers keep integer °C internally; in °F mode the stepper still moves 1 °C
  and shows the rounded °F.
  Apply `LocalUnits` in files this package owns (`RecipeCard`, Gear, editor screens); WP-4/WP-5/WP-6
  apply it in theirs.
- Gear › App gets a **YOU** section above AI analysis: units, area, "Brewers you own" (multi-select
  picker), "Your water" chips, "Run setup again" (callback `onRunSetup: () -> Unit = {}` on
  `GearScreen`, wired by WP-6). The "ABOUT THE RECIPES" copy also explains `custom` recipes.
- Beans tab card: roaster, origin country + region, process label, roast level, rest status.
  Grinders tab card: `GrindRangeCard` collapsed to the one line for the most-brewed method.
- `HomeScreen(onPersonalise: () -> Unit = {}, …)`: when `onboardingAuto && !personaliseDismissed`,
  one card "Make it yours — pick your grinder from the list and the brewers you own" with
  "Personalise" (callback) and "Not now" (sets `personaliseDismissed`). Next-brew suggestion prefers
  recipes whose `brewer.family` is in the owned set.
- `RecipeListScreen(onNewRecipe: () -> Unit = {}, …)`: "+" `IconWell` → `onNewRecipe`; filter chips:
  "Mine" (custom), "My brewers" (default on when `ownedBrewerIds` non-empty; matches by `family`),
  then brewers present in the list. All new callbacks default to no-ops so this package compiles
  before WP-6 wires navigation.

---

## 8. "Add your key" (WP-3)

### 8.1 Providers

`domain/ai/Providers.kt`:
```kotlin
enum class ProviderApi(val id: String) { OpenAiChat("openai-chat"), AnthropicMessages("anthropic-messages") }
enum class AuthStyle(val id: String) { Bearer("bearer"), XApiKey("x-api-key") }
data class AiModel(val id: String, val label: String, val price: Price?, val vision: Boolean, val isDefault: Boolean, val why: String)
data class AiProvider(
    val id: String, val name: String, val baseUrl: String, val api: ProviderApi, val auth: AuthStyle,
    val extraHeaders: Map<String, String>, val keyUrl: String, val keyHint: String,
    val modelsPath: String?, val modelsListable: Boolean, val models: List<AiModel>,
    val pricesCheckedOn: String, val isCustom: Boolean,
)
object AiProviders {
    /** Catalog order. Only providers usable from a phone: https base URLs that pass BaseUrl.check, plus "custom". ollama / lm-studio (http://localhost) are left out on Android. */
    val all: List<AiProvider>
    fun byId(id: String?): AiProvider?
    fun forHost(host: String): AiProvider?    // used by the prefs migration
    const val CUSTOM = "custom"
    const val COMMANDCODE = "commandcode"
}
```
`extraHeaders` are sent only if the name matches `^[A-Za-z0-9-]{1,64}$`, is not one of
`authorization`, `x-api-key`, `cookie`, `host`, `content-length`, `content-type` (case-insensitive),
and the value is printable ASCII ≤ 256 chars. `browserHeaders` are web-only and ignored on Android.

`ModelCatalog` keeps `formatUsd`, `formatTokens`, `costMicroUsd`, `Price`, `COMMANDCODE_HOST`;
loses `pinned`, the DeepSeek peak logic, `DEFAULT_BASE_URL/DEFAULT_MODEL` (moved to
`AiPrefsMigration` as 1.x constants); gains `price(providerId, modelId): Price?` (catalog
`inputPerM/outputPerM`, both non-null) and `label(providerId, modelId)` (catalog label, else the
id after the last `/`). Cost is shown for any provider whose catalog lists the price, with
"prices as of <pricesCheckedOn>".

### 8.2 `AiSettingsStore` v2 (one key per provider, same host binding)

DataStore file stays `coffee_ai`. New keys:
```
"ai_layout" = 2
"provider"                         active provider id ("" = none chosen yet; fresh installs)
"p.<id>.base_url"                  custom only (presets always use the catalog baseUrl)
"p.<id>.model"
"p.<id>.key_ct" / "p.<id>.key_hint" / "p.<id>.key_host"
"p.<id>.test_ok_at" / "p.<id>.test_sig"
"p.<id>.vision"                    "yes" | "no" | absent — user override for models the catalog doesn't know
"send_photos"                      Boolean, default true
```
`AiSettings` gains `providerId`, `providerName`, `api`, `auth`, `sendPhotos`, `modelSeesImages`
(catalog `vision`, else the override, else false), `keys: Map<String, KeyBadge(hint, host)>` for
the provider list. `host`, `ready`, `signature` (`"$providerId|$baseUrl|$model|$keyHint"`),
`testedOk`, `keyForOtherHost` keep their meaning per active provider. API:
`setProvider(id)`, `setBaseUrl(raw)` (custom only; same validation as today), `setModel(id)`,
`saveKey(raw, providerId, forHost)`, `clearKey(providerId)`, `readKey(providerId, forHost)`,
`markTested(providerId, sig, at)`, `setVisionOverride(providerId, Boolean?)`, `setSendPhotos(Boolean)`.
Key validation rules and the host-moved check inside `edit {}` are unchanged. One Keystore AES key
(`KeyVault`, same alias) encrypts every provider's key; each blob has its own IV.
`clearKey(providerId)` deletes the Keystore entry only when no provider key remains.

`data/ai/AiPrefsMigration.kt` — a `DataMigration<Preferences>` passed via
`preferencesDataStore(name = "coffee_ai", produceMigrations = { listOf(AiPrefsMigration) })`:
- `shouldMigrate`: `ai_layout` absent and any 1.x key present.
- `migrate`: host = host(`base_url` ?: 1.x default `https://api.commandcode.ai/provider/v1`);
  pid = `AiProviders.forHost(host)?.id ?: "custom"` (CommandCode → `commandcode`); if custom,
  `p.custom.base_url = base_url`; move `model`, `key_ct`, `key_hint`, `key_host`,
  `last_test_ok_at`, `last_test_sig` to `p.<pid>.*` unchanged (the ciphertext stays valid);
  `provider = pid`; `ai_layout = 2`; remove 1.x keys. The pure core
  `fun migrateV1(old: Map<String, Any?>): Map<String, Any?>` is unit-tested.
- The owner's CommandCode key, model and tested state survive with no re-entry.

### 8.3 Transport and clients

- `data/ai/HttpTransport.kt` — extract from `OpenAiCompatClient` unchanged: `exchange`, `send`,
  `readCapped`, `Progress`, `mapException`, `statusError`, `providerText`, `checkBase`, timeouts,
  no redirects, cancel-closes-socket, 1 MiB reply cap. `send` takes `headers: Map<String,String>`
  instead of hard-coding `Authorization`. `User-Agent: Coffee-Android/${BuildConfig.VERSION_NAME}`.
- `data/ai/ChatClient.kt`:
```kotlin
data class Endpoint(val providerId: String, val baseUrl: String, val api: ProviderApi, val auth: AuthStyle,
                    val extraHeaders: Map<String, String>, val modelsPath: String?)
data class AiImage(val label: String, val mediaType: String = "image/jpeg", val base64: String)
data class AiRequest(val model: String, val system: String, val user: String, val images: List<AiImage> = emptyList(),
                     val temperature: Double? = null, val maxTokens: Int)
interface ChatClient {
    suspend fun complete(e: Endpoint, apiKey: String, r: AiRequest, readTimeoutMs: Int = 90_000): AiResult<ChatResult>
    suspend fun testConnection(e: Endpoint, apiKey: String, model: String): AiResult<TestResult>
    suspend fun listModels(e: Endpoint, apiKey: String?): AiResult<List<RemoteModel>>
}
```
- `OpenAiCompatClient : ChatClient`. `ChatMessage(role, content: JsonElement)`: a plain string when
  there are no images, else an array `[{"type":"text","text":…}, {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}…]`.
  Token field: `max_completion_tokens` for provider `openai`, `max_tokens` for everyone else; on a
  400 whose provider text names `max_tokens` (e.g. "use max_completion_tokens"), retry once with
  the other field (free, like the existing temperature retry). Keep the temperature retry.
  Keep the `supported_endpoints` filter and CommandCode's keyless `/models`.
- `AnthropicClient : ChatClient` (new): `POST {base}/messages`; headers `x-api-key: <key>`,
  `content-type: application/json`, plus catalog `extraHeaders` (carries `anthropic-version`; if
  absent send `anthropic-version: 2023-06-01`). Body
  `{"model", "max_tokens" (required), "system", "temperature"?, "messages":[{"role":"user","content":[{"type":"text","text":…}, {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":…}}…]}]}`.
  Reply: join `content[]` blocks with `type == "text"` (skip `thinking`, `redacted_thinking`);
  `stop_reason` `max_tokens` → `AiError.Truncated`, `refusal` → `AiError.Filtered`; usage
  `input_tokens`/`output_tokens`; `model`. Error body `{"type":"error","error":{"type","message"}}`
  works with the existing `errorMessage()`. Status mapping as `statusError`, plus 529 → ServerError.
  `listModels`: `GET {base}/models` with the same headers → `data[].id`, `display_name`.
  Test connection: a 16-token message, same rules as today.
- `BaseUrl`: add `messages(normalized)` and `/messages` to `PASTED_SUFFIXES`.
- `AnalysisRepository` picks the client by `settings.api`, builds `Endpoint` from the provider,
  writes `providerId` and `photoCount` on the row, prices with `ModelCatalog.price(providerId, model)`.
  Photos come through `interface PhotoAttachmentSource { suspend fun forBrew(brewId: Long): List<AiImage> }`
  (default `NoPhotos`; WP-4 supplies the real one). Attach only when `target is Brew`,
  `sendPhotos && modelSeesImages`.
- Estimate shown before a run adds ≈1,100 prompt tokens per attached image (app heuristic).

### 8.4 Settings UI (`ui/ai/AiSettingsSection.kt`, `AiSettingsViewModel.kt`, new `ui/ai/ProviderPicker.kt`)

`AiSettingsSection(modifier)` and `AiSettingsSheet(onDismiss)` keep their signatures (Gear and the
analysis cards call them). Layout:
1. Headline line (as today), naming the provider: "Ready · Claude Haiku 5.5 via Anthropic".
2. **Provider** list: one row per `AiProviders.all` — name, and a status word + colour: "Key saved"
   (Good), "Key for another address" (Attention), "No key" (Neutral). Tap selects.
3. For the selected provider: "Get a key" (opens `keyUrl` in the browser), `keyHint`, the existing
   key entry/saved-key UI (per provider), Save and test. Custom only: base URL field (today's).
4. **Model**: catalog models (label, "$in / $out per 1M", `StatusChip(Neutral, "Sees photos")`
   when `vision`, DEFAULT badge, `why` as subtitle); "Browse all" when `modelsListable` or custom;
   "Use another model id" free text. For a model the catalog doesn't know: toggle "This model can
   see images".
5. "Send photos with analyses" toggle (visible when the model sees images): "Up to 3 photos of the
   brew you're analysing. Untick any photo to keep it out."
6. Footnote: "Your key is encrypted on this phone and only ever sent to <host>. Prices checked <date>."
Copy everywhere that says "CommandCode" generalises to the provider name; CommandCode-plan
wording (401/403) stays only for provider `commandcode`.

### 8.5 Prompt enrichment (`domain/ai/AnalysisPrompt.kt`; `VERSION = 2`)

`BrewPromptInput`/`TrendPromptInput` gain `brewer: Brewer?`, `water: CatalogWater?`,
`photos: List<PhotoMeta>` (kind, takenAt; brew only), `units: String` ("c"/"f"), `principles: List<Principle>`.
Blocks (JSON, `AiJson.prompt`, explicit nulls, deterministic key order):

GRINDER (click grinders):
```json
{"name":"1Zpresso K-Ultra","catalogId":"…","burrs":"Conical, 48 mm",
 "scale":{"adjustsBy":"clicks","unit":"clicks","finerDirection":"FEWER clicks is finer","clicksPerTurn":100,
          "micronsPerStep":20,"min":0,"max":300,"howTheDialReads":"<catalog dialReading>","whatTheyType":"<catalog entryHint>","dialFormat":"number-tick:10"},
 "currentClicks":75,"currentOnDial":"7.5","lastTrustedClicks":null,"lastTrustedFrom":null,
 "moveSizes":{"nudge":2,"clearMiss":6,"maxAtOnce":13,"basis":"20 µm per click (app rule of thumb)"},
 "startingRange":{"method":"pourover","from":60,"to":80,"from_who":["…"]},
 "example":"6 clicks finer from 75 (dial 7.5) is 69 (dial 6.9)"}
```
Non-click grinders: same `scale` with `adjustsBy` "numbered dial"/"stepless", `currentSetting`
(grindValue, else the text), `moveSizes`, `startingRange`, `example`. `currentOnDial` comes from
`GrindScale.reading`. **`grindSetting` text is no longer sent for click grinders** (fact 4).

COFFEE adds: `roaster` (+ `roasterCountry`), `origin` (country), `growingRegion`, `farm`,
`producer`, `washingStation`, `lotType`, `species`, `varietals` (labels + custom text), `process`
(label) and `processGroup`, `decaf` (method label or null), `roastLevel`, `roastIntent`,
`altitudeM`, `harvest`, `daysOffRoast` (log snapshot, unchanged), `restDaysSuggested`, `bagNotes`.
BREWER block: `{"name","category","method","filter"}`. WATER block: `{"label","kind","ghPpm","khPpm"}`
or "not recorded". PHOTOS block (brew only): "2 attached: #1 bed (2026-11-17 08:14Z), #2 cup" or
"none". USER UNITS line when °F: "Write temperatures in °F for this person; the data is in °C."
PRINCIPLES block (§8.9), filtered to the brew's method, at most 10.

System prompt (brew) — replace the grind bullet with:
"- If the change is grind: give exact numbers in this grinder's own units — grindClicksFrom/To for
click grinders, grindValueFrom/To for numbered or stepless dials. Follow grinder.scale.finerDirection
exactly; grinder.example shows which way is finer. Size the move with grinder.moveSizes: nudge for a
small change, clearMiss for a clear miss, never more than maxAtOnce at once. Phrase the instruction
the way this person reads the dial (grinder.currentOnDial, grinder.scale.howTheDialReads), e.g.
'75 -> 69 clicks (dial 7.5 -> 6.9)'. grinder.startingRange is a sanity check, not a target. If the
current grind is unknown, give the change relative to the last brew."
Add: "- PRINCIPLES are from the people whose methods these are. Use them to reason and name the
person when you lean on one. Never quote a principle that isn't listed." and "- Photos, when
attached, are evidence of the bed and the cup. Mention what you see only if it changes the
diagnosis; never read a grind setting off a photo." Trend system prompt: the grinder bullet
references each grinder's `moveSizes`. `example()` uses `GrindScale`; drop the `cpr == 10` checks.

Reply shape: `change` gains `"grindValueFrom": null, "grindValueTo": null` (numbers). Parser
`normalizeChange` accepts them; `Change` gets `grindValueFrom/To: Double? = null`. New
`@Serializable data class GrindValueMove(val from: Double?, val to: Double, val lowerIsFiner: Boolean, val unit: String, val dialFormat: String)`
in `StoredResult` (`valueMove: GrindValueMove? = null`) with `GrindValueMove.resolve` mirroring
`GrindMove.resolve` (direction check, `maxAtOnce` warning). `AnalysisCard` shows it as the hero
like the click move ("14 → 13 · finer").

### 8.6 Staleness wording (WP-3)

`StoredAnalysis` gains `promptVersion`, `providerId`, `photoCount`, `origin`. The brew and trend
VMs compute `staleReason`: `Edited` (hash differs, same version, origin android) → "Brew edited
since · re-run for a fresh read"; `OlderPrompt` (`promptVersion < VERSION`) → "Made by an older
version of the analysis · re-run to use your grinder's scale"; `OtherDevice` (`origin != "android"`)
→ "Made on another device · re-run here for a fresh read". Analysis card footer adds "· 2 photos"
when `photoCount > 0`, and the provider name.

The hash names every record by its uid, never by its row id, so a restored analysis is still
current on rows that came back under new ids; such a hash starts with `s2:`. A stored hash
without that prefix was made from row ids by 2.0.0 and cannot be compared: it reads as
`OlderPrompt`, never `Edited`. A stored trend keeps `brewUids` (the number the model was shown
to the brew's uid) and its date chips are found through those uids; a trend stored without them
still reads its numbers as row ids when its origin is android, and shows no chips otherwise (the
numbers are another app's row ids). The data-quality checks read a brew whose coffee or grinder
was deleted as having none, as a restore leaves it. The hash's PHOTOS block names the brew's
eligible photos (bed/cup, ticked, at most 3) whether or not the settings send them. All of these
rules are the same on the web (`shared/schema/backup-v2.md`).

### 8.7 Share-sheet report (`domain/BrewReport.markdown`, WP-3)

Add the new coffee fields, brewer (label + method), water, the grinder scale lines (per turn,
microns, finer direction, dial reading of the setting, starting range with who) and
"N photos of this brew are in the app (not attached)". The "What I want back" grind question asks
for the change "in this grinder's units, the way the dial reads".

### 8.8 Tests (WP-3)

`ProvidersTest` (Android-usable filter, custom present, header allow-list), `AiPrefsMigrationTest`
(CommandCode, OpenRouter-host → openrouter, unknown host → custom, nothing to migrate),
`OpenAiWireTest` (string vs parts content; `max_completion_tokens` for openai), `AnthropicWireTest`
(request JSON shape incl. image block; reply joins text blocks, skips thinking; stop reasons),
`AnalysisPromptTest` (K-Ultra NumberTick → `currentOnDial` "7.5", no `grindSetting` for clicks,
`moveSizes`, principles filtered by method, °F line), updates to `ModelCatalogTest`,
`AnalysisParserTest` (grindValueFrom/To), `GrindMoveTest`.

### 8.9 `shared/principles.json` (WP-3 researches and writes it)

```json
{"version": 1, "updated": "2026-11-17", "principles": [
  {"id": "kasuya-46-balance-strength", "topic": "recipe", "appliesTo": ["pourover"],
   "text": "≤ 240 chars, a plain statement of what the person teaches", "who": "Tetsu Kasuya",
   "url": "https://…", "note": "where in the source"}
]}
```
`topic` ∈ extraction | grind | agitation | flow | bed | temperature | ratio | freshness | water |
espresso | recipe. `appliesTo` = `BrewMethod` ids, empty = all. Every entry MUST have a URL to the
person's own video/blog/book/shop page or the competition organiser (aggregators allowed only if
named in `note`). Follow the RESEARCH RULES (load WebSearch/WebFetch via ToolSearch). Candidates to
**verify, source and keep or drop** — do not include any you cannot source:
Kasuya 4:6 (first 40 % of the water sets acidity/sweetness balance, the last 60 % sets strength
by number of pours); Hoffmann (swirl after bloom and after the last pour to flatten the bed; grind
finer if drawdown is fast and the cup thin; boiling or near-boiling water for light roasts; bloom
with roughly 2× the dose in water); Rao (a flat bed after drawdown signals even extraction; spin to
flatten; keep agitation consistent between brews; aim for even rather than merely high extraction);
Hedrick (what he publishes about agitation, pour height/flow and dialling light roasts — verify
specific claims); Gagné / Coffee ad Astra (fines migration slows drawdown; gentler pours reduce it;
kettle flow rate matters); Barista Hustle Coffee Compass (sour → extract more, bitter/drying →
extract less) — aggregator, name it; Colonna-Dashwood & Hendon, *Water for Coffee* (alkalinity
buffers acidity; high KH flattens the cup); Wendelboe (filter brew ratio ~60 g/L — verify).
`shared/schema/principles.md` documents the schema. Catalog test: ids unique, URL present,
`text` ≤ 240 chars, `appliesTo` valid.

---

## 9. Photos for feedback (WP-4)

### 9.1 Capture and storage

- Manifest (inside `<application>`):
  `<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.photos" android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/photo_paths" /></provider>`;
  `res/xml/photo_paths.xml`: `<paths><cache-path name="camera" path="camera/" /></paths>`.
  The authority uses `${applicationId}`, so debug and release never clash. **No CAMERA or storage
  permission is declared** — declaring CAMERA would make `TakePicture` require a runtime grant.
- Pick: `ActivityResultContracts.PickVisualMedia()` with `ImageOnly` (falls back to
  `ACTION_OPEN_DOCUMENT` below Android 11 via androidx.activity; the file is copied immediately, so
  no persistable permission is needed). Camera: `ActivityResultContracts.TakePicture()` into
  `cacheDir/camera/<uid>.jpg` through `FileProvider.getUriForFile`; catch
  `ActivityNotFoundException` → "No camera app on this phone."
- `data/photos/PhotoStore.kt` (all on `Dispatchers.IO`): decode bounds; `inSampleSize` = largest
  power of two keeping the long edge ≥ 1024; decode; read orientation with
  `androidx.exifinterface.media.ExifInterface(InputStream)` and rotate/flip with a `Matrix`; scale
  so the long edge is ≤ 1024 px (filter = true); `compress(JPEG, 82)` to `files/photos/<uid>.jpg.tmp`,
  then rename. Re-encoding drops all EXIF (location, camera). Insert `PhotoEntity` with real
  width/height/bytes, `takenAt` = EXIF DateTimeOriginal if present else now. Delete the camera temp
  file. Max 6 photos per brew, 3 per bean ("That's the most for one brew").
- `sweep()`: delete files in `files/photos` with no row, and `*.tmp`; rows whose file is missing
  stay and render a "Not on this phone" tile (e.g. restored from Android backup) with Remove.
- `PhotoRepository` (`data/photos/PhotoRepository.kt`): `observeForBrew`, `observeForBean`,
  `add(uri, kind, brewLogId?, beanId?)`, `setKind`, `setCaption`, `setSendToAi`, `delete(photo)`
  (row then file), `deleteForBrew(id)` used by `CoffeeRepository.deleteLog` and `deleteBean`
  before the row delete (the FK cascade removes rows; files need explicit deletion).
- `ui/photos/ThumbnailCache.kt`: `LruCache<String, ImageBitmap>` sized 8 MB, decoded at 256 px on
  IO, `produceState` per tile; no image library.

### 9.2 UI

- `ui/photos/PhotoStrip.kt`: `PhotoStrip(photos, onAdd(kind), onOpen(photo), allowedKinds, modifier)`
  — a `LazyRow` of 72 dp rounded tiles with a kind label, an add tile opening a small menu
  ("Take photo" / "Choose from library", then kind chips Bed / Cup / Bag / Other).
- `ui/photos/PhotoViewerDialog.kt`: full-screen `Dialog(usePlatformDefaultWidth = false)`, image with
  pinch-zoom (`transformable`, `graphicsLayer`), kind chips, caption field, "Send with analyses"
  toggle, Delete (confirm: "Delete this photo? It can't be undone.").
- Review (`ReviewScreen`/`ReviewViewModel`): a **PHOTOS** section after THE BED, kinds bed/cup,
  copy "A photo of the bed says more than any shape on this list." Also swap the grind field for
  `DialReadingInput` with a toggle "Type it as the dial reads" (WP-2's widget).
- Brew detail (`BrewDetailScreen`, `BrewDetailViewModel` in `HistoryViewModel.kt`): a strip after
  THE BED (hidden when empty, with an "Add photo" quiet button).
- Bean editor (`BeanEditorSheet`): a "Bag photo" row, kind bag, `beanId`.
- Analysis section (`ui/ai/BrewAnalysisSection.kt`): above Analyse, when photos exist:
  "Sends 2 photos" with the ticked thumbnails, or "Photos stay on the phone — <model> can't see images".

### 9.3 AI hook

`data/photos/PhotoAttachments.kt : PhotoAttachmentSource` — the brew's `bed`/`cup` photos with
`sendToAi`, newest 3, read the stored JPEG (already ≤ 1024 px), `Base64.getEncoder()`; if the total
base64 exceeds 4 MB drop the oldest. Labels "bed, 08:14". Wire it into `AnalysisRepository` from
`CoffeeApp`. Photo uids + bytes appear in the PHOTOS prompt block, so adding/removing a ticked
photo marks the analysis "Brew edited since".

---

## 10. Backup format v2 (WP-5)

### 10.1 Content of `shared/schema/backup-v2.md` (copy verbatim, then keep in sync)

> **Coffee backup, version 2.** One JSON file, UTF-8, written and read by the Android app
> (2.0.0+) and the web app. It holds everything a person logged, never an API key.
>
> **Top level**
> ```json
> {
>   "format": "coffee-backup",
>   "version": 2,
>   "exportedAt": "2026-11-17T09:41:00.000Z",
>   "app": {"platform": "android", "appVersion": "2.0.0", "packageName": "com.laksh.coffee.debug", "dbVersion": 4},
>   "options": {"includesPhotos": true, "includesAnalyses": true},
>   "settings": { … },
>   "ai": { … },
>   "beans": [], "grinders": [], "brews": [], "recipes": [], "analyses": [], "photos": []
> }
> ```
> - `format` MUST be `"coffee-backup"`. `version` is an integer; a reader rejects a version it does
>   not know ("This backup was made by a newer version of Coffee").
> - Instants are ISO-8601 UTC strings (`Instant.toString()` / `Date.toISOString()`); readers accept
>   with or without milliseconds. Calendar dates are `"YYYY-MM-DD"`.
> - Every array may be empty or missing. Every field may be missing; readers use the defaults below.
>   Writers write every key, nulls included. Readers ignore keys they don't know.
> - Records are linked by `uid` (an opaque string, 1–64 chars, unique per record type; Android and
>   web write UUIDs, 1.x data migrated to 32 hex chars). Numeric database ids never appear.
> - Ids from the shared catalog (`roasterId`, `originId`, `processId`, `brewerId`, `waterId`, …) are
>   the ids in `shared/catalog/*.json`. An id the reader's catalog lacks is kept and shown as-is.
>
> **settings** (all optional): `tempUnit` "c"|"f", `weightUnit` "g"|"oz", `darkMode`
> "system"|"light"|"dark", `haptics`, `soundCues`, `keepScreenOn`, `autoAdvanceSteps` (booleans),
> `name`, `ownedBrewerIds` [], `favouriteRoasterIds` [], `homeArea`, `defaultWaterId`,
> `defaultBeanUid`, `defaultGrinderUid`, `lastRecipeId`, `onboardingDone`.
>
> **ai** (never a key): `{"activeProvider": "commandcode", "sendPhotos": true,
> "providers": [{"id": "commandcode", "baseUrl": null, "model": "z-ai/glm-5.3-flash", "vision": null}]}`.
> `baseUrl` only for `custom`. `vision` is the user's override for an unknown model (true/false/null).
>
> **beans[]**: `uid`, `name` (required), `roaster`, `roasterId`, `origin`, `originId`, `region`,
> `farm`, `producer`, `washingStation`, `lotTypeId`, `speciesId`, `varietalIds` [], `varietal`
> (free text), `processId`, `isDecaf`, `decafMethodId`, `roastLevelId`, `roastIntentId`,
> `altitudeM` (string "1700" or "1700-1900"), `harvest`, `roastedOn` (date|null), `openedOn`
> (date|null), `restDays` (int|null), `bagSizeG` (number|null), `priceAmount`, `currency`,
> `priceText` (1.x free text), `tastingNotes`, `notes`, `isArchived`, `createdAt`, `updatedAt`.
> `processId`/`roastLevelId` `""` = not recorded.
>
> **grinders[]**: `uid`, `catalogId`, `brand`, `model`, `burr` ("conical"|"flat"|"ghost"|"blade"|null),
> `burrSizeMm` (string as typed), `setting`: {`style` "clicks"|"numbers"|"stepless"|"microns",
> `min`, `max`, `step`, `lowerIsFiner`, `clicksPerRotation` (int|null), `micronsPerStep`
> (number|null), `dialFormat` (""|"plain"|"turns"|"turns-dot"|"number-tick:10")},
> `defaultFilterSetting`, `defaultEspressoSetting` (number|null, stored units), `notes`,
> `isDefault`, `isArchived`, `updatedAt`.
>
> **brews[]**: `uid`, `startedAt`, `updatedAt`, `recipeId` (built-in or `custom-…`, may be ""),
> `recipeName`, `recipeAuthor`, `brewerId`, `brewerLabel`, `beanUid`, `beanLabel`, `daysOffRoast`,
> `grinderUid`, `grinderLabel`, `grindSetting` (text), `grindClicks` (int|null — total clicks from
> zero on click grinders), `grindValue` (number|null — numbered/stepless dials), `doseG`, `waterG`,
> `tempC` (int, always °C), `waterId`, `totalTimeSec`, `drawdownSec`, `beverageG`, `tdsPercent`,
> `extractionYield`, `flow`, `bed`: {`shape`, `surface`, `channelling`, `notes`},
> `taste`: {`acidity`,`sweetness`,`bitterness`,`body`,`balance`,`aftertaste`,`clarity`} (0–10),
> `overall` (0 = not rated), `descriptors` [], `faults` [], `notes`, `nextTime`, `isFavourite`, `sharedAt`.
> Enumerations (null = not recorded):
> `flow` stalled | slow | on-target | fast | gusher;
> `bed.shape` flat | domed | crater | high-and-dry | sloped | sinkhole | muddy;
> `bed.surface` none | light | heavy; `bed.channelling` none | suspected | obvious.
> `descriptors`/`faults` are the English chip labels the apps show (e.g. "Stone fruit", "Boozy / ferment-heavy").
>
> **recipes[]**: the user's own recipes and their edits of built-ins, each exactly an entry of
> `shared/recipes.json` (see `schema/recipes.md`) plus `updatedAt`. An entry whose `id` is a
> built-in id is an edit of it; resetting it means deleting the entry.
>
> **analyses[]**: `uid`, `kind` "brew"|"trend", `brewUid` (null for trend), `createdAt`,
> `origin` "android"|"web", `promptVersion`, `inputHash`, `brewCount`, `newestBrewAt`,
> `providerId`, `model`, `host`, `rawText`, `result` (the parsed result object or null), `flags` [],
> `promptTokens`, `completionTokens`, `costMicroUsd`, `latencyMs`, `photoCount`.
>
> **photos[]**: `uid`, `brewUid` | `beanUid` (one of them), `kind` "bed"|"cup"|"bag"|"other",
> `takenAt`, `createdAt`, `caption`, `sendToAi`, `widthPx`, `heightPx`, `mediaType` "image/jpeg",
> `data` (base64, no line breaks; null when the backup was saved without photos). Images are already
> downscaled (long edge ≤ 1024 px) and carry no EXIF.
>
> **Importing.** *Add to this device* (default): a record whose `uid` already exists is replaced
> only if the incoming `updatedAt` is newer (records without `updatedAt` never replace); new uids
> are added; recipes by `id` the same way. *Replace everything*: delete beans, grinders, brews,
> analyses, photos and the user's recipes on this device, then add. Settings and the `ai` block are
> applied only when the person ticks "Also bring settings" / "Also bring AI provider and model".
> Importing the same file twice changes nothing the second time.
>
> **Older files.** Readers also accept the 1.x export (`"schema": "coffee-brew-log/1"`, written by
> "Save JSON export" in every Android build up to 1.0, database versions 1–3). Map its labels with
> the tables in `LegacyIds`/this document (process and roast **labels**, bed/flow/sludge/channelling
> **labels**, brewer **labels**). It has no uids: derive `uid = "legacy-" + first 16 hex of
> SHA-256("<exportedAt>|<type>|<id>")` so re-importing it is a no-op. Resolve `recipeId` by exact
> recipe name + author against the built-ins (else ""), the grinder by `"<brand> <model>"` == the
> brew's `grinder` label, `beanUid` from `beanId`. Fields an older build didn't write are missing:
> use defaults. Accept `NaN`/`Infinity` (non-standard numbers 1.x could write) as null.

1.x label tables (put in the doc too): bed shapes "Not recorded"→null, "Flat and even"→flat,
"Domed in the middle"→domed, "Cratered / dug out"→crater, "High and dry ring"→high-and-dry,
"Sloped to one side"→sloped, "Sinkholes / pits"→sinkhole, "Muddy and compacted"→muddy;
sludge "Clean"→none, "A little silt"→light, "Thick muddy cap"→heavy; channelling
"None visible"→none, "Maybe"→suspected, "Obvious holes"→obvious; flow "Stalled / never finished"→stalled,
"Slower than target"→slow, "On target"→on-target, "Faster than target"→fast,
"Fell straight through"→gusher; burr "Conical"→conical, "Flat"→flat, "Ghost burr"→ghost,
"Blade"→blade, "Unknown"→null; setting style "Clicks"→clicks, "Numbered dial"→numbers,
"Microns"→microns, "Stepless / degrees"→stepless. Readers also accept the Kotlin enum names.

### 10.2 Android implementation

- `domain/backup/BackupModels.kt` — `@Serializable` DTOs for the doc above (every field
  defaulted/nullable), `BackupIds.kt` (enum ↔ id both ways, plus 1.x label tables),
  `BackupCodec.kt` (`detect(json): Kind { V2, Legacy1, Unknown, TooNew }`, decode, encode),
  `LegacyExportReader.kt` (coffee-brew-log/1 → v2 DTO).
  Json: `ignoreUnknownKeys = true`, `coerceInputValues = true`, `allowSpecialFloatingPointValues = true`,
  `isLenient = true` for reading; `prettyPrint = true`, `prettyPrintIndent = "  "`,
  `encodeDefaults = true`, `explicitNulls = true` for writing.
- `data/backup/BackupExporter.kt` — snapshot everything via the DAOs on IO, encode all but photos
  with `encodeToString`, then stream: write the envelope up to `"photos": [`, then each photo DTO
  (reading and base64-encoding one file at a time), then `]}`; all through a
  `BufferedWriter(OutputStreamWriter(contentResolver.openOutputStream(uri), UTF_8))`. Report the
  size estimate before saving ("about 14 MB with photos").
- `data/backup/BackupImporter.kt` — copy the picked file to `cacheDir/import.json` (refuse > 64 MB:
  "Too big to import here. Save the backup without photos and try again."), `decodeFromStream`,
  build a preview (counts, platform, package, date, version), then on confirm: decode photos to
  `files/photos/staging/` first, then one `db.withTransaction { … }` that applies the merge rules,
  remaps uids → local ids, inserts/updates rows, and moves staged files into place on success; on
  failure delete the staging dir and nothing changes. Runs in `CoffeeApp.appScope`
  (survives leaving the screen); progress via `StateFlow<ImportState>`.
- `ui/backup/BackupSection.kt` + `BackupViewModel.kt` in Gear › App, section **YOUR DATA**:
  "Back up everything" (`CreateDocument("application/json")`, name `coffee-backup-<date>.json`,
  toggle "Include photos"); "Restore from a backup" (`OpenDocument(arrayOf("application/json", "text/plain", "application/octet-stream"))`).
  `ui/backup/ImportPreviewSheet.kt`: "Found 52 brews, 4 coffees, 1 grinder, 3 recipes, 7 analyses,
  12 photos · made on Android (com.laksh.coffee.debug) on 17 Nov 2026"; mode
  `SegmentedControl` (Add to this phone / Replace everything — the latter shows
  `StatusChip(Fault, "Deletes")` and a confirm); toggles "Also bring settings", "Also bring AI
  provider and model (keys never travel)"; Import; then a result card ("Added 52 brews · 0 already
  here"). Plain-word errors for: not JSON, not a Coffee backup, newer version, too big, storage full.
- Insights › SHARE & EXPORT: "Save JSON export" becomes "Save backup (JSON)" — same writer, photos
  off, analyses on, file `coffee-backup-<date>.json`; delete `BrewReport.json()`. Update
  `claude/ANALYSE.md` to the v2 field names (`brews[].bed.shape`, `grindClicks`, `beans[].processId`…).
- Rewrite `privacyCopy` (InsightsScreen, owned by WP-5) — must stay literally true:
  "The app does nothing online in the background. It goes online only when you tap a button that
  runs an analysis or a connection test, or Browse models, and only to {provider} at {host}, the
  service you chose in Gear › App › AI analysis. An analysis sends the brews it reads — recipe,
  coffee details, grinder and its scale, brewer, water, times, bed, taste and your notes — and, if
  the model can see images and you left them ticked, up to 3 photos of that brew. Your key is
  encrypted with this phone's key store, travels only in the request header to that address, and
  is never in a backup. Photos are re-saved without location or camera details and leave the phone
  only in an analysis or a backup you save. Android's own backup, if it is on, can include the
  brew log and saved analyses, but never the key or photos." (Not-configured variant analogous.)

### 10.3 Moving data from the debug package to the release package (README section, WP-5)

1. Install the 2.0.0 **debug** build over the old debug app (same package and debug key, so Room
   migrates 3 → 4 in place). 2. Gear › App › Back up everything → save to Downloads or Drive.
3. Install the release APK (`com.laksh.coffee`, a separate app). 4. Gear › App › Restore from a
   backup → pick the file → Add to this phone. 5. Re-enter the AI key in the release app (keys
   never travel). Fallback without updating the debug app: its Insights › Save JSON export (1.x
   format) imports too, minus saved analyses, custom recipe edits, and a few bean/grinder fields.

### 10.4 Tests (WP-5)

`BackupRoundTripTest` (entities → DTO → JSON → DTO → entities equal, ids remapped, enums
round-trip, `NaN` tolerated), `LegacyImportTest` (`shared/fixtures/coffee-brew-log-1-sample.json`,
written from today's `BrewReport.json` output on the debug seed; asserts label mapping, recipe
resolution, grinder link, deterministic uids), `BackupFixtureTest` (`shared/fixtures/backup-v2-sample.json`
parses; the web app tests the same file), `MergeRulesTest` (pure merge planner: newer wins, equal
keeps, unknown added, replace mode).

---

## 11. Release (build edits in WP-1; docs in WP-5)

- `versionCode = 2`, `versionName = "2.0.0"` (WP-1). Release keeps `isMinifyEnabled` /
  `isShrinkResources`; `proguard-rules.pro` already keeps `com.laksh.coffee.**` members, which covers
  every new `@Serializable` class and the custom serializers.
- Keystore (the **owner** runs this once; the password never goes into the repo or chat):
  `keytool -genkeypair -v -keystore %USERPROFILE%\.coffee\coffee-release.jks -alias coffee -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Coffee"`,
  then `%USERPROFILE%\.coffee\keystore.properties`:
  ```
  storeFile=C:/Users/<you>/.coffee/coffee-release.jks
  storePassword=…
  keyAlias=coffee
  keyPassword=…
  ```
  and in `%USERPROFILE%\.gradle\gradle.properties`: `coffee.signing=C:/Users/<you>/.coffee/keystore.properties`.
  Back up the .jks and passwords: losing them means future releases can't install over this one.
- Build: `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`;
  check with `apksigner verify --print-certs` (build-tools); rename to `coffee-2.0.0.apk` for the
  GitHub release. Anyone can install it by allowing installs from their browser/files app.
- README (WP-5): Install section (download the APK from Releases, what the "unknown apps"
  prompt means), Building a release, the debug → release move (§10.3), What's new in 2.0, and an
  updated "Not built" list (Bluetooth scales; photos now built; recipe editing UI now built;
  exportSchema now on).

---

## 12. Navigation contract and the unavoidable PERF-file edits (WP-6, after PERF)

New screens are plain composables with callbacks; nothing in WP-2..WP-5 touches `CoffeeNav.kt` or
`MainActivity.kt`. WP-6 registers them in whatever navigation architecture PERF leaves (string
routes today). If PERF has replaced string routes, keep the names below as the route identities.

| identity | args | composable | entered from |
|---|---|---|---|
| `onboarding` | — | `OnboardingScreen(onFinished = { pop back to home }, onOpenAiSettings = { show AiSettingsSheet or Gear })` | first launch; Home personalise card; Gear › App › Run setup again |
| `recipeEdit` | `id: String?` (optional), `mode: new|edit|duplicate` | `RecipeEditorScreen(recipeId, mode, onDone = { id -> pop; if new/duplicate navigate recipe(id) }, onBack = pop)` | Recipe list "+", Recipe detail Edit / Duplicate |

Why each edit is unavoidable:
- `CoffeeNav.kt` (routes, ~40 lines): these are full screens with predictive back and shared-axis
  motion; a Dialog would skip the app's transitions — the opposite of the "smooth between sections"
  ask. First-launch gate: a `LaunchedEffect(Unit)` that reads
  `!settings.first().onboardingDone && app.repository.isEmpty()` (suspend) and navigates to
  `onboarding` with `popUpTo(HOME)`. Reading emptiness (not just the flag) means an existing user
  is never sent to onboarding even if StartupTasks hasn't run yet.
- `MainActivity.kt` (1 line): `CoffeeTheme(darkMode = settings.darkMode, units = Units(settings.tempUnit == "f", settings.weightUnit == "oz"))`.
  Units must be provided above every screen; the theme call lives only here.
- Also in WP-6, because PERF may be reshaping these screens: `BrewScreen.kt` setup (insert
  `GrindStartHint` and a `DialReadingInput` toggle into `GrindCard`, `catalog entryHint` as helper
  text, temp via `LocalUnits`); `RecipeDetailScreen.kt` (Edit / Duplicate / Reset actions, a
  `GrindRangeCard` for the default grinder and the recipe's method, temp via `LocalUnits`);
  `HomeScreen.kt` TEMP cell via `LocalUnits`; wire `HomeScreen.onPersonalise`,
  `RecipeListScreen.onNewRecipe`, `GearScreen.onRunSetup`.

---

## 13. Work packages

### 13.1 Order

```
WP-1 Foundation ──┬── WP-2 Personalisation UI ──┐
                  ├── WP-3 Add your key / AI ───┼── WP-4 Photos ── WP-5 Backup & docs ── WP-6 Integration
                  └── PERF (other owner) ───────────────────────────────────────────────┘
```
WP-2 and WP-3 run in parallel (disjoint files, §13.3). PERF may start once WP-1's build edits and
mechanical Brewer call-site fixes are in (both touch shared files). WP-6 needs PERF, WP-2 and
ideally WP-5 (it runs the final full verification and `assembleRelease`).

### 13.2 Packages

**WP-1 Foundation: shared data, catalog, Brewer, grind scale, Room v4, settings, signing.**
Deliverables §2, §3, §4, §5, §6, §11 build part. Done when: `assembleDebug`, `testDebugUnitTest`,
`lintDebug` and `connectedDebugAndroidTest` (MigrationTest) pass; `shared/recipes.json` validated;
schemas 3.json and 4.json committed; the app behaves exactly as 1.x for the owner's data (same
captions for the 10-click K-Ultra row, same flags), plus nothing new visible yet except labels from
the catalog.

**WP-2 Personalisation UI.** §7. Done when: onboarding, picker (search tests pass), bean editor,
grinder editor with catalog fill + scale notice + ranges, recipe builder (validation tests pass),
units, Gear YOU section, Home card and recipe-list filters work in the debug build. Until WP-6
registers routes, reach the two new screens through rows in Gear › App shown only when
`BuildConfig.DEBUG`, hosting each screen in a full-screen `Dialog`; WP-6 deletes those rows.

**WP-3 Add your key / AI.** §8. Done when: provider list from the catalog, per-provider keys with
the 1.x CommandCode key migrated intact, Anthropic and OpenAI-compatible clients (wire tests pass),
model picker, prompt v2 with scale/coffee/brewer/water/principles, `shared/principles.json`
validated and sourced, stale wording, markdown report enriched.

**WP-4 Photos.** §9. Done when: add from camera and library on an API 26 and an API 36 emulator,
thumbnails, viewer, delete, bag photo, sweep, analysis attaches ticked photos only for
vision models, EXIF stripped (verify with `exiftool` or `ExifInterface` on the stored file).

**WP-5 Backup v2, release docs.** §10, §11 docs. Done when: round-trip and legacy tests pass; a
real debug → release move works on the emulator (export in `com.laksh.coffee.debug`, import in
`com.laksh.coffee`, counts match, re-import adds nothing); `shared/schema/backup-v2.md` and fixtures
committed and validated; README and `claude/ANALYSE.md` updated; privacy copy rewritten.

**WP-6 Integration (after PERF).** §12. Done when: routes work with predictive back, first-launch
gate correct for a fresh install and for an upgraded install with data, units flow everywhere,
BrewScreen/RecipeDetail hooks in, all tests + `assembleRelease` (signed if the owner's
keystore.properties is configured) pass.

### 13.3 File ownership (who may edit what; "→" = later package edits the same file after the earlier one is done)

| file | owner(s) in order |
|---|---|
| `gradle/libs.versions.toml`, root `build.gradle.kts`, `app/build.gradle.kts` | WP-1 (first commit) → PERF (append its blocks after) |
| `app/schemas/**` | WP-1 |
| `shared/recipes.json`, `shared/schema/recipes.md` | WP-1 (→ WP-6 only if recipe research arrives late, §2.7) |
| `shared/principles.json`, `shared/schema/principles.md` | WP-3 |
| `shared/schema/backup-v2.md`, `shared/fixtures/*` | WP-5 |
| `shared/catalog/*.json` | research agents; WP-1 may fix ids/data only to satisfy CatalogParseTest/LegacyIdsTest and must tell the web agent |
| `domain/catalog/*` (Catalog, models, sources, LegacyIds) | WP-1; `CatalogSearch.kt` WP-2 |
| `data/catalog/*` | WP-1 (CatalogRepository.search body: WP-2) |
| `domain/Brewer.kt`, `domain/Recipe.kt`, `domain/recipes/ExpertRecipes.kt`, `domain/grind/*` | WP-1 |
| `domain/RecipeValidation.kt` | WP-2 |
| `domain/ai/DataQuality.kt` | WP-1 → WP-3 (read-only unless needed) |
| `domain/ai/AnalysisResult.kt` | WP-1 (GrindMove) → WP-3 |
| `domain/ai/AnalysisPrompt.kt`, `ModelCatalog.kt`, `AiError.kt`, `BaseUrl.kt`, `Providers.kt` | WP-3 |
| `domain/BrewReport.kt` | WP-1 (compile fixes) → WP-3 (markdown) → WP-5 (remove json()) |
| `domain/backup/*`, `data/backup/*`, `ui/backup/*` | WP-5 |
| `data/db/**` (entities, DAOs, CoffeeDatabase, Converters, Migrations), `data/ai/AnalysisEntity.kt`, `data/ai/AnalysisDao.kt` | WP-1 |
| `data/prefs/SettingsStore.kt`, `data/StartupTasks.kt` | WP-1 (→ WP-4 appends sweep to StartupTasks) |
| `data/repo/CoffeeRepository.kt` | WP-1 → WP-4 (photo file deletion) |
| `data/ai/*` other than entity/dao | WP-3 → WP-4 (`AnalysisRepository` photo source wiring only) |
| `data/photos/*`, `ui/photos/*`, `res/xml/photo_paths.xml`, `AndroidManifest.xml` | WP-4 |
| `CoffeeApp.kt` | WP-1 → WP-3 → WP-4 → WP-5 (each adds its singletons) |
| `ui/catalog/*`, `ui/onboarding/*`, `ui/recipes/*`, `ui/theme/Units.kt`, `ui/theme/Theme.kt` | WP-2 |
| `ui/components/DialReadingInput.kt`, `GrindRangeCard.kt`, `GrindStartHint.kt` | WP-2 |
| `ui/components/CoffeeArt.kt`, `Grouped.kt`, `RecipeCard.kt` | WP-1 (≤10-line Brewer fixes) → WP-2 (`RecipeCard` units) — PERF must not be mid-edit on these while WP-1 runs |
| `ui/screens/gear/*` (GearScreen, GearViewModel, BeanEditorSheet, GrinderEditorSheet) | WP-1 (compile fixes) → WP-2 → WP-4 (bag photo in BeanEditorSheet) → WP-5 (YOUR DATA section call in AppTab) |
| `ui/screens/home/HomeScreen.kt`, `HomeViewModel.kt` | WP-2 (card + suggestion) → WP-6 (units, wiring) |
| `ui/screens/recipes/RecipeListScreen.kt`, `RecipesViewModel.kt` | WP-1 (key fix) → WP-2 |
| `ui/screens/recipes/RecipeDetailScreen.kt` | WP-6 only (PERF likely reshapes its shared header) |
| `ui/screens/brew/BrewScreen.kt` | WP-1 (POUR_OVER + describeClicks delegation, ≤10 lines) → PERF → WP-6 |
| `ui/screens/brew/BrewViewModel.kt` | WP-1 |
| `ui/screens/review/*` | WP-1 (dialReading) → WP-4 |
| `ui/screens/history/*` (BrewDetailScreen, HistoryScreen, HistoryViewModel) | WP-1 (brewer tag) → WP-4 |
| `ui/screens/insights/*` | WP-1 (dial helpers) → WP-5 (export, privacy) |
| `ui/ai/*` | WP-3 → WP-4 (`BrewAnalysisSection` photo line) |
| `ui/nav/CoffeeNav.kt`, `MainActivity.kt` | PERF → WP-6 |
| `src/debug/.../DebugTools.kt` | WP-1 → WP-3 (compile fixes for removed `ModelCatalog` constants only) → WP-4 (seed a sample photo: optional) |
| `README.md`, `claude/ANALYSE.md` | WP-5 (→ WP-6 final check) |
| `app/src/test/**`, `app/src/androidTest/**` | each package its own new files; WP-1 owns `DataQualityTest`, `GrindMoveTest` edits; WP-3 owns `ModelCatalogTest`, `AnalysisParserTest` edits |

---

## 14. Risks

See the structured output; the main ones are repeated here for implementers:
- Catalog ids still moving while WP-1 runs (LegacyIdsTest catches it; fallbacks keep the app
  working).
- The K-Ultra entry in grinders.json must express ranges in total clicks (style `clicks`, whole
  numbers); if research writes dial numbers (6.0–8.0) the ranges will be wrong by ×10 —
  CatalogParseTest's whole-number rule catches it.
- 3.json must be generated before entities change, or the migration test can't be written.
- Prompt changes mark every stored analysis stale; §8.6 wording must ship with WP-3.
- Backup import holds the decoded file in memory; capped at 64 MB.
- Shared UI files between WP-1 and PERF; sequence per §13.3.

## 15. Open questions

See the structured output.
