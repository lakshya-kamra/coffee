/**
 * Bean editor (A§7.6, replaces 1.x BeanSheet). Six sections, each its own component reading only
 * its fields, so a keystroke re-renders one section. Only the name is required; "More details"
 * opens sections 3–6 (open from the start when any of them is already filled in).
 */
import { memo, useRef, useState } from "react";
import { catalog } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import type { CatalogOrigin } from "@/catalog/types";
import { CoffeeSheet, EyebrowLabel, FieldWell, PillButton, PrimaryButton, QuietButton, SelectableChip, ToggleRow } from "@/design/components";
import { repo } from "@/data/repo";
import type { BeanRow } from "@/data/schema";
import { useSettings } from "@/data/store";
import { epochDayOf, grams, oneDp } from "@/domain/core/format";
import { GRAMS_PER_OUNCE, unitsOf } from "@/domain/core/units";
import { processGroupLabel } from "@/domain/search";
import { CatalogPickerSheet, closeEnclosingSheet, type CatalogKind } from "@/features/catalog-picker";
import { NumberStepper } from "@/features/grind";
import { PhotoStrip } from "@/features/photos";
import { joinAltitude, localCurrency, restStatus, splitAltitude } from "./mapping";
import { ChipGroup, ChoiceChips, DateField, Disclosure, PickRow, SheetNote, SheetTitle, useTwoTap } from "./parts";
import s from "./gear.module.css";

type Update = (patch: Partial<BeanRow>) => void;
type OpenPicker = (kind: Extract<CatalogKind, "roaster" | "origin" | "varietal">) => void;

const ROAST_DATE = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });

function today(): number {
  return epochDayOf(Date.now());
}

function parseNum(t: string): number | null {
  const v = Number(t.trim().replace(",", "."));
  return t.trim() !== "" && Number.isFinite(v) ? v : null;
}

/** Anything in sections 3–6 already recorded: "More details" starts open. */
function hasDetails(b: BeanRow): boolean {
  return Boolean(
    b.speciesId || b.varietalIds.length || b.varietal || b.processId || b.isDecaf || b.roastLevelId || b.roastIntentId || b.roastedOnEpochDay != null ||
      b.restDays != null || b.openedOnEpochDay != null || b.bagSizeG != null || b.price || b.priceAmount != null || b.tastingNotes || b.notes,
  );
}

// ---------------------------------------------------------------- 1. The coffee

const CoffeeSection = memo(function CoffeeSection({ name, roaster, lotTypeId, update, openPicker }: Pick<BeanRow, "name" | "roaster" | "lotTypeId"> & { update: Update; openPicker: OpenPicker }) {
  const lotTypes = catalog.beans.lotTypes;
  return (
    <section className={s.section} aria-label="The coffee">
      <FieldWell label="Coffee name" value={name} placeholder="Kiamabara AA" onChange={(v) => update({ name: v })} maxLength={80} />
      <PickRow label="Roaster" value={roaster} placeholder="Pick a roaster" onClick={() => openPicker("roaster")} />
      {lotTypes.length > 0 && (
        <ChoiceChips label="Lot" options={lotTypes} value={lotTypeId} onChange={(v) => update({ lotTypeId: v })} describe={(id) => lotTypes.find((t) => t.id === id)?.description} />
      )}
    </section>
  );
});

// ---------------------------------------------------------------- 2. Origin

const OriginSection = memo(function OriginSection({
  origin,
  originId,
  region,
  farm,
  producer,
  washingStation,
  altitudeMasl,
  harvest,
  update,
  openPicker,
}: Pick<BeanRow, "origin" | "originId" | "region" | "farm" | "producer" | "washingStation" | "altitudeMasl" | "harvest"> & { update: Update; openPicker: OpenPicker }) {
  const o: CatalogOrigin | null = catalog.origin(originId);
  const regions = o?.growingRegions ?? [];
  const [other, setOther] = useState(region !== "" && !regions.includes(region));
  const [alt, setAlt] = useState(() => splitAltitude(altitudeMasl));
  const showText = other || (region !== "" && !regions.includes(region)) || regions.length === 0;
  return (
    <section className={s.section} aria-label="Origin">
      <EyebrowLabel text="Origin" as="h3" />
      <PickRow label="Country" value={origin} placeholder="Pick a country" onClick={() => openPicker("origin")} />
      {regions.length > 0 && (
        <ChipGroup label="Growing region">
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
      <div className={s.row2}>
        <FieldWell
          label="Altitude from"
          value={alt[0]}
          suffix="m"
          numeric
          onChange={(v) => {
            const next: [string, string] = [v.replace(/[^\d]/g, "").slice(0, 4), alt[1]];
            setAlt(next);
            update({ altitudeMasl: joinAltitude(next[0], next[1]) });
          }}
        />
        <FieldWell
          label="Altitude to"
          value={alt[1]}
          suffix="m"
          numeric
          onChange={(v) => {
            const next: [string, string] = [alt[0], v.replace(/[^\d]/g, "").slice(0, 4)];
            setAlt(next);
            update({ altitudeMasl: joinAltitude(next[0], next[1]) });
          }}
        />
      </div>
      <FieldWell label="Harvest" value={harvest} placeholder="As the bag says it" onChange={(v) => update({ harvest: v })} />
    </section>
  );
});

// ---------------------------------------------------------------- 3. Plant

const PlantSection = memo(function PlantSection({ speciesId, varietalIds, varietal, originId, update, openPicker }: Pick<BeanRow, "speciesId" | "varietalIds" | "varietal" | "originId"> & { update: Update; openPicker: OpenPicker }) {
  const o = catalog.origin(originId);
  const quick = (o?.commonVarietals ?? []).filter((id) => catalog.varietal(id));
  const extra = varietalIds.filter((id) => !quick.includes(id));
  const toggle = (id: string) => update({ varietalIds: varietalIds.includes(id) ? varietalIds.filter((x) => x !== id) : [...varietalIds, id] });
  return (
    <section className={s.section} aria-label="Plant">
      <EyebrowLabel text="Plant" as="h3" />
      <ChoiceChips label="Species" options={catalog.beans.species} value={speciesId} onChange={(v) => update({ speciesId: v })} />
      <ChipGroup label={o ? `Varietals · common in ${o.country}` : "Varietals"}>
        {[...quick, ...extra].map((id) => (
          <SelectableChip key={id} text={catalog.varietal(id)?.label ?? id} selected={varietalIds.includes(id)} onClick={() => toggle(id)} />
        ))}
        <PillButton text={quick.length || extra.length ? "More varietals…" : "Pick varietals…"} onClick={() => openPicker("varietal")} />
      </ChipGroup>
      <FieldWell label="Other varietal names" value={varietal} helper="For anything not on the list, comma-separated." onChange={(v) => update({ varietal: v })} />
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

// ---------------------------------------------------------------- 5. Roast

const RoastSection = memo(function RoastSection({ roastLevelId, roastIntentId, roastedOnEpochDay, restDays, openedOnEpochDay, update }: Pick<BeanRow, "roastLevelId" | "roastIntentId" | "roastedOnEpochDay" | "restDays" | "openedOnEpochDay"> & { update: Update }) {
  const levels = catalog.beans.roastLevels;
  const t = today();
  const [daysAgo, setDaysAgo] = useState(roastedOnEpochDay == null ? "" : String(Math.max(0, t - roastedOnEpochDay)));
  const [seen, setSeen] = useState(roastedOnEpochDay);
  if (seen !== roastedOnEpochDay) {
    setSeen(roastedOnEpochDay);
    setDaysAgo(roastedOnEpochDay == null ? "" : String(Math.max(0, t - roastedOnEpochDay)));
  }
  const rest = restStatus({ roastedOnEpochDay, restDays }, t);
  return (
    <section className={s.section} aria-label="Roast">
      <EyebrowLabel text="Roast" as="h3" />
      <ChoiceChips label="Roast level" options={levels} value={roastLevelId} onChange={(v) => update({ roastLevelId: v ?? "" })} describe={(id) => levels.find((l) => l.id === id)?.description} />
      <ChoiceChips label="Roasted for" options={catalog.beans.roastIntents} value={roastIntentId} onChange={(v) => update({ roastIntentId: v })} />
      <div className={s.row2}>
        <DateField label="Roasted on" day={roastedOnEpochDay} max={t} onDay={(d) => update({ roastedOnEpochDay: d })} />
        <FieldWell
          label="Or days ago"
          value={daysAgo}
          suffix={daysAgo === "1" ? "day" : "days"}
          numeric
          onChange={(v) => {
            const d = v.replace(/[^\d]/g, "").slice(0, 3);
            setDaysAgo(d);
            const day = d ? t - Number(d) : null;
            setSeen(day);
            update({ roastedOnEpochDay: day });
          }}
        />
      </div>
      <SheetNote
        text={
          roastedOnEpochDay != null
            ? `Roasted on ${ROAST_DATE.format(new Date(roastedOnEpochDay * 86_400_000))}.`
            : "The one field that pays for itself. You will not remember it later."
        }
      />
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

// ---------------------------------------------------------------- 6. The bag

const BagSection = memo(function BagSection({
  id,
  bagSizeG,
  priceAmount,
  price,
  currency,
  tastingNotes,
  notes,
  ounces,
  update,
}: Pick<BeanRow, "id" | "bagSizeG" | "priceAmount" | "price" | "currency" | "tastingNotes" | "notes"> & { ounces: boolean; update: Update }) {
  const toText = (g: number | null) => (g == null ? "" : ounces ? oneDp(g / GRAMS_PER_OUNCE) : grams(g));
  const [bag, setBag] = useState(toText(bagSizeG));
  const [priceText, setPriceText] = useState(priceAmount != null ? String(priceAmount) : price);
  return (
    <section className={s.section} aria-label="The bag">
      <EyebrowLabel text="The bag" as="h3" />
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

// ---------------------------------------------------------------- the sheet

export function BeanEditorSheet({ initial, onClose }: { initial: BeanRow; onClose: () => void }) {
  // The sections below read the catalog while rendering, and the compiler caches those reads on
  // props alone. Remounting them once the files land (one time, right after a cold start) is what
  // swaps the empty lists for the real ones.
  const ck = useCatalog(["beans", "origins", "roasters"]) ? "catalog" : "waiting";
  const isNew = initial.id == null;
  const st = useSettings();
  const units = unitsOf(st);
  const [bean, setBean] = useState<BeanRow>(() => (isNew && !initial.currency ? { ...initial, currency: localCurrency() } : initial));
  const [more, setMore] = useState(() => hasDetails(initial));
  const [picker, setPicker] = useState<Extract<CatalogKind, "roaster" | "origin" | "varietal"> | null>(null);
  const [saving, setSaving] = useState(false);
  const [armed, tapDelete] = useTwoTap();
  const box = useRef<HTMLDivElement>(null);

  // Stable across renders (setState identity), so memoised sections skip unrelated keystrokes.
  const [update] = useState<Update>(() => (patch: Partial<BeanRow>) => setBean((b) => ({ ...b, ...patch })));
  const [openPicker] = useState<OpenPicker>(() => (kind: Parameters<OpenPicker>[0]) => setPicker(kind));

  const save = async () => {
    if (saving || !bean.name.trim()) return;
    setSaving(true);
    try {
      await repo.upsertBean({ ...bean, name: bean.name.trim() });
      closeEnclosingSheet(box.current);
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <CoffeeSheet label={isNew ? "New coffee" : "Edit coffee"} onClose={onClose} full>
        <div ref={box} className={s.sheetContent}>
          <SheetTitle text={isNew ? "New coffee" : "Edit coffee"} />
          <CoffeeSection key={ck} name={bean.name} roaster={bean.roaster} lotTypeId={bean.lotTypeId} update={update} openPicker={openPicker} />
          <OriginSection
            key={ck}
            origin={bean.origin}
            originId={bean.originId}
            region={bean.region}
            farm={bean.farm}
            producer={bean.producer}
            washingStation={bean.washingStation}
            altitudeMasl={bean.altitudeMasl}
            harvest={bean.harvest}
            update={update}
            openPicker={openPicker}
          />
          <Disclosure label="More details" open={more} onToggle={setMore}>
            <PlantSection key={ck} speciesId={bean.speciesId} varietalIds={bean.varietalIds} varietal={bean.varietal} originId={bean.originId} update={update} openPicker={openPicker} />
            <ProcessSection key={ck} processId={bean.processId} isDecaf={bean.isDecaf} decafMethodId={bean.decafMethodId} originId={bean.originId} update={update} />
            <RoastSection
              key={ck}
              roastLevelId={bean.roastLevelId}
              roastIntentId={bean.roastIntentId}
              roastedOnEpochDay={bean.roastedOnEpochDay}
              restDays={bean.restDays}
              openedOnEpochDay={bean.openedOnEpochDay}
              update={update}
            />
            <BagSection
              id={bean.id}
              bagSizeG={bean.bagSizeG}
              priceAmount={bean.priceAmount}
              price={bean.price}
              currency={bean.currency}
              tastingNotes={bean.tastingNotes}
              notes={bean.notes}
              ounces={units.ounces}
              update={update}
            />
          </Disclosure>

          {!isNew && (
            <ToggleRow label="Finished this bag" checked={bean.isArchived} onChange={(v) => update({ isArchived: v })} subtitle="Hidden from brew setup. Its brews stay in the log." />
          )}

          <div className={s.actions}>
            <PrimaryButton text={saving ? "Saving…" : "Save"} enabled={bean.name.trim() !== "" && !saving} onClick={() => void save()} />
            {!isNew && (
              <QuietButton
                text={armed ? "Tap again to delete" : "Delete"}
                color="var(--fault)"
                onClick={() => tapDelete(() => void repo.deleteBean(initial.id!).then(() => closeEnclosingSheet(box.current)))}
              />
            )}
          </div>
        </div>
      </CoffeeSheet>

      {picker === "roaster" && (
        <CatalogPickerSheet
          kind="roaster"
          title="Roaster"
          selected={bean.roasterId ? [bean.roasterId] : []}
          onPick={(p) => update("id" in p ? { roasterId: p.id, roaster: catalog.roaster(p.id)?.name ?? "" } : { roasterId: null, roaster: p.custom })}
          onClose={() => setPicker(null)}
        />
      )}
      {picker === "origin" && (
        <CatalogPickerSheet
          kind="origin"
          title="Country"
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
