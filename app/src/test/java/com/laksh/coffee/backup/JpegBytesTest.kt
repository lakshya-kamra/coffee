package com.laksh.coffee.backup

import com.laksh.coffee.domain.backup.BackupCodec
import com.laksh.coffee.domain.backup.JpegBytes
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** Photos that arrive in a backup lose their metadata on the way in, and keep their picture. */
class JpegBytesTest {

    // The zero byte that ends the name of an EXIF, XMP or ICC block.
    private val NUL = 0.toChar().toString()

    /** A real JPEG from the JDK's encoder: a gradient, so a damaged copy would decode differently. */
    private fun jpeg(width: Int = 64, height: Int = 48): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or ((x + y) and 0xFF))
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    /** [clean] with segments spliced in right after the start marker, the way a camera writes them. */
    private fun withSegments(clean: ByteArray, vararg segments: ByteArray): ByteArray =
        clean.copyOfRange(0, 2) + segments.reduce { a, b -> a + b } + clean.copyOfRange(2, clean.size)

    private fun pixels(bytes: ByteArray): List<Int> {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertNotNull("not decodable", image)
        return (0 until image.height).flatMap { y -> (0 until image.width).map { x -> image.getRGB(x, y) } }
    }

    private fun contains(haystack: ByteArray, needle: String): Boolean =
        String(haystack, Charsets.ISO_8859_1).contains(needle)

    @Test
    fun exifXmpIptcAndCommentsAreDroppedAndThePictureIsUntouched() {
        val clean = jpeg()
        val exif = segment(0xE1, ("Exif" + NUL + NUL + "GPSLatitude 51.5074 Make SecretCam").toByteArray(Charsets.ISO_8859_1))
        val xmp = segment(0xE1, ("http://ns.adobe.com/xap/1.0/" + NUL + "<x:xmpmeta>home address</x:xmpmeta>").toByteArray(Charsets.ISO_8859_1))
        val iptc = segment(0xED, "Photoshop 3.0 byline Sam".toByteArray(Charsets.ISO_8859_1))
        val comment = segment(0xFE, "taken at my flat".toByteArray(Charsets.ISO_8859_1))
        val vendor = segment(0xEB, "JUMBF credentials".toByteArray(Charsets.ISO_8859_1))
        val dirty = withSegments(clean, exif, xmp, iptc, comment, vendor)
        assertTrue(contains(dirty, "GPSLatitude"))

        val out = JpegBytes.withoutMetadata(dirty)!!
        for (word in listOf("Exif", "GPSLatitude", "SecretCam", "xmpmeta", "home address", "byline", "my flat", "JUMBF")) {
            assertFalse(word, contains(out, word))
        }
        assertEquals("same picture, pixel for pixel", pixels(clean), pixels(out))
        assertTrue(out.size < dirty.size)
    }

    @Test
    fun anythingAfterTheEndOfTheImageIsCutOff() {
        val clean = jpeg()
        // A "motion photo": a second file glued on after the end marker.
        val glued = clean + "ftypmp4 a video clip with sound".toByteArray(Charsets.ISO_8859_1) + jpeg(8, 8)
        val out = JpegBytes.withoutMetadata(glued)!!
        assertFalse(contains(out, "video clip"))
        assertEquals(pixels(clean), pixels(out))
        assertEquals(0xD9, out.last().toInt() and 0xFF)
    }

    @Test
    fun aCleanImageComesBackWithTheSamePicture() {
        val clean = jpeg(200, 120)
        val out = JpegBytes.withoutMetadata(clean)!!
        assertEquals(pixels(clean), pixels(out))
        // Doing it twice changes nothing more.
        assertArrayEquals(out, JpegBytes.withoutMetadata(out))
    }

    @Test
    fun aColourProfileIsKeptBecauseItChangesHowThePictureLooks() {
        val clean = jpeg()
        val icc = segment(0xE2, ("ICC_PROFILE" + NUL + "fake-profile-bytes").toByteArray(Charsets.ISO_8859_1))
        val mpf = segment(0xE2, ("MPF" + NUL + "second picture index").toByteArray(Charsets.ISO_8859_1))
        val out = JpegBytes.withoutMetadata(withSegments(clean, icc, mpf))!!
        assertTrue(contains(out, "ICC_PROFILE"))
        assertFalse(contains(out, "second picture index"))
    }

    @Test
    fun whatIsNotAJpegIsRefused() {
        assertNull(JpegBytes.withoutMetadata(ByteArray(0)))
        assertNull(JpegBytes.withoutMetadata("not an image at all".toByteArray()))
        // PNG signature.
        assertNull(JpegBytes.withoutMetadata(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        // Starts like a JPEG but has no picture in it.
        assertNull(JpegBytes.withoutMetadata(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
        // A segment that claims to be longer than the file.
        val clean = jpeg()
        val lying = clean.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x7F) + ByteArray(10)
        assertNull(JpegBytes.withoutMetadata(lying))
        assertFalse(JpegBytes.isJpeg("GIF89a".toByteArray()))
        assertTrue(JpegBytes.isJpeg(clean))
    }

    @Test
    fun aFileCutShortIsClosedProperly() {
        val clean = jpeg()
        val cut = clean.copyOfRange(0, clean.size - 2) // no end marker
        val out = JpegBytes.withoutMetadata(cut)!!
        assertEquals(0xD9, out.last().toInt() and 0xFF)
        assertEquals(pixels(clean), pixels(out))
    }

    @Test
    fun theSharedFixturesPhotoPasses() {
        val doc = BackupCodec.decodeV2(BackupCodec.parse(Fixtures.text(Fixtures.V2)) as JsonObject).doc
        val bytes = Base64.getDecoder().decode(doc.photos.single().data)
        val out = JpegBytes.withoutMetadata(bytes)
        assertNotNull("the web's sample photo must be importable", out)
        assertTrue(JpegBytes.isJpeg(out!!))
    }
}
