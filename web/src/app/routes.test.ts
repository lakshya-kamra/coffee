// @vitest-environment jsdom
// URL ⇄ Route, deep-link stack synthesis and the tab back-behaviour rules (web-spec §6.1–6.2).
import { afterEach, describe, expect, it } from "vitest";
import { choreographyFor } from "./DetailHost";
import { nav, router, startRouter, stopRouterForTests } from "./router";
import { FOCUS_KINDS, PANEABLE_KINDS, parentTab, parsePath, pathOf, type Route } from "./routes";
import { SharedKeys } from "./sharedKeys";

const CASES: [string, Route][] = [
  ["/", { kind: "tab", tab: "today" }],
  ["/recipes", { kind: "tab", tab: "recipes" }],
  ["/log", { kind: "tab", tab: "log" }],
  ["/insights", { kind: "tab", tab: "insights" }],
  ["/gear", { kind: "tab", tab: "gear", gearTab: "beans" }],
  ["/gear/grinders", { kind: "tab", tab: "gear", gearTab: "grinders" }],
  ["/gear/app", { kind: "tab", tab: "gear", gearTab: "app" }],
  ["/recipes/hoffmann-v60", { kind: "recipe", id: "hoffmann-v60" }],
  ["/recipes/new", { kind: "recipeEdit", id: null, mode: "new" }],
  ["/recipes/custom-1/edit", { kind: "recipeEdit", id: "custom-1", mode: "edit" }],
  ["/recipes/kasuya-46/duplicate", { kind: "recipeEdit", id: "kasuya-46", mode: "duplicate" }],
  ["/brew/kasuya-46", { kind: "brew", recipeId: "kasuya-46" }],
  ["/review/0b6c6f3e-uid", { kind: "review", brewUid: "0b6c6f3e-uid" }],
  ["/log/0b6c6f3e-uid", { kind: "brewDetail", brewUid: "0b6c6f3e-uid" }],
  ["/welcome", { kind: "onboarding" }],
  // v4: compare two brews (pane-able, Log) and brew again from a logged brew.
  ["/log/0b6c6f3e-uid/compare/9a8b-uid", { kind: "compare", brewUid: "0b6c6f3e-uid", otherUid: "9a8b-uid" }],
  ["/brew/kasuya-46/again/0b6c6f3e-uid", { kind: "brew", recipeId: "kasuya-46", againUid: "0b6c6f3e-uid" }],
];

describe("routes", () => {
  it("parses and prints every route", () => {
    for (const [path, route] of CASES) {
      expect(parsePath(path), path).toEqual(route);
      expect(pathOf(route), path).toBe(path === "/gear" ? "/gear" : path);
    }
  });
  it("rejects unknown paths and tolerates a trailing slash", () => {
    for (const p of ["/nope", "/recipes/a/b/c", "/brew", "/gear/x", "/insights/1", "/log/a/compare", "/log/a/compare/b/c", "/log/a/b/c", "/brew/a/again", "/brew/a/x/b", "/recipes/a/edit/b"])
      expect(parsePath(p), p).toBeNull();
    expect(parsePath("/log/")).toEqual({ kind: "tab", tab: "log" });
  });
  it("encodes ids", () => {
    expect(pathOf({ kind: "recipe", id: "a b/c" })).toBe("/recipes/a%20b%2Fc");
    expect(parsePath("/recipes/a%20b%2Fc")).toEqual({ kind: "recipe", id: "a b/c" });
  });
  it("encodes both uids of a compare and the again uid", () => {
    expect(pathOf({ kind: "compare", brewUid: "a/b", otherUid: "c d" })).toBe("/log/a%2Fb/compare/c%20d");
    expect(parsePath("/log/a%2Fb/compare/c%20d")).toEqual({ kind: "compare", brewUid: "a/b", otherUid: "c d" });
    expect(pathOf({ kind: "brew", recipeId: "x", againUid: undefined })).toBe("/brew/x");
  });
  it("marks pane-able and focus routes (v4 §2.3, D4)", () => {
    expect([...PANEABLE_KINDS].sort()).toEqual(["brewDetail", "compare", "recipe"]);
    expect([...FOCUS_KINDS].sort()).toEqual(["brew", "onboarding", "recipeEdit", "review"]);
  });
  it("knows each detail's parent tab", () => {
    expect(parentTab({ kind: "compare", brewUid: "x", otherUid: "y" })).toBe("log");
    expect(parentTab({ kind: "brew", recipeId: "x", againUid: "u" })).toBe("today");
    expect(parentTab({ kind: "brewDetail", brewUid: "x" })).toBe("log");
    expect(parentTab({ kind: "recipe", id: "x" })).toBe("recipes");
    expect(parentTab({ kind: "review", brewUid: "x" })).toBe("today");
  });
  it("shared keys match Android", () => {
    expect(SharedKeys.recipeHeader("home", "kasuya-46")).toBe("home/recipe-header/kasuya-46");
  });
});

/** history.back()/go() fire popstate asynchronously in jsdom. */
const settle = () => new Promise((r) => setTimeout(r, 30));

function go(path: string) {
  history.replaceState(null, "", path);
}

describe("router", () => {
  afterEach(() => stopRouterForTests());

  it("a cold deep link builds Today → parent tab → detail, so Back stays in the app", async () => {
    go("/log/abc");
    startRouter();
    const s = router.get();
    expect(s.tab).toBe("log");
    expect(s.stack.map(pathOf)).toEqual(["/log", "/log/abc"]);
    expect(location.pathname).toBe("/log/abc");
    nav.back();
    await settle();
    expect(router.get().stack.map(pathOf)).toEqual(["/log"]);
    nav.back();
    await settle();
    expect(router.get().tab).toBe("today");
    expect(location.pathname).toBe("/");
  });

  it("unknown paths render Today and replace the URL", () => {
    go("/definitely/not");
    startRouter();
    expect(router.get().tab).toBe("today");
    expect(location.pathname).toBe("/");
  });

  it("Today ↔ tab keeps one entry; tab ↔ tab replaces; Back from any tab returns to Today", async () => {
    go("/");
    startRouter();
    nav.switchTab("recipes");
    // history.length also counts forward entries left by earlier tests, so measure after the push.
    expect(router.get().tabDepth).toBe(1);
    expect((history.state as { tabDepth: number }).tabDepth).toBe(1);
    const n = history.length;
    nav.switchTab("log");
    nav.switchTab("insights");
    expect(history.length).toBe(n);
    expect(router.get().tab).toBe("insights");
    nav.back();
    await settle();
    expect(router.get().tab).toBe("today");
  });

  it("push, replace and popToRoot", async () => {
    go("/");
    startRouter();
    nav.push("/recipes/kasuya-46", { from: "home" });
    expect(router.get().from).toBe("home");
    nav.push("/brew/kasuya-46");
    nav.replace("/review/u1"); // a finished timer is never on the stack
    expect(router.get().stack.map(pathOf)).toEqual(["/", "/recipes/kasuya-46", "/review/u1"]);
    nav.popToRoot("log");
    await settle();
    expect(router.get().depth).toBe(0);
    expect(router.get().tab).toBe("log");
  });

  it("a reload restores the stack from history.state", () => {
    go("/");
    startRouter();
    nav.push("/recipes/kasuya-46", { from: "recipes" });
    nav.push("/brew/kasuya-46");
    stopRouterForTests();
    startRouter();
    expect(router.get().stack.map(pathOf)).toEqual(["/", "/recipes/kasuya-46", "/brew/kasuya-46"]);
    expect(router.get().froms).toEqual(["recipes", null]);
  });

  it("the first-launch gate opens /welcome over Today", () => {
    go("/log");
    startRouter({ forcePath: "/welcome" });
    expect(router.get().top).toEqual({ kind: "onboarding" });
    expect(location.pathname).toBe("/welcome");
  });

  it("gear sub-tabs replace", () => {
    go("/gear");
    startRouter();
    const n = history.length;
    nav.switchTab("gear", "app");
    expect(router.get().gearTab).toBe("app");
    expect(location.pathname).toBe("/gear/app");
    expect(history.length).toBe(n);
  });
});

describe("detail choreography", () => {
  it("only opacity and whole transform strings, ζ ≥ 0.9 springs, no container scale for the shared recipe", () => {
    for (const kind of ["recipe", "recipeEdit", "brew", "review", "brewDetail", "onboarding"] as const) {
      for (const reduced of [false, true]) {
        const c = choreographyFor(kind, reduced, true);
        for (const t of [c.initial, c.animate, c.exit]) {
          const keys = Object.keys(t).filter((k) => k !== "transition");
          for (const k of keys) expect(["opacity", "transform"], `${kind} ${k}`).toContain(k);
          if (reduced) expect(keys).toEqual(["opacity"]);
        }
      }
    }
    expect(choreographyFor("recipe", false, true).backdrop).toBe(true);
    expect(choreographyFor("recipe", false, true).animate).not.toHaveProperty("transform");
  });
});
