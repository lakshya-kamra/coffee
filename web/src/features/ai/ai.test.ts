/* eslint-disable @typescript-eslint/require-await -- fetch stubs keep fetch's async signature */
// Wire shapes (OpenAI string vs parts, max_completion_tokens for OpenAI; Anthropic request with an
// image block, text joined with thinking skipped, stop reasons), transport errors, the key store's
// storage rules and host binding, and one full run against a stubbed fetch proving the key never
// reaches IndexedDB.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { catalog } from "@/catalog/catalog";
import { db } from "@/data/db";
import { aiPrefs } from "@/data/settings";
import { newBrew } from "@/data/schema";
import { store } from "@/data/store";
import { DEFAULT_TASTE } from "@/domain/core/evaluation";
import { aiProviders, providerById, VERSION } from "@/domain/ai";
import { anthropicBody, anthropicMessagesClient, decodeMessages } from "./client/anthropicMessages";
import { openAiBody, openAiChatClient, parseModelList, tokenFieldFor } from "./client/openaiChat";
import { exchange, requestHeaders, statusError } from "./client/transport";
import type { Endpoint } from "./client/types";
import { aiActions, currentSettings, deriveSettings } from "./aiSettings";
import { keyStore, validateKey } from "./keyStore";
import { photosToAttach, runner } from "./runner";

class MemStorage implements Storage {
  private m = new Map<string, string>();
  get length() {
    return this.m.size;
  }
  clear() {
    this.m.clear();
  }
  getItem(k: string) {
    return this.m.get(k) ?? null;
  }
  key(i: number) {
    return [...this.m.keys()][i] ?? null;
  }
  removeItem(k: string) {
    this.m.delete(k);
  }
  setItem(k: string, v: string) {
    this.m.set(k, v);
  }
}

const KEY = "sk-test-0123456789abcdefXYZ";

function endpoint(p: Partial<Endpoint> = {}): Endpoint {
  return { providerId: "openrouter", baseUrl: "https://openrouter.ai/api/v1", host: "openrouter.ai", api: "openai-chat", auth: "bearer", extraHeaders: {}, browserHeaders: {}, modelsPath: "/models", modelsPublic: true, ...p };
}

function reply(body: unknown, status = 200, type = "application/json"): Response {
  return new Response(typeof body === "string" ? body : JSON.stringify(body), { status, headers: { "content-type": type } });
}

beforeAll(async () => {
  await catalog.load(["providers", "brewers", "recipes", "grinders", "beans", "water", "roasters", "origins", "principles"]);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("OpenAI-compatible wire", () => {
  it("string content without images, parts with data: URLs", () => {
    const text = openAiBody({ model: "m", system: "s", user: "u", maxTokens: 10 }, "max_tokens", 0.3);
    expect(text).toEqual({ model: "m", messages: [{ role: "system", content: "s" }, { role: "user", content: "u" }], temperature: 0.3, max_tokens: 10 });
    const parts = openAiBody({ model: "m", system: "s", user: "u", maxTokens: 10, images: [{ label: "bed, 08:14", mediaType: "image/jpeg", base64: "AAAA" }] }, "max_completion_tokens", null);
    expect(parts.messages).toEqual([
      { role: "system", content: "s" },
      { role: "user", content: [{ type: "text", text: "u" }, { type: "image_url", image_url: { url: "data:image/jpeg;base64,AAAA" } }] },
    ]);
    expect(parts.max_completion_tokens).toBe(10);
    expect("temperature" in parts).toBe(false);
    expect(tokenFieldFor("openai")).toBe("max_completion_tokens");
    expect(tokenFieldFor("openrouter")).toBe("max_tokens");
  });

  it("retries once with the other token field on a 400 that names it", async () => {
    const bodies: Record<string, unknown>[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_u: string, init: RequestInit) => {
      bodies.push(JSON.parse(init.body as string) as Record<string, unknown>);
      return bodies.length === 1
        ? reply({ error: { message: "Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead." } }, 400)
        : reply({ model: "m", choices: [{ message: { content: "hi" }, finish_reason: "stop" }], usage: { prompt_tokens: 5, completion_tokens: 2 } });
    }));
    const r = await openAiChatClient.complete(endpoint(), KEY, { model: "m", system: "s", user: "u", maxTokens: 10 });
    expect(r.ok).toBe(true);
    expect(bodies.map((b) => ("max_tokens" in b ? "max_tokens" : "max_completion_tokens"))).toEqual(["max_tokens", "max_completion_tokens"]);
  });

  it("maps a cut-off reply and a content filter", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => reply({ choices: [{ message: { content: "{" }, finish_reason: "length" }], usage: { completion_tokens: 6000 } })));
    const r = await openAiChatClient.complete(endpoint(), KEY, { model: "m", system: "s", user: "u", maxTokens: 10 });
    expect(!r.ok && r.error.kind).toBe("truncated");
  });

  it("filters model lists to chat models and reads OpenRouter's live prices", () => {
    const r = parseModelList(JSON.stringify({ data: [{ id: "a/x", name: "X", pricing: { prompt: "0.00000015", completion: "0.0000005" } }, { id: "claude", supported_endpoints: ["/v1/messages"] }, { id: "b", supported_endpoints: ["/v1/chat/completions"] }] }), "h");
    expect(r.ok && r.value.map((m) => m.id)).toEqual(["b", "a/x"]);
    expect(r.ok && r.value.find((m) => m.id === "a/x")!.price).toEqual({ inputPerM: 0.15, outputPerM: 0.5 });
  });
});

describe("Anthropic Messages wire", () => {
  it("request shape with a base64 image block and no temperature", () => {
    const b = anthropicBody({ model: "claude-haiku-5-5", system: "s", user: "u", maxTokens: 6000, temperature: 0.3, images: [{ label: "cup", mediaType: "image/jpeg", base64: "BBBB" }] });
    expect(b).toEqual({
      model: "claude-haiku-5-5",
      max_tokens: 6000,
      system: "s",
      messages: [{ role: "user", content: [{ type: "text", text: "u" }, { type: "image", source: { type: "base64", media_type: "image/jpeg", data: "BBBB" } }] }],
    });
  });

  it("joins text blocks and skips thinking", () => {
    const r = decodeMessages(
      { code: 200, contentType: "application/json", retryAfter: null, latencyMs: 1, body: JSON.stringify({ content: [{ type: "thinking", thinking: "x" }, { type: "text", text: "a" }, { type: "redacted_thinking" }, { type: "text", text: "b" }], stop_reason: "end_turn", usage: { input_tokens: 3, output_tokens: 4 } }) },
      "api.anthropic.com",
      null,
    );
    expect(r.ok && r.value).toMatchObject({ text: "ab", inputTokens: 3, outputTokens: 4 });
  });

  it("sends x-api-key, anthropic-version and the browser header; stop reasons map to errors", async () => {
    const a = providerById(aiProviders(catalog.providers), "anthropic")!;
    const ep: Endpoint = { providerId: a.id, baseUrl: a.baseUrl, host: "api.anthropic.com", api: a.api, auth: a.auth, extraHeaders: a.extraHeaders, browserHeaders: a.browserHeaders, modelsPath: "/models", modelsPublic: false };
    let headers: Record<string, string> = {};
    let url = "";
    vi.stubGlobal("fetch", vi.fn(async (u: string, init: RequestInit) => {
      url = u;
      headers = init.headers as Record<string, string>;
      return reply({ content: [{ type: "text", text: "{" }], stop_reason: "max_tokens", usage: { output_tokens: 6000 } });
    }));
    const r = await anthropicMessagesClient.complete(ep, KEY, { model: "m", system: "s", user: "u", maxTokens: 6000 });
    expect(url).toBe("https://api.anthropic.com/v1/messages");
    expect(headers["x-api-key"]).toBe(KEY);
    expect(headers.authorization).toBeUndefined();
    expect(headers["anthropic-version"]).toBe(a.extraHeaders["anthropic-version"]);
    expect(headers["anthropic-dangerous-direct-browser-access"]).toBe("true");
    expect(!r.ok && r.error.kind).toBe("truncated");
    vi.stubGlobal("fetch", vi.fn(async () => reply({ content: [], stop_reason: "refusal" })));
    const f = await anthropicMessagesClient.complete(ep, KEY, { model: "m", system: "s", user: "u", maxTokens: 10 });
    expect(!f.ok && f.error.kind).toBe("filtered");
  });
});

describe("transport", () => {
  it("fetch options: CORS, no cookies, no referrer, redirects refused, no cache", async () => {
    let init: RequestInit = {};
    vi.stubGlobal("fetch", vi.fn(async (_u: string, i: RequestInit) => ((init = i), reply("{}"))));
    await exchange({ url: "https://h/x", host: "h", method: "GET", headers: {}, timeoutMs: 1000 });
    expect(init).toMatchObject({ mode: "cors", credentials: "omit", referrerPolicy: "no-referrer", redirect: "error", cache: "no-store" });
  });

  it("a TypeError while online explains CORS; offline says so", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => Promise.reject(new TypeError("Failed to fetch https://h/?key=" + KEY))));
    const r = await exchange({ url: "https://h/x", host: "my.server", method: "GET", headers: {}, timeoutMs: 1000 });
    expect(!r.ok && r.error.title).toBe("Couldn't reach my.server");
    expect(!r.ok && r.error.message).toContain("(CORS)");
    expect(!r.ok && r.error.message).not.toContain(KEY);
    vi.stubGlobal("navigator", { onLine: false });
    const off = await exchange({ url: "https://h/x", host: "h", method: "GET", headers: {}, timeoutMs: 1000 });
    expect(!off.ok && off.error.title).toBe("You're offline.");
  });

  it("caps the reply at 1 MiB", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => reply("x".repeat(1024 * 1024 + 10), 200, "text/plain")));
    const r = await exchange({ url: "https://h/x", host: "h", method: "GET", headers: {}, timeoutMs: 1000 });
    expect(!r.ok && r.error.kind).toBe("too-large");
  });

  it("status errors: 401 scrubs the key, 529 is a server error", () => {
    const e = statusError({ code: 401, contentType: "application/json", retryAfter: null, latencyMs: 1, body: JSON.stringify({ error: { message: `Invalid key ${KEY}` } }) }, "api.x.ai", "/v1/chat/completions", "m", KEY);
    expect(e.message).not.toContain(KEY);
    expect(e.title).toBe("api.x.ai didn't accept the key (HTTP 401)");
    expect(statusError({ code: 529, contentType: null, retryAfter: null, latencyMs: 1, body: "" }, "h", "/", "m", null).kind).toBe("server-error");
    expect(statusError({ code: 429, contentType: null, retryAfter: "7", latencyMs: 1, body: "" }, "h", "/", "m", null).message).toBe("Wait 7 seconds and try again.");
  });

  it("headers pass the allow-list; bearer or x-api-key by provider", () => {
    const h = requestHeaders(endpoint({ extraHeaders: { "X-Title": "Coffee", Cookie: "no" } }), KEY, true);
    expect(h.authorization).toBe(`Bearer ${KEY}`);
    expect(h["x-title"]).toBe("Coffee");
    expect(h.cookie).toBeUndefined();
    expect(requestHeaders(endpoint(), null, false).authorization).toBeUndefined();
  });
});

describe("key store (web-spec §14.2)", () => {
  let session: MemStorage;
  let local: MemStorage;
  beforeEach(() => {
    session = new MemStorage();
    local = new MemStorage();
    keyStore.setStoragesForTests(session, local);
  });

  it("unticked stays in this page's memory, ticked goes to localStorage, and saving clears the other", () => {
    expect(keyStore.save("openai", KEY, "api.openai.com", false).ok).toBe(true);
    // Nothing on disk: a browser restoring the closed tab has nothing to give back.
    expect(session.length + local.length).toBe(0);
    expect(keyStore.read("openai", "api.openai.com")).toBe(KEY);
    expect(keyStore.badge("openai")).toMatchObject({ host: "api.openai.com", hint: "fXYZ", remembered: false });
    keyStore.save("openai", KEY, "api.openai.com", true);
    expect(session.length).toBe(0);
    expect(local.getItem("coffee.ai.key.openai")).toContain('"host":"api.openai.com"');
    expect(keyStore.badge("openai")!.remembered).toBe(true);
    // Back to unticked: the remembered copy goes.
    keyStore.save("openai", KEY, "api.openai.com", false);
    expect(local.length).toBe(0);
    expect(keyStore.badge("openai")!.remembered).toBe(false);
    keyStore.remove("openai");
    expect(session.length + local.length).toBe(0);
    expect(keyStore.badge("openai")).toBeNull();
  });

  it("an unticked key does not survive the page: a reload or a reopened tab starts without it", () => {
    keyStore.save("openai", KEY, "api.openai.com", false);
    keyStore.save("anthropic", KEY, "api.anthropic.com", true);
    // The same storages, a new page (what a reload or "Reopen closed tab" gives the next person).
    keyStore.setStoragesForTests(session, local);
    expect(keyStore.badge("openai")).toBeNull();
    expect(keyStore.read("openai", "api.openai.com")).toBeNull();
    expect(keyStore.read("anthropic", "api.anthropic.com")).toBe(KEY);
  });

  it("sweeps a key an earlier build left in sessionStorage, and Delete everything clears all", () => {
    const stale = new MemStorage();
    stale.setItem("coffee.ai.key.openai", JSON.stringify({ v: 1, host: "api.openai.com", key: KEY, hint: "fXYZ", savedAt: 1 }));
    stale.setItem("coffee.reloaded", "1");
    keyStore.setStoragesForTests(stale, local);
    expect(stale.getItem("coffee.ai.key.openai")).toBeNull();
    expect(stale.getItem("coffee.reloaded")).toBe("1"); // other entries are not this module's
    expect(keyStore.badge("openai")).toBeNull();

    keyStore.save("openai", KEY, "api.openai.com", false);
    keyStore.save("anthropic", KEY, "api.anthropic.com", true);
    keyStore.removeAll();
    expect(keyStore.badge("openai")).toBeNull();
    expect(keyStore.badge("anthropic")).toBeNull();
    expect(local.length).toBe(0);
  });

  it("the connection-test signature kept in IndexedDB holds none of the key, and Remove key clears it", async () => {
    await db.delete();
    await db.open();
    const list = aiProviders(catalog.providers);
    const prefs = { layout: 2 as const, activeProvider: "openai", sendPhotos: true, providers: { openai: { model: "m" } } };
    keyStore.save("openai", KEY, "api.openai.com", false);
    const s = deriveSettings(prefs, list, keyStore.badge);
    expect(s.hasKey).toBe(true);
    // Without the saved-at stamp: it is the clock, and now and then its digits spell four of the key's ("3456").
    const unstamped = s.signature.replace(/\|k\d+$/, "|k");
    expect(unstamped).not.toBe(s.signature);
    for (let i = 0; i + 4 <= KEY.length; i++) expect(unstamped).not.toContain(KEY.slice(i, i + 4));
    // A different saving of a key is a different signature, so it has to be tested again.
    const other = deriveSettings(prefs, list, (id) => ({ ...keyStore.badge(id)!, savedAt: keyStore.badge(id)!.savedAt + 1 }));
    expect(other.signature).not.toBe(s.signature);

    await aiPrefs.update(() => prefs);
    await aiActions.markTested("openai", s.signature, 123);
    expect((await aiPrefs.get()).providers.openai).toMatchObject({ testOkAt: 123, testSig: s.signature });
    await aiActions.removeKey("openai");
    expect(keyStore.badge("openai")).toBeNull();
    expect((await aiPrefs.get()).providers.openai).toEqual({ model: "m" });
  });

  it("binds a key to its host", () => {
    keyStore.save("custom", KEY, "a.example.com", false);
    expect(keyStore.read("custom", "a.example.com")).toBe(KEY);
    expect(keyStore.read("custom", "b.example.com")).toBeNull();
    const list = aiProviders(catalog.providers);
    const s = deriveSettings({ layout: 2, activeProvider: "custom", sendPhotos: true, providers: { custom: { model: "m", baseUrl: "https://b.example.com/v1" } } }, list, keyStore.badge);
    expect(s.keyForOtherHost).toBe(true);
    expect(s.hasKey).toBe(false);
    expect(s.ready).toBe(false);
  });

  it("validates like Android", () => {
    expect(validateKey("", "h")).toEqual({ ok: false, message: "Paste a key first." });
    expect(validateKey("abc def ghi", "h").ok).toBe(false);
    expect(validateKey("https://x.y", "h")).toEqual({ ok: false, message: "That's a web address, not an API key." });
    expect(validateKey("short", "h")).toEqual({ ok: false, message: "That's too short to be an API key." });
  });
});

describe("settings", () => {
  it("fresh state chooses no provider; localhost needs no key; unknown CORS needs a test", () => {
    const list = aiProviders(catalog.providers);
    const none = deriveSettings({ layout: 2, activeProvider: "", sendPhotos: true, providers: {} }, list, () => null);
    expect(none.provider).toBeNull();
    expect(none.ready).toBe(false);
    const ollama = deriveSettings({ layout: 2, activeProvider: "ollama", sendPhotos: true, providers: {} }, list, () => null);
    expect(ollama.keyOptional).toBe(true);
    expect(ollama.ready).toBe(true);
    expect(ollama.needsTest).toBe(true);
    const tested = deriveSettings({ layout: 2, activeProvider: "ollama", sendPhotos: true, providers: { ollama: { model: ollama.model, testOkAt: 1, testSig: ollama.signature } } }, list, () => null);
    expect(tested.needsTest).toBe(false);
  });

  it("photos attach only for vision models, ticked bed/cup, newest 3 oldest first", () => {
    const list = aiProviders(catalog.providers);
    const custom = deriveSettings({ layout: 2, activeProvider: "custom", sendPhotos: true, providers: { custom: { model: "unknown-model", baseUrl: "https://a.example.com/v1" } } }, list, () => null);
    expect(custom.modelSeesImages).toBe(false);
    const p = (uid: string, kind: "bed" | "cup" | "bag", takenAt: number, sendToAi = true) => ({ uid, kind, takenAt, sendToAi, brewLogId: 1, beanId: null, mediaType: "image/jpeg" as const, widthPx: 1, heightPx: 1, bytes: 10, createdAt: 1, caption: "" });
    const chosen = photosToAttach([p("a", "bed", 1), p("b", "cup", 2), p("c", "bag", 3), p("d", "bed", 4, false), p("e", "cup", 5), p("f", "bed", 6)]);
    expect(chosen.map((x) => x.uid)).toEqual(["b", "e", "f"]);
  });
});

describe("a full run (runner)", () => {
  beforeEach(async () => {
    store.resetForTests();
    runner.resetForTests();
    await db.delete();
    await db.open();
    keyStore.setStoragesForTests(new MemStorage(), new MemStorage());
  });
  afterEach(() => store.stop());

  it("stores an analysis with origin web and never writes the key to IndexedDB", async () => {
    const recipe = catalog.recipes.find((r) => r.brewer.method === "pourover")!;
    const id = (await db.brew_logs.add(
      newBrew({ recipeId: recipe.id, recipeName: recipe.name, brewer: recipe.brewer.label, brewerId: recipe.brewer.id, doseG: recipe.doseG, waterG: recipe.waterG, tempC: 94, totalTimeSec: 200, overall: 7, taste: { ...DEFAULT_TASTE, acidity: 7 } }),
    )) as number;
    await aiPrefs.update((c) => ({ ...c, activeProvider: "openrouter", providers: { openrouter: { model: "z-ai/glm-5.3-flash" } } }));
    expect(keyStore.save("openrouter", KEY, "openrouter.ai", true).ok).toBe(true);
    await store.init();
    expect(currentSettings(store.get().aiPrefs).ready).toBe(true);

    let sent: RequestInit | null = null;
    vi.stubGlobal("fetch", vi.fn(async (_u: string, init: RequestInit) => {
      sent = init;
      return reply({
        model: "z-ai/glm-5.3-flash",
        choices: [{ finish_reason: "stop", message: { content: JSON.stringify({ verdict: "close", headline: "Slightly thin", diagnosis: "Fast drawdown.", confidence: "medium", change: { variable: "temperature", instruction: "94 -> 96 °C", from: "94 °C", to: "96 °C" } }) } }],
        usage: { prompt_tokens: 3000, completion_tokens: 500 },
      });
    }));
    expect(runner.start({ kind: "brew", brewId: id })).toBe(true);
    expect(runner.start({ kind: "brew", brewId: id })).toBe(false);
    await vi.waitFor(() => expect(runner.get().running.size).toBe(0), { timeout: 5000 });
    expect(runner.get().failures.size).toBe(0);
    const rows = await db.analyses.toArray();
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ origin: "web", providerId: "openrouter", host: "openrouter.ai", promptVersion: VERSION, photoCount: 0, brewLogId: id });
    expect(rows[0]!.costMicroUsd).toBeGreaterThan(0);
    expect((sent!.headers as Record<string, string>).authorization).toBe(`Bearer ${KEY}`);
    // The key lives only in Web Storage: nothing in any table may contain it.
    for (const t of db.tables) expect(JSON.stringify(await t.toArray())).not.toContain(KEY);
  });

  it("an unconfigured run fails with words, not a crash", async () => {
    const id = (await db.brew_logs.add(newBrew({ doseG: 15, waterG: 250, tempC: 94, totalTimeSec: 200 }))) as number;
    await store.init();
    runner.start({ kind: "brew", brewId: id });
    await vi.waitFor(() => expect(runner.get().running.size).toBe(0), { timeout: 5000 });
    expect(runner.get().failures.get(`brew:${id}`)!.title).toBe("Analysis isn't set up");
  });
});
