/**
 * Port of domain/ai/AiError.kt for the browser (web-spec §14.3). Every failure carries the exact
 * words the user sees. Messages are built only from the host, the model id and provider text that
 * has already been through `redactSecrets`, so a key can never reach the UI through an error.
 */
import { formatTokens } from "./modelCatalog";

export const COMMANDCODE_HOST = "api.commandcode.ai";

export type InvalidBaseUrlReason = "blank" | "not-https" | "malformed" | "has-credentials" | "has-query" | "looks-like-key";

export interface AiError {
  readonly kind: string;
  readonly title: string;
  readonly message: string;
  readonly retryable: boolean;
  readonly opensSettings: boolean;
  readonly maybeBilled: boolean;
}

export type AiResult<T> = { readonly ok: true; readonly value: T } | { readonly ok: false; readonly error: AiError };

export const ok = <T>(value: T): AiResult<T> => ({ ok: true, value });
export const err = <T = never>(error: AiError): AiResult<T> => ({ ok: false, error });

function make(kind: string, title: string, message: string, o: Partial<Pick<AiError, "retryable" | "opensSettings" | "maybeBilled">> = {}): AiError {
  return Object.freeze({ kind, title, message, retryable: o.retryable ?? true, opensSettings: o.opensSettings ?? false, maybeBilled: o.maybeBilled ?? false });
}

const quoted = (said: string | null, lead: string) => (said ? `${lead}"${said}"` : "");

export const AiErrors = {
  // ------------------------------------------------------------ setup and local errors

  /** [host] is where requests would go and [keyHost] where the saved key belongs ("" when none). */
  notConfigured(p: { missingKey: boolean; missingModel: boolean; host: string; keyHost: string; noProvider?: boolean }): AiError {
    let message: string;
    if (p.noProvider) message = "Choose an AI service and add your key in Gear › App › AI analysis.";
    else if (p.keyHost && p.host && p.keyHost !== p.host) message = `Your key is saved for ${p.keyHost}. Paste a key for ${p.host} in Gear › App › AI analysis.`;
    else if (p.missingModel && !p.missingKey) message = "Pick a model in Gear › App › AI analysis.";
    else if (p.host) message = `Add your API key for ${p.host} in Gear › App › AI analysis.`;
    else message = "Add an API key in Gear › App › AI analysis.";
    return make("not-configured", "Analysis isn't set up", message, { opensSettings: true });
  },

  invalidBaseUrl(reason: InvalidBaseUrlReason): AiError {
    const [title, message] = (() => {
      switch (reason) {
        case "blank": return ["Base URL is empty", "Enter the API address, for example https://api.example.com/v1."];
        // Web wording (web-spec §14.1): the browser itself blocks plain http from an https page.
        case "not-https": return ["Base URL must use https", "Browsers block plain http:// from this site. Use https://, or run the model on this computer (localhost)."];
        case "malformed": return ["That isn't a web address", "It should look like https://api.example.com/v1."];
        case "has-credentials": return ["Take the key out of the address", "Paste the key into the API key field. It never belongs in a URL."];
        case "has-query": return ["Remove the ?… part", "The base URL is just the API root, with no query string or #fragment."];
        case "looks-like-key": return ["That looks like your API key", "Paste it into the API key field. The base URL is the API address, for example https://api.example.com/v1."];
      }
    })();
    return make(`invalid-base-url:${reason}`, title, message, { retryable: false, opensSettings: true });
  },

  notEnoughData(needed: number, have: number): AiError & { readonly needed: number; readonly have: number } {
    return { ...make("not-enough-data", "Not enough brews yet", `Patterns need at least ${needed} rated brews. You have ${have}.`, { retryable: false }), needed, have };
  },

  brewMissing(): AiError {
    return make("brew-missing", "This brew no longer exists", "It was deleted while the analysis was running. Nothing was saved.", { retryable: false });
  },

  /** A connection test is required first for a provider whose browser support is unknown. */
  needsTest(host: string): AiError {
    return make("needs-test", "Test the connection first", `The app can't tell whether ${host} accepts requests from a browser. Run Test connection in Gear › App › AI analysis.`, { retryable: false, opensSettings: true });
  },

  // ------------------------------------------------------------------ network errors

  offline(): AiError {
    return make("offline", "You're offline.", "Check your connection, then try again.");
  },

  /** A TypeError with no response while online: the host is down, or it does not allow this site (CORS). */
  unreachable(host: string, origin: string): AiError {
    return make("unreachable", `Couldn't reach ${host}`, `If this is your own server, it has to allow requests from ${origin} (CORS).`, { opensSettings: true });
  },

  readTimeout(host: string, seconds: number): AiError {
    return make(
      "read-timeout",
      `Still thinking after ${seconds} seconds`,
      `The model at ${host} didn't finish in time, so the app stopped waiting. Reasoning models are sometimes slow. Try again, or pick a faster model. The provider may still bill the tokens it used.`,
      { maybeBilled: true },
    );
  },

  tooLarge(host: string): AiError {
    return make("too-large", "The reply was too large", `${host} sent more than 1 MiB, so the app stopped reading it. Try again, or pick another model.`, { maybeBilled: true });
  },

  // -------------------------------------------------------------- HTTP status errors

  unauthorized(code: number, host: string, model: string, said: string | null): AiError {
    if (host === COMMANDCODE_HOST) {
      return make(
        "unauthorized",
        `Command Code didn't accept the key (HTTP ${code})`,
        "Command Code opens its Provider API, the endpoint this app calls, only to plans that include it; the Go plan gets 403 even with a valid key. Check your plan at commandcode.ai, or pick another provider in Gear › App › AI analysis. If your plan includes it, check the whole key was pasted." +
          quoted(said, "\n\nCommand Code said: "),
        { opensSettings: true },
      );
    }
    return make("unauthorized", `${host} didn't accept the key (HTTP ${code})`, `Check the key is complete and allowed to use ${model}.` + quoted(said, " Server said: "), { opensSettings: true });
  },

  paymentRequired(host: string): AiError {
    return make("payment-required", "Out of credit (HTTP 402)", `${host} says the account has no credit left, or the model needs a higher tier. Top up there, then try again.`);
  },

  modelNotFound(host: string, model: string): AiError {
    return make("model-not-found", "Model not available", `"${model}" isn't offered at ${host}. Pick another model in Gear › App › AI analysis.`, { opensSettings: true });
  },

  endpointNotFound(host: string, path: string): AiError {
    return make("endpoint-not-found", "Nothing at that address (HTTP 404)", `${host}${path} doesn't answer these requests. The base URL should be the API root, usually ending in /v1.`, { opensSettings: true });
  },

  rateLimited(retryAfterSec: number | null): AiError {
    const message = retryAfterSec == null ? "Wait a minute and try again." : retryAfterSec === 1 ? "Wait 1 second and try again." : `Wait ${retryAfterSec} seconds and try again.`;
    return make("rate-limited", "Too many requests (HTTP 429)", message);
  },

  badRequest(code: number, host: string, said: string | null): AiError {
    return make("bad-request", `Request rejected (HTTP ${code})`, `${host} didn't accept the request: ${said ?? "no reason given"}. Try again, or pick another model.`);
  },

  serverError(code: number, host: string): AiError {
    return make("server-error", `${host} had a problem (HTTP ${code})`, "That's on their side. Nothing was saved. Try again in a minute.");
  },

  // ---------------------------------------------------------------------- reply errors

  truncated(completionTokens: number | null, costLabel: string | null): AiError & { readonly completionTokens: number | null; readonly costLabel: string | null } {
    const used = completionTokens != null ? formatTokens(completionTokens) : "all its";
    return {
      ...make(
        "truncated",
        "The answer was cut off",
        `The model used its whole length budget (${used} tokens) before it finished, so there's nothing reliable to show. Try again, or pick another model. Those tokens are billed${costLabel ? ` (${costLabel})` : ""}.`,
        { maybeBilled: true },
      ),
      completionTokens,
      costLabel,
    };
  },

  emptyReply(): AiError {
    return make("empty-reply", "The model returned nothing", "It answered with an empty message. Try again, or pick another model.", { maybeBilled: true });
  },

  filtered(host: string): AiError {
    return make("filtered", "The provider blocked the reply", `${host}'s content filter stopped the answer. Try again, or switch model.`);
  },

  notExpectedFormat(host: string): AiError {
    return make("not-expected-format", `Unexpected reply from ${host}`, "It answered, but not in the format the app expects. Check the base URL: it should be the API root, not a website.", { opensSettings: true });
  },

  /** [kind] is a type name only, never an exception message (which can quote a URL or header). */
  unknown(kind: string): AiError {
    return make("unknown", "Something went wrong", `The request failed (${kind}). Nothing was saved. Try again.`);
  },
};

// ------------------------------------------------------------------------- redaction

const MASK = "•••";
const BEARER = /bearer\s+[^\s"',;]+/gi;
const TOKEN = /(sk|cc|key)[-_][A-Za-z0-9_-]{8,}/g;
const SPACE = /\s+/g;

/** Scrubs anything key-shaped out of provider text (Redact.secrets). */
export function redactSecrets(text: string | null | undefined, key: string | null | undefined, max = 240): string | null {
  if (text == null) return null;
  let out = text;
  // Short strings would mask ordinary words, and no real key is that short.
  if (key && key.length >= 8) out = out.split(key).join(MASK);
  out = out.replace(BEARER, `Bearer ${MASK}`).replace(TOKEN, MASK).replace(SPACE, " ").trim();
  if (max > 0 && out.length > max) out = out.slice(0, max - 1).trimEnd() + "…";
  return out;
}
