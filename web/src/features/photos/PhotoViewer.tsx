/**
 * PhotoViewer (A§9.2, web-spec §13, v4 §3.13): a full-screen <dialog> with the photo, pinch-zoom
 * and pan written to motion values (transform only, rule 3), kind chips, a caption, the "Send
 * with analyses" toggle and Delete (confirmed). Like a full CoffeeSheet it has its own history
 * entry, so system Back closes the photo and not the screen under it.
 *
 * v4: previous/next buttons and "1 of 2" on every class; Left/Right step, Escape closes, + / - and
 * Ctrl+wheel zoom (0 resets). From an 840 wide window it is a lightbox: the image centred (at most
 * 90vh), arrows that show on hover and are always focusable, and a 320 side panel with the kind,
 * date, caption and Delete.
 */
import { animate } from "motion";
import { m, useMotionValue } from "motion/react";
import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type PointerEvent as ReactPointerEvent } from "react";
import { isTypingTarget } from "@/app/shortcuts";
import { repo } from "@/data/repo";
import type { PhotoKind, PhotoRow } from "@/data/schema";
import { FieldWell, Icon, IconWell, SelectableChip, ToggleRow, tooltipProps, useSheetHistory } from "@/design/components";
import { screenDate, screenTime } from "@/domain/core/format";
import { settle } from "@/design/motion";
import { ConfirmSheet } from "@/viz/ConfirmSheet";
import { forgetPhoto, usePhotoUrl } from "./urlCache";
import { KIND_LABELS, photoAlt } from "./photoText";
import s from "./photos.module.css";

const MAX_ZOOM = 5;
const ZOOM_STEP = 1.25;

/** Where `uid` sits among `photos`, and its neighbours. Pure, for the buttons, the keys and tests. */
export function viewerPosition(photos: readonly Pick<PhotoRow, "uid">[], uid: string): { index: number; count: number; prev: string | null; next: string | null } {
  const index = photos.findIndex((p) => p.uid === uid);
  if (index < 0) return { index: 0, count: 1, prev: null, next: null };
  return { index, count: photos.length, prev: photos[index - 1]?.uid ?? null, next: photos[index + 1]?.uid ?? null };
}

/** The zoom after one + or - (or a wheel notch), kept between 1 and MAX_ZOOM. */
export function stepZoom(current: number, dir: 1 | -1, factor = ZOOM_STEP): number {
  const k = dir > 0 ? current * factor : current / factor;
  return Math.min(MAX_ZOOM, Math.max(1, Math.round(k * 100) / 100));
}

export function PhotoViewer({
  photo,
  photos,
  onShow,
  kinds,
  editable,
  onClose,
}: {
  photo: PhotoRow;
  /** The strip's photos in order: enables previous/next. */
  photos?: readonly PhotoRow[];
  onShow?: (uid: string) => void;
  kinds: PhotoKind[];
  editable: boolean;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const stage = useRef<HTMLDivElement>(null);
  const url = usePhotoUrl(photo.uid, "full");
  const [caption, setCaption] = useState(photo.caption);
  const [captionOf, setCaptionOf] = useState(photo.uid);
  const [confirming, setConfirming] = useState(false);
  const scale = useMotionValue(1);
  const x = useMotionValue(0);
  const y = useMotionValue(0);
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const pinch = useRef<{ d: number; s: number; mx: number; my: number; x: number; y: number } | null>(null);
  // X, Escape and Delete go through this, so they pop the entry Back would have popped.
  const requestClose = useSheetHistory(true, onClose);
  const pos = viewerPosition(photos ?? [photo], photo.uid);
  const many = pos.count > 1;

  // A new photo brings its own caption (React's adjust-state-while-rendering pattern).
  if (captionOf !== photo.uid) {
    setCaptionOf(photo.uid);
    setCaption(photo.caption);
  }

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
  const zoomTo = (k: number) => {
    if (k <= 1.01) reset();
    else animate(scale, k, settle);
  };

  // Each photo opens unzoomed.
  useEffect(() => {
    scale.set(1);
    x.set(0);
    y.set(0);
  }, [photo.uid, scale, x, y]);

  // Ctrl+wheel (and trackpad pinch, which arrives as one) zooms. Native and not passive, so the
  // page itself never zooms instead.
  useEffect(() => {
    const el = stage.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (!e.ctrlKey) return;
      e.preventDefault();
      const k = Math.min(MAX_ZOOM, Math.max(1, scale.get() * Math.exp(-e.deltaY * 0.0025)));
      scale.set(k);
      if (k <= 1.01) {
        x.set(0);
        y.set(0);
      }
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, [scale, x, y]);

  const show = (uid: string | null) => {
    if (uid && onShow) onShow(uid);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDialogElement>) => {
    if (e.defaultPrevented || e.altKey || e.metaKey || e.ctrlKey || isTypingTarget(e.target)) return;
    // The confirm sheet on top handles its own keys.
    if (confirming) return;
    switch (e.key) {
      case "ArrowLeft":
        e.preventDefault();
        show(pos.prev);
        return;
      case "ArrowRight":
        e.preventDefault();
        show(pos.next);
        return;
      case "+":
      case "=":
        e.preventDefault();
        zoomTo(stepZoom(scale.get(), 1));
        return;
      case "-":
      case "_":
        e.preventDefault();
        zoomTo(stepZoom(scale.get(), -1));
        return;
      case "0":
        e.preventDefault();
        reset();
        return;
    }
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

  const when = photo.takenAt > 0 ? `${screenDate(photo.takenAt)}, ${screenTime(photo.takenAt)}` : null;

  return (
    <dialog
      ref={dialog}
      className={s.viewer}
      aria-label={photoAlt(photo)}
      onKeyDown={onKeyDown}
      onCancel={(e) => {
        e.preventDefault();
        requestClose();
      }}
    >
      <div className={s.viewerTop}>
        {many && (
          <span className={`t-label-l ${s.counter}`} aria-live="polite">
            {pos.index + 1} of {pos.count}
          </span>
        )}
        <IconWell icon="Close" label="Close" keys="escape" onClick={requestClose} onCanvas={false} size={44} />
      </div>
      <div className={s.viewerBody}>
        <div className={s.stageWrap}>
          <div
            ref={stage}
            className={s.stage}
            onPointerDown={onDown}
            onPointerMove={onMove}
            onPointerUp={onUp}
            onPointerCancel={onUp}
            onDoubleClick={() => (scale.get() > 1.01 ? reset() : animate(scale, 2, settle))}
          >
            {url && <m.img key={photo.uid} src={url} alt={photoAlt(photo)} className={s.full} style={{ x, y, scale }} draggable={false} decoding="async" width={photo.widthPx} height={photo.heightPx} />}
          </div>
          {many && (
            <>
              <button type="button" className={s.navBtn} data-side="prev" disabled={!pos.prev} aria-label="Previous photo" {...tooltipProps("Previous photo", "left")} onClick={() => show(pos.prev)}>
                <Icon name="ChevronLeft" size={28} />
              </button>
              <button type="button" className={s.navBtn} data-side="next" disabled={!pos.next} aria-label="Next photo" {...tooltipProps("Next photo", "right")} onClick={() => show(pos.next)}>
                <span className={s.flip}>
                  <Icon name="ChevronLeft" size={28} />
                </span>
              </button>
            </>
          )}
        </div>
        <div className={s.viewerPanel} data-editable={editable || undefined}>
          <div className={s.panelHead}>
            <span className="micro ink-low">{KIND_LABELS[photo.kind].toUpperCase()} PHOTO</span>
            {when && <span className="t-body-s ink-mid">{when}</span>}
          </div>
          {editable && (
            <>
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
            </>
          )}
          {!editable && photo.caption.trim() && <p className="t-body-m">{photo.caption}</p>}
          <p className={`t-body-s ink-low ${s.zoomHint}`}>Zoom with + and −, or Ctrl and the scroll wheel. 0 resets.</p>
        </div>
      </div>
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
