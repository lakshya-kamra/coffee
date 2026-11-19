# Coffee backup, version 2

One JSON file, UTF-8, written and read by the Android app (2.0.0+) and the web app. It holds
everything a person logged, never an API key.

Both apps are tested against the same files in `shared/fixtures/`:

| File | What it is |
| --- | --- |
| `backup-v2-sample.json` | A v2 backup with every key, real catalog ids, one photo and three analyses: two as 2.0.0 stored them, and a trend with `brewUids`. |
| `coffee-brew-log-1-sample.json` | A 1.x export, including the non-standard numbers 1.x could write. |
| `coffee-brew-log-1-expected.json` | What reading that export must give, uids included, and the catalog ids linking then sets. |

Change a fixture only together with this document and both apps.

## Top level

```json
{
  "format": "coffee-backup",
  "version": 2,
  "exportedAt": "2026-11-17T09:41:00.000Z",
  "app": {"platform": "android", "appVersion": "2.0.0", "packageName": "com.laksh.coffee.debug", "dbVersion": 4},
  "options": {"includesPhotos": true, "includesAnalyses": true},
  "settings": { … },
  "ai": { … },
  "beans": [], "grinders": [], "brews": [], "recipes": [], "analyses": [], "photos": []
}
```

- `format` MUST be `"coffee-backup"`. `version` is an integer; a reader rejects a version it does
  not know ("This backup was made by a newer version of Coffee").
- Instants are ISO-8601 UTC strings (`Instant.toString()` / `Date.toISOString()`); readers accept
  them with or without milliseconds, and with more fraction digits than three (the rest is
  dropped). Calendar dates are `"YYYY-MM-DD"`.
- Every array may be empty or missing. Every field may be missing; readers use the defaults below.
  Writers write every key, nulls included, in the order given here. Readers ignore keys they
  don't know.
- Records are linked by `uid` (an opaque string, 1–64 chars, unique per record type; Android and
  web write UUIDs, 1.x data migrated to 32 hex chars). Numeric database ids never link records.
  The one place they appear is inside a stored trend, with the uids they stand for beside them
  (see `result.brewUids` under analyses).
- Ids from the shared catalog (`roasterId`, `originId`, `processId`, `brewerId`, `waterId`, …) are
  the ids in `shared/catalog/*.json`. An id the reader's catalog lacks is kept and shown as-is.
- `photos` is the last key, so a writer can stream the photos and a reader can take them out of
  the file one at a time.

## settings

All optional: `tempUnit` "c"|"f", `weightUnit` "g"|"oz", `darkMode` "system"|"light"|"dark",
`haptics`, `soundCues`, `keepScreenOn`, `autoAdvanceSteps` (booleans), `name`, `ownedBrewerIds` [],
`favouriteRoasterIds` [], `homeArea`, `defaultWaterId`, `defaultBeanUid`, `defaultGrinderUid`,
`lastRecipeId`, `onboardingDone`.

A reader changes only the keys the file carries. `defaultBeanUid` / `defaultGrinderUid` written as
`null` mean "no default"; left out, they mean "say nothing".

## ai

Never a key, and nothing derived from one (no hint, no host a key was saved for, no test result):

```json
{"activeProvider": "commandcode", "sendPhotos": true,
 "providers": [{"id": "commandcode", "baseUrl": null, "model": "z-ai/glm-5.3-flash", "vision": null}]}
```

`activeProvider` is a `shared/catalog/providers.json` id or null (none chosen). `baseUrl` only for
`custom`. `vision` is the user's override for a model the catalog does not know (true/false/null).
A reader skips a provider it does not offer (the phone has no Ollama or LM Studio) and checks a
custom address exactly as it checks one typed in.

## beans[]

`uid`, `name` (required), `roaster`, `roasterId`, `origin`, `originId`, `region`, `farm`,
`producer`, `washingStation`, `lotTypeId`, `speciesId`, `varietalIds` [], `varietal` (free text),
`processId`, `isDecaf`, `decafMethodId`, `roastLevelId`, `roastIntentId`, `altitudeM` (string
"1700" or "1700-1900"), `harvest`, `roastedOn` (date|null), `openedOn` (date|null), `restDays`
(int|null), `bagSizeG` (number|null), `priceAmount`, `currency`, `priceText` (1.x free text),
`tastingNotes`, `notes`, `isArchived`, `createdAt`, `updatedAt`.

`processId` / `roastLevelId` `""` = not recorded.

## grinders[]

`uid`, `catalogId`, `brand`, `model`, `burr` ("conical"|"flat"|"ghost"|"blade"|null), `burrSizeMm`
(string as typed), `setting`: {`style` "clicks"|"numbers"|"stepless"|"microns", `min`, `max`,
`step`, `lowerIsFiner`, `clicksPerRotation` (int|null), `micronsPerStep` (number|null),
`dialFormat` (""|"plain"|"turns"|"turns-dot"|"number-tick:10")}, `defaultFilterSetting`,
`defaultEspressoSetting` (number|null, stored units), `notes`, `isDefault`, `isArchived`,
`updatedAt`.

`clicksPerRotation` unknown is written as `null` (both apps store 0). A grinder's numbers travel
exactly as stored: a 1.x K-Ultra row saved at 10 clicks a turn stays at 10.

## brews[]

`uid`, `startedAt`, `updatedAt`, `recipeId` (built-in or `custom-…`, may be ""), `recipeName`,
`recipeAuthor`, `brewerId`, `brewerLabel`, `beanUid`, `beanLabel`, `daysOffRoast`, `grinderUid`,
`grinderLabel`, `grindSetting` (text), `grindClicks` (int|null — total clicks from zero on click
grinders), `grindValue` (number|null — numbered/stepless dials), `doseG`, `waterG`, `tempC` (int,
always °C), `waterId`, `totalTimeSec`, `drawdownSec`, `beverageG`, `tdsPercent`,
`extractionYield`, `flow`, `bed`: {`shape`, `surface`, `channelling`, `notes`}, `taste`:
{`acidity`, `sweetness`, `bitterness`, `body`, `balance`, `aftertaste`, `clarity`} (0–10),
`overall` (0 = not rated), `descriptors` [], `faults` [], `notes`, `nextTime`, `isFavourite`,
`sharedAt`.

Enumerations (null = not recorded):

- `flow`: stalled | slow | on-target | fast | gusher
- `bed.shape`: flat | domed | crater | high-and-dry | sloped | sinkhole | muddy
- `bed.surface`: none | light | heavy
- `bed.channelling`: none | suspected | obvious

`descriptors` / `faults` are the English chip labels the apps show (e.g. "Stone fruit",
"Boozy / ferment-heavy"). Brews are written oldest first.

## recipes[]

The user's own recipes and their edits of built-ins, each exactly an entry of
`shared/recipes.json` (see `schema/recipes.md`) plus `updatedAt`. An entry whose `id` is a built-in
id is an edit of it; resetting it means deleting the entry. An entry a reader cannot open as a
recipe is left out and reported.

## analyses[]

`uid`, `kind` "brew"|"trend", `brewUid` (null for trend), `createdAt`, `origin` "android"|"web",
`promptVersion`, `inputHash`, `brewCount`, `newestBrewAt`, `providerId`, `model`, `host`,
`rawText`, `result` (the parsed result object or null), `flags` [], `promptTokens`,
`completionTokens`, `costMicroUsd`, `latencyMs`, `photoCount`.

`newestBrewAt` is an instant or null (readers also accept epoch milliseconds). `origin` is kept as
written: an analysis made on the other platform is shown as "Made on another device". `result`
and `flags` are each platform's own stored shape and are carried untouched, apart from the one
shared key `result.brewUids` (below).

`inputHash` says whether the data behind an analysis has changed since it was made. It is only
ever compared on the platform named in `origin`, and only when it starts with `s2:`. Such a hash
is made from the records' uids and field values, never from database row ids, so it is the same
before and after a restore. For the same reason it leaves out anything a restore changes without
the data changing: a brew whose coffee or grinder was deleted is read as having none (the file
has no uid to write for it), and a brew's photos count whether or not this install's settings
send them. Any other value is a 2.0.0 hash, which was made from row ids: a
reader keeps it as written and shows the analysis as "Made by an older version of the analysis",
never as edited.

### result.brewUids

A trend's findings name brews by number (`result.trend.patterns[].brewIds`). Those numbers are
what the model was shown: row ids on the install that asked. `result.brewUids` is an object from
each such number, as a string, to that brew's `uid`:

```json
"result": {"trend": {"patterns": [{"title": "…", "brewIds": [3, 7]}]}, "localWarnings": [],
           "brewUids": {"3": "c3f1a2b4-…", "7": "d4a2b3c5-…"}}
```

- A reader finds a finding's brews through `brewUids` and the brews' own uids. It never reads a
  number from a file as one of its own row ids. A number with no entry, or whose brew is not on
  the device, opens nothing.
- A writer writes `brewUids` with every trend result. A result stored before the key existed gets
  it on the way out, filled in from the writer's own rows (the numbers are still its row ids),
  but only when `origin` is the writer's own platform. A result the other platform made was
  brought in by 2.0.0 with that install's numbers: it is written with an empty object. A result
  2.0.0 brought in from another install of the same platform cannot be told apart from one made
  on the device; this one case is accepted, and no build after 2.0.0 can add to it.
- A reader that meets a trend result with no `brewUids` (a 2.0.0 file) stores it with an empty
  one: none of its numbers can be followed here.
- The numbers themselves are never rewritten. They are what the model said, and its text may
  mention them.

## photos[]

`uid`, `brewUid` | `beanUid` (one of them), `kind` "bed"|"cup"|"bag"|"other", `takenAt`,
`createdAt`, `caption`, `sendToAi`, `widthPx`, `heightPx`, `mediaType` "image/jpeg", `data`
(base64, no line breaks; null when the backup was saved without photos).

Images are already downscaled (long edge ≤ 1024 px) and carry no EXIF. A reader does not rely on
that: Android walks each incoming JPEG and drops EXIF, XMP, IPTC, comments, other vendor blocks
and anything after the end of the image before storing it, and refuses data that is not a JPEG.
The web keeps the bytes exactly when they are a JPEG of at most 1024 px with no Exif, XMP or
IPTC segment and under 4 MB; anything else is decoded and written again as such a JPEG, and
left out (and said so) when it is not an image the browser can open.

A backup saved without photos still lists them, with `data: null`; a reader leaves those out and
says so. A photo whose brew or coffee is gone is not written. The web writer also writes
`data: null` for the oldest photos when all of them would push the file over the limit below
(the newest that fit keep their image, and the save says how many were left out).

## Importing

**Add to this device** (default): a record whose `uid` already exists is replaced only if the
incoming `updatedAt` is strictly newer (records without `updatedAt` never replace); new uids are
added; recipes by `id` the same way. Analyses and photos have no `updatedAt`, so they are only
ever added or kept.

**Replace everything**: delete beans, grinders, brews, analyses, photos and the user's recipes on
this device, then add.

Settings and the `ai` block are applied only when the person ticks "Also bring settings" / "Also
bring AI provider and model". Importing the same file twice changes nothing the second time.

Details both apps follow, so the same file gives the same result everywhere:

- A `uid` that appears twice in one file is taken once, from its newest copy.
- A brew analysis whose brew is neither in the file nor on the device is left out; so is a photo
  whose brew or coffee is missing.
- A row with no `updatedAt` in the file is stored with a stand-in: a brew's `startedAt`, a bean's
  `createdAt` (or the file's `exportedAt`), a grinder's `exportedAt`.
- At most one grinder is the default. When adding, the default already on the device stays.
- Whoever brings data in has used the app before: setup is marked done. (Android also never
  turns setup back on because a file says `"onboardingDone": false`.)
- The import is all or nothing: a failure leaves the device as it was.

## Older files

Readers also accept the 1.x export (`"schema": "coffee-brew-log/1"`, written by "Save JSON export"
in every Android build up to 1.0, database versions 1–3).

- It has no uids. Derive `uid = "legacy-" + first 16 hex of SHA-256("<exportedAt>|<type>|<id>")`
  over UTF-8, with `exportedAt` **exactly as written in the file** (e.g. `…07.123456Z`) and
  `type` one of `bean`, `grinder`, `brew`, so re-importing it is a no-op on either platform.
  Fixed vector: `SHA-256("2026-11-17T09:41:07.123456Z|bean|1")` → `legacy-bcf423451d822f64`.
- Resolve `recipeId` by exact recipe name + author against the built-ins (else ""), the grinder
  by `"<brand> <model>"` == the brew's `grinder` label (the first one when two share a name),
  `beanUid` from `beanId`.
- Map its labels with the tables below. Readers also accept the Kotlin enum names.
- Fields an older build didn't write are missing: use defaults. Grinder fields that were never
  exported take the database defaults: `min` 0, `max` 40, `step` 1, `dialFormat` "".
- Accept `NaN` / `Infinity` / `-Infinity` (non-standard numbers 1.x could write) as null. Only
  as bare numbers: the same words inside a string are text.
- After a 1.x import, link the new rows to the catalog as at first start: ids only, never a
  number, and a `grinder-scale:<uid>` notice when a linked grinder's scale differs from a
  well-sourced catalog entry. A v2 backup is not linked: a row saved there without a catalog id
  was left that way on purpose.

### 1.x label tables

| Field | 1.x label → v2 id |
| --- | --- |
| bed shape | "Not recorded" → null, "Flat and even" → flat, "Domed in the middle" → domed, "Cratered / dug out" → crater, "High and dry ring" → high-and-dry, "Sloped to one side" → sloped, "Sinkholes / pits" → sinkhole, "Muddy and compacted" → muddy |
| sludge (`bed.surface`) | "Clean" → none, "A little silt" → light, "Thick muddy cap" → heavy |
| channelling | "None visible" → none, "Maybe" → suspected, "Obvious holes" → obvious |
| flow | "Stalled / never finished" → stalled, "Slower than target" → slow, "On target" → on-target, "Faster than target" → fast, "Fell straight through" → gusher |
| burr | "Conical" → conical, "Flat" → flat, "Ghost burr" → ghost, "Blade" → blade, "Unknown" → null |
| setting style | "Clicks" → clicks, "Numbered dial" → numbers, "Microns" → microns, "Stepless / degrees" → stepless |
| process | "Washed" → washed, "Natural" → natural, "Honey" → honey, "Anaerobic" → anaerobic, "Carbonic maceration" → carbonic-maceration, "Wet-hulled" → wet-hulled, "Other" → unknown, "Unknown" → "" |
| roast level | "Light" → light, "Medium-light" → medium-light, "Medium" → medium, "Medium-dark" → medium-dark, "Dark" → dark, "Unknown" → "" |
| brewer | "Hario V60" / "V60" → hario-v60-02, "Chemex" → chemex, "Kalita Wave" / "Kalita" → kalita-wave-185, "Hario Switch" / "Switch" → hario-switch, "AeroPress" → aeropress, "French Press" → french-press, "Moka Pot" / "Moka" → moka-pot, "Espresso" → espresso-machine, "Cold Brew" → cold-brew-jar |

The brewer label itself is kept in `brewerLabel` as written.

## Limits

A reader refuses a file over 64 MB ("Too big to import here. Save the backup without photos and
try again."). Android reads photos out of the file one at a time and never holds them all.
