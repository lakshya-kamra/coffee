package com.laksh.coffee.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.laksh.coffee.data.ai.AiSettingsStore
import com.laksh.coffee.data.db.CoffeeDatabase
import com.laksh.coffee.data.photos.PhotoFiles
import com.laksh.coffee.data.prefs.SettingsStore
import com.laksh.coffee.domain.backup.Backup
import com.laksh.coffee.domain.backup.BackupApp
import com.laksh.coffee.domain.backup.BackupMapping
import com.laksh.coffee.domain.backup.BackupSnapshot
import com.laksh.coffee.domain.backup.BackupTime
import com.laksh.coffee.domain.backup.BackupWriter
import com.laksh.coffee.domain.backup.Counts
import com.laksh.coffee.domain.backup.JpegBytes
import com.laksh.coffee.domain.backup.PhotoBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Base64

/**
 * Writes "everything you logged" as one backup-v2 JSON file (shared/schema/backup-v2.md).
 *
 * What goes in: beans, grinders, brews, the user's recipes, saved analyses, settings, which AI
 * provider and model, and (when asked) the photos. What never goes in: an API key. The key
 * lives in its own store (coffee_ai) and this class reads only [AiSettingsStore.backup], which
 * is built without touching it.
 *
 * Nothing here uses the network. The file goes wherever the system file picker was pointed.
 */
class BackupExporter(
    private val context: Context,
    private val db: CoffeeDatabase,
    private val photoFiles: PhotoFiles,
    private val settings: SettingsStore,
    private val ai: AiSettingsStore,
    private val app: BackupApp,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Sizes in bytes, for "about 14 MB with photos" before saving. */
    data class Estimate(val withoutPhotos: Long, val withPhotos: Long, val photoCount: Int)

    /** [photosLeftOut]: photos listed without their image because the file would have been too big to restore. */
    data class Written(val counts: Counts, val photosWithData: Int, val photosLeftOut: Int = 0)

    /** Every table as it is at one moment: a brew is never written without the bean it points at. */
    suspend fun snapshot(): BackupSnapshot {
        val prefs = settings.settings.first()
        val aiPrefs = runCatching { ai.backup() }.getOrNull()
        return db.withTransaction {
            BackupSnapshot(
                beans = db.beanDao().all(),
                grinders = db.grinderDao().all(),
                brews = db.brewLogDao().all(),
                savedRecipes = db.savedRecipeDao().all(),
                analyses = db.analysisDao().all(),
                photos = db.photoDao().all(),
                settings = prefs,
                ai = aiPrefs,
            )
        }
    }

    /**
     * The per-record sizes are the app's own rule of thumb from pretty-printed sample records,
     * not a measurement; photos are their stored bytes times 4/3 for base64.
     */
    suspend fun estimate(): Estimate = withContext(Dispatchers.IO) {
        val s = snapshot()
        val analysisBytes = s.analyses.sumOf { 900L + (it.rawText.length * 1.1).toLong() + (it.resultJson?.length ?: 0) }
        val base = 1500L + s.brews.size * 1400L + s.beans.size * 1000L + s.grinders.size * 600L +
            s.savedRecipes.sumOf { 400L + it.json.length * 2L } + analysisBytes + s.photos.size * 350L
        val photoBytes = s.photos.sumOf { p -> ((photoFiles.length(p.fileName) ?: 0L) + 2) / 3 * 4 }
        Estimate(withoutPhotos = base, withPhotos = base + photoBytes, photoCount = s.photos.size)
    }

    /**
     * Streams the backup into [out] and closes it. Only one photo is held in memory at a time.
     * A photo whose file is not on this phone is still listed, with no data.
     *
     * The file is never bigger than [maxBytes]: neither app reads a larger one back, so it
     * would be a backup that cannot be restored. When the photos do not all fit, the newest
     * that do keep their image and the rest are listed without one, as the web app does.
     */
    suspend fun write(
        out: OutputStream,
        includePhotos: Boolean,
        includeAnalyses: Boolean = true,
        maxBytes: Long = Backup.MAX_BACKUP_BYTES,
    ): Written =
        withContext(Dispatchers.IO) {
            val snapshot = snapshot()
            // The schema version of the database as it is open now (4 in 2.0.0).
            val dbVersion = runCatching { db.openHelper.readableDatabase.version }.getOrDefault(app.dbVersion)
            val doc = BackupMapping.document(
                snapshot, now(), app.copy(dbVersion = dbVersion),
                includesPhotos = includePhotos, includesAnalyses = includeAnalyses,
            )
            val fileNames = snapshot.photos.associate { it.uid to it.fileName }
            var keep: Set<String> = emptySet()
            var leftOut = 0
            if (includePhotos && doc.photos.isNotEmpty()) {
                val sized = doc.photos.mapNotNull { photo ->
                    val length = fileNames[photo.uid]?.let(photoFiles::length) ?: return@mapNotNull null
                    PhotoBudget.Sized(photo.uid, BackupTime.parse(photo.createdAt) ?: 0L, PhotoBudget.base64Size(length))
                }
                // Every record is written either way; only the images compete for the room left.
                val fixed = Utf8Counter().also { BackupWriter.write(it, doc, doc.photos.asSequence()) }.bytes
                keep = PhotoBudget.thatFit(sized, maxBytes - fixed)
                leftOut = sized.size - keep.size
            }
            var withData = 0
            val photos = doc.photos.asSequence().map { photo ->
                val bytes = if (photo.uid in keep) fileNames[photo.uid]?.let(::readJpeg) else null
                if (bytes == null) photo else {
                    withData++
                    photo.copy(data = Base64.getEncoder().encodeToString(bytes))
                }
            }
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024).use { writer ->
                BackupWriter.write(writer, doc, photos)
                writer.flush()
            }
            Written(Counts.of(doc), withData, leftOut)
        }

    /** As [write], into a document the person just created with the system file picker. */
    suspend fun writeTo(uri: Uri, includePhotos: Boolean, includeAnalyses: Boolean = true): Written =
        withContext(Dispatchers.IO) {
            try {
                // "wt": truncate, in case the provider handed back an existing file.
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No stream for the picked file")
                write(out, includePhotos, includeAnalyses)
            } catch (e: Exception) {
                // Half a backup looks like a backup. Remove it; if the provider won't, it stays.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                throw e
            }
        }

    /** How many bytes the text appended to it takes as UTF-8, without keeping any of it. */
    private class Utf8Counter : Appendable {
        var bytes = 0L
            private set

        override fun append(csq: CharSequence?): Appendable = append(csq, 0, csq?.length ?: 0)

        override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
            if (csq != null) for (i in start until end) append(csq[i])
            return this
        }

        override fun append(c: Char): Appendable {
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                // Half of a pair that takes four bytes together.
                c.isSurrogate() -> 2
                else -> 3
            }
            return this
        }
    }

    /** The stored JPEG, or null when it is not on this phone or is not one. */
    private fun readJpeg(fileName: String): ByteArray? {
        val file = photoFiles.stored(fileName)?.takeIf { it.isFile } ?: return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return bytes.takeIf(JpegBytes::isJpeg)
    }
}
