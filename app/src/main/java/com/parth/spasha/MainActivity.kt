package com.parth.spasha

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.parth.spasha.databinding.ActivityMainBinding
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SPASHA (spaśa, स्पश) — a live camera that puts a track on everything in
 * frame: people, vehicles and carried objects get boxes, track IDs, trails
 * and a dwell timeline, all processed on-device. A track can be pinned as a
 * Person of Interest, a zone can be drawn on the view, and vehicle speed can
 * be measured once the user calibrates two ground lines.
 *
 * There is deliberately no face identification and no identity lookup for
 * live tracks.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CAMERA = 1001
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private val KEPT_LABELS = setOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck",
            "backpack", "handbag", "suitcase", "dog"
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var detector: ObjectDetector? = null
    private val tracker = Tracker()

    // Session log: lines already formatted as "HH:mm:ss  text".
    private val logLines = mutableListOf<String>()
    private var poiPinnedCount = 0
    private var zoneEventCount = 0

    private var selectedKey: String? = null

    // Zone drawing state. Points are in image (rotated bitmap) coordinates.
    private var zoneMode = false
    private val zonePoints = mutableListOf<PointF>()
    private var zoneClosed = false

    private var calibration: Calibration? = null

    @Volatile private var lastImageWidth = 0
    @Volatile private var lastImageHeight = 0
    @Volatile private var currentFps = 0
    private var fpsFrames = 0
    private var fpsWindowStartMs = System.currentTimeMillis()

    private val handler = Handler(Looper.getMainLooper())
    private val uiTicker = object : Runnable {
        override fun run() {
            updateHud()
            refreshInfoPanel()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        addLog("SESSION START — SPASHA LIVE")
        setupTabs()
        setupLiveControls()
        binding.overlayView.tapCallback = { sx, sy -> onOverlayTap(sx, sy) }

        handler.post(uiTicker)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            initDetector()
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
            )
            binding.infoText.text = "CAMERA PERMISSION NEEDED — ALLOW IT TO START LIVE TRACKING."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(uiTicker)
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        detector?.close()
        detector = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initDetector()
                startCamera()
            } else {
                binding.infoText.text =
                    "CAMERA PERMISSION DENIED — SPASHA CANNOT SEE WITHOUT THE CAMERA. GRANT IT IN APP SETTINGS AND REOPEN THE APP."
                binding.hudText.text = "CAM 01 · NO CAMERA"
                addLog("CAMERA PERMISSION DENIED")
            }
        }
    }

    // ------------------------------------------------------------------ tabs

    private fun setupTabs() {
        binding.tabLiveButton.setOnClickListener { showTab(0) }
        binding.tabLogButton.setOnClickListener { showTab(1) }
    }

    private fun showTab(tab: Int) {
        binding.liveContainer.visibility = if (tab == 0) View.VISIBLE else View.GONE
        binding.logContainer.visibility = if (tab == 1) View.VISIBLE else View.GONE
        binding.tabLiveButton.setBackgroundResource(
            if (tab == 0) R.drawable.bg_nav_selected else R.drawable.bg_nav_idle
        )
        binding.tabLogButton.setBackgroundResource(
            if (tab == 1) R.drawable.bg_nav_selected else R.drawable.bg_nav_idle
        )
        if (tab == 1) {
            renderLog()
        }
    }

    // ------------------------------------------------------------- live view

    private fun setupLiveControls() {
        binding.poiButton.setOnClickListener { togglePoi() }

        binding.zoneButton.setOnClickListener {
            if (zoneMode) {
                zoneMode = false
                binding.zoneButton.text = "ZONE"
            } else {
                // Starting a new zone replaces any existing one.
                zonePoints.clear()
                zoneClosed = false
                zoneMode = true
                binding.zoneButton.text = "ZONE: ON"
                binding.infoText.text =
                    "ZONE MODE — TAP ON THE CAMERA VIEW TO DROP AT LEAST 3 POINTS, THEN PRESS DONE."
            }
            binding.zoneDoneButton.isEnabled = zonePoints.size >= 3
            pushOverlayState()
        }

        binding.zoneDoneButton.setOnClickListener {
            if (zonePoints.size >= 3) {
                zoneClosed = true
                zoneMode = false
                binding.zoneButton.text = "ZONE"
                binding.zoneDoneButton.isEnabled = false
                addLog("ZONE SET (" + zonePoints.size + " POINTS)")
                pushOverlayState()
            }
        }

        binding.clearZoneButton.setOnClickListener {
            zonePoints.clear()
            zoneClosed = false
            zoneMode = false
            binding.zoneButton.text = "ZONE"
            binding.zoneDoneButton.isEnabled = false
            addLog("ZONE CLEARED")
            pushOverlayState()
        }

        binding.calButton.setOnClickListener {
            binding.calPanel.visibility =
                if (binding.calPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.calCloseButton.setOnClickListener {
            binding.calPanel.visibility = View.GONE
        }
        binding.calApplyButton.setOnClickListener {
            val distance = binding.calDistanceInput.text.toString().toDoubleOrNull()
            val limit = binding.calLimitInput.text.toString().toDoubleOrNull() ?: 40.0
            if (distance == null || distance <= 0.0) {
                Toast.makeText(this, "ENTER A REAL DISTANCE IN METRES", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            calibration = Calibration(distance, limit)
            addLog(
                String.format(
                    Locale.US, "CALIBRATION SET %.1f m · LIMIT %.0f km/h", distance, limit
                )
            )
            binding.calPanel.visibility = View.GONE
            pushOverlayState()
        }
    }

    private fun initDetector() {
        try {
            val baseOptions = BaseOptions.builder().setNumThreads(4).build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setScoreThreshold(0.5f)
                .setMaxResults(12)
                .build()
            detector = ObjectDetector.createFromFileAndOptions(this, MODEL_FILE, options)
            addLog("DETECTOR READY — EfficientDet Lite0 · ON-DEVICE")
        } catch (e: Exception) {
            addLog("DETECTOR FAILED: " + (e.message ?: "unknown error"))
            binding.infoText.text =
                "DETECTOR FAILED TO LOAD (" + (e.message ?: "?") + "). THE CAMERA WILL RUN WITHOUT TRACKING."
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzeFrame(imageProxy)
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
                addLog("CAMERA LIVE — CAM 01 (BACK)")
            } catch (e: Exception) {
                addLog("CAMERA FAILED: " + (e.message ?: "unknown error"))
                binding.infoText.text =
                    "CAMERA FAILED TO START (" + (e.message ?: "?") + ")."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val det = detector
        if (det == null) {
            imageProxy.close()
            return
        }
        var bitmap: Bitmap? = null
        try {
            val raw = imageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees
            imageProxy.close()
            if (raw == null) return
            bitmap = rotateBitmap(raw, rotation)

            val tensorImage = TensorImage()
            tensorImage.load(bitmap)
            val results = det.detect(tensorImage)

            val detections = mutableListOf<RawDetection>()
            for (detection in results) {
                if (detection.categories.isEmpty()) continue
                val category = detection.categories[0]
                val label = category.label.lowercase(Locale.US)
                if (label !in KEPT_LABELS) continue
                detections.add(
                    RawDetection(label, category.score, RectF(detection.boundingBox))
                )
            }

            val nowMs = System.currentTimeMillis()
            val frameW = bitmap.width
            val frameH = bitmap.height
            lastImageWidth = frameW
            lastImageHeight = frameH

            fpsFrames++
            if (nowMs - fpsWindowStartMs >= 1000L) {
                currentFps = (fpsFrames * 1000L / (nowMs - fpsWindowStartMs)).toInt()
                fpsFrames = 0
                fpsWindowStartMs = nowMs
            }

            val zonePolygon: List<PointF> =
                if (zoneClosed) ArrayList(zonePoints) else emptyList()
            val events: List<String>
            val snapshot: List<Track>
            synchronized(tracker) {
                events = tracker.update(
                    detections, nowMs, frameW, frameH, zonePolygon, calibration
                )
                snapshot = ArrayList(tracker.tracks)
            }
            val poiZoneEntry = events.any { it.startsWith("POI ENTERED ZONE") }

            runOnUiThread {
                for (event in events) {
                    if (event.startsWith("ZONE ENTRY") || event.startsWith("ZONE EXIT")) {
                        zoneEventCount++
                    }
                    addLog(event)
                }
                if (poiZoneEntry) {
                    vibrateBrief()
                }
                val overlay = binding.overlayView
                overlay.tracksSnapshot = snapshot
                overlay.imageWidth = frameW
                overlay.imageHeight = frameH
                overlay.zonePoints = ArrayList(zonePoints)
                overlay.zoneClosed = zoneClosed
                overlay.calibrationActive = calibration != null
                overlay.poiKey = tracker.poiKey
                overlay.invalidate()
            }
        } catch (e: Exception) {
            // A single bad frame must never kill the feed — drop it and move on.
            try {
                imageProxy.close()
            } catch (ignored: Exception) {
                // already closed
            }
        } finally {
            bitmap?.recycle()
        }
    }

    /** YUV_420_888 -> NV21 -> JPEG -> Bitmap. Dependency-free, works everywhere. */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0

        // Y plane, row by row (respecting rowStride).
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            if (rowStart + width > yBuffer.capacity()) break
            yBuffer.position(rowStart)
            yBuffer.get(nv21, pos, width)
            pos += width
        }

        // Interleaved VU (NV21), sampling the U/V planes with their strides.
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                if (vIndex >= vBuffer.capacity() || uIndex >= uBuffer.capacity()) continue
                if (pos + 1 >= nv21.size) break
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        val ok = yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
        if (!ok) return null
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) {
            src.recycle()
        }
        return rotated
    }

    // ------------------------------------------------------------ interaction

    private fun onOverlayTap(sx: Float, sy: Float) {
        if (lastImageWidth <= 0 || lastImageHeight <= 0) return
        val imagePoint = binding.overlayView.unmap(sx, sy)

        if (zoneMode) {
            zonePoints.add(imagePoint)
            binding.zoneDoneButton.isEnabled = zonePoints.size >= 3
            pushOverlayState()
            return
        }

        // Hit-test: smallest box containing the tap wins.
        var best: Track? = null
        var bestArea = Float.MAX_VALUE
        synchronized(tracker) {
            for (t in tracker.tracks) {
                if (t.box.contains(imagePoint.x, imagePoint.y)) {
                    val area = t.box.width() * t.box.height()
                    if (area < bestArea) {
                        bestArea = area
                        best = t
                    }
                }
            }
        }
        selectedKey = best?.key
        refreshInfoPanel()
    }

    private fun togglePoi() {
        val key = selectedKey ?: return
        synchronized(tracker) {
            val track = tracker.findTrack(key) ?: return
            if (tracker.poiKey == key) {
                tracker.poiKey = null
                addLog("POI UNPINNED " + track.displayName())
            } else {
                tracker.poiKey = key
                poiPinnedCount++
                addLog("POI PINNED " + track.displayName())
            }
        }
        pushOverlayState()
        refreshInfoPanel()
    }

    private fun pushOverlayState() {
        val overlay = binding.overlayView
        synchronized(tracker) {
            overlay.tracksSnapshot = ArrayList(tracker.tracks)
            overlay.poiKey = tracker.poiKey
        }
        overlay.imageWidth = lastImageWidth
        overlay.imageHeight = lastImageHeight
        overlay.zonePoints = ArrayList(zonePoints)
        overlay.zoneClosed = zoneClosed
        overlay.calibrationActive = calibration != null
        overlay.invalidate()
    }

    private fun refreshInfoPanel() {
        val key = selectedKey
        if (key == null) {
            binding.infoText.text = "TAP A TRACK TO INSPECT IT."
            binding.poiButton.isEnabled = false
            binding.poiButton.text = "PIN AS POI"
            return
        }
        val track: Track?
        val poiKeyNow: String?
        synchronized(tracker) {
            track = tracker.findTrack(key)
            poiKeyNow = tracker.poiKey
        }
        if (track == null) {
            selectedKey = null
            binding.infoText.text = "TRACK LOST — IT LEFT THE FRAME. TAP ANOTHER TRACK."
            binding.poiButton.isEnabled = false
            return
        }
        val dwellSec = track.dwellMs / 1000L
        val speedLine = when {
            track.lastSpeedKmh != null -> String.format(
                Locale.US, "SPEED: %.0f km/h%s", track.lastSpeedKmh,
                if (track.overLimit) " (OVER LIMIT)" else ""
            )
            calibration == null -> "SPEED: NOT CALIBRATED"
            else -> "SPEED: NO READING YET — CALIBRATED, WAITING FOR A LINE CROSSING"
        }
        val scoreAndRules = computeFlagScore(track)
        val rulesText = if (scoreAndRules.second.isEmpty()) {
            "No risk rules triggered"
        } else {
            scoreAndRules.second.joinToString("\n") { "· $it" }
        }
        val firstSeen = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(track.firstSeenMs))
        binding.infoText.text =
            track.displayName() +
                " · CONF " + (track.confidence * 100).toInt() + "%\n" +
                "DWELL " + dwellSec + "s · FIRST SEEN " + firstSeen + "\n" +
                speedLine + "\n" +
                "FLAG SCORE (rule-based): " + scoreAndRules.first + " / 100\n" +
                rulesText
        binding.poiButton.isEnabled = true
        binding.poiButton.text = if (poiKeyNow == key) "UNPIN" else "PIN AS POI"
    }

    private fun updateHud() {
        var persons = 0
        var vehicles = 0
        var total = 0
        synchronized(tracker) {
            total = tracker.tracks.size
            for (t in tracker.tracks) {
                if (t.classLabel == "person") persons++
                if (isVehicleLabel(t.classLabel)) vehicles++
            }
            // Clean up a POI pin whose track has retired.
            val pk = tracker.poiKey
            if (pk != null && tracker.findTrack(pk) == null) {
                tracker.poiKey = null
            }
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        binding.hudText.text = String.format(
            Locale.US,
            "CAM 01 · %s · FPS %d · PERSON %d · VEHICLE %d · TRACKS %d",
            time, currentFps, persons, vehicles, total
        )
    }

    @Suppress("DEPRECATION")
    private fun vibrateBrief() {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                vibrator.vibrate(200)
            }
        } catch (e: Exception) {
            // Vibration is a nicety, never a crash.
        }
    }

    // -------------------------------------------------------------------- log

    private fun addLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logLines.add("$time  $text")
        if (binding.logContainer.visibility == View.VISIBLE) {
            renderLog()
        }
    }

    private fun renderLog() {
        val unique: Int
        synchronized(tracker) {
            unique = tracker.uniqueTrackCount
        }
        binding.logStatsText.text = String.format(
            Locale.US,
            "SESSION — UNIQUE TRACKS %d · POIs PINNED %d · ZONE EVENTS %d",
            unique, poiPinnedCount, zoneEventCount
        )
        binding.logText.text = logLines.joinToString("\n")
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
