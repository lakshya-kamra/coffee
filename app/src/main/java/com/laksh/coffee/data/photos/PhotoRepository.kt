package com.laksh.coffee.data.photos

import android.net.Uri
import com.laksh.coffee.data.db.dao.PhotoDao
import com.laksh.coffee.data.db.entity.PhotoEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** What a photo belongs to: one brew, or one bag of coffee. */
sealed interface PhotoOwner {
    data class Brew(val brewLogId: Long) : PhotoOwner
    data class Bean(val beanId: Long) : PhotoOwner
}

sealed interface AddPhotoResult {
    data class Added(val photo: PhotoEntity) : AddPhotoResult

    /** The owner already has as many photos as it may. */
    data object Full : AddPhotoResult

    /** Not an image this phone can decode, too large, or the brew or bean is gone. */
    data object Unreadable : AddPhotoResult
}

object PhotoKinds {
    const val BED = "bed"
    const val CUP = "cup"
    const val BAG = "bag"
    const val OTHER = "other"

    val ALL: List<String> = listOf(BED, CUP, BAG, OTHER)

    /** The kinds a brew's photo can be, in the order they are offered. */
    val FOR_BREW: List<String> = listOf(BED, CUP, OTHER)
    val FOR_BEAN: List<String> = listOf(BAG)

    fun label(kind: String): String = when (kind) {
        BED -> "Bed"
        CUP -> "Cup"
        BAG -> "Bag"
        else -> "Other"
    }
}

/**
 * Photos of brews and bags: a row in Room and a JPEG in filesDir/photos. The file is written
 * before its row and deleted after it, so a crash in between leaves at worst a file that
 * [sweep] removes, never a row that promises a photo which was never stored.
 */
class PhotoRepository(
    private val dao: PhotoDao,
    private val store: PhotoStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun observe(owner: PhotoOwner): Flow<List<PhotoEntity>> = when (owner) {
        is PhotoOwner.Brew -> dao.observeForBrew(owner.brewLogId)
        is PhotoOwner.Bean -> dao.observeForBean(owner.beanId)
    }

    fun observeForBrew(brewLogId: Long): Flow<List<PhotoEntity>> = dao.observeForBrew(brewLogId)
    fun observeForBean(beanId: Long): Flow<List<PhotoEntity>> = dao.observeForBean(beanId)

    /** The photos an analysis of this brew would be offered, kept current as photos are added, ticked or removed. */
    fun observeForAnalysis(brewLogId: Long): Flow<List<PhotoEntity>> =
        dao.observeForBrew(brewLogId)
            .map { PhotoPick.forAnalysis(it) { p -> store.files.length(p.fileName) } }
            .flowOn(Dispatchers.IO)

    /** The stored file, or null when the row's name is not one this app writes. It may not exist. */
    fun file(photo: PhotoEntity): File? = store.files.stored(photo.fileName)

    fun newCameraTarget(): CameraTarget? = store.newCameraTarget()

    suspend fun discardCamera(name: String) = store.discardCamera(name)

    suspend fun addFromLibrary(uri: Uri, kind: String, owner: PhotoOwner): AddPhotoResult =
        add(kind, owner) { store.importUri(uri) }

    suspend fun addFromCamera(name: String, kind: String, owner: PhotoOwner): AddPhotoResult =
        add(kind, owner) { store.importCamera(name) }.also {
            // A capture that was refused (the brew is full) still leaves its scratch file behind.
            if (it !is AddPhotoResult.Added) store.discardCamera(name)
        }

    // Not cancellable: leaving the screen halfway must not strand a file without its row.
    private suspend fun add(
        kind: String,
        owner: PhotoOwner,
        import: suspend () -> SavedPhoto?,
    ): AddPhotoResult = withContext(NonCancellable) {
        if (count(owner) >= limit(owner)) return@withContext AddPhotoResult.Full
        val saved = import() ?: return@withContext AddPhotoResult.Unreadable
        val row = PhotoEntity(
            uid = saved.uid,
            brewLogId = (owner as? PhotoOwner.Brew)?.brewLogId,
            beanId = (owner as? PhotoOwner.Bean)?.beanId,
            kind = kind.takeIf { it in PhotoKinds.ALL } ?: PhotoKinds.OTHER,
            fileName = saved.fileName,
            widthPx = saved.widthPx,
            heightPx = saved.heightPx,
            bytes = saved.bytes,
            takenAt = saved.takenAt,
            createdAt = now(),
        )
        try {
            AddPhotoResult.Added(row.copy(id = dao.insert(row)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The brew or bean was deleted while the photo was being read (foreign key).
            store.delete(listOf(saved.fileName))
            AddPhotoResult.Unreadable
        }
    }

    private suspend fun count(owner: PhotoOwner): Int = when (owner) {
        is PhotoOwner.Brew -> dao.countForBrew(owner.brewLogId)
        is PhotoOwner.Bean -> dao.forBean(owner.beanId).size
    }

    // One edit at a time, each on the row as it is now: a caption being typed must not undo a
    // tick that was changed a moment earlier on an older copy of the row.
    private val edits = Mutex()

    private suspend fun edit(uid: String, change: (PhotoEntity) -> PhotoEntity) = edits.withLock {
        val current = dao.byUid(uid) ?: return@withLock
        val next = change(current)
        if (next != current) dao.update(next)
    }

    suspend fun setKind(uid: String, kind: String) {
        if (kind in PhotoKinds.ALL) edit(uid) { it.copy(kind = kind) }
    }

    suspend fun setCaption(uid: String, caption: String) = edit(uid) { it.copy(caption = caption.trim().take(MAX_CAPTION)) }

    suspend fun setSendToAi(uid: String, send: Boolean) = edit(uid) { it.copy(sendToAi = send) }

    /** Row first, then the file. */
    suspend fun delete(photo: PhotoEntity) = withContext(NonCancellable) {
        dao.deleteById(photo.id)
        store.delete(listOf(photo.fileName))
    }

    /**
     * Removes files nothing points at. Rows whose file is missing are kept: they show as
     * "Not on this phone" (a phone restored from Android's backup has the rows, not the files).
     */
    suspend fun sweep(): Int = store.sweep(dao.all().mapTo(HashSet()) { it.fileName })

    companion object {
        const val MAX_PER_BREW = 6
        const val MAX_PER_BEAN = 3
        const val MAX_CAPTION = 200

        fun limit(owner: PhotoOwner): Int = when (owner) {
            is PhotoOwner.Brew -> MAX_PER_BREW
            is PhotoOwner.Bean -> MAX_PER_BEAN
        }
    }
}
