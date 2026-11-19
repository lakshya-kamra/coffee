/**
 * Port of A§2.4 LegacyIds plus the 1.x label tables from A§10.1. Frozen: old data and old
 * exports point at these strings forever. A test checks every mapped id exists in the catalog.
 */
import { makeBrewer, type Brewer } from "./brewer";
import type { BedShape, Channeling, FlowFeel, Sludge } from "./evaluation";

function frozen<T extends Record<string, string>>(o: T): Readonly<T> {
  return Object.freeze(o);
}

export const LegacyIds = {
  /** Brewer enum names AND labels written by 1.x → brewers.json ids (lead decision 8). */
  BREWER: frozen<Record<string, string>>({
    "Hario V60": "hario-v60-02", V60: "hario-v60-02",
    Chemex: "chemex",
    "Kalita Wave": "kalita-wave-185", Kalita: "kalita-wave-185",
    "Hario Switch": "hario-switch", Switch: "hario-switch",
    AeroPress: "aeropress",
    "French Press": "french-press", FrenchPress: "french-press",
    "Moka Pot": "moka-pot", Moka: "moka-pot",
    Espresso: "espresso-machine",
    "Cold Brew": "cold-brew-jar", ColdBrew: "cold-brew-jar",
  }),
  /** 1.x Process enum NAMES (Room) → beans.json process ids. "" = not recorded. */
  PROCESS: frozen<Record<string, string>>({
    Washed: "washed", Natural: "natural", Honey: "honey", Anaerobic: "anaerobic",
    CarbonicMaceration: "carbonic-maceration", Wet: "wet-hulled", Other: "unknown", Unknown: "",
  }),
  /** 1.x Process LABELS (old JSON export) → ids. */
  PROCESS_LABEL: frozen<Record<string, string>>({
    Washed: "washed", Natural: "natural", Honey: "honey", Anaerobic: "anaerobic",
    "Carbonic maceration": "carbonic-maceration", "Wet-hulled": "wet-hulled", Other: "unknown", Unknown: "",
  }),
  ROAST_LEVEL: frozen<Record<string, string>>({
    Light: "light", MediumLight: "medium-light", Medium: "medium", MediumDark: "medium-dark", Dark: "dark", Unknown: "",
  }),
  ROAST_LEVEL_LABEL: frozen<Record<string, string>>({
    Light: "light", "Medium-light": "medium-light", Medium: "medium", "Medium-dark": "medium-dark", Dark: "dark", Unknown: "",
  }),
  /** Used only when brewers.json lacks a legacy id, so 1.x data always resolves. Labels are 1.x labels. */
  FALLBACK_BREWERS: Object.freeze<Brewer[]>([
    makeBrewer({ id: "hario-v60-02", label: "Hario V60", brand: "Hario", category: "cone", method: "pourover", filter: "paper" }),
    makeBrewer({ id: "chemex", label: "Chemex", brand: "Chemex", category: "cone", method: "pourover", filter: "paper" }),
    makeBrewer({ id: "kalita-wave-185", label: "Kalita Wave", brand: "Kalita", category: "flat-bottom", method: "pourover", filter: "paper" }),
    makeBrewer({ id: "hario-switch", label: "Hario Switch", brand: "Hario", category: "hybrid", method: "pourover", filter: "paper" }),
    makeBrewer({ id: "aeropress", label: "AeroPress", brand: "AeroPress", category: "hybrid", method: "aeropress", filter: "paper" }),
    makeBrewer({ id: "french-press", label: "French Press", brand: "", category: "immersion", method: "frenchPress", filter: "metal" }),
    makeBrewer({ id: "moka-pot", label: "Moka Pot", brand: "", category: "pressure", method: "moka", filter: "metal" }),
    makeBrewer({ id: "espresso-machine", label: "Espresso", brand: "", category: "espresso", method: "espresso", filter: "metal" }),
    makeBrewer({ id: "cold-brew-jar", label: "Cold Brew", brand: "", category: "cold", method: "coldBrew", filter: "paper" }),
  ]),
  /** The 14 recipe ids shipped in 1.x. Old logs point at them; they must resolve forever. */
  LEGACY_RECIPE_IDS: Object.freeze([
    "hoffmann-v60", "kasuya-46", "hoffmann-french-press", "hoffmann-aeropress",
    "hedrick-aeropress", "rao-v60", "hedrick-espresso", "perger-v60", "gagne-v60", "wendelboe-chemex",
    "onyx-kalita", "eckroth-v60", "switch-hybrid", "hoffmann-moka",
  ]),
} as const;

/** 1.x labels (old JSON export) AND Kotlin enum names → backup v2 ids (A§10.1). null = not recorded. */
export const LegacyLabels = {
  BED: frozen<Record<string, BedShape | "">>({
    "Not recorded": "", "Flat and even": "flat", "Domed in the middle": "domed", "Cratered / dug out": "crater",
    "High and dry ring": "high-and-dry", "Sloped to one side": "sloped", "Sinkholes / pits": "sinkhole",
    "Muddy and compacted": "muddy",
    Unset: "", Flat: "flat", Domed: "domed", Crater: "crater", HighAndDry: "high-and-dry", Sloped: "sloped",
    Sinkhole: "sinkhole", Muddy: "muddy",
  }),
  SLUDGE: frozen<Record<string, Sludge | "">>({
    "Not recorded": "", Clean: "none", "A little silt": "light", "Thick muddy cap": "heavy",
    Unset: "", None: "none", Light: "light", Heavy: "heavy",
  }),
  CHANNELING: frozen<Record<string, Channeling | "">>({
    "Not recorded": "", "None visible": "none", Maybe: "suspected", "Obvious holes": "obvious",
    Unset: "", None: "none", Suspected: "suspected", Obvious: "obvious",
  }),
  FLOW: frozen<Record<string, FlowFeel | "">>({
    "Not recorded": "", "Stalled / never finished": "stalled", "Slower than target": "slow", "On target": "on-target",
    "Faster than target": "fast", "Fell straight through": "gusher",
    Unset: "", Stalled: "stalled", Slow: "slow", OnTarget: "on-target", Fast: "fast", Gusher: "gusher",
  }),
  BURR: frozen<Record<string, "conical" | "flat" | "ghost" | "blade" | "">>({
    Conical: "conical", Flat: "flat", "Ghost burr": "ghost", Ghost: "ghost", Blade: "blade", Unknown: "",
  }),
  SETTING_STYLE: frozen<Record<string, "clicks" | "numbers" | "microns" | "stepless">>({
    Clicks: "clicks", "Numbered dial": "numbers", Numbers: "numbers", Microns: "microns",
    "Stepless / degrees": "stepless", Stepless: "stepless",
  }),
} as const;

/** Map a legacy string through one of the tables; "" and unknown → null. */
export function legacyLabel<T extends string>(table: Readonly<Record<string, T | "">>, raw: string | null | undefined): T | null {
  if (raw == null) return null;
  const v = table[raw.trim()];
  return v ? v : null;
}
