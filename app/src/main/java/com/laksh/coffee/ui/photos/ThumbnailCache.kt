package com.laksh.coffee.ui.photos

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.scale
import com.laksh.coffee.CoffeeApp
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.photos.PhotoMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What a tile can show for a photo. */
sealed interface PhotoImage {
    data object Loading : PhotoImage

    /** The row is here but its file is not (a restore brings rows, not files). */
    data object Missing : PhotoImage

    class Ready(val bitmap: ImageBitmap) : PhotoImage
}

/**
 * Small decoded copies of stored photos, keyed by the photo's uid (a stored photo never changes,
 * so an entry is never stale). No image library: the files are local JPEGs of a known size.
 */
object ThumbnailCache {

    private const val BUDGET_BYTES = 8 * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(BUDGET_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    // Two at a time: a strip of six must not take every IO thread from Room.
    private val decoder = Dispatchers.IO.limitedParallelism(2)

    fun peek(uid: String): ImageBitmap? = cache.get(uid)

    fun evict(uid: String) {
        cache.remove(uid)
    }

    /** The 256 px thumbnail, from memory or decoded off the main thread. Null when the file is missing or unreadable. */
    suspend fun load(uid: String, file: File?): ImageBitmap? {
        cache.get(uid)?.let { return it }
        if (file == null) return null
        return withContext(decoder) {
            cache.get(uid) ?: decode(file, PhotoMath.THUMB_EDGE)?.also { cache.put(uid, it) }
        }
    }

    /** The whole stored photo (at most 1024 px), for the viewer. Not cached: one is open at a time. */
    suspend fun full(file: File?): ImageBitmap? {
        if (file == null) return null
        return withContext(decoder) { decode(file, PhotoMath.MAX_EDGE) }
    }

    private fun decode(file: File, edge: Int): ImageBitmap? = try {
        if (!file.isFile) {
            null
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = PhotoMath.sampleSize(bounds.outWidth, bounds.outHeight, edge)
                }
                BitmapFactory.decodeFile(file.path, options)?.let { raw ->
                    val (w, h) = PhotoMath.fit(raw.width, raw.height, edge)
                    val sized = if (w == raw.width && h == raw.height) raw else raw.scale(w, h)
                    if (sized !== raw) raw.recycle()
                    sized.asImageBitmap()
                }
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

/** Where a photo's file is, through the app's one photo repository. */
@Composable
internal fun rememberPhotoFile(photo: PhotoEntity): File? {
    val app = LocalContext.current.applicationContext as? CoffeeApp
    return app?.photos?.file(photo)
}

/** The thumbnail for [photo]. A cached one is there on the first frame, so a strip never flickers on return. */
@Composable
fun rememberThumbnail(photo: PhotoEntity): State<PhotoImage> {
    val file = rememberPhotoFile(photo)
    val cached = ThumbnailCache.peek(photo.uid)
    return produceState<PhotoImage>(
        initialValue = cached?.let { PhotoImage.Ready(it) } ?: PhotoImage.Loading,
        photo.uid,
        photo.fileName,
    ) {
        value = ThumbnailCache.load(photo.uid, file)?.let { PhotoImage.Ready(it) } ?: PhotoImage.Missing
    }
}

/** The full stored photo for the viewer, showing the thumbnail until it is decoded. */
@Composable
fun rememberFullPhoto(photo: PhotoEntity): State<PhotoImage> {
    val file = rememberPhotoFile(photo)
    val cached = ThumbnailCache.peek(photo.uid)
    return produceState<PhotoImage>(
        initialValue = cached?.let { PhotoImage.Ready(it) } ?: PhotoImage.Loading,
        photo.uid,
        photo.fileName,
    ) {
        value = ThumbnailCache.full(file)?.let { PhotoImage.Ready(it) } ?: PhotoImage.Missing
    }
}
