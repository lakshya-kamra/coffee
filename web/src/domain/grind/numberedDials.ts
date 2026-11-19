/**
 * A§3.2 rule 3: catalog grinders whose dial prints a number every ten clicks, so a reading like
 * "7.5" means number 7 plus 5 clicks. Filled only from each grinders.json entry's own
 * `dialReading` text ("10 clicks between numbers" / "10 clicks per number"); never add an id on
 * a guess. Lead decision 1 moves this into grinders.json as `setting.dialFormat`, which wins
 * when present; this set covers catalogs written before that field landed.
 *
 * Mirror of Android's domain/grind/NumberedDials.kt. If the Kotlin set changes, change this too.
 */
export const NUMBERED_DIAL_IDS: ReadonlySet<string> = new Set([
  "1zpresso-k-ultra",
  "1zpresso-k-max",
  "1zpresso-k-plus",
  "1zpresso-zp6-special",
  "1zpresso-j-ultra",
  "1zpresso-j-max",
  "1zpresso-x-ultra",
  "1zpresso-x-pro",
  "timemore-chestnut-s3",
]);
