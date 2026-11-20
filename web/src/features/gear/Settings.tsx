/**
 * Gear › Settings (A§7.8, web-spec §11, v4 §3.10): seven sections. At expanded+ the list pane
 * lists them and the chosen one fills the right pane (max 720); below that they are one scroll
 * with a sticky jump row. Every setting writes straight to IndexedDB through `settings`; the
 * theme and motion choices reach boot.js through prefsMirror.
 */
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { promptInstall, useInstallPrompt } from "@/app/install";
import { ANDROID_DOWNLOAD_URL, SOURCE_URL } from "@/app/links";
import { nav } from "@/app/router";
import { catalog } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import { EyebrowLabel, FieldWell, HairLine, Icon, PillButton, RadioRow, SectionLabel, SegmentedControl, SelectableChip, SoftCard, ToggleRow, useRovingList } from "@/design/components";
import { settings, type Settings } from "@/data/settings";
import { isSafari, isStandalone } from "@/data/storage";
import { useSettings } from "@/data/store";
import { docFor } from "@/domain/search";
import { AiSettingsSection } from "@/features/ai";
import { BackupSection } from "@/features/backup";
import { CatalogPickerSheet } from "@/features/catalog-picker";
import { EXPERIENCE_CHOICES } from "@/features/onboarding/persist";
import { PickRow } from "./parts";
import { sectionAnchor, selectSection, SETTINGS_SECTIONS, takeJump, useSettingsSection, type SettingsSectionId } from "./settingsSections";
import s from "./gear.module.css";

function isIos(): boolean {
  return typeof navigator !== "undefined" && /iPad|iPhone|iPod/.test(navigator.userAgent);
}

const support = {
  vibrate: typeof navigator !== "undefined" && typeof navigator.vibrate === "function",
  wakeLock: typeof navigator !== "undefined" && "wakeLock" in navigator,
  audio: typeof window !== "undefined" && ("AudioContext" in window || "webkitAudioContext" in window),
};
const UNSUPPORTED = "Not supported in this browser";

const set = <K extends keyof Settings>(k: K) => (v: Settings[K]) => void settings.set(k, v);

/** "a, b and 3 more" for a PickRow value. */
function names(list: readonly string[]): string {
  if (list.length === 0) return "";
  return list.length <= 3 ? list.join(", ") : `${list.slice(0, 2).join(", ")} and ${list.length - 2} more`;
}

// ---------------------------------------------------------------- You

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
  const themes = ["system", "light", "dark"] as const;
  return (
    <>
      <SoftCard>
        <div className={s.prefCard}>
          <NameField name={st.name} />
          <div className={s.prefLabel}>
            <EyebrowLabel text="How do you make coffee now?" />
            <div className={s.radioGroup} role="radiogroup" aria-label="How do you make coffee now?">
              {EXPERIENCE_CHOICES.map((c) => (
                <RadioRow key={c.id} name="experience" title={c.title} subtitle={c.body} selected={st.experience === c.id} onClick={() => void settings.set("experience", c.id)} />
              ))}
            </div>
            <p className={`t-body-s ${s.described}`}>Changes how much we explain. Scale, review and sound each have their own switch below.</p>
          </div>
          <HairLine />
          <PillButton text="Run setup again" onClick={() => nav.push("/welcome")} />
        </div>
      </SoftCard>
      <SectionLabel text="Appearance" />
      <SoftCard>
        <div className={s.prefCard}>
          <div className={s.prefLabel}>
            <EyebrowLabel text="Theme" />
            <SegmentedControl options={["Auto", "Light", "Dark"]} selectedIndex={Math.max(0, themes.indexOf(st.darkMode))} onSelect={(i) => void settings.set("darkMode", themes[i]!)} onCard label="Theme" />
          </div>
          <div className={s.prefLabel}>
            <EyebrowLabel text="Motion" />
            <SegmentedControl options={["Match system", "Reduce motion"]} selectedIndex={st.motion === "reduced" ? 1 : 0} onSelect={(i) => void settings.set("motion", i === 1 ? "reduced" : "system")} onCard label="Motion" />
          </div>
        </div>
      </SoftCard>
    </>
  );
}

// ---------------------------------------------------------------- Units and measuring

function UnitsSection({ st }: { st: Settings }) {
  return (
    <SoftCard>
      <div className={s.prefCard}>
        <ToggleRow label="I have a kitchen scale" checked={st.hasScale} onChange={set("hasScale")} subtitle="Off: amounts show in tablespoons and ml." />
        <HairLine />
        <div className={s.prefLabel}>
          <EyebrowLabel text="Temperature" />
          <SegmentedControl options={["°C", "°F"]} selectedIndex={st.tempUnit === "f" ? 1 : 0} onSelect={(i) => void settings.set("tempUnit", i === 1 ? "f" : "c")} onCard label="Temperature unit" />
        </div>
        <div className={s.prefLabel}>
          <EyebrowLabel text="Weight" />
          <SegmentedControl options={["Grams", "Ounces"]} selectedIndex={st.weightUnit === "oz" ? 1 : 0} onSelect={(i) => void settings.set("weightUnit", i === 1 ? "oz" : "g")} onCard label="Weight unit" />
          <p className={`t-body-s ${s.described}`}>Brew targets stay in grams — that's what coffee scales read. Bag sizes and totals follow your choice.</p>
        </div>
      </div>
    </SoftCard>
  );
}

// ---------------------------------------------------------------- Brewing

function BrewingSection({ st }: { st: Settings }) {
  "use no memo"; // reads the brewer names from the catalog while rendering
  useCatalog(["brewers"]);
  const [pickBrewers, setPickBrewers] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const owned = st.ownedBrewerIds.map((id) => docFor("brewer", id)?.title ?? id);
  return (
    <>
      <SoftCard>
        <div className={s.prefCard}>
          <PickRow label="Brewers you own" value={names(owned)} placeholder="Pick the brewers you have" onClick={() => setPickBrewers(true)} anchorRef={anchor} />
          <div className={s.prefLabel}>
            <EyebrowLabel text="Review after brewing" />
            <SegmentedControl options={["Quick", "Detailed"]} selectedIndex={st.reviewMode === "detailed" ? 1 : 0} onSelect={(i) => void settings.set("reviewMode", i === 1 ? "detailed" : "quick")} onCard label="Review after brewing" />
            <p className={`t-body-s ${s.described}`}>
              {st.reviewMode === "detailed" ? "Every section open: taste, flow, the coffee bed, TDS." : "A score and anything off. The rest is one tap away under More detail."}
            </p>
          </div>
        </div>
      </SoftCard>
      <SoftCard padding="4px 18px">
        <ToggleRow
          label="Sound on each step"
          checked={st.soundCues && support.audio}
          disabled={!support.audio}
          subtitle={support.audio ? "A short chime when it's time for the next step." : UNSUPPORTED}
          onChange={(v) => void settings.update({ soundCues: v, soundCuesAsked: true })}
        />
        <HairLine />
        <ToggleRow label="Buzz on each step" checked={st.haptics && support.vibrate} disabled={!support.vibrate} subtitle={support.vibrate ? undefined : UNSUPPORTED} onChange={set("haptics")} />
        <HairLine />
        <ToggleRow label="Keep the screen awake" checked={st.keepScreenOn && support.wakeLock} disabled={!support.wakeLock} subtitle={support.wakeLock ? undefined : UNSUPPORTED} onChange={set("keepScreenOn")} />
        <HairLine />
        <ToggleRow label="Move to the next step on time" checked={st.autoAdvanceSteps} onChange={set("autoAdvanceSteps")} subtitle="Off: each step waits for you to tap Next." />
      </SoftCard>
      {pickBrewers && (
        <CatalogPickerSheet
          kind="brewer"
          title="Brewers you own"
          anchor={anchor}
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

// ---------------------------------------------------------------- Coffee and water

function CoffeeSection({ st }: { st: Settings }) {
  // Reads the catalog while rendering (water, roaster names): the compiler would cache those reads
  // on props alone and keep the empty answer from before the files landed.
  "use no memo";
  const ready = useCatalog(["roasters", "water"]);
  const [pickRoasters, setPickRoasters] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  // `ready` flips once the files land; the list is rebuilt then and never on a keystroke.
  const areas = useMemo(() => (ready ? [...new Set(catalog.roasters.map((r) => r.area).filter(Boolean))].sort() : []), [ready]);
  const water = catalog.waterById(st.defaultWaterId);
  const favs = st.favouriteRoasterIds.map((id) => catalog.roaster(id)?.name ?? id);
  return (
    <>
      <SoftCard padding="4px 18px">
        <ToggleRow
          label="I buy ground coffee"
          checked={st.buysGround}
          onChange={set("buysGround")}
          subtitle={st.buysGround ? "You're set: brews will use the coffee as bought." : "On: brews skip grind settings and use the coffee as bought."}
        />
      </SoftCard>
      <SoftCard>
        <div className={s.prefCard}>
          {areas.length > 0 && (
            <div className={s.prefLabel} role="group" aria-label="Where you buy coffee">
              <EyebrowLabel text="Where you buy coffee" />
              <div className={s.chipRow}>
                {areas.map((a) => (
                  <SelectableChip key={a} text={a} selected={st.homeArea === a} onClick={() => void settings.set("homeArea", st.homeArea === a ? "" : a)} />
                ))}
              </div>
              <p className={`t-body-s ${s.described}`}>Roasters and supermarket brands sold near you come first in the list.</p>
            </div>
          )}
          <PickRow label="Favourite roasters" value={names(favs)} placeholder="Pick roasters you like" onClick={() => setPickRoasters(true)} anchorRef={anchor} />
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
        </div>
      </SoftCard>
      {pickRoasters && (
        <CatalogPickerSheet
          kind="roaster"
          title="Favourite roasters"
          anchor={anchor}
          multiSelect
          selected={st.favouriteRoasterIds}
          onPick={() => undefined}
          onDone={(ids) => void settings.set("favouriteRoasterIds", ids)}
          onClose={() => setPickRoasters(false)}
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------- Your data, About

function DataSection() {
  const [dev, setDev] = useState<null | { seed: () => Promise<void>; wipe: () => Promise<void> }>(null);
  useEffect(() => {
    if (!import.meta.env.DEV) return;
    void import("@/data/devSeed").then((m) => setDev({ seed: () => m.seedBrews(1000), wipe: m.wipeDev }));
  }, []);
  return (
    <>
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
    </>
  );
}

function AboutSection() {
  const install = useInstallPrompt();
  const standalone = isStandalone();
  return (
    <>
      <SoftCard>
        <EyebrowLabel text="About the recipes" />
        <p className={s.aboutText} style={{ marginTop: 8 }}>
          Every recipe says where its numbers came from. Some are transcribed exactly from a published method; others are marked ADAPTED, meaning the technique is the author's but the exact
          figures are a sensible starting point rather than a quote. Recipes you make or change are marked YOURS: duplicate any recipe to start from it, and the original stays as it was. Change any
          of them to match the source you trust.
        </p>
      </SoftCard>
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
            {!standalone && install && <PillButton text="Install Coffee" onClick={() => void promptInstall()} />}
          </div>
          {!standalone && !install && isIos() && isSafari() && <p className={`t-body-s ${s.described}`}>To install Coffee, tap Share, then Add to Home Screen.</p>}
        </div>
      </SoftCard>
    </>
  );
}

/** One section's body. */
function SectionBody({ id, st }: { id: SettingsSectionId; st: Settings }) {
  switch (id) {
    case "you":
      return <YouSection st={st} />;
    case "units":
      return <UnitsSection st={st} />;
    case "brewing":
      return <BrewingSection st={st} />;
    case "coffee":
      return <CoffeeSection st={st} />;
    case "ai":
      // AiSettingsSection opens with its own AI ANALYSIS eyebrow (as on Android).
      return <AiSettingsSection />;
    case "data":
      return <DataSection />;
    case "about":
      return <AboutSection />;
  }
}

// ---------------------------------------------------------------- compact / medium: one scroll

/**
 * Brings a section's heading just under the sticky header (whatever height the header has wrapped
 * to at this width and text size), in the one scroll container.
 */
function scrollToSection(id: SettingsSectionId, header: Element | null, smooth: boolean): void {
  const el = document.getElementById(sectionAnchor(id));
  if (!el) return;
  let scroller: HTMLElement | null = el.parentElement;
  while (scroller && !/(auto|scroll)/.test(getComputedStyle(scroller).overflowY)) scroller = scroller.parentElement;
  if (!scroller) return el.scrollIntoView({ block: "start" });
  const under = header?.getBoundingClientRect().bottom ?? scroller.getBoundingClientRect().top;
  scroller.scrollTo({ top: scroller.scrollTop + el.getBoundingClientRect().top - under - 8, behavior: smooth ? "smooth" : "auto" });
}

/** The sticky jump row under Gear's header (it lives in ScreenHeader's status slot). */
export function SettingsJumpRow() {
  const current = useSettingsSection();
  return (
    <nav className={s.jumpRow} aria-label="Settings sections">
      {SETTINGS_SECTIONS.map((x) => (
        <button
          key={x.id}
          type="button"
          className={`${s.jump} t-label-l`}
          aria-current={current === x.id ? "true" : undefined}
          onClick={(e) => {
            selectSection(x.id);
            scrollToSection(x.id, e.currentTarget.closest("header"), true);
            // The section's heading takes focus so keyboard and screen reader users land there too.
            document.getElementById(sectionAnchor(x.id))?.querySelector<HTMLElement>("h2")?.focus({ preventScroll: true });
          }}
        >
          {x.label}
        </button>
      ))}
    </nav>
  );
}

export function SettingsScroll() {
  const st = useSettings();
  const current = useSettingsSection();
  // A jump asked for from elsewhere (the palette's "Import a backup") scrolls once.
  useLayoutEffect(() => {
    const j = takeJump();
    if (j) scrollToSection(j, document.getElementById(sectionAnchor(j))?.closest("[data-tab]")?.querySelector("header") ?? null, false);
  }, [current]);
  return (
    <div className={s.pane} style={{ paddingTop: 0 }}>
      {SETTINGS_SECTIONS.map((x, i) => (
        <section key={x.id} id={sectionAnchor(x.id)} className={s.settingsSection} aria-labelledby={`${sectionAnchor(x.id)}-h`}>
          <h2 id={`${sectionAnchor(x.id)}-h`} tabIndex={-1} className="eyebrow" style={{ margin: i === 0 ? "0 0 10px" : "24px 0 10px" }}>
            {x.label}
          </h2>
          <div className={s.pane} style={{ paddingTop: 0 }}>
            <SectionBody id={x.id} st={st} />
          </div>
        </section>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------- expanded+: list and pane

export function SettingsList({ onSelect }: { onSelect: (id: SettingsSectionId) => void }) {
  const current = useSettingsSection();
  const selectedIndex = SETTINGS_SECTIONS.findIndex((x) => x.id === current);
  const list = useRovingList({ count: SETTINGS_SECTIONS.length, selectedIndex, onSettle: (i) => onSelect(SETTINGS_SECTIONS[i]!.id) });
  return (
    <ul className={s.sectionRows} aria-label="Settings sections">
      {SETTINGS_SECTIONS.map((x, i) => (
        <li key={x.id}>
          <button type="button" {...list.itemProps(i)} className={`${s.sectionRow} interactive-row t-title-m`} aria-current={i === selectedIndex ? "true" : undefined} onClick={() => onSelect(x.id)}>
            <span data-title="">{x.label}</span>
            <Icon name="ChevronLeft" size={18} className={s.chev} />
          </button>
        </li>
      ))}
    </ul>
  );
}

export function SettingsSectionPane({ id }: { id: SettingsSectionId }) {
  const st = useSettings();
  const label = SETTINGS_SECTIONS.find((x) => x.id === id)?.label ?? "Settings";
  return (
    <div className={s.settingsPane}>
      <header className={s.settingsPaneHead}>
        <EyebrowLabel text="Settings" />
        <h1 className="t-display-s">{label}</h1>
      </header>
      <div className={s.pane} style={{ paddingTop: 8 }}>
        <SectionBody id={id} st={st} />
      </div>
    </div>
  );
}
