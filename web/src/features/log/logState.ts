/**
 * Log search, filter chips, sort and the XL table (v4 §3.7, §4.11) as pure state: the screen only
 * draws. Matching and filtering are CORE's (domain/core/logQuery.ts); this file owns the UI copy,
 * the chip menus, the reducer and the table's sort order.
 */
import type { BrewRow } from "@/data/schema";
import { screenTime } from "@/domain/core/format";
import { brewerKey, EMPTY_LOG_FILTER, isFilterActive, logCountLabel, recipeKey, type LogFilter, type LogSort } from "@/domain/core/logQuery";
import { judgeTime, type TimeTarget } from "@/domain/core/shotTarget";

// ---------------------------------------------------------------- copy (§4.11)

export const SEARCH_PLACEHOLDER = "Search coffee, recipe, notes";
export const NO_RESULTS = "No brews match.";
export const CLEAR_FILTERS = "Clear filters";

export type ToggleChip = "favourites" | "withPhotos" | "rated8";
export type MenuChip = "coffee" | "recipe" | "brewer";

export const TOGGLE_CHIPS: readonly { readonly key: ToggleChip; readonly label: string }[] = [
  { key: "favourites", label: "Favourites" },
  { key: "withPhotos", label: "With photos" },
  { key: "rated8", label: "Rated 8+" },
];

export const MENU_CHIPS: readonly { readonly key: MenuChip; readonly label: string; readonly any: string }[] = [
  { key: "coffee", label: "Coffee", any: "Any coffee" },
  { key: "recipe", label: "Recipe", any: "Any recipe" },
  { key: "brewer", label: "Brewer", any: "Any brewer" },
];

export const SORTS: readonly { readonly key: LogSort; readonly label: string }[] = [
  { key: "newest", label: "Newest" },
  { key: "best", label: "Best rated" },
];

// ---------------------------------------------------------------- menu options

export interface FilterOption {
  /** The value the filter stores (beanLabel, recipeKey or brewerKey). */
  readonly key: string;
  readonly label: string;
  readonly count: number;
}

/** "Square Mile — Red Brick" reads as "Red Brick" in a chip; the menu row keeps the roaster below. */
function coffeeName(label: string): string {
  const i = label.indexOf(" — ");
  return (i >= 0 ? label.slice(i + 3) : label).trim();
}

/** The values one menu chip can take, most brewed first, then by name. Brews with no value are left out. */
export function filterOptions(brews: readonly BrewRow[], kind: MenuChip): FilterOption[] {
  const by = new Map<string, { label: string; count: number }>();
  for (const b of brews) {
    const key = kind === "coffee" ? b.beanLabel.trim() : kind === "recipe" ? recipeKey(b).trim() : brewerKey(b).trim();
    if (!key) continue;
    const label = kind === "coffee" ? coffeeName(b.beanLabel) || b.beanLabel : kind === "recipe" ? b.recipeName.trim() || "Brew" : b.brewer.trim() || key;
    const cur = by.get(key);
    if (cur) cur.count++;
    else by.set(key, { label, count: 1 });
  }
  return [...by.entries()].map(([key, v]) => ({ key, label: v.label, count: v.count })).sort((a, b) => b.count - a.count || a.label.localeCompare(b.label));
}

/** The chip's text: "Coffee ▾" with nothing picked, else the picked value's name. */
export function menuChipLabel(kind: MenuChip, filter: LogFilter, options: readonly FilterOption[]): string {
  const value = filter[kind];
  const base = MENU_CHIPS.find((c) => c.key === kind)!.label;
  if (value == null) return `${base} ▾`;
  const name = options.find((o) => o.key === value)?.label ?? (kind === "coffee" ? coffeeName(value) : value);
  return `${name} ▾`;
}

// ---------------------------------------------------------------- reducer

export type LogUiAction =
  | { readonly type: "query"; readonly query: string }
  | { readonly type: "toggle"; readonly chip: ToggleChip }
  | { readonly type: "pick"; readonly chip: MenuChip; readonly value: string | null }
  | { readonly type: "sort"; readonly sort: LogSort }
  | { readonly type: "clear" };

/** Clear keeps the sort: it is an order, not a filter. */
export function logUiReducer(f: LogFilter, a: LogUiAction): LogFilter {
  switch (a.type) {
    case "query":
      return f.query === a.query ? f : { ...f, query: a.query };
    case "toggle":
      return { ...f, [a.chip]: !f[a.chip] };
    case "pick":
      return f[a.chip] === a.value ? f : { ...f, [a.chip]: a.value };
    case "sort":
      return f.sort === a.sort ? f : { ...f, sort: a.sort };
    case "clear":
      return isFilterActive(f) ? { ...EMPTY_LOG_FILTER, sort: f.sort } : f;
  }
}

/** "12 brews"; with a filter on, "3 of 25 brews". */
export function resultLine(shown: number, total: number, active: boolean): string {
  return active && shown !== total ? `${shown} of ${logCountLabel(total)}` : logCountLabel(shown);
}

// ---------------------------------------------------------------- brew again (§4.10)

/** /brew/<recipeId>/again/<uid>. A brew without a recipe id goes through as "deleted": the brew screen falls back to a one-off setup. */
export function brewAgainPath(b: Pick<BrewRow, "recipeId" | "uid">): string {
  return `/brew/${encodeURIComponent(b.recipeId.trim() || "deleted")}/again/${encodeURIComponent(b.uid)}`;
}

// ---------------------------------------------------------------- table (XL)

export type TableKey = "date" | "recipe" | "coffee" | "grinder" | "dose" | "ratio" | "time" | "score" | "faults";
export type SortDir = "asc" | "desc";

export const TABLE_COLUMNS: readonly { readonly key: TableKey; readonly label: string; readonly numeric: boolean }[] = [
  { key: "date", label: "Date", numeric: false },
  { key: "recipe", label: "Recipe", numeric: false },
  { key: "coffee", label: "Coffee", numeric: false },
  { key: "grinder", label: "Grinder + setting", numeric: false },
  { key: "dose", label: "Dose", numeric: true },
  { key: "ratio", label: "Ratio", numeric: true },
  { key: "time", label: "Time vs plan", numeric: true },
  { key: "score", label: "Score", numeric: true },
  { key: "faults", label: "Faults", numeric: false },
];

/** A new column sorts its natural way first: dates and scores newest/highest first, words A to Z. */
export function defaultDir(key: TableKey): SortDir {
  return key === "recipe" || key === "coffee" || key === "grinder" ? "asc" : "desc";
}

/** Clicking the sorted column flips it; another column starts at its default. */
export function nextSort(cur: { key: TableKey; dir: SortDir }, key: TableKey): { key: TableKey; dir: SortDir } {
  return cur.key === key ? { key, dir: cur.dir === "asc" ? "desc" : "asc" } : { key, dir: defaultDir(key) };
}

/** Seconds over (+) or under (-) the plan, or outside a shot's band; null without a plan or a time. */
export function timeDelta(b: BrewRow, plan: TimeTarget | null): number | null {
  return judgeTime(b.totalTimeSec, plan)?.delta ?? null;
}

function sortValue(b: BrewRow, key: TableKey, plan: TimeTarget | null): number | string | null {
  switch (key) {
    case "date":
      return b.startedAt;
    case "recipe":
      return b.recipeName.trim().toLowerCase() || null;
    case "coffee":
      return coffeeName(b.beanLabel).toLowerCase() || null;
    case "grinder":
      return b.grinderLabel.trim().toLowerCase() || null;
    case "dose":
      return b.doseG > 0 ? b.doseG : null;
    case "ratio":
      return b.doseG > 0 && b.waterG > 0 ? b.waterG / b.doseG : null;
    case "time": {
      const d = timeDelta(b, plan);
      // Closest to the plan first when ascending: the size of the miss, not its sign.
      return d == null ? null : Math.abs(d);
    }
    case "score":
      return b.overall > 0 ? b.overall : null;
    case "faults":
      return b.faults.length;
  }
}

/**
 * Sorted copy. Missing values always go last, whichever way; ties fall back to newest first, so the
 * order never jumps between renders.
 */
export function sortTable(rows: readonly BrewRow[], key: TableKey, dir: SortDir, planOf: (b: BrewRow) => TimeTarget | null = () => null): BrewRow[] {
  const sign = dir === "asc" ? 1 : -1;
  const keyed = rows.map((b) => ({ b, v: sortValue(b, key, planOf(b)) }));
  keyed.sort((x, y) => {
    if (x.v == null && y.v == null) return y.b.startedAt - x.b.startedAt;
    if (x.v == null) return 1;
    if (y.v == null) return -1;
    const c = typeof x.v === "number" && typeof y.v === "number" ? x.v - y.v : String(x.v).localeCompare(String(y.v));
    return c * sign || y.b.startedAt - x.b.startedAt;
  });
  return keyed.map((k) => k.b);
}

// ---------------------------------------------------------------- per-device view

/** List or table (XL). Stored in the device-only settings key logView (§6.1), never in backups. */
export type LogView = "list" | "table";

/** The brew a log path points at: /log/<uid> and /log/<uid>/compare/<other> both select <uid>. */
export function selectedUidOf(path: string | null): string | null {
  if (!path) return null;
  const m = /^\/log\/([^/]+)/.exec(path);
  return m ? decodeURIComponent(m[1]!) : null;
}

/** "Thu 12 Nov · 08:14" in the reader's locale (§4.15); exports keep the pinned formats. */
export function detailWhen(at: number, locale?: string): string {
  const date = new Intl.DateTimeFormat(locale, { weekday: "short", day: "numeric", month: "short" }).format(new Date(at));
  return `${date} · ${screenTime(at, locale)}`;
}
