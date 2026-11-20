/**
 * Recipe builder (A§7.7, web-spec §11): New / Edit / Duplicate. The draft keeps typed numbers as
 * text; the preview and the validation read a deferred copy, so typing stays urgent (rule 12).
 * Start/end seconds are derived from step durations on save. Built-ins edited here are saved as
 * an override under the same id ("Reset to original" brings the published numbers back).
 */
import { memo, useDeferredValue, useLayoutEffect, useMemo, useRef, useState } from "react";
import { nav, useRouter } from "@/app/router";
import { useShortcuts, type Binding } from "@/app/shortcuts";
import { atLeast, useWindowClass } from "@/app/windowClass";
import { Brewers, catalog } from "@/catalog/catalog";
import { retryCatalog, useCatalog, useCatalogFailed } from "@/catalog/useCatalog";
import {
  CoffeeSheet,
  ConfirmSheet,
  DetailTopBar,
  EyebrowLabel,
  FieldWell,
  Icon,
  PillButton,
  PrimaryButton,
  QuietButton,
  QuietPanel,
  SectionLabel,
  SegmentedControl,
  SelectableChip,
  SoftCard,
  StatusLine,
  StickyCtaBar,
} from "@/design/components";
import { repo } from "@/data/repo";
import { useRecipe, useRecipes, useSettings, useStore } from "@/data/store";
import { clock, ratio } from "@/domain/core/format";
import { encodeRecipe, type Recipe, type RecipeLevel, type RecipeNeed } from "@/domain/core/recipe";
import { tempUnit, temp as tempText, unitsOf } from "@/domain/core/units";
import {
  addStep,
  blankDraft,
  check,
  draftFromRecipe,
  duplicateDraft,
  hasBlocking,
  moveStep,
  nudgeDuration,
  overrideNote,
  parseAmount,
  POUR_KINDS,
  pourDelta,
  recipeFromDraft,
  removeStep,
  setLastPour,
  STEP_KINDS,
  totalDurationSec,
  updateStep,
  type DraftStep,
  type EditorMode,
  type Issue,
  type RecipeDraft,
} from "@/domain/editor";
import { brewerTitle, docFor } from "@/domain/search";
import { CatalogPickerSheet, closeEnclosingSheet } from "@/features/catalog-picker";
import { NumberStepper } from "@/features/grind";
import { JourneyLine, journeyNodes, RecipeRoute } from "@/viz";
import s from "./editor.module.css";

const resolveBrewer = (id: string) => Brewers.byId(id) ?? Brewers.resolve(id);

type Dispatch = (f: (d: RecipeDraft) => RecipeDraft) => void;

// v4 §6.4: level and needs feed the first-brew pick and "What you'll need".
const LEVELS: readonly { id: RecipeLevel; label: string }[] = [
  { id: "", label: "Not set" },
  { id: "easy", label: "Easy" },
  { id: "medium", label: "Medium" },
  { id: "advanced", label: "Advanced" },
];
const NEEDS: readonly { id: RecipeNeed; label: string }[] = [
  { id: "scale", label: "Scale" },
  { id: "gooseneck", label: "Gooseneck kettle" },
  { id: "grinder", label: "Grinder" },
  { id: "thermometer", label: "Thermometer" },
];

const draftJson = (d: RecipeDraft | null) => (d ? JSON.stringify(d) : "");

// ---------------------------------------------------------------- duration stepper (tap ±5 s, hold ±1 s)

function HoldButton({ label, text, onStep }: { label: string; text: string; onStep: (by: number) => void }) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const held = useRef(false);
  const stop = () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  };
  const sign = text === "−" ? -1 : 1;
  return (
    <button
      type="button"
      className="t-headline-s"
      style={{ width: 44, height: 44, borderRadius: "var(--r-well)", background: "var(--surface2)", touchAction: "manipulation", userSelect: "none" }}
      aria-label={`${label} (hold for 1 second steps)`}
      onPointerDown={() => {
        held.current = false;
        stop();
        // Held past 400 ms: 1 s steps every 150 ms until released (A§7.7 "long press ±1 s").
        const repeat = () => {
          held.current = true;
          onStep(sign);
          timer.current = setTimeout(repeat, 150);
        };
        timer.current = setTimeout(repeat, 400);
      }}
      onPointerUp={stop}
      onPointerLeave={stop}
      onPointerCancel={stop}
      onClick={() => {
        if (held.current) held.current = false;
        else onStep(sign * 5);
      }}
    >
      {text}
    </button>
  );
}

// ---------------------------------------------------------------- one step

const StepCard = memo(function StepCard({ step, index, count, delta, issues, dispatch }: { step: DraftStep; index: number; count: number; delta: number | null; issues: readonly Issue[]; dispatch: Dispatch }) {
  const pour = POUR_KINDS.has(step.kind) || step.pourTo.trim() !== "";
  const set = (patch: Partial<Omit<DraftStep, "key">>) => dispatch((d) => updateStep(d, index, patch));
  return (
    <SoftCard>
      <div className={s.step} role="group" aria-label={`Step ${index + 1}`}>
        <div className={s.stepHead}>
          <EyebrowLabel text={`Step ${index + 1}`} />
          <span className="t-body-s ink-mid">{clock(step.durationSec)}</span>
        </div>
        <div className={s.kinds} role="group" aria-label="Kind of step">
          {STEP_KINDS.map((k) => (
            <SelectableChip key={k} text={k} selected={step.kind === k} onClick={() => set({ kind: k, title: step.title === step.kind || step.title === "" ? k : step.title })} />
          ))}
        </div>
        <FieldWell label="Title" value={step.title} placeholder={step.kind} maxLength={60} onChange={(v) => set({ title: v })} />
        <FieldWell label="What to do" value={step.detail} multiline onChange={(v) => set({ detail: v })} />
        <div className={s.field}>
          <EyebrowLabel text="How long" />
          <div className={s.durRow}>
            <HoldButton label="5 seconds shorter" text="−" onStep={(by) => dispatch((d) => nudgeDuration(d, index, by))} />
            <span className={`num-small ${s.durValue}`} aria-live="polite">
              {clock(step.durationSec)}
            </span>
            <HoldButton label="5 seconds longer" text="+" onStep={(by) => dispatch((d) => nudgeDuration(d, index, by))} />
          </div>
        </div>
        {pour && (
          <FieldWell
            label="Pour to"
            value={step.pourTo}
            suffix="g"
            numeric
            placeholder="Total on the scale"
            helper={delta != null ? `${delta >= 0 ? "+" : "−"}${Math.abs(delta)} g this pour` : "The total on the scale when this step ends."}
            onChange={(v) => set({ pourTo: v.replace(/[^\d.,]/g, "").slice(0, 6) })}
          />
        )}
        {issues.map((i) => (
          <p key={i.code} className={`t-body-s ${s.stepIssue}`} role="alert">
            {i.message}
          </p>
        ))}
        <div className={s.stepActions}>
          <QuietButton text="Move up" enabled={index > 0} onClick={() => dispatch((d) => moveStep(d, index, -1))} />
          <QuietButton text="Move down" enabled={index < count - 1} onClick={() => dispatch((d) => moveStep(d, index, 1))} />
          <QuietButton text="Remove" color="var(--fault)" enabled={count > 1} onClick={() => dispatch((d) => removeStep(d, index))} />
        </div>
      </div>
    </SoftCard>
  );
});

// ---------------------------------------------------------------- editable list (prep, tips)

function TextList({ label, items, addLabel, onChange }: { label: string; items: readonly string[]; addLabel: string; onChange: (v: string[]) => void }) {
  return (
    <div className={s.list}>
      {items.map((t, i) => (
        <div key={i} className={s.listItem}>
          <FieldWell label={`${label} ${i + 1}`} value={t} multiline onChange={(v) => onChange(items.map((x, j) => (j === i ? v : x)))} />
          <QuietButton text="Remove" onClick={() => onChange(items.filter((_, j) => j !== i))} />
        </div>
      ))}
      <div>
        <PillButton text={addLabel} icon="Add" onClick={() => onChange([...items, ""])} />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- "Start from…"

function StartFromSheet({ onPick, onClose }: { onPick: (r: Recipe) => void; onClose: () => void }) {
  const recipes = useRecipes();
  const box = useRef<HTMLDivElement>(null);
  return (
    <CoffeeSheet label="Start from a recipe" onClose={onClose} full>
      <div ref={box}>
        <h2 className="t-headline-s" style={{ padding: "4px 0 8px" }}>
          Start from a recipe
        </h2>
        <ul className={s.starts}>
          {recipes.map((r) => (
            <li key={r.id}>
              <button
                type="button"
                className={s.startRow}
                onClick={() => {
                  onPick(r);
                  closeEnclosingSheet(box.current);
                }}
              >
                <span className="t-body-l">{r.name}</span>
                <span className={`t-body-s ${s.startSub}`}>
                  {[r.author, brewerTitle(r.brewer)].filter(Boolean).join(" · ")}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </CoffeeSheet>
  );
}

// ---------------------------------------------------------------- the screen

function initialDraft(mode: EditorMode, source: Recipe | null, author: string): RecipeDraft | null {
  if (mode === "new") return blankDraft(author);
  if (!source) return null;
  return mode === "duplicate" ? duplicateDraft(source, author) : draftFromRecipe(source);
}

function sameRecipe(a: Recipe, b: Recipe): boolean {
  return JSON.stringify({ ...encodeRecipe(a), isBuiltIn: false }) === JSON.stringify({ ...encodeRecipe(b), isBuiltIn: false });
}

export function RecipeEditorScreen({ id, mode }: { id: string | null; mode: "new" | "edit" | "duplicate" }) {
  useCatalog(["brewers", "recipes"]);
  // Without the brewer list every brewer resolves to "Other", and saving would store that.
  const noBrewers = useCatalogFailed(["brewers"]);
  const st = useSettings();
  const units = unitsOf(st);
  const source = useRecipe(id ?? "");
  const hasOverride = useStore((x) => (id ? x.savedRecipes.some((r) => r.id === id) : false), Object.is, id);
  const builtIn = id ? catalog.recipe(id) : null;
  const [draft, setDraft] = useState<RecipeDraft | null>(() => initialDraft(mode, source, st.name));
  // What "unchanged" means, for the discard question (v4 §3.12).
  const [baseline, setBaseline] = useState(() => draftJson(draft));
  // The recipe can arrive after the first frame (recipes.json still loading on a cold deep link).
  if (draft == null && source != null && mode !== "new") {
    const d = initialDraft(mode, source, st.name);
    setDraft(d);
    setBaseline(draftJson(d));
  }
  const [askDiscard, setAskDiscard] = useState(false);
  const wide = atLeast(useWindowClass().width, "expanded");
  const onTop = useRouter((x) => x.top.kind === "recipeEdit", "recipe-edit-top");
  const [dispatch] = useState<Dispatch>(() => (f: (d: RecipeDraft) => RecipeDraft) => setDraft((d) => (d ? f(d) : d)));
  const [pickBrewer, setPickBrewer] = useState(false);
  const [startFrom, setStartFrom] = useState(false);
  const [saving, setSaving] = useState(false);
  const [armed, setArmed] = useState(false);
  const [tempDraft, setTempDraft] = useState<string | null>(null);

  const deferred = useDeferredValue(draft);
  const preview = useMemo(() => (deferred ? recipeFromDraft(deferred, resolveBrewer) : null), [deferred]);
  const issues = useMemo(() => (preview ? check(preview) : []), [preview]);
  const stepIssues = useMemo(() => {
    const m = new Map<number, Issue[]>();
    for (const i of issues) if (i.stepIndex != null && i.code !== "last-pour") m.set(i.stepIndex, [...(m.get(i.stepIndex) ?? []), i]);
    return m;
  }, [issues]);

  const title = mode === "new" ? "New recipe" : mode === "duplicate" ? "Duplicate" : "Edit recipe";
  const dirty = draft != null && draftJson(draft) !== baseline;
  const blocking = hasBlocking(issues);

  const save = async () => {
    if (!draft || saving || blocking || noBrewers) return;
    // Validate the live draft, not the deferred one, so a fast tap after typing can't slip past.
    let recipe = recipeFromDraft(draft, resolveBrewer);
    if (hasBlocking(check(recipe))) return;
    if (mode === "edit" && source && sameRecipe(recipe, source)) {
      nav.back();
      return;
    }
    if (mode === "edit" && builtIn) {
      // Your numbers now: say so, and say how to get the published ones back.
      recipe = { ...recipe, fidelity: "custom", sourceNote: overrideNote(builtIn), basedOn: null };
    }
    setSaving(true);
    try {
      await repo.saveRecipe(recipe);
      if (mode === "edit") nav.back();
      else nav.replace(`/recipes/${encodeURIComponent(recipe.id)}`);
    } finally {
      setSaving(false);
    }
  };
  // Back, Escape: never lose typed work without asking (v4 §3.12).
  const leave = () => {
    if (dirty) setAskDiscard(true);
    else nav.back();
  };

  // Read by the key bindings when they run, so the bindings themselves stay stable.
  const keyActions = useRef({ save: () => undefined as void, leave: () => undefined as void });
  useLayoutEffect(() => {
    keyActions.current = { save: () => void save(), leave };
  });
  const bindings = useMemo<Binding[]>(
    () => [
      { keys: "mod+s", label: "Save recipe", group: "Editing", run: () => keyActions.current.save(), whileTyping: true },
      { keys: "escape", label: "Leave the editor (asks first if changed)", group: "Editing", run: () => keyActions.current.leave() },
    ],
    [],
  );
  useShortcuts("recipe-editor", bindings, onTop);
  const previewNodes = useMemo(() => (preview && preview.doseG > 0 && preview.waterG > 0 ? journeyNodes(preview) : null), [preview]);

  if (!draft) {
    return (
      <div className={`column ${s.screen}`}>
        <DetailTopBar onBack={nav.back} title={title} />
        <QuietPanel text="This recipe isn't here any more. It may have been deleted on another tab." />
      </div>
    );
  }

  const set = (patch: Partial<RecipeDraft>) => setDraft({ ...draft, ...patch });
  const dose = parseAmount(draft.dose);
  const water = parseAmount(draft.water);
  const fieldIssue = (f: Issue["field"]) => issues.find((i) => i.field === f && i.stepIndex == null)?.message ?? null;
  const brewerLabel = docFor("brewer", draft.brewerId)?.title ?? brewerTitle(resolveBrewer(draft.brewerId));
  // Step problems that belong to no single card, and empty required fields (which show no
  // error under the field until you have typed in it).
  const blank: Partial<Record<Issue["field"], boolean>> = { name: !draft.name.trim(), dose: !draft.dose.trim(), water: !draft.water.trim() };
  const listIssues = issues.filter((i) => (i.field === "steps" ? i.stepIndex == null || i.code === "last-pour" : blank[i.field] === true));
  const toggleNeed = (n: RecipeNeed) => set({ needs: draft.needs.includes(n) ? draft.needs.filter((x) => x !== n) : NEEDS.map((x) => x.id).filter((x) => x === n || draft.needs.includes(x)) });
  const saveEnabled = !blocking && !saving && !noBrewers;

  const extraActions = (
    <>
      {mode === "edit" && builtIn && hasOverride && (
        <QuietButton
          text="Reset to original"
          onClick={() => {
            void repo.resetRecipe(builtIn.id).then(() => nav.back());
          }}
        />
      )}
      {mode === "edit" && !builtIn && id && (
        <QuietButton
          text={armed ? "Tap again to delete" : "Delete recipe"}
          color="var(--fault)"
          onClick={() => {
            if (!armed) {
              setArmed(true);
              setTimeout(() => setArmed(false), 4000);
              return;
            }
            // Brews keep their recipeName snapshot, so the log never breaks.
            void repo.deleteRecipe(id).then(() => nav.popToRoot("recipes"));
          }}
        />
      )}
    </>
  );

  return (
    <div className={`column ${s.screen}`} data-wide={wide || undefined}>
      {/* Wide windows: Save stays top-right while the two columns scroll (v4 §3.12). */}
      <div className={s.topBar}>
        <DetailTopBar
          onBack={leave}
          title={title}
          actions={wide ? <PrimaryButton text={saving ? "Saving…" : "Save"} enabled={saveEnabled} height={44} className={s.topSave} onClick={() => void save()} /> : undefined}
        />
      </div>
      <h1 className={`t-headline-l ${s.title}`}>{draft.name.trim() || title}</h1>

      <div className={s.form}>
        {mode === "new" && (
          <div className={s.aStart}>
            <PillButton text="Start from a recipe…" onClick={() => setStartFrom(true)} />
          </div>
        )}

        <div className={s.aFields}>
          <SectionLabel text="The recipe" topGap={0} />
          <FieldWell label="Name" value={draft.name} maxLength={60} onChange={(v) => set({ name: v })} />
          <FieldWell label="Author" value={draft.author} maxLength={60} onChange={(v) => set({ author: v })} />
          <div className={s.field}>
            <EyebrowLabel text="Brewer" />
            <button type="button" className={s.pickRow} aria-label={`Brewer: ${brewerLabel}`} onClick={() => setPickBrewer(true)}>
              <span className="t-body-l">{brewerLabel}</span>
              <Icon name="ChevronLeft" size={20} className={s.chev} />
            </button>
          </div>
          <div className={s.row2}>
            <FieldWell
              label="Coffee"
              value={draft.dose}
              suffix="g"
              numeric
              error={draft.dose.trim() ? fieldIssue("dose") : null}
              onChange={(v) => set({ dose: v.replace(/[^\d.,]/g, "").slice(0, 5) })}
            />
            <FieldWell
              label="Water"
              value={draft.water}
              suffix="g"
              numeric
              error={draft.water.trim() ? fieldIssue("water") : null}
              helper={dose && water ? `Ratio ${ratio(dose, water)}` : undefined}
              onChange={(v) => set({ water: v.replace(/[^\d.,]/g, "").slice(0, 6) })}
            />
          </div>
          <div className={s.field}>
            <EyebrowLabel text="Water temperature" />
            <NumberStepper
              label={`Temperature in ${tempUnit(units)}`}
              value={tempDraft ?? tempText(units, draft.tempC)}
              min={units.fahrenheit ? 32 : 0}
              max={units.fahrenheit ? 212 : 100}
              onText={(t) => {
                setTempDraft(t);
                const n = Number(t);
                if (t === "" || !Number.isFinite(n)) return;
                // °F is display only: the recipe keeps whole °C (A§7.8).
                const c = units.fahrenheit ? Math.round(((n - 32) * 5) / 9) : n;
                set({ tempC: Math.max(0, Math.min(100, c)) });
              }}
              onStep={(by) => {
                setTempDraft(null);
                set({ tempC: Math.max(0, Math.min(100, draft.tempC + by)) });
              }}
            />
            {units.fahrenheit && <p className={`t-body-s ${s.helper}`}>Moves 1 °C at a time, shown in °F.</p>}
          </div>
          <FieldWell label="Grind" value={draft.grind} placeholder="Medium-fine" maxLength={60} helper="Words, not a number: each grinder's number differs." onChange={(v) => set({ grind: v })} />
          <div className={s.field}>
            <EyebrowLabel text="Level" />
            <SegmentedControl label="Level" options={LEVELS.map((l) => l.label)} selectedIndex={LEVELS.findIndex((l) => l.id === draft.level)} onSelect={(i) => set({ level: LEVELS[i]?.id ?? "" })} />
            <p className={`t-body-s ${s.helper}`}>How much practice it takes. New brewers are offered easy recipes first.</p>
          </div>
          <div className={s.field}>
            <EyebrowLabel text="Needs" />
            <div className={s.chipWrap} role="group" aria-label="Needs">
              {NEEDS.map((n) => (
                <SelectableChip key={n.id} text={n.label} selected={draft.needs.includes(n.id)} onClick={() => toggleNeed(n.id)} />
              ))}
            </div>
            <p className={`t-body-s ${s.helper}`}>Beyond the brewer and a kettle. Someone without one is shown the way around it.</p>
          </div>
          <FieldWell label="About this recipe" value={draft.blurb} multiline onChange={(v) => set({ blurb: v })} />
        </div>

        <div className={s.aSteps}>
          {/* Live preview: the route and the journey line follow every keystroke (deferred). */}
          {preview && previewNodes && (
            <div className={s.previewWrap}>
              <SoftCard>
                <div className={s.preview}>
                  <RecipeRoute recipe={preview} numeral="num-medium" />
                  <JourneyLine nodes={previewNodes} style="live" progress={0} startLabel="0:00" endLabel={clock(totalDurationSec(draft))} label={`Preview: ${draft.steps.length} steps, ${clock(totalDurationSec(draft))} in all`} />
                  <p className={`t-body-s ${s.previewMeta}`}>
                    {clock(totalDurationSec(draft))} · {tempText(units, draft.tempC)} {tempUnit(units)} · {draft.steps.length} {draft.steps.length === 1 ? "step" : "steps"}
                  </p>
                </div>
              </SoftCard>
            </div>
          )}
          <SectionLabel text="Steps" topGap={12} />
          <div className={s.steps}>
            {draft.steps.map((step, i) => (
              <StepCard key={step.key} step={step} index={i} count={draft.steps.length} delta={pourDelta(draft, i)} issues={stepIssues.get(i) ?? []} dispatch={dispatch} />
            ))}
          </div>
          <div>
            <PillButton text="Add a step" icon="Add" onClick={() => dispatch((d) => addStep(d))} />
          </div>
        </div>

        <div className={s.aPrep}>
          <SectionLabel text="Before you start" topGap={12} />
          <TextList label="Prep" items={draft.prep} addLabel="Add a prep step" onChange={(v) => set({ prep: v })} />
        </div>
        <div className={s.aTips}>
          <SectionLabel text="Tips" topGap={12} />
          <TextList label="Tip" items={draft.tips} addLabel="Add a tip" onChange={(v) => set({ tips: v })} />
        </div>

        {draft.sourceNote && (
          <div className={s.aSource}>
            <SectionLabel text="Source" topGap={12} />
            <p className={`t-body-s ${s.helper}`}>{mode === "edit" && builtIn ? overrideNote(builtIn) : draft.sourceNote}</p>
          </div>
        )}

        {(noBrewers || listIssues.length > 0) && (
          <div className={`${s.issues} ${s.aIssues}`} role="status">
            {noBrewers && (
              <div className={s.issue}>
                <StatusLine status="fault" lead="Can't save yet" rest="The brewer list didn't load. Check your connection." />
                <PillButton text="Try again" onClick={() => retryCatalog(["brewers", "recipes"])} />
              </div>
            )}
            {listIssues.map((i) => (
              <div key={`${i.code}:${i.stepIndex ?? ""}`} className={s.issue}>
                <StatusLine status="attention" lead="Check" rest={i.message} />
                {i.fix && <PillButton text={i.fix.label} onClick={() => dispatch((d) => setLastPour(d, i.fix!.waterG))} />}
              </div>
            ))}
          </div>
        )}
        {wide && <div className={`${s.wideActions} ${s.aRest}`}>{extraActions}</div>}
      </div>

      {!wide && (
        <StickyCtaBar>
          <div className={s.cta} style={{ flex: 1 }}>
            <PrimaryButton text={saving ? "Saving…" : "Save"} enabled={saveEnabled} onClick={() => void save()} />
            {extraActions}
          </div>
        </StickyCtaBar>
      )}

      {askDiscard && (
        <ConfirmSheet
          title="Discard changes?"
          body="You've changed this recipe and haven't saved it."
          confirmText="Discard"
          cancelText="Keep editing"
          danger
          safe="cancel"
          onClose={() => setAskDiscard(false)}
          onConfirm={() => nav.back()}
        />
      )}

      {pickBrewer && (
        <CatalogPickerSheet
          kind="brewer"
          title="Brewer"
          selected={[draft.brewerId]}
          allowCustom={false}
          onPick={(p) => {
            if ("id" in p) dispatch((d) => ({ ...d, brewerId: p.id }));
          }}
          onClose={() => setPickBrewer(false)}
        />
      )}
      {startFrom && <StartFromSheet onPick={(r) => setDraft(duplicateDraft(r, st.name))} onClose={() => setStartFrom(false)} />}
    </div>
  );
}

/** Chunk + data warm-up, called on pointerdown of anything that navigates here. */
export function prefetch(): Promise<unknown> {
  return catalog.load(["brewers", "recipes"]);
}
