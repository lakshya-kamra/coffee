/**
 * A§8.3 `OpenAiCompatClient` for the browser (web-spec §14.4): POST {base}/chat/completions.
 * Content is a plain string without images, else parts with data: URLs; `max_completion_tokens`
 * for OpenAI's reasoning models, `max_tokens` for everyone else, with one free retry on a 400 that
 * names the other field, and the free temperature retry. A 400 is not billed.
 */
import { AiErrors, err, ok, redactSecrets, type AiResult } from "@/domain/ai/aiError";
import { chatCompletionsUrl, modelsUrl } from "@/domain/ai/baseUrl";
import { OPENAI } from "@/domain/ai/providers";
import { exchange, isHtml, keyInUrl, MODELS_TIMEOUT_MS, ANALYSIS_TIMEOUT_MS, pathOf, providerText, requestHeaders, statusError, TEST_TIMEOUT_MS, errorMessage, type HttpReply } from "./transport";
import type { AiRequest, ChatClient, ChatResult, Endpoint, RemoteModel } from "./types";

type TokenField = "max_tokens" | "max_completion_tokens";
type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => v != null && typeof v === "object" && !Array.isArray(v);

/** OpenAI's reasoning models reject max_tokens and temperature (providers.json notes). */
export function tokenFieldFor(providerId: string): TokenField {
  return providerId === OPENAI ? "max_completion_tokens" : "max_tokens";
}

/** The request body, exactly as sent. Exported for the wire tests. */
export function openAiBody(r: AiRequest, tokenField: TokenField, temperature: number | null): Obj {
  const images = r.images ?? [];
  const userContent: unknown =
    images.length === 0
      ? r.user
      : [{ type: "text", text: r.user }, ...images.map((i) => ({ type: "image_url", image_url: { url: `data:${i.mediaType};base64,${i.base64}` } }))];
  const body: Obj = {
    model: r.model,
    messages: [
      { role: "system", content: r.system },
      { role: "user", content: userContent },
    ],
  };
  if (temperature != null) body.temperature = temperature;
  body[tokenField] = r.maxTokens;
  return body;
}

/** message.content as text: a string, or the text parts of an array. Reasoning is never shown. */
function contentText(v: unknown): string | null {
  if (typeof v === "string") return v;
  if (Array.isArray(v)) {
    const parts = v.filter(isObj).filter((p) => (p.type === "text" || p.type === "output_text") && typeof p.text === "string").map((p) => p.text as string);
    return parts.length ? parts.join("") : null;
  }
  return null;
}

interface Decoded {
  content: string | null;
  finishReason: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  model: string | null;
}

function decodeChat(reply: HttpReply, host: string, apiKey: string | null): AiResult<Decoded> {
  if (isHtml(reply)) return err(AiErrors.notExpectedFormat(host));
  let root: unknown;
  try {
    root = JSON.parse(reply.body);
  } catch {
    return err(AiErrors.notExpectedFormat(host));
  }
  if (!isObj(root)) return err(AiErrors.notExpectedFormat(host));
  if (root.error != null) return err(AiErrors.badRequest(reply.code, host, redactSecrets(errorMessage(root), apiKey)));
  if (!Array.isArray(root.choices)) return err(AiErrors.notExpectedFormat(host));
  const choice: unknown = root.choices[0];
  const usage = isObj(root.usage) ? root.usage : {};
  const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : null);
  if (!isObj(choice)) return ok({ content: null, finishReason: null, promptTokens: num(usage.prompt_tokens), completionTokens: num(usage.completion_tokens), model: typeof root.model === "string" ? root.model : null });
  const message = isObj(choice.message) ? choice.message : {};
  return ok({
    content: contentText(message.content),
    finishReason: typeof choice.finish_reason === "string" ? choice.finish_reason : null,
    promptTokens: num(usage.prompt_tokens),
    completionTokens: num(usage.completion_tokens),
    model: typeof root.model === "string" ? root.model : null,
  });
}

async function post(e: Endpoint, apiKey: string | null, body: Obj, signal: AbortSignal | undefined, timeoutMs: number) {
  return exchange({ url: chatCompletionsUrl(e.baseUrl), host: e.host, method: "POST", headers: requestHeaders(e, apiKey, true), body: JSON.stringify(body), signal, timeoutMs });
}

export const openAiChatClient: ChatClient = {
  async complete(e, apiKey, r, signal, timeoutMs = ANALYSIS_TIMEOUT_MS): Promise<AiResult<ChatResult>> {
    if (keyInUrl(e, apiKey)) return err(AiErrors.invalidBaseUrl("has-credentials"));
    const url = chatCompletionsUrl(e.baseUrl);
    let tokenField = tokenFieldFor(e.providerId);
    // OpenAI's own reasoning models reject a temperature, so it is never sent there.
    let temperature = e.providerId === OPENAI ? null : (r.temperature ?? null);
    let first = await post(e, apiKey, openAiBody(r, tokenField, temperature), signal, timeoutMs);
    if (!first.ok) return first;
    let reply = first.value;
    let latency = reply.latencyMs;
    let retriedTokens = false;
    let retriedTemp = false;
    // Up to two free retries: a 400 is not billed.
    for (let i = 0; i < 2 && reply.code === 400; i++) {
      const said = (providerText(reply.body) ?? "").toLowerCase();
      if (!retriedTemp && temperature != null && said.includes("temperature")) {
        retriedTemp = true;
        temperature = null;
      } else if (!retriedTokens && (said.includes("max_tokens") || said.includes("max_completion_tokens"))) {
        retriedTokens = true;
        tokenField = tokenField === "max_tokens" ? "max_completion_tokens" : "max_tokens";
      } else break;
      first = await post(e, apiKey, openAiBody(r, tokenField, temperature), signal, timeoutMs);
      if (!first.ok) return first;
      reply = first.value;
      latency += reply.latencyMs;
    }
    if (reply.code < 200 || reply.code > 299) return err(statusError(reply, e.host, pathOf(url), r.model, apiKey));
    const d = decodeChat(reply, e.host, apiKey);
    if (!d.ok) return d;
    const v = d.value;
    switch ((v.finishReason ?? "").toLowerCase()) {
      // Gateways pass their own spelling of "ran out of tokens" through, so all of them count.
      case "length":
      case "max_tokens":
      case "max_output_tokens":
        return err(AiErrors.truncated(v.completionTokens, null));
      case "content_filter":
        return err(AiErrors.filtered(e.host));
    }
    if (!v.content?.trim()) return err(AiErrors.emptyReply());
    return ok({ content: v.content, finishReason: v.finishReason, promptTokens: v.promptTokens, completionTokens: v.completionTokens, servedModel: v.model, latencyMs: latency });
  },

  /**
   * A real 16-token completion, because only a completion proves the key, the plan and the model
   * together. Any HTTP 200 in chat format counts, even an empty or cut-off reply.
   */
  async testConnection(e, apiKey, model, signal) {
    if (keyInUrl(e, apiKey)) return err(AiErrors.invalidBaseUrl("has-credentials"));
    const url = chatCompletionsUrl(e.baseUrl);
    let field = tokenFieldFor(e.providerId);
    const req: AiRequest = { model, system: "Reply with the single word OK.", user: "OK?", maxTokens: 16 };
    let x = await post(e, apiKey, openAiBody(req, field, null), signal, TEST_TIMEOUT_MS);
    if (!x.ok) return x;
    if (x.value.code === 400 && /max_(completion_)?tokens/i.test(providerText(x.value.body) ?? "")) {
      field = field === "max_tokens" ? "max_completion_tokens" : "max_tokens";
      x = await post(e, apiKey, openAiBody(req, field, null), signal, TEST_TIMEOUT_MS);
      if (!x.ok) return x;
    }
    const reply = x.value;
    if (reply.code < 200 || reply.code > 299) return err(statusError(reply, e.host, pathOf(url), model, apiKey));
    const d = decodeChat(reply, e.host, apiKey);
    if (!d.ok) return d;
    return ok({ servedModel: d.value.model, latencyMs: reply.latencyMs, reply: d.value.content ?? "" });
  },

  /** GET /models, only when the user taps Browse. Public lists never get a key they don't need. */
  async listModels(e, apiKey, signal) {
    const key = e.modelsPublic ? null : apiKey;
    if (keyInUrl(e, key)) return err(AiErrors.invalidBaseUrl("has-credentials"));
    const url = modelsUrl(e.baseUrl, e.modelsPath ?? "/models");
    const x = await exchange({ url, host: e.host, method: "GET", headers: requestHeaders(e, key, false), signal, timeoutMs: MODELS_TIMEOUT_MS });
    if (!x.ok) return x;
    const reply = x.value;
    if (reply.code < 200 || reply.code > 299) return err(statusError(reply, e.host, pathOf(url), null, apiKey));
    if (isHtml(reply)) return err(AiErrors.notExpectedFormat(e.host));
    return parseModelList(reply.body, e.host);
  },
};

/** data[] or a bare array; one odd entry never hides the list. Exported for tests. */
export function parseModelList(body: string, host: string): AiResult<RemoteModel[]> {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return err(AiErrors.notExpectedFormat(host));
  }
  const entries = Array.isArray(root) ? root : isObj(root) && Array.isArray(root.data) ? root.data : null;
  if (!entries) return err(AiErrors.notExpectedFormat(host));
  const seen = new Set<string>();
  const out: RemoteModel[] = [];
  for (const m of entries) {
    if (!isObj(m) || typeof m.id !== "string" || !m.id.trim() || seen.has(m.id)) continue;
    // Drops models that only speak another API (Claude's /messages on Command Code).
    if (Array.isArray(m.supported_endpoints) && !m.supported_endpoints.some((s) => typeof s === "string" && s.replace(/\/+$/, "").endsWith("chat/completions"))) continue;
    seen.add(m.id);
    const name = typeof m.name === "string" && m.name.trim() ? m.name : typeof m.display_name === "string" && m.display_name.trim() ? m.display_name : m.id;
    const ctx = typeof m.context_length === "number" ? m.context_length : null;
    const pricing = isObj(m.pricing) ? m.pricing : null;
    const pIn = pricing ? Number(pricing.prompt) : NaN;
    const pOut = pricing ? Number(pricing.completion) : NaN;
    // OpenRouter: USD per token; the app shows per million.
    const price = Number.isFinite(pIn) && Number.isFinite(pOut) && pIn >= 0 && pOut >= 0 ? { inputPerM: Math.round(pIn * 1e6 * 1000) / 1000, outputPerM: Math.round(pOut * 1e6 * 1000) / 1000 } : null;
    out.push({ id: m.id, name, contextLength: ctx, price });
  }
  out.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  return ok(out);
}
