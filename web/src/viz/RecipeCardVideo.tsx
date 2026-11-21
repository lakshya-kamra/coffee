/**
 * A recipe's video cover (shared/recipes.json `video`): the picture YouTube serves for the
 * expert's own video of the recipe. On the detail it is a link card that opens the video on
 * YouTube in a new tab; on cards it is a small picture that says "there is a video" (the card
 * itself opens the detail, so the picture there is decoration and its alt is empty).
 *
 * Pictures come from i.ytimg.com (img-src in vercel.json) and are never cached by the service
 * worker. Every box has its size before the picture arrives (aspect-ratio 16/9 plus the
 * picture's own width and height), so nothing shifts when it loads, and a picture that fails
 * (blocked, gone) leaves the box with its play mark instead of a broken image. Offline, no
 * picture is asked for at all (a failed request would only log an error); they come back with
 * the network.
 *
 * Nothing here is part of the shared-element flight: the card picture sits in the card body,
 * and the detail's card mounts with the rest of the body after the header lands.
 */
import { memo, useState, useSyncExternalStore } from "react";
import { catalog } from "@/catalog/catalog";
import type { Recipe } from "@/domain/core/recipe";
import { COVER_PIXELS, watchLabel, youtubeCoverUrl, youtubeWatchUrl, type CoverSize, type RecipeVideo } from "@/domain/core/recipeVideo";
import c from "./recipeCardVideo.module.css";

/**
 * The video for a recipe: its own, else (an edited built-in, saved without one) the built-in's
 * it was edited from. A recipe of your own has none.
 */
export function videoOf(recipe: Recipe): RecipeVideo | null {
  return recipe.video ?? (recipe.isBuiltIn ? null : (catalog.recipe(recipe.id)?.video ?? null));
}

function subscribeOnline(onChange: () => void): () => void {
  window.addEventListener("online", onChange);
  window.addEventListener("offline", onChange);
  return () => {
    window.removeEventListener("online", onChange);
    window.removeEventListener("offline", onChange);
  };
}
const isOnline = () => navigator.onLine !== false;
const onServer = () => true;

/** A play mark: a dark disc with a white triangle, readable on any picture in either theme. */
function PlayMark({ size }: { size: number }) {
  return (
    <span className={c.play} style={{ width: size, height: size }} aria-hidden="true">
      <svg width={size * 0.42} height={size * 0.42} viewBox="0 0 24 24" focusable="false">
        <path d="M8 5.6v12.8a1 1 0 0 0 1.52.85l10.2-6.4a1 1 0 0 0 0-1.7L9.52 4.75A1 1 0 0 0 8 5.6Z" fill="currentColor" />
      </svg>
    </span>
  );
}

/** The picture itself, in a 16:9 box that holds its size before and after it loads. */
function Cover({
  video,
  size,
  alt,
  playSize,
  className,
  onFailed,
}: {
  video: RecipeVideo;
  size: CoverSize;
  alt: string;
  playSize: number;
  className?: string;
  onFailed?: () => void;
}) {
  const [failed, setFailed] = useState(false);
  const online = useSyncExternalStore(subscribeOnline, isOnline, onServer);
  const px = COVER_PIXELS[size];
  return (
    <span className={className ? `${c.frame} ${className}` : c.frame}>
      {online && !failed && (
        <img
          className={c.img}
          src={youtubeCoverUrl(video.youtubeId, size)}
          alt={alt}
          width={px.width}
          height={px.height}
          loading="lazy"
          decoding="async"
          draggable={false}
          // No referrer leaves with it (the site's Referrer-Policy says the same).
          referrerPolicy="no-referrer"
          onError={() => {
            setFailed(true);
            onFailed?.();
          }}
        />
      )}
      {playSize > 0 && <PlayMark size={playSize} />}
    </span>
  );
}

/**
 * The detail's card: the cover, a play mark, "Watch James Hoffmann's video" and the title. Opens
 * YouTube in a new tab. Narrow, the picture spans the card; from 520 wide it sits at the left.
 */
export const RecipeVideoCard = memo(function RecipeVideoCard({ video, className }: { video: RecipeVideo; className?: string }) {
  const label = watchLabel(video);
  const online = useSyncExternalStore(subscribeOnline, isOnline, onServer);
  const [failed, setFailed] = useState(false);
  // The title is read once: as the picture's alt while there is a picture, else from the caption.
  const pictured = online && !failed;
  return (
    <a
      className={className ? `${c.hero} ${className}` : c.hero}
      href={youtubeWatchUrl(video.youtubeId)}
      target="_blank"
      rel="noopener noreferrer"
    >
      <span className={c.heroInner}>
        <Cover video={video} size="hq" alt={video.title} playSize={56} className={c.heroFrame} onFailed={() => setFailed(true)} />
        <span className={c.caption}>
          <span className={c.captionText}>
            <span className="t-title-s">{label}</span>
            {video.title && (
              <span className={`t-body-s ink-mid ${c.title}`} aria-hidden={pictured || undefined}>
                {video.title}
              </span>
            )}
          </span>
          <span className={`micro ink-low ${c.where}`}>
            YOUTUBE
            <span className="visually-hidden"> (opens in a new tab)</span>
            {/* "Opens elsewhere": an arrow out to the top right. */}
            <svg width={14} height={14} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="M7 17 17 7M9 7h8v8" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </span>
        </span>
      </span>
    </a>
  );
});

/**
 * The small picture on a recipe card. Full cards give it a row of its own under the numbers
 * ("VIDEO" and the channel); compact cards (Today's carousel) tuck it at the end of the meta line.
 */
export const RecipeCardThumb = memo(function RecipeCardThumb({ video, compact }: { video: RecipeVideo; compact: boolean }) {
  if (compact) return <Cover video={video} size="mq" alt="" playSize={0} className={c.thumbCompact} />;
  return (
    <span className={c.thumbRow}>
      <Cover video={video} size="mq" alt="" playSize={0} className={c.thumb} />
      <span className={c.thumbText}>
        <span className="micro ink-low">VIDEO</span>
        <span className={`t-body-s ink-mid ${c.ellipsis}`}>{video.channel || "YouTube"}</span>
      </span>
    </span>
  );
});

/** A list row's picture (Recipes beside its detail pane): 16:9 in the row's icon slot. */
export const RecipeRowThumb = memo(function RecipeRowThumb({ video }: { video: RecipeVideo }) {
  return <Cover video={video} size="mq" alt="" playSize={0} className={c.thumbRowSlot} />;
});
