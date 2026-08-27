package com.skyhorizon.app.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regression tests against the worked examples in Meeus, "Astronomical Algorithms"
 * (2nd edition). Tolerances allow for the fact that the app works in UT while the
 * book's examples are in TD (about one minute of time in the early 1990s).
 */
class AstroAccuracyTest {

    // 1992 October 13.0 TD - example 25.b.
    private val sunExampleJd = 2_448_908.5

    // 1992 April 12.0 TD - examples 47.a and 48.a.
    private val moonExampleJd = 2_448_724.5

    @Test
    fun `sun apparent longitude matches Meeus example 25b`() {
        val sun = SunCalculator.compute(sunExampleJd)
        assertEquals(199.90895, sun.ecliptic.longitudeDeg, 0.001)
        // Radius vector implied by the book's own intermediates (M = 278.99397,
        // C = -1.89732 give a true anomaly of 277.09665 deg).
        assertEquals(0.9976619, sun.distanceAu, 0.00002)
    }

    @Test
    fun `sun equatorial coordinates match Meeus example 25b`() {
        val sun = SunCalculator.compute(sunExampleJd)
        assertEquals(198.38083, sun.equatorial.rightAscensionDeg, 0.002)
        assertEquals(-7.78507, sun.equatorial.declinationDeg, 0.002)
    }

    @Test
    fun `moon ecliptic position matches Meeus example 47a`() {
        val moon = MoonCalculator.compute(moonExampleJd)
        // 133.162655 geometric + 0.004610 nutation in longitude.
        assertEquals(133.167265, moon.ecliptic.longitudeDeg, 0.001)
        assertEquals(-3.229126, moon.ecliptic.latitudeDeg, 0.001)
        assertEquals(368409.7, moon.ecliptic.distanceKm, 0.5)
        assertEquals(0.991990, moon.parallaxDeg, 0.0005)
    }

    @Test
    fun `illuminated fraction matches Meeus example 48a`() {
        val snapshot = SkyEngine.snapshot(
            observer = Observer(0.0, 0.0, 0.0),
            epochMillis = julianDayToMillis(moonExampleJd),
        )
        assertEquals(0.6786, snapshot.moonPhase.illuminatedFraction, 0.002)
        assertTrue(snapshot.moonPhase.isWaxing)
    }

    @Test
    fun `sun crosses the meridian due south from the northern hemisphere`() {
        // 2024-06-21 at solar noon in Greenwich, roughly 12:02 UT.
        val observer = Observer(latitudeDeg = 51.4779, longitudeDeg = 0.0)
        var bestAltitude = -90.0
        var bestAzimuth = 0.0
        for (minute in 0 until 24 * 60) {
            val millis = 1_718_928_000_000L + minute * 60_000L // 2024-06-21T00:00Z
            val snapshot = SkyEngine.snapshot(observer, millis)
            if (snapshot.sun.altitudeDeg > bestAltitude) {
                bestAltitude = snapshot.sun.altitudeDeg
                bestAzimuth = snapshot.sun.azimuthDeg
            }
        }
        // Maximum solar altitude at the solstice equals 90 - latitude + obliquity.
        assertEquals(62.0, bestAltitude, 0.5)
        assertEquals(180.0, bestAzimuth, 1.0)
    }

    @Test
    fun `sun culminates due north from the southern hemisphere`() {
        val observer = Observer(latitudeDeg = -33.8688, longitudeDeg = 151.2093)
        var bestAltitude = -90.0
        var bestAzimuth = 0.0
        for (minute in 0 until 24 * 60) {
            val millis = 1_718_928_000_000L + minute * 60_000L
            val snapshot = SkyEngine.snapshot(observer, millis)
            if (snapshot.sun.altitudeDeg > bestAltitude) {
                bestAltitude = snapshot.sun.altitudeDeg
                bestAzimuth = snapshot.sun.azimuthDeg
            }
        }
        assertEquals(0.0, normalizeSignedDegrees(bestAzimuth), 1.0)
        assertTrue("winter sun should stay low", bestAltitude in 30.0..35.0)
    }

    @Test
    fun `refraction lifts a body sitting on the geometric horizon`() {
        val apparent = Refraction.apparent(0.0)
        assertTrue("expected roughly 34 arcminutes, got $apparent", apparent in 0.45..0.65)
        // Monotonic and vanishing towards the zenith.
        assertTrue(Refraction.apparent(45.0) > 45.0)
        assertTrue(Refraction.apparent(89.0) - 89.0 < 0.01)
        assertEquals(-10.0, Refraction.apparent(-10.0), 1e-9)
    }

    @Test
    fun `topocentric parallax moves the moon by up to one degree`() {
        val julianDay = 2_460_000.5
        val moon = MoonCalculator.compute(julianDay)
        val observer = Observer(latitudeDeg = 45.0, longitudeDeg = 0.0)
        val geocentric = CoordinateTransform.toHorizontal(moon.equatorial, observer, julianDay, false)
        val topocentric = CoordinateTransform.toHorizontal(moon.equatorial, observer, julianDay, true)
        val shift = abs(geocentric.altitudeDeg - topocentric.altitudeDeg)
        assertTrue("parallax shift was $shift", shift in 0.0..1.1)
        // Parallax always pushes the body downwards, never up.
        assertTrue(topocentric.altitudeDeg <= geocentric.altitudeDeg + 1e-9)
    }

    @Test
    fun `azimuth stays inside the full circle and altitude inside the hemisphere`() {
        val observer = Observer(latitudeDeg = 78.2232, longitudeDeg = 15.6469)
        for (hour in 0 until 24 * 30 step 7) {
            val snapshot = SkyEngine.snapshot(observer, 1_700_000_000_000L + hour * 3_600_000L)
            assertTrue(snapshot.sun.azimuthDeg in 0.0..360.0)
            assertTrue(snapshot.moon.azimuthDeg in 0.0..360.0)
            assertTrue(snapshot.sun.altitudeDeg in -90.0..90.0)
            assertTrue(snapshot.moon.altitudeDeg in -90.0..90.0)
            assertTrue(snapshot.moonPhase.illuminatedFraction in 0.0..1.0)
        }
    }

    private fun julianDayToMillis(julianDay: Double): Long =
        ((julianDay - 2_440_587.5) * 86_400_000.0).toLong()
}
