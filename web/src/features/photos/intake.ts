/**
 * Photos from the desktop (v4 §4.14): drag image files onto a photo strip, or paste one with
 * Ctrl/Cmd+V. While files are dragged over the strip's screen the strip shows its drop zone;
 * paste works when that screen is the one in front and no text field has focus. Everything still
 * goes through the strip's own add path (pipeline.ts, same limits and messages).
 */
import { useEffect, useRef, useState } from "react";
import { activeContainer } from "@/app/a11y";
import { isTypingTarget } from "@/app/shortcuts";

/** The files in a drop or a paste that could be photos. An empty type is let through: the pipeline decides. */
export function imageFiles(list: FileList | readonly File[] | null | undefined): File[] {
  if (!list) return [];
  return Array.from(list as ArrayLike<File>).filter((f) => f.type === "" || f.type.startsWith("image/"));
}

/** True while what is dragged includes files (not text or a link from the page). */
export function dragHasFiles(dt: Pick<DataTransfer, "types"> | null | undefined): boolean {
  if (!dt) return false;
  return Array.from(dt.types as ArrayLike<string>).includes("Files");
}

/**
 * Whether `el` is on the screen in front: inside the top open dialog when one is open, else inside
 * the shell's active layer or tab. Outside the app shell (tests, a bare page) anything visible counts.
 */
export function onActiveScreen(el: Element): boolean {
  const dialogs = document.querySelectorAll("dialog[open]");
  const top = dialogs[dialogs.length - 1];
  if (top) return top.contains(el);
  if (el.closest("[inert], [data-hidden], [aria-hidden='true']")) return false;
  const active = activeContainer();
  return active ? active.contains(el) : true;
}

export interface IntakeOptions {
  /** Off: nothing is listened to (a read-only strip, or one that is full). */
  readonly enabled: boolean;
  readonly onFiles: (files: File[]) => void;
  /** Injected in tests. */
  readonly isActive?: (el: Element) => boolean;
}

/**
 * Window-level drag and paste for one strip. Returns `dragging` (show the drop zone) and the
 * handlers for the zone itself, which is the only place a drop lands; a drop anywhere else on the
 * screen is swallowed so the browser never navigates away to the image.
 */
export function usePhotoIntake(ref: React.RefObject<HTMLElement | null>, { enabled, onFiles, isActive = onActiveScreen }: IntakeOptions) {
  const [dragging, setDragging] = useState(false);
  const depth = useRef(0);
  const filesRef = useRef(onFiles);
  const activeRef = useRef(isActive);
  useEffect(() => {
    filesRef.current = onFiles;
    activeRef.current = isActive;
  });

  useEffect(() => {
    if (!enabled) return;
    const mine = () => {
      const el = ref.current;
      return el != null && activeRef.current(el);
    };
    const reset = () => {
      depth.current = 0;
      setDragging(false);
    };
    const onEnter = (e: DragEvent) => {
      if (!dragHasFiles(e.dataTransfer) || !mine()) return;
      depth.current++;
      setDragging(true);
    };
    const onLeave = (e: DragEvent) => {
      if (!dragHasFiles(e.dataTransfer)) return;
      depth.current = Math.max(0, depth.current - 1);
      // Leaving the window (relatedTarget null) or the last element: the zone goes.
      if (depth.current === 0 || e.relatedTarget == null) reset();
    };
    const onOver = (e: DragEvent) => {
      if (!dragHasFiles(e.dataTransfer) || !mine()) return;
      // Without this the drop event never fires and the browser opens the file instead.
      e.preventDefault();
      if (e.dataTransfer) e.dataTransfer.dropEffect = ref.current?.contains(e.target as Node) ? "copy" : "none";
    };
    const onDrop = (e: DragEvent) => {
      if (!dragHasFiles(e.dataTransfer) || !mine()) return;
      e.preventDefault();
      reset();
      if (ref.current?.contains(e.target as Node)) {
        const files = imageFiles(e.dataTransfer?.files);
        if (files.length) filesRef.current(files);
      }
    };
    const onPaste = (e: ClipboardEvent) => {
      if (e.defaultPrevented || isTypingTarget(document.activeElement) || !mine()) return;
      const files = imageFiles(e.clipboardData?.files);
      if (files.length === 0) return;
      e.preventDefault();
      filesRef.current(files);
    };
    window.addEventListener("dragenter", onEnter);
    window.addEventListener("dragleave", onLeave);
    window.addEventListener("dragover", onOver);
    window.addEventListener("drop", onDrop);
    window.addEventListener("dragend", reset);
    window.addEventListener("paste", onPaste);
    return () => {
      window.removeEventListener("dragenter", onEnter);
      window.removeEventListener("dragleave", onLeave);
      window.removeEventListener("dragover", onOver);
      window.removeEventListener("drop", onDrop);
      window.removeEventListener("dragend", reset);
      window.removeEventListener("paste", onPaste);
      reset();
    };
  }, [enabled, ref]);

  return { dragging };
}
