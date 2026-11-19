/**
 * Motion's feature bundle (layout projection, drag, gestures), split from the entry chunk so
 * first paint does not parse it. App.tsx starts the download while the database opens.
 */
export { domMax as default } from "motion/react";
