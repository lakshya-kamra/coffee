// @vitest-environment jsdom
// grinders.json loads after first paint. A grinder picked from the list keeps its dial format and
// ranges there, so what a screen shows for it must follow the file landing: lead decision 2 says
// a K-Ultra at 75 reads "dial 7.5", and it must not sit at "0 turns + 75" until something else
// happens to re-render the screen.
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { needsGrinderCatalog } from "@/app/boot";
import { catalog, resetCatalogForTests } from "@/catalog/catalog";
import { useCatalogGrinder } from "@/catalog/useCatalog";
import { newGrinder, type GrinderRow } from "@/data/schema";
import { dialWords, GrindRangeCard, useGrindScale } from "./index";

// As a row picked from the catalog is stored (mapping.ts fieldsFromCatalog): dialFormat "".
const kultra = newGrinder({ brand: "1Zpresso", model: "K-Ultra", catalogId: "1zpresso-k-ultra", clicksPerRotation: 100, micronsPerClick: 20, dialFormat: "" });
const typed = newGrinder({ brand: "Old", model: "Mill", clicksPerRotation: 12 });

function Probe({ g }: { g: GrinderRow | null }) {
  const scale = useGrindScale(g);
  const entry = useCatalogGrinder(g?.catalogId);
  return (
    <div>
      <p data-testid="reading">{scale ? dialWords(scale, 75) : "no grinder"}</p>
      <p data-testid="entry">{entry ? entry.id : "none"}</p>
      {scale && <GrindRangeCard scale={scale} method="pourover" collapsed />}
    </div>
  );
}

beforeEach(() => resetCatalogForTests());
afterEach(() => cleanup());

describe("a grinder's scale on screen", () => {
  it("is rebuilt when grinders.json lands, with nothing else changing", async () => {
    render(<Probe g={kultra} />);
    // First frame, catalog not here yet: the row alone says 100 clicks a turn.
    expect(screen.getByTestId("reading").textContent).toBe("0 turns + 75");
    expect(screen.getByTestId("entry").textContent).toBe("none");
    expect(screen.queryByText(/Start pour-over at/i)).toBeNull();

    // The hook asked for the file itself; wait for it.
    await act(async () => {
      await catalog.load(["grinders"]);
    });
    expect(screen.getByTestId("reading").textContent).toBe("dial 7.5");
    expect(screen.getByTestId("entry").textContent).toBe("1zpresso-k-ultra");
    expect(screen.getByText(/Start pour-over at/i)).toBeTruthy();
  });

  it("leaves a typed-in grinder alone and handles no grinder", async () => {
    const view = render(<Probe g={typed} />);
    const before = screen.getByTestId("reading").textContent;
    await act(async () => {
      await catalog.load(["grinders"]);
    });
    expect(screen.getByTestId("reading").textContent).toBe(before);
    view.rerender(<Probe g={null} />);
    expect(screen.getByTestId("reading").textContent).toBe("no grinder");
  });

  it("boot fetches grinders.json first only when a grinder is linked to it", () => {
    expect(needsGrinderCatalog([])).toBe(false);
    expect(needsGrinderCatalog([typed])).toBe(false);
    expect(needsGrinderCatalog([{ catalogId: "" }])).toBe(false);
    expect(needsGrinderCatalog([typed, kultra])).toBe(true);
  });
});
