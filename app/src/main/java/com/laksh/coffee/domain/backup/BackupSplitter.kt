package com.laksh.coffee.domain.backup

import java.io.Reader

/**
 * Reads a backup file as a stream and takes the photos out of it, so the importer never holds
 * the whole file (photos are most of it) in memory at once.
 *
 * It copies everything except the elements of the top-level "photos" array into the returned
 * text, which stays valid JSON with "photos": []; each element of that array is handed to
 * [onPhoto] as its own JSON text, one at a time. Only the structure is followed here (strings,
 * escapes, brackets); the real parsing is the codec's.
 */
object BackupSplitter {

    /** One photo with its base64 is a few hundred kB; anything this large is not one. */
    const val MAX_PHOTO_CHARS = 12 * 1024 * 1024

    /** The rest of the document may not be bigger than this (thousands of brews fit many times). */
    const val MAX_BODY_CHARS = 48 * 1024 * 1024

    class TooBig : Exception()

    /**
     * @param onPhoto the JSON text of one element of "photos", or null for an element that was
     *   not an object or was larger than [MAX_PHOTO_CHARS].
     * @return the document without its photos.
     */
    fun split(input: Reader, onPhoto: (String?) -> Unit): String {
        val body = StringBuilder()
        val element = StringBuilder()
        val key = StringBuilder()
        val buffer = CharArray(64 * 1024)

        var depth = 0
        var inString = false
        var escaped = false
        // The string being read sits directly inside the top-level object, so it may be a key.
        var topLevelString = false
        var lastTopLevelString: String? = null
        // "photos" was just read as a key and its value has not started yet.
        var photosNext = false
        // Depth of the photos array's elements while inside it, else -1.
        var photoDepth = -1
        var inElement = false
        var elementTooBig = false

        fun emit(c: Char) {
            if (photoDepth < 0) {
                if (body.length >= MAX_BODY_CHARS) throw TooBig()
                body.append(c)
            } else if (inElement) {
                if (element.length < MAX_PHOTO_CHARS) element.append(c) else elementTooBig = true
            }
            // Between elements (commas, spaces, stray scalars): dropped.
        }

        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            for (i in 0 until n) {
                val c = buffer[i]
                if (inString) {
                    emit(c)
                    // Collected raw: a key written with an escape in it keeps its backslash here
                    // and so never reads as "photos". The apps do not write keys that way.
                    if (topLevelString && (escaped || c != '"')) key.append(c)
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> {
                            inString = false
                            if (topLevelString) {
                                lastTopLevelString = key.toString()
                                topLevelString = false
                            }
                        }
                    }
                    continue
                }
                when (c) {
                    '"' -> {
                        inString = true
                        topLevelString = depth == 1 && photoDepth < 0
                        if (topLevelString) key.setLength(0)
                        emit(c)
                    }
                    ':' -> {
                        if (depth == 1 && photoDepth < 0) photosNext = lastTopLevelString == "photos"
                        lastTopLevelString = null
                        emit(c)
                    }
                    '{', '[' -> {
                        if (photoDepth >= 0 && depth == photoDepth - 1 && !inElement) {
                            // A new element of the photos array.
                            inElement = true
                            elementTooBig = false
                            element.setLength(0)
                        }
                        val startsPhotos = photosNext && c == '[' && depth == 1
                        photosNext = false
                        emit(c)
                        depth++
                        if (startsPhotos) photoDepth = depth + 1
                    }
                    '}', ']' -> {
                        depth--
                        if (photoDepth >= 0 && depth == photoDepth - 2) {
                            // The photos array itself closes.
                            photoDepth = -1
                            emit(c)
                        } else {
                            emit(c)
                            if (photoDepth >= 0 && inElement && depth == photoDepth - 1) {
                                inElement = false
                                val text = element.toString()
                                element.setLength(0)
                                onPhoto(if (elementTooBig || !text.startsWith("{")) null else text)
                            }
                        }
                    }
                    else -> {
                        if (depth == 1 && photoDepth < 0 && !c.isWhitespace()) {
                            // A scalar value follows the key: whatever it is, it is not the array.
                            if (c != ',') photosNext = false
                        }
                        emit(c)
                    }
                }
            }
        }
        return body.toString()
    }
}
