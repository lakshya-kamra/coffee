// @vitest-environment jsdom
// Most catalog files load after first paint. A screen that mounts before its file lands must
// still see `ready` turn true: on a cold load of /gear/app the provider list, the water list and
// "Where you buy coffee" stayed empty for good, and first-launch onboarding never asked the region.
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { catalog, resetCatalogForTests } from "./catalog";
import { useCatalog } from "./useCatalog";

function Probe() {
  // A literal, as every caller writes it: its identity never changes between renders.
  const ready = useCatalog(["water", "roasters"]);
  return <p data-testid="ready">{ready ? `ready ${catalog.water.length > 0}` : "waiting"}</p>;
}

beforeEach(() => resetCatalogForTests());
afterEach(() => cleanup());

describe("useCatalog", () => {
  it("turns ready when the files land after the first render", async () => {
    render(<Probe />);
    expect(screen.getByTestId("ready").textContent).toBe("waiting");
    await act(async () => {
      await catalog.load(["water", "roasters"]);
    });
    expect(screen.getByTestId("ready").textContent).toBe("ready true");
  });

  it("is ready on the first render when the files are already here", async () => {
    await catalog.load(["water", "roasters"]);
    render(<Probe />);
    expect(screen.getByTestId("ready").textContent).toBe("ready true");
  });
});
