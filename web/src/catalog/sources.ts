/**
 * Where shared/ JSON comes from (web-spec §3.3). Globs, not static imports, so a file that does
 * not exist yet (recipes.json before Android WP-1, principles.json before WP-3) is simply absent
 * instead of a build error. Each file is its own lazily loaded chunk, precached by the SW.
 */
import type { CatalogName } from "./types";

type Loader = () => Promise<unknown>;

const catalogFiles = import.meta.glob<unknown>("@shared/catalog/*.json", { import: "default" });
const rootFiles = import.meta.glob<unknown>(["@shared/recipes.json", "@shared/principles.json", "@shared/glossary.json"], { import: "default" });

/** Files at the root of shared/ rather than in shared/catalog/. */
const ROOT: ReadonlySet<CatalogName> = new Set<CatalogName>(["recipes", "principles", "glossary"]);

function find(files: Record<string, Loader>, fileName: string): Loader | undefined {
  // Keys keep the alias or a relative form depending on the Vite version; match the file name.
  const hit = Object.entries(files).find(([k]) => k.endsWith("/" + fileName));
  return hit?.[1];
}

/** Path shown in problem lines, relative to shared/. */
export function sourcePath(name: CatalogName): string {
  return ROOT.has(name) ? `${name}.json` : `catalog/${name}.json`;
}

/** The parsed JSON of one shared file, or undefined when it does not exist in this build. */
export async function readShared(name: CatalogName): Promise<unknown> {
  const loader = ROOT.has(name) ? find(rootFiles, `${name}.json`) : find(catalogFiles, `${name}.json`);
  return loader ? loader() : undefined;
}
