# Coffee

A brew timer and a brewing log for people who make coffee by hand, and a way to work out why
a cup tasted the way it did.

Pick a recipe from someone who has thought about it properly, follow the timer, answer a few
questions about the bed and the taste, and the app remembers. Over time it shows what you
change, what you never change, and what tracks with your best cups. If you add your own key
for an AI service, it will read a brew and tell you the one thing to change next, as a number
on your own grinder.

- **Android app** (this repo, `app/`): Android 8.0 or newer.
- **Web app** (`web/`): the same thing in a browser, sharing the recipes, the catalogs and the
  backup file with the phone.

No account. No analytics. No ads. Everything you log stays on your device until you save a
backup or share something yourself. See [Privacy](#privacy).

---

## Get it

### Android

1. Open the [latest release](https://github.com/lakshya-kamra/coffee/releases/latest) on your
   phone and download the `.apk` file attached to it (`coffee-2.0.0.apk` for version 2.0.0).
   That is the signed release build. There is nothing to build yourself.
2. Open the downloaded file. Android will say something like "For your security, your phone
   is not allowed to install unknown apps from this source". That prompt appears for every
   app that does not come from an app store. Tap **Settings**, allow your browser (or Files
   app) to install apps, go back and tap **Install**. You can switch that permission off
   again afterwards.
3. Open Coffee. The first launch asks a few questions (units, your grinder, your brewers) so
   the recipes and grind advice fit what you own. Every step can be skipped.

The app is not on the Play Store. To update, download the newer APK from the same page and
install it over the old one; your brews stay. Updates only install over a copy signed with
the same key, which is why you should always take the APK from this repository's Releases.

### Web

Web app: <https://coffee-psi-brown.vercel.app>

The browser version needs no install. It can be added to a home screen and works offline
once loaded. See [Web](#web).

---

## What is in it

**Recipes from people who know.** 44 methods for V60, Kalita, Chemex, Origami, Orea, April,
Pulsar, Switch, Clever, AeroPress, French press, moka pot, espresso and cold brew, from James
Hoffmann, Tetsu Kasuya, Lance Hedrick, Scott Rao, Jonathan Gagné, Matt Perger, Morgan Eckroth,
Tim Wendelboe, Onyx Coffee Lab, eight World AeroPress Championship winners and several
brewers-cup competitors. Every recipe says
where its numbers came from, with a link where a published source exists (see [Credits](#credits-and-sources)).
Change the dose and every pour rescales. Edit any recipe, copy one, or write your own in the
recipe builder; "Reset to original" always brings the published version back.

**A timer you can read at arm's length.** The whole brew on one dial: elapsed time as an arc,
every step as a tick, the water target as a level in the middle. It buzzes when a step
changes so your eyes stay on the scale. Pause, nudge five seconds, skip a step, and tap once
when the bed is exposed so drawdown is measured, not guessed.

**Your gear, not a generic list.** Pick your grinder from 57 popular ones and the app knows
its scale: how many clicks or numbers per turn, which way is finer, how the dial reads, and
roughly where each brew method sits on it. Pick your brewers from 51, your roaster from 404,
and describe a coffee as loosely or as precisely as you like: origin and region, farm,
producer, washing station, single estate or blend, variety, process (28 of them), decaf
method, roast level, altitude, harvest, roast date, rest, price. Grams or ounces, °C or °F,
and the water you brew with. Anything not on a list can be typed in.

**Grind as a number.** A grind setting is stored as a number on your grinder's own scale,
not as text, so advice can say "go to 7.2 on the dial" instead of "a bit finer". The app
shows it the way your dial does (a 1Zpresso K-Ultra reads 7.5, a Comandante counts clicks)
and flags a logged value that is finer than that grinder's espresso range on a filter brew.

**The questions afterwards.** Drawdown and what came out; what the spent bed looked like
(seven shapes, each with a plain reading of what it means), silt and channelling; seven taste
sliders, descriptors, faults, a score, and one line on what you will change next time.

**Photos.** Add a photo of the bed or the cup to a brew, or of the bag to a coffee. Copies
are shrunk and saved without location or camera details. No camera or storage permission is
requested: the system camera and photo picker do the work.

**Insights.** A brew passport (lifetime brews, litres, streaks, personal bests), your recent
scores, a grind journey for your most-brewed recipe, your most-brewed recipes and coffees,
and a read of the patterns across your whole log.

**Analysis, if you want it.** See the next section.

**Backup.** Everything in one file you keep: see [Your data](#your-data).

### What's new in 2.0

- Works for any setup: grinder, brewer, roaster, origin, process and water catalogs, a setup
  flow, units, and a recipe builder.
- 30 more recipes, each one sourced; two published recipes that 1.x misquoted are corrected
  (Kasuya's 4:6 opening pours, Hoffmann's AeroPress).
- "Add your key" for a dozen AI services instead of one, with a key per service.
- Photos of the bed, the cup and the bag, and models that can see them.
- Backup and restore, on the phone and the web, in one shared format.
- A web version.
- Smoother movement between screens: tabs stay alive and cross-fade, the app asks the
  display for its highest refresh rate, and a baseline profile ships in the APK.
- The 1Zpresso K-Ultra scale is fixed. 1.x said 10 clicks a turn; the grinder has 100, and a
  dial reading of 7.5 is 75 clicks. A grinder saved by 1.x keeps its numbers and its old
  read-out until you tap "Use these numbers" on its card. Your logged brews are never changed.

---

## Analysis: bring your own key

Tap **Analyse brew** on a brew, or **Find patterns** on Insights, and a model reads it: that brew
plus the 8 before it, or the whole log (up to 60 brews). A brew read comes back with one
change and an exact number on your grinder, what to expect if it is right, and what it means
if it is wrong. The pattern read says what keeps going wrong and sets a three-brew
experiment.

**You choose who answers.** In Gear › App › AI analysis pick a provider and paste your own
key for it: OpenAI, Anthropic (Claude), Google Gemini, OpenRouter, DeepSeek, xAI, Groq,
Mistral, Together, Fireworks, Perplexity, Command Code, or **Custom** for any
OpenAI-compatible `https` address. The provider bills you directly; the app has no server
and takes nothing. Ollama and LM Studio run on a computer, so they are offered in the web
app; on the phone, reach one through Custom with an `https` URL. A fresh install has no
provider chosen and never goes online until you set one up.

**What the model is told.** The recipe and how you deviated from it, the coffee (origin,
process, roast, days off roast), your grinder with its scale and sensible move sizes, the
brewer, the water, times, bed, taste and notes, and a short list of sourced brewing
principles from the same experts as the recipes, so advice is anchored in what they have
published and not in whatever the model half-remembers. If the model can see images and you
leave them ticked, up to 3 bed or cup photos of that brew go too.

**Checked before anything is sent.** The log is checked on the phone for impossible values:
0 clicks, a cup weight that implies impossible retention, a drawdown longer than the brew,
taste sliders never touched, a missing roast date. Those go to the model as "not known", with
the reason, so a typo is never diagnosed as a grind problem.

**Stored, never re-billed.** Results are saved, so reopening a brew never calls the model
again. Re-run is always an explicit tap, and a failed re-run keeps the previous result.

**Without a key** the brew still gets an offline first read: pattern-matching on what you
logged, hedged because that is all it is. The share sheet builds a full report you can paste
into any chat app, and [`claude/ANALYSE.md`](claude/ANALYSE.md) has a prompt for reading a
whole backup file with Claude Code.

---

## Privacy

The app shows this same text under Insights › What leaves the phone, and names the provider
you chose.

- The app does nothing online in the background. It goes online only when you tap a button
  that runs an analysis or a connection test, or Browse models, and only to the service you
  chose.
- An analysis sends the brews it reads (recipe, coffee details, grinder and its scale,
  brewer, water, times, bed, taste, your notes) and, if the model can see images and you
  left them ticked, up to 3 bed or cup photos of that brew.
- Your key is encrypted with the phone's key store, is kept apart from everything else, and
  travels only in the request header to the address it was saved for. It is never logged,
  never in a URL, and **never in a backup**. Only `https` addresses are accepted.
- Photos are re-saved without location or camera details when you add them, and again when
  they arrive in a backup. They leave the phone only in an analysis or in a backup you save
  with photos included.
- A backup goes only where you save it.
- Android's own backup, if you have it on, can include the brew log and saved analyses, but
  never the key or the photos.

The web app's version of this is under [Web › Privacy](#privacy-1).

---

## Your data

**Gear › App › Your data.**

- **Back up everything** writes one `.json` file wherever you choose (Downloads, Drive, a USB
  stick): brews, coffees, grinders, your recipes, saved analyses, settings, which AI provider
  and model you use, and your photos if you leave "Include photos" on. Never a key.
- **Restore from a backup** reads such a file from the phone or the web app, shows what is in
  it, and then either **adds** it to what is here (a record that is in both places is replaced
  only when the backup's copy is newer) or **replaces everything**. Settings and the AI choice
  come along only if you tick them. Restoring the same file twice changes nothing the second
  time, and a restore that fails changes nothing at all.
- Insights › **Save backup (JSON)** writes the same file without photos, for reading with
  other tools.

The file format is documented in [`shared/schema/backup-v2.md`](shared/schema/backup-v2.md),
and both apps are tested against the same sample files in `shared/fixtures/`.

### Moving from the old debug app to 2.0

This only concerns you if you ran the app before 2.0. Back then it was never released: it
was only ever installed as a debug build, and Android knows that build as
`com.laksh.coffee.debug`. Version 2.0 from Releases is `com.laksh.coffee`.

**They are two separate apps.** Installing 2.0 does not update or replace the old one. Both
sit on the phone side by side, each with its own data, and neither can read the other's.
Your brews move across in a file: **back up** in the old app, **restore** in the new one.

The short way, with nothing but the phone:

1. In the old app: Insights › **Save JSON export**, and save the file to Downloads or Drive.
2. Install 2.0 from Releases. It appears as a second Coffee app.
3. In 2.0: Gear › App › **Restore from a backup**, pick the file, **Add to this phone**.

That file is the older 1.x format. It carries your brews, coffees and grinders, and your
logged grind numbers arrive exactly as you logged them. It does not carry saved analyses.

The complete way, if you built the old app yourself and still have that computer:

1. Install the 2.0 **debug** build over the old debug app (`./gradlew installDebug`). It is
   the same package and the same debug key, so the database upgrades in place and nothing
   is lost.
2. In that app: Gear › App › **Back up everything**, and save the file.
3. Install 2.0 from Releases, then Gear › App › **Restore from a backup**, pick the file,
   **Add to this phone**.

Either way, add your AI key again in 2.0 (keys never travel in a file), check that your brews
are there, and then uninstall the old app.

---

## Building it

JDK 17 and the Android SDK with platform 36. The Gradle 9.7.1 wrapper is checked in.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # onto a connected phone with USB debugging on
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The debug build is for development. It installs as `com.laksh.coffee.debug`, a separate app
next to the release (`com.laksh.coffee`) with its own data; to use the app day to day, take
the signed APK from [Releases](https://github.com/lakshya-kamra/coffee/releases/latest).
Minimum Android 8.0 (API 26); compileSdk and targetSdk 36.

Tests: JVM unit tests cover the recipes and catalogs, grind scales, data-quality checks, the
prompt, both AI wire formats, the backup codec and merge rules, and the Room migration SQL
against a real SQLite. Instrumented tests (`app/src/androidTest`) cover the Room migration,
the photo pipeline and backup and restore on a device.

### Building a release

```bash
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

The release is minified with R8 and ships a baseline profile. After changing screens,
regenerate the profile with an emulator or phone connected
(`./gradlew :app:generateReleaseBaselineProfile`) and commit
`app/src/release/generated/baselineProfiles/`.

**Signing.** Android only installs an update over an app signed with the same key, so the
release key matters and is deliberately **not in this repository**. The build looks for a
small properties file outside the repo, in this order:

1. the Gradle property `coffee.signing` (`-Pcoffee.signing=/path/to/keystore.properties`, or
   that line in `~/.gradle/gradle.properties`),
2. the environment variable `COFFEE_SIGNING` (for CI),
3. `~/.coffee-signing/keystore.properties`, if it exists.

That file has four lines and never leaves your machine:

```
storeFile=coffee-release.jks      # absolute, or relative to this file
storePassword=<your store password>
keyAlias=coffee
keyPassword=<your key password>
```

Without it, `assembleRelease` still succeeds and produces an unsigned APK, so a fresh clone
or a CI machine never breaks. To make your own key (once):

```bash
keytool -genkeypair -v -keystore coffee-release.jks -alias coffee \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Coffee"
```

Keep the `.jks` and both passwords somewhere safe and backed up. If they are lost, nobody can
publish an update that installs over the existing app; everyone would have to back up,
uninstall and restore. If you fork the project, your build is signed with your key and
installs as a different publisher's app: it will not install over an official release.

Check a signed APK with `apksigner verify --print-certs app-release.apk` (in the SDK's
build-tools), rename it `coffee-<version>.apk`, and attach it to a GitHub release.

### Why these dependency versions

They are the newest that build against a **stable** Android SDK. AGP 9.4.0, Gradle 9.7.1,
Kotlin 2.2.10, Compose BOM 2026.06.01. Anything newer on the AndroidX side wants compileSdk
37, which is still in preview, so these are held back on purpose and lint reports them as
"a newer version is available":

| Pinned | Newest | Held back because |
| --- | --- | --- |
| Compose BOM 2026.06.01 | 2026.08.00 | Compose 1.12 requires compileSdk 37 |
| navigation 2.9.8 | 2.10.0 | requires compileSdk 37 |
| lifecycle 2.10.0 | 2.11.0 | requires compileSdk 37 |
| core-ktx 1.17.0 | 1.19.0 | requires compileSdk 37 |
| activity-compose 1.12.4 | 1.13.0 | moves with lifecycle |

When API 37 goes stable, install that platform, set `compileSdk = 37`, and take all five.
Kotlin is pinned at 2.2.10 because KSP has no 2.4.x release and Room needs KSP;
kotlinx-serialization stays on 1.9.0 to match that compiler.

Three things AGP 9 needed:

- **No `org.jetbrains.kotlin.android` plugin.** AGP 9 has built-in Kotlin; applying the
  Kotlin Gradle plugin fails outright. Compose, serialization and KSP still apply normally.
- **`android.disallowKotlinSourceSets=false`** in `gradle.properties`, because KSP still
  registers its generated sources through `kotlin.sourceSets`. Drop it once KSP goes AGP 9
  native.
- **The Room Gradle plugin applies as is** (`room { schemaDirectory(…) }`) and adds
  `app/schemas` to the instrumented test assets for `MigrationTestHelper`.

Lint's one warning about `mipmap-anydpi-v26` is wrong (removing the qualifier breaks the
launcher icon) and is suppressed in `app/build.gradle.kts` with a comment saying so.

---

## Web

The same app in a browser: `web/` (Vite, React 19, TypeScript, Motion, Dexie). It reads the same
`shared/` catalogs and recipes as the Android app and reads and writes the same backup file, so
a backup saved on the phone restores in the browser and the other way round. There is no server:
the site is static files, and everything you log is stored in that browser's IndexedDB.

### Run and build

Node 22 or newer (`web/.nvmrc` says 24, which is what Vercel should use).

```bash
cd web
npm ci
npm run dev          # http://localhost:5173, hot reload
npm run build        # typecheck, then the production build in web/dist
npm run preview      # serves web/dist with the production headers from vercel.json
```

Checks, all run from `web/`:

| command | what it proves |
|---|---|
| `npm run typecheck` | `tsc -b` over the app, worker, test and config projects |
| `npm run lint` | ESLint, including the bans that keep the strict CSP and the key storage rules honest |
| `npm test` | Vitest: domain logic, grind scales, backup codec, AI request shapes, motion tokens against `Motion.kt` |
| `npm run smoke` | builds, serves the build with the production headers and drives it in headless Chrome: every route, a full brew, backup out and back in, offline, axe in both themes, keyboard and 320 px checks, and the performance pass. `--no-build` reuses `dist/`; `--only=<name>` runs one flow |
| `npm run vercel-sim` | copies only what `.vercelignore` lets through into a temp folder and builds there, so the deploy needs nothing outside the upload |

The smoke run uses the Chrome installed on the machine. Its performance pass throttles the CPU
4× and fails on any long animation frame (over 50 ms) during ten tab switches and three recipe
open and close cycles. Headless Chrome draws at 60 Hz, so 120 Hz itself is checked on a phone.

### Deploy

The site is deployed from the repo root; `vercel.json` there holds the build commands, the SPA
rewrite and every response header, and `.vercelignore` is an allow-list (only `web/`, `shared/`
and the two config files are uploaded, never the Android sources).

1. In Vercel, import the GitHub repo. Root Directory: the repo root. Framework preset: Other.
   Node.js version: 24.x. Everything else comes from `vercel.json`. (Or, from the repo root:
   `npx vercel link`, then `npx vercel deploy --prod`.)
2. Check the deployment: `cd web && npm run check-deploy -- https://<your-deployment>`. It reads
   a few URLs and compares the security headers with `vercel.json`, checks that `/sw.js` is
   served as JavaScript and not swallowed by the rewrite, that a deep link such as
   `/log/anything` returns the app, and that a missing `/assets/` file is a 404.
3. Put the site's address in `WEB_APP_URL` in `web/src/app/links.ts`. The Android download and
   source links in the same file already point at the GitHub repo and its latest release.

### Privacy

- No account, no analytics, no third-party scripts, fonts or images. The Content-Security-Policy
  allows scripts and styles from the site itself only.
- Brews, coffees, grinders, recipes, photos and saved analyses live in IndexedDB in that browser.
  Clearing site data deletes them; **Gear › App › Your data** saves and restores a backup file.
  Browsers may evict storage for sites that are not installed or not visited (Safari after about
  a week), so the app asks for persistent storage and reminds you to back up.
- The page goes online for one reason: an analysis, a connection test or a model list that you
  start. The request goes straight from the browser to the AI service you chose, with your key.
  Nothing passes through a server of ours, because there is none.
- An API key is kept only in the open page's memory (gone when the tab closes or reloads, and
  never written to disk) unless you tick "Remember on this device", which saves it in local
  storage. Keys are never written to IndexedDB, a backup, a URL or the console, and **Delete
  everything** removes them too.
- Photos have their EXIF data (location included) stripped before they are stored, and photos
  arriving in a backup made elsewhere are re-encoded the same way. They are sent
  with an analysis only to models that can see images, three at most, each one untickable.

### Browser support

The last two versions of Chrome, Edge and Firefox, and Safari on iOS and macOS 17 or newer.
Newer features are used only where missing them costs nothing but polish: the screen stays awake
during a brew where the Wake Lock API exists, vibration cues work on Android, sound cues are off
by default, and installing to the home screen needs Chrome, Edge or Safari's Share menu. Local
models (Ollama, LM Studio) work from a desktop browser on the same machine. Reduced motion is
honoured from the system setting or from Gear › App.

---

## Design and motion

- Flighty-inspired: numbers are the heroes, labels sit above them, and colour appears only
  when it means something. Green is on target, amber needs attention, red is a fault, and
  every status is paired with a word. Crema is the one live accent: the brew in progress and
  the one primary action on a screen.
- The brew is a journey line, planned against actual. A brew's detail is a boarding pass, and
  Insights carries a brew passport.
- Roboto at 400, 500 and 700 only, with tabular numerals everywhere, so digits never jiggle.
  Dark and light are tuned separately, token by token.

The motion brief was PixelPlayer: physics-based, continuous, interruptible, nothing snaps.
Most of what makes that true is invisible:

- **The five tabs stay alive.** They are composed once and switched with a fade-through on
  the layer only, with their data already loaded, so a switch draws existing pixels instead
  of building a screen mid-animation.
- **Only placement, layer and draw move.** Nothing re-measures per frame; indicators slide in
  `graphicsLayer`, and colours cross-fade in the draw phase.
- **The timer does not recompose the screen.** The dial reads the clock once per frame and
  draws; the rest of the brew screen stays still.
- **Screens hand off.** A recipe card's header flies into the detail screen's header, the
  setup drawing morphs into the dial, and back gestures scrub the transition.
- **The display is asked for its fastest mode**, and the APK ships a baseline profile so the
  first launches are not spent compiling.

One vocabulary of springs lives in `ui/theme/Motion.kt`, and the web app's
`web/src/design/motion.ts` carries the same table. What has not been done: a measurement on
a real 120 Hz phone. The emulator used during development is 60 Hz with a software renderer,
so its frame numbers say little.

---

## Credits and sources

The recipes and the brewing advice in this app are other people's work, and the app says so
on every one. Nobody listed here is affiliated with the app or has endorsed it.

**Recipes** (`shared/recipes.json`, each entry with its sources and links): James Hoffmann,
Tetsu Kasuya, Lance Hedrick, Scott Rao, Jonathan Gagné, Matt Perger, Morgan Eckroth, Tim
Wendelboe, Onyx Coffee Lab, Matt Winton, Emi Fukahori, Patrik Rolf, Carlos Medina, Carolina
Ibarra Garay, George Stanica, Jibbi Little, Némo Pop, Ole Kristian Bøen, Paulina Miczka,
Sherry Hsu, Tay Wipvasutt, Tuomas Merikanto and Wendelien van Bunnik, plus one community
standard.

Each recipe carries one of four labels:

- **Published** (21): the numbers are the author's own, from a video, article or
  championship routine that states them.
- **Adapted** (22): the technique is clearly the author's, but they give ranges or vary it
  between sources, so the figures are a sensible starting point and not a quotation.
- **Community** (1): a widely used method with no single author.
- **Custom**: yours.

**Brewing principles** (`shared/principles.json`, 35 short statements with the page each
came from): Scott Rao, Jonathan Gagné (Coffee ad Astra), James Hoffmann, Tetsu Kasuya, Lance
Hedrick, Tim Wendelboe and Matt Perger (Barista Hustle). These are what an analysis is asked
to reason from.

**Catalogs** (`shared/catalog/`): grinder scales from the makers' manuals and published
settings charts, with a confidence level on each entry; roasters, origins, varieties,
processes, brewers and water recipes from public sources. A scale the catalog is not sure of
says so in the app, and nothing a catalog says ever overwrites a number you entered.

If something is attributed wrongly or a number is off, the files above are plain JSON: open
an issue or a pull request with the source.

Design debts: [Flighty](https://flighty.com) for the information design, PixelPlayer for the
motion brief.

---

## Layout

```
app/                     the Android app (Kotlin, Compose, Room)
  src/main/java/com/laksh/coffee/
    core/                formatting helpers
    data/
      ai/                key vault, AI settings, the app's only HTTP code, analyses
      backup/            backup writer, restore, merge into the database
      catalog/           loading and linking the shared catalogs
      db/                Room entities, DAOs, migrations
      photos/            photo files and rows
      prefs/             DataStore settings
      repo/              one repository over the lot
    domain/
      ai/                data-quality checks, prompt, result contract, providers
      backup/            backup v2 records, codec, 1.x reader, merge rules
      catalog/           catalog models and search
      grind/             grinder scales and dial read-outs
      Recipe.kt, Brewer.kt, Evaluation.kt, BrewReport.kt
    ui/
      theme/ components/ nav/
      ai/ backup/ catalog/ onboarding/ photos/ recipes/
      screens/           home, recipes, brew, review, history, gear, insights
  schemas/               exported Room schemas (migration tests build old databases from them)
baselineprofile/         baseline-profile generator and macrobenchmarks
shared/                  one copy of the data for both apps
  recipes.json  principles.json  catalog/*.json
  schema/                what each file means
  fixtures/              sample backups both apps are tested against
web/                     the web app (Vite, React, TypeScript)
claude/ANALYSE.md        a prompt for reading a backup with Claude Code
docs/plan/               the specs and research this version was built from
```

No dependency-injection framework: singletons hang off the `Application`, and ViewModels are
built by `viewModelFactory`.

---

## Not built

- No cloud sync. A backup file is the way between devices.
- No Bluetooth scale integration.
- No translations: the app is in English.
- Not on the Play Store or F-Droid.
- Not yet verified: frame pacing on a real 120 Hz phone, and the photo picker and camera on
  Android 8 to 10 (developed on Android 16).
- Room migrations: 3 → 4 is covered by `MigrationTest` (instrumented) and `MigrationSqlTest`
  (JVM, real SQLite) against the exported schemas. 2 → 3 was run on an emulator in September
  2026 by upgrading a seeded install in place. 1 → 2 was checked by reading it against the
  entities, not by running it.
