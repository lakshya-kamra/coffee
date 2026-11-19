/**
 * Gear › App › YOUR DATA (web-spec §15.4, §8.5): back up, restore, how safe the browser keeps
 * the data, and deleting everything. Insights › SHARE & EXPORT uses SaveBackupRow, the same
 * writer with photos off and analyses on.
 */
import { useLiveQuery } from "dexie-react-hooks";
import { useEffect, useRef, useState } from "react";
import { repo } from "@/data/repo";
import { requestPersist, SAFARI_EVICTION_NOTE, shouldShowSafariNote, storageStatus, type StorageStatus } from "@/data/storage";
import {
  ActionRow,
  CoffeeSheet,
  GroupRow,
  QuietButton,
  QuietPanel,
  SecondaryButton,
  StatusLine,
  ToggleRow,
} from "@/design/components";
import type { Status } from "@/domain/core/status";
import type { ReadResult } from "@/domain/backup/read";
import { DELETE_ALL_CONFIRM, PHOTOS_WONT_ALL_FIT, readErrorText, savedMessage, shortDate } from "./copy";
import { aboutSize, estimateBackupBytes, MAX_BACKUP_BYTES } from "./exporter";
import { ImportPreviewSheet } from "./ImportPreviewSheet";
import { lastBackupAt } from "./meta";
import { saveBackup } from "./save";
import { readBackupFile } from "./workerClient";
import s from "./backup.module.css";

type ReadOk = Extract<ReadResult, { ok: true }>;
type Message = { status: Status; lead: string; rest?: string };

const SAVE_FAILED: Message = { status: "fault", lead: "Not saved", rest: "The backup couldn't be written. Try again." };

/** "12 MB used" (1 MB = 1,000,000 bytes, as phones show sizes). */
function usedText(bytes: number | null): string {
  if (bytes == null) return "Space used unknown";
  return aboutSize(bytes).replace(/^about /, "") + " used";
}

/** The one save path, shared by YOUR DATA and Insights. */
async function runSave(includePhotos: boolean): Promise<Message | null> {
  try {
    const r = await saveBackup({ includePhotos, includeAnalyses: true });
    if (!r.saved) return null;
    return savedMessage(r.built.fileName, r.built.counts, r.built.photosLeftOut, r.built.blob.size);
  } catch (e) {
    console.error("Backup failed", e);
    return SAVE_FAILED;
  }
}

/** Deletes every row, then any saved AI keys, then starts the app fresh. */
async function deleteEverything(): Promise<void> {
  await repo.wipeAll();
  // Keys live in Web Storage and only features/ai/keyStore.ts may touch it (web-spec §14.2).
  try {
    const ai: Record<string, unknown> = await import("@/features/ai");
    const removeAll = ai.removeAllKeys;
    if (typeof removeAll === "function") await (removeAll as () => unknown)();
  } catch (e) {
    console.warn("Could not remove saved keys", e);
  }
  location.replace("/");
}

export function BackupSection() {
  const [includePhotos, setIncludePhotos] = useState(true);
  const [busy, setBusy] = useState<"saving" | "reading" | null>(null);
  const [message, setMessage] = useState<Message | null>(null);
  const [read, setRead] = useState<ReadOk | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [storage, setStorage] = useState<StorageStatus | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const estimate = useLiveQuery(estimateBackupBytes, [], null);
  const last = useLiveQuery(lastBackupAt, [], null);

  useEffect(() => {
    let live = true;
    void storageStatus().then((st) => live && setStorage(st));
    return () => {
      live = false;
    };
  }, []);

  const size = estimate ? aboutSize(includePhotos ? estimate.withPhotos : estimate.withoutPhotos) : null;
  const withPhotos = includePhotos && (estimate?.photoCount ?? 0) > 0;
  // Said before the tap: a file over the import limit could never be restored, so the writer stops short of it.
  const overLimit = withPhotos && estimate != null && estimate.withPhotos > MAX_BACKUP_BYTES;
  const sizeText = overLimit
    ? PHOTOS_WONT_ALL_FIT
    : size
      ? `${size.charAt(0).toUpperCase()}${size.slice(1)}${withPhotos ? " with photos" : ""}`
      : "Everything you logged, in one file";
  const backupSubtitle = last != null ? `${sizeText} · last saved ${shortDate(new Date(last).toISOString())}` : sizeText;

  const onSave = async () => {
    if (busy) return;
    setBusy("saving");
    setMessage(null);
    const m = await runSave(includePhotos);
    setBusy(null);
    if (m) setMessage(m);
  };

  const onFile = async (file: File | undefined) => {
    if (!file) return;
    setBusy("reading");
    setMessage(null);
    const r = await readBackupFile(file);
    setBusy(null);
    if (r.ok) setRead(r);
    else setMessage({ status: "fault", lead: "Can't import", rest: readErrorText(r.error) });
  };

  const onKeepSafe = async () => {
    const granted = await requestPersist();
    const st = await storageStatus();
    setStorage(st);
    if (!granted && !st.persisted) {
      setMessage({ status: "attention", lead: "Not yet", rest: "This browser decides for itself. Installing Coffee or using it often usually changes its mind." });
    }
  };

  return (
    // Gear's App tab puts the YOUR DATA label above this section.
    <section aria-label="Your data">
      <div className={s.group}>
        <ActionRow
          position="first"
          icon="Share"
          title={busy === "saving" ? "Saving a backup…" : "Back up everything"}
          subtitle={backupSubtitle}
          onClick={() => void onSave()}
        />
        <GroupRow position="middle">
          <div className={s.padH}>
            <ToggleRow label="Include photos" checked={includePhotos} onChange={setIncludePhotos} />
          </div>
        </GroupRow>
        <ActionRow
          position="middle"
          icon="History"
          title={busy === "reading" ? "Reading the backup…" : "Restore from a backup"}
          subtitle="From Android or the web, or an older JSON export"
          onClick={() => input.current?.click()}
        />
        <GroupRow position="last">
          <div className={s.storage}>
            {storage == null ? (
              <span className="t-body-m ink-mid">Checking storage…</span>
            ) : storage.persisted ? (
              <StatusLine status="good" lead="Kept safe" rest={usedText(storage.usageBytes)} />
            ) : (
              <>
                <StatusLine status="attention" lead="May be cleared by the browser" rest={usedText(storage.usageBytes)} />
                <QuietButton text="Keep my data safe" onClick={() => void onKeepSafe()} />
              </>
            )}
          </div>
        </GroupRow>
      </div>

      <input
        ref={input}
        type="file"
        accept="application/json,.json,text/plain"
        className="visually-hidden"
        tabIndex={-1}
        aria-hidden="true"
        onChange={(e) => {
          const file = e.currentTarget.files?.[0];
          e.currentTarget.value = ""; // picking the same file again still fires change
          void onFile(file);
        }}
      />

      {message && (
        <div className={s.message} role="status">
          <StatusLine status={message.status} lead={message.lead} rest={message.rest} />
        </div>
      )}

      {storage != null && shouldShowSafariNote(storage) && (
        <div className={s.note}>
          <QuietPanel text={SAFARI_EVICTION_NOTE} />
        </div>
      )}

      <div className={s.note}>
        <ActionRow position="only" icon="Delete" title="Delete everything in this browser" onClick={() => setConfirmDelete(true)} danger />
      </div>

      {read && <ImportPreviewSheet read={read} onClose={() => setRead(null)} />}
      {confirmDelete && <DeleteEverythingSheet onClose={() => setConfirmDelete(false)} />}
    </section>
  );
}

function DeleteEverythingSheet({ onClose }: { onClose: () => void }) {
  const root = useRef<HTMLDivElement>(null);
  const [deleting, setDeleting] = useState(false);
  return (
    <CoffeeSheet label="Delete everything" onClose={onClose}>
      <div ref={root} className={s.sheet}>
        <h2 className={`t-title-l ${s.sheetTitle}`}>Delete everything?</h2>
        <p className="t-body-l">{DELETE_ALL_CONFIRM}</p>
        <div className={s.buttons}>
          <SecondaryButton
            text={deleting ? "Deleting…" : "Delete everything"}
            enabled={!deleting}
            className={s.danger}
            onClick={() => {
              setDeleting(true);
              void deleteEverything();
            }}
          />
          <QuietButton text="Cancel" enabled={!deleting} onClick={() => root.current?.closest("dialog")?.dispatchEvent(new Event("cancel", { cancelable: true }))} />
        </div>
      </div>
    </CoffeeSheet>
  );
}

/** Insights › SHARE & EXPORT: "Save backup (JSON)", photos off, analyses on (lead decision 9). */
export function SaveBackupRow({ includePhotos, label, subtitle }: { includePhotos: boolean; label: string; subtitle: string }) {
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  return (
    <div className={s.group}>
      <ActionRow
        position="only"
        icon="Share"
        title={saving ? "Saving…" : label}
        subtitle={subtitle}
        onClick={() => {
          if (saving) return;
          setSaving(true);
          setMessage(null);
          void runSave(includePhotos).then((m) => {
            setSaving(false);
            if (m) setMessage(m);
          });
        }}
      />
      {message && (
        <div className={s.message} role="status">
          <StatusLine status={message.status} lead={message.lead} rest={message.rest} />
        </div>
      )}
    </div>
  );
}
