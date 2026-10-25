package com.hocus.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class PlateLine(val text: String, val bounds: Box, val multiline: Boolean = false)
data class PlateCandidate(val text: String, val canonical: String, val bounds: Box, val score: Float)
data class PlateReading(val id: Int, val vehicle: String, val text: String?, val confirmed: Boolean, val state: String)

object PlateRules {
    val vehicleLabels = setOf("car", "motorcycle", "truck", "bus")
    fun best(lines: List<PlateLine>, width: Int, height: Int): PlateCandidate? {
        val paired = lines.flatMap { prefix ->
            if (!Regex("[A-Z]{1,4}(?:[- ]?[0-9]{2})?").matches(prefix.text.uppercase(Locale.ROOT).trim())) emptyList()
            else lines.filter { number ->
                Regex("[0-9]{3,6}").matches(number.text.trim()) &&
                    number.bounds.top >= prefix.bounds.bottom - prefix.bounds.height * 0.2f &&
                    number.bounds.top - prefix.bounds.bottom < prefix.bounds.height * 2 &&
                    kotlin.math.abs(prefix.bounds.cx - number.bounds.cx) < maxOf(prefix.bounds.width, number.bounds.width) * 0.7f
            }.map { number -> PlateLine(prefix.text + " " + number.text,
                Box(minOf(prefix.bounds.left, number.bounds.left), prefix.bounds.top, maxOf(prefix.bounds.right, number.bounds.right), number.bounds.bottom), true) }
        }
        return (lines + paired).mapNotNull { line ->
            val text = line.text.uppercase(Locale.ROOT).trim()
            if (listOf("MODEL", "TEL", "PHONE", "CONTACT", "WHATSAPP", "CALL", "SPEED", "DIESEL", "LIMIT").any { text.startsWith(it) }) return@mapNotNull null
            if (!Regex("[A-Z0-9 .-]+").matches(text)) return@mapNotNull null
            val canonical = text.filter { it.isLetterOrDigit() }
            val digits = canonical.count { it.isDigit() }
            val letters = canonical.count { it in 'A'..'Z' }
            if (canonical.length !in 4..12 || digits < 2 || (letters == 0 && canonical.length !in 4..8)) return@mapNotNull null
            val box = line.bounds
            val aspect = box.width / box.height
            val area = box.width * box.height / (width.toFloat() * height)
            if (box.height < 7 || aspect !in 1.2f..10f || area !in 0.001f..0.25f || box.cy < height * 0.25f) return@mapNotNull null
            val score = (if (letters > 0) 0.5f else 0.25f) + (if (canonical.length in 5..9) 0.2f else 0f) +
                (if (box.cy > height * 0.45f) 0.15f else 0f) + (if (aspect in 2f..6f) 0.15f else 0f) + (if (line.multiline) 0.35f else 0f)
            PlateCandidate(text.take(24), canonical, box, score)
        }.maxByOrNull { it.score }
    }
}

class PlateConsensus {
    private data class Vote(var candidate: String, var hits: Int, var last: Long)
    private val votes = mutableMapOf<Int, Vote>()
    fun observe(id: Int, canonical: String?, now: Long): Boolean {
        votes.entries.removeAll { now - it.value.last > 6000 }
        if (canonical == null) { votes.remove(id); return false }
        val previous = votes[id]
        if (previous == null || previous.candidate != canonical) votes[id] = Vote(canonical, 1, now)
        else if (now - previous.last >= 250) { previous.hits++; previous.last = now }
        return (votes[id]?.hits ?: 0) >= 2
    }
}

/** Bundled Latin OCR over detected vehicles. Numbers stay local and are not persisted. */
class PlateScanner(private val callback: (PlateReading) -> Unit) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    private val released = AtomicBoolean(false)
    private val attempted = mutableMapOf<Int, Long>()
    private val announced = mutableSetOf<Int>()
    private val consensus = PlateConsensus()
    @Synchronized fun scan(bitmap: Bitmap, tracks: List<TrackSnapshot>, now: Long) {
        if (closed) return
        val vehicles = tracks.filter { !it.predicted && it.label in PlateRules.vehicleLabels && it.hits >= 2 }
        vehicles.forEach { if (announced.add(it.id)) callback(PlateReading(it.id, it.label, null, false, "Looking for a readable plate")) }
        if (busy.get()) return
        val target = vehicles.filter { now - (attempted[it.id] ?: 0L) >= 900 }
            .minByOrNull { attempted[it.id] ?: 0L } ?: return
        if (!busy.compareAndSet(false, true)) return
        attempted[target.id] = now
        val liveIds = tracks.map { it.id }.toSet()
        attempted.keys.retainAll(liveIds); announced.retainAll(liveIds)
        val crop = try { cropObject(bitmap, target.box, 1280) } catch (error: Exception) { busy.set(false); return }
        try {
            recognizer.process(InputImage.fromBitmap(crop, 0)).addOnCompleteListener(worker) { task ->
                try {
                    if (!closed) {
                        val text = if (task.isSuccessful) task.result else null
                        val lines = text?.textBlocks?.flatMap { it.lines }?.mapNotNull { line ->
                            line.boundingBox?.let { rect -> PlateLine(line.text, Box(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())) }
                        } ?: emptyList()
                        // Also consider two-line plates as a combined region.
                        val blocks = text?.textBlocks?.mapNotNull { block -> block.boundingBox?.let { rect ->
                            PlateLine(block.text.replace('\n', ' '), Box(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()), block.lines.size in 2..3)
                        } } ?: emptyList()
                        val candidate = PlateRules.best(lines + blocks, crop.width, crop.height)
                        val confirmed = consensus.observe(target.id, candidate?.canonical, android.os.SystemClock.elapsedRealtime())
                        callback(PlateReading(target.id, target.label, candidate?.text, confirmed,
                            if (!task.isSuccessful) "OCR unavailable — retrying" else if (candidate == null) "Plate unreadable — hold steady or move closer"
                            else if (confirmed) "Repeated OCR match — verify visually" else "Possible plate — verifying"))
                    }
                } finally {
                    crop.recycle(); busy.set(false)
                    if (closed) release()
                }
            }
        } catch (error: Exception) { crop.recycle(); busy.set(false); callback(PlateReading(target.id, target.label, null, false, "OCR unavailable — retrying")) }
    }
    @Synchronized override fun close() {
        closed = true
        if (!busy.get()) release()
    }
    private fun release() { if (released.compareAndSet(false, true)) { recognizer.close(); worker.shutdown() } }
}
