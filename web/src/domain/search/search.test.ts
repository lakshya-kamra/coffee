// A§7.1 CatalogSearch cases, against the catalog the app ships. A case whose target id is not in
// shared/ is skipped rather than failed (the catalog may lose an entry; the ranker did not break).
import { beforeAll, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { docFor, docsFor, type CatalogKind } from "./catalogDocs";
import { editDistance, normaliseText, search, type SearchDoc } from "./search";

beforeAll(async () => {
  await catalog.load();
});

function top(kind: CatalogKind, q: string): string | undefined {
  return search(docsFor(kind), q)[0]?.doc.id;
}

function present(kind: CatalogKind, id: string): boolean {
  return docFor(kind, id) != null;
}

describe("normaliseText", () => {
  it("folds accents, digraphs and punctuation", () => {
    expect(normaliseText("Mahlkönig")).toBe("mahlkonig");
    expect(normaliseText("Mahlkoenig")).toBe("mahlkonig");
    expect(normaliseText("K-Ultra")).toBe("k ultra");
    expect(normaliseText("  Café  Grüße ")).toBe("cafe grusse");
  });
});

describe("editDistance", () => {
  it("counts an adjacent swap as one edit", () => {
    expect(editDistance("comandante", "commandante")).toBe(1);
    expect(editDistance("geisha", "gesha")).toBe(1);
    expect(editDistance("ab", "ba")).toBe(1);
    expect(editDistance("kitten", "sitting", 5)).toBe(3);
    expect(editDistance("abcdef", "uvwxyz", 1)).toBe(2); // capped at max + 1
  });
});

describe("search ranking", () => {
  const docs: SearchDoc[] = [
    { id: "a", title: "Alpha One", subtitle: "", group: "g1", keys: ["Alpha One"], popularity: 3 },
    { id: "b", title: "Alpha Two", subtitle: "", group: "g2", keys: ["Alpha Two"], popularity: 1 },
    { id: "c", title: "Beta", subtitle: "", group: "g1", keys: ["Beta", "Alphabet soup"], popularity: 2 },
  ];

  it("scores exact over prefix over token prefix over substring", () => {
    expect(search(docs, "alpha one")[0]).toMatchObject({ score: 100 });
    expect(search(docs, "alph")[0]?.score).toBe(80);
    expect(search(docs, "one alp")).toEqual([{ doc: docs[0], score: 60 }]);
    expect(search(docs, "habet")).toEqual([{ doc: docs[2], score: 40 }]);
    expect(search(docs, "betta")).toEqual([{ doc: docs[2], score: 20 }]);
  });

  it("breaks ties by popularity, then title", () => {
    expect(search(docs, "alpha").map((h) => h.doc.id)).toEqual(["b", "c", "a"]);
  });

  it("returns everything in group order for an empty query", () => {
    expect(search(docs, "  ").map((h) => h.doc.id)).toEqual(["c", "a", "b"]);
  });

  it("does not fuzzy-match short tokens", () => {
    expect(search(docs, "bat")).toEqual([]);
  });

  it("respects the limit", () => {
    expect(search(docs, "a", 1)).toHaveLength(1);
  });
});

describe("A§7.1 cases on the shipped catalog", () => {
  const cases: [CatalogKind, string, string][] = [
    ["grinder", "commandante", "comandante-c40-mk4"],
    ["grinder", "mahlkonigg", "mahlkonig-ek43"],
    ["grinder", "mahlkoenig", "mahlkonig-ek43"],
    ["grinder", "k ultra", "1zpresso-k-ultra"],
    ["grinder", "kultra", "1zpresso-k-ultra"],
    ["grinder", "ek43", "mahlkonig-ek43"],
    ["varietal", "gesha", "gesha"],
    ["varietal", "geisha", "gesha"],
    ["roaster", "square mile", "square-mile"],
  ];
  for (const [kind, q, id] of cases) {
    it(`"${q}" → ${id}`, () => {
      if (!present(kind, id)) return; // target not shipped: nothing to assert (A§7.1)
      if (kind === "grinder" && q.startsWith("mahlko")) {
        // "Mahlkönig entries": every hit before the first non-Mahlkönig is a Mahlkönig.
        const hits = search(docsFor(kind), q);
        expect(hits.length).toBeGreaterThan(0);
        expect(hits.every((h) => h.doc.id.startsWith("mahlkonig-"))).toBe(true);
        expect(hits.map((h) => h.doc.id)).toContain(id);
        return;
      }
      expect(top(kind, q)).toBe(id);
    });
  }

  it("builds grinder rows with the A§7.2 subtitle", () => {
    const k = docFor("grinder", "1zpresso-k-ultra");
    if (!k) return;
    expect(k.subtitle).toBe("Hand · Conical 48 mm");
    expect(k.group).toBe("1Zpresso");
  });

  it("finds a country by ISO code and a region by name", () => {
    if (present("origin", "ethiopia")) expect(top("origin", "et")).toBe("ethiopia");
    if (present("region", "ethiopia/Yirgacheffe")) expect(top("region", "yirgachefe")).toBe("ethiopia/Yirgacheffe");
  });
});
