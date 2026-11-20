import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./design/tokens.gen.css";
import "./design/tokens.css";
import "./design/base.css";
import { App } from "./app/App";
import { afterFirstPaint, boot } from "./app/boot";
import "./app/install";
import { reloadOnceForPreloadError } from "./app/prefsMirror";
import shell from "./app/shell.module.css";
import { startSidebar } from "./app/sidebar";
import { startWindowClass } from "./app/windowClass";

// Window class and sidebar state before the first render, so the first frame already has the
// right navigation (no bar flashing on a desktop).
startWindowClass();
startSidebar();

// A stale hashed chunk after a deploy 404s (vercel.json excludes /assets from the SPA rewrite):
// reload once to pick up the new index.html instead of rendering a broken screen.
window.addEventListener("vite:preloadError", (e) => {
  if (reloadOnceForPreloadError()) e.preventDefault();
});

const rootEl = document.getElementById("root");
if (!rootEl) throw new Error("#root missing from index.html");
const root = createRoot(rootEl);

void boot().then((result) => {
  if (!result.ok) {
    root.render(
      <p className={`t-body-l ${shell.fatal}`}>This browser won't let Coffee save anything here. Try a normal window, or another browser.</p>,
    );
    return;
  }
  root.render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
  requestAnimationFrame(() => afterFirstPaint());
});
