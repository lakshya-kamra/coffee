/**
 * Port of MeshGradient.kt: soft colour blobs drifting over a flat base, the colour of the room
 * being the phase of the brew. The drift is CSS keyframes on transform (compositor); a mood change
 * cross-fades a second layer's opacity with effectsSlow, so a phase change feels like the room
 * changed rather than a value changed. Intensity and the bottom floor are opacity springs.
 */
import { m } from "motion/react";
import { memo, useState } from "react";
import { effectsSlow, heavy } from "@/design/motion";
import type { BrewMood } from "./journey";
import v from "./viz.module.css";

function Layer({ mood, shown }: { mood: BrewMood; shown: boolean }) {
  return (
    <m.div className={v.meshLayer} data-mood={mood} initial={false} animate={{ opacity: shown ? 1 : 0 }} transition={effectsSlow} aria-hidden="true">
      <span className={`${v.blobX} ${v.blobXA}`} data-loop="">
        <span className={`${v.blobY} ${v.blobYA}`} data-loop="">
          <span className={`${v.blob} ${v.blobA}`} />
        </span>
      </span>
      <span className={`${v.blobX} ${v.blobXB}`} data-loop="">
        <span className={`${v.blobY} ${v.blobYB}`} data-loop="">
          <span className={`${v.blob} ${v.blobB}`} />
        </span>
      </span>
      <span className={`${v.blobX} ${v.blobXC}`} data-loop="">
        <span className={`${v.blobY} ${v.blobYC}`} data-loop="">
          <span className={`${v.blob} ${v.blobC}`} />
        </span>
      </span>
    </m.div>
  );
}

export const MeshGradient = memo(function MeshGradient({
  mood,
  intensity = 1,
  floor = 0,
}: {
  mood: BrewMood;
  /** 0 = flat base only, 1 = full colour. Dial down behind dense content. */
  intensity?: number;
  /** px at the bottom where the mesh settles into the flat canvas, so a sticky bar meets it without a seam. */
  floor?: number;
}) {
  // Two layers: the new mood goes into the hidden one, which fades in over the shown one.
  const [layers, setLayers] = useState<{ moods: [BrewMood, BrewMood]; front: 0 | 1 }>({ moods: [mood, mood], front: 0 });
  if (layers.moods[layers.front] !== mood) {
    const back = layers.front === 0 ? 1 : 0;
    const moods: [BrewMood, BrewMood] = [...layers.moods];
    moods[back] = mood;
    setLayers({ moods, front: back });
  }
  // The floor keeps its last height while it fades out: height never animates (rule 1).
  const [floorPx, setFloorPx] = useState(floor);
  if (floor > 0 && floor !== floorPx) setFloorPx(floor);
  return (
    <div className={v.mesh} aria-hidden="true">
      <m.div className={v.meshBlobs} initial={false} animate={{ opacity: intensity }} transition={heavy}>
        <Layer mood={layers.moods[0]} shown={layers.front === 0} />
        <Layer mood={layers.moods[1]} shown={layers.front === 1} />
      </m.div>
      <span className={v.meshVignette} />
      <m.span className={v.meshFloor} style={{ height: Math.max(1, floorPx) * 2 }} initial={false} animate={{ opacity: floor > 0 ? 1 : 0 }} transition={heavy} />
    </div>
  );
});
