// @vitest-environment jsdom
// The photo viewer's previous/next (v4 §3.13): "1 of 2" on every class, the buttons, Left/Right.
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeAll, describe, expect, it } from "vitest";
import type { PhotoRow } from "@/data/schema";
import { PhotoViewer, stepZoom, viewerPosition } from "./PhotoViewer";

const mk = (id: number, uid: string, kind: PhotoRow["kind"]): PhotoRow => ({ id, uid, brewLogId: 1, beanId: null, kind, mediaType: "image/jpeg", widthPx: 800, heightPx: 600, bytes: 1000, takenAt: 0, createdAt: 0, caption: "", sendToAi: false });
const PHOTOS = [mk(1, "p1", "bed"), mk(2, "p2", "cup"), mk(3, "p3", "bag")];

const settle = () => new Promise((r) => setTimeout(r, 30));

function Host({ start = "p1", photos = PHOTOS }: { start?: string; photos?: PhotoRow[] }) {
  const [uid, setUid] = useState(start);
  const photo = photos.find((p) => p.uid === uid)!;
  return <PhotoViewer photo={photo} photos={photos} onShow={setUid} kinds={["bed", "cup", "bag"]} editable={false} onClose={() => undefined} />;
}

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute("open");
  };
});

afterEach(async () => {
  cleanup();
  await settle();
});

describe("viewerPosition", () => {
  it("index, count and neighbours", () => {
    expect(viewerPosition(PHOTOS, "p1")).toEqual({ index: 0, count: 3, prev: null, next: "p2" });
    expect(viewerPosition(PHOTOS, "p2")).toEqual({ index: 1, count: 3, prev: "p1", next: "p3" });
    expect(viewerPosition(PHOTOS, "p3")).toEqual({ index: 2, count: 3, prev: "p2", next: null });
    expect(viewerPosition(PHOTOS, "gone")).toEqual({ index: 0, count: 1, prev: null, next: null });
  });
  it("zoom steps stay between 1 and 5", () => {
    expect(stepZoom(1, 1)).toBe(1.25);
    expect(stepZoom(1, -1)).toBe(1);
    expect(stepZoom(4.5, 1)).toBe(5);
  });
});

describe("previous and next", () => {
  const counter = () => screen.getByText(/of 3$/, { selector: "span" }).textContent;

  it("shows '1 of 3' and the buttons step through, disabled at the ends", () => {
    render(<Host />);
    expect(counter()).toBe("1 of 3");
    const prev = screen.getByRole("button", { name: "Previous photo", hidden: true });
    const next = screen.getByRole("button", { name: "Next photo", hidden: true });
    expect((prev as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(next);
    expect(counter()).toBe("2 of 3");
    fireEvent.click(next);
    expect(counter()).toBe("3 of 3");
    expect(screen.getByRole<HTMLButtonElement>("button", { name: "Next photo", hidden: true }).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Previous photo", hidden: true }));
    expect(counter()).toBe("2 of 3");
  });

  it("Left and Right step too; keys stay put at the ends", () => {
    const { container } = render(<Host start="p2" />);
    const dialog = container.querySelector("dialog")!;
    act(() => void fireEvent.keyDown(dialog, { key: "ArrowRight" }));
    expect(counter()).toBe("3 of 3");
    act(() => void fireEvent.keyDown(dialog, { key: "ArrowRight" }));
    expect(counter()).toBe("3 of 3");
    act(() => void fireEvent.keyDown(dialog, { key: "ArrowLeft" }));
    act(() => void fireEvent.keyDown(dialog, { key: "ArrowLeft" }));
    expect(counter()).toBe("1 of 3");
    // The dialog's name follows the photo shown.
    expect(dialog.getAttribute("aria-label")).toMatch(/^Bed photo/);
  });

  it("a single photo has no counter and no arrows", () => {
    render(<Host photos={[PHOTOS[0]!]} />);
    expect(screen.queryByText(/of 1/)).toBeNull();
    expect(screen.queryByRole("button", { name: "Next photo", hidden: true })).toBeNull();
  });
});
