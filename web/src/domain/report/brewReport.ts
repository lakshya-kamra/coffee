/**
 * Port of domain/BrewReport.markdown / trendMarkdown with the A§8.7 additions (new coffee fields,
 * brewer, water, the grinder's scale lines, photo count). This never touches the network: it is
 * text you share or copy, and where it ends up is your choice.
 */
import { catalog, Brewers } from "@/catalog/catalog";
import { brewMethodLabel } from "@/domain/core/brewer";
import { BrewMath, TASTE_AXES, bedShapeLabel, channelingLabel, flowFeelLabel, sludgeLabel } from "@/domain/core/evaluation";
import { clock, grams, oneDp, plural } from "@/domain/core/format";
import { totalSec, type Recipe } from "@/domain/core/recipe";
import { finerDirection, format as fmtGrind, rangeFor, reading, scaleFor, showsDialNumber, talksInTurns, usesClicks } from "@/domain/grind";
import { beanDisplay, type BeanRow, type BrewRow, type GrinderRow } from "@/data/schema";

const DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"] as const;
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"] as const;
const pad2 = (n: number) => (n < 10 ? `0${n}` : String(n));

/** "Tue 17 Nov, 08:14" in local time (Kotlin "EEE d MMM, HH:mm"). */
export function whenLabel(ms: number): string {
  const d = new Date(ms);
  return `${DAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]}, ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

function ratioSuffix(dose: number, water: number): string {
  return dose > 0 && water > 0 ? ` (1:${oneDp(water / dose)})` : "";
}

const BURRS: Record<string, string> = { conical: "Conical", flat: "Flat", ghost: "Ghost", blade: "Blade" };

export interface ReportExtras {
  /** Photos of this brew stored in the app (never attached to the text). */
  photoCount?: number;
}

/** A single brew, framed as a question. This is what Share report sends. */
export function brewMarkdown(log: BrewRow, recipe: Recipe | null, bean: BeanRow | null, grinder: GrinderRow | null, history: readonly BrewRow[] = [], extras: ReportExtras = {}): string {
  const out: string[] = [];
  const line = (s = "") => out.push(s);
  line(`# Brew log — ${whenLabel(log.startedAt)}`);
  line();
  line("I brewed this and I want help working out what to change next time.");
  line("Please read the bed analysis and the taste notes together before answering,");
  line("and give me **one** change to make on the next brew, not five.");
  line();

  const brewer = recipe?.brewer ?? Brewers.byId(log.brewerId) ?? Brewers.resolveOrNull(log.brewer);
  line("## Recipe");
  line(`- Following: **${log.recipeName.trim() || "own recipe"}**` + (log.recipeAuthor.trim() ? ` (${log.recipeAuthor})` : ""));
  line(`- Brewer: ${brewer ? `${brewer.label} (${brewMethodLabel(brewer.method).toLowerCase()})` : log.brewer.trim() || "unspecified"}`);
  line(`- Dose: ${grams(log.doseG)} g`);
  line(`- Water: ${grams(log.waterG)} g${ratioSuffix(log.doseG, log.waterG)}`);
  line(`- Temperature: ${log.tempC} C`);
  const water = log.waterId ? catalog.waterById(log.waterId) : null;
  if (water) {
    const minerals = [water.ghPpm != null ? `GH ${water.ghPpm} ppm` : null, water.khPpm != null ? `KH ${water.khPpm} ppm` : null].filter(Boolean).join(", ");
    line(`- Water: ${water.label}${minerals ? ` (${minerals})` : ""}`);
  }
  if (recipe) line(`- Recipe target time: ${clock(totalSec(recipe))}`);
  line();

  line("## Coffee");
  if (bean) {
    line(`- ${beanDisplay(bean)}`);
    const roaster = catalog.roaster(bean.roasterId);
    if (roaster?.country) line(`- Roaster's country: ${roaster.country}`);
    const origin = bean.origin.trim() || catalog.origin(bean.originId)?.country || "";
    if (origin) line(`- Origin: ${`${origin} ${bean.region}`.trim()}`);
    if (bean.farm.trim()) line(`- Farm: ${bean.farm}`);
    if (bean.producer.trim()) line(`- Producer: ${bean.producer}`);
    if (bean.washingStation.trim()) line(`- Washing station: ${bean.washingStation}`);
    const lot = catalog.label("lotType", bean.lotTypeId);
    if (lot) line(`- Lot: ${lot}`);
    const varietals = [...bean.varietalIds.map((id) => catalog.label("varietal", id) ?? id), ...(bean.varietal.trim() ? [bean.varietal.trim()] : [])];
    if (varietals.length) line(`- Varietal: ${varietals.join(", ")}`);
    line(`- Process: ${catalog.process(bean.processId)?.label ?? "Unknown"}`);
    if (bean.isDecaf) line(`- Decaf: ${catalog.label("decaf", bean.decafMethodId) ?? "yes"}`);
    line(`- Roast level: ${catalog.label("roastLevel", bean.roastLevelId) ?? "Unknown"}`);
    if (bean.altitudeMasl.trim()) line(`- Altitude: ${bean.altitudeMasl} m`);
    if (log.daysOffRoast != null) line(`- Days off roast: ${log.daysOffRoast}`);
    if (bean.tastingNotes.trim()) line(`- Roaster's notes: ${bean.tastingNotes}`);
  } else {
    line(`- ${log.beanLabel.trim() || "not recorded"}`);
  }
  line();

  line("## Grind");
  line(`- Grinder: ${log.grinderLabel.trim() || "not recorded"}`);
  if (grinder) {
    const b = grinder.burrType ? BURRS[grinder.burrType] : "Unknown";
    line(`- Burrs: ${b}${grinder.burrSizeMm.trim() ? ` ${grinder.burrSizeMm} mm` : ""}`);
  }
  const clicks = log.grindClicks;
  const scale = grinder ? scaleFor(grinder) : null;
  if (clicks != null) {
    line(`- Setting used: **${clicks} clicks** out from zero (burrs touching)`);
    if (grinder && scale) {
      const cpr = grinder.clicksPerRotation;
      if (cpr > 0 && talksInTurns(scale.dial)) {
        line(`- Dial: ${cpr} clicks per full turn, so ${clicks} is ${plural(Math.floor(clicks / cpr), "turn")} plus ${clicks % cpr}`);
      } else if (showsDialNumber(scale.dial)) {
        // A numbered dial: 75 total clicks reads 7.5 on the ring, not 7 turns.
        line(`- Dial: ${cpr > 0 ? `${cpr} clicks per full turn; ` : ""}${clicks} reads ${reading(scale, clicks)} on the dial`);
      }
      line(`- Direction: ${finerDirection(scale)}`);
      if (grinder.micronsPerClick != null) line(`- Roughly ${Math.trunc(grinder.micronsPerClick * clicks)} micron burr gap (${oneDp(grinder.micronsPerClick)} per click)`);
    }
  } else if (log.grindValue != null && scale && !usesClicks(scale)) {
    line(`- Setting used: **${fmtGrind(scale, log.grindValue)}${scale.unit ? ` ${scale.unit}` : ""}** on the dial`);
    line(`- Direction: ${finerDirection(scale)}`);
  } else {
    line(`- Setting: ${log.grindSetting.trim() || "not recorded"}`);
  }
  if (scale && brewer) {
    const r = rangeFor(scale, brewer.method);
    if (r) {
      const who = [...new Set(scale.rangeSources.map((s) => s.who).filter(Boolean))];
      line(`- Usual ${brewMethodLabel(brewer.method).toLowerCase()} starting range on this grinder: ${fmtGrind(scale, r.from)}–${fmtGrind(scale, r.to)}${who.length ? ` (${who.join(", ")})` : ""}`);
    }
  }
  if (recipe) line(`- Recipe calls for: ${recipe.grind}`);
  line();

  line("## What happened");
  line(`- Total time: ${clock(log.totalTimeSec)}`);
  if (log.drawdownSec != null) line(`- Drawdown (last pour to exposed bed): ${log.drawdownSec}s`);
  if (log.flowFeel) line(`- Flow: ${flowFeelLabel(log.flowFeel)}`);
  if (log.beverageG != null) line(`- In the cup: ${grams(log.beverageG)} g`);
  if (log.tdsPercent != null) line(`- TDS: ${log.tdsPercent}%`);
  if (log.extractionYield != null) line(`- Extraction yield: ${BrewMath.round1(log.extractionYield)}%`);
  line();

  line("## Bed analysis");
  line(`- Shape: ${bedShapeLabel(log.bedShape)}`);
  if (log.sludge) line(`- Surface: ${sludgeLabel(log.sludge)}`);
  if (log.channeling) line(`- Channelling: ${channelingLabel(log.channeling)}`);
  if (log.bedNotes.trim()) line(`- Notes: ${log.bedNotes}`);
  const photos = extras.photoCount ?? 0;
  if (photos > 0) line(`- ${photos === 1 ? "1 photo" : `${photos} photos`} of this brew ${photos === 1 ? "is" : "are"} in the app (not attached)`);
  line();

  line("## Taste");
  line("| Axis | 0-10 |");
  line("| --- | --- |");
  for (const a of TASTE_AXES) line(`| ${a.label} | ${log.taste[a.key]} |`);
  line();
  line(`- Overall: ${log.overall}/10`);
  if (log.descriptors.length) line(`- Tasted like: ${log.descriptors.join(", ")}`);
  if (log.faults.length) line(`- Problems: ${log.faults.join(", ")}`);
  if (log.notes.trim()) {
    line();
    line(`> ${log.notes}`);
  }
  if (log.nextTime.trim()) {
    line();
    line(`**My own plan for next time:** ${log.nextTime}`);
  }
  line();

  if (history.length) {
    line("## Recent brews for context");
    line();
    line("| When | Recipe | Grind | Time | Overall | Bed |");
    line("| --- | --- | --- | --- | --- | --- |");
    for (const h of history.slice(0, 8)) {
      const g = h.grindClicks != null ? String(h.grindClicks) : h.grindValue != null ? String(h.grindValue) : h.grindSetting.trim() || "-";
      line(`| ${whenLabel(h.startedAt)} | ${h.recipeName.trim() || "-"} | ${g} | ${clock(h.totalTimeSec)} | ${h.overall}/10 | ${bedShapeLabel(h.bedShape)} |`);
    }
    line();
  }

  line("## What I want back");
  line("1. What most likely explains this cup?");
  if (clicks != null || log.grindValue != null) {
    line("2. The single change to make next brew. If it is a grind change, give it in this grinder's units, the way the dial reads, and say which way to turn, not just \"finer\" or \"coarser\".");
  } else {
    line("2. The single change I should make next brew, and roughly how big a change.");
  }
  line("3. What I should expect to taste if that change worked.");
  return out.join("\n") + "\n";
}

/** A run of brews, for asking about trends rather than one cup. */
export function trendMarkdown(logs: readonly BrewRow[], beans: readonly BeanRow[]): string {
  const out: string[] = [];
  const line = (s = "") => out.push(s);
  line(`# Brew history — last ${plural(logs.length, "brew")}`);
  line();
  line("Please look for patterns across these rather than diagnosing any single one.");
  line("I am interested in what my consistent mistakes are.");
  line();
  line("| When | Coffee | Recipe | Dose | Water | Grind | Time | Bed | Overall | Problems |");
  line("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |");
  for (const l of logs) {
    const bean = beans.find((b) => b.id === l.beanId);
    const name = bean ? beanDisplay(bean) : l.beanLabel.trim() || "-";
    const g = l.grindClicks != null ? String(l.grindClicks) : l.grindValue != null ? String(l.grindValue) : l.grindSetting.trim() || "-";
    line(
      `| ${whenLabel(l.startedAt)} | ${name} | ${l.recipeName.trim() || "-"} | ${grams(l.doseG)} | ${grams(l.waterG)} | ${g} | ${clock(l.totalTimeSec)} | ${bedShapeLabel(l.bedShape)} | ${l.overall}/10 | ${l.faults.join(", ") || "-"} |`,
    );
  }
  line();
  line("## What I want back");
  line("1. Which variable am I changing too often, and which am I never changing?");
  line("2. Is there a bed-shape or timing pattern that tracks with my low scores?");
  line("3. Give me one experiment to run over the next three brews.");
  return out.join("\n") + "\n";
}
