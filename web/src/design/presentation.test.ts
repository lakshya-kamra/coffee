// Sheet role -> presentation (v4 §2.4, D6), the whole table.
import { describe, expect, it } from "vitest";
import { windowClassFor } from "@/app/windowClass";
import { presentationFor, type SheetRole } from "./presentation";

const PHONE = windowClassFor(390, 844);
const TABLET = windowClassFor(700, 1000);
const IPAD_L = windowClassFor(1024, 768);
const LAPTOP = windowClassFor(1440, 900);
const DESKTOP = windowClassFor(1920, 1080);
const PHONE_L = windowClassFor(844, 390);
const SMALL_SHORT = windowClassFor(640, 360);

const kind = (role: SheetRole, wc = PHONE, opts: { anchored?: boolean; full?: boolean } = {}) => presentationFor(role, wc, opts);

describe("presentationFor", () => {
  it("compact: sheets only (full height for editors and `full`)", () => {
    expect(kind("confirm").kind).toBe("bottom");
    expect(kind("content").kind).toBe("bottom");
    expect(kind("editor").kind).toBe("full");
    expect(kind("picker").kind).toBe("bottom");
    expect(kind("picker", PHONE, { full: true }).kind).toBe("full");
    expect(kind("picker", PHONE, { anchored: true }).kind).toBe("bottom");
  });

  it("medium: confirm is a 400 dialog, the rest stay sheets capped at 640", () => {
    expect(kind("confirm", TABLET)).toEqual({ kind: "dialog", maxWidth: 400, maxHeight: null });
    expect(kind("content", TABLET)).toEqual({ kind: "bottom", maxWidth: 640, maxHeight: null });
    expect(kind("editor", TABLET)).toEqual({ kind: "full", maxWidth: 640, maxHeight: null });
    expect(kind("picker", TABLET, { anchored: true })).toEqual({ kind: "bottom", maxWidth: 640, maxHeight: null });
  });

  it("expanded and up: dialogs, a 560 side sheet and an anchored 480 popover", () => {
    for (const wc of [IPAD_L, LAPTOP, DESKTOP]) {
      expect(kind("confirm", wc)).toEqual({ kind: "dialog", maxWidth: 420, maxHeight: null });
      expect(kind("content", wc)).toEqual({ kind: "dialog", maxWidth: 640, maxHeight: "80vh" });
      expect(kind("content", wc, { full: true }).kind).toBe("dialog");
      expect(kind("editor", wc)).toEqual({ kind: "side", maxWidth: 560, maxHeight: null });
      expect(kind("picker", wc, { anchored: true })).toEqual({ kind: "popover", maxWidth: 480, maxHeight: "560px" });
      expect(kind("picker", wc)).toEqual({ kind: "dialog", maxWidth: 520, maxHeight: "80vh" });
    }
  });

  it("short windows at least 600 wide: dialogs and the side sheet, never a bottom sheet", () => {
    for (const wc of [PHONE_L, SMALL_SHORT]) {
      expect(kind("confirm", wc).kind).toBe("dialog");
      expect(kind("content", wc)).toEqual({ kind: "dialog", maxWidth: 640, maxHeight: "92vh" });
      expect(kind("editor", wc).kind).toBe("side");
      expect(kind("picker", wc, { anchored: true })).toEqual({ kind: "dialog", maxWidth: 520, maxHeight: "92vh" });
    }
    // Short but compact (a small phone on its side): still sheets.
    expect(kind("confirm", windowClassFor(560, 320)).kind).toBe("bottom");
  });
});
