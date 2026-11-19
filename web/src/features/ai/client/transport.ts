/**
 * Browser transport (web-spec §14.3), the fetch port of Kotlin HttpTransport: CORS, no cookies,
 * no referrer, redirects refused (so the key can never be forwarded elsewhere), no cache, a
 * user-cancel signal combined with a hard timeout, a 1 MiB reply cap, and errors mapped like
 * Kotlin's statusError. Nothing here logs, and nothing adds anything that identifies the user.
 */
import { AiErrors, err, ok, redactSecrets, type AiError, type AiResult } from "@/domain/ai/aiError";
import { headerAllowed } from "@/domain/ai/providers";
import type { Endpoint } from "./types";

export const ANALYSIS_TIMEOUT_MS = 90_000;
export const TEST_TIMEOUT_MS = 20_000;
export const MODELS_TIMEOUT_MS = 20_000;
export const MAX_BODY_BYTES = 1024 * 1024;

export interface HttpReply {
  readonly code: number;
  readonly contentType: string | null;
  readonly body: string;
  readonly retryAfter: string | null;
  readonly latencyMs: number;
}

export const isHtml = (r: HttpReply) => (r.contentType ?? "").toLowerCase().includes("html");

/** Thrown (not returned) when the user cancels: a cancel records no failure. */
export class CancelledError extends Error {
  constructor() {
    super("Cancelled");
    this.name = "CancelledError";
  }
}

/** AbortSignal.any with a fallback for browsers that lack it. */
export function anySignal(signals: readonly AbortSignal[]): AbortSignal {
  const any = (AbortSignal as unknown as { any?: (s: AbortSignal[]) => AbortSignal }).any;
  if (typeof any === "function") return any.call(AbortSignal, [...signals]);
  const c = new AbortController();
  for (const s of signals) {
    if (s.aborted) {
      c.abort(s.reason);
      break;
    }
    s.addEventListener("abort", () => c.abort(s.reason), { once: true });
  }
  return c.signal;
}

function timeoutSignal(ms: number): AbortSignal {
  if (typeof AbortSignal.timeout === "function") return AbortSignal.timeout(ms);
  const c = new AbortController();
  setTimeout(() => c.abort(new DOMException("Timed out", "TimeoutError")), ms);
  return c.signal;
}

/** Auth, content type, the catalog's extra headers, then the web-only browser headers. */
export function requestHeaders(e: Endpoint, apiKey: string | null, json: boolean): Record<string, string> {
  const h: Record<string, string> = { accept: "application/json" };
  if (apiKey) {
    if (e.auth === "x-api-key") h["x-api-key"] = apiKey;
    else h.authorization = `Bearer ${apiKey}`;
  }
  if (json) h["content-type"] = "application/json";
  for (const [k, v] of Object.entries(e.extraHeaders)) if (headerAllowed(k, v)) h[k.toLowerCase()] = v;
  for (const [k, v] of Object.entries(e.browserHeaders)) if (headerAllowed(k, v)) h[k.toLowerCase()] = v;
  return h;
}

async function readCapped(res: Response): Promise<string | null> {
  if (!res.body) return "";
  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_BODY_BYTES) {
      await reader.cancel().catch(() => undefined);
      return null;
    }
    chunks.push(value);
  }
  const all = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) {
    all.set(c, off);
    off += c.byteLength;
  }
  return new TextDecoder().decode(all);
}

function origin(): string {
  return typeof location !== "undefined" ? location.origin : "this site";
}

function online(): boolean {
  return typeof navigator === "undefined" || navigator.onLine !== false;
}

/** One round trip. Resolves to an AiError for every failure except a user cancel, which throws. */
export async function exchange(p: {
  url: string;
  host: string;
  method: "GET" | "POST";
  headers: Record<string, string>;
  body?: string;
  signal?: AbortSignal;
  timeoutMs: number;
}): Promise<AiResult<HttpReply>> {
  const timeout = timeoutSignal(p.timeoutMs);
  const signal = p.signal ? anySignal([p.signal, timeout]) : timeout;
  const started = performance.now();
  let res: Response;
  try {
    res = await fetch(p.url, {
      method: p.method,
      headers: p.headers,
      body: p.body,
      signal,
      mode: "cors",
      credentials: "omit",
      referrerPolicy: "no-referrer",
      redirect: "error",
      cache: "no-store",
    });
  } catch {
    return err(failure(p, timeout));
  }
  let body: string | null;
  try {
    body = await readCapped(res);
  } catch {
    return err(failure(p, timeout));
  }
  if (body == null) return err(AiErrors.tooLarge(p.host));
  return ok({ code: res.status, contentType: res.headers.get("content-type"), body, retryAfter: res.headers.get("retry-after"), latencyMs: Math.round(performance.now() - started) });

  function failure(q: typeof p, t: AbortSignal): AiError {
    if (q.signal?.aborted) throw new CancelledError();
    if (t.aborted) return AiErrors.readTimeout(q.host, Math.round(q.timeoutMs / 1000));
    // A TypeError with no response: offline, or the server does not allow this site (CORS).
    // The exception's own text is never shown: it can quote the URL.
    return online() ? AiErrors.unreachable(q.host, origin()) : AiErrors.offline();
  }
}

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => v != null && typeof v === "object" && !Array.isArray(v);

/** The provider's own error text from any of the usual shapes. */
export function errorMessage(v: unknown): string | null {
  if (typeof v === "string") return v;
  if (Array.isArray(v)) return v.length ? errorMessage(v[0]) : null;
  if (!isObj(v)) return null;
  const e = v.error;
  if (typeof e === "string") return e;
  if (isObj(e) && typeof e.message === "string") return e.message;
  if (typeof v.message === "string") return v.message;
  if (typeof v.detail === "string") return v.detail;
  return null;
}

/** The provider's error text, or a short plain-text body. Never an HTML page. */
export function providerText(body: string): string | null {
  if (!body.trim()) return null;
  try {
    return errorMessage(JSON.parse(body));
  } catch {
    const t = body.trim();
    return t.startsWith("<") ? null : t;
  }
}

const MODEL_WORD = /\bmodel\b/i;
const MISSING_WORDS = /not found|does not exist|doesn't exist|invalid|unknown|not supported/i;
const PARAMETER_WORDS = /temperature|max_tokens|max_completion_tokens|top_p|parameter/i;

function saysModelMissing(message: string | null, model: string): boolean {
  if (!message?.trim()) return false;
  const namesModel = message.toLowerCase().includes(model.toLowerCase()) || MODEL_WORD.test(message);
  // "invalid temperature for this model" is a parameter problem, not a missing model.
  return namesModel && !PARAMETER_WORDS.test(message) && MISSING_WORDS.test(message);
}

export function pathOf(url: string): string {
  try {
    return new URL(url).pathname;
  } catch {
    return "";
  }
}

/** Kotlin statusError, plus 529 (Anthropic "overloaded") as a server error. */
export function statusError(reply: HttpReply, host: string, path: string, model: string | null, apiKey: string | null): AiError {
  const code = reply.code;
  const message = redactSecrets(providerText(reply.body), apiKey);
  if (code === 401 || code === 403) return AiErrors.unauthorized(code, host, model ?? "", message);
  if (code === 402) return AiErrors.paymentRequired(host);
  if (code === 404 && model != null && reply.body.toLowerCase().includes(model.toLowerCase())) return AiErrors.modelNotFound(host, model);
  if ((code === 400 || code === 422) && model != null && saysModelMissing(message, model)) return AiErrors.modelNotFound(host, model);
  if (code === 404) return AiErrors.endpointNotFound(host, path);
  if (code === 429) {
    const n = Number(reply.retryAfter?.trim());
    return AiErrors.rateLimited(reply.retryAfter != null && Number.isInteger(n) ? n : null);
  }
  if (code === 400 || code === 413 || code === 422) return AiErrors.badRequest(code, host, message);
  if (code === 408 || code >= 500) return AiErrors.serverError(code, host);
  return AiErrors.badRequest(code, host, message);
}

/** Refuses an address that carries the key before anything touches the network. */
export function keyInUrl(e: Endpoint, apiKey: string | null): boolean {
  return apiKey != null && apiKey.length >= 8 && e.baseUrl.includes(apiKey);
}
