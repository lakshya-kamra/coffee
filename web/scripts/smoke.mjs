// `npm run smoke` (web-spec §19.2): the real production build under the production headers
// from vercel.json, driven in Chromium. WEB-1 covers the shell (first launch, every route, the
// bar, Back, deep links, CSP, axe in both themes, offline). Later packages APPEND their flows
// to the FLOWS list at the bottom; WEB-6 adds the performance pass.
//
// Fails on any console error, securitypolicyviolation, uncaught error, or failed same-origin
// request. Flags: --no-build (reuse dist/), --headed, --only=<part of a flow name>. Browser: the machine's Chrome by default
// (SMOKE_CHANNEL=chrome); SMOKE_CHANNEL="" uses Playwright's own Chromium
// (`npx playwright install chromium` once).
import { execFileSync, spawn } from "node:child_process";
import { readFileSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const web = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(import.meta.url);
const vite = path.join(web, "node_modules", "vite", "bin", "vite.js");
const PORT = Number(process.env.SMOKE_PORT ?? 4319);
const BASE = `http://localhost:${PORT}`;
const args = new Set(process.argv.slice(2));
const only = process.argv.find((a) => a.startsWith("--only="))?.slice(7).toLowerCase();
const axeSource = readFileSync(require.resolve("axe-core/axe.min.js"), "utf8");

const problems = [];
const fail = (msg) => {
  problems.push(msg);
  console.error(`  ✗ ${msg}`);
};
const ok = (msg) => console.log(`  ✓ ${msg}`);

if (!args.has("--no-build")) {
  console.log("vite build");
  execFileSync(process.execPath, [vite, "build", "--logLevel", "warn"], { cwd: web, stdio: "inherit" });
}

console.log(`vite preview on ${BASE} (production headers from vercel.json)`);
const preview = spawn(process.execPath, [vite, "preview", "--port", String(PORT), "--strictPort", "--host", "localhost"], { cwd: web, stdio: "pipe" });
preview.stderr.on("data", (d) => process.stderr.write(d));

async function waitForServer() {
  for (let i = 0; i < 100; i++) {
    try {
      const r = await fetch(BASE);
      if (r.ok) return r;
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error("vite preview did not start");
}

/** Lets compositor transitions and idle mounting finish. */
const settle = (page, ms = 700) => page.waitForTimeout(ms);

function watch(page, label) {
  page.on("console", (m) => {
    if (m.type() === "error") fail(`${label()}: console error: ${m.text()}`);
  });
  page.on("pageerror", (e) => fail(`${label()}: uncaught: ${e.message}`));
  page.on("requestfailed", (r) => {
    // ERR_ABORTED = the page navigated away while idle-time chunks were loading; not a failure.
    if (r.failure()?.errorText === "net::ERR_ABORTED") return;
    if (r.url().startsWith(BASE) && !page.context().__offline) fail(`${label()}: request failed: ${r.url()} ${r.failure()?.errorText}`);
  });
  page.on("response", (r) => {
    if (r.url().startsWith(BASE) && r.status() >= 400) fail(`${label()}: HTTP ${r.status()} ${r.url()}`);
  });
}

async function cspViolations(page) {
  return page.evaluate(() => window.__csp ?? []);
}

async function axe(page, label) {
  await page.evaluate(axeSource);
  const res = await page.evaluate(async () => {
    // Only the visible screen: inactive tab layers are inert and aria-hidden.
    const r = await window.axe.run(document, { resultTypes: ["violations"] });
    return r.violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => `${v.id} (${v.nodes.length}): ${v.help}`);
  });
  if (res.length) for (const v of res) fail(`${label}: axe ${v}`);
  else ok(`${label}: axe clean (serious/critical)`);
}

async function activeTab(page) {
  return page.evaluate(() => document.querySelector('section[data-tab][data-state="active"]')?.getAttribute("data-tab") ?? null);
}

async function h1(page) {
  return page.evaluate(() => {
    const top = [...document.querySelectorAll("h1")].filter((h) => h.offsetParent !== null || h.getClientRects().length > 0);
    return top.map((h) => h.textContent?.trim()).filter(Boolean);
  });
}

/**
 * Marks onboarding done the way the app stores it (kv "settings".onboardingDone), straight
 * through IndexedDB from the test side: quicker than tapping Skip in every flow (the
 * integration flow does tap it). No app code or build carries a test hook for this.
 */
async function markOnboarded(page) {
  await page.goto(BASE + "/");
  await page.waitForURL("**/welcome");
  await page.evaluate(
    () =>
      new Promise((resolve, reject) => {
        const open = indexedDB.open("coffee");
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const db = open.result;
          const tx = db.transaction("kv", "readwrite");
          const kv = tx.objectStore("kv");
          const get = kv.get("settings");
          get.onsuccess = () => kv.put({ key: "settings", value: { ...(get.result?.value ?? {}), onboardingDone: true } });
          tx.oncomplete = () => {
            db.close();
            resolve();
          };
          tx.onerror = () => reject(tx.error);
        };
      }),
  );
}

// ---------------------------------------------------------------- flows (append below)

const FLOWS = [
  {
    name: "first launch, tabs, details and Back",
    async run({ page }) {
      await page.goto(BASE + "/");
      await page.waitForURL("**/welcome");
      ok("fresh browser lands on /welcome");
      await page.getByRole("button", { name: "Back" }).first().click();
      await page.waitForURL(BASE + "/");
      await settle(page);
      if ((await activeTab(page)) !== "today") fail("Back from /welcome did not land on Today");
      else ok("Back from onboarding → Today");

      const bar = page.getByRole("navigation", { name: "Sections" });
      for (const [label, tab, url] of [
        ["Recipes", "recipes", "/recipes"],
        ["Log", "log", "/log"],
        ["Insights", "insights", "/insights"],
        ["Gear", "gear", "/gear"],
        ["Today", "today", "/"],
      ]) {
        await bar.getByRole("link", { name: label }).click();
        await settle(page);
        const current = await bar.getByRole("link", { name: label }).getAttribute("aria-current");
        const t = await activeTab(page);
        const u = new URL(page.url()).pathname;
        if (t !== tab || current !== "page" || u !== url) fail(`bar ${label}: active=${t} aria-current=${current} url=${u}`);
      }
      ok("bottom bar switches all five tabs");
      const layers = await page.evaluate(() => [...document.querySelectorAll("section[data-tab]")].map((s) => [s.getAttribute("data-tab"), s.getAttribute("data-state"), s.inert, s.childElementCount > 0]));
      if (layers.length !== 5 || layers.some(([, , , mounted]) => !mounted)) fail(`keep-alive: ${JSON.stringify(layers)}`);
      else if (layers.filter(([, st]) => st === "active").length !== 1 || layers.some(([, st, inert]) => st !== "active" && !inert)) fail(`layer states: ${JSON.stringify(layers)}`);
      else ok("five tab layers mounted, exactly one active, the rest inert");

      // Today ↔ tab keeps one entry; tab ↔ tab replaces; Back returns to Today.
      await bar.getByRole("link", { name: "Log" }).click();
      await bar.getByRole("link", { name: "Gear" }).click();
      await settle(page);
      await page.goBack();
      await settle(page);
      if ((await activeTab(page)) !== "today") fail("Back from a tab did not return to Today");
      else ok("Back from any tab returns to Today");

      // Re-tapping the active tab must not navigate.
      await bar.getByRole("link", { name: "Today" }).click();
      if (new URL(page.url()).pathname !== "/") fail("re-tap of Today navigated");
    },
  },
  {
    name: "every route renders from a cold deep link, Back stays in the app",
    async run({ page }) {
      await markOnboarded(page);
      const routes = [
        ["/recipes/hoffmann-v60", "/recipes"],
        ["/recipes/new", "/recipes"],
        ["/recipes/hoffmann-v60/edit", "/recipes"],
        ["/recipes/hoffmann-v60/duplicate", "/recipes"],
        ["/brew/hoffmann-v60", "/"],
        ["/review/smoke-uid", "/"],
        ["/log/smoke-uid", "/log"],
        ["/gear/grinders", null],
        ["/gear/app", null],
        ["/insights", null],
      ];
      for (const [url, parent] of routes) {
        await page.goto(BASE + url);
        await settle(page, 500);
        const heads = await h1(page);
        if (new URL(page.url()).pathname !== url) fail(`${url}: ended at ${page.url()}`);
        if (heads.length === 0) fail(`${url}: no visible h1`);
        if (parent) {
          await page.getByRole("button", { name: "Back" }).last().click();
          await settle(page, 600);
          const at = new URL(page.url()).pathname;
          if (at !== parent) fail(`${url}: Back went to ${at}, expected ${parent}`);
        }
      }
      ok(`${routes.length} deep links render and Back lands on the parent tab`);
      await page.goto(BASE + "/no/such/page");
      await settle(page, 300);
      if (new URL(page.url()).pathname !== "/") fail("unknown path did not replace with /");
      else ok("unknown path → Today");
    },
  },
  {
    name: "a11y in both themes",
    async run({ page }) {
      await markOnboarded(page);
      for (const scheme of ["dark", "light"]) {
        await page.emulateMedia({ colorScheme: scheme });
        for (const url of ["/", "/recipes", "/log", "/insights", "/gear", "/recipes/hoffmann-v60", "/welcome"]) {
          await page.goto(BASE + url);
          await settle(page, 500);
          const theme = await page.evaluate(() => document.documentElement.dataset.theme);
          if (theme !== scheme) fail(`${url}: data-theme ${theme}, expected ${scheme}`);
          await axe(page, `${scheme} ${url}`);
        }
      }
      await page.emulateMedia({ colorScheme: "dark" });
    },
  },
  {
    name: "offline after the service worker is active",
    async run({ page, context }) {
      await markOnboarded(page);
      await page.goto(BASE + "/");
      await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
      await page.reload(); // the first visit is not controlled until a reload (no clientsClaim with "prompt")
      await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
      const controlled = await page.evaluate(() => navigator.serviceWorker.controller != null);
      if (!controlled) {
        fail("service worker never controlled the page");
        return;
      }
      context.__offline = true;
      await context.setOffline(true);
      await page.goto(BASE + "/log");
      await settle(page, 600);
      const t = await activeTab(page);
      await context.setOffline(false);
      context.__offline = false;
      if (t !== "log") fail(`offline reload: active tab ${t}`);
      else ok("works offline (precached shell, catalogs and recipes)");
    },
  },
  // WEB-5: restore the shared v2 fixture through the real UI, save a backup, delete everything,
  // restore the saved file, and check the counts match.
  {
    name: "backup: restore, save, delete everything, restore again",
    async run({ page }) {
      await markOnboarded(page);
      // The system save dialog cannot be driven headless; without it the app falls back to a download.
      await page.addInitScript(() => Object.defineProperty(window, "showSaveFilePicker", { value: undefined, configurable: true }));
      const restore = async (file, expect) => {
        await page.goto(BASE + "/gear/app");
        await settle(page);
        await page.locator('input[type="file"][accept*="json"]').setInputFiles(file);
        const sheet = page.getByRole("dialog", { name: "Restore from a backup" });
        await sheet.getByText(/^Found /).waitFor({ timeout: 10_000 });
        await sheet.getByRole("button", { name: "Import", exact: true }).click();
        await sheet.getByRole("button", { name: "Done", exact: true }).waitFor({ timeout: 10_000 });
        const line = await sheet.innerText();
        if (!line.includes(expect)) fail(`restore ${path.basename(file)}: expected "${expect}", got "${line.replace(/\s+/g, " ").slice(0, 200)}"`);
        else ok(`restored ${path.basename(file)}: ${expect}`);
        await sheet.getByRole("button", { name: "Done", exact: true }).click();
        await settle(page);
      };
      const all = "Added 2 brews, 1 coffee, 1 grinder, 1 recipe, 3 analyses, 1 photo";
      await restore(path.join(web, "..", "shared", "fixtures", "backup-v2-sample.json"), all);
      await axe(page, "/gear/app YOUR DATA");

      const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: /Back up everything/ }).click()]);
      if (!/^coffee-backup-\d{4}-\d{2}-\d{2}\.json$/.test(download.suggestedFilename())) fail(`backup file name ${download.suggestedFilename()}`);
      const saved = await download.path();
      const doc = JSON.parse(readFileSync(saved, "utf8"));
      if (doc.format !== "coffee-backup" || doc.app?.platform !== "web" || doc.brews?.length !== 2 || typeof doc.photos?.[0]?.data !== "string") {
        fail(`saved backup looks wrong: ${JSON.stringify({ format: doc.format, app: doc.app, brews: doc.brews?.length })}`);
      } else ok("saved a v2 backup with photo bytes");

      await page.getByRole("button", { name: "Delete everything in this browser" }).click();
      await page.getByRole("dialog", { name: "Delete everything" }).getByRole("button", { name: "Delete everything", exact: true }).click();
      await page.waitForURL("**/welcome", { timeout: 10_000 });
      ok("Delete everything lands on /welcome");
      await markOnboarded(page);
      await restore(saved, all);
    },
  },
  // WEB-4: onboarding persists each step, Gear settings round-trip, catalog search, bean editor,
  // recipe builder.
  {
    name: "gear: onboarding, catalog search, editors, recipe builder",
    async run({ page }) {
      await page.goto(BASE + "/");
      await page.waitForURL("**/welcome");
      // v4: "How do you make coffee now?" comes first, and a burr grinder is one of three choices.
      await page.locator("label").filter({ hasText: /^I tinker/ }).click();
      await page.getByRole("button", { name: "Next", exact: true }).click(); // How you measure
      await settle(page, 400);
      await page.getByRole("button", { name: "Next", exact: true }).click(); // Your grinder
      await settle(page, 400);
      await page.locator("label").filter({ hasText: /^Burr grinder/ }).click();
      await page.getByRole("searchbox").fill("k ultra");
      await settle(page, 400);
      await page.getByRole("list", { name: "Matches" }).getByRole("button").first().click();
      await page.getByText(/^How to read your dial/).waitFor({ timeout: 5000 });
      ok("onboarding grinder step: search, pick, dial help");
      await page.getByRole("button", { name: "Next", exact: true }).click(); // Brewers
      await settle(page, 400);
      await page.getByRole("button", { name: "Hario V60 02", exact: true }).click();
      await page.getByRole("button", { name: "Next", exact: true }).click(); // Your coffee
      await settle(page, 400);
      await page.getByRole("button", { name: "Next", exact: true }).click(); // Your first brew
      await settle(page, 400);
      await page.getByRole("button", { name: "Go to Today", exact: true }).click();
      await page.waitForURL(BASE + "/");
      await page.reload();
      await settle(page);
      if (new URL(page.url()).pathname !== "/") fail("onboarding came back after Start brewing");
      else ok("onboarding finished and stays finished");

      await page.goto(BASE + "/gear/grinders");
      await settle(page);
      const card = page.getByRole("button", { name: "Edit 1Zpresso K-Ultra" });
      if ((await card.count()) !== 1) fail("onboarding grinder is not in Gear › Grinders");
      else ok("onboarding saved the K-Ultra as a grinder");

      // Catalog search through the grinder editor's picker (A§7.1 cases).
      await page.getByRole("button", { name: "Add grinder" }).click();
      await page.getByRole("button", { name: /^Which grinder\?/ }).click();
      const picker = page.getByRole("dialog", { name: "Which grinder?" });
      for (const [q, want] of [["commandante", "Comandante"], ["mahlkonigg", "Mahlkönig"], ["ek43", "EK43"]]) {
        await picker.getByRole("searchbox").fill(q);
        await settle(page, 300);
        const first = (await picker.getByRole("list", { name: "Matches" }).getByRole("button").first().innerText()).split("\n")[0];
        if (!first.includes(want)) fail(`search "${q}": first hit ${first}`);
      }
      ok("picker finds commandante, mahlkonigg and ek43");
      await page.keyboard.press("Escape");
      await settle(page, 500);
      await page.keyboard.press("Escape");
      await settle(page, 500);

      // App settings round-trip through IndexedDB and boot.js.
      await page.goto(BASE + "/gear/app");
      await settle(page);
      await page.getByRole("radiogroup", { name: "Theme" }).getByRole("radio", { name: "Light" }).click();
      await settle(page, 400);
      await page.reload();
      await settle(page);
      const theme = await page.evaluate(() => [document.documentElement.dataset.theme, localStorage.getItem("coffee.theme")]);
      if (theme[0] !== "light" || theme[1] !== "light") fail(`theme after reload: ${theme}`);
      else ok("theme survives a reload (IndexedDB → boot.js)");
      await page.getByRole("radiogroup", { name: "Theme" }).getByRole("radio", { name: "Auto" }).click();

      // A coffee with only a name saves.
      await page.goto(BASE + "/gear");
      await settle(page);
      await page.getByRole("button", { name: "Add coffee" }).click();
      const bean = page.getByRole("dialog", { name: "New coffee" });
      await bean.getByRole("textbox", { name: "Coffee name" }).fill("Smoke Beans");
      await bean.getByRole("button", { name: "Save", exact: true }).click();
      await page.getByRole("button", { name: "Edit Smoke Beans" }).waitFor({ timeout: 5000 });
      ok("bean editor saves a coffee");

      // Recipe builder: validation blocks Save until the numbers add up.
      await page.goto(BASE + "/recipes/new");
      await settle(page);
      const save = page.getByRole("button", { name: "Save", exact: true });
      if (await save.isEnabled()) fail("Save enabled on a blank recipe");
      await page.getByRole("textbox", { name: "Name", exact: true }).fill("Smoke recipe");
      await page.getByRole("textbox", { name: "Coffee", exact: true }).fill("15");
      await page.getByRole("textbox", { name: "Water", exact: true }).fill("250");
      await page.getByRole("textbox", { name: "Pour to", exact: true }).fill("200");
      await settle(page, 300);
      const fix = page.getByRole("button", { name: "Set the last pour to 250 g" });
      if ((await fix.count()) !== 1) fail("no one-tap last-pour fix");
      else await fix.click();
      await settle(page, 300);
      await save.click();
      await page.waitForURL(/\/recipes\/custom-[0-9a-f]{8}$/, { timeout: 5000 });
      ok("recipe builder validates, fixes the last pour and saves");
    },
  },
  {
    // WEB-2: a whole brew, reload mid-brew, review with a photo whose EXIF must not be stored.
    name: "brew flow: start, reload resumes, finish, review autosave, photo without EXIF",
    async run({ page }) {
      await markOnboarded(page);
      await page.goto(BASE + "/brew/hoffmann-v60");
      await settle(page, 600);
      await axe(page, "brew setup");
      await page.getByRole("button", { name: "Start brew" }).last().click();
      await page.waitForTimeout(2500);
      await axe(page, "brew running");
      const before = await page.locator('[role="timer"]').textContent();
      await page.reload();
      await settle(page, 1200);
      const after = await page.locator('[role="timer"]').textContent();
      const sec = (t) => Number((t ?? "0:00").split(":")[0]) * 60 + Number((t ?? "0:00").split(":")[1]);
      if (!(sec(after) >= sec(before))) fail(`reload did not resume the brew (${before} → ${after})`);
      else ok(`reload mid-brew resumes from the wall clock (${before} → ${after})`);
      await page.getByRole("button", { name: "Finish early" }).click();
      await page.waitForURL("**/review/**");
      await settle(page, 900);
      const heads = await h1(page);
      if (!heads.some((h) => h.includes("V60"))) fail(`review h1: ${JSON.stringify(heads)}`);
      await axe(page, "review");
      await page.getByRole("radio", { name: "8 of 10" }).click();
      await page.waitForTimeout(1200);
      const overall = await page.evaluate(
        () =>
          new Promise((res) => {
            const r = indexedDB.open("coffee");
            r.onsuccess = () => {
              const q = r.result.transaction("brew_logs").objectStore("brew_logs").getAll();
              q.onsuccess = () => res(q.result.map((b) => b.overall));
            };
          }),
      );
      if (JSON.stringify(overall) !== "[8]") fail(`review autosave: ${JSON.stringify(overall)}`);
      else ok("finish writes the brew; review autosaves the score");

      // A JPEG with an Exif APP1 (DateTimeOriginal) goes in; the stored one must have no APP1.
      const jpeg = await page.evaluate(async () => {
        const c = document.createElement("canvas");
        c.width = 1600;
        c.height = 1200;
        const x = c.getContext("2d");
        x.fillStyle = "#6b4428";
        x.fillRect(0, 0, 1600, 1200);
        const src = new Uint8Array(await (await new Promise((r) => c.toBlob(r, "image/jpeg", 0.9))).arrayBuffer());
        const u16 = (v) => [v & 255, v >> 8];
        const u32 = (v) => [v & 255, (v >> 8) & 255, (v >> 16) & 255, v >>> 24];
        const t = [0x49, 0x49, 42, 0, 8, 0, 0, 0, ...u16(1), ...u16(0x8769), ...u16(4), ...u32(1), ...u32(26), ...u32(0), ...u16(1), ...u16(0x9003), ...u16(2), ...u32(20), ...u32(44), ...u32(0)];
        for (const ch of "2026:11:17 08:14:05") t.push(ch.charCodeAt(0));
        t.push(0);
        const payload = [0x45, 0x78, 0x69, 0x66, 0, 0, ...t];
        const len = payload.length + 2;
        return [0xff, 0xd8, 0xff, 0xe1, len >> 8, len & 255, ...payload, ...src.subarray(2)];
      });
      await page.getByRole("button", { name: "Add photo" }).click();
      await page.locator('dialog[open] input[type="file"][multiple]').setInputFiles({ name: "bed.jpg", mimeType: "image/jpeg", buffer: Buffer.from(jpeg) });
      await page.waitForTimeout(2500);
      const stored = await page.evaluate(
        () =>
          new Promise((res) => {
            const r = indexedDB.open("coffee");
            r.onsuccess = () => {
              const tx = r.result.transaction(["photos", "photo_blobs"]);
              const p = tx.objectStore("photos").getAll();
              const b = tx.objectStore("photo_blobs").getAll();
              tx.oncomplete = async () => {
                const full = b.result[0] ? new Uint8Array(await b.result[0].full.arrayBuffer()) : null;
                let app1 = null;
                if (full) {
                  app1 = false;
                  for (let o = 2; o + 4 < full.length && full[o] === 0xff && full[o + 1] !== 0xda; o += 2 + ((full[o + 2] << 8) | full[o + 3])) if (full[o + 1] === 0xe1) app1 = true;
                }
                const d = p.result[0] ? new Date(p.result[0].takenAt) : null;
                res({ n: p.result.length, w: p.result[0]?.widthPx, app1, taken: d ? [d.getFullYear(), d.getMonth() + 1, d.getDate(), d.getHours(), d.getMinutes()] : null });
              };
            };
          }),
      );
      if (stored.n !== 1 || stored.w !== 1024 || stored.app1 !== false || JSON.stringify(stored.taken) !== "[2026,11,17,8,14]") fail(`photo: ${JSON.stringify(stored)}`);
      else ok("photo stored at 1024 px, EXIF date kept as takenAt, no APP1 segment in the stored JPEG");

      // The viewer has its own history entry: Back closes the photo and stays on Review, and
      // closing with X or Escape pops that entry instead of leaving it for the next Back.
      const reviewPath = new URL(page.url()).pathname;
      const viewer = page.locator("dialog[open]").filter({ has: page.getByRole("button", { name: "Close", exact: true }) });
      const where = () => page.evaluate(() => ({ path: location.pathname, hash: location.hash, sheets: (history.state?.sheets ?? []).length }));
      const closed = async (how) => {
        await viewer.waitFor({ state: "detached", timeout: 3000 }).catch(() => fail(`photo viewer: still open after ${how}`));
        await page.waitForTimeout(150);
        const w = await where();
        if (w.path !== reviewPath || w.hash !== "" || w.sheets !== 0) fail(`photo viewer after ${how}: ${JSON.stringify(w)}, expected ${reviewPath} with no sheet entry`);
        else ok(`photo viewer: ${how} closes it and leaves Review's own history entry on top`);
      };
      const openViewer = async () => {
        await page.getByRole("button", { name: /^Open / }).first().click();
        await viewer.waitFor({ timeout: 3000 });
        const w = await where();
        if (w.hash !== "#sheet" || w.sheets !== 1) fail(`photo viewer: no history entry of its own (${JSON.stringify(w)})`);
      };
      await openViewer();
      await page.goBack();
      await closed("Back");
      await openViewer();
      await viewer.getByRole("button", { name: "Close", exact: true }).click();
      await closed("X");
      await openViewer();
      await page.keyboard.press("Escape");
      await closed("Escape");
    },
  },
  // Review fixes: a started brew can be left running or discarded (it used to be finish-or-nothing,
  // and a brew started by mistake stayed "in progress" for good); a brew left for hours offers
  // Discard on Today; and tab switches cut short by another tap no longer hold Review's autosave.
  {
    name: "leaving a brew: keep it going, discard, a brew left behind, autosave after cut-short tab switches",
    async run({ page }) {
      const seen = (text) => page.getByText(text).filter({ visible: true }).first();
      // Straight through IndexedDB from the test side, as markOnboarded does.
      const kvKeys = () =>
        page.evaluate(
          () =>
            new Promise((res, rej) => {
              const r = indexedDB.open("coffee");
              r.onerror = () => rej(r.error);
              r.onsuccess = () => {
                const q = r.result.transaction("kv").objectStore("kv").getAllKeys();
                q.onsuccess = () => (r.result.close(), res(q.result));
              };
            }),
        );
      const overalls = () =>
        page.evaluate(
          () =>
            new Promise((res, rej) => {
              const r = indexedDB.open("coffee");
              r.onerror = () => rej(r.error);
              r.onsuccess = () => {
                const q = r.result.transaction("brew_logs").objectStore("brew_logs").getAll();
                q.onsuccess = () => (r.result.close(), res(q.result.map((b) => b.overall)));
              };
            }),
        );
      const putActiveBrew = (value) =>
        page.evaluate(
          (v) =>
            new Promise((res, rej) => {
              const r = indexedDB.open("coffee");
              r.onerror = () => rej(r.error);
              r.onsuccess = () => {
                const tx = r.result.transaction("kv", "readwrite");
                tx.objectStore("kv").put({ key: "activeBrew", value: v });
                tx.oncomplete = () => (r.result.close(), res(true));
                tx.onerror = () => rej(tx.error);
              };
            }),
          value,
        );
      const bar = page.getByRole("navigation", { name: "Sections" });

      await markOnboarded(page);
      await page.goto(BASE + "/brew/hoffmann-v60");
      await settle(page, 600);
      await page.getByRole("button", { name: "Start brew" }).last().click();
      await page.waitForTimeout(1200);

      // The X asks; "Keep it going" leaves with the timer running and Today showing the way back.
      await page.getByRole("button", { name: "Leave this brew" }).click();
      await page.getByRole("dialog", { name: "Leave this brew?" }).getByRole("button", { name: "Keep it going", exact: true }).click();
      await page.waitForURL(BASE + "/");
      await settle(page);
      if (!(await seen(/Brew in progress/).count()) || !(await kvKeys()).includes("activeBrew")) fail("the brew that was kept going is not on Today");
      else ok("X on a running brew asks; Keep it going leaves it running, Today shows it");
      await axe(page, "Today with a brew in progress");

      // Back in, then out for good.
      await page.getByRole("button", { name: /Brew in progress/ }).click();
      await page.waitForURL("**/brew/hoffmann-v60");
      await settle(page, 700);
      await page.getByRole("button", { name: "Leave this brew" }).click();
      await settle(page, 500);
      await axe(page, "leave-this-brew sheet");
      await page.getByRole("dialog", { name: "Leave this brew?" }).getByRole("button", { name: "Discard", exact: true }).click();
      await page.waitForURL(BASE + "/");
      await settle(page);
      const left = await kvKeys();
      if (left.includes("activeBrew")) fail("Discard left the brew in kv");
      else if (await seen(/Brew in progress|Brew paused|Brew left unfinished/).count()) fail("Today still shows a brew after Discard");
      else if ((await overalls()).length !== 0) fail("Discard wrote a brew to the log");
      else ok("Discard ends the brew and logs nothing");

      // A brew started fourteen hours ago and never finished: Today says so and offers Discard.
      await putActiveBrew({
        recipeId: "hoffmann-v60",
        startedAtWall: Date.now() - 14 * 3600_000,
        accumulatedMs: 0,
        running: true,
        setup: { doseG: 15, beanId: null, grinderId: null, grindClicks: null, grindSetting: "", tempC: 96 },
        stepIndex: 0,
        lastPourEndSec: null,
        bedExposedSec: null,
      });
      await page.goto(BASE + "/");
      await settle(page);
      if (!(await seen(/Brew left unfinished/).count())) fail("a 14-hour-old brew is not called left unfinished");
      await axe(page, "Today with a brew left unfinished");
      await page.getByRole("button", { name: "Discard", exact: true }).click();
      await page.getByRole("dialog", { name: "Discard this brew?" }).getByRole("button", { name: "Discard", exact: true }).click();
      await settle(page, 600);
      if ((await kvKeys()).includes("activeBrew") || (await seen(/Brew left unfinished/).count())) fail("Discard on Today did not clear the brew");
      else ok("a brew left for hours says so on Today and can be discarded there");

      // Tab switches cut short by the next tap (no reload from here on: the stuck state was per page).
      await bar.getByRole("link", { name: "Log" }).click();
      await bar.getByRole("link", { name: "Today" }).click();
      await bar.getByRole("link", { name: "Insights" }).click();
      await bar.getByRole("link", { name: "Log" }).click();
      await bar.getByRole("link", { name: "Recipes" }).click();
      await settle(page, 900);
      await page.locator('section[data-tab="recipes"] button').filter({ hasText: /RATIO/ }).first().click();
      await page.waitForURL(/\/recipes\/.+/);
      await settle(page, 700);
      await page.getByRole("button", { name: "Brew this" }).last().click();
      await page.waitForURL(/\/brew\/.+/);
      await settle(page, 600);
      await page.getByRole("button", { name: "Start brew" }).last().click();
      await page.waitForTimeout(1200);
      await page.getByRole("button", { name: "Finish early" }).click();
      await page.waitForURL("**/review/**");
      await settle(page, 900);
      await page.getByRole("radio", { name: "8 of 10" }).click();
      await page.waitForTimeout(1300);
      // Still on Review, nothing tapped: only autosave can have written the score.
      const saved = await overalls();
      if (JSON.stringify(saved) !== "[8]") fail(`autosave after cut-short tab switches: ${JSON.stringify(saved)}`);
      else ok("review autosaves after tab switches that were cut short");
    },
  },
  // WEB-3: Log, Brew detail, Insights and "add your key". The provider is answered by a route
  // mock (no real key, no real network); service workers are blocked in this context so the
  // mock sees the page's own requests. Tabs stay mounted (hidden), so text checks use visible matches.
  {
    name: "log, brew detail, insights and AI analysis",
    async run({ page: first }) {
      await first.close();
      const ctx = await first.context().browser().newContext({ serviceWorkers: "block", viewport: { width: 412, height: 915 }, colorScheme: "dark" });
      const page = await ctx.newPage();
      const seen = (text) => page.getByText(text).filter({ visible: true }).first();
      watch(page, () => "web-3");
      const KEY = "sk-ant-smoke-0123456789abcdef";
      let sentKey = null;
      let sentBrowserHeader = null;
      await page.route("https://api.anthropic.com/**", async (route) => {
        const req = route.request();
        const cors = { "access-control-allow-origin": "*", "access-control-allow-headers": "*", "access-control-allow-methods": "POST, GET, OPTIONS" };
        if (req.method() === "OPTIONS") return route.fulfill({ status: 200, headers: cors });
        sentKey = req.headers()["x-api-key"] ?? null;
        sentBrowserHeader = req.headers()["anthropic-dangerous-direct-browser-access"] ?? null;
        const body = JSON.parse(req.postData() ?? "{}");
        const test = body.max_tokens === 16;
        const answer = test
          ? "OK"
          : JSON.stringify({ verdict: "needs_change", headline: "Fast and a little thin", diagnosis: "It ran 0:30 fast.", confidence: "medium", change: { variable: "grind", instruction: "75 -> 69 clicks (dial 7.5 -> 6.9)", grindClicksFrom: 75, grindClicksTo: 69 }, expectIfRight: "Sweeter, about 3:30", ifWrong: "Bitter means too far", keep: "Same dose", dataWarnings: [] });
        return route.fulfill({ status: 200, headers: { ...cors, "content-type": "application/json" }, body: JSON.stringify({ model: body.model, content: [{ type: "text", text: answer }], stop_reason: "end_turn", usage: { input_tokens: 3000, output_tokens: 400 } }) });
      });
      await markOnboarded(page);
      // Three brews with a catalog-linked K-Ultra, written the way the app stores them.
      await page.evaluate(
        () =>
          new Promise((resolve, reject) => {
            const open = indexedDB.open("coffee");
            open.onerror = () => reject(open.error);
            open.onsuccess = () => {
              const db = open.result;
              const tx = db.transaction(["grinders", "brew_logs"], "readwrite");
              const now = Date.now();
              tx.objectStore("grinders").add({ uid: crypto.randomUUID(), brand: "1Zpresso", model: "K-Ultra", burrType: "conical", burrSizeMm: "48", settingStyle: "clicks", clicksPerRotation: 100, micronsPerClick: 20, lowerIsFiner: true, minSetting: 0, maxSetting: 200, step: 1, defaultFilterSetting: null, defaultEspressoSetting: null, notes: "", isDefault: true, isArchived: false, catalogId: "1zpresso-k-ultra", dialFormat: "", updatedAt: now });
              const taste = { acidity: 7, sweetness: 4, bitterness: 3, body: 4, balance: 5, aftertaste: 5, clarity: 6 };
              for (let i = 0; i < 3; i++) {
                const at = now - i * 86_400_000;
                tx.objectStore("brew_logs").add({ uid: crypto.randomUUID(), startedAt: at, updatedAt: at, recipeId: "hoffmann-v60", recipeName: "The Ultimate V60 Technique", recipeAuthor: "James Hoffmann", brewer: "V60 02", brewerId: "hario-v60-02", beanId: null, beanLabel: "Square Mile — Red Brick", daysOffRoast: 12, grinderId: 1, grinderLabel: "1Zpresso K-Ultra", grindSetting: "", grindClicks: 75 + i, grindValue: null, doseG: 30, waterG: 500, tempC: 94, waterId: null, totalTimeSec: 180 + i * 10, drawdownSec: null, beverageG: null, tdsPercent: null, extractionYield: null, flowFeel: "fast", bedShape: "flat", sludge: null, channeling: null, bedNotes: "", taste, overall: 6 + i, descriptors: ["Citrus"], faults: i === 0 ? ["Thin"] : [], notes: "", nextTime: "", isFavourite: false, sharedAt: null });
              }
              tx.oncomplete = () => (db.close(), resolve());
              tx.onerror = () => reject(tx.error);
            };
          }),
      );

      await page.goto(BASE + "/log");
      await settle(page);
      const rows = page.getByRole("button", { name: /^(?!More for ).*The Ultimate V60 Technique/ });
      if ((await rows.count()) !== 3) fail(`log rows: ${await rows.count()}`);
      else ok("Log shows three day-grouped brews");
      if (!(await seen("3 BREWS · 1 WEEK").count())) fail("log eyebrow");
      await axe(page, "log");

      await rows.first().click();
      await page.waitForURL(/\/log\/[0-9a-f-]{36}$/);
      await settle(page, 900);
      await seen("NOT SET UP").waitFor({ timeout: 5000 });
      ok("brew detail: boarding pass and analysis card (not set up)");
      await axe(page, "brew detail");

      await page.goto(BASE + "/insights");
      await settle(page);
      await seen("BREW PASSPORT").waitFor({ timeout: 5000 });
      if (!(await seen(/No AI service is set up, so nothing you log leaves this browser\./).count())) fail("privacy copy (not configured)");
      else ok("insights: passport, charts and the not-configured privacy copy");
      await axe(page, "insights");

      // Add a key: Anthropic, not remembered (this page's memory only), tested through the mock.
      await page.goto(BASE + "/gear/app");
      await settle(page);
      await page.getByRole("radio", { name: /Anthropic/ }).first().click();
      await settle(page, 300);
      const remember = page.getByRole("checkbox", { name: "Remember on this device" });
      if (await remember.isChecked()) fail("Remember on this device is ticked by default");
      if (!(await seen(/^Your key is kept only while this page stays open: closing or reloading the tab forgets it/).count())) fail("unticked key copy");
      await page.getByLabel("API KEY").fill(KEY);
      await page.getByRole("button", { name: "Save and test" }).click();
      await seen("Connected").waitFor({ timeout: 10_000 });
      if (sentKey !== KEY || sentBrowserHeader !== "true") fail(`test headers: key ${sentKey === KEY}, browser header ${sentBrowserHeader}`);
      else ok("Anthropic test connection sends x-api-key and the browser header");
      const whereOf = () => page.evaluate(async () => {
        const inSession = Object.keys(sessionStorage).some((k) => k.startsWith("coffee.ai.key."));
        const inLocal = Object.keys(localStorage).some((k) => k.startsWith("coffee.ai.key."));
        const dump = await new Promise((resolve) => {
          const open = indexedDB.open("coffee");
          open.onsuccess = () => {
            const db = open.result;
            const names = [...db.objectStoreNames];
            const tx = db.transaction(names, "readonly");
            const out = [];
            for (const n of names) {
              const r = tx.objectStore(n).getAll();
              r.onsuccess = () => out.push(JSON.stringify(r.result));
            }
            tx.oncomplete = () => (db.close(), resolve(out.join("")));
          };
        });
        return { inSession, inLocal, dump };
      });
      const unticked = await whereOf();
      // Nothing on disk at all: a browser that restores the closed tab has nothing to give back.
      if (unticked.inSession || unticked.inLocal) fail(`an unremembered key was stored: session ${unticked.inSession}, local ${unticked.inLocal}`);
      // The connection-test record lives in IndexedDB: not the key, and not its last four either.
      if (unticked.dump.includes(KEY) || unticked.dump.includes(`|${KEY.slice(-4)}"`)) fail("the key, or its last four characters, is in IndexedDB");
      else ok("unremembered key: in memory only, nothing of it in Web Storage or IndexedDB");

      // A reload (or a reopened tab) starts without it.
      await page.goto(BASE + "/gear/app");
      await settle(page);
      if (await seen(/kept until this tab closes or reloads/).count()) fail("the unremembered key survived a reload");
      else ok("a reload forgets the unremembered key");

      // Remembered: the rest of this flow reloads pages, as someone who ticked the box may.
      await page.getByRole("checkbox", { name: "Remember on this device" }).check();
      await page.getByLabel("API KEY").fill(KEY);
      await page.getByRole("button", { name: "Save and test" }).click();
      await seen("Connected").waitFor({ timeout: 10_000 });
      const where = await whereOf();
      if (where.inSession || !where.inLocal) fail(`remembered key storage: session ${where.inSession}, local ${where.inLocal}`);
      if (where.dump.includes(KEY) || where.dump.includes(`|${KEY.slice(-4)}"`)) fail("the key is in IndexedDB");
      else ok("remembered key in localStorage only, never in IndexedDB");

      // Analyse a brew: the hero shows the move in the grinder's own units.
      await page.goto(BASE + "/log");
      await settle(page);
      await page.getByRole("button", { name: /^(?!More for ).*The Ultimate V60 Technique/ }).first().click();
      await settle(page, 900);
      await page.getByRole("button", { name: "Analyse brew" }).click();
      await seen("THE ONE CHANGE").waitFor({ timeout: 10_000 });
      if (!(await seen(/dial 7\.5\s→\s6\.9/).count())) fail("hero caption in dial words");
      else ok("analysis: the one change, 75 → 69 · dial 7.5 → 6.9");
      await settle(page, 600);
      await axe(page, "brew detail with analysis");

      // WEB-6: Review's "Save & analyse" saves, starts the analysis and lands on that brew.
      await page.goto(BASE + "/log");
      await settle(page);
      await page.getByRole("button", { name: /^(?!More for ).*The Ultimate V60 Technique/ }).nth(1).click();
      await page.waitForURL(/\/log\/[0-9a-f-]{36}$/);
      const uid = new URL(page.url()).pathname.split("/").pop();
      await page.goto(`${BASE}/review/${uid}`);
      await settle(page, 900);
      if (!(await seen("Offline pattern matching, not a verdict. Save & analyse for a real read.").count())) fail("review: first-read copy with AI ready");
      await page.getByRole("button", { name: "Save and analyse", exact: true }).click();
      await page.waitForURL(`**/log/${uid}`, { timeout: 5000 });
      await seen("THE ONE CHANGE").waitFor({ timeout: 10_000 });
      ok("review: Save & analyse lands on the brew with its analysis");

      // WEB-6: "Delete everything" also forgets the saved key (Web Storage, not IndexedDB).
      await page.goto(BASE + "/gear/app");
      await settle(page);
      await page.getByRole("button", { name: "Delete everything in this browser" }).click();
      await page.getByRole("dialog", { name: "Delete everything" }).getByRole("button", { name: "Delete everything", exact: true }).click();
      await page.waitForURL("**/welcome", { timeout: 10_000 });
      const keysLeft = await page.evaluate(() => [...Object.keys(sessionStorage), ...Object.keys(localStorage)].filter((k) => k.startsWith("coffee.ai.key.")).length);
      if (keysLeft) fail(`Delete everything left ${keysLeft} saved key(s)`);
      else ok("Delete everything removes the saved key");

      for (const x of await cspViolations(page).catch(() => [])) fail(`web-3: CSP violation ${x}`);
      await ctx.close();
    },
  },
  // WEB-6: cross-package wiring and the accessibility audit beyond axe (web-spec §18).
  {
    name: "integration: Skip, stacked sheets, focus ring, headings, narrow screens",
    async run({ page }) {
      // Backing out of setup leaves Today's Personalise card, which opens setup again.
      await page.goto(BASE + "/");
      await page.waitForURL("**/welcome");
      await page.getByRole("button", { name: "Back" }).first().click();
      await page.waitForURL(BASE + "/");
      await settle(page);
      await axe(page, "Today with the Personalise card");
      await page.getByRole("button", { name: "Personalise", exact: true }).click();
      await page.waitForURL("**/welcome");
      ok("Today's Personalise card opens /welcome");
      // The real Skip (other flows use markOnboarded only because it is faster).
      await page.getByRole("button", { name: "Skip for now", exact: true }).click();
      await page.waitForURL(BASE + "/");
      await page.reload();
      await settle(page);
      if (new URL(page.url()).pathname !== "/") fail("Skip did not finish onboarding (came back after a reload)");
      else if (await page.getByRole("button", { name: "Personalise", exact: true }).count()) fail("the Personalise card is still there after Skip");
      else ok("onboarding Skip → Today, it stays skipped and the card is gone");

      // A picker over an editor: each sheet owns its history entry, so Back closes only the top one.
      await page.goto(BASE + "/gear");
      await settle(page);
      await page.getByRole("button", { name: "Add coffee" }).click();
      const editor = page.getByRole("dialog", { name: "New coffee" });
      await editor.getByRole("textbox", { name: "Coffee name" }).fill("Stacked");
      await editor.getByRole("button", { name: /^Roaster:/ }).click();
      const picker = page.getByRole("dialog", { name: "Roaster" });
      await picker.getByRole("searchbox").or(picker.getByRole("textbox")).first().waitFor({ timeout: 5000 });
      await settle(page, 500);
      await axe(page, "picker over the bean editor");
      await page.goBack();
      await picker.waitFor({ state: "detached", timeout: 5000 });
      if (!(await editor.isVisible())) fail("Back from the picker also closed the editor");
      else ok("Back closes the picker and keeps the editor");
      await editor.getByRole("button", { name: /^Roaster:/ }).click();
      await picker.getByRole("searchbox").or(picker.getByRole("textbox")).first().fill("square mile");
      await page.waitForTimeout(400);
      await picker.getByRole("list", { name: "Matches" }).getByRole("button").first().click();
      await picker.waitFor({ state: "detached", timeout: 5000 });
      const roaster = await editor.getByRole("button", { name: /^Roaster:/ }).textContent();
      if (!/Square Mile/i.test(roaster ?? "") || !(await editor.isVisible())) fail(`picking in the stacked picker: editor visible ${await editor.isVisible()}, ${roaster}`);
      else ok("a pick closes the picker and fills the editor");
      await axe(page, "bean editor sheet");
      await page.goBack();
      await editor.waitFor({ state: "detached", timeout: 5000 });
      if (new URL(page.url()).pathname !== "/gear" || new URL(page.url()).hash) fail(`after closing both sheets: ${page.url()}`);
      else ok("Back closes the editor; the route is unchanged");

      // A multi-select picker hands over its ticks however it closes; Back used to drop them.
      await page.goto(BASE + "/gear/app");
      await settle(page);
      await page.getByRole("button", { name: /^Brewers you own:/ }).click();
      const multi = page.getByRole("dialog", { name: "Brewers you own" });
      const unticked = multi.locator('button[aria-pressed="false"]').first();
      const ticked = ((await unticked.locator("span > span").first().textContent()) ?? "").trim();
      await unticked.click();
      await page.goBack();
      await multi.waitFor({ state: "detached", timeout: 5000 });
      // The setting is written to IndexedDB, so the row follows a moment later.
      await page.waitForTimeout(500);
      const owned = await page.getByRole("button", { name: /^Brewers you own:/ }).textContent();
      if (!ticked || !owned?.includes(ticked)) fail(`multi-select picker closed with Back: ticked "${ticked}", row says "${owned}"`);
      else ok("Back keeps what was ticked in a multi-select picker");

      // Keyboard: a visible 2 px focus ring on whatever Tab reaches.
      await page.goto(BASE + "/recipes");
      await settle(page);
      for (let i = 0; i < 3; i++) await page.keyboard.press("Tab");
      const ring = await page.evaluate(() => {
        const el = document.activeElement;
        if (!el || el === document.body) return null;
        const cs = getComputedStyle(el);
        return { tag: el.tagName, style: cs.outlineStyle, width: parseFloat(cs.outlineWidth), visible: el.matches(":focus-visible") };
      });
      if (!ring || !ring.visible || ring.style === "none" || ring.width < 2) fail(`focus ring: ${JSON.stringify(ring)}`);
      else ok(`Tab shows a ${ring.width}px focus ring (${ring.tag.toLowerCase()})`);
      // 1–5 switch tabs from the keyboard (not while typing).
      await page.evaluate(() => document.activeElement?.blur?.());
      await page.keyboard.press("3");
      await settle(page);
      if ((await activeTab(page)) !== "log") fail("keyboard shortcut 3 did not open Log");
      else ok("keyboard: 3 opens Log");

      // One h1 per screen, and nothing scrolls sideways at 320 px (a 1280 px window at 400 %).
      const routes = ["/", "/recipes", "/log", "/insights", "/gear", "/gear/grinders", "/gear/app", "/recipes/hoffmann-v60", "/recipes/new", "/brew/hoffmann-v60", "/welcome"];
      await page.setViewportSize({ width: 320, height: 640 });
      for (const url of routes) {
        await page.goto(BASE + url);
        await settle(page, 600);
        const r = await page.evaluate(() => {
          const shown = (el) => {
            for (let n = el; n; n = n.parentElement) {
              const cs = getComputedStyle(n);
              if (cs.visibility === "hidden" || cs.display === "none" || n.inert) return false;
            }
            return el.getClientRects().length > 0;
          };
          const h1 = [...document.querySelectorAll("h1")].filter(shown).length;
          const wide = [];
          for (const el of document.querySelectorAll(".detail, section[data-tab], body, dialog[open]")) {
            if (shown(el) && el.scrollWidth > el.clientWidth + 1) wide.push(`${el.tagName.toLowerCase()}.${String(el.className).slice(0, 24)} ${el.scrollWidth}>${el.clientWidth}`);
          }
          return { h1, wide, lang: document.documentElement.lang };
        });
        // A detail covers its tab (visibility hidden under an opaque detail), so one h1 is visible.
        if (r.h1 !== 1) fail(`${url}: ${r.h1} visible h1`);
        if (r.wide.length) fail(`${url} at 320 px scrolls sideways: ${r.wide.join("; ")}`);
        if (r.lang !== "en-GB") fail(`${url}: lang ${r.lang}`);
      }
      ok(`${routes.length} screens: one visible h1 each, no sideways scroll at 320 px`);
      await page.setViewportSize({ width: 412, height: 915 });

      // Screens the first a11y flow does not open, in both themes.
      for (const scheme of ["dark", "light"]) {
        await page.emulateMedia({ colorScheme: scheme });
        for (const url of ["/gear/grinders", "/gear/app", "/recipes/new"]) {
          await page.goto(BASE + url);
          await settle(page, 500);
          await axe(page, `${scheme} ${url}`);
        }
      }
      await page.emulateMedia({ colorScheme: "dark" });

      // A cold load of /gear/app mounts the screen before roasters, water and providers land;
      // the lists those files fill must still appear (they once stayed empty for good).
      await page.goto(BASE + "/gear/app");
      for (const [what, locator] of [
        ["Where you buy coffee", page.getByRole("group", { name: "Where you buy coffee" })],
        ["Your water", page.getByRole("group", { name: "Your water" })],
        ["the AI service list", page.getByText("Anthropic (Claude)", { exact: true })],
      ]) {
        await locator.first().waitFor({ state: "visible", timeout: 8000 }).catch(() => fail(`cold /gear/app never shows ${what}`));
      }
      ok("cold /gear/app fills in the region, water and AI service lists once their files land");

      // Reduced motion (the OS setting): a tab switch still lands, with no transform animation.
      const layerMoves = () =>
        page.evaluate(() => document.getAnimations().filter((a) => a.effect?.target?.matches?.("section[data-tab]") && a.effect.getKeyframes().some((k) => "transform" in k && k.transform !== "none")).length);
      const sections = page.getByRole("navigation", { name: "Sections" });
      await page.goto(BASE + "/");
      await settle(page, 1500);
      await sections.getByRole("link", { name: "Log" }).click();
      const control = await layerMoves(); // with motion on, the layers do slide: proves the probe sees them
      await settle(page);
      await page.emulateMedia({ reducedMotion: "reduce" });
      await sections.getByRole("link", { name: "Gear" }).click();
      const moving = await layerMoves();
      await settle(page);
      if (!control) fail("reduced motion: the probe saw no layer slide with motion on, so the check proves nothing");
      else if ((await activeTab(page)) !== "gear") fail("reduced motion: tab switch did not land");
      else if (moving) fail(`reduced motion: ${moving} layer slide(s) still ran on a tab switch`);
      else ok("reduced motion: tab switch fades only");
      await page.emulateMedia({ reducedMotion: "no-preference" });
    },
  },
  // WEB-6: the performance pass (web-spec §19.2 step 7). CPU throttled 4× through CDP; ten tab
  // switches and three recipe open/close cycles. Headless Chrome draws at 60 Hz, so this proves
  // "no long frames on a slow CPU"; 120 Hz itself is the device checklist (§19.3).
  {
    name: "performance: tab switches and recipe open/close at 4× CPU throttle",
    async run({ page, context }) {
      await markOnboarded(page);
      // Some data on screen: the shared backup fixture through the real restore UI.
      await page.goto(BASE + "/gear/app");
      await settle(page);
      await page.locator('input[type="file"][accept*="json"]').setInputFiles(path.join(web, "..", "shared", "fixtures", "backup-v2-sample.json"));
      const sheet = page.getByRole("dialog", { name: "Restore from a backup" });
      await sheet.getByRole("button", { name: "Import", exact: true }).click();
      await sheet.getByRole("button", { name: "Done", exact: true }).click();
      await page.goto(BASE + "/");
      // Idle mounting of the other four tabs, their chunks and the catalogs.
      await settle(page, 3000);
      const bar = page.getByRole("navigation", { name: "Sections" });
      // Warm-up, unmeasured: the first visit to a tab is a first paint, not a switch.
      for (const label of ["Recipes", "Log", "Insights", "Gear", "Today"]) {
        await bar.getByRole("link", { name: label }).click();
        await settle(page, 500);
      }

      const cdp = await context.newCDPSession(page);
      await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 });
      await page.evaluate(() => {
        const p = (window.__perf = { frames: [], loafs: [], spans: [], on: true });
        try {
          new PerformanceObserver((list) => {
            for (const e of list.getEntries()) {
              const scripts = e.scripts ?? [];
              p.loafs.push({
                start: e.startTime,
                duration: e.duration,
                blocking: e.blockingDuration,
                // Where the frame went: script before rendering, rAF callbacks, style and layout.
                script: e.renderStart ? e.renderStart - e.startTime : e.duration,
                raf: e.renderStart && e.styleAndLayoutStart ? e.styleAndLayoutStart - e.renderStart : 0,
                layout: e.styleAndLayoutStart ? e.startTime + e.duration - e.styleAndLayoutStart : 0,
                forced: scripts.reduce((n, s) => n + (s.forcedStyleAndLayoutDuration ?? 0), 0),
                scripts: scripts.map((s) => `${s.invoker || s.name} ${Math.round(s.duration)}ms`).slice(0, 4),
              });
            }
          }).observe({ type: "long-animation-frame" });
          p.loafSupported = true;
        } catch {
          p.loafSupported = false;
        }
        let last = performance.now();
        const tick = (now) => {
          p.frames.push([now, now - last]);
          last = now;
          if (p.on) requestAnimationFrame(tick);
        };
        requestAnimationFrame(tick);
      });
      const span = async (kind, act, ms) => {
        const t0 = await page.evaluate(() => performance.now());
        await act();
        await page.waitForTimeout(ms);
        const t1 = await page.evaluate(() => performance.now());
        await page.evaluate(([k, a, b]) => window.__perf.spans.push({ kind: k, t0: a, t1: b }), [kind, t0, t1]);
      };

      // Raw mouse clicks at coordinates resolved outside the measured spans: Playwright's own
      // locator scripts (role queries over a few thousand elements, at 4× throttle) would
      // otherwise be billed to the app's frames.
      const centre = async (locator) => {
        const b = await locator.boundingBox();
        if (!b) throw new Error("perf: target has no box");
        return [b.x + b.width / 2, b.y + b.height / 2];
      };
      const at = {};
      for (const label of ["Today", "Recipes", "Log", "Insights", "Gear"]) at[label] = await centre(bar.getByRole("link", { name: label }));
      const order = ["Recipes", "Log", "Insights", "Gear", "Today", "Log", "Recipes", "Gear", "Insights", "Recipes"];
      // Three different recipes, so each open pays for a body it has not laid out before.
      // The tap lands on the card's header (the third card's middle is under the bar).
      const cards = page.locator('section[data-tab="recipes"] button').filter({ hasText: /RATIO/ });
      let back = null;
      const pct = (xs, q) => (xs.length ? [...xs].sort((x, y) => x - y)[Math.min(xs.length - 1, Math.floor(q * xs.length))] : 0);
      const KINDS = ["tab switch", "recipe open", "recipe close"];

      /** One pass: ten tab switches, three recipe open/close cycles. Returns the long frames per kind. */
      const pass = async (title) => {
        await page.evaluate(() => {
          window.__perf.frames.length = 0;
          window.__perf.loafs.length = 0;
          window.__perf.spans.length = 0;
        });
        for (const label of order) {
          await span("tab switch", () => page.mouse.click(...at[label]), 700);
          if ((await activeTab(page)) !== label.toLowerCase()) fail(`perf: ${label} did not open`);
        }
        // On Recipes now: open a card (the header flies to the detail), then Back.
        for (let i = 0; i < 3; i++) {
          const box = await cards.nth(i).boundingBox();
          if (!box) throw new Error("perf: recipe card has no box");
          await span("recipe open", () => page.mouse.click(box.x + box.width / 2, box.y + 40), 1000);
          if (!/\/recipes\/.+/.test(new URL(page.url()).pathname)) fail(`recipe did not open (${page.url()})`);
          back ??= await centre(page.getByRole("button", { name: "Back" }).last());
          await span("recipe close", () => page.mouse.click(...back), 1000);
          if (new URL(page.url()).pathname !== "/recipes") fail(`recipe did not close (${page.url()})`);
        }
        const perf = await page.evaluate(() => ({ frames: window.__perf.frames, loafs: window.__perf.loafs, spans: window.__perf.spans }));
        const long = {};
        console.log(`  ${title}`);
        for (const kind of KINDS) {
          const spans = perf.spans.filter((x) => x.kind === kind);
          const frames = perf.frames.filter(([t]) => spans.some((x) => t >= x.t0 && t <= x.t1)).map(([, d]) => d);
          // A LoAF belongs to a transition when it overlaps one. v4 (V4-WINT): a tab switch at 4×
          // may run to 60 ms. The tab layer is now a `pane` size container (v4 §1.1), so showing a
          // tab re-lays out its whole subtree instead of taking Blink's cheap inherited-visibility
          // path: switching into Recipes (≈2.3k elements) measures 48–56 ms at 4×, 12–14 ms at 1×.
          // The 1× desktop pass (v4 performance flow) still allows no long frame at all.
          const budget = kind === "tab switch" ? 60 : 50;
          long[kind] = perf.loafs.filter((l) => l.duration > budget && spans.some((x) => l.start < x.t1 && l.start + l.duration > x.t0));
          const over8 = frames.filter((d) => d > 8.34).length;
          const over16 = frames.filter((d) => d > 16.9).length;
          console.log(
            `  · ${kind} ×${spans.length}: ${frames.length} frames, p50 ${pct(frames, 0.5).toFixed(1)} ms, p95 ${pct(frames, 0.95).toFixed(1)} ms, max ${Math.max(0, ...frames).toFixed(1)} ms; over 8.33 ms: ${over8}, over 16.7 ms: ${over16}; long animation frames: ${long[kind].length}`,
          );
          for (const l of long[kind]) {
            const parts = `script ${Math.round(l.script)} ms (forced layout ${Math.round(l.forced)} ms), rAF ${Math.round(l.raf)} ms, style and layout ${Math.round(l.layout)} ms`;
            console.log(`      long animation frame ${Math.round(l.duration)} ms: ${parts}${l.scripts.length ? ` · ${l.scripts.join(", ")}` : ""}`);
          }
        }
        return long;
      };

      // LoAF entries start at 50 ms, so any one inside a transition is a failure. At 4× throttle
      // the heaviest frames here sit at 30–45 ms, close enough to the line that another process
      // on the machine can push one over. So a long frame fails the pass only when the same kind
      // of transition is long again in a second pass. Known and tolerated by this rule: the first
      // recipe opened in a session renders the detail screen for the first time (60–70 ms at 4×,
      // before the flight starts, so it is a late start, not a dropped frame); repeats stay under 50.
      const first = await pass("first pass");
      const suspects = KINDS.filter((k) => first[k].length > 0);
      const second = suspects.length ? await pass("second pass (the first saw a long frame)") : null;
      await page.evaluate(() => {
        window.__perf.on = false;
      });
      await cdp.send("Emulation.setCPUThrottlingRate", { rate: 1 });
      if (!(await page.evaluate(() => window.__perf.loafSupported))) fail("this Chrome has no long-animation-frame entries; the perf pass cannot run");
      for (const kind of KINDS) {
        const again = second?.[kind] ?? [];
        if (first[kind].length && again.length) {
          fail(`${kind}: long animation frames in both passes (${first[kind].map((l) => Math.round(l.duration)).join(", ")} ms, then ${again.map((l) => Math.round(l.duration)).join(", ")} ms)`);
        } else if (first[kind].length) {
          ok(`${kind}: one pass had a long frame (${first[kind].map((l) => Math.round(l.duration)).join(", ")} ms), the repeat had none`);
        } else ok(`${kind}: no long animation frame at 4× CPU throttle`);
      }
    },
  },
  ...V4_FLOWS(),
];

// ---------------------------------------------------------------- v4 flows (V4-WINT, v4-spec §8)

/** Onboarded, with optional settings and N brews of the Hoffmann V60, written as the app stores them. */
async function seed(page, settings = {}, brews = 0) {
  await page.goto(BASE + "/");
  await page.waitForURL("**/welcome");
  await page.evaluate(
    ({ settings, brews }) =>
      new Promise((resolve, reject) => {
        const open = indexedDB.open("coffee");
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const db = open.result;
          const tx = db.transaction(["kv", "brew_logs"], "readwrite");
          const kv = tx.objectStore("kv");
          const get = kv.get("settings");
          get.onsuccess = () => kv.put({ key: "settings", value: { ...(get.result?.value ?? {}), onboardingDone: true, ...settings } });
          const taste = { acidity: 5, sweetness: 5, bitterness: 5, body: 5, balance: 5, aftertaste: 5, clarity: 5 };
          const now = Date.now();
          for (let i = 0; i < brews; i++) {
            const at = now - (i + 1) * 86_400_000;
            tx.objectStore("brew_logs").add({ uid: crypto.randomUUID(), startedAt: at, updatedAt: at, recipeId: i % 2 ? "hoffmann-french-press" : "hoffmann-v60", recipeName: i % 2 ? "The Ultimate French Press Technique" : "The Ultimate V60 Technique", recipeAuthor: "James Hoffmann", brewer: i % 2 ? "French press" : "V60 02", brewerId: i % 2 ? "french-press" : "hario-v60-02", beanId: null, beanLabel: i % 3 ? "Square Mile — Red Brick" : "Onyx — Geometry", daysOffRoast: 12, grinderId: null, grinderLabel: "", grindSetting: "Medium-fine", grindClicks: null, grindValue: null, doseG: 30, waterG: 500, tempC: 94, waterId: null, totalTimeSec: 200 + i * 5, drawdownSec: null, beverageG: null, tdsPercent: null, extractionYield: null, flowFeel: null, bedShape: null, sludge: null, channeling: null, bedNotes: "", taste, overall: 5 + (i % 5), descriptors: [], faults: [], notes: i === 0 ? "geometry was lovely" : "", nextTime: "", isFavourite: false, sharedAt: null, grindKind: "" });
          }
          tx.oncomplete = () => (db.close(), resolve());
          tx.onerror = () => reject(tx.error);
        };
      }),
    { settings, brews },
  );
}

/** Which navigation is showing: "bar", "rail" or "sidebar" (the CSS module class of the visible nav). */
function navKind(page) {
  return page.evaluate(() => {
    const kinds = [...document.querySelectorAll('nav[aria-label="Sections"]')]
      .filter((n) => n.getClientRects().length > 0 && getComputedStyle(n).visibility !== "hidden")
      .map((n) => /(?:^|\s)_(bar|rail|sidebar)_/.exec(n.className)?.[1] ?? "?");
    return kinds.join("+") || "none";
  });
}

/** Rect of an element and whether it sits wholly inside the window. */
function rectOf(page, selector) {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { top: Math.round(r.top), bottom: Math.round(r.bottom), left: Math.round(r.left), right: Math.round(r.right), inside: r.width > 0 && r.left >= 0 && r.top >= 0 && r.right <= innerWidth + 0.5 && r.bottom <= innerHeight + 0.5, font: parseFloat(getComputedStyle(el).fontSize) };
  }, selector);
}

const paneH1 = (page) => page.evaluate(() => document.querySelector('section[data-tab][data-state="active"] [data-detail-pane] h1')?.textContent?.trim() ?? null);
const path_ = (page) => new URL(page.url()).pathname;
const visibleH1 = async (page) => (await h1(page)).at(-1) ?? null;

async function startBrew(page, id) {
  await page.goto(`${BASE}/brew/${id}`);
  await settle(page, 900);
  await page.getByRole("button", { name: "Start brew" }).last().click();
  await settle(page, 900);
}

function V4_FLOWS() {
  return [
    {
      name: "v4 (a) navigation per window class, [ collapses the sidebar",
      async run({ page }) {
        await seed(page);
        for (const [w, h, want] of [
          [390, 844, "bar"],
          [700, 900, "rail"],
          [1024, 768, "rail"],
          [1440, 900, "sidebar"],
        ]) {
          await page.setViewportSize({ width: w, height: h });
          await page.goto(BASE + "/");
          await settle(page);
          const got = await navKind(page);
          if (got !== want) fail(`nav at ${w}x${h}: ${got}, expected ${want}`);
        }
        ok("bar at 390, rail at 700 and 1024, sidebar at 1440");
        await page.evaluate(() => document.activeElement?.blur?.());
        await page.keyboard.press("[");
        await settle(page, 300);
        const collapsed = await navKind(page);
        await page.keyboard.press("[");
        await settle(page, 300);
        const expanded = await navKind(page);
        if (collapsed !== "rail" || expanded !== "sidebar") fail(`[ toggles the sidebar: ${collapsed} then ${expanded}`);
        else ok("[ collapses the sidebar to the rail and back");
        // No sideways scroll at any §8.1 size on Today.
        const wide = [];
        for (const [w, h] of [[360, 800], [640, 360], [844, 390], [915, 412], [720, 450], [600, 960], [768, 1024], [820, 1180], [1024, 600], [1180, 820], [1280, 800], [1600, 900], [1920, 1080], [2560, 1440]]) {
          await page.setViewportSize({ width: w, height: h });
          await settle(page, 250);
          const over = await page.evaluate(() => [...document.querySelectorAll("section[data-tab], .detail, [data-list-pane], [data-detail-pane], body")].filter((el) => el.getClientRects().length && el.scrollWidth > el.clientWidth + 1).map((el) => `${el.tagName.toLowerCase()} ${el.scrollWidth}>${el.clientWidth}`));
          if (over.length) wide.push(`${w}x${h}: ${over.join(", ")}`);
        }
        if (wide.length) fail(`sideways scroll: ${wide.join("; ")}`);
        else ok("Today never scrolls sideways at the 14 other §8.1 sizes");
      },
    },
    {
      name: "v4 (b) recipes list-detail at 1440: select, J/Enter, Esc, resize",
      async run({ page }) {
        await seed(page);
        await page.setViewportSize({ width: 1440, height: 900 });
        await page.goto(BASE + "/recipes");
        await settle(page, 1000);
        const rows = page.locator('section[data-tab="recipes"] ul[aria-label="Recipes"] > li > button:first-child');
        if ((await rows.count()) < 3) {
          fail(`recipes at 1440: ${await rows.count()} rows (not list-detail?)`);
          return;
        }
        const defaultH1 = await paneH1(page);
        if (!defaultH1) fail("recipes at 1440: the detail pane is empty by default");
        const nameOf = async (i) => ((await rows.nth(i).locator("[class*=rowNameText]").textContent()) ?? "").trim();
        const second = await nameOf(1);
        await rows.nth(1).click();
        await settle(page);
        const at = path_(page);
        if (!/^\/recipes\/[^/]+$/.test(at) || (await paneH1(page)) !== second) fail(`row click: url ${at}, pane "${await paneH1(page)}", expected "${second}"`);
        else ok(`row click selects into the pane (${at})`);
        if ((await page.locator(".detail").count()) > 0 && (await page.locator(".detail").first().isVisible())) fail("a full-window layer opened over the pane");
        // J moves the roving focus and (after 120 ms of rest) the selection; Enter opens that row.
        await page.keyboard.press("j");
        await settle(page, 400);
        await page.keyboard.press("Enter");
        await settle(page);
        const third = await nameOf(2);
        if ((await paneH1(page)) !== third) fail(`J then Enter: pane shows "${await paneH1(page)}", expected "${third}"`);
        else ok("J then Enter moves the selection to the next row");
        await page.keyboard.press("Escape");
        await settle(page);
        if (path_(page) !== "/recipes" || (await paneH1(page)) !== defaultH1) fail(`Escape: ${path_(page)}, pane "${await paneH1(page)}"`);
        else ok("Escape returns the pane to the default");
        // Resize with a selection: the detail survives as a layer, then returns to the pane.
        await rows.nth(1).click();
        await settle(page);
        const sel = path_(page);
        await page.setViewportSize({ width: 400, height: 860 });
        await settle(page);
        const narrow = await visibleH1(page);
        if (path_(page) !== sel || narrow !== second) fail(`1440 → 400: url ${path_(page)}, visible h1 "${narrow}"`);
        await page.setViewportSize({ width: 1440, height: 900 });
        await settle(page);
        if (path_(page) !== sel || (await paneH1(page)) !== second || !(await rows.first().isVisible())) fail(`400 → 1440: url ${path_(page)}, pane "${await paneH1(page)}"`);
        else ok("1440 → 400 keeps the detail on screen; back to 1440 restores the pane");
        await axe(page, "recipes list-detail at 1440");
      },
    },
    {
      name: "v4 (c) command palette",
      async run({ page }) {
        await seed(page);
        await page.setViewportSize({ width: 1440, height: 900 });
        await page.goto(BASE + "/");
        await settle(page, 1000);
        await page.keyboard.press("Control+k");
        const field = page.getByRole("combobox", { name: "Search or type a command" });
        await field.waitFor({ timeout: 5000 });
        await field.fill("hoff");
        await settle(page, 300);
        await page.keyboard.press("Enter");
        await settle(page);
        if (!/^\/recipes\/[^/]*hoffmann[^/]*$/.test(path_(page))) fail(`palette Enter on "hoff": ${path_(page)}`);
        else ok(`Ctrl+K, "hoff", Enter opens ${path_(page)}`);
        await page.keyboard.press("Control+k");
        await field.waitFor({ timeout: 5000 });
        await field.fill("hoff");
        await settle(page, 300);
        await page.keyboard.press("Control+Enter");
        await settle(page);
        if (!/^\/brew\/[^/]*hoffmann/.test(path_(page))) fail(`palette Ctrl+Enter: ${path_(page)}`);
        else ok(`Ctrl+Enter starts a brew (${path_(page)})`);
        // brew.next is registered by Today and drives B and the sidebar's Start brew.
        await page.goto(BASE + "/");
        await settle(page, 1000);
        await page.evaluate(() => document.activeElement?.blur?.());
        await page.keyboard.press("b");
        await settle(page);
        if (!path_(page).startsWith("/brew/")) fail(`B on Today: ${path_(page)}`);
        else ok(`B on Today runs brew.next (${path_(page)})`);
        // Short windows (phone landscape): the field, not the scrolling sheet body, takes focus.
        for (const [w, h] of [[844, 390], [640, 360]]) {
          await page.setViewportSize({ width: w, height: h });
          await page.goto(BASE + "/");
          await settle(page, 1000);
          await page.keyboard.press("Control+k");
          await settle(page, 600);
          await page.keyboard.type("aero");
          await settle(page, 600);
          const st = await page.evaluate(() => ({ value: document.activeElement?.value ?? null, status: document.querySelector("dialog[open] [role=status]")?.textContent ?? "" }));
          if (st.value !== "aero") fail(`palette at ${w}x${h}: typing landed in ${JSON.stringify(st.value)}`);
          else if (!/results?$/.test(st.status)) fail(`palette at ${w}x${h}: status "${st.status}"`);
          else ok(`palette at ${w}x${h} focuses its field and says "${st.status}"`);
          await page.keyboard.press("Escape");
          await settle(page, 400);
        }
        await page.setViewportSize({ width: 1440, height: 900 });
      },
    },
    {
      name: "v4 fixes: short-window a11y, Space on brew controls, review way out, pane focus",
      async run({ page }) {
        await seed(page);
        // A11 at the short rail: phone landscape and a 1440 laptop at 200% zoom.
        for (const [w, h, dpr] of [[844, 390, 1], [720, 450, 2]]) {
          await page.setViewportSize({ width: w, height: h });
          for (const url of ["/", "/recipes", "/log"]) {
            await page.goto(BASE + url);
            await settle(page, 800);
            await axe(page, `${w}x${h}${dpr > 1 ? " (200% zoom)" : ""} ${url}`);
          }
        }
        // Space on a focused brew control presses it; it must not toggle play/pause instead.
        await page.setViewportSize({ width: 1440, height: 900 });
        await page.goto(BASE + "/brew/hoffmann-v60");
        await settle(page, 1000);
        await page.getByRole("button", { name: "Start brew" }).last().click();
        await settle(page, 1200);
        const stepOf = () => page.evaluate(() => document.body.innerText.match(/Step (\d+) of \d+/)?.[1] ?? "");
        const before = await stepOf();
        await page.getByRole("button", { name: /Next step/ }).first().focus();
        await page.keyboard.press(" ");
        await settle(page, 500);
        const after = await stepOf();
        if (after === before || (await page.getByRole("button", { name: /^Pause/ }).count()) === 0) fail(`Space on Next step: step ${before} → ${after}, timer paused?`);
        else ok(`Space on a focused Next step presses it (step ${before} → ${after}) and the timer keeps running`);
        // Review has its own way out (it covers the nav).
        await page.keyboard.press("f");
        await settle(page, 600);
        const finish = page.getByRole("button", { name: "Finish" });
        if (await finish.count()) await finish.last().click();
        await settle(page, 1400);
        const leave = page.getByRole("button", { name: /^Leave review/ });
        if (!path_(page).startsWith("/review/") || (await leave.count()) === 0) fail(`review at ${path_(page)} has no Leave control`);
        else {
          await leave.click();
          await settle(page, 1000);
          if (path_(page).startsWith("/review/")) fail("Leave review did not leave");
          else ok(`review's close leaves to ${path_(page)}`);
        }
        // Closing a pane selection from inside the pane puts focus back on its row.
        await page.goto(BASE + "/recipes");
        await settle(page, 1000);
        await page.keyboard.press("j");
        await page.keyboard.press("ArrowDown");
        await page.keyboard.press("Enter");
        await settle(page, 700);
        await page.getByRole("button", { name: "Close recipe" }).focus();
        await page.keyboard.press("Enter");
        await settle(page, 700);
        const f = await page.evaluate(() => (document.activeElement === document.body ? "body" : document.activeElement?.closest("[data-list-pane]") ? "list" : "elsewhere"));
        if (path_(page) !== "/recipes" || f !== "list") fail(`close recipe from the pane: ${path_(page)}, focus in ${f}`);
        else ok("closing a recipe from its pane returns focus to the list row");
        // Gear's inline editor takes focus when N opens it.
        await page.goto(BASE + "/gear");
        await settle(page, 1000);
        await page.keyboard.press("n");
        await settle(page, 700);
        const tag = await page.evaluate(() => document.activeElement?.tagName ?? "");
        if (tag !== "INPUT") fail(`Gear N at 1440: focus on ${tag}`);
        else ok("Gear N at 1440 focuses the new coffee's name");
      },
    },
    {
      name: "v4 (d) brew timer layouts, Space and Esc",
      async run({ page }) {
        await seed(page, { soundCuesAsked: true });
        await page.setViewportSize({ width: 1024, height: 768 });
        await startBrew(page, "hoffmann-v60");
        const big = await rectOf(page, '[role="timer"]');
        if (!(big?.font >= 120)) fail(`1024x768 clock font ${big?.font}px`);
        else ok(`1024x768 clock is ${big.font}px`);
        for (const [w, h] of [[640, 360], [844, 390], [915, 412], [720, 450]]) {
          await page.setViewportSize({ width: w, height: h });
          await settle(page, 500);
          const c = await rectOf(page, '[role="timer"]');
          const p = await rectOf(page, 'button[aria-label="Pause"], button[aria-label="Resume"]');
          const t = await rectOf(page, 'section[aria-label="Current step"] h2');
          if (!c?.inside || !p?.inside || !t?.inside) fail(`${w}x${h}: clock ${JSON.stringify(c)}, pause ${JSON.stringify(p)}, title ${JSON.stringify(t)}`);
        }
        ok("640x360, 844x390, 915x412, 720x450: clock, Pause and the step title in the window");
        await page.setViewportSize({ width: 1440, height: 900 });
        await settle(page, 400);
        await page.evaluate(() => document.activeElement?.blur?.());
        await page.keyboard.press("Space");
        await settle(page, 400);
        const label = await page.evaluate(() => document.querySelector('button[aria-label="Pause"], button[aria-label="Resume"]')?.getAttribute("aria-label"));
        if (label !== "Resume") fail(`Space: the button says ${label}`);
        else ok("Space pauses");
        await page.keyboard.press("Escape");
        const dlg = page.getByRole("dialog", { name: "Leave this brew?" });
        await dlg.waitFor({ timeout: 5000 });
        await settle(page, 300);
        const focused = await page.evaluate(() => document.activeElement?.textContent?.trim());
        if (focused !== "Keep it going") fail(`Esc confirm focus: ${focused}`);
        else ok('Esc asks "Leave this brew?" with "Keep it going" focused');
        await page.keyboard.press("Escape");
      },
    },
    {
      name: "v4 (e) method-aware review: French press and espresso",
      async run({ page }) {
        await seed(page, { soundCuesAsked: true, reviewMode: "quick" }, 1);
        await startBrew(page, "hoffmann-french-press");
        await page.keyboard.press("f");
        await page.getByRole("button", { name: "Finish", exact: true }).click();
        await page.waitForURL(/\/review\//, { timeout: 10_000 });
        await settle(page, 900);
        await page.getByRole("button", { name: /More detail/ }).click();
        await settle(page, 400);
        const press = await page.evaluate(() => document.body.innerText);
        if (/DRAWDOWN/.test(press) || /THE BED/.test(press)) fail("French press review shows Drawdown or The bed");
        else ok("French press quick review (with More detail open): no Drawdown, no Bed");
        await axe(page, "French press review");
        await page.goto(`${BASE}/brew/3fe-classic-double`);
        await settle(page, 900);
        await page.getByRole("button", { name: "Start brew" }).last().click();
        await settle(page, 700);
        await page.keyboard.press("Space");
        await page.waitForTimeout(1500);
        await page.keyboard.press("Space");
        await settle(page, 500);
        await page.getByRole("button", { name: "Rate it" }).click();
        await page.waitForURL(/\/review\//, { timeout: 10_000 });
        await settle(page, 900);
        const shot = await page.evaluate(() => document.body.innerText);
        if (!/YIELD/.test(shot) || !/SHOT TIME/.test(shot) || /DRAWDOWN/.test(shot)) fail("espresso review: YIELD / SHOT TIME missing or Drawdown shown");
        else ok("espresso review shows YIELD and SHOT TIME");
      },
    },
    {
      name: "v4 (f) a beginner's onboarding: new, no scale, ground coffee, drip machine",
      async run({ page }) {
        await page.goto(BASE + "/");
        await page.waitForURL("**/welcome");
        await settle(page);
        await page.locator("label").filter({ hasText: /^I'm new to this/ }).click();
        await page.getByRole("button", { name: "Next", exact: true }).click();
        await settle(page, 400);
        const noScale = page.getByRole("radio", { name: "No, I'll use spoons and a jug" });
        if (!(await noScale.isChecked())) fail('"I\'m new to this" did not preselect "No" on the scale question');
        await page.getByRole("button", { name: "Next", exact: true }).click();
        await settle(page, 400);
        await page.locator("label").filter({ hasText: /^I buy ground coffee/ }).click();
        await page.getByRole("button", { name: "Next", exact: true }).click();
        await settle(page, 400);
        await page.getByRole("button", { name: "Automatic drip machine", exact: true }).click();
        await page.getByRole("button", { name: "Next", exact: true }).click();
        await settle(page, 400);
        await page.getByRole("button", { name: "Next", exact: true }).click();
        await settle(page, 600);
        await axe(page, "onboarding: your first brew");
        await page.getByRole("button", { name: "Start my first brew", exact: true }).click();
        await page.waitForURL(/\/brew\//, { timeout: 10_000 });
        await settle(page, 900);
        const id = path_(page).split("/")[2];
        const text = await page.evaluate(() => document.body.innerText);
        if (/v60/i.test(id)) fail(`first brew for a drip machine is ${id}`);
        else ok(`first brew: ${id}`);
        if (!/tablespoon|tbsp/i.test(text) || !/\d\s?ml\b/.test(text)) fail("no-scale setup: no tablespoons or ml");
        else ok("setup shows tablespoons and ml");
        if (!/Pre-ground/.test(text)) fail("setup: no Pre-ground (as bought)");
      },
    },
    {
      name: "v4 (g) log at 1440: search and compare",
      async run({ page }) {
        await seed(page, {}, 8);
        await page.setViewportSize({ width: 1440, height: 900 });
        await page.goto(BASE + "/log");
        await settle(page, 1000);
        const rows = page.locator("section[data-tab=\"log\"] [data-list-pane]").getByRole("button", { name: /^(?!More for ).*Technique/ });
        const before = await rows.count();
        await page.getByRole("searchbox", { name: "Search the log" }).or(page.getByLabel("Search the log")).first().fill("geometry");
        await settle(page, 500);
        const after = await rows.count();
        if (!(before > 1 && after >= 1 && after < before)) fail(`log search: ${before} → ${after} rows`);
        else ok(`search narrows the log (${before} → ${after})`);
        await rows.first().click();
        await settle(page);
        if (!/^\/log\/[0-9a-f-]{36}$/.test(path_(page))) fail(`log row: ${path_(page)}`);
        await page.locator("section[data-tab=\"log\"] [data-detail-pane]").getByRole("button", { name: /Compare with/ }).first().click();
        const picker = page.getByRole("dialog", { name: "Compare with" });
        await picker.waitFor({ timeout: 5000 });
        await picker.locator("li button").first().click();
        await settle(page);
        if (!/^\/log\/[0-9a-f-]{36}\/compare\/[0-9a-f-]{36}$/.test(path_(page)) || (await paneH1(page)) !== "Compare brews") fail(`compare: ${path_(page)}, pane "${await paneH1(page)}"`);
        else ok("Compare with… opens the comparison in the pane");
        await axe(page, "compare at 1440");
      },
    },
    {
      name: "v4 (h) forced colors: rating dots and segments",
      async run({ page }) {
        await seed(page, { soundCuesAsked: true, reviewMode: "detailed" }, 1);
        await page.emulateMedia({ forcedColors: "active" });
        await page.goto(BASE + "/gear/app");
        await settle(page, 900);
        const seg = await page.evaluate(() => {
          const g = [...document.querySelectorAll('[role="radiogroup"]')].find((x) => x.getAttribute("aria-label") === "Theme");
          const radios = g ? [...g.querySelectorAll('[role="radio"]')] : [];
          const look = (el) => {
            const cs = getComputedStyle(el);
            return `${cs.backgroundColor}|${cs.color}|${cs.borderTopWidth} ${cs.borderTopStyle} ${cs.borderTopColor}|${cs.outlineStyle}`;
          };
          const on = radios.find((r) => r.getAttribute("aria-checked") === "true");
          const off = radios.find((r) => r.getAttribute("aria-checked") !== "true");
          return on && off ? { on: look(on), off: look(off) } : null;
        });
        if (!seg || seg.on === seg.off) fail(`forced colors: selected segment looks like the others (${JSON.stringify(seg)})`);
        else ok("forced colors: the selected segment is distinguishable");
        await startBrew(page, "hoffmann-v60");
        await page.keyboard.press("f");
        await page.getByRole("button", { name: "Finish", exact: true }).click();
        await page.waitForURL(/\/review\//, { timeout: 10_000 });
        await settle(page, 900);
        const dots = await page.evaluate(() => {
          const d = document.querySelector('[role="radiogroup"] [role="radio"][aria-label$=" of 10"]');
          if (!d) return null;
          // The dot is drawn by its ::before (A1).
          const cs = getComputedStyle(d, "::before");
          const visible = (cs.borderTopStyle !== "none" && parseFloat(cs.borderTopWidth) > 0) || (cs.backgroundColor !== "rgba(0, 0, 0, 0)" && cs.backgroundColor !== "transparent");
          return { visible, border: `${cs.borderTopWidth} ${cs.borderTopStyle}`, bg: cs.backgroundColor, w: d.getBoundingClientRect().width };
        });
        if (!dots?.visible) fail(`forced colors: rating dots invisible (${JSON.stringify(dots)})`);
        else ok("forced colors: rating dots have a visible edge");
        await page.emulateMedia({ forcedColors: "none" });
      },
    },
    {
      name: "v4 (i) the bar is inert under a layer at 390",
      async run({ page }) {
        await seed(page);
        await page.setViewportSize({ width: 390, height: 844 });
        for (const url of ["/brew/hoffmann-v60", "/recipes/hoffmann-v60", "/welcome"]) {
          await page.goto(BASE + url);
          await settle(page, 800);
          const inert = await page.evaluate(() => {
            const nav = document.querySelector('nav[aria-label="Sections"]');
            return nav ? nav.closest("[inert]") != null || nav.inert : null;
          });
          if (inert !== true) fail(`${url} at 390: the bar is not inert (${inert})`);
        }
        ok("bar is inert on /brew, a recipe and /welcome at 390");
      },
    },
    {
      name: "v4 (j) 200% text: no clipped text in the brew step card or recipe cards",
      async run({ page }) {
        await seed(page, { soundCuesAsked: true });
        const clipped = (root) =>
          page.evaluate((sel) => {
            const out = [];
            for (const box of document.querySelectorAll(sel)) {
              if (!box.getClientRects().length) continue;
              for (const el of [box, ...box.querySelectorAll("*")]) {
                if (el.closest(".visually-hidden")) continue;
                const cs = getComputedStyle(el);
                const hides = ["hidden", "clip"].includes(cs.overflowY) || ["hidden", "clip"].includes(cs.overflow);
                const clamp = cs.webkitLineClamp && cs.webkitLineClamp !== "none";
                if ((hides || clamp) && el.scrollHeight > el.clientHeight + 2 && (el.textContent ?? "").trim()) out.push(`${el.tagName.toLowerCase()}.${String(el.className).slice(0, 20)} ${el.scrollHeight}>${el.clientHeight}`);
              }
            }
            return out.slice(0, 6);
          }, root);
        for (const [w, h] of [[390, 844], [1440, 900]]) {
          await page.setViewportSize({ width: w, height: h });
          await page.goto(BASE + "/recipes");
          await settle(page, 900);
          await page.evaluate(() => (document.documentElement.style.fontSize = "32px"));
          await settle(page, 500);
          const cards = await clipped('section[data-tab="recipes"] ul[aria-label="Recipes"] > li');
          if (cards.length) fail(`200% text at ${w}: clipped in recipe cards: ${cards.join(", ")}`);
          await page.evaluate(() => (document.documentElement.style.fontSize = ""));
          await startBrew(page, "hoffmann-v60");
          await page.evaluate(() => (document.documentElement.style.fontSize = "32px"));
          await settle(page, 500);
          const step = await clipped('section[aria-label="Current step"]');
          if (step.length) fail(`200% text at ${w}: clipped in the step card: ${step.join(", ")}`);
          const p = await rectOf(page, 'button[aria-label="Pause"], button[aria-label="Resume"]');
          if (!p?.inside) fail(`200% text at ${w}: Pause is outside the window`);
          await page.evaluate(() => (document.documentElement.style.fontSize = ""));
          await page.getByRole("button", { name: "Leave this brew" }).click();
          await page.getByRole("dialog", { name: "Leave this brew?" }).getByRole("button", { name: "Discard", exact: true }).click();
          await settle(page);
        }
        ok("200% text at 390 and 1440: recipe cards and the step card clip nothing, Pause stays reachable");
      },
    },
    {
      name: "v4 (k) backup 2.1: import, export, import into a fresh browser",
      async run({ page, context }) {
        await page.addInitScript(() => Object.defineProperty(window, "showSaveFilePicker", { value: undefined, configurable: true }));
        const restore = async (p, file) => {
          await p.goto(BASE + "/gear/app");
          await settle(p);
          await p.locator('input[type="file"][accept*="json"]').setInputFiles(file);
          const sheet = p.getByRole("dialog", { name: "Restore from a backup" });
          await sheet.getByText(/^Found /).waitFor({ timeout: 10_000 });
          await sheet.getByRole("button", { name: "Import", exact: true }).click();
          await sheet.getByRole("button", { name: "Done", exact: true }).waitFor({ timeout: 10_000 });
          const line = (await sheet.innerText()).replace(/\s+/g, " ");
          await sheet.getByRole("button", { name: "Done", exact: true }).click();
          await settle(p);
          return line;
        };
        const save = async (p) => {
          await p.goto(BASE + "/gear/app");
          await settle(p);
          const [dl] = await Promise.all([p.waitForEvent("download"), p.getByRole("button", { name: /Back up everything/ }).click()]);
          return JSON.parse(readFileSync(await dl.path(), "utf8"));
        };
        const keys = (doc) => ({
          form: doc.beans?.map((b) => `${b.form ?? ""}/${b.groundFor ?? ""}`).sort().join(","),
          unit: doc.grinders?.map((g) => g.setting?.settingUnit ?? "").sort().join(","),
          kind: doc.brews?.map((b) => b.grindKind ?? "").sort().join(","),
          faults: doc.brews?.flatMap((b) => b.faults ?? []).sort().join(","),
        });
        const v21 = path.join(web, "..", "shared", "fixtures", "backup-v2.1-sample.json");
        const want = keys(JSON.parse(readFileSync(v21, "utf8")));
        await markOnboarded(page);
        const line = await restore(page, v21);
        if (!/Added /.test(line)) fail(`2.1 restore: ${line.slice(0, 160)}`);
        const first = await save(page);
        const got1 = keys(first);
        if (JSON.stringify(got1) !== JSON.stringify(want)) fail(`2.1 keys after import + export: ${JSON.stringify(got1)}, expected ${JSON.stringify(want)}`);
        else ok(`2.1 sample imports and exports with its new keys (${want.form}; ${want.unit}; ${want.kind}; ${want.faults})`);
        // Into a fresh browser: the exported file, then the 2.0 sample alongside it.
        const tmp = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "dist", "smoke-v21-roundtrip.json");
        writeFileSync(tmp, JSON.stringify(first));
        const ctx2 = await context.browser().newContext({ serviceWorkers: "block", viewport: { width: 412, height: 915 } });
        const p2 = await ctx2.newPage();
        watch(p2, () => "v4 (k) fresh");
        await p2.addInitScript(() => Object.defineProperty(window, "showSaveFilePicker", { value: undefined, configurable: true }));
        await markOnboarded(p2);
        await restore(p2, tmp);
        const v2line = await restore(p2, path.join(web, "..", "shared", "fixtures", "backup-v2-sample.json"));
        if (!/Added |Nothing new|already/i.test(v2line)) fail(`2.0 sample after 2.1: ${v2line.slice(0, 160)}`);
        const second = keys(await save(p2));
        for (const k of ["form", "unit", "kind"]) {
          for (const v of want[k].split(",").filter((x) => x && x !== "/")) if (!second[k].split(",").includes(v)) fail(`fresh browser lost ${k} ${v}: ${second[k]}`);
        }
        if (!second.faults.includes("Too weak")) fail(`fresh browser lost the "Too weak" fault: ${second.faults}`);
        else ok("a fresh browser keeps the 2.1 keys and also reads the 2.0 sample");
        await ctx2.close();
        rmSync(tmp, { force: true });
      },
    },
    {
      // v4-spec §8.3 at 1× on the desktop size (the 4× flow above is the phone check).
      name: "v4 performance at 1440: tab switch, pane changes, dialog, side sheet, palette, resize",
      async run({ page }) {
        await seed(page, {}, 12);
        await page.setViewportSize({ width: 1440, height: 900 });
        await page.goto(BASE + "/");
        await settle(page, 3000);
        const side = page.getByRole("navigation", { name: "Sections" });
        for (const label of ["Recipes", "Log", "Insights", "Gear", "Today"]) {
          await side.getByRole("link", { name: label }).first().click();
          await settle(page, 500);
        }
        await page.evaluate(() => {
          const p = (window.__perf = { frames: [], loafs: [], spans: [], on: true });
          new PerformanceObserver((l) => {
            for (const e of l.getEntries()) p.loafs.push({ start: e.startTime, duration: e.duration });
          }).observe({ type: "long-animation-frame" });
          let last = performance.now();
          const tick = (now) => {
            p.frames.push([now, now - last]);
            last = now;
            if (p.on) requestAnimationFrame(tick);
          };
          requestAnimationFrame(tick);
        });
        const span = async (kind, act, ms = 600) => {
          const t0 = await page.evaluate(() => performance.now());
          await act();
          await page.waitForTimeout(ms);
          const t1 = await page.evaluate(() => performance.now());
          await page.evaluate(([k, a, b]) => window.__perf.spans.push({ kind: k, t0: a, t1: b }), [kind, t0, t1]);
        };
        const centre = async (loc) => {
          const b = await loc.boundingBox();
          return [b.x + b.width / 2, b.y + b.height / 2];
        };
        const at = {};
        for (const label of ["Today", "Recipes", "Log", "Insights", "Gear"]) at[label] = await centre(side.getByRole("link", { name: label }).first());
        for (const label of ["Recipes", "Log", "Insights", "Gear", "Today", "Log", "Recipes", "Gear", "Insights", "Recipes"]) await span("tab switch", () => page.mouse.click(...at[label]));
        const rows = page.locator('section[data-tab="recipes"] ul[aria-label="Recipes"] > li > button:first-child');
        const rowAt = [];
        for (let i = 0; i < 5; i++) rowAt.push(await centre(rows.nth(i)));
        for (let i = 0; i < 10; i++) await span("pane change", () => page.mouse.click(...rowAt[i % 5]));
        // In-app navigation only from here: a reload would drop the observers.
        await side.getByRole("link", { name: "Settings" }).first().click();
        await settle(page, 1200);
        // At large the Settings sections are a list with the section beside it.
        await page.locator('section[data-tab="gear"] [data-list-pane]').getByText("Your data", { exact: true }).first().click();
        await settle(page, 600);
        const del = await centre(page.getByRole("button", { name: "Delete everything in this browser" }));
        for (let i = 0; i < 2; i++) {
          await span("dialog", () => page.mouse.click(...del));
          await span("dialog", () => page.keyboard.press("Escape"));
        }
        await page.mouse.click(...at.Log);
        await settle(page, 1200);
        const addKey = page.locator("section[data-tab=\"log\"] [data-detail-pane]").getByRole("button", { name: /Set up analysis|Add key/ }).first();
        if (await addKey.count()) {
          const k = await centre(addKey);
          for (let i = 0; i < 2; i++) {
            await span("side sheet", () => page.mouse.click(...k));
            await span("side sheet", () => page.keyboard.press("Escape"));
          }
        } else fail("perf: no Set up analysis button in the Log pane to open the side sheet");
        for (let i = 0; i < 3; i++) {
          await span("palette", () => page.keyboard.press("Control+k"));
          await span("palette", () => page.keyboard.press("Escape"));
        }
        for (const [w, h] of [[1300, 900], [1100, 900], [800, 900], [1100, 900], [1300, 900], [1440, 900]]) await span("resize", () => page.setViewportSize({ width: w, height: h }), 400);
        const perf = await page.evaluate(() => ((window.__perf.on = false), { frames: window.__perf.frames, loafs: window.__perf.loafs, spans: window.__perf.spans }));
        const pct = (xs, q) => (xs.length ? [...xs].sort((x, y) => x - y)[Math.min(xs.length - 1, Math.floor(q * xs.length))] : 0);
        for (const kind of ["tab switch", "pane change", "dialog", "side sheet", "palette", "resize"]) {
          const spans = perf.spans.filter((x) => x.kind === kind);
          const frames = perf.frames.filter(([t]) => spans.some((x) => t >= x.t0 && t <= x.t1)).map(([, d]) => d);
          const long = perf.loafs.filter((l) => spans.some((x) => l.start < x.t1 && l.start + l.duration > x.t0));
          const line = `${kind} ×${spans.length}: ${frames.length} frames, p50 ${pct(frames, 0.5).toFixed(1)} ms, p95 ${pct(frames, 0.95).toFixed(1)} ms, max ${Math.max(0, ...frames).toFixed(1)} ms; LoAF ${long.map((l) => Math.round(l.duration)).join(", ") || "none"}`;
          // Resizing re-lays out the whole window by nature; it is recorded, and only (a)–(c) gate.
          if (kind !== "resize" && (long.length || pct(frames, 0.95) > 17.5)) fail(line);
          else ok(line);
        }
      },
    },
  ];
}

// ---------------------------------------------------------------- runner

let browser;
const started = Date.now();
try {
  const r = await waitForServer();
  const csp = r.headers.get("content-security-policy") ?? "";
  if (!csp.includes("script-src 'self'")) fail(`preview is not serving the production CSP (got "${csp}")`);
  const deep = await fetch(BASE + "/log/anything");
  if (!deep.ok || !(await deep.text()).includes('id="root"')) fail("/log/anything does not return the app");

  const channel = process.env.SMOKE_CHANNEL ?? "chrome";
  browser = await chromium.launch({ ...(channel ? { channel } : {}), headless: !args.has("--headed") });
  for (const flow of FLOWS) {
    if (only && !flow.name.toLowerCase().includes(only)) continue;
    console.log(`\n${flow.name}`);
    // A fresh context per flow: empty IndexedDB, so the first-launch gate runs.
    const context = await browser.newContext({ serviceWorkers: "allow", viewport: { width: 412, height: 915 }, colorScheme: "dark" });
    await context.addInitScript(() => {
      window.__csp = [];
      document.addEventListener("securitypolicyviolation", (e) => window.__csp.push(`${e.violatedDirective} ${e.blockedURI}`));
    });
    const page = await context.newPage();
    let where = "";
    page.on("framenavigated", (f) => {
      if (f === page.mainFrame()) where = new URL(f.url()).pathname;
    });
    watch(page, () => `${flow.name} @ ${where}`);
    try {
      await flow.run({ page, context });
      const v = await cspViolations(page).catch(() => []);
      for (const x of v) fail(`${flow.name}: CSP violation ${x}`);
    } catch (e) {
      // The first line plus Playwright's "waiting for <locator>" line, so a timeout names its target.
      const lines = e instanceof Error ? e.message.split("\n") : [String(e)];
      const target = lines.find((l) => l.includes("waiting for"))?.trim();
      fail(`${flow.name}: ${lines[0]}${target ? ` (${target})` : ""}`);
    } finally {
      await context.close();
    }
  }
} catch (e) {
  fail(e instanceof Error ? e.message : String(e));
} finally {
  await browser?.close();
  preview.kill();
}

const secs = ((Date.now() - started) / 1000).toFixed(1);
if (problems.length) {
  console.error(`\nsmoke: ${problems.length} problem(s) in ${secs}s`);
  process.exit(1);
}
console.log(`\nsmoke: OK in ${secs}s`);
process.exit(0);
