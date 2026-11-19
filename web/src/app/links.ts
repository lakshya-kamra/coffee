/**
 * Outbound links (lead decision 10). The Vercel URL is unknown until the first deploy, so it
 * lives in one constant for the lead to fill in; everything else derives from the repo.
 */
export const GITHUB_REPO = "lakshya-kamra/coffee";
export const SOURCE_URL = `https://github.com/${GITHUB_REPO}`;
/** "Get the Android app": the signed APK on the latest GitHub release. */
export const ANDROID_DOWNLOAD_URL = `https://github.com/${GITHUB_REPO}/releases/latest`;
/** Set after the first Vercel deploy (web-spec §20.5 step 3). Empty = not deployed yet. */
export const WEB_APP_URL = "https://coffee-psi-brown.vercel.app";
