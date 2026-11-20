/**
 * Brew setup (v4 §3.4): dose, grind, water temperature, coffee and the prep list, over a calm
 * mesh. One column on phones; two at expanded and up and in wide landscape (art, title and dose
 * left; grind, temperature, coffee, notes and the chime question right, Start at the right
 * column's foot); the short split keeps the dose on the left while the right side scrolls.
 *
 * Everyone can brew from here (§4.2, §4.3): "Pre-ground (as bought)" is the first grind choice,
 * a blade grinder is timed in seconds, and without a scale the amounts read in spoons and ml.
 */
import { useMemo, useState } from "react";
import { nav } from "@/app/router";
import { CoffeeMug, Dripper } from "@/art";
import { grinderDisplay, type BeanRow, type BrewRow, type GrinderRow } from "@/data/schema";
import { settings as settingsStore, type Settings } from "@/data/settings";
import {
  EyebrowLabel,
  FieldWell,
  HairLine,
  IconWell,
  PillButton,
  PrimaryButton,
  QuietButton,
  RadioRow,
  SecondaryButton,
  SelectableChip,
  SoftCard,
  StatusLine,
  Term,
  TickingNumber,
  toCaps,
} from "@/design/components";
import { brewerArt, brewerShortName, methodLabels } from "@/domain/core/brewer";
import { epochDayOf, grams, halves, ratio, ratioPhrase } from "@/domain/core/format";
import { grindReference } from "@/domain/core/glossary";
import { GROUND_COFFEE_G_PER_LEVEL_TBSP, noScaleAmounts, noScalePrep, servingsPreset } from "@/domain/core/noScale";
import type { Recipe } from "@/domain/core/recipe";
import { roastStatus, roastWord, scoreStatus } from "@/domain/core/status";
import { renderTemps, temp, tempUnit, type Units } from "@/domain/core/units";
import { describe, format as formatGrind } from "@/domain/grind";
import { DialReadingInput, GrindStartHint, useGrindScale } from "@/features/grind";
import type { SetupLayout } from "./layout";
import { Stepper } from "./parts";
import { BLADE_MAX_SEC, BLADE_MIN_SEC, bladeLabel, grindPreference, isBlade, minClicks, prefillGrind, usesClicks, withBean, withDose, type Setup } from "./setup";
import { wakeLockSupported } from "./wakeLock";
import b from "./brew.module.css";

/** Brewers that actually look like a cone with a bed in it. */
// Drip and Siphon drew as V60 and Chemex before v4, so they keep the dripper.
const POUR_OVER = new Set(["V60", "Chemex", "Kalita", "Switch", "Drip", "Siphon"]);
/** Methods a mug of 250 ml makes sense for (not a shot, a moka base or a cezve). */
const NO_MUGS = new Set(["espresso", "moka", "turkish"]);
const MUG_PRESETS = [1, 2, 3] as const;
/** Steps longer than this make the chime worth asking about (§4.8). */
const CHIME_STEP_SEC = 60;

export interface SetupData {
  beans: readonly BeanRow[];
  grinders: readonly GrinderRow[];
  brews: readonly BrewRow[];
}

export function SetupPane({
  recipe,
  base,
  setup,
  setSetup,
  data,
  settings,
  units,
  layout,
  title,
  eyebrow,
  onStart,
}: {
  recipe: Recipe;
  base: Recipe;
  setup: Setup;
  setSetup: (fn: (s: Setup) => Setup) => void;
  data: SetupData;
  settings: Settings;
  units: Units;
  layout: SetupLayout;
  /** The h1: the recipe's name, or "Brew again" for a one-off. */
  title: string;
  eyebrow: string;
  onStart: () => void;
}) {
  const art = brewerArt(recipe.brewer);
  const ready = setup.doseG > 0;
  const grinder = setup.preground ? null : (data.grinders.find((g) => g.id === setup.grinderId) ?? null);
  const form = isBlade(grinder) ? "whole" : "";
  const prep = useMemo(
    () => (settings.hasScale ? recipe.prep.map((l) => renderTemps(l, units.fahrenheit ? "f" : "c")) : noScalePrep(recipe, units, form)),
    [recipe, settings.hasScale, units, form],
  );
  const askChime = !settings.soundCuesAsked && recipe.steps.some((s) => s.endSec - s.startSec > CHIME_STEP_SEC);
  const artSize = layout === "short" ? 72 : layout === "two" ? 136 : 104;

  const head = (
    <>
      <div className={b.setupTop}>
        <IconWell icon="Close" label="Back" keys="escape" onClick={nav.back} />
        <EyebrowLabel text={eyebrow} />
        <span className={`micro ${b.brewerPill}`}>{toCaps(recipe.brewer.label)}</span>
      </div>
      <div className={b.hero} aria-hidden="true">
        {POUR_OVER.has(art) ? <Dripper waterLevel={0.55} size={artSize} /> : <CoffeeMug fill={0.65} size={artSize} />}
      </div>
      <h1 className={`t-headline-m ${b.center}`}>{title}</h1>
      <p className={`t-body-s ink-mid ${b.center}`}>{title === recipe.name ? recipe.author : recipe.name}</p>
    </>
  );
  const dose = <DoseCard setup={setup} base={base} recipe={recipe} hasScale={settings.hasScale} form={form} setSetup={setSetup} />;
  const rest = (
    <>
      <GrindCard recipe={recipe} base={base} setup={setup} setSetup={setSetup} data={data} settings={settings} />
      <TempCard tempC={setup.tempC} units={units} onTemp={(c) => setSetup((s) => ({ ...s, tempC: Math.min(100, Math.max(0, c)) }))} />
      <CoffeeCard
        beans={data.beans}
        selected={setup.beanId}
        onSelect={(id) => {
          const bean = data.beans.find((x) => x.id === id) ?? null;
          setSetup((s) => withBean(s, bean, settings, data.grinders));
          void settingsStore.set("defaultBeanId", id);
        }}
      />
      {prep.length > 0 && (
        <SoftCard>
          <EyebrowLabel text="BEFORE YOU START" />
          <ol className={b.prep}>
            {prep.map((line, i) => (
              <li key={i}>
                <span className="num-inline ink-low">{i + 1}</span>
                <span className={b.prepText}>{line}</span>
              </li>
            ))}
          </ol>
        </SoftCard>
      )}
      {settings.keepScreenOn && !wakeLockSupported() && <p className="t-body-s ink-low">Your browser may dim the screen during a brew.</p>}
      {askChime && <ChimePrompt />}
    </>
  );
  const start = (
    <div className={b.sticky}>
      {/* Ink, not crema: nothing is live yet. Crema arrives with the running dial. */}
      <PrimaryButton text="Start brew" icon="Play" onClick={onStart} enabled={ready} className={`${b.inverse} ${b.startBtn}`} />
    </div>
  );

  if (layout === "single") {
    return (
      <div className={b.setupScroll}>
        <div className={`column ${b.setupColumn}`}>
          {head}
          <div className={b.cards}>
            {dose}
            {rest}
          </div>
          {start}
        </div>
      </div>
    );
  }
  return (
    <div className={b.setupScroll}>
      <div className={b.setupTwo} data-layout={layout}>
        <div className={b.setupLeft}>
          {head}
          <div className={b.cards}>{dose}</div>
        </div>
        <div className={b.setupRight}>
          <div className={b.cards}>{rest}</div>
          {start}
        </div>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------------ dose

function DoseCard({
  setup,
  base,
  recipe,
  hasScale,
  form,
  setSetup,
}: {
  setup: Setup;
  base: Recipe;
  recipe: Recipe;
  hasScale: boolean;
  form: "" | "whole";
  setSetup: (fn: (s: Setup) => Setup) => void;
}) {
  const method = recipe.brewer.method;
  const labels = methodLabels(method);
  const amounts = noScaleAmounts(setup.doseG, recipe.waterG, method, form);
  // Without a scale the stepper moves half a tablespoon, the smallest step a spoon can measure.
  const step = hasScale || amounts.coffeeTbsp == null ? 0.5 : GROUND_COFFEE_G_PER_LEVEL_TBSP / 2;
  const onDose = (d: number) => setSetup((s) => withDose(s, Math.max(1, d)));
  const waterWord = method === "espresso" ? "in the cup" : labels.water === "WATER IN THE BASE" ? "water in the base" : "water";
  const mugs = !NO_MUGS.has(method);
  return (
    <SoftCard>
      <div className={b.cardHead}>
        <EyebrowLabel text="DOSE" />
        <span className="micro ink-low">
          <Term id="ratio">RATIO {ratio(setup.doseG, recipe.waterG)}</Term>
        </span>
      </div>
      <div className={b.stepRow}>
        <Stepper dir={-1} label="Less coffee" onClick={() => onDose(setup.doseG - step)} />
        <div className={b.stepMid}>
          {hasScale || amounts.coffeeTbsp == null ? (
            <>
              {/* Large, not Display: the grind below is the one hero on this screen. */}
              <span className={b.nwu}>
                <TickingNumber value={grams(setup.doseG)} numeral="num-large" label={`${grams(setup.doseG)} grams`} />
                <span className={`num-unit ${b.unitL}`}>g</span>
              </span>
              {!hasScale && amounts.basket && <span className={`t-body-s ink-mid ${b.center}`}>{amounts.basket}</span>}
            </>
          ) : (
            <>
              <span className={b.nwu}>
                <TickingNumber value={halves(amounts.coffeeTbsp)} numeral="num-large" label={amounts.coffee} />
                <span className={`num-unit ${b.unitL}`}>level tbsp</span>
              </span>
              <span className={`t-body-s ink-low ${b.center}`}>({grams(setup.doseG)} g)</span>
            </>
          )}
          <span className={`t-body-m ink-mid ${b.center}`}>→ {hasScale ? `${grams(recipe.waterG)} g` : amounts.water} {waterWord}</span>
        </div>
        <Stepper dir={1} label="More coffee" onClick={() => onDose(setup.doseG + step)} />
      </div>
      <p className={`t-body-s ink-low ${b.center} ${b.ratioLine}`}>{ratioPhrase(setup.doseG, recipe.waterG, method)}</p>
      {mugs && (
        <div className={b.servings} role="group" aria-label="Make more or less">
          {MUG_PRESETS.map((n) => {
            const preset = servingsPreset(base, n);
            const on = Math.abs(preset.doseG - setup.doseG) < 0.05 && setup.waterG == null;
            return <SelectableChip key={n} text={n === 1 ? "1 mug" : `${n} mugs`} selected={on} onClick={() => setSetup((s) => ({ ...s, doseG: preset.doseG, waterG: null }))} />;
          })}
        </div>
      )}
    </SoftCard>
  );
}

// ------------------------------------------------------------------------ grind

function GrindCard({
  recipe,
  base,
  setup,
  setSetup,
  data,
  settings,
}: {
  recipe: Recipe;
  base: Recipe;
  setup: Setup;
  setSetup: (fn: (s: Setup) => Setup) => void;
  data: SetupData;
  settings: Settings;
}) {
  const grinder = setup.preground ? null : (data.grinders.find((g) => g.id === setup.grinderId) ?? null);
  const scale = useGrindScale(grinder);
  const method = recipe.brewer.method;
  const [typing, setTyping] = useState(false);
  const recipeLogs = useMemo(() => data.brews.filter((l) => l.recipeId === base.id), [data.brews, base.id]);
  const last = setup.preground ? null : (recipeLogs.find((l) => l.grinderId === setup.grinderId) ?? recipeLogs[0] ?? null);
  const bean = data.beans.find((x) => x.id === setup.beanId) ?? null;
  const clicks = grinder && usesClicks(grinder) && scale;
  const blade = isBlade(grinder);
  const ref = grindReference(recipe.grind);

  const setGrinder = (id: number) => {
    setTyping(false);
    setSetup((s) => prefillGrind({ ...s, preground: false, grinderId: id, grindClicks: null, grindSetting: "", bladeSec: null }, base, data.grinders, data.brews));
    void settingsStore.set("defaultGrinderId", id);
  };
  const adjust = (delta: number) =>
    setSetup((s) => {
      const g = data.grinders.find((x) => x.id === s.grinderId);
      const pref = g ? grindPreference(base, g) : null;
      const current = s.grindClicks ?? (pref != null ? Math.round(pref) : 0);
      return { ...s, grindClicks: Math.max(minClicks(g), current + delta) };
    });
  const setClicks = (v: number | null) =>
    setSetup((s) => {
      const floor = minClicks(data.grinders.find((x) => x.id === s.grinderId));
      return { ...s, grindClicks: v == null ? null : Math.min(Math.max(floor, Math.round(v)), Math.max(floor, 999)) };
    });

  return (
    <SoftCard>
      <div className={b.cardHead}>
        <span className="micro ink-mid">
          <Term id="grind">GRIND</Term>
        </span>
        {data.grinders.length === 0 && !setup.preground && <span className="micro ink-low">NO GRINDER YET</span>}
      </div>
      {/* Pre-ground first: the coffee as bought needs no grinder at all (§4.2). */}
      <div className={b.chipWrap} role="group" aria-label="Grinder">
        <SelectableChip text="Pre-ground (as bought)" selected={setup.preground} onClick={() => setSetup((s) => ({ ...s, preground: true }))} />
        {data.grinders.map((g) => (
          <SelectableChip key={g.id} text={grinderDisplay(g)} selected={!setup.preground && g.id === setup.grinderId} onClick={() => setGrinder(g.id!)} />
        ))}
      </div>
      <div className={b.gap12} />
      {setup.preground ? (
        <p className="t-body-m ink-mid">
          {bean?.form === "ground" && bean.groundFor ? `Ground for ${groundForWord(bean.groundFor)}. ` : ""}
          Use the coffee as it came: nothing to set.
          {settings.buysGround ? "" : " Add a grinder under Gear when you have one."}
        </p>
      ) : blade ? (
        <BladeSeconds value={setup.bladeSec} onValue={(v) => setSetup((s) => ({ ...s, bladeSec: v }))} />
      ) : clicks ? (
        typing ? (
          <DialReadingInput scale={scale} value={setup.grindClicks} onValue={setClicks} />
        ) : (
          <ClickDial clicks={setup.grindClicks} grinder={grinder} caption={setup.grindClicks == null ? "Set your grind" : describe(scale, setup.grindClicks)} onAdjust={adjust} />
        )
      ) : (
        <>
          {data.grinders.length === 0 && <p className="t-body-s ink-mid">Type a setting, or add a grinder under Gear.</p>}
          <FieldWell label="SETTING" value={setup.grindSetting} onChange={(v) => setSetup((s) => ({ ...s, grindSetting: v }))} placeholder={recipe.grind} />
        </>
      )}
      {clicks && <QuietButton text={typing ? "Use the stepper" : "Type it as the dial reads"} onClick={() => setTyping((t) => !t)} className={b.quietLeft} />}
      {scale && !blade && (
        <GrindStartHint
          scale={scale}
          method={method}
          onUse={(v) => (usesClicks(grinder) ? setClicks(v) : setSetup((s) => ({ ...s, grindSetting: formatGrind(scale, v) })))}
          isCurrent={(v) => (usesClicks(grinder) ? setup.grindClicks === v : setup.grindSetting.trim() === formatGrind(scale, v))}
        />
      )}
      {last && (
        <>
          <div className={b.gap12} />
          <HairLine />
          <div className={b.gap12} />
          {/* The verdict takes the status colour; the grind that earned it is the detail. */}
          <StatusLine
            status={scoreStatus(last.overall)}
            lead={last.overall > 0 ? `Last time ${last.overall}/10` : "Last time unrated"}
            rest={`${last.grindClicks != null ? `${last.grindClicks} clicks` : last.grindSetting.trim() || "no grind"} on the ${brewerShortName(recipe.brewer)}`}
          />
        </>
      )}
      <p className={`t-body-s ink-low ${b.recipeSays}`}>
        Recipe says: {recipe.grind}
        {ref ? ` · ${ref}` : ""}
      </p>
    </SoftCard>
  );
}

function groundForWord(g: string): string {
  switch (g) {
    case "press":
      return "a French press";
    case "espresso":
      return "espresso";
    case "moka":
      return "a moka pot";
    default:
      return "filter";
  }
}

/** A blade grinder is timed, not dialled (§4.2). */
function BladeSeconds({ value, onValue }: { value: number | null; onValue: (v: number) => void }) {
  const v = value ?? 12;
  const set = (n: number) => onValue(Math.min(BLADE_MAX_SEC, Math.max(BLADE_MIN_SEC, n)));
  return (
    <div className={b.dial}>
      <EyebrowLabel text="GRIND FOR" />
      <div className={b.stepRow}>
        <Stepper dir={-1} label="One second less" onClick={() => set(v - 1)} />
        <div className={b.stepMid}>
          <span className={b.nwu}>
            <TickingNumber value={String(v)} numeral="num-display" label={bladeLabel(v)} />
            <span className={`num-unit ${b.unitXL}`}>s</span>
          </span>
        </div>
        <Stepper dir={1} label="One second more" onClick={() => set(v + 1)} />
      </div>
      <p className={`t-body-s ink-mid ${b.center}`}>Pulse in short bursts and shake the grinder between them.</p>
    </div>
  );
}

/**
 * Grind position for click grinders: a number you can nudge with your thumb mid-setup. Stored as
 * an integer so a report can say "go three clicks finer" and mean something. An unset grind is a
 * dash, never 0 (0 is burr contact).
 */
function ClickDial({ clicks, grinder, caption, onAdjust }: { clicks: number | null; grinder: GrinderRow; caption: string; onAdjust: (d: number) => void }) {
  const down = grinder.lowerIsFiner ? "finer" : "coarser";
  const up = grinder.lowerIsFiner ? "coarser" : "finer";
  return (
    <div className={b.dial}>
      <div className={b.stepRow}>
        <div className={b.stepSide}>
          <Stepper dir={-1} label={`One click ${down}`} onClick={() => onAdjust(-1)} />
          <span className="micro ink-low">{toCaps(down)}</span>
        </div>
        <div className={b.stepMid}>
          <span className={b.nwu} style={{ color: clicks == null ? "var(--ink-low)" : undefined }}>
            <TickingNumber value={clicks == null ? "—" : String(clicks)} numeral="num-display" label={clicks == null ? "Grind not set" : `${clicks} clicks`} />
            {clicks != null && <span className={`num-unit ${b.unitXL}`}>clicks</span>}
          </span>
          <span className={`t-body-s ink-mid ${b.center}`}>{caption}</span>
        </div>
        <div className={b.stepSide}>
          <Stepper dir={1} label={`One click ${up}`} onClick={() => onAdjust(1)} />
          <span className="micro ink-low">{toCaps(up)}</span>
        </div>
      </div>
      <div className={b.pills}>
        {[-5, -1, 1, 5].map((d) => (
          <PillButton key={d} text={d < 0 ? `−${-d}` : `+${d}`} onClick={() => onAdjust(d)} className={b.pill} />
        ))}
      </div>
    </div>
  );
}

// ------------------------------------------------------------------------ temperature, coffee

function TempCard({ tempC, units, onTemp }: { tempC: number; units: Units; onTemp: (c: number) => void }) {
  const u = { fahrenheit: units.fahrenheit, ounces: false };
  // In °F mode the stepper still moves 1 °C and shows the rounded °F (A§7.8).
  return (
    <SoftCard>
      <div className={b.tempRow}>
        <div className={b.grow}>
          <EyebrowLabel text="WATER TEMP" />
          <span className={b.nwu}>
            <TickingNumber value={temp(u, tempC)} numeral="num-large" label={`${temp(u, tempC)} ${tempUnit(u)}`} />
            <span className={`num-unit ${b.unitL}`}>{tempUnit(u)}</span>
          </span>
        </div>
        <Stepper dir={-1} label="Cooler" onClick={() => onTemp(tempC - 1)} />
        <Stepper dir={1} label="Hotter" onClick={() => onTemp(tempC + 1)} />
      </div>
    </SoftCard>
  );
}

function CoffeeCard({ beans, selected, onSelect }: { beans: readonly BeanRow[]; selected: number | null; onSelect: (id: number) => void }) {
  // Read once: days off roast only needs to be right to the day while this screen is open.
  const [today] = useState(() => epochDayOf(Date.now()));
  return (
    <SoftCard>
      <EyebrowLabel text="COFFEE" />
      {beans.length === 0 ? (
        <p className={`t-body-m ink-mid ${b.gap8}`}>No coffee yet. Add one under Gear.</p>
      ) : (
        <div role="radiogroup" aria-label="Coffee">
          {beans.map((bean) => {
            const days = bean.roastedOnEpochDay != null ? Math.max(0, today - bean.roastedOnEpochDay) : null;
            const word = roastWord(days);
            return (
              <RadioRow
                key={bean.id}
                name="brew-bean"
                title={bean.name}
                selected={bean.id === selected}
                onClick={() => onSelect(bean.id!)}
                subtitle={[bean.roaster.trim() || null, bean.form === "ground" ? "ground" : null, days != null ? `${days} d off roast` : "roast date unknown"].filter(Boolean).join(" · ")}
                note={word ?? undefined}
                noteStatus={roastStatus(days) === "attention" ? "attention" : undefined}
              />
            );
          })}
        </div>
      )}
    </SoftCard>
  );
}

/** §4.8: asked once per device, on the first brew that has a step longer than a minute. */
function ChimePrompt() {
  const answer = (on: boolean) => void settingsStore.update({ soundCues: on, soundCuesAsked: true });
  return (
    <SoftCard>
      <p className="t-title-l">Chime when it&apos;s time for the next step?</p>
      <div className={b.chimeRow}>
        <SecondaryButton text="Yes, chime" onClick={() => answer(true)} className={b.chimeBtn} />
        <SecondaryButton text="No sound" onClick={() => answer(false)} className={b.chimeBtn} />
      </div>
    </SoftCard>
  );
}
