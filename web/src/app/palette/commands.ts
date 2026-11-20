/**
 * Command palette registry (v4 §2.8): the Command type, registerCommands, recents. Small and
 * search-free on purpose: the rail's Start brew needs it at first paint, while the ranking
 * (rank.ts), the built-ins and the data commands (builtins.ts) load with the palette. Everything
 * is local; nothing here touches the network.
 */
import type { IconName } from "@/design/icons";
import { readDeviceKey, writeDeviceKey } from "../prefsMirror";

export type CommandGroup = "Actions" | "Recipes" | "Coffees" | "Grinders" | "Brews" | "Go to";

export interface Command {
  /** Stable id: "brew.next", "recipe:hoffmann-v60", "go.log". Recents are stored by id. */
  readonly id: string;
  readonly title: string;
  /** Dim second line ("James Hoffmann · V60 · 30 g → 500 g"). */
  readonly subtitle?: string;
  readonly group: CommandGroup;
  readonly icon?: IconName;
  /** Shortcut hint printed on the right ("mod+k", "?", "1"). */
  readonly keys?: string;
  /** Extra words that should find it ("dark", "fahrenheit"). */
  readonly keywords?: readonly string[];
  /**
   * Found by its keywords only, not its title or subtitle. For an action that names a recipe
   * ("Brew Hoffmann V60"): typing the recipe's name should open the recipe, with Ctrl/Cmd+Enter
   * to brew it, rather than the Actions group (ranked first) starting a brew on Enter.
   */
  readonly keywordsOnly?: boolean;
  /** Lower ranks first among equal matches (1 = most used). */
  readonly popularity?: number;
  readonly run: () => void;
  /** Ctrl/Cmd+Enter (recipes: brew instead of open). */
  readonly secondary?: { readonly label: string; readonly run: () => void };
}

/** A command source is read every time the palette opens or a command id is looked up. */
export type CommandSource = () => readonly Command[];

const sources = new Set<CommandSource>();

/**
 * Adds commands for as long as the returned function is not called. Screens register from an
 * effect: `useEffect(() => registerCommands(() => [...]), [deps])`.
 */
export function registerCommands(source: CommandSource | readonly Command[]): () => void {
  const fn: CommandSource = typeof source === "function" ? source : () => source;
  sources.add(fn);
  return () => {
    sources.delete(fn);
  };
}

/** Every registered command (shell built-ins register through the same call). */
export function registeredCommands(): Command[] {
  const out: Command[] = [];
  const seen = new Set<string>();
  // Newest source wins on a duplicate id.
  for (const s of [...sources].reverse()) {
    for (const c of s()) {
      if (seen.has(c.id)) continue;
      seen.add(c.id);
      out.push(c);
    }
  }
  return out;
}

export function findCommand(id: string): Command | null {
  return registeredCommands().find((c) => c.id === id) ?? null;
}

/** Runs a command by id; false when none is registered (Start brew falls back to Recipes). */
export function runCommand(id: string): boolean {
  const c = findCommand(id);
  if (!c) return false;
  c.run();
  return true;
}

/** Test hook. */
export function clearCommandsForTests(): void {
  sources.clear();
}

// ---------------------------------------------------------------- recents

const RECENT_MAX = 5;

export function readRecent(): string[] {
  const raw = readDeviceKey("coffee.paletteRecent");
  if (!raw) return [];
  try {
    const v = JSON.parse(raw) as unknown;
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string").slice(0, RECENT_MAX) : [];
  } catch {
    return [];
  }
}

export function pushRecent(list: readonly string[], id: string): string[] {
  return [id, ...list.filter((x) => x !== id)].slice(0, RECENT_MAX);
}

export function rememberRun(id: string): void {
  writeDeviceKey("coffee.paletteRecent", JSON.stringify(pushRecent(readRecent(), id)));
}
