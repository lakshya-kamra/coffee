/**
 * The imperative tab switch (web-spec §6.3): the audit's fade-through. The outgoing layer is
 * nearly gone before the incoming one appears, so only one page is translucent at a time;
 * distances are 8–20 px; values are sub-pixel floats; nothing overshoots. Whole `transform`
 * strings and `opacity` with no onUpdate, so Motion hands both to WAAPI (compositor, rule 2).
 * A tap mid-switch stops the running animations and starts from the current values.
 */
import { animate, type AnimationPlaybackControlsWithThen } from "motion";
import { delayed, effectsDefault, effectsFast, spatialDefault, spatialFast } from "@/design/motion";
import { beginTransition, prefersReducedMotion } from "./transitionState";

export type LayerState = "active" | "leaving" | "hidden";

/**
 * Applies a layer's state as attributes only (React never renders these).
 *
 * Cost matters here: `data-hidden` (visibility, paused animations) and `inert` are inherited by
 * every element in the layer, so flipping them restyles the whole tab (measured at 4× CPU
 * throttle: 20–30 ms for Today or Recipes). They change only when a layer is really shown or
 * really hidden. A leaving layer keeps both as they are and just drops under the incoming one
 * (`data-state` drives z-index on the layer itself, which no descendant inherits), so the frame
 * a switch starts in restyles one tab, not two; the outgoing tab is restyled when its fade ends.
 */
export function setLayerState(el: HTMLElement, state: LayerState): void {
  el.dataset.state = state;
  if (state === "hidden") {
    el.dataset.hidden = "";
    el.inert = true;
  } else if (el.dataset.hidden != null) {
    delete el.dataset.hidden;
    el.inert = false;
  }
  if (state !== "active") el.setAttribute("aria-hidden", "true");
  else el.removeAttribute("aria-hidden");
}

function clearMotionStyles(el: HTMLElement) {
  el.style.removeProperty("opacity");
  el.style.removeProperty("transform");
  el.style.removeProperty("will-change");
}

/**
 * One running layer animation. `settle` releases its share of the switch's transition token and
 * runs on a natural finish *and* when a newer switch stops it: a stopped Motion animation never
 * settles its promise, so a token that waited on the promise alone would be held for good and
 * afterTransition() would never run again.
 */
interface Run {
  readonly controls: AnimationPlaybackControlsWithThen;
  readonly settle: () => void;
}

const running = new Map<HTMLElement, Run>();

function stop(el: HTMLElement) {
  const r = running.get(el);
  if (!r) return;
  running.delete(el);
  r.controls.stop();
  r.settle();
}

/**
 * Switches from `out` to `inc`. `d` = +1 when moving right along the bar, -1 left.
 * Resolves when both animations have settled or been superseded by a newer switch.
 */
export function switchTabLayers(out: HTMLElement | null, inc: HTMLElement, d: 1 | -1): Promise<void> {
  const end = beginTransition();
  const reduced = prefersReducedMotion();
  if (out) stop(out);
  stop(inc);
  const wasVisible = inc.dataset.state === "active" || inc.dataset.state === "leaving";

  return new Promise<void>((resolve) => {
    // One share held here until both animations are tracked, so an early settle cannot end it.
    let open = 1;
    const release = () => {
      if (--open > 0) return;
      end();
      resolve();
    };
    /** Tracks one layer's animation; `finished` runs only when it ends by itself. */
    const track = (el: HTMLElement, controls: AnimationPlaybackControlsWithThen, finished: () => void) => {
      open++;
      let settled = false;
      const run: Run = {
        controls,
        settle: () => {
          if (settled) return;
          settled = true;
          release();
        },
      };
      running.set(el, run);
      const done = () => {
        if (running.get(el) === run) {
          running.delete(el);
          finished();
        }
        run.settle();
      };
      controls.then(done, done);
    };

    setLayerState(inc, "active");
    inc.style.willChange = "transform, opacity";

    if (out && out !== inc) {
      setLayerState(out, "leaving");
      out.style.willChange = "transform, opacity";
      const a = reduced
        ? animate(out, { opacity: [null, 0] }, effectsFast)
        : animate(out, { opacity: [null, 0], transform: [null, `translateX(${-8 * d}px)`] }, { opacity: effectsFast, transform: spatialFast });
      track(out, a, () => {
        if (out.dataset.state === "leaving") setLayerState(out, "hidden");
        clearMotionStyles(out);
      });
    }

    const b = reduced
      ? animate(inc, { opacity: [wasVisible ? null : 0, 1] }, effectsFast)
      : animate(
          inc,
          {
            opacity: [wasVisible ? null : 0, 1],
            transform: [wasVisible ? null : `translateX(${20 * d}px)`, "translateX(0px)"],
          },
          { opacity: delayed(effectsDefault, 0.06), transform: delayed(spatialDefault, 0.06) },
        );
    track(inc, b, () => clearMotionStyles(inc));

    release();
  });
}

/** Initial state without animation (boot, tests). */
export function showLayerNow(active: HTMLElement, others: readonly HTMLElement[]): void {
  for (const o of others) {
    stop(o);
    setLayerState(o, "hidden");
    clearMotionStyles(o);
  }
  stop(active);
  setLayerState(active, "active");
  clearMotionStyles(active);
}
