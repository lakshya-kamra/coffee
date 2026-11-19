/**
 * Port of A§7.1 `CatalogSearch`: pure, synchronous, no catalog import. Forgiving on purpose:
 * people type "commandante", "mahlkoenig" or "k ultra", and each of those must still land on
 * the right grinder.
 *
 * Score per doc = best over its keys: exact 100; prefix 80; every query token a prefix of a key
 * token 60; substring 40; fuzzy 20 (every query token of 4+ chars within Damerau-Levenshtein 1
 * of some key token, 2 when the token has 8+ chars; shorter tokens must still be prefixes).
 * Ties: popularity ascending (1 = most popular), then title.
 */

export interface SearchDoc {
  readonly id: string;
  readonly title: string;
  readonly subtitle: string;
  /** Header the picker groups under when there is no query. */
  readonly group: string;
  /** Brand, model, "brand model", aliases, country, city… Raw text; normalised here. */
  readonly keys: readonly string[];
  readonly popularity: number;
}

export interface SearchHit {
  readonly doc: SearchDoc;
  readonly score: number;
}

const FOLD: Readonly<Record<string, string>> = { ß: "ss", æ: "ae", ø: "o", œ: "oe", ł: "l", đ: "d", ı: "i" };

/**
 * Lowercase, accents stripped (NFD), a few letters NFD keeps folded, German digraphs collapsed
 * (oe→o, ue→u, ae→a so "Mahlkoenig" meets "Mahlkönig"), and anything not a letter or digit
 * becomes a token break.
 */
export function normaliseText(s: string): string {
  const lower = s.toLowerCase();
  let out = "";
  for (const ch of lower.normalize("NFD")) {
    const code = ch.charCodeAt(0);
    if (code >= 0x300 && code <= 0x36f) continue; // combining marks
    out += FOLD[ch] ?? ch;
  }
  return out.replace(/oe/g, "o").replace(/ue/g, "u").replace(/ae/g, "a").replace(/[^a-z0-9]+/g, " ").trim();
}

export function tokensOf(s: string): string[] {
  const n = normaliseText(s);
  return n ? n.split(" ") : [];
}

/** Optimal string alignment distance (Damerau-Levenshtein with adjacent swaps), capped at `max + 1`. */
export function editDistance(a: string, b: string, max = 2): number {
  if (a === b) return 0;
  if (Math.abs(a.length - b.length) > max) return max + 1;
  const n = b.length;
  let prev2 = new Array<number>(n + 1).fill(0);
  let prev = Array.from({ length: n + 1 }, (_, j) => j);
  let cur = new Array<number>(n + 1).fill(0);
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i;
    let rowMin = i;
    for (let j = 1; j <= n; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      let v = Math.min(prev[j]! + 1, cur[j - 1]! + 1, prev[j - 1]! + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) v = Math.min(v, prev2[j - 2]! + 1);
      cur[j] = v;
      if (v < rowMin) rowMin = v;
    }
    if (rowMin > max) return max + 1;
    [prev2, prev, cur] = [prev, cur, prev2];
  }
  return prev[n]!;
}

interface PreparedKey {
  readonly joined: string;
  readonly tokens: readonly string[];
}

interface PreparedDoc {
  readonly doc: SearchDoc;
  readonly keys: readonly PreparedKey[];
}

const prepared = new WeakMap<readonly SearchDoc[], readonly PreparedDoc[]>();

/** Keys are normalised once per docs array (the picker caches its docs per kind). */
function prepare(items: readonly SearchDoc[]): readonly PreparedDoc[] {
  let p = prepared.get(items);
  if (!p) {
    p = items.map((doc) => ({
      doc,
      keys: doc.keys
        .map((k) => tokensOf(k))
        .filter((t) => t.length > 0)
        .map((tokens) => ({ joined: tokens.join(""), tokens })),
    }));
    prepared.set(items, p);
  }
  return p;
}

function fuzzyTokenMatch(q: string, keyTokens: readonly string[]): boolean {
  if (q.length < 4) return keyTokens.some((t) => t.startsWith(q));
  const max = q.length >= 8 ? 2 : 1;
  return keyTokens.some((t) => editDistance(q, t, max) <= max);
}

function scoreKey(qJoined: string, qTokens: readonly string[], k: PreparedKey): number {
  if (k.joined === qJoined) return 100;
  if (k.joined.startsWith(qJoined)) return 80;
  if (qTokens.every((q) => k.tokens.some((t) => t.startsWith(q)))) return 60;
  if (k.joined.includes(qJoined)) return 40;
  if (qTokens.some((q) => q.length >= 4) && qTokens.every((q) => fuzzyTokenMatch(q, k.tokens))) return 20;
  return 0;
}

function byPopularityThenTitle(a: SearchDoc, b: SearchDoc): number {
  return a.popularity - b.popularity || a.title.localeCompare(b.title);
}

/**
 * Ranked hits. An empty query returns every doc in group order (groups in the order they first
 * appear in `items`), popularity first inside each group.
 */
export function search(items: readonly SearchDoc[], query: string, limit = 60): SearchHit[] {
  const qTokens = tokensOf(query);
  if (qTokens.length === 0) {
    const groupIndex = new Map<string, number>();
    for (const d of items) if (!groupIndex.has(d.group)) groupIndex.set(d.group, groupIndex.size);
    return [...items]
      .sort((a, b) => groupIndex.get(a.group)! - groupIndex.get(b.group)! || byPopularityThenTitle(a, b))
      .map((doc) => ({ doc, score: 0 }));
  }
  const qJoined = qTokens.join("");
  const hits: SearchHit[] = [];
  for (const p of prepare(items)) {
    let best = 0;
    for (const k of p.keys) {
      const s = scoreKey(qJoined, qTokens, k);
      if (s > best) best = s;
      if (best === 100) break;
    }
    if (best > 0) hits.push({ doc: p.doc, score: best });
  }
  hits.sort((a, b) => b.score - a.score || byPopularityThenTitle(a.doc, b.doc));
  return hits.slice(0, limit);
}
