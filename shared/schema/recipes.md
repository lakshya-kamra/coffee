# shared/recipes.json

The built-in recipes, read by the Android app (`Catalog.recipes`, via `ExpertRecipes`) and the
web app. One file, so both apps always show the same numbers.

```json
{
  "version": 1,
  "updated": "2026-11-17",
  "recipes": [
    {
      "id": "hoffmann-v60",
      "name": "The Ultimate V60 Technique",
      "author": "James Hoffmann",
      "brewer": "hario-v60-02",
      "doseG": 30.0,
      "waterG": 500.0,
      "grind": "Medium-fine",
      "tempC": 100,
      "blurb": "…",
      "sourceNote": "…",
      "fidelity": "published",
      "sources": [{ "who": "James Hoffmann", "url": "https://…", "note": "…" }],
      "prep": ["…"],
      "tips": ["…"],
      "steps": [
        { "kind": "Bloom", "title": "Bloom to 60 g", "detail": "…", "startSec": 0, "endSec": 45, "targetWaterG": 60 }
      ],
      "accent": "#E9A855"
    }
  ]
}
```

## Fields

| field | type | notes |
|---|---|---|
| `id` | string | lowercase-kebab, stable forever. Brew logs point at it. Never starts with `custom-` (user recipes). |
| `name`, `author` | string | shown as written |
| `brewer` | string | a `shared/catalog/brewers.json` id. Readers also accept the 1.x enum names (`V60`, `Kalita`…). |
| `doseG`, `waterG` | number | grams |
| `grind` | string | words; grind numbers belong to a grinder, not a recipe |
| `tempC` | integer | °C |
| `blurb` | string | one or two sentences |
| `sourceNote` | string | where the numbers come from and how far to trust them. Shown in the UI. |
| `fidelity` | string | `published` (every number traced to a cited source), `adapted` (some numbers are ours, or the sources disagree; the note says which), `community` (a common pattern, nobody's recipe). User recipes use `custom`. |
| `sources` | array | `{who, url, note}`; `url` and `note` may be empty |
| `prep`, `tips` | string[] | |
| `steps[].kind` | string | `Prep`, `Bloom`, `Pour`, `Swirl`, `Stir`, `Steep`, `Press`, `Drawdown`, `Serve` |
| `steps[].startSec`, `endSec` | integer | seconds from the start of the timer; each step starts where the previous one ends |
| `steps[].targetWaterG` | integer or null | CUMULATIVE grams by the end of the step, never decreasing; the largest equals `waterG`. For bypass recipes the last target counts all water used. |
| `accent` | string | `#RRGGBB` (or `#AARRGGBB`). 1.x saved recipes wrote an ARGB number, which readers still accept. |
| `isBuiltIn` | boolean | omitted here (default true); user recipes write false |
| `basedOn` | string | optional: the id a user recipe was duplicated from |
| `level` | string | v4, optional: `easy`, `medium` or `advanced`; omitted or `""` = not said (user recipes). Every built-in has one. Judged from the steps: one pour and a steep is easy; several timed pours, medium; competition recipes, two water temperatures or espresso, advanced. |
| `needs` | string[] | v4, optional: kit the recipe really depends on, a subset of `scale` (several weighed pours or a weighed yield; one pour of all the water does not count), `gooseneck` (controlled pour-over pours), `grinder` (a grind pre-ground coffee does not offer), `thermometer` (a stated temperature of 93 °C or less, or two temperatures). Readers drop words they don't know. |
| `prepNoScale` | string[] | v4, optional: prep lines for someone without a scale. They replace the `prep` lines that need one (they mention a scale, zeroing, taring or weighing, or give an amount in grams), where the first of those stood; a prep with no such line gets them first. Without it, the basket line (espresso, moka) or two generic lines ("Measure {water} of water in a jug first.", "Measure {coffee}.") stand in. `{coffee}` becomes e.g. "6 level tablespoons of coffee" and `{water}` "500 ml (2 mugs)" (see `schema/units.md`). Every `easy` recipe whose prep mentions a scale has one, and so does every recipe whose prep gives grams of something that is not the brew water (ice, a bypass, a dose per roast), because the apps would otherwise drop that line with nothing in its place. |
| `video` | object | optional, built-ins only: the expert's own YouTube video that teaches this recipe, `{youtubeId, title, channel}`. `youtubeId` is the 11-character id (`[A-Za-z0-9_-]{11}`); `title` and `channel` are YouTube's own (oEmbed `title` and `author_name`). Only added when the video is the recipe's cited source, is embedded in its cited page, or is the named person (or the channel that filmed them) teaching this same recipe; otherwise left out. The web shows its cover from `i.ytimg.com` and links to the video; Android ignores it. Never written into saved recipes or backups. |

## Rules for changing it

- The 14 ids that shipped in 1.x come first, in their 1.x order (`ExpertRecipes.LEGACY_IDS`), then
  the rest grouped by author.
- An existing id keeps its numbers. Only `sources`, `fidelity`, `sourceNote` and wording may
  change, except when an audit proves a `published` recipe misquotes its author: then fix the
  numbers and say so in `sourceNote` ("Corrected 2026-11 to match …"). Adapted recipes never
  change numbers; publish a corrected version under a new id.
- A new recipe needs complete numbers from a cited source and at least one `sources` entry with
  a URL.
- Without a scale, step titles and details read their grams as ml (water) or level tablespoons
  ("16 g of coffee"), so step prose says amounts as amounts in the cup: "until there is 150 g in
  the cup", not "until the cup weighs 150 g" (which would read "weighs 150 ml"). `tips`, `blurb`
  and `sourceNote` stay in grams for everyone: they describe the recipe as its author wrote it,
  and a brew never asks anyone to act on them.
- Temperatures in prose are written "70 °C" or "90-95 °C" (a space, then °C). Both apps convert
  that form to °F for people who use °F, with the pattern in `fixtures/domain-v4-cases.json`.
- `RecipeIdsTest` (Android) checks ids, order, contiguous steps, pour targets, levels, needs,
  prose temperatures and golden numbers; the web's recipe tests check the same.

## History

- 2026-11-17: created from the 1.x `ExpertRecipes.kt`, then merged with the expert-recipe
  research (`docs/plan/recipes-research.json`): `kasuya-46` and `hoffmann-aeropress` corrected to
  their authors' published numbers; corrected versions of five adapted recipes added as
  `hedrick-aeropress-very-good`, `rao-v60-spin`, `gagne-v60-adastra`, `wendelboe-pourover-chemex`
  and `onyx-kalita-monarch`; 25 new recipes added.
- 2026-11-19 (v4): `level` and `needs` for every built-in; `prepNoScale` for the easy recipes that
  weigh (Hoffmann's and Onyx's French press, Hoffmann's and Wendelboe's AeroPress). Prose
  temperatures normalised to "N °C" (Onyx's "200 F (93 C)" now reads "93 °C"). Hoffmann's French
  press "Skim" step is kind `Stir` (was `Serve`; title unchanged). Six recipes added for brewers
  that had none, each from a page read on 2026-11-19: `3fe-classic-double` (3FE espresso guide),
  `allpress-drip-machine` (Allpress Moccamaster recipe), `copper-cow-phin` (Copper Cow phin guide),
  `tarykina-cezve` (Tetiana Tarykina via Fresh Cup), `hario-syphon` (Hario UK syphon guide) and
  `blue-tokai-south-indian-filter` (Blue Tokai). All but the 3FE double are `adapted`: each
  `sourceNote` names the numbers that are ours (a pour or drain time the source leaves out, a
  temperature from the middle of a range). No drip-bag recipe: no maker's page with a dose was
  found.
- 2026-11-20: the four built-ins without sources now cite what they are or are not based on, and
  their `sourceNote` says plainly whose numbers they are. `hedrick-aeropress` and `gagne-v60` are
  our own recipes that 1.x credited to Hedrick and Gagné (their real recipes are
  `hedrick-aeropress-very-good` and `gagne-v60-adastra`); `hedrick-espresso` is our starting
  point after Hedrick's light-roast advice on the Flair blog; `switch-hybrid` cites two pages that
  brew the open-close-open pattern. No numbers changed. `name` and `author` stay as they were
  because both apps match 1.x export rows to built-ins by exact name and author
  (`LegacyExportReader`, `domain/backup/legacy.ts`); renaming needs a legacy alias in both
  readers first.
- 2026-11-20 (v4.1): both legacy readers fall back to `LegacyIds.RECIPE`, the (name, author) pairs
  1.x wrote, so `hedrick-aeropress`, `hedrick-espresso` and `gagne-v60` now carry the author
  "House recipe" (names unchanged) and still resolve from 1.x exports. `hedrick-aeropress`'s
  `sourceNote` now says its timeline follows Hedrick's published recipe. `prepNoScale` added to
  `hoffmann-iced-immersion` (ice in cubes), `wac-2025-pop` (the bypass in ml) and
  `hedrick-aeropress-very-good` (one dose line for every roast); the "cup weighs N g" steps of
  `wac-2022-little`, `wac-2023-wipvasutt` and `wac-2024-stanica` now read "until there is N g in
  the cup". No numbers changed.
