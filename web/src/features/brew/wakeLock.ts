/**
 * Screen Wake Lock (web-spec §12.4): requested on Start/Resume when "Keep screen on" is set,
 * re-requested when the page becomes visible (the browser releases it when hidden), released
 * after a pause longer than 60 s, on finish, reset and leaving the screen. Feature-detected;
 * failures are silent (the setup pane says the screen may dim).
 */

interface Sentinel {
  release(): Promise<void>;
  released?: boolean;
}

export function wakeLockSupported(): boolean {
  return typeof navigator !== "undefined" && "wakeLock" in navigator;
}

export class ScreenLock {
  private sentinel: Sentinel | null = null;
  private wanted = false;
  private pauseTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly onVisible = () => {
    if (document.visibilityState === "visible" && this.wanted) void this.acquire();
  };

  constructor() {
    if (typeof document !== "undefined") document.addEventListener("visibilitychange", this.onVisible);
  }

  private async acquire(): Promise<void> {
    if (!wakeLockSupported() || (this.sentinel && !this.sentinel.released)) return;
    try {
      this.sentinel = (await (navigator as Navigator & { wakeLock: { request(t: "screen"): Promise<Sentinel> } }).wakeLock.request("screen")) ?? null;
    } catch {
      this.sentinel = null;
    }
  }

  /** Start or resume. */
  hold(): void {
    this.wanted = true;
    if (this.pauseTimer) clearTimeout(this.pauseTimer);
    this.pauseTimer = null;
    void this.acquire();
  }

  /** A pause keeps the screen on for a minute, then lets it dim. */
  paused(): void {
    if (this.pauseTimer) clearTimeout(this.pauseTimer);
    this.pauseTimer = setTimeout(() => this.release(), 60_000);
  }

  release(): void {
    this.wanted = false;
    if (this.pauseTimer) clearTimeout(this.pauseTimer);
    this.pauseTimer = null;
    const s = this.sentinel;
    this.sentinel = null;
    void s?.release().catch(() => undefined);
  }

  dispose(): void {
    this.release();
    if (typeof document !== "undefined") document.removeEventListener("visibilitychange", this.onVisible);
  }
}
