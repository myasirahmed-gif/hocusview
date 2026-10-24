package com.hocus.vision

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.*

fun Box.rect() = RectF(left, top, right, bottom)
fun RectF.box() = Box(left, top, right, bottom)
fun cropObject(bitmap: Bitmap, box: Box, maxSize: Int = 224): Bitmap {
    val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
    val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
    val width = box.width.toInt().coerceIn(1, bitmap.width - left)
    val height = box.height.toInt().coerceIn(1, bitmap.height - top)
    val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
    val scale = min(1f, maxSize.toFloat() / max(width, height))
    val result = Bitmap.createScaledBitmap(crop, max(1, (width * scale).toInt()), max(1, (height * scale).toInt()), true)
    // A full-frame crop may alias the source. Never hand ownership of the analysis frame to the UI.
    if (result === bitmap) return result.copy(Bitmap.Config.ARGB_8888, false)
    if (result !== crop && crop !== bitmap) crop.recycle()
    return result
}

fun appearance(bitmap: Bitmap, box: Box): FloatArray {
    val features = FloatArray(64)
    for (y in 0 until 16) for (x in 0 until 16) {
        val px = (box.left + (x + 0.5f) * box.width / 16).toInt().coerceIn(0, bitmap.width - 1)
        val py = (box.top + (y + 0.5f) * box.height / 16).toInt().coerceIn(0, bitmap.height - 1)
        val color = bitmap.getPixel(px, py)
        val r = (color shr 16) and 255; val g = (color shr 8) and 255; val b = color and 255
        features[r / 16] += 1f / 256
        features[16 + g / 16] += 1f / 256
        features[32 + b / 16] += 1f / 256
        features[48 + y / 4 * 4 + x / 4] += (r * 0.299f + g * 0.587f + b * 0.114f) / (255 * 16)
    }
    val length = sqrt(features.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(0.001f)
    return features.map { it / length }.toFloatArray()
}

/** Small selected-target template search also works for objects outside detector classes. */
class PatchFollower {
    private var template: FloatArray? = null
    private var box: Box? = null
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastGood = 0L
    val active get() = template != null
    fun start(bitmap: Bitmap, bounds: Box, now: Long) {
        template = sample(bitmap, bounds); box = bounds
        velocityX = 0f; velocityY = 0f; lastGood = now
    }
    fun clear() { template = null; box = null }
    fun locate(bitmap: Bitmap, now: Long): Pair<Box, Float>? {
        val initial = box ?: return null
        val reference = template ?: return null
        if (now - lastGood > 1800) { clear(); return null }
        val prediction = initial.moved(velocityX, velocityY)
        var best = prediction
        var bestScore = -1f
        val radius = max(12f, min(bitmap.width, bitmap.height) * 0.09f)
        for (dy in -4..4) for (dx in -4..4) {
            val candidate = prediction.moved(dx * radius / 4, dy * radius / 4)
            if (candidate.left < 0 || candidate.top < 0 || candidate.right > bitmap.width || candidate.bottom > bitmap.height) continue
            val values = sample(bitmap, candidate)
            var error = 0f
            values.indices.forEach { error += abs(values[it] - reference[it]) }
            val score = 1f - error / values.size
            if (score > bestScore) { bestScore = score; best = candidate }
        }
        if (bestScore < 0.82f) return null
        velocityX = (best.cx - initial.cx) * 0.6f
        velocityY = (best.cy - initial.cy) * 0.6f
        box = best; lastGood = now
        return best to bestScore
    }
    private fun sample(bitmap: Bitmap, bounds: Box): FloatArray {
        val values = FloatArray(12 * 12 * 3)
        var index = 0
        for (y in 0 until 12) for (x in 0 until 12) {
            val px = (bounds.left + (x + 0.5f) * bounds.width / 12).toInt().coerceIn(0, bitmap.width - 1)
            val py = (bounds.top + (y + 0.5f) * bounds.height / 12).toInt().coerceIn(0, bitmap.height - 1)
            val c = bitmap.getPixel(px, py)
            values[index++] = ((c shr 16) and 255) / 255f
            values[index++] = ((c shr 8) and 255) / 255f
            values[index++] = (c and 255) / 255f
        }
        return values
    }
}
