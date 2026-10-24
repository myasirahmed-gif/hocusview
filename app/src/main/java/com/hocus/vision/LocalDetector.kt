package com.hocus.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bundled CPU inference. No Google services or network dependency. */
class LocalDetector(context: Context) : AutoCloseable {
    private val labels = context.assets.open("labels.txt").bufferedReader().use { it.readLines() }
    private val model = context.assets.open("efficientdet-lite0.tflite").use { stream ->
        val bytes = stream.readBytes()
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2)).apply { allocateTensors() }
    private val inputShape = interpreter.getInputTensor(0).shape()
    private val input = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
    private val outputs = (0 until interpreter.outputTensorCount).associateWith { index ->
        ByteBuffer.allocateDirect(interpreter.getOutputTensor(index).numBytes()).order(ByteOrder.nativeOrder())
    }
    private val pixels = IntArray(inputShape[1] * inputShape[2])

    init {
        require(interpreter.getInputTensor(0).dataType() == DataType.UINT8)
        require(interpreter.outputTensorCount == 4)
        require(interpreter.getOutputTensor(0).shape().last() == 4)
    }

    fun detect(bitmap: Bitmap): List<Target> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputShape[2], inputShape[1], true)
        try {
            resized.getPixels(pixels, 0, inputShape[2], 0, 0, inputShape[2], inputShape[1])
            input.rewind()
            for (pixel in pixels) {
                input.put(((pixel shr 16) and 255).toByte())
                input.put(((pixel shr 8) and 255).toByte())
                input.put((pixel and 255).toByte())
            }
            input.rewind()
            outputs.values.forEach { it.rewind() }
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs.mapValues { it.value as Any })
            val boxes = outputs.getValue(0).apply { rewind() }.asFloatBuffer()
            val classes = outputs.getValue(1).apply { rewind() }.asFloatBuffer()
            val scores = outputs.getValue(2).apply { rewind() }.asFloatBuffer()
            val count = outputs.getValue(3).getFloat(0).toInt().coerceIn(0, scores.capacity())
            return (0 until count).mapNotNull { index ->
                val score = scores.get(index)
                val label = labels.getOrNull(classes.get(index).toInt()) ?: "Object"
                if (score < 0.45f || label == "???") return@mapNotNull null
                val top = boxes.get(index * 4).coerceIn(0f, 1f) * bitmap.height
                val left = boxes.get(index * 4 + 1).coerceIn(0f, 1f) * bitmap.width
                val bottom = boxes.get(index * 4 + 2).coerceIn(0f, 1f) * bitmap.height
                val right = boxes.get(index * 4 + 3).coerceIn(0f, 1f) * bitmap.width
                if (right <= left || bottom <= top) null else Target(RectF(left, top, right, bottom), null, label, score)
            }.sortedByDescending { it.confidence }.take(8)
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    override fun close() = interpreter.close()
}
