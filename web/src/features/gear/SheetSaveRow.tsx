/** The editor sheets' shared bits: in-body Save/Delete, and a bridge to close after a save. */
import { useEffect, type RefObject } from "react";
import { PrimaryButton, QuietButton, useSheetClose, useSheetPresentation } from "@/design/components";
import s from "./gear.module.css";

/** In-body Save and Delete; at the side-sheet presentation Save lives in the sticky header. */
export function SheetSaveRow({ canSave, saving, onSave, deleteText = "Delete", onDelete }: { canSave: boolean; saving: boolean; onSave: () => void; deleteText?: string; onDelete?: () => void }) {
  const side = useSheetPresentation()?.kind === "side";
  return (
    <div className={s.actions}>
      {!side && <PrimaryButton text={saving ? "Saving…" : "Save"} enabled={canSave && !saving} onClick={onSave} />}
      {onDelete && <QuietButton text={deleteText} color="var(--fault)" onClick={onDelete} />}
    </div>
  );
}

/** Hands the sheet's own close (no "Discard changes?" question) to a Save defined outside it. */
export function CloseBridge({ closeRef }: { closeRef: RefObject<(() => void) | null> }) {
  const close = useSheetClose();
  useEffect(() => {
    closeRef.current = close;
  }, [close, closeRef]);
  return null;
}
