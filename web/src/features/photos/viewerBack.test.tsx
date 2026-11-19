// @vitest-environment jsdom
// The photo viewer and system Back: opening pushes one #sheet entry, Back closes the viewer and
// nothing under it, and closing by hand (X, Escape) pops that entry instead of leaving it behind.
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { PhotoRow } from "@/data/schema";
import { useSheetHistory } from "@/design/components";
import { PhotoViewer } from "./PhotoViewer";

const photo: PhotoRow = { id: 1, uid: "p1", brewLogId: 1, beanId: null, kind: "bed", mediaType: "image/jpeg", widthPx: 800, heightPx: 600, bytes: 1000, takenAt: 0, createdAt: 0, caption: "", sendToAi: false };

/** history.back() fires popstate asynchronously in jsdom. */
const settle = () => new Promise((r) => setTimeout(r, 30));
const sheets = () => ((history.state as { sheets?: string[] } | null)?.sheets ?? []).length;

/** Stands in for a full CoffeeSheet under the viewer (a photo opened from the coffee editor). */
function Layer({ onClose }: { onClose: () => void }) {
  useSheetHistory(true, onClose);
  return null;
}

beforeAll(() => {
  // jsdom has no modal dialogs; the viewer only needs them to open and close.
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute("open");
  };
});

beforeEach(() => history.replaceState({ v: 1, paths: ["/", "/log/abc"] }, "", "/log/abc"));

afterEach(async () => {
  cleanup();
  await settle();
});

describe("PhotoViewer and system Back", () => {
  it("opens on its own history entry, over the same screen", () => {
    render(<PhotoViewer photo={photo} kinds={["bed"]} editable={false} onClose={() => undefined} />);
    expect(sheets()).toBe(1);
    expect(location.pathname + location.hash).toBe("/log/abc#sheet");
    // The route under it rides along, so the router sees the same screen.
    expect((history.state as { paths: string[] }).paths).toEqual(["/", "/log/abc"]);
  });

  it("Back closes the viewer and stays on the screen underneath", async () => {
    const onClose = vi.fn();
    render(<PhotoViewer photo={photo} kinds={["bed"]} editable={false} onClose={onClose} />);
    history.back();
    await settle();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(sheets()).toBe(0);
    expect(location.pathname + location.hash).toBe("/log/abc");
  });

  it("X pops the entry it pushed, and a double tap pops only one", async () => {
    const onClose = vi.fn();
    render(<PhotoViewer photo={photo} kinds={["bed"]} editable={false} onClose={onClose} />);
    const x = screen.getByRole("button", { name: "Close", hidden: true });
    fireEvent.click(x);
    fireEvent.click(x);
    await settle();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(sheets()).toBe(0);
    expect(location.pathname + location.hash).toBe("/log/abc");
  });

  it("Escape pops the entry too", async () => {
    const onClose = vi.fn();
    const { container } = render(<PhotoViewer photo={photo} kinds={["bed"]} editable={false} onClose={onClose} />);
    container.querySelector("dialog")!.dispatchEvent(new Event("cancel", { cancelable: true }));
    await settle();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(sheets()).toBe(0);
  });

  it("over an open sheet, Back closes only the viewer", async () => {
    const closeSheet = vi.fn();
    const closeViewer = vi.fn();
    render(<Layer onClose={closeSheet} />);
    render(<PhotoViewer photo={photo} kinds={["bed"]} editable={false} onClose={closeViewer} />);
    expect(sheets()).toBe(2);
    history.back();
    await settle();
    expect(closeViewer).toHaveBeenCalledTimes(1);
    expect(closeSheet).not.toHaveBeenCalled();
    expect(sheets()).toBe(1);
    history.back();
    await settle();
    expect(closeSheet).toHaveBeenCalledTimes(1);
    expect(sheets()).toBe(0);
  });

  it("stays open under StrictMode's double mount (npm run dev), on one entry", async () => {
    const onClose = vi.fn();
    render(
      <StrictMode>
        <PhotoViewer photo={photo} kinds={["bed"]} editable={false} onClose={onClose} />
      </StrictMode>,
    );
    await settle();
    expect(onClose).not.toHaveBeenCalled();
    expect(sheets()).toBe(1);
    history.back();
    await settle();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(location.pathname + location.hash).toBe("/log/abc");
  });

  it("unmounting while its own Back is on the way does not pop a second entry", async () => {
    // Delete closes the viewer and, a moment later, the photo leaves the list and unmounts it.
    render(<Layer onClose={() => undefined} />);
    const { container, unmount } = render(<PhotoViewer photo={photo} kinds={["bed"]} editable={false} onClose={() => undefined} />);
    container.querySelector("dialog")!.dispatchEvent(new Event("cancel", { cancelable: true }));
    unmount();
    await settle();
    expect(sheets()).toBe(1);
  });
});
