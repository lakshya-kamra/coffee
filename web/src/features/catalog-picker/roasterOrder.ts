/**
 * The roaster picker's order with an empty query (v4 §4.7): your favourites and your region
 * first, then supermarket brands, then specialty roasters A-Z. Pure, so it is tested without the
 * catalog files.
 */

export interface RoasterLike {
  readonly id: string;
  readonly name: string;
  readonly area: string;
  readonly mainstream: boolean;
  readonly regions: readonly string[];
}

export interface RoasterGroup {
  readonly name: string;
  readonly ids: readonly string[];
}

export const FAVOURITES_GROUP = "Your roasters";
export const MAINSTREAM_GROUP = "Supermarket brands";
export const SPECIALTY_GROUP = "Specialty roasters";

/** Sold where you buy coffee: based there, or a brand stocked there. */
export function inRegion(r: RoasterLike, homeArea: string): boolean {
  return homeArea !== "" && (r.area === homeArea || r.regions.includes(homeArea));
}

const byName = (a: RoasterLike, b: RoasterLike) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" });

/**
 * Favourites stay listed in their own group too (as owned brewers do), so the list reads the
 * same whether or not you starred anything. Every other roaster appears exactly once. Empty
 * groups are left out.
 */
export function roasterGroups(roasters: readonly RoasterLike[], homeArea: string, favourites: readonly string[]): RoasterGroup[] {
  const fav = new Set(favourites);
  const favs = roasters.filter((r) => fav.has(r.id)).sort(byName);
  const region = roasters.filter((r) => inRegion(r, homeArea)).sort(byName);
  const inReg = new Set(region.map((r) => r.id));
  const mainstream = roasters.filter((r) => !inReg.has(r.id) && r.mainstream).sort(byName);
  const specialty = roasters.filter((r) => !inReg.has(r.id) && !r.mainstream).sort(byName);
  const out: RoasterGroup[] = [
    { name: FAVOURITES_GROUP, ids: favs.map((r) => r.id) },
    { name: homeArea, ids: region.map((r) => r.id) },
    { name: MAINSTREAM_GROUP, ids: mainstream.map((r) => r.id) },
    { name: SPECIALTY_GROUP, ids: specialty.map((r) => r.id) },
  ];
  return out.filter((g) => g.ids.length > 0);
}
