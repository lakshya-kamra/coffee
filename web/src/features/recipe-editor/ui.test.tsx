// @vitest-environment jsdom
// The WEB-4 widgets render and react to input in a real DOM: the inline catalog picker ranks
// as A§7.1 says, the dial input stores what the dial means, and the recipe builder keeps Save
// off until the numbers add up, then offers the one-tap last-pour fix.
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { catalog } from "@/catalog/catalog";
import { newGrinder } from "@/data/schema";
import { store } from "@/data/store";
import { scaleFor } from "@/domain/grind";
import { CatalogPickerList } from "@/features/catalog-picker";
import { DialReadingInput, GrindRangeCard } from "@/features/grind";
import { RecipeEditorScreen } from "./index";

beforeAll(async () => {
  await catalog.load(["brewers", "recipes", "grinders"]);
  await store.init();
  // jsdom has no ResizeObserver or matchMedia; the widgets only need them to exist.
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

const kUltra = () => scaleFor(newGrinder({ brand: "1Zpresso", model: "K-Ultra", clicksPerRotation: 100, micronsPerClick: 20, maxSetting: 200, catalogId: "1zpresso-k-ultra" }));

describe("CatalogPickerList", () => {
  it("ranks K-Ultra first for 'k ultra' after the debounce", async () => {
    vi.useFakeTimers();
    const onPick = vi.fn();
    render(<CatalogPickerList kind="grinder" onPick={onPick} focusSearch={false} />);
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "k ultra" } });
    act(() => {
      vi.advanceTimersByTime(100);
    });
    vi.useRealTimers();
    const first = (await screen.findByRole("list", { name: "Matches" })).querySelector("button");
    expect(first?.textContent).toContain("1Zpresso K-Ultra");
    fireEvent.click(first!);
    expect(onPick).toHaveBeenCalledWith({ id: "1zpresso-k-ultra" });
  });

  it("offers your own entry when nothing matches", async () => {
    vi.useFakeTimers();
    const onPick = vi.fn();
    render(<CatalogPickerList kind="grinder" onPick={onPick} focusSearch={false} />);
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Zzyzx Mill" } });
    act(() => {
      vi.advanceTimersByTime(100);
    });
    vi.useRealTimers();
    fireEvent.click(await screen.findByRole("button", { name: /Use “Zzyzx Mill”/ }));
    expect(onPick).toHaveBeenCalledWith({ custom: "Zzyzx Mill" });
  });
});

describe("grind widgets", () => {
  it("turns a K-Ultra dial reading into stored clicks", () => {
    const onValue = vi.fn();
    render(<DialReadingInput scale={kUltra()} value={75} onValue={onValue} />);
    expect(screen.getByText("= 75 clicks (what the app stores)")).toBeTruthy();
    fireEvent.change(screen.getByRole("textbox", { name: "Mark" }), { target: { value: "8" } });
    expect(onValue).toHaveBeenLastCalledWith(78);
    fireEvent.change(screen.getByRole("textbox", { name: "Mark" }), { target: { value: "12" } });
    expect(screen.getByRole("alert").textContent).toContain("Marks go 0–9");
    fireEvent.click(screen.getByRole("button", { name: "Turns: more" }));
    expect(onValue).toHaveBeenLastCalledWith(175);
  });

  it("shows starting points with sources and the confidence word", () => {
    render(<GrindRangeCard scale={kUltra()} method="pourover" />);
    expect(screen.getByText("Pour-over")).toBeTruthy();
    expect(screen.getByText("A starting point. Taste decides.")).toBeTruthy();
    expect(screen.getAllByRole("link").length).toBeGreaterThan(0);
  });
});

describe("RecipeEditorScreen", () => {
  it("keeps Save off on a blank recipe, then fixes the last pour and enables it", async () => {
    render(<RecipeEditorScreen id={null} mode="new" />);
    const save = () => screen.getByRole<HTMLButtonElement>("button", { name: "Save" });
    expect(save().disabled).toBe(true);
    fireEvent.change(screen.getByRole("textbox", { name: "Name" }), { target: { value: "Test" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Coffee" }), { target: { value: "15" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Water" }), { target: { value: "250" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Pour to" }), { target: { value: "200" } });
    const fix = await screen.findByRole("button", { name: "Set the last pour to 250 g" });
    expect(save().disabled).toBe(true);
    fireEvent.click(fix);
    expect(screen.getByRole<HTMLInputElement>("textbox", { name: "Pour to" }).value).toBe("250");
    await vi.waitFor(() => expect(save().disabled).toBe(false));
  });

  it("duplicates a published recipe as yours", () => {
    if (!catalog.recipe("hoffmann-v60")) return;
    render(<RecipeEditorScreen id="hoffmann-v60" mode="duplicate" />);
    expect(screen.getByRole<HTMLInputElement>("textbox", { name: "Name" }).value).toMatch(/\(mine\)$/);
  });
});
