package com.laksh.coffee.data.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import com.laksh.coffee.data.db.entity.newUid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId

/** A photo as it was written to filesDir/photos. */
data class SavedPhoto(
    val uid: String,
    val fileName: String,
    val widthPx: Int,
    val heightPx: Int,
    val bytes: Long,
    val takenAt: Long,
)

/** One capture the camera app is about to write: [name] finds the file again, [uri] is what the camera gets. */
class CameraTarget(val name: String, val uri: Uri)

/**
 * Turns a picked or captured image into the app's own copy: at most 1024 px on the long edge,
 * upright, JPEG at quality 82. The copy is re-encoded from pixels, so nothing of the original's
 * EXIF (location, camera, time) is carried into it. All work runs on Dispatchers.IO.
 */
class PhotoStore(
    context: Context,
    /** Tests point this at a folder of their own. */
    val files: PhotoFiles = PhotoFiles(File(context.filesDir, "photos"), File(context.cacheDir, "camera")),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val app = context.applicationContext

    /** Matches the provider in the manifest; the package name keeps debug and release apart. */
    private val authority = "${app.packageName}.photos"

    /** A fresh scratch file for the camera app to write into, shared with it for this one capture. */
    fun newCameraTarget(): CameraTarget? {
        val name = PhotoFiles.nameFor(newUid())
        val file = files.newScratch(name) ?: return null
        return runCatching { CameraTarget(name, FileProvider.getUriForFile(app, authority, file)) }.getOrNull()
    }

    /** Drops a capture that was cancelled or never arrived. */
    suspend fun discardCamera(name: String) = withContext(Dispatchers.IO) {
        files.scratch(name)?.delete()
        Unit
    }

    /** The capture the camera wrote into [name]; the scratch file is deleted either way. */
    suspend fun importCamera(name: String): SavedPhoto? = withContext(Dispatchers.IO) {
        val source = files.scratch(name) ?: return@withContext null
        try {
            encode(source)
        } finally {
            source.delete()
        }
    }

    /**
     * A picked image. It is copied in first (the picker's grant ends with the activity, and some
     * providers hand out streams that can only be read once), then re-encoded like a capture.
     */
    suspend fun importUri(uri: Uri): SavedPhoto? = withContext(Dispatchers.IO) {
        val source = files.newScratch(newUid() + ".src") ?: return@withContext null
        try {
            if (!copyIn(uri, source)) null else encode(source)
        } finally {
            source.delete()
        }
    }

    private fun copyIn(uri: Uri, target: File): Boolean = try {
        val input = app.contentResolver.openInputStream(uri) ?: return false
        input.use { stream ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    // A file this large is not a phone photo; stop before it fills the cache.
                    if (total > MAX_SOURCE_BYTES) return false
                    out.write(buffer, 0, n)
                }
                total > 0
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun encode(source: File): SavedPhoto? {
        if (!source.isFile || source.length() == 0L) return null
        var decoded: Bitmap? = null
        var upright: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = PhotoMath.sampleSize(bounds.outWidth, bounds.outHeight)
            }
            decoded = BitmapFactory.decodeFile(source.path, options) ?: return null

            // Read before the pixels are re-encoded: these two facts are all that is kept.
            val exif = runCatching { source.inputStream().use { ExifInterface(it) } }.getOrNull()
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL
            val exifDate = PhotoMath.exifDate(
                exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif?.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                ZoneId.systemDefault(),
            )

            upright = stand(decoded, PhotoMath.turn(orientation))
            val uid = newUid()
            val temp = files.temp(uid) ?: return null
            val written = FileOutputStream(temp).use { out ->
                val ok = upright.compress(Bitmap.CompressFormat.JPEG, PhotoMath.JPEG_QUALITY, out)
                out.fd.sync()
                ok
            }
            if (!written) {
                temp.delete()
                return null
            }
            if (!files.commit(temp, uid)) return null
            val fileName = PhotoFiles.nameFor(uid)
            return SavedPhoto(
                uid = uid,
                fileName = fileName,
                widthPx = upright.width,
                heightPx = upright.height,
                bytes = files.length(fileName) ?: 0L,
                takenAt = PhotoMath.takenAt(exifDate, now()),
            )
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            // A photo the phone can't hold in memory is reported like one it can't read.
            return null
        } finally {
            if (upright != null && upright !== decoded) upright.recycle()
            decoded?.recycle()
        }
    }

    /** Stands the image upright and brings the long edge down to 1024 px, in one pass over the pixels. */
    private fun stand(src: Bitmap, turn: PhotoMath.Turn): Bitmap {
        val (w, h) = PhotoMath.fit(src.width, src.height)
        val k = w.toFloat() / src.width
        val matrix = Matrix()
        if (turn.degrees != 0) matrix.postRotate(turn.degrees.toFloat())
        if (turn.mirror) matrix.postScale(-1f, 1f)
        if (w != src.width || h != src.height) matrix.postScale(k, k)
        val turned = if (matrix.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (!turned.hasAlpha()) return turned
        // JPEG has no transparency; without this a cut-out PNG would come out on black.
        val flat = createBitmap(turned.width, turned.height)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(turned, 0f, 0f, null)
        }
        if (turned !== src) turned.recycle()
        return flat
    }

    /** The stored JPEG's bytes, or null when the file is not on this phone or is not a JPEG. */
    suspend fun read(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = files.stored(fileName)?.takeIf { it.isFile } ?: return@withContext null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
        // FF D8: never hand on bytes labelled image/jpeg that are something else.
        if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) null else bytes
    }

    suspend fun delete(fileNames: List<String>) = withContext(Dispatchers.IO) {
        fileNames.forEach { files.delete(it) }
    }

    /** See [PhotoFiles.sweep]. [known] is every fileName that still has a row. */
    suspend fun sweep(known: Set<String>): Int = withContext(Dispatchers.IO) {
        files.sweep(known, now())
    }

    companion object {
        private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
    }
}
