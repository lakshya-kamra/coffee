package com.laksh.coffee.photos

import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.photos.PhotoKinds
import com.laksh.coffee.data.photos.PhotoOwner
import com.laksh.coffee.data.photos.PhotoPick
import com.laksh.coffee.data.photos.PhotoRepository
import com.laksh.coffee.domain.ai.AnalysisPrompt
import com.laksh.coffee.ui.ai.PhotoHold
import com.laksh.coffee.ui.ai.PhotoLine
import com.laksh.coffee.ui.ai.photoLine
import com.laksh.coffee.ui.ai.photoLineText
import com.laksh.coffee.ui.photos.suggestedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Which photos go with an analysis, and what the card says about them before anything is sent. */
class PhotoPickTest {

    private fun photo(
        id: Long,
        kind: String = PhotoKinds.BED,
        takenAt: Long = id * 1_000,
        send: Boolean = true,
        bytes: Long = 200_000,
    ) = PhotoEntity(
        id = id,
        uid = "uid-$id",
        brewLogId = 7,
        kind = kind,
        fileName = "uid-$id.jpg",
        widthPx = 1024,
        heightPx = 768,
        bytes = bytes,
        takenAt = takenAt,
        createdAt = takenAt,
        sendToAi = send,
    )

    private val onDisk: (PhotoEntity) -> Long? = { it.bytes }

    @Test
    fun `the newest three ticked bed and cup photos go, oldest of them first`() {
        val photos = listOf(
            photo(1),
            photo(2, kind = PhotoKinds.CUP),
            photo(3),
            photo(4, kind = PhotoKinds.CUP),
        )
        assertEquals(listOf(2L, 3L, 4L), PhotoPick.forAnalysis(photos, length = onDisk).map { it.id })
        assertEquals(3, AnalysisPrompt.MAX_PHOTOS)
    }

    @Test
    fun `an unticked photo stays on the phone`() {
        val photos = listOf(photo(1), photo(2, send = false), photo(3))
        assertEquals(listOf(1L, 3L), PhotoPick.forAnalysis(photos, length = onDisk).map { it.id })
        assertTrue(PhotoPick.forAnalysis(photos.map { it.copy(sendToAi = false) }, length = onDisk).isEmpty())
    }

    @Test
    fun `bag and other photos are never sent`() {
        val photos = listOf(
            photo(1, kind = PhotoKinds.BAG),
            photo(2, kind = PhotoKinds.OTHER),
            photo(3, kind = "selfie"),
            photo(4, kind = PhotoKinds.CUP),
        )
        assertEquals(listOf(4L), PhotoPick.forAnalysis(photos, length = onDisk).map { it.id })
    }

    @Test
    fun `a photo whose file is not on this phone is passed over for an older one`() {
        val photos = listOf(photo(1), photo(2), photo(3), photo(4))
        val missing: (PhotoEntity) -> Long? = { if (it.id == 4L) null else it.bytes }
        assertEquals(listOf(1L, 2L, 3L), PhotoPick.forAnalysis(photos, length = missing).map { it.id })
        val empty: (PhotoEntity) -> Long? = { if (it.id == 3L) 0L else it.bytes }
        assertEquals(listOf(1L, 2L, 4L), PhotoPick.forAnalysis(photos, length = empty).map { it.id })
    }

    @Test
    fun `photos taken in the same instant keep a fixed order`() {
        val photos = listOf(photo(1, takenAt = 5), photo(2, takenAt = 5), photo(3, takenAt = 5), photo(4, takenAt = 5))
        assertEquals(listOf(2L, 3L, 4L), PhotoPick.forAnalysis(photos, length = onDisk).map { it.id })
        assertEquals(listOf(2L, 3L, 4L), PhotoPick.forAnalysis(photos.reversed(), length = onDisk).map { it.id })
    }

    @Test
    fun `past four megabytes of base64 the oldest is dropped`() {
        assertEquals(4L, PhotoPick.base64Chars(1))
        assertEquals(4L, PhotoPick.base64Chars(3))
        assertEquals(8L, PhotoPick.base64Chars(4))
        // 1.2 MB each is 1.6 MB of base64: three pass the limit, two fit.
        val big = listOf(photo(1, bytes = 1_200_000), photo(2, bytes = 1_200_000), photo(3, bytes = 1_200_000))
        assertEquals(listOf(2L, 3L), PhotoPick.forAnalysis(big, length = onDisk).map { it.id })
        // One photo too large on its own sends nothing rather than a request that would be refused.
        assertTrue(PhotoPick.forAnalysis(listOf(photo(1, bytes = 4_000_000)), length = onDisk).isEmpty())
        // The size on disk decides, not what the row remembers.
        val grown: (PhotoEntity) -> Long? = { 1_200_000L }
        assertEquals(2, PhotoPick.forAnalysis(listOf(photo(1), photo(2), photo(3)), length = grown).size)
    }

    @Test
    fun `the label names the kind and the local time`() {
        val zone = ZoneId.of("Europe/London")
        val at = ZonedDateTime.of(2026, 11, 17, 8, 14, 40, 0, zone).toInstant().toEpochMilli()
        assertEquals("bed, 08:14", PhotoPick.label(photo(1, takenAt = at), zone))
        assertEquals("cup, 13:44", PhotoPick.label(photo(1, kind = PhotoKinds.CUP, takenAt = at), ZoneId.of("Asia/Kolkata")))
    }

    // ---------------------------------------------------------- the card's line

    @Test
    fun `no bed or cup photo means the card says nothing about photos`() {
        assertNull(photoLine(onBrew = 0, chosen = emptyList(), sendPhotos = true, modelSeesImages = true))
    }

    @Test
    fun `the card lists exactly the photos that would go`() {
        val chosen = listOf(photo(1), photo(2, kind = PhotoKinds.CUP))
        val line = photoLine(onBrew = 3, chosen = chosen, sendPhotos = true, modelSeesImages = true)
        assertEquals(PhotoLine(chosen, null), line)
        assertEquals("Sends 2 photos. Open one to keep it out.", photoLineText(line!!, "GPT-5"))
        assertEquals(
            "Sends 1 photo. Open one to keep it out.",
            photoLineText(PhotoLine(chosen.take(1), null), "GPT-5"),
        )
    }

    @Test
    fun `a model that cannot see images gets no photo, and the card says which model`() {
        val line = photoLine(onBrew = 2, chosen = listOf(photo(1)), sendPhotos = true, modelSeesImages = false)
        assertEquals(PhotoLine(emptyList(), PhotoHold.ModelBlind), line)
        assertEquals("Photos stay on the phone — DeepSeek V3 can't see images", photoLineText(line!!, "DeepSeek V3"))
        assertEquals("Photos stay on the phone — this model can't see images", photoLineText(line, ""))
    }

    @Test
    fun `the switch being off and nothing being ticked are told apart`() {
        val off = photoLine(onBrew = 2, chosen = listOf(photo(1)), sendPhotos = false, modelSeesImages = true)
        assertEquals(PhotoHold.SwitchedOff, off!!.hold)
        assertTrue(off.sending.isEmpty())
        val none = photoLine(onBrew = 2, chosen = emptyList(), sendPhotos = true, modelSeesImages = true)
        assertEquals(PhotoHold.NoneTicked, none!!.hold)
        assertTrue(photoLineText(off, "m").contains("switched off"))
        assertTrue(photoLineText(none, "m").contains("none is ticked"))
    }

    // ------------------------------------------------------------- small rules

    @Test
    fun `the add menu suggests the first kind the brew has no photo of`() {
        val kinds = PhotoKinds.FOR_BREW
        assertEquals(PhotoKinds.BED, suggestedKind(emptyList(), kinds))
        assertEquals(PhotoKinds.CUP, suggestedKind(listOf(photo(1)), kinds))
        assertEquals(PhotoKinds.OTHER, suggestedKind(listOf(photo(1), photo(2, kind = PhotoKinds.CUP)), kinds))
        val all = listOf(photo(1), photo(2, kind = PhotoKinds.CUP), photo(3, kind = PhotoKinds.OTHER))
        assertEquals(PhotoKinds.BED, suggestedKind(all, kinds))
        assertEquals(PhotoKinds.BAG, suggestedKind(emptyList(), PhotoKinds.FOR_BEAN))
    }

    @Test
    fun `a brew holds six photos and a bag three`() {
        assertEquals(6, PhotoRepository.limit(PhotoOwner.Brew(1)))
        assertEquals(3, PhotoRepository.limit(PhotoOwner.Bean(1)))
        assertEquals("Bed", PhotoKinds.label(PhotoKinds.BED))
        assertEquals("Other", PhotoKinds.label("anything else"))
    }
}
