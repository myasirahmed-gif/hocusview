package com.hocus.vision

import kotlin.math.*

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = max(1f, right - left)
    val height get() = max(1f, bottom - top)
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
    fun moved(x: Float, y: Float) = Box(left + x, top + y, right + x, bottom + y)
    fun blend(other: Box, weight: Float) = Box(
        left + (other.left - left) * weight, top + (other.top - top) * weight,
        right + (other.right - right) * weight, bottom + (other.bottom - bottom) * weight)
    fun iou(other: Box): Float {
        val intersection = max(0f, min(right, other.right) - max(left, other.left)) *
            max(0f, min(bottom, other.bottom) - max(top, other.top))
        return intersection / max(1f, width * height + other.width * other.height - intersection)
    }
}

data class Observation(val box: Box, val label: String, val confidence: Float, val appearance: FloatArray = floatArrayOf(), val preferredId: Int? = null)
data class TrackSnapshot(val id: Int, val box: Box, val label: String, val confidence: Float,
    val predicted: Boolean, val ageMs: Long, val hits: Int)

/** One-to-one motion + appearance association. Kept independent of Android for regression tests. */
class MotionTracker {
    private data class Track(val id: Int, var box: Box, var label: String, var confidence: Float,
        var appearance: FloatArray, var seen: Long, var time: Long, var vx: Float = 0f,
        var vy: Float = 0f, var hits: Int = 1)
    private val tracks = mutableListOf<Track>()
    private var nextId = 1
    fun clear() { tracks.clear() }
    fun seed(observation: Observation, now: Long): Int {
        val id = nextId++
        tracks.add(Track(id, observation.box, observation.label, observation.confidence, observation.appearance, now, now))
        return id
    }

    fun update(observations: List<Observation>, now: Long, selectedId: Int?): List<TrackSnapshot> {
        tracks.removeAll { now - it.seen > if (it.id == selectedId) 1800 else 850 }
        val predicted = tracks.map { t ->
            val dt = (now - t.time).coerceIn(0, 250) / 1000f
            t.box.moved(t.vx * dt, t.vy * dt)
        }
        data class Pairing(val track: Int, val observation: Int, val cost: Float)
        val pairs = mutableListOf<Pairing>()
        val reserved = observations.mapNotNull { it.preferredId }.toSet()
        tracks.forEachIndexed { ti, t ->
            observations.forEachIndexed observation@ { oi, o ->
                if (o.preferredId != null && o.preferredId != t.id) return@observation
                if (t.id in reserved && o.preferredId != t.id) return@observation
                if (o.preferredId == t.id) {
                    pairs.add(Pairing(ti, oi, -1f))
                    return@observation
                }
                val p = predicted[ti]
                val distance = hypot(p.cx - o.box.cx, p.cy - o.box.cy) / max(30f, hypot(p.width, p.height))
                val similarity = cosine(t.appearance, o.appearance)
                val overlap = p.iou(o.box)
                val sameLabel = t.label == o.label || t.label == "Unknown object" || o.label == "Unknown object"
                val gate = if (t.id == selectedId) 1.6f else 1.05f
                val sizeRatio = max(p.width * p.height, o.box.width * o.box.height) /
                    max(1f, min(p.width * p.height, o.box.width * o.box.height))
                if (sizeRatio < 5f && (overlap > 0.05f || distance < gate) &&
                    (sameLabel || (similarity > 0.88f && distance < 0.65f))) {
                    val cost = (1 - overlap) * 0.38f + distance * 0.30f +
                        (1 - similarity) * 0.5f + (if (sameLabel) 0f else 0.17f)
                    pairs.add(Pairing(ti, oi, cost))
                }
            }
        }
        val usedTracks = mutableSetOf<Int>()
        val usedObservations = mutableSetOf<Int>()
        pairs.sortedBy { it.cost }.forEach { pair ->
            if (pair.track in usedTracks || pair.observation in usedObservations) return@forEach
            usedTracks.add(pair.track); usedObservations.add(pair.observation)
            val t = tracks[pair.track]
            val o = observations[pair.observation]
            val dt = max(0.016f, (now - t.time) / 1000f)
            t.vx = t.vx * 0.35f + ((o.box.cx - t.box.cx) / dt) * 0.65f
            t.vy = t.vy * 0.35f + ((o.box.cy - t.box.cy) / dt) * 0.65f
            t.box = predicted[pair.track].blend(o.box, 0.82f)
            if (o.label != "Unknown object" && (o.confidence >= 0.5f || t.label == "Unknown object")) t.label = o.label
            t.confidence = o.confidence
            t.appearance = o.appearance
            t.seen = now; t.time = now; t.hits++
        }
        tracks.forEachIndexed { index, t ->
            if (index !in usedTracks) { t.box = predicted[index]; t.time = now; t.vx *= 0.92f; t.vy *= 0.92f }
        }
        observations.forEachIndexed { index, o -> if (index !in usedObservations) seed(o, now) }
        return tracks.map { t -> TrackSnapshot(t.id, t.box, t.label, t.confidence,
            now > t.seen, now - t.seen, t.hits) }
    }
}

fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.isEmpty() || a.size != b.size) return 0.5f
    var dot = 0f; var aa = 0f; var bb = 0f
    a.indices.forEach { i -> dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
    return if (aa == 0f || bb == 0f) 0f else (dot / sqrt(aa * bb)).coerceIn(-1f, 1f)
}
