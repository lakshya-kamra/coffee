/**
 * Port of CoffeeArt.kt BrewerIcon: a small line drawing of the brewer, one per BrewerArt. Drawn
 * in a 100-unit square (the Kotlin unit coordinates × 100); the ink follows `currentColor`, so it
 * takes a chip's or a label's colour like Compose's LocalContentColor.
 */
import { memo } from "react";
import { brewerArt, type Brewer, type BrewerArt } from "@/domain/core/brewer";

interface Art {
  /** Stroked outline paths. */
  line: string;
  /** Filled shapes (collars, knobs, lids). */
  solid?: string;
}

const box = (l: number, t: number, r: number, b: number, c = 0): string =>
  c > 0
    ? `M${l + c} ${t}H${r - c}Q${r} ${t} ${r} ${t + c}V${b - c}Q${r} ${b} ${r - c} ${b}H${l + c}Q${l} ${b} ${l} ${b - c}V${t + c}Q${l} ${t} ${l + c} ${t}Z`
    : `M${l} ${t}H${r}V${b}H${l}Z`;

const ARTS: Record<BrewerArt, Art> = {
  // A pointed cone on a base plate.
  V60: { line: "M14 22L86 22L54 70L46 70Z M26 82L74 82 M50 70L50 82" },
  // A flat-bottomed cone, wide at the base.
  Kalita: { line: "M14 22L86 22L66 70L34 70Z M24 82L76 82 M40 70L40 82 M60 70L60 82" },
  // The V60 cone on a round valve base with its lever.
  Switch: { line: `M14 18L86 18L54 62L46 62Z ${box(28, 66, 72, 84, 6)} M72 75L88 70` },
  // An hourglass flask with a solid collar at the waist.
  Chemex: { line: "M22 10L78 10L56 46L80 90L20 90L44 46Z", solid: "M36 39L64 39L62 55L38 55Z" },
  // A beaker with a handle, a lid and a knobbed plunger rod.
  FrenchPress: {
    line: `${box(20, 32, 68, 90, 5)} M16 30L72 30 M44 30L44 14 M68 42L84 42L84 76L68 76`,
    solid: "M37 10A7 6 0 1 0 51 10A7 6 0 1 0 37 10Z",
  },
  // A narrow chamber, the plunger in its top, a wide plunger cap and the flanged filter cap.
  AeroPress: { line: `${box(30, 36, 70, 84)} ${box(36, 16, 64, 36)} M24 12L76 12 M22 90L78 90` },
  // Two faceted chambers pinched at the waist, a spout, a lid knob, a handle.
  Moka: {
    line: "M28 90L72 90L64 56L36 56Z M36 52L64 52L72 20L28 20Z M28 20L16 15 M44 12L56 12 M71 24L86 30L84 48L67 46",
  },
  // A demitasse on a saucer.
  Espresso: { line: "M20 34L72 34L66 70L26 70Z M71 40Q92 44 68 60 M10 82L84 82" },
  // A jar with a lid and a fill line.
  ColdBrew: { line: `${box(22, 26, 78, 90, 10)} M30 54L70 54`, solid: box(28, 10, 72, 22, 3) },
  // A drip machine: the head with its filter basket, the tower, a carafe on the hot plate. The
  // coffee in the carafe is the solid, as the cold brew's lid.
  Drip: {
    line: `${box(14, 10, 86, 28, 5)} M50 28L70 28L66 38L54 38Z M24 28L24 90 M14 90L86 90 M44 50L76 50L80 84L40 84Z M78 58Q92 62 80 76`,
    solid: "M42.1 70L77.9 70L80 84L40 84Z",
  },
  // A cezve: a flared lip with a pouring spout, a pinched neck, a wide belly and a long handle
  // whose grip is the solid.
  Cezve: {
    line: "M10 26L54 26 M10 26L6 20 M16 26L24 44L14 84Q14 90 20 90L48 90Q54 90 54 84L44 44L52 26 M47 50L92 34",
    solid: "M69 39L91 31L93 37L71 45Z",
  },
  // A siphon: the upper chamber and its tube, the round lower flask, the stand's pole and clamp.
  Siphon: {
    line: "M34 8L62 8L62 30L52 40L44 40L34 30Z M48 40L48 54 M32 70A16 16 0 1 0 64 70A16 16 0 1 0 32 70Z M82 12L82 92 M62 20L82 20 M16 92L90 92",
    solid: "M32.5 74L63.5 74A16 16 0 0 1 32.5 74Z",
  },
  // A phin: the lid with its knob, the small chamber, the wide plate it rests on, a glass below.
  Phin: {
    line: `M30 16L70 16 ${box(34, 20, 66, 52, 3)} M16 56L84 56 M28 62L32 92L68 92L72 62`,
    solid: "M45 9A5 4 0 1 0 55 9A5 4 0 1 0 45 9Z M31.2 80L68.8 80L68 92L32 92Z",
  },
};

export const BrewerIcon = memo(function BrewerIcon({
  brewer,
  size = 16,
  tint,
  className,
  decorative = false,
}: {
  brewer: Brewer;
  size?: number;
  /** A CSS colour; defaults to the surrounding text colour. */
  tint?: string;
  className?: string;
  /** Hide from screen readers when a visible label already names the brewer. */
  decorative?: boolean;
}) {
  const art = ARTS[brewerArt(brewer)];
  // At least 1 CSS px of stroke at small sizes, as Kotlin's maxOf(1.dp, s * 0.085).
  const stroke = Math.max(100 / size, 8.5);
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 100 100"
      className={className}
      style={tint ? { color: tint } : undefined}
      role={decorative ? undefined : "img"}
      aria-label={decorative ? undefined : brewer.label}
      aria-hidden={decorative ? true : undefined}
      focusable="false"
    >
      <path d={art.line} fill="none" stroke="currentColor" strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round" />
      {art.solid && <path d={art.solid} fill="currentColor" />}
    </svg>
  );
});
