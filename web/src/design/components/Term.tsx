/**
 * Words explained where they appear (v4 §4.5). <Term id="ratio">RATIO</Term> draws the word with
 * a dotted underline (or as a small chip); pressing it explains the word: an anchored popover at
 * expanded and up, a content sheet on compact and medium. The text comes from
 * shared/glossary.json through the catalog (loaded on first open). `hint` adds the small "?"
 * that `experience = new` shows on a screen's first occurrence.
 */
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import { createPortal } from "react-dom";
import { atLeast, useWindowClass } from "@/app/windowClass";
import { catalog } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import { findTerm, type GlossaryTerm } from "@/domain/core/glossary";
import { CoffeeSheet } from "./Sheet";
import s from "./components.module.css";

export function Term({ id, children, chip = false, hint = false, className }: { id: string; children?: ReactNode; chip?: boolean; hint?: boolean; className?: string }) {
  const [open, setOpen] = useState(false);
  const btn = useRef<HTMLButtonElement>(null);
  const wide = atLeast(useWindowClass().width, "expanded");
  const close = useCallback(() => setOpen(false), []);
  return (
    <>
      <button
        ref={btn}
        type="button"
        className={`${s.term} ${className ?? ""}`}
        data-chip={chip || undefined}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={(e) => {
          // Inside a clickable card the word explains itself and does not open the card.
          e.stopPropagation();
          setOpen((o) => !o);
        }}
      >
        {children ?? <TermWord id={id} />}
        {hint && (
          <span className={s.termHint} aria-hidden="true">
            ?
          </span>
        )}
        <span className="visually-hidden">, explain</span>
      </button>
      {open && (wide ? <TermPopover id={id} anchor={btn} onClose={close} /> : <TermSheet id={id} onClose={close} />)}
    </>
  );
}

/** The word itself when no children are given (the glossary's spelling). */
function TermWord({ id }: { id: string }) {
  const ready = useCatalog(["glossary"]);
  const t = ready ? findTerm(catalog.glossary, id) : null;
  return <>{t?.term ?? id}</>;
}

function useTerm(id: string): GlossaryTerm | null | undefined {
  const ready = useCatalog(["glossary"]);
  // undefined while loading, null when the id is unknown.
  return ready ? findTerm(catalog.glossary, id) : undefined;
}

export function TermBody({ term, titleId }: { term: GlossaryTerm | null | undefined; titleId?: string }) {
  if (term === undefined) return <p className="t-body-m ink-mid">Loading…</p>;
  if (term === null) return <p className="t-body-m ink-mid">No explanation for this word yet.</p>;
  return (
    <div className={s.termBody}>
      <h2 id={titleId} className="t-headline-s">
        {term.term}
      </h2>
      <p className="t-body-l">{term.short}</p>
      {term.more && <p className="t-body-m ink-mid">{term.more}</p>}
      {term.sources.length > 0 && (
        <ul className={s.termSources}>
          {term.sources.map((src) => (
            <li key={src.url} className="t-body-s">
              <a href={src.url} target="_blank" rel="noopener noreferrer">
                {src.who}
              </a>
              {src.note && <span className="ink-low"> · {src.note}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function TermSheet({ id, onClose }: { id: string; onClose: () => void }) {
  const term = useTerm(id);
  return (
    <CoffeeSheet role="content" label={term?.term ?? "What this means"} onClose={onClose}>
      <TermBody term={term} />
    </CoffeeSheet>
  );
}

/** A small non-modal popover under the word; Escape or a press outside closes it. */
function TermPopover({ id, anchor, onClose }: { id: string; anchor: RefObject<HTMLButtonElement | null>; onClose: () => void }) {
  const term = useTerm(id);
  const box = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null);

  useLayoutEffect(() => {
    const el = box.current;
    const a = anchor.current;
    if (!el || !a) return;
    const r = a.getBoundingClientRect();
    const w = el.offsetWidth;
    const h = el.offsetHeight;
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const top = r.bottom + 8 + h > vh - 8 ? r.top - 8 - h : r.bottom + 8;
    setPos({ left: Math.max(8, Math.min(r.left, vw - w - 8)), top: Math.max(8, top) });
  }, [anchor, term]);

  useEffect(() => {
    const el = box.current;
    el?.focus({ preventScroll: true });
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopPropagation();
      onClose();
      anchor.current?.focus({ preventScroll: true });
    };
    el?.addEventListener("keydown", onKey);
    const onDown = (e: PointerEvent) => {
      const t = e.target as Node;
      if (!box.current?.contains(t) && !anchor.current?.contains(t)) onClose();
    };
    const onAway = () => onClose();
    document.addEventListener("pointerdown", onDown, { capture: true });
    window.addEventListener("resize", onAway);
    return () => {
      el?.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onDown, { capture: true });
      window.removeEventListener("resize", onAway);
    };
  }, [anchor, onClose]);

  const host = (anchor.current?.closest("dialog[open]") as HTMLElement | null) ?? document.body;
  return createPortal(
    <div
      ref={box}
      role="dialog"
      aria-labelledby={titleId}
      tabIndex={-1}
      data-popover=""
      className={s.termPop}
      style={pos ? { left: pos.left, top: pos.top } : { left: -9999, top: 0 }}
    >
      <TermBody term={term} titleId={titleId} />
    </div>,
    host,
  );
}
