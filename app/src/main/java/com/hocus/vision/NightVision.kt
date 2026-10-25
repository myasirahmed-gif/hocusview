package com.hocus.vision

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import kotlin.math.pow

object NightVision {
    val previewFilter get() = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        1.18f, 0f, 0f, 0f, 8f, 0f, 1.18f, 0f, 0f, 8f, 0f, 0f, 1.18f, 0f, 8f, 0f, 0f, 0f, 1f, 0f)))
    fun luminance(bitmap: Bitmap): Float {
        var sum = 0f
        for (y in 0 until 16) for (x in 0 until 16) {
            val p = bitmap.getPixel((x * bitmap.width / 16).coerceAtMost(bitmap.width - 1), (y * bitmap.height / 16).coerceAtMost(bitmap.height - 1))
            sum += ((p shr 16) and 255) * 0.299f + ((p shr 8) and 255) * 0.587f + (p and 255) * 0.114f
        }
        return sum / 256
    }
    private val gamma = IntArray(256) { (255 * (it / 255.0).pow(0.75)).toInt().coerceIn(0, 255) }
    fun enhance(bitmap: Bitmap): Bitmap {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.indices.forEach { i ->
            val p = pixels[i]
            pixels[i] = (0xFF shl 24) or (gamma[(p shr 16) and 255] shl 16) or (gamma[(p shr 8) and 255] shl 8) or gamma[p and 255]
        }
        return Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    }
}
