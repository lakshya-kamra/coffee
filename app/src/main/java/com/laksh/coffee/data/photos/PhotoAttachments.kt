package com.laksh.coffee.data.photos

import com.laksh.coffee.data.ai.AiImage
import com.laksh.coffee.data.ai.PhotoAttachmentSource
import com.laksh.coffee.data.db.dao.PhotoDao
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.domain.ai.AnalysisPrompt
import com.laksh.coffee.domain.ai.PhotoMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Which of a brew's photos go with an analysis. Pure, so the rule is tested without files. */
object PhotoPick {

    /** Only these say something about the brew. A bag or "other" photo is never sent. */
    val KINDS: Set<String> = setOf(PhotoKinds.BED, PhotoKinds.CUP)

    /** Providers refuse very large requests; photos are about 0.2 MB each, so this is a backstop. */
    const val MAX_BASE64_CHARS = 4L * 1024 * 1024

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun base64Chars(bytes: Long): Long = 4 * ((bytes + 2) / 3)

    /**
     * The newest [max] ticked bed and cup photos whose file is on this phone, oldest of those
     * first (so a bed photo comes before the cup it led to). If together they pass
     * [MAX_BASE64_CHARS], the oldest is dropped until they fit. [length] is the file's size on
     * disk, or null when it is missing.
     */
    fun forAnalysis(
        photos: List<PhotoEntity>,
        max: Int = AnalysisPrompt.MAX_PHOTOS,
        length: (PhotoEntity) -> Long?,
    ): List<PhotoEntity> {
        val sized = photos
            .filter { it.sendToAi && it.kind in KINDS }
            .mapNotNull { p -> length(p)?.takeIf { it > 0 }?.let { p to it } }
            .sortedWith(compareByDescending<Pair<PhotoEntity, Long>> { it.first.takenAt }.thenByDescending { it.first.id })
            .take(max)
            .toMutableList()
        while (sized.isNotEmpty() && sized.sumOf { base64Chars(it.second) } > MAX_BASE64_CHARS) sized.removeAt(sized.lastIndex)
        return sized.map { it.first }.reversed()
    }

    /** "bed, 08:14": what the model is told the image is. */
    fun label(photo: PhotoEntity, zone: ZoneId): String =
        "${photo.kind}, ${TIME.format(Instant.ofEpochMilli(photo.takenAt).atZone(zone))}"
}

/**
 * The analysis repository's view of photos. [metaForBrew] and [forBrew] choose by the same rule,
 * so the photos a preview counts are the ones a run attaches.
 */
class PhotoAttachments(
    private val dao: PhotoDao,
    private val store: PhotoStore,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : PhotoAttachmentSource {

    private suspend fun chosen(brewId: Long): List<PhotoEntity> =
        PhotoPick.forAnalysis(dao.forBrew(brewId)) { store.files.length(it.fileName) }

    override suspend fun metaForBrew(brewId: Long): List<PhotoMeta> = withContext(Dispatchers.IO) {
        chosen(brewId).map { PhotoMeta(uid = it.uid, kind = it.kind, takenAt = it.takenAt, bytes = it.bytes) }
    }

    override suspend fun forBrew(brewId: Long): List<AiImage> = withContext(Dispatchers.IO) {
        val z = zone()
        chosen(brewId).mapNotNull { photo ->
            val bytes = store.read(photo.fileName) ?: return@mapNotNull null
            AiImage(
                label = PhotoPick.label(photo, z),
                base64 = Base64.getEncoder().encodeToString(bytes),
                uid = photo.uid,
            )
        }
    }
}
