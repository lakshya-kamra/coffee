/**
 * The recipe header's flight between a card and the detail (web-spec §6.5), done by hand as a
 * FLIP: measure both boxes once, then one WAAPI animation of a whole `transform` string, which
 * the browser runs on the compositor. No frame of the flight needs the main thread.
 *
 * It replaces Motion's `layoutId` here for two measured reasons. Motion's shared stack skips the
 * animation when the old and new lead carry the same `layoutDependency`, which is how the cards
 * were written, so the header never flew. With that fixed, every card on Today and Recipes was a
 * projection node that Motion re-measured at the start of each flight: at 4× CPU throttle the
 * frame that closed a recipe took 250–440 ms. Two `getBoundingClientRect` calls cost nothing
 * next to that, and the text scales with the box just as it did under Motion.
 */
import { animate, type AnimationPlaybackControls } from "motion";
import { bounds } from "@/design/motion";
import { SHARED_ELEMENT } from "./sharedKeys";
import { prefersReducedMotion } from "./transitionState";

/** True when shared-element flights run: the flag is on and motion is not reduced. */
export function sharedFlightsOn(): boolean {
  return SHARED_ELEMENT && !prefersReducedMotion();
}

/** Card headers by shared key (`SharedKeys.recipeHeader`), registered while they are mounted. */
const sources = new Map<string, HTMLElement>();
const flights = new WeakMap<HTMLElement, AnimationPlaybackControls>();

const REST = "translate(0px, 0px) scale(1, 1)";

export function registerSharedSource(key: string, el: HTMLElement): () => void {
  sources.set(key, el);
  return () => {
    if (sources.get(key) === el) sources.delete(key);
  };
}

function settle(el: HTMLElement) {
  flights.delete(el);
  el.style.removeProperty("transform");
  el.style.removeProperty("transform-origin");
  el.style.removeProperty("will-change");
}

/** Stops a flight on `el` and puts it back in its own place, so it can be measured. */
function land(el: HTMLElement) {
  flights.get(el)?.stop();
  settle(el);
}

/** A card in the tab the user is looking at, at least partly on screen. */
function usable(el: HTMLElement | undefined): el is HTMLElement {
  if (!el?.isConnected) return false;
  return el.closest<HTMLElement>("section[data-tab]")?.dataset.state === "active";
}

function onScreen(r: DOMRect): boolean {
  return r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < window.innerHeight && r.left < window.innerWidth;
}

/** Animates `el` from the box `from` (viewport px) to its own place. */
function fly(el: HTMLElement, from: DOMRect, done?: () => void): boolean {
  const to = el.getBoundingClientRect();
  if (!onScreen(from) || to.width <= 0 || to.height <= 0) return false;
  const start = `translate(${from.left - to.left}px, ${from.top - to.top}px) scale(${from.width / to.width}, ${from.height / to.height})`;
  el.style.transformOrigin = "0 0";
  el.style.willChange = "transform";
  // Both keyframes have the same shape, so WAAPI interpolates functions, not a matrix.
  const a = animate(el, { transform: [start, REST] }, bounds);
  flights.set(el, a);
  void a.then(() => {
    if (flights.get(el) !== a) return; // superseded
    settle(el);
    done?.();
  });
  return true;
}

/** The detail header has mounted: fly it in from the card it was opened from. */
export function flyFromCard(key: string, detail: HTMLElement): void {
  const card = sources.get(key);
  if (!sharedFlightsOn() || !usable(card)) return;
  land(card);
  const from = card.getBoundingClientRect();
  // One header on screen: the card's hides while its twin flies. By the time the flight ends
  // the tabs are covered by the detail, so giving it back is invisible.
  card.style.visibility = "hidden";
  if (!fly(detail, from, () => card.style.removeProperty("visibility"))) card.style.removeProperty("visibility");
}

/** The detail is leaving: the card's header flies home from where the detail's header is now. */
export function flyBackToCard(key: string, detail: HTMLElement): void {
  const card = sources.get(key);
  if (!sharedFlightsOn() || !usable(card)) return;
  // Measured before anything is reset, so a Back in mid-flight continues from where the header is.
  const from = detail.getBoundingClientRect();
  flights.get(detail)?.stop();
  card.style.removeProperty("visibility");
  land(card);
  fly(card, from);
}
