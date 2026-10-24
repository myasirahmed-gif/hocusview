package com.hocus.vision

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PatchFollowerTest {
    private fun frame(dx: Int = 0, dy: Int = 0): Bitmap {
        val pixels = IntArray(160 * 120) { 0xFF000000.toInt() }
        for (y in 0 until 30) for (x in 0 until 30) {
            val color = 0xFF000000.toInt() or (((x * 41 + y * 13) % 256) shl 16) or
                (((y * 53 + x * 7) % 256) shl 8) or ((x * y * 19) % 256)
            pixels[(35 + y + dy) * 160 + 30 + x + dx] = color
        }
        return Bitmap.createBitmap(pixels, 160, 120, Bitmap.Config.ARGB_8888)
    }
    @Test fun followsUnclassifiedPatchWithoutObjectDetector() {
        val follower = PatchFollower()
        follower.start(frame(), Box(30f, 35f, 60f, 65f), 1000)
        val next = follower.locate(frame(6, 3), 1100)
        assertNotNull(next)
        assertEquals(51f, next!!.first.cx, 2f)
        assertEquals(53f, next.first.cy, 2f)
    }
    @Test fun rejectsMissingPatchAndExpires() {
        val follower = PatchFollower()
        follower.start(frame(), Box(30f, 35f, 60f, 65f), 1000)
        val blank = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        assertNull(follower.locate(blank, 1200))
        assertNull(follower.locate(blank, 3000))
        assertFalse(follower.active)
    }
    @Test fun fullFrameCropDoesNotTransferOwnershipOfAnalysisBitmap() {
        val original = frame()
        val crop = cropObject(original, Box(0f, 0f, 160f, 120f))
        assertNotSame(original, crop)
        crop.recycle()
        assertFalse(original.isRecycled)
    }
}
