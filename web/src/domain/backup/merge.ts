/**
 * The pure merge planner (A§10.4 MergeRulesTest, web-spec §15.3). Given what this browser holds
 * (key -> local id and updatedAt) and what the file brings, decide per record:
 *   add      — the key is new here;
 *   replace  — the key exists and the file's updatedAt is strictly newer;
 *   keep     — the key exists and the file's copy is older, equal, or has no updatedAt
 *              (records without updatedAt never replace).
 * "Replace everything" plans against an empty browser, so every record is added. Running the
 * same plan twice therefore changes nothing the second time.
 */
import { parseInstant } from "./codec";
import type { BackupDoc, Counts } from "./model";

export type MergeMode = "add" | "replace";

export interface Existing<Id> {
  id: Id;
  /** Epoch ms; null when the row has none (never compared as newer). */
  updatedAt: number | null;
}

export interface TablePlan<T, Id> {
  add: T[];
  replace: { item: T; id: Id }[];
  keep: { item: T; id: Id }[];
}

/**
 * Plans one table. A key that appears twice in the file is planned once, from its newest copy
 * (the first copy when neither is newer), so a hand-merged file cannot add a row twice.
 */
export function planTable<T, Id>(
  incoming: readonly T[],
  key: (t: T) => string,
  updatedAt: (t: T) => number | null,
  existing: ReadonlyMap<string, Existing<Id>>,
  mode: MergeMode,
): TablePlan<T, Id> {
  const unique = new Map<string, T>();
  for (const item of incoming) {
    const k = key(item);
    const prev = unique.get(k);
    if (prev === undefined || (updatedAt(item) ?? -Infinity) > (updatedAt(prev) ?? -Infinity)) unique.set(k, item);
  }
  const plan: TablePlan<T, Id> = { add: [], replace: [], keep: [] };
  for (const [k, item] of unique) {
    const here = mode === "replace" ? undefined : existing.get(k);
    if (!here) {
      plan.add.push(item);
      continue;
    }
    const theirs = updatedAt(item);
    if (theirs != null && (here.updatedAt == null || theirs > here.updatedAt)) plan.replace.push({ item, id: here.id });
    else plan.keep.push({ item, id: here.id });
  }
  return plan;
}

const stamp = (v: string | null | undefined): number | null => (v ? parseInstant(v) : null);

export interface ExistingIndex {
  beans: ReadonlyMap<string, Existing<number>>;
  grinders: ReadonlyMap<string, Existing<number>>;
  brews: ReadonlyMap<string, Existing<number>>;
  recipes: ReadonlyMap<string, Existing<string>>;
  analyses: ReadonlyMap<string, Existing<number>>;
  photos: ReadonlyMap<string, Existing<number>>;
}

export interface MergePlan {
  beans: TablePlan<BackupDoc["beans"][number], number>;
  grinders: TablePlan<BackupDoc["grinders"][number], number>;
  brews: TablePlan<BackupDoc["brews"][number], number>;
  recipes: TablePlan<BackupDoc["recipes"][number], string>;
  /** Analyses and photos carry no updatedAt in the file, so they are only ever added or kept. */
  analyses: TablePlan<BackupDoc["analyses"][number], number>;
  photos: TablePlan<BackupDoc["photos"][number], number>;
}

export function planBackup(doc: Pick<BackupDoc, "beans" | "grinders" | "brews" | "recipes" | "analyses" | "photos">, existing: ExistingIndex, mode: MergeMode): MergePlan {
  return {
    beans: planTable(doc.beans, (b) => b.uid, (b) => stamp(b.updatedAt), existing.beans, mode),
    grinders: planTable(doc.grinders, (g) => g.uid, (g) => stamp(g.updatedAt), existing.grinders, mode),
    brews: planTable(doc.brews, (l) => l.uid, (l) => stamp(l.updatedAt), existing.brews, mode),
    recipes: planTable(doc.recipes, (r) => r.id, (r) => stamp(r.updatedAt), existing.recipes, mode),
    analyses: planTable(doc.analyses, (a) => a.uid, () => null, existing.analyses, mode),
    photos: planTable(doc.photos, (p) => p.uid, () => null, existing.photos, mode),
  };
}

export function planCounts(plan: MergePlan, part: "add" | "replace" | "keep"): Counts {
  return {
    brews: plan.brews[part].length,
    beans: plan.beans[part].length,
    grinders: plan.grinders[part].length,
    recipes: plan.recipes[part].length,
    analyses: plan.analyses[part].length,
    photos: plan.photos[part].length,
  };
}
