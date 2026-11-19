// Anchor for tsconfig.worker.json so `tsc -b` has an input before the first worker lands
// (WEB-2's photo.worker.ts, WEB-5's backup.worker.ts). Workers are module workers.
export {};
