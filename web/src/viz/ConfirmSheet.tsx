/**
 * A short question in a CoffeeSheet ("Delete this photo? It can't be undone."). The choice runs
 * after the sheet has slid away, so nothing re-renders under a moving panel. Closing is routed
 * through the dialog's own cancel path, which plays the close animation.
 */
import { useRef } from "react";
import { CoffeeSheet } from "@/design/components";
import v from "./confirm.module.css";

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
}: {
  title: string;
  body?: string;
  confirmText: string;
  cancelText?: string;
  danger?: boolean;
  onConfirm: () => void;
  /** The cancel button. */
  onCancel?: () => void;
  /** Escape, the scrim or a drag closed it without a choice. */
  onDismiss?: () => void;
  /** Always called once the sheet has closed (unmount it here). */
  onClose: () => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const choice = useRef<"confirm" | "cancel" | "dismiss">("dismiss");
  const close = (c: "confirm" | "cancel") => {
    choice.current = c;
    box.current?.closest("dialog")?.dispatchEvent(new Event("cancel", { cancelable: true }));
  };
  return (
    <CoffeeSheet
      label={title}
      onClose={() => {
        onClose();
        if (choice.current === "confirm") onConfirm();
        else if (choice.current === "cancel") onCancel?.();
        else onDismiss?.();
      }}
    >
      <div ref={box} className={v.box}>
        <h2 className="t-headline-s">{title}</h2>
        {body && <p className="t-body-m ink-mid">{body}</p>}
        <div className={v.actions}>
          <button type="button" className={`${v.btn} t-label-l`} onClick={() => close("cancel")}>
            {cancelText}
          </button>
          <button type="button" className={`${v.btn} ${danger ? v.danger : v.go} t-label-l`} onClick={() => close("confirm")}>
            {confirmText}
          </button>
        </div>
      </div>
    </CoffeeSheet>
  );
}
