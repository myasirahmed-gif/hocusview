package com.hocus.vision

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

data class Target(val bounds: android.graphics.RectF, val id: Int?, val label: String, val confidence: Float?,
    val predicted: Boolean = false, val hits: Int = 1)
private data class Capture(val id: Int, val image: Bitmap, val vector: FloatArray, val name: String,
    val subtitle: String, val confidence: Float, val time: Long)
private data class SceneFrame(val image: Bitmap, val light: Float, val time: Long)

class MainActivity : ComponentActivity() {
    private val cyan = Color.rgb(104, 243, 224)
    private val white = Color.rgb(235, 247, 250)
    private val muted = Color.rgb(144, 169, 182)
    private lateinit var preview: PreviewView
    private lateinit var hud: HudView
    private lateinit var status: TextView
    private lateinit var telemetry: TextView
    private lateinit var targetName: TextView
    private lateinit var targetInfo: TextView
    private lateinit var targetImage: ImageView
    private lateinit var confidence: ProgressBar
    private lateinit var zoomLabel: TextView
    private lateinit var zoom: SeekBar
    private lateinit var permissionButton: TextView
    private lateinit var pauseButton: TextView
    private lateinit var assistButton: TextView
    private lateinit var nightButton: TextView
    private lateinit var lampButton: TextView
    private lateinit var platePopup: TextView
    private lateinit var expandedTools: LinearLayout
    @Volatile private var nightMode = false
    private var lampOn = false
    private var sceneFrame: SceneFrame? = null
    private var lastSceneAt = 0L
    private var nightConfigVersion = 0
    private val networkWorker = Executors.newSingleThreadExecutor()
    private val onlineBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val apiStore by lazy { ApiKeyStore(this) }
    private val gemini by lazy { GeminiClient() }
    private val plateDelegate = lazy { PlateScanner { reading -> uiExecutor.execute { if (!disposed && scanning) receivePlate(reading) } } }
    private val plates by plateDelegate
    private val plateReadings = mutableMapOf<Int, PlateReading>()
    private var platePopupKey = ""
    private var plateMutedUntil = 0L
    private var plateHide: Runnable? = null
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var selectedId: Int? = null
    @Volatile private var scanning = true
    @Volatile private var disposed = false
    @Volatile private var assisted = true
    @Volatile private var resetRequested = false
    private val manualRequest = AtomicReference<Box?>(null)
    private val generation = AtomicInteger(0)
    private val executor = Executors.newSingleThreadExecutor()
    private val uiExecutor by lazy { ContextCompat.getMainExecutor(this) }
    private val detectorDelegate = lazy { LocalDetector(this) }
    private val detector by detectorDelegate
    private val classifierDelegate = lazy { FocusClassifier(this) }
    private val classifier by classifierDelegate
    private val memoryDelegate = lazy { VisualMemory(this) }
    private val memory by memoryDelegate
    private val tracker = MotionTracker()
    private val follower = PatchFollower()
    private var workerTracks: List<TrackSnapshot> = emptyList()
    private var followerId: Int? = null
    private var manualId: Int? = null
    private var featureId: Int? = null
    private var lastFeatureAt = 0L
    private var featureCapture: Capture? = null
    private var displayCapture: Capture? = null
    private var catalog: List<CatalogEntry> = emptyList()
    private var learned: List<LearnedClass> = emptyList()
    private var lastImageSize = Pair(0, 0)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionHelp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        assisted = getPreferences(MODE_PRIVATE).getBoolean("assisted", true)
        nightMode = getPreferences(MODE_PRIVATE).getBoolean("night", false)
        buildInterface()
        executor.execute {
            try {
                val entries = classifier.catalog; val saved = memory.classes()
                uiExecutor.execute { if (!disposed) { catalog = entries; learned = saved } }
            } catch (error: Exception) { report("Object library could not load: ${error.localizedMessage}") }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun buildInterface() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(3, 12, 18)) }
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        hud = HudView(this) { target, x, y ->
            if (scanning) {
                if (target != null) {
                    generation.incrementAndGet()
                    selectedId = target.id; displayCapture = null; hud.selectedId = target.id
                    hud.thumbnail = null
                    targetName.text = title(target.label); targetInfo.text = "ACQUIRING VISUAL SIGNATURE"
                    focusAt(x, y)
                } else requestManual(hud.boxAround(x, y))
            }
        }
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brandRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brandRow.addView(label("+", 22f, cyan).apply {
            gravity = Gravity.CENTER; background = surface(0xCC0A202A.toInt(), 12, cyan)
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        brand.addView(label("HOCUS VISION", 15f, white).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.08f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        brand.addView(micro("VISION + GEMINI  /  03", muted).apply { textSize = 7f })
        brandRow.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        pauseButton = action("PAUSE", false) { toggleScanning() }
        brandRow.addView(pauseButton, LinearLayout.LayoutParams(dp(55), dp(32)))
        brandRow.addView(action("SET", false) { showApiSettings() }, LinearLayout.LayoutParams(dp(36), dp(32)).apply { leftMargin = dp(5) })
        header.addView(brandRow)
        val liveRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        status = micro("STARTING CAMERA", cyan).apply {
            background = surface(0xCC071C24.toInt(), 20); setPadding(dp(8), dp(5), dp(8), dp(5)); textSize = 8f
        }
        liveRow.addView(status)
        telemetry = micro("100 OBJECT LIBRARY", muted).apply { gravity = Gravity.END; textSize = 8f }
        liveRow.addView(telemetry, LinearLayout.LayoutParams(0, -2, 1f)); header.addView(liveRow)
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12), dp(10), dp(12), 0) })
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = surface(0xE8081923.toInt(), 18, 0x334F7D8D)
            setPadding(dp(10), dp(8), dp(10), dp(8)); elevation = dp(8).toFloat()
        }
        val targetRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        targetImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = surface(0xFF102C37.toInt(), 12, 0x444F7D8D)
            clipToOutline = true; contentDescription = "Selected object live thumbnail"; setImageResource(R.drawable.ic_hocus)
        }
        targetRow.addView(targetImage, LinearLayout.LayoutParams(dp(36), dp(36)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), 0, 0, 0) }
        targetName = label("Tap an object to lock", 14f, white).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        info.addView(targetName)
        targetInfo = micro("LOCAL TRACKING / SET FOR GEMINI", muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textSize = 8f }
        info.addView(targetInfo)
        confidence = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(cyan)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF173540.toInt())
        }
        info.addView(confidence, LinearLayout.LayoutParams(-1, dp(2)).apply { topMargin = dp(3) })
        targetRow.addView(info, LinearLayout.LayoutParams(0, -2, 1f)); dock.addView(targetRow)
        expandedTools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val zoomRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, 0) }
        zoomLabel = micro("1.0x", white).apply { gravity = Gravity.CENTER_VERTICAL; setOnClickListener { resetZoom() }; contentDescription = "Reset zoom to 1x" }
        zoomRow.addView(zoomLabel, LinearLayout.LayoutParams(dp(40), dp(30)))
        zoom = SeekBar(this).apply {
            max = 100; isEnabled = false; progressTintList = ColorStateList.valueOf(cyan)
            thumbTintList = ColorStateList.valueOf(cyan); progressBackgroundTintList = ColorStateList.valueOf(0xFF24404C.toInt())
            contentDescription = "Camera zoom"
        }
        zoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) camera?.cameraControl?.setLinearZoom(value / 100f)
            }
        })
        zoomRow.addView(zoom, LinearLayout.LayoutParams(0, dp(30), 1f)); zoomRow.addView(micro("ZOOM", muted)); expandedTools.addView(zoomRow)
        val actions = LinearLayout(this).apply { setPadding(0, dp(7), 0, 0) }
        actions.addView(action("LOCK", true) {
            if (scanning) requestManual(hud.boxAround(hud.width / 2f, hud.height / 2f))
        }, LinearLayout.LayoutParams(0, dp(34), 1f))
        actions.addView(action("ANALYZE", false) { requestOnline(false) }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(5) })
        actions.addView(action("PATH", false) { requestOnline(true) }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(5) })
        actions.addView(action("TOOLS", false) { expandedTools.visibility = if (expandedTools.visibility == View.GONE) View.VISIBLE else View.GONE }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(5) })
        dock.addView(actions)
        val secondary = LinearLayout(this)
        secondary.addView(action("TEACH", false) { teachTarget() }, LinearLayout.LayoutParams(0, dp(32), 1f))
        secondary.addView(action("LIBRARY", false) { showLibrary() }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(5) })
        expandedTools.addView(secondary)
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        footer.addView(micro("LOCAL / AI ON TAP", muted), LinearLayout.LayoutParams(0, -2, 1f))
        assistButton = micro(if (assisted) "ASSIST ON" else "ASSIST OFF", cyan).apply {
            setPadding(dp(12), dp(8), 0, dp(8)); setOnClickListener { toggleAssisted() }; contentDescription = "Toggle assisted learning"
        }
        footer.addView(assistButton); expandedTools.addView(footer); dock.addView(expandedTools)
        root.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
        val nightRail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nightButton = action(if (nightMode) "NIGHT ON" else "NIGHT", false) {
            nightMode = !nightMode; getPreferences(MODE_PRIVATE).edit().putBoolean("night", nightMode).apply(); applyNightMode()
        }
        lampButton = action("LAMP", false) { toggleLamp() }
        nightRail.addView(nightButton, LinearLayout.LayoutParams(dp(58), dp(32)))
        nightRail.addView(lampButton, LinearLayout.LayoutParams(dp(58), dp(32)).apply { topMargin = dp(6) })
        root.addView(nightRail, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(8) })
        platePopup = micro("", 0xFFFFCD74.toInt()).apply {
            visibility = View.GONE; textSize = 10f; maxLines = 3
            background = surface(0xED16212A.toInt(), 12, 0x77FFCD74); setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { plateMutedUntil = SystemClock.elapsedRealtime() + 8000; visibility = View.GONE }
            contentDescription = "Vehicle plate popup. Tap to dismiss."
        }
        root.addView(platePopup, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12), dp(82), dp(12), 0) })
        permissionButton = action("ENABLE CAMERA", true) { requestCamera() }.apply { visibility = View.GONE }
        root.addView(permissionButton, FrameLayout.LayoutParams(dp(220), dp(54), Gravity.CENTER))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (header.layoutParams as FrameLayout.LayoutParams).apply { topMargin = safe.top + dp(10); header.layoutParams = this }
            (dock.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = safe.bottom + dp(10); dock.layoutParams = this }
            hud.safeTop = safe.top + dp(80); hud.safeBottom = safe.bottom + dp(110)
            insets
        }
        header.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            hud.safeTop = bottom + dp(8)
            (platePopup.layoutParams as FrameLayout.LayoutParams).apply { topMargin = bottom + dp(8); platePopup.layoutParams = this }
        }
        dock.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ -> hud.safeBottom = root.height - top + dp(8) }
        setContentView(root)
        WindowInsetsControllerCompat(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        preview.setLayerType(View.LAYER_TYPE_HARDWARE, if (nightMode) Paint().apply { colorFilter = NightVision.previewFilter } else null)
    }

    private fun requestManual(box: Box?) {
        if (box == null) { report("Wait for the camera to start."); return }
        generation.incrementAndGet()
        displayCapture = null; hud.thumbnail = null; manualRequest.set(box)
        targetName.text = "Acquiring target"; targetInfo.text = "HOLD THE CAMERA STEADY"
    }
    private fun toggleScanning() {
        scanning = !scanning; generation.incrementAndGet(); selectedId = null; resetRequested = true
        manualRequest.set(null)
        displayCapture = null; hud.selectedId = null; hud.thumbnail = null; hud.targets = emptyList()
        platePopup.visibility = View.GONE; plateReadings.clear(); hud.plateLabels = emptyMap()
        pauseButton.text = if (scanning) "PAUSE" else "RESUME"
        status.text = if (scanning) "SCANNING" else "SCAN PAUSED"
        targetName.text = if (scanning) "Choose your target" else "Scan paused"
        targetInfo.text = "CAMERA PREVIEW REMAINS LIVE"; confidence.progress = 0
        targetImage.setImageResource(R.drawable.ic_hocus)
    }
    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) permission.launch(Manifest.permission.CAMERA)
        else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
    private fun showPermissionHelp() {
        status.text = "CAMERA ACCESS NEEDED"; targetInfo.text = "ALLOW CAMERA ACCESS IN APP SETTINGS"; permissionButton.visibility = View.VISIBLE
    }
    private fun startCamera() {
        permissionButton.visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get(); provider = cameraProvider
                val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                hud.mirrored = selector == CameraSelector.DEFAULT_FRONT_CAMERA
                val livePreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val resolution = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(
                    android.util.Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
                val analyzer = ImageAnalysis.Builder().setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis = analyzer
                analyzer.setAnalyzer(executor) { frame ->
                    try {
                        if (disposed || !scanning) return@setAnalyzer
                        val bitmap = frame.toBitmap()
                        val rotation = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
                        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotation, true)
                        try { processFrame(upright) } finally { if (upright !== bitmap) upright.recycle(); bitmap.recycle() }
                    } catch (error: Exception) {
                        uiExecutor.execute { if (!disposed && scanning) { status.text = "VISION UNAVAILABLE"; targetInfo.text = error.localizedMessage } }
                    } finally { frame.close() }
                }
                cameraProvider.unbindAll(); camera = cameraProvider.bindToLifecycle(this, selector, livePreview, analyzer)
                camera?.cameraInfo?.cameraState?.observe(this) { state -> if (state.type == CameraState.Type.OPEN) applyNightMode() }
                camera?.cameraInfo?.zoomState?.observe(this) { state ->
                    zoom.isEnabled = state.maxZoomRatio > state.minZoomRatio; zoom.progress = (state.linearZoom * 100).toInt()
                    zoomLabel.text = String.format(Locale.US, "%.1fx", state.zoomRatio)
                }
                status.text = "SCANNING"
            } catch (error: Exception) {
                status.text = "CAMERA UNAVAILABLE"; targetInfo.text = error.localizedMessage
                permissionButton.text = "RETRY CAMERA"; permissionButton.visibility = View.VISIBLE
            }
        }, uiExecutor)
    }

    private fun processFrame(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime(); val started = System.nanoTime(); val epoch = generation.get()
        val rawLight = NightVision.luminance(bitmap)
        val snapshot = if (now - lastSceneAt > 700) {
            lastSceneAt = now
            val image = cropObject(bitmap, Box(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), 960)
            val enhanced = if (nightMode && rawLight < 80) NightVision.enhance(image).also { image.recycle() } else image
            SceneFrame(enhanced, rawLight, now)
        } else null
        if (resetRequested || lastImageSize != (bitmap.width to bitmap.height)) {
            tracker.clear(); follower.clear(); workerTracks = emptyList(); followerId = null; manualId = null; featureCapture = null
            resetRequested = false; lastImageSize = bitmap.width to bitmap.height
        }
        val request = manualRequest.getAndSet(null)
        if (request != null) {
            selectedId = tracker.seed(Observation(request, "Unknown object", 0.8f, appearance(bitmap, request)), now)
            manualId = selectedId
            follower.start(bitmap, request, now); followerId = selectedId
        }
        if (selectedId != followerId || (!follower.active && selectedId != null)) {
            follower.clear()
            workerTracks.firstOrNull { it.id == selectedId && !it.predicted }?.let { follower.start(bitmap, it.box, now) }
            followerId = selectedId
        }
        val detections = detector.detect(bitmap)
        val observations = detections.map { Observation(it.bounds.box(), it.label, it.confidence ?: 0f, appearance(bitmap, it.bounds.box())) }.toMutableList()
        val located = if (selectedId != null) follower.locate(bitmap, now) else null
        if (located != null && (manualId == selectedId || observations.none { it.box.iou(located.first) > 0.12f })) {
            val compatible = detections.filter { it.bounds.box().iou(located.first) > 0.25f }.maxByOrNull { it.confidence ?: 0f }
            val name = compatible?.label ?: workerTracks.firstOrNull { it.id == selectedId }?.label ?: "Unknown object"
            observations.add(Observation(located.first, name, located.second, appearance(bitmap, located.first), selectedId))
        }
        val tracks = tracker.update(observations, now, selectedId); workerTracks = tracks
        plates.scan(bitmap, tracks, now)
        val selected = tracks.firstOrNull { it.id == selectedId }
        if (selected != null && !selected.predicted && located != null && selected.box.iou(located.first) > 0.2f && detections.any { it.bounds.box().iou(selected.box) > 0.3f }) {
            follower.start(bitmap, selected.box, now)
        }
        var libraryChanged = false
        if (selected != null && !selected.predicted && (featureId != selected.id || now - lastFeatureAt > 450)) {
            val crop = cropObject(bitmap, selected.box); val result = classifier.classify(crop); val match = memory.match(result.vector)
            val name = match?.name ?: result.name?.takeIf { manualId == selected.id || selected.label == "Unknown object" || result.confidence >= 0.6f } ?: selected.label
            val score = match?.similarity ?: if (name == result.name) result.confidence else selected.confidence
            val subtitle = if (match != null) "LEARNED / ${(match.similarity * 100).toInt()}% VISUAL MATCH"
                else if (name == "Unknown object") "UNNAMED / TAP TEACH TO LEARN"
                else if (name != result.name && detections.none { it.bounds.box().iou(selected.box) > 0.3f }) "${(score * 100).toInt()}% PATCH MATCH"
                else "${(score * 100).toInt()}% MODEL CONFIDENCE"
            featureCapture = Capture(selected.id, crop, result.vector, name, subtitle, score, now)
            featureId = selected.id; lastFeatureAt = now
            if (assisted && match != null) {
                libraryChanged = runCatching { memory.assist(match, result.vector, crop, selected.hits, now) }
                    .onFailure { report("Could not save an assisted view: ${it.localizedMessage}") }.getOrDefault(false)
            }
        }
        val capture = featureCapture?.takeIf { it.id == selected?.id }
        val savedClasses = if (libraryChanged) memory.classes() else null
        val visible = tracks.filter { it.id == selectedId || selected == null || manualId != selectedId || it.box.iou(selected.box) < 0.4f }
        val targets = visible.map { track -> Target(track.box.rect(), track.id, if (capture?.id == track.id) capture.name else track.label,
            track.confidence, track.predicted, track.hits) }
        val frameWidth = bitmap.width; val frameHeight = bitmap.height
        val ms = ((System.nanoTime() - started) / 1_000_000).toInt()
        uiExecutor.execute {
            if (disposed || !scanning || generation.get() != epoch) return@execute
            hud.imageWidth = frameWidth; hud.imageHeight = frameHeight; hud.targets = targets; hud.selectedId = selectedId
            if (snapshot != null) sceneFrame = snapshot
            hud.plateLabels = plateReadings.mapValues { (_, reading) -> reading.text?.let { (if (reading.confirmed) "OCR " else "? ") + it } ?: "PLATE: SCANNING" }
            telemetry.text = if (rawLight < 22) "DARK / USE LAMP" else "${targets.count { !it.predicted }} TRACKS / ${ms}ms"
            val active = targets.firstOrNull { it.id == selectedId }
            if (active == null && selectedId != null) {
                selectedId = null; hud.selectedId = null; hud.thumbnail = null; displayCapture = null
                status.text = "TARGET LOST"; targetName.text = "Reacquire your target"
                targetInfo.text = "TAP AN OBJECT TO LOCK AGAIN"; confidence.progress = 0
            } else if (active != null) {
                status.text = if (active.predicted) "REACQUIRING" else "TARGET LOCKED"; targetName.text = title(active.label)
                if (capture != null && capture.id == selectedId) {
                    if (displayCapture !== capture) {
                        displayCapture = capture; targetImage.setImageBitmap(capture.image); hud.thumbnail = capture.image
                    }
                    targetInfo.text = "TRACK ${active.id} / ${if (active.predicted) "PREDICTED POSITION" else capture.subtitle}"
                    confidence.progress = (capture.confidence * 100).toInt()
                }
            } else status.text = "SCANNING"
            if (onlineBusy.get()) status.text = "GEMINI / WORKING"
            if (savedClasses != null) { learned = savedClasses; report("Added a new confirmed-object view to memory") }
        }
    }

    private fun teachTarget() {
        val capture = displayCapture
        if (!scanning || capture == null || capture.id != selectedId ||
            hud.targets.firstOrNull { it.id == selectedId }?.predicted != false || SystemClock.elapsedRealtime() - capture.time > 2000) {
            report("Lock a visible object and hold steady before teaching it."); return
        }
        val image = capture.image.copy(Bitmap.Config.ARGB_8888, false)
        val field = AutoCompleteTextView(this).apply {
            hint = "Object name, e.g. my blue tool"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, learned.map { it.name }))
            threshold = 0
            setOnClickListener { if (learned.isNotEmpty()) showDropDown() }
            setText(if (capture.name == "Unknown object") "" else capture.name); selectAll(); setSingleLine(true)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this).setTitle("Teach this object")
            .setMessage("Save this view with its name. Teach three different angles to enable assisted learning for this object.")
            .setView(field).setNegativeButton("Cancel") { _, _ -> image.recycle() }
            .setPositiveButton("Save example") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isBlank()) { image.recycle(); report("Enter a name to save the example.") }
                else executor.execute {
                    try {
                        memory.save(name, capture.vector, image); featureId = null
                        val saved = memory.classes()
                        uiExecutor.execute { if (!disposed) { learned = saved; report("Learned $name. Add another angle with Teach.") } }
                    } catch (error: Exception) { report(error.localizedMessage ?: "Could not save example") }
                    finally { image.recycle() }
                }
            }.setOnCancelListener { image.recycle() }.show()
    }
    private fun showLibrary() {
        AlertDialog.Builder(this).setTitle("Object library")
            .setItems(arrayOf("100 built-in categories", "Learned objects (${learned.size}/100)", "Assisted learning: ${if (assisted) "ON" else "OFF"}")) { _, which ->
                when (which) {
                    0 -> if (catalog.isEmpty()) report("The library is still loading") else {
                        AlertDialog.Builder(this).setTitle("80 automatic + 20 focused categories")
                            .setItems(catalog.map { "${title(it.name)} / ${if (it.mode == "scene") "AUTO" else "FOCUS"}" }.toTypedArray()) { _, index ->
                                val entry = catalog[index]
                                AlertDialog.Builder(this).setTitle(title(entry.name))
                                    .setMessage(if (entry.mode == "scene") "Detected automatically in the live camera view."
                                        else "Center this object, tap Lock, and hold steady. Focus recognition works on the selected crop.")
                                    .setPositiveButton("Got it", null).show()
                            }.setNegativeButton("Close", null).show()
                    }
                    1 -> showLearned()
                    2 -> toggleAssisted()
                }
            }.setNegativeButton("Close", null).show()
    }
    private fun showLearned() {
        if (learned.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Your visual memory")
                .setMessage("Lock an object and tap Teach to save its name and appearance. Images stay on this phone.")
                .setPositiveButton("Got it", null).show(); return
        }
        val snapshot = learned.toList()
        AlertDialog.Builder(this).setTitle("Learned objects")
            .setItems(snapshot.map { "${it.name} / ${it.samples} views (${it.confirmed} taught)" }.toTypedArray()) { _, index ->
                val item = snapshot[index]
                AlertDialog.Builder(this).setTitle(item.name)
                    .setMessage("${item.confirmed} confirmed examples. ${item.samples - item.confirmed} assisted views. To correct a label, forget it and teach the correct name.")
                    .setNegativeButton("Keep", null).setPositiveButton("Forget object") { _, _ ->
                        executor.execute {
                            try {
                                memory.delete(item.name); featureId = null
                                val saved = memory.classes()
                                uiExecutor.execute { if (!disposed) { learned = saved; report("Forgot ${item.name}") } }
                            } catch (error: Exception) { report(error.localizedMessage ?: "Could not remove object") }
                        }
                    }.show()
            }.setNegativeButton("Close", null).show()
    }
    private fun toggleAssisted() {
        assisted = !assisted; getPreferences(MODE_PRIVATE).edit().putBoolean("assisted", assisted).apply()
        assistButton.text = if (assisted) "ASSIST ON" else "ASSIST OFF"
        report(if (assisted) "Assisted learning on: requires three taught angles per object." else "Assisted learning paused. Taught examples are kept.")
    }
    private fun applyNightMode() {
        val version = ++nightConfigVersion
        nightButton.text = if (nightMode) "NIGHT ON" else "NIGHT"
        preview.setLayerType(View.LAYER_TYPE_HARDWARE, if (nightMode) Paint().apply { colorFilter = NightVision.previewFilter } else null)
        val active = camera ?: return
        fun exposureFallback() {
            val exposure = active.cameraInfo.exposureState
            if (exposure.isExposureCompensationSupported) {
                val step = exposure.exposureCompensationStep.toFloat()
                val index = if (nightMode && step > 0) (0.7f / step).toInt().coerceIn(exposure.exposureCompensationRange.lower, exposure.exposureCompensationRange.upper) else 0
                active.cameraControl.setExposureCompensationIndex(index)
            }
        }
        fun configureBoost() {
            if (disposed || version != nightConfigVersion) return
            if (active.cameraInfo.isLowLightBoostSupported) {
                val future = active.cameraControl.enableLowLightBoostAsync(nightMode && !lampOn)
                future.addListener({ if (!disposed && version == nightConfigVersion) runCatching { future.get() }.onFailure { exposureFallback() } }, uiExecutor)
            } else exposureFallback()
        }
        if (active.cameraInfo.hasFlashUnit()) {
            val requested = lampOn
            val torch = active.cameraControl.enableTorch(requested)
            torch.addListener({
                if (!disposed && version == nightConfigVersion) {
                    runCatching { torch.get() }.onFailure { lampOn = false; report("Lamp unavailable on this camera.") }
                    lampButton.text = if (lampOn) "LAMP ON" else "LAMP"
                    configureBoost()
                }
            }, uiExecutor)
        } else configureBoost()
    }
    private fun toggleLamp() {
        if (camera?.cameraInfo?.hasFlashUnit() != true) { report("This camera has no lamp. Use an external light."); return }
        lampOn = !lampOn; applyNightMode()
    }
    private fun receivePlate(reading: PlateReading) {
        if (hud.targets.none { it.id == reading.id && !it.predicted }) return
        plateReadings[reading.id] = reading
        val liveIds = hud.targets.mapNotNull { it.id }.toSet()
        plateReadings.keys.retainAll(liveIds)
        hud.plateLabels = plateReadings.mapValues { (_, p) -> p.text?.let { (if (p.confirmed) "OCR " else "? ") + it } ?: "PLATE: SCANNING" }
        val key = "${reading.id}:${reading.text}:${reading.confirmed}"
        if (SystemClock.elapsedRealtime() < plateMutedUntil) return
        if (key == platePopupKey && platePopup.visibility != View.VISIBLE) return
        platePopup.text = "${reading.vehicle.uppercase()} #${reading.id}  /  ${reading.text ?: "PLATE SCAN"}\n${reading.state}"
        if (key != platePopupKey) {
            platePopupKey = key
            platePopup.visibility = View.VISIBLE
            plateHide?.let { platePopup.removeCallbacks(it) }
            plateHide = Runnable { platePopup.visibility = View.GONE }.also { platePopup.postDelayed(it, 5000) }
        }
    }
    private fun showApiSettings() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(4)) }
        val hasKey = runCatching { apiStore.load() != null }.getOrDefault(false)
        form.addView(label("Images go to Google only when Analyze or Path is tapped. Model quotas and charges depend on your account.", 12f, muted))
        val key = EditText(this).apply {
            hint = if (hasKey) "Key saved — leave blank to keep" else "Paste Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val model = EditText(this).apply { hint = "Gemini image-capable model"; setSingleLine(true); setText(apiStore.model) }
        val allow = CheckBox(this).apply { text = "Allow these requested image uploads to Google"; isChecked = apiStore.consent }
        form.addView(key); form.addView(model); form.addView(allow)
        form.addView(action("REMOVE SAVED KEY", false) {
            runCatching { apiStore.clear(); key.setText(""); allow.isChecked = false }.onFailure { report("Could not remove key.") }
        }, LinearLayout.LayoutParams(-1, dp(34)))
        val dialog = AlertDialog.Builder(this).setTitle("Gemini settings").setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Close", null).setNeutralButton("Test / Models", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    require(ApiKeyStore.validModel(model.text.toString().trim())) { "Enter a valid Gemini model ID." }
                    if (key.text.isNotBlank()) apiStore.save(key.text.toString())
                    require(apiStore.load() != null) { "Enter your API key first." }
                    apiStore.model = model.text.toString().trim(); apiStore.consent = allow.isChecked
                }.onSuccess { key.setText(""); dialog.dismiss(); report("Gemini settings saved on this device.") }
                    .onFailure { report(it.localizedMessage ?: "Could not save Gemini settings.") }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val value = key.text.toString().trim().ifBlank { runCatching { apiStore.load() }.getOrNull() ?: "" }
                if (value.length < 16) report("Enter your API key first.")
                else if (!onlineBusy.compareAndSet(false, true)) report("An online request is already running.")
                else networkWorker.execute {
                    try {
                        val models = gemini.models(value)
                        uiExecutor.execute {
                            if (!disposed && dialog.isShowing) {
                                if (models.isEmpty()) report("Key accepted, but no supported models were listed.")
                                else AlertDialog.Builder(this).setTitle("Available models — quotas vary")
                                    .setItems(models.toTypedArray()) { _, index -> model.setText(models[index]) }.setNegativeButton("Close", null).show()
                            }
                        }
                    } catch (error: Exception) { report(error.localizedMessage ?: "Gemini connection failed.") }
                    finally { onlineBusy.set(false) }
                }
            }
        }
        dialog.show()
    }
    private fun requestOnline(pathScan: Boolean) {
        if (!scanning) { report("Resume scanning first."); return }
        val scene = sceneFrame
        if (scene == null || SystemClock.elapsedRealtime() - scene.time > 2000) { report("Wait for a fresh camera frame."); return }
        if (pathScan && scene.light < 22) {
            AlertDialog.Builder(this).setTitle("Too dark to inspect a path")
                .setMessage("Stop and turn on the lamp or use an external light. Floor and stairs cannot be reliably inferred from darkness.")
                .setPositiveButton("Lamp") { _, _ -> if (!lampOn) toggleLamp() }.setNegativeButton("Close", null).show()
            return
        }
        val key = runCatching { apiStore.load() }.getOrNull()
        if (key == null) { showApiSettings(); return }
        if (!apiStore.consent) {
            AlertDialog.Builder(this).setTitle(if (pathScan) "Send path snapshot to Gemini?" else "Send image to Gemini?")
                .setMessage("Google will receive ${if (pathScan) "the full camera snapshot" else "the selected object crop, or full frame if nothing is selected"}. Path findings are uncertain snapshot observations, not safe walking directions.")
                .setPositiveButton("Allow and analyze") { _, _ -> apiStore.consent = true; requestOnline(pathScan) }.setNegativeButton("Cancel", null).show()
            return
        }
        if (!onlineBusy.compareAndSet(false, true)) { report("Gemini is still analyzing the previous request."); return }
        val target = displayCapture?.takeIf { it.id == selectedId && SystemClock.elapsedRealtime() - it.time < 1500 && hud.targets.any { track -> track.id == it.id && !track.predicted } }
        val image = (if (!pathScan && target != null) target.image else scene.image).copy(Bitmap.Config.ARGB_8888, false)
        val captured = if (!pathScan && target != null) target.time else scene.time
        val model = apiStore.model
        status.text = if (pathScan) "PATH SNAPSHOT / ANALYZING" else "GEMINI / ANALYZING"
        report(if (pathScan) "Stop and hold still. Analyzing a snapshot, not a live route." else "Analyzing requested image with Gemini.")
        networkWorker.execute {
            try {
                val response = gemini.analyze(image, key, model, pathScan)
                val analysis = if (pathScan) SceneAnalysis.parse(response) else null
                uiExecutor.execute {
                    if (disposed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) { image.recycle(); return@execute }
                    showCloudResult(image, response, analysis, captured)
                }
            } catch (error: Exception) { image.recycle(); report(error.localizedMessage ?: "Gemini analysis failed. Local tracking remains available.") }
            finally { onlineBusy.set(false) }
        }
    }
    private fun showCloudResult(image: Bitmap, response: JSONObject, scene: SceneAnalysis?, captured: Long) {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(8)) }
        val age = (SystemClock.elapsedRealtime() - captured) / 1000
        column.addView(label("SNAPSHOT / captured ${age}s ago", 10f, cyan))
        if (scene != null) {
            column.addView(SceneMapView(this, image, scene), LinearLayout.LayoutParams(-1, dp(300)))
            column.addView(label(scene.guidance, 15f, 0xFFFFCD74.toInt()))
            column.addView(label("Relative image sketch only. Cyan = floor-like region, amber = possible hazard. No distances or safe route.", 11f, muted))
        } else column.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(image) }, LinearLayout.LayoutParams(-1, dp(170)))
        val lines = response.optJSONArray("details")
        val details = if (lines == null) "" else (0 until minOf(lines.length(), 5)).joinToString("\n") { "• ${lines.optString(it).take(250)}" }
        column.addView(label((scene?.summary ?: response.optString("summary").take(800)) + "\n" + details + "\n" + response.optString("uncertainty").take(400), 14f, white))
        column.addView(micro("AI OBSERVATIONS / VERIFY DIRECTLY", muted))
        scroll.addView(column)
        AlertDialog.Builder(this).setTitle(if (scene == null) "Gemini analysis" else "Path snapshot — not navigation")
            .setView(scroll).setPositiveButton("Close", null).show()
    }
    private fun focusAt(x: Float, y: Float) {
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(preview.meteringPointFactory.createPoint(x, y)).build())
    }
    private fun resetZoom() {
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        camera?.cameraControl?.setZoomRatio(1f.coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }
    private fun report(message: String) { uiExecutor.execute { if (!disposed) Toast.makeText(this, message, Toast.LENGTH_LONG).show() } }
    override fun onDestroy() {
        disposed = true; analysis?.clearAnalyzer(); provider?.unbindAll()
        plateHide?.let { platePopup.removeCallbacks(it) }
        executor.execute { if (detectorDelegate.isInitialized()) detector.close(); if (classifierDelegate.isInitialized()) classifier.close(); if (plateDelegate.isInitialized()) plates.close() }
        networkWorker.shutdownNow()
        executor.shutdown(); super.onDestroy()
    }
    private fun title(value: String) = value.replaceFirstChar { it.uppercase() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); typeface = Typeface.create("sans-serif", Typeface.NORMAL); includeFontPadding = false
    }
    private fun micro(value: String, color: Int) = label(value, 10f, color).apply { typeface = Typeface.MONOSPACE; letterSpacing = 0.04f }
    private fun surface(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun action(value: String, primary: Boolean, click: () -> Unit) = label(value, 10f, if (primary) 0xFF062029.toInt() else white).apply {
        gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.05f
        background = RippleDrawable(ColorStateList.valueOf(0x335FFFFF), surface(if (primary) cyan else 0xFF142D38.toInt(), 12), null)
        isClickable = true; isFocusable = true; contentDescription = value; setOnClickListener { click() }
    }
}
