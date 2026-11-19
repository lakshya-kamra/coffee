// `npm run vercel-sim` (web-spec §16.3): proves the Vercel build needs nothing outside the
// upload. Copies exactly what .vercelignore lets through into a fresh temp directory, runs
// vercel.json's installCommand and buildCommand there, and checks the output.
//
// .vercelignore is read with gitignore semantics for the patterns it uses: "/x" anchors at the
// root, "**/x" matches at any depth, "!" re-includes, the last matching rule wins, and nothing
// under an excluded directory is looked at again.
import { execSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const keep = process.argv.includes("--keep");

function toRegex(glob) {
  let g = glob.replace(/\/$/, "");
  const anchored = g.startsWith("/");
  if (anchored) g = g.slice(1);
  let re = "";
  for (let i = 0; i < g.length; i++) {
    const c = g[i];
    if (c === "*" && g[i + 1] === "*") {
      re += ".*";
      i++;
      if (g[i + 1] === "/") i++;
    } else if (c === "*") re += "[^/]*";
    else if (c === "?") re += "[^/]";
    else re += c.replace(/[.+^${}()|[\]\\]/g, "\\$&");
  }
  // Unanchored patterns without a slash match a name at any depth (gitignore rule).
  const prefix = anchored || g.includes("/") ? "^" : "^(?:.*/)?";
  return new RegExp(`${prefix}${re}$`);
}

const rules = readFileSync(path.join(repo, ".vercelignore"), "utf8")
  .split(/\r?\n/)
  .map((l) => l.trim())
  .filter((l) => l && !l.startsWith("#"))
  .map((l) => (l.startsWith("!") ? { negate: true, re: toRegex(l.slice(1)) } : { negate: false, re: toRegex(l) }));

function ignored(rel) {
  let out = false;
  for (const r of rules) if (r.re.test(rel)) out = !r.negate;
  return out;
}

const tmp = mkdtempSync(path.join(os.tmpdir(), "coffee-vercel-sim-"));
let files = 0;
function copyTree(rel) {
  const abs = path.join(repo, rel);
  for (const ent of readdirSync(abs, { withFileTypes: true })) {
    const childRel = rel ? `${rel}/${ent.name}` : ent.name;
    if (ent.name === ".git" || ignored(childRel)) continue;
    if (ent.isDirectory()) copyTree(childRel);
    else {
      mkdirSync(path.join(tmp, rel), { recursive: true });
      cpSync(path.join(repo, childRel), path.join(tmp, childRel));
      files++;
    }
  }
}

function run(cmd) {
  console.log(`$ ${cmd}`);
  execSync(cmd, { cwd: tmp, stdio: "inherit", env: { ...process.env, CI: "1" } });
}

let ok = false;
try {
  copyTree("");
  const top = readdirSync(tmp).sort();
  console.log(`Copied ${files} files into ${tmp}: ${top.join(", ")}`);
  for (const banned of ["app", "gradle", "docs", "build.gradle.kts", "local.properties"]) {
    if (existsSync(path.join(tmp, banned))) throw new Error(`.vercelignore let ${banned} through`);
  }
  const v = JSON.parse(readFileSync(path.join(tmp, "vercel.json"), "utf8"));
  run(v.installCommand);
  run(v.buildCommand);
  for (const f of ["index.html", "sw.js", "manifest.webmanifest", "boot.js", "icons/pwa-192x192.png"]) {
    const p = path.join(tmp, v.outputDirectory, f);
    if (!existsSync(p)) throw new Error(`missing ${v.outputDirectory}/${f}`);
  }
  ok = true;
  console.log("vercel-sim: OK (the upload builds on its own)");
} catch (e) {
  console.error(`vercel-sim: FAILED: ${e instanceof Error ? e.message : String(e)}`);
  process.exitCode = 1;
} finally {
  if (!keep) rmSync(tmp, { recursive: true, force: true });
  else if (ok) console.log(`Kept ${tmp}`);
}
