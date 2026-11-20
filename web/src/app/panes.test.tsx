// @vitest-environment jsdom
// List-detail panes (v4 §2.3) through a harness: ListDetail + DetailHost with the real router and
// a stand-in registry. Rule 1 (pane only with a mounted ListDetail at expanded+), rule 2 (push
// from the default, replace over a selection), resizing across 840 keeping the URL, and a tab
// without ListDetail behaving exactly as before.
import { act, cleanup, render, screen } from "@testing-library/react";
import { domMax, LazyMotion } from "motion/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { DetailRoute } from "./routes";

vi.mock("./registry", () => ({
  DetailScreen: ({ route }: { route: DetailRoute }) => <h1>{`screen ${route.kind} ${"id" in route ? route.id : "brewUid" in route ? route.brewUid : ""}`}</h1>,
  DetailScreens: {},
  TabScreens: {},
  tabChunks: {},
  prefetchers: {},
  startupTasks: [],
}));

const { DetailHost } = await import("./DetailHost");
const { ListDetail, selectMode, useSelectInPane, usePane } = await import("./panes");
const { nav, router, startRouter, stopRouterForTests } = await import("./router");
const { resetPaneHostsForTests } = await import("./paneHosts");
const { setWindowClassForTests, windowClassFor } = await import("./windowClass");

/** history.back()/go() fire popstate asynchronously in jsdom. */
const settle = () => new Promise((r) => setTimeout(r, 30));

function at(path: string) {
  history.replaceState(null, "", path);
  startRouter();
}

function size(w: number, h: number) {
  act(() => setWindowClassForTests(windowClassFor(w, h)));
}

function Default() {
  const pane = usePane();
  return <p>{`default inPane=${pane.inPane}`}</p>;
}

let select: ((path: string) => void) | null = null;
function RecipeList() {
  const sel = useSelectInPane("recipes");
  useEffect(() => {
    select = sel;
  }, [sel]);
  return <p>recipe list</p>;
}

function Harness({ withLog = false }: { withLog?: boolean }) {
  return (
    <LazyMotion features={domMax}>
      <ListDetail tab="recipes" listLabel="Recipes" list={<RecipeList />} defaultDetail={<Default />} />
      {withLog && <ListDetail tab="log" listLabel="Log" list={<p>log list</p>} defaultDetail={<p>newest brew</p>} />}
      <DetailHost />
    </LazyMotion>
  );
}

const layers = () => document.querySelectorAll("[data-layer-key]");
const detailPane = () => document.querySelector("[data-detail-pane]");

beforeEach(() => {
  // Opacity-only choreography, so exits finish fast in jsdom.
  document.documentElement.dataset.motion = "reduced";
});

afterEach(() => {
  cleanup();
  stopRouterForTests();
  resetPaneHostsForTests();
  setWindowClassForTests(null);
  select = null;
});

describe("selectMode (rule 2)", () => {
  const snap = (paths: string[]) => {
    history.replaceState(null, "", paths[0]);
    startRouter();
    for (const p of paths.slice(1)) nav.push(p);
    return router.get();
  };
  it("pushes from the tab's default and replaces over a selection", () => {
    expect(selectMode(snap(["/recipes"]), "/recipes/a", "recipes")).toBe("push");
    stopRouterForTests();
    expect(selectMode(snap(["/recipes", "/recipes/a"]), "/recipes/b", "recipes")).toBe("replace");
    expect(selectMode(router.get(), "/recipes/a", "recipes")).toBe("none");
    stopRouterForTests();
    // Something other than a pane selection on top: push (a brew is never replaced).
    expect(selectMode(snap(["/recipes", "/recipes/a", "/brew/a"]), "/recipes/b", "recipes")).toBe("push");
    stopRouterForTests();
    // Another tab's detail never replaces this tab's selection.
    expect(selectMode(snap(["/log", "/log/u1"]), "/recipes/b", "recipes")).toBe("push");
  });
});

describe("ListDetail + DetailHost", () => {
  it("renders a selection in the pane at expanded+, with no layer", async () => {
    size(1440, 900);
    at("/recipes/hoffmann-v60");
    render(<Harness />);
    await settle();
    expect(detailPane()?.textContent).toContain("screen recipe hoffmann-v60");
    expect(layers().length).toBe(0);
    expect(screen.getByText("recipe list")).toBeTruthy();
  });

  it("shows the default detail with nothing selected", () => {
    size(1024, 768);
    at("/recipes");
    render(<Harness />);
    expect(detailPane()?.textContent).toBe("default inPane=true");
  });

  it("selects with push, then replace, and one Back returns to the default", async () => {
    size(1440, 900);
    at("/recipes");
    render(<Harness />);
    act(() => select!("/recipes/a"));
    expect(router.get().depth).toBe(1);
    const len = history.length;
    act(() => select!("/recipes/b"));
    expect(router.get().depth).toBe(1);
    expect(history.length).toBe(len);
    expect(detailPane()?.textContent).toContain("screen recipe b");
    act(() => nav.back());
    await act(settle);
    expect(router.get().depth).toBe(0);
    expect(detailPane()?.textContent).toBe("default inPane=true");
    expect(layers().length).toBe(0);
  });

  it("narrowing below 840 turns the selection into a layer instantly, keeping the URL; widening moves it back", async () => {
    size(1440, 900);
    at("/recipes/x");
    render(<Harness />);
    await settle();
    expect(layers().length).toBe(0);
    size(800, 900);
    expect(location.pathname).toBe("/recipes/x");
    expect(detailPane()).toBeNull();
    expect(layers().length).toBe(1);
    expect(layers()[0]!.textContent).toContain("screen recipe x");
    // Instant: no enter animation left running on the layer.
    expect((layers()[0] as HTMLElement).style.opacity === "" || (layers()[0] as HTMLElement).style.opacity === "1").toBe(true);
    size(1440, 900);
    await act(settle);
    expect(location.pathname).toBe("/recipes/x");
    expect(layers().length).toBe(0);
    expect(detailPane()?.textContent).toContain("screen recipe x");
  });

  it("anything above the pane still renders as a focus layer", async () => {
    size(1440, 900);
    at("/recipes/x");
    render(<Harness />);
    act(() => nav.push("/brew/x"));
    await act(settle);
    expect(layers().length).toBe(1);
    expect(layers()[0]!.getAttribute("data-layer-key")).toBe("1:/brew/x");
    expect(layers()[0]!.hasAttribute("data-focus")).toBe(true);
    expect(detailPane()?.textContent).toContain("screen recipe x");
  });

  it("a tab without ListDetail keeps today's layer, at any width", async () => {
    size(1920, 1080);
    at("/log/u1");
    render(<Harness />);
    await settle();
    expect(layers().length).toBe(1);
    expect(layers()[0]!.getAttribute("aria-label")).toBe("Brew details");
    expect(layers()[0]!.textContent).toContain("screen brewDetail u1");
  });

  it("the same Log route lands in the pane once Log adopts ListDetail", async () => {
    size(1920, 1080);
    at("/log/u1");
    render(<Harness withLog />);
    await settle();
    expect(layers().length).toBe(0);
    const panes = document.querySelectorAll("[data-detail-pane]");
    expect([...panes].map((p) => p.textContent)).toEqual(["default inPane=true", "screen brewDetail u1"]);
  });

  it("below expanded ListDetail renders just the list", () => {
    size(390, 844);
    at("/recipes");
    render(<Harness />);
    expect(detailPane()).toBeNull();
    expect(screen.getByText("recipe list")).toBeTruthy();
    expect(document.querySelector("[data-list-pane]")?.getAttribute("role")).toBeNull();
  });
});
