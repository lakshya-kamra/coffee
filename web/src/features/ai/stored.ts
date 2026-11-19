/**
 * Stored analyses as the cards read them (AnalysisRepository.toStored), and live hooks over the
 * analyses table. Rows from Android imports carry their own origin and read as "Made on another
 * device" (lead decision 12).
 */
import { useLiveQuery } from "dexie-react-hooks";
import { useMemo } from "react";
import { catalog } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import { db } from "@/data/db";
import type { AnalysisRow } from "@/data/schema";
import { aiProviders, formatTokens, formatUsd, modelLabel, providerById, readStoredResult, storedBrewUids, WEB_ORIGIN, type BrewAnalysis, type DataFlag, type GrindMove, type GrindValueMove, type TrendAnalysis } from "@/domain/ai";
import type { Target } from "./runner";

export const SAMPLE_HOST = "sample.local";

export interface Usage {
  readonly promptTokens: number | null;
  readonly completionTokens: number | null;
  readonly costMicroUsd: number | null;
  readonly latencyMs: number;
}

/** "3,180 in · 1,260 out · ≈0.11¢ · 14 s", leaving out whatever isn't known. */
export function usageLabel(u: Usage): string {
  return [
    u.promptTokens != null ? `${formatTokens(u.promptTokens)} in` : null,
    u.completionTokens != null ? `${formatTokens(u.completionTokens)} out` : null,
    u.costMicroUsd != null ? "≈" + formatUsd(u.costMicroUsd) : null,
    u.latencyMs > 0 ? `${Math.round(u.latencyMs / 1000)} s` : null,
  ]
    .filter((s): s is string => s != null)
    .join(" · ");
}

export interface StoredAnalysis {
  readonly id: number;
  readonly createdAt: number;
  readonly model: string;
  readonly modelLabel: string;
  readonly providerName: string;
  readonly host: string;
  readonly brew: BrewAnalysis | null;
  readonly trend: TrendAnalysis | null;
  readonly grindMove: GrindMove | null;
  readonly valueMove: GrindValueMove | null;
  readonly localWarnings: readonly string[];
  /** A trend's brew numbers to brew uids (brewRowIds); null when the result was stored without them. */
  readonly brewUids: Readonly<Record<string, string>> | null;
  readonly rawText: string;
  /** True only when the result decoded AND holds the answer for its kind. */
  readonly parsed: boolean;
  readonly flags: readonly DataFlag[];
  readonly usage: Usage;
  readonly inputHash: string;
  readonly brewCount: number;
  readonly newestBrewAt: number;
  readonly photoCount: number;
  readonly origin: string;
  readonly promptVersion: number;
  /** Written by a debug seed, not by a model: the UI must say so. */
  readonly isSample: boolean;
}

function flagsOf(v: unknown[]): DataFlag[] {
  return v.filter((f): f is DataFlag => f != null && typeof f === "object" && typeof (f as DataFlag).field === "string" && typeof (f as DataFlag).message === "string");
}

export function toStored(row: AnalysisRow): StoredAnalysis {
  const r = readStoredResult(row.result);
  const trend = row.kind === "trend";
  const providers = aiProviders(catalog.providers);
  const provider = providerById(providers, row.providerId);
  return {
    id: row.id ?? 0,
    createdAt: row.createdAt,
    model: row.model,
    modelLabel: modelLabel(provider, row.model),
    providerName: provider?.name ?? row.host,
    host: row.host,
    brew: r?.brew ?? null,
    trend: r?.trend ?? null,
    grindMove: r?.grindMove ?? null,
    valueMove: r?.valueMove ?? null,
    localWarnings: r?.localWarnings ?? [],
    brewUids: storedBrewUids(r?.brewUids, row.origin === WEB_ORIGIN),
    rawText: row.rawText,
    // '{}' decodes but holds no answer, so it counts as unparsed and shows the raw reply.
    parsed: r != null && (trend ? r.trend != null : r.brew != null),
    flags: flagsOf(Array.isArray(row.flags) ? row.flags : []),
    usage: { promptTokens: row.promptTokens, completionTokens: row.completionTokens, costMicroUsd: row.costMicroUsd, latencyMs: row.latencyMs },
    inputHash: row.inputHash,
    brewCount: row.brewCount,
    newestBrewAt: row.newestBrewAt,
    photoCount: row.photoCount ?? 0,
    origin: row.origin,
    promptVersion: row.promptVersion,
    isSample: row.host === SAMPLE_HOST,
  };
}

async function latestRow(target: Target): Promise<AnalysisRow | null> {
  if (target.kind === "brew") {
    const rows = await db.analyses.where("brewLogId").equals(target.brewId).toArray();
    return rows.sort((a, b) => b.createdAt - a.createdAt)[0] ?? null;
  }
  return (await db.analyses.where("[kind+createdAt]").between(["trend", -Infinity], ["trend", Infinity]).last()) ?? null;
}

/** undefined while the first read is in flight; null when there is none. */
export function useLatestAnalysis(target: Target): StoredAnalysis | null | undefined {
  const key = target.kind === "brew" ? target.brewId : -1;
  const row = useLiveQuery(() => latestRow(target).then((r) => r ?? null), [target.kind, key]);
  // toStored reads provider and model names from providers.json. `named` is an input so the card
  // swaps the bare host and model id for those names once the file lands (the compiler caches on
  // the inputs it can see).
  const named = useCatalog(["providers"]);
  return row === undefined ? undefined : row ? storedAt(row, named) : null;
}

function storedAt(row: AnalysisRow, _providersReady: boolean): StoredAnalysis {
  return toStored(row);
}

/** Brews with at least one stored analysis (the Log's small marker). */
export function useAnalysedBrewIds(): ReadonlySet<number> {
  const ids = useLiveQuery(() => db.analyses.orderBy("brewLogId").uniqueKeys(), []);
  const key = (ids ?? []).filter((k): k is number => typeof k === "number").join(",");
  // Same content keeps the same Set, so memoised rows don't re-render on every emission.
  return useMemo(() => new Set(key ? key.split(",").map(Number) : []), [key]);
}

/** "17 Nov 08:14", local (the footer's "d MMM HH:mm"). */
export function footerTime(ms: number): string {
  const d = new Date(ms);
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const p = (n: number) => (n < 10 ? `0${n}` : String(n));
  return `${d.getDate()} ${months[d.getMonth()]} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
