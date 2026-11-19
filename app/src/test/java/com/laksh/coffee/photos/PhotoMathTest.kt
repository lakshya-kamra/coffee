package com.laksh.coffee.photos

import com.laksh.coffee.data.photos.PhotoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The numbers behind a stored photo: how far it is sampled down, its final size, which way is up, and when it was taken. */
class PhotoMathTest {

    @Test
    fun `sampling keeps the long edge at or above 1024`() {
        // A 12 MP phone photo: 4000 / 2 = 2000 still clears 1024, 4000 / 4 = 1000 would not.
        assertEquals(2, PhotoMath.sampleSize(4000, 3000))
        assertEquals(2, PhotoMath.sampleSize(3000, 4000))
        // 50 MP: 8160 / 4 = 2040, / 8 = 1020.
        assertEquals(4, PhotoMath.sampleSize(8160, 6120))
        assertEquals(8, PhotoMath.sampleSize(8192, 100))
        assertEquals(1, PhotoMath.sampleSize(2047, 1000))
        assertEquals(2, PhotoMath.sampleSize(2048, 1000))
        assertEquals(1, PhotoMath.sampleSize(640, 480))
        assertEquals(1, PhotoMath.sampleSize(0, 0))
    }

    @Test
    fun `every sampled size still scales down to exactly 1024 on the long edge`() {
        for (long in listOf(1024, 1025, 1500, 2047, 2048, 4000, 4032, 8160, 12000)) {
            val s = PhotoMath.sampleSize(long, long * 3 / 4)
            val decodedLong = long / s
            assertTrue("$long sampled by $s", decodedLong >= 1024)
            val (w, h) = PhotoMath.fit(decodedLong, decodedLong * 3 / 4)
            assertEquals(1024, w)
            assertTrue(h in 767..768)
        }
    }

    @Test
    fun `fit never enlarges and keeps the shape`() {
        assertEquals(800 to 600, PhotoMath.fit(800, 600))
        assertEquals(1024 to 768, PhotoMath.fit(2000, 1500))
        assertEquals(768 to 1024, PhotoMath.fit(1500, 2000))
        assertEquals(1024 to 1024, PhotoMath.fit(1024, 1024))
        // A panorama keeps at least one pixel of height.
        assertEquals(1024 to 1, PhotoMath.fit(20000, 3))
        assertEquals(256 to 192, PhotoMath.fit(1024, 768, PhotoMath.THUMB_EDGE))
    }

    @Test
    fun `the eight exif orientations stand the image upright`() {
        assertEquals(PhotoMath.Turn(0, false), PhotoMath.turn(1))
        assertEquals(PhotoMath.Turn(0, true), PhotoMath.turn(2))
        assertEquals(PhotoMath.Turn(180, false), PhotoMath.turn(3))
        assertEquals(PhotoMath.Turn(180, true), PhotoMath.turn(4))
        assertEquals(PhotoMath.Turn(90, true), PhotoMath.turn(5))
        assertEquals(PhotoMath.Turn(90, false), PhotoMath.turn(6))
        assertEquals(PhotoMath.Turn(270, true), PhotoMath.turn(7))
        assertEquals(PhotoMath.Turn(270, false), PhotoMath.turn(8))
        // Undefined (0) and nonsense leave the pixels as they are.
        assertEquals(PhotoMath.Turn(0, false), PhotoMath.turn(0))
        assertEquals(PhotoMath.Turn(0, false), PhotoMath.turn(42))
        assertTrue(PhotoMath.turn(6).swapsSides)
        assertTrue(PhotoMath.turn(5).swapsSides)
        assertFalse(PhotoMath.turn(3).swapsSides)
    }

    @Test
    fun `an exif date is read in the offset the camera wrote, else in the zone of the phone`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val expected = ZonedDateTime.of(2026, 11, 17, 8, 14, 2, 0, kolkata).toInstant().toEpochMilli()
        assertEquals(expected, PhotoMath.exifDate("2026:11:17 08:14:02", null, kolkata))
        assertEquals(expected, PhotoMath.exifDate("2026:11:17 08:14:02", "+05:30", ZoneId.of("UTC")))
        // An offset that is not one falls back to the zone rather than failing.
        assertEquals(expected, PhotoMath.exifDate("2026:11:17 08:14:02", "   ", kolkata))
        assertEquals(expected, PhotoMath.exifDate("2026:11:17 08:14:02", "nonsense", kolkata))
    }

    @Test
    fun `a missing or broken exif date is no date`() {
        val utc = ZoneId.of("UTC")
        assertNull(PhotoMath.exifDate(null, null, utc))
        assertNull(PhotoMath.exifDate("", null, utc))
        assertNull(PhotoMath.exifDate("0000:00:00 00:00:00", null, utc))
        assertNull(PhotoMath.exifDate("2026-11-17T08:14:02", null, utc))
        assertNull(PhotoMath.exifDate("2026:13:40 08:14:02", null, utc))
    }

    @Test
    fun `a date from a camera with no clock, or from the future, falls back to now`() {
        val now = 1_795_000_000_000L
        assertEquals(now - 5_000, PhotoMath.takenAt(now - 5_000, now))
        assertEquals(now, PhotoMath.takenAt(null, now))
        // 1980-01-01, the default of a camera whose clock was never set.
        assertEquals(now, PhotoMath.takenAt(315_532_800_000L, now))
        assertEquals(now, PhotoMath.takenAt(now + 3 * 86_400_000L, now))
        // A few hours ahead is a time-zone slip, not a broken clock.
        assertEquals(now + 3_600_000L, PhotoMath.takenAt(now + 3_600_000L, now))
    }
}
