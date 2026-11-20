// Keyboard shortcuts (v4 §2.7): parsing, matching, chords, typing and dialog rules, scope order.
import { beforeEach, describe, expect, it, vi } from "vitest";
import { formatKeys, ariaKeyShortcuts } from "@/design/keys";
import { allowedWhileTyping, CHORD_WINDOW_MS, groupedLive, matchesStep, parseKeys, parseStep, ShortcutEngine, type Binding, type KeyEventLike } from "./shortcuts";

const key = (k: string, mods: Partial<Omit<KeyEventLike, "key">> = {}): KeyEventLike => ({ key: k, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...mods });
const ctx = (over: Partial<{ typing: boolean; dialogOpen: boolean; now: number }> = {}) => ({ typing: false, dialogOpen: false, now: 0, ...over });

describe("parsing and matching", () => {
  it("parses steps, modifiers and aliases", () => {
    expect(parseStep("mod+k")).toEqual({ key: "k", mod: true, ctrl: false, meta: false, alt: false, shift: false });
    expect(parseStep("ctrl+\\")).toMatchObject({ key: "\\", ctrl: true });
    expect(parseStep("shift+d")).toMatchObject({ key: "d", shift: true });
    expect(parseStep("esc").key).toBe("escape");
    expect(parseStep("space").key).toBe(" ");
    expect(parseStep("down").key).toBe("arrowdown");
    expect(parseStep("mod++").key).toBe("+");
    expect(parseKeys("g t").map((s) => s.key)).toEqual(["g", "t"]);
  });

  it("mod is Ctrl or Cmd", () => {
    const s = parseStep("mod+k");
    expect(matchesStep(s, key("k", { ctrlKey: true }))).toBe(true);
    expect(matchesStep(s, key("k", { metaKey: true }))).toBe(true);
    expect(matchesStep(s, key("k"))).toBe(false);
    expect(matchesStep(s, key("K", { ctrlKey: true, altKey: true }))).toBe(false);
  });

  it("letters ignore case but not Shift; punctuation ignores Shift", () => {
    expect(matchesStep(parseStep("j"), key("j"))).toBe(true);
    expect(matchesStep(parseStep("j"), key("J", { shiftKey: true }))).toBe(false);
    expect(matchesStep(parseStep("shift+j"), key("J", { shiftKey: true }))).toBe(true);
    expect(matchesStep(parseStep("?"), key("?", { shiftKey: true }))).toBe(true);
    expect(matchesStep(parseStep("/"), key("?", { shiftKey: true }))).toBe(false);
    expect(matchesStep(parseStep("j"), key("j", { ctrlKey: true }))).toBe(false);
    expect(matchesStep(parseStep("escape"), key("Escape"))).toBe(true);
    expect(matchesStep(parseStep("1"), key("1"))).toBe(true);
  });

  it("only Ctrl/Cmd combos and Escape run while typing", () => {
    expect(allowedWhileTyping(parseKeys("mod+k"))).toBe(true);
    expect(allowedWhileTyping(parseKeys("escape"))).toBe(true);
    expect(allowedWhileTyping(parseKeys("/"))).toBe(false);
    expect(allowedWhileTyping(parseKeys("g t"))).toBe(false);
  });
});

describe("ShortcutEngine", () => {
  let engine: ShortcutEngine;
  const ran: string[] = [];
  const b = (keys: string, name: string, extra: Partial<Binding> = {}): Binding => ({ keys, label: name, group: "General", run: () => ran.push(name), ...extra });
  beforeEach(() => {
    engine = new ShortcutEngine();
    ran.length = 0;
  });

  it("runs the newest scope first", () => {
    engine.register("shell", [b("b", "shell-b"), b("1", "today")], true);
    engine.register("recipes", [b("b", "brew-selected")], true);
    engine.handle(key("b"), ctx());
    engine.handle(key("1"), ctx());
    expect(ran).toEqual(["brew-selected", "today"]);
  });

  it("disabled scopes neither run nor list", () => {
    const id = engine.register("screen", [b("b", "screen-b")], false);
    engine.register("shell", [b("b", "shell-b")], true);
    expect(engine.handle(key("b"), ctx())).not.toBeNull();
    expect(ran).toEqual(["shell-b"]);
    engine.update(id, [b("b", "screen-b")], true);
    expect(engine.live().map((x) => x.label)).toEqual(["shell-b"]); // shell registered later: wins
  });

  it("chords within 1 s, and the window closes", () => {
    engine.register("shell", [b("g t", "today"), b("g l", "log"), b("t", "plain-t")], true);
    expect(engine.handle(key("g"), ctx({ now: 0 }))).toBe("pending");
    engine.handle(key("l"), ctx({ now: 500 }));
    expect(ran).toEqual(["log"]);
    expect(engine.handle(key("g"), ctx({ now: 1000 }))).toBe("pending");
    engine.handle(key("t"), ctx({ now: 1000 + CHORD_WINDOW_MS + 1 }));
    expect(ran).toEqual(["log", "plain-t"]);
  });

  it("ignores keys while typing, except Ctrl/Cmd combos, Escape and whileTyping bindings", () => {
    engine.register("shell", [b("/", "slash"), b("mod+k", "palette"), b("escape", "esc"), b("x", "x", { whileTyping: true })], true);
    expect(engine.handle(key("/"), ctx({ typing: true }))).toBeNull();
    engine.handle(key("k", { metaKey: true }), ctx({ typing: true }));
    engine.handle(key("Escape"), ctx({ typing: true }));
    engine.handle(key("x"), ctx({ typing: true }));
    expect(ran).toEqual(["palette", "esc", "x"]);
  });

  it("is quiet while a dialog is open, except inDialog bindings", () => {
    engine.register("shell", [b("?", "help"), b("mod+s", "save", { inDialog: true })], true);
    expect(engine.handle(key("?", { shiftKey: true }), ctx({ dialogOpen: true }))).toBeNull();
    engine.handle(key("s", { ctrlKey: true }), ctx({ dialogOpen: true }));
    expect(ran).toEqual(["save"]);
  });

  it("lists live bindings by group for the ? dialog, hidden ones left out", () => {
    engine.register("shell", [b("?", "Keyboard shortcuts"), { ...b("1", "Go to a section", { display: "1-5" }), group: "Navigation" }, { ...b("2", "Recipes", { hidden: true }), group: "Navigation" }], true);
    engine.register("brew", [{ ...b("space", "Play or pause"), group: "Brewing" }], true);
    const g = groupedLive(engine.live());
    expect(g.map((x) => x.group)).toEqual(["General", "Navigation", "Brewing"]);
    expect(g[1]!.bindings.map((x) => x.display)).toEqual(["1-5"]);
  });

  it("unregister removes a scope and notifies", () => {
    const fn = vi.fn();
    engine.subscribe(fn);
    const id = engine.register("a", [b("a", "a")], true);
    engine.unregister(id);
    expect(engine.handle(key("a"), ctx())).toBeNull();
    expect(fn).toHaveBeenCalledTimes(2);
  });
});

describe("key labels", () => {
  it("prints by platform", () => {
    expect(formatKeys("mod+k", false)).toBe("Ctrl K");
    expect(formatKeys("mod+k", true)).toBe("⌘K");
    expect(formatKeys("g t", false)).toBe("G then T");
    expect(formatKeys("1-5", false)).toBe("1-5");
    expect(formatKeys("escape", false)).toBe("Esc");
  });
  it("aria-keyshortcuts names both Ctrl and Cmd for mod, nothing for chords", () => {
    expect(ariaKeyShortcuts("mod+k")).toBe("Control+K Meta+K");
    expect(ariaKeyShortcuts("space")).toBe("Space");
    expect(ariaKeyShortcuts("[")).toBe("[");
    expect(ariaKeyShortcuts("g t")).toBeUndefined();
  });
});
