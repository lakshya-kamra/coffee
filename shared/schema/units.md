# Units and conversions

Both apps store grams and °C and nothing else. Everything below is rendering, never saved
(v4 decision D10), and both apps give the same text for the same numbers
(`fixtures/domain-v4-cases.json`).

## No-scale brewing (`hasScale = false`)

| Constant | Value | Where |
|---|---|---|
| `GROUND_COFFEE_G_PER_LEVEL_TBSP` | 5 | `NoScale.kt`, `domain/core/noScale.ts` |
| `ML_PER_MUG` | 250 | same |

- Coffee: level tablespoons = dose ÷ 5, rounded to the nearest half, at least 1: "6 level
  tablespoons", "4½ level tablespoons", "1 level tablespoon". The line reads "… of coffee", or
  "… of beans" for whole beans measured before a blade grinder.
- Espresso and moka get no spoons: "Fill the basket level, then tamp." / "Fill the basket level;
  don't press it down. Water to just below the valve."
- Water: ml = grams (1 g of water is 1 ml), rounded to whole ml. From 200 ml a mug count is added at
  250 ml a mug, rounded to the nearest half, at least 1: "500 ml (2 mugs)", "360 ml (1½ mugs)".
- Servings presets "1 mug", "2 mugs", "3 mugs" scale the recipe to 250, 500 or 750 g of water and
  take the dose from the recipe's ratio.

### Where 5 g comes from

A level tablespoon of ground coffee weighs about 5 g. Two sources, read on 2026-11-19, agree:

- Allpress Espresso's Moccamaster recipe gives "70 grams (approx. 14 tbsp)" of ground coffee:
  5 g a tablespoon. https://allpress.com/coffee-guides/how-to-brew-moccamaster
- Moccamaster gives both "55 - 60 grams of ground coffee per liter" and "two tablespoons of
  coffee for every 6 oz of water". 6 US fl oz is 177 ml, so two tablespoons are 9.7-10.6 g:
  about 5 g each.
  https://us.moccamaster.com/blogs/blog/how-to-make-a-perfect-cup-of-coffee-with-a-moccamaster

The SCA's "2 tablespoons ≈ 10 g" scoop reference says the same, but the SCA page that states it
could not be opened (the standards are behind a members' login), so it is not cited here.

A tablespoon of whole beans weighs less than one of ground coffee; we use the same 5 g and the
same rounding for both, so treat spoons as a starting point and adjust by taste.

## Temperature

- °F = °C × 9 / 5 + 32, rounded to a whole degree.
- Fresh installs take °F when the locale's region is US, PR, GU, VI, AS, MP, UM, LR, BZ, BS, KY, PW,
  FM or MH, else °C. The region is the two letters after the first `-` or `_` in the language tag
  (`en-US` → US). A unit someone already has never changes.
- Recipe prose is written "70 °C" or "90-95 °C"; °F users read "158 °F" or "194-203 °F". The
  pattern both apps use is `proseTempPattern` in `fixtures/domain-v4-cases.json`.

## Durations

- Under an hour: "3:30". From an hour: "12 h 5 min", or "12 h" on the hour (cold brew).

## Dates and times on screen

Screens use the runtime locale: Today's eyebrow reads "EVENING · THU, NOV 19" in en-US, a brew time
"7:05 PM" in en-US and "19:05" in en-GB. Exports, backups and AI prompts keep their pinned formats.
