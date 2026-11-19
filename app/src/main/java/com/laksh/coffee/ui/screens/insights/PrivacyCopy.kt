package com.laksh.coffee.ui.screens.insights

/** Where an analysis would go: the provider's name and the host of its address. */
data class AiTarget(val provider: String, val host: String)

/**
 * "What leaves the phone". Must stay literally true; each sentence is pinned to code:
 *
 * - The network is used only by buttons that run an analysis or a connection test, and by
 *   Browse models (data/ai/HttpTransport is the app's only HTTP code).
 * - What an analysis sends is what domain/ai/AnalysisPrompt builds, plus the photos
 *   data/photos/PhotoAttachments picks: bed or cup photos of the one brew being analysed, at
 *   most 3, only for a model that sees images, only while sending photos is on and the photo is
 *   still ticked.
 * - The key lives in its own store (coffee_ai) and no backup code reads it: BackupExporter takes
 *   the provider and model from AiSettingsStore.backup(), and the format has no field for a key.
 * - Photos are re-encoded when added (data/photos/PhotoStore) and stripped of metadata when they
 *   arrive in a backup (domain/backup/JpegBytes), so no stored photo carries EXIF.
 * - Android's own backup is limited to the database by res/xml/backup_rules.xml and
 *   data_extraction_rules.xml: brews and saved analyses, never the key store or the photo files.
 */
internal object PrivacyCopy {

    private const val BUTTONS = "a button that runs an analysis or a connection test (Analyse, Save & analyse, " +
        "Find patterns, Re-run, Try again, Save and test, Test connection) or Browse models"

    private const val PHOTOS = "Photos are re-saved without location or camera details and leave the phone only " +
        "in an analysis or in a backup you save with photos included."

    private const val BACKUP = "A backup you save holds your brews, coffees, grinders, recipes, saved analyses " +
        "and settings, and goes only where you save it."

    private const val ANDROID_BACKUP = "Android's own backup, if it is on, can include the brew log and saved " +
        "analyses, but never the key or photos."

    fun text(target: AiTarget?): String = if (target != null) {
        val who = if (target.provider.isBlank() || target.provider == target.host) target.host else "${target.provider} at ${target.host}"
        "The app does nothing online in the background. It goes online only when you tap $BUTTONS, " +
            "and only to $who, the service you chose in Gear › App › AI analysis. An analysis sends " +
            "the brews it reads: recipe, coffee details, grinder and its scale, brewer, water, times, " +
            "bed, taste and your notes, and, if the model can see images and you left them ticked, up " +
            "to 3 bed or cup photos of the brew being analysed. Your key is encrypted with this " +
            "phone's key store, travels only in the request header to that address, and is never in " +
            "a backup. $PHOTOS $BACKUP Sharing, copying and saving a backup work without the network. " +
            ANDROID_BACKUP
    } else {
        "The app sends nothing about your brews off this phone. If you set up analysis in Gear › " +
            "App, it goes online only when you tap $BUTTONS, and only to the service you choose " +
            "there. Sharing, copying and saving a backup never use the network. A key, once you add " +
            "one, is never in a backup. $PHOTOS $BACKUP $ANDROID_BACKUP"
    }
}
