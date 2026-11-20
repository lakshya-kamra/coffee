/**
 * How a sheet presents, by role and window class (v4 §2.4, D6). Pure and tested; CoffeeSheet
 * reads it live, so an open sheet re-lays itself out when the window crosses a breakpoint.
 *
 * | role    | compact          | medium              | expanded+                       | short (>= 600 wide) |
 * | confirm | bottom sheet     | dialog 400          | dialog 420                      | dialog 420          |
 * | content | bottom sheet     | bottom sheet <= 640 | dialog 640 x <= 80vh            | dialog 640 x 92vh   |
 * | editor  | full-height      | full-height <= 640  | side sheet 560                  | side sheet 560      |
 * | picker  | sheet            | sheet <= 640        | popover 480 x 560 at the anchor | dialog 520 x 92vh   |
 * |         |                  |                     | (dialog 520 without an anchor)  |                     |
 *
 * `full` (a full-height sheet with its own history entry) keeps its height wherever the result
 * is still a sheet.
 */
import type { WindowClass } from "@/app/windowClass";

export type SheetRole = "confirm" | "content" | "editor" | "picker";
export type PresentationKind = "bottom" | "full" | "dialog" | "side" | "popover";

export interface Presentation {
  readonly kind: PresentationKind;
  /** px; null = the sheet default (--column-max on compact). */
  readonly maxWidth: number | null;
  /** CSS length; null = the kind's default. */
  readonly maxHeight: string | null;
}

const p = (kind: PresentationKind, maxWidth: number | null = null, maxHeight: string | null = null): Presentation => ({ kind, maxWidth, maxHeight });

export function presentationFor(role: SheetRole, wc: WindowClass, opts: { anchored?: boolean; full?: boolean } = {}): Presentation {
  const sheet = (max: number | null) => p(opts.full || role === "editor" ? "full" : "bottom", max);
  const short = wc.short && wc.width !== "compact";
  if (wc.width === "compact") return sheet(null);
  if (short) {
    switch (role) {
      case "confirm":
        return p("dialog", 420);
      case "content":
        return p("dialog", 640, "92vh");
      case "editor":
        return p("side", 560);
      case "picker":
        return p("dialog", 520, "92vh");
    }
  }
  if (wc.width === "medium") {
    return role === "confirm" ? p("dialog", 400) : sheet(640);
  }
  switch (role) {
    case "confirm":
      return p("dialog", 420);
    case "content":
      return p("dialog", 640, "80vh");
    case "editor":
      return p("side", 560);
    case "picker":
      return opts.anchored ? p("popover", 480, "560px") : p("dialog", 520, "80vh");
  }
}
