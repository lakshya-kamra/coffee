/**
 * Onboarding (A§7.3, web-spec §11): five steps over the brew screen's mesh, with progress dots,
 * Back, Skip on every step, and Next that saves its own step (partial progress survives).
 * Visited steps stay mounted and hidden, so going back finds them as you left them; the
 * incoming step fades in with a 24 px drift (opacity + transform, spatialDefault).
 */
import { animate } from "motion";
import { useLayoutEffect, useMemo, useRef, useState } from "react";
import { prefersReducedMotion } from "@/app/transitionState";
import { nav } from "@/app/router";
import { catalog } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import { EyebrowLabel, FieldWell, IconWell, PillButton, PrimaryButton, QuietButton, SegmentedControl, SelectableChip, SoftCard, StatusChip } from "@/design/components";
import { effectsDefault, spatialDefault } from "@/design/motion";
import { newGrinder, type SettingStyle } from "@/data/schema";
import { store, useGrinders, useSettings } from "@/data/store";
import { scaleFor } from "@/domain/grind";
import { BREWER_CATEGORY_ORDER, brewerCategoryLabel, brewerTitle, docFor, grinderSubtitle } from "@/domain/search";
import { AiSettingsSheet } from "@/features/ai";
import { CatalogPickerList, CatalogPickerSheet } from "@/features/catalog-picker";
import { fieldsFromCatalog, scaleSummary } from "@/features/gear";
import { confidenceChip, DialReadingInput, startValue } from "@/features/grind";
import { MeshGradient } from "@/viz";
import { COMMON_BREWER_IDS, finish, saveBrewers, saveCoffee, saveGrinder, saveMeasure, type GrinderChoice } from "./persist";
import s from "./onboarding.module.css";

const STEPS = ["measure", "grinder", "brewers", "coffee", "done"] as const;

// ---------------------------------------------------------------- 1. How you measure

function MeasureStep({ temp, weight, area, onTemp, onWeight, onArea }: { temp: "c" | "f"; weight: "g" | "oz"; area: string; onTemp: (v: "c" | "f") => void; onWeight: (v: "g" | "oz") => void; onArea: (v: string) => void }) {
  const ready = useCatalog(["roasters"]);
  const areas = useMemo(() => (ready ? [...new Set(catalog.roasters.map((r) => r.area).filter(Boolean))].sort() : []), [ready]);
  return (
    <>
      <h1 className="t-display-s">How you measure</h1>
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
          <p className={`t-body-s ${s.described}`}>Roasters near you come first when you pick one.</p>
        </div>
      )}
    </>
  );
}

// ---------------------------------------------------------------- 2. Your grinder

interface CustomGrinder {
  brand: string;
  model: string;
  settingStyle: SettingStyle;
  cpr: string;
}

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

// ---------------------------------------------------------------- 3. Brewers you own

function BrewersStep({ owned, onOwned }: { owned: readonly string[]; onOwned: (ids: string[]) => void }) {
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
      <h1 className="t-display-s">Brewers you own</h1>
      <p className={`t-body-m ${s.lede}`}>Recipes for these come first. Pick as many as you like.</p>
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

// ---------------------------------------------------------------- 4. Your coffee

function CoffeeStep({ roasters, water, onRoasters, onWater }: { roasters: readonly string[]; water: string; onRoasters: (ids: string[]) => void; onWater: (id: string) => void }) {
  // Reads the catalog while rendering (water list, roaster names): the compiler would cache those
  // reads on props alone and keep the empty answer from before the files landed.
  "use no memo";
  useCatalog(["roasters", "water"]);
  const [picking, setPicking] = useState(false);
  const w = catalog.waterById(water);
  return (
    <>
      <h1 className="t-display-s">Your coffee</h1>
      <p className={`t-body-m ${s.lede}`}>Optional. Both help the app, and an AI if you add one, say more about your cups.</p>
      <div className={s.group} role="group" aria-label="Favourite roasters">
        <EyebrowLabel text="Favourite roasters" />
        <div className={s.chips}>
          {roasters.map((id) => (
            <SelectableChip key={id} text={docFor("roaster", id)?.title ?? id} selected onClick={() => onRoasters(roasters.filter((x) => x !== id))} />
          ))}
          <PillButton text={roasters.length ? "Add more…" : "Pick roasters…"} onClick={() => setPicking(true)} />
        </div>
      </div>
      {catalog.water.length > 0 && (
        <div className={s.group} role="group" aria-label="What water do you brew with?">
          <EyebrowLabel text="What water do you brew with?" />
          <div className={s.chips}>
            {catalog.water.map((x) => (
              <SelectableChip key={x.id} text={x.label} selected={water === x.id} onClick={() => onWater(water === x.id ? "" : x.id)} />
            ))}
          </div>
          {w && <p className={`t-body-s ${s.described}`}>{w.description}</p>}
        </div>
      )}
      {picking && <CatalogPickerSheet kind="roaster" title="Favourite roasters" multiSelect selected={[...roasters]} onPick={() => undefined} onDone={onRoasters} onClose={() => setPicking(false)} />}
    </>
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

export function OnboardingScreen() {
  const st = useSettings();
  const grinders = useGrinders();
  const [step, setStep] = useState(0);
  const [visited, setVisited] = useState<ReadonlySet<number>>(() => new Set([0]));
  if (!visited.has(step)) setVisited(new Set([...visited, step]));

  // Prefilled from settings, so "Run setup again" starts from what you chose before.
  const [temp, setTemp] = useState(st.tempUnit);
  const [weight, setWeight] = useState(st.weightUnit);
  const [area, setArea] = useState(st.homeArea);
  const [grinderId, setGrinderId] = useState<string | null>(() => grinders.find((g) => g.isDefault)?.catalogId ?? null);
  const [custom, setCustom] = useState<CustomGrinder | null>(null);
  const [owned, setOwned] = useState<string[]>(st.ownedBrewerIds);
  const [roasters, setRoasters] = useState<string[]>(st.favouriteRoasterIds);
  const [water, setWater] = useState(st.defaultWaterId);
  const [aiOpen, setAiOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const panes = useRef<(HTMLDivElement | null)[]>([]);
  const shown = useRef(step);

  useLayoutEffect(() => {
    const from = shown.current;
    if (from === step) return;
    shown.current = step;
    const el = panes.current[step];
    // A new step starts at its top (the detail layer is the scroller).
    const scroller = el ? scrollParent(el) : null;
    if (scroller) scroller.scrollTop = 0;
    if (!el) return;
    const dir = step > from ? 1 : -1;
    const a = prefersReducedMotion()
      ? animate(el, { opacity: [0, 1] }, effectsDefault)
      : animate(el, { opacity: [0, 1], transform: [`translateX(${dir * 24}px)`, "translateX(0px)"] }, spatialDefault);
    return () => a.stop();
  }, [step]);

  /** Saves the step you are on (A§7.3: each Next persists that step). */
  async function persist(i: number): Promise<void> {
    switch (STEPS[i]) {
      case "measure":
        await saveMeasure({ tempUnit: temp, weightUnit: weight, homeArea: area });
        break;
      case "grinder": {
        let choice: GrinderChoice | null = null;
        if (grinderId) choice = { kind: "catalog", id: grinderId };
        else if (custom && (custom.brand.trim() || custom.model.trim()))
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
    if (busy) return;
    setBusy(true);
    try {
      await persist(step);
      if (step < STEPS.length - 1) setStep(step + 1);
      else {
        await finish();
        nav.back();
      }
    } finally {
      setBusy(false);
    }
  };

  const skip = async () => {
    await finish();
    nav.back();
  };

  const back = () => {
    if (step > 0) setStep(step - 1);
    else nav.back();
  };

  const last = step === STEPS.length - 1;

  return (
    <div className={s.screen}>
      <div className={s.mesh} aria-hidden="true">
        <MeshGradient mood="idle" />
      </div>
      <div className={`column ${s.body}`}>
        <div className={s.top}>
          <IconWell icon="ChevronLeft" label="Back" onClick={back} />
          <div className={s.dots} role="img" aria-label={`Step ${step + 1} of ${STEPS.length}`}>
            {STEPS.map((id, i) => (
              <span key={id} className={s.dot} data-on={i === step} data-done={i < step} />
            ))}
          </div>
          {last ? <span style={{ width: 64 }} /> : <QuietButton text="Skip" onClick={() => void skip()} />}
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
              {id === "measure" && <MeasureStep temp={temp} weight={weight} area={area} onTemp={setTemp} onWeight={setWeight} onArea={setArea} />}
              {id === "grinder" && (
                <>
                  <h1 className="t-display-s">Your grinder</h1>
                  <p className={`t-body-m ${s.lede}`}>Pick it from the list so the app reads your numbers the way your dial does.</p>
                  {grinderId ? (
                    <GrinderSummary id={grinderId} onChange={() => setGrinderId(null)} />
                  ) : custom ? (
                    <>
                      <CustomGrinderFields value={custom} onChange={setCustom} />
                      <QuietButton text="Pick from the list instead" onClick={() => setCustom(null)} />
                    </>
                  ) : (
                    <>
                      <div className={s.pickerBox}>
                        <CatalogPickerList
                          kind="grinder"
                          focusSearch={false}
                          onPick={(p) => {
                            if ("id" in p) setGrinderId(p.id);
                            else {
                              const t = p.custom.trim();
                              const sp = t.indexOf(" ");
                              setCustom({ brand: sp < 0 ? t : t.slice(0, sp), model: sp < 0 ? "" : t.slice(sp + 1), settingStyle: "clicks", cpr: "" });
                            }
                          }}
                        />
                      </div>
                      <QuietButton text="Not listed? Add it yourself" onClick={() => setCustom({ brand: "", model: "", settingStyle: "clicks", cpr: "" })} />
                    </>
                  )}
                </>
              )}
              {id === "brewers" && <BrewersStep owned={owned} onOwned={setOwned} />}
              {id === "coffee" && <CoffeeStep roasters={roasters} water={water} onRoasters={setRoasters} onWater={setWater} />}
              {id === "done" && (
                <>
                  <h1 className="t-display-s">You're set</h1>
                  <p className={`t-body-m ${s.lede}`}>
                    Everything you chose is saved in this browser. Change any of it later in Gear. An AI key is optional: the app reads every brew offline, and a model of your choice can say more.
                  </p>
                  <PillButton text="Add an AI key (optional)" onClick={() => setAiOpen(true)} />
                </>
              )}
            </div>
          ) : null,
        )}

        <div className={s.footer}>
          <PrimaryButton text={last ? "Start brewing" : "Next"} enabled={!busy} onClick={() => void next()} />
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

