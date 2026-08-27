package com.skyhorizon.app.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The horizon maths is checked against landscapes whose answer can be worked out on
 * paper: a flat plain (where the answer is the textbook dip of the horizon) and a
 * plateau at a known distance and height.
 */
class HorizonCalculatorTest {

    private val latitude = 46.0
    private val longitude = 8.0
    private val eyeHeight = HorizonCalculator.EYE_HEIGHT_M

    private fun metersFromObserver(latDeg: Double, lonDeg: Double): Double {
        val dLat = (latDeg - latitude) * HorizonCalculator.METERS_PER_DEGREE
        val dLon = (lonDeg - longitude) * HorizonCalculator.METERS_PER_DEGREE *
            cos(latitude * PI / 180.0)
        return hypot(dLat, dLon)
    }

    private fun bearingFromObserver(latDeg: Double, lonDeg: Double): Double {
        val dLat = (latDeg - latitude) * HorizonCalculator.METERS_PER_DEGREE
        val dLon = (lonDeg - longitude) * HorizonCalculator.METERS_PER_DEGREE *
            cos(latitude * PI / 180.0)
        return (atan2(dLon, dLat) * 180.0 / PI + 360.0) % 360.0
    }

    @Test
    fun `flat plain gives the textbook dip of the horizon`() {
        val profile = HorizonCalculator.compute(
            latitudeDeg = latitude,
            longitudeDeg = longitude,
            source = { _, _ -> 0f },
        )

        // dip = sqrt(2h / R_effective), the standard surveying result.
        val expectedDip = -sqrt(
            2.0 * eyeHeight / HorizonCalculator.REFRACTED_RADIUS_M,
        ) * 180.0 / PI

        assertEquals(0.0, profile.observerElevationMeters, 1e-6)
        for (azimuth in 0 until 360 step 7) {
            assertEquals(
                "dip at $azimuth deg",
                expectedDip,
                profile.angleAt(azimuth.toDouble()).toDouble(),
                0.004,
            )
        }
        // The horizon of a flat plain lies at sqrt(2 h R) away, about 5 km.
        val expectedRange = sqrt(2.0 * eyeHeight * HorizonCalculator.REFRACTED_RADIUS_M)
        assertEquals(expectedRange, profile.distanceAt(0.0).toDouble(), 400.0)
    }

    @Test
    fun `a plateau shows up at the angle plain trigonometry predicts`() {
        val plateauHeight = 2000.0
        val plateauStart = 9_000.0
        val profile = HorizonCalculator.compute(
            latitudeDeg = latitude,
            longitudeDeg = longitude,
            source = { lat, lon ->
                if (metersFromObserver(lat, lon) >= plateauStart) plateauHeight.toFloat() else 0f
            },
        )

        val drop = plateauStart * plateauStart / (2.0 * HorizonCalculator.REFRACTED_RADIUS_M)
        val expected = atan2(plateauHeight - eyeHeight - drop, plateauStart) * 180.0 / PI

        for (azimuth in 0 until 360 step 11) {
            assertEquals(
                "plateau angle at $azimuth deg",
                expected,
                profile.angleAt(azimuth.toDouble()).toDouble(),
                0.05,
            )
            assertEquals(
                "plateau distance at $azimuth deg",
                plateauStart,
                profile.distanceAt(azimuth.toDouble()).toDouble(),
                150.0,
            )
        }
    }

    @Test
    fun `a ridge in one direction leaves the rest of the sky clear`() {
        val ridgeBearing = 235.0
        val ridgeHeight = 3000.0
        val ridgeDistance = 8_000.0
        val profile = HorizonCalculator.compute(
            latitudeDeg = latitude,
            longitudeDeg = longitude,
            source = { lat, lon ->
                val distance = metersFromObserver(lat, lon)
                val offBearing = abs(
                    ((bearingFromObserver(lat, lon) - ridgeBearing + 540.0) % 360.0) - 180.0,
                )
                if (distance in (ridgeDistance - 400.0)..(ridgeDistance + 400.0) && offBearing < 4.0) {
                    ridgeHeight.toFloat()
                } else {
                    0f
                }
            },
        )

        // The skyline is set by the near face of the ridge, not its middle.
        val nearFace = ridgeDistance - 400.0
        val drop = nearFace * nearFace / (2.0 * HorizonCalculator.REFRACTED_RADIUS_M)
        val expected = atan2(ridgeHeight - eyeHeight - drop, nearFace) * 180.0 / PI
        assertEquals(expected, profile.angleAt(ridgeBearing).toDouble(), 0.3)

        // Ninety degrees away the plain is still a plain.
        val awayFromRidge = profile.angleAt(ridgeBearing + 90.0)
        assertTrue("expected clear sky, got $awayFromRidge", awayFromRidge < 0.0f)
    }

    @Test
    fun `a high range well beyond a hundred kilometres still reaches the skyline`() {
        // From low ground the ranges that shape a wide panorama often lie far out;
        // an earlier cut-off at 70 km silently dropped all of them.
        val rangeHeight = 3500.0
        val rangeDistance = 120_000.0
        val profile = HorizonCalculator.compute(
            latitudeDeg = latitude,
            longitudeDeg = longitude,
            source = { lat, lon ->
                if (metersFromObserver(lat, lon) >= rangeDistance) rangeHeight.toFloat() else 0f
            },
        )

        val drop = rangeDistance * rangeDistance / (2.0 * HorizonCalculator.REFRACTED_RADIUS_M)
        val expected = atan2(rangeHeight - eyeHeight - drop, rangeDistance) * 180.0 / PI
        assertTrue("expected a visible distant range, got $expected deg", expected > 1.0)

        for (azimuth in 0 until 360 step 13) {
            assertEquals(
                "distant range at $azimuth deg",
                expected,
                profile.angleAt(azimuth.toDouble()).toDouble(),
                0.05,
            )
            assertEquals(
                rangeDistance,
                profile.distanceAt(azimuth.toDouble()).toDouble(),
                600.0,
            )
        }
    }

    @Test
    fun `the observer elevation comes from the ground below them`() {
        val profile = HorizonCalculator.compute(
            latitudeDeg = latitude,
            longitudeDeg = longitude,
            source = { _, _ -> 1608f },
        )
        assertEquals(1608.0, profile.observerElevationMeters, 1e-3)
    }

    @Test
    fun `the profile wraps continuously through north`() {
        val ringDistance = 10_000.0
        val samples = 720
        val profile = HorizonCalculator.compute(
            latitudeDeg = latitude,
            longitudeDeg = longitude,
            sampleCount = samples,
            source = { lat, lon ->
                // A ring of hills whose height swells smoothly with bearing, so any
                // seam at north would stand out against an otherwise gentle profile.
                val distance = metersFromObserver(lat, lon)
                if (distance in (ringDistance - 400.0)..(ringDistance + 400.0)) {
                    val bearing = bearingFromObserver(lat, lon) * PI / 180.0
                    (400.0 * (1.0 + kotlin.math.sin(bearing))).toFloat()
                } else {
                    0f
                }
            },
        )

        assertEquals(
            profile.angleAt(0.0).toDouble(),
            profile.angleAt(360.0).toDouble(),
            1e-4,
        )

        // The step across north must be no worse than the typical step elsewhere.
        val step = 360.0 / samples
        val seamJump = abs(profile.angleAt(360.0 - step) - profile.angleAt(step))
        var largestElsewhere = 0f
        for (index in 2 until samples - 2) {
            val jump = abs(
                profile.angleAt(index * step) - profile.angleAt((index + 1) * step),
            )
            if (jump > largestElsewhere) largestElsewhere = jump
        }
        assertTrue(
            "seam at north jumped $seamJump vs $largestElsewhere elsewhere",
            seamJump <= largestElsewhere * 3f + 0.01f,
        )
    }

    @Test
    fun `web mercator tile indices match the published tile grid`() {
        // The tile that covers 8.4375 E, 47.99 N at zoom 10 is 10/536/356.
        assertEquals(536, TileMath.tileIndex(TileMath.pixelX(10, 8.4375)))
        assertEquals(356, TileMath.tileIndex(TileMath.pixelY(10, 47.9899)))
        assertEquals(0, TileMath.tileIndex(TileMath.pixelX(0, -180.0)))
        assertEquals(1023, TileMath.wrapTileX(-1, 10))
    }
}
