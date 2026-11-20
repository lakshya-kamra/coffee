/**
 * A backup file picked outside Your data (the palette's "Import a backup"): held here until
 * BackupSection mounts or sees it, then shown in the import preview. Tiny on purpose, so the
 * palette commands need none of the backup code.
 */
let pending: File | null = null;
const listeners = new Set<() => void>();

export function requestImport(file: File): void {
  pending = file;
  for (const l of listeners) l();
}

/** The pending file, once. */
export function takeImport(): File | null {
  const f = pending;
  pending = null;
  return f;
}

export function subscribeImport(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}
