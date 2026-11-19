package com.laksh.coffee.domain.backup

import java.io.ByteArrayOutputStream

/**
 * A photo arriving in a backup is a file someone else wrote. The app's promise is that stored
 * photos carry no location or camera details, so the image is walked segment by segment and its
 * metadata is dropped on the way in: EXIF and XMP (APP1), IPTC (APP13), comments, every other
 * vendor block, and anything stuck on after the end of the image (a second picture, a clip).
 * The picture data itself is copied byte for byte; nothing is decoded or re-compressed.
 */
object JpegBytes {

    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val APP0 = 0xE0
    private const val APP2 = 0xE2
    private const val APP14 = 0xEE
    private const val COM = 0xFE

    fun isJpeg(b: ByteArray): Boolean = b.size >= 4 && u(b, 0) == 0xFF && u(b, 1) == SOI && u(b, 2) == 0xFF

    /** The image without metadata, or null when [b] is not a JPEG whose structure can be followed. */
    fun withoutMetadata(b: ByteArray): ByteArray? {
        if (!isJpeg(b)) return null
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        var pos = 2
        var sawScan = false
        while (pos < b.size) {
            if (u(b, pos) != 0xFF) return null
            // Any number of 0xFF fill bytes may come before a marker.
            var m = pos + 1
            while (m < b.size && u(b, m) == 0xFF) m++
            if (m >= b.size) break
            val marker = u(b, m)
            val next = m + 1
            when {
                marker == EOI -> {
                    out.write(0xFF)
                    out.write(EOI)
                    return if (sawScan) out.toByteArray() else null
                }
                marker == SOI || marker == 0x00 -> return null
                // RSTn and TEM stand alone.
                marker in 0xD0..0xD7 || marker == 0x01 -> {
                    out.write(0xFF)
                    out.write(marker)
                    pos = next
                }
                else -> {
                    if (next + 2 > b.size) return null
                    val length = (u(b, next) shl 8) or u(b, next + 1)
                    val end = next + length
                    if (length < 2 || end > b.size) return null
                    if (keep(marker, b, next + 2, end)) {
                        out.write(0xFF)
                        out.write(marker)
                        out.write(b, next, length)
                    }
                    pos = end
                    if (marker == SOS) {
                        sawScan = true
                        pos = copyScan(b, pos, out)
                    }
                }
            }
        }
        // The file stops without an end marker: keep what there is, properly closed.
        if (!sawScan) return null
        out.write(0xFF)
        out.write(EOI)
        return out.toByteArray()
    }

    /** Copies compressed picture data from [start] up to the next real marker; returns where that marker is. */
    private fun copyScan(b: ByteArray, start: Int, out: ByteArrayOutputStream): Int {
        var i = start
        while (i < b.size) {
            if (u(b, i) == 0xFF && i + 1 < b.size) {
                val n = u(b, i + 1)
                // 0x00 is a stuffed 0xFF, RSTn belongs to the scan, 0xFF is fill.
                if (n != 0x00 && n !in 0xD0..0xD7 && n != 0xFF) break
            }
            i++
        }
        out.write(b, start, i - start)
        return i
    }

    private fun keep(marker: Int, b: ByteArray, from: Int, to: Int): Boolean = when {
        marker == COM -> false
        // JFIF header, and the Adobe block some decoders need to read the colours right.
        marker == APP0 || marker == APP14 -> true
        // An ICC colour profile is the one APP2 block that changes how the picture looks.
        marker == APP2 -> startsWith(b, from, to, "ICC_PROFILE")
        marker in 0xE0..0xEF -> false
        // Tables, frame and scan headers: the picture.
        else -> true
    }

    private fun startsWith(b: ByteArray, from: Int, to: Int, text: String): Boolean {
        if (to - from < text.length) return false
        for (i in text.indices) if (b[from + i] != text[i].code.toByte()) return false
        return true
    }

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}
