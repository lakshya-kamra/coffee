/**
 * PhotoStrip (A§9.2, web-spec §13): 72 px rounded tiles with a kind label and an add tile. Adding
 * opens a small sheet: kind chips (Bed / Cup / Bag / Other, as allowed), then "Take photo" or
 * "Choose from library". Desktop also accepts paste and drag-and-drop. No getUserMedia, so no
 * camera permission is ever asked for (Permissions-Policy keeps camera=()).
 */
import { useLiveQuery } from "dexie-react-hooks";
import { useRef, useState, type ClipboardEvent, type DragEvent } from "react";
import { TumblingBeans } from "@/art";
import { repo } from "@/data/repo";
import { newUid, type PhotoKind, type PhotoRow } from "@/data/schema";
import { persistOnce } from "@/data/storage";
import { CoffeeSheet, Icon, SelectableChip } from "@/design/components";
import { PhotoError, processPhoto } from "./pipeline";
import { MAX_PER_BEAN, MAX_PER_BREW, PhotoErrors } from "./photoMath";
import { KIND_LABELS, photoAlt } from "./photoText";
import { PhotoViewer } from "./PhotoViewer";
import { usePhotoUrl } from "./urlCache";
import s from "./photos.module.css";

function Tile({ photo, onOpen }: { photo: PhotoRow; onOpen: () => void }) {
  const url = usePhotoUrl(photo.uid, "thumb");
  return (
    <button type="button" className={s.tile} onClick={onOpen} aria-label={`Open ${photoAlt(photo)}`}>
      {url ? <img src={url} alt="" width={72} height={72} decoding="async" loading="lazy" className={s.thumb} /> : <span className={s.thumbEmpty} />}
      <span className={`micro ${s.kindTag}`}>{KIND_LABELS[photo.kind]}</span>
    </button>
  );
}

export function PhotoStrip({ brewId, beanId, kinds, editable = true }: { brewId?: number; beanId?: number; kinds: PhotoKind[]; editable?: boolean }) {
  const photos = useLiveQuery(
    () => (brewId != null ? repo.photosForBrew(brewId) : beanId != null ? repo.photosForBean(beanId) : Promise.resolve([] as PhotoRow[])),
    [brewId, beanId],
  );
  const [adding, setAdding] = useState(false);
  const [kind, setKind] = useState<PhotoKind>(kinds[0] ?? "other");
  const [busy, setBusy] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [openUid, setOpenUid] = useState<string | null>(null);
  const camera = useRef<HTMLInputElement>(null);
  const library = useRef<HTMLInputElement>(null);
  const sheetBox = useRef<HTMLDivElement>(null);

  const max = brewId != null ? MAX_PER_BREW : MAX_PER_BEAN;
  const count = photos?.length ?? 0;
  const full = count + busy >= max;
  const open = photos?.find((p) => p.uid === openUid) ?? null;

  const addFiles = async (files: readonly File[], k: PhotoKind) => {
    const images = files.filter((f) => f.type === "" || f.type.startsWith("image/"));
    if (images.length === 0) return;
    const room = Math.max(0, max - count - busy);
    setError(room < images.length ? (brewId != null ? PhotoErrors.brewFull : PhotoErrors.beanFull) : null);
    const take = images.slice(0, room);
    setBusy((b) => b + take.length);
    for (const f of take) {
      try {
        const out = await processPhoto(f);
        const now = Date.now();
        await repo.addPhoto(
          {
            uid: newUid(),
            brewLogId: brewId ?? null,
            beanId: beanId ?? null,
            kind: k,
            mediaType: "image/jpeg",
            widthPx: out.width,
            heightPx: out.height,
            bytes: out.full.size,
            takenAt: out.takenAt,
            createdAt: now,
            caption: "",
            // Lead decision 9: photos go with analyses unless unticked.
            sendToAi: true,
          },
          out.full,
          out.thumb,
        );
        void persistOnce();
      } catch (e) {
        setError(e instanceof PhotoError ? e.message : PhotoErrors.format);
      } finally {
        setBusy((b) => b - 1);
      }
    }
  };

  const onPicked = (input: HTMLInputElement | null) => {
    if (!input?.files) return;
    const files = [...input.files];
    input.value = "";
    closeSheet();
    void addFiles(files, kind);
  };

  // The inputs live inside the sheet: a modal <dialog> makes everything outside it inert. The
  // sheet closes once files arrive (or the picker is cancelled).
  const closeSheet = () => sheetBox.current?.closest("dialog")?.dispatchEvent(new Event("cancel", { cancelable: true }));
  const pick = (which: "camera" | "library") => (which === "camera" ? camera : library).current?.click();

  const onDrop = (e: DragEvent) => {
    if (!editable) return;
    e.preventDefault();
    void addFiles([...e.dataTransfer.files], kinds[0] ?? "other");
  };
  const onPaste = (e: ClipboardEvent) => {
    if (!editable) return;
    const files = [...e.clipboardData.files];
    if (files.length) {
      e.preventDefault();
      void addFiles(files, kinds[0] ?? "other");
    }
  };

  return (
    <div className={s.strip} onDragOver={(e) => editable && e.preventDefault()} onDrop={onDrop} onPaste={onPaste}>
      <div className={s.row} role="list" aria-label="Photos">
        {(photos ?? []).map((p) => (
          <div key={p.uid} role="listitem">
            <Tile photo={p} onOpen={() => setOpenUid(p.uid)} />
          </div>
        ))}
        {Array.from({ length: busy }, (_, i) => (
          <div key={`busy${i}`} role="listitem" className={s.busyTile}>
            <TumblingBeans size={40} label="Adding photo" />
          </div>
        ))}
        {editable && !full && (
          <div role="listitem">
            <button type="button" className={s.addTile} onClick={() => setAdding(true)} aria-label="Add photo">
              <Icon name="Camera" size={22} />
              <span className="micro">Add</span>
            </button>
          </div>
        )}
      </div>
      {error && (
        <p className={`t-body-s ${s.error}`} role="alert">
          {error}
        </p>
      )}
      {adding && (
        <CoffeeSheet label="Add photo" onClose={() => setAdding(false)}>
          <div ref={sheetBox} className={s.sheet}>
            <h2 className="t-headline-s">Add a photo</h2>
            {kinds.length > 1 && (
              <div className={s.chips} role="group" aria-label="What it shows">
                {kinds.map((k) => (
                  <SelectableChip key={k} text={KIND_LABELS[k]} selected={k === kind} onClick={() => setKind(k)} />
                ))}
              </div>
            )}
            <button type="button" className={`${s.sheetAction} t-body-l`} onClick={() => pick("camera")}>
              <Icon name="Camera" size={22} />
              Take photo
            </button>
            <button type="button" className={`${s.sheetAction} t-body-l`} onClick={() => pick("library")}>
              <Icon name="Image" size={22} />
              Choose from library
            </button>
            <input ref={camera} type="file" accept="image/*" capture="environment" hidden aria-label="Take photo" onChange={(e) => onPicked(e.currentTarget)} />
            <input ref={library} type="file" accept="image/*" multiple hidden aria-label="Choose from library" onChange={(e) => onPicked(e.currentTarget)} />
          </div>
        </CoffeeSheet>
      )}
      {open && <PhotoViewer photo={open} kinds={kinds} editable={editable} onClose={() => setOpenUid(null)} />}
    </div>
  );
}
