// @vitest-environment jsdom
// ConfirmSheet (v4 §2.4, A6): the safe action has focus when it opens, a choice runs after the
// close, it presents as a dialog at expanded+; editors ask before discarding unsaved changes.
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { setWindowClassForTests, windowClassFor } from "@/app/windowClass";
import { ConfirmSheet, defaultSafe } from "./ConfirmSheet";
import { CoffeeSheet, DISCARD_COPY } from "./Sheet";

beforeAll(() => {
  // jsdom has no modal dialogs; the sheet only needs these to exist.
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute("open");
  };
  document.documentElement.dataset.motion = "reduced";
});

afterEach(() => {
  cleanup();
  setWindowClassForTests(null);
});

/** A destructive confirm: "Leave" is the action, "Keep it going" the safe cancel. */
function leave(overrides: Partial<Parameters<typeof ConfirmSheet>[0]> = {}) {
  const calls = { confirm: vi.fn(), cancel: vi.fn(), dismiss: vi.fn(), close: vi.fn() };
  render(
    <ConfirmSheet
      title="Leave this brew?"
      body="The timer stops and nothing is saved."
      confirmText="Leave"
      cancelText="Keep it going"
      danger
      onConfirm={calls.confirm}
      onCancel={calls.cancel}
      onDismiss={calls.dismiss}
      onClose={calls.close}
      {...overrides}
    />,
  );
  return calls;
}

describe("ConfirmSheet", () => {
  it("focuses the safe action, never the destructive one", () => {
    leave();
    const safe = screen.getByRole("button", { name: "Keep it going", hidden: true });
    expect(document.activeElement).toBe(safe);
    expect(document.activeElement?.textContent).not.toBe("Leave");
  });

  it("knows the safe side of today's call sites", () => {
    expect(defaultSafe(true, "Cancel")).toBe("cancel"); // Delete this photo?
    expect(defaultSafe(false, "Cancel")).toBe("cancel"); // Reset to the original?
    expect(defaultSafe(false, "Discard")).toBe("confirm"); // Leave this brew? Keep it going / Discard
    cleanup();
    // The brew screen's call site as it is today: "Keep it going" is the confirm.
    render(<ConfirmSheet title="Leave this brew?" confirmText="Keep it going" cancelText="Discard" onConfirm={vi.fn()} onClose={vi.fn()} />);
    expect(document.activeElement?.textContent).toBe("Keep it going");
    cleanup();
    render(<ConfirmSheet title="Delete?" confirmText="Delete" cancelText="Keep" danger safe="confirm" onConfirm={vi.fn()} onClose={vi.fn()} />);
    expect(document.activeElement?.textContent).toBe("Delete");
  });

  it("presents as a centred dialog at 1440 and a bottom sheet at 390", () => {
    act(() => setWindowClassForTests(windowClassFor(1440, 900)));
    leave();
    expect(document.querySelector("dialog")?.getAttribute("data-present")).toBe("dialog");
    cleanup();
    act(() => setWindowClassForTests(windowClassFor(390, 844)));
    leave();
    expect(document.querySelector("dialog")?.getAttribute("data-present")).toBe("bottom");
  });

  it("runs the choice after closing", async () => {
    const calls = leave();
    fireEvent.click(screen.getByRole("button", { name: "Leave", hidden: true }));
    await waitFor(() => expect(calls.close).toHaveBeenCalled());
    expect(calls.confirm).toHaveBeenCalledTimes(1);
    expect(calls.cancel).not.toHaveBeenCalled();
  });

  it("Escape (the dialog's cancel) dismisses without a choice", async () => {
    const calls = leave();
    const d = document.querySelector("dialog")!;
    fireEvent(d, new Event("cancel", { cancelable: true }));
    await waitFor(() => expect(calls.close).toHaveBeenCalled());
    expect(calls.dismiss).toHaveBeenCalledTimes(1);
    expect(calls.confirm).not.toHaveBeenCalled();
  });
});

function Editor({ onSave, onClose }: { onSave: () => void; onClose: () => void }) {
  const [text, setText] = useState("");
  return (
    <CoffeeSheet role="editor" label="Edit coffee" title="Edit coffee" dirty={text !== ""} onSave={onSave} onClose={onClose}>
      <input aria-label="Name" value={text} onChange={(e) => setText(e.currentTarget.value)} />
    </CoffeeSheet>
  );
}

describe("editor sheet", () => {
  it("is a side sheet with a header Save at expanded+, and Ctrl/Cmd+S saves", () => {
    act(() => setWindowClassForTests(windowClassFor(1440, 900)));
    const onSave = vi.fn();
    render(<Editor onSave={onSave} onClose={vi.fn()} />);
    expect(document.querySelector("dialog")?.getAttribute("data-present")).toBe("side");
    fireEvent.click(screen.getByRole("button", { name: "Save", hidden: true }));
    fireEvent.keyDown(screen.getByRole("textbox", { name: "Name", hidden: true }), { key: "s", ctrlKey: true });
    fireEvent.keyDown(screen.getByRole("textbox", { name: "Name", hidden: true }), { key: "s", metaKey: true });
    expect(onSave).toHaveBeenCalledTimes(3);
  });

  it("asks before discarding unsaved changes, with Keep editing focused", async () => {
    act(() => setWindowClassForTests(windowClassFor(1440, 900)));
    const onClose = vi.fn();
    render(<Editor onSave={vi.fn()} onClose={onClose} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Name", hidden: true }), { target: { value: "Gesha" } });
    const outer = document.querySelector("dialog")!;
    fireEvent(outer, new Event("cancel", { cancelable: true }));
    expect(await screen.findByText(DISCARD_COPY.title)).toBeTruthy();
    expect(screen.getByText(DISCARD_COPY.body)).toBeTruthy();
    expect(document.activeElement?.textContent).toBe(DISCARD_COPY.keep);
    // Keep editing: the editor stays.
    fireEvent.click(screen.getByRole("button", { name: DISCARD_COPY.keep, hidden: true }));
    await waitFor(() => expect(screen.queryByText(DISCARD_COPY.title)).toBeNull());
    expect(onClose).not.toHaveBeenCalled();
    // Ask again and discard: the editor closes.
    fireEvent(outer, new Event("cancel", { cancelable: true }));
    fireEvent.click(await screen.findByRole("button", { name: DISCARD_COPY.discard, hidden: true }));
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  });
});
