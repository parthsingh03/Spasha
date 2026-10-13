package com.parth.spasha

import android.graphics.PointF
import android.graphics.RectF
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One raw detection coming out of the model, in rotated-bitmap coordinates. */
data class RawDetection(
    val label: String,
    val score: Float,
    val box: RectF
)

/** User-set speed calibration: real distance between line A and line B. */
data class Calibration(
    val distanceM: Double,
    val limitKmh: Double
)

val VEHICLE_LABELS = setOf("bicycle", "car", "motorcycle", "bus", "truck")

fun isVehicleLabel(label: String): Boolean = label in VEHICLE_LABELS

/**
 * A tracked subject. The ID is per-class and only lives while the subject
 * stays in view — when a track is retired, a subject coming back gets a new
 * ID. There is deliberately no identity attached to a track.
 */
class Track(
    val key: String,
    val classLabel: String,
    val numId: Int,
    var box: RectF,
    var confidence: Float,
    val firstSeenMs: Long,
    var lastSeenMs: Long
) {
    /** Last 24 centre points, oldest first — drawn as the trail. */
    val trail = mutableListOf<PointF>()

    var crossedLineAMs: Long? = null
    var crossedLineBMs: Long? = null
    var lastSpeedKmh: Double? = null
    var overLimit: Boolean = false

    var zoneInside: Boolean = false
    var everEnteredZone: Boolean = false
    var loiterLogged: Boolean = false

    val dwellMs: Long
        get() = lastSeenMs - firstSeenMs

    fun centre(): PointF = PointF(box.centerX(), box.centerY())

    fun displayName(): String =
        String.format(Locale.US, "%s #%02d", classLabel.uppercase(Locale.US), numId)
}

/**
 * FLAG SCORE — fully rule-based and transparent. Returns the total (capped
 * at 100) plus the list of rules that fired, with their points, so the UI
 * can print exactly why a track has the score it has. This is not an AI
 * judgement and never claims to be one.
 */
fun computeFlagScore(track: Track): Pair<Int, List<String>> {
    var total = 0
    val rules = mutableListOf<String>()
    if (track.dwellMs > 30_000L) {
        total += 20
        rules.add("Dwell over 30s  +20")
    }
    if (track.dwellMs > 60_000L) {
        total += 20
        rules.add("Dwell over 60s  +20")
    }
    if (track.everEnteredZone) {
        total += 40
        rules.add("Entered drawn zone  +40")
    }
    val speed = track.lastSpeedKmh
    if (track.overLimit && speed != null) {
        total += 30
        rules.add(
            String.format(Locale.US, "Speed %.0f km/h over set limit  +30", speed)
        )
    }
    if (total > 100) total = 100
    return Pair(total, rules)
}

private fun boxIou(a: RectF, b: RectF): Float {
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    if (right <= left || bottom <= top) return 0f
    val intersection = (right - left) * (bottom - top)
    val union = a.width() * a.height() + b.width() * b.height() - intersection
    if (union <= 0f) return 0f
    return intersection / union
}

/** Standard ray-casting point-in-polygon test. */
fun pointInPolygon(p: PointF, poly: List<PointF>): Boolean {
    if (poly.size < 3) return false
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val xi = poly[i].x
        val yi = poly[i].y
        val xj = poly[j].x
        val yj = poly[j].y
        if ((yi > p.y) != (yj > p.y) &&
            p.x < (xj - xi) * (p.y - yi) / (yj - yi) + xi
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/**
 * Simple greedy tracker: a detection joins the best unmatched track of the
 * same class when IoU > 0.25 or the centre moved less than 15% of the frame
 * diagonal. Anything else starts a new track.
 */
class Tracker {

    val tracks = mutableListOf<Track>()

    var uniqueTrackCount: Int = 0
        private set

    /** key of the pinned Person-of-Interest track, if any. */
    var poiKey: String? = null

    fun findTrack(key: String): Track? = tracks.firstOrNull { it.key == key }

    /**
     * Feeds one frame of detections in. Returns log event lines produced by
     * this frame (new tracks, zone crossings, loiter, speed readings).
     */
    fun update(
        detections: List<RawDetection>,
        nowMs: Long,
        frameWidth: Int,
        frameHeight: Int,
        zone: List<PointF>,
        calibration: Calibration?
    ): List<String> {
        val events = mutableListOf<String>()
        val matched = mutableSetOf<Track>()
        val diagonal = hypot(frameWidth.toDouble(), frameHeight.toDouble()).toFloat()

        for (det in detections) {
            var best: Track? = null
            var bestRank = Float.NEGATIVE_INFINITY
            for (t in tracks) {
                if (t.classLabel != det.label || matched.contains(t)) continue
                val iou = boxIou(t.box, det.box)
                val tc = t.centre()
                val dc = PointF(det.box.centerX(), det.box.centerY())
                val dist = hypot((tc.x - dc.x).toDouble(), (tc.y - dc.y).toDouble()).toFloat()
                val qualifies = iou > 0.25f || (diagonal > 0f && dist < 0.15f * diagonal)
                if (!qualifies) continue
                val rank = iou * 2f - if (diagonal > 0f) dist / diagonal else 0f
                if (rank > bestRank) {
                    bestRank = rank
                    best = t
                }
            }

            val existing = best
            if (existing != null) {
                val prevCentreY = existing.centre().y
                existing.box = RectF(det.box)
                existing.confidence = det.score
                existing.lastSeenMs = nowMs
                addTrailPoint(existing)
                matched.add(existing)
                checkSpeed(existing, prevCentreY, nowMs, frameHeight, calibration, events)
                checkZone(existing, zone, events)
                checkLoiter(existing, events)
            } else {
                val numId = nextIdFor(det.label)
                val key = det.label + "#" + numId
                val track = Track(
                    key = key,
                    classLabel = det.label,
                    numId = numId,
                    box = RectF(det.box),
                    confidence = det.score,
                    firstSeenMs = nowMs,
                    lastSeenMs = nowMs
                )
                addTrailPoint(track)
                // A track born inside the zone has not "entered" it — no event.
                track.zoneInside = pointInPolygon(track.centre(), zone)
                tracks.add(track)
                matched.add(track)
                uniqueTrackCount++
                events.add("TRACK NEW " + track.displayName())
            }
        }

        // Retire tracks not seen for over 2.5 seconds.
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (nowMs - t.lastSeenMs > 2_500L) {
                it.remove()
            }
        }
        return events
    }

    private val nextIdByClass = mutableMapOf<String, Int>()

    private fun nextIdFor(label: String): Int {
        val next = (nextIdByClass[label] ?: 0) + 1
        nextIdByClass[label] = next
        return next
    }

    private fun addTrailPoint(track: Track) {
        track.trail.add(track.centre())
        while (track.trail.size > 24) {
            track.trail.removeAt(0)
        }
    }

    private fun checkZone(track: Track, zone: List<PointF>, events: MutableList<String>) {
        if (zone.size < 3) return
        val inside = pointInPolygon(track.centre(), zone)
        if (inside && !track.zoneInside) {
            track.zoneInside = true
            track.everEnteredZone = true
            events.add("ZONE ENTRY " + track.displayName())
            if (track.key == poiKey) {
                events.add("POI ENTERED ZONE " + track.displayName())
            }
        } else if (!inside && track.zoneInside) {
            track.zoneInside = false
            events.add("ZONE EXIT " + track.displayName())
            if (track.key == poiKey) {
                events.add("POI EXITED ZONE " + track.displayName())
            }
        }
    }

    private fun checkLoiter(track: Track, events: MutableList<String>) {
        if (!track.loiterLogged && track.dwellMs > 60_000L) {
            track.loiterLogged = true
            events.add("LOITER 60s " + track.displayName())
            if (track.key == poiKey) {
                events.add("POI LOITER 60s " + track.displayName())
            }
        }
    }

    private fun crossedLine(prevY: Float, curY: Float, lineY: Float): Boolean {
        return (prevY < lineY && curY >= lineY) || (prevY > lineY && curY <= lineY)
    }

    private fun checkSpeed(
        track: Track,
        prevCentreY: Float,
        nowMs: Long,
        frameHeight: Int,
        calibration: Calibration?,
        events: MutableList<String>
    ) {
        if (calibration == null) return
        if (!isVehicleLabel(track.classLabel)) return
        val curY = track.centre().y
        val lineAY = 0.35f * frameHeight
        val lineBY = 0.65f * frameHeight

        if (crossedLine(prevCentreY, curY, lineAY)) {
            val tB = track.crossedLineBMs
            if (tB != null && nowMs > tB) {
                recordSpeed(track, tB, nowMs, calibration, events)
            } else {
                track.crossedLineAMs = nowMs
            }
        }
        if (crossedLine(prevCentreY, curY, lineBY)) {
            val tA = track.crossedLineAMs
            if (tA != null && nowMs > tA) {
                recordSpeed(track, tA, nowMs, calibration, events)
            } else {
                track.crossedLineBMs = nowMs
            }
        }
    }

    private fun recordSpeed(
        track: Track,
        startMs: Long,
        endMs: Long,
        calibration: Calibration,
        events: MutableList<String>
    ) {
        val dtSec = (endMs - startMs) / 1000.0
        track.crossedLineAMs = null
        track.crossedLineBMs = null
        if (dtSec < 0.2) return
        val speedKmh = calibration.distanceM / dtSec * 3.6
        track.lastSpeedKmh = speedKmh
        track.overLimit = speedKmh > calibration.limitKmh
        events.add(
            String.format(Locale.US, "%s SPEED %.0f km/h", track.displayName(), speedKmh)
        )
        if (track.overLimit) {
            events.add(
                String.format(
                    Locale.US, "%s OVER LIMIT (%.0f km/h)", track.displayName(), calibration.limitKmh
                )
            )
        }
    }
}
