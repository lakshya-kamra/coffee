// Flat config (web-spec §4.3): recommended JS + type-checked TS + React hooks (v7 includes the
// React Compiler rules) + jsx-a11y, plus the project's CSP, storage and key-safety bans.
import js from "@eslint/js";
import jsxA11y from "eslint-plugin-jsx-a11y";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";

/** Fact 6: these inject <style> or style attributes, which the strict CSP blocks. */
const cspBans = [
  { selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']", message: "No dangerouslySetInnerHTML: render text, or build React elements (web-spec §16.4)." },
  {
    selector: "JSXOpeningElement[name.name='AnimatePresence'] > JSXAttribute[name.name='mode'][value.value='popLayout']",
    message: "AnimatePresence mode=\"popLayout\" injects a <style> element, which the CSP blocks (web-spec fact 6).",
  },
  { selector: "AssignmentExpression > MemberExpression.left[property.name='innerHTML']", message: "No innerHTML (web-spec §16.4)." },
  { selector: "AssignmentExpression > MemberExpression.left[property.name='outerHTML']", message: "No outerHTML (web-spec §16.4)." },
];

/** Only these files may touch Web Storage (keys and the boot-time theme mirror). */
const storageOwners = ["src/features/ai/keyStore.ts", "src/app/prefsMirror.ts", "public/boot.js"];
const storageMessage = "Web Storage is reserved for features/ai/keyStore.ts and app/prefsMirror.ts (web-spec §4.3); use IndexedDB through src/data.";

export default tseslint.config(
  { ignores: ["dist/**", "dev-dist/**", "node_modules/**", "public/**", "scripts/**", "*.config.js"] },
  {
    files: ["src/**/*.{ts,tsx}", "vite.config.ts"],
    extends: [js.configs.recommended, ...tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    plugins: { "react-hooks": reactHooks, "jsx-a11y": jsxA11y },
    rules: {
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.configs.recommended.rules,
      "no-restricted-syntax": ["error", ...cspBans],
      "no-restricted-globals": ["error", { name: "localStorage", message: storageMessage }, { name: "sessionStorage", message: storageMessage }],
      "no-restricted-properties": [
        "error",
        { object: "window", property: "localStorage", message: storageMessage },
        { object: "window", property: "sessionStorage", message: storageMessage },
      ],
      "no-console": ["warn", { allow: ["warn", "error"] }],
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
      // Promises handed to event handlers and effects are deliberate fire-and-forget (`void p`).
      "@typescript-eslint/no-misused-promises": ["error", { checksVoidReturn: { attributes: false } }],
    },
  },
  {
    files: storageOwners.filter((f) => f.startsWith("src/")),
    rules: { "no-restricted-globals": "off", "no-restricted-properties": "off" },
  },
  {
    // Keys must never reach a log.
    files: ["src/features/ai/**", "src/domain/ai/**"],
    rules: { "no-console": "error" },
  },
  {
    files: ["src/**/*.test.{ts,tsx}", "src/test/**"],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: {
      "@typescript-eslint/no-non-null-assertion": "off",
      "@typescript-eslint/unbound-method": "off",
      "no-restricted-globals": "off",
      "no-restricted-properties": "off",
    },
  },
);
