/**
 * Port of A§8.1 `ModelCatalog` on top of shared/catalog/providers.json: prices come from the
 * catalog (USD per million tokens, "prices as of pricesCheckedOn"), nothing is hard-coded here.
 */

/** USD per million tokens. */
export interface Price {
  readonly inputPerM: number;
  readonly outputPerM: number;
}

/** $1 per million tokens is exactly one micro-dollar per token. */
export function costMicroUsd(price: Price, promptTokens: number, completionTokens: number): number {
  return Math.round(promptTokens * price.inputPerM + completionTokens * price.outputPerM);
}

/** "0.11¢", "1.4¢" or "$2.50"; always "." decimals. */
export function formatUsd(microUsd: number): string {
  if (microUsd < 10_000) return `${(microUsd / 10_000).toFixed(2)}¢`;
  if (microUsd < 1_000_000) return `${(microUsd / 10_000).toFixed(1)}¢`;
  return `$${(microUsd / 1_000_000).toFixed(2)}`;
}

/** "3,180". */
export function formatTokens(n: number): string {
  return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

/** "$0.15", "$0.435": at least two decimals, so prices line up and never read as "$0.3". */
export function usd(v: number): string {
  const s = String(v);
  const dot = s.indexOf(".");
  if (dot < 0) return `$${v.toFixed(2)}`;
  return s.length - dot - 1 < 2 ? `$${v.toFixed(2)}` : `$${s}`;
}

/** "Claude Haiku 5.5" from the catalog, else the id after the last "/". */
export function fallbackModelLabel(id: string): string {
  const i = id.lastIndexOf("/");
  return i >= 0 ? id.slice(i + 1) : id;
}

/** App heuristic (web-spec §14.5): an attached image costs about this many prompt tokens. */
export const TOKENS_PER_IMAGE = 1100;
