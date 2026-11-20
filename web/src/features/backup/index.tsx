// Backup & storage (WEB-5, web-spec §15, §8.5). Public surface per the §20.4 stub contract.
export { BackupSection, exportBackupNow, SaveBackupRow } from "./BackupSection";
export { requestImport } from "./importRequest";
export { runStartupLinking } from "./linking";
/** For Today's backup nudge (storage.shouldNudgeBackup): when this browser last saved a backup. */
export { lastBackupAt } from "./meta";
