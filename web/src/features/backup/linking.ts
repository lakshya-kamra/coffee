/**
 * Runs the catalog linker (A§6) against IndexedDB: once at startup (startupTasksVersion 1, as
 * Android's StartupTasks) and after every import. Ids are written straight to the tables without
 * touching updatedAt: linking is a derived fact, not an edit, so re-importing the same file still
 * finds every row unchanged.
 */
import { catalog } from "@/catalog/catalog";
import { db } from "@/data/db";
import { settings } from "@/data/settings";
import { grinderScaleNotice, linkBean, linkGrinder } from "@/domain/backup/linker";

export interface LinkResult {
  grinders: number;
  beans: number;
  notices: string[];
}

/** `only` limits linking to these uids (the rows an import just wrote); omitted = every row. */
export async function linkAll(only?: ReadonlySet<string>): Promise<LinkResult> {
  await catalog.load(["grinders", "roasters", "origins"]);
  const grinderCatalog = catalog.grinders;
  const roasters = catalog.roasters;
  const origins = catalog.origins;
  const out: LinkResult = { grinders: 0, beans: 0, notices: [] };
  await db.transaction("rw", [db.grinders, db.beans], async () => {
    for (const g of await db.grinders.toArray()) {
      if (only && !only.has(g.uid)) continue;
      const link = g.id != null ? linkGrinder(g, grinderCatalog) : null;
      if (!link) continue;
      await db.grinders.update(g.id, { catalogId: link.catalogId });
      out.grinders++;
      if (link.notice) out.notices.push(grinderScaleNotice(g.uid));
    }
    for (const b of await db.beans.toArray()) {
      if (only && !only.has(b.uid)) continue;
      if (b.id == null || (b.roasterId != null && b.originId != null)) continue;
      const linked = linkBean(b, roasters, origins);
      if (!linked) continue;
      await db.beans.update(b.id, { roasterId: linked.roasterId, originId: linked.originId });
      out.beans++;
    }
  });
  for (const n of out.notices) await settings.addNotice(n);
  return out;
}

/** Startup task (registry.startupTasks). Idempotent: version 1 runs once per browser. */
export async function runStartupLinking(): Promise<void> {
  const s = await settings.get();
  if (s.startupTasksVersion >= 1) return;
  await linkAll();
  await settings.set("startupTasksVersion", 1);
}
