/**
 * Port of ReviewScreen.kt's FirstReadSection: the offline first read under Review, from
 * BrewReport.quickRead. Nothing leaves the browser; the copy points at the real read when an AI
 * service is set up.
 */
import { SectionLabel, SoftCard, HairLine } from "@/design/components";
import { useStore } from "@/data/store";
import { quickRead } from "@/domain/core/quickRead";
import { useAiSettings } from "./aiSettings";
import s from "./ai.module.css";

export function FirstReadSection({ brewId }: { brewId: number }) {
  const log = useStore((st) => st.brews.find((b) => b.id === brewId) ?? null, undefined, brewId);
  const recipe = useStore((st) => (log ? (st.recipes.find((r) => r.id === log.recipeId) ?? null) : null), undefined, log?.recipeId ?? "");
  const ai = useAiSettings();
  if (!log) return null;
  const lines = quickRead(log, recipe);
  if (lines.length === 0) return null;
  return (
    <section aria-label="First read, offline">
      <SectionLabel text="FIRST READ · OFFLINE" />
      <SoftCard padding="10px 18px">
        {lines.map((line, i) => (
          <div key={i}>
            {i > 0 && <HairLine />}
            <p className={s.readLine}>{line}</p>
          </div>
        ))}
        <p className={s.body12} style={{ marginTop: 4, marginBottom: 8 }}>
          {ai.ready ? "Offline pattern matching, not a verdict. Save & analyse for a real read." : "Offline pattern matching, not a verdict. Share it, or set up analysis in Gear › App."}
        </p>
      </SoftCard>
    </section>
  );
}
