// `npm run check-deploy -- https://<deployment>` (web-spec §16.1 post-deploy check, §20.5 step 3).
// Asks a deployed copy of the app for the handful of things only the host can get wrong: the
// security headers from vercel.json, the SPA rewrite, the service worker's content type and
// cache rules, and that a missing hashed asset is a 404 rather than the app's HTML.
//
// It only reads (GET), sends no data, and talks to the one origin it is given.
// `--local` is for `vite preview`, which applies the global headers but not Vercel's rewrites
// or Cache-Control rules: those checks are reported as skipped.
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const argv = process.argv.slice(2);
const local = argv.includes("--local");
const target = argv.find((a) => /^https?:\/\//.test(a));
if (!target) {
  console.error("usage: npm run check-deploy -- https://<deployment> [--local]");
  process.exit(2);
}
const origin = new URL(target).origin;
const vercel = JSON.parse(readFileSync(path.join(repo, "vercel.json"), "utf8"));
const wanted = vercel.headers.find((h) => h.source === "/(.*)")?.headers ?? [];

let failed = 0;
const ok = (msg) => console.log(`  ok    ${msg}`);
const skip = (msg) => console.log(`  skip  ${msg}`);
const fail = (msg) => {
  failed += 1;
  console.log(`  FAIL  ${msg}`);
};

async function get(p) {
  const res = await fetch(origin + p, { redirect: "manual", headers: { accept: p.endsWith(".js") ? "*/*" : "text/html,*/*" } });
  return { res, body: await res.text(), type: res.headers.get("content-type") ?? "" };
}
const isHtml = (r) => r.res.status === 200 && /text\/html/.test(r.type) && r.body.includes('<div id="root">');
const isJs = (r) => r.res.status === 200 && /javascript/.test(r.type);
/** A Cache-Control rule Vercel applies; `vite preview` does not, so `--local` skips it. */
function cache(r, what, pattern) {
  const v = r.res.headers.get("cache-control") ?? "";
  if (local) skip(`${what}: Cache-Control (not applied by vite preview)`);
  else if (pattern.test(v)) ok(`${what}: Cache-Control ${v}`);
  else fail(`${what}: Cache-Control is "${v}"`);
}

console.log(`check-deploy ${origin}${local ? " (local preview)" : ""}`);
try {
  const home = await get("/");
  if (isHtml(home)) ok("/ serves the app");
  else fail(`/ returned ${home.res.status} ${home.type}`);
  for (const h of wanted) {
    // HSTS means nothing over http://localhost, but the preview still sends it, so it is compared too.
    const got = home.res.headers.get(h.key);
    if (got === h.value) ok(`header ${h.key}`);
    else fail(`header ${h.key}: expected the vercel.json value, got ${got === null ? "nothing" : `"${got}"`}`);
  }
  // Vercel matches header rules against the path asked for, not the rewrite's destination, so the
  // vercel.json no-cache rule (/index.html, /sw.js…) never applies to "/" or a deep link. Those get
  // Vercel's own default for HTML, "public, max-age=0, must-revalidate", which is just as good:
  // what must never happen is the app shell being cached for any length of time.
  const REVALIDATES = /no-cache|max-age=0\b/;
  cache(home, "/", REVALIDATES);

  const deep = await get("/log/anything");
  if (isHtml(deep)) ok("/log/anything returns the app (SPA rewrite)");
  else fail(`/log/anything returned ${deep.res.status} ${deep.type}`);
  cache(deep, "/log/anything", REVALIDATES);

  const sw = await get("/sw.js");
  if (isJs(sw)) ok(`/sw.js is JavaScript (${sw.type})`);
  else fail(`/sw.js returned ${sw.res.status} ${sw.type}: the rewrite is swallowing it`);
  cache(sw, "/sw.js", /no-cache/);

  const boot = await get("/boot.js");
  if (isJs(boot)) ok("/boot.js is JavaScript");
  else fail(`/boot.js returned ${boot.res.status} ${boot.type}`);

  const manifest = await get("/manifest.webmanifest");
  let icons = [];
  try {
    icons = JSON.parse(manifest.body).icons ?? [];
  } catch {
    /* reported below */
  }
  if (manifest.res.status === 200 && icons.length) ok(`/manifest.webmanifest parses, ${icons.length} icons`);
  else fail(`/manifest.webmanifest returned ${manifest.res.status} ${manifest.type}`);
  for (const icon of icons) {
    const r = await fetch(origin + icon.src, { redirect: "manual" });
    await r.arrayBuffer();
    if (r.status === 200 && /image\//.test(r.headers.get("content-type") ?? "")) ok(`icon ${icon.src}`);
    else fail(`icon ${icon.src} returned ${r.status} ${r.headers.get("content-type")}`);
  }

  const entry = /<script type="module"[^>]*src="(\/assets\/[^"]+)"/.exec(home.body)?.[1];
  if (!entry) fail("index.html names no /assets entry script");
  else {
    const r = await get(entry);
    if (isJs(r)) ok(`${entry} is JavaScript`);
    else fail(`${entry} returned ${r.res.status} ${r.type}`);
    cache(r, entry, /immutable/);
  }

  // A stale hashed chunk must 404, so the app reloads once (vite:preloadError) instead of
  // trying to run index.html as a script.
  const missing = await get("/assets/no-such-chunk-00000000.js");
  if (local) skip("/assets/<missing> is a 404 (vite preview falls back to the app)");
  else if (missing.res.status === 404) ok("/assets/<missing> is a 404");
  else fail(`/assets/<missing> returned ${missing.res.status} ${missing.type}`);
} catch (e) {
  fail(`could not reach ${origin}: ${e instanceof Error ? e.message : String(e)}`);
}

console.log(failed ? `\ncheck-deploy: ${failed} problem(s)` : "\ncheck-deploy: OK");
process.exit(failed ? 1 : 0);
