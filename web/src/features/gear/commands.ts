/**
 * Gear's palette commands (v4 §2.8): "Add a coffee", "Add a grinder", "Export a backup", "Import a
 * backup". Light on purpose (no screen code), so the shell can register it at boot and the
 * commands work before Gear has ever been opened; GearScreen registers it too.
 */
import type { Command } from "@/app/palette/commands";
import { nav } from "@/app/router";
import { requestImport } from "@/features/backup/importRequest";
import { selectSection } from "./settingsSections";

export type GearRequest = "addBean" | "addGrinder";

let pending: GearRequest | null = null;
const listeners = new Set<() => void>();

/** Asks Gear to open a new coffee/grinder editor (now if it is mounted, else when it mounts). */
export function requestGear(r: GearRequest): void {
  pending = r;
  for (const l of listeners) l();
}

/** The pending request, once. */
export function takeGearRequest(): GearRequest | null {
  const r = pending;
  pending = null;
  return r;
}

export function subscribeGearRequests(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/**
 * Picks the file synchronously inside the palette's key press (browsers only open a file picker
 * from a user gesture), then shows Your data with the preview.
 */
function pickBackupFile(): void {
  const input = document.createElement("input");
  input.type = "file";
  input.accept = "application/json,.json,text/plain";
  input.addEventListener("change", () => {
    const file = input.files?.[0];
    if (!file) return;
    selectSection("data", { scroll: true });
    nav.switchTab("gear", "app");
    requestImport(file);
  });
  input.click();
}

export function gearCommands(): Command[] {
  return [
    {
      id: "gear.addCoffee",
      title: "Add a coffee",
      subtitle: "Gear · Beans",
      group: "Actions",
      icon: "Add",
      keywords: ["bean", "beans", "bag", "new coffee", "roaster"],
      popularity: 20,
      run: () => {
        nav.switchTab("gear", "beans");
        requestGear("addBean");
      },
    },
    {
      id: "gear.addGrinder",
      title: "Add a grinder",
      subtitle: "Gear · Grinders",
      group: "Actions",
      icon: "Add",
      keywords: ["grinder", "new grinder", "mill"],
      popularity: 21,
      run: () => {
        nav.switchTab("gear", "grinders");
        requestGear("addGrinder");
      },
    },
    {
      id: "backup.export",
      title: "Export a backup",
      subtitle: "Save everything to one file",
      group: "Actions",
      icon: "Share",
      keywords: ["backup", "export", "save", "json", "download"],
      popularity: 30,
      run: () => {
        void import("@/features/backup").then((m) => m.exportBackupNow());
      },
    },
    {
      id: "backup.import",
      title: "Import a backup",
      subtitle: "Restore from a backup file",
      group: "Actions",
      icon: "History",
      keywords: ["backup", "import", "restore", "json"],
      popularity: 31,
      run: pickBackupFile,
    },
  ];
}
