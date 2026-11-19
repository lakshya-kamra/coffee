/**
 * A§8.1 `AiProviders` for the web (web-spec §14.1), built from shared/catalog/providers.json.
 * Unlike Android, ollama and lm-studio stay in (lead decision 4): their http://localhost address
 * is reachable from a browser on the same computer. Each provider keeps `browserCors` so the
 * settings can say "Needs setup" and require a test for unknown servers.
 */
import type { BrowserCors, CatalogProvider } from "@/catalog/types";
import { checkBaseUrl, isLocalHost } from "./baseUrl";
import { fallbackModelLabel, type Price } from "./modelCatalog";

export type ProviderApi = "openai-chat" | "anthropic-messages";
export type AuthStyle = "bearer" | "x-api-key";

export interface AiModel {
  readonly id: string;
  readonly label: string;
  readonly price: Price | null;
  readonly vision: boolean;
  readonly isDefault: boolean;
  readonly why: string;
}

export interface AiProvider {
  readonly id: string;
  readonly name: string;
  /** Normalised; "" for custom (the user's own address is in the prefs). */
  readonly baseUrl: string;
  readonly api: ProviderApi;
  readonly auth: AuthStyle;
  /** Already through the header allow-list. */
  readonly extraHeaders: Readonly<Record<string, string>>;
  readonly browserHeaders: Readonly<Record<string, string>>;
  readonly keyUrl: string;
  readonly keyHint: string;
  readonly modelsPath: string | null;
  readonly modelsListable: boolean;
  /** The model list answers without a key (OpenRouter, Command Code), so none is sent. */
  readonly modelsPublic: boolean;
  readonly models: readonly AiModel[];
  readonly pricesCheckedOn: string;
  readonly isCustom: boolean;
  readonly browserCors: BrowserCors;
  readonly corsEvidence: string;
  readonly notes: string;
}

export const CUSTOM = "custom";
export const COMMANDCODE = "commandcode";
export const OPENAI = "openai";

const FORBIDDEN_HEADERS = new Set(["authorization", "x-api-key", "cookie", "host", "content-length", "content-type"]);

/** A§8.1 allow-list: name `^[A-Za-z0-9-]{1,64}$`, not a reserved header, printable ASCII value ≤ 256. */
export function headerAllowed(name: string, value: string): boolean {
  if (!/^[A-Za-z0-9-]{1,64}$/.test(name)) return false;
  if (FORBIDDEN_HEADERS.has(name.toLowerCase())) return false;
  return value.length <= 256 && /^[\x20-\x7E]*$/.test(value);
}

function allowedHeaders(map: Readonly<Record<string, string>>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(map)) if (typeof v === "string" && headerAllowed(k, v)) out[k] = v;
  return out;
}

function toApi(api: string): ProviderApi | null {
  return api === "openai-chat" || api === "anthropic-messages" ? api : null;
}

/** null when the entry cannot be used from a browser (unknown wire format, unusable address). */
export function providerFrom(p: CatalogProvider): AiProvider | null {
  const api = toApi(p.api);
  if (!api) return null;
  const isCustom = p.id === CUSTOM;
  let baseUrl = "";
  if (!isCustom) {
    const c = checkBaseUrl(p.baseUrl);
    if (!c.ok) return null;
    baseUrl = c.normalized;
  }
  return Object.freeze({
    id: p.id,
    name: p.name,
    baseUrl,
    api,
    auth: p.auth === "x-api-key" ? "x-api-key" : "bearer",
    extraHeaders: allowedHeaders(p.extraHeaders),
    browserHeaders: allowedHeaders(p.browserHeaders),
    keyUrl: p.keyUrl,
    keyHint: p.keyHint,
    modelsPath: p.listModels.path || null,
    modelsListable: p.listModels.works,
    modelsPublic: /^public\b/i.test(p.listModels.note.trim()),
    models: p.models.map((m) => ({
      id: m.id,
      label: m.label || fallbackModelLabel(m.id),
      price: m.inputPerM != null && m.outputPerM != null ? { inputPerM: m.inputPerM, outputPerM: m.outputPerM } : null,
      vision: m.vision,
      isDefault: m.default,
      why: m.why,
    })),
    pricesCheckedOn: p.pricesCheckedOn,
    isCustom,
    browserCors: p.browserCors,
    corsEvidence: p.corsEvidence,
    notes: p.notes,
  });
}

let builtFor: readonly CatalogProvider[] | null = null;
let built: readonly AiProvider[] = [];

/** Catalog order; memoised on the catalog list's identity. */
export function aiProviders(catalogProviders: readonly CatalogProvider[]): readonly AiProvider[] {
  if (catalogProviders !== builtFor) {
    builtFor = catalogProviders;
    built = Object.freeze(catalogProviders.map(providerFrom).filter((p): p is AiProvider => p != null));
  }
  return built;
}

export function providerById(list: readonly AiProvider[], id: string | null | undefined): AiProvider | null {
  return id ? (list.find((p) => p.id === id) ?? null) : null;
}

export function defaultModel(p: AiProvider): AiModel | null {
  return p.models.find((m) => m.isDefault) ?? p.models[0] ?? null;
}

export function catalogModel(p: AiProvider | null, modelId: string): AiModel | null {
  return p?.models.find((m) => m.id === modelId) ?? null;
}

/** ModelCatalog.price(providerId, model): both catalog prices present, else null. */
export function priceOf(p: AiProvider | null, modelId: string): Price | null {
  return catalogModel(p, modelId)?.price ?? null;
}

/** ModelCatalog.label: the catalog label, else the id after the last "/". */
export function modelLabel(p: AiProvider | null, modelId: string): string {
  return catalogModel(p, modelId)?.label ?? fallbackModelLabel(modelId);
}

/** A model on this computer: the server ignores or does not need a key (Ollama, LM Studio). */
export function keyOptional(host: string): boolean {
  return isLocalHost(host);
}
