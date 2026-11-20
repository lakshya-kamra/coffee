/**
 * Key names for display (v4 §2.6, §2.7): one spelling for bindings ("mod+k", "g t", "shift+/")
 * shown as "Ctrl K" / "⌘K" by platform. `mod` is Cmd on Apple platforms and Ctrl elsewhere.
 */

let macCache: boolean | null = null;

export function isApplePlatform(): boolean {
  if (macCache != null) return macCache;
  if (typeof navigator === "undefined") return (macCache = false);
  const uaData = (navigator as Navigator & { userAgentData?: { platform?: string } }).userAgentData;
  const p = uaData?.platform || navigator.platform || "";
  macCache = /mac|iphone|ipad|ipod/i.test(p);
  return macCache;
}

/** Test hook. */
export function setApplePlatformForTests(v: boolean | null): void {
  macCache = v;
}

const NAMES: Record<string, string> = {
  escape: "Esc",
  esc: "Esc",
  enter: "Enter",
  space: "Space",
  " ": "Space",
  arrowup: "↑",
  up: "↑",
  arrowdown: "↓",
  down: "↓",
  arrowleft: "←",
  left: "←",
  arrowright: "→",
  right: "→",
  delete: "Delete",
  backspace: "Backspace",
  home: "Home",
  end: "End",
  tab: "Tab",
};

function keyName(k: string): string {
  const n = NAMES[k.toLowerCase()];
  if (n) return n;
  return k.length === 1 ? k.toUpperCase() : k.charAt(0).toUpperCase() + k.slice(1);
}

/** The parts of one step, for <Kbd>: "mod+k" -> ["Ctrl", "K"] or ["⌘", "K"]. */
export function keyParts(step: string, apple = isApplePlatform()): string[] {
  const tokens = step === "+" ? ["+"] : step.split("+").filter((t) => t !== "");
  return tokens.map((t) => {
    switch (t.toLowerCase()) {
      case "mod":
        return apple ? "⌘" : "Ctrl";
      case "ctrl":
        return apple ? "⌃" : "Ctrl";
      case "meta":
      case "cmd":
        return apple ? "⌘" : "Win";
      case "alt":
      case "option":
        return apple ? "⌥" : "Alt";
      case "shift":
        return apple ? "⇧" : "Shift";
      default:
        return keyName(t);
    }
  });
}

/**
 * "mod+k" -> "Ctrl K" (or "⌘K"); "g t" -> "G then T"; "1-5" stays. Apple glyphs join without a
 * space, as macOS menus print them.
 */
export function formatKeys(keys: string, apple = isApplePlatform()): string {
  if (/^\d-\d$/.test(keys)) return keys;
  const steps = keys.trim().split(/\s+/);
  const one = (s: string) => {
    const parts = keyParts(s, apple);
    return apple ? parts.join("") : parts.join(" ");
  };
  return steps.map(one).join(" then ");
}

/** The aria-keyshortcuts value ("Control+K Meta+K" for mod), so the name stays clean (A10). */
export function ariaKeyShortcuts(keys: string): string | undefined {
  if (/\s/.test(keys.trim()) || /^\d-\d$/.test(keys)) return undefined; // chords and ranges have no ARIA form
  const tokens = keys === "+" ? ["+"] : keys.split("+");
  const key = tokens[tokens.length - 1] ?? "";
  const mods = tokens.slice(0, -1).map((t) => t.toLowerCase());
  const name = key.length === 1 ? key.toUpperCase() : keyName(key) === "Esc" ? "Escape" : keyName(key);
  const build = (m: string[]) => [...m, name].join("+");
  const base = mods.filter((m) => m !== "mod").map((m) => ({ ctrl: "Control", meta: "Meta", cmd: "Meta", alt: "Alt", option: "Alt", shift: "Shift" })[m] ?? m);
  if (mods.includes("mod")) return `${build(["Control", ...base])} ${build(["Meta", ...base])}`;
  return build(base);
}
