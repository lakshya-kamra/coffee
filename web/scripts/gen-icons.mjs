// `npm run icons`: rasterises public/icon.svg into the PNGs the manifest and index.html name
// (the minimal-2023 set: 64, 192, 512, maskable 512, apple-touch 180). Outputs are committed so
// builds are deterministic and Vercel never runs this.
//
// Uses Playwright with the machine's Chrome (channel "chrome"; set ICONS_CHANNEL="" to use
// Playwright's own Chromium) instead of @vite-pwa/assets-generator, which needs sharp's native
// binary. The icon is full-bleed (#2A1C12) with the art inside the maskable safe zone, so the
// maskable and apple-touch icons are the same drawing.
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const here = path.dirname(fileURLToPath(import.meta.url));
const pub = path.join(here, "..", "public");
const svg = readFileSync(path.join(pub, "icon.svg"), "utf8");

const OUT = [
  ["pwa-64x64.png", 64],
  ["pwa-192x192.png", 192],
  ["pwa-512x512.png", 512],
  ["maskable-icon-512x512.png", 512],
  ["apple-touch-icon-180x180.png", 180],
];

const channel = process.env.ICONS_CHANNEL ?? "chrome";
const browser = await chromium.launch(channel ? { channel } : {});
try {
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  for (const [name, size] of OUT) {
    await page.setViewportSize({ width: size, height: size });
    const sized = svg.replace(/width="\d+" height="\d+"/, `width="${size}" height="${size}"`);
    await page.setContent(`<!doctype html><html><body style="margin:0;background:#2A1C12">${sized}</body></html>`);
    const png = await page.locator("svg").screenshot({ omitBackground: false });
    writeFileSync(path.join(pub, "icons", name), png);
    console.log(`public/icons/${name} (${size}×${size}, ${png.length} bytes)`);
  }
} finally {
  await browser.close();
}
