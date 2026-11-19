package com.laksh.coffee.data.backup

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.withTransaction
import com.laksh.coffee.data.ai.AiSettingsStore
import com.laksh.coffee.data.db.CoffeeDatabase
import com.laksh.coffee.data.db.entity.newUid
import com.laksh.coffee.data.photos.PhotoFiles
import com.laksh.coffee.data.prefs.SettingsStore
import com.laksh.coffee.domain.backup.Backup
import com.laksh.coffee.domain.backup.BackupCodec
import com.laksh.coffee.domain.backup.BackupDoc
import com.laksh.coffee.domain.backup.BackupKind
import com.laksh.coffee.domain.backup.BackupMapping
import com.laksh.coffee.domain.backup.BackupSplitter
import com.laksh.coffee.domain.backup.Counts
import com.laksh.coffee.domain.backup.JpegBytes
import com.laksh.coffee.domain.backup.LegacyExportReader
import com.laksh.coffee.domain.backup.MergeMode
import com.laksh.coffee.domain.backup.PhotoDto
import com.laksh.coffee.domain.catalog.Catalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64

/** What the picked file holds, shown before anything is changed. */
data class ImportPreview(
    val kind: BackupKind,
    val counts: Counts,
    val platform: String,
    val packageName: String,
    /** ISO instant, or "" when the file does not say. */
    val exportedAt: String,
    val version: Int,
    /** Photos whose image is in the file (a backup saved without photos lists them with none). */
    val photosWithData: Int,
    /** Records that could not be read and will be left out. */
    val problems: Int,
    val hasSettings: Boolean,
    val hasAi: Boolean,
    /**
     * The address the file would set for the custom AI provider, as it would be stored, or ""
     * when it carries none this phone would accept. Shown before it is brought across.
     */
    val aiCustomAddress: String = "",
)

data class ImportOptions(
    val mode: MergeMode = MergeMode.Add,
    val bringSettings: Boolean = false,
    val bringAi: Boolean = false,
)

data class ImportResult(
    val added: Counts,
    val replaced: Counts,
    val kept: Counts,
    /** Plain-word notes about what was left out. */
    val skipped: List<String>,
)

enum class ImportError { NotJson, NotBackup, TooNew, TooBig, Unreadable, StorageFull, Failed }

sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class Ready(val preview: ImportPreview) : ImportState
    data class Importing(val preview: ImportPreview) : ImportState
    data class Done(val preview: ImportPreview, val result: ImportResult) : ImportState
    data class Failed(val error: ImportError) : ImportState
}

/**
 * Restores a backup-v2 file, or a 1.x JSON export, into this phone.
 *
 * The picked file is first copied into the app's cache (the picker's permission ends with the
 * screen) and read into a preview; nothing is changed until [confirm]. Then, in order:
 * photos are written to the photo folder as temporary files; one database transaction applies
 * the merge rules and, as its last step, gives those files their final names; if anything in
 * it fails the transaction rolls back, the files are deleted, and the phone is as it was.
 * Settings and the AI provider choice are applied afterwards and only when ticked. A key is
 * never read from a backup: the format has no field for one.
 *
 * "Already here" is not decided by uid alone where the same thing can be here under another
 * one, or only half here: see [BackupMerge].
 *
 * Work runs in [scope] (the app's), so it finishes even if the screen is left. Nothing here
 * uses the network.
 */
class BackupImporter(
    private val context: Context,
    private val db: CoffeeDatabase,
    private val photoFiles: PhotoFiles,
    private val settings: SettingsStore,
    private val ai: AiSettingsStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val mutableState = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = mutableState.asStateFlow()

    /** The file being previewed, decoded without its photo data. */
    private class Pending(val file: File, val doc: BackupDoc, val preview: ImportPreview)

    @Volatile private var pending: Pending? = null
    private val busy = Mutex()

    private val cacheFile: File get() = File(context.cacheDir, "import.json")

    // ------------------------------------------------------------------ screen-facing

    /** Copies the picked file in and reads it. Ends in [ImportState.Ready] or [ImportState.Failed]. */
    fun open(uri: Uri) {
        if (!busy.tryLock()) return
        mutableState.value = ImportState.Reading
        scope.launch(Dispatchers.IO) {
            try {
                pending = null
                val target = cacheFile
                val copied = copyIn(uri, target)
                mutableState.value = when {
                    copied == null -> ImportState.Failed(ImportError.Unreadable)
                    !copied -> ImportState.Failed(ImportError.TooBig)
                    else -> when (val r = read(target)) {
                        is ReadResult.Ok -> {
                            pending = Pending(target, r.doc, r.preview)
                            ImportState.Ready(r.preview)
                        }
                        is ReadResult.Error -> ImportState.Failed(r.error)
                    }
                }
                if (mutableState.value is ImportState.Failed) target.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Out of memory included: a file that cannot be read is reported, not a crash.
                cacheFile.delete()
                mutableState.value = ImportState.Failed(if (e is OutOfMemoryError) ImportError.TooBig else ImportError.Unreadable)
            } finally {
                busy.unlock()
            }
        }
    }

    /** Runs the import the preview described. Ends in [ImportState.Done] or [ImportState.Failed]. */
    fun confirm(options: ImportOptions) {
        val p = pending ?: return
        if (!busy.tryLock()) return
        mutableState.value = ImportState.Importing(p.preview)
        scope.launch(Dispatchers.IO) {
            try {
                // Not cancellable: half an import is the one thing this must never leave behind.
                val result = withContext(NonCancellable) { import(p.file, p.doc, p.preview.kind, options) }
                mutableState.value = ImportState.Done(p.preview, result)
            } catch (e: Throwable) {
                mutableState.value = ImportState.Failed(if (isStorageFull(e)) ImportError.StorageFull else ImportError.Failed)
            } finally {
                pending = null
                p.file.delete()
                busy.unlock()
            }
        }
    }

    /** Closes the preview or the result. Does nothing while a file is being read or imported. */
    fun dismiss() {
        if (!busy.tryLock()) return
        try {
            pending = null
            cacheFile.delete()
            mutableState.value = ImportState.Idle
        } finally {
            busy.unlock()
        }
    }

    // ------------------------------------------------------------------ reading

    sealed interface ReadResult {
        class Ok(val doc: BackupDoc, val preview: ImportPreview) : ReadResult
        class Error(val error: ImportError) : ReadResult
    }

    /** True when copied, false when the file is over the limit, null when it could not be opened. */
    private fun copyIn(uri: Uri, target: File): Boolean? {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: return null
        input.use { stream ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > Backup.MAX_IMPORT_BYTES) return false
                    out.write(buffer, 0, n)
                }
            }
        }
        return true
    }

    /** Reads [file] into a document without photo data, and a preview. Changes nothing. */
    suspend fun read(file: File): ReadResult = withContext(Dispatchers.IO) {
        if (file.length() > Backup.MAX_IMPORT_BYTES) return@withContext ReadResult.Error(ImportError.TooBig)
        val photos = ArrayList<PhotoDto>()
        var photosWithData = 0
        var photoProblems = 0
        val body = try {
            file.reader(Charsets.UTF_8).use { reader ->
                BackupSplitter.split(reader) { raw ->
                    // One photo at a time: its image is looked at and let go, only the details are kept.
                    val photo = raw?.let { BackupCodec.parse(it) as? JsonObject }?.let(BackupCodec::decodePhoto)
                    if (photo == null) {
                        photoProblems++
                    } else {
                        if (photo.data != null) photosWithData++
                        photos += photo.copy(data = null)
                    }
                }
            }
        } catch (e: BackupSplitter.TooBig) {
            return@withContext ReadResult.Error(ImportError.TooBig)
        } catch (e: IOException) {
            return@withContext ReadResult.Error(ImportError.Unreadable)
        } catch (e: OutOfMemoryError) {
            return@withContext ReadResult.Error(ImportError.TooBig)
        }
        val root = BackupCodec.parse(body) ?: return@withContext ReadResult.Error(ImportError.NotJson)
        val kind = BackupCodec.detect(root)
        val decoded = when (kind) {
            BackupKind.V2 -> BackupCodec.decodeV2(root as JsonObject).let { it.copy(doc = it.doc.copy(photos = photos)) }
            BackupKind.Legacy1 -> LegacyExportReader.read(root as JsonObject, Catalog.recipes)
            BackupKind.TooNew -> return@withContext ReadResult.Error(ImportError.TooNew)
            BackupKind.Unknown -> return@withContext ReadResult.Error(ImportError.NotBackup)
        }
        val doc = decoded.doc
        ReadResult.Ok(
            doc,
            ImportPreview(
                kind = kind,
                counts = Counts.of(doc),
                platform = doc.app.platform,
                packageName = doc.app.packageName,
                exportedAt = doc.exportedAt,
                version = doc.version,
                photosWithData = photosWithData,
                problems = decoded.problems.size + photoProblems,
                hasSettings = doc.settings != null,
                hasAi = doc.ai != null,
                aiCustomAddress = doc.ai?.let(AiSettingsStore::customAddressIn).orEmpty(),
            ),
        )
    }

    // ------------------------------------------------------------------ importing

    private class Staged(val temp: File, val fileUid: String, val photo: StagedPhoto)

    /**
     * Applies [doc] (read from [file] by [read]). Throws when nothing was changed: the database
     * transaction rolled back and every photo file this import wrote is gone again.
     */
    suspend fun import(file: File, doc: BackupDoc, kind: BackupKind, options: ImportOptions): ImportResult =
        withContext(Dispatchers.IO) {
            val staged = LinkedHashMap<String, Staged>()
            val committed = ArrayList<String>()
            var unreadablePhotos = 0
            val photosWithoutFile = HashSet<String>()
            val result: MergeResult
            try {
                if (doc.photos.isNotEmpty()) unreadablePhotos = stagePhotos(file, doc, options.mode, staged, photosWithoutFile)
                result = db.withTransaction {
                    val merged = BackupMerge.apply(
                        store = RoomBackupStore(db),
                        doc = doc,
                        mode = options.mode,
                        staged = staged.mapValues { it.value.photo },
                        now = now(),
                        legacy = kind == BackupKind.Legacy1,
                        photosWithoutFile = photosWithoutFile,
                    )
                    // Last step inside the transaction: a file that cannot take its name undoes
                    // the rows, instead of leaving a row that promises a photo which is not there.
                    for (s in staged.values) {
                        if (s.photo.fileName !in merged.usedPhotoFiles) continue
                        if (!photoFiles.commit(s.temp, s.fileUid)) throw IOException("Couldn't store a photo")
                        committed += s.photo.fileName
                    }
                    merged
                }
            } catch (e: Throwable) {
                staged.values.forEach { it.temp.delete() }
                // Only files this import created: a name is never one that was already in use.
                committed.forEach { photoFiles.delete(it) }
                throw e
            }

            // From here on the data is in. Nothing below may undo it.
            staged.values.forEach { if (it.photo.fileName !in result.usedPhotoFiles) it.temp.delete() }
            (result.removedPhotoFiles - result.usedPhotoFiles).forEach { photoFiles.delete(it) }

            runCatching {
                settings.restore { cur ->
                    var next = cur
                    if (options.mode == MergeMode.Replace) {
                        // The rows those ids pointed at were just deleted.
                        if (next.defaultBeanId !in result.beanIds.values) next = next.copy(defaultBeanId = 0L)
                        if (next.defaultGrinderId !in result.grinderIds.values) next = next.copy(defaultGrinderId = 0L)
                    }
                    val fromFile = doc.settings
                    if (options.bringSettings && fromFile != null) {
                        next = BackupMapping.settings(next, fromFile, result.beanIds::get, result.grinderIds::get)
                    }
                    // Someone who brought data has used the app: no setup screens. It was skipped
                    // for them, not by them, so Home offers "Personalise" instead.
                    if (result.arrived && !next.onboardingDone) next = next.copy(onboardingDone = true, onboardingAuto = true)
                    next
                }
            }
            val aiChoice = doc.ai
            if (options.bringAi && aiChoice != null) runCatching { ai.restore(aiChoice) }
            if (kind == BackupKind.Legacy1) runCatching { linkToCatalog(result.written) }
            // Whatever the file was and whatever was ticked: the question lives on the phone,
            // so a backup cannot carry it, and without it a 1.x scale would stay unasked here.
            runCatching { askAboutScales(result.written) }

            val skipped = result.skipped.toMutableList()
            if (unreadablePhotos > 0) {
                skipped += if (unreadablePhotos == 1) "1 photo couldn't be read" else "$unreadablePhotos photos couldn't be read"
            }
            ImportResult(result.added, result.replaced, result.kept, skipped)
        }

    /**
     * Second pass over the file: each photo's image is decoded, checked, stripped of metadata
     * and written to a temporary file in the photo folder. Returns how many could not be used.
     * [withoutFile] receives the uids of rows that are here with no image on this phone.
     */
    private suspend fun stagePhotos(
        file: File,
        doc: BackupDoc,
        mode: MergeMode,
        staged: MutableMap<String, Staged>,
        withoutFile: MutableSet<String>,
    ): Int {
        val rows = db.photoDao().all()
        // In Add mode a photo that is already here is kept as it is, so its image is not needed.
        // Unless only its row is here: Android's own backup and a device transfer carry the
        // database but not the photo folder, and this file is then where the images are.
        val alreadyHere = HashSet<String>()
        if (mode == MergeMode.Add) {
            for (row in rows) if (photoFiles.exists(row.fileName)) alreadyHere += row.uid else withoutFile += row.uid
        }
        val namesInUse = rows.mapTo(HashSet()) { it.fileName }
        val wanted = doc.photos.mapTo(HashSet()) { it.uid }
        var unreadable = 0
        file.reader(Charsets.UTF_8).use { reader ->
            BackupSplitter.split(reader) { raw ->
                val photo = raw?.let { BackupCodec.parse(it) as? JsonObject }?.let(BackupCodec::decodePhoto) ?: return@split
                val data = photo.data ?: return@split
                if (photo.uid !in wanted || photo.uid in alreadyHere || photo.uid in staged) return@split
                val image = decodeImage(data)
                if (image == null) {
                    unreadable++
                    return@split
                }
                // The uid is the file name when it can be: short, plain, and not a name in use.
                // Never an existing name, so nothing this import writes can replace a stored photo.
                var fileUid = photo.uid
                var name = PhotoFiles.nameFor(fileUid)
                if (photoFiles.stored(name) == null || photoFiles.exists(name) || name in namesInUse) {
                    fileUid = newUid()
                    name = PhotoFiles.nameFor(fileUid)
                }
                val temp = photoFiles.temp(fileUid) ?: run {
                    unreadable++
                    return@split
                }
                try {
                    FileOutputStream(temp).use { out ->
                        out.write(image.bytes)
                        out.fd.sync()
                    }
                } catch (e: IOException) {
                    // Usually a full disk: stop the whole import, leaving nothing half-written.
                    temp.delete()
                    throw e
                }
                namesInUse += name
                staged[photo.uid] = Staged(temp, fileUid, StagedPhoto(name, image.bytes.size.toLong(), image.width, image.height))
            }
        }
        return unreadable
    }

    private class Image(val bytes: ByteArray, val width: Int, val height: Int)

    /** The JPEG without metadata and its size, or null when it is not an image this phone can show. */
    private fun decodeImage(base64: String): Image? {
        if (base64.length > MAX_PHOTO_BASE64) return null
        val raw = try {
            Base64.getMimeDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val clean = JpegBytes.withoutMetadata(raw) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(clean, 0, clean.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return Image(clean, bounds.outWidth, bounds.outHeight)
    }

    private suspend fun linkToCatalog(written: Set<String>) {
        if (written.isEmpty()) return
        val grinders = db.grinderDao()
        val beans = db.beanDao()
        val linked = LegacyLinking.link(
            grinders = grinders.all().filter { it.uid in written },
            beans = beans.all().filter { it.uid in written },
            catalogGrinders = Catalog.grinders,
            roasters = Catalog.roasters,
            origins = Catalog.origins,
        )
        // Plain updates: only the ids change, updatedAt stays, so a second import is still a no-op.
        linked.grinders.forEach { grinders.update(it) }
        linked.beans.forEach { beans.update(it) }
        linked.notices.forEach { settings.addNotice(it) }
    }

    private suspend fun askAboutScales(written: Set<String>) {
        if (written.isEmpty()) return
        val arrived = db.grinderDao().all().filter { it.uid in written }
        LegacyLinking.scaleNotices(arrived, Catalog::grinder).forEach { settings.addNotice(it) }
    }

    private fun isStorageFull(e: Throwable): Boolean {
        var x: Throwable? = e
        var depth = 0
        while (x != null && depth < 5) {
            if (x is SQLiteFullException) return true
            val message = x.message.orEmpty()
            if (x is IOException && ("ENOSPC" in message || "No space left" in message)) return true
            x = x.cause
            depth++
        }
        return false
    }

    private companion object {
        /** A stored photo is at most 1024 px and well under a megabyte; this is 6 MB of image. */
        const val MAX_PHOTO_BASE64 = 8 * 1024 * 1024
    }
}
