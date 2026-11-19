/**
 * Port of RecipeListScreen.kt + A§7.8 (web-spec §11 Recipes): header eyebrow, "+" for a new
 * recipe, search, filter chips (All, Mine, My brewers, then the brewers present) and the cards.
 * The query field updates synchronously; the list follows through a deferred value, and chip
 * changes run in startTransition (rule 12).
 */
import { startTransition, useDeferredValue, useMemo, useState } from "react";
import { prefetchers } from "@/app/registry";
import { nav } from "@/app/router";
import shell from "@/app/shell.module.css";
import { BrewerIcon } from "@/art";
import { Brewers } from "@/catalog/catalog";
import { useRecipes, useSettings } from "@/data/store";
import { Icon, IconWell, QuietButton, ScreenHeader, SelectableChip } from "@/design/components";
import { RecipeCard } from "@/viz";
import { brewersIn, filterRecipes, headerEyebrow, isMine, ownedFamilies, type RecipeFilter } from "./recipeFilters";
import r from "./recipes.module.css";

const warm = () => void prefetchers.recipe().catch(() => undefined);

// Material Symbols Rounded "Search" (Apache-2.0, web/NOTICE): not in the design icon set.
const SEARCH = "M380-320q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l224 224q11 11 11 28t-11 28q-11 11-28 11t-28-11L532-372q-30 24-69 38t-83 14Zm0-80q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z";

export function RecipesScreen() {
  const all = useRecipes();
  const settings = useSettings();
  const [query, setQuery] = useState("");
  // "My brewers" starts on when the person has said which brewers they own (A§7.8).
  const [chips, setChips] = useState<Omit<RecipeFilter, "query">>(() => ({ mine: false, myBrewers: settings.ownedBrewerIds.length > 0, brewerId: null }));
  const deferredQuery = useDeferredValue(query);
  const families = useMemo(() => ownedFamilies(settings.ownedBrewerIds, (id) => Brewers.byId(id)), [settings.ownedBrewerIds]);
  const list = useMemo(() => filterRecipes(all, { ...chips, query: deferredQuery }, families), [all, chips, deferredQuery, families]);
  const brewers = useMemo(() => brewersIn(all), [all]);
  const hasMine = useMemo(() => all.some(isMine), [all]);
  const setChip = (patch: Partial<typeof chips>) => startTransition(() => setChips((c) => ({ ...c, ...patch })));
  const anyChip = chips.mine || chips.myBrewers || chips.brewerId != null;

  return (
    <div className={`column ${shell.screen}`}>
      <ScreenHeader
        eyebrow={headerEyebrow(all)}
        title="Recipes"
        trailing={<IconWell icon="Add" label="New recipe" onClick={() => nav.push("/recipes/new")} />}
      />
      <label className={r.search}>
        <svg width={20} height={20} viewBox="0 -960 960 960" className={r.searchIcon} aria-hidden="true" focusable="false">
          <path d={SEARCH} fill="currentColor" />
        </svg>
        <input
          type="search"
          className={`t-body-l ${r.searchInput}`}
          placeholder="Search recipes or people"
          aria-label="Search recipes or people"
          value={query}
          onChange={(e) => setQuery(e.currentTarget.value)}
          enterKeyHint="search"
          autoComplete="off"
        />
        {query && (
          <button type="button" className={r.clear} onClick={() => setQuery("")} aria-label="Clear search">
            <Icon name="Close" size={16} />
          </button>
        )}
      </label>
      {/* These chips sit on the bare canvas, where a surface2 pill would disappear. */}
      <div className={r.chips} role="group" aria-label="Filter recipes">
        <SelectableChip text="All" selected={!anyChip} onClick={() => setChip({ mine: false, myBrewers: false, brewerId: null })} onCanvas />
        {hasMine && <SelectableChip text="Mine" selected={chips.mine} onClick={() => setChip({ mine: !chips.mine })} onCanvas />}
        {families.size > 0 && <SelectableChip text="My brewers" selected={chips.myBrewers} onClick={() => setChip({ myBrewers: !chips.myBrewers })} onCanvas />}
        {brewers.map((b) => (
          <SelectableChip
            key={b.id}
            text={b.label}
            selected={chips.brewerId === b.id}
            onClick={() => setChip({ brewerId: chips.brewerId === b.id ? null : b.id })}
            leading={<BrewerIcon brewer={b} size={16} decorative />}
            onCanvas
          />
        ))}
      </div>
      {list.length === 0 && all.length > 0 ? (
        <div className={r.empty}>
          <p className="t-body-m ink-mid">{query.trim() ? `Nothing matches “${query.trim()}”.` : "Nothing matches that."}</p>
          <QuietButton
            text="Clear search"
            className={r.clearAll}
            onClick={() => {
              setQuery("");
              setChip({ mine: false, myBrewers: false, brewerId: null });
            }}
          />
        </div>
      ) : (
        <ul className={r.list} aria-label="Recipes">
          {list.map((recipe) => (
            <li key={recipe.id}>
              <RecipeCard recipe={recipe} sharedPrefix="recipes" onOpen={() => nav.push(`/recipes/${encodeURIComponent(recipe.id)}`, { from: "recipes" })} onPrefetch={warm} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
