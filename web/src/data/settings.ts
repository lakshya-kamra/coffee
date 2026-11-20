/**
 * Settings (web-spec §8.4; Android Settings + A§5, same defaults) and the non-secret AI prefs
 * (web-spec §14.2). Both live in the kv table; the hot store mirrors them for synchronous reads.
 */
import { defaultTempUnit } from "@/domain/core/units";
import { db } from "./db";

/** Onboarding's "How do you make coffee now?" (v4 §4.1); "" = not asked yet. */
export type Experience = "" | "new" | "recipes" | "tinker";
export type ReviewMode = "quick" | "detailed";

export interface Settings {
  lastRecipeId: string;
  /** Local ids, 0 = none. */
  defaultBeanId: number;
  defaultGrinderId: number;
  haptics: boolean;
  soundCues: boolean;
  keepScreenOn: boolean;
  autoAdvanceSteps: boolean;
  darkMode: "system" | "light" | "dark";
  name: string;
  tempUnit: "c" | "f";
  weightUnit: "g" | "oz";
  onboardingDone: boolean;
  onboardingAuto: boolean;
  personaliseDismissed: boolean;
  ownedBrewerIds: string[];
  favouriteRoasterIds: string[];
  homeArea: string;
  defaultWaterId: string;
  pendingNotices: string[];
  startupTasksVersion: number;
  /** Web only (rule 13): "Motion: Match system / Reduce motion". */
  motion: "system" | "reduced";
  // ---- v4 (backup 2.1)
  experience: Experience;
  /** false: brewing amounts show in tablespoons and ml (grams stay the stored truth). */
  hasScale: boolean;
  /** "quick" on a fresh install; an install that already had brews reads "detailed" (store.init). */
  reviewMode: ReviewMode;
  /** "I buy ground coffee": brews default to pre-ground. */
  buysGround: boolean;
  // ---- v4, this device only: never written to a backup
  /** The chime question has been answered. */
  soundCuesAsked: boolean;
  /** Large windows: the sidebar folded to the rail. */
  sidebarCollapsed: boolean;
  /** Log layout on wide windows. */
  logView: "list" | "table";
}

export const DEFAULT_SETTINGS: Readonly<Settings> = Object.freeze({
  lastRecipeId: "",
  defaultBeanId: 0,
  defaultGrinderId: 0,
  haptics: true,
  // Lead decision 10: the web plays a real sound when this is on, so it starts off.
  soundCues: false,
  keepScreenOn: true,
  autoAdvanceSteps: true,
  darkMode: "system",
  name: "",
  tempUnit: "c",
  weightUnit: "g",
  onboardingDone: false,
  onboardingAuto: false,
  personaliseDismissed: false,
  ownedBrewerIds: [],
  favouriteRoasterIds: [],
  homeArea: "",
  defaultWaterId: "",
  pendingNotices: [],
  startupTasksVersion: 0,
  motion: "system",
  experience: "",
  hasScale: true,
  reviewMode: "quick",
  buysGround: false,
  soundCuesAsked: false,
  sidebarCollapsed: false,
  logView: "list",
});

function browserLocale(): string {
  return typeof navigator === "undefined" ? "en-GB" : navigator.language;
}

export interface AiProviderPrefs {
  model: string;
  baseUrl?: string;
  /** The user's override for a model the catalog does not know (true/false/null). */
  vision?: boolean | null;
  testOkAt?: number;
  testSig?: string;
}

export interface AiPrefs {
  layout: 2;
  /** "" = no provider chosen (lead decision 5: fresh installs choose nothing). */
  activeProvider: string;
  /** Lead decision 9: on by default (max 3 photos, vision models only, each untickable). */
  sendPhotos: boolean;
  providers: Record<string, AiProviderPrefs>;
}

export const DEFAULT_AI_PREFS: Readonly<AiPrefs> = Object.freeze({ layout: 2, activeProvider: "", sendPhotos: true, providers: {} });

export const KV = { settings: "settings", ai: "ai", activeBrew: "activeBrew" } as const;

/** Defaults filled in for any key a stored object lacks (older rows, imports). */
export function withSettingsDefaults(raw: unknown, locale: string = browserLocale()): Settings {
  const o = raw != null && typeof raw === "object" ? (raw as Partial<Settings>) : {};
  // A fresh install takes °F where the region uses it (v4 §4.15); a stored value never changes.
  return { ...DEFAULT_SETTINGS, tempUnit: defaultTempUnit(locale), ...o };
}

/**
 * reviewMode for a settings object that has never stored one: quick on a fresh install, detailed
 * for someone who already has brews (they are used to every section open). Null when stored.
 */
export function initialReviewMode(raw: unknown, brewCount: number): ReviewMode | null {
  const o = raw != null && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  if (o.reviewMode === "quick" || o.reviewMode === "detailed") return null;
  return brewCount > 0 ? "detailed" : "quick";
}

export function withAiDefaults(raw: unknown): AiPrefs {
  const o = raw != null && typeof raw === "object" ? (raw as Partial<AiPrefs>) : {};
  return { ...DEFAULT_AI_PREFS, ...o, layout: 2, providers: { ...(o.providers ?? {}) } };
}

async function patchKv<T>(key: string, read: (raw: unknown) => T, patch: (cur: T) => T): Promise<T> {
  return db.transaction("rw", db.kv, async () => {
    const cur = read((await db.kv.get(key))?.value);
    const next = patch(cur);
    await db.kv.put({ key, value: next });
    return next;
  });
}

export const settings = {
  async get(): Promise<Settings> {
    return withSettingsDefaults((await db.kv.get(KV.settings))?.value);
  },
  set<K extends keyof Settings>(key: K, value: Settings[K]): Promise<Settings> {
    return patchKv(KV.settings, withSettingsDefaults, (cur) => ({ ...cur, [key]: value }));
  },
  update(patch: Partial<Settings>): Promise<Settings> {
    return patchKv(KV.settings, withSettingsDefaults, (cur) => ({ ...cur, ...patch }));
  },
  addNotice(id: string): Promise<Settings> {
    return patchKv(KV.settings, withSettingsDefaults, (cur) =>
      cur.pendingNotices.includes(id) ? cur : { ...cur, pendingNotices: [...cur.pendingNotices, id] },
    );
  },
  removeNotice(id: string): Promise<Settings> {
    return patchKv(KV.settings, withSettingsDefaults, (cur) => ({ ...cur, pendingNotices: cur.pendingNotices.filter((n) => n !== id) }));
  },
};

export const aiPrefs = {
  async get(): Promise<AiPrefs> {
    return withAiDefaults((await db.kv.get(KV.ai))?.value);
  },
  update(patch: (cur: AiPrefs) => AiPrefs): Promise<AiPrefs> {
    return patchKv(KV.ai, withAiDefaults, patch);
  },
};
