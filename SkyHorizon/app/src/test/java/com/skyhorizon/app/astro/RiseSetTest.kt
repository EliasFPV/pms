package com.skyhorizon.app.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class RiseSetTest {

    private fun minutesOfDay(millis: Long): Double {
        val time = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalTime()
        return time.hour * 60.0 + time.minute + time.second / 60.0
    }

    /** 2026-03-20, close to the March equinox. */
    private val equinoxStart = Instant.parse("2026-03-20T00:00:00Z").toEpochMilli()

    @Test
    fun `at the equator on the equinox sunrise and sunset straddle solar noon`() {
        val observer = Observer(latitudeDeg = 0.0, longitudeDeg = 0.0)
        val events = RiseSetCalculator.forWindow(observer, equinoxStart)
        val rise = events.sun.riseMillis
        val set = events.sun.setMillis
        assertNotNull("expected a sunrise", rise)
        assertNotNull("expected a sunset", set)

        // Solar noon is not 12:00 UTC: in late March the equation of time puts the
        // Sun's transit some minutes late, and rise and set must straddle that, not
        // the clock.
        val equationOfTime = SkyEngine
            .snapshot(observer, equinoxStart + 12 * 3_600_000L)
            .equationOfTimeMinutes
        assertTrue(
            "equation of time in late March should be about -7 min, was $equationOfTime",
            equationOfTime in -9.0..-6.0,
        )
        val solarNoonMinutes = 12 * 60.0 - equationOfTime

        val midpoint = (minutesOfDay(rise!!) + minutesOfDay(set!!)) / 2.0
        assertEquals("rise and set should straddle solar noon", solarNoonMinutes, midpoint, 1.5)

        // Refraction and the solar semi-diameter together lift the Sun into view a few
        // minutes early and hold it a few minutes late, so the day runs slightly long.
        val dayLengthMinutes = (set - rise) / 60_000.0
        assertTrue("day length was $dayLengthMinutes min", dayLengthMinutes in 720.0..735.0)
        assertEquals(solarNoonMinutes - dayLengthMinutes / 2.0, minutesOfDay(rise), 1.5)
    }

    @Test
    fun `a ridge to the east delays sunrise and one to the west brings sunset forward`() {
        val observer = Observer(latitudeDeg = 0.0, longitudeDeg = 0.0)
        val flat = RiseSetCalculator.forWindow(observer, equinoxStart)
        val walled = RiseSetCalculator.forWindow(
            observer = observer,
            startMillis = equinoxStart,
            skyline = { 10.0 },
        )

        val riseDelay = (walled.sun.riseMillis!! - flat.sun.riseMillis!!) / 60_000.0
        val setAdvance = (flat.sun.setMillis!! - walled.sun.setMillis!!) / 60_000.0

        // At the equator the Sun climbs about 15 degrees an hour, so ten degrees of
        // skyline costs roughly forty minutes at each end.
        assertEquals(40.0, riseDelay, 6.0)
        assertEquals(40.0, setAdvance, 6.0)
    }

    @Test
    fun `the upper limb definition makes the day longer than a centre crossing would`() {
        val observer = Observer(latitudeDeg = 0.0, longitudeDeg = 0.0)
        val events = RiseSetCalculator.forWindow(observer, equinoxStart)
        val dayLength = (events.sun.setMillis!! - events.sun.riseMillis!!) / 60_000.0
        // Geometric sunrise to sunset at the equinox is twelve hours exactly; the limb
        // and refraction add several minutes.
        assertTrue("day length was $dayLength min", dayLength > 723.0)
    }

    @Test
    fun `the midnight sun never sets`() {
        val events = RiseSetCalculator.forWindow(
            observer = Observer(latitudeDeg = 78.22, longitudeDeg = 15.65),
            startMillis = Instant.parse("2026-06-21T00:00:00Z").toEpochMilli(),
        )
        assertTrue("expected midnight sun", events.sun.alwaysUp)
        assertNull(events.sun.riseMillis)
        assertNull(events.sun.setMillis)
    }

    @Test
    fun `polar night keeps the sun below the skyline`() {
        val events = RiseSetCalculator.forWindow(
            observer = Observer(latitudeDeg = 78.22, longitudeDeg = 15.65),
            startMillis = Instant.parse("2026-12-21T00:00:00Z").toEpochMilli(),
        )
        assertTrue("expected polar night", events.sun.alwaysDown)
    }

    @Test
    fun `the moon rises later each day`() {
        val observer = Observer(latitudeDeg = 45.0, longitudeDeg = 9.0)
        val first = RiseSetCalculator.forWindow(observer, equinoxStart)
        val second = RiseSetCalculator.forWindow(observer, equinoxStart + 24 * 3_600_000L)
        val firstRise = first.moon.riseMillis
        val secondRise = second.moon.riseMillis
        if (firstRise != null && secondRise != null) {
            val shiftMinutes = (secondRise - firstRise) / 60_000.0 - 24 * 60.0
            // The Moon slips roughly 50 minutes a day, varying with its orbit.
            assertTrue("moon shifted $shiftMinutes min", shiftMinutes in 20.0..90.0)
        }
    }
}
