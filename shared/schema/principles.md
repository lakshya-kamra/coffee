# principles.json

Short statements of what named coffee people teach, each with a link to where they say it. Both
apps send a few of them with an AI analysis (the `PRINCIPLES` block of the prompt), so the model
reasons from sourced teaching and can name whose idea it is leaning on. They are background for
the model, not rules the apps apply themselves.

```json
{
  "version": 1,
  "updated": "2026-11-18",
  "principles": [
    {
      "id": "kasuya-46-first-pours-set-balance",
      "topic": "recipe",
      "appliesTo": ["pourover"],
      "text": "In Kasuya's 4:6 method the first 40% of the water ...",
      "who": "Tetsu Kasuya",
      "url": "https://kurasu.kyoto/blogs/kurasu-journal/2016-world-brewers-cup-champion-tetsu-kasuya",
      "note": "Kurasu's transcript of his 2016 World Brewers Cup routine ..."
    }
  ]
}
```

## Fields

| field | type | rule |
|---|---|---|
| `id` | string | Unique, stable, lowercase kebab (`^[a-z0-9]+(-[a-z0-9]+)*$`). Starts with the person's surname. |
| `topic` | string | One of `extraction`, `grind`, `agitation`, `flow`, `bed`, `temperature`, `ratio`, `freshness`, `water`, `espresso`, `recipe`. |
| `appliesTo` | string[] | Brew method ids (`espresso`, `moka`, `aeropress`, `pourover`, `frenchPress`, `coldBrew`, `siphon`, `turkish`), the same ids as `method` in `catalog/brewers.json`. Empty means every method. |
| `text` | string | At most 240 characters. A plain statement of what the person teaches, in our words, naming them. No number that is not in the source. |
| `who` | string | The person. An organisation goes in brackets when the page is theirs, e.g. `Matt Perger (Barista Hustle)`. |
| `url` | string | https link to the person's own page, video or shop page, or to the competition organiser or brand that published their words. |
| `note` | string | Where in the source it is. If the wording was checked through someone else's write-up (an aggregator) or could not be opened directly, the note says so and names it. |

## Rules for editing

- Every entry needs a source that was read. An idea "everyone knows" someone says, with no page to
  point at, stays out. As of 2026-11-18 that is why there is nothing from Morgan Eckroth (their
  brewing advice is in videos we could not read, with no written version) and only two AeroPress
  entries from Lance Hedrick, both checked against an aggregator's summary and marked as such.
- Coverage is uneven for the same reason: most entries are about pour-over, espresso has one
  (Gagné, about a flow-controlled machine, marked as tentative in its note), French press and
  siphon get the Coffee Compass entry, and moka, cold brew and Turkish only get the entries that
  apply to every method (rest and water).
- The Barista Hustle entry is marked in its note: the site refuses automated readers, so its
  wording was checked through a search engine's reading of the page.
- Do not put a recipe here; recipes live in `recipes.json`. A principle says why or which way,
  e.g. which way to move the grind when a brew runs long.
- Order matters. The apps send at most 10 per analysis. Android takes the recipe author's own
  entries first, then one entry per topic in the order the brew's evidence suggests, then a second
  per topic, and so on; within a topic, file order decides. Keep the most broadly useful entry of
  each topic first.
- Changing or removing an entry never marks a stored analysis as out of date: the principles are
  not part of the hash of the brew's data.

## Who is in the file

James Hoffmann, Tetsu Kasuya, Scott Rao, Jonathan Gagné (Coffee ad Astra), Tim Wendelboe,
Lance Hedrick, Matt Perger (Barista Hustle).
