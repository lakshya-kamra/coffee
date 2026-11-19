/**
 * CoffeeSheet (web-spec §6.6): a native <dialog> opened with showModal() — focus trap, Escape and
 * an inert background for free. The open/close slide runs on an outer wrapper with a whole
 * transform string (compositor, WAAPI); the drag is a motion value on the inner panel (rule 3),
 * so the two never fight over one transform.
 */
import { animate } from "motion";
import { m, useDragControls, type PanInfo } from "motion/react";
import { useCallback, useEffect, useLayoutEffect, useRef, type ReactNode } from "react";
import { prefersReducedMotion } from "@/app/transitionState";
import { effectsDefault, effectsFast, spatialDefault, spatialFast } from "../motion";
import s from "./components.module.css";

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

export function CoffeeSheet({
  onClose,
  children,
  label,
  full = false,
}: {
  /** Called once the close animation has finished. */
  onClose: () => void;
  children: ReactNode;
  /** Accessible name of the dialog. */
  label: string;
  /** Full-height editor sheet: pushes its own #sheet history entry so system Back closes it. */
  full?: boolean;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const outer = useRef<HTMLDivElement>(null);
  const scrim = useRef<HTMLDivElement>(null);
  const body = useRef<HTMLDivElement>(null);
  const closing = useRef(false);
  const drag = useDragControls();
  const onCloseRef = useRef(onClose);
  useLayoutEffect(() => {
    onCloseRef.current = onClose;
  });

  const animateOut = useCallback(() => {
    if (closing.current) return;
    closing.current = true;
    const o = outer.current;
    const sc = scrim.current;
    const done = () => {
      dialog.current?.close();
      onCloseRef.current();
    };
    if (!o || !sc) return done();
    const reduced = prefersReducedMotion();
    animate(sc, { opacity: [null, 0] }, effectsFast);
    const a = reduced ? animate(o, { opacity: [null, 0] }, effectsFast) : animate(o, { transform: [null, "translateY(100%)"] }, spatialFast);
    void a.then(done, done);
  }, []);

  /** Every close path ends here: pops our history entry first when we pushed one. */
  const requestClose = useSheetHistory(full, animateOut);

  useLayoutEffect(() => {
    const d = dialog.current;
    const o = outer.current;
    const sc = scrim.current;
    if (!d || !o || !sc) return;
    if (!d.open) d.showModal();
    const reduced = prefersReducedMotion();
    const a1 = animate(sc, { opacity: [0, 1] }, effectsDefault);
    const a2 = reduced ? animate(o, { opacity: [0, 1] }, effectsFast) : animate(o, { transform: ["translateY(100%)", "translateY(0%)"] }, spatialDefault);
    return () => {
      a1.stop();
      a2.stop();
    };
  }, []);

  const onDragEnd = (_e: unknown, info: PanInfo) => {
    const h = outer.current?.offsetHeight ?? 1;
    if (info.velocity.y > 600 || info.offset.y > h * 0.3) requestClose();
  };

  return (
    <dialog
      ref={dialog}
      className={s.sheet}
      aria-label={label}
      onCancel={(e) => {
        e.preventDefault();
        requestClose();
      }}
    >
      <div ref={scrim} className={s.sheetScrim} role="presentation" onClick={requestClose} />
      <div ref={outer} className={s.sheetPanel} data-full={full}>
        <m.div
          className={s.sheetInner}
          drag="y"
          dragListener={false}
          dragControls={drag}
          dragConstraints={{ top: 0, bottom: 0 }}
          dragElastic={{ top: 0.06, bottom: 1 }}
          dragSnapToOrigin
          onDragEnd={onDragEnd}
        >
          <div className={s.sheetGrabber} style={{ touchAction: "none" }} onPointerDown={(e) => drag.start(e)} aria-hidden="true" />
          <div
            ref={body}
            className={s.sheetBody}
            onPointerDown={(e) => {
              // Content drags the sheet only while it is scrolled to the top (rule: never fight scrolling).
              if ((body.current?.scrollTop ?? 1) <= 0 && e.pointerType === "touch") drag.start(e);
            }}
          >
            {children}
          </div>
        </m.div>
      </div>
    </dialog>
  );
}
