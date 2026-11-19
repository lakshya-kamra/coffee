/**
 * A§8.3 `AnthropicClient` for the browser (web-spec §14.4): POST {base}/messages with x-api-key,
 * the catalog's anthropic-version (fallback 2023-06-01) and the browser header the catalog lists
 * (anthropic-dangerous-direct-browser-access: without it the CORS preflight fails). Temperature is
 * never sent: providers.json records that anthropic-version 2026-10-21 rejects it with HTTP 400.
 */
import { AiErrors, err, ok, redactSecrets, type AiResult } from "@/domain/ai/aiError";
import { messagesUrl, modelsUrl } from "@/domain/ai/baseUrl";
import { ANALYSIS_TIMEOUT_MS, errorMessage, exchange, isHtml, keyInUrl, MODELS_TIMEOUT_MS, pathOf, requestHeaders, statusError, TEST_TIMEOUT_MS, type HttpReply } from "./transport";
import type { AiRequest, ChatClient, ChatResult, Endpoint, RemoteModel } from "./types";

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => v != null && typeof v === "object" && !Array.isArray(v);

export const FALLBACK_ANTHROPIC_VERSION = "2023-06-01";

function withVersion(e: Endpoint): Endpoint {
  const has = Object.keys(e.extraHeaders).some((k) => k.toLowerCase() === "anthropic-version");
  return has ? e : { ...e, extraHeaders: { ...e.extraHeaders, "anthropic-version": FALLBACK_ANTHROPIC_VERSION } };
}

/** The request body, exactly as sent. Exported for the wire tests. */
export function anthropicBody(r: AiRequest): Obj {
  return {
    model: r.model,
    max_tokens: r.maxTokens,
    system: r.system,
    messages: [
      {
        role: "user",
        content: [
          { type: "text", text: r.user },
          ...(r.images ?? []).map((i) => ({ type: "image", source: { type: "base64", media_type: i.mediaType, data: i.base64 } })),
        ],
      },
    ],
  };
}

interface Decoded {
  text: string;
  stopReason: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  model: string | null;
}

/** Joins the text blocks; thinking and redacted_thinking are skipped. Exported for tests. */
export function decodeMessages(reply: HttpReply, host: string, apiKey: string | null): AiResult<Decoded> {
  if (isHtml(reply)) return err(AiErrors.notExpectedFormat(host));
  let root: unknown;
  try {
    root = JSON.parse(reply.body);
  } catch {
    return err(AiErrors.notExpectedFormat(host));
  }
  if (!isObj(root)) return err(AiErrors.notExpectedFormat(host));
  if (root.type === "error" || root.error != null) return err(AiErrors.badRequest(reply.code, host, redactSecrets(errorMessage(root), apiKey)));
  if (!Array.isArray(root.content)) return err(AiErrors.notExpectedFormat(host));
  const text = root.content
    .filter(isObj)
    .filter((b) => b.type === "text" && typeof b.text === "string")
    .map((b) => b.text as string)
    .join("");
  const usage = isObj(root.usage) ? root.usage : {};
  const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : null);
  return ok({
    text,
    stopReason: typeof root.stop_reason === "string" ? root.stop_reason : null,
    inputTokens: num(usage.input_tokens),
    outputTokens: num(usage.output_tokens),
    model: typeof root.model === "string" ? root.model : null,
  });
}

async function post(e: Endpoint, apiKey: string | null, body: Obj, signal: AbortSignal | undefined, timeoutMs: number) {
  const ep = withVersion(e);
  return exchange({ url: messagesUrl(ep.baseUrl), host: ep.host, method: "POST", headers: requestHeaders(ep, apiKey, true), body: JSON.stringify(body), signal, timeoutMs });
}

export const anthropicMessagesClient: ChatClient = {
  async complete(e, apiKey, r, signal, timeoutMs = ANALYSIS_TIMEOUT_MS): Promise<AiResult<ChatResult>> {
    if (keyInUrl(e, apiKey)) return err(AiErrors.invalidBaseUrl("has-credentials"));
    const x = await post(e, apiKey, anthropicBody(r), signal, timeoutMs);
    if (!x.ok) return x;
    const reply = x.value;
    if (reply.code < 200 || reply.code > 299) return err(statusError(reply, e.host, pathOf(messagesUrl(e.baseUrl)), r.model, apiKey));
    const d = decodeMessages(reply, e.host, apiKey);
    if (!d.ok) return d;
    const v = d.value;
    if (v.stopReason === "max_tokens") return err(AiErrors.truncated(v.outputTokens, null));
    if (v.stopReason === "refusal") return err(AiErrors.filtered(e.host));
    if (!v.text.trim()) return err(AiErrors.emptyReply());
    return ok({ content: v.text, finishReason: v.stopReason, promptTokens: v.inputTokens, completionTokens: v.outputTokens, servedModel: v.model, latencyMs: reply.latencyMs });
  },

  /** A 16-token message; any 200 in Messages format counts, as on Android. */
  async testConnection(e, apiKey, model, signal) {
    if (keyInUrl(e, apiKey)) return err(AiErrors.invalidBaseUrl("has-credentials"));
    const x = await post(e, apiKey, anthropicBody({ model, system: "Reply with the single word OK.", user: "OK?", maxTokens: 16 }), signal, TEST_TIMEOUT_MS);
    if (!x.ok) return x;
    const reply = x.value;
    if (reply.code < 200 || reply.code > 299) return err(statusError(reply, e.host, pathOf(messagesUrl(e.baseUrl)), model, apiKey));
    const d = decodeMessages(reply, e.host, apiKey);
    if (!d.ok) return d;
    return ok({ servedModel: d.value.model, latencyMs: reply.latencyMs, reply: d.value.text });
  },

  async listModels(e, apiKey, signal) {
    if (keyInUrl(e, apiKey)) return err(AiErrors.invalidBaseUrl("has-credentials"));
    const ep = withVersion(e);
    const url = modelsUrl(ep.baseUrl, ep.modelsPath ?? "/models");
    const x = await exchange({ url, host: ep.host, method: "GET", headers: requestHeaders(ep, apiKey, false), signal, timeoutMs: MODELS_TIMEOUT_MS });
    if (!x.ok) return x;
    const reply = x.value;
    if (reply.code < 200 || reply.code > 299) return err(statusError(reply, e.host, pathOf(url), null, apiKey));
    let root: unknown;
    try {
      root = JSON.parse(reply.body);
    } catch {
      return err(AiErrors.notExpectedFormat(e.host));
    }
    if (!isObj(root) || !Array.isArray(root.data)) return err(AiErrors.notExpectedFormat(e.host));
    const out: RemoteModel[] = root.data
      .filter(isObj)
      .filter((m) => typeof m.id === "string" && m.id.trim() !== "")
      .map((m) => ({ id: m.id as string, name: typeof m.display_name === "string" && m.display_name.trim() ? m.display_name : (m.id as string), contextLength: typeof m.max_input_tokens === "number" ? m.max_input_tokens : null, price: null }));
    return ok(out);
  },
};
