package com.laksh.coffee.photos

import com.laksh.coffee.data.photos.PhotoFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Photo files on disk: which names are accepted, what a sweep removes and what it must leave. */
class PhotoFilesTest {

    private lateinit var root: File
    private lateinit var files: PhotoFiles

    private val hour = 60 * 60 * 1000L
    private val now = 1_795_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("coffee-photos").toFile()
        files = PhotoFiles(File(root, "files/photos"), File(root, "cache/camera"))
        files.photosDir.mkdirs()
        files.cameraDir.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun stored(name: String, ageMs: Long, text: String = "jpeg"): File =
        File(files.photosDir, name).apply {
            writeText(text)
            setLastModified(now - ageMs)
        }

    private fun scratch(name: String, ageMs: Long): File =
        File(files.cameraDir, name).apply {
            writeText("raw")
            setLastModified(now - ageMs)
        }

    @Test
    fun `only names the app writes become paths`() {
        val uid = "3f2b8c1e-7a44-4d0b-9c1d-5e6f7a8b9c0d"
        assertEquals(File(files.photosDir, "$uid.jpg"), files.stored("$uid.jpg"))
        assertNotNull(files.stored("web_photo-12.jpg"))
        // A row restored from a backup must not be able to name another file of the app.
        assertNull(files.stored("../databases/coffee.db"))
        assertNull(files.stored("..\\databases\\coffee.db"))
        assertNull(files.stored("a/b.jpg"))
        assertNull(files.stored("/data/data/com.laksh.coffee/databases/coffee.db"))
        assertNull(files.stored("$uid.jpg.tmp"))
        assertNull(files.stored("$uid.png"))
        assertNull(files.stored(".jpg"))
        assertNull(files.stored(""))
        assertNull(files.stored("a".repeat(65) + ".jpg"))
        assertNull(files.temp("../evil"))
        assertNull(files.scratch("../evil.jpg"))
        assertNotNull(files.scratch("$uid.jpg"))
        assertNotNull(files.scratch("$uid.src"))
    }

    @Test
    fun `delete never leaves the photo folder`() {
        val outside = File(root, "files/keep.jpg").apply { writeText("keep") }
        assertFalse(files.delete("../keep.jpg"))
        assertTrue(outside.exists())

        stored("a.jpg", 0)
        assertTrue(files.delete("a.jpg"))
        assertFalse(files.exists("a.jpg"))
        // Already gone counts as done.
        assertTrue(files.delete("a.jpg"))
    }

    @Test
    fun `a photo is written beside its final name and then renamed`() {
        val temp = files.temp("abc")!!
        assertEquals("abc.jpg.tmp", temp.name)
        temp.writeText("pixels")
        assertFalse(files.exists("abc.jpg"))
        assertTrue(files.commit(temp, "abc"))
        assertFalse(temp.exists())
        assertTrue(files.exists("abc.jpg"))
        assertEquals(6L, files.length("abc.jpg"))
        assertNull(files.length("missing.jpg"))
    }

    @Test
    fun `an empty file is not a photo on this phone`() {
        stored("empty.jpg", 0, text = "")
        assertNull(files.length("empty.jpg"))
    }

    @Test
    fun `sweep removes old files nothing points at and keeps the rest`() {
        val kept = stored("kept.jpg", 5 * hour)
        val orphan = stored("orphan.jpg", 5 * hour)
        val oldTemp = stored("half.jpg.tmp", 5 * hour)
        // Being added right now: the file exists before its row does.
        val fresh = stored("fresh.jpg", 60_000)
        val freshTemp = stored("writing.jpg.tmp", 1_000)
        // Not ours to judge.
        val stranger = stored("notes.txt", 5 * hour)
        // The camera app may still be in front after a process death, so scratch gets a day.
        val recentCapture = scratch("capture.jpg", 2 * hour)
        val staleCapture = scratch("old.jpg", 30 * hour)
        val staleCopy = scratch("old.src", 30 * hour)

        val removed = files.sweep(known = setOf("kept.jpg"), now = now)

        assertEquals(4, removed)
        assertTrue(kept.exists())
        assertFalse(orphan.exists())
        assertFalse(oldTemp.exists())
        assertTrue(fresh.exists())
        assertTrue(freshTemp.exists())
        assertTrue(stranger.exists())
        assertTrue(recentCapture.exists())
        assertFalse(staleCapture.exists())
        assertFalse(staleCopy.exists())
    }

    @Test
    fun `sweep on folders that do not exist yet does nothing`() {
        root.deleteRecursively()
        assertEquals(0, files.sweep(emptySet(), now))
    }
}
