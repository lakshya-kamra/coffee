// Backup v2 (web-spec §15, A§10): model, codec, 1.x reader, merge planner, row mapping, catalog linker.
export * from "./model";
export { detect, detectValue, decodeV2, encode, parseBackupText, parseInstant, formatInstant, type BackupKind, type DecodeOutcome } from "./codec";
export { sanitizeNonStandardNumbers } from "./sanitize";
export { legacyUid, readLegacy, type LegacyType, type RecipeRef } from "./legacy";
export { planBackup, planTable, planCounts, type MergeMode, type MergePlan, type ExistingIndex, type Existing, type TablePlan } from "./merge";
export { readBackupText, base64ToBytes, bytesToBase64, type ReadError, type ReadPreview, type ReadResult, type PhotoBytes } from "./read";
export * from "./rows";
export * from "./linker";
