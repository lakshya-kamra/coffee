/**
 * Port of core/Format.kt. Measurements are pinned to "." decimals and English month names, so
 * "1:16.7" never reads as a different number in a comma-decimal locale.
 */

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"] as const;

/** Kotlin's roundToInt: ties go up (floor(x + 0.5)), same as Math.round for the values we show. */
export function roundToInt(v: number): number {
  return Math.round(v);
}

/** Kotlin Double.toString for the short values we print: 20 → "20.0", 20.5 → "20.5". */
export function kotlinDouble(v: number): string {
  return Number.isInteger(v) ? v.toFixed(1) : String(v);
}

/** "18" not "18.0", but "18.5" stays "18.5". Used everywhere grams are shown. */
export function grams(v: number): string {
  if (Math.abs(v - roundToInt(v)) < 0.05) return String(roundToInt(v));
  return kotlinDouble(roundToInt(v * 10) / 10);
}

export function gramsOrDash(v: number | null | undefined): string {
  return v == null ? "—" : grams(v);
}

function pad2(n: number): string {
  return n < 10 ? "0" + n : String(n);
}

/** "3:30". */
export function clock(totalSec: number): string {
  const s = Math.trunc(totalSec);
  return `${Math.trunc(s / 60)}:${pad2(s % 60)}`;
}

/** One decimal place, half up, always ".". */
export function oneDp(v: number): string {
  const r = Math.round(v * 10) / 10;
  return (Object.is(r, -0) ? 0 : r).toFixed(1);
}

export function ratio(dose: number, water: number): string {
  return dose > 0 ? "1:" + oneDp(water / dose) : "—";
}

/** Days since 1970-01-01 of the LOCAL calendar date (Kotlin LocalDate.toEpochDay()). */
export function epochDayOf(epochMs: number): number {
  const d = new Date(epochMs);
  return Math.round(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86_400_000);
}

export function epochDayToIso(day: number): string {
  const d = new Date(day * 86_400_000);
  return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}`;
}

export function isoToEpochDay(iso: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso.trim());
  if (!m) return null;
  const ms = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return Number.isNaN(ms) ? null : Math.round(ms / 86_400_000);
}

/** "Today", "Yesterday", else "5 Nov". */
export function dayLabel(epochMs: number, nowMs: number = Date.now()): string {
  const day = epochDayOf(epochMs);
  const today = epochDayOf(nowMs);
  if (day === today) return "Today";
  if (day === today - 1) return "Yesterday";
  const d = new Date(epochMs);
  return `${d.getDate()} ${MONTHS[d.getMonth()]}`;
}

/** "08:14", local time, 24-hour. */
export function timeLabel(epochMs: number): string {
  const d = new Date(epochMs);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

export function greeting(hour: number = new Date().getHours()): string {
  if (hour <= 4) return "Still up";
  if (hour <= 11) return "Morning";
  if (hour <= 16) return "Afternoon";
  if (hour <= 21) return "Evening";
  return "Late one";
}

/** "1 day", "3 days", "0 brews": every count that sits next to its noun goes through here. */
export function plural(n: number, one: string, many: string = one + "s"): string {
  return `${n} ${n === 1 ? one : many}`;
}

/**
 * Free text for a one-line row: whole words up to `max` characters, then "…", so a typed grind
 * reads "about 10…" and never "about 10 secon". One long word is still cut where it has to be.
 */
export function clipWords(text: string, max: number): string {
  const t = text.trim();
  if (t.length <= max) return t;
  // One character past the limit, so a word that ends exactly at the limit is kept whole.
  const cut = t.slice(0, max + 1).lastIndexOf(" ");
  const head = cut > 0 ? t.slice(0, cut) : t.slice(0, max);
  return head.replace(/[\s,;:·(–-]+$/, "") + "…";
}

export function daysOffRoastLabel(days: number | null | undefined): string {
  if (days == null) return "—";
  if (days <= 0) return "Roasted today";
  if (days === 1) return "1 day off roast";
  return `${days} days off roast`;
}

/** "1,500": thousands grouped with commas, as Kotlin's "%,d" in Locale.US. */
export function groupedInt(v: number): string {
  const n = roundToInt(v);
  const s = String(Math.abs(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return n < 0 ? "-" + s : s;
}

// ---------------------------------------------------------------- v4

/** "3:30" under an hour; "12 h 5 min" (or "12 h") from an hour up, for cold brew (v4 §4.9). */
export function formatDuration(totalSec: number): string {
  const s = Math.max(0, Math.trunc(totalSec));
  if (s < 3600) return clock(s);
  const h = Math.trunc(s / 3600);
  const m = Math.trunc((s % 3600) / 60);
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

/** "1 part coffee to 16.7 parts water" ("…parts espresso" for espresso); "" without a dose. */
export function ratioPhrase(doseG: number, waterG: number, method: string = ""): string {
  if (doseG <= 0) return "";
  const r = oneDp(waterG / doseG).replace(/\.0$/, "");
  return `1 part coffee to ${r} parts ${method === "espresso" ? "espresso" : "water"}`;
}

/** 6 → "6", 2.5 → "2½": spoons and mugs come in halves. */
export function halves(v: number): string {
  const whole = Math.trunc(v);
  return v - whole >= 0.5 ? (whole === 0 ? "½" : `${whole}½`) : String(whole);
}

// Screen dates and times follow the runtime locale (v4 §4.15). Exports and AI prompts keep the
// pinned dayLabel / timeLabel above.

/** "8:14 PM" in en-US, "20:14" in en-GB. */
export function screenTime(epochMs: number, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { hour: "numeric", minute: "2-digit" }).format(new Date(epochMs));
}

/** "Nov 19" in en-US, "19 Nov" in en-GB. */
export function screenDate(epochMs: number, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { day: "numeric", month: "short" }).format(new Date(epochMs));
}

/** "Today", "Yesterday", else screenDate. */
export function screenDayLabel(epochMs: number, nowMs: number = Date.now(), locale?: string): string {
  const day = epochDayOf(epochMs);
  const today = epochDayOf(nowMs);
  if (day === today) return "Today";
  if (day === today - 1) return "Yesterday";
  return screenDate(epochMs, locale);
}

/** Today's eyebrow: "EVENING · THU, NOV 19" in en-US. */
export function todayEyebrow(nowMs: number = Date.now(), locale?: string): string {
  const d = new Date(nowMs);
  const date = new Intl.DateTimeFormat(locale, { weekday: "short", month: "short", day: "numeric" }).format(d);
  return `${greeting(d.getHours()).toUpperCase()} · ${date.toUpperCase()}`;
}
