// Public surface of features/photos (web-spec §13, §20.4): the strip, the AI hook, and the
// pieces other packages may reuse (the pipeline for a bag photo, the EXIF check for tests).
export { PhotoStrip } from "./PhotoStrip";
export { photoAttachmentsForBrew, type AiImage } from "./attachments";
export { KIND_LABELS, photoAlt } from "./photoText";
export { processPhoto, PhotoError, type ProcessedPhoto } from "./pipeline";
export { hasApp1 } from "./exifDate";
