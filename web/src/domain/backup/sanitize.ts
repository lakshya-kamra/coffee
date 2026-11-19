/**
 * 1.x Android exports were hand-built strings, so a Double that was NaN or infinite came out as
 * the bare tokens NaN / Infinity / -Infinity, which JSON.parse rejects. This replaces those
 * tokens with null OUTSIDE strings only: a note that says "NaN" stays as typed (web-spec §15.2).
 */

const QUOTE = 34; // "
const BACKSLASH = 92;

function isWordChar(c: number): boolean {
  return (c >= 48 && c <= 57) || (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || c === 95 || c === 46;
}

export function sanitizeNonStandardNumbers(text: string): string {
  // Fast path: most files never contain either word.
  if (!text.includes("NaN") && !text.includes("Infinity")) return text;
  const parts: string[] = [];
  let copyFrom = 0;
  let i = 0;
  const n = text.length;
  while (i < n) {
    const c = text.charCodeAt(i);
    if (c === QUOTE) {
      // Skip the whole string literal, honouring escapes.
      i++;
      while (i < n) {
        const d = text.charCodeAt(i);
        if (d === BACKSLASH) i += 2;
        else if (d === QUOTE) break;
        else i++;
      }
      i++;
      continue;
    }
    let len = 0;
    if (text.startsWith("NaN", i)) len = 3;
    else if (text.startsWith("Infinity", i)) len = 8;
    else if (text.startsWith("-Infinity", i)) len = 9;
    if (len > 0) {
      const before = i > 0 ? text.charCodeAt(i - 1) : 32;
      const after = i + len < n ? text.charCodeAt(i + len) : 32;
      if (!isWordChar(before) && !isWordChar(after)) {
        parts.push(text.slice(copyFrom, i), "null");
        i += len;
        copyFrom = i;
        continue;
      }
    }
    i++;
  }
  if (copyFrom === 0) return text;
  parts.push(text.slice(copyFrom));
  return parts.join("");
}
