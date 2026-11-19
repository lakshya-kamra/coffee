/**
 * Details above the tabs, one opaque page at a time (web-spec §6.4). Each detail is a fixed,
 * opaque scroll layer. The tabs underneath never animate: once a push settles they get
 * visibility:hidden (stop painting), and they are made visible again synchronously at the start
 * of a pop, before the exit animation runs. All enter/exit values are opacity and whole
 * transform strings (compositor).
 */
import { AnimatePresence, m, type TargetAndTransition, type Transition } from "motion/react";
import { Suspense, useLayoutEffect, useRef, useState, type CSSProperties } from "react";
import { effectsFast, spatialDefault, spatialFast } from "@/design/motion";
import { DetailScreen } from "./registry";
import { useRoute } from "./router";
import { pathOf, type DetailRoute, type SharedFrom } from "./routes";
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

const detailEls = new Map<number, HTMLElement>();

function setCovered(el: HTMLElement | null | undefined, covered: boolean) {
  if (!el) return;
  if (covered) el.dataset.covered = "true";
  else delete el.dataset.covered;
}

/** Once a push settles: everything below the top stops painting. */
function coverBelow(index: number) {
  setCovered(shellElements.tabs, true);
  for (const [i, el] of detailEls) if (i < index) setCovered(el, true);
}

function DetailLayer({ route, from, index, instant }: { route: DetailRoute; from: SharedFrom | undefined; index: number; instant: boolean }) {
  // Read once per layer: a preference change mid-flight must not swap the choreography.
  const [c] = useState(() => {
    const reduced = prefersReducedMotion();
    const ch = choreographyFor(route.kind, reduced, SHARED_ELEMENT && !reduced);
    const p = recentPointer();
    return p && !ch.backdrop && route.kind === "recipe" ? { ...ch, origin: `${p.x}px ${p.y}px` } : ch;
  });
  const [settled, setSettled] = useState(instant);
  const end = useRef<(() => void) | null>(null);

  useLayoutEffect(() => {
    if (instant) coverBelow(index);
    else end.current = beginTransition();
    return () => end.current?.();
    // Mount-only: a layer's index never changes while it is mounted (keys include the index).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onEntered = (def: unknown) => {
    if (def !== "visible" || settled) return;
    setSettled(true);
    coverBelow(index);
    end.current?.();
    end.current = null;
  };

  const style: CSSProperties | undefined = c.origin ? { transformOrigin: c.origin } : undefined;

  return (
    <m.section
      ref={(el: HTMLElement | null) => {
        if (el) detailEls.set(index, el);
        else detailEls.delete(index);
      }}
      className={shell.detail}
      data-backdrop={c.backdrop || undefined}
      style={style}
      variants={c.backdrop ? undefined : { hidden: c.initial, visible: c.animate }}
      initial={c.backdrop ? undefined : "hidden"}
      animate={c.backdrop ? undefined : "visible"}
      exit={c.exit}
      onAnimationComplete={c.backdrop ? undefined : onEntered}
      aria-label={route.kind}
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
    </m.section>
  );
}

export function DetailHost() {
  const r = useRoute();
  const details = r.stack.slice(1) as DetailRoute[];
  const prevDepth = useRef(details.length);
  // Details present on the first render (a deep link) appear without animating.
  const [bootDepth] = useState(details.length);
  const [firstKeys] = useState(() => new Set(details.map((d, i) => `${i}:${pathOf(d)}`)));

  useLayoutEffect(() => {
    const depth = details.length;
    if (depth < prevDepth.current) {
      // A pop: reveal what is underneath before the exit animation paints its first frame.
      if (depth === 0) setCovered(shellElements.tabs, false);
      else setCovered(detailEls.get(depth - 1), false);
    }
    prevDepth.current = depth;
  }, [details.length]);

  return (
    <div className={shell.details}>
      <AnimatePresence initial={false}>
        {details.map((d, i) => {
          const key = `${i}:${pathOf(d)}`;
          return <DetailLayer key={key} route={d} from={r.froms[i] ?? undefined} index={i} instant={i < bootDepth && firstKeys.has(key)} />;
        })}
      </AnimatePresence>
    </div>
  );
}
