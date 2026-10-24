package com.hocus.vision

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.Locale
import kotlin.math.max

data class Target(val bounds: RectF, val id: Int?, val label: String, val confidence: Float?)

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var hud: HudView
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoom: SeekBar
    private lateinit var permissionButton: Button
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var selectedId: Int? = null
    @Volatile private var scanning = true
    @Volatile private var disposed = false
    private val executor = Executors.newSingleThreadExecutor()
    private val uiExecutor by lazy { ContextCompat.getMainExecutor(this) }
    private val detectorDelegate = lazy {
        LocalDetector(this)
    }
    private val detector by detectorDelegate
    private var previousTargets: List<Target> = emptyList()
    private var nextTargetId = 1
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionHelp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(3, 12, 18))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(16), dp(12) + insets.systemWindowInsetTop,
                    dp(16), dp(12) + insets.systemWindowInsetBottom)
                insets
            }
        }
        root.addView(text("HOCUS / VISION", 23f))
        status = text("INITIALIZING CAMERA", 12f)
        root.addView(status)
        val viewport = FrameLayout(this)
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        viewport.addView(preview, FrameLayout.LayoutParams(-1, -1))
        hud = HudView(this) { target ->
            selectedId = target?.id
            hud.selectedId = selectedId
            updateDetails(target)
            hud.invalidate()
        }
        viewport.addView(hud, FrameLayout.LayoutParams(-1, -1))
        permissionButton = Button(this).apply {
            text = "ENABLE CAMERA"
            visibility = View.GONE
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    startCamera()
                } else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    permission.launch(Manifest.permission.CAMERA)
                } else {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
        }
        root.addView(permissionButton)
        details = text("Point at an object. Tap its box for details.", 14f)
        details.minLines = 3
        root.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        zoomLabel = text("CAMERA ZOOM / 1.0x", 12f)
        root.addView(zoomLabel)
        zoom = SeekBar(this).apply { max = 100; isEnabled = false }
        zoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) camera?.cameraControl?.setLinearZoom(value / 100f)
                    ?.addListener({}, uiExecutor)
            }
        })
        root.addView(zoom)
        val controls = LinearLayout(this)
        controls.addView(Button(this).apply {
            text = "PAUSE SCAN"
            setOnClickListener {
                scanning = !scanning
                text = if (scanning) "PAUSE SCAN" else "RESUME SCAN"
                hud.targets = emptyList()
                selectedId = null
                hud.selectedId = null
                hud.invalidate()
                details.text = if (scanning) "Point at an object. Tap its box for details." else "Scan paused. Camera remains live."
                status.text = if (scanning) "SCANNING" else "SCAN PAUSED"
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            text = "RESET ZOOM"
            setOnClickListener { camera?.cameraControl?.setLinearZoom(0f) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        root.addView(text("ON-DEVICE DETECTION • TAP TO SELECT", 10f))
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permission.launch(Manifest.permission.CAMERA)
    }

    private fun showPermissionHelp() {
        status.text = "CAMERA ACCESS NEEDED"
        details.text = "Allow camera access to start the HUD. You can enable it in app settings."
        permissionButton.visibility = View.VISIBLE
    }

    private fun startCamera() {
        permissionButton.visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                    CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                hud.mirrored = selector == CameraSelector.DEFAULT_FRONT_CAMERA
                val livePreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val resolution = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(640, 480),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val analyzer = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis = analyzer
                analyzer.setAnalyzer(executor) { frame ->
                    try {
                        if (disposed || !scanning) return@setAnalyzer
                        val bitmap = frame.toBitmap()
                        val rotation = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
                        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotation, true)
                        val imageWidth = upright.width
                        val imageHeight = upright.height
                        val targets = try {
                            val detections = detector.detect(upright)
                            val available = previousTargets.toMutableList()
                            detections.map { obj ->
                                val bounds = RectF(obj.bounds)
                                val match = available.filter { it.label == obj.label }
                                    .maxByOrNull { overlap(it.bounds, bounds) }
                                    ?.takeIf { overlap(it.bounds, bounds) >= 0.25f }
                                if (match != null) available.remove(match)
                                Target(bounds, match?.id ?: nextTargetId++, obj.label, obj.confidence)
                            }.also { previousTargets = it }
                        } finally {
                            if (upright !== bitmap) upright.recycle()
                            bitmap.recycle()
                        }
                        uiExecutor.execute {
                                if (!disposed && scanning) {
                                    hud.imageWidth = imageWidth
                                    hud.imageHeight = imageHeight
                                    hud.targets = targets
                                    hud.invalidate()
                                    status.text = "SCANNING / ${targets.size} OBJECTS"
                                    if (selectedId != null) {
                                        val selected = hud.targets.find { it.id == selectedId }
                                        if (selected == null) {
                                            details.text = "Selected target left view. Tap a box to select again."
                                            selectedId = null
                                            hud.selectedId = null
                                        } else updateDetails(selected)
                                    }
                                }
                        }
                    } catch (error: Exception) {
                        uiExecutor.execute {
                                if (!disposed && scanning) {
                                    hud.targets = emptyList()
                                    hud.invalidate()
                                    status.text = "DETECTION UNAVAILABLE"
                                    details.text = error.localizedMessage ?: "The object model could not start on this device."
                                }
                        }
                    } finally {
                        frame.close()
                    }
                }
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, selector, livePreview, analyzer)
                camera?.cameraInfo?.zoomState?.observe(this) { state ->
                    zoom.isEnabled = state.maxZoomRatio > state.minZoomRatio
                    zoom.progress = (state.linearZoom * 100).toInt()
                    zoomLabel.text = String.format(Locale.US, "CAMERA ZOOM / %.1fx", state.zoomRatio)
                }
                status.text = "SCANNING / PREPARING OBJECT MODEL"
            } catch (error: Exception) {
                status.text = "CAMERA UNAVAILABLE"
                details.text = error.localizedMessage ?: "Close other camera apps and try again."
                permissionButton.text = "RETRY CAMERA"
                permissionButton.visibility = View.VISIBLE
            }
        }, uiExecutor)
    }

    private fun updateDetails(target: Target?) {
        details.text = if (target == null) "Point at an object. Tap its box for details." else {
            val confidence = target.confidence?.let { "${(it * 100).toInt()}% detection confidence" } ?: "Category unknown"
            "${target.label.uppercase()} / TRACK ${target.id ?: "—"}\n$confidence\nUse the slider to zoom. Distance is not measured."
        }
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val intersection = RectF(a)
        if (!intersection.intersect(b)) return 0f
        val area = intersection.width() * intersection.height()
        val union = a.width() * a.height() + b.width() * b.height() - area
        return if (union > 0f) area / union else 0f
    }

    override fun onDestroy() {
        disposed = true
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        // Run close after any analyzer invocation already queued on this executor.
        executor.execute { if (detectorDelegate.isInitialized()) detector.close() }
        executor.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(98, 241, 235))
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(4), 0, dp(4))
    }
}

class HudView(context: Context, private val onTarget: (Target?) -> Unit) : View(context) {
    var targets: List<Target> = emptyList()
    var selectedId: Int? = null
    var imageWidth = 1
    var imageHeight = 1
    var mirrored = false
    private val density = resources.displayMetrics.density
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * density; typeface = Typeface.MONOSPACE }
    private fun screenBox(box: RectF): RectF {
        // Both use cases use CameraX's default full sensor crop; FILL_CENTER crops the preview.
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val x = (width - imageWidth * scale) / 2f
        val y = (height - imageHeight * scale) / 2f
        val mapped = RectF(box.left * scale + x, box.top * scale + y, box.right * scale + x, box.bottom * scale + y)
        return if (mirrored) RectF(width - mapped.right, mapped.top, width - mapped.left, mapped.bottom) else mapped
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ink.color = Color.argb(160, 98, 241, 235)
        ink.style = Paint.Style.STROKE
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, 28 * density, ink)
        canvas.drawLine(cx - 40 * density, cy, cx - 20 * density, cy, ink)
        canvas.drawLine(cx + 20 * density, cy, cx + 40 * density, cy, ink)
        canvas.drawLine(cx, cy - 40 * density, cx, cy - 20 * density, ink)
        canvas.drawLine(cx, cy + 20 * density, cx, cy + 40 * density, ink)
        targets.forEach { target ->
            val rect = screenBox(target.bounds)
            ink.color = if (target.id != null && target.id == selectedId) Color.rgb(255, 210, 95) else Color.rgb(98, 241, 235)
            ink.style = Paint.Style.STROKE
            canvas.drawRoundRect(rect, 8 * density, 8 * density, ink)
            ink.style = Paint.Style.FILL
            ink.textSize = 12 * density
            canvas.drawText(target.label, rect.left.coerceAtLeast(4 * density), (rect.top - 8 * density).coerceAtLeast(16 * density), ink)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            onTarget(targets.filter { screenBox(it.bounds).contains(event.x, event.y) }
                .minByOrNull { it.bounds.width() * it.bounds.height() })
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
