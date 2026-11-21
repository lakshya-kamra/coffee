/**
 * A built-in recipe's own video (shared/recipes.json `video`): the expert's YouTube video that
 * teaches that recipe. The web shows its cover picture, loaded from YouTube's image server
 * (i.ytimg.com, allowed by img-src in vercel.json), and links out to the video. No player is
 * embedded: frame-src stays 'none' and nothing from YouTube runs here.
 */

export interface RecipeVideo {
  /** The 11-character YouTube video id. */
  readonly youtubeId: string;
  /** The video's title as YouTube gives it (oEmbed), used as the picture's alt text. */
  readonly title: string;
  /** The channel that published it (oEmbed author_name). */
  readonly channel: string;
}

/** What a YouTube video id looks like: 11 of A-Z, a-z, 0-9, "_" and "-". */
export const YOUTUBE_ID = /^[A-Za-z0-9_-]{11}$/;

/**
 * Cover sizes YouTube serves for every video: mq is 320x180 (16:9, for small cards and rows),
 * hq is 480x360 (4:3 with the 16:9 frame letterboxed in its middle; shown with object-fit: cover,
 * which crops exactly the black bars).
 */
export type CoverSize = "mq" | "hq";

export const COVER_PIXELS: Readonly<Record<CoverSize, { readonly width: number; readonly height: number }>> = {
  mq: { width: 320, height: 180 },
  hq: { width: 480, height: 360 },
};

export function isYoutubeId(id: string): boolean {
  return YOUTUBE_ID.test(id);
}

/** The cover picture's URL. The id is checked again here, so a bad id can never build a URL. */
export function youtubeCoverUrl(id: string, size: CoverSize): string {
  if (!isYoutubeId(id)) throw new Error(`not a YouTube id: ${id}`);
  return `https://i.ytimg.com/vi/${id}/${size}default.jpg`;
}

/** The video's page on YouTube (opened in a new tab). */
export function youtubeWatchUrl(id: string): string {
  if (!isYoutubeId(id)) throw new Error(`not a YouTube id: ${id}`);
  return `https://www.youtube.com/watch?v=${id}`;
}

/** Lenient read of a recipe's `video`: null unless it has a valid id. A missing title or channel reads as "". */
export function decodeRecipeVideo(raw: unknown): RecipeVideo | null {
  if (raw == null || typeof raw !== "object" || Array.isArray(raw)) return null;
  const o = raw as Record<string, unknown>;
  const id = typeof o.youtubeId === "string" ? o.youtubeId.trim() : "";
  if (!isYoutubeId(id)) return null;
  return Object.freeze({
    youtubeId: id,
    title: typeof o.title === "string" ? o.title.trim() : "",
    channel: typeof o.channel === "string" ? o.channel.trim() : "",
  });
}

/** "Watch James Hoffmann's video"; a channel ending in s takes a bare apostrophe. */
export function watchLabel(v: RecipeVideo): string {
  const who = v.channel.trim();
  if (!who) return "Watch the video";
  return `Watch ${who}${/s$/i.test(who) ? "'" : "'s"} video`;
}
