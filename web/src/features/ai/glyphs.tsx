/**
 * The few marks the AI cards need that the shared icon set does not carry, drawn here as simple
 * 24-unit SVG shapes (no third-party artwork). They take the surrounding text colour.
 */
import { memo } from "react";

export type GlyphName = "spark" | "warning" | "key" | "expand" | "external" | "copy";

const PATHS: Record<GlyphName, string> = {
  // A four-point star, the "AI" mark beside ANALYSIS / PATTERNS.
  spark: "M12 2.5c.5 4.6 2.4 6.5 7 7-4.6.5-6.5 2.4-7 7-.5-4.6-2.4-6.5-7-7 4.6-.5 6.5-2.4 7-7Zm6.5 11c.25 2 1 2.75 3 3-2 .25-2.75 1-3 3-.25-2-1-2.75-3-3 2-.25 2.75-1 3-3Z",
  warning: "M12 3.2 22 20.5H2L12 3.2Zm0 3.9L5.4 18.5h13.2L12 7.1Zm-1 3.4h2v4.2h-2v-4.2Zm0 5.4h2v1.9h-2v-1.9Z",
  key: "M7.5 14.5a3 3 0 1 1 0-6 3 3 0 0 1 0 6Zm0-8a5 5 0 1 0 4.6 7H15v2.5h2.5V13.5H20v-3h-7.9a5 5 0 0 0-4.6-4Z",
  expand: "M7.4 9 12 13.6 16.6 9 18 10.4l-6 6-6-6L7.4 9Z",
  external: "M14 4h6v6h-2V7.4l-7.3 7.3-1.4-1.4L16.6 6H14V4ZM5 6h6v2H6v10h10v-5h2v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1Z",
  copy: "M8 3h10a2 2 0 0 1 2 2v12h-2V5H8V3ZM5 7h9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2Zm0 2v10h9V9H5Z",
};

export const Glyph = memo(function Glyph({ name, size = 16, className }: { name: GlyphName; size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" className={className} aria-hidden="true" focusable="false">
      <path d={PATHS[name]} fill="currentColor" fillRule="evenodd" />
    </svg>
  );
});
