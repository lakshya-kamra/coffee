package com.laksh.coffee.data.photos

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

/** The arithmetic of storing a photo, kept free of Android so it can be tested on the JVM. */
object PhotoMath {

    /** Long edge of a stored photo, in pixels. Enough to read a coffee bed; small enough to send. */
    const val MAX_EDGE = 1024
    const val JPEG_QUALITY = 82
    const val THUMB_EDGE = 256

    /** Largest power of two that still leaves the long edge at or above [target]; 1 for a small image. */
    fun sampleSize(width: Int, height: Int, target: Int = MAX_EDGE): Int {
        val long = max(width, height)
        var s = 1
        while (long / (s * 2) >= target) s *= 2
        return s
    }

    /** [width] x [height] shrunk so the long edge is at most [maxEdge]. Never enlarges. */
    fun fit(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= maxEdge || long <= 0) return width to height
        val k = maxEdge.toDouble() / long
        return max(1, (width * k).roundToInt()) to max(1, (height * k).roundToInt())
    }

    /** How to stand an image upright: rotate clockwise by [degrees], then mirror left to right if [mirror]. */
    data class Turn(val degrees: Int, val mirror: Boolean) {
        val swapsSides: Boolean get() = degrees == 90 || degrees == 270
    }

    /** [orientation] is the EXIF Orientation tag (1 to 8); anything else means "as stored". */
    fun turn(orientation: Int): Turn = when (orientation) {
        2 -> Turn(0, true)
        3 -> Turn(180, false)
        4 -> Turn(180, true)
        5 -> Turn(90, true)
        6 -> Turn(90, false)
        7 -> Turn(270, true)
        8 -> Turn(270, false)
        else -> Turn(0, false)
    }

    private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** 2000-01-01T00:00:00Z. An older date on a photo is a camera with no clock set. */
    private const val EARLIEST = 946_684_800_000L
    private const val DAY_MS = 86_400_000L

    /**
     * EXIF DateTimeOriginal ("2026:11:17 08:14:02") as epoch milliseconds. [offset] is
     * OffsetTimeOriginal ("+05:30") when the camera wrote one; otherwise the time is read in [zone].
     */
    fun exifDate(text: String?, offset: String?, zone: ZoneId): Long? {
        val t = text?.trim()?.takeIf { it.length >= 19 }?.take(19) ?: return null
        val local = runCatching { LocalDateTime.parse(t, EXIF_DATE) }.getOrNull() ?: return null
        val fixed = offset?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        val instant = if (fixed != null) local.toInstant(fixed) else local.atZone(zone).toInstant()
        return instant.toEpochMilli()
    }

    /** The photo's own date when it is believable, else [now]. */
    fun takenAt(exif: Long?, now: Long): Long = exif?.takeIf { it in EARLIEST..(now + DAY_MS) } ?: now
}
