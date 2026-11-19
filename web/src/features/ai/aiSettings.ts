/**
 * The AI settings the screens read (A§8.2 `AiSettings`), derived from three sources: the
 * non-secret prefs in IndexedDB (kv `ai`), providers.json, and the key store's badges (never the
 * keys). Writes go through `aiActions`; keys only through keyStore.
 */
import { useSyncExternalStore } from "react";
import { catalog } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import { aiPrefs as aiPrefsStore, type AiPrefs, type AiProviderPrefs } from "@/data/settings";
import { useAiPrefs } from "@/data/store";
import { checkBaseUrl, hostOf, type BaseUrlCheck } from "@/domain/ai/baseUrl";
import type { Price } from "@/domain/ai/modelCatalog";
import { aiProviders, catalogModel, CUSTOM, defaultModel, keyOptional, modelLabel, priceOf, providerById, type AiProvider } from "@/domain/ai/providers";
import type { Endpoint } from "./client/types";
import { keyStore, type KeyBadge } from "./keyStore";

export interface AiSettings {
  readonly providers: readonly AiProvider[];
  readonly provider: AiProvider | null;
  readonly providerId: string;
  readonly providerName: string;
  /** Normalised; "" when no provider is chosen or the custom address is not set. */
  readonly baseUrl: string;
  readonly host: string;
  readonly model: string;
  readonly modelLabel: string;
  readonly hasKey: boolean;
  readonly keyHint: string;
  /** The host the saved key belongs to ("" when none). */
  readonly keyHost: string;
  readonly keyRemembered: boolean;
  /** A key is saved, but for another address: it is not sent. */
  readonly keyForOtherHost: boolean;
  /** A model on this computer: a key is not needed. */
  readonly keyOptional: boolean;
  readonly ready: boolean;
  readonly signature: string;
  readonly testedOk: boolean;
  readonly testOkAt: number | null;
  /** browserCors is not "yes": a passing test is required before Analyse (web-spec §14.1). */
  readonly needsTest: boolean;
  readonly modelSeesImages: boolean;
  /** The catalog knows this model, so its vision flag is not the user's guess. */
  readonly modelInCatalog: boolean;
  readonly visionOverride: boolean | null;
  readonly sendPhotos: boolean;
  readonly price: Price | null;
  readonly pricesCheckedOn: string;
}

export function providerPrefs(prefs: AiPrefs, id: string): AiProviderPrefs | null {
  return prefs.providers[id] ?? null;
}

export function deriveSettings(prefs: AiPrefs, providers: readonly AiProvider[], badge: (providerId: string) => KeyBadge | null): AiSettings {
  const provider = providerById(providers, prefs.activeProvider);
  const p = provider ? providerPrefs(prefs, provider.id) : null;
  let baseUrl = provider?.baseUrl ?? "";
  if (provider?.isCustom) {
    const c = p?.baseUrl ? checkBaseUrl(p.baseUrl) : null;
    baseUrl = c?.ok ? c.normalized : "";
  }
  const host = baseUrl ? hostOf(baseUrl) : "";
  const model = (p?.model ?? "").trim() || (provider ? (defaultModel(provider)?.id ?? "") : "");
  const b = provider ? badge(provider.id) : null;
  const hasKey = b != null && host !== "" && b.host === host;
  const optional = keyOptional(host);
  const inCatalog = catalogModel(provider, model);
  const override = p?.vision ?? null;
  // What a passed connection test vouches for. It is stored in IndexedDB (testSig), so the key's
  // part is the moment it was saved, never any of its characters.
  const signature = `${provider?.id ?? ""}|${baseUrl}|${model}|${hasKey ? `k${b?.savedAt ?? 0}` : ""}`;
  const testedOk = p?.testOkAt != null && p.testSig === signature;
  const ready = provider != null && host !== "" && model !== "" && (hasKey || optional);
  return {
    providers,
    provider,
    providerId: provider?.id ?? "",
    providerName: provider?.name ?? "",
    baseUrl,
    host,
    model,
    modelLabel: model ? modelLabel(provider, model) : "",
    hasKey,
    keyHint: b?.hint ?? "",
    keyHost: b?.host ?? "",
    keyRemembered: b?.remembered ?? false,
    keyForOtherHost: b != null && host !== "" && b.host !== host,
    keyOptional: optional,
    ready,
    signature,
    testedOk,
    testOkAt: testedOk ? (p?.testOkAt ?? null) : null,
    needsTest: provider != null && provider.browserCors !== "yes" && !testedOk,
    modelSeesImages: inCatalog ? inCatalog.vision : override === true,
    modelInCatalog: inCatalog != null,
    visionOverride: override,
    sendPhotos: prefs.sendPhotos,
    price: priceOf(provider, model),
    pricesCheckedOn: provider?.pricesCheckedOn ?? "",
  };
}

export function endpointOf(s: AiSettings): Endpoint | null {
  const p = s.provider;
  if (!p || !s.baseUrl) return null;
  return { providerId: p.id, baseUrl: s.baseUrl, host: s.host, api: p.api, auth: p.auth, extraHeaders: p.extraHeaders, browserHeaders: p.browserHeaders, modelsPath: p.modelsPath, modelsPublic: p.modelsPublic };
}

/** The key for the active provider, bound to the host the request is about to go to. */
export function readKeyFor(s: AiSettings): string | null {
  return s.provider && s.host ? keyStore.read(s.provider.id, s.host) : null;
}

// ------------------------------------------------------------------- the hook

let cache: { prefs: AiPrefs; providers: readonly unknown[]; keys: number; value: AiSettings } | null = null;

/** Synchronous derivation for code outside React (the runner). */
export function currentSettings(prefs: AiPrefs): AiSettings {
  const providers = aiProviders(catalog.providers);
  const keys = keyStore.version();
  if (cache && cache.prefs === prefs && cache.providers === catalog.providers && cache.keys === keys) return cache.value;
  const value = deriveSettings(prefs, providers, keyStore.badge);
  cache = { prefs, providers: catalog.providers, keys, value };
  return value;
}

export function useAiSettings(): AiSettings {
  const ready = useCatalog(["providers"]);
  const prefs = useAiPrefs();
  const keys = useSyncExternalStore(keyStore.subscribe, keyStore.version, keyStore.version);
  // `ready` and `keys` are inputs on purpose: the compiler caches on the inputs it can see, and on
  // prefs alone the provider list stayed empty when providers.json landed after the first render.
  return settingsAt(prefs, ready, keys);
}

function settingsAt(prefs: AiPrefs, _providersReady: boolean, _keys: number): AiSettings {
  return currentSettings(prefs);
}

// ------------------------------------------------------------------- writes

function patchProvider(id: string, patch: (p: AiProviderPrefs) => AiProviderPrefs): Promise<AiPrefs> {
  return aiPrefsStore.update((cur) => {
    const old = cur.providers[id] ?? { model: "" };
    return { ...cur, providers: { ...cur.providers, [id]: patch(old) } };
  });
}

export const aiActions = {
  setProvider: (id: string) => aiPrefsStore.update((cur) => ({ ...cur, activeProvider: id })),

  /** Custom only; only a valid address is stored (A§8.2 setBaseUrl). */
  async setBaseUrl(raw: string): Promise<BaseUrlCheck> {
    const c = checkBaseUrl(raw);
    if (c.ok) await patchProvider(CUSTOM, (p) => ({ ...p, baseUrl: c.normalized }));
    return c;
  },

  setModel: (providerId: string, model: string) => patchProvider(providerId, (p) => ({ ...p, model: model.trim() })),

  setVisionOverride: (providerId: string, v: boolean | null) => patchProvider(providerId, (p) => ({ ...p, vision: v })),

  setSendPhotos: (on: boolean) => aiPrefsStore.update((cur) => ({ ...cur, sendPhotos: on })),

  markTested: (providerId: string, signature: string, at: number) => patchProvider(providerId, (p) => ({ ...p, testOkAt: at, testSig: signature })),

  /** Removes the key and what was remembered about testing it, so nothing of it outlives "Remove key". */
  removeKey(providerId: string): Promise<AiPrefs> {
    keyStore.remove(providerId);
    return patchProvider(providerId, ({ testOkAt: _at, testSig: _sig, ...rest }) => rest);
  },
};
