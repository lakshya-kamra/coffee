/**
 * "Brew <recipe>" for Today's next brew (v4 §2.7, §2.8): the palette command `brew.next` (which
 * the rail's and sidebar's Start brew run) and the B key while the screen is in front. Today and
 * Recipes both register it, so Start brew works whichever tab was opened first.
 */
import { useEffect, useMemo } from "react";
import { registerCommands } from "@/app/palette/commands";
import { prefetchers } from "@/app/registry";
import { nav } from "@/app/router";
import { useShortcuts, type Binding } from "@/app/shortcuts";
import { useSettings } from "@/data/store";
import { brewerShortName } from "@/domain/core/brewer";
import { routeText } from "@/domain/core/noScale";
import type { Recipe } from "@/domain/core/recipe";

export function brewRecipe(id: string): void {
  void prefetchers.brew().catch(() => undefined);
  nav.push(`/brew/${encodeURIComponent(id)}`);
}

export function useBrewNext(next: Recipe | null, scope: string, keysEnabled: boolean): void {
  const hasScale = useSettings().hasScale;
  useEffect(() => {
    if (!next) return undefined;
    return registerCommands(() => [
      {
        id: "brew.next",
        title: `Brew ${next.name}`,
        subtitle: `${next.author} · ${brewerShortName(next.brewer)} · ${routeText(next.doseG, next.waterG, next.brewer.method, hasScale)}`,
        group: "Actions",
        icon: "Play",
        keys: "b",
        keywords: ["start brew", "brew next", "next brew", "start", "brew", "next"],
        keywordsOnly: true,
        run: () => brewRecipe(next.id),
      },
    ]);
  }, [next, hasScale]);
  const bindings = useMemo<Binding[]>(() => (next ? [{ keys: "b", label: `Brew ${next.name}`, group: "General", run: () => brewRecipe(next.id) }] : []), [next]);
  useShortcuts(scope, bindings, keysEnabled && next != null);
}
