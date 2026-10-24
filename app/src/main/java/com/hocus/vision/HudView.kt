package com.hocus.vision

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class HudView(context: Context, private val onTarget: (Target?, Float, Float) -> Unit) : View(context) {
    var targets: List<Target> = emptyList()
    var selectedId: Int? = null
    var imageWidth = 0
    var imageHeight = 0
    var mirrored = false
    var thumbnail: Bitmap? = null
    var safeTop = 0
    var safeBottom = 0
    private val density = resources.displayMetrics.density
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val displayed = mutableMapOf<Int, RectF>()
    private val cyan = Color.rgb(104, 243, 224)
    private val amber = Color.rgb(255, 205, 116)
    private var downX = 0f
    private var downY = 0f
    init { contentDescription = "Camera tracking view. Tap an object to lock; tap an empty area to track a new object." }
    private fun screenBox(box: RectF): RectF {
        if (imageWidth == 0 || imageHeight == 0) return RectF()
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val x = (width - imageWidth * scale) / 2f; val y = (height - imageHeight * scale) / 2f
        val mapped = RectF(box.left * scale + x, box.top * scale + y, box.right * scale + x, box.bottom * scale + y)
        return if (mirrored) RectF(width - mapped.right, mapped.top, width - mapped.left, mapped.bottom) else mapped
    }
    fun boxAround(x: Float, y: Float): Box? {
        if (imageWidth == 0 || imageHeight == 0) return null
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f; val offsetY = (height - imageHeight * scale) / 2f
        val px = (((if (mirrored) width - x else x) - offsetX) / scale).coerceIn(0f, imageWidth.toFloat())
        val py = ((y - offsetY) / scale).coerceIn(0f, imageHeight.toFloat())
        val size = min(imageWidth, imageHeight) * 0.36f
        val left = (px - size / 2).coerceIn(0f, imageWidth - size); val top = (py - size / 2).coerceIn(0f, imageHeight - size)
        return Box(left, top, left + size, top + size)
    }
    fun centerTarget(): Target? = targets.filter { !it.predicted && screenBox(it.bounds).contains(width / 2f, height / 2f) }
        .minByOrNull { it.bounds.width() * it.bounds.height() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ink.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(0xDA031018.toInt(), 0x00031018, 0x00031018, 0xDA031018.toInt()),
            floatArrayOf(0f, 0.24f, 0.64f, 1f), Shader.TileMode.CLAMP)
        ink.style = Paint.Style.FILL; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ink); ink.shader = null
        val cx = width / 2f; val cy = height / 2f
        ink.color = 0x447DEADD; ink.strokeWidth = density; ink.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, 22 * density, ink)
        canvas.drawLine(cx - 34 * density, cy, cx - 15 * density, cy, ink); canvas.drawLine(cx + 15 * density, cy, cx + 34 * density, cy, ink)
        canvas.drawLine(cx, cy - 34 * density, cx, cy - 15 * density, ink); canvas.drawLine(cx, cy + 15 * density, cx, cy + 34 * density, ink)
        displayed.keys.retainAll(targets.mapNotNull { it.id }.toSet())
        targets.sortedBy { it.id == selectedId }.forEach { target ->
            val dest = screenBox(target.bounds); val rect = displayed.getOrPut(target.id ?: -1) { RectF(dest) }
            rect.left += (dest.left - rect.left) * 0.55f; rect.top += (dest.top - rect.top) * 0.55f
            rect.right += (dest.right - rect.right) * 0.55f; rect.bottom += (dest.bottom - rect.bottom) * 0.55f
            val selected = target.id != null && target.id == selectedId
            val color = if (target.predicted) amber else if (selected) cyan else 0xAAAECDCF.toInt()
            ink.color = if (selected) 0x1268F3E0 else Color.TRANSPARENT; ink.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, 6 * density, 6 * density, ink)
            ink.color = color; ink.style = Paint.Style.STROKE; ink.strokeWidth = (if (selected) 2f else 1.2f) * density
            if (target.predicted) ink.pathEffect = DashPathEffect(floatArrayOf(5 * density, 5 * density), 0f)
            val corner = min(20 * density, min(rect.width(), rect.height()) * 0.25f)
            path.reset()
            path.moveTo(rect.left, rect.top + corner); path.lineTo(rect.left, rect.top); path.lineTo(rect.left + corner, rect.top)
            path.moveTo(rect.right - corner, rect.top); path.lineTo(rect.right, rect.top); path.lineTo(rect.right, rect.top + corner)
            path.moveTo(rect.right, rect.bottom - corner); path.lineTo(rect.right, rect.bottom); path.lineTo(rect.right - corner, rect.bottom)
            path.moveTo(rect.left + corner, rect.bottom); path.lineTo(rect.left, rect.bottom); path.lineTo(rect.left, rect.bottom - corner)
            canvas.drawPath(path, ink); ink.pathEffect = null
            val text = "${if (target.predicted) "HOLD" else target.id?.toString()?.padStart(2, '0')} / ${target.label.uppercase().take(20)}"
            ink.typeface = Typeface.MONOSPACE; ink.textSize = 10 * density
            val labelWidth = ink.measureText(text) + 16 * density
            val lx = rect.left.coerceIn(8 * density, max(8 * density, width - labelWidth - 8 * density))
            val ly = (rect.top - 26 * density).coerceIn(safeTop.toFloat(), max(safeTop.toFloat(), height - safeBottom - 26 * density))
            ink.style = Paint.Style.FILL; ink.color = 0xE4081B24.toInt()
            canvas.drawRoundRect(RectF(lx, ly, lx + labelWidth, ly + 22 * density), 5 * density, 5 * density, ink)
            ink.color = color; canvas.drawText(text, lx + 8 * density, ly + 15 * density, ink)
            if (selected) drawThumbnail(canvas, rect, target, color)
        }
        if (selectedId == null) {
            ink.typeface = Typeface.MONOSPACE; ink.textSize = 9 * density; ink.color = 0xAAC2D8DA.toInt(); ink.style = Paint.Style.FILL
            val hint = "POINT  /  TAP TO LOCK"; canvas.drawText(hint, cx - ink.measureText(hint) / 2, cy + 58 * density, ink)
        }
        if (isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }
    private fun drawThumbnail(canvas: Canvas, box: RectF, target: Target, color: Int) {
        val bitmap = thumbnail ?: return
        if (bitmap.isRecycled) return
        val cardW = 84 * density; val cardH = 92 * density
        var left = if (box.right + cardW + 20 * density < width) box.right + 16 * density else box.left - cardW - 16 * density
        left = left.coerceIn(10 * density, max(10 * density, width - cardW - 10 * density))
        val top = box.top.coerceIn(safeTop + 30 * density, max(safeTop + 30 * density, height - safeBottom - cardH - 12 * density))
        val card = RectF(left, top, left + cardW, top + cardH)
        ink.style = Paint.Style.STROKE; ink.color = color; ink.strokeWidth = density
        canvas.drawLine(box.centerX().coerceIn(0f, width.toFloat()), box.top.coerceIn(top, top + cardH), card.centerX(), card.centerY(), ink)
        ink.style = Paint.Style.FILL; ink.color = 0xF0081B24.toInt(); canvas.drawRoundRect(card, 9 * density, 9 * density, ink)
        ink.style = Paint.Style.STROKE; ink.color = color; canvas.drawRoundRect(card, 9 * density, 9 * density, ink)
        val photo = RectF(left + 5 * density, top + 21 * density, left + cardW - 5 * density, top + cardH - 20 * density)
        canvas.save(); path.reset(); path.addRoundRect(photo, 4 * density, 4 * density, Path.Direction.CW); canvas.clipPath(path)
        val imageRatio = bitmap.width.toFloat() / bitmap.height; val targetRatio = photo.width() / photo.height()
        val src = if (imageRatio > targetRatio) {
            val cropWidth = (bitmap.height * targetRatio).toInt(); Rect((bitmap.width - cropWidth) / 2, 0, (bitmap.width + cropWidth) / 2, bitmap.height)
        } else {
            val cropHeight = (bitmap.width / targetRatio).toInt(); Rect(0, (bitmap.height - cropHeight) / 2, bitmap.width, (bitmap.height + cropHeight) / 2)
        }
        ink.style = Paint.Style.FILL; ink.alpha = if (target.predicted) 130 else 255; canvas.drawBitmap(bitmap, src, photo, ink)
        canvas.restore(); ink.alpha = 255; ink.color = color; ink.typeface = Typeface.MONOSPACE; ink.textSize = 8 * density
        canvas.drawText("VISUAL / ${target.id}", left + 7 * density, top + 14 * density, ink)
        canvas.drawText(if (target.predicted) "REACQUIRING" else "LIVE LOCK", left + 7 * density, top + cardH - 7 * density, ink)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
            MotionEvent.ACTION_UP -> if (hypot(event.x - downX, event.y - downY) < 24 * density) {
                val target = targets.filter { screenBox(it.bounds).contains(event.x, event.y) }.minByOrNull { it.bounds.width() * it.bounds.height() }
                onTarget(target, event.x, event.y); performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
