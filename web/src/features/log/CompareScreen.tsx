/**
 * Compare two brews (v4 §3.8), /log/:uid/compare/:other: a two-column table, left the brew it
 * started from, right the other, each changed row with a word saying how the right one differs
 * ("finer by 5 clicks", "+0:20", "2 °C hotter", "+1"). Pushed on compact (the columns stay two,
 * narrow), in Log's detail pane from expanded up. "Change" reopens the picker.
 */
import { useMemo, useRef } from "react";
import { usePane } from "@/app/panes";
import { nav } from "@/app/router";
import { Brewers } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import type { BrewRow } from "@/data/schema";
import { useBrew, useStore } from "@/data/store";
import { DetailTopBar, EyebrowLabel, IconWell, PillButton, QuietPanel } from "@/design/components";
import { methodLabels } from "@/domain/core/brewer";
import { totalSec } from "@/domain/core/recipe";
import { scoreStatus, statusColorVar } from "@/domain/core/status";
import { unitsOf } from "@/domain/core/units";
import { compareRows } from "./compare";
import { brewPath, useComparePicker } from "./logActions";
import { detailWhen } from "./logState";
import l from "./log.module.css";

export function CompareScreen({ brewUid, otherUid }: { brewUid: string; otherUid: string }) {
  useCatalog(["brewers", "grinders"]);
  const pane = usePane();
  const a = useBrew(brewUid);
  const b = useBrew(otherUid);
  const bar = pane.inPane ? (
    <div className={l.paneBar}>
      <IconWell icon="Close" label="Close" keys="escape" onClick={pane.close} size={44} />
    </div>
  ) : (
    <DetailTopBar onBack={nav.back} />
  );
  if (!a || !b) {
    return (
      <div className={l.page}>
        {bar}
        <h1 className="t-headline-m" tabIndex={-1}>
          Compare brews
        </h1>
        <div style={{ height: 12 }} />
        <QuietPanel text={!a && !b ? "Neither brew is in the log any more." : "One of these brews is no longer in the log."} />
      </div>
    );
  }
  return <Compare a={a} b={b} bar={bar} />;
}

function Compare({ a, b, bar }: { a: BrewRow; b: BrewRow; bar: React.ReactNode }) {
  const recipes = useStore((s) => s.recipes);
  const grinders = useStore((s) => s.grinders);
  const units = unitsOf(useStore((s) => s.settings));
  const picker = useComparePicker();
  const change = useRef<HTMLDivElement>(null);
  const rows = useMemo(() => {
    const plan = (x: BrewRow) => {
      const r = recipes.find((r) => r.id === x.recipeId);
      return r ? totalSec(r) : null;
    };
    const grinder = a.grinderId != null ? grinders.find((g) => g.id === a.grinderId) : undefined;
    const brewer = Brewers.byId(a.brewerId) ?? Brewers.resolveOrNull(a.brewer);
    const water = methodLabels(brewer?.method ?? "pourover").water;
    return compareRows(a, b, {
      units,
      planA: plan(a),
      planB: plan(b),
      lowerIsFiner: grinder?.lowerIsFiner ?? true,
      waterLabel: water.charAt(0) + water.slice(1).toLowerCase(),
      dateOf: detailWhen,
    });
  }, [a, b, recipes, grinders, units]);
  const changed = rows.filter((r) => r.word != null).length;
  const headOf = (x: BrewRow) => (
    <span className={l.cmpHead}>
      <a
        href={brewPath(x.uid)}
        className={`t-title-s ${l.cmpLink}`}
        onClick={(e) => {
          e.preventDefault();
          nav.push(brewPath(x.uid));
        }}
      >
        {x.recipeName.trim() || "Brew"}
      </a>
      <span className="t-body-s ink-mid">{detailWhen(x.startedAt)}</span>
      <span className={`num-inline ${l.cmpScore}`} style={{ color: x.overall > 0 ? statusColorVar(scoreStatus(x.overall)) : "var(--ink-low)" }}>
        {x.overall > 0 ? `${x.overall}/10` : "Unrated"}
      </span>
    </span>
  );
  return (
    <div className={l.page}>
      {bar}
      <div className={l.cmpTitle}>
        <div>
          <EyebrowLabel text={changed === 0 ? "NO DIFFERENCES" : `${changed} ${changed === 1 ? "DIFFERENCE" : "DIFFERENCES"}`} />
          <h1 className="t-headline-m" tabIndex={-1}>
            Compare brews
          </h1>
        </div>
        <div ref={change}>
          <PillButton text="Change" icon="History" onClick={() => picker.open(a, change.current?.querySelector("button") ?? null)} />
        </div>
      </div>
      <div className={l.cmpScroll}>
        <table className={l.cmpTable}>
          <caption className="visually-hidden">
            {a.recipeName.trim() || "Brew"} compared with {b.recipeName.trim() || "Brew"}. The right column says how the second brew differs.
          </caption>
          <thead>
            <tr>
              <td />
              <th scope="col">{headOf(a)}</th>
              <th scope="col">{headOf(b)}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.label} data-changed={r.word != null || undefined}>
                <th scope="row" className="micro ink-low">
                  {r.label.toUpperCase()}
                </th>
                <td className={r.long ? l.cmpLong : undefined}>{r.left}</td>
                <td className={r.long ? l.cmpLong : undefined}>
                  {r.right}
                  {r.word && <span className={`t-label-m ${l.cmpWord}`}>{r.word}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {picker.element}
    </div>
  );
}
