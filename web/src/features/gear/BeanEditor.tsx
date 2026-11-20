/**
 * Bean editor (A§7.6, v4 §4.7). The fields in the order a supermarket shopper and a specialty
 * drinker both meet them: Name, Roaster, Whole beans / Ground, Roasted on, Roast level, Bag size,
 * then "More details" (origin, farm, process, lot and the rest). Each section is its own
 * memoised component reading only its fields, so a keystroke re-renders one section.
 *
 * One form, two homes: `BeanEditorSheet` (role "editor": a full-height sheet below expanded, a
 * right side sheet when opened elsewhere at expanded+) and `BeanEditorPane` (inline in Gear's
 * detail pane at expanded+, Save/Revert in the pane header, v4 §3.10).
 */
import { memo, useEffect, useId, useRef, useState, type RefObject } from "react";
import { catalog } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import type { CatalogOrigin } from "@/catalog/types";
import { CoffeeSheet, EyebrowLabel, FieldWell, PillButton, SegmentedControl, SelectableChip, ToggleRow } from "@/design/components";
import { repo } from "@/data/repo";
import type { BeanRow, BrewRow } from "@/data/schema";
import { useSettings } from "@/data/store";
import { epochDayOf, grams, oneDp } from "@/domain/core/format";
import { GRAMS_PER_OUNCE, unitsOf } from "@/domain/core/units";
import { processGroupLabel } from "@/domain/search";
import { CatalogPickerSheet, type CatalogKind } from "@/features/catalog-picker";
import { NumberStepper } from "@/features/grind";
import { PhotoStrip } from "@/features/photos";
import { BeanBrews } from "./BeanBrews";
import { rowsDiffer } from "./inlineEdit";
import { feetToMetres, joinAltitude, localCurrency, metresToFeetText, restStatus, roastedAgoText, splitAltitude } from "./mapping";
import { ChipGroup, ChoiceChips, DateField, Disclosure, PickRow, SheetNote } from "./parts";
import { PaneDeleteRow, PaneEditorHeader } from "./PaneEditor";
import { CloseBridge, SheetSaveRow } from "./SheetSaveRow";
import s from "./gear.module.css";

type Update = (patch: Partial<BeanRow>) => void;
type PickKind = Extract<CatalogKind, "roaster" | "origin" | "varietal">;
type OpenPicker = (kind: PickKind) => void;

const ROAST_DATE = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });

const GROUND_FOR: readonly { id: Exclude<BeanRow["groundFor"], "">; label: string }[] = [
  { id: "filter", label: "Filter" },
  { id: "press", label: "Press" },
  { id: "espresso", label: "Espresso" },
  { id: "moka", label: "Moka" },
];

function today(): number {
  return epochDayOf(Date.now());
}

function parseNum(t: string): number | null {
  const v = Number(t.trim().replace(",", "."));
  return t.trim() !== "" && Number.isFinite(v) ? v : null;
}

/** Anything under "More details" already recorded: the disclosure starts open. */
function hasDetails(b: BeanRow): boolean {
  return Boolean(
    b.lotTypeId || b.origin || b.region || b.farm || b.producer || b.washingStation || b.altitudeMasl || b.processId || b.isDecaf || b.speciesId || b.varietalIds.length ||
      b.varietal || b.harvest || b.roastIntentId || b.restDays != null || b.openedOnEpochDay != null || b.price || b.priceAmount != null || b.tastingNotes || b.notes,
  );
}

/** A fresh coffee starts in the local currency (the price field's default). */
export function beanStart(initial: BeanRow): BeanRow {
  return initial.id == null && !initial.currency ? { ...initial, currency: localCurrency() } : initial;
}

// ---------------------------------------------------------------- 1. Name, roaster, form

const EssentialsSection = memo(function EssentialsSection({
  name,
  roaster,
  form,
  groundFor,
  update,
  openPicker,
  roasterRef,
  roasterId,
}: Pick<BeanRow, "name" | "roaster" | "form" | "groundFor" | "roasterId"> & { update: Update; openPicker: OpenPicker; roasterRef: RefObject<HTMLButtonElement | null> }) {
  const ground = form === "ground";
  useCatalog(["roasters"]);
  const site = roasterId ? (catalog.roaster(roasterId)?.website ?? "") : "";
  return (
    <section className={s.section} aria-label="The coffee">
      <FieldWell label="Coffee name" value={name} placeholder="Kiamabara AA, or House Blend" onChange={(v) => update({ name: v })} maxLength={80} />
      <PickRow label="Roaster" value={roaster} placeholder="Pick a roaster or brand" onClick={() => openPicker("roaster")} anchorRef={roasterRef} />
      {/* A catalog roaster's own site, in a new tab; nothing is fetched by the app. */}
      {site && (
        <div className={s.linkRow}>
          <a className={`${s.link} t-label-l`} href={site} target="_blank" rel="noopener noreferrer">
            Roaster's site<span className="visually-hidden"> (opens in a new tab)</span>
          </a>
        </div>
      )}
      <div className={s.field}>
        <EyebrowLabel text="Whole beans or ground" />
        <SegmentedControl
          options={["Whole beans", "Ground"]}
          selectedIndex={ground ? 1 : 0}
          onSelect={(i) => update(i === 1 ? { form: "ground" } : { form: "whole", groundFor: "" })}
          onCard
          label="Whole beans or ground"
        />
      </div>
      {ground && (
        <div className={s.reveal}>
          <ChoiceChips label="Ground for" options={GROUND_FOR} value={groundFor} onChange={(v) => update({ groundFor: v ?? "" })} />
          <SheetNote text="Ground coffee skips the grind settings when you brew." />
        </div>
      )}
    </section>
  );
});

// ---------------------------------------------------------------- 2. Roasted on, roast level, bag size

const BasicsSection = memo(function BasicsSection({
  roastedOnEpochDay,
  roastLevelId,
  bagSizeG,
  ounces,
  update,
}: Pick<BeanRow, "roastedOnEpochDay" | "roastLevelId" | "bagSizeG"> & { ounces: boolean; update: Update }) {
  const levels = catalog.beans.roastLevels;
  const t = today();
  const days = roastedOnEpochDay == null ? null : Math.max(0, t - roastedOnEpochDay);
  const toText = (g: number | null) => (g == null ? "" : ounces ? oneDp(g / GRAMS_PER_OUNCE) : grams(g));
  const [bag, setBag] = useState(toText(bagSizeG));
  const setDays = (d: number | null) => update({ roastedOnEpochDay: d == null ? null : t - Math.max(0, Math.min(999, d)) });
  const daysId = useId();
  return (
    <section className={s.section} aria-label="Roast and bag">
      <div className={s.row2}>
        <DateField label="Roasted on" day={roastedOnEpochDay} max={t} onDay={(d) => update({ roastedOnEpochDay: d })} />
        <div className={s.field}>
          <span className="eyebrow" id={daysId}>
            Or days ago
          </span>
          <NumberStepper
            label="Days since roasting"
            value={days == null ? "" : String(days)}
            min={0}
            max={999}
            describedBy={daysId}
            onText={(v) => setDays(v.trim() === "" ? null : Number(v.replace(/[^\d]/g, "")))}
            onStep={(by) => setDays(Math.max(0, (days ?? 0) + by))}
          />
        </div>
      </div>
      <SheetNote
        text={
          days != null
            ? `${roastedAgoText(days)} (${ROAST_DATE.format(new Date(roastedOnEpochDay! * 86_400_000))}).`
            : "The one field that pays for itself. On a supermarket bag, use the best-before date's roast line if it has one."
        }
      />
      <ChoiceChips label="Roast level" options={levels} value={roastLevelId} onChange={(v) => update({ roastLevelId: v ?? "" })} describe={(id) => levels.find((l) => l.id === id)?.description} />
      <FieldWell
        label="Bag size"
        value={bag}
        suffix={ounces ? "oz" : "g"}
        numeric
        onChange={(v) => {
          setBag(v);
          const n = parseNum(v);
          update({ bagSizeG: n == null ? null : Math.round((ounces ? n * GRAMS_PER_OUNCE : n) * 10) / 10 });
        }}
      />
    </section>
  );
});

// ---------------------------------------------------------------- 3. More details: lot and origin

const OriginSection = memo(function OriginSection({
  lotTypeId,
  origin,
  originId,
  region,
  farm,
  producer,
  washingStation,
  altitudeMasl,
  update,
  openPicker,
  originRef,
}: Pick<BeanRow, "lotTypeId" | "origin" | "originId" | "region" | "farm" | "producer" | "washingStation" | "altitudeMasl"> & { update: Update; openPicker: OpenPicker; originRef: RefObject<HTMLButtonElement | null> }) {
  const lotTypes = catalog.beans.lotTypes;
  const o: CatalogOrigin | null = catalog.origin(originId);
  const regions = o?.growingRegions ?? [];
  const [other, setOther] = useState(region !== "" && !regions.includes(region));
  const [feet, setFeet] = useState(false);
  const [alt, setAlt] = useState(() => splitAltitude(altitudeMasl));
  const showText = other || (region !== "" && !regions.includes(region)) || regions.length === 0;
  // In feet the fields show feet; what is stored is always metres ("1700-1900").
  const shown = feet ? splitAltitude(metresToFeetText(joinAltitude(alt[0], alt[1]))) : alt;
  const setEnd = (i: 0 | 1, v: string) => {
    const digits = v.replace(/[^\d]/g, "").slice(0, 5);
    const metres = feet ? feetToMetres(digits) : digits.slice(0, 4);
    const next: [string, string] = i === 0 ? [metres, alt[1]] : [alt[0], metres];
    setAlt(next);
    update({ altitudeMasl: joinAltitude(next[0], next[1]) });
  };
  return (
    <section className={s.section} aria-label="Lot and origin">
      {lotTypes.length > 0 && (
        <ChoiceChips label="Lot" options={lotTypes} value={lotTypeId} onChange={(v) => update({ lotTypeId: v })} describe={(id) => lotTypes.find((t) => t.id === id)?.description} />
      )}
      <PickRow label="Origin" value={origin} placeholder="Pick a country" onClick={() => openPicker("origin")} anchorRef={originRef} />
      {regions.length > 0 && (
        <ChipGroup label="Region">
          {regions.map((r) => (
            <SelectableChip
              key={r}
              text={r}
              selected={region === r}
              onClick={() => {
                setOther(false);
                update({ region: region === r ? "" : r });
              }}
            />
          ))}
          <SelectableChip
            text="Other…"
            selected={showText}
            onClick={() => {
              setOther(true);
              if (regions.includes(region)) update({ region: "" });
            }}
          />
        </ChipGroup>
      )}
      {showText && <FieldWell label={regions.length ? "Other region" : "Region"} value={region} onChange={(v) => update({ region: v })} />}
      <FieldWell label="Farm / estate" value={farm} onChange={(v) => update({ farm: v })} />
      <div className={s.row2}>
        <FieldWell label="Producer" value={producer} onChange={(v) => update({ producer: v })} />
        <FieldWell label="Washing station" value={washingStation} onChange={(v) => update({ washingStation: v })} />
      </div>
      <div className={s.field}>
        <div className={s.labelRow}>
          <EyebrowLabel text="Altitude" />
          <div className={s.unitToggle}>
            <SegmentedControl options={["m", "ft"]} selectedIndex={feet ? 1 : 0} onSelect={(i) => setFeet(i === 1)} onCard label="Altitude unit" />
          </div>
        </div>
        <div className={s.row2}>
          <FieldWell label="From" value={shown[0]} suffix={feet ? "ft" : "m"} numeric onChange={(v) => setEnd(0, v)} />
          <FieldWell label="To" value={shown[1]} suffix={feet ? "ft" : "m"} numeric onChange={(v) => setEnd(1, v)} />
        </div>
      </div>
    </section>
  );
});

// ---------------------------------------------------------------- 4. Process

const ProcessSection = memo(function ProcessSection({ processId, isDecaf, decafMethodId, originId, update }: Pick<BeanRow, "processId" | "isDecaf" | "decafMethodId" | "originId"> & { update: Update }) {
  const all = catalog.beans.processes;
  const o = catalog.origin(originId);
  const common = (o?.commonProcesses ?? []).map((id) => catalog.process(id)).filter((p): p is NonNullable<typeof p> => p != null);
  const commonIds = new Set(common.map((p) => p.id));
  const groups = new Map<string, typeof all>();
  for (const p of all) {
    if (commonIds.has(p.id)) continue;
    const list = groups.get(p.group) ?? [];
    groups.set(p.group, [...list, p]);
  }
  const selected = catalog.process(processId);
  const chip = (p: (typeof all)[number]) => (
    <SelectableChip key={p.id} text={p.label} selected={processId === p.id} onClick={() => update({ processId: processId === p.id ? "" : p.id })} />
  );
  return (
    <section className={s.section} aria-label="Process">
      <EyebrowLabel text="Process" as="h3" />
      {common.length > 0 && <ChipGroup label={`Common in ${o!.country}`}>{common.map(chip)}</ChipGroup>}
      {[...groups].map(([group, list]) => (
        <ChipGroup key={group} label={processGroupLabel(group)}>
          {list.map(chip)}
        </ChipGroup>
      ))}
      {selected && <p className={`t-body-s ${s.described}`}>{selected.description}</p>}
      <ToggleRow label="Decaf" checked={isDecaf} onChange={(v) => update({ isDecaf: v, decafMethodId: v ? decafMethodId : null })} />
      {isDecaf && catalog.beans.decafMethods.length > 0 && (
        <div className={s.reveal}>
          <ChoiceChips label="Decaf method" options={catalog.beans.decafMethods} value={decafMethodId} onChange={(v) => update({ decafMethodId: v })} />
        </div>
      )}
    </section>
  );
});

// ---------------------------------------------------------------- 5. Varietal

const VarietalSection = memo(function VarietalSection({
  speciesId,
  varietalIds,
  varietal,
  originId,
  update,
  openPicker,
  varietalRef,
}: Pick<BeanRow, "speciesId" | "varietalIds" | "varietal" | "originId"> & { update: Update; openPicker: OpenPicker; varietalRef: RefObject<HTMLSpanElement | null> }) {
  const o = catalog.origin(originId);
  const quick = (o?.commonVarietals ?? []).filter((id) => catalog.varietal(id));
  const extra = varietalIds.filter((id) => !quick.includes(id));
  const toggle = (id: string) => update({ varietalIds: varietalIds.includes(id) ? varietalIds.filter((x) => x !== id) : [...varietalIds, id] });
  return (
    <section className={s.section} aria-label="Varietal">
      <EyebrowLabel text="Varietal" as="h3" />
      <ChoiceChips label="Species" options={catalog.beans.species} value={speciesId} onChange={(v) => update({ speciesId: v })} />
      <ChipGroup label={o ? `Varietals · common in ${o.country}` : "Varietals"}>
        {[...quick, ...extra].map((id) => (
          <SelectableChip key={id} text={catalog.varietal(id)?.label ?? id} selected={varietalIds.includes(id)} onClick={() => toggle(id)} />
        ))}
        <span ref={varietalRef} className={s.anchorWrap}>
          <PillButton text={quick.length || extra.length ? "More varietals…" : "Pick varietals…"} onClick={() => openPicker("varietal")} />
        </span>
      </ChipGroup>
      <FieldWell label="Other varietal names" value={varietal} helper="For anything not on the list, comma-separated." onChange={(v) => update({ varietal: v })} />
    </section>
  );
});

// ---------------------------------------------------------------- 6. Harvest, rest, opened

const HarvestSection = memo(function HarvestSection({
  harvest,
  roastIntentId,
  roastedOnEpochDay,
  restDays,
  openedOnEpochDay,
  update,
}: Pick<BeanRow, "harvest" | "roastIntentId" | "roastedOnEpochDay" | "restDays" | "openedOnEpochDay"> & { update: Update }) {
  const t = today();
  const rest = restStatus({ roastedOnEpochDay, restDays }, t);
  return (
    <section className={s.section} aria-label="Harvest and rest">
      <FieldWell label="Harvest" value={harvest} placeholder="As the bag says it" onChange={(v) => update({ harvest: v })} />
      <ChoiceChips label="Roasted for" options={catalog.beans.roastIntents} value={roastIntentId} onChange={(v) => update({ roastIntentId: v })} />
      <div className={s.field}>
        <EyebrowLabel text="Rest before opening" />
        <NumberStepper
          label="Rest days"
          value={restDays == null ? "" : String(restDays)}
          min={0}
          onText={(v) => update({ restDays: v === "" ? null : Math.min(90, Number(v)) })}
          onStep={(by) => update({ restDays: Math.max(0, (restDays ?? 0) + by) })}
        />
        <p className={`t-body-s ${s.described}`}>{rest ?? "The rest your roaster suggests before opening, if any. In days."}</p>
      </div>
      <DateField label="Opened on" day={openedOnEpochDay} max={t} onDay={(d) => update({ openedOnEpochDay: d })} />
    </section>
  );
});

// ---------------------------------------------------------------- 7. Price, notes, photo

const NotesSection = memo(function NotesSection({ id, priceAmount, price, currency, tastingNotes, notes, update }: Pick<BeanRow, "id" | "priceAmount" | "price" | "currency" | "tastingNotes" | "notes"> & { update: Update }) {
  const [priceText, setPriceText] = useState(priceAmount != null ? String(priceAmount) : price);
  return (
    <section className={s.section} aria-label="Price and notes">
      <div className={s.row2}>
        <FieldWell
          label="Price"
          value={priceText}
          numeric
          onChange={(v) => {
            setPriceText(v);
            const n = parseNum(v);
            // 1.x kept price as free text; a number goes in priceAmount, anything else stays text.
            update(n != null ? { priceAmount: n, price: "" } : { priceAmount: null, price: v });
          }}
        />
        <FieldWell label="Currency" value={currency} placeholder="GBP" maxLength={3} onChange={(v) => update({ currency: v.toUpperCase().replace(/[^A-Z]/g, "") })} />
      </div>
      <FieldWell label="Roaster's tasting notes" value={tastingNotes} multiline onChange={(v) => update({ tastingNotes: v })} />
      <FieldWell label="Your notes" value={notes} multiline onChange={(v) => update({ notes: v })} />
      {id != null ? (
        <div className={s.field}>
          <EyebrowLabel text="Photo of the bag" />
          <PhotoStrip beanId={id} kinds={["bag"]} editable />
        </div>
      ) : (
        <SheetNote text="Save the coffee first to add a photo of the bag." />
      )}
    </section>
  );
});

// ---------------------------------------------------------------- the form

/** The draft and its updater. `update` is stable, so memoised sections skip unrelated keystrokes. */
function useBeanDraft(start: BeanRow, onDraft?: (b: BeanRow) => void) {
  const [bean, setBean] = useState<BeanRow>(start);
  const [update] = useState<Update>(() => (patch: Partial<BeanRow>) => setBean((b) => ({ ...b, ...patch })));
  const onDraftRef = useRef(onDraft);
  useEffect(() => {
    onDraftRef.current = onDraft;
  });
  // Reported after the render that made it, so the owner never re-renders on a keystroke.
  useEffect(() => {
    onDraftRef.current?.(bean);
  }, [bean]);
  return { bean, setBean, update };
}

function BeanForm({ bean, update, ounces, isNew, keyPrefix }: { bean: BeanRow; update: Update; ounces: boolean; isNew: boolean; keyPrefix: string }) {
  // The sections read the catalog while rendering, and the compiler caches those reads on props
  // alone. Remounting them once the files land (one time, after a cold start) swaps the empty
  // lists for the real ones.
  const ck = keyPrefix + (useCatalog(["beans", "origins", "roasters"]) ? ":catalog" : ":waiting");
  const [more, setMore] = useState(() => hasDetails(bean));
  const [picker, setPicker] = useState<PickKind | null>(null);
  const [openPicker] = useState<OpenPicker>(() => (kind: PickKind) => setPicker(kind));
  const roasterRef = useRef<HTMLButtonElement>(null);
  const originRef = useRef<HTMLButtonElement>(null);
  const varietalRef = useRef<HTMLSpanElement>(null);

  return (
    <>
      <EssentialsSection key={ck} name={bean.name} roaster={bean.roaster} roasterId={bean.roasterId} form={bean.form} groundFor={bean.groundFor} update={update} openPicker={openPicker} roasterRef={roasterRef} />
      <BasicsSection key={`${ck}:${ounces}`} roastedOnEpochDay={bean.roastedOnEpochDay} roastLevelId={bean.roastLevelId} bagSizeG={bean.bagSizeG} ounces={ounces} update={update} />
      <Disclosure label="More details" hint="Origin, farm, process, lot" open={more} onToggle={setMore}>
        <OriginSection
          key={ck}
          lotTypeId={bean.lotTypeId}
          origin={bean.origin}
          originId={bean.originId}
          region={bean.region}
          farm={bean.farm}
          producer={bean.producer}
          washingStation={bean.washingStation}
          altitudeMasl={bean.altitudeMasl}
          update={update}
          openPicker={openPicker}
          originRef={originRef}
        />
        <ProcessSection key={`p${ck}`} processId={bean.processId} isDecaf={bean.isDecaf} decafMethodId={bean.decafMethodId} originId={bean.originId} update={update} />
        <VarietalSection
          key={`v${ck}`}
          speciesId={bean.speciesId}
          varietalIds={bean.varietalIds}
          varietal={bean.varietal}
          originId={bean.originId}
          update={update}
          openPicker={openPicker}
          varietalRef={varietalRef}
        />
        <HarvestSection
          harvest={bean.harvest}
          roastIntentId={bean.roastIntentId}
          roastedOnEpochDay={bean.roastedOnEpochDay}
          restDays={bean.restDays}
          openedOnEpochDay={bean.openedOnEpochDay}
          update={update}
        />
        <NotesSection id={bean.id} priceAmount={bean.priceAmount} price={bean.price} currency={bean.currency} tastingNotes={bean.tastingNotes} notes={bean.notes} update={update} />
      </Disclosure>

      {!isNew && <ToggleRow label="Finished this bag" checked={bean.isArchived} onChange={(v) => update({ isArchived: v })} subtitle="Hidden from brew setup. Its brews stay in the log." />}

      {picker === "roaster" && (
        <CatalogPickerSheet
          kind="roaster"
          title="Roaster"
          anchor={roasterRef}
          selected={bean.roasterId ? [bean.roasterId] : []}
          onPick={(p) => update("id" in p ? { roasterId: p.id, roaster: catalog.roaster(p.id)?.name ?? "" } : { roasterId: null, roaster: p.custom })}
          onClose={() => setPicker(null)}
        />
      )}
      {picker === "origin" && (
        <CatalogPickerSheet
          kind="origin"
          title="Origin"
          anchor={originRef}
          selected={bean.originId ? [bean.originId] : []}
          onPick={(p) => {
            if ("id" in p) {
              const changed = p.id !== bean.originId;
              // A new country's region list does not include the old region.
              update({ originId: p.id, origin: catalog.origin(p.id)?.country ?? "", ...(changed ? { region: "" } : {}) });
            } else update({ originId: null, origin: p.custom });
          }}
          onClose={() => setPicker(null)}
        />
      )}
      {picker === "varietal" && (
        <CatalogPickerSheet
          kind="varietal"
          title="Varietals"
          anchor={varietalRef}
          multiSelect
          selected={bean.varietalIds}
          onPick={() => undefined}
          onDone={(ids) => update({ varietalIds: ids })}
          onClose={() => setPicker(null)}
        />
      )}
    </>
  );
}

/** Saves a coffee; returns its id. Only the name is required. */
async function saveBean(bean: BeanRow): Promise<number> {
  return repo.upsertBean({ ...bean, name: bean.name.trim() });
}

// ---------------------------------------------------------------- the sheet

export function BeanEditorSheet({ initial, draft, onClose, onDelete }: { initial: BeanRow; draft?: BeanRow; onClose: () => void; onDelete?: (b: BeanRow) => void }) {
  const isNew = initial.id == null;
  const st = useSettings();
  const base = beanStart(initial);
  const { bean, update } = useBeanDraft(draft ?? base);
  const [saving, setSaving] = useState(false);
  const closeRef = useRef<(() => void) | null>(null);
  const dirty = rowsDiffer(bean, base);
  const canSave = bean.name.trim() !== "";
  const title = isNew ? "New coffee" : "Edit coffee";

  const save = async () => {
    if (saving || !canSave) return;
    setSaving(true);
    try {
      await saveBean(bean);
      closeRef.current?.();
    } finally {
      setSaving(false);
    }
  };

  return (
    <CoffeeSheet role="editor" label={title} title={title} onSave={() => void save()} dirty={dirty} onClose={onClose} full>
      <CloseBridge closeRef={closeRef} />
      <div className={s.sheetContent}>
        <h2 className={`t-headline-s ${s.sheetTitle}`}>{title}</h2>
        <BeanForm bean={bean} update={update} ounces={unitsOf(st).ounces} isNew={isNew} keyPrefix={initial.uid} />
        <SheetSaveRow canSave={canSave} saving={saving} onSave={() => void save()} onDelete={!isNew && onDelete ? () => onDelete(initial) : undefined} />
      </div>
    </CoffeeSheet>
  );
}

// ---------------------------------------------------------------- inline (expanded+)

/**
 * The editor inline in Gear's detail pane (v4 §3.10): a sticky pane header with Revert and Save
 * (Ctrl/Cmd+S), the form, then "Brewed with this coffee" for a saved coffee. `saved` is the row
 * on disk (null for a new one, compared with `template`); `draft` is where you left off.
 */
export function BeanEditorPane({
  saved,
  template,
  draft,
  brews,
  onDraft,
  onSaved,
  onDelete,
}: {
  saved: BeanRow | null;
  template: BeanRow;
  draft?: BeanRow;
  brews: readonly BrewRow[];
  onDraft: (b: BeanRow) => void;
  onSaved: (b: BeanRow) => void;
  onDelete?: (b: BeanRow) => void;
}) {
  const st = useSettings();
  const base = beanStart(saved ?? template);
  const { bean, setBean, update } = useBeanDraft(draft ?? base, onDraft);
  const [saving, setSaving] = useState(false);
  const [formKey, setFormKey] = useState(0);
  const dirty = rowsDiffer(bean, base);
  const canSave = bean.name.trim() !== "" && dirty;
  const isNew = saved == null;

  const save = async () => {
    if (saving || !canSave) return;
    setSaving(true);
    try {
      const id = await saveBean(bean);
      onSaved({ ...bean, id, name: bean.name.trim() });
    } finally {
      setSaving(false);
    }
  };
  const revert = () => {
    setBean(base);
    // Fields that keep their own text (bag size, price, altitude) start again from the row.
    setFormKey((k) => k + 1);
  };

  return (
    <article className={s.paneEditor} aria-label={isNew ? "New coffee" : bean.name.trim() || "Coffee"}>
      <PaneEditorHeader
        isNew={isNew}
        eyebrow={isNew ? "New coffee" : bean.roaster.trim() || "Coffee"}
        title={bean.name.trim() || (isNew ? "New coffee" : "Unnamed")}
        dirty={dirty}
        canSave={canSave}
        saving={saving}
        onSave={() => void save()}
        onRevert={revert}
      />
      <div className={s.paneForm}>
        <BeanForm key={formKey} bean={bean} update={update} ounces={unitsOf(st).ounces} isNew={isNew} keyPrefix={`${bean.uid}:${formKey}`} />
        {saved?.id != null && <BeanBrews beanId={saved.id} brews={brews} />}
        {saved && onDelete && <PaneDeleteRow text="Delete this coffee" onClick={() => onDelete(saved)} />}
      </div>
    </article>
  );
}
