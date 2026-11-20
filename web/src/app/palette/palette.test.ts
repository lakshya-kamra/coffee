// Command palette ranking (v4 §2.8): group order, recents, caps, accent-insensitive matches,
// the registry and brew.next.
import { afterEach, describe, expect, it, vi } from "vitest";
import { clearCommandsForTests, findCommand, pushRecent, registerCommands, runCommand, type Command } from "./commands";
import { flatten, GROUP_CAP, rankCommands } from "./rank";

const noop = () => undefined;
const cmd = (id: string, title: string, group: Command["group"], extra: Partial<Command> = {}): Command => ({ id, title, group, run: noop, ...extra });

const ALL: Command[] = [
  cmd("go.today", "Today", "Go to", { popularity: 0 }),
  cmd("go.recipes", "Recipes", "Go to", { popularity: 1 }),
  cmd("go.log", "Log", "Go to", { popularity: 2 }),
  cmd("shell.shortcuts", "Keyboard shortcuts", "Actions", { keys: "?" }),
  cmd("shell.theme", "Switch to light theme", "Actions", { keywords: ["dark", "theme"] }),
  cmd("recipe:hoffmann-v60", "Hoffmann V60", "Recipes", { subtitle: "James Hoffmann · V60 · 15 g → 250 g", keywords: ["James Hoffmann", "V60"] }),
  cmd("recipe:kasuya-46", "4:6 method", "Recipes", { subtitle: "Tetsu Kasuya · V60", keywords: ["Tetsu Kasuya"] }),
  cmd("recipe:rao", "Rao Allongé", "Recipes", { subtitle: "Scott Rao · V60" }),
  cmd("bean:1", "Café de Colombia", "Coffees", { subtitle: "Square Mile" }),
  cmd("grinder:1", "1Zpresso K-Ultra", "Grinders"),
  cmd("brew:1", "Hoffmann V60", "Brews", { subtitle: "Thu 12 Nov · Café de Colombia · 8/10" }),
];

afterEach(() => clearCommandsForTests());

describe("rankCommands", () => {
  it("empty query: Recent (still existing, newest first), then Actions, then Go to", () => {
    const g = rankCommands(ALL, "", ["recipe:kasuya-46", "gone", "go.log"]);
    expect(g.map((x) => x.group)).toEqual(["Recent", "Actions", "Go to"]);
    expect(g[0]!.items.map((c) => c.id)).toEqual(["recipe:kasuya-46", "go.log"]);
    // A recent command is not repeated below.
    expect(g[2]!.items.map((c) => c.id)).toEqual(["go.today", "go.recipes"]);
  });

  it("with a query: fixed group order Actions, Recipes, Coffees, Grinders, Brews, Go to", () => {
    const g = rankCommands(ALL, "hoffmann", []);
    expect(g.map((x) => x.group)).toEqual(["Recipes", "Brews"]);
    const g2 = rankCommands([...ALL, cmd("go.hoff", "Hoffmann corner", "Go to"), cmd("act.hoff", "Hoffmann action", "Actions")], "hoffmann", []);
    expect(g2.map((x) => x.group)).toEqual(["Actions", "Recipes", "Brews", "Go to"]);
  });

  it("ignores accents and matches words in subtitles and keywords", () => {
    expect(flatten(rankCommands(ALL, "cafe colombia")).map((c) => c.id)).toEqual(["bean:1", "brew:1"]);
    expect(flatten(rankCommands(ALL, "allonge")).map((c) => c.id)).toEqual(["recipe:rao"]);
    expect(flatten(rankCommands(ALL, "kasuya")).map((c) => c.id)).toEqual(["recipe:kasuya-46"]);
    expect(flatten(rankCommands(ALL, "dark")).map((c) => c.id)).toEqual(["shell.theme"]);
    expect(flatten(rankCommands(ALL, "k ultra")).map((c) => c.id)).toEqual(["grinder:1"]);
  });

  it("ranks a title prefix above a substring", () => {
    const list = [cmd("a", "Pour over basics", "Recipes"), cmd("b", "Espresso pour", "Recipes"), cmd("c", "Pour", "Recipes")];
    expect(flatten(rankCommands(list, "pour")).map((c) => c.id)).toEqual(["c", "a", "b"]);
  });

  it("caps each group so the list stays under 50 rows", () => {
    const many = Array.from({ length: 200 }, (_, i) => cmd(`brew:${i}`, `V60 brew ${i}`, "Brews"));
    const recipes = Array.from({ length: 200 }, (_, i) => cmd(`recipe:${i}`, `V60 recipe ${i}`, "Recipes"));
    const g = rankCommands([...many, ...recipes], "v60");
    expect(g.find((x) => x.group === "Brews")!.items.length).toBe(GROUP_CAP.Brews);
    expect(flatten(g).length).toBeLessThanOrEqual(50);
  });

  it("nothing found: no groups", () => {
    expect(rankCommands(ALL, "zzzzqqq")).toEqual([]);
  });
});

describe("recents", () => {
  it("keeps the last five, newest first, without repeats", () => {
    let r: string[] = [];
    for (const id of ["a", "b", "c", "a", "d", "e", "f"]) r = pushRecent(r, id);
    expect(r).toEqual(["f", "e", "d", "a", "c"]);
  });
});

describe("registry", () => {
  it("registers, finds, runs and removes commands; the newest source wins an id", () => {
    const first = vi.fn();
    const second = vi.fn();
    const off1 = registerCommands([cmd("brew.next", "Brew A", "Actions", { run: first })]);
    expect(runCommand("brew.next")).toBe(true);
    const off2 = registerCommands(() => [cmd("brew.next", "Brew B", "Actions", { run: second })]);
    expect(findCommand("brew.next")?.title).toBe("Brew B");
    runCommand("brew.next");
    expect(first).toHaveBeenCalledTimes(1);
    expect(second).toHaveBeenCalledTimes(1);
    off2();
    off1();
    expect(runCommand("brew.next")).toBe(false);
  });
});
