package com.laksh.coffee.data.photos

import java.io.File

/**
 * Where photo files live, and the only code that deletes them. Plain java.io, so it is tested on
 * the JVM. Every name is checked before it becomes a path: a row restored from a backup must not
 * be able to point a delete at another file of the app.
 *
 * [photosDir] is filesDir/photos (kept); [cameraDir] is cacheDir/camera (scratch: a capture the
 * camera app writes, or a picked image copied in before it is re-encoded).
 */
class PhotoFiles(val photosDir: File, val cameraDir: File) {

    /** The stored file for a row's fileName, or null when the name is not one this app writes. */
    fun stored(fileName: String): File? = if (STORED_NAME.matches(fileName)) File(photosDir, fileName) else null

    fun exists(fileName: String): Boolean = stored(fileName)?.isFile == true

    /** Size on disk, or null when the file is not on this phone. A stat, not a read. */
    fun length(fileName: String): Long? = stored(fileName)?.takeIf { it.isFile }?.length()?.takeIf { it > 0 }

    /** Where a new photo is written before it is complete. */
    fun temp(uid: String): File? {
        val name = nameFor(uid)
        if (!STORED_NAME.matches(name)) return null
        photosDir.mkdirs()
        return File(photosDir, name + TMP)
    }

    /** Makes a finished temp file the stored photo. False when the rename did not happen. */
    fun commit(temp: File, uid: String): Boolean {
        val target = stored(nameFor(uid)) ?: return false
        if (temp.renameTo(target)) return true
        temp.delete()
        return false
    }

    /** A scratch file by name, or null for a name this app did not make. */
    fun scratch(name: String): File? = if (SCRATCH_NAME.matches(name)) File(cameraDir, name) else null

    fun newScratch(name: String): File? {
        cameraDir.mkdirs()
        return scratch(name)
    }

    /** True when the file is gone afterwards (deleted now, or never there). */
    fun delete(fileName: String): Boolean {
        val f = stored(fileName) ?: return false
        return !f.exists() || f.delete()
    }

    /**
     * Removes what nothing points at: stored photos with no row in [known], half-written temp
     * files, and old camera scratch. Anything touched in the last [graceMs] is left alone, because
     * a photo being added right now has a file before it has a row. Returns how many were removed.
     */
    fun sweep(known: Set<String>, now: Long, graceMs: Long = GRACE_MS): Int {
        var removed = 0
        photosDir.listFiles()?.forEach { f ->
            if (!f.isFile || now - f.lastModified() < graceMs) return@forEach
            val orphan = f.name.endsWith(TMP) || (STORED_NAME.matches(f.name) && f.name !in known)
            if (orphan && f.delete()) removed++
        }
        cameraDir.listFiles()?.forEach { f ->
            // A capture can outlive the process (the camera app is in front), so scratch gets a day.
            if (f.isFile && now - f.lastModified() >= SCRATCH_KEEP_MS && f.delete()) removed++
        }
        return removed
    }

    companion object {
        const val TMP = ".tmp"
        const val GRACE_MS = 60 * 60 * 1000L
        const val SCRATCH_KEEP_MS = 24 * 60 * 60 * 1000L

        /** "<uid>.jpg". Uids are UUIDs here; anything short and plain is accepted so another platform's ids fit. */
        private val STORED_NAME = Regex("^[A-Za-z0-9_-]{1,64}\\.jpg$")
        private val SCRATCH_NAME = Regex("^[A-Za-z0-9_-]{1,64}\\.(jpg|src)$")

        fun nameFor(uid: String): String = "$uid.jpg"
    }
}
