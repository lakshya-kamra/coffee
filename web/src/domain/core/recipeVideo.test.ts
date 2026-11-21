// The recipe video (shared/recipes.json `video`): URL building, the lenient decode, and the data
// itself (every video a real-looking YouTube id, only on built-ins, never in a saved recipe).
import { beforeAll, describe, expect, it } from "vitest";
import raw from "@shared/recipes.json";
import { Brewers, catalog } from "@/catalog/catalog";
import { decodeRecipe, encodeRecipe } from "./recipe";
import { COVER_PIXELS, decodeRecipeVideo, isYoutubeId, watchLabel, youtubeCoverUrl, youtubeWatchUrl, YOUTUBE_ID } from "./recipeVideo";

beforeAll(async () => {
  await catalog.load(["brewers", "recipes"]);
});

describe("recipe video URLs", () => {
  it("builds the cover and watch URLs from the id", () => {
    expect(youtubeCoverUrl("AI4ynXzkSQo", "hq")).toBe("https://i.ytimg.com/vi/AI4ynXzkSQo/hqdefault.jpg");
    expect(youtubeCoverUrl("MPDfn--vxK8", "mq")).toBe("https://i.ytimg.com/vi/MPDfn--vxK8/mqdefault.jpg");
    expect(youtubeWatchUrl("ggCK_oOS_08")).toBe("https://www.youtube.com/watch?v=ggCK_oOS_08");
  });

  it("refuses to build a URL from anything but an id", () => {
    for (const bad of ["", "short", "AI4ynXzkSQo1", "AI4ynXzkSQ/", "../../x.jpg", "AI4ynXzk Qo", "javascript:"]) {
      expect(isYoutubeId(bad), bad).toBe(false);
      expect(() => youtubeCoverUrl(bad, "mq"), bad).toThrow();
      expect(() => youtubeWatchUrl(bad), bad).toThrow();
    }
  });

  it("knows each cover's pixel size (16:9 mq, 4:3 hq)", () => {
    expect(COVER_PIXELS.mq).toEqual({ width: 320, height: 180 });
    expect(COVER_PIXELS.hq).toEqual({ width: 480, height: 360 });
  });

  it("says whose video it is", () => {
    expect(watchLabel({ youtubeId: "AI4ynXzkSQo", title: "t", channel: "James Hoffmann" })).toBe("Watch James Hoffmann's video");
    expect(watchLabel({ youtubeId: "AI4ynXzkSQo", title: "t", channel: "World Coffee Championships" })).toBe("Watch World Coffee Championships' video");
    expect(watchLabel({ youtubeId: "AI4ynXzkSQo", title: "t", channel: " " })).toBe("Watch the video");
  });
});

describe("decodeRecipeVideo", () => {
  it("reads a valid video and trims it", () => {
    expect(decodeRecipeVideo({ youtubeId: " AI4ynXzkSQo ", title: " The Ultimate V60 Technique ", channel: "James Hoffmann" })).toEqual({
      youtubeId: "AI4ynXzkSQo",
      title: "The Ultimate V60 Technique",
      channel: "James Hoffmann",
    });
  });

  it("drops anything without a valid id, and fills a missing title or channel with nothing", () => {
    expect(decodeRecipeVideo(undefined)).toBeNull();
    expect(decodeRecipeVideo(null)).toBeNull();
    expect(decodeRecipeVideo("AI4ynXzkSQo")).toBeNull();
    expect(decodeRecipeVideo([])).toBeNull();
    expect(decodeRecipeVideo({ youtubeId: "nope" })).toBeNull();
    expect(decodeRecipeVideo({ youtubeId: 42 })).toBeNull();
    expect(decodeRecipeVideo({ youtubeId: "AI4ynXzkSQo" })).toEqual({ youtubeId: "AI4ynXzkSQo", title: "", channel: "" });
  });
});

describe("recipe videos in shared/recipes.json", () => {
  type RawRecipe = { id: string; video?: unknown };
  const recipes = (raw as unknown as { recipes: RawRecipe[] }).recipes;
  const withVideo = recipes.filter((r) => r.video !== undefined);

  it("has videos, each exactly {youtubeId, title, channel} with an 11-character id", () => {
    expect(withVideo.length).toBeGreaterThanOrEqual(20);
    for (const r of withVideo) {
      const v = r.video as Record<string, unknown>;
      expect(Object.keys(v).sort(), r.id).toEqual(["channel", "title", "youtubeId"]);
      expect(typeof v.youtubeId === "string" && v.youtubeId.length === 11 && YOUTUBE_ID.test(v.youtubeId), r.id).toBe(true);
      expect(typeof v.title === "string" && v.title.trim().length > 0, r.id).toBe(true);
      expect(typeof v.channel === "string" && v.channel.trim().length > 0, r.id).toBe(true);
    }
  });

  it("reaches the decoded built-ins, and a recipe without one has none", () => {
    for (const r of recipes) {
      const decoded = catalog.recipe(r.id);
      expect(decoded, r.id).toBeDefined();
      if (r.video === undefined) expect(decoded?.video, r.id).toBeUndefined();
      else expect(decoded?.video?.youtubeId, r.id).toBe((r.video as { youtubeId: string }).youtubeId);
    }
  });

  it("is never written into a saved recipe or a backup", () => {
    const r = catalog.recipe("hoffmann-v60")!;
    expect(r.video?.youtubeId).toBe("AI4ynXzkSQo");
    const json = encodeRecipe(r);
    expect("video" in json).toBe(false);
    const back = decodeRecipe(JSON.parse(JSON.stringify(json)), Brewers.resolve, false);
    expect(back.ok && back.value.video).toBeUndefined();
  });
});
