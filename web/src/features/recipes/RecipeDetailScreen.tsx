/**
 * Port of RecipeDetailScreen.kt (web-spec §11 Recipe detail), adaptive in v4 (§3.3).
 *
 * Pushed (compact, medium, or opened from Today): the header is the shared element (it flies in
 * from the tapped card); everything below waits behind a fixed-height placeholder until the
 * flight lands (§6.4), then fades up, and "Brew this" is pinned to the bottom.
 *
 * In Recipes' detail pane (expanded and up, `usePane().inPane`): no Back, no flight, no fade of its
 * own (the pane swap fades); a header row with the brewer art, the name and "Brew this". From a
 * pane (or a layer) 880 wide the page goes two columns: the numbers on the left, sticky, the method
 * on the right. Width decisions are container queries; JS only knows "in a pane or not".
 */
import { m } from "motion/react";
import { useMemo, useState } from "react";
import { usePane, useSelectedPath } from "@/app/panes";
import { nav } from "@/app/router";
import type { SharedFrom } from "@/app/routes";
import { useTransitionSettled } from "@/app/transitionState";
import { atLeast, useWindowClass } from "@/app/windowClass";
import { BrewerIcon } from "@/art";
import { catalog } from "@/catalog/catalog";
import { repo } from "@/data/repo";
import { useBrews, useGrinders, useRecipe, useSettings } from "@/data/store";
import {
  ActionRow,
  DetailTopBar,
  EyebrowLabel,
  HairLine,
  IconWell,
  NumberWithUnit,
  PillButton,
  PrimaryButton,
  QuietButton,
  SectionLabel,
  SoftCard,
  StatusChip,
  StatusLine,
  Term,
  groupPosition,
  toCaps,
  tooltipProps,
} from "@/design/components";
import { delayed, effectsDefault, spatialDefault } from "@/design/motion";
import { methodLabels } from "@/domain/core/brewer";
import { formatDuration, grams, ratioPhrase } from "@/domain/core/format";
import { grindReference } from "@/domain/core/glossary";
import { noScaleAmounts, noScalePrep, servingsPreset } from "@/domain/core/noScale";
import { fidelityWord, scaledToDose, totalSec, type Recipe } from "@/domain/core/recipe";
import { scoreStatus } from "@/domain/core/status";
import { renderTemps, temp, tempUnit, unitsOf } from "@/domain/core/units";
import { GrindRangeCard, useGrindScale } from "@/features/grind";
import { brewRecipe } from "@/features/today/brewNext";
import { ConfirmSheet, JourneyLine, RecipeHeader, RecipeRoute, TimelineCard, journeyNodes, plannedTimelineRows } from "@/viz";
import { needItems } from "./needs";
import { StatGrid } from "./StatGrid";
import r from "./recipes.module.css";

const MINUS = "M240-440q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h480q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H240Z";
const PLUS = "M440-440H240q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h200v-200q0-17 11.5-28.5T480-760q17 0 28.5 11.5T520-720v200h200q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H520v200q0 17-11.5 28.5T480-200q-17 0-28.5-11.5T440-240v-200Z";

const FIDELITY_TERMS = new Set(["published", "adapted", "community"]);

export function RecipeDetailScreen({ id, from }: { id: string; from: SharedFrom }) {
  const recipe = useRecipe(id);
  const pane = usePane();
  if (!recipe) {
    return (
      <div className="column">
        {!pane.inPane && <DetailTopBar onBack={nav.back} />}
        <div className={r.gone}>
          <h1 className="t-headline-s">That recipe is gone.</h1>
          <QuietButton text="Back to recipes" onClick={pane.close} />
        </div>
      </div>
    );
  }
  // Keyed by id: the default pane detail can change recipe, and "Make more or less" must not carry over.
  return <RecipeDetail key={recipe.id} recipe={recipe} from={from} />;
}

/** Recipe amounts as chosen in "Make more or less": a dose in grams, or a number of mugs. */
type Amount = { readonly dose: number } | { readonly mugs: number };

function RecipeDetail({ recipe, from }: { recipe: Recipe; from: SharedFrom }) {
  const settled = useTransitionSettled();
  const { inPane, close } = usePane();
  const selected = useSelectedPath("recipes");
  const large = atLeast(useWindowClass().width, "large");
  const [amount, setAmount] = useState<Amount>({ dose: recipe.doseG });
  const shown = useMemo(() => ("mugs" in amount ? servingsPreset(recipe, amount.mugs) : amount.dose === recipe.doseG ? recipe : scaledToDose(recipe, amount.dose)), [recipe, amount]);
  const brew = () => brewRecipe(recipe.id);
  const cta = <PrimaryButton text="Brew this" icon="Play" className={r.inverse} onClick={brew} />;
  const ready = settled || inPane;
  // The pane swap already fades a pane detail in; only a pushed one fades up after the flight.
  const body = <Body recipe={recipe} shown={shown} amount={amount} setAmount={setAmount} onDeleted={close} fade={!inPane} />;

  return (
    <div className={`column ${r.detail}`} data-in-pane={inPane || undefined}>
      {!inPane ? (
        <DetailTopBar onBack={nav.back} />
      ) : (
        // A selection in the pane gets a quiet close at large and up; Escape does the same.
        selected != null &&
        large && (
          <div className={r.paneTop}>
            <IconWell icon="Close" label="Close recipe" keys="escape" onClick={close} />
          </div>
        )
      )}
      <div className={r.layout}>
        <div className={r.head}>
          {inPane ? <PaneTitle recipe={recipe} /> : <RecipeHeader recipe={recipe} size="detail" sharedPrefix={from} headingLevel={1} />}
          <div className={r.headCta}>{cta}</div>
        </div>
        {ready ? (
          body
        ) : (
          // Fixed height until the flight lands: nothing reflows when the body arrives.
          <div className={r.placeholder} aria-hidden="true" />
        )}
      </div>
      {/* Inverse, not crema: crema is kept for things that are live, and nothing is yet. */}
      {!inPane && <div className={r.sticky}>{cta}</div>}
    </div>
  );
}

/** The pane's header row: the brewer drawn large, who and which brewer, the name (h1). */
function PaneTitle({ recipe }: { recipe: Recipe }) {
  return (
    <div className={r.paneTitle}>
      <span className={r.paneArt}>
        <BrewerIcon brewer={recipe.brewer} size={44} decorative />
      </span>
      <div className={r.paneTitleText}>
        <span className="micro ink-low">
          {toCaps(recipe.author)} · {toCaps(recipe.brewer.label)}
        </span>
        <h1 className="t-headline-l">{recipe.name}</h1>
      </div>
    </div>
  );
}

/** "6 level tablespoons → 500 ml (2 mugs)", grams small (v4 §4.3). */
function NoScaleRoute({ recipe }: { recipe: Recipe }) {
  const a = noScaleAmounts(recipe.doseG, recipe.waterG, recipe.brewer.method);
  const labels = methodLabels(recipe.brewer.method);
  return (
    <div className={r.noScale}>
      <div className={r.noScaleSide}>
        <EyebrowLabel text="COFFEE" />
        {a.coffeeTbsp != null ? <NumberWithUnit value={a.coffee.split(" ")[0]!} unit="level tbsp" numeral="num-large" /> : <span className="t-title-m">{a.basket}</span>}
        <span className="t-body-s ink-low">({grams(recipe.doseG)} g)</span>
      </div>
      <span className={`ink-low ${r.noScaleArrow}`} aria-hidden="true">
        →
      </span>
      <div className={r.noScaleSide}>
        <EyebrowLabel text={labels.water} />
        <NumberWithUnit value={String(a.waterMl)} unit="ml" numeral="num-large" />
        <span className="t-body-s ink-low">
          {a.mugs != null ? `${a.water.slice(a.water.indexOf("(") + 1, -1)} · ` : ""}({grams(recipe.waterG)} g)
        </span>
      </div>
    </div>
  );
}

const FADE_FROM = { opacity: 0, transform: "translateY(12px)" };
const FADE_TO = { opacity: 1, transform: "translateY(0px)" };
const FADE_TIMING = { opacity: delayed(effectsDefault, 0.08), transform: delayed(spatialDefault, 0.08) };

function Body({ recipe, shown, amount, setAmount, onDeleted, fade }: { recipe: Recipe; shown: Recipe; amount: Amount; setAmount: (a: Amount) => void; onDeleted: () => void; fade: boolean }) {
  const settings = useSettings();
  const units = unitsOf(settings);
  const unit = units.fahrenheit ? "f" : "c";
  const brews = useBrews();
  const grinders = useGrinders();
  const [confirm, setConfirm] = useState<"reset" | "delete" | null>(null);
  const method = recipe.brewer.method;
  const labels = methodLabels(method);
  // Temperatures in prose follow the person's unit (v4 §4.15), steps included.
  const forUnits = useMemo(() => (unit === "c" ? shown : { ...shown, steps: shown.steps.map((s) => ({ ...s, detail: renderTemps(s.detail, unit) })) }), [shown, unit]);
  const logs = useMemo(() => brews.filter((l) => l.recipeId === recipe.id), [brews, recipe.id]);
  // Highest rated; on a tie the newest wins, since its grind is the one to trust today.
  const best = useMemo(() => logs.reduce<(typeof logs)[number] | null>((acc, l) => (l.overall > 0 && (acc == null || l.overall > acc.overall) ? l : acc), null), [logs]);
  const nodes = useMemo(() => journeyNodes(shown), [shown]);
  const timeline = useMemo(() => plannedTimelineRows(forUnits), [forUnits]);
  const grinder = grinders.find((g) => g.id === settings.defaultGrinderId) ?? grinders.find((g) => g.isDefault) ?? null;
  const grindScale = useGrindScale(grinder);
  const needs = useMemo(() => needItems(shown, settings, grinders.length), [shown, settings, grinders.length]);
  const prep = useMemo(() => (settings.hasScale ? recipe.prep.map((l) => renderTemps(l, unit)) : noScalePrep(shown, units)), [settings.hasScale, recipe.prep, shown, units, unit]);
  const builtIn = catalog.recipe(recipe.id) != null;
  const edited = builtIn && !recipe.isBuiltIn;
  const total = totalSec(shown);
  const isNew = settings.experience === "new";
  // Said once: a grind that already reads "…, like flour" needs no reference under it.
  const reference = /\blike\b/i.test(recipe.grind) ? null : grindReference(recipe.grind);
  const servings = method !== "espresso" && method !== "moka";
  const mugs = "mugs" in amount ? amount.mugs : null;
  const stepDose = (by: number) => setAmount({ dose: Math.max(1, Math.round(shown.doseG) + by) });
  const actions: { key: string; title: string; icon: "Edit" | "Add" | "Replay" | "Delete"; danger?: boolean; run: () => void }[] = [
    { key: "edit", title: "Edit", icon: "Edit", run: () => nav.push(`/recipes/${encodeURIComponent(recipe.id)}/edit`) },
    { key: "dup", title: "Duplicate", icon: "Add", run: () => nav.push(`/recipes/${encodeURIComponent(recipe.id)}/duplicate`) },
    ...(edited ? [{ key: "reset", title: "Reset to original", icon: "Replay" as const, run: () => setConfirm("reset") }] : []),
    ...(!builtIn ? [{ key: "delete", title: "Delete recipe", icon: "Delete" as const, danger: true, run: () => setConfirm("delete") }] : []),
  ];
  const noScale = !settings.hasScale ? noScaleAmounts(shown.doseG, shown.waterG, method) : null;

  return (
    <>
      {/* Two motion boxes, not one wrapper: side and main are the two-column grid's own items. */}
      <m.aside className={r.side} aria-label="The numbers" initial={fade ? FADE_FROM : false} animate={FADE_TO} transition={FADE_TIMING}>
        <div className={r.numbers}>
          {settings.hasScale ? <RecipeRoute recipe={shown} numeral="num-large" /> : <NoScaleRoute recipe={shown} />}
          {/* Said once per surface, in words (v4 §4.5). */}
          <p className={`t-body-s ink-mid ${r.ratioLine}`}>
            <Term id="ratio" hint={isNew}>
              {ratioPhrase(shown.doseG, shown.waterG, method)}
            </Term>
          </p>
          <StatGrid
            label="Temperature, time and grind"
            cells={[
              { key: "temp", label: "TEMP", value: temp(units, recipe.tempC), unit: tempUnit(units) },
              { key: "time", label: method === "espresso" ? <Term id="shot-time"><span className="eyebrow">{labels.time}</span></Term> : labels.time, value: formatDuration(total) },
              {
                key: "grind",
                label: (
                  <Term id="grind">
                    <span className="eyebrow">GRIND</span>
                  </Term>
                ),
                value: recipe.grind || null,
                text: true,
                sub: reference,
              },
            ]}
          />
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

        <SectionLabel text="MAKE MORE OR LESS" />
        <SoftCard>
          <div className={r.scaler}>
            <button type="button" className={r.step} aria-label="One gram less coffee" {...tooltipProps("One gram less coffee")} onClick={() => stepDose(-1)}>
              <svg width={20} height={20} viewBox="0 -960 960 960" aria-hidden="true" focusable="false">
                <path d={MINUS} fill="currentColor" />
              </svg>
            </button>
            <span className={r.scalerMid} aria-live="polite">
              {noScale && noScale.coffeeTbsp != null ? (
                <>
                  <span className="t-title-m">{noScale.coffee}</span>
                  <span className="t-body-s ink-mid">
                    → {noScale.water} <span className="ink-low">({grams(shown.doseG)} g → {grams(shown.waterG)} g)</span>
                  </span>
                </>
              ) : (
                <>
                  <span className="num-medium">
                    {grams(shown.doseG)}
                    <span className={`num-unit ${r.unit}`}> g coffee</span>
                  </span>
                  <span className="t-body-s ink-mid">→ {grams(shown.waterG)} g water, pours scaled to match</span>
                </>
              )}
            </span>
            <button type="button" className={r.step} aria-label="One gram more coffee" {...tooltipProps("One gram more coffee")} onClick={() => stepDose(1)}>
              <svg width={20} height={20} viewBox="0 -960 960 960" aria-hidden="true" focusable="false">
                <path d={PLUS} fill="currentColor" />
              </svg>
            </button>
          </div>
          {servings && (
            <div className={r.presets} role="group" aria-label="Mugs of coffee">
              {[1, 2, 3].map((n) => (
                <PillButton key={n} text={n === 1 ? "1 mug" : `${n} mugs`} selected={mugs === n} onClick={() => setAmount(mugs === n ? { dose: recipe.doseG } : { mugs: n })} />
              ))}
            </div>
          )}
          {shown !== recipe && <QuietButton text="Back to the recipe's amounts" onClick={() => setAmount({ dose: recipe.doseG })} className={r.quietLeft} />}
        </SoftCard>

        <SectionLabel text="WHAT YOU'LL NEED" />
        <SoftCard>
          <ul className={r.needs}>
            {needs.map((n) => (
              <li key={n.id}>
                <span className={r.rowText}>{n.text}</span>
                {n.note && <span className="t-body-s ink-mid">{n.note}</span>}
              </li>
            ))}
          </ul>
        </SoftCard>
      </m.aside>

      <m.div className={r.main} initial={fade ? FADE_FROM : false} animate={FADE_TO} transition={FADE_TIMING}>
        <div className={r.fidelity}>
          {FIDELITY_TERMS.has(recipe.fidelity) && recipe.isBuiltIn ? (
            <Term id={recipe.fidelity} chip>
              <span className="t-label-m">{fidelityWord(recipe.fidelity, recipe.isBuiltIn)}</span>
            </Term>
          ) : (
            <StatusChip status="neutral" text={fidelityWord(recipe.fidelity, recipe.isBuiltIn)} />
          )}
        </div>
        {recipe.blurb && <p className={`t-body-m ink-mid measure ${r.blurb}`}>{renderTemps(recipe.blurb, unit)}</p>}

        <SectionLabel text="THE ROUTE" />
        <SoftCard>
          <JourneyLine nodes={nodes} style="live" progress={0} startLabel="0:00" endLabel={formatDuration(total)} label={`Pours at ${nodes.map((n) => n.label).filter(Boolean).join(", ")}; ${formatDuration(total)} in all`} />
        </SoftCard>

        {/* Everything from here is below the first screen. It is laid out when it nears the
            viewport (content-visibility), so the frame the body arrives in stays short. */}
        <div className={r.below}>
          <SectionLabel text={`THE BREW · ${recipe.steps.length} ${recipe.steps.length === 1 ? "STEP" : "STEPS"}`} />
          {/* The whole instruction on every step: this page is where the method is learnt. */}
          <SoftCard>
            <TimelineCard rows={timeline} detailMaxLines={0} />
          </SoftCard>

          {prep.length > 0 && (
            <>
              <SectionLabel text="SET UP" />
              <SoftCard>
                <ol className={r.numbered}>
                  {prep.map((line, i) => (
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
                    <p className={`${r.rowText} ${r.tip}`}>{renderTemps(tip, unit)}</p>
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
            {recipe.sourceNote && <p className={`t-body-s ink-low ${r.sourceNote}`}>{renderTemps(recipe.sourceNote, unit)}</p>}
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
      </m.div>

      {confirm === "reset" && (
        <ConfirmSheet
          title="Reset to the original?"
          body="Your changes to this recipe go, and the published numbers come back."
          confirmText="Reset"
          safe="cancel"
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
          safe="cancel"
          onClose={() => setConfirm(null)}
          onConfirm={() => {
            onDeleted();
            void repo.deleteRecipe(recipe.id);
          }}
        />
      )}
    </>
  );
}
