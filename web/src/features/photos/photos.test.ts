import { describe, expect, it } from "vitest";
import { newUid, type PhotoRow } from "@/data/schema";
import { attachmentLabel, eligiblePhotos, MAX_BASE64_CHARS, trimToBudget } from "./attachments";
import { exifDateTimeOriginal, hasApp1, parseExifDateTime } from "./exifDate";
import { downscaleSteps, targetSize } from "./photoMath";

/** A minimal JPEG header: SOI, an Exif APP1 with IFD0 → ExifIFD → DateTimeOriginal, then SOS. */
function jpegWithDate(date: string, littleEndian = true): Uint8Array {
  const tiff: number[] = [];
  const u16 = (v: number) => (littleEndian ? [v & 0xff, v >> 8] : [v >> 8, v & 0xff]);
  const u32 = (v: number) => (littleEndian ? [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, v >>> 24] : [v >>> 24, (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff]);
  tiff.push(...(littleEndian ? [0x49, 0x49] : [0x4d, 0x4d]), ...u16(42), ...u32(8));
  // IFD0 at 8: one entry, the Exif IFD pointer.
  const exifIfd = 8 + 2 + 12 + 4;
  tiff.push(...u16(1), ...u16(0x8769), ...u16(4), ...u32(1), ...u32(exifIfd), ...u32(0));
  // Exif IFD: DateTimeOriginal, ASCII, 20 bytes, stored after the IFD.
  const strAt = exifIfd + 2 + 12 + 4;
  tiff.push(...u16(1), ...u16(0x9003), ...u16(2), ...u32(20), ...u32(strAt), ...u32(0));
  for (const ch of date) tiff.push(ch.charCodeAt(0));
  tiff.push(0);
  const payload = [0x45, 0x78, 0x69, 0x66, 0, 0, ...tiff];
  const len = payload.length + 2;
  return new Uint8Array([0xff, 0xd8, 0xff, 0xe1, len >> 8, len & 0xff, ...payload, 0xff, 0xda, 0x00, 0x02, 0x11, 0x22]);
}

/** A JPEG without any APP1: SOI, an APP0 (JFIF) and SOS. */
const PLAIN_JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x07, 0x4a, 0x46, 0x49, 0x46, 0x00, 0xff, 0xda, 0x00, 0x02]);

describe("exifDate", () => {
  it("reads DateTimeOriginal as local time, both byte orders", () => {
    const want = new Date(2026, 10, 17, 8, 14, 5).getTime();
    expect(exifDateTimeOriginal(jpegWithDate("2026:11:17 08:14:05"))).toBe(want);
    expect(exifDateTimeOriginal(jpegWithDate("2026:11:17 08:14:05", false))).toBe(want);
  });

  it("null without EXIF, for a bad date, or for garbage", () => {
    expect(exifDateTimeOriginal(PLAIN_JPEG)).toBeNull();
    expect(exifDateTimeOriginal(jpegWithDate("0000:00:00 00:00:00"))).toBeNull();
    expect(exifDateTimeOriginal(new Uint8Array([1, 2, 3]))).toBeNull();
    expect(exifDateTimeOriginal(jpegWithDate("2026:11:17 08:14:05").slice(0, 30))).toBeNull();
    expect(parseExifDateTime("2026:13:01 00:00:00")).toBeNull();
  });

  it("knows whether a JPEG still carries APP1 (stored photos must not)", () => {
    expect(hasApp1(jpegWithDate("2026:11:17 08:14:05"))).toBe(true);
    expect(hasApp1(PLAIN_JPEG)).toBe(false);
  });
});

describe("photo size maths", () => {
  it("long edge ≤ 1024, aspect kept, never upscaled", () => {
    expect(targetSize(4032, 3024, 1024)).toEqual({ w: 1024, h: 768 });
    expect(targetSize(3024, 4032, 1024)).toEqual({ w: 768, h: 1024 });
    expect(targetSize(800, 600, 1024)).toEqual({ w: 800, h: 600 });
    expect(targetSize(4032, 3024, 256)).toEqual({ w: 256, h: 192 });
  });

  it("halves while the ratio is over 2, then one final draw at the target", () => {
    expect(downscaleSteps(4032, 3024, 1024)).toEqual([
      { w: 2016, h: 1512 },
      { w: 1024, h: 768 },
    ]);
    expect(downscaleSteps(8000, 6000, 1024)).toEqual([
      { w: 4000, h: 3000 },
      { w: 2000, h: 1500 },
      { w: 1024, h: 768 },
    ]);
    // Already small: drawn once at its own size (re-encoding is what strips EXIF).
    expect(downscaleSteps(640, 480, 1024)).toEqual([{ w: 640, h: 480 }]);
  });
});

describe("AI attachments (A§9.3)", () => {
  const photo = (p: Partial<PhotoRow>): PhotoRow => ({
    uid: newUid(), brewLogId: 1, beanId: null, kind: "bed", mediaType: "image/jpeg", widthPx: 1024, heightPx: 768, bytes: 1, takenAt: 0, createdAt: 0, caption: "", sendToAi: true, ...p,
  });

  it("bed and cup photos that are ticked, newest three", () => {
    const list = [
      photo({ uid: "a", takenAt: 1 }),
      photo({ uid: "b", takenAt: 5, kind: "cup" }),
      photo({ uid: "c", takenAt: 4, kind: "bag" }),
      photo({ uid: "d", takenAt: 3, sendToAi: false }),
      photo({ uid: "e", takenAt: 2 }),
      photo({ uid: "f", takenAt: 6, kind: "other" }),
      photo({ uid: "g", takenAt: 7 }),
    ];
    expect(eligiblePhotos(list).map((p) => p.uid)).toEqual(["g", "b", "e"]);
  });

  it("drops the oldest while the total base64 is over 4 MB, then reads oldest first", () => {
    const big = "x".repeat(1_800_000);
    const items = [
      { takenAt: 3, base64: big, label: "bed, 08:03" },
      { takenAt: 2, base64: big, label: "cup, 08:02" },
      { takenAt: 1, base64: big, label: "bed, 08:01" },
    ];
    expect(trimToBudget(items).map((x) => x.takenAt)).toEqual([2, 3]);
    expect(trimToBudget(items, MAX_BASE64_CHARS * 2).map((x) => x.takenAt)).toEqual([1, 2, 3]);
    expect(trimToBudget([{ takenAt: 1, base64: "x".repeat(MAX_BASE64_CHARS + 1) }])).toEqual([]);
  });

  it("labels read 'bed, 08:14'", () => {
    expect(attachmentLabel({ kind: "bed", takenAt: new Date(2026, 10, 17, 8, 14).getTime() })).toBe("bed, 08:14");
  });
});
