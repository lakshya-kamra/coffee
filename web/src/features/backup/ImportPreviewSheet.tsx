/**
 * What a picked backup holds, how to bring it in, then what happened (web-spec §15.4, A§10.2).
 * The replace confirmation is a step inside this sheet rather than a second dialog on top.
 */
import { useRef, useState } from "react";
import { CoffeeSheet, PrimaryButton, QuietButton, SecondaryButton, SegmentedControl, StatusChip, StatusLine, ToggleRow } from "@/design/components";
import type { ReadResult } from "@/domain/backup/read";
import { applyBackup, ImportError, type ImportResult } from "./apply";
import { IMPORT_FAILED, previewLine, previewNotes, REPLACE_CONFIRM, resultLine, STORAGE_FULL } from "./copy";
import s from "./backup.module.css";

type ReadOk = Extract<ReadResult, { ok: true }>;

type Phase = { kind: "choose" } | { kind: "confirmReplace" } | { kind: "importing" } | { kind: "done"; result: ImportResult } | { kind: "error"; text: string };

const MODES = ["Add to this browser", "Replace everything"] as const;

/** Closes the CoffeeSheet around `el` the way Escape does, so it slides away. */
function closeSheet(el: Element | null): void {
  el?.closest("dialog")?.dispatchEvent(new Event("cancel", { cancelable: true }));
}

export function ImportPreviewSheet({ read, onClose, onImported }: { read: ReadOk; onClose: () => void; onImported?: (r: ImportResult) => void }) {
  const root = useRef<HTMLDivElement>(null);
  const [mode, setMode] = useState(0);
  const [bringSettings, setBringSettings] = useState(false);
  const [bringAi, setBringAi] = useState(false);
  const [phase, setPhase] = useState<Phase>({ kind: "choose" });
  const { doc, preview } = read;
  const replace = mode === 1;
  const busy = phase.kind === "importing";

  const run = async () => {
    setPhase({ kind: "importing" });
    try {
      const result = await applyBackup(read, { mode: replace ? "replace" : "add", bringSettings: bringSettings && doc.settings != null, bringAi: bringAi && doc.ai != null });
      setPhase({ kind: "done", result });
      onImported?.(result);
    } catch (e) {
      setPhase({ kind: "error", text: e instanceof ImportError && e.kind === "storageFull" ? STORAGE_FULL : IMPORT_FAILED });
    }
  };

  const onImport = () => {
    if (replace && phase.kind !== "confirmReplace") setPhase({ kind: "confirmReplace" });
    else void run();
  };

  return (
    <CoffeeSheet label="Restore from a backup" onClose={onClose}>
      <div ref={root} className={s.sheet}>
        <h2 className={`t-title-l ${s.sheetTitle}`}>Restore from a backup</h2>
        <div className={s.lines}>
          <p className={`t-body-l ${s.preview}`}>{previewLine(preview)}</p>
          {previewNotes(preview).map((n) => (
            <p key={n} className="t-body-m">
              {n}
            </p>
          ))}
        </div>

        {phase.kind === "done" ? (
          <>
            <StatusLine status="good" lead="Done" rest={resultLine(phase.result)} />
            {phase.result.skipped.length > 0 && (
              <div className={s.lines}>
                {phase.result.skipped.map((t, i) => (
                  <p key={`${i}-${t}`} className="t-body-s">
                    Left out: {t}
                  </p>
                ))}
              </div>
            )}
            <PrimaryButton text="Done" onClick={() => closeSheet(root.current)} />
          </>
        ) : phase.kind === "confirmReplace" ? (
          <>
            <div className={s.chipLine}>
              <StatusChip status="fault" text="Deletes" />
            </div>
            <p className="t-body-l">{REPLACE_CONFIRM}</p>
            <div className={s.buttons}>
              <SecondaryButton text="Replace everything" onClick={() => void run()} className={s.danger} />
              <QuietButton text="Cancel" onClick={() => setPhase({ kind: "choose" })} />
            </div>
          </>
        ) : (
          <>
            <SegmentedControl options={MODES} selectedIndex={mode} onSelect={(i) => !busy && setMode(i)} label="How to import" />
            {replace && (
              <div className={s.chipLine}>
                <StatusChip status="fault" text="Deletes" />
                <span className="t-body-m">Everything in this browser now is deleted first.</span>
              </div>
            )}
            <div>
              <ToggleRow
                label="Also bring settings"
                subtitle={doc.settings ? "Units, theme, your brewers and defaults" : "This backup has no settings"}
                checked={bringSettings && doc.settings != null}
                disabled={doc.settings == null || busy}
                onChange={setBringSettings}
              />
              <ToggleRow
                label="Also bring AI provider and model (keys never travel)"
                subtitle={doc.ai ? "You add your key again here" : "This backup has no AI choices"}
                checked={bringAi && doc.ai != null}
                disabled={doc.ai == null || busy}
                onChange={setBringAi}
              />
            </div>
            {phase.kind === "error" && <StatusLine status="fault" lead="Not imported" rest={phase.text} />}
            <PrimaryButton text={busy ? "Importing…" : "Import"} enabled={!busy} onClick={onImport} />
          </>
        )}
      </div>
    </CoffeeSheet>
  );
}
