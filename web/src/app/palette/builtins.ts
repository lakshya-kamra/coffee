/**
 * The shell's own palette commands (v4 §2.8): go to every tab and Gear section, keyboard
 * shortcuts, theme, units and the sidebar; plus the searchable data (recipes, coffees, grinders,
 * brews) read from the hot store when the palette opens. Screens add their own actions with
 * registerCommands (e.g. Today's `brew.next`).
 */
import { settings } from "@/data/settings";
import type { Snapshot } from "@/data/store";
import { grams, screenDate } from "@/domain/core/format";
import { openInTab } from "../panes";
import { nav } from "../router";
import { TAB_LABELS, TABS, type GearTab } from "../routes";
import { openShortcuts } from "../shellUi";
import { isSidebarCollapsed, toggleSidebar } from "../sidebar";
import { atLeast, windowClass } from "../windowClass";
import type { Command } from "./commands";
import { TAB_ICON } from "../navShared";

const enc = encodeURIComponent;

const GEAR: readonly { id: GearTab; title: string; keywords: string[] }[] = [
  { id: "beans", title: "Beans", keywords: ["coffees", "gear"] },
  { id: "grinders", title: "Grinders", keywords: ["gear", "grind"] },
  { id: "app", title: "Settings", keywords: ["gear", "preferences", "app", "units", "theme", "backup", "ai"] },
];

/** Built-in places and actions. Read live (theme, units and sidebar labels follow the state). */
export function shellCommands(s: Snapshot["settings"]): Command[] {
  const out: Command[] = [];
  TABS.forEach((t, i) => {
    out.push({ id: `go.${t}`, title: TAB_LABELS[t], subtitle: "Go to", group: "Go to", icon: TAB_ICON[t], keys: String(i + 1), run: () => nav.switchTab(t), popularity: i });
  });
  GEAR.forEach((g, i) => {
    out.push({ id: `go.gear.${g.id}`, title: g.title, subtitle: "Gear", group: "Go to", icon: "Tune", keywords: g.keywords, run: () => nav.switchTab("gear", g.id), popularity: 10 + i });
  });
  out.push({ id: "shell.shortcuts", title: "Keyboard shortcuts", group: "Actions", icon: "Keyboard", keys: "?", keywords: ["keys", "help", "hotkeys"], run: openShortcuts, popularity: 50 });
  const dark = typeof document !== "undefined" && document.documentElement.dataset.theme !== "light";
  out.push({
    id: "shell.theme",
    title: dark ? "Switch to light theme" : "Switch to dark theme",
    group: "Actions",
    keywords: ["theme", "dark", "light", "appearance", "mode"],
    run: () => void settings.set("darkMode", dark ? "light" : "dark"),
    popularity: 60,
  });
  out.push({
    id: "shell.temp",
    title: s.tempUnit === "f" ? "Use °C" : "Use °F",
    group: "Actions",
    keywords: ["temperature", "celsius", "fahrenheit", "units"],
    run: () => void settings.set("tempUnit", s.tempUnit === "f" ? "c" : "f"),
    popularity: 61,
  });
  out.push({
    id: "shell.weight",
    title: s.weightUnit === "oz" ? "Use grams" : "Use ounces",
    group: "Actions",
    keywords: ["weight", "grams", "ounces", "units"],
    run: () => void settings.set("weightUnit", s.weightUnit === "oz" ? "g" : "oz"),
    popularity: 62,
  });
  if (atLeast(windowClass.get().width, "large")) {
    out.push({ id: "shell.sidebar", title: isSidebarCollapsed() ? "Expand sidebar" : "Collapse sidebar", group: "Actions", icon: "ViewSidebar", keys: "[", run: toggleSidebar, popularity: 63 });
  }
  return out;
}

/** Recipes, coffees, grinders and brews from the store, searchable by name and details. */
export function dataCommands(s: Snapshot): Command[] {
  const out: Command[] = [];
  s.recipes.forEach((r, i) => {
    out.push({
      id: `recipe:${r.id}`,
      title: r.name,
      subtitle: `${r.author} · ${r.brewer.label} · ${grams(r.doseG)} g → ${grams(r.waterG)} g`,
      group: "Recipes",
      icon: "MenuBook",
      keywords: [r.author, r.brewer.label, r.brewer.brand],
      popularity: i,
      run: () => openInTab("recipes", `/recipes/${enc(r.id)}`),
      secondary: { label: "Brew", run: () => nav.push(`/brew/${enc(r.id)}`) },
    });
  });
  s.beans
    .filter((b) => !b.isArchived)
    .forEach((b, i) => {
      out.push({
        id: `bean:${b.uid}`,
        title: b.name || "Unnamed coffee",
        subtitle: [b.roaster, b.origin].filter(Boolean).join(" · "),
        group: "Coffees",
        icon: "Coffee",
        keywords: [b.roaster, b.origin],
        popularity: i,
        run: () => nav.switchTab("gear", "beans"),
      });
    });
  s.grinders
    .filter((g) => !g.isArchived)
    .forEach((g, i) => {
      out.push({ id: `grinder:${g.uid}`, title: `${g.brand} ${g.model}`.trim() || "Grinder", group: "Grinders", icon: "Tune", popularity: i, run: () => nav.switchTab("gear", "grinders") });
    });
  // Newest first; the most recent few hundred is plenty to jump to.
  s.brews.slice(0, 300).forEach((b, i) => {
    out.push({
      id: `brew:${b.uid}`,
      title: b.recipeName || "Brew",
      subtitle: [screenDate(b.startedAt), b.beanLabel, b.overall > 0 ? `${b.overall}/10` : ""].filter(Boolean).join(" · "),
      group: "Brews",
      icon: "History",
      keywords: [b.beanLabel, b.grinderLabel, b.brewer, b.notes],
      popularity: i,
      run: () => openInTab("log", `/log/${enc(b.uid)}`),
    });
  });
  return out;
}
