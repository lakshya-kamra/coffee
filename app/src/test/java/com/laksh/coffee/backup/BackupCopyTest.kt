package com.laksh.coffee.backup

import com.laksh.coffee.data.backup.ImportError
import com.laksh.coffee.data.backup.ImportPreview
import com.laksh.coffee.data.backup.ImportResult
import com.laksh.coffee.domain.backup.Backup
import com.laksh.coffee.domain.backup.BackupKind
import com.laksh.coffee.domain.backup.Counts
import com.laksh.coffee.ui.backup.BackupCopy
import com.laksh.coffee.ui.screens.insights.AiTarget
import com.laksh.coffee.ui.screens.insights.PrivacyCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The sentences the restore sheet and the privacy card show, pinned word for word. */
class BackupCopyTest {

    private val preview = ImportPreview(
        kind = BackupKind.V2,
        counts = Counts(brews = 52, beans = 4, grinders = 1, recipes = 3, analyses = 7, photos = 12),
        platform = "android",
        packageName = "com.laksh.coffee.debug",
        exportedAt = "2026-11-17T09:41:00Z",
        version = 2,
        photosWithData = 12,
        problems = 0,
        hasSettings = true,
        hasAi = true,
    )

    @Test
    fun thePreviewLineIsTheSpecsSentence() {
        assertEquals(
            "Found 52 brews, 4 coffees, 1 grinder, 3 recipes, 7 analyses, 12 photos · made on Android (com.laksh.coffee.debug) on 17 Nov 2026",
            BackupCopy.previewLine(preview, ZoneOffset.UTC),
        )
        assertEquals(
            "Found 1,204 brews · made on the web on 17 Nov 2026",
            BackupCopy.previewLine(preview.copy(counts = Counts(brews = 1204), platform = "web", packageName = ""), ZoneOffset.UTC),
        )
        assertEquals(
            "Found 23 brews, 1 coffee, 2 grinders · a 1.x export from Android on 5 Mar 2026",
            BackupCopy.previewLine(
                // Made-up numbers and date, like every value in these tests.
                preview.copy(kind = BackupKind.Legacy1, counts = Counts(brews = 23, beans = 1, grinders = 2), packageName = "", exportedAt = "2026-03-05T08:15:00.250Z"),
                ZoneOffset.UTC,
            ),
        )
        assertEquals("This backup is empty · made on Android", BackupCopy.previewLine(preview.copy(counts = Counts(), packageName = "", exportedAt = ""), ZoneOffset.UTC))
    }

    @Test
    fun notesSayWhatWillBeLeftOut() {
        assertTrue(BackupCopy.previewNotes(preview).isEmpty())
        assertEquals(
            listOf("This backup was saved without photos, so none will come across."),
            BackupCopy.previewNotes(preview.copy(photosWithData = 0)),
        )
        assertEquals(
            listOf("2 photos aren't in the file and will be left out.", "1 record in the file couldn't be read and will be left out."),
            BackupCopy.previewNotes(preview.copy(photosWithData = 10, problems = 1)),
        )
        assertEquals(
            listOf("Older exports have no saved analyses, recipe edits or photos. Everything else comes across."),
            BackupCopy.previewNotes(preview.copy(kind = BackupKind.Legacy1, counts = Counts(brews = 23))),
        )
    }

    @Test
    fun theResultLineCountsWhatHappened() {
        assertEquals(
            "Added 52 brews · 0 already here",
            BackupCopy.resultLine(ImportResult(Counts(brews = 52), Counts(), Counts(), emptyList())),
        )
        assertEquals(
            "Nothing new · 58 already here",
            BackupCopy.resultLine(ImportResult(Counts(), Counts(), Counts(brews = 52, beans = 4, grinders = 2), emptyList())),
        )
        assertEquals(
            "Added 1 brew, 1 photo · 3 updated · 10 already here",
            BackupCopy.resultLine(ImportResult(Counts(brews = 1, photos = 1), Counts(brews = 2, beans = 1), Counts(brews = 10), emptyList())),
        )
    }

    @Test
    fun everyErrorHasPlainWordsThatSayNothingChanged() {
        ImportError.entries.forEach { e ->
            val text = BackupCopy.errorText(e)
            assertTrue(e.name, text.isNotBlank() && !text.contains("!") && text.endsWith("."))
        }
        assertEquals("This backup was made by a newer version of Coffee. Update Coffee and try again.", BackupCopy.errorText(ImportError.TooNew))
        assertEquals("Too big to import here. Save the backup without photos and try again.", BackupCopy.errorText(ImportError.TooBig))
        assertEquals("This file isn't a Coffee backup.", BackupCopy.errorText(ImportError.NotBackup))
    }

    @Test
    fun sizesReadLikeAPhoneShowsThem() {
        assertEquals("about 14 MB", BackupCopy.aboutSize(14_200_000))
        assertEquals("about 1.4 MB", BackupCopy.aboutSize(1_420_000))
        assertEquals("about 2 MB", BackupCopy.aboutSize(2_040_000))
        assertEquals("about 120 KB", BackupCopy.aboutSize(120_300))
        assertEquals("about 1 KB", BackupCopy.aboutSize(12))
        assertEquals("About 14 MB with photos", BackupCopy.sizeLine(80_000, 14_200_000, 12, includePhotos = true))
        assertEquals("About 80 KB", BackupCopy.sizeLine(80_000, 14_200_000, 12, includePhotos = false))
        assertEquals("About 80 KB", BackupCopy.sizeLine(80_000, 80_000, 0, includePhotos = true))
        assertEquals("coffee-backup-2026-11-18.json", BackupCopy.fileName(LocalDate.of(2026, 11, 18)))
    }

    @Test
    fun thePrivacyCopyNamesTheChosenProviderAndNeverPromisesTheKeyAway() {
        val on = PrivacyCopy.text(AiTarget("Anthropic", "api.anthropic.com"))
        assertTrue(on.contains("only to Anthropic at api.anthropic.com, the service you chose in Gear › App › AI analysis"))
        assertTrue(on.contains("is never in a backup"))
        assertTrue(on.contains("up to 3 bed or cup photos"))
        assertTrue(on.contains("never the key or photos"))
        // A custom address has no name of its own: the host is said once.
        assertTrue(PrivacyCopy.text(AiTarget("", "llm.example.com")).contains("only to llm.example.com, the service you chose"))

        val off = PrivacyCopy.text(null)
        assertTrue(off.startsWith("The app sends nothing about your brews off this phone."))
        assertTrue(off.contains("is never in a backup"))

        // Since 2.0 no provider is built in: neither text may name one on its own.
        for (text in listOf(off, PrivacyCopy.text(AiTarget("Mistral", "api.mistral.ai")))) {
            assertFalse(text.contains("Command Code", ignoreCase = true))
            assertFalse(text.contains("CommandCode", ignoreCase = true))
            assertFalse(text.contains("JSON export"))
            assertFalse(text.contains("!"))
        }
    }

    @Test
    fun aBackupThatCannotHoldEveryPhotoSaysSoBeforeAndAfter() {
        val over = Backup.MAX_BACKUP_BYTES + 1
        assertEquals("More photos than one file can hold · the newest that fit are saved", BackupCopy.sizeLine(80_000, over, 400, includePhotos = true))
        // Without photos it is an ordinary small file again.
        assertEquals("About 80 KB", BackupCopy.sizeLine(80_000, over, 400, includePhotos = false))
        assertTrue(Backup.MAX_BACKUP_BYTES < Backup.MAX_IMPORT_BYTES)

        val counts = Counts(brews = 52, photos = 400)
        assertEquals("52 brews, 400 photos", BackupCopy.savedDetail(counts, photosWithData = 400, photosLeftOut = 0))
        assertEquals(
            "52 brews, 280 photos · 120 older photos left out: a backup over 64 MB can't be restored",
            BackupCopy.savedDetail(counts, photosWithData = 280, photosLeftOut = 120),
        )
        assertEquals(
            "52 brews, 399 photos · 1 older photo left out: a backup over 64 MB can't be restored",
            BackupCopy.savedDetail(counts, photosWithData = 399, photosLeftOut = 1),
        )
        assertEquals("an empty backup", BackupCopy.savedDetail(Counts(), 0, 0))
    }

    @Test
    fun theAiRowNamesACustomAddressBeforeItIsBroughtAcross() {
        assertEquals("Keys never travel: you add your key again here", BackupCopy.aiLine(preview))
        assertEquals(
            "Sets the custom address to https://llm.example.com/v1. Keys never travel: you add your key again here",
            BackupCopy.aiLine(preview.copy(aiCustomAddress = "https://llm.example.com/v1")),
        )
        assertEquals("This backup has no AI choices", BackupCopy.aiLine(preview.copy(hasAi = false)))
    }
}
