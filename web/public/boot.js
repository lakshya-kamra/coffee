// Runs before the first paint (render-blocking on purpose): resolves the theme and motion
// preference so the page never flashes the wrong mode. External file because the CSP has no
// 'unsafe-inline'. src/app/prefsMirror.ts is the only writer of these two keys.
(function () {
  var theme = "system";
  var motion = "system";
  try {
    theme = localStorage.getItem("coffee.theme") || "system";
    motion = localStorage.getItem("coffee.motion") || "system";
  } catch (e) {
    // Storage blocked (private mode, policy): fall back to the system preference.
  }
  var dark = theme === "dark" || (theme !== "light" && !(window.matchMedia && matchMedia("(prefers-color-scheme: light)").matches));
  var el = document.documentElement;
  el.setAttribute("data-theme", dark ? "dark" : "light");
  el.setAttribute("data-motion", motion === "reduced" ? "reduced" : "system");
})();
