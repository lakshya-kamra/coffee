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

## Rules for changing it

- The 14 ids that shipped in 1.x come first, in their 1.x order (`ExpertRecipes.LEGACY_IDS`), then
  the rest grouped by author.
- An existing id keeps its numbers. Only `sources`, `fidelity`, `sourceNote` and wording may
  change, except when an audit proves a `published` recipe misquotes its author: then fix the
  numbers and say so in `sourceNote` ("Corrected 2026-11 to match …"). Adapted recipes never
  change numbers; publish a corrected version under a new id.
- A new recipe needs complete numbers from a cited source and at least one `sources` entry with
  a URL.
- `RecipeIdsTest` (Android) checks ids, order, contiguous steps, pour targets and golden numbers.

## History

- 2026-11-17: created from the 1.x `ExpertRecipes.kt`, then merged with the expert-recipe
  research (`docs/plan/recipes-research.json`): `kasuya-46` and `hoffmann-aeropress` corrected to
  their authors' published numbers; corrected versions of five adapted recipes added as
  `hedrick-aeropress-very-good`, `rao-v60-spin`, `gagne-v60-adastra`, `wendelboe-pourover-chemex`
  and `onyx-kalita-monarch`; 25 new recipes added.
