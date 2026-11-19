package com.laksh.coffee.data.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laksh.coffee.data.db.CoffeeDatabase
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.ai.AnalysisPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64

/**
 * The photo pipeline on a real device: a camera-sized JPEG full of EXIF goes in, and what is
 * stored is small, upright and carries none of it. Works in a folder of its own, so the
 * installed app's photos are never touched.
 */
@RunWith(AndroidJUnit4::class)
class PhotoStoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var files: PhotoFiles
    private lateinit var store: PhotoStore
    private lateinit var db: CoffeeDatabase
    private lateinit var repo: PhotoRepository

    private val shotAt = ZonedDateTime.of(2026, 11, 1, 7, 30, 0, 0, ZoneOffset.ofHours(2)).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        root = File(context.cacheDir, "photo-test-${System.nanoTime()}")
        files = PhotoFiles(File(root, "photos"), File(root, "camera"))
        store = PhotoStore(context, files)
        db = Room.inMemoryDatabaseBuilder(context, CoffeeDatabase::class.java).build()
        repo = PhotoRepository(db.photoDao(), store)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    /**
     * A 3000 x 2000 capture as a phone held upright writes it: pixels on their side with
     * Orientation 6, a red top-left quarter to tell which way is up, and location, camera and
     * time in EXIF.
     */
    private fun capture(name: String, orientation: Int = ExifInterface.ORIENTATION_ROTATE_90): File {
        val file = files.newScratch(name)!!
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, 1500f, 1000f, Paint().apply { color = Color.RED })
        }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_MAKE, "Pixelish")
            setAttribute(ExifInterface.TAG_MODEL, "Phone 9")
            setAttribute(ExifInterface.TAG_DATETIME, "2026:11:01 07:30:00")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:01 07:30:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00")
            setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "kitchen counter")
            setLatLong(51.5007, -0.1246)
            saveAttributes()
        }
        // The fixture really carries what the test says is removed.
        assertNotNull(ExifInterface(file.path).latLong)
        return file
    }

    private fun containsAscii(bytes: ByteArray, text: String): Boolean {
        val needle = text.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun isRed(pixel: Int) = Color.red(pixel) > 180 && Color.blue(pixel) < 90
    private fun isBlue(pixel: Int) = Color.blue(pixel) > 180 && Color.red(pixel) < 90

    @Test
    fun aCapture_isStoredSmallUprightAndWithoutExif(): Unit = runBlocking {
        val scratch = capture("shot.jpg")
        val saved = store.importCamera("shot.jpg")!!

        // Stood upright: the long edge is now the height, and it is 1024.
        assertEquals(683, saved.widthPx)
        assertEquals(1024, saved.heightPx)
        assertEquals(shotAt, saved.takenAt)
        assertFalse("the camera's scratch file is deleted", scratch.exists())

        val stored = files.stored(saved.fileName)!!
        assertTrue(stored.isFile)
        assertEquals(stored.length(), saved.bytes)
        assertTrue("small enough to send: ${saved.bytes} bytes", saved.bytes < 600_000)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(stored.path, bounds)
        assertEquals(683, bounds.outWidth)
        assertEquals(1024, bounds.outHeight)
        assertEquals("image/jpeg", bounds.outMimeType)

        val exif = ExifInterface(stored.path)
        assertNull(exif.latLong)
        listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ).forEach { tag -> assertNull(tag, exif.getAttribute(tag)) }
        assertEquals(
            ExifInterface.ORIENTATION_UNDEFINED,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
        )
        // And not just unreadable: there is no EXIF block in the bytes at all.
        val bytes = stored.readBytes()
        assertFalse(containsAscii(bytes, "Exif"))
        assertFalse(containsAscii(bytes, "Pixelish"))
        assertFalse(containsAscii(bytes, "kitchen counter"))

        // Rotated a quarter turn clockwise, the red top-left quarter is now top-right.
        val upright = BitmapFactory.decodeFile(stored.path)
        assertTrue(isRed(upright.getPixel(upright.width - 40, 40)))
        assertTrue(isBlue(upright.getPixel(40, 40)))
        assertTrue(isBlue(upright.getPixel(40, upright.height - 40)))
        assertTrue(isBlue(upright.getPixel(upright.width - 40, upright.height - 40)))
        upright.recycle()
    }

    @Test
    fun aPickedImage_goesThroughTheSamePipeline(): Unit = runBlocking {
        val source = capture("picked.jpg", ExifInterface.ORIENTATION_NORMAL)
        val saved = store.importUri(Uri.fromFile(source))!!
        assertEquals(1024, saved.widthPx)
        assertEquals(683, saved.heightPx)
        assertFalse(containsAscii(files.stored(saved.fileName)!!.readBytes(), "Exif"))
        // The pick is copied in and the copy removed; the original is not the app's to delete.
        assertTrue(source.exists())
        assertEquals(listOf("picked.jpg"), files.cameraDir.list()!!.toList())
    }

    @Test
    fun somethingThatIsNotAnImage_isRefusedAndLeavesNothingBehind(): Unit = runBlocking {
        files.newScratch("notes.jpg")!!.writeText("not a picture")
        assertNull(store.importCamera("notes.jpg"))
        assertNull(store.importCamera("../escape.jpg"))
        assertNull(store.importUri(Uri.parse("content://com.laksh.coffee.nowhere/missing")))
        assertTrue(files.cameraDir.list().isNullOrEmpty())
        assertTrue(files.photosDir.list().isNullOrEmpty())
    }

    @Test
    fun aBrew_holdsSixPhotos_andDeletingOneRemovesItsFile(): Unit = runBlocking {
        val brewId = db.brewLogDao().insert(BrewLogEntity(recipeName = "Test"))
        val owner = PhotoOwner.Brew(brewId)
        repeat(PhotoRepository.MAX_PER_BREW) { i ->
            capture("c$i.jpg")
            assertTrue(repo.addFromCamera("c$i.jpg", PhotoKinds.BED, owner) is AddPhotoResult.Added)
        }
        capture("extra.jpg")
        assertEquals(AddPhotoResult.Full, repo.addFromCamera("extra.jpg", PhotoKinds.BED, owner))
        assertTrue("a refused capture is not left in the cache", files.cameraDir.list().isNullOrEmpty())

        val rows = repo.observeForBrew(brewId).first()
        assertEquals(6, rows.size)
        assertEquals(6, files.photosDir.list()!!.size)
        assertTrue(rows.all { it.sendToAi && it.kind == PhotoKinds.BED && it.brewLogId == brewId && it.beanId == null })

        repo.delete(rows.first())
        assertFalse(files.exists(rows.first().fileName))
        assertEquals(5, repo.observeForBrew(brewId).first().size)
        assertEquals(5, files.photosDir.list()!!.size)
    }

    @Test
    fun aBag_holdsThree_andAPhotoForAMissingBrewIsNotKept(): Unit = runBlocking {
        val beanId = db.beanDao().insert(BeanEntity(name = "Test bean"))
        val owner = PhotoOwner.Bean(beanId)
        repeat(PhotoRepository.MAX_PER_BEAN) { i ->
            capture("b$i.jpg")
            assertTrue(repo.addFromCamera("b$i.jpg", PhotoKinds.BAG, owner) is AddPhotoResult.Added)
        }
        capture("b-extra.jpg")
        assertEquals(AddPhotoResult.Full, repo.addFromCamera("b-extra.jpg", PhotoKinds.BAG, owner))

        // The foreign key refuses the row; the file written for it must go too.
        capture("orphan.jpg")
        assertEquals(AddPhotoResult.Unreadable, repo.addFromCamera("orphan.jpg", PhotoKinds.BED, PhotoOwner.Brew(999)))
        assertEquals(3, files.photosDir.list()!!.size)
    }

    @Test
    fun edits_areMadeOnTheRowAsItIsNow(): Unit = runBlocking {
        val brewId = db.brewLogDao().insert(BrewLogEntity(recipeName = "Test"))
        capture("e.jpg")
        val added = (repo.addFromCamera("e.jpg", PhotoKinds.BED, PhotoOwner.Brew(brewId)) as AddPhotoResult.Added).photo
        repo.setSendToAi(added.uid, false)
        repo.setKind(added.uid, PhotoKinds.CUP)
        repo.setCaption(added.uid, "  flat, a little muddy  ")
        repo.setKind(added.uid, "not-a-kind")
        val row = db.photoDao().byUid(added.uid)!!
        assertFalse(row.sendToAi)
        assertEquals(PhotoKinds.CUP, row.kind)
        assertEquals("flat, a little muddy", row.caption)
    }

    @Test
    fun sweep_removesOldOrphansAndKeepsRowsWhoseFileIsMissing(): Unit = runBlocking {
        val brewId = db.brewLogDao().insert(BrewLogEntity(recipeName = "Test"))
        val owner = PhotoOwner.Brew(brewId)
        capture("s1.jpg")
        capture("s2.jpg")
        val kept = (repo.addFromCamera("s1.jpg", PhotoKinds.BED, owner) as AddPhotoResult.Added).photo
        val lost = (repo.addFromCamera("s2.jpg", PhotoKinds.CUP, owner) as AddPhotoResult.Added).photo
        val day = 24 * 60 * 60 * 1000L
        files.stored(kept.fileName)!!.setLastModified(System.currentTimeMillis() - day)
        // A restore that brought the row but not the file.
        assertTrue(files.stored(lost.fileName)!!.delete())
        val orphan = File(files.photosDir, "orphan.jpg").apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() - day)
        }

        assertEquals(1, repo.sweep())
        assertFalse(orphan.exists())
        assertTrue(files.exists(kept.fileName))
        assertEquals(2, repo.observeForBrew(brewId).first().size)
    }

    @Test
    fun anAnalysis_isOfferedTheTickedBedAndCupPhotosAsBase64Jpeg(): Unit = runBlocking {
        val brewId = db.brewLogDao().insert(BrewLogEntity(recipeName = "Test"))
        val owner = PhotoOwner.Brew(brewId)
        val added = listOf(PhotoKinds.BED, PhotoKinds.CUP, PhotoKinds.OTHER, PhotoKinds.BED).mapIndexed { i, kind ->
            capture("a$i.jpg")
            (repo.addFromCamera("a$i.jpg", kind, owner) as AddPhotoResult.Added).photo
        }
        repo.setSendToAi(added[0].uid, false)
        val attachments = PhotoAttachments(db.photoDao(), store) { ZoneOffset.UTC }

        val expected = listOf(added[1].uid, added[3].uid)
        val meta = attachments.metaForBrew(brewId)
        val images = attachments.forBrew(brewId)
        assertEquals(expected.toSet(), meta.map { it.uid }.toSet())
        assertEquals(meta.map { it.uid }, images.map { it.uid })
        assertTrue(images.size <= AnalysisPrompt.MAX_PHOTOS)
        images.forEach { image ->
            assertEquals("image/jpeg", image.mediaType)
            assertFalse(image.base64.startsWith("data:"))
            val bytes = Base64.getDecoder().decode(image.base64)
            assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), bytes.copyOfRange(0, 2))
            assertArrayEquals(files.stored("${image.uid}.jpg")!!.readBytes(), bytes)
            assertTrue(image.label, image.label.matches(Regex("(bed|cup), \\d\\d:\\d\\d")))
        }

        // A file that is gone is neither counted nor sent.
        assertTrue(files.stored(added[3].fileName)!!.delete())
        assertEquals(listOf(added[1].uid), attachments.metaForBrew(brewId).map { it.uid })
        assertEquals(listOf(added[1].uid), attachments.forBrew(brewId).map { it.uid })
    }
}
