/**
 * Grinder editor (A§7.4, v4 §4.2, §3.10). "Which grinder?" fills everything from the catalog;
 * the scale shows as one line with "Edit the scale" for the manual numbers; the catalog's
 * dial-reading help, entry hint and starting points sit under it; usual filter and espresso can be
 * typed as stored numbers or as a dial reading. A blade grinder has no dial: it is timed, so its
 * usual setting is seconds.
 *
 * One form, two homes, as the bean editor: `GrinderEditorSheet` (role "editor") and
 * `GrinderEditorPane` (inline in Gear's detail pane at expanded+).
 */
import { useEffect, useRef, useState } from "react";
import { catalog } from "@/catalog/catalog";
import { useCatalog, useCatalogGrinder } from "@/catalog/useCatalog";
import { CoffeeSheet, FieldWell, QuietButton, SelectableChip, StatusChip, ToggleRow } from "@/design/components";
import { repo } from "@/data/repo";
import { grinderDisplay, type BurrType, type GrinderRow, type SettingStyle } from "@/data/schema";
import { settings } from "@/data/settings";
import { grams } from "@/domain/core/format";
import { format, usesRotation } from "@/domain/grind";
import { CatalogPickerSheet } from "@/features/catalog-picker";
import { confidenceChip, dialWords, DialReadingInput, GrindRangeCard, useGrindScale } from "@/features/grind";
import { rowsDiffer } from "./inlineEdit";
import { fieldsFromCatalog, grinderName, isTimed, scaleDifferences, scaleSummary } from "./mapping";
import { ChipGroup, Disclosure, PickRow, SheetNote } from "./parts";
import { PaneDeleteRow, PaneEditorHeader } from "./PaneEditor";
import { CloseBridge, SheetSaveRow } from "./SheetSaveRow";
import s from "./gear.module.css";

const BURRS: readonly { id: BurrType; label: string }[] = [
  { id: "conical", label: "Conical" },
  { id: "flat", label: "Flat" },
  { id: "ghost", label: "Ghost" },
  { id: "blade", label: "Blade" },
];

/** "Seconds" is a blade grinder: stepless underneath, so 2.0.0 readers still open the row. */
const STYLES: readonly { id: SettingStyle | "seconds"; label: string }[] = [
  { id: "clicks", label: "Clicks" },
  { id: "numbers", label: "Numbered dial" },
  { id: "stepless", label: "Stepless" },
  { id: "seconds", label: "Seconds (blade)" },
];

const DIAL_FORMATS: readonly { id: string; label: string }[] = [
  { id: "", label: "Auto" },
  { id: "plain", label: "Plain number" },
  { id: "turns", label: "Turns + clicks" },
  { id: "number-tick:10", label: "Numbered dial (10 marks a number)" },
];

export const BLADE_HELPER = "Pulse in short bursts and shake the grinder between them.";

function numText(v: number | null | undefined): string {
  return v == null ? "" : grams(v);
}

function parseNum(t: string): number | null {
  const v = Number(t.trim().replace(",", "."));
  return t.trim() !== "" && Number.isFinite(v) ? v : null;
}

/** Splits a typed custom name into brand and model at the first space ("Acme Mill 2"). */
function splitName(text: string): { brand: string; model: string } {
  const t = text.trim();
  const i = t.indexOf(" ");
  return i < 0 ? { brand: t, model: "" } : { brand: t.slice(0, i), model: t.slice(i + 1).trim() };
}

function UsualField({ label, g, value, onValue }: { label: string; g: GrinderRow; value: number | null; onValue: (v: number | null) => void }) {
  const scale = useGrindScale(g);
  const canDial = usesRotation(scale.dial);
  const [asDial, setAsDial] = useState(false);
  const [text, setText] = useState(numText(value));
  // Keep the typed text in step when the dial input changes the number.
  const [seen, setSeen] = useState(value);
  if (seen !== value) {
    setSeen(value);
    if (parseNum(text) !== value) setText(value == null ? "" : format(scale, value));
  }
  const unit = g.settingStyle === "clicks" ? "clicks" : scale.unit || undefined;
  return (
    <div className={s.section}>
      {asDial && canDial ? (
        <>
          <span className="eyebrow">{label}</span>
          <DialReadingInput scale={scale} value={value} onValue={onValue} />
        </>
      ) : (
        <FieldWell
          label={label}
          value={text}
          numeric
          suffix={unit}
          // Read back the way the dial is read, so 66 is visibly "dial 6.6" on a K-Ultra.
          helper={dialWords(scale, parseNum(text)) ?? undefined}
          onChange={(t) => {
            setText(t);
            onValue(parseNum(t));
          }}
        />
      )}
      {canDial && <QuietButton text={asDial ? "Type the number instead" : "Enter it as a dial reading"} onClick={() => setAsDial(!asDial)} />}
    </div>
  );
}

/** A blade grinder's usual time: "GRIND FOR 12 s" (v4 §4.2). */
function SecondsField({ value, onValue }: { value: number | null; onValue: (v: number | null) => void }) {
  const [text, setText] = useState(numText(value));
  return (
    <FieldWell
      label="Grind for"
      value={text}
      suffix="s"
      numeric
      placeholder="12"
      helper={BLADE_HELPER}
      onChange={(t) => {
        const d = t.replace(/[^\d]/g, "").slice(0, 3);
        setText(d);
        onValue(d ? Number(d) : null);
      }}
    />
  );
}

function useGrinderDraft(start: GrinderRow, onDraft?: (g: GrinderRow) => void) {
  const [g, setG] = useState(start);
  const onDraftRef = useRef(onDraft);
  useEffect(() => {
    onDraftRef.current = onDraft;
  });
  useEffect(() => {
    onDraftRef.current?.(g);
  }, [g]);
  return { g, setG };
}

function GrinderForm({ g, setG }: { g: GrinderRow; setG: (fn: (cur: GrinderRow) => GrinderRow) => void }) {
  useCatalog(["grinders"]);
  const [picking, setPicking] = useState(false);
  const [editScale, setEditScale] = useState(g.id == null ? false : g.catalogId == null);
  const [cprText, setCprText] = useState(g.clicksPerRotation > 0 ? String(g.clicksPerRotation) : "");
  const [micronsText, setMicronsText] = useState(numText(g.micronsPerClick));
  const [minText, setMinText] = useState(numText(g.minSetting));
  const [maxText, setMaxText] = useState(numText(g.maxSetting));
  const [stepText, setStepText] = useState(numText(g.step));
  const [secondsKey, setSecondsKey] = useState(0);
  const anchor = useRef<HTMLButtonElement>(null);

  const set = (patch: Partial<GrinderRow>) => setG((cur) => ({ ...cur, ...patch }));
  const cat = useCatalogGrinder(g.catalogId);
  const edited = cat ? scaleDifferences(g, cat).length > 0 : false;
  const chip = cat ? confidenceChip(cat.confidence) : null;
  const scale = useGrindScale(g);
  const named = g.brand.trim() !== "" || g.model.trim() !== "";
  const timed = isTimed(g);

  const applyCatalog = (id: string) => {
    const c = catalog.grinder(id);
    if (!c) return;
    const f = fieldsFromCatalog(c);
    setG((cur) => ({ ...cur, ...f }));
    setCprText(f.clicksPerRotation > 0 ? String(f.clicksPerRotation) : "");
    setMicronsText(numText(f.micronsPerClick));
    setMinText(numText(f.minSetting));
    setMaxText(numText(f.maxSetting));
    setStepText(numText(f.step));
    setSecondsKey((k) => k + 1);
    setEditScale(false);
  };

  return (
    <>
      <PickRow
        label="Which grinder?"
        value={named ? grinderName(g) || grinderDisplay(g) : ""}
        placeholder="Pick from the list"
        onClick={() => setPicking(true)}
        anchorRef={anchor}
        // A blade grinder has no dial numbers to verify, so no confidence chip.
        trailing={timed ? undefined : cat && edited ? <StatusChip status="attention" text="Edited" /> : chip ? <StatusChip status={chip.status} text={chip.text} /> : undefined}
      />

      <div className={s.section}>
        <p className="t-body-m">{scaleSummary(g)}</p>
        {cat?.setting.dialReading && <SheetNote text={`How to read your dial: ${cat.setting.dialReading}`} />}
        {cat?.setting.entryHint && <SheetNote text={`What to type: ${cat.setting.entryHint}`} />}
      </div>

      <Disclosure label={timed ? "Edit the name" : "Edit the scale"} open={editScale} onToggle={setEditScale}>
        <div className={s.row2}>
          <FieldWell label="Brand" value={g.brand} onChange={(v) => set({ brand: v })} />
          <FieldWell label="Model" value={g.model} onChange={(v) => set({ model: v })} />
        </div>
        {!timed && (
          <>
            <FieldWell label="Burr size" value={g.burrSizeMm} suffix="mm" numeric onChange={(v) => set({ burrSizeMm: v })} />
            <ChipGroup label="Burrs">
              {BURRS.map((b) => (
                <SelectableChip key={b.id} text={b.label} selected={g.burrType === b.id} onClick={() => set({ burrType: g.burrType === b.id ? null : b.id })} />
              ))}
            </ChipGroup>
          </>
        )}
        <ChipGroup label="How it adjusts">
          {STYLES.map((st) => (
            <SelectableChip
              key={st.id}
              text={st.label}
              selected={st.id === "seconds" ? timed : !timed && (g.settingStyle === st.id || (st.id === "numbers" && g.settingStyle === "microns"))}
              onClick={() =>
                set(
                  st.id === "seconds"
                    ? { settingStyle: "stepless", settingUnit: "s", burrType: "blade", minSetting: 0, maxSetting: 60, step: 1, lowerIsFiner: false }
                    : { settingStyle: st.id, settingUnit: "" },
                )
              }
            />
          ))}
        </ChipGroup>
        {timed ? null : g.settingStyle === "clicks" ? (
          <>
            <div className={s.row2}>
              <FieldWell
                label="Clicks per turn"
                value={cprText}
                numeric
                onChange={(t) => {
                  const d = t.replace(/[^\d]/g, "").slice(0, 3);
                  setCprText(d);
                  set({ clicksPerRotation: d ? Number(d) : 0 });
                }}
              />
              <FieldWell
                label="Microns / click"
                value={micronsText}
                suffix="µm"
                numeric
                onChange={(t) => {
                  setMicronsText(t);
                  set({ micronsPerClick: parseNum(t) });
                }}
              />
            </div>
            <SheetNote text="Both optional. They make the dial words and the brew report more specific." />
            <ToggleRow label="Fewer clicks is finer" checked={g.lowerIsFiner} onChange={(v) => set({ lowerIsFiner: v })} subtitle="True for anything you zero at burr contact and dial outward." />
            <ChipGroup label="How your dial reads">
              {DIAL_FORMATS.map((f) => (
                <SelectableChip key={f.id || "auto"} text={f.label} selected={g.dialFormat === f.id} onClick={() => set({ dialFormat: f.id })} />
              ))}
            </ChipGroup>
          </>
        ) : (
          <>
            <div className={s.row2}>
              <FieldWell
                label="Lowest"
                value={minText}
                numeric
                onChange={(t) => {
                  setMinText(t);
                  set({ minSetting: parseNum(t) ?? 0 });
                }}
              />
              <FieldWell
                label="Highest"
                value={maxText}
                numeric
                onChange={(t) => {
                  setMaxText(t);
                  set({ maxSetting: parseNum(t) ?? 0 });
                }}
              />
            </div>
            <FieldWell
              label="Smallest step"
              value={stepText}
              numeric
              helper="0.5 if the dial has half marks; 0.1 for a fine stepless scale."
              onChange={(t) => {
                setStepText(t);
                set({ step: parseNum(t) ?? 1 });
              }}
            />
            <ToggleRow label="Lower numbers are finer" checked={g.lowerIsFiner} onChange={(v) => set({ lowerIsFiner: v })} />
          </>
        )}
      </Disclosure>

      {timed ? (
        <SecondsField key={secondsKey} value={g.defaultFilterSetting} onValue={(v) => set({ defaultFilterSetting: v })} />
      ) : (
        <>
          <GrindRangeCard scale={scale} method="pourover" />
          <div className={s.row2}>
            <UsualField label="Usual filter" g={g} value={g.defaultFilterSetting} onValue={(v) => set({ defaultFilterSetting: v })} />
            <UsualField label="Usual espresso" g={g} value={g.defaultEspressoSetting} onValue={(v) => set({ defaultEspressoSetting: v })} />
          </div>
        </>
      )}

      <ToggleRow label="Use this one by default" checked={g.isDefault} onChange={(v) => set({ isDefault: v })} />

      {picking && (
        <CatalogPickerSheet
          kind="grinder"
          title="Which grinder?"
          anchor={anchor}
          selected={g.catalogId ? [g.catalogId] : []}
          onPick={(p) => {
            if ("id" in p) applyCatalog(p.id);
            else {
              setG((cur) => ({ ...cur, ...splitName(p.custom), catalogId: null, dialFormat: "" }));
              setEditScale(true);
            }
          }}
          onClose={() => setPicking(false)}
        />
      )}
    </>
  );
}

/** Saves; one default at a time (Room's clearDefaults), and settings point at it for brew setup. */
async function saveGrinder(g: GrinderRow): Promise<number> {
  const id = await repo.upsertGrinder(g);
  if (g.isDefault) {
    await repo.setDefaultGrinder(id);
    await settings.set("defaultGrinderId", id);
  }
  return id;
}

function named(g: GrinderRow): boolean {
  return g.brand.trim() !== "" || g.model.trim() !== "";
}

export function GrinderEditorSheet({ initial, draft, onClose, onRemove }: { initial: GrinderRow; draft?: GrinderRow; onClose: () => void; onRemove?: (g: GrinderRow) => void }) {
  const isNew = initial.id == null;
  const { g, setG } = useGrinderDraft(draft ?? initial);
  const [saving, setSaving] = useState(false);
  const closeRef = useRef<(() => void) | null>(null);
  const dirty = rowsDiffer(g, initial);
  const title = isNew ? "New grinder" : "Edit grinder";

  const save = async () => {
    if (saving || !named(g)) return;
    setSaving(true);
    try {
      await saveGrinder(g);
      closeRef.current?.();
    } finally {
      setSaving(false);
    }
  };

  return (
    <CoffeeSheet role="editor" label={title} title={title} onSave={() => void save()} dirty={dirty} discardBody="You've changed this grinder and haven't saved it." onClose={onClose} full>
      <CloseBridge closeRef={closeRef} />
      <div className={s.sheetContent}>
        <h2 className={`t-headline-s ${s.sheetTitle}`}>{title}</h2>
        <GrinderForm g={g} setG={setG} />
        <SheetSaveRow canSave={named(g)} saving={saving} onSave={() => void save()} deleteText="Remove grinder" onDelete={!isNew && onRemove ? () => onRemove(initial) : undefined} />
        {!isNew && <SheetNote text="Removed grinders leave the list and brew setup. Their brews stay in the log." />}
      </div>
    </CoffeeSheet>
  );
}

export function GrinderEditorPane({
  saved,
  template,
  draft,
  onDraft,
  onSaved,
  onRemove,
}: {
  saved: GrinderRow | null;
  template: GrinderRow;
  draft?: GrinderRow;
  onDraft: (g: GrinderRow) => void;
  onSaved: (g: GrinderRow) => void;
  onRemove?: (g: GrinderRow) => void;
}) {
  const base = saved ?? template;
  const { g, setG } = useGrinderDraft(draft ?? base, onDraft);
  const [saving, setSaving] = useState(false);
  const [formKey, setFormKey] = useState(0);
  const dirty = rowsDiffer(g, base);
  const canSave = named(g) && dirty;
  const isNew = saved == null;

  const save = async () => {
    if (saving || !canSave) return;
    setSaving(true);
    try {
      const id = await saveGrinder(g);
      onSaved({ ...g, id });
    } finally {
      setSaving(false);
    }
  };

  return (
    <article className={s.paneEditor} aria-label={isNew ? "New grinder" : grinderName(g) || "Grinder"}>
      <PaneEditorHeader
        isNew={isNew}
        eyebrow={isNew ? "New grinder" : g.brand.trim() && g.brand.trim().toLowerCase() !== "any" ? g.brand.trim() : "Grinder"}
        title={(isNew && !named(g) ? "New grinder" : g.model.trim() || g.brand.trim()) || "Unnamed"}
        dirty={dirty}
        canSave={canSave}
        saving={saving}
        onSave={() => void save()}
        onRevert={() => {
          setG(() => base);
          setFormKey((k) => k + 1);
        }}
      />
      <div className={s.paneForm}>
        <GrinderForm key={formKey} g={g} setG={setG} />
        {saved && onRemove && (
          <>
            <PaneDeleteRow text="Remove this grinder" onClick={() => onRemove(saved)} />
            <SheetNote text="Removed grinders leave the list and brew setup. Their brews stay in the log." />
          </>
        )}
      </div>
    </article>
  );
}
