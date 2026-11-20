/**
 * Port of RecipeCard.kt. It reads like a flight: who and which brewer, the name, then the route
 * from coffee to water with the ratio under the line. The recipe's own colour survives only as
 * the identity dot. The header is the shared element (web-spec §6.5): RecipeHeader is the same
 * tree on the card and on the detail, so the flight only scales and translates (app/sharedFlight).
 */
import { useIsPresent } from "motion/react";
import { memo, useContext, useLayoutEffect, useRef, type ReactNode } from "react";
import type { SharedFrom } from "@/app/routes";
import { flyBackToCard, flyFromCard, registerSharedSource, sharedFlightsOn } from "@/app/sharedFlight";
import { SharedKeys } from "@/app/sharedKeys";
import { SettledContext } from "@/app/transitionState";
import { BrewerIcon } from "@/art/BrewerIcon";
import { toCaps } from "@/design/components";
import { brewerShortName } from "@/domain/core/brewer";
import { clock, grams } from "@/domain/core/format";
import { accentCss, recipeRatioLabel, totalSec, type Recipe } from "@/domain/core/recipe";
import { temp, tempLabel, tempUnit, unitsOf, type Units } from "@/domain/core/units";
import { useSettings } from "@/data/store";
import { InfoGrid } from "./InfoGrid";
import { RouteRow } from "./JourneyLine";
import { pourNodes } from "./journey";
import v from "./viz.module.css";

const HEADER_RADII = { borderTopLeftRadius: 22, borderTopRightRadius: 22, borderBottomLeftRadius: 0, borderBottomRightRadius: 0 } as const;

export { sharedFlightsOn };

export const RecipeHeader = memo(function RecipeHeader({
  recipe,
  size,
  sharedPrefix,
  compact = false,
  eyebrow,
  headingLevel,
}: {
  recipe: Recipe;
  size: "card" | "detail";
  /** The list this header sits in; omit for a header that never flies. */
  sharedPrefix?: SharedFrom;
  compact?: boolean;
  /** A line above the author row ("NEXT BREW"). */
  eyebrow?: ReactNode;
  /** The name as a heading (the detail's h1). */
  headingLevel?: 1 | 2 | 3;
}) {
  const detail = size === "detail";
  const box = useRef<HTMLDivElement>(null);
  const key = sharedPrefix ? SharedKeys.recipeHeader(sharedPrefix, recipe.id) : null;
  // A detail restored by a reload or a deep link is already settled when it mounts: no flight.
  const arrivedSettled = useRef(useContext(SettledContext));
  const present = useIsPresent();

  // A card's header is a place to fly from and back to; the detail's flies in when it mounts.
  useLayoutEffect(() => {
    const el = box.current;
    if (!key || !el) return;
    if (!detail) return registerSharedSource(key, el);
    if (!arrivedSettled.current) flyFromCard(key, el);
    return undefined;
  }, [key, detail]);
  // The detail is being popped (AnimatePresence keeps it mounted while it fades).
  useLayoutEffect(() => {
    if (detail && key && !present && box.current) flyBackToCard(key, box.current);
  }, [detail, key, present]);

  const nameClass = detail ? "t-headline-l" : compact ? v.nameCompact : "t-headline-s";
  const NameTag = headingLevel ? (`h${headingLevel}` as const) : "span";
  const body = (
    <>
      {eyebrow}
      <span className={v.headerMeta}>
        <span className={v.accentDot} style={{ background: accentCss(recipe.accent), width: compact ? 6 : 8, height: compact ? 6 : 8 }} />
        <span className={`micro ink-low ${v.ellipsis} ${v.grow}`}>{toCaps(recipe.author)}</span>
        <BrewerIcon brewer={recipe.brewer} size={detail ? 14 : 12} tint="var(--ink-low)" decorative />
        <span className="micro ink-low">{toCaps(detail ? recipe.brewer.label : brewerShortName(recipe.brewer))}</span>
      </span>
      {/* The card name wraps in full (A7: at 200% text a 2-line clamp cut long names off). */}
      <NameTag className={`${nameClass} ${detail ? v.clamp3 : ""} ${v.headerName}`}>{recipe.name}</NameTag>
    </>
  );
  return (
    <div ref={box} className={`${v.header} ${detail ? v.headerDetail : v.headerCard}`} style={HEADER_RADII}>
      {body}
    </div>
  );
});

/** "30 g ─○─○─ 500 g · RATIO 1:16.7": the route row every recipe surface shows. */
export function RecipeRoute({ recipe, numeral = "num-large" }: { recipe: Recipe; numeral?: "num-large" | "num-medium" | "num-small" }) {
  return (
    <RouteRow
      mode="planned"
      from={grams(recipe.doseG)}
      fromUnit="g"
      fromLabel="COFFEE"
      to={grams(recipe.waterG)}
      toUnit="g"
      toLabel="WATER"
      nodes={pourNodes(recipe)}
      midLabel={recipeRatioLabel(recipe)}
      numeral={numeral}
    />
  );
}

function CardBody({ recipe, compact, units }: { recipe: Recipe; compact: boolean; units: Units }) {
  return (
    <span className={v.cardBody}>
      <RecipeRoute recipe={recipe} numeral={compact ? "num-small" : "num-medium"} />
      {compact ? (
        <span className={`t-body-s ink-mid ${v.ellipsis} ${v.cardMeta}`}>
          {clock(totalSec(recipe))} · {tempLabel(units, recipe.tempC)}
        </span>
      ) : (
        <span className={v.cardGrid}>
          <InfoGrid
            compact
            cells={[
              { label: "TIME", value: clock(totalSec(recipe)) },
              { label: "TEMP", value: temp(units, recipe.tempC), unit: tempUnit(units) },
              { label: "GRIND", value: recipe.grind || null, textValue: true },
            ]}
          />
        </span>
      )}
    </span>
  );
}

/**
 * The recipe card on the Today carousel (compact) and in the library. `sharedPrefix` names the
 * list it sits in, so the header can fly into the detail; Today and Recipes use different prefixes
 * because keep-alive tabs are all mounted at once.
 */
export const RecipeCard = memo(function RecipeCard({
  recipe,
  sharedPrefix,
  onOpen,
  compact = false,
  onPrefetch,
}: {
  recipe: Recipe;
  sharedPrefix: SharedFrom;
  onOpen: () => void;
  compact?: boolean;
  onPrefetch?: () => void;
}) {
  const settings = useSettings();
  const units = unitsOf(settings);
  return (
    <button type="button" className={v.card} data-compact={compact || undefined} onClick={onOpen} onPointerDown={onPrefetch}>
      <RecipeHeader recipe={recipe} size="card" sharedPrefix={sharedPrefix} compact={compact} />
      <CardBody recipe={recipe} compact={compact} units={units} />
    </button>
  );
});
