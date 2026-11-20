/**
 * The imperative tab switch (web-spec §6.3, v4.1 Forge-style motion). Between tabs the pages
 * slide by tab order, as Forge 2.0's ForgeNavTransitions do: on the bottom bar the incoming page
 * comes in from half the window on the side of the tapped tab while the outgoing one leaves half
 * the window the other way (380 ms, Emphasized), both fading over 190 ms. Beside a rail or the
 * sidebar the slide is vertical (the nav is a column) and an eighth of the pane, so a big screen
 * never throws whole pages around. Reduced motion is a 150 ms cross-fade.
 *
 * Whole `transform` strings and `opacity` with no onUpdate, so Motion hands both to WAAPI
 * (compositor, rule 2) and React renders nothing per frame. A tap mid-switch stops the running
 * animations (Motion commits where they were) and starts from those values: the pages retarget
 * from where they are, never jump, never stay half-translated.
 */
import { animate, type AnimationPlaybackControlsWithThen } from "motion";
import { EASE_EMPHASIZED } from "@/design/motion";
import { flushDeferredSizes } from "@/viz/measure";
import { beginTransition, prefersReducedMotion } from "./transitionState";

/** Forge's TAB_MS, TAB_FADE_MS and REDUCED_MS. */
export const TAB_SLIDE_MS = 380;
export const TAB_FADE_MS = 190;
export const TAB_REDUCED_MS = 150;
/** Compose's FastOutSlowIn, the default easing of Forge's fadeIn/fadeOut tweens. */
const EASE_STANDARD = [0.4, 0, 0.2, 1] as const;

type Bezier = readonly [number, number, number, number];

/** "x": the bottom bar (compact). "y": a rail or the sidebar beside the pane. */
export type SwitchAxis = "x" | "y";

/**
 * How far a page travels: half the window across on compact, an eighth of the window's height
 * beside a rail. Window sizes, not the layer's box: reading a layer's size here would force a
 * style and layout pass in the same task as the switch.
 */
export function slideDistance(axis: SwitchAxis, w: number, h: number): number {
  return axis === "x" ? w / 2 : h / 8;
}

export interface SwitchPlan {
  readonly incoming: { readonly from: string | null; readonly to: string; readonly opacityFrom: number | null };
  readonly outgoing: { readonly from: string | null; readonly to: string; readonly opacityFrom: number | null };
  readonly slide: { readonly duration: number; readonly ease: Bezier } | null;
  readonly fade: { readonly duration: number; readonly ease: Bezier };
}

/**
 * The keyframes of one switch. `d` +1 = to a tab later in the order (right on the bar, below on
 * the rail). `inMoving` / `outMoving`: that page was caught mid-switch, so it starts where it is
 * (null: Motion's committed value) rather than from its rest position.
 *
 * A settled page always gets an explicit from-keyframe: Motion remembers each element's last
 * animated value, and the layer's inline styles are cleared behind its back once a switch ends, so
 * a null there would restart a hidden tab from wherever its last switch left it (195 px away).
 */
export function switchPlan(d: 1 | -1, axis: SwitchAxis, distance: number, reduced: boolean, inMoving: boolean, outMoving = false): SwitchPlan {
  const fn = axis === "x" ? "translateX" : "translateY";
  const px = (v: number) => `${fn}(${Math.round(v * 100) / 100}px)`;
  const outgoing = { from: outMoving ? null : px(0), opacityFrom: outMoving ? null : 1 };
  if (reduced) {
    return {
      incoming: { from: inMoving ? null : px(0), to: px(0), opacityFrom: inMoving ? null : 0 },
      outgoing: { ...outgoing, to: px(0) },
      slide: null,
      fade: { duration: TAB_REDUCED_MS / 1000, ease: EASE_STANDARD },
    };
  }
  return {
    incoming: { from: inMoving ? null : px(distance * d), to: px(0), opacityFrom: inMoving ? null : 0 },
    outgoing: { ...outgoing, to: px(-distance * d) },
    slide: { duration: TAB_SLIDE_MS / 1000, ease: EASE_EMPHASIZED },
    fade: { duration: TAB_FADE_MS / 1000, ease: EASE_STANDARD },
  };
}

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
    flushDeferredSizes(el);
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
 * A finished Motion animation writes its last keyframe inline on the next frame's render step,
 * after its promise has resolved: cleared straight away, a settled page kept its end transform
 * (a hidden tab sat at translateX(-195px) for good). So the clear waits one frame, and is skipped
 * if a newer switch has taken the layer by then.
 */
function clearAfterRender(el: HTMLElement) {
  if (typeof requestAnimationFrame !== "function") {
    clearMotionStyles(el);
    return;
  }
  requestAnimationFrame(() => {
    if (!running.has(el)) clearMotionStyles(el);
  });
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
 * Switches from `out` to `inc`. `d` = +1 when moving to a later tab (right on the bar, down the
 * rail), -1 back. Resolves when both animations have settled or been superseded by a newer switch.
 */
export function switchTabLayers(out: HTMLElement | null, inc: HTMLElement, d: 1 | -1, axis: SwitchAxis = "x"): Promise<void> {
  const end = beginTransition();
  const reduced = prefersReducedMotion();
  const outMoving = out != null && running.has(out);
  const inMoving = running.has(inc);
  if (out) stop(out);
  stop(inc);
  const w = typeof innerWidth === "number" ? innerWidth : 0;
  const h = typeof innerHeight === "number" ? innerHeight : 0;
  const plan = switchPlan(d, axis, slideDistance(axis, w, h), reduced, inMoving, outMoving);

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

    const fade = { type: "tween" as const, duration: plan.fade.duration, ease: plan.fade.ease };
    const slide = plan.slide ? { type: "tween" as const, duration: plan.slide.duration, ease: plan.slide.ease } : null;

    if (out && out !== inc) {
      setLayerState(out, "leaving");
      out.style.willChange = "transform, opacity";
      // Reduced motion: a page caught mid-slide still glides home while it fades, never jumps.
      const a = slide
        ? animate(out, { opacity: [plan.outgoing.opacityFrom, 0], transform: [plan.outgoing.from, plan.outgoing.to] }, { opacity: fade, transform: slide })
        : animate(out, { opacity: [plan.outgoing.opacityFrom, 0], transform: [plan.outgoing.from, plan.outgoing.to] }, fade);
      track(out, a, () => {
        if (out.dataset.state === "leaving") setLayerState(out, "hidden");
        clearAfterRender(out);
      });
    }

    const keyframes = { opacity: [plan.incoming.opacityFrom, 1], transform: [plan.incoming.from, plan.incoming.to] };
    const b = slide ? animate(inc, keyframes, { opacity: fade, transform: slide }) : animate(inc, keyframes, fade);
    track(inc, b, () => clearAfterRender(inc));

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
