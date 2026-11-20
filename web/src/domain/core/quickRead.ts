/**
 * Port of BrewReport.quickRead, v2 (v4 §4.4): the offline first read under Review. Advice depends
 * on how the brew works (percolation, immersion, espresso) and on whether the grind can change
 * (a dial or a blade grinder can; pre-ground cannot), so it never says "go finer" to someone who
 * bought ground coffee. Kotlin says exactly the same lines (shared/fixtures/domain-v4-cases.json).
 */
import { brewStyle, reviewSections, type Brewer } from "./brewer";
import { formatDuration, kotlinDouble, plural, roundToInt } from "./format";
import { BrewMath, type BedShape, type Channeling, type TasteProfile } from "./evaluation";
import { totalSec, type Recipe } from "./recipe";
import { shotBand } from "./shotTarget";
import { tempLabel } from "./units";

/** The slice of a brew row quickRead looks at. The v4 fields are optional so older callers still fit. */
export interface QuickReadInput {
  readonly taste: TasteProfile;
  readonly faults: readonly string[];
  readonly bedShape: BedShape | null;
  readonly channeling: Channeling | null;
  readonly totalTimeSec: number;
  readonly daysOffRoast: number | null;
  readonly extractionYield: number | null;
  readonly doseG?: number;
  /** "preground" means the grind cannot change. */
  readonly grindKind?: string;
}

export interface QuickReadOptions {
  /** The brewer; defaults to the recipe's. Null or unknown reads as a filter brew. */
  readonly brewer?: Brewer | null;
  /** false: "+1 tbsp" instead of "+2 g". */
  readonly hasScale?: boolean;
  readonly fahrenheit?: boolean;
  /** Data quality already distrusted the total time: no advice about time. */
  readonly timeIgnored?: boolean;
}

/** Time advice needs a time that is plausibly the whole brew. */
function timeUsable(actual: number, target: number): boolean {
  return actual > 0 && target > 0 && actual >= 0.5 * target && actual <= 2.5 * target;
}

export function quickRead(log: QuickReadInput, recipe: Recipe | null, opts: QuickReadOptions = {}): string[] {
  const out: string[] = [];
  const t = log.taste;
  const brewer = opts.brewer !== undefined ? opts.brewer : (recipe?.brewer ?? null);
  const style = brewStyle(brewer);
  const espresso = style === "espresso";
  const adjustable = log.grindKind !== "preground";
  const hasScale = opts.hasScale ?? true;
  const units = { fahrenheit: opts.fahrenheit ?? false, ounces: false };
  const dose = (log.doseG ?? 0) > 0 ? log.doseG! : (recipe?.doseG ?? 0);

  const sour = log.faults.includes("Sour");
  const bitter = log.faults.includes("Bitter");
  const weak = log.faults.includes("Too weak");
  const strong = log.faults.includes("Too strong");
  const hollow = log.faults.includes("Hollow") || log.faults.includes("Thin");
  const under = sour || weak || hollow || (t.acidity >= 7 && t.sweetness <= 4 && t.bitterness <= 3);

  // Extraction direction, by method and by whether the grind can change.
  if (under) {
    if (espresso) {
      const aim = dose > 0 ? `aim for about ${roundToInt(dose * 2.5)} g` : "";
      if (adjustable) out.push(aim ? `Let more out: ${aim}, or grind finer.` : "Let more out, or grind finer.");
      else out.push(aim ? `Let more out: ${aim}.` : "Let more out.");
    } else if (style === "immersion") {
      out.push(adjustable ? "Steep 30 seconds longer, or grind a little finer." : "Steep 1 minute longer.");
    } else {
      out.push(adjustable ? "Grind a little finer next time." : "Pour more slowly, and use water just off the boil.");
    }
  }
  if (weak) {
    out.push(espresso ? "Use a little more coffee: +1 g." : `Or use a little more coffee: ${hasScale ? "+2 g" : "+1 tbsp"}.`);
  }
  if (bitter) {
    if (espresso) out.push(adjustable ? "Stop the shot sooner, or grind coarser." : "Stop the shot sooner.");
    else if (style === "immersion") out.push(adjustable ? "Steep 30 seconds less, or grind a little coarser." : "Steep 30 seconds less.");
    else out.push(adjustable ? "Grind a little coarser next time." : `Use water a little cooler, about ${tempLabel(units, 90)}.`);
  }
  if (strong) {
    out.push(espresso ? "Add hot water for an Americano, or let a little more out." : "Use a little less coffee, or add a splash of hot water to the cup.");
  }

  // The bed, only where there is one to look at.
  if (reviewSections(brewer).bed) {
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
  }

  // Time against the recipe: drawdown, steep or shot time.
  // Espresso is judged against shot mode's target, never the total with "Serve" in it (A4).
  const band = espresso && recipe ? shotBand(recipe) : null;
  const target = band ? band.to : recipe ? totalSec(recipe) : 0;
  const actual = log.totalTimeSec;
  if (!opts.timeIgnored && timeUsable(actual, target)) {
    const delta = actual - target;
    if (band) {
      const aim = band.from === band.to ? formatDuration(band.to) : band.label;
      if (delta > 5) {
        out.push(`Your shot ran ${formatDuration(actual)}, longer than the recipe's ${aim}. ` +
          (adjustable ? "If it tasted bitter, grind a little coarser." : "If it tasted bitter, stop it sooner."));
      } else if (actual - band.from < -5) {
        out.push(`Your shot ran ${formatDuration(actual)}, quicker than the recipe's ${aim}. ` +
          (adjustable ? "If it tasted sour, grind a little finer." : "If it tasted sour, let a little more out."));
      }
    } else if (style === "immersion") {
      if (delta > 60) {
        out.push(`You steeped ${formatDuration(delta)} longer than the recipe's ${formatDuration(target)}. If it tasted heavy or bitter, stick to the recipe's time.`);
      } else if (delta < -45) {
        out.push(`You stopped ${formatDuration(-delta)} before the recipe's ${formatDuration(target)}. If it tasted thin or sour, give it the full time.`);
      }
    } else if (delta > 60) {
      out.push(`You ran ${formatDuration(delta)} past the recipe's ${formatDuration(target)}. ` +
        (adjustable
          ? "That is a lot of extra contact — grind coarser if the cup came out heavy."
          : "Pre-ground coffee often drains slowly; if the cup came out heavy, use a little less coffee next time."));
    } else if (delta < -45) {
      out.push(`You finished ${formatDuration(-delta)} early against ${formatDuration(target)}. ` +
        (adjustable ? "A fast drawdown with a thin cup means go finer." : "If the cup was thin, pour more slowly next time."));
    }
  }

  // Freshness
  const d = log.daysOffRoast;
  if (d != null) {
    if (d <= 3) {
      // Only a percolation brew has a drawdown to speed up.
      const expect = espresso
        ? "a lot of crema"
        : style === "immersion"
          ? "a lively bloom"
          : "an aggressive bloom and a faster-than-usual drawdown";
      out.push(`Only ${plural(d, "day")} off roast. Expect ${expect} from all that CO2. Not a fault.`);
    } else if (d >= 35) {
      out.push(`${d} days off roast. Flat and papery at this age is the beans, not your technique.`);
    }
  }

  if (log.extractionYield != null) {
    const ey = kotlinDouble(BrewMath.round1(log.extractionYield));
    out.push(espresso
      ? `Measured extraction yield ${ey}%. Taste beats the number.`
      : `Measured extraction yield ${ey}%. For filter, most people land somewhere between 18 and 22 — but taste beats the number.`);
  }

  if (out.length === 0) {
    out.push("Nothing obviously wrong in what you logged. If the cup still is not right, the next useful move is changing one variable and brewing it again.");
  }
  return out;
}
