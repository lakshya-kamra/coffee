// @vitest-environment jsdom
// Photos from the desktop (v4 §4.14): drag-and-drop onto the strip's zone and Ctrl/Cmd+V paste.
import { act, cleanup, render } from "@testing-library/react";
import { useRef } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { dragHasFiles, imageFiles, usePhotoIntake } from "./intake";

/** jsdom has no DataTransfer: the hook reads only `types` and `files`. */
function transfer(files: File[], types: string[] = ["Files"]) {
  return { types, files, dropEffect: "none" } as unknown as DataTransfer;
}

function dragEvent(type: string, dt: DataTransfer, target: EventTarget) {
  const e = new Event(type, { bubbles: true, cancelable: true }) as DragEvent;
  Object.defineProperty(e, "dataTransfer", { value: dt });
  Object.defineProperty(e, "relatedTarget", { value: type === "dragleave" ? null : target });
  target.dispatchEvent(e);
  return e;
}

function pasteEvent(files: File[], target: EventTarget = document.body) {
  const e = new Event("paste", { bubbles: true, cancelable: true }) as ClipboardEvent;
  Object.defineProperty(e, "clipboardData", { value: { files } });
  target.dispatchEvent(e);
  return e;
}

const jpeg = () => new File([new Uint8Array([0xff, 0xd8, 0xff])], "bed.jpg", { type: "image/jpeg" });
const text = () => new File(["hi"], "notes.txt", { type: "text/plain" });

function Strip({ onFiles, enabled = true, active = true }: { onFiles: (f: File[]) => void; enabled?: boolean; active?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const { dragging } = usePhotoIntake(ref, { enabled, onFiles, isActive: () => active });
  return (
    <div>
      <div ref={ref} data-testid="zone">
        {dragging && <span>Drop photos here</span>}
      </div>
      <div data-testid="elsewhere" />
      <input data-testid="field" />
    </div>
  );
}

afterEach(cleanup);

describe("pure helpers", () => {
  it("keeps image files (and untyped ones, the pipeline decides)", () => {
    const untyped = new File(["x"], "IMG_0001.HEIC", { type: "" });
    expect(imageFiles([jpeg(), text(), untyped]).map((f) => f.name)).toEqual(["bed.jpg", "IMG_0001.HEIC"]);
    expect(imageFiles(null)).toEqual([]);
  });
  it("only a drag that carries files counts", () => {
    expect(dragHasFiles(transfer([], ["Files"]))).toBe(true);
    expect(dragHasFiles(transfer([], ["text/plain", "text/uri-list"]))).toBe(false);
    expect(dragHasFiles(null)).toBe(false);
  });
});

describe("drag and drop", () => {
  it("shows the drop zone while files are over the screen and adds what lands on it", () => {
    const onFiles = vi.fn();
    const { getByTestId, queryByText } = render(<Strip onFiles={onFiles} />);
    const zone = getByTestId("zone");
    const dt = transfer([jpeg(), text()]);
    act(() => void dragEvent("dragenter", dt, getByTestId("elsewhere")));
    expect(queryByText("Drop photos here")).not.toBeNull();
    const over = dragEvent("dragover", dt, zone);
    expect(over.defaultPrevented).toBe(true);
    let drop!: Event;
    act(() => void (drop = dragEvent("drop", dt, zone)));
    expect(drop.defaultPrevented).toBe(true);
    expect(onFiles).toHaveBeenCalledTimes(1);
    expect((onFiles.mock.calls[0]![0] as File[]).map((f) => f.name)).toEqual(["bed.jpg"]);
    expect(queryByText("Drop photos here")).toBeNull();
  });

  it("a drop beside the zone is swallowed (no navigation) and adds nothing", () => {
    const onFiles = vi.fn();
    const { getByTestId, queryByText } = render(<Strip onFiles={onFiles} />);
    const dt = transfer([jpeg()]);
    act(() => void dragEvent("dragenter", dt, getByTestId("elsewhere")));
    let drop!: Event;
    act(() => void (drop = dragEvent("drop", dt, getByTestId("elsewhere"))));
    expect(drop.defaultPrevented).toBe(true);
    expect(onFiles).not.toHaveBeenCalled();
    expect(queryByText("Drop photos here")).toBeNull();
  });

  it("leaving the window hides the zone; a text drag never shows it", () => {
    const { getByTestId, queryByText } = render(<Strip onFiles={() => undefined} />);
    act(() => void dragEvent("dragenter", transfer([jpeg()]), getByTestId("zone")));
    act(() => void dragEvent("dragleave", transfer([jpeg()]), getByTestId("zone")));
    expect(queryByText("Drop photos here")).toBeNull();
    act(() => void dragEvent("dragenter", transfer([], ["text/plain"]), getByTestId("zone")));
    expect(queryByText("Drop photos here")).toBeNull();
  });

  it("a strip on a screen that is not in front ignores the drag", () => {
    const onFiles = vi.fn();
    const { getByTestId, queryByText } = render(<Strip onFiles={onFiles} active={false} />);
    const dt = transfer([jpeg()]);
    act(() => void dragEvent("dragenter", dt, getByTestId("zone")));
    expect(queryByText("Drop photos here")).toBeNull();
    const drop = dragEvent("drop", dt, getByTestId("zone"));
    expect(drop.defaultPrevented).toBe(false);
    expect(onFiles).not.toHaveBeenCalled();
  });
});

describe("paste", () => {
  it("adds a pasted image when no text field has focus", () => {
    const onFiles = vi.fn();
    render(<Strip onFiles={onFiles} />);
    const e = pasteEvent([jpeg()]);
    expect(e.defaultPrevented).toBe(true);
    expect(onFiles).toHaveBeenCalledTimes(1);
  });

  it("leaves paste alone while typing, for text, when disabled or not in front", () => {
    const onFiles = vi.fn();
    const { getByTestId, unmount } = render(<Strip onFiles={onFiles} />);
    (getByTestId("field") as HTMLInputElement).focus();
    expect(pasteEvent([jpeg()], getByTestId("field")).defaultPrevented).toBe(false);
    (getByTestId("field") as HTMLInputElement).blur();
    expect(pasteEvent([text()]).defaultPrevented).toBe(false);
    unmount();
    render(<Strip onFiles={onFiles} enabled={false} />);
    expect(pasteEvent([jpeg()]).defaultPrevented).toBe(false);
    cleanup();
    render(<Strip onFiles={onFiles} active={false} />);
    expect(pasteEvent([jpeg()]).defaultPrevented).toBe(false);
    expect(onFiles).not.toHaveBeenCalled();
  });
});
