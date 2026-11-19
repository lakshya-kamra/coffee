/**
 * PhotoViewer (A§9.2, web-spec §13): a full-screen <dialog> with the photo, pinch-zoom and pan
 * written to motion values (transform only, rule 3), kind chips, a caption, the "Send with
 * analyses" toggle and Delete (confirmed). Like a full CoffeeSheet it has its own history entry,
 * so system Back closes the photo and not the screen under it.
 */
import { animate } from "motion";
import { m, useMotionValue } from "motion/react";
import { useLayoutEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { repo } from "@/data/repo";
import type { PhotoKind, PhotoRow } from "@/data/schema";
import { FieldWell, IconWell, SelectableChip, ToggleRow, useSheetHistory } from "@/design/components";
import { settle } from "@/design/motion";
import { ConfirmSheet } from "@/viz/ConfirmSheet";
import { forgetPhoto, usePhotoUrl } from "./urlCache";
import { KIND_LABELS, photoAlt } from "./photoText";
import s from "./photos.module.css";

const MAX_ZOOM = 5;

export function PhotoViewer({ photo, kinds, editable, onClose }: { photo: PhotoRow; kinds: PhotoKind[]; editable: boolean; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const url = usePhotoUrl(photo.uid, "full");
  const [caption, setCaption] = useState(photo.caption);
  const [confirming, setConfirming] = useState(false);
  const scale = useMotionValue(1);
  const x = useMotionValue(0);
  const y = useMotionValue(0);
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const pinch = useRef<{ d: number; s: number; mx: number; my: number; x: number; y: number } | null>(null);
  // X, Escape and Delete go through this, so they pop the entry Back would have popped.
  const requestClose = useSheetHistory(true, onClose);

  useLayoutEffect(() => {
    const d = dialog.current;
    if (d && !d.open) d.showModal();
    return () => d?.close();
  }, []);

  const save = (patch: Partial<PhotoRow>) => void repo.updatePhoto({ ...photo, ...patch });

  const reset = () => {
    animate(scale, 1, settle);
    animate(x, 0, settle);
    animate(y, 0, settle);
  };

  const onDown = (e: ReactPointerEvent) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()] as [{ x: number; y: number }, { x: number; y: number }];
      pinch.current = { d: Math.hypot(a.x - b.x, a.y - b.y) || 1, s: scale.get(), mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2, x: x.get(), y: y.get() };
    }
  };
  const onMove = (e: ReactPointerEvent) => {
    const prev = pointers.current.get(e.pointerId);
    if (!prev) return;
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.current.size >= 2 && pinch.current) {
      const [a, b] = [...pointers.current.values()] as [{ x: number; y: number }, { x: number; y: number }];
      const p = pinch.current;
      const k = Math.min(MAX_ZOOM, Math.max(0.8, (p.s * Math.hypot(a.x - b.x, a.y - b.y)) / p.d));
      scale.set(k);
      x.set(p.x + (a.x + b.x) / 2 - p.mx);
      y.set(p.y + (a.y + b.y) / 2 - p.my);
    } else if (scale.get() > 1.01) {
      x.set(x.get() + e.clientX - prev.x);
      y.set(y.get() + e.clientY - prev.y);
    }
  };
  const onUp = (e: ReactPointerEvent) => {
    pointers.current.delete(e.pointerId);
    if (pointers.current.size < 2) pinch.current = null;
    if (pointers.current.size === 0 && scale.get() < 1.02) reset();
  };

  return (
    <dialog
      ref={dialog}
      className={s.viewer}
      aria-label={photoAlt(photo)}
      onCancel={(e) => {
        e.preventDefault();
        requestClose();
      }}
    >
      <div className={s.viewerTop}>
        <IconWell icon="Close" label="Close" onClick={requestClose} onCanvas={false} />
      </div>
      <div
        className={s.stage}
        onPointerDown={onDown}
        onPointerMove={onMove}
        onPointerUp={onUp}
        onPointerCancel={onUp}
        onDoubleClick={() => (scale.get() > 1.01 ? reset() : animate(scale, 2, settle))}
      >
        {url && <m.img src={url} alt={photoAlt(photo)} className={s.full} style={{ x, y, scale }} draggable={false} decoding="async" width={photo.widthPx} height={photo.heightPx} />}
      </div>
      {editable && (
        <div className={s.viewerPanel}>
          <div className={s.chips} role="group" aria-label="What it shows">
            {kinds.map((k) => (
              <SelectableChip key={k} text={KIND_LABELS[k]} selected={photo.kind === k} onClick={() => save({ kind: k })} />
            ))}
          </div>
          <div onBlur={() => caption !== photo.caption && save({ caption })}>
            <FieldWell label="Caption" value={caption} onChange={setCaption} placeholder="What it shows" maxLength={200} />
          </div>
          <ToggleRow label="Send with analyses" subtitle="Only to models that can see images." checked={photo.sendToAi} onChange={(on) => save({ sendToAi: on })} />
          <button type="button" className={`${s.delete} t-label-l`} onClick={() => setConfirming(true)}>
            Delete photo
          </button>
        </div>
      )}
      {confirming && (
        <ConfirmSheet
          title="Delete this photo?"
          body="It can't be undone."
          confirmText="Delete"
          danger
          onClose={() => setConfirming(false)}
          onConfirm={() => {
            forgetPhoto(photo.uid);
            void repo.deletePhoto(photo.id!);
            requestClose();
          }}
        />
      )}
    </dialog>
  );
}
