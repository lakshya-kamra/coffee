/**
 * Port of domain/ai/BaseUrl.kt with the web rules (web-spec §14.1): `https://` any host, or
 * `http://localhost[:port]` / `http://127.0.0.1[:port]` for a model on this computer. Only a
 * valid result is ever stored, and every request URL is built from it, so these rules are the one
 * gate between the key and the network.
 */
import { AiErrors, type AiError, type InvalidBaseUrlReason } from "./aiError";

export type BaseUrlCheck = { readonly ok: true; readonly normalized: string; readonly host: string } | { readonly ok: false; readonly error: AiError };

const PASTED_SUFFIXES = ["/chat/completions", "/messages", "/models"];
// Same shapes redactSecrets scrubs, anchored to a word start so hosts like "disk-cache" pass.
const KEY_TOKEN = /(?<![A-Za-z0-9])(sk|cc|key)[-_][A-Za-z0-9_-]{8,}/;
const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1"]);

const invalid = (reason: InvalidBaseUrlReason): BaseUrlCheck => ({ ok: false, error: AiErrors.invalidBaseUrl(reason) });

function stripSuffix(path: string): string {
  let p = path.replace(/\/+$/, "");
  const suffix = PASTED_SUFFIXES.find((s) => p.toLowerCase().endsWith(s));
  if (suffix) p = p.slice(0, -suffix.length);
  return p.replace(/\/+$/, "");
}

export function isLocalHost(host: string): boolean {
  return LOCAL_HOSTS.has(host.toLowerCase());
}

export function checkBaseUrl(raw: string): BaseUrlCheck {
  const trimmed = raw.trim();
  if (!trimmed) return invalid("blank");
  // A bare key has no dot and no slash. Catch it before it is ever treated as a host name.
  if (looksLikeBareKey(trimmed)) return invalid("looks-like-key");

  const withScheme = trimmed.includes("://") ? trimmed : `https://${trimmed}`;
  let url: URL;
  try {
    url = new URL(withScheme);
  } catch {
    return invalid("malformed");
  }
  const host = url.hostname.toLowerCase();
  if (!host) return invalid("malformed");
  const scheme = url.protocol.toLowerCase();
  if (scheme === "http:") {
    if (!isLocalHost(host)) return invalid("not-https");
  } else if (scheme !== "https:") {
    return invalid("not-https");
  }
  if (url.username || url.password) return invalid("has-credentials");
  // URL drops an empty "?" or "#", so look at what was typed.
  if (url.search || url.hash || /[?#]/.test(withScheme.slice(withScheme.indexOf("://") + 3))) return invalid("has-query");
  const rawPath = url.pathname;
  if (hostLooksLikeKey(host) || KEY_TOKEN.test(rawPath) || tailLooksLikeKey(rawPath)) return invalid("looks-like-key");
  // Every real API host has a dot; a dotless one is a typo or a pasted token.
  if (!host.includes(".") && !isLocalHost(host)) return invalid("malformed");

  const port = url.port ? `:${url.port}` : "";
  return { ok: true, normalized: `${scheme}//${host}${port}${stripSuffix(rawPath)}`, host };
}

export const chatCompletionsUrl = (normalized: string) => `${normalized}/chat/completions`;
export const messagesUrl = (normalized: string) => `${normalized}/messages`;
export const modelsUrl = (normalized: string, path = "/models") => `${normalized}${path.startsWith("/") ? path : `/${path}`}`;

export function hostOf(normalized: string): string {
  try {
    return new URL(normalized).hostname.toLowerCase();
  } catch {
    return "";
  }
}

function looksLikeBareKey(text: string): boolean {
  return !text.includes(".") && !text.includes("/") && !/^localhost(:\d+)?$/i.test(text) && (text.length >= 16 || KEY_TOKEN.test(text));
}

/**
 * An unprefixed key stuck on the end of a real address: only the tail is checked, because
 * gateways carry long ids mid-path (a Cloudflare account id, then the gateway name).
 */
function tailLooksLikeKey(rawPath: string): boolean {
  const path = stripSuffix(rawPath);
  const tail = path.slice(path.lastIndexOf("/") + 1);
  const noVowels = tail.length >= 20 && !tail.includes("-") && !/[aeiou]/i.test(tail);
  return looksRandom(tail) || noVowels;
}

function hostLooksLikeKey(host: string): boolean {
  return host.split(".").some((label) => (KEY_TOKEN.test(label) && /\d/.test(label)) || looksRandom(label));
}

/** A label of 20+ characters that keeps switching between upper case, lower case and digits. */
function looksRandom(label: string): boolean {
  const chars = label.replace(/-/g, "");
  if (chars.length < 20 || !/\d/.test(chars)) return false;
  let switches = 0;
  for (let i = 1; i < chars.length; i++) if (charClass(chars[i]!) !== charClass(chars[i - 1]!)) switches++;
  return switches >= 6;
}

function charClass(c: string): number {
  if (c >= "0" && c <= "9") return 0;
  if (c !== c.toLowerCase()) return 1;
  return 2;
}
