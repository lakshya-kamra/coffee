/**
 * Port of A§6 / data/catalog/CatalogLinker.kt. Links rows typed before the catalog existed (or
 * imported from 1.x) to catalog ids. Only ids are written: no number or text a person entered is
 * ever changed. When a linked grinder's scale disagrees with a well-sourced catalog entry, the
 * caller adds a "grinder-scale:<uid>" notice so the user decides. Pure.
 */
import type { CatalogGrinder, CatalogOrigin, CatalogRoaster } from "@/catalog/types";
import type { BeanRow, GrinderRow, SettingStyle } from "@/data/schema";

/** Lowercase, accents stripped (ö -> o), only [a-z0-9] kept. */
export function norm(s: string): string {
  return s.toLowerCase().normalize("NFD").replace(/\p{M}+/gu, "").replace(/ø/g, "o").replace(/[^a-z0-9]/g, "");
}

/** The catalog entry whose brand + model matches exactly one entry, else null. */
export function matchGrinder(row: Pick<GrinderRow, "brand" | "model">, catalog: readonly CatalogGrinder[]): CatalogGrinder | null {
  const key = norm(row.brand + row.model);
  if (!key) return null;
  const hits = catalog.filter((c) => norm(c.brand + c.model) === key);
  return hits.length === 1 ? hits[0]! : null;
}

/** True when the row's scale differs from the catalog's and the catalog is sourced well enough to say so. */
export function scaleDiffers(
  row: Pick<GrinderRow, "settingStyle" | "clicksPerRotation" | "micronsPerClick" | "lowerIsFiner">,
  c: CatalogGrinder,
): boolean {
  if (c.confidence !== "high" && c.confidence !== "medium") return false;
  const s = c.setting;
  let style: SettingStyle;
  switch (s.style) {
    case "clicks":
    case "numbers":
    case "stepless":
      style = s.style;
      break;
    default:
      return false;
  }
  const styleDiffers = row.settingStyle !== style && !(row.settingStyle === "microns" && style !== "clicks");
  const cprDiffers = s.clicksPerRotation != null && row.clicksPerRotation !== s.clicksPerRotation;
  const micronsDiffer = s.micronsPerStep != null && row.micronsPerClick != null && Math.abs(row.micronsPerClick - s.micronsPerStep) > 0.001;
  return styleDiffers || cprDiffers || micronsDiffer || row.lowerIsFiner !== s.lowerIsFiner;
}

// "Square Mile Coffee Roasters" and "Square Mile" are the same roaster.
const SUFFIX_WORDS = new Set(["coffee", "roasters", "roastery", "roasting", "co", "company"]);

function stripSuffix(name: string): string {
  const words = name.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((w) => w !== "");
  while (words.length > 1 && SUFFIX_WORDS.has(words[words.length - 1]!)) words.pop();
  return norm(words.join(""));
}

export function matchRoaster(name: string, catalog: readonly CatalogRoaster[]): CatalogRoaster | null {
  if (!name.trim()) return null;
  const key = norm(name);
  const bare = stripSuffix(name);
  return catalog.find((r) => norm(r.name) === key) ?? catalog.find((r) => stripSuffix(r.name) !== "" && stripSuffix(r.name) === bare) ?? null;
}

export function matchOrigin(origin: string, catalog: readonly CatalogOrigin[]): CatalogOrigin | null {
  const t = origin.trim().toLowerCase();
  if (!t) return null;
  return catalog.find((o) => o.country.toLowerCase() === t || o.iso2.toLowerCase() === t) ?? null;
}

/** The bean with roaster/origin ids filled where a match is certain; null when nothing changes. */
export function linkBean<B extends Pick<BeanRow, "roaster" | "roasterId" | "origin" | "originId">>(
  b: B,
  roasters: readonly CatalogRoaster[],
  origins: readonly CatalogOrigin[],
): B | null {
  const roasterId = b.roasterId ?? matchRoaster(b.roaster, roasters)?.id ?? null;
  const originId = b.originId ?? matchOrigin(b.origin, origins)?.id ?? null;
  if (roasterId === b.roasterId && originId === b.originId) return null;
  return { ...b, roasterId, originId };
}

export interface GrinderLink {
  catalogId: string;
  /** Add notice "grinder-scale:<uid>". */
  notice: boolean;
}

/** For a grinder with no catalogId: the id to set and whether to ask about the scale. */
export function linkGrinder(row: GrinderRow, catalog: readonly CatalogGrinder[]): GrinderLink | null {
  if (row.catalogId != null) return null;
  const c = matchGrinder(row, catalog);
  return c ? { catalogId: c.id, notice: scaleDiffers(row, c) } : null;
}

export const grinderScaleNotice = (uid: string) => `grinder-scale:${uid}`;
