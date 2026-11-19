/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react, { reactCompilerPreset } from "@vitejs/plugin-react";
import babel from "@rolldown/plugin-babel";
import { VitePWA } from "vite-plugin-pwa";
import path from "node:path";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const pkg = JSON.parse(readFileSync(path.join(here, "package.json"), "utf8")) as { version: string };

/** Production headers from vercel.json, applied to `vite preview` so CSP problems show up locally. */
function prodHeaders(): Record<string, string> {
  try {
    const v = JSON.parse(readFileSync(path.join(root, "vercel.json"), "utf8")) as {
      headers: { source: string; headers: { key: string; value: string }[] }[];
    };
    const all = v.headers.find((h) => h.source === "/(.*)");
    return Object.fromEntries((all?.headers ?? []).map((h) => [h.key, h.value]));
  } catch {
    // vercel-sim copies only the allow-listed files; vercel.json is one of them, but never fail a build over preview headers.
    return {};
  }
}

export default defineConfig({
  plugins: [
    react(),
    // React Compiler through plugin-react v6's Babel route (lead decision 10: keep while the build stays green).
    babel({ presets: [reactCompilerPreset()] }),
    VitePWA({
      strategies: "generateSW",
      registerType: "prompt",
      injectRegister: false,
      manifest: {
        name: "Coffee",
        short_name: "Coffee",
        description: "A brew timer, brew log and grind notes for pour-over and espresso.",
        id: "/",
        start_url: "/",
        scope: "/",
        display: "standalone",
        background_color: "#0D0B09",
        theme_color: "#0D0B09",
        categories: ["food", "lifestyle"],
        icons: [
          { src: "/icons/pwa-192x192.png", sizes: "192x192", type: "image/png" },
          { src: "/icons/pwa-512x512.png", sizes: "512x512", type: "image/png" },
          { src: "/icons/maskable-icon-512x512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
      workbox: {
        globPatterns: ["**/*.{js,css,html,svg,png,json,webmanifest}"],
        navigateFallback: "/index.html",
        navigateFallbackDenylist: [/^\/assets\//],
        cleanupOutdatedCaches: true,
        runtimeCaching: [],
        // Catalog chunks (grinders, roasters) are large but must work offline.
        maximumFileSizeToCacheInBytes: 4 * 1024 * 1024,
      },
    }),
  ],
  resolve: {
    alias: {
      "@": path.resolve(here, "src"),
      "@shared": path.join(root, "shared"),
    },
  },
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  server: { fs: { allow: [root] } },
  preview: { headers: prodHeaders() },
  build: { target: "es2022", sourcemap: true, assetsInlineLimit: 0, chunkSizeWarningLimit: 400 },
  worker: { format: "es" },
  test: {
    environment: "node",
    setupFiles: ["src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
