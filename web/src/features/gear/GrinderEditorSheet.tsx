/**
 * Grinder editor (A§7.4, replaces 1.x GrinderSheet). "Which grinder?" fills everything from the
 * catalog; the scale shows as one line with "Edit the scale" for the manual numbers; the
 * catalog's dial-reading help, entry hint and starting points sit under it; usual filter and
 * espresso can be typed as stored numbers or as a dial reading.
 */
import { useRef, useState } from "react";
import { catalog } from "@/catalog/catalog";
import { useCatalog, useCatalogGrinder } from "@/catalog/useCatalog";
import { CoffeeSheet, FieldWell, PrimaryButton, QuietButton, SelectableChip, StatusChip, ToggleRow } from "@/design/components";
import { repo } from "@/data/repo";
import { grinderDisplay, type BurrType, type GrinderRow, type SettingStyle } from "@/data/schema";
import { settings } from "@/data/settings";
import { grams } from "@/domain/core/format";
import { format, usesRotation } from "@/domain/grind";
import { CatalogPickerSheet, closeEnclosingSheet } from "@/features/catalog-picker";
import { confidenceChip, dialWords, DialReadingInput, GrindRangeCard, useGrindScale } from "@/features/grind";
import { fieldsFromCatalog, scaleDifferences, scaleSummary } from "./mapping";
import { ChipGroup, Disclosure, PickRow, SheetNote, SheetTitle, useTwoTap } from "./parts";
import s from "./gear.module.css";

const BURRS: readonly { id: BurrType; label: string }[] = [
  { id: "conical", label: "Conical" },
  { id: "flat", label: "Flat" },
  { id: "ghost", label: "Ghost" },
  { id: "blade", label: "Blade" },
];

const STYLES: readonly { id: SettingStyle; label: string }[] = [
  { id: "clicks", label: "Clicks" },
  { id: "numbers", label: "Numbered dial" },
  { id: "stepless", label: "Stepless" },
];

const DIAL_FORMATS: readonly { id: string; label: string }[] = [
  { id: "", label: "Auto" },
  { id: "plain", label: "Plain number" },
  { id: "turns", label: "Turns + clicks" },
  { id: "number-tick:10", label: "Numbered dial (10 marks a number)" },
];

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

export function GrinderEditorSheet({ initial, onClose }: { initial: GrinderRow; onClose: () => void }) {
  useCatalog(["grinders"]);
  const isNew = initial.id == null;
  const [g, setG] = useState(initial);
  const [picking, setPicking] = useState(false);
  const [editScale, setEditScale] = useState(isNew ? false : initial.catalogId == null);
  const [cprText, setCprText] = useState(initial.clicksPerRotation > 0 ? String(initial.clicksPerRotation) : "");
  const [micronsText, setMicronsText] = useState(numText(initial.micronsPerClick));
  const [minText, setMinText] = useState(numText(initial.minSetting));
  const [maxText, setMaxText] = useState(numText(initial.maxSetting));
  const [stepText, setStepText] = useState(numText(initial.step));
  const [saving, setSaving] = useState(false);
  const [armed, tapDelete] = useTwoTap();
  const box = useRef<HTMLDivElement>(null);

  const set = (patch: Partial<GrinderRow>) => setG((cur) => ({ ...cur, ...patch }));
  const cat = useCatalogGrinder(g.catalogId);
  const edited = cat ? scaleDifferences(g, cat).length > 0 : false;
  const chip = cat ? confidenceChip(cat.confidence) : null;
  const scale = useGrindScale(g);
  const named = g.brand.trim() !== "" || g.model.trim() !== "";

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
    setEditScale(false);
  };

  const save = async () => {
    if (saving || !named) return;
    setSaving(true);
    try {
      const id = await repo.upsertGrinder(g);
      // One default at a time (Room's clearDefaults), and settings point at it for brew setup.
      if (g.isDefault) {
        await repo.setDefaultGrinder(id);
        await settings.set("defaultGrinderId", id);
      }
      closeEnclosingSheet(box.current);
    } finally {
      setSaving(false);
    }
  };

  // The picker is a sibling dialog, not a child: a child would bubble its touches into this
  // sheet's drag handler.
  return (
    <>
      <CoffeeSheet label={isNew ? "New grinder" : "Edit grinder"} onClose={onClose} full>
        <div ref={box} className={s.sheetContent}>
          <SheetTitle text={isNew ? "New grinder" : "Edit grinder"} />

          <PickRow
            label="Which grinder?"
            value={named ? grinderDisplay(g) : ""}
            placeholder="Pick from the list"
            onClick={() => setPicking(true)}
            trailing={cat && edited ? <StatusChip status="attention" text="Edited" /> : chip ? <StatusChip status={chip.status} text={chip.text} /> : undefined}
          />

          <div className={s.section}>
            <p className="t-body-m">{scaleSummary(g)}</p>
            {cat?.setting.dialReading && <SheetNote text={`How to read your dial: ${cat.setting.dialReading}`} />}
            {cat?.setting.entryHint && <SheetNote text={`What to type: ${cat.setting.entryHint}`} />}
          </div>

          <Disclosure label="Edit the scale" open={editScale} onToggle={setEditScale}>
            <div className={s.row2}>
              <FieldWell label="Brand" value={g.brand} onChange={(v) => set({ brand: v })} />
              <FieldWell label="Model" value={g.model} onChange={(v) => set({ model: v })} />
            </div>
            <FieldWell label="Burr size" value={g.burrSizeMm} suffix="mm" numeric onChange={(v) => set({ burrSizeMm: v })} />
            <ChipGroup label="Burrs">
              {BURRS.map((b) => (
                <SelectableChip key={b.id} text={b.label} selected={g.burrType === b.id} onClick={() => set({ burrType: g.burrType === b.id ? null : b.id })} />
              ))}
            </ChipGroup>
            <ChipGroup label="How it adjusts">
              {STYLES.map((st) => (
                <SelectableChip key={st.id} text={st.label} selected={g.settingStyle === st.id || (st.id === "numbers" && g.settingStyle === "microns")} onClick={() => set({ settingStyle: st.id })} />
              ))}
            </ChipGroup>
            {g.settingStyle === "clicks" ? (
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

          <GrindRangeCard scale={scale} method="pourover" />

          <div className={s.row2}>
            <UsualField label="Usual filter" g={g} value={g.defaultFilterSetting} onValue={(v) => set({ defaultFilterSetting: v })} />
            <UsualField label="Usual espresso" g={g} value={g.defaultEspressoSetting} onValue={(v) => set({ defaultEspressoSetting: v })} />
          </div>

          <ToggleRow label="Use this one by default" checked={g.isDefault} onChange={(v) => set({ isDefault: v })} />

          <div className={s.actions}>
            <PrimaryButton text={saving ? "Saving…" : "Save"} enabled={named && !saving} onClick={() => void save()} />
            {!isNew && (
              <QuietButton
                text={armed ? "Tap again to remove" : "Remove grinder"}
                color="var(--fault)"
                onClick={() =>
                  tapDelete(() => {
                    // Archived, not deleted: old brews keep their grinder, so their dial words still read right.
                    void repo.archiveGrinder(initial.id!).then(() => closeEnclosingSheet(box.current));
                  })
                }
              />
            )}
            {!isNew && <SheetNote text="Removed grinders leave the list and brew setup. Their brews stay in the log." />}
          </div>
        </div>
      </CoffeeSheet>

      {picking && (
        <CatalogPickerSheet
          kind="grinder"
          title="Which grinder?"
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
