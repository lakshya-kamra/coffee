# Brew log analysis prompt

Use this when you have saved a backup from the app and want Claude Code (or any assistant
that can read a file) to look across your whole history.

1. In the app: Insights › **Save backup (JSON)**. It writes `coffee-backup-<date>.json`
   without photos. (Gear › App › **Back up everything** writes the same file; switch
   "Include photos" off, or the file will be mostly image data.)
2. Put the file in a folder, open Claude Code there, and paste the prompt below.

The file never contains an API key. It does contain everything you logged, your notes
included, so share it only where you would share those.

---

Read the `coffee-backup-*.json` file in this folder. It is my coffee brewing history in the
"coffee-backup" format, version 2. `brews` has one object per brew: the recipe I followed,
the coffee, the grinder setting, what actually happened (times, drawdown, weights), what the
spent bed looked like, and how it tasted on 0–10 axes. `beans` and `grinders` describe what
the brews point at through `beanUid` and `grinderUid`.

Do not diagnose brews one at a time. I want the patterns:

1. **What am I actually controlling?** Which variables do I change between brews, and which
   have I never moved? Name the one I am treating as fixed that probably should not be.
2. **What tracks with my low scores?** Correlate `overall` against grind setting, dose,
   ratio, total time, drawdown, days off roast and bed shape. Say which relationships are
   real and which are too thin to call — I would rather hear "only four data points" than
   a confident story built on noise.
3. **Bed shape vs taste.** Do my `crater`, `high-and-dry` and `sinkhole` brews score
   differently from my `flat` ones? That would point at pour technique rather than grind.
4. **Per-coffee.** Group by `beanUid`. Am I brewing light roasts and dark roasts, or washed
   and natural coffees, the same way? Is there a coffee I keep failing at?
5. **One experiment.** Give me a three-brew experiment that would resolve the biggest open
   question, with the exact settings for each brew, in my grinder's own units, and what I
   should expect to taste.

Where the data cannot answer something, say so rather than filling the gap.

---

## Schema notes

The full format is in [`shared/schema/backup-v2.md`](../shared/schema/backup-v2.md). What
matters for reading it:

**Brews** (`brews[]`, oldest first)

- `startedAt` is a UTC instant. `recipeName`, `recipeAuthor`, `brewerLabel`, `beanLabel` and
  `grinderLabel` are snapshots taken at brew time, so they stay true after I edit or delete
  gear. `recipeId` and `brewerId` are ids in `shared/recipes.json` and
  `shared/catalog/brewers.json`.
- `doseG`, `waterG`, `beverageG` are grams. `tempC` is always °C, whatever the app displays.
- `totalTimeSec` is the whole brew. `drawdownSec` is measured from the last pour finishing to
  the bed being exposed, and is null when I did not tap the marker.
- `taste` axes are 0–10. `bitterness` is more-is-more, not better-is-more.
- `overall` of `0` means I did not rate it: exclude those rather than treating them as zero.
- `extractionYield` and `tdsPercent` are present only when I entered a refractometer reading.
- `daysOffRoast` is computed at brew time, so it stays correct in history.
- `flow`: `stalled`, `slow`, `on-target`, `fast`, `gusher`, or null (not recorded).
- `bed.shape`: `flat`, `domed`, `crater`, `high-and-dry`, `sloped`, `sinkhole`, `muddy`, or
  null. `bed.surface` (silt on top): `none`, `light`, `heavy`. `bed.channelling`: `none`,
  `suspected`, `obvious`. `bed.notes` is free text.
- `descriptors` and `faults` are the labels I ticked. `notes` and `nextTime` are mine.

**Grind**

- `grindClicks` is the total number of clicks from zero on a click grinder. `grindValue` is
  the dial number on a numbered or stepless grinder. Only one of them is set. `grindSetting`
  is the same thing as text, the way the app showed it.
- Look the brew's `grinderUid` up in `grinders[]` before reading either number:
  `setting.lowerIsFiner` says which way is finer, `setting.clicksPerRotation` how many clicks
  make one turn, `setting.micronsPerStep` (when known) how far the burrs move per click (burr
  travel, so clicks times it is the distance from zero, never the size of the grounds), and
  `setting.dialFormat` how the dial is read (`number-tick:10` means 75 clicks reads "7.5").
- A grinder saved by app version 1.x may say 10 clicks per rotation for a 1Zpresso K-Ultra.
  The real grinder has 100; the click counts logged with it are still correct totals.
- A filter brew logged at 0 clicks, or finer than espresso, is almost certainly a typo. Say
  so and leave it out instead of explaining it.

**Coffees** (`beans[]`)

- `processId`, `roastLevelId`, `lotTypeId`, `speciesId`, `varietalIds`, `originId` and
  `roasterId` are ids from `shared/catalog/beans.json`, `origins.json` and `roasters.json`
  (for example `washed`, `natural`, `anaerobic`, `medium-light`, `single-estate`). `""` or
  null means I did not record it. `roaster`, `origin`, `region`, `farm`, `producer` and
  `varietal` are the words as I typed them.
- `roastedOn` and `openedOn` are calendar dates. `altitudeM` is text such as "1700-1900".

**Other tables**

- `recipes[]` holds only recipes I wrote or edited. Built-in recipes are not in the file;
  their ids are in `shared/recipes.json`.
- `analyses[]` are answers an AI model already gave inside the app (`rawText`, and `result`
  when it parsed). Treat them as someone else's earlier opinion, not as data.
- `photos[]` lists photos; `data` is null in a backup saved without them.

**Older files.** A file whose first lines say `"schema": "coffee-brew-log/1"` is the 1.x
export. Its brews use `recipe`, `flowFeel`, `bedShape`, `sludge` and `channeling` with
sentence labels ("Flat and even", "Cratered / dug out", "High and dry ring", "Sinkholes /
pits", "Not recorded"…) instead of the ids above, and it may contain the bare words `NaN` or
`Infinity` where a number was missing. Restoring it in the app and saving a new backup gives
the format described here.
