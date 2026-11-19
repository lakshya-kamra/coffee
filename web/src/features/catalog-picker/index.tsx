/**
 * The catalog picker (A§7.2), reused for grinders, roasters, origins, regions, varietals,
 * brewers and the bean vocabulary. A sheet with a pinned search, sticky group headers, a
 * "Use “x”" custom entry and optional multi-select. `CatalogPickerList` is the same content
 * without the sheet, for onboarding's inline grinder step.
 *
 * Input stays urgent (rule 12): the field updates on every keystroke; the query used for
 * results is debounced 80 ms and read through useDeferredValue.
 */
import { memo, useDeferredValue, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { retryCatalog, useCatalog, useCatalogFailed } from "@/catalog/useCatalog";
import { CoffeeSheet, Icon, PillButton } from "@/design/components";
import { useSettings } from "@/data/store";
import { docsFor, KIND_FILE, search, type CatalogKind, type SearchDoc } from "@/domain/search";
import s from "./picker.module.css";

export type { CatalogKind };

export type CatalogPick = { id: string } | { custom: string };

/** Kinds with long lists: the search field takes focus as the sheet opens (A§7.2). */
const LONG: ReadonlySet<CatalogKind> = new Set<CatalogKind>(["grinder", "roaster", "origin", "varietal", "region"]);
/** Kinds with lists long enough to hold the sheet at full height. */
const TALL: ReadonlySet<CatalogKind> = new Set<CatalogKind>(["grinder", "roaster", "origin", "varietal", "region", "brewer", "process", "water"]);

const PLACEHOLDER: Readonly<Record<CatalogKind, string>> = {
  grinder: "Brand or model, like K-Ultra",
  roaster: "Roaster or city",
  origin: "Country",
  region: "Region or country",
  varietal: "Varietal, like Gesha",
  process: "Process",
  brewer: "Brewer, like V60",
  water: "Water",
  lotType: "Lot type",
  decaf: "Decaf method",
  roastLevel: "Roast level",
};

/** Closes the CoffeeSheet around `el` the same way Escape does, so it slides away (not cut). */
export function closeEnclosingSheet(el: Element | null): void {
  el?.closest("dialog")?.dispatchEvent(new Event("cancel", { cancelable: true }));
}

interface Group {
  readonly name: string;
  readonly docs: readonly SearchDoc[];
}

/**
 * Groups for the empty query, with the picker's ordering rules: roasters put your favourites at
 * the very top and your area first; everything else keeps the catalog's group order.
 */
function groupsFor(kind: CatalogKind, docs: readonly SearchDoc[], homeArea: string, favourites: readonly string[], owned: readonly string[]): Group[] {
  const byName = new Map<string, SearchDoc[]>();
  for (const d of docs) {
    let g = byName.get(d.group);
    if (!g) byName.set(d.group, (g = []));
    g.push(d);
  }
  let groups: Group[] = [...byName].map(([name, list]) => ({ name, docs: list }));
  if (kind === "roaster" || kind === "origin") {
    if (homeArea) groups = [...groups.filter((g) => g.name === homeArea), ...groups.filter((g) => g.name !== homeArea)];
  }
  if (kind === "roaster" && favourites.length) {
    const fav = new Set(favourites);
    const top = docs.filter((d) => fav.has(d.id));
    if (top.length) groups = [{ name: "Your roasters", docs: top }, ...groups];
  }
  if (kind === "brewer" && owned.length) {
    // Owned brewers first (A§7.7), still listed in their category below.
    const mine = new Set(owned);
    const top = docs.filter((d) => mine.has(d.id));
    if (top.length) groups = [{ name: "Yours", docs: top }, ...groups];
  }
  return groups;
}

const Row = memo(function Row({ doc, selected, multi, onTap }: { doc: SearchDoc; selected: boolean; multi: boolean; onTap: (id: string) => void }) {
  return (
    <li>
      <button
        type="button"
        className={s.row}
        aria-pressed={multi ? selected : undefined}
        aria-current={!multi && selected ? "true" : undefined}
        onClick={() => onTap(doc.id)}
      >
        <span className={s.rowText}>
          <span className={`t-body-l ${s.rowTitle}`}>{doc.title}</span>
          {doc.subtitle && <span className={`t-body-s ${s.rowSub}`}>{doc.subtitle}</span>}
        </span>
        {selected && <Icon name="Check" size={22} className={s.check} />}
      </button>
    </li>
  );
});

/** The picker's content: search field, results, custom entry. Used inline and inside the sheet. */
export function CatalogPickerList({
  kind,
  title,
  multiSelect = false,
  selected = [],
  allowCustom = true,
  customLabel = (q: string) => `Use “${q}”`,
  onPick,
  onToggle,
  trailing,
  focusSearch,
}: {
  kind: CatalogKind;
  title?: string;
  multiSelect?: boolean;
  selected?: readonly string[];
  allowCustom?: boolean;
  customLabel?: (q: string) => string;
  onPick: (pick: CatalogPick) => void;
  /** Multi-select: called on every tap with the new set. */
  onToggle?: (ids: string[]) => void;
  /** Header action (the sheet's "Done"). */
  trailing?: ReactNode;
  /** Focus the search field on open; defaults to on for long lists (A§7.2). */
  focusSearch?: boolean;
}) {
  const ready = useCatalog([KIND_FILE[kind]]);
  const failed = useCatalogFailed([KIND_FILE[kind]]);
  const settings = useSettings();
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const deferred = useDeferredValue(query);
  const root = useRef<HTMLDivElement>(null);
  const head = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);

  // 80 ms debounce (A§7.2): typing stays instant, results follow when you pause.
  useEffect(() => {
    if (text === query) return;
    const t = setTimeout(() => setQuery(text), 80);
    return () => clearTimeout(t);
  }, [text, query]);

  // Group headers stick under the search field, whatever height it wraps to.
  useLayoutEffect(() => {
    const h = head.current;
    const r = root.current;
    if (!h || !r || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(([e]) => {
      if (e) r.style.setProperty("--head-h", `${Math.round(e.borderBoxSize?.[0]?.blockSize ?? h.offsetHeight)}px`);
    });
    ro.observe(h);
    return () => ro.disconnect();
  }, []);

  useEffect(() => {
    if (focusSearch ?? LONG.has(kind)) input.current?.focus({ preventScroll: true });
  }, [focusSearch, kind]);

  // `ready` is a dependency so the docs rebuild once the file lands.
  const docs = useMemo(() => (ready ? docsFor(kind) : []), [kind, ready]);
  const selectedSet = useMemo(() => new Set(selected), [selected]);
  const q = deferred.trim();
  const hits = useMemo(() => (q ? search(docs, q, 60).map((h) => h.doc) : null), [docs, q]);
  const groups = useMemo(
    () => (hits ? null : groupsFor(kind, docs, settings.homeArea, settings.favouriteRoasterIds, multiSelect ? [] : settings.ownedBrewerIds)),
    [hits, kind, docs, settings.homeArea, settings.favouriteRoasterIds, settings.ownedBrewerIds, multiSelect],
  );

  const onTap = (id: string) => {
    if (multiSelect) {
      const next = selectedSet.has(id) ? selected.filter((x) => x !== id) : [...selected, id];
      onToggle?.(next);
    } else {
      onPick({ id });
    }
  };

  const typed = text.trim();
  const exact = hits?.some((d) => d.title.toLowerCase() === typed.toLowerCase()) ?? false;

  return (
    <div ref={root} className={s.picker}>
      <div ref={head} className={s.head}>
        {(title || trailing) && (
          <div className={s.titleRow}>
            {title && <h2 className="t-headline-s">{title}</h2>}
            {trailing}
          </div>
        )}
        <div className={s.search}>
          <input
            ref={input}
            className={`${s.searchInput} t-body-l`}
            type="search"
            enterKeyHint="search"
            autoComplete="off"
            spellCheck={false}
            value={text}
            placeholder={PLACEHOLDER[kind]}
            aria-label={`Search ${title ?? PLACEHOLDER[kind]}`}
            onChange={(e) => setText(e.currentTarget.value)}
            onKeyDown={(e) => {
              // Enter takes the best match, or the typed text as your own entry.
              if (e.key !== "Enter") return;
              e.preventDefault();
              const first = hits?.[0];
              if (first && !multiSelect) onPick({ id: first.id });
              else if (!first && typed && allowCustom) onPick({ custom: typed });
            }}
          />
          {text && (
            <button
              type="button"
              className={s.clear}
              aria-label="Clear search"
              onClick={() => {
                setText("");
                setQuery("");
                input.current?.focus();
              }}
            >
              <Icon name="Close" size={20} />
            </button>
          )}
        </div>
      </div>

      {!ready && !failed && <p className={`t-body-m ${s.empty}`}>Loading the list…</p>}
      {!ready && failed && (
        // Not an empty list: the file never arrived. Say so, and offer another go.
        <div className={`${s.empty} ${s.failed}`} role="status">
          <p className="t-body-m">Couldn't load the list. Check your connection.</p>
          <PillButton text="Try again" onClick={() => retryCatalog([KIND_FILE[kind]])} />
        </div>
      )}

      {ready && groups &&
        groups.map((g) => (
          <section key={g.name} className={s.group} aria-label={g.name}>
            <h3 className={`eyebrow ${s.groupHead}`}>{g.name}</h3>
            <ul>
              {g.docs.map((d) => (
                <Row key={d.id} doc={d} selected={selectedSet.has(d.id)} multi={multiSelect} onTap={onTap} />
              ))}
            </ul>
          </section>
        ))}

      {ready && hits && (
        <>
          {hits.length === 0 && (
            <p className={`t-body-m ${s.empty}`} role="status">
              {allowCustom ? `Nothing called “${typed}”. Use it as your own entry.` : `Nothing called “${typed}”.`}
            </p>
          )}
          <ul aria-label="Matches">
            {hits.map((d) => (
              <Row key={d.id} doc={d} selected={selectedSet.has(d.id)} multi={multiSelect} onTap={onTap} />
            ))}
            {allowCustom && typed && !exact && (
              <li>
                <button type="button" className={`${s.row} ${s.custom}`} onClick={() => onPick({ custom: typed })}>
                  <span className={s.rowText}>
                    <span className={`t-body-l ${s.rowTitle}`}>{customLabel(typed)}</span>
                    <span className={`t-body-s ${s.rowSub}`}>Not on the list. Save it as you typed it.</span>
                  </span>
                </button>
              </li>
            )}
          </ul>
        </>
      )}
    </div>
  );
}

/**
 * The picker in a CoffeeSheet (web-spec §20.4 contract). Single-select picks and closes;
 * multi-select collects taps and hands them over when the sheet closes, however it closes:
 * "Done", system Back, Escape, the scrim or a drag. Ticks are work; Back must not throw them
 * away. `onDone` then `onClose` run once the sheet has slid away.
 */
export function CatalogPickerSheet({
  kind,
  title,
  multiSelect = false,
  selected = [],
  allowCustom = true,
  onPick,
  onDone,
  onClose,
}: {
  kind: CatalogKind;
  title: string;
  multiSelect?: boolean;
  selected?: string[];
  allowCustom?: boolean;
  // Property syntax (same shape as the §20.4 contract's methods) so destructuring is safe.
  onPick: (pick: { id: string } | { custom: string }) => void;
  onDone?: (ids: string[]) => void;
  onClose: () => void;
}) {
  const [picked, setPicked] = useState<string[]>(selected);
  const box = useRef<HTMLDivElement>(null);
  // Always `full`, so system Back closes it, including over an editor sheet: every CoffeeSheet
  // tags its own history entry. Full height also keeps it from resizing as results narrow.
  return (
    <CoffeeSheet
      label={title}
      onClose={() => {
        if (multiSelect) onDone?.(picked);
        onClose();
      }}
      full
    >
      <div ref={box} className={TALL.has(kind) ? s.tall : undefined}>
        <CatalogPickerList
          kind={kind}
          title={title}
          multiSelect={multiSelect}
          selected={multiSelect ? picked : selected}
          allowCustom={allowCustom && !multiSelect}
          onToggle={setPicked}
          onPick={(p) => {
            onPick(p);
            closeEnclosingSheet(box.current);
          }}
          trailing={
            multiSelect ? (
              <PillButton text={picked.length ? `Done · ${picked.length}` : "Done"} onClick={() => closeEnclosingSheet(box.current)} />
            ) : undefined
          }
        />
      </div>
    </CoffeeSheet>
  );
}
