# shared/glossary.json

Plain explanations of the words the apps use (v4 §4.5), read by the Android app
(`Catalog.glossary`, `Glossary`) and the web app (`catalog.glossary`, `domain/core/glossary.ts`).
The screens show a term's `short` in a small popover or sheet where the word appears: step kinds,
RATIO, GRIND, the Review sections, "Published numbers", "Bed exposed", "Days off roast".

```json
{
  "version": 1,
  "updated": "2026-11-19",
  "terms": [
    {
      "id": "drawdown",
      "term": "Drawdown",
      "short": "The time for the last of the water to drain through the coffee. …",
      "more": "",
      "aliases": ["draw-down", "drain"],
      "sources": [{ "who": "Tim Wendelboe", "url": "https://…", "note": "…" }]
    }
  ]
}
```

## Fields

| field | type | notes |
|---|---|---|
| `id` | string | lowercase-kebab, stable. Screens look terms up by id. |
| `term` | string | the word as shown ("Days off roast") |
| `short` | string | one or two plain sentences, no jargon a newcomer would need explained in turn |
| `more` | string | optional longer text; `""` or omitted when there is none |
| `aliases` | string[] | other spellings and names; `Glossary.find` / `findTerm` match them case-insensitively |
| `sources` | array | `{who, url, note}`, each a page someone read; `url` is https. Empty when the term is ours (`clicks`) or plain vocabulary (`dose`, `immersion`). |

## Rules

- Copy stays plain and correct. A claim that is more than a definition cites a source that says
  it, reusing `principles.json` and `recipes.json` sources where they fit. Never an invented URL.
- Step kinds map to terms in code (`termForStepKind`): Bloom, Pour, Swirl, Stir, Steep, Press and
  Drawdown each have one; Prep and Serve need none.
- `published`, `adapted` and `community` repeat the fidelity meanings in `recipes.md` in plain
  words; change them together.

## History

- 2026-11-19: created with 27 terms (v4).
- 2026-11-20: every source URL checked. 14 of the 18 source entries (9 distinct pages) were read and
  say what the note claims; the other four changed. Barista
  Hustle refuses automated readers, so `steep`, `extraction` and `grind` now cite the Coffee
  Compass post by its permalink (`/blog/the-coffee-compass/`, confirmed through an archived copy)
  instead of `?p=2185`. `press` cites AeroPrecipe's written summary of Hedrick's video, which says
  to press gently, instead of the video page, which has no text to check.
