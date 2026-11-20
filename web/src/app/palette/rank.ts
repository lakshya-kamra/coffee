/**
 * Palette ranking (v4 §2.8), loaded with the palette: groups in rank order, recents first on an
 * empty query, matches through domain/search (accents ignored, prefix and token matches).
 */
import { search, type SearchDoc } from "@/domain/search/search";
import type { Command, CommandGroup } from "./commands";

/** Rank order of the groups (§2.8). "Recent" leads when the query is empty. */
export const GROUPS: readonly CommandGroup[] = ["Actions", "Recipes", "Coffees", "Grinders", "Brews", "Go to"];

export interface RankedGroup {
  readonly group: CommandGroup | "Recent";
  readonly items: readonly Command[];
}

/** At most this many rows per group, so the list never passes 50 (§2.9 rule 5). */
export const GROUP_CAP: Readonly<Record<CommandGroup | "Recent", number>> = {
  Recent: 5,
  Actions: 6,
  Recipes: 10,
  Coffees: 6,
  Grinders: 5,
  Brews: 8,
  "Go to": 10,
};

const docCache = new WeakMap<readonly Command[], SearchDoc[]>();

function docsOf(commands: readonly Command[]): SearchDoc[] {
  let d = docCache.get(commands);
  if (!d) {
    d = commands.map((c, i) => ({
      id: String(i),
      title: c.title,
      subtitle: c.subtitle ?? "",
      group: c.group,
      keys: [c.title, ...(c.subtitle ? [c.subtitle] : []), ...(c.keywords ?? [])],
      popularity: c.popularity ?? 1000 + i,
    }));
    docCache.set(commands, d);
  }
  return d;
}

/**
 * Groups for the palette. Empty query: Recent (ids that still exist, newest first), then
 * Actions, then Go to. With a query: matches by score (domain/search: accents ignored, prefix
 * and token matches, a little fuzz), shown in the fixed group order, each group capped.
 */
export function rankCommands(commands: readonly Command[], query: string, recent: readonly string[] = []): RankedGroup[] {
  const q = query.trim();
  if (q === "") {
    const byId = new Map(commands.map((c) => [c.id, c]));
    const rec = recent.map((id) => byId.get(id)).filter((c): c is Command => c != null).slice(0, GROUP_CAP.Recent);
    const recIds = new Set(rec.map((c) => c.id));
    const actions = commands.filter((c) => c.group === "Actions" && !recIds.has(c.id)).slice(0, GROUP_CAP.Actions);
    const go = commands.filter((c) => c.group === "Go to" && !recIds.has(c.id)).slice(0, GROUP_CAP["Go to"]);
    const out: RankedGroup[] = [];
    if (rec.length) out.push({ group: "Recent", items: rec });
    if (actions.length) out.push({ group: "Actions", items: actions });
    if (go.length) out.push({ group: "Go to", items: go });
    return out;
  }
  const hits = search(docsOf(commands), q, 400);
  const by = new Map<CommandGroup, Command[]>();
  for (const h of hits) {
    const c = commands[Number(h.doc.id)]!;
    const arr = by.get(c.group) ?? [];
    if (arr.length < GROUP_CAP[c.group]) arr.push(c);
    by.set(c.group, arr);
  }
  return GROUPS.filter((g) => (by.get(g)?.length ?? 0) > 0).map((g) => ({ group: g, items: by.get(g)! }));
}

/** The flat row order the keyboard walks. */
export function flatten(groups: readonly RankedGroup[]): Command[] {
  return groups.flatMap((g) => g.items);
}
