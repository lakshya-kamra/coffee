/**
 * Empty-state art (v4 §4.12): the person's own first brewer, drawn large in the brewer line art,
 * else the dripper. Decorative: the empty state's words carry the meaning.
 */
import { memo } from "react";
import type { Brewer } from "@/domain/core/brewer";
import { BrewerIcon } from "./BrewerIcon";
import { Dripper } from "./Mug";
import s from "./art.module.css";

export const EmptyArt = memo(function EmptyArt({ brewer, size = 56 }: { brewer: Brewer | null; size?: number }) {
  return (
    <span className={s.emptyArt} aria-hidden="true">
      {brewer ? <BrewerIcon brewer={brewer} size={size} decorative /> : <Dripper waterLevel={0.5} size={size} shell="var(--empty-art-ink)" />}
    </span>
  );
});
