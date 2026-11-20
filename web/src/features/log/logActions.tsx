/**
 * What can be done to one brew from anywhere in Log (v4 §3.7, §3.8, §4.10): Brew again, Compare
 * with…, Favourite, Delete (asks, then an Undo toast). The list rows, the table, the context menu,
 * the brew detail header and the keyboard all go through here, so they always agree.
 */
import { useCallback, useMemo, useState, type ReactNode } from "react";
import { usePaneMode, useSelectInPane } from "@/app/panes";
import { nav, router } from "@/app/router";
import { pathOf } from "@/app/routes";
import { showToast } from "@/app/toast";
import { db } from "@/data/db";
import { repo } from "@/data/repo";
import type { AnalysisRow, BrewRow, PhotoBlobRow, PhotoRow } from "@/data/schema";
import { useStore } from "@/data/store";
import { CoffeeSheet, ConfirmSheet, type MenuItem } from "@/design/components";
import { screenDate, screenTime } from "@/domain/core/format";
import { scoreStatus, statusColorVar } from "@/domain/core/status";
import { comparePath, comparePickerGroups } from "./compare";
import { brewAgainPath } from "./logState";
import l from "./log.module.css";

export function brewAgain(b: Pick<BrewRow, "recipeId" | "uid">): void {
  nav.push(brewAgainPath(b));
}

export function toggleFavourite(b: BrewRow): void {
  if (b.id != null) void repo.toggleFavourite(b.id);
}

// ---------------------------------------------------------------- delete with undo

interface Removed {
  readonly brew: BrewRow;
  readonly analyses: AnalysisRow[];
  readonly photos: PhotoRow[];
  readonly blobs: PhotoBlobRow[];
}

/** Everything repo.deleteBrew removes, read first so Undo can put it back with the same ids. */
async function removeBrew(id: number): Promise<Removed | null> {
  let out: Removed | null = null;
  await db.transaction("rw", [db.brew_logs, db.analyses, db.photos, db.photo_blobs], async () => {
    const brew = await db.brew_logs.get(id);
    if (!brew) return;
    const analyses = await db.analyses.where("brewLogId").equals(id).toArray();
    const photos = await db.photos.where("brewLogId").equals(id).toArray();
    const blobs = (await db.photo_blobs.bulkGet(photos.map((p) => p.uid))).filter((b): b is PhotoBlobRow => b != null);
    out = { brew, analyses, photos, blobs };
    await repo.deleteBrew(id);
  });
  return out;
}

async function restore(r: Removed): Promise<void> {
  await db.transaction("rw", [db.brew_logs, db.analyses, db.photos, db.photo_blobs], async () => {
    await db.brew_logs.put(r.brew);
    if (r.analyses.length) await db.analyses.bulkPut(r.analyses);
    if (r.photos.length) await db.photos.bulkPut(r.photos);
    if (r.blobs.length) await db.photo_blobs.bulkPut(r.blobs);
  });
}

/** True when the route on top shows this brew (its detail or a compare from it). */
function isShowing(uid: string): boolean {
  const top = router.get().top;
  return (top.kind === "brewDetail" && top.brewUid === uid) || (top.kind === "compare" && (top.brewUid === uid || top.otherUid === uid));
}

/** `ask(brew)` opens "Delete this brew?"; render `element` once. */
export function useDeleteBrew(): { ask: (b: BrewRow) => void; element: ReactNode } {
  const [target, setTarget] = useState<BrewRow | null>(null);
  const ask = useCallback((b: BrewRow) => setTarget(b), []);
  const element = target ? (
    <ConfirmSheet
      title="Delete this brew?"
      body="It will not be in the log or in anything you export after this. Any analysis of it and its photos go too."
      confirmText="Delete"
      danger
      safe="cancel"
      onClose={() => setTarget(null)}
      onConfirm={() => {
        const b = target;
        if (b.id == null) return;
        // Leave a detail that shows it first, so nothing renders a missing brew.
        if (isShowing(b.uid)) nav.back();
        void removeBrew(b.id).then((removed) => {
          if (!removed) return;
          showToast({ text: "Brew deleted", action: { label: "Undo", run: () => void restore(removed) } });
        });
      }}
    />
  ) : null;
  return { ask, element };
}

// ---------------------------------------------------------------- compare picker

/** `open(brew, anchor)` opens the "Compare with…" picker; render `element` once. */
export function useComparePicker(): { open: (b: BrewRow, anchor?: HTMLElement | null) => void; element: ReactNode } {
  const [state, setState] = useState<{ base: BrewRow; anchor: HTMLElement | null } | null>(null);
  const open = useCallback((base: BrewRow, anchor?: HTMLElement | null) => setState({ base, anchor: anchor ?? null }), []);
  const element = state ? <ComparePicker base={state.base} anchor={state.anchor} onClose={() => setState(null)} /> : null;
  return { open, element };
}

function ComparePicker({ base, anchor, onClose }: { base: BrewRow; anchor: HTMLElement | null; onClose: () => void }) {
  const brews = useStore((s) => s.brews);
  const groups = useMemo(() => comparePickerGroups(base, brews), [base, brews]);
  const paneMode = usePaneMode();
  const select = useSelectInPane("log");
  const anchorRef = useMemo(() => ({ current: anchor }), [anchor]);
  const go = (other: BrewRow) => {
    const path = comparePath(base.uid, other.uid);
    onClose();
    // In a pane the selection rule applies (Back returns to the default); pushed elsewhere, so
    // Back from the comparison returns to the brew it started from.
    if (paneMode) select(path);
    else nav.push(path);
  };
  return (
    <CoffeeSheet role="picker" anchor={anchor ? anchorRef : undefined} label="Compare with" onClose={onClose}>
      <div className={l.picker}>
        <h2 className="t-headline-s">Compare with…</h2>
        <p className="t-body-s ink-mid">
          {base.recipeName.trim() || "Brew"}, {screenDate(base.startedAt)} {screenTime(base.startedAt)}
        </p>
        {groups.length === 0 && <p className="t-body-m ink-mid">There's no other brew to compare with yet.</p>}
        {groups.map((g) => (
          <section key={g.title} aria-label={g.title}>
            <h3 className={`micro ink-low ${l.pickerHead}`}>{g.title.toUpperCase()}</h3>
            <ul className={l.pickerList}>
              {g.brews.map((b) => (
                <li key={b.uid}>
                  <button type="button" className={l.pickerRow} onClick={() => go(b)}>
                    <span className={l.pickerMain}>
                      <span className="t-title-s">{b.recipeName.trim() || "Brew"}</span>
                      <span className="t-body-s ink-mid">
                        {screenDate(b.startedAt)} · {screenTime(b.startedAt)}
                        {b.beanLabel.trim() ? ` · ${b.beanLabel.trim()}` : ""}
                      </span>
                    </span>
                    <span className={l.pickerScore} style={{ color: b.overall > 0 ? statusColorVar(scoreStatus(b.overall)) : "var(--ink-low)" }}>
                      {b.overall > 0 ? `${b.overall}/10` : "Unrated"}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </CoffeeSheet>
  );
}

// ---------------------------------------------------------------- menu

/** The row's context menu items (§3.7): Brew again, Compare with…, Favourite, Delete. */
export function brewMenuItems(b: BrewRow, act: { compare: (b: BrewRow) => void; del: (b: BrewRow) => void }): MenuItem[] {
  return [
    { label: "Brew again", icon: "Play", keys: "b", onSelect: () => brewAgain(b) },
    { label: "Compare with…", icon: "History", onSelect: () => act.compare(b) },
    { label: b.isFavourite ? "Remove from favourites" : "Favourite", icon: "Star", onSelect: () => toggleFavourite(b) },
    { label: "Delete", icon: "Delete", keys: "delete", danger: true, onSelect: () => act.del(b) },
  ];
}

/** The brew-detail path, for selection. */
export function brewPath(uid: string): string {
  return pathOf({ kind: "brewDetail", brewUid: uid });
}
