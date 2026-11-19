/**
 * Hand-written guards for shared/ JSON (web-spec §9). Each file's list is read entry by entry;
 * a bad entry is skipped and recorded as "catalog/grinders.json[12] (id=…): <reason>". A missing
 * file is an empty list plus one problem line. Catalog data can never crash the app.
 */
import type {
  BeanVocabulary,
  BrowserCors,
  CatalogGrinder,
  CatalogModel,
  CatalogOrigin,
  CatalogProvider,
  CatalogRoaster,
  CatalogWater,
  DescribedTerm,
  GrindRange,
  GrinderSetting,
  Principle,
  ProcessTerm,
  SourceRef,
  Term,
  VarietalTerm,
} from "./types";

export const KEBAB = /^[a-z0-9]+(-[a-z0-9]+)*$/;

type Obj = Record<string, unknown>;

class EntryError extends Error {}

function fail(reason: string): never {
  throw new EntryError(reason);
}

function isObj(v: unknown): v is Obj {
  return v != null && typeof v === "object" && !Array.isArray(v);
}

function reqStr(o: Obj, k: string): string {
  const v = o[k];
  if (typeof v !== "string" || v.trim() === "") fail(`missing ${k}`);
  return v;
}

function optStr(o: Obj, k: string, d = ""): string {
  const v = o[k];
  return typeof v === "string" ? v : d;
}

function reqNum(o: Obj, k: string): number {
  const v = o[k];
  if (typeof v !== "number" || !Number.isFinite(v)) fail(`missing number ${k}`);
  return v;
}

function optNum(o: Obj, k: string): number | null {
  const v = o[k];
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function optBool(o: Obj, k: string, d: boolean): boolean {
  const v = o[k];
  return typeof v === "boolean" ? v : d;
}

function strList(o: Obj, k: string): string[] {
  const v = o[k];
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
}

function strMap(o: Obj, k: string): Record<string, string> {
  const v = o[k];
  if (!isObj(v)) return {};
  const out: Record<string, string> = {};
  for (const [key, val] of Object.entries(v)) if (typeof val === "string") out[key] = val;
  return Object.freeze(out);
}

function id(o: Obj): string {
  const v = reqStr(o, "id");
  if (!KEBAB.test(v)) fail(`id "${v}" is not lowercase-kebab`);
  return v;
}

function sourceRefs(o: Obj, k: string): SourceRef[] {
  const v = o[k];
  if (!Array.isArray(v)) return [];
  return v.filter(isObj).filter((s) => typeof s.who === "string" && s.who !== "")
    .map((s) => Object.freeze({ who: s.who as string, url: optStr(s, "url"), note: optStr(s, "note") }));
}

export interface Parsed<T> {
  readonly items: readonly T[];
  readonly problems: readonly string[];
}

/** Reads `raw[listKey]` entry by entry with `one`, collecting problems instead of throwing. */
export function parseList<T extends { id: string }>(
  file: string,
  raw: unknown,
  listKey: string,
  one: (o: Obj) => T,
): Parsed<T> {
  const problems: string[] = [];
  if (raw === undefined) return { items: [], problems: [`${file}: missing file`] };
  if (!isObj(raw)) return { items: [], problems: [`${file}: not a JSON object`] };
  if (raw.version !== 1) problems.push(`${file}: version ${String(raw.version)} (expected 1)`);
  const list = raw[listKey];
  if (!Array.isArray(list)) return { items: [], problems: [...problems, `${file}: "${listKey}" is not a list`] };
  const items: T[] = [];
  const seen = new Set<string>();
  list.forEach((entry, i) => {
    const label = isObj(entry) && typeof entry.id === "string" ? ` (id=${entry.id})` : "";
    try {
      if (!isObj(entry)) fail("not an object");
      const item = one(entry);
      if (seen.has(item.id)) fail("duplicate id");
      seen.add(item.id);
      items.push(Object.freeze(item));
    } catch (e) {
      const reason = e instanceof Error ? e.message : String(e);
      problems.push(`${file}[${i}]${label}: ${reason}`);
    }
  });
  return { items: Object.freeze(items), problems };
}

// ---------------------------------------------------------------- per-file entry readers

const STYLES = ["clicks", "numbers", "stepless"] as const;
const CONFIDENCE = ["high", "medium", "low"] as const;

function setting(o: Obj): GrinderSetting {
  const s = o.setting;
  if (!isObj(s)) fail("missing setting");
  const style = reqStr(s, "style");
  if (!(STYLES as readonly string[]).includes(style)) fail(`setting.style "${style}" unknown`);
  const cpr = optNum(s, "clicksPerRotation");
  const dialFormat = typeof s.dialFormat === "string" && s.dialFormat !== "" ? s.dialFormat : null;
  return Object.freeze({
    style: style as GrinderSetting["style"],
    unit: optStr(s, "unit"),
    min: reqNum(s, "min"),
    max: reqNum(s, "max"),
    step: reqNum(s, "step"),
    lowerIsFiner: optBool(s, "lowerIsFiner", true),
    clicksPerRotation: cpr == null ? null : Math.trunc(cpr),
    micronsPerStep: optNum(s, "micronsPerStep"),
    dialReading: optStr(s, "dialReading"),
    entryHint: optStr(s, "entryHint"),
    dialFormat,
  });
}

function ranges(o: Obj): Record<string, GrindRange> {
  const v = o.ranges;
  if (!isObj(v)) return {};
  const out: Record<string, GrindRange> = {};
  for (const [k, r] of Object.entries(v)) {
    if (!isObj(r)) continue;
    const from = optNum(r, "from");
    const to = optNum(r, "to");
    if (from != null && to != null) out[k] = Object.freeze({ from, to });
  }
  return Object.freeze(out);
}

export function grinder(o: Obj): CatalogGrinder {
  const brand = reqStr(o, "brand");
  const model = reqStr(o, "model");
  const conf = optStr(o, "confidence", "low");
  return {
    id: id(o),
    brand,
    model,
    kind: optStr(o, "kind", "hand"),
    burr: optStr(o, "burr"),
    burrSizeMm: optNum(o, "burrSizeMm"),
    popularity: optNum(o, "popularity") ?? 3,
    setting: setting(o),
    ranges: ranges(o),
    rangeSources: sourceRefs(o, "rangeSources"),
    confidence: (CONFIDENCE as readonly string[]).includes(conf) ? (conf as CatalogGrinder["confidence"]) : "low",
    notes: optStr(o, "notes"),
    display: `${brand} ${model}`.trim(),
  };
}

export function roaster(o: Obj): CatalogRoaster {
  return {
    id: id(o),
    name: reqStr(o, "name"),
    country: optStr(o, "country"),
    city: optStr(o, "city"),
    area: optStr(o, "area"),
    notable: optStr(o, "notable"),
  };
}

export function origin(o: Obj): CatalogOrigin {
  return {
    id: id(o),
    country: reqStr(o, "country"),
    iso2: optStr(o, "iso2"),
    area: optStr(o, "area"),
    growingRegions: Object.freeze(strList(o, "growingRegions")),
    commonProcesses: Object.freeze(strList(o, "commonProcesses")),
    commonVarietals: Object.freeze(strList(o, "commonVarietals")),
  };
}

export function water(o: Obj): CatalogWater {
  return {
    id: id(o),
    label: reqStr(o, "label"),
    kind: optStr(o, "kind", "other"),
    description: optStr(o, "description"),
    ghPpm: optNum(o, "ghPpm"),
    khPpm: optNum(o, "khPpm"),
  };
}

/** Raw brewers.json entry; catalog.ts maps it to a domain Brewer. */
export interface CatalogBrewerRaw {
  readonly id: string;
  readonly name: string;
  readonly brand: string;
  readonly category: string;
  readonly method: string;
  readonly filter: string;
  readonly notes: string;
}

export function brewer(o: Obj): CatalogBrewerRaw {
  return {
    id: id(o),
    name: reqStr(o, "name"),
    brand: optStr(o, "brand"),
    category: optStr(o, "category", "other"),
    method: optStr(o, "method", "pourover"),
    filter: optStr(o, "filter", "paper"),
    notes: optStr(o, "notes"),
  };
}

function model(o: Obj): CatalogModel {
  return Object.freeze({
    id: reqStr(o, "id"),
    label: optStr(o, "label"),
    inputPerM: optNum(o, "inputPerM"),
    outputPerM: optNum(o, "outputPerM"),
    vision: optBool(o, "vision", false),
    default: optBool(o, "default", false),
    why: optStr(o, "why"),
  });
}

export function provider(o: Obj): CatalogProvider {
  const cors = optStr(o, "browserCors", "unknown");
  const lm = isObj(o.listModels) ? o.listModels : {};
  return {
    id: id(o),
    name: reqStr(o, "name"),
    baseUrl: optStr(o, "baseUrl"),
    api: optStr(o, "api", "openai-chat"),
    auth: optStr(o, "auth", "bearer"),
    extraHeaders: strMap(o, "extraHeaders"),
    browserHeaders: strMap(o, "browserHeaders"),
    keyUrl: optStr(o, "keyUrl"),
    keyHint: optStr(o, "keyHint"),
    browserCors: (["yes", "no", "unknown"] as const).includes(cors as BrowserCors) ? (cors as BrowserCors) : "unknown",
    corsEvidence: optStr(o, "corsEvidence"),
    listModels: Object.freeze({ path: optStr(lm, "path", "/models"), works: optBool(lm, "works", false), note: optStr(lm, "note") }),
    models: Object.freeze(Array.isArray(o.models) ? o.models.filter(isObj).map(model) : []),
    pricesCheckedOn: optStr(o, "pricesCheckedOn"),
    notes: optStr(o, "notes"),
  };
}

export function principle(o: Obj): Principle {
  return {
    id: id(o),
    topic: optStr(o, "topic"),
    appliesTo: Object.freeze(strList(o, "appliesTo")),
    text: reqStr(o, "text"),
    who: optStr(o, "who"),
    url: optStr(o, "url"),
    note: optStr(o, "note"),
  };
}

const term = (o: Obj): Term => ({ id: id(o), label: reqStr(o, "label") });
const described = (o: Obj): DescribedTerm => ({ ...term(o), description: optStr(o, "description") });
const processTerm = (o: Obj): ProcessTerm => ({ ...described(o), group: optStr(o, "group", "other") });
const varietal = (o: Obj): VarietalTerm => ({ ...term(o), aliases: Object.freeze(strList(o, "aliases")), species: optStr(o, "species") });

/** beans.json holds seven vocabularies in one file. */
export function parseBeans(file: string, raw: unknown): { vocab: BeanVocabulary; problems: string[] } {
  const problems: string[] = [];
  const get = <T extends { id: string }>(key: string, one: (o: Obj) => T): readonly T[] => {
    if (raw === undefined) return [];
    const p = parseList(`${file}#${key}`, isObj(raw) ? { version: raw.version, [key]: raw[key] } : raw, key, one);
    problems.push(...p.problems);
    return p.items;
  };
  if (raw === undefined) problems.push(`${file}: missing file`);
  const vocab: BeanVocabulary = Object.freeze({
    species: get("species", term),
    processes: get("processes", processTerm),
    varietals: get("varietals", varietal),
    lotTypes: get("lotTypes", described),
    decafMethods: get("decafMethods", term),
    roastLevels: get("roastLevels", described),
    roastIntents: get("roastIntents", term),
  });
  return { vocab, problems };
}
