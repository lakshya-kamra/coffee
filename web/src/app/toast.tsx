/**
 * Toasts (v4 §3.14): above the bar on compact, bottom-centre of the content area (beside the
 * nav, 24 above the edge, at most 480 wide) from medium up. One at a time: a new toast replaces
 * the old one. For "Deleted · Undo" after a confirmed delete (§2.7) and short confirmations.
 */
import { animate } from "motion";
import { useLayoutEffect, useRef, useSyncExternalStore } from "react";
import { effectsDefault, spatialDefault } from "@/design/motion";
import { prefersReducedMotion } from "./transitionState";
import shell from "./shell.module.css";

export interface ToastOptions {
  readonly text: string;
  readonly action?: { readonly label: string; readonly run: () => void };
  /** Default 5 s, 8 s with an action (time to reach Undo). */
  readonly durationMs?: number;
}

interface Live extends ToastOptions {
  readonly id: number;
}

let current: Live | null = null;
let seq = 0;
let timer: ReturnType<typeof setTimeout> | null = null;
const listeners = new Set<() => void>();

function emit() {
  for (const l of listeners) l();
}

export function dismissToast(id?: number): void {
  if (!current || (id != null && current.id !== id)) return;
  if (timer) clearTimeout(timer);
  timer = null;
  current = null;
  emit();
}

/** Shows a toast; returns its dismiss function. */
export function showToast(opts: ToastOptions): () => void {
  if (timer) clearTimeout(timer);
  const id = ++seq;
  current = { ...opts, id };
  timer = setTimeout(() => dismissToast(id), opts.durationMs ?? (opts.action ? 8000 : 5000));
  emit();
  return () => dismissToast(id);
}

const store = {
  get: () => current,
  subscribe: (fn: () => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
};

function ToastView({ t }: { t: Live }) {
  const body = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const el = body.current;
    if (!el) return;
    const a = prefersReducedMotion()
      ? animate(el, { opacity: [0, 1] }, effectsDefault)
      : animate(el, { opacity: [0, 1], transform: ["translateY(12px)", "translateY(0px)"] }, { opacity: effectsDefault, transform: spatialDefault });
    return () => a.stop();
  }, []);
  return (
    <div ref={body} className={shell.toastBody}>
      <span className={`t-body-m ${shell.toastText}`}>{t.text}</span>
      {t.action && (
        <button
          type="button"
          className={`${shell.toastAction} t-label-l`}
          onClick={() => {
            dismissToast(t.id);
            t.action!.run();
          }}
        >
          {t.action.label}
        </button>
      )}
    </div>
  );
}

/** Mount once in the shell. */
export function ToastHost() {
  const t = useSyncExternalStore(store.subscribe, store.get, store.get);
  return (
    <div className={shell.toast} role="status" aria-live="polite">
      {t && <ToastView key={t.id} t={t} />}
    </div>
  );
}
