package com.hocus.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneMapRenderTest {
    @Test fun rendersPossibleStairsOverTheAnalyzedSnapshotOnly() {
        val image = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(18, 24, 32))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 49, 57) }
        val floor = Path().apply { moveTo(60f, 640f); lineTo(190f, 230f); lineTo(290f, 230f); lineTo(430f, 640f); close() }
        canvas.drawPath(floor, paint)
        for (i in 0..5) {
            paint.color = Color.rgb(65 + i * 7, 73 + i * 7, 80 + i * 7)
            canvas.drawRect(145f, 250f + i * 25, 340f, 272f + i * 25, paint)
        }
        val scene = SceneAnalysis.parse(JSONObject("""{"visibility":"adequate","floor_polygon":[[600,100],[600,900],[1000,1000],[1000,0]],"hazards":[{"kind":"stairs","box":[380,280,640,740],"confidence":0.85}]}"""))
        val view = SceneMapView(RuntimeEnvironment.getApplication(), image, scene)
        view.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 900, 1200)
        val output = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(output))
        val file = File("build/reports/screenshots/hocus-path-snapshot.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(scene.guidance.contains("Stop"))
        assertTrue(file.length() > 1000)
    }
}
