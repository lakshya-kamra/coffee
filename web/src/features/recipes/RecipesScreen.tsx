/**
 * Port of RecipeListScreen.kt + A§7.8 (web-spec §11 Recipes), adaptive in v4 (§3.2): header, "+"
 * for a new recipe, search, filter chips (All, Mine, My brewers, then the brewers present), a sort,
 * and the recipes. Compact: cards in a column. Medium: a card grid. Expanded and up: list-detail
 * through the shell's ListDetail, with compact rows on the left (a roving list: J/K, Enter) and the
 * recipe on the right; nothing selected shows the next brew.
 *
 * The query field updates synchronously; the list follows through a deferred value, and chip
 * changes run in startTransition (rule 12). "My brewers" is derived on every render (§4.13).
 */
import { startTransition, useCallback, useDeferredValue, useLayoutEffect, useMemo, useRef, useState, type MouseEvent } from "react";
import { ListDetail, usePaneMode, useSelectedPath, useSelectInPane } from "@/app/panes";
import { prefetchers } from "@/app/registry";
import { nav, router, useRouter } from "@/app/router";
import shell from "@/app/shell.module.css";
import { openPalette } from "@/app/shellUi";
import { useWindowClass } from "@/app/windowClass";
import { showToast } from "@/app/toast";
import { useShortcuts, type Binding } from "@/app/shortcuts";
import { BrewerIcon, EmptyArt } from "@/art";
import { Brewers, catalog } from "@/catalog/catalog";
import { repo } from "@/data/repo";
import { useBrews, useGrinders, useRecipes, useSettings } from "@/data/store";
import { ConfirmSheet, Icon, IconWell, QuietButton, ScreenHeader, SecondaryButton, SelectableChip, toCaps, tooltipProps, useContextMenu, useRovingList, type MenuItem, type RovingItemProps } from "@/design/components";
import { brewerShortName } from "@/domain/core/brewer";
import { firstBrew } from "@/domain/core/firstBrew";
import { formatDuration, grams } from "@/domain/core/format";
import { accentCss, totalSec, type Recipe } from "@/domain/core/recipe";
import { brewRecipe, useBrewNext } from "@/features/today/brewNext";
import { noRecipeLine } from "@/features/today/firstBrewCard";
import { nextRecipe, RECENT_WINDOW } from "@/features/today/homeModel";
import { RecipeCard } from "@/viz";
import { brewerPlainName } from "./needs";
import { RecipeDetailScreen } from "./RecipeDetailScreen";
import { RECIPE_SORTS, brewersIn, filterRecipes, headerEyebrow, isMine, myBrewersOn, ownedFamilies, recipeStats, sortRecipes, type RecipeSort } from "./recipeFilters";
import r from "./recipes.module.css";

const warm = () => void prefetchers.recipe().catch(() => undefined);
const pathFor = (id: string) => `/recipes/${encodeURIComponent(id)}`;
/** Built-ins (and your edits of them) are the library's; only your own recipes can be deleted. */
const isOwn = (recipe: Recipe) => catalog.recipe(recipe.id) == null;
/** Where chips wrap, this many brewer chips show before "N more". */
const BREWER_CHIPS = 6;

// Material Symbols Rounded "Search" (Apache-2.0, web/NOTICE).
const SEARCH = "M380-320q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l224 224q11 11 11 28t-11 28q-11 11-28 11t-28-11L532-372q-30 24-69 38t-83 14Zm0-80q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z";

/** The recipe Today offers next: the pane's default detail and what B brews with nothing chosen. */
function useNextRecipe(): Recipe | null {
  const all = useRecipes();
  const brews = useBrews();
  const settings = useSettings();
  const grinders = useGrinders();
  const logs = useMemo(() => brews.slice(0, RECENT_WINDOW), [brews]);
  // No brews yet: the same first-brew pick Today offers (v4 §4.6), so B means one thing everywhere.
  return useMemo(
    () => (logs.length === 0 ? firstBrew(all, settings, grinders, (id) => Brewers.byId(id)).recipe : null) ?? nextRecipe(all, logs, settings, (id) => Brewers.byId(id)),
    [all, logs, settings, grinders],
  );
}

export function RecipesScreen() {
  const next = useNextRecipe();
  // Start brew (rail, sidebar) works even when Today was never opened; B here is the list's own.
  useBrewNext(next, "recipes-next", false);
  return (
    <ListDetail
      tab="recipes"
      listLabel="Recipes"
      list={<RecipeList next={next} />}
      defaultDetail={
        next ? (
          <RecipeDetailScreen id={next.id} from="recipes" />
        ) : (
          <div className={`column ${r.noDetail}`}>
            <h1 className="t-headline-s">No recipe yet</h1>
          </div>
        )
      }
      detailLabel="Recipe"
    />
  );
}

interface Chips {
  readonly mine: boolean;
  readonly brewerId: string | null;
  /** Only the explicit choice is kept; null = never touched, so it follows what you own. */
  readonly myChoice: boolean | null;
}

function RecipeList({ next }: { next: Recipe | null }) {
  const all = useRecipes();
  const settings = useSettings();
  const brews = useBrews();
  const grinders = useGrinders();
  const paneMode = usePaneMode();
  const compact = useWindowClass().width === "compact";
  // Chips wrap on anything but a touch phone (§2.5), and then a long brewer list is cut short.
  const [finePointer] = useState(() => typeof matchMedia === "function" && matchMedia("(pointer: fine)").matches);
  const wrapChips = !compact || finePointer;
  const selectedPath = useSelectedPath("recipes");
  const select = useSelectInPane("recipes");
  const [query, setQuery] = useState("");
  const [chips, setChips] = useState<Chips>({ mine: false, brewerId: null, myChoice: null });
  const [sort, setSort] = useState<RecipeSort>("suggested");
  const [allBrewers, setAllBrewers] = useState(false);
  const [deleting, setDeleting] = useState<Recipe | null>(null);
  const deferredQuery = useDeferredValue(query);
  const families = useMemo(() => ownedFamilies(settings.ownedBrewerIds, (id) => Brewers.byId(id)), [settings.ownedBrewerIds]);
  const myBrewers = myBrewersOn(families.size > 0, chips.myChoice);
  const stats = useMemo(() => recipeStats(brews), [brews]);
  const list = useMemo(
    () => sortRecipes(filterRecipes(all, { mine: chips.mine, brewerId: chips.brewerId, myBrewers, query: deferredQuery }, families), sort, stats, families, next?.id ?? null),
    [all, chips.mine, chips.brewerId, myBrewers, deferredQuery, families, sort, stats, next],
  );
  const brewers = useMemo(() => brewersIn(all), [all]);
  const missing = useMemo(
    () =>
      myBrewers
        ? firstBrew(all, settings, grinders, (id) => Brewers.byId(id)).noRecipeFor.flatMap((id) => {
            const b = Brewers.byId(id);
            return b ? [brewerPlainName(b)] : [];
          })
        : [],
    [myBrewers, all, settings, grinders],
  );
  const setChip = (patch: Partial<Chips>) => startTransition(() => setChips((c) => ({ ...c, ...patch })));
  const anyChip = chips.mine || myBrewers || chips.brewerId != null;
  const clearAll = () => {
    setQuery("");
    setChip({ mine: false, brewerId: null, myChoice: false });
  };
  const firstOwned = settings.ownedBrewerIds.map((id) => Brewers.byId(id)).find((b) => b != null) ?? null;

  // The recipe the detail shows: the selection, else the default (next brew).
  const shownId = selectedPath != null ? decodeURIComponent(selectedPath.slice("/recipes/".length)) : paneMode ? (next?.id ?? null) : null;
  const shownIndex = shownId != null ? list.findIndex((x) => x.id === shownId) : -1;
  const open = useCallback(
    (recipe: Recipe) => {
      if (paneMode) select(pathFor(recipe.id));
      else nav.push(pathFor(recipe.id), { from: "recipes" });
    },
    [paneMode, select],
  );
  const listRef = useRef<HTMLUListElement>(null);
  const roving = useRovingList({
    count: list.length,
    selectedIndex: shownIndex,
    onSettle: paneMode
      ? (i) => {
          const x = list[i];
          if (x) select(pathFor(x.id));
        }
      : undefined,
  });

  const actionsFor = (recipe: Recipe): MenuItem[] => [
    { label: "Brew", icon: "Play", keys: "b", onSelect: () => brewRecipe(recipe.id) },
    { label: "Duplicate", icon: "Add", keys: "d", onSelect: () => nav.push(`${pathFor(recipe.id)}/duplicate`) },
    ...(isOwn(recipe) ? [{ label: "Edit", icon: "Edit" as const, keys: "e", onSelect: () => nav.push(`${pathFor(recipe.id)}/edit`) }] : []),
    ...(isOwn(recipe) ? [{ label: "Delete", icon: "Delete" as const, keys: "delete", danger: true, onSelect: () => setDeleting(recipe) }] : []),
  ];
  const menuTarget = useRef<Recipe | null>(null);
  const menu = useContextMenu(() => (menuTarget.current ? actionsFor(menuTarget.current) : []), "Recipe actions");
  const onContext = (recipe: Recipe) => (e: MouseEvent<HTMLElement>) => {
    menuTarget.current = recipe;
    menu.onContextMenu(e);
  };

  // Keys while Recipes is in front (§2.7): only its list and its pane, never under a layer.
  const front = useRouter((s) => s.tab === "recipes" && (s.depth === 0 || (s.depth === 1 && s.stack[1]?.kind === "recipe")), "recipes-front");
  // What B/E/D/Delete act on, read when a key runs: the focused row, else the one in the detail.
  const latest = useRef({ list, all, shownId, shownIndex, next, focusIndex: roving.focusIndex });
  useLayoutEffect(() => {
    latest.current = { list, all, shownId, shownIndex, next, focusIndex: roving.focusIndex };
  });
  const bindings = useMemo<Binding[]>(() => {
    const current = (): Recipe | null => {
      const l = latest.current;
      const el = document.activeElement as HTMLElement | null;
      const idx = el?.closest?.("[data-roving-index]")?.getAttribute("data-roving-index");
      if (idx != null && listRef.current?.contains(el)) return l.list[Number(idx)] ?? null;
      return l.all.find((x) => x.id === l.shownId) ?? l.next;
    };
    const enterList = () => {
      const l = latest.current;
      l.focusIndex(l.shownIndex >= 0 ? l.shownIndex : 0);
    };
    return [
      {
        keys: "b",
        label: "Brew the selected recipe",
        group: "Lists",
        run: () => {
          const x = current();
          if (x) brewRecipe(x.id);
        },
      },
      {
        keys: "e",
        label: "Edit (your recipes)",
        group: "Lists",
        run: () => {
          const x = current();
          if (x && isOwn(x)) nav.push(`${pathFor(x.id)}/edit`);
        },
      },
      {
        keys: "d",
        label: "Duplicate",
        group: "Lists",
        run: () => {
          const x = current();
          if (x) nav.push(`${pathFor(x.id)}/duplicate`);
        },
      },
      {
        keys: "delete",
        label: "Delete (your recipes)",
        group: "Lists",
        run: () => {
          const x = current();
          if (x && isOwn(x)) setDeleting(x);
        },
      },
      { keys: "n", label: "New recipe", group: "Lists", run: () => nav.push("/recipes/new") },
      // Outside the list J/K and Down step into it; inside, the roving list handles them.
      { keys: "j", label: "Next recipe", group: "Lists", run: enterList, hidden: true },
      { keys: "k", label: "Previous recipe", group: "Lists", run: enterList, hidden: true },
      { keys: "down", label: "Into the list", group: "Lists", run: enterList, hidden: true },
    ];
  }, []);
  useShortcuts("recipes", bindings, front);

  const confirmDelete = (recipe: Recipe) => {
    // Leave the deleted recipe first, so the pane goes back to its default.
    if (router.get().tab === "recipes" && selectedPath === pathFor(recipe.id)) nav.back();
    void repo.deleteRecipe(recipe.id).then(() =>
      showToast({ text: `Deleted ${recipe.name}`, action: { label: "Undo", run: () => void repo.saveRecipe(recipe) } }),
    );
  };

  // Beside a detail pane the list column is ~380 wide: brewers go behind one menu chip so the
  // filters stay one row and the list starts near the top (as Log's Coffee/Recipe/Brewer chips).
  const brewerChosen = brewers.find((b) => b.id === chips.brewerId) ?? null;
  const brewerMenu = useContextMenu(
    (): MenuItem[] => [
      { label: "Any brewer", icon: chips.brewerId == null ? "Check" : undefined, onSelect: () => setChip({ brewerId: null }) },
      ...brewers.map((b): MenuItem => ({ label: b.label, icon: chips.brewerId === b.id ? "Check" : undefined, onSelect: () => setChip({ brewerId: b.id }) })),
    ],
    "Brewer",
  );
  const shownBrewers = wrapChips && !allBrewers ? brewers.filter((b, i) => i < BREWER_CHIPS || b.id === chips.brewerId) : brewers;
  const hiddenBrewers = brewers.length - shownBrewers.length;
  const mineEmpty = chips.mine && list.length === 0 && !all.some(isMine);
  const search = (
    <div className={r.searchBar}>
      <label className={r.search}>
        <svg width={20} height={20} viewBox="0 -960 960 960" className={r.searchIcon} aria-hidden="true" focusable="false">
          <path d={SEARCH} fill="currentColor" />
        </svg>
        <input
          type="search"
          className={`t-body-l ${r.searchInput}`}
          placeholder="Search recipes, people, brewers"
          aria-label="Search recipes, people, brewers"
          aria-keyshortcuts="/"
          data-list-search=""
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
    </div>
  );

  return (
    <div className={`column ${shell.screen} ${r.listScreen}`} data-panes={paneMode || undefined}>
      {paneMode ? (
        <div className={r.paneHead}>
          <div className={r.paneHeadText}>
            <span className="eyebrow">{headerEyebrow(all)}</span>
            <h1 className="t-headline-l">Recipes</h1>
          </div>
          <IconWell icon="Add" label="New recipe" keys="n" onClick={() => nav.push("/recipes/new")} />
        </div>
      ) : (
        <ScreenHeader
          eyebrow={headerEyebrow(all)}
          title="Recipes"
          trailing={
            <span className={r.headActions}>
              {/* The rail has its own search from medium up; touch phones get the palette here. */}
              {compact && <IconWell icon="Search" label="Search or jump" keys="mod+k" onClick={() => openPalette()} />}
              <IconWell icon="Add" label="New recipe" keys="n" onClick={() => nav.push("/recipes/new")} />
            </span>
          }
        />
      )}
      {search}
      {/* These chips sit on the bare canvas, where a surface2 pill would disappear. */}
      <div className={r.filterBar}>
      <div className={r.chips} role="group" aria-label="Filter recipes">
        <SelectableChip text="All" selected={!anyChip} onClick={() => setChip({ mine: false, brewerId: null, myChoice: false })} onCanvas />
        <SelectableChip text="Mine" selected={chips.mine} onClick={() => setChip({ mine: !chips.mine })} onCanvas />
        {families.size > 0 && <SelectableChip text="My brewers" selected={myBrewers} onClick={() => setChip({ myChoice: !myBrewers })} onCanvas />}
        {paneMode && brewers.length > 0 && (
          <button type="button" className={`t-label-l ${r.menuChip}`} aria-haspopup="menu" data-on={brewerChosen != null} onClick={(e) => brewerMenu.openFrom(e.currentTarget)}>
            {brewerChosen ? brewerChosen.label : "Brewer"} ▾
          </button>
        )}
        {!paneMode && shownBrewers.map((b) => (
          <SelectableChip
            key={b.id}
            text={b.label}
            selected={chips.brewerId === b.id}
            onClick={() => setChip({ brewerId: chips.brewerId === b.id ? null : b.id })}
            leading={<BrewerIcon brewer={b} size={16} decorative />}
            onCanvas
          />
        ))}
        {!paneMode && hiddenBrewers > 0 && <SelectableChip text={`${hiddenBrewers} more`} selected={false} onClick={() => setAllBrewers(true)} onCanvas />}
      </div>
      <div className={r.toolbar}>
        <span className={`t-body-s ${r.count}`} aria-live="polite">
          {list.length === 1 ? "1 recipe" : `${list.length} recipes`}
        </span>
        <label className={`t-label-m ${r.sortLabel}`}>
          <span className={r.sortWord}>Sort</span>
          <select className={`t-label-l ${r.sort}`} value={sort} onChange={(e) => startTransition(() => setSort(e.currentTarget.value as RecipeSort))}>
            {RECIPE_SORTS.map((o) => (
              <option key={o.id} value={o.id}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
      </div>
      </div>
      {missing.length > 0 && <p className={`t-body-s ink-mid ${r.missing}`}>{noRecipeLine(missing)}</p>}

      {mineEmpty ? (
        <div className={r.emptyMine}>
          <EmptyArt brewer={firstOwned} size={56} />
          <div className={r.emptyMineText}>
            <p className="t-body-m">Your own recipes live here.</p>
            <div className={r.emptyActions}>
              <SecondaryButton text="Make your own" icon="Add" onClick={() => nav.push("/recipes/new")} />
              <QuietButton text="Duplicate one you like" onClick={() => setChip({ mine: false })} />
            </div>
          </div>
        </div>
      ) : list.length === 0 && all.length > 0 ? (
        <div className={r.empty}>
          <p className="t-body-m ink-mid">{query.trim() ? `Nothing matches “${query.trim()}”.` : "Nothing matches that."}</p>
          <QuietButton text="Clear search and filters" className={r.clearAll} onClick={clearAll} />
        </div>
      ) : paneMode ? (
        <ul ref={listRef} className={r.rows} aria-label="Recipes">
          {list.map((recipe, i) => (
            <RecipeRow
              key={recipe.id}
              recipe={recipe}
              current={i === shownIndex}
              itemProps={roving.itemProps(i)}
              onOpen={() => open(recipe)}
              onContextMenu={onContext(recipe)}
              onMore={(el) => {
                menuTarget.current = recipe;
                menu.openFrom(el);
              }}
            />
          ))}
        </ul>
      ) : (
        <ul ref={listRef} className={r.cards} aria-label="Recipes">
          {list.map((recipe) => (
            <li key={recipe.id} onContextMenu={onContext(recipe)}>
              <RecipeCard recipe={recipe} sharedPrefix="recipes" onOpen={() => open(recipe)} onPrefetch={warm} />
            </li>
          ))}
        </ul>
      )}
      {menu.element}
      {brewerMenu.element}
      {deleting && (
        <ConfirmSheet
          title="Delete this recipe?"
          body="Brews you made with it stay in your log. You can undo it for a few seconds."
          confirmText="Delete"
          danger
          safe="cancel"
          onClose={() => setDeleting(null)}
          onConfirm={() => confirmDelete(deleting)}
        />
      )}
    </div>
  );
}

/** One compact row (~76 px): who, which brewer, the numbers; the "..." menu on hover. */
function RecipeRow({
  recipe,
  current,
  itemProps,
  onOpen,
  onContextMenu,
  onMore,
}: {
  recipe: Recipe;
  current: boolean;
  itemProps: RovingItemProps;
  onOpen: () => void;
  onContextMenu: (e: MouseEvent<HTMLElement>) => void;
  onMore: (el: HTMLElement) => void;
}) {
  return (
    <li className={`interactive-row ${r.rowItem}`} aria-current={current || undefined} onContextMenu={onContextMenu}>
      <button
        type="button"
        {...itemProps}
        className={r.row}
        aria-current={current || undefined}
        onClick={onOpen}
        onPointerDown={warm}
        onKeyDown={(e) => {
          // The menu key (or Shift+F10) opens the row's actions, as right-click does.
          if (e.key === "ContextMenu" || (e.key === "F10" && e.shiftKey)) {
            e.preventDefault();
            onMore(e.currentTarget);
            return;
          }
          itemProps.onKeyDown(e);
        }}
      >
        <span className={r.rowIcon}>
          <BrewerIcon brewer={recipe.brewer} size={22} decorative />
        </span>
        <span className={r.rowMain}>
          <span className={`t-title-m ${r.rowName}`} data-title="">
            <span className={r.rowDot} style={{ background: accentCss(recipe.accent) }} aria-hidden="true" />
            <span className={r.rowNameText}>{recipe.name}</span>
          </span>
          <span className={`micro ink-low ${r.rowMeta}`}>
            {toCaps(recipe.author)} · {toCaps(brewerShortName(recipe.brewer))}
          </span>
        </span>
        <span className={r.rowNums}>
          <span className="num-inline">
            {grams(recipe.doseG)} → {grams(recipe.waterG)} g
          </span>
          <span className="t-body-s ink-low">{formatDuration(totalSec(recipe))}</span>
        </span>
      </button>
      {/* Mouse affordance; keyboards use the menu key or B/E/D/Delete, so it takes no tab stop. */}
      <button type="button" tabIndex={-1} className={`row-more ${r.rowMore}`} aria-label={`More for ${recipe.name}`} {...tooltipProps("More")} onClick={(e) => onMore(e.currentTarget)}>
        <Icon name="MoreHoriz" size={20} />
      </button>
    </li>
  );
}
