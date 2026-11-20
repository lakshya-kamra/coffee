/**
 * The grind in a word or two for a recipe card cell ("Medium-fine", "~800 µm", "31 clicks"). The
 * catalog's grind is a sentence ("Coarse, a step coarser than your usual paper-filter setting"),
 * and a 90 px cell cut it to "Coarse, a …", which says nothing. The full sentence stays on the
 * recipe detail and the brew setup.
 */

const SIZE = String.raw`(?:very |extra )?(?:fine|medium|coarse)(?:[- ](?:fine|medium|coarse))?`;
const SIZE_ONLY = new RegExp(String.raw`^(?:espresso|filter grind)$`, "i");
const SIZE_ANY = new RegExp(String.raw`\b(${SIZE})\b`, "i");

function cap(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function grindWord(grind: string): string {
  const text = grind.trim();
  if (!text) return "";
  // First clause: up to a comma, semicolon, bracket, or a full stop that ends a sentence (not 7.0).
  const clause = text.split(/[,;(]|\.\s|\.$/)[0]!.trim();
  // "Fine filter grind" reads as "Fine filter"; a bare "Filter grind" keeps its noun.
  if (clause.length <= 18 && !/\d/.test(clause) && (SIZE_ONLY.test(clause) || SIZE_ANY.test(clause))) return cap(SIZE_ANY.test(clause) ? clause.replace(/ grind$/i, "") : clause);
  if (/finer end of medium/i.test(text)) return "Medium-fine";
  if (/^finer than (?:your|a|the) pour-?over/i.test(text)) return "Medium-fine";
  const scale = /rates the grind (\d+(?:\.\d+)?) on a 1-10/i.exec(text);
  if (scale) return `${scale[1]} of 10`;
  const clicks = /(\d+) clicks/i.exec(text);
  if (clicks) return `${clicks[1]} clicks`;
  const microns = /(\d{3,4}) microns?/i.exec(text);
  if (microns) return `~${microns[1]} µm`;
  const any = SIZE_ANY.exec(text);
  if (any) return cap(any[1]!.toLowerCase());
  if (/^not\b/i.test(text)) return "Not given";
  return clause.length <= 18 ? cap(clause) : "See recipe";
}
