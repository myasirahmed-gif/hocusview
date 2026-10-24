package com.hocus.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class MemoryMatch(val name: String, val similarity: Float, val margin: Float, val confirmed: Int)
data class LearnedClass(val name: String, val samples: Int, val confirmed: Int)

/** Local, supervised exemplar learning. The pretrained network weights remain fixed. */
class VisualMemory(context: Context) {
    private data class Example(val name: String, val vector: FloatArray, val image: String, val confirmed: Boolean)
    private val directory = File(context.filesDir, "object-memory").apply { mkdirs() }
    private val index = AtomicFile(File(directory, "examples.json"))
    private val examples = mutableListOf<Example>()
    private var lastAssisted = 0L
    init {
        if (index.baseFile.exists()) {
            val data = index.openRead().bufferedReader().use { JSONArray(it.readText()) }
            for (i in 0 until data.length()) {
                val value = data.getJSONObject(i)
                val vector = value.getJSONArray("vector")
                examples.add(Example(value.getString("name"), FloatArray(vector.length()) { vector.getDouble(it).toFloat() },
                    value.getString("image"), value.optBoolean("confirmed", true)))
            }
        }
    }
    fun classes(): List<LearnedClass> = examples.groupBy { it.name }.map { (name, items) ->
        LearnedClass(name, items.size, items.count { it.confirmed })
    }.sortedBy { it.name }
    fun match(vector: FloatArray): MemoryMatch? {
        val best = examples.groupBy { it.name }.map { (name, items) ->
            name to items.maxOf { cosine(it.vector, vector) }
        }.sortedByDescending { it.second }
        val first = best.firstOrNull() ?: return null
        val margin = first.second - (best.getOrNull(1)?.second ?: 0f)
        if (first.second < 0.94f || margin < 0.035f) return null
        return MemoryMatch(first.first, first.second, margin, examples.count { it.name == first.first && it.confirmed })
    }
    fun save(name: String, vector: FloatArray, bitmap: Bitmap, confirmed: Boolean = true) {
        val clean = name.trim().take(48)
        require(clean.isNotEmpty()) { "Enter an object name." }
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "Capture a clear object first." }
        require(examples.any { it.name == clean } || classes().size < 100) { "Memory is full. Remove a learned object first." }
        val same = examples.filter { it.name == clean }
        require(!confirmed || same.none { it.confirmed && cosine(it.vector, vector) > 0.9998f }) {
            "This view is already saved. Move to a different angle and teach again."
        }
        require(confirmed || same.count { it.confirmed } >= 3) { "Teach three angles before assisted learning." }
        val removed = if (same.size >= 12) same.firstOrNull { !it.confirmed } ?: same.first() else null
        val fileName = "${UUID.randomUUID()}.jpg"
        File(directory, fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val added = Example(clean, vector.copyOf(), fileName, confirmed)
        if (removed != null) examples.remove(removed)
        examples.add(added)
        try { persist() } catch (error: Exception) {
            examples.remove(added)
            if (removed != null) examples.add(removed)
            File(directory, fileName).delete()
            throw error
        }
        if (removed != null) File(directory, removed.image).delete()
    }
    fun assist(match: MemoryMatch, vector: FloatArray, bitmap: Bitmap, stableHits: Int, now: Long): Boolean {
        if (match.confirmed < 3 || stableHits < 12 || match.similarity < 0.975f || match.margin < 0.06f || now - lastAssisted < 10000) return false
        val same = examples.filter { it.name == match.name }
        if (same.size >= 12 || same.any { cosine(it.vector, vector) > 0.995f }) return false
        save(match.name, vector, bitmap, false)
        lastAssisted = now
        return true
    }
    fun delete(name: String) {
        val removed = examples.filter { it.name == name }
        examples.removeAll(removed.toSet())
        try { persist() } catch (error: Exception) { examples.addAll(removed); throw error }
        removed.forEach { File(directory, it.image).delete() }
    }
    private fun persist() {
        val data = JSONArray()
        examples.forEach { example ->
            data.put(JSONObject().put("name", example.name).put("image", example.image).put("confirmed", example.confirmed)
                .put("vector", JSONArray(example.vector.map { it.toDouble() })))
        }
        val stream = index.startWrite()
        try { stream.write(data.toString().toByteArray(Charsets.UTF_8)); index.finishWrite(stream) }
        catch (error: Exception) { index.failWrite(stream); throw error }
    }
}
