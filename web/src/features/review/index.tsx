// Public surface of features/review (web-spec §11 Review, §20.4).
import { registerCommands, type Command } from "@/app/palette/commands";
import { nav } from "@/app/router";
import { getSnapshot } from "@/data/store";
import { newestUnrated } from "./reviewModel";

export { ReviewScreen } from "./ReviewScreen";

/** Chunk + data warm-up, called on pointerdown of anything that navigates here. */
export function prefetch(): Promise<unknown> {
  return Promise.resolve();
}

let unregister: (() => void) | null = null;

/**
 * Palette command (v4 §2.8): "Rate your last brew" opens Review for the newest unrated brew.
 * Read when the palette opens, so it is there only while such a brew exists. Idempotent.
 */
export function registerReviewCommands(): void {
  if (unregister) return;
  unregister = registerCommands((): readonly Command[] => {
    const brew = newestUnrated(getSnapshot().brews);
    if (!brew) return [];
    return [
      {
        id: "review.last",
        title: "Rate your last brew",
        subtitle: brew.recipeName.trim() || undefined,
        group: "Actions",
        icon: "Star",
        keywords: ["review", "score", "rate"],
        run: () => nav.push(`/review/${encodeURIComponent(brew.uid)}`),
      },
    ];
  });
}

// The chunk loads with the first brew or review; registering here makes the command live from then on.
registerReviewCommands();
