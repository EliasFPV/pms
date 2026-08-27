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
        const val MIN_FOV = 20f
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
    showTerrain: Boolean = true,
) {
    val textMeasurer = rememberTextMeasurer()
    val stars = remember { generateStars() }
    val terrain = remember {
        listOf(
            TerrainLayer(buildTerrain(seed = 1741, maxHeightDeg = 9.0f), haze = 1.0f),
            TerrainLayer(buildTerrain(seed = 90210, maxHeightDeg = 5.8f), haze = 0.55f),
            TerrainLayer(buildTerrain(seed = 31337, maxHeightDeg = 3.4f), haze = 0.0f),
        )
    }

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
            terrain = if (showTerrain) terrain else emptyList(),
            textMeasurer = textMeasurer,
        )
    }
}

private fun DrawScope.drawSky(
    snapshot: SkySnapshot,
    track: List<TrackSample>,
    viewState: SkyViewState,
    stars: List<Star>,
    terrain: List<TerrainLayer>,
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
    val minBodyRadius = 15.dp.toPx()
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
    terrain.forEach { layer ->
        // Distant ranges are washed towards the colour of the sky at the horizon, which
        // is what gives a real skyline its sense of depth.
        val ridgeColor = lerp(
            SkyPalette.RidgeNear,
            lerp(SkyPalette.RidgeFar, palette.second, 0.45f),
            layer.haze,
        )
        drawPath(
            path = ridgePath(
                profile = layer.profile,
                centerAzimuth = centerAzimuth,
                fieldOfView = viewState.fieldOfView.toDouble(),
                bottom = height,
                xFor = ::xFor,
                yFor = ::yFor,
            ),
            color = ridgeColor,
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
    drawLine(
        color = SkyPalette.Horizon.copy(alpha = if (terrain.isEmpty()) 1f else 0.8f),
        start = Offset(0f, horizonY),
        end = Offset(width, horizonY),
        strokeWidth = horizonStroke,
    )

    // --- Moon -------------------------------------------------------------
    val moonX = xFor(snapshot.moon.azimuthDeg)
    val moonY = yFor(snapshot.moon.apparentAltitudeDeg)
    val moonRadius = max(
        (snapshot.moon.angularDiameterDeg / 2.0).toFloat() * pixelsPerDegreeX,
        minBodyRadius,
    )
    if (isVisibleX(moonX)) {
        drawMoon(
            centerX = moonX,
            centerY = moonY,
            radius = moonRadius,
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
            y = moonY + moonRadius + labelGap,
            centered = true,
        )
    }

    // --- Sun --------------------------------------------------------------
    val sunX = xFor(snapshot.sun.azimuthDeg)
    val sunY = yFor(snapshot.sun.apparentAltitudeDeg)
    val sunRadius = max(
        (snapshot.sun.angularDiameterDeg / 2.0).toFloat() * pixelsPerDegreeX,
        minBodyRadius,
    )
    if (isVisibleX(sunX)) {
        drawSun(
            centerX = sunX,
            centerY = sunY,
            radius = sunRadius,
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
            y = sunY + sunRadius + labelGap,
            centered = true,
        )
    }
}

/** Filled silhouette of one mountain range across the visible span of horizon. */
private fun ridgePath(
    profile: TerrainProfile,
    centerAzimuth: Double,
    fieldOfView: Double,
    bottom: Float,
    xFor: (Double) -> Float,
    yFor: (Double) -> Float,
): Path {
    val path = Path()
    val halfSpan = fieldOfView / 2.0 + 4.0
    val start = centerAzimuth - halfSpan
    val end = centerAzimuth + halfSpan
    // Roughly one sample every two pixels, whatever the zoom level.
    val step = (fieldOfView / 320.0).coerceIn(0.05, 0.75)

    path.moveTo(xFor(start), bottom)
    var azimuth = start
    while (azimuth <= end) {
        path.lineTo(xFor(azimuth), yFor(profile.heightAt(azimuth).toDouble()))
        azimuth += step
    }
    path.lineTo(xFor(end), bottom)
    path.close()
    return path
}

private fun DrawScope.drawSun(
    centerX: Float,
    centerY: Float,
    radius: Float,
    aboveHorizon: Boolean,
    outlineStroke: Float,
    dash: PathEffect,
) {
    val center = Offset(centerX, centerY)
    if (aboveHorizon) {
        val glowRadius = radius * 6f
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
        drawCircle(color = SkyPalette.SunCore, radius = radius * 0.62f, center = center)
    } else {
        // Below the horizon the Sun is drawn shaded, with a dashed outline so its
        // position is still readable against the ground.
        drawCircle(color = SkyPalette.SunBelow.copy(alpha = 0.55f), radius = radius, center = center)
        drawCircle(
            color = SkyPalette.Sun.copy(alpha = 0.85f),
            radius = radius,
            center = center,
            style = Stroke(width = outlineStroke, pathEffect = dash),
        )
    }
}

private fun DrawScope.drawMoon(
    centerX: Float,
    centerY: Float,
    radius: Float,
    illuminatedFraction: Float,
    rotationDegrees: Float,
    aboveHorizon: Boolean,
    nightFactor: Float,
    outlineStroke: Float,
) {
    val center = Offset(centerX, centerY)
    val litColor = if (aboveHorizon) SkyPalette.MoonLit else SkyPalette.MoonBelow
    val darkColor = if (aboveHorizon) SkyPalette.MoonDark else SkyPalette.MoonDark.copy(alpha = 0.5f)

    if (aboveHorizon && nightFactor > 0.05f) {
        val glowRadius = radius * 3.4f
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
