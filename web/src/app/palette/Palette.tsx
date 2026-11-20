/**
 * The command palette (v4 §2.8). A content dialog 640 wide near the top at expanded+ (a
 * full-height sheet with the field on top on compact). One text field drives a listbox: Up/Down
 * move, Enter runs, Ctrl/Cmd+Enter runs the secondary action (recipes: brew instead of open),
 * Escape closes. The chosen command runs after the dialog has closed, so focus can land on the
 * screen it opens. Loaded lazily the first time it opens.
 */
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { getSnapshot } from "@/data/store";
import { CoffeeSheet, useSheetClose } from "@/design/components/Sheet";
import { Icon } from "@/design/components/Icon";
import { Kbd } from "@/design/components/Kbd";
import { useWindowClass } from "../windowClass";
import { dataCommands, shellCommands } from "./builtins";
import { readRecent, registeredCommands, rememberRun, type Command } from "./commands";
import { flatten, rankCommands } from "./rank";
import p from "./palette.module.css";

export const PLACEHOLDER = "Search recipes, coffees, brews, or type a command";

export default function Palette({ initialQuery, onClose }: { initialQuery: string; onClose: () => void }) {
  const compact = useWindowClass().width === "compact";
  const pending = useRef<(() => void) | null>(null);
  return (
    <CoffeeSheet
      role="content"
      label="Search or jump"
      full={compact}
      align="top"
      onClose={() => {
        onClose();
        const run = pending.current;
        pending.current = null;
        run?.();
      }}
    >
      <PaletteBody initialQuery={initialQuery} onPick={(run) => (pending.current = run)} />
    </CoffeeSheet>
  );
}

function PaletteBody({ initialQuery, onPick }: { initialQuery: string; onPick: (run: () => void) => void }) {
  const close = useSheetClose();
  const [query, setQuery] = useState(initialQuery);
  const [active, setActive] = useState(0);
  // Read once when it opens: the palette is a snapshot, not a live view.
  const [all] = useState<Command[]>(() => {
    const snap = getSnapshot();
    const registered = registeredCommands();
    const ids = new Set(registered.map((c) => c.id));
    return [...registered, ...shellCommands(snap.settings).filter((c) => !ids.has(c.id)), ...dataCommands(snap)];
  });
  const [recent] = useState(readRecent);
  const groups = useMemo(() => rankCommands(all, query, recent), [all, query, recent]);
  const rows = useMemo(() => flatten(groups), [groups]);
  const at = rows.length === 0 ? -1 : Math.min(active, rows.length - 1);
  const listId = useId();
  const optId = (i: number) => `${listId}-o${i}`;
  const list = useRef<HTMLDivElement>(null);
  // What the query returned, for screen readers (the listbox itself says nothing when empty).
  // Debounced so each keystroke does not interrupt with a new count.
  const said = query.trim() === "" ? "" : rows.length === 0 ? `Nothing matches ${query.trim()}` : `${rows.length} ${rows.length === 1 ? "result" : "results"}`;
  const [status, setStatus] = useState("");
  useEffect(() => {
    const t = window.setTimeout(() => setStatus(said), 400);
    return () => window.clearTimeout(t);
  }, [said]);

  const run = (c: Command, secondary: boolean) => {
    rememberRun(c.id);
    onPick(secondary && c.secondary ? c.secondary.run : c.run);
    close();
  };

  const moveTo = (i: number) => {
    setActive(i);
    list.current?.querySelector(`#${CSS.escape(optId(i))}`)?.scrollIntoView({ block: "nearest" });
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown" && rows.length) {
      e.preventDefault();
      moveTo((at + 1) % rows.length);
    } else if (e.key === "ArrowUp" && rows.length) {
      e.preventDefault();
      moveTo((at - 1 + rows.length) % rows.length);
    } else if (e.key === "Enter" && at >= 0) {
      e.preventDefault();
      run(rows[at]!, e.ctrlKey || e.metaKey);
    }
  };

  // Row index where each group starts (the keyboard walks the flat list).
  const starts = groups.reduce<number[]>((acc, _g, i) => [...acc, i === 0 ? 0 : acc[i - 1]! + groups[i - 1]!.items.length], []);
  return (
    <div className={p.palette}>
      <div className={p.field}>
        <Icon name="Search" size={20} />
        <input
          className={`t-body-l ${p.input}`}
          type="text"
          role="combobox"
          aria-expanded="true"
          aria-controls={listId}
          aria-activedescendant={at >= 0 ? optId(at) : undefined}
          aria-autocomplete="list"
          aria-label="Search or type a command"
          placeholder={PLACEHOLDER}
          // A palette opens to type into; CoffeeSheet focuses it once the dialog is open.
          data-autofocus
          autoComplete="off"
          spellCheck={false}
          value={query}
          onChange={(e) => {
            setQuery(e.currentTarget.value);
            setActive(0);
          }}
          onKeyDown={onKeyDown}
        />
      </div>
      <div ref={list} id={listId} role="listbox" aria-label="Results" className={p.list}>
        {groups.length === 0 && <p className={`t-body-m ink-mid ${p.empty}`}>Nothing matches “{query.trim()}”.</p>}
        {groups.map((g, gi) => (
          <div key={g.group} role="group" aria-label={g.group} className={p.group}>
            <div className={`eyebrow ${p.groupLabel}`} aria-hidden="true">
              {g.group}
            </div>
            {g.items.map((c, ci) => {
              const i = starts[gi]! + ci;
              return (
                <div
                  key={c.id}
                  id={optId(i)}
                  role="option"
                  aria-selected={i === at}
                  tabIndex={-1}
                  className={p.row}
                  onPointerMove={() => i !== at && setActive(i)}
                  onClick={(e) => run(c, e.ctrlKey || e.metaKey)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") run(c, e.ctrlKey || e.metaKey);
                  }}
                >
                  <span className={p.icon} aria-hidden="true">
                    {c.icon && <Icon name={c.icon} size={20} />}
                  </span>
                  <span className={p.text}>
                    <span className="t-body-l">{c.title}</span>
                    {c.subtitle && <span className={`t-body-s ${p.sub}`}>{c.subtitle}</span>}
                  </span>
                  {c.keys && <Kbd keys={c.keys} />}
                </div>
              );
            })}
          </div>
        ))}
      </div>
      <p role="status" className="visually-hidden">
        {status}
      </p>
      <p className={`t-body-s ink-low ${p.foot}`}>
        <Kbd keys="enter" /> open · <Kbd keys="mod+enter" /> brew a recipe · <Kbd keys="escape" /> close
      </p>
    </div>
  );
}
