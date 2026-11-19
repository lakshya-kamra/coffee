package com.laksh.coffee.backup

import com.laksh.coffee.domain.backup.BackupCodec
import com.laksh.coffee.domain.backup.BackupSplitter
import com.laksh.coffee.domain.backup.BackupWriter
import com.laksh.coffee.domain.backup.PhotoDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** The importer reads photos out of the file one at a time; this is the part that finds them. */
class BackupSplitterTest {

    private fun split(text: String): Pair<String, List<String?>> {
        val photos = ArrayList<String?>()
        val body = BackupSplitter.split(StringReader(text)) { photos += it }
        return body to photos
    }

    @Test
    fun theFixturesPhotoComesOutAndTheRestStaysValid() {
        val text = Fixtures.text(Fixtures.V2)
        val (body, photos) = split(text)

        val whole = BackupCodec.decodeV2(BackupCodec.parse(text) as JsonObject).doc
        val rest = BackupCodec.decodeV2(BackupCodec.parse(body) as JsonObject).doc
        assertTrue(rest.photos.isEmpty())
        assertEquals(whole.copy(photos = emptyList()), rest)

        val photo = BackupCodec.decodePhoto(BackupCodec.parse(photos.single()!!) as JsonObject)
        assertEquals(whole.photos.single(), photo)
        assertFalse("the image is not in what stays in memory", body.contains(whole.photos.single().data!!))
    }

    @Test
    fun manyPhotosComeOutInOrderFromAFileThisAppWrote() {
        val doc = BackupCodec.decodeV2(BackupCodec.parse(Fixtures.text(Fixtures.V2)) as JsonObject).doc
        val one = doc.photos.single()
        val many = (1..25).map { one.copy(uid = "photo-$it", caption = "Braces } ] and a quote \" in caption $it \\") }
        val text = StringBuilder().also { BackupWriter.write(it, doc, many.asSequence()) }.toString()

        val (body, photos) = split(text)
        assertEquals(25, photos.size)
        val read: List<PhotoDto?> = photos.map { BackupCodec.decodePhoto(BackupCodec.parse(it!!) as JsonObject) }
        assertEquals(many, read)
        assertEquals(doc.copy(photos = emptyList()), BackupCodec.decodeV2(BackupCodec.parse(body) as JsonObject).doc)
    }

    @Test
    fun aCompactFileSplitsTheSameWay() {
        val compact = """{"format":"coffee-backup","version":2,"photos":[{"uid":"a","brewUid":"b","takenAt":"2026-11-10T07:16:00Z","data":"QUJD"},{"uid":"c","beanUid":"d","takenAt":"2026-11-10T07:16:00Z","data":null}],"brews":[]}"""
        val (body, photos) = split(compact)
        assertEquals("""{"format":"coffee-backup","version":2,"photos":[],"brews":[]}""", body)
        assertEquals(2, photos.size)
        assertEquals("QUJD", BackupCodec.decodePhoto(BackupCodec.parse(photos[0]!!) as JsonObject)!!.data)
        assertNull(BackupCodec.decodePhoto(BackupCodec.parse(photos[1]!!) as JsonObject)!!.data)
    }

    @Test
    fun onlyTheTopLevelPhotosArrayIsTakenOut() {
        // "photos" as a value, as a nested key, and as text in a note: all left alone.
        val text = """{"note":"photos","brews":[{"uid":"x","photos":[{"uid":"nested"}],"notes":"\"photos\": [ {"}],"photos":[{"uid":"real"}, 7, "text", [1,2], null]}"""
        val (body, photos) = split(text)
        assertEquals("""{"note":"photos","brews":[{"uid":"x","photos":[{"uid":"nested"}],"notes":"\"photos\": [ {"}],"photos":[]}""", body)
        // The object, then one null for the element that is an array; bare values are not elements to import.
        assertEquals(listOf("""{"uid":"real"}""", null), photos)
        assertTrue(BackupCodec.parse(body) is JsonObject)
    }

    @Test
    fun aFileWithoutPhotosIsReturnedWhole() {
        val legacy = Fixtures.text(Fixtures.LEGACY)
        val (body, photos) = split(legacy)
        assertEquals(legacy, body)
        assertTrue(photos.isEmpty())

        val (body2, photos2) = split("""{"photos": null, "x": {"photos": "no"}}""")
        assertEquals("""{"photos": null, "x": {"photos": "no"}}""", body2)
        assertTrue(photos2.isEmpty())
    }

    @Test
    fun anEmptyOrBrokenFileDoesNotThrow() {
        assertEquals("", split("").first)
        assertEquals("[1,2", split("[1,2").first)
        val (body, photos) = split("""{"photos":[{"uid":"cut off""")
        assertEquals("""{"photos":[""", body)
        assertTrue(photos.isEmpty())
        assertNull(BackupCodec.parse(body))
    }

    @Test
    fun theBodyKeepsEveryOtherTable() {
        val text = Fixtures.text(Fixtures.V2)
        val root = BackupCodec.parse(split(text).first) as JsonObject
        assertEquals(2, (root["brews"] as JsonArray).size)
        assertEquals(0, (root["photos"] as JsonArray).size)
    }
}
