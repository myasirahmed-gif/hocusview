package com.hocus.vision

import android.content.Context
import android.graphics.*
import android.view.View

/** Geometry is drawn only over the exact analyzed snapshot, never over a later live frame. */
class SceneMapView(context: Context, private val image: Bitmap, private val scene: SceneAnalysis) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val left = (width - image.width * scale) / 2
        val top = (height - image.height * scale) / 2
        val rect = RectF(left, top, left + image.width * scale, top + image.height * scale)
        ink.color = Color.WHITE; ink.style = Paint.Style.FILL
        canvas.drawBitmap(image, null, rect, ink)
        fun x(value: Float) = rect.left + value * rect.width()
        fun y(value: Float) = rect.top + value * rect.height()
        if (scene.floor.isNotEmpty()) {
            path.reset()
            scene.floor.forEachIndexed { index, p -> if (index == 0) path.moveTo(x(p.x), y(p.y)) else path.lineTo(x(p.x), y(p.y)) }
            path.close(); ink.color = 0x3368F3E0; canvas.drawPath(path, ink)
            ink.color = 0xFF68F3E0.toInt(); ink.style = Paint.Style.STROKE; ink.strokeWidth = 2 * resources.displayMetrics.density
            canvas.drawPath(path, ink); ink.style = Paint.Style.FILL
        }
        scene.hazards.forEach { hazard ->
            ink.color = 0xFFFFCD74.toInt(); ink.style = Paint.Style.STROKE; ink.strokeWidth = 3 * resources.displayMetrics.density
            canvas.drawRect(x(hazard.bounds.left), y(hazard.bounds.top), x(hazard.bounds.right), y(hazard.bounds.bottom), ink)
            ink.style = Paint.Style.FILL; ink.textSize = 12 * resources.displayMetrics.scaledDensity; ink.typeface = Typeface.MONOSPACE
            canvas.drawText("POSSIBLE ${hazard.kind.uppercase()}", x(hazard.bounds.left), y(hazard.bounds.top).coerceAtLeast(rect.top + ink.textSize), ink)
        }
        // Relative image-sector sketch. No distances, localization, or walkability claims.
        val cell = 30 * resources.displayMetrics.density
        val sx = rect.right - cell * 3 - 8; val sy = rect.bottom - cell * 3 - 8
        ink.color = 0xDD071820.toInt(); canvas.drawRect(sx - 4, sy - 20, sx + cell * 3 + 4, sy + cell * 3 + 4, ink)
        ink.textSize = 8 * resources.displayMetrics.scaledDensity; ink.color = Color.WHITE
        canvas.drawText("IMAGE SECTORS", sx, sy - 6, ink)
        for (row in 0..2) for (column in 0..2) {
            ink.style = Paint.Style.STROKE; ink.color = 0xFF507780.toInt(); ink.strokeWidth = 1f
            canvas.drawRect(sx + column * cell, sy + row * cell, sx + (column + 1) * cell, sy + (row + 1) * cell, ink)
        }
        ink.style = Paint.Style.FILL; ink.color = 0xFFFFCD74.toInt()
        scene.hazards.forEach { hazard -> canvas.drawCircle(sx + hazard.bounds.cx * cell * 3, sy + hazard.bounds.cy * cell * 3, cell * 0.12f, ink) }
    }
}
