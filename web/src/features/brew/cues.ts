/**
 * Step cues (web-spec §12.3). Vibration where the browser has it (not iOS); and, when "Sound
 * cues" is on, a soft two-tone 80 ms cue scheduled on the audio clock at every future step
 * boundary, so cues stay on time while the tab is in the background. Rescheduled on pause,
 * resume and seek. Volume −18 dBFS. Android has the setting without a sound; the web needs it
 * because iOS cannot vibrate (open question 9).
 */
import type { Recipe } from "@/domain/core/recipe";

/** −18 dBFS as a linear gain. */
export const CUE_GAIN = 10 ** (-18 / 20);
const TONE_SEC = 0.04;

/** Seconds from now to every step start still ahead of `elapsedMs` (ascending). */
export function cueOffsetsSec(r: Recipe, elapsedMs: number): number[] {
  const out: number[] = [];
  for (const s of r.steps) {
    const at = s.startSec * 1000 - elapsedMs;
    if (at > 0 && !out.includes(at / 1000)) out.push(at / 1000);
  }
  return out.sort((a, b) => a - b);
}

type AudioCtor = typeof AudioContext;

function audioCtor(): AudioCtor | null {
  const w = globalThis as unknown as { AudioContext?: AudioCtor; webkitAudioContext?: AudioCtor };
  return w.AudioContext ?? w.webkitAudioContext ?? null;
}

export function canVibrate(): boolean {
  return typeof navigator !== "undefined" && typeof navigator.vibrate === "function";
}

/** One short buzz on a step change, only while the page is visible. */
export function vibrateStep(): void {
  if (canVibrate() && document.visibilityState === "visible") navigator.vibrate(30);
}

export class CuePlayer {
  private ctx: AudioContext | null = null;
  private scheduled: OscillatorNode[] = [];

  static supported(): boolean {
    return audioCtor() != null;
  }

  /** Must run inside a user gesture (the Start / Resume tap) so the context may play. */
  unlock(): void {
    const C = audioCtor();
    if (!C) return;
    if (!this.ctx) {
      try {
        this.ctx = new C();
      } catch {
        this.ctx = null;
        return;
      }
    }
    if (this.ctx.state === "suspended") void this.ctx.resume().catch(() => undefined);
  }

  /** Cancels everything queued, then queues a cue at each offset (seconds from now). */
  schedule(offsetsSec: readonly number[]): void {
    this.cancel();
    const ctx = this.ctx;
    if (!ctx) return;
    const now = ctx.currentTime;
    for (const off of offsetsSec) {
      const t = now + off;
      // Two tones, 40 ms each, a short fade so nothing clicks.
      [880, 660].forEach((freq, i) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        const t0 = t + i * TONE_SEC;
        osc.type = "sine";
        osc.frequency.value = freq;
        gain.gain.setValueAtTime(0, t0);
        gain.gain.linearRampToValueAtTime(CUE_GAIN, t0 + 0.005);
        gain.gain.setValueAtTime(CUE_GAIN, t0 + TONE_SEC - 0.01);
        gain.gain.linearRampToValueAtTime(0, t0 + TONE_SEC);
        osc.connect(gain).connect(ctx.destination);
        osc.start(t0);
        osc.stop(t0 + TONE_SEC + 0.01);
        this.scheduled.push(osc);
      });
    }
  }

  cancel(): void {
    for (const o of this.scheduled) {
      try {
        o.stop();
        o.disconnect();
      } catch {
        // Already stopped.
      }
    }
    this.scheduled = [];
  }

  close(): void {
    this.cancel();
    void this.ctx?.close().catch(() => undefined);
    this.ctx = null;
  }
}
