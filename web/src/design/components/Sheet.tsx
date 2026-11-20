/**
 * CoffeeSheet (web-spec §6.6, v4 §2.4): a native <dialog> opened with showModal() — focus trap,
 * Escape and an inert background for free. It presents by `role` and window class
 * (design/presentation.ts): a bottom or full-height sheet on compact, a centred dialog, a right
 * side sheet or a popover anchored to a field at expanded and up. The open/close motion runs on
 * an outer wrapper with whole transform strings (compositor, WAAPI); the drag (sheets only) is a
 * motion value on the inner panel (rule 3), so the two never fight over one transform.
 */
import { animate } from "motion";
import { m, useDragControls, type PanInfo } from "motion/react";
import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode, type RefObject } from "react";
import { prefersReducedMotion } from "@/app/transitionState";
import { useWindowClass } from "@/app/windowClass";
import { effectsDefault, effectsFast, spatialDefault, spatialFast } from "../motion";
import { presentationFor, type Presentation, type SheetRole } from "../presentation";
import s from "./components.module.css";

export type { SheetRole } from "../presentation";

/**
 * Full sheets stack (a picker over an editor). Each one tags its own history entry, so a Back
 * that leaves the inner sheet's entry does not look like the outer one's. Ids are unique across
 * reloads too, because a reloaded page can still have an old entry above it.
 */
let sheetSeq = 0;
function newSheetId(): string {
  sheetSeq += 1;
  return `${Date.now().toString(36)}-${sheetSeq}`;
}
function sheetsIn(state: unknown): string[] {
  const v = (state as { sheets?: unknown } | null)?.sheets;
  return Array.isArray(v) ? (v as string[]) : [];
}
function topSheet(): string | undefined {
  const all = sheetsIn(history.state);
  return all[all.length - 1];
}

/**
 * One #sheet history entry for a full-screen layer (a full CoffeeSheet, the photo viewer), so
 * system Back closes that layer and nothing under it. `close` runs once our entry is gone.
 * Returns the request every other close path (X, Escape, scrim) must go through: it pops our
 * entry first, so closing by hand leaves no stray entry for Back to swallow.
 */
export function useSheetHistory(enabled: boolean, close: () => void): () => void {
  // "popping": our own history.back() is on its way. Asking again, or unmounting, in that gap
  // must not call back() a second time: that would pop the screen underneath.
  // "leaving": unmounted with the entry still on top; it is dropped a microtask later.
  const entry = useRef<"none" | "pushed" | "popping" | "leaving">("none");
  const sheetId = useRef("");
  const closeRef = useRef(close);
  useLayoutEffect(() => {
    closeRef.current = close;
  });

  useEffect(() => {
    if (!enabled) return;
    // React's dev-mode double mount: the cleanup below has just run and its entry is still ours.
    const kept = entry.current === "leaving" && topSheet() === sheetId.current;
    const id = kept ? sheetId.current : newSheetId();
    if (!kept) {
      sheetId.current = id;
      const below = history.state as object | null;
      history.pushState({ ...below, sheet: true, sheets: [...sheetsIn(below), id] }, "", location.pathname + location.search + "#sheet");
    }
    entry.current = "pushed";
    const onPop = () => {
      // Our entry is gone (Back, or our own history.back()). A sheet opened above us popping
      // leaves our id in place, so this one stays open.
      if (entry.current !== "none" && !sheetsIn(history.state).includes(id)) {
        entry.current = "none";
        closeRef.current();
      }
    };
    window.addEventListener("popstate", onPop);
    return () => {
      window.removeEventListener("popstate", onPop);
      if (entry.current !== "pushed" || topSheet() !== id) {
        entry.current = "none";
        return;
      }
      // Unmounted without closing (route change): drop our entry so Back is not swallowed. A
      // microtask later, because the dev-mode double mount unmounts and remounts in one task,
      // and a second entry pushed while this back() is on its way would close the sheet at once.
      entry.current = "leaving";
      queueMicrotask(() => {
        if (entry.current !== "leaving") return;
        entry.current = "none";
        if (topSheet() === id) history.back();
      });
    };
  }, [enabled]);

  return useCallback(() => {
    if (entry.current === "popping") return;
    if (entry.current === "pushed" && topSheet() === sheetId.current) {
      entry.current = "popping";
      history.back(); // the popstate listener above runs close
    } else {
      closeRef.current();
    }
  }, []);
}

// ---------------------------------------------------------------- context for the content

interface SheetCtx {
  readonly close: () => void;
  readonly presentation: Presentation;
}

const SheetContext = createContext<SheetCtx | null>(null);

/** Closes the enclosing CoffeeSheet with its animation (and history entry). Skips the dirty check. */
export function useSheetClose(): () => void {
  const ctx = useContext(SheetContext);
  return ctx?.close ?? noop;
}

/** How the enclosing sheet presents now (content may hide its own Save at "side"). */
export function useSheetPresentation(): Presentation | null {
  return useContext(SheetContext)?.presentation ?? null;
}

function noop() {}

/** Copy for the discard question (v4 §2.4). */
export const DISCARD_COPY = {
  title: "Discard changes?",
  body: "You've changed this coffee and haven't saved it.",
  keep: "Keep editing",
  discard: "Discard",
} as const;

// ---------------------------------------------------------------- motion per presentation

function enterFrames(kind: Presentation["kind"], reduced: boolean): { opacity?: [number, number]; transform?: [string, string] } {
  if (reduced) return { opacity: [0, 1] };
  switch (kind) {
    case "bottom":
    case "full":
      return { transform: ["translateY(100%)", "translateY(0%)"] };
    case "dialog":
      return { opacity: [0, 1], transform: ["scale(0.96)", "scale(1)"] };
    case "side":
      return { opacity: [0, 1], transform: ["translateX(24px)", "translateX(0px)"] };
    case "popover":
      return { opacity: [0, 1], transform: ["translateY(-4px)", "translateY(0px)"] };
  }
}

function exitFrames(kind: Presentation["kind"], reduced: boolean): { opacity?: [null, number]; transform?: [null, string] } {
  if (reduced) return { opacity: [null, 0] };
  switch (kind) {
    case "bottom":
    case "full":
      return { transform: [null, "translateY(100%)"] };
    case "dialog":
      return { opacity: [null, 0], transform: [null, "scale(0.96)"] };
    case "side":
      return { opacity: [null, 0], transform: [null, "translateX(24px)"] };
    case "popover":
      return { opacity: [null, 0], transform: [null, "translateY(-4px)"] };
  }
}

/** Places a popover under (or above) its anchor, clamped to the window. */
function popoverPlacement(anchor: HTMLElement | null, maxW: number, maxH: number): CSSProperties {
  if (!anchor) return {};
  const r = anchor.getBoundingClientRect();
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const w = Math.min(maxW, vw - 32);
  const left = Math.max(16, Math.min(r.left, vw - w - 16));
  const below = vh - r.bottom - 16;
  const above = r.top - 16;
  if (below >= Math.min(maxH, 320) || below >= above) return { left, top: r.bottom + 8, width: w, maxHeight: Math.min(maxH, below - 8) };
  return { left, bottom: vh - r.top + 8, width: w, maxHeight: Math.min(maxH, above - 8) };
}

// ---------------------------------------------------------------- CoffeeSheet

export interface CoffeeSheetProps {
  /** Called once the close animation has finished. */
  onClose: () => void;
  children: ReactNode;
  /** Accessible name of the dialog (and the side sheet's title unless `title` is given). */
  label: string;
  /** Full-height sheet: pushes its own #sheet history entry so system Back closes it. */
  full?: boolean;
  /** How it presents at each window class (§2.4). Default "content". */
  role?: SheetRole;
  /** Picker: the field it belongs to; at expanded+ it opens as a popover under it. */
  anchor?: RefObject<HTMLElement | null>;
  /** Editor: the side sheet's header title (default `label`). */
  title?: string;
  /** Editor: shows Save in the side sheet header and binds Ctrl/Cmd+S. */
  onSave?: () => void;
  saveLabel?: string;
  /** Editor: unsaved changes. Scrim, Escape and drag then ask "Discard changes?" first. */
  dirty?: boolean;
  /** Body of the discard question (default: the coffee copy). */
  discardBody?: string;
  /** Focused when it opens (ConfirmSheet: the safe action). */
  initialFocus?: RefObject<HTMLElement | null>;
  /** Dialog presentations: "top" sits at 18vh (the command palette). */
  align?: "center" | "top";
}

export function CoffeeSheet({ onClose, children, label, full = false, role = "content", anchor, title, onSave, saveLabel = "Save", dirty = false, discardBody, initialFocus, align = "center" }: CoffeeSheetProps) {
  const dialog = useRef<HTMLDialogElement>(null);
  const outer = useRef<HTMLDivElement>(null);
  const scrim = useRef<HTMLDivElement>(null);
  const body = useRef<HTMLDivElement>(null);
  const closing = useRef(false);
  const trigger = useRef<Element | null>(null);
  const drag = useDragControls();
  const wc = useWindowClass();
  const pres = presentationFor(role, wc, { anchored: anchor != null, full });
  const presRef = useRef(pres);
  const [asking, setAsking] = useState(false);
  const [place, setPlace] = useState<CSSProperties>({});
  const onCloseRef = useRef(onClose);
  const onSaveRef = useRef(onSave);
  const dirtyRef = useRef(dirty);
  useLayoutEffect(() => {
    onCloseRef.current = onClose;
    onSaveRef.current = onSave;
    dirtyRef.current = dirty;
    presRef.current = pres;
  });

  const animateOut = useCallback(() => {
    if (closing.current) return;
    closing.current = true;
    const o = outer.current;
    const sc = scrim.current;
    const done = () => {
      dialog.current?.close();
      onCloseRef.current();
      // Back to whatever opened it (A5), if it is still there and nothing else took focus.
      const t = trigger.current as HTMLElement | null;
      if (t?.isConnected && (document.activeElement == null || document.activeElement === document.body)) t.focus({ preventScroll: true });
    };
    if (!o || !sc) return done();
    const reduced = prefersReducedMotion();
    animate(sc, { opacity: [null, 0] }, effectsFast);
    const kind = presRef.current.kind;
    const t = kind === "bottom" || kind === "full" ? spatialFast : { opacity: effectsFast, transform: spatialFast };
    const a = reduced ? animate(o, { opacity: [null, 0] }, effectsFast) : animate(o, exitFrames(kind, false), t);
    void a.then(done, done);
  }, []);

  /** Every close path ends here: pops our history entry first when we pushed one. */
  const requestClose = useSheetHistory(full, animateOut);

  /** Scrim, Escape and drag: ask first when there are unsaved changes. */
  const softClose = useCallback(() => {
    if (dirtyRef.current) setAsking(true);
    else requestClose();
  }, [requestClose]);

  useLayoutEffect(() => {
    const d = dialog.current;
    const o = outer.current;
    const sc = scrim.current;
    if (!d || !o || !sc) return;
    trigger.current = document.activeElement;
    if (!d.open) d.showModal();
    // A child's own autoFocus ran while the dialog was still closed, so showModal() picks the
    // first focusable node, which in a short window is the scrolling body. [data-autofocus]
    // lets content name its field without a ref.
    const first = initialFocus?.current ?? body.current?.querySelector<HTMLElement>("[data-autofocus]");
    first?.focus({ preventScroll: true });
    const reduced = prefersReducedMotion();
    const kind = presRef.current.kind;
    const a1 = animate(sc, { opacity: [0, 1] }, effectsDefault);
    const t = kind === "bottom" || kind === "full" ? spatialDefault : { opacity: effectsFast, transform: spatialFast };
    const a2 = reduced ? animate(o, { opacity: [0, 1] }, effectsFast) : animate(o, enterFrames(kind, false), t);
    return () => {
      a1.stop();
      a2.stop();
    };
    // Mount-only: the open animation runs once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A popover follows its anchor while open (the only resize work a sheet does, and only then).
  const isPopover = pres.kind === "popover";
  const popW = pres.maxWidth ?? 480;
  useLayoutEffect(() => {
    if (!isPopover) return;
    const place = () => setPlace(popoverPlacement(anchor?.current ?? null, popW, 560));
    place();
    let raf = 0;
    const onResize = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(place);
    };
    window.addEventListener("resize", onResize);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", onResize);
    };
  }, [isPopover, anchor, popW]);

  const onDragEnd = (_e: unknown, info: PanInfo) => {
    const h = outer.current?.offsetHeight ?? 1;
    if (info.velocity.y > 600 || info.offset.y > h * 0.3) softClose();
  };

  const draggable = pres.kind === "bottom" || pres.kind === "full";
  const headed = pres.kind === "side" && (onSave != null || title != null);
  const panelStyle: CSSProperties = {
    ...(pres.maxWidth != null && !isPopover ? { maxWidth: pres.maxWidth } : {}),
    ...(pres.maxHeight != null && !isPopover ? { maxHeight: pres.maxHeight } : {}),
    ...(isPopover ? place : {}),
  };

  return (
    <dialog
      ref={dialog}
      className={s.sheet}
      aria-label={label}
      data-present={pres.kind}
      data-align={align}
      onCancel={(e) => {
        // A nested sheet's own cancel reaches here through React's tree: not ours.
        if (e.target !== dialog.current) return;
        e.preventDefault();
        softClose();
      }}
      onKeyDown={(e) => {
        if (onSaveRef.current && (e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === "s") {
          e.preventDefault();
          onSaveRef.current();
        }
      }}
    >
      <div ref={scrim} className={s.sheetScrim} data-clear={isPopover || undefined} role="presentation" onClick={softClose} />
      <div ref={outer} className={s.sheetPanel} data-full={pres.kind === "full"} data-present={pres.kind} style={panelStyle}>
        <SheetContext.Provider value={{ close: requestClose, presentation: pres }}>
          <m.div
            className={s.sheetInner}
            drag={draggable ? "y" : false}
            dragListener={false}
            dragControls={drag}
            dragConstraints={{ top: 0, bottom: 0 }}
            dragElastic={{ top: 0.06, bottom: 1 }}
            dragSnapToOrigin
            onDragEnd={onDragEnd}
          >
            {draggable && <div className={s.sheetGrabber} style={{ touchAction: "none" }} onPointerDown={(e) => drag.start(e)} aria-hidden="true" />}
            {headed && (
              <header className={s.sideHeader}>
                <h2 className={`t-headline-s ${s.sideTitle}`}>{title ?? label}</h2>
                <button type="button" className={`${s.sideCancel} t-label-l`} onClick={requestClose}>
                  Cancel
                </button>
                {onSave && (
                  <button type="button" className={`${s.sideSave} t-label-l`} onClick={() => onSaveRef.current?.()} aria-keyshortcuts="Control+S Meta+S">
                    {saveLabel}
                  </button>
                )}
              </header>
            )}
            <div
              ref={body}
              className={s.sheetBody}
              data-present={pres.kind}
              onPointerDown={(e) => {
                // Content drags the sheet only while it is scrolled to the top (rule: never fight scrolling).
                if (draggable && (body.current?.scrollTop ?? 1) <= 0 && e.pointerType === "touch") drag.start(e);
              }}
            >
              {children}
            </div>
          </m.div>
        </SheetContext.Provider>
      </div>
      {asking && <DiscardConfirm body={discardBody ?? DISCARD_COPY.body} onDiscard={requestClose} onDone={() => setAsking(false)} />}
    </dialog>
  );
}

/**
 * The discard question over a dirty editor. Built here rather than with ConfirmSheet so the two
 * files do not import each other; same look and the same safe-first focus.
 */
function DiscardConfirm({ body, onDiscard, onDone }: { body: string; onDiscard: () => void; onDone: () => void }) {
  const keep = useRef<HTMLButtonElement>(null);
  const choice = useRef<"discard" | "keep">("keep");
  return (
    <CoffeeSheet
      role="confirm"
      label={DISCARD_COPY.title}
      initialFocus={keep}
      onClose={() => {
        onDone();
        if (choice.current === "discard") onDiscard();
      }}
    >
      <ConfirmBody
        title={DISCARD_COPY.title}
        body={body}
        cancelText={DISCARD_COPY.keep}
        confirmText={DISCARD_COPY.discard}
        danger
        safe="cancel"
        safeRef={keep}
        onChoice={(c) => {
          choice.current = c === "confirm" ? "discard" : "keep";
        }}
      />
    </CoffeeSheet>
  );
}

/** The inside of a confirm: title, body, Cancel then the action; `safeRef` goes on the safe one. */
export function ConfirmBody({
  title,
  body,
  cancelText,
  confirmText,
  danger,
  safe,
  safeRef,
  onChoice,
}: {
  title: string;
  body?: string;
  cancelText: string;
  confirmText: string;
  danger: boolean;
  /** Which button is the safe action (focused when it opens, A6). */
  safe: "cancel" | "confirm";
  safeRef: RefObject<HTMLButtonElement | null>;
  onChoice: (c: "confirm" | "cancel") => void;
}) {
  const close = useSheetClose();
  return (
    <div className={s.confirmBox}>
      <h2 className="t-headline-s">{title}</h2>
      {body && <p className="t-body-m ink-mid">{body}</p>}
      <div className={s.confirmActions}>
        <button
          ref={safe === "cancel" ? safeRef : undefined}
          type="button"
          className={`${s.confirmBtn} t-label-l`}
          data-safe={safe === "cancel" || undefined}
          onClick={() => {
            onChoice("cancel");
            close();
          }}
        >
          {cancelText}
        </button>
        <button
          ref={safe === "confirm" ? safeRef : undefined}
          type="button"
          className={`${s.confirmBtn} ${danger ? s.confirmDanger : s.confirmGo} t-label-l`}
          data-safe={safe === "confirm" || undefined}
          onClick={() => {
            onChoice("confirm");
            close();
          }}
        >
          {confirmText}
        </button>
      </div>
    </div>
  );
}
