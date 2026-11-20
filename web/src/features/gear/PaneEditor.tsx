/**
 * The inline editor's frame at expanded+ (v4 §3.10): a sticky pane header (eyebrow, the row's
 * name as the pane's h1, Revert, Save with Ctrl/Cmd+S) that stays at the top of the detail pane,
 * so Save is always on screen however long the form is.
 */
import { useEffect, useMemo, useRef } from "react";
import { useRouter } from "@/app/router";
import { useShortcuts } from "@/app/shortcuts";
import { EyebrowLabel, Icon, Kbd } from "@/design/components";
import s from "./gear.module.css";

/** Gear is the visible tab with nothing on top of it: its keys may run. */
export function useGearActive(): boolean {
  return useRouter((r) => r.tab === "gear" && r.depth === 0);
}

export function PaneEditorHeader({
  isNew = false,
  eyebrow,
  title,
  dirty,
  canSave,
  saving,
  onSave,
  onRevert,
}: {
  isNew?: boolean;
  eyebrow: string;
  title: string;
  dirty: boolean;
  canSave: boolean;
  saving: boolean;
  onSave: () => void;
  onRevert: () => void;
}) {
  const active = useGearActive();
  const keys = useMemo(() => [{ keys: "mod+s", label: "Save", group: "Editing", run: onSave, whileTyping: true }], [onSave]);
  useShortcuts("gear-editor", keys, active);
  // A new item (Add or N) opens here instead of in a sheet, so focus has to follow it, or the
  // next keystroke runs a shortcut instead of typing the name (A5). The form is the next sibling.
  const head = useRef<HTMLElement>(null);
  useEffect(() => {
    if (!isNew || !active) return;
    const form = head.current?.nextElementSibling;
    const first = form?.querySelector<HTMLElement>("input:not([type=hidden]), textarea, select, button");
    first?.focus({ preventScroll: true });
    // Once, when the new editor appears.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return (
    <header ref={head} className={s.paneHeader}>
      <div className={s.paneHeaderText}>
        <EyebrowLabel text={eyebrow} />
        <h1 className={`t-headline-m ${s.paneTitle}`}>{title}</h1>
        <p className={`t-body-s ${s.paneState}`} aria-live="polite">
          {saving ? "Saving…" : isNew ? "Not saved yet" : dirty ? "Unsaved changes" : "All changes saved"}
        </p>
      </div>
      <div className={s.paneActions}>
        <button type="button" className={`${s.paneRevert} t-label-l`} disabled={!dirty || saving} onClick={onRevert}>
          Revert
        </button>
        <button type="button" className={`${s.paneSave} t-label-l`} disabled={!canSave || saving} onClick={onSave} aria-keyshortcuts="Control+S Meta+S">
          {saving ? "Saving…" : "Save"}
          <Kbd keys="mod+s" className={s.paneKbd} />
        </button>
      </div>
    </header>
  );
}

/** The quiet destructive row at the end of an inline editor (it asks first). */
export function PaneDeleteRow({ text, onClick }: { text: string; onClick: () => void }) {
  return (
    <div className={s.paneDelete}>
      <button type="button" className={`${s.paneDeleteBtn} t-label-l`} onClick={onClick} aria-keyshortcuts="Delete">
        <Icon name="Delete" size={20} />
        {text}
      </button>
    </div>
  );
}
