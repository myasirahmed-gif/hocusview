package com.hocus.vision

import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.camera.view.PreviewView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HudLayoutTest {
    @Test fun rendersLockedTargetScreenshotWithFloatingThumbnail() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredMethod("buildInterface").apply { isAccessible = true }.invoke(activity)
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        val fixture = File("build/YellowLabrador.jpg")
        val photo = if (fixture.exists()) BitmapFactory.decodeFile(fixture.path) else
            Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 65, 72)) }
        root.addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(photo) },
            1, FrameLayout.LayoutParams(-1, -1))
        fun <T> field(name: String): T {
            @Suppress("UNCHECKED_CAST")
            return MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
        }
        val hud = field<HudView>("hud")
        val box = Box(photo.width * 0.36f, photo.height * 0.20f, photo.width * 0.64f, photo.height * 0.48f)
        hud.imageWidth = photo.width; hud.imageHeight = photo.height
        hud.selectedId = 7
        hud.targets = listOf(Target(box.rect(), 7, "Dog", 0.84f))
        val crop = cropObject(photo, box)
        hud.thumbnail = crop
        field<ImageView>("targetImage").setImageBitmap(crop)
        field<TextView>("targetName").text = "Dog"
        field<TextView>("targetInfo").text = "TRACK 7 / 84% MODEL CONFIDENCE"
        field<TextView>("status").text = "TARGET LOCKED"
        field<TextView>("telemetry").text = "1 TRACK / 42ms"
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        val screenshot = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(screenshot))
        val output = File("build/reports/screenshots/hocus-vision-locked.png").apply { parentFile!!.mkdirs() }
        output.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(output.length() > 10000)
    }
    @Test fun cameraFillsScreenAndDockRemainsAboveBottomEdge() {
        // Build the actual interface without starting native inference or requesting a camera.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredMethod("buildInterface").apply { isAccessible = true }.invoke(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as ViewGroup
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        val camera = root.getChildAt(0) as PreviewView
        assertEquals(1080, camera.width)
        assertEquals(2400, camera.height)
        val header = root.getChildAt(2)
        val dock = root.getChildAt(3)
        assertTrue(header.bottom < dock.top)
        assertTrue(dock.bottom <= 2400)
        assertTrue(dock.top > 1200)
        assertTrue("Collapsed controls must leave more camera space", dock.height < 130 * root.resources.displayMetrics.density)
        assertTrue("At least 70% of vertical space remains between controls", (dock.top - header.bottom).toFloat() / root.height > 0.70f)
    }
    @Test fun manualTargetMapsInsideImageForRearAndMirroredFrontCamera() {
        val hud = HudView(RuntimeEnvironment.getApplication()) { _, _, _ -> }
        hud.imageWidth = 480; hud.imageHeight = 640
        hud.layout(0, 0, 720, 1280)
        for (mirrored in listOf(false, true)) {
            hud.mirrored = mirrored
            for (x in listOf(0f, 360f, 720f)) {
                val box = hud.boxAround(x, 640f)!!
                assertTrue(box.left >= 0f && box.right <= 480f)
                assertTrue(box.top >= 0f && box.bottom <= 640f)
            }
        }
        hud.mirrored = false
        val rear = hud.boxAround(0f, 640f)!!
        hud.mirrored = true
        val front = hud.boxAround(0f, 640f)!!
        assertEquals(480f, rear.cx + front.cx, 0.01f)
    }
}
