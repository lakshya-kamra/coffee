/**
 * Port of data/ai/AnalysisRepository.kt: runs, stores and serves analyses. Runs live here at module
 * scope, not in a component, so leaving the screen never throws away a reply already paid for.
 * `start` is idempotent per target, so a double tap can't bill twice. Nothing runs by itself:
 * every network call starts from a tap. Failures and cancels are held in memory only and a
 * failed re-run never hides the previous result. Nothing is ever logged.
 */
import { useSyncExternalStore } from "react";
import { catalog } from "@/catalog/catalog";
import { db } from "@/data/db";
import { repo } from "@/data/repo";
import { newUid, type AnalysisRow, type BeanRow, type BrewRow, type GrinderRow, type PhotoRow } from "@/data/schema";
import { getSnapshot } from "@/data/store";
import type { Recipe } from "@/domain/core/recipe";
import {
  AiErrors,
  AnalysisParser,
  brewerOf,
  brewUidsFor,
  buildBrewPrompt,
  buildTrendPrompt,
  costMicroUsd,
  formatUsd,
  HISTORY_FOR_BREW,
  MAX_PHOTOS,
  MIN_RATED_FOR_TREND,
  resolveGrindMove,
  resolveGrindValueMove,
  TEMPERATURE,
  TREND_MAX_BREWS,
  VERSION,
  WEB_ORIGIN,
  type AiError,
  type BuiltPrompt,
  type DataFlag,
  type PhotoMeta,
  type StoredResult,
} from "@/domain/ai";
import { clientFor } from "./client";
import { CancelledError } from "./client/transport";
import type { AiImage } from "./client/types";
import { currentSettings, endpointOf, readKeyFor, type AiSettings } from "./aiSettings";

export type Target = { readonly kind: "brew"; readonly brewId: number } | { readonly kind: "trend" };

export const KEEP_PER_TARGET = 5;
const MAX_IMAGE_BASE64 = 4 * 1024 * 1024;

export const targetKey = (t: Target): string => (t.kind === "trend" ? "trend" : `brew:${t.brewId}`);

// ------------------------------------------------------------------ live state

interface RunnerState {
  /** Target key → when its run started. */
  readonly running: ReadonlyMap<string, number>;
  readonly failures: ReadonlyMap<string, AiError>;
}

let state: RunnerState = { running: new Map(), failures: new Map() };
const listeners = new Set<() => void>();
const controllers = new Map<string, AbortController>();

function set(next: RunnerState) {
  state = next;
  for (const l of listeners) l();
}

function withMap<V>(m: ReadonlyMap<string, V>, key: string, value: V | undefined): Map<string, V> {
  const out = new Map(m);
  if (value === undefined) out.delete(key);
  else out.set(key, value);
  return out;
}

export const runner = {
  subscribe: (fn: () => void): (() => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
  get: (): RunnerState => state,

  /** Starts a run unless one is already going for this target. Returns false if it was. */
  start: (target: Target): boolean => {
    const key = targetKey(target);
    if (controllers.has(key)) return false;
    const c = new AbortController();
    controllers.set(key, c);
    set({ running: withMap(state.running, key, Date.now()), failures: withMap(state.failures, key, undefined) });
    void perform(target, c.signal)
      .then((error) => {
        if (error) set({ ...state, failures: withMap(state.failures, key, error) });
      })
      .finally(() => {
        controllers.delete(key);
        set({ ...state, running: withMap(state.running, key, undefined) });
      });
    return true;
  },

  /** Stops waiting. Records no failure; the provider may still bill what it already did. */
  cancel: (target: Target): void => {
    controllers.get(targetKey(target))?.abort();
  },

  clearFailure: (target: Target): void => {
    const key = targetKey(target);
    if (state.failures.has(key)) set({ ...state, failures: withMap(state.failures, key, undefined) });
  },

  /** Test hook. */
  resetForTests: (): void => {
    controllers.forEach((c) => c.abort());
    controllers.clear();
    set({ running: new Map(), failures: new Map() });
  },
};

export function useRun(target: Target): { runningSince: number | null; error: AiError | null } {
  const key = targetKey(target);
  const since = useSyncExternalStore(runner.subscribe, () => state.running.get(key) ?? null);
  const error = useSyncExternalStore(runner.subscribe, () => state.failures.get(key) ?? null);
  return { runningSince: since, error };
}

// ------------------------------------------------------------------ prompt building

interface Loaded {
  recipes: Map<string, Recipe>;
  beans: Map<number, BeanRow>;
  grinders: Map<number, GrinderRow>;
  /** Newest first. */
  logs: readonly BrewRow[];
  units: string;
}

/** Catalog files the prompt reads labels and scales from. */
export const PROMPT_CATALOGS = ["brewers", "recipes", "grinders", "roasters", "origins", "beans", "water", "providers", "principles"] as const;

async function load(): Promise<Loaded> {
  await catalog.load(PROMPT_CATALOGS);
  const s = getSnapshot();
  // Archived grinders and beans are in the snapshot too: old brews still name them.
  return {
    recipes: new Map(s.recipes.map((r) => [r.id, r])),
    beans: new Map(s.beans.filter((b) => b.id != null).map((b) => [b.id!, b])),
    grinders: new Map(s.grinders.filter((g) => g.id != null).map((g) => [g.id!, g])),
    logs: s.brews,
    units: s.settings.tempUnit,
  };
}

/** A§9.3 rule: bed and cup photos that are ticked, newest 3, oldest first in the prompt. */
export function photosToAttach(rows: readonly PhotoRow[]): PhotoMeta[] {
  return rows
    .filter((p) => (p.kind === "bed" || p.kind === "cup") && p.sendToAi)
    .sort((a, b) => b.takenAt - a.takenAt)
    .slice(0, MAX_PHOTOS)
    .reverse()
    .map((p) => ({ uid: p.uid, kind: p.kind, takenAt: p.takenAt, bytes: p.bytes }));
}

export function attachesPhotos(s: Pick<AiSettings, "sendPhotos" | "modelSeesImages">): boolean {
  return s.sendPhotos && s.modelSeesImages;
}

type Build =
  | { readonly ready: true; readonly built: BuiltPrompt; readonly log: BrewRow | null; readonly grinder: GrinderRow | null; readonly photos: readonly PhotoMeta[]; /** The earlier brews sent as context; for a trend, every brew sent. */ readonly history: readonly BrewRow[] }
  | { readonly ready: false; readonly error: AiError; readonly brewCount: number; readonly ratedCount: number };

async function build(target: Target, s: AiSettings): Promise<Build> {
  const ctx = await load();
  const principles = catalog.principles;
  if (target.kind === "brew") {
    const log = ctx.logs.find((l) => l.id === target.brewId) ?? null;
    if (!log) return { ready: false, error: AiErrors.brewMissing(), brewCount: 0, ratedCount: 0 };
    const history = ctx.logs.filter((l) => l.startedAt < log.startedAt).slice(0, HISTORY_FOR_BREW);
    const grinder = log.grinderId != null ? (ctx.grinders.get(log.grinderId) ?? null) : null;
    const brewPhotos = photosToAttach(await repo.photosForBrew(target.brewId));
    const photos = attachesPhotos(s) ? brewPhotos : [];
    const built = buildBrewPrompt({
      log,
      recipe: ctx.recipes.get(log.recipeId) ?? null,
      bean: log.beanId != null ? (ctx.beans.get(log.beanId) ?? null) : null,
      grinder,
      history,
      photos,
      brewPhotos,
      recipes: ctx.recipes,
      beans: ctx.beans,
      grinders: ctx.grinders,
      units: ctx.units,
      principles,
    });
    return { ready: true, built, log, grinder, photos, history };
  }
  const capped = ctx.logs.slice(0, TREND_MAX_BREWS);
  const rated = capped.filter((l) => l.overall > 0).length;
  // Checked before the builder runs, so an empty log never reaches it.
  if (rated < MIN_RATED_FOR_TREND) return { ready: false, error: AiErrors.notEnoughData(MIN_RATED_FOR_TREND, rated), brewCount: capped.length, ratedCount: rated };
  const built = buildTrendPrompt({ logs: capped, totalLogged: ctx.logs.length, recipes: ctx.recipes, beans: ctx.beans, grinders: ctx.grinders, units: ctx.units, principles });
  return { ready: true, built, log: null, grinder: null, photos: [], history: capped };
}

export interface AnalysisPreview {
  /** Brew: flags on the target brew. Trend: flags on every included brew. */
  readonly flags: readonly DataFlag[];
  /** Brew: flags on the earlier brews sent as context. */
  readonly contextFlags: readonly DataFlag[];
  readonly inputHash: string;
  readonly brewCount: number;
  readonly ratedCount: number;
  readonly newestBrewAt: number;
  readonly approxPromptTokens: number;
  readonly expectedCompletionTokens: number;
  readonly photoCount: number;
  /** Why an analysis can't run right now (NotEnoughData, BrewMissing), or null. */
  readonly blocker: AiError | null;
}

function blocked(error: AiError, brewCount: number, ratedCount: number): AnalysisPreview {
  return { flags: [], contextFlags: [], inputHash: "", brewCount, ratedCount, newestBrewAt: 0, approxPromptTokens: 0, expectedCompletionTokens: 0, photoCount: 0, blocker: error };
}

/** Local only, never the network: flags, the input hash for staleness, and the estimate. */
export async function preview(target: Target, s: AiSettings): Promise<AnalysisPreview> {
  try {
    const b = await build(target, s);
    if (!b.ready) return blocked(b.error, b.brewCount, b.ratedCount);
    const p = b.built;
    const own = target.kind === "brew" ? p.flags.filter((f) => f.brewId === target.brewId) : p.flags;
    const context = target.kind === "brew" ? p.flags.filter((f) => f.brewId !== target.brewId) : [];
    return {
      flags: own,
      contextFlags: context,
      inputHash: p.inputHash,
      brewCount: p.brewCount,
      ratedCount: p.ratedCount,
      newestBrewAt: p.newestBrewAt,
      approxPromptTokens: p.approxPromptTokens,
      expectedCompletionTokens: p.expectedCompletionTokens,
      photoCount: p.photoCount,
      blocker: null,
    };
  } catch (e) {
    // A broken row or a builder bug shows as a disabled card, not a crash.
    return blocked(AiErrors.unknown(e instanceof Error ? e.name : "Error"), 0, 0);
  }
}

// ------------------------------------------------------------------ the run

/** Resolves to the failure to show, or null on success or cancel. */
async function perform(target: Target, signal: AbortSignal): Promise<AiError | null> {
  try {
    await catalog.load(PROMPT_CATALOGS);
    const s = currentSettings(getSnapshot().aiPrefs);
    if (!s.provider) return AiErrors.notConfigured({ missingKey: true, missingModel: true, host: "", keyHost: "", noProvider: true });
    const endpoint = endpointOf(s);
    if (!endpoint) return AiErrors.invalidBaseUrl("blank");
    if (!s.ready) return AiErrors.notConfigured({ missingKey: !s.hasKey && !s.keyOptional, missingModel: !s.model, host: s.host, keyHost: s.keyHost });
    if (s.needsTest) return AiErrors.needsTest(s.host);
    // Bound to the host this request is about to go to, read at the moment of use.
    const key = readKeyFor(s);
    if (!key && !s.keyOptional) return AiErrors.notConfigured({ missingKey: true, missingModel: false, host: s.host, keyHost: s.keyHost });

    const b = await build(target, s);
    if (!b.ready) return b.error;
    const built = b.built;

    let images: AiImage[] = [];
    if (target.kind === "brew" && b.photos.length > 0) {
      const { photoAttachmentsForBrew } = await import("@/features/photos");
      images = trimImages((await photoAttachmentsForBrew(target.brewId)).slice(-b.photos.length));
    }

    const client = clientFor(endpoint.api);
    const r = await client.complete(endpoint, key, { model: s.model, system: built.system, user: built.user, images, temperature: TEMPERATURE, maxTokens: built.maxTokens }, signal);
    if (!r.ok) return withCostLabel(r.error, s, built, images.length);
    const reply = r.value;

    const result = parse(target, b, reply.content);
    // A reply that spent the whole budget and does not parse was cut off.
    if (result == null && reply.completionTokens != null && reply.completionTokens >= built.maxTokens) {
      return withCostLabel(AiErrors.truncated(reply.completionTokens, null), s, built, images.length);
    }
    const cost = reply.promptTokens != null && reply.completionTokens != null && s.price ? costMicroUsd(s.price, reply.promptTokens, reply.completionTokens) : null;
    const row: AnalysisRow = {
      uid: newUid(),
      kind: target.kind,
      brewLogId: target.kind === "brew" ? target.brewId : null,
      createdAt: Date.now(),
      promptVersion: VERSION,
      inputHash: built.inputHash,
      brewCount: built.brewCount,
      newestBrewAt: built.newestBrewAt,
      model: s.model,
      host: s.host,
      rawText: reply.content,
      result,
      flags: [...built.flags],
      promptTokens: reply.promptTokens,
      completionTokens: reply.completionTokens,
      costMicroUsd: cost,
      latencyMs: reply.latencyMs,
      providerId: s.providerId,
      photoCount: images.length,
      origin: WEB_ORIGIN,
    };
    // The brew may have been deleted while the model was thinking.
    if (target.kind === "brew") {
      const ok = await db.transaction("rw", [db.brew_logs, db.analyses], async () => {
        if (!(await db.brew_logs.get(target.brewId))) return false;
        await repo.insertAnalysis(row);
        return true;
      });
      if (!ok) return AiErrors.brewMissing();
    } else {
      await repo.insertAnalysis(row);
    }
    await prune(target);
    return null;
  } catch (e) {
    if (e instanceof CancelledError) return null;
    // Anything unexpected becomes an error card; the type name only, never its message.
    return AiErrors.unknown(e instanceof Error ? e.name || "Error" : "Error");
  }
}

/** If the total base64 exceeds 4 MB, drop the oldest (A§9.3). */
function trimImages(images: AiImage[]): AiImage[] {
  const out = [...images];
  while (out.length > 0 && out.reduce((n, i) => n + i.base64.length, 0) > MAX_IMAGE_BASE64) out.shift();
  return out;
}

/** A reply that isn't the JSON contract is still stored (result null) and shown raw. */
function parse(target: Target, b: Extract<Build, { ready: true }>, content: string): StoredResult | null {
  if (target.kind === "trend") {
    const p = AnalysisParser.parseTrend(content);
    if (!p.ok) return null;
    // A trend keeps the uid of every brew its patterns name: the numbers alone don't survive a restore.
    const uids = new Map(b.history.map((l) => [l.id ?? 0, l.uid]));
    return { trend: p.value, localWarnings: [], brewUids: brewUidsFor(p.value, (n) => uids.get(n)) };
  }
  const p = AnalysisParser.parseBrew(content);
  if (!p.ok) return null;
  // The hero starts from a value the app trusts, never the model's guess.
  const method = b.log ? (brewerOf(b.log, null)?.method ?? null) : null;
  const clicks = resolveGrindMove(p.value.change, b.grinder, b.built.grindAnchor, method);
  const value = resolveGrindValueMove(p.value.change, b.grinder, b.built.valueAnchor, method);
  return { brew: p.value, grindMove: clicks.move, valueMove: value.move, localWarnings: [...clicks.warnings, ...value.warnings] };
}

function withCostLabel(error: AiError, s: AiSettings, built: BuiltPrompt, images: number): AiError {
  if (error.kind !== "truncated") return error;
  const t = error as AiError & { completionTokens: number | null; costLabel: string | null };
  if (t.costLabel != null || t.completionTokens == null || !s.price) return error;
  // The prompt side is the local estimate: a cut-off reply carries no usage we can trust.
  const micro = costMicroUsd(s.price, estimatePromptTokens(built.approxPromptTokens, images), t.completionTokens);
  return AiErrors.truncated(t.completionTokens, "≈" + formatUsd(micro));
}

/** ≈1,100 prompt tokens per attached image (the app's own rule of thumb). */
export function estimatePromptTokens(approx: number, images: number): number {
  return approx + images * 1100;
}

async function prune(target: Target): Promise<void> {
  const rows =
    target.kind === "brew"
      ? await repo.analysesForBrew(target.brewId)
      : (await db.analyses.where("[kind+createdAt]").between(["trend", -Infinity], ["trend", Infinity]).toArray()).sort((a, b) => b.createdAt - a.createdAt);
  const extra = rows.slice(KEEP_PER_TARGET).map((r) => r.id!).filter((id) => id != null);
  if (extra.length) await db.analyses.bulkDelete(extra);
}
