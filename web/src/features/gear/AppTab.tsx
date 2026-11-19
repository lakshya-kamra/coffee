/**
 * Gear › App (A§7.8 and web-spec §11): YOU, APPEARANCE, WHILE BREWING, AI ANALYSIS, YOUR DATA,
 * ABOUT THE RECIPES, ABOUT. Every setting writes straight to IndexedDB through `settings`; the
 * theme and motion choices reach boot.js through prefsMirror.
 */
import { useEffect, useMemo, useState } from "react";
import { promptInstall, useInstallPrompt } from "@/app/install";
import { nav } from "@/app/router";
import { ANDROID_DOWNLOAD_URL, SOURCE_URL } from "@/app/links";
import { catalog } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import { EyebrowLabel, FieldWell, HairLine, PillButton, SectionLabel, SegmentedControl, SelectableChip, SoftCard, ToggleRow } from "@/design/components";
import { settings, type Settings } from "@/data/settings";
import { isSafari, isStandalone } from "@/data/storage";
import { useSettings } from "@/data/store";
import { docFor } from "@/domain/search";
import { AiSettingsSection } from "@/features/ai";
import { BackupSection } from "@/features/backup";
import { CatalogPickerSheet } from "@/features/catalog-picker";
import { PickRow } from "./parts";
import s from "./gear.module.css";

// ---------------------------------------------------------------- install (web-spec §11)

function isIos(): boolean {
  return typeof navigator !== "undefined" && /iPad|iPhone|iPod/.test(navigator.userAgent);
}

// ---------------------------------------------------------------- browser support

const support = {
  vibrate: typeof navigator !== "undefined" && typeof navigator.vibrate === "function",
  wakeLock: typeof navigator !== "undefined" && "wakeLock" in navigator,
  audio: typeof window !== "undefined" && ("AudioContext" in window || "webkitAudioContext" in window),
};
const UNSUPPORTED = "Not supported in this browser";

const set = <K extends keyof Settings>(k: K) => (v: Settings[K]) => void settings.set(k, v);

// ---------------------------------------------------------------- YOU

function NameField({ name }: { name: string }) {
  const [text, setText] = useState(name);
  // Written 400 ms after you stop typing, not on every keystroke.
  useEffect(() => {
    if (text === name) return;
    const t = setTimeout(() => void settings.set("name", text.trim()), 400);
    return () => clearTimeout(t);
  }, [text, name]);
  return <FieldWell label="Your name" value={text} onChange={setText} helper="For the greeting on Today, and as the author of recipes you make." maxLength={40} />;
}

function YouSection({ st }: { st: Settings }) {
  // Reads the catalog while rendering (water, brewer names): the compiler would cache those reads
  // on props alone and keep the empty answer from before the files landed.
  "use no memo";
  const ready = useCatalog(["roasters", "water", "brewers"]);
  const [pickBrewers, setPickBrewers] = useState(false);
  // `ready` flips once the files land; the list is rebuilt then and never on a keystroke.
  const areas = useMemo(() => (ready ? [...new Set(catalog.roasters.map((r) => r.area).filter(Boolean))].sort() : []), [ready]);
  const water = catalog.waterById(st.defaultWaterId);
  const owned = st.ownedBrewerIds.map((id) => docFor("brewer", id)?.title ?? id);
  return (
    <>
      <SectionLabel text="You" topGap={0} />
      <SoftCard>
        <div className={s.prefCard}>
          <NameField name={st.name} />
          <div className={s.prefLabel}>
            <EyebrowLabel text="Temperature" />
            <SegmentedControl options={["°C", "°F"]} selectedIndex={st.tempUnit === "f" ? 1 : 0} onSelect={(i) => void settings.set("tempUnit", i === 1 ? "f" : "c")} onCard label="Temperature unit" />
          </div>
          <div className={s.prefLabel}>
            <EyebrowLabel text="Weight" />
            <SegmentedControl options={["Grams", "Ounces"]} selectedIndex={st.weightUnit === "oz" ? 1 : 0} onSelect={(i) => void settings.set("weightUnit", i === 1 ? "oz" : "g")} onCard label="Weight unit" />
            <p className={`t-body-s ${s.described}`}>Brew targets stay in grams — that's what coffee scales read. Bag sizes and totals follow your choice.</p>
          </div>
          {areas.length > 0 && (
            <div className={s.prefLabel} role="group" aria-label="Where you buy coffee">
              <EyebrowLabel text="Where you buy coffee" />
              <div className={s.chipRow}>
                {areas.map((a) => (
                  <SelectableChip key={a} text={a} selected={st.homeArea === a} onClick={() => void settings.set("homeArea", st.homeArea === a ? "" : a)} />
                ))}
              </div>
              <p className={`t-body-s ${s.described}`}>Roasters near you come first in the list.</p>
            </div>
          )}
          <PickRow
            label="Brewers you own"
            value={owned.length === 0 ? "" : owned.length <= 3 ? owned.join(", ") : `${owned.slice(0, 2).join(", ")} and ${owned.length - 2} more`}
            placeholder="Pick the brewers you have"
            onClick={() => setPickBrewers(true)}
          />
          {catalog.water.length > 0 && (
            <div className={s.prefLabel} role="group" aria-label="Your water">
              <EyebrowLabel text="Your water" />
              <div className={s.chipRow}>
                {catalog.water.map((w) => (
                  <SelectableChip key={w.id} text={w.label} selected={st.defaultWaterId === w.id} onClick={() => void settings.set("defaultWaterId", st.defaultWaterId === w.id ? "" : w.id)} />
                ))}
              </div>
              {water && <p className={`t-body-s ${s.described}`}>{water.description}</p>}
            </div>
          )}
          <HairLine />
          <PillButton text="Run setup again" onClick={() => nav.push("/welcome")} />
        </div>
      </SoftCard>
      {pickBrewers && (
        <CatalogPickerSheet
          kind="brewer"
          title="Brewers you own"
          multiSelect
          selected={st.ownedBrewerIds}
          onPick={() => undefined}
          onDone={(ids) => void settings.set("ownedBrewerIds", ids)}
          onClose={() => setPickBrewers(false)}
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------- the tab

export function AppTab() {
  const st = useSettings();
  const install = useInstallPrompt();
  const standalone = isStandalone();
  const [dev, setDev] = useState<null | { seed: () => Promise<void>; wipe: () => Promise<void> }>(null);
  useEffect(() => {
    if (!import.meta.env.DEV) return;
    void import("@/data/devSeed").then((m) => setDev({ seed: () => m.seedBrews(1000), wipe: m.wipeDev }));
  }, []);
  const themes = ["system", "light", "dark"] as const;

  return (
    <div className={s.pane} style={{ paddingTop: 0 }}>
      <YouSection st={st} />

      <SectionLabel text="Appearance" />
      <SoftCard>
        <div className={s.prefCard}>
          <SegmentedControl options={["Auto", "Light", "Dark"]} selectedIndex={Math.max(0, themes.indexOf(st.darkMode))} onSelect={(i) => void settings.set("darkMode", themes[i]!)} onCard label="Theme" />
          <div className={s.prefLabel}>
            <EyebrowLabel text="Motion" />
            <SegmentedControl options={["Match system", "Reduce motion"]} selectedIndex={st.motion === "reduced" ? 1 : 0} onSelect={(i) => void settings.set("motion", i === 1 ? "reduced" : "system")} onCard label="Motion" />
          </div>
        </div>
      </SoftCard>

      <SectionLabel text="While brewing" />
      <SoftCard padding="4px 18px">
        <ToggleRow label="Buzz on each step" checked={st.haptics && support.vibrate} disabled={!support.vibrate} subtitle={support.vibrate ? undefined : UNSUPPORTED} onChange={set("haptics")} />
        <HairLine />
        <ToggleRow label="Sound on each step" checked={st.soundCues && support.audio} disabled={!support.audio} subtitle={support.audio ? undefined : UNSUPPORTED} onChange={set("soundCues")} />
        <HairLine />
        <ToggleRow label="Keep the screen awake" checked={st.keepScreenOn && support.wakeLock} disabled={!support.wakeLock} subtitle={support.wakeLock ? undefined : UNSUPPORTED} onChange={set("keepScreenOn")} />
        <HairLine />
        <ToggleRow label="Move to the next step on time" checked={st.autoAdvanceSteps} onChange={set("autoAdvanceSteps")} subtitle="Off: each step waits for you to tap Next." />
      </SoftCard>

      {/* AiSettingsSection opens with its own AI ANALYSIS eyebrow (as on Android). */}
      <div style={{ marginTop: 24 }}>
        <AiSettingsSection />
      </div>

      <SectionLabel text="Your data" />
      <BackupSection />
      {dev && (
        <SoftCard>
          <div className={s.prefCard}>
            <EyebrowLabel text="Developer build only" />
            <div className={s.devRow}>
              <PillButton text="Seed 1,000 brews" onClick={() => void dev.seed()} />
              <PillButton text="Wipe" onClick={() => void dev.wipe()} />
            </div>
          </div>
        </SoftCard>
      )}

      <SectionLabel text="About the recipes" />
      <SoftCard>
        <p className={s.aboutText}>
          Every recipe says where its numbers came from. Some are transcribed exactly from a published method; others are marked ADAPTED, meaning the technique is the author's but the exact
          figures are a sensible starting point rather than a quote. Recipes you make or change are marked YOURS: duplicate any recipe to start from it, and the original stays as it was. Change any
          of them to match the source you trust.
        </p>
      </SoftCard>

      <SectionLabel text="About" />
      <SoftCard>
        <div className={s.prefCard}>
          <p className={`t-body-m ${s.version}`}>Coffee {__APP_VERSION__} for the web. Everything you log stays in this browser.</p>
          <div className={s.linkRow}>
            <a className={`${s.link} t-label-l`} href={SOURCE_URL} target="_blank" rel="noopener noreferrer">
              Source code
            </a>
            <a className={`${s.link} t-label-l`} href={ANDROID_DOWNLOAD_URL} target="_blank" rel="noopener noreferrer">
              Get the Android app
            </a>
            {!standalone && install && (
              <PillButton
                text="Install Coffee"
                onClick={() => void promptInstall()}
              />
            )}
          </div>
          {!standalone && !install && isIos() && isSafari() && <p className={`t-body-s ${s.described}`}>To install Coffee, tap Share, then Add to Home Screen.</p>}
        </div>
      </SoftCard>
    </div>
  );
}
