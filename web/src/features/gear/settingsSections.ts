/**
 * Gear › Settings sections (v4 §3.10): at expanded+ a sub-list with the chosen section in the
 * right pane; below that one scroll with a sticky jump row. The chosen section lives here, not in
 * the URL (Gear has one route per sub-tab), so it survives tab switches and resizes, and the
 * palette can ask for a section before Gear has ever mounted.
 */
import { useSyncExternalStore } from "react";

export const SETTINGS_SECTIONS = [
  { id: "you", label: "You" },
  { id: "units", label: "Units and measuring" },
  { id: "brewing", label: "Brewing" },
  { id: "coffee", label: "Coffee and water" },
  { id: "ai", label: "AI analysis" },
  { id: "data", label: "Your data" },
  { id: "about", label: "About" },
] as const;

export type SettingsSectionId = (typeof SETTINGS_SECTIONS)[number]["id"];

const IDS: readonly string[] = SETTINGS_SECTIONS.map((x) => x.id);

/** Old names and loose words people (and older links) use for a section. */
const ALIASES: Readonly<Record<string, SettingsSectionId>> = {
  appearance: "you",
  theme: "you",
  measuring: "units",
  scale: "units",
  "while-brewing": "brewing",
  sound: "brewing",
  review: "brewing",
  water: "coffee",
  roasters: "coffee",
  "ai-analysis": "ai",
  backup: "data",
  "your-data": "data",
  "about-the-recipes": "about",
};

/** Any id, label or alias to a section; anything unknown is the default, "You". */
export function sectionFor(raw: string | null | undefined): SettingsSectionId {
  const k = (raw ?? "")
    .trim()
    .toLowerCase()
    .replace(/[\s_]+/g, "-");
  if (IDS.includes(k)) return k as SettingsSectionId;
  const byLabel = SETTINGS_SECTIONS.find((x) => x.label.toLowerCase().replace(/\s+/g, "-") === k);
  return byLabel?.id ?? ALIASES[k] ?? "you";
}

export function sectionLabel(id: SettingsSectionId): string {
  return SETTINGS_SECTIONS.find((x) => x.id === id)?.label ?? "You";
}

/** The element id of a section in the one-scroll layout (the jump row's targets). */
export function sectionAnchor(id: SettingsSectionId): string {
  return `settings-${id}`;
}

// ---------------------------------------------------------------- the chosen section

let current: SettingsSectionId = "you";
/** A jump the one-scroll layout still has to scroll to (consumed once). */
let jump: SettingsSectionId | null = null;
const listeners = new Set<() => void>();

function emit(): void {
  for (const l of listeners) l();
}

/** Selects a section. `scroll`: the one-scroll layout should bring it into view once. */
export function selectSection(raw: string | null | undefined, opts: { scroll?: boolean } = {}): SettingsSectionId {
  const id = sectionFor(raw);
  const changed = id !== current;
  current = id;
  if (opts.scroll) jump = id;
  if (changed || opts.scroll) emit();
  return id;
}

export function currentSection(): SettingsSectionId {
  return current;
}

/** The pending scroll target, once; null after that. */
export function takeJump(): SettingsSectionId | null {
  const j = jump;
  jump = null;
  return j;
}

export function subscribeSection(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

export function useSettingsSection(): SettingsSectionId {
  return useSyncExternalStore(subscribeSection, currentSection, currentSection);
}

/** Test hook. */
export function resetSectionForTests(): void {
  current = "you";
  jump = null;
}
