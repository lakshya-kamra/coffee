/**
 * Port of RecipeDetailScreen.kt (web-spec §11 Recipe detail). The header is the shared element
 * (it flies in from the tapped card); everything below waits behind a fixed-height placeholder
 * until the flight lands (§6.4), then fades up. The recipe comes from the hot store, so the first
 * frame already has it, as Android seeds RecipeDetailViewModel synchronously.
 */
import { m } from "motion/react";
import { useMemo, useState } from "react";
import { prefetchers } from "@/app/registry";
import { nav } from "@/app/router";
import type { SharedFrom } from "@/app/routes";
import { useTransitionSettled } from "@/app/transitionState";
import { catalog } from "@/catalog/catalog";
import { repo } from "@/data/repo";
import { useBrews, useGrinders, useRecipe, useSettings } from "@/data/store";
import { ActionRow, DetailTopBar, EyebrowLabel, HairLine, PrimaryButton, QuietButton, SectionLabel, SoftCard, StatusChip, StatusLine, groupPosition } from "@/design/components";
import { delayed, effectsDefault, spatialDefault } from "@/design/motion";
import { clock, grams } from "@/domain/core/format";
import { fidelityWord, scaledToDose, totalSec, type Recipe } from "@/domain/core/recipe";
import { scoreStatus } from "@/domain/core/status";
import { temp, tempUnit, unitsOf } from "@/domain/core/units";
import { GrindRangeCard, useGrindScale } from "@/features/grind";
import { ConfirmSheet, InfoGrid, JourneyLine, RecipeHeader, RecipeRoute, TimelineCard, journeyNodes, plannedTimelineRows } from "@/viz";
import r from "./recipes.module.css";

const MINUS = "M240-440q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h480q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H240Z";
const PLUS = "M440-440H240q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h200v-200q0-17 11.5-28.5T480-760q17 0 28.5 11.5T520-720v200h200q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H520v200q0 17-11.5 28.5T480-200q-17 0-28.5-11.5T440-240v-200Z";

export function RecipeDetailScreen({ id, from }: { id: string; from: SharedFrom }) {
  const recipe = useRecipe(id);
  if (!recipe) {
    return (
      <div className="column">
        <DetailTopBar onBack={nav.back} />
        <div className={r.gone}>
          <h1 className="t-headline-s">That recipe is gone.</h1>
          <QuietButton text="Back to recipes" onClick={nav.back} />
        </div>
      </div>
    );
  }
  return <RecipeDetail recipe={recipe} from={from} />;
}

function RecipeDetail({ recipe, from }: { recipe: Recipe; from: SharedFrom }) {
  const settled = useTransitionSettled();
  return (
    <div className={`column ${r.detail}`}>
      <DetailTopBar onBack={nav.back} />
      <RecipeHeader recipe={recipe} size="detail" sharedPrefix={from} headingLevel={1} />
      {settled ? (
        <m.div
          initial={{ opacity: 0, transform: "translateY(12px)" }}
          animate={{ opacity: 1, transform: "translateY(0px)" }}
          transition={{ opacity: delayed(effectsDefault, 0.08), transform: delayed(spatialDefault, 0.08) }}
        >
          <Body recipe={recipe} />
        </m.div>
      ) : (
        // Fixed height until the flight lands: nothing reflows when the body arrives.
        <div className={r.placeholder} aria-hidden="true" />
      )}
      <div className={r.sticky}>
        {/* Inverse, not crema: crema is kept for things that are live, and nothing is yet. */}
        <PrimaryButton
          text="Brew this"
          icon="Play"
          className={r.inverse}
          onClick={() => {
            void prefetchers.brew().catch(() => undefined);
            nav.push(`/brew/${encodeURIComponent(recipe.id)}`);
          }}
        />
      </div>
    </div>
  );
}

function Body({ recipe }: { recipe: Recipe }) {
  const settings = useSettings();
  const units = unitsOf(settings);
  const brews = useBrews();
  const grinders = useGrinders();
  const [dose, setDose] = useState(recipe.doseG);
  const [confirm, setConfirm] = useState<"reset" | "delete" | null>(null);
  const shown = useMemo(() => (dose === recipe.doseG ? recipe : scaledToDose(recipe, dose)), [recipe, dose]);
  const logs = useMemo(() => brews.filter((l) => l.recipeId === recipe.id), [brews, recipe.id]);
  // Highest rated; on a tie the newest wins, since its grind is the one to trust today.
  const best = useMemo(() => logs.reduce<(typeof logs)[number] | null>((acc, l) => (l.overall > 0 && (acc == null || l.overall > acc.overall) ? l : acc), null), [logs]);
  const nodes = useMemo(() => journeyNodes(shown), [shown]);
  const timeline = useMemo(() => plannedTimelineRows(shown), [shown]);
  const grinder = grinders.find((g) => g.id === settings.defaultGrinderId) ?? grinders.find((g) => g.isDefault) ?? null;
  const grindScale = useGrindScale(grinder);
  const builtIn = catalog.recipe(recipe.id) != null;
  const edited = builtIn && !recipe.isBuiltIn;
  const total = totalSec(recipe);
  const actions: { key: string; title: string; icon: "Edit" | "Add" | "Replay" | "Delete"; danger?: boolean; run: () => void }[] = [
    { key: "edit", title: "Edit", icon: "Edit", run: () => nav.push(`/recipes/${encodeURIComponent(recipe.id)}/edit`) },
    { key: "dup", title: "Duplicate", icon: "Add", run: () => nav.push(`/recipes/${encodeURIComponent(recipe.id)}/duplicate`) },
    ...(edited ? [{ key: "reset", title: "Reset to original", icon: "Replay" as const, run: () => setConfirm("reset") }] : []),
    ...(!builtIn ? [{ key: "delete", title: "Delete recipe", icon: "Delete" as const, danger: true, run: () => setConfirm("delete") }] : []),
  ];

  return (
    <>
      <div className={r.headerFoot}>
        <RecipeRoute recipe={shown} numeral="num-large" />
      </div>

      {logs.length > 0 && (
        <div className={r.gap12}>
          {/* The coloured words are the verdict that earns the colour; the count is detail. */}
          <StatusLine
            status={scoreStatus(best?.overall ?? 0)}
            lead={best ? `Best ${best.overall}/10` : `Brewed ${logs.length}×`}
            rest={best ? `brewed ${logs.length}×${best.grindClicks != null && best.grindClicks > 0 ? ` · at ${best.grindClicks} clicks` : ""}` : "not rated yet"}
          />
        </div>
      )}

      <div className={`${r.gridCard} ${r.gap12}`}>
        <InfoGrid
          cells={[
            { label: "TEMP", value: temp(units, recipe.tempC), unit: tempUnit(units) },
            { label: "TIME", value: clock(total) },
            { label: "GRIND", value: recipe.grind || null, textValue: true },
          ]}
        />
      </div>

      <div className={r.fidelity}>
        <StatusChip status="neutral" text={fidelityWord(recipe.fidelity, recipe.isBuiltIn)} />
      </div>
      {recipe.blurb && <p className={`t-body-m ink-mid ${r.blurb}`}>{recipe.blurb}</p>}

      <SectionLabel text="MAKE MORE OR LESS" />
      <SoftCard>
        <div className={r.scaler}>
          <button type="button" className={r.step} aria-label="One gram less" onClick={() => setDose((d) => Math.max(1, Math.round(d) - 1))}>
            <svg width={20} height={20} viewBox="0 -960 960 960" aria-hidden="true" focusable="false">
              <path d={MINUS} fill="currentColor" />
            </svg>
          </button>
          <span className={r.scalerMid}>
            <span className="num-medium">
              {grams(shown.doseG)}
              <span className={`num-unit ${r.unit}`}> g coffee</span>
            </span>
            <span className="t-body-s ink-mid">
              → {grams(shown.waterG)} g water, pours scaled to match
            </span>
          </span>
          <button type="button" className={r.step} aria-label="One gram more" onClick={() => setDose((d) => Math.round(d) + 1)}>
            <svg width={20} height={20} viewBox="0 -960 960 960" aria-hidden="true" focusable="false">
              <path d={PLUS} fill="currentColor" />
            </svg>
          </button>
        </div>
        {dose !== recipe.doseG && <QuietButton text="Back to the recipe's dose" onClick={() => setDose(recipe.doseG)} className={r.quietLeft} />}
      </SoftCard>

      <SectionLabel text="THE ROUTE" />
      <SoftCard>
        <JourneyLine nodes={nodes} style="live" progress={0} startLabel="0:00" endLabel={clock(total)} label={`Pours at ${nodes.map((n) => n.label).filter(Boolean).join(", ")}; ${clock(total)} in all`} />
      </SoftCard>

      {/* Everything from here is below the first screen. It is laid out when it nears the
          viewport (content-visibility), so the frame the body arrives in stays short. */}
      <div className={r.below}>
      <SectionLabel text={`THE BREW · ${recipe.steps.length} ${recipe.steps.length === 1 ? "STEP" : "STEPS"}`} />
      {/* The whole instruction on every step: this page is where the method is learnt. */}
      <SoftCard>
        <TimelineCard rows={timeline} detailMaxLines={0} />
      </SoftCard>

      {recipe.prep.length > 0 && (
        <>
          <SectionLabel text="SET UP" />
          <SoftCard>
            <ol className={r.numbered}>
              {recipe.prep.map((line, i) => (
                <li key={i}>
                  <span className="num-inline ink-low">{i + 1}</span>
                  <span className={r.rowText}>{line}</span>
                </li>
              ))}
            </ol>
          </SoftCard>
        </>
      )}

      {recipe.tips.length > 0 && (
        <>
          <SectionLabel text="WORTH KNOWING" />
          <SoftCard>
            {recipe.tips.map((tip, i) => (
              <div key={i}>
                {i > 0 && <HairLine />}
                <p className={`${r.rowText} ${r.tip}`}>{tip}</p>
              </div>
            ))}
          </SoftCard>
        </>
      )}

      {grindScale && (
        <>
          <SectionLabel text="ON YOUR GRINDER" />
          <GrindRangeCard scale={grindScale} method={recipe.brewer.method} />
        </>
      )}

      {/* Attribution honesty, deliberately not buried. */}
      <div className={r.source}>
        <EyebrowLabel text="SOURCE" />
        {recipe.sourceNote && <p className={`t-body-s ink-low ${r.sourceNote}`}>{recipe.sourceNote}</p>}
        {recipe.sources.length > 0 && (
          <ul className={r.sources}>
            {recipe.sources.map((s, i) => (
              <li key={i} className="t-body-s">
                {/^https?:\/\//.test(s.url) ? (
                  <a href={s.url} target="_blank" rel="noopener noreferrer" className={r.link}>
                    {s.who}
                  </a>
                ) : (
                  <span className="ink-mid">{s.who}</span>
                )}
                {s.note && <span className="ink-low"> · {s.note}</span>}
              </li>
            ))}
          </ul>
        )}
        {recipe.basedOn && <p className="t-body-s ink-low">Based on {catalog.recipe(recipe.basedOn)?.name ?? recipe.basedOn}.</p>}
      </div>

      <SectionLabel text="THIS RECIPE" />
      <div>
        {actions.map((a, i) => (
          <ActionRow key={a.key} position={groupPosition(i, actions.length)} icon={a.icon} title={a.title} danger={a.danger} onClick={a.run} />
        ))}
      </div>
      </div>

      {confirm === "reset" && (
        <ConfirmSheet
          title="Reset to the original?"
          body="Your changes to this recipe go, and the published numbers come back."
          confirmText="Reset"
          onClose={() => setConfirm(null)}
          onConfirm={() => void repo.resetRecipe(recipe.id)}
        />
      )}
      {confirm === "delete" && (
        <ConfirmSheet
          title="Delete this recipe?"
          body="It can't be undone. Brews you made with it stay in your log."
          confirmText="Delete"
          danger
          onClose={() => setConfirm(null)}
          onConfirm={() => {
            nav.back();
            void repo.deleteRecipe(recipe.id);
          }}
        />
      )}
    </>
  );
}
