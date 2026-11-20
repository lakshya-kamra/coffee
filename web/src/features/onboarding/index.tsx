/**
 * Onboarding (A§7.3, v4 §4.1, §3.11): six steps (you, measure, grinder, brewers, coffee, your
 * first brew) over the brew screen's mesh, with progress dots, Back, "Skip for now" on every
 * step, and Next that saves its own step (partial progress survives). Visited steps stay mounted
 * and hidden, so going back finds them as you left them; the incoming step fades in with a 24 px
 * drift (opacity + transform). Enter = Next, Esc = Back.
 *
 * Layout by the layer's width (container queries): compact as before; medium a centred card
 * (max 560); expanded+ a 960 frame split 40/60 with why-we-ask copy and art on the left; short
 * windows the same split without the art.
 */
import { animate } from "motion";
import { useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { nav, useRouter } from "@/app/router";
import { isTypingTarget, useShortcuts } from "@/app/shortcuts";
import { prefersReducedMotion } from "@/app/transitionState";
import { BrewerIcon, CoffeeMug, Dripper } from "@/art";
import { Brewers, catalog } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import { EyebrowLabel, FieldWell, IconWell, PillButton, PrimaryButton, QuietButton, SegmentedControl, SelectableChip, SoftCard, StatusChip } from "@/design/components";
import { effectsDefault, spatialDefault } from "@/design/motion";
import { newGrinder, type SettingStyle } from "@/data/schema";
import { store, useGrinders, useRecipes, useSettings } from "@/data/store";
import { brewerFamily } from "@/domain/core/brewer";
import { firstBrew } from "@/domain/core/firstBrew";
import { noScaleAmounts } from "@/domain/core/noScale";
import { scaleFor } from "@/domain/grind";
import { BREWER_CATEGORY_ORDER, brewerCategoryLabel, brewerTitle, docFor, grinderSubtitle } from "@/domain/search";
import { AiSettingsSheet } from "@/features/ai";
import { CatalogPickerList, CatalogPickerSheet } from "@/features/catalog-picker";
import { fieldsFromCatalog, scaleSummary } from "@/features/gear/mapping";
import { confidenceChip, DialReadingInput, startValue } from "@/features/grind";
import { MeshGradient } from "@/viz";
import {
  BLADE_GRINDER_ID,
  COMMON_BREWER_IDS,
  EXPERIENCE_CHOICES,
  finish,
  PLAIN_WATERS,
  presetsFor,
  saveBrewers,
  saveCoffee,
  saveGrinder,
  saveMeasure,
  saveYou,
  STEPS,
  type ExperienceChoice,
  type GrinderChoice,
  type StepId,
} from "./persist";
import s from "./onboarding.module.css";

/** The left column at expanded+: why each step asks (two sentences each). */
const WHY: Readonly<Record<StepId, string>> = {
  you: "Coffee can be as simple or as fussy as you like. Your answer decides how much we show and explain, and you can change it any time.",
  measure: "Recipes are written in grams, but a scale isn't required. Without one, every amount becomes spoons and millilitres.",
  grinder: "Grind changes the taste more than anything else you can adjust. If you buy ground coffee, we skip grind settings entirely.",
  brewers: "Every recipe is written for one kind of brewer. Tell us yours and we only suggest recipes that work with it.",
  coffee: "Roasters you like come first when you add a coffee. Water is most of the cup, so knowing yours helps explain the taste.",
  done: "This is the easiest good recipe for what you have. Every step is timed, and the words are explained as you go.",
};

// ---------------------------------------------------------------- shared bits

/** A big choice card: a native radio in a label, so arrows move and Space picks. */
function ChoiceCard({ name, title, body, checked, onPick }: { name: string; title: string; body?: string; checked: boolean; onPick: () => void }) {
  return (
    <label className={s.choice} data-checked={checked}>
      <input type="radio" name={name} className={s.choiceInput} checked={checked} onChange={onPick} />
      <span className={s.choiceText}>
        <span className="t-title-l">{title}</span>
        {body && <span className={`t-body-m ${s.described}`}>{body}</span>}
      </span>
      <span className={s.choiceMark} aria-hidden="true" />
    </label>
  );
}

function StepHead({ index, title, lede }: { index: number; title: string; lede?: string }) {
  return (
    <>
      <EyebrowLabel text={`Step ${index + 1} of ${STEPS.length}`} />
      <h1 className={`t-display-s ${s.title}`} tabIndex={-1} data-step-title="">
        {title}
      </h1>
      {lede && <p className={`t-body-m ${s.lede}`}>{lede}</p>}
    </>
  );
}

// ---------------------------------------------------------------- 1. How do you make coffee now?

function YouStep({ index, value, onValue }: { index: number; value: ExperienceChoice | ""; onValue: (v: ExperienceChoice) => void }) {
  return (
    <>
      <StepHead index={index} title="How do you make coffee now?" />
      <div className={s.choices} role="radiogroup" aria-label="How do you make coffee now?">
        {EXPERIENCE_CHOICES.map((c) => (
          <ChoiceCard key={c.id} name="experience" title={c.title} body={c.body} checked={value === c.id} onPick={() => onValue(c.id)} />
        ))}
      </div>
    </>
  );
}

// ---------------------------------------------------------------- 2. How you measure

function MeasureStep({
  index,
  hasScale,
  temp,
  weight,
  area,
  onScale,
  onTemp,
  onWeight,
  onArea,
}: {
  index: number;
  hasScale: boolean;
  temp: "c" | "f";
  weight: "g" | "oz";
  area: string;
  onScale: (v: boolean) => void;
  onTemp: (v: "c" | "f") => void;
  onWeight: (v: "g" | "oz") => void;
  onArea: (v: string) => void;
}) {
  const ready = useCatalog(["roasters"]);
  const areas = useMemo(() => (ready ? [...new Set(catalog.roasters.map((r) => r.area).filter(Boolean))].sort() : []), [ready]);
  return (
    <>
      <StepHead index={index} title="How you measure" />
      <div className={s.group}>
        <EyebrowLabel text="Do you have a kitchen scale?" />
        <div className={s.choicesTight} role="radiogroup" aria-label="Do you have a kitchen scale?">
          <ChoiceCard name="scale" title="Yes" checked={hasScale} onPick={() => onScale(true)} />
          <ChoiceCard name="scale" title="No, I'll use spoons and a jug" checked={!hasScale} onPick={() => onScale(false)} />
        </div>
        <p className={`t-body-s ${s.described}`}>You can change this any time in Gear › Settings.</p>
      </div>
      <div className={s.group}>
        <EyebrowLabel text="Temperature" />
        <SegmentedControl options={["°C", "°F"]} selectedIndex={temp === "f" ? 1 : 0} onSelect={(i) => onTemp(i === 1 ? "f" : "c")} label="Temperature unit" />
      </div>
      <div className={s.group}>
        <EyebrowLabel text="Weight" />
        <SegmentedControl options={["Grams", "Ounces"]} selectedIndex={weight === "oz" ? 1 : 0} onSelect={(i) => onWeight(i === 1 ? "oz" : "g")} label="Weight unit" />
        <p className={`t-body-s ${s.described}`}>Brew targets stay in grams — that's what coffee scales read. Bag sizes and totals follow your choice.</p>
      </div>
      {areas.length > 0 && (
        <div className={s.group} role="group" aria-label="Where do you buy coffee?">
          <EyebrowLabel text="Where do you buy coffee?" />
          <div className={s.chips}>
            {areas.map((a) => (
              <SelectableChip key={a} text={a} selected={area === a} onClick={() => onArea(area === a ? "" : a)} />
            ))}
          </div>
          <p className={`t-body-s ${s.described}`}>Roasters and supermarket brands sold near you come first when you pick one.</p>
        </div>
      )}
    </>
  );
}

// ---------------------------------------------------------------- 3. Your grinder

interface CustomGrinder {
  brand: string;
  model: string;
  settingStyle: SettingStyle;
  cpr: string;
}

type GrinderKind = "ground" | "blade" | "burr" | null;

function GrinderSummary({ id, onChange }: { id: string; onChange: () => void }) {
  const c = catalog.grinder(id);
  // The demo starts on the pour-over starting point, so the reading means something at once.
  const [demo, setDemo] = useState<number | null>(() => (c ? startValue(scaleFor(newGrinder(fieldsFromCatalog(c))), "pourover") : null));
  if (!c) return null;
  const f = fieldsFromCatalog(c);
  const scale = scaleFor(newGrinder(f));
  const chip = confidenceChip(c.confidence);
  return (
    <SoftCard>
      <div className={s.summary}>
        <div className={s.summaryTop}>
          <EyebrowLabel text={c.brand} />
          {chip && <StatusChip status={chip.status} text={chip.text} />}
        </div>
        <h2 className="t-headline-s">{c.model}</h2>
        <p className={`t-body-s ${s.described}`}>{grinderSubtitle(c)}</p>
        <p className="t-body-m">{scaleSummary(f)}</p>
        {c.setting.dialReading && <p className={s.note}>How to read your dial: {c.setting.dialReading}</p>}
        {c.setting.entryHint && <p className={s.note}>What to type: {c.setting.entryHint}</p>}
        {scale.dial.kind !== "plain" && (
          <div className={s.group}>
            <EyebrowLabel text="Try it: set a reading" />
            <DialReadingInput scale={scale} value={demo} onValue={setDemo} />
          </div>
        )}
        <QuietButton text="Pick a different grinder" onClick={onChange} />
      </div>
    </SoftCard>
  );
}

function CustomGrinderFields({ value, onChange }: { value: CustomGrinder; onChange: (v: CustomGrinder) => void }) {
  return (
    <SoftCard>
      <div className={s.summary}>
        <EyebrowLabel text="Your grinder" />
        <div className={s.row2}>
          <FieldWell label="Brand" value={value.brand} onChange={(v) => onChange({ ...value, brand: v })} />
          <FieldWell label="Model" value={value.model} onChange={(v) => onChange({ ...value, model: v })} />
        </div>
        <div className={s.group} role="group" aria-label="How it adjusts">
          <EyebrowLabel text="How it adjusts" />
          <div className={s.chips}>
            {(
              [
                ["clicks", "Clicks"],
                ["numbers", "Numbered dial"],
                ["stepless", "Stepless"],
              ] as const
            ).map(([id, label]) => (
              <SelectableChip key={id} text={label} selected={value.settingStyle === id} onClick={() => onChange({ ...value, settingStyle: id })} />
            ))}
          </div>
        </div>
        {value.settingStyle === "clicks" && (
          <FieldWell
            label="Clicks per turn"
            value={value.cpr}
            numeric
            helper="Optional. Your manual says it; the app uses it to read clicks back as turns."
            onChange={(v) => onChange({ ...value, cpr: v.replace(/[^\d]/g, "").slice(0, 3) })}
          />
        )}
      </div>
    </SoftCard>
  );
}

function GrinderStep({
  index,
  kind,
  grinderId,
  custom,
  onKind,
  onGrinder,
  onCustom,
}: {
  index: number;
  kind: GrinderKind;
  grinderId: string | null;
  custom: CustomGrinder | null;
  onKind: (k: GrinderKind) => void;
  onGrinder: (id: string | null) => void;
  onCustom: (c: CustomGrinder | null) => void;
}) {
  return (
    <>
      <StepHead index={index} title="Your grinder" lede="Pick yours so the app reads your numbers the way your dial does." />
      <div className={s.choices} role="radiogroup" aria-label="How do you grind?">
        <ChoiceCard name="grinder" title="I buy ground coffee" body="We'll skip grind settings." checked={kind === "ground"} onPick={() => onKind("ground")} />
        <ChoiceCard name="grinder" title="Blade grinder" body="The kind with a spinning blade. We'll time it in seconds." checked={kind === "blade"} onPick={() => onKind("blade")} />
        <ChoiceCard name="grinder" title="Burr grinder (hand or electric)" body="Find yours in the list below." checked={kind === "burr"} onPick={() => onKind("burr")} />
      </div>
      {kind === "burr" &&
        (grinderId ? (
          <GrinderSummary id={grinderId} onChange={() => onGrinder(null)} />
        ) : custom ? (
          <>
            <CustomGrinderFields value={custom} onChange={onCustom} />
            <QuietButton text="Pick from the list instead" onClick={() => onCustom(null)} />
          </>
        ) : (
          <>
            <div className={s.pickerBox}>
              <CatalogPickerList
                kind="grinder"
                focusSearch={false}
                onPick={(p) => {
                  if ("id" in p) onGrinder(p.id);
                  else {
                    const t = p.custom.trim();
                    const sp = t.indexOf(" ");
                    onCustom({ brand: sp < 0 ? t : t.slice(0, sp), model: sp < 0 ? "" : t.slice(sp + 1), settingStyle: "clicks", cpr: "" });
                  }
                }}
              />
            </div>
            <QuietButton text="Not listed? Add it yourself" onClick={() => onCustom({ brand: "", model: "", settingStyle: "clicks", cpr: "" })} />
          </>
        ))}
    </>
  );
}

// ---------------------------------------------------------------- 4. Brewers you own

function BrewersStep({ index, owned, onOwned }: { index: number; owned: readonly string[]; onOwned: (ids: string[]) => void }) {
  const ready = useCatalog(["brewers"]);
  const groups = useMemo(() => {
    if (!ready) return [];
    const common = COMMON_BREWER_IDS.map((id) => catalog.brewer(id)).filter((b): b is NonNullable<typeof b> => b != null);
    const rest = catalog.brewers.filter((b) => !COMMON_BREWER_IDS.includes(b.id) && b.id !== "other");
    const byCat = new Map<string, typeof rest>();
    for (const b of [...rest].sort((a, c) => brewerTitle(a).localeCompare(brewerTitle(c)))) byCat.set(b.category, [...(byCat.get(b.category) ?? []), b]);
    const cats = [...byCat.keys()].sort((a, b) => {
      const ia = BREWER_CATEGORY_ORDER.indexOf(a);
      const ib = BREWER_CATEGORY_ORDER.indexOf(b);
      return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
    });
    return [{ name: "Most common", list: common }, ...cats.map((c) => ({ name: brewerCategoryLabel(c), list: byCat.get(c)! }))];
  }, [ready]);
  const set = new Set(owned);
  const toggle = (id: string) => onOwned(set.has(id) ? owned.filter((x) => x !== id) : [...owned, id]);
  return (
    <>
      <StepHead index={index} title="Brewers you own" lede="Recipes for these come first. Pick as many as you like." />
      {groups.map((g) => (
        <div key={g.name} className={s.group} role="group" aria-label={g.name}>
          <EyebrowLabel text={g.name} />
          <div className={s.chips}>
            {g.list.map((b) => (
              <SelectableChip key={b.id} text={brewerTitle(b)} selected={set.has(b.id)} onClick={() => toggle(b.id)} />
            ))}
          </div>
        </div>
      ))}
    </>
  );
}

// ---------------------------------------------------------------- 5. Your coffee

function CoffeeStep({
  index,
  roasters,
  water,
  unsure,
  onRoasters,
  onWater,
}: {
  index: number;
  roasters: readonly string[];
  water: string;
  unsure: boolean;
  onRoasters: (ids: string[]) => void;
  onWater: (id: string, unsure: boolean) => void;
}) {
  // Reads the catalog while rendering (water list, roaster names): the compiler would cache those
  // reads on props alone and keep the empty answer from before the files landed.
  "use no memo";
  useCatalog(["roasters", "water"]);
  const [picking, setPicking] = useState(false);
  const plainIds = new Set(PLAIN_WATERS.map((w) => w.waterId).filter(Boolean));
  const [more, setMore] = useState(() => water !== "" && !plainIds.has(water));
  const anchor = useRef<HTMLSpanElement>(null);
  const w = catalog.waterById(water);
  return (
    <>
      <StepHead index={index} title="Your coffee" lede="Optional. Both help the app, and an AI if you add one, say more about your cups." />
      <div className={s.group} role="group" aria-label="Favourite roasters">
        <EyebrowLabel text="Favourite roasters" />
        <div className={s.chips}>
          {roasters.map((id) => (
            <SelectableChip key={id} text={docFor("roaster", id)?.title ?? id} selected onClick={() => onRoasters(roasters.filter((x) => x !== id))} />
          ))}
          <span ref={anchor} className={s.anchorWrap}>
            <PillButton text={roasters.length ? "Add more…" : "Pick roasters…"} onClick={() => setPicking(true)} />
          </span>
        </div>
      </div>
      <div className={s.group} role="radiogroup" aria-label="What water do you brew with?">
        <EyebrowLabel text="What water do you brew with?" />
        <div className={s.choicesTight}>
          {PLAIN_WATERS.map((p) => (
            <ChoiceCard key={p.key} name="water" title={p.label} checked={p.waterId === "" ? unsure && water === "" : water === p.waterId} onPick={() => onWater(p.waterId, p.waterId === "")} />
          ))}
        </div>
        <button type="button" className={`${s.disclosure} t-label-l`} aria-expanded={more} onClick={() => setMore(!more)}>
          More waters
        </button>
        {more && catalog.water.length > 0 && (
          <div className={s.chips} role="group" aria-label="More waters">
            {catalog.water.map((x) => (
              <SelectableChip key={x.id} text={x.label} selected={water === x.id} onClick={() => onWater(water === x.id ? "" : x.id, false)} />
            ))}
          </div>
        )}
        {w && <p className={`t-body-s ${s.described}`}>{w.description}</p>}
      </div>
      {picking && (
        <CatalogPickerSheet kind="roaster" title="Favourite roasters" anchor={anchor} multiSelect selected={[...roasters]} onPick={() => undefined} onDone={onRoasters} onClose={() => setPicking(false)} />
      )}
    </>
  );
}

// ---------------------------------------------------------------- 6. Your first brew

function FirstBrewStep({ index, onAi }: { index: number; onAi: () => void }) {
  const st = useSettings();
  const recipes = useRecipes();
  const grinders = useGrinders();
  useCatalog(["brewers"]);
  const pick = useMemo(() => firstBrew(recipes, st, grinders, (id) => Brewers.byId(id)), [recipes, st, grinders]);
  const r = pick.recipe;
  const missing = pick.noRecipeFor.map((id) => Brewers.byId(id)).filter((b) => b != null);
  const amounts = r ? noScaleAmounts(r.doseG, r.waterG, r.brewer.method, st.buysGround ? "ground" : "") : null;
  // A drip machine heats its own water and cold brew uses none: no kettle for those.
  const machine = r != null && brewerFamily(r.brewer) === "drip-machine";
  const cold = r != null && /cold|toddy|kyoto/.test(r.brewer.id);
  const kettle = !machine && !cold;
  const needs = r && amounts ? [brewerTitle(r.brewer), kettle ? "A kettle" : "", st.hasScale ? `${Math.round(r.doseG)} g of coffee` : amounts.coffeeLine || amounts.basket || "", st.hasScale ? `${Math.round(r.waterG)} g water` : `${amounts.water} water`, "A mug"].filter(Boolean) : [];
  return (
    <>
      <StepHead index={index} title="Your first brew" />
      {r ? (
        <SoftCard>
          <div className={s.summary}>
            <EyebrowLabel text={r.author || "Recipe"} />
            <h2 className="t-headline-s">{r.name}</h2>
            <div className={s.group}>
              <EyebrowLabel text="You'll need" />
              <ul className={s.chips} aria-label="You'll need">
                {needs.map((n) => (
                  <li key={n} className={`${s.need} t-label-l`}>
                    {n}
                  </li>
                ))}
              </ul>
            </div>
            <ol className={s.checklist}>
              <li>{machine ? "Fill the machine's tank with the water." : cold ? "Use cold, fresh water." : "Boil the kettle, then let it sit for 30 seconds."}</li>
              <li>Measure the coffee and the water.</li>
              <li>{machine ? "Press Start as you switch the machine on. We'll tell you each step." : "Press Start as you pour. We'll tell you each step."}</li>
            </ol>
          </div>
        </SoftCard>
      ) : (
        <p className={`t-body-m ${s.lede}`}>Pick a recipe that suits your brewer from Recipes. Every one says what you need.</p>
      )}
      {missing.map((b) => (
        <p key={b.id} className={`t-body-m ${s.lede}`}>
          We don&apos;t have a tested recipe for your {brewerTitle(b)} yet.
        </p>
      ))}
      <QuietButton text="Add an AI key (optional)" onClick={onAi} />
    </>
  );
}

// ---------------------------------------------------------------- the left column (expanded+)

function WhyArt({ step, owned }: { step: StepId; owned: readonly string[] }) {
  const b = owned.map((id) => Brewers.byId(id)).find((x) => x != null);
  if ((step === "brewers" || step === "done") && b) return <BrewerIcon brewer={b} size={140} decorative />;
  if (step === "grinder" || step === "measure") return <Dripper size={150} />;
  return <CoffeeMug fill={step === "coffee" ? 0.75 : 0.55} size={150} />;
}

function Why({ step, owned }: { step: StepId; owned: readonly string[] }) {
  return (
    <aside className={s.why} aria-label="Why we ask">
      <div className={s.whyArt} aria-hidden="true">
        <WhyArt step={step} owned={owned} />
      </div>
      <EyebrowLabel text={step === "done" ? "Why this one" : "Why we ask"} />
      <p className={`t-body-l ${s.whyText}`}>{WHY[step]}</p>
    </aside>
  );
}

// ---------------------------------------------------------------- the screen

function scrollParent(el: HTMLElement): HTMLElement | null {
  for (let p = el.parentElement; p; p = p.parentElement) {
    const o = getComputedStyle(p).overflowY;
    if (o === "auto" || o === "scroll") return p;
  }
  return null;
}

/** Where Enter means "Next": not on a control that has its own Enter (buttons, links, fields). */
function enterIsNext(e: KeyboardEvent<HTMLElement>): boolean {
  const t = e.target as HTMLElement;
  if (e.key !== "Enter" || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey || e.nativeEvent.isComposing) return false;
  if (t.closest("dialog, button, a, select, textarea")) return false;
  return !(isTypingTarget(t) && (t as HTMLInputElement).type !== "radio");
}

function initialGrinderKind(buysGround: boolean, catalogId: string | null | undefined, hasGrinder: boolean): GrinderKind {
  if (catalogId === BLADE_GRINDER_ID) return "blade";
  if (hasGrinder) return "burr";
  return buysGround ? "ground" : null;
}

export function OnboardingScreen() {
  const st = useSettings();
  const grinders = useGrinders();
  const onTop = useRouter((r) => r.top.kind === "onboarding");
  const [step, setStep] = useState(0);
  const [visited, setVisited] = useState<ReadonlySet<number>>(() => new Set([0]));
  if (!visited.has(step)) setVisited(new Set([...visited, step]));

  // Prefilled from settings, so "Run setup again" starts from what you chose before. The units
  // already default by region (CORE's defaultTempUnit on a fresh install).
  const def = grinders.find((g) => g.isDefault);
  const [experience, setExperience] = useState<ExperienceChoice | "">(st.experience);
  const [hasScale, setHasScale] = useState(st.hasScale);
  const [scaleTouched, setScaleTouched] = useState(false);
  const [temp, setTemp] = useState(st.tempUnit);
  const [weight, setWeight] = useState(st.weightUnit);
  const [area, setArea] = useState(st.homeArea);
  const [grinderKind, setGrinderKind] = useState<GrinderKind>(() => initialGrinderKind(st.buysGround, def?.catalogId, def != null));
  const [grinderId, setGrinderId] = useState<string | null>(() => (def?.catalogId && def.catalogId !== BLADE_GRINDER_ID ? def.catalogId : null));
  const [custom, setCustom] = useState<CustomGrinder | null>(null);
  const [owned, setOwned] = useState<string[]>(st.ownedBrewerIds);
  const [roasters, setRoasters] = useState<string[]>(st.favouriteRoasterIds);
  const [water, setWater] = useState(st.defaultWaterId);
  const [unsureWater, setUnsureWater] = useState(false);
  const [aiOpen, setAiOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const panes = useRef<(HTMLDivElement | null)[]>([]);
  const shown = useRef(step);

  useLayoutEffect(() => {
    const from = shown.current;
    if (from === step) return;
    shown.current = step;
    const el = panes.current[step];
    // A new step starts at its top (the detail layer is the scroller), with its title focused so
    // screen readers hear it and Enter keeps meaning Next.
    const scroller = el ? scrollParent(el) : null;
    if (scroller) scroller.scrollTop = 0;
    if (!el) return;
    el.querySelector<HTMLElement>("[data-step-title]")?.focus({ preventScroll: true });
    const dir = step > from ? 1 : -1;
    const a = prefersReducedMotion()
      ? animate(el, { opacity: [0, 1] }, effectsDefault)
      : animate(el, { opacity: [0, 1], transform: [`translateX(${dir * 24}px)`, "translateX(0px)"] }, spatialDefault);
    return () => a.stop();
  }, [step]);

  const pickExperience = (e: ExperienceChoice) => {
    setExperience(e);
    // "I'm new to this" preselects "No" on the scale question, unless it was already answered.
    const p = presetsFor(e);
    if (!scaleTouched && p.hasScale != null) setHasScale(p.hasScale);
  };

  /** Saves the step you are on (A§7.3: each Next persists that step). */
  async function persist(i: number): Promise<void> {
    switch (STEPS[i]) {
      case "you":
        if (experience) await saveYou(experience);
        break;
      case "measure":
        await saveMeasure({ tempUnit: temp, weightUnit: weight, homeArea: area, hasScale });
        break;
      case "grinder": {
        let choice: GrinderChoice | null = null;
        if (grinderKind === "ground") choice = { kind: "ground" };
        else if (grinderKind === "blade") choice = { kind: "catalog", id: BLADE_GRINDER_ID };
        else if (grinderKind === "burr" && grinderId) choice = { kind: "catalog", id: grinderId };
        else if (grinderKind === "burr" && custom && (custom.brand.trim() || custom.model.trim()))
          choice = { kind: "custom", brand: custom.brand, model: custom.model, settingStyle: custom.settingStyle, clicksPerRotation: Number(custom.cpr || "0") };
        if (choice) await saveGrinder(choice, store.get().grinders);
        break;
      }
      case "brewers":
        await saveBrewers(owned);
        break;
      case "coffee":
        await saveCoffee({ favouriteRoasterIds: roasters, defaultWaterId: water });
        break;
      case "done":
        break;
    }
  }

  const next = async () => {
    if (busy || step >= STEPS.length - 1) return;
    setBusy(true);
    try {
      await persist(step);
      setStep(step + 1);
    } finally {
      setBusy(false);
    }
  };

  const leave = async (then: () => void) => {
    await finish();
    then();
  };
  const skip = () => void leave(() => nav.back());
  const back = () => {
    if (step > 0) setStep(step - 1);
    else nav.back();
  };

  const last = step === STEPS.length - 1;
  const recipes = useRecipes();
  const firstId = useMemo(() => (last ? (firstBrew(recipes, st, grinders, (id) => Brewers.byId(id)).recipe?.id ?? null) : null), [last, recipes, st, grinders]);

  // Esc = Back a step (newest scope wins over the shell's Back); a field just loses focus first.
  const keys = useMemo(
    () => [
      {
        keys: "escape",
        label: "Back a step",
        group: "General",
        run: (e: globalThis.KeyboardEvent) => {
          if (isTypingTarget(e.target)) (e.target as HTMLElement).blur();
          else back();
        },
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [step],
  );
  useShortcuts("onboarding", keys, onTop);

  const stepId = STEPS[step]!;
  let footer: ReactNode;
  if (last) {
    footer = (
      <>
        {firstId ? (
          <PrimaryButton text="Start my first brew" enabled={!busy} onClick={() => void leave(() => nav.replace(`/brew/${encodeURIComponent(firstId)}`))} />
        ) : (
          <PrimaryButton text="See all recipes" enabled={!busy} onClick={() => void leave(() => nav.popToRoot("recipes"))} />
        )}
        <QuietButton text="Go to Today" onClick={() => void leave(() => nav.popToRoot("today"))} />
      </>
    );
  } else {
    footer = <PrimaryButton text="Next" enabled={!busy} onClick={() => void next()} />;
  }

  const body = (id: StepId, i: number): ReactNode => {
    switch (id) {
      case "you":
        return <YouStep index={i} value={experience} onValue={pickExperience} />;
      case "measure":
        return (
          <MeasureStep
            index={i}
            hasScale={hasScale}
            temp={temp}
            weight={weight}
            area={area}
            onScale={(v) => {
              setScaleTouched(true);
              setHasScale(v);
            }}
            onTemp={setTemp}
            onWeight={setWeight}
            onArea={setArea}
          />
        );
      case "grinder":
        return <GrinderStep index={i} kind={grinderKind} grinderId={grinderId} custom={custom} onKind={setGrinderKind} onGrinder={setGrinderId} onCustom={setCustom} />;
      case "brewers":
        return <BrewersStep index={i} owned={owned} onOwned={setOwned} />;
      case "coffee":
        return (
          <CoffeeStep
            index={i}
            roasters={roasters}
            water={water}
            unsure={unsureWater}
            onRoasters={setRoasters}
            onWater={(id, unsure) => {
              setWater(id);
              setUnsureWater(unsure);
            }}
          />
        );
      case "done":
        return <FirstBrewStep index={i} onAi={() => setAiOpen(true)} />;
    }
  };

  return (
    // Enter = Next anywhere that is not a control with its own Enter (§3.11).
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      className={s.screen}
      onKeyDown={(e) => {
        if (!last && enterIsNext(e)) {
          e.preventDefault();
          void next();
        }
      }}
    >
      <div className={s.mesh} aria-hidden="true">
        <MeshGradient mood="idle" />
      </div>
      <div className={s.frame}>
        <Why step={stepId} owned={owned} />
        <div className={s.body}>
          <div className={s.top}>
            <IconWell icon="ChevronLeft" label="Back" keys="escape" onClick={back} />
            <div className={s.dots} role="img" aria-label={`Step ${step + 1} of ${STEPS.length}`}>
              {STEPS.map((id, i) => (
                <span key={id} className={s.dot} data-on={i === step} data-done={i < step} />
              ))}
            </div>
            <QuietButton text="Skip for now" onClick={skip} />
          </div>

          {STEPS.map((id, i) =>
            visited.has(i) ? (
              <div
                key={id}
                ref={(el) => {
                  panes.current[i] = el;
                }}
                className={s.step}
                hidden={i !== step}
              >
                {body(id, i)}
              </div>
            ) : null,
          )}

          <div className={s.footer}>{footer}</div>
        </div>
      </div>
      {aiOpen && <AiSettingsSheet onClose={() => setAiOpen(false)} />}
    </div>
  );
}

/** Chunk + data warm-up, called on pointerdown of anything that navigates here. */
export function prefetch(): Promise<unknown> {
  return catalog.load(["grinders", "roasters", "water", "brewers"]);
}
