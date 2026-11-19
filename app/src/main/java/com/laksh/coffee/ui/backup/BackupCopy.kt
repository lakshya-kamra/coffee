package com.laksh.coffee.ui.backup

import com.laksh.coffee.data.backup.ImportError
import com.laksh.coffee.data.backup.ImportPreview
import com.laksh.coffee.data.backup.ImportResult
import com.laksh.coffee.domain.backup.Backup
import com.laksh.coffee.domain.backup.BackupKind
import com.laksh.coffee.domain.backup.BackupTime
import com.laksh.coffee.domain.backup.Counts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Words for YOUR DATA and the restore sheet. Plain, second person, no exclamation marks. Pure,
 * so tests pin the exact sentences; the web app says the same things about the same file.
 */
object BackupCopy {

    // English month names, like every other word in the app ("17 Nov 2026").
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    private fun grouped(n: Int): String = String.format(Locale.US, "%,d", n)

    private fun counted(n: Int, one: String, many: String): String? =
        if (n > 0) grouped(n) + " " + (if (n == 1) one else many) else null

    /** "52 brews, 4 coffees, 1 grinder": zero counts left out. */
    fun countList(c: Counts): String = listOfNotNull(
        counted(c.brews, "brew", "brews"),
        counted(c.beans, "coffee", "coffees"),
        counted(c.grinders, "grinder", "grinders"),
        counted(c.recipes, "recipe", "recipes"),
        counted(c.analyses, "analysis", "analyses"),
        counted(c.photos, "photo", "photos"),
    ).joinToString(", ")

    /** "17 Nov 2026" in the phone's time zone, or "" when the file gives no date. */
    fun shortDate(isoInstant: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val ms = BackupTime.parse(isoInstant) ?: return ""
        return DATE.format(Instant.ofEpochMilli(ms).atZone(zone))
    }

    private fun madeOn(p: ImportPreview): String = when {
        p.kind == BackupKind.Legacy1 -> "a 1.x export from Android"
        p.platform == "web" -> if (p.packageName.isNotEmpty()) "made on the web (${p.packageName})" else "made on the web"
        p.platform == "android" -> if (p.packageName.isNotEmpty()) "made on Android (${p.packageName})" else "made on Android"
        p.platform.isNotEmpty() -> "made on ${p.platform}"
        else -> "made by Coffee"
    }

    /** "Found 52 brews, 4 coffees, 1 grinder · made on Android (com.laksh.coffee.debug) on 17 Nov 2026" */
    fun previewLine(p: ImportPreview, zone: ZoneId = ZoneId.systemDefault()): String {
        val found = if (p.counts.total > 0) "Found " + countList(p.counts) else "This backup is empty"
        val date = shortDate(p.exportedAt, zone)
        return found + " · " + madeOn(p) + (if (date.isNotEmpty()) " on $date" else "")
    }

    /** Notes under the preview: photos saved without their images, records that could not be read. */
    fun previewNotes(p: ImportPreview): List<String> {
        val notes = ArrayList<String>()
        val missing = p.counts.photos - p.photosWithData
        if (missing > 0 && p.photosWithData == 0) {
            notes += "This backup was saved without photos, so none will come across."
        } else if (missing > 0) {
            notes += grouped(missing) + (if (missing == 1) " photo isn't" else " photos aren't") + " in the file and will be left out."
        }
        if (p.problems > 0) {
            notes += grouped(p.problems) + (if (p.problems == 1) " record" else " records") +
                " in the file couldn't be read and will be left out."
        }
        if (p.kind == BackupKind.Legacy1) {
            notes += "Older exports have no saved analyses, recipe edits or photos. Everything else comes across."
        }
        return notes
    }

    /** "Added 52 brews · 0 already here", plus "· 3 updated" when newer copies replaced older ones. */
    fun resultLine(r: ImportResult): String {
        val parts = ArrayList<String>()
        parts += if (r.added.total > 0) "Added " + countList(r.added) else "Nothing new"
        if (r.replaced.total > 0) parts += grouped(r.replaced.total) + " updated"
        parts += grouped(r.kept.total) + " already here"
        return parts.joinToString(" · ")
    }

    fun errorText(e: ImportError): String = when (e) {
        ImportError.NotJson -> "This file isn't a backup. Pick the .json file Coffee saved."
        ImportError.NotBackup -> "This file isn't a Coffee backup."
        ImportError.TooNew -> "This backup was made by a newer version of Coffee. Update Coffee and try again."
        ImportError.TooBig -> "Too big to import here. Save the backup without photos and try again."
        ImportError.Unreadable -> "This backup couldn't be read. Nothing was changed."
        ImportError.StorageFull -> "This phone is out of space, so nothing was changed. Free some space and try again."
        ImportError.Failed -> "The import didn't finish, so nothing was changed. Try again."
    }

    const val REPLACE_CONFIRM =
        "Replace everything on this phone with this backup? Every brew, coffee, grinder, recipe, analysis and " +
            "photo here now is deleted first. This can't be undone."

    const val SAVE_FAILED = "The backup couldn't be written. Try again."

    /** "about 14 MB", "about 120 KB" (1 MB = 1,000,000 bytes, as phones show file sizes). */
    fun aboutSize(bytes: Long): String {
        if (bytes >= 1_000_000) {
            val mb = bytes / 1_000_000.0
            val text = if (bytes >= 10_000_000) Math.round(mb).toString() else String.format(Locale.US, "%.1f", mb).removeSuffix(".0")
            return "about $text MB"
        }
        return "about " + maxOf(1L, Math.round(bytes / 1000.0)) + " KB"
    }

    private val LIMIT_MB: Long = Backup.MAX_IMPORT_BYTES / (1024 * 1024)

    /** Under "Back up everything" when the photos cannot all go in one restorable file. Same words as the web app. */
    const val PHOTOS_WONT_ALL_FIT = "More photos than one file can hold · the newest that fit are saved"

    /** After a save: what is in the file, and the photos that had to stay out of it. */
    fun savedDetail(counts: Counts, photosWithData: Int, photosLeftOut: Int): String {
        val what = countList(counts.copy(photos = photosWithData)).ifEmpty { "an empty backup" }
        if (photosLeftOut <= 0) return what
        return what + " · " + grouped(photosLeftOut) + " older " + (if (photosLeftOut == 1) "photo" else "photos") +
            " left out: a backup over $LIMIT_MB MB can't be restored"
    }

    /** Under "Also bring AI provider and model". The address is said, since analyses would go there. */
    fun aiLine(p: ImportPreview): String = when {
        !p.hasAi -> "This backup has no AI choices"
        p.aiCustomAddress.isNotEmpty() ->
            "Sets the custom address to ${p.aiCustomAddress}. Keys never travel: you add your key again here"
        else -> "Keys never travel: you add your key again here"
    }

    /** The line under "Back up everything": the size it will be, labelled as an estimate by "about". */
    fun sizeLine(withoutPhotos: Long, withPhotos: Long, photoCount: Int, includePhotos: Boolean): String {
        if (includePhotos && photoCount > 0 && withPhotos > Backup.MAX_BACKUP_BYTES) return PHOTOS_WONT_ALL_FIT
        val size = aboutSize(if (includePhotos && photoCount > 0) withPhotos else withoutPhotos)
        val tail = if (includePhotos && photoCount > 0) " with photos" else ""
        return size.replaceFirstChar { it.uppercase() } + tail
    }

    fun fileName(date: java.time.LocalDate): String = "coffee-backup-$date.json"
}
