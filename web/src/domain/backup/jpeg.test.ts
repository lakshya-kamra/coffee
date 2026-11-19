import { describe, expect, it } from "vitest";
import { inspectJpeg, isStoredPhotoShape, MAX_STORED_PHOTO_BYTES, STORED_EDGE_PX } from "./jpeg";

const SOI = [0xff, 0xd8];
const EOI = [0xff, 0xd9];
const seg = (marker: number, payload: number[]) => [0xff, marker, (payload.length + 2) >> 8, (payload.length + 2) & 255, ...payload];
const sof = (w: number, h: number, marker = 0xc0) => seg(marker, [8, h >> 8, h & 255, w >> 8, w & 255, 1, 1, 0x11, 0]);
const JFIF = seg(0xe0, [0x4a, 0x46, 0x49, 0x46, 0]);
const EXIF = seg(0xe1, [0x45, 0x78, 0x69, 0x66, 0, 0]);
const bytes = (...parts: number[][]) => new Uint8Array(parts.flat());

describe("photos arriving in a backup", () => {
  it("reads the size from the frame header, baseline or progressive", () => {
    expect(inspectJpeg(bytes(SOI, JFIF, sof(1024, 768), EOI))).toEqual({ width: 1024, height: 768, hasMetadata: false });
    expect(inspectJpeg(bytes(SOI, JFIF, seg(0xdb, [0, 1, 2]), seg(0xc4, [0, 1]), sof(640, 480, 0xc2), EOI))).toEqual({ width: 640, height: 480, hasMetadata: false });
  });

  it("notices Exif, XMP and IPTC segments, before or after the frame header", () => {
    expect(inspectJpeg(bytes(SOI, EXIF, sof(4, 3), EOI))?.hasMetadata).toBe(true);
    expect(inspectJpeg(bytes(SOI, seg(0xed, [1, 2, 3]), sof(4, 3), EOI))?.hasMetadata).toBe(true);
    expect(inspectJpeg(bytes(SOI, sof(4, 3), EXIF, EOI))?.hasMetadata).toBe(true);
    // An ICC profile (APP2) says nothing about where or with what the photo was taken.
    expect(inspectJpeg(bytes(SOI, seg(0xe2, [1, 2, 3]), sof(4, 3), EOI))?.hasMetadata).toBe(false);
  });

  it("is not fooled by things that are not JPEGs", () => {
    expect(inspectJpeg(new Uint8Array())).toBeNull();
    expect(inspectJpeg(new TextEncoder().encode("<svg onload=alert(1)>"))).toBeNull();
    expect(inspectJpeg(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))).toBeNull(); // PNG
    expect(inspectJpeg(bytes(SOI, JFIF, EOI))).toBeNull(); // no picture in it
    expect(inspectJpeg(bytes(SOI, [0xff, 0xc0, 0, 11, 8, 0]))).toBeNull(); // cut off inside the header
    expect(inspectJpeg(bytes(SOI, sof(0, 3), EOI))).toBeNull();
  });

  it("keeps bytes as they are only in the shape both apps store", () => {
    expect(isStoredPhotoShape(bytes(SOI, JFIF, sof(STORED_EDGE_PX, 768), EOI))).toBe(true);
    expect(isStoredPhotoShape(bytes(SOI, JFIF, sof(768, STORED_EDGE_PX), EOI))).toBe(true);
    expect(isStoredPhotoShape(bytes(SOI, JFIF, sof(STORED_EDGE_PX + 1, 768), EOI))).toBe(false);
    expect(isStoredPhotoShape(bytes(SOI, EXIF, sof(800, 600), EOI))).toBe(false);
    const fat = new Uint8Array(MAX_STORED_PHOTO_BYTES + 1);
    fat.set(bytes(SOI, JFIF, sof(800, 600)));
    expect(isStoredPhotoShape(fat)).toBe(false);
  });
});
