package com.hocus.vision

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class CatalogEntry(val name: String, val mode: String, val aliases: List<String>)
data class FocusResult(val name: String?, val confidence: Float, val vector: FloatArray)

class FocusClassifier(context: Context) : AutoCloseable {
    val catalog: List<CatalogEntry> = context.assets.open("object-catalog.json").bufferedReader().use { reader ->
        val items = JSONArray(reader.readText())
        (0 until items.length()).map { index ->
            val value = items.getJSONObject(index)
            val aliases = value.getJSONArray("aliases")
            CatalogEntry(value.getString("name"), value.getString("mode"), (0 until aliases.length()).map { aliases.getString(it) })
        }
    }
    private val labels = context.assets.open("imagenet-labels.txt").bufferedReader().use { it.readLines() }
    private val model = context.assets.open("mobilenet_v1_1.0_224.tflite").use { stream ->
        val bytes = stream.readBytes()
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    private val input = ByteBuffer.allocateDirect(224 * 224 * 3 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(224 * 224)
    private val output = arrayOf(FloatArray(1001))
    fun classify(bitmap: Bitmap): FocusResult {
        val small = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        try {
            small.getPixels(pixels, 0, 224, 0, 0, 224, 224)
            input.rewind()
            pixels.forEach { pixel ->
                input.putFloat((((pixel shr 16) and 255) - 127.5f) / 127.5f)
                input.putFloat((((pixel shr 8) and 255) - 127.5f) / 127.5f)
                input.putFloat(((pixel and 255) - 127.5f) / 127.5f)
            }
            input.rewind()
            interpreter.run(input, output)
            val scores = output[0]
            val top = scores.indices.maxBy { scores[it] }
            val entry = catalog.firstOrNull { it.mode == "focus" && labels[top] in it.aliases }
            val appearance = appearance(bitmap, Box(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
            // Hellinger features retain more information than the winning class alone.
            val vector = FloatArray(scores.size + appearance.size)
            scores.indices.forEach { vector[it] = sqrt(scores[it].coerceAtLeast(0f)) * 0.8f }
            appearance.indices.forEach { vector[scores.size + it] = appearance[it] * 0.6f }
            return FocusResult(entry?.name?.takeIf { scores[top] >= 0.35f }, scores[top], vector)
        } finally { if (small !== bitmap) small.recycle() }
    }
    override fun close() = interpreter.close()
}
