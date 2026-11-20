package com.laksh.coffee.data.backup

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laksh.coffee.data.ai.AiSettingsStore
import com.laksh.coffee.data.db.CoffeeDatabase
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.data.photos.PhotoFiles
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.data.prefs.SettingsStore
import com.laksh.coffee.domain.backup.BackupApp
import com.laksh.coffee.domain.backup.BackupKind
import com.laksh.coffee.domain.backup.Counts
import com.laksh.coffee.domain.backup.JpegBytes
import com.laksh.coffee.domain.backup.MergeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Backup and restore on a real device, against real Room databases and real files: the move
 * from one install to another (export on "phone A", import on "phone B"), the 1.x export, and
 * "Replace everything". Each phone here is an in-memory database and a photo folder of its own
 * under the cache, so the installed app's brews and photos are never touched. The app's settings
 * are read before each test and put back after it.
 */
@RunWith(AndroidJUnit4::class)
class BackupImportExportTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = SettingsStore(context)
    private val ai = AiSettingsStore(context)
    private lateinit var root: File
    private lateinit var settingsBefore: Settings
    private val phones = ArrayList<Phone>()

    /** One install: its database, its photo folder, and the two halves of backup over them. */
    private inner class Phone(name: String) {
        val db: CoffeeDatabase = Room.inMemoryDatabaseBuilder(context, CoffeeDatabase::class.java).build()
        val files = PhotoFiles(File(root, "$name/photos"), File(root, "$name/camera"))
        val importer = BackupImporter(context, db, files, settings, ai, scope)
        val exporter = BackupExporter(context, db, files, settings, ai, BackupApp("android", "2.0.0-test", "com.laksh.coffee.$name", 4))

        suspend fun counts() = Counts(
            brews = db.brewLogDao().all().size, beans = db.beanDao().all().size, grinders = db.grinderDao().all().size,
            recipes = db.savedRecipeDao().all().size, analyses = db.analysisDao().all().size, photos = db.photoDao().all().size,
        )

        fun photoFiles(): List<File> = files.photosDir.listFiles()?.toList().orEmpty()

        suspend fun restore(file: File, options: ImportOptions = ImportOptions()): ImportResult {
            val read = importer.read(file) as BackupImporter.ReadResult.Ok
            return importer.import(file, read.doc, read.preview.kind, options)
        }
    }

    private fun phone(name: String) = Phone(name).also { phones += it }

    private fun asset(name: String): File = File(root, name).also { target ->
        testAssets.open(name).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
    }

    @Before
    fun setUp() {
        root = File(context.cacheDir, "backup-test-${System.nanoTime()}").also { it.mkdirs() }
        settingsBefore = runBlocking { settings.settings.first() }
    }

    @After
    fun tearDown() {
        runBlocking {
            settings.restore { settingsBefore }
            val now = settings.settings.first()
            (now.pendingNotices - settingsBefore.pendingNotices).forEach { settings.removeNotice(it) }
        }
        phones.forEach { it.db.close() }
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun theSharedFixtureRestoresWithItsPhotoAndASecondRestoreChangesNothing() = runBlocking {
        val file = asset("backup-v2-sample.json")
        val phone = phone("a")

        val read = phone.importer.read(file) as BackupImporter.ReadResult.Ok
        assertEquals(BackupKind.V2, read.preview.kind)
        assertEquals(Counts(brews = 2, beans = 1, grinders = 1, recipes = 1, analyses = 3, photos = 1), read.preview.counts)
        assertEquals(1, read.preview.photosWithData)
        assertEquals(0, read.preview.problems)
        assertEquals("com.laksh.coffee.debug", read.preview.packageName)
        assertTrue(read.preview.hasSettings && read.preview.hasAi)
        assertNull("the preview holds no image", read.doc.photos.single().data)
        assertEquals("reading changes nothing", Counts(), phone.counts())

        val first = phone.importer.import(file, read.doc, read.preview.kind, ImportOptions())
        assertEquals(read.preview.counts, first.added)
        assertEquals(emptyList<String>(), first.skipped)
        assertEquals(read.preview.counts, phone.counts())

        // The photo is a real, decodable JPEG under the name its row points at; no scratch is left.
        val row = phone.db.photoDao().all().single()
        val stored = phone.files.stored(row.fileName)!!
        assertTrue(stored.isFile)
        val bytes = stored.readBytes()
        assertTrue(JpegBytes.isJpeg(bytes))
        assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertEquals(bytes.size.toLong(), row.bytes)
        assertEquals(listOf(stored.name), phone.photoFiles().map { it.name })
        // Joined by this phone's ids.
        val brew = phone.db.brewLogDao().all().first()
        assertEquals(brew.id, row.brewLogId)
        assertEquals(phone.db.beanDao().all().single().id, brew.beanId)
        assertEquals(phone.db.grinderDao().all().single().id, brew.grinderId)
        assertEquals("android", phone.db.analysisDao().all().first().origin)

        val second = phone.restore(file)
        assertEquals(0, second.added.total)
        assertEquals(0, second.replaced.total)
        // Every row in the file is already here, the photo included, so every one is kept.
        assertEquals(read.preview.counts, second.kept)
        assertEquals(read.preview.counts, phone.counts())
        assertEquals("no second copy of the photo", 1, phone.photoFiles().size)
    }

    @Test
    fun aBackupMadeOnOnePhoneRestoresOnAnother() = runBlocking {
        val a = phone("debug")
        a.restore(asset("backup-v2-sample.json"))

        // Export on A, with photos, exactly as "Back up everything" does.
        val backup = File(root, "coffee-backup.json")
        val written = a.exporter.write(FileOutputStream(backup), includePhotos = true)
        assertEquals(a.counts(), written.counts)
        assertEquals(1, written.photosWithData)
        val text = backup.readText()
        assertTrue(text.contains("\"packageName\": \"com.laksh.coffee.debug\""))
        assertFalse("no key, hint or key host in a backup", text.contains("key_", ignoreCase = true))

        // Import on B: same counts, same rows apart from the local ids, same photo bytes.
        val b = phone("release")
        val result = b.restore(backup)
        assertEquals(a.counts(), result.added)
        assertEquals(a.counts(), b.counts())
        assertEquals(
            a.db.brewLogDao().all().map { it.copy(id = 0, beanId = null, grinderId = null) },
            b.db.brewLogDao().all().map { it.copy(id = 0, beanId = null, grinderId = null) },
        )
        assertEquals(a.db.beanDao().all().map { it.copy(id = 0) }, b.db.beanDao().all().map { it.copy(id = 0) })
        assertEquals(a.db.grinderDao().all().map { it.copy(id = 0) }, b.db.grinderDao().all().map { it.copy(id = 0) })
        assertEquals(a.db.savedRecipeDao().all(), b.db.savedRecipeDao().all())
        assertEquals(
            a.db.analysisDao().all().map { it.copy(id = 0, brewLogId = null) },
            b.db.analysisDao().all().map { it.copy(id = 0, brewLogId = null) },
        )
        val photoA = a.db.photoDao().all().single()
        val photoB = b.db.photoDao().all().single()
        assertEquals(photoA.copy(id = 0, brewLogId = null), photoB.copy(id = 0, brewLogId = null))
        assertArrayEquals(a.files.stored(photoA.fileName)!!.readBytes(), b.files.stored(photoB.fileName)!!.readBytes())

        // Re-importing on B adds nothing, and B's own backup restores back on A as a no-op.
        assertEquals(0, b.restore(backup).added.total)
        val back = File(root, "back.json")
        b.exporter.write(FileOutputStream(back), includePhotos = true)
        val home = a.restore(back)
        assertEquals(0, home.added.total + home.replaced.total)
    }

    @Test
    fun aBackupSavedWithoutPhotosBringsEverythingElse() = runBlocking {
        val a = phone("a")
        a.restore(asset("backup-v2-sample.json"))
        val backup = File(root, "no-photos.json")
        val written = a.exporter.write(FileOutputStream(backup), includePhotos = false, includeAnalyses = true)
        assertEquals(0, written.photosWithData)

        val b = phone("b")
        val read = b.importer.read(backup) as BackupImporter.ReadResult.Ok
        assertEquals(1, read.preview.counts.photos)
        assertEquals(0, read.preview.photosWithData)
        val result = b.importer.import(backup, read.doc, read.preview.kind, ImportOptions())
        assertEquals(0, result.added.photos)
        assertEquals(listOf("1 photo isn't in the file"), result.skipped)
        assertEquals(2, b.counts().brews)
        assertTrue(b.photoFiles().isEmpty())
    }

    @Test
    fun aRowThatCameAcrossWithoutItsImageGetsItBackFromTheBackup() = runBlocking {
        val a = phone("a")
        a.restore(asset("backup-v2-sample.json"))
        val backup = File(root, "coffee-backup.json")
        a.exporter.write(FileOutputStream(backup), includePhotos = true)
        val row = a.db.photoDao().all().single()
        val image = a.files.stored(row.fileName)!!.readBytes()
        // What Android's own backup or a device transfer leaves: the database, no photo folder.
        assertTrue(a.files.delete(row.fileName))
        assertTrue(a.photoFiles().isEmpty())

        // "Add to this phone", the default: the row is already here, its image is not.
        val result = a.restore(backup)
        assertEquals(1, result.added.photos)
        assertEquals(0, result.kept.photos)
        val after = a.db.photoDao().all().single()
        assertEquals(row.id, after.id)
        assertEquals(row.uid, after.uid)
        assertEquals(row.brewLogId, after.brewLogId)
        assertArrayEquals(image, a.files.stored(after.fileName)!!.readBytes())
        assertEquals(listOf(after.fileName), a.photoFiles().map { it.name })

        // Once it is back, the same file again changes nothing.
        val again = a.restore(backup)
        assertEquals(0, again.added.total + again.replaced.total)
        assertEquals(1, a.photoFiles().size)
    }

    @Test
    fun aBackupNeverGrowsPastWhatCanBeReadBack() = runBlocking {
        val a = phone("a")
        a.restore(asset("backup-v2-sample.json"))
        val full = File(root, "full.json")
        val all = a.exporter.write(FileOutputStream(full), includePhotos = true)
        assertEquals(1, all.photosWithData)
        assertEquals(0, all.photosLeftOut)

        // A limit just under the whole file: the image stays out, its record is still listed.
        val capped = File(root, "capped.json")
        val written = a.exporter.write(FileOutputStream(capped), includePhotos = true, maxBytes = full.length() - 1)
        assertEquals(0, written.photosWithData)
        assertEquals(1, written.photosLeftOut)
        assertEquals(1, written.counts.photos)
        assertTrue(capped.length() < full.length())
        val read = a.importer.read(capped) as BackupImporter.ReadResult.Ok
        assertEquals(1, read.preview.counts.photos)
        assertEquals(0, read.preview.photosWithData)
    }

    @Test
    fun theOneXExportIsNotAddedTwiceUnderOtherUids() = runBlocking {
        val file = asset("coffee-brew-log-1-sample.json")
        val phone = phone("a")
        phone.restore(file)
        val before = phone.counts()
        // What the upgrade from 1.x leaves: the same rows under uids it made up.
        phone.db.brewLogDao().all().forEach { phone.db.brewLogDao().update(it.copy(uid = "upgraded-brew-${it.id}")) }
        phone.db.beanDao().all().forEach { phone.db.beanDao().update(it.copy(uid = "upgraded-bean-${it.id}")) }
        phone.db.grinderDao().all().forEach { phone.db.grinderDao().update(it.copy(uid = "upgraded-grinder-${it.id}")) }

        val result = phone.restore(file)
        assertEquals(0, result.added.total)
        assertEquals(before, phone.counts())
    }

    @Test
    fun theOneXExportImportsLinksToTheCatalogAndAsksAboutTheScale() = runBlocking {
        val file = asset("coffee-brew-log-1-sample.json")
        val phone = phone("a")
        val read = phone.importer.read(file) as BackupImporter.ReadResult.Ok
        assertEquals(BackupKind.Legacy1, read.preview.kind)
        assertEquals(Counts(brews = 4, beans = 3, grinders = 3), read.preview.counts)
        assertFalse(read.preview.hasSettings || read.preview.hasAi)

        val result = phone.importer.import(file, read.doc, read.preview.kind, ImportOptions())
        assertEquals(Counts(brews = 4, beans = 3, grinders = 3), result.added)

        val kUltra = phone.db.grinderDao().all().single { it.model == "K-Ultra" }
        assertEquals("1zpresso-k-ultra", kUltra.catalogId)
        assertEquals("the 1.x scale is kept until the owner accepts the catalog's", 10, kUltra.clicksPerRotation)
        assertEquals(SettingStyle.Clicks, kUltra.settingStyle)
        assertTrue("grinder-scale:${kUltra.uid}" in settings.settings.first().pendingNotices)
        assertEquals("baratza-encore", phone.db.grinderDao().all().single { it.model == "Encore" }.catalogId)
        assertEquals("colombia", phone.db.beanDao().all().single { it.origin == "CO" }.originId)
        assertEquals(77, phone.db.brewLogDao().all().first().grindClicks)

        // Someone who brought data has used the app: no setup screens for them.
        assertTrue(settings.settings.first().onboardingDone)

        val again = phone.restore(file)
        assertEquals(0, again.added.total)
        assertEquals(10, again.kept.total)
    }

    @Test
    fun replaceEverythingRemovesWhatWasHereIncludingOldPhotoFiles() = runBlocking {
        val phone = phone("a")
        phone.restore(asset("backup-v2-sample.json"))
        val oldPhoto = phone.photoFiles().single()

        val result = phone.restore(asset("coffee-brew-log-1-sample.json"), ImportOptions(mode = MergeMode.Replace))
        assertEquals(Counts(brews = 4, beans = 3, grinders = 3), result.added)
        assertEquals(Counts(brews = 4, beans = 3, grinders = 3), phone.counts())
        assertFalse(oldPhoto.exists())
        assertTrue(phone.photoFiles().isEmpty())
    }

    @Test
    fun aFileThatIsNotABackupIsRefusedAndNothingChanges() = runBlocking {
        val phone = phone("a")
        fun file(name: String, text: String) = File(root, name).also { it.writeText(text) }
        fun error(f: File) = runBlocking { (phone.importer.read(f) as BackupImporter.ReadResult.Error).error }

        assertEquals(ImportError.NotJson, error(file("a.json", "this is a shopping list")))
        assertEquals(ImportError.NotBackup, error(file("b.json", "{\"hello\": 1}")))
        assertEquals(ImportError.TooNew, error(file("c.json", "{\"format\":\"coffee-backup\",\"version\":3}")))
        assertEquals(Counts(), phone.counts())
    }

    /**
     * The owner's own 1.x export through the real importer and a real database. The file is
     * private and not in the repository: push it to the debug app's files folder by hand
     * (adb push + run-as cp to files/owner-export.json) to run this; otherwise it is skipped.
     */
    @Test
    fun theOwnersRealExportImportsOnADevice() = runBlocking {
        val file = File(context.filesDir, "owner-export.json")
        assumeTrue("owner-export.json has not been pushed to this device", file.isFile)
        val phone = phone("owner")
        val read = phone.importer.read(file) as BackupImporter.ReadResult.Ok
        assertEquals(BackupKind.Legacy1, read.preview.kind)
        assertEquals(0, read.preview.problems)

        val result = phone.importer.import(file, read.doc, read.preview.kind, ImportOptions())
        assertEquals(read.preview.counts, result.added)
        assertEquals(read.preview.counts, phone.counts())
        val grinder = phone.db.grinderDao().all().single()
        assertEquals("1zpresso-k-ultra", grinder.catalogId)
        assertEquals(10, grinder.clicksPerRotation)
        assertEquals(read.doc.brews.map { it.grindClicks }, phone.db.brewLogDao().all().sortedBy { it.id }.map { it.grindClicks })

        val again = phone.restore(file)
        assertEquals(0, again.added.total)
        assertEquals(read.preview.counts.total, again.kept.total)
    }
}
