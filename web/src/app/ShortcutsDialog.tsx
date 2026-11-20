/**
 * The `?` dialog (v4 §2.7): every binding that is live right now, by group, as useShortcuts
 * registered them, plus the list keys every roving list understands. Loaded lazily.
 */
import { useState } from "react";
import { CoffeeSheet } from "@/design/components/Sheet";
import { Kbd } from "@/design/components/Kbd";
import { groupedLive, shortcuts } from "./shortcuts";
import shell from "./shell.module.css";

const LIST_KEYS: readonly { keys: string; label: string }[] = [
  { keys: "down", label: "Next row (also J)" },
  { keys: "up", label: "Previous row (also K)" },
  { keys: "home", label: "First row" },
  { keys: "end", label: "Last row" },
  { keys: "enter", label: "Open" },
];

export default function ShortcutsDialog({ onClose }: { onClose: () => void }) {
  // A snapshot at open: what is live under the dialog, not the dialog's own keys.
  const [groups] = useState(() => groupedLive(shortcuts.live()));
  return (
    <CoffeeSheet role="content" label="Keyboard shortcuts" onClose={onClose}>
      <div className={shell.shortcuts}>
        <h2 className="t-headline-s">Keyboard shortcuts</h2>
        {groups.map((g) => (
          <section key={g.group} className={shell.shortcutGroup} aria-label={g.group}>
            <h3 className="eyebrow">{g.group}</h3>
            <dl>
              {g.bindings.map((b) => (
                <div key={b.keys} className={shell.shortcutRow}>
                  <dt className="t-body-m">{b.label}</dt>
                  <dd>
                    <Kbd keys={b.display ?? b.keys} />
                  </dd>
                </div>
              ))}
            </dl>
          </section>
        ))}
        {!groups.some((g) => g.group === "Lists") && (
          <section className={shell.shortcutGroup} aria-label="In lists">
            <h3 className="eyebrow">In lists</h3>
            <dl>
              {LIST_KEYS.map((b) => (
                <div key={b.keys} className={shell.shortcutRow}>
                  <dt className="t-body-m">{b.label}</dt>
                  <dd>
                    <Kbd keys={b.keys} />
                  </dd>
                </div>
              ))}
            </dl>
          </section>
        )}
      </div>
    </CoffeeSheet>
  );
}
