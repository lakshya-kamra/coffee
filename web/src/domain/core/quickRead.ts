/**
 * Port of BrewReport.quickRead: the offline first read under Review. The lines are Kotlin's,
 * so both apps say the same thing about the same brew, except that times here are always m:ss
 * (Kotlin still says "160s") and a single day is "1 day".
 */
import { kotlinDouble, clock, plural } from "./format";
import { BrewMath, type BedShape, type Channeling, type TasteProfile } from "./evaluation";
import { totalSec, type Recipe } from "./recipe";

/** The slice of a brew row quickRead looks at. */
export interface QuickReadInput {
  readonly taste: TasteProfile;
  readonly faults: readonly string[];
  readonly bedShape: BedShape | null;
  readonly channeling: Channeling | null;
  readonly totalTimeSec: number;
  readonly daysOffRoast: number | null;
  readonly extractionYield: number | null;
}

export function quickRead(log: QuickReadInput, recipe: Recipe | null): string[] {
  const out: string[] = [];
  const t = log.taste;
  const sour = log.faults.includes("Sour");
  const bitter = log.faults.includes("Bitter");
  const astringent = log.faults.includes("Astringent") || log.faults.includes("Drying");
  const hollow = log.faults.includes("Hollow") || log.faults.includes("Thin");

  // Extraction direction
  if (sour || (t.acidity >= 7 && t.sweetness <= 4 && t.bitterness <= 3) || hollow) {
    out.push(
      "Reads under-extracted: sharp up front, not much sweetness behind it. " +
        "One change at a time — grind finer first, then hotter water, then more contact.",
    );
  }
  if (bitter && astringent) {
    out.push("Bitter plus drying is the over-extraction signature. Go a step coarser, or cut the contact time before you touch anything else.");
  } else if (bitter && !astringent && t.sweetness >= 6) {
    out.push("Bitter but still sweet is usually roast character, not a brewing fault. Worth trying a lower temperature before you re-dial the grind.");
  }

  // Bed
  switch (log.bedShape) {
    case "high-and-dry":
      out.push("Grounds stranded up the wall never brewed at all — that is dose you paid for and did not extract. Swirl right after the bloom, or pour more centrally.");
      break;
    case "crater":
      out.push("A cratered bed means the stream dug into one spot. Pour from lower, slower, and keep the circles small.");
      break;
    case "sinkhole":
      out.push("Pits in the bed are channelling. Water found a shortcut, so part of the bed is over-extracted and part never got touched.");
      break;
    case "muddy":
      out.push("A compacted, muddy bed points at fines migration choking the filter. Coarser grind, or less agitation late in the brew.");
      break;
    case "sloped":
      out.push("A sloped bed usually means the brewer was not level, or one side got most of the water.");
      break;
    case "flat":
      out.push("Flat bed. Whatever you did not like about this cup, distribution was not it.");
      break;
    default:
      break;
  }

  if (log.channeling === "obvious") {
    out.push("You called obvious channelling — fix that before reading anything else into the taste. Channelled brews taste under and over extracted at the same time.");
  }

  // Drawdown against the recipe
  const target = recipe ? totalSec(recipe) : null;
  const actual = log.totalTimeSec;
  if (target != null && target > 0 && actual > 0) {
    const delta = actual - target;
    if (delta > 60) {
      out.push(`You ran ${clock(delta)} past the recipe's ${clock(target)}. That is a lot of extra contact — grind coarser if the cup came out heavy.`);
    } else if (delta < -45) {
      out.push(`You finished ${clock(-delta)} early against ${clock(target)}. A fast drawdown with a thin cup means go finer.`);
    }
  }

  // Freshness
  const d = log.daysOffRoast;
  if (d != null) {
    if (d <= 3) {
      out.push(`Only ${plural(d, "day")} off roast. Expect an aggressive bloom and a faster-than-usual drawdown from all that CO2. Not a fault.`);
    } else if (d >= 35) {
      out.push(`${d} days off roast. Flat and papery at this age is the beans, not your technique.`);
    }
  }

  if (log.extractionYield != null) {
    const ey = kotlinDouble(BrewMath.round1(log.extractionYield));
    out.push(`Measured extraction yield ${ey}%. For filter, most people land somewhere between 18 and 22 — but taste beats the number.`);
  }

  if (out.length === 0) {
    out.push("Nothing obviously wrong in what you logged. If the cup still is not right, the next useful move is changing one variable and brewing it again.");
  }
  return out;
}
