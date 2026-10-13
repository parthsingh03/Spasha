package com.parth.spasha

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.max

/**
 * Draws track boxes, trails, the user zone and the calibration lines over
 * the camera preview.
 *
 * Coordinate mapping follows the TensorFlow Lite object-detection example:
 * the camera image is scaled with a fill (centre-crop) factor —
 * scaleFactor = max(viewWidth / imageWidth, viewHeight / imageHeight) —
 * and centred, so detection boxes (in rotated-bitmap coordinates) land on
 * the right place on screen.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var tracksSnapshot: List<Track> = emptyList()
    var imageWidth: Int = 0
    var imageHeight: Int = 0
    var zonePoints: List<PointF> = emptyList()
    var zoneClosed: Boolean = false
    var calibrationActive: Boolean = false
    var poiKey: String? = null

    /** Called with raw view (screen) coordinates on tap. */
    var tapCallback: ((Float, Float) -> Unit)? = null

    private val boxPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val poiPaint = Paint().apply {
        color = Color.rgb(255, 46, 46)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val trailPaint = Paint().apply {
        color = Color.WHITE
        alpha = 55
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    private val labelBgPaint = Paint().apply {
        color = Color.BLACK
        alpha = 155
        style = Paint.Style.FILL
    }
    private val reticlePaint = Paint().apply {
        color = Color.WHITE
        alpha = 95
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val reticleRedPaint = Paint().apply {
        color = Color.rgb(255, 46, 46)
        alpha = 85
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val poiLabelBgPaint = Paint().apply {
        color = Color.rgb(183, 28, 28)
        style = Paint.Style.FILL
    }
    private val zoneStrokePaint = Paint().apply {
        color = Color.rgb(255, 46, 46)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        isAntiAlias = true
    }
    private val zoneFillPaint = Paint().apply {
        color = Color.rgb(255, 46, 46)
        alpha = 36
        style = Paint.Style.FILL
    }
    private val zonePointPaint = Paint().apply {
        color = Color.rgb(255, 46, 46)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val calLinePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        isAntiAlias = true
    }
    private val smallLabelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    fun scaleFactor(): Float {
        if (imageWidth <= 0 || imageHeight <= 0) return 1f
        return max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
    }

    fun offsetX(): Float = (width - imageWidth * scaleFactor()) / 2f

    fun offsetY(): Float = (height - imageHeight * scaleFactor()) / 2f

    fun mapX(imageX: Float): Float = imageX * scaleFactor() + offsetX()

    fun mapY(imageY: Float): Float = imageY * scaleFactor() + offsetY()

    /** Inverse of mapX/mapY — used to turn a screen tap into image coordinates. */
    fun unmap(sx: Float, sy: Float): PointF {
        val sf = scaleFactor()
        return PointF((sx - offsetX()) / sf, (sy - offsetY()) / sf)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth <= 0 || imageHeight <= 0) return

        drawZone(canvas)
        drawCalibrationLines(canvas)
        drawTrails(canvas)
        drawBoxes(canvas)
    }

    private fun drawZone(canvas: Canvas) {
        if (zonePoints.size < 2) {
            for (p in zonePoints) {
                canvas.drawCircle(mapX(p.x), mapY(p.y), 8f, zonePointPaint)
            }
            return
        }
        val path = Path()
        zonePoints.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(mapX(p.x), mapY(p.y)) else path.lineTo(mapX(p.x), mapY(p.y))
        }
        if (zoneClosed && zonePoints.size >= 3) {
            path.close()
            canvas.drawPath(path, zoneFillPaint)
        }
        canvas.drawPath(path, zoneStrokePaint)
        for (p in zonePoints) {
            canvas.drawCircle(mapX(p.x), mapY(p.y), 8f, zonePointPaint)
        }
    }

    private fun drawCalibrationLines(canvas: Canvas) {
        if (!calibrationActive) return
        val yA = mapY(0.35f * imageHeight)
        val yB = mapY(0.65f * imageHeight)
        canvas.drawLine(0f, yA, width.toFloat(), yA, calLinePaint)
        canvas.drawLine(0f, yB, width.toFloat(), yB, calLinePaint)
        canvas.drawRect(6f, yA - 34f, 150f, yA - 4f, labelBgPaint)
        canvas.drawText("LINE A", 12f, yA - 10f, smallLabelPaint)
        canvas.drawRect(6f, yB - 34f, 150f, yB - 4f, labelBgPaint)
        canvas.drawText("LINE B", 12f, yB - 10f, smallLabelPaint)
    }

    private fun drawTrails(canvas: Canvas) {
        for (track in tracksSnapshot) {
            if (track.trail.size < 2) continue
            val path = Path()
            track.trail.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(mapX(p.x), mapY(p.y)) else path.lineTo(mapX(p.x), mapY(p.y))
            }
            canvas.drawPath(path, trailPaint)
        }
    }

    /** Ghost Glass target frame: corner brackets only, never a heavy full box. */
    private fun drawCornerFrame(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: Paint
    ) {
        val arm = (minOf(right - left, bottom - top) * 0.28f).coerceIn(18f, 54f)
        // Top-left
        canvas.drawLine(left, top, left + arm, top, paint)
        canvas.drawLine(left, top, left, top + arm, paint)
        // Top-right
        canvas.drawLine(right - arm, top, right, top, paint)
        canvas.drawLine(right, top, right, top + arm, paint)
        // Bottom-left
        canvas.drawLine(left, bottom, left + arm, bottom, paint)
        canvas.drawLine(left, bottom - arm, left, bottom, paint)
        // Bottom-right
        canvas.drawLine(right - arm, bottom, right, bottom, paint)
        canvas.drawLine(right, bottom - arm, right, bottom, paint)
    }

    private fun drawBoxes(canvas: Canvas) {
        for (track in tracksSnapshot) {
            val isPoi = track.key == poiKey
            val left = mapX(track.box.left)
            val top = mapY(track.box.top)
            val right = mapX(track.box.right)
            val bottom = mapY(track.box.bottom)

            if (isPoi) {
                // Soft double reticle around the pinned target, as in the
                // Ghost Glass concept. It stays behind the corner frame.
                val cx = (left + right) / 2f
                val cy = (top + bottom) / 2f
                val rx = (right - left) / 2f
                val ry = (bottom - top) / 2f
                canvas.drawOval(
                    cx - rx - 24f, cy - ry - 24f, cx + rx + 24f, cy + ry + 24f,
                    reticlePaint
                )
                canvas.drawOval(
                    cx - rx - 46f, cy - ry - 46f, cx + rx + 46f, cy + ry + 46f,
                    reticleRedPaint
                )
            }

            drawCornerFrame(canvas, left, top, right, bottom, if (isPoi) poiPaint else boxPaint)

            val confidencePct = (track.confidence * 100).toInt()
            val label = if (isPoi) {
                val flag = computeFlagScore(track).first
                String.format(Locale.US, "POI %s · %d%% · FLAG %d", track.displayName(), confidencePct, flag)
            } else {
                String.format(Locale.US, "%s · %d%%", track.displayName(), confidencePct)
            }
            val textWidth = labelPaint.measureText(label)
            val labelTop = if (top - 46f < 0f) top + 6f else top - 46f
            canvas.drawRoundRect(
                left, labelTop, left + textWidth + 18f, labelTop + 40f, 10f, 10f,
                if (isPoi) poiLabelBgPaint else labelBgPaint
            )
            canvas.drawText(label, left + 9f, labelTop + 29f, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            tapCallback?.invoke(event.x, event.y)
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
