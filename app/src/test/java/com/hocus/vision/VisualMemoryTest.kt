package com.hocus.vision

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VisualMemoryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = folder.root
    }
    private fun photo() = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)

    @Test fun taughtExampleAndLabelSurviveRestart() {
        val context = context()
        VisualMemory(context).save("My blue tool", floatArrayOf(1f, 0f, 0f), photo())
        val restored = VisualMemory(context)
        assertEquals("My blue tool", restored.match(floatArrayOf(1f, 0f, 0f))?.name)
        assertEquals(1, restored.classes().single().confirmed)
        assertTrue(File(context.filesDir, "object-memory").listFiles()!!.any { it.extension == "jpg" })
    }
    @Test fun unknownAndAmbiguousExamplesAreRejected() {
        val memory = VisualMemory(context())
        memory.save("One", floatArrayOf(1f, 0f, 0f), photo())
        assertNull(memory.match(floatArrayOf(0f, 1f, 0f)))
        memory.save("Two", floatArrayOf(1f, 0.05f, 0f), photo())
        assertNull(memory.match(floatArrayOf(1f, 0.03f, 0f)))
    }
    @Test fun assistRequiresThreeConfirmedExamples() {
        val memory = VisualMemory(context())
        memory.save("Tool", floatArrayOf(1f, 0.1f, 0f), photo())
        val vector = floatArrayOf(1f, 0f, 0.06f)
        assertFalse(memory.assist(memory.match(vector)!!, vector, photo(), 20, 20000))
        memory.save("Tool", floatArrayOf(1f, -0.1f, 0f), photo())
        memory.save("Tool", floatArrayOf(1f, 0f, 0.2f), photo())
        assertTrue(memory.assist(memory.match(vector)!!, vector, photo(), 20, 20000))
        assertEquals(3, memory.classes().single().confirmed)
        assertEquals(4, memory.classes().single().samples)
        assertFalse(memory.assist(memory.match(vector)!!, vector, photo(), 20, 40000))
    }
    @Test fun duplicateViewDoesNotInflateTrainingCount() {
        val memory = VisualMemory(context())
        memory.save("Tool", floatArrayOf(1f, 0f), photo())
        try { memory.save("Tool", floatArrayOf(1f, 0f), photo()); fail("Duplicate must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, memory.classes().single().confirmed)
    }
    @Test fun forgettingRemovesSavedImagesAndIndexEntry() {
        val context = context()
        val memory = VisualMemory(context)
        memory.save("Tool", floatArrayOf(1f, 0f), photo())
        memory.delete("Tool")
        assertTrue(VisualMemory(context).classes().isEmpty())
        assertFalse(File(context.filesDir, "object-memory").listFiles()!!.any { it.extension == "jpg" })
    }
}
