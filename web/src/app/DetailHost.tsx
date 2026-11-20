/**
 * Details above the tabs, one opaque page at a time (web-spec §6.4). Each detail is a fixed,
 * opaque scroll layer. The tabs underneath never animate: once a push settles they get
 * visibility:hidden (stop painting), and they are made visible again synchronously at the start
 * of a pop, before the exit animation runs. All enter/exit values are opacity and whole
 * transform strings (compositor).
 *
 * v4 §2.3: a pane-able detail whose tab has a mounted ListDetail renders in that tab's pane at
 * expanded and up (layerPlan.ts decides). It gets no layer here, and `tabs[data-covered]` is set
 * only by a real layer. A detail that moves between pane and layer because of a resize (or a
 * ListDetail mounting) appears or disappears without animation, and every layer keeps its key
 * (`index:path`), so a brew or a half-written review above it keeps its state (§2.10).
 */
import { AnimatePresence, m, type TargetAndTransition, type Transition } from "motion/react";
import { Suspense, useLayoutEffect, useRef, useState, type CSSProperties } from "react";
import { effectsFast, spatialDefault, spatialFast } from "@/design/motion";
import { flippedKeys, layersOf, type PlannedDetail } from "./layerPlan";
import { useDetailPlan } from "./paneHosts";
import { DetailScreen } from "./registry";
import { useRoute } from "./router";
import type { DetailRoute, SharedFrom } from "./routes";
import { SHARED_ELEMENT } from "./sharedKeys";
import { beginTransition, prefersReducedMotion, recentPointer, SettledContext, shellElements } from "./transitionState";
import shell from "./shell.module.css";

interface Choreography {
  initial: TargetAndTransition;
  animate: TargetAndTransition;
  exit: TargetAndTransition;
  /** Recipe with the shared header: only the canvas fades; content is not faded on enter. */
  backdrop: boolean;
  origin?: string;
}

const enterDefault: Transition = { opacity: effectsFast, transform: spatialDefault };
const exitDefault: Transition = { opacity: effectsFast, transform: spatialFast };

/** Exported for tests: the choreography per route kind (Android CoffeeNav.kt + audit §3.4). */
export function choreographyFor(kind: DetailRoute["kind"], reduced: boolean, shared: boolean): Choreography {
  if (reduced) {
    return {
      initial: { opacity: 0 },
      animate: { opacity: 1, transition: effectsFast },
      exit: { opacity: 0, transition: effectsFast },
      backdrop: false,
    };
  }
  switch (kind) {
    case "recipe":
      if (shared) {
        // No container scale: it would land the body at a different scale from the flying header.
        return {
          initial: { opacity: 1 },
          animate: { opacity: 1 },
          exit: { opacity: 0, transition: effectsFast },
          backdrop: true,
        };
      }
      return {
        initial: { opacity: 0, transform: "scale(0.94)" },
        animate: { opacity: 1, transform: "scale(1)", transition: enterDefault },
        exit: { opacity: 0, transform: "scale(0.94)", transition: exitDefault },
        backdrop: false,
      };
    case "brew":
      return {
        initial: { opacity: 0, transform: "scale(0.96)" },
        animate: { opacity: 1, transform: "scale(1)", transition: enterDefault },
        exit: { opacity: 0, transform: "scale(0.96)", transition: exitDefault },
        backdrop: false,
      };
    case "review":
      return {
        initial: { opacity: 0, transform: "translateY(20%)" },
        animate: { opacity: 1, transform: "translateY(0%)", transition: enterDefault },
        exit: { opacity: 0, transform: "translateY(20%)", transition: exitDefault },
        backdrop: false,
      };
    default:
      return {
        initial: { opacity: 0, transform: "scale(0.94)" },
        animate: { opacity: 1, transform: "scale(1)", transition: enterDefault },
        exit: { opacity: 0, transform: "scale(0.94)", transition: exitDefault },
        backdrop: false,
      };
  }
}

/** Human names for each layer (v4 A3); also the first part of most document titles. */
export const LAYER_LABELS: Readonly<Record<DetailRoute["kind"], string>> = {
  recipe: "Recipe",
  recipeEdit: "Edit recipe",
  brew: "Brew",
  review: "Rate this brew",
  brewDetail: "Brew details",
  compare: "Compare brews",
  onboarding: "Welcome",
};

export function layerLabel(route: DetailRoute): string {
  if (route.kind === "recipeEdit") return route.mode === "new" ? "New recipe" : route.mode === "duplicate" ? "Duplicate recipe" : "Edit recipe";
  return LAYER_LABELS[route.kind];
}

/** Real layers by their position among layers (0 = lowest), not by stack index. */
const detailEls = new Map<number, HTMLElement>();

function setCovered(el: HTMLElement | null | undefined, covered: boolean) {
  if (!el) return;
  if (covered) el.dataset.covered = "true";
  else delete el.dataset.covered;
}

/** Once a push settles: everything below the top stops painting. */
function coverBelow(pos: number) {
  setCovered(shellElements.tabs, true);
  for (const [i, el] of detailEls) if (i < pos) setCovered(el, true);
}

const INSTANT_EXIT: TargetAndTransition = { opacity: 0, transition: { duration: 0 } };

function DetailLayer({ route, from, pos, layerKey, instant, focus }: { route: DetailRoute; from: SharedFrom | undefined; pos: number; layerKey: string; instant: boolean; focus: boolean }) {
  // Read once per layer: a preference change mid-flight must not swap the choreography.
  const [c] = useState(() => {
    const reduced = prefersReducedMotion();
    const ch = choreographyFor(route.kind, reduced, SHARED_ELEMENT && !reduced && !instant);
    const p = recentPointer();
    return p && !ch.backdrop && route.kind === "recipe" ? { ...ch, origin: `${p.x}px ${p.y}px` } : ch;
  });
  const [settled, setSettled] = useState(instant);
  const end = useRef<(() => void) | null>(null);
  const self = useRef<HTMLElement | null>(null);

  useLayoutEffect(() => {
    if (instant) coverBelow(pos);
    else end.current = beginTransition();
    return () => end.current?.();
    // Mount-only: covering happens once, when the layer has entered.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onEntered = (def: unknown) => {
    if (def !== "visible" || settled) return;
    setSettled(true);
    coverBelow(pos);
    end.current?.();
    end.current = null;
  };

  const style: CSSProperties | undefined = c.origin ? { transformOrigin: c.origin } : undefined;
  // `custom` comes from AnimatePresence at exit time: the keys that flipped to a pane leave at once.
  const exit = (flipped: ReadonlySet<string> | undefined) => (flipped?.has(layerKey) ? INSTANT_EXIT : c.exit);

  return (
    <m.main
      ref={(el: HTMLElement | null) => {
        if (el) {
          self.current = el;
          detailEls.set(pos, el);
        } else if (detailEls.get(pos) === self.current) {
          detailEls.delete(pos);
        }
      }}
      className={shell.detail}
      data-focus={focus || undefined}
      data-backdrop={c.backdrop || undefined}
      data-layer-key={layerKey}
      tabIndex={-1}
      style={style}
      variants={c.backdrop ? { exit } : { hidden: c.initial, visible: c.animate, exit }}
      initial={c.backdrop || instant ? false : "hidden"}
      animate={c.backdrop ? undefined : "visible"}
      exit="exit"
      onAnimationComplete={c.backdrop ? undefined : onEntered}
      aria-label={layerLabel(route)}
    >
      {c.backdrop && (
        <m.div
          className={shell.detailBackdrop}
          variants={{ hidden: { opacity: 0 }, visible: { opacity: 1, transition: effectsFast } }}
          initial="hidden"
          animate="visible"
          exit={{ opacity: 0, transition: effectsFast }}
          onAnimationComplete={onEntered}
          aria-hidden="true"
        />
      )}
      <div className={shell.detailContent}>
        <SettledContext.Provider value={settled}>
          <Suspense fallback={null}>
            <DetailScreen route={route} from={from} />
          </Suspense>
        </SettledContext.Provider>
      </div>
    </m.main>
  );
}

export function DetailHost() {
  const r = useRoute();
  const plan = useDetailPlan();
  const layers = layersOf(plan);
  const prevCount = useRef(layers.length);
  // Layers present on the first render (a deep link) appear without animating.
  const [firstKeys] = useState(() => new Set(layers.map((d) => d.key)));
  // Keys whose placement flipped (pane <-> layer) in the latest plan change: no enter or exit
  // animation for them. Derived while rendering (React's "adjust state" pattern), not in an effect.
  const [flips, setFlips] = useState<{ plan: readonly PlannedDetail[]; keys: ReadonlySet<string> }>(() => ({ plan, keys: new Set() }));
  if (flips.plan !== plan) setFlips({ plan, keys: flippedKeys(flips.plan, plan) });

  useLayoutEffect(() => {
    const count = layers.length;
    if (count < prevCount.current) {
      // A pop (or a layer moving into a pane): reveal what is underneath before any exit paints.
      if (count === 0) setCovered(shellElements.tabs, false);
      else setCovered(detailEls.get(count - 1), false);
    }
    prevCount.current = count;
  }, [layers.length]);

  return (
    <div className={shell.details}>
      <AnimatePresence initial={false} custom={flips.keys}>
        {layers.map((d, pos) => (
          <DetailLayer
            key={d.key}
            route={d.route}
            from={r.froms[d.index] ?? undefined}
            pos={pos}
            layerKey={d.key}
            focus={d.placement === "focus"}
            instant={firstKeys.has(d.key) || flips.keys.has(d.key)}
          />
        ))}
      </AnimatePresence>
    </div>
  );
}
