@file:OptIn(ExperimentalTextApi::class)

package com.skyhorizon.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyhorizon.app.astro.SkySnapshot
import com.skyhorizon.app.astro.TrackSample
import com.skyhorizon.app.astro.normalizeDegrees
import com.skyhorizon.app.astro.normalizeSignedDegrees
import com.skyhorizon.app.terrain.HorizonProfile
import com.skyhorizon.app.ui.theme.SkyPalette
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Camera for the panoramic horizon view: where it looks and how wide the lens is. */
class SkyViewState(
    initialAzimuth: Float = 180f,
    initialAltitude: Float = 22f,
    initialFieldOfView: Float = 85f,
) {
    var centerAzimuth by mutableFloatStateOf(normalize(initialAzimuth))
        private set
    var centerAltitude by mutableFloatStateOf(initialAltitude.coerceIn(MIN_ALTITUDE, MAX_ALTITUDE))
        private set
    var fieldOfView by mutableFloatStateOf(initialFieldOfView.coerceIn(MIN_FOV, MAX_FOV))
        private set

    fun pan(deltaAzimuthDeg: Float, deltaAltitudeDeg: Float) {
        centerAzimuth = normalize(centerAzimuth + deltaAzimuthDeg)
        centerAltitude = (centerAltitude + deltaAltitudeDeg).coerceIn(MIN_ALTITUDE, MAX_ALTITUDE)
    }

    fun zoomBy(factor: Float) {
        if (factor <= 0f) return
        fieldOfView = (fieldOfView / factor).coerceIn(MIN_FOV, MAX_FOV)
    }

    /** Swings the view so the given position sits in the middle of the screen. */
    fun centerOn(azimuthDeg: Double, altitudeDeg: Double) {
        centerAzimuth = normalize(azimuthDeg.toFloat())
        centerAltitude = altitudeDeg.toFloat().coerceIn(MIN_ALTITUDE, MAX_ALTITUDE)
    }

    fun reset() {
        centerAzimuth = 180f
        centerAltitude = 22f
        fieldOfView = 85f
    }

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f

    companion object {
        const val MIN_ALTITUDE = -35f
        const val MAX_ALTITUDE = 80f
        // Tight enough that the Sun and Moon reach their true angular size on screen:
        // half a degree of disc only outgrows the minimum marker below about 9 degrees
        // of field, so stopping at 20 would pin them at a fixed size forever.
        const val MIN_FOV = 2f
        const val MAX_FOV = 200f

        // listSaver keeps the three floats individually bundle-storable, so the camera
        // survives configuration changes and process death.
        val Saver: Saver<SkyViewState, Any> = listSaver(
            save = { listOf(it.centerAzimuth, it.centerAltitude, it.fieldOfView) },
            restore = { SkyViewState(it[0], it[1], it[2]) },
        )
    }
}

@Composable
fun rememberSkyViewState(): SkyViewState =
    rememberSaveable(saver = SkyViewState.Saver) { SkyViewState() }

private data class Star(val azimuthDeg: Double, val altitudeDeg: Double, val magnitude: Float)

private fun generateStars(count: Int = 320): List<Star> {
    val random = Random(20240621)
    return List(count) {
        // Uniform over the visible hemisphere, so the field does not clump at the zenith.
        val altitude = Math.toDegrees(asin(random.nextDouble()))
        Star(
            azimuthDeg = random.nextDouble() * 360.0,
            altitudeDeg = altitude,
            magnitude = 0.25f + random.nextFloat() * 0.75f,
        )
    }
}

/**
 * Panoramic sky renderer. Azimuth runs left to right, altitude bottom to top, and the
 * whole 360 degrees of horizon can be panned through. Drag to look around, pinch to
 * change the field of view.
 */
@Composable
fun SkyCanvas(
    snapshot: SkySnapshot,
    track: List<TrackSample>,
    viewState: SkyViewState,
    modifier: Modifier = Modifier,
    horizonProfile: HorizonProfile? = null,
    showTerrain: Boolean = true,
) {
    val textMeasurer = rememberTextMeasurer()
    val stars = remember { generateStars() }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                val pixelsPerDegree = size.width / viewState.fieldOfView
                viewState.zoomBy(zoom)
                viewState.pan(-pan.x / pixelsPerDegree, pan.y / pixelsPerDegree)
            }
        },
    ) {
        drawSky(
            snapshot = snapshot,
            track = track,
            viewState = viewState,
            stars = stars,
            horizonProfile = if (showTerrain) horizonProfile else null,
            textMeasurer = textMeasurer,
        )
    }
}

private fun DrawScope.drawSky(
    snapshot: SkySnapshot,
    track: List<TrackSample>,
    viewState: SkyViewState,
    stars: List<Star>,
    horizonProfile: HorizonProfile?,
    textMeasurer: TextMeasurer,
) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return

    // Every dimension below is expressed in dp and converted here, so the drawing keeps
    // its proportions on a high-density screen instead of shrinking to a third of its
    // intended size.
    val hairline = 0.8.dp.toPx()
    val gridStroke = 1.dp.toPx()
    val cardinalStroke = 1.4.dp.toPx()
    val horizonStroke = 2.dp.toPx()
    val tickShort = 3.dp.toPx()
    val tickLong = 6.dp.toPx()
    // Below this the true disc is too small to find, so a marker ring stands in for it.
    val markerRadius = 12.dp.toPx()
    val minDiscRadius = 2.dp.toPx()
    val labelGap = 6.dp.toPx()

    val pixelsPerDegreeX = width / viewState.fieldOfView
    // Cap the vertical span so a wide field of view does not squash the sky flat.
    val pixelsPerDegreeY = max(pixelsPerDegreeX, height / 110f)
    val centerAzimuth = viewState.centerAzimuth.toDouble()
    val centerAltitude = viewState.centerAltitude.toDouble()

    fun xFor(azimuthDeg: Double): Float =
        width / 2f + normalizeSignedDegrees(azimuthDeg - centerAzimuth).toFloat() * pixelsPerDegreeX

    fun yFor(altitudeDeg: Double): Float =
        height / 2f - (altitudeDeg - centerAltitude).toFloat() * pixelsPerDegreeY

    fun isVisibleX(x: Float): Boolean = x > -width * 0.2f && x < width * 1.2f

    val horizonY = yFor(0.0)
    val sunAltitude = snapshot.sun.apparentAltitudeDeg
    val palette = skyColors(sunAltitude)
    val night = nightFactor(sunAltitude)

    // --- Background -------------------------------------------------------
    val skyBottom = horizonY.coerceIn(0f, height)
    if (skyBottom > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(palette.first, palette.second),
                startY = yFor(90.0),
                endY = horizonY,
            ),
            topLeft = Offset.Zero,
            size = Size(width, skyBottom),
        )
    }
    if (skyBottom < height) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(SkyPalette.GroundNear, SkyPalette.GroundFar),
                startY = horizonY,
                endY = height,
            ),
            topLeft = Offset(0f, skyBottom),
            size = Size(width, height - skyBottom),
        )
    }

    // Warm glow where the Sun meets the horizon, strongest around sunrise and sunset.
    val glowStrength = twilightGlow(sunAltitude)
    if (glowStrength > 0.01f) {
        val glowX = xFor(snapshot.sun.azimuthDeg)
        if (isVisibleX(glowX)) {
            val glowRadius = max(width * 0.55f, 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        SkyPalette.GoldenHorizon.copy(alpha = 0.55f * glowStrength),
                        Color.Transparent,
                    ),
                    center = Offset(glowX, horizonY),
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = Offset(glowX, horizonY),
            )
        }
    }

    // --- Stars ------------------------------------------------------------
    if (night > 0.02f) {
        val starRadius = 1.3.dp.toPx()
        stars.forEach { star ->
            val x = xFor(star.azimuthDeg)
            if (!isVisibleX(x)) return@forEach
            val y = yFor(star.altitudeDeg)
            if (y > horizonY) return@forEach
            drawCircle(
                color = SkyPalette.Star.copy(alpha = star.magnitude * night * 0.9f),
                radius = star.magnitude * starRadius,
                center = Offset(x, y),
            )
        }
    }

    // --- Altitude grid ----------------------------------------------------
    val dashed = PathEffect.dashPathEffect(
        floatArrayOf(4.dp.toPx(), 5.dp.toPx()),
        0f,
    )
    val labelStyle = TextStyle(
        color = SkyPalette.Label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
    val tickStyle = labelStyle.copy(fontSize = 10.sp)
    val cardinalStyle = TextStyle(
        color = SkyPalette.Horizon,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
    )

    for (altitude in -30..90 step 15) {
        val y = yFor(altitude.toDouble())
        if (y < -tickLong || y > height + tickLong) continue
        if (altitude == 0) continue
        drawLine(
            color = SkyPalette.Grid,
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = gridStroke,
            pathEffect = dashed,
        )
        drawLabel(
            textMeasurer = textMeasurer,
            text = "${altitude}°",
            style = labelStyle,
            x = labelGap,
            y = y - labelGap - 12.dp.toPx(),
            centered = false,
        )
    }

    // --- Azimuth grid -----------------------------------------------------
    var azimuth = 0
    while (azimuth < 360) {
        val x = xFor(azimuth.toDouble())
        if (isVisibleX(x)) {
            when {
                azimuth % 45 == 0 -> {
                    drawLine(
                        color = SkyPalette.GridStrong,
                        start = Offset(x, 0f),
                        end = Offset(x, horizonY.coerceIn(0f, height)),
                        strokeWidth = cardinalStroke,
                        pathEffect = dashed,
                    )
                    // Cardinal names sit just above the horizon so the skyline never
                    // covers them.
                    drawLabel(
                        textMeasurer = textMeasurer,
                        text = cardinalName(azimuth),
                        style = cardinalStyle,
                        x = x,
                        y = horizonY - 26.dp.toPx(),
                        centered = true,
                    )
                }

                azimuth % 15 == 0 -> {
                    drawLine(
                        color = SkyPalette.Grid,
                        start = Offset(x, horizonY - tickLong),
                        end = Offset(x, horizonY + tickLong),
                        strokeWidth = gridStroke,
                    )
                    drawLabel(
                        textMeasurer = textMeasurer,
                        text = "$azimuth",
                        style = tickStyle,
                        x = x,
                        y = horizonY - tickLong - 13.dp.toPx(),
                        centered = true,
                    )
                }

                else -> drawLine(
                    color = SkyPalette.Grid,
                    start = Offset(x, horizonY - tickShort),
                    end = Offset(x, horizonY + tickShort),
                    strokeWidth = hairline,
                )
            }
        }
        azimuth += 5
    }

    // --- Skyline ----------------------------------------------------------
    if (horizonProfile != null) {
        drawHorizon(
            profile = horizonProfile,
            centerAzimuth = centerAzimuth,
            fieldOfView = viewState.fieldOfView.toDouble(),
            width = width,
            bottom = height,
            skyAtHorizon = palette.second,
            xFor = ::xFor,
            yFor = ::yFor,
        )
    }

    // --- Daily arcs -------------------------------------------------------
    val trackStroke = Stroke(
        width = 1.6.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()), 0f),
    )
    drawTrack(track, SkyPalette.SunTrack, ::xFor, ::yFor, width, trackStroke) {
        it.sunAzimuthDeg to it.sunAltitudeDeg
    }
    drawTrack(track, SkyPalette.MoonTrack, ::xFor, ::yFor, width, trackStroke) {
        it.moonAzimuthDeg to it.moonAltitudeDeg
    }

    // --- Horizon ----------------------------------------------------------
    // Kept on top of the skyline: it is the astronomical 0 degree reference, not the
    // visible ridge line.
    // Thin when a skyline is drawn: distant ranges show up barely a degree above zero,
    // and a heavy reference line would sit right on top of them.
    drawLine(
        color = SkyPalette.Horizon.copy(alpha = if (horizonProfile == null) 1f else 0.7f),
        start = Offset(0f, horizonY),
        end = Offset(width, horizonY),
        strokeWidth = if (horizonProfile == null) horizonStroke else horizonStroke * 0.55f,
    )

    // --- Moon -------------------------------------------------------------
    val moonX = xFor(snapshot.moon.azimuthDeg)
    val moonY = yFor(snapshot.moon.apparentAltitudeDeg)
    val moonTrueRadius = (snapshot.moon.angularDiameterDeg / 2.0).toFloat() * pixelsPerDegreeX
    val moonRadius = max(moonTrueRadius, minDiscRadius)
    val moonLabelRadius = max(moonRadius, markerRadius)
    if (isVisibleX(moonX)) {
        drawMoon(
            centerX = moonX,
            centerY = moonY,
            radius = moonRadius,
            markerRadius = if (moonTrueRadius < markerRadius) markerRadius else null,
            illuminatedFraction = snapshot.moonPhase.illuminatedFraction.toFloat(),
            rotationDegrees = (
                snapshot.moon.horizontal.parallacticAngleDeg -
                    snapshot.moonPhase.brightLimbAngleDeg
                ).toFloat() - 90f,
            aboveHorizon = snapshot.moon.isAboveHorizon,
            nightFactor = night,
            outlineStroke = hairline * 1.5f,
        )
        drawLabel(
            textMeasurer = textMeasurer,
            text = "Moon  ${format1(snapshot.moon.azimuthDeg)}° / " +
                "${format1(snapshot.moon.apparentAltitudeDeg)}°",
            style = labelStyle.copy(color = SkyPalette.MoonLit),
            x = moonX,
            y = moonY + moonLabelRadius + labelGap,
            centered = true,
        )
    }

    // --- Sun --------------------------------------------------------------
    val sunX = xFor(snapshot.sun.azimuthDeg)
    val sunY = yFor(snapshot.sun.apparentAltitudeDeg)
    val sunTrueRadius = (snapshot.sun.angularDiameterDeg / 2.0).toFloat() * pixelsPerDegreeX
    val sunRadius = max(sunTrueRadius, minDiscRadius)
    val sunLabelRadius = max(sunRadius, markerRadius)
    if (isVisibleX(sunX)) {
        drawSun(
            centerX = sunX,
            centerY = sunY,
            radius = sunRadius,
            markerRadius = if (sunTrueRadius < markerRadius) markerRadius else null,
            aboveHorizon = snapshot.sun.isAboveHorizon,
            outlineStroke = hairline * 2f,
            dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()), 0f),
        )
        drawLabel(
            textMeasurer = textMeasurer,
            text = "Sun  ${format1(snapshot.sun.azimuthDeg)}° / " +
                "${format1(snapshot.sun.apparentAltitudeDeg)}°",
            style = labelStyle.copy(color = SkyPalette.Sun),
            x = sunX,
            y = sunY + sunLabelRadius + labelGap,
            centered = true,
        )
    }
}

/**
 * Draws the real skyline. Each column of the view is filled from the terrain's
 * elevation angle down to the bottom of the canvas, tinted by how far away that
 * terrain is: near ridges stay dark, distant ones wash out towards the colour of
 * the sky, which is what aerial perspective does to a real horizon.
 */
private fun DrawScope.drawHorizon(
    profile: HorizonProfile,
    centerAzimuth: Double,
    fieldOfView: Double,
    width: Float,
    bottom: Float,
    skyAtHorizon: Color,
    xFor: (Double) -> Float,
    yFor: (Double) -> Float,
) {
    val columns = (width / 3f).toInt().coerceIn(64, 480)
    val span = fieldOfView + 8.0
    val start = centerAzimuth - span / 2.0
    val step = span / columns

    val angles = FloatArray(columns + 1)
    val hazeSteps = IntArray(columns + 1)
    val rawHaze = IntArray(columns + 1)

    for (index in 0..columns) {
        val azimuth = start + index * step
        angles[index] = profile.angleAt(azimuth)
        rawHaze[index] = hazeStep(profile.distanceAt(azimuth))
    }

    // A single stray column of distant terrain between near ridges is sampling noise
    // rather than a real ridge, so smooth the haze without touching the silhouette.
    for (index in 0..columns) {
        var lower = 0
        var upper = 0
        for (offset in -2..2) {
            val neighbour = rawHaze[(index + offset).coerceIn(0, columns)]
            if (neighbour < rawHaze[index]) lower++
            if (neighbour > rawHaze[index]) upper++
        }
        hazeSteps[index] = when {
            lower >= 3 -> rawHaze[(index - 2).coerceAtLeast(0)]
            upper >= 3 -> rawHaze[(index + 2).coerceAtMost(columns)]
            else -> rawHaze[index]
        }
    }

    var runStart = 0
    while (runStart < columns) {
        var runEnd = runStart
        while (runEnd < columns && hazeSteps[runEnd + 1] == hazeSteps[runStart]) runEnd++
        // Overlap the next column so neighbouring runs meet without a seam.
        val last = (runEnd + 1).coerceAtMost(columns)

        val path = Path()
        path.moveTo(xFor(start + runStart * step), bottom)
        for (index in runStart..last) {
            path.lineTo(xFor(start + index * step), yFor(angles[index].toDouble()))
        }
        path.lineTo(xFor(start + last * step), bottom)
        path.close()
        drawPath(path = path, color = ridgeColor(hazeSteps[runStart], skyAtHorizon))

        runStart = runEnd + 1
    }
}

private const val HAZE_STEPS = 6

/** Quantised distance band, so the silhouette fills in a handful of runs. */
private fun hazeStep(distanceMeters: Float): Int {
    val kilometres = distanceMeters / 1000f
    // Ridges are drawn out to 200 km; by about 80 km haze has washed them out about
    // as far as it can, so that is where the scale tops out.
    val fraction = (kilometres / 80f).coerceIn(0f, 1f)
    // A square root spreads the near distances, where haze changes fastest.
    val eased = kotlin.math.sqrt(fraction)
    return (eased * HAZE_STEPS).roundToInt().coerceIn(0, HAZE_STEPS)
}

private fun ridgeColor(hazeStep: Int, skyAtHorizon: Color): Color {
    val t = hazeStep.toFloat() / HAZE_STEPS
    return lerp(SkyPalette.RidgeNear, lerp(SkyPalette.RidgeFar, skyAtHorizon, 0.5f), t)
}

private fun DrawScope.drawSun(
    centerX: Float,
    centerY: Float,
    radius: Float,
    markerRadius: Float?,
    aboveHorizon: Boolean,
    outlineStroke: Float,
    dash: PathEffect,
) {
    val center = Offset(centerX, centerY)
    // The disc is drawn at its true angular size, so zooming in grows it exactly as
    // the real Sun would grow in a telephoto view. Half a degree is only a few pixels
    // at a wide field, so a ring marks where it is until the disc outgrows it.
    val halo = markerRadius ?: radius

    if (aboveHorizon) {
        val glowRadius = halo * 4.5f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    SkyPalette.Sun.copy(alpha = 0.55f),
                    SkyPalette.Sun.copy(alpha = 0.12f),
                    Color.Transparent,
                ),
                center = center,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = center,
        )
        drawCircle(color = SkyPalette.Sun, radius = radius, center = center)
        if (radius > outlineStroke * 3f) {
            drawCircle(color = SkyPalette.SunCore, radius = radius * 0.62f, center = center)
        }
    } else {
        // Below the horizon the Sun is drawn shaded, with a dashed outline so its
        // position is still readable against the ground.
        drawCircle(color = SkyPalette.SunBelow.copy(alpha = 0.7f), radius = radius, center = center)
        drawCircle(
            color = SkyPalette.Sun.copy(alpha = 0.85f),
            radius = halo,
            center = center,
            style = Stroke(width = outlineStroke, pathEffect = dash),
        )
    }

    if (markerRadius != null && aboveHorizon) {
        drawCircle(
            color = SkyPalette.Sun.copy(alpha = 0.45f),
            radius = markerRadius,
            center = center,
            style = Stroke(width = outlineStroke * 0.7f),
        )
    }
}

private fun DrawScope.drawMoon(
    centerX: Float,
    centerY: Float,
    radius: Float,
    markerRadius: Float?,
    illuminatedFraction: Float,
    rotationDegrees: Float,
    aboveHorizon: Boolean,
    nightFactor: Float,
    outlineStroke: Float,
) {
    val center = Offset(centerX, centerY)
    val litColor = if (aboveHorizon) SkyPalette.MoonLit else SkyPalette.MoonBelow
    val darkColor = if (aboveHorizon) SkyPalette.MoonDark else SkyPalette.MoonDark.copy(alpha = 0.5f)
    val halo = markerRadius ?: radius

    if (aboveHorizon && nightFactor > 0.05f) {
        val glowRadius = halo * 3.0f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    SkyPalette.MoonLit.copy(alpha = 0.22f * nightFactor * illuminatedFraction),
                    Color.Transparent,
                ),
                center = center,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = center,
        )
    }

    rotate(degrees = rotationDegrees, pivot = center) {
        // Unlit disc first, then the sunlit part on top.
        drawCircle(color = darkColor, radius = radius, center = center)
        drawPath(
            path = lunarLimbPath(centerX, centerY, radius, illuminatedFraction),
            color = litColor,
        )
    }
    drawCircle(
        color = litColor.copy(alpha = 0.55f),
        radius = radius,
        center = center,
        style = Stroke(width = outlineStroke),
    )

    // Marker ring while the true disc is still smaller than it: at a wide field the
    // Moon is half a degree across, which is a couple of pixels.
    if (markerRadius != null) {
        drawCircle(
            color = litColor.copy(alpha = 0.4f),
            radius = markerRadius,
            center = center,
            style = Stroke(width = outlineStroke * 0.7f),
        )
        if (radius < markerRadius * 0.6f) {
            // Too small to read the phase on the disc itself, so show it in the ring.
            rotate(degrees = rotationDegrees, pivot = center) {
                drawPath(
                    path = lunarLimbPath(
                        centerX, centerY, markerRadius * 0.82f, illuminatedFraction,
                    ),
                    color = litColor.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/**
 * Path covering the sunlit part of the disc, with the bright limb pointing along +x
 * (or -x when [litOnRight] is false).
 * The terminator is the projection of the great circle dividing day from night, which
 * appears as a half-ellipse whose semi-axis is `r(2k - 1)`.
 */
internal fun lunarLimbPath(
    centerX: Float,
    centerY: Float,
    radius: Float,
    illuminatedFraction: Float,
    litOnRight: Boolean = true,
): Path {
    val k = illuminatedFraction.coerceIn(0f, 1f)
    val terminatorSemiAxis = radius * (2f * k - 1f)
    val mirror = if (litOnRight) 1f else -1f
    val path = Path()
    val step = 4.0

    // Bright limb: the right half of the disc, from top to bottom.
    var angle = -90.0
    var first = true
    while (angle <= 90.0) {
        val x = centerX + mirror * radius * cos(Math.toRadians(angle)).toFloat()
        val y = centerY + radius * sin(Math.toRadians(angle)).toFloat()
        if (first) {
            path.moveTo(x, y)
            first = false
        } else {
            path.lineTo(x, y)
        }
        angle += step
    }

    // Terminator: back up from bottom to top along the half-ellipse.
    angle = 90.0
    while (angle >= -90.0) {
        val x = centerX + mirror * terminatorSemiAxis * cos(Math.toRadians(angle)).toFloat()
        val y = centerY + radius * sin(Math.toRadians(angle)).toFloat()
        path.lineTo(x, y)
        angle -= step
    }

    path.close()
    return path
}

private fun DrawScope.drawTrack(
    track: List<TrackSample>,
    color: Color,
    xFor: (Double) -> Float,
    yFor: (Double) -> Float,
    width: Float,
    stroke: Stroke,
    select: (TrackSample) -> Pair<Double, Double>,
) {
    if (track.size < 2) return
    val path = Path()
    var previousX: Float? = null
    var started = false
    track.forEach { sample ->
        val (azimuth, altitude) = select(sample)
        val x = xFor(azimuth)
        val y = yFor(altitude)
        val wrapped = previousX != null && abs(x - previousX!!) > width / 2f
        if (!started || wrapped) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
        previousX = x
    }
    drawPath(path = path, color = color, style = stroke)
}

private fun DrawScope.drawLabel(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
    centered: Boolean,
) {
    val layout: TextLayoutResult = textMeasurer.measure(text, style)
    val left = if (centered) x - layout.size.width / 2f else x
    if (left > size.width || left + layout.size.width < 0f) return
    if (y > size.height || y + layout.size.height < 0f) return
    drawText(
        textLayoutResult = layout,
        color = style.color,
        topLeft = Offset(left, y),
    )
}

private fun cardinalName(azimuth: Int): String = when (normalizeDegrees(azimuth.toDouble()).roundToInt()) {
    0, 360 -> "N"
    45 -> "NE"
    90 -> "E"
    135 -> "SE"
    180 -> "S"
    225 -> "SW"
    270 -> "W"
    315 -> "NW"
    else -> "$azimuth°"
}

private fun format1(value: Double): String = ((value * 10.0).roundToInt() / 10.0).toString()

/** Zenith and horizon colours for the current solar altitude. */
private fun skyColors(sunAltitudeDeg: Double): Pair<Color, Color> {
    val stops = listOf(
        -18.0 to (SkyPalette.NightZenith to SkyPalette.NightHorizon),
        -12.0 to (SkyPalette.AstronomicalZenith to SkyPalette.AstronomicalHorizon),
        -6.0 to (SkyPalette.NauticalZenith to SkyPalette.NauticalHorizon),
        -0.833 to (SkyPalette.CivilZenith to SkyPalette.CivilHorizon),
        6.0 to (SkyPalette.GoldenZenith to SkyPalette.GoldenHorizon),
        20.0 to (SkyPalette.DayZenith to SkyPalette.DayHorizon),
    )
    if (sunAltitudeDeg <= stops.first().first) return stops.first().second
    if (sunAltitudeDeg >= stops.last().first) return stops.last().second
    for (index in 0 until stops.size - 1) {
        val (lowAltitude, lowColors) = stops[index]
        val (highAltitude, highColors) = stops[index + 1]
        if (sunAltitudeDeg in lowAltitude..highAltitude) {
            val t = ((sunAltitudeDeg - lowAltitude) / (highAltitude - lowAltitude)).toFloat()
            return lerp(lowColors.first, highColors.first, t) to
                lerp(lowColors.second, highColors.second, t)
        }
    }
    return stops.last().second
}

/** 0 in daylight, 1 once the Sun is far enough below the horizon for stars. */
private fun nightFactor(sunAltitudeDeg: Double): Float = when {
    sunAltitudeDeg <= -12.0 -> 1f
    sunAltitudeDeg >= 0.0 -> 0f
    else -> (-sunAltitudeDeg / 12.0).toFloat().coerceIn(0f, 1f)
}

/** Strength of the sunrise/sunset glow band. */
private fun twilightGlow(sunAltitudeDeg: Double): Float {
    val distance = abs(sunAltitudeDeg)
    return when {
        sunAltitudeDeg < -14.0 -> 0f
        sunAltitudeDeg > 14.0 -> 0f
        else -> (1.0 - distance / 14.0).toFloat().coerceIn(0f, 1f)
    }
}
