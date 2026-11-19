/**
 * Search documents per catalog kind (A§7.1 `CatalogRepository.search`): built once per loaded
 * catalog and cached, so typing never rebuilds them. Group names are the picker's sticky headers
 * (grinders: brand; roasters and origins: area; varietals: species; brewers: category).
 */
import { catalog } from "@/catalog/catalog";
import type { CatalogGrinder, CatalogName } from "@/catalog/types";
import type { Brewer } from "@/domain/core/brewer";
import type { SearchDoc } from "./search";

export type CatalogKind = "grinder" | "roaster" | "origin" | "region" | "varietal" | "process" | "brewer" | "water" | "lotType" | "decaf" | "roastLevel";

/** The shared file each kind reads; the picker loads it on open. */
export const KIND_FILE: Readonly<Record<CatalogKind, CatalogName>> = {
  grinder: "grinders",
  roaster: "roasters",
  origin: "origins",
  region: "origins",
  varietal: "beans",
  process: "beans",
  brewer: "brewers",
  water: "water",
  lotType: "beans",
  decaf: "beans",
  roastLevel: "beans",
};

const BURR_WORD: Readonly<Record<string, string>> = { conical: "Conical", flat: "Flat", ghost: "Ghost", blade: "Blade" };

/** "Hand · Conical 48 mm" (A§7.2 row subtitle). */
export function grinderSubtitle(g: CatalogGrinder): string {
  const kind = g.kind ? g.kind[0]!.toUpperCase() + g.kind.slice(1) : "";
  const burr = [BURR_WORD[g.burr] ?? "", g.burrSizeMm != null ? `${g.burrSizeMm} mm` : ""].filter(Boolean).join(" ");
  return [kind, burr].filter(Boolean).join(" · ");
}

/** "Hario V60 02", "Chemex", "Moka pot": the brand only where it adds something. */
export function brewerTitle(b: Pick<Brewer, "brand" | "label">): string {
  const brand = b.brand.trim();
  if (!brand || /\b(others|various)\b/i.test(brand) || b.label.toLowerCase().startsWith(brand.toLowerCase())) return b.label;
  return `${brand} ${b.label}`;
}

/** Brewer categories in the order a person thinks of them, then anything new A–Z. */
export const BREWER_CATEGORY_ORDER: readonly string[] = ["cone", "flat-bottom", "hybrid", "immersion", "pressure", "espresso", "siphon", "cold", "other"];

const CATEGORY_WORD: Readonly<Record<string, string>> = {
  cone: "Cones",
  "flat-bottom": "Flat-bottom",
  hybrid: "Hybrid",
  immersion: "Immersion",
  pressure: "Stovetop",
  espresso: "Espresso",
  siphon: "Siphon",
  cold: "Cold brew",
  other: "Other",
};

export function brewerCategoryLabel(c: string): string {
  return CATEGORY_WORD[c] ?? c.charAt(0).toUpperCase() + c.slice(1);
}

const PROCESS_GROUP_WORD: Readonly<Record<string, string>> = { classic: "Classic", honey: "Honey", fermentation: "Fermentation", other: "Other" };

export function processGroupLabel(g: string): string {
  return PROCESS_GROUP_WORD[g] ?? g.charAt(0).toUpperCase() + g.slice(1);
}

const WATER_KIND_WORD: Readonly<Record<string, string>> = { tap: "Tap", bottled: "Bottled", drops: "Mineral drops", recipe: "Recipes", other: "Other" };

/** "region" ids are "<originId>/<region>" so a region is unique across countries. */
export function regionId(originId: string, region: string): string {
  return `${originId}/${region}`;
}

function sortedBy<T>(list: readonly T[], key: (t: T) => string): T[] {
  return [...list].sort((a, b) => key(a).localeCompare(key(b)));
}

function build(kind: CatalogKind): SearchDoc[] {
  switch (kind) {
    case "grinder":
      // Brands A–Z so headers read like an index; search ranks across them anyway.
      return sortedBy(catalog.grinders, (g) => g.brand).map((g) => ({
        id: g.id,
        title: g.display,
        subtitle: grinderSubtitle(g),
        group: g.brand,
        keys: [g.brand, g.model, `${g.brand} ${g.model}`],
        popularity: g.popularity,
      }));
    case "roaster":
      return sortedBy(catalog.roasters, (r) => r.area).map((r) => ({
        id: r.id,
        title: r.name,
        subtitle: [[r.city, r.country].filter(Boolean).join(", "), r.notable].filter(Boolean).join(" · "),
        group: r.area || "Elsewhere",
        keys: [r.name, r.city, r.country],
        popularity: 3,
      }));
    case "origin":
      return sortedBy(catalog.origins, (o) => o.area).map((o) => ({
        id: o.id,
        title: o.country,
        subtitle: o.growingRegions.slice(0, 4).join(", "),
        group: o.area || "Elsewhere",
        keys: [o.country, o.iso2],
        popularity: 3,
      }));
    case "region":
      return sortedBy(catalog.origins, (o) => o.country).flatMap((o) =>
        o.growingRegions.map((r) => ({ id: regionId(o.id, r), title: r, subtitle: o.country, group: o.country, keys: [r, `${o.country} ${r}`], popularity: 3 })),
      );
    case "varietal": {
      const species = new Map(catalog.beans.species.map((s, i) => [s.id, { label: s.label, i }]));
      return [...catalog.beans.varietals]
        .sort((a, b) => (species.get(a.species)?.i ?? 99) - (species.get(b.species)?.i ?? 99))
        .map((v) => ({
          id: v.id,
          title: v.label,
          subtitle: v.aliases.join(", "),
          group: species.get(v.species)?.label ?? "Other",
          keys: [v.label, ...v.aliases],
          popularity: 3,
        }));
    }
    case "process":
      return catalog.beans.processes.map((p) => ({ id: p.id, title: p.label, subtitle: p.description, group: processGroupLabel(p.group), keys: [p.label], popularity: 3 }));
    case "brewer": {
      const rank = (c: string) => {
        const i = BREWER_CATEGORY_ORDER.indexOf(c);
        return i < 0 ? BREWER_CATEGORY_ORDER.length : i;
      };
      return [...catalog.brewers]
        .sort((a, b) => rank(a.category) - rank(b.category) || a.category.localeCompare(b.category))
        .map((b) => ({
          id: b.id,
          title: brewerTitle(b),
          subtitle: b.notes.length > 90 ? b.notes.slice(0, 89).trimEnd() + "…" : b.notes,
          group: brewerCategoryLabel(b.category),
          keys: [b.label, b.brand, `${b.brand} ${b.label}`],
          popularity: 3,
        }));
    }
    case "water":
      return catalog.water.map((w) => ({ id: w.id, title: w.label, subtitle: w.description, group: WATER_KIND_WORD[w.kind] ?? "Other", keys: [w.label], popularity: 3 }));
    case "lotType":
      return catalog.beans.lotTypes.map((t) => ({ id: t.id, title: t.label, subtitle: t.description, group: "Lot types", keys: [t.label], popularity: 3 }));
    case "decaf":
      return catalog.beans.decafMethods.map((t) => ({ id: t.id, title: t.label, subtitle: "", group: "Decaf methods", keys: [t.label], popularity: 3 }));
    case "roastLevel":
      return catalog.beans.roastLevels.map((t) => ({ id: t.id, title: t.label, subtitle: t.description, group: "Roast levels", keys: [t.label], popularity: 3 }));
  }
}

interface Entry {
  readonly version: number;
  readonly docs: readonly SearchDoc[];
  readonly byId: ReadonlyMap<string, SearchDoc>;
}

const cache = new Map<CatalogKind, Entry>();

function entry(kind: CatalogKind): Entry {
  const v = catalog.version();
  const hit = cache.get(kind);
  if (hit && hit.version === v) return hit;
  const docs = Object.freeze(build(kind));
  const e = { version: v, docs, byId: new Map(docs.map((d) => [d.id, d])) };
  cache.set(kind, e);
  return e;
}

/** Docs for one kind, rebuilt only when a catalog file has loaded since the last call. */
export function docsFor(kind: CatalogKind): readonly SearchDoc[] {
  return entry(kind).docs;
}

/** The doc for a picked id (chips, summaries); null when the id is unknown. */
export function docFor(kind: CatalogKind, id: string): SearchDoc | null {
  return entry(kind).byId.get(id) ?? null;
}
