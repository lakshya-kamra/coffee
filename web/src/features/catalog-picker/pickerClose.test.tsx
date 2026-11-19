// @vitest-environment jsdom
// A multi-select picker keeps its ticks however it closes: Back hands over the same set Done does.
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { catalog } from "@/catalog/catalog";
import { store } from "@/data/store";
import { CatalogPickerSheet } from "./index";

beforeAll(async () => {
  await catalog.load(["brewers"]);
  await store.init();
  // jsdom has no modal dialogs, ResizeObserver or matchMedia; the sheet only needs them to exist.
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute("open");
  };
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  vi.stubGlobal("matchMedia", () => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
});

afterEach(() => cleanup());
afterAll(() => store.stop());

function open(onDone: (ids: string[]) => void, onClose: () => void) {
  render(<CatalogPickerSheet kind="brewer" title="Brewers you own" multiSelect selected={[]} onPick={() => undefined} onDone={onDone} onClose={onClose} />);
}

/** Ticks the first two rows. */
async function tickTwo(): Promise<void> {
  const rows = (await screen.findAllByRole("button", { pressed: false, hidden: true })).slice(0, 2);
  for (const r of rows) fireEvent.click(r);
  expect(screen.getAllByRole("button", { pressed: true, hidden: true })).toHaveLength(2);
}

/** The one set a close handed over: two different brewers from the catalog. */
function handedOver(onDone: ReturnType<typeof vi.fn>): void {
  expect(onDone).toHaveBeenCalledTimes(1);
  const ids = onDone.mock.calls[0]![0] as string[];
  expect(new Set(ids).size).toBe(2);
  for (const id of ids) expect(catalog.brewer(id), id).toBeTruthy();
}

describe("CatalogPickerSheet, multi-select", () => {
  it("system Back keeps what was ticked", async () => {
    const onDone = vi.fn();
    const onClose = vi.fn();
    open(onDone, onClose);
    await tickTwo();
    expect(onDone).not.toHaveBeenCalled();
    history.back();
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1), { timeout: 3000 });
    handedOver(onDone);
  });

  it("Done hands the ticks over once", async () => {
    const onDone = vi.fn();
    const onClose = vi.fn();
    open(onDone, onClose);
    await tickTwo();
    fireEvent.click(screen.getByRole("button", { name: "Done · 2", hidden: true }));
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1), { timeout: 3000 });
    handedOver(onDone);
  });
});
