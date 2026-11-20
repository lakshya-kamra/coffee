/**
 * A short question ("Delete this photo? It can't be undone.") in a CoffeeSheet with role
 * "confirm": a bottom sheet on compact, a centred dialog from medium up (v4 §2.4). The safe
 * action is focused first, never the destructive one (A6); Enter activates the focused button,
 * Escape dismisses. Which button is safe: `safe`, or by default Cancel when the action is
 * `danger` or Cancel keeps its default label, else the action (as in "Leave this brew?" with
 * "Keep it going" / "Discard", or "Resume" / "Discard"). The choice runs after the sheet has
 * closed, so nothing re-renders under a moving panel.
 */
import { useRef } from "react";
import { ConfirmBody, CoffeeSheet, type SheetRole } from "./Sheet";

export function ConfirmSheet({
  title,
  body,
  confirmText,
  cancelText = "Cancel",
  danger = false,
  onConfirm,
  onCancel,
  onDismiss,
  onClose,
  role = "confirm",
  safe,
}: {
  title: string;
  body?: string;
  confirmText: string;
  /** The first button ("Cancel" by default). */
  cancelText?: string;
  danger?: boolean;
  onConfirm: () => void;
  /** The cancel button. */
  onCancel?: () => void;
  /** Escape, the scrim or a drag closed it without a choice. */
  onDismiss?: () => void;
  /** Always called once the sheet has closed (unmount it here). */
  onClose: () => void;
  role?: SheetRole;
  /** The safe action (default: see above). */
  safe?: "cancel" | "confirm";
}) {
  const safeRef = useRef<HTMLButtonElement>(null);
  const safeSide = safe ?? defaultSafe(danger, cancelText);
  const choice = useRef<"confirm" | "cancel" | "dismiss">("dismiss");
  return (
    <CoffeeSheet
      role={role}
      label={title}
      initialFocus={safeRef}
      onClose={() => {
        onClose();
        if (choice.current === "confirm") onConfirm();
        else if (choice.current === "cancel") onCancel?.();
        else onDismiss?.();
      }}
    >
      <ConfirmBody
        title={title}
        body={body}
        cancelText={cancelText}
        confirmText={confirmText}
        danger={danger}
        safe={safeSide}
        safeRef={safeRef}
        onChoice={(c) => {
          choice.current = c;
        }}
      />
    </CoffeeSheet>
  );
}

/**
 * A destructive action, or a plain "Cancel": Cancel is safe. A custom cancel ("Discard") on a
 * non-destructive action means the action is the safe way out.
 */
export function defaultSafe(danger: boolean, cancelText: string): "cancel" | "confirm" {
  return danger || cancelText === "Cancel" ? "cancel" : "confirm";
}
