// Catalog → grinder fill (A§7.4), the scale notice words, scale summary, altitude and rest status (A§7.6).
import { beforeAll, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { newGrinder } from "@/data/schema";
import { burrSizeText, feetToMetres, fieldsFromCatalog, findExistingGrinder, isTimed, joinAltitude, localCurrency, metresToFeetText, roastedAgoText, restStatus, scaleDifferences, scaleFromCatalog, scaleNoticeText, scaleSummary, splitAltitude } from "./mapping";

beforeAll(async () => {
  await catalog.load(["grinders"]);
});

describe("grinder from the catalog", () => {
  it("fills every A§7.4 field and leaves usual settings empty", () => {
    const c = catalog.grinder("1zpresso-k-ultra");
    if (!c) return;
    const row = newGrinder(fieldsFromCatalog(c));
    expect(row).toMatchObject({
      brand: "1Zpresso",
      model: "K-Ultra",
      burrType: "conical",
      burrSizeMm: "48",
      settingStyle: "clicks",
      clicksPerRotation: 100,
      micronsPerClick: 20,
      lowerIsFiner: true,
      minSetting: 0,
      maxSetting: 200,
      step: 1,
      catalogId: "1zpresso-k-ultra",
      dialFormat: "",
      settingUnit: "",
      defaultFilterSetting: null,
      defaultEspressoSetting: null,
    });
    expect(scaleDifferences(row, c)).toEqual([]);
    expect(scaleSummary(row)).toBe("clicks · fewer is finer · 100 a turn · 20 µm a click");
  });

  it("explains a 1.x 10-click row and fixes only the scale on accept", () => {
    const c = catalog.grinder("1zpresso-k-ultra");
    if (!c) return;
    const legacy = newGrinder({ brand: "1Zpresso", model: "K-Ultra", clicksPerRotation: 10, micronsPerClick: 20, catalogId: c.id, defaultFilterSetting: 75 });
    expect(scaleDifferences(legacy, c)).toContain("clicksPerRotation");
    expect(scaleNoticeText(legacy, c)).toBe(`${c.rangeSources[0]?.who ?? "1Zpresso"} says 100 clicks a turn; yours says 10`);
    const accepted = { ...legacy, ...scaleFromCatalog(c) };
    expect(accepted.clicksPerRotation).toBe(100);
    expect(accepted.defaultFilterSetting).toBe(75); // 75 stays 75
  });

  it("fills a blade grinder as timed in seconds (v4 §4.2)", async () => {
    await catalog.load(["grinders"]);
    const c = catalog.grinder("blade-grinder");
    if (!c) return;
    const row = newGrinder(fieldsFromCatalog(c));
    expect(row).toMatchObject({ settingUnit: "s", settingStyle: "stepless", burrType: "blade", catalogId: "blade-grinder" });
    expect(isTimed(row)).toBe(true);
    expect(scaleSummary(row)).toBe("timed in seconds · longer is finer");
  });

  it("formats burr sizes without a trailing .0", () => {
    expect(burrSizeText(48)).toBe("48");
    expect(burrSizeText(47.5)).toBe("47.5");
    expect(burrSizeText(null)).toBe("");
  });

  it("summarises a numbered dial", () => {
    expect(scaleSummary(newGrinder({ brand: "Niche", model: "Zero", settingStyle: "numbers", minSetting: 0, maxSetting: 50, step: 0.5 }))).toBe("numbered dial · lower is finer · 0–50 in steps of 0.5");
  });

  it("finds an existing grinder by catalog id, then by brand + model", () => {
    const a = { ...newGrinder({ brand: "1Zpresso", model: "K-Ultra", catalogId: "1zpresso-k-ultra" }), id: 1 };
    const b = { ...newGrinder({ brand: "Mahlkönig", model: "EK43" }), id: 2 };
    expect(findExistingGrinder([a, b], { catalogId: "1zpresso-k-ultra", brand: "x", model: "y" })?.id).toBe(1);
    expect(findExistingGrinder([a, b], { catalogId: null, brand: "Mahlkonig", model: "ek-43" })?.id).toBe(2);
    expect(findExistingGrinder([{ ...b, isArchived: true }], { catalogId: null, brand: "Mahlkönig", model: "EK43" })).toBeNull();
  });
});

describe("beans", () => {
  it("splits and joins altitude", () => {
    expect(splitAltitude("1700-1900")).toEqual(["1700", "1900"]);
    expect(splitAltitude("1850")).toEqual(["1850", ""]);
    expect(joinAltitude("1900", "1700")).toBe("1700-1900");
    expect(joinAltitude("1800", "1800")).toBe("1800");
    expect(joinAltitude("", "2000")).toBe("2000");
  });

  it("shows altitude in feet and reads feet back as metres", () => {
    expect(metresToFeetText("1700-1900")).toBe("5580-6230");
    expect(metresToFeetText("1850")).toBe("6070");
    expect(metresToFeetText("")).toBe("");
    expect(feetToMetres("6000")).toBe("1829");
    expect(feetToMetres("")).toBe("");
  });

  it("says how long ago it was roasted", () => {
    expect(roastedAgoText(0)).toBe("Roasted today");
    expect(roastedAgoText(1)).toBe("Roasted about 1 day ago");
    expect(roastedAgoText(9)).toBe("Roasted about 9 days ago");
  });

  it("says resting only while the roaster's rest is ahead", () => {
    expect(restStatus({ roastedOnEpochDay: 100, restDays: 7 }, 104)).toBe("Resting · ready in 3 days");
    expect(restStatus({ roastedOnEpochDay: 100, restDays: 7 }, 106)).toBe("Resting · ready in 1 day");
    expect(restStatus({ roastedOnEpochDay: 100, restDays: 7 }, 107)).toBeNull();
    expect(restStatus({ roastedOnEpochDay: 100, restDays: null }, 101)).toBeNull();
    expect(restStatus({ roastedOnEpochDay: null, restDays: 7 }, 101)).toBeNull();
  });

  it("guesses the currency from the browser's region", () => {
    expect(localCurrency("en-GB")).toBe("GBP");
    expect(localCurrency("de-DE")).toBe("EUR");
    expect(localCurrency("en")).toBe("");
  });
});
