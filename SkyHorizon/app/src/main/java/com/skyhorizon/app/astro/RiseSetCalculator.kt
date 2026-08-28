package com.skyhorizon.app.astro

/**
 * Rise and set times for the Sun and the Moon.
 *
 * A body rises when its **upper limb** appears, not its centre, so its semi-diameter
 * is added to the refracted altitude of the centre. Over a flat horizon that
 * reproduces the conventional -0.833 deg definition of sunrise; here the threshold is
 * instead the observer's real skyline, so a mountain to the east genuinely delays
 * sunrise.
 */
object RiseSetCalculator {

    /** Altitude of the local skyline, in degrees, as a function of azimuth. */
    fun interface SkylineAltitude {
        fun degreesAt(azimuthDeg: Double): Double
    }

    /** The idealised horizon at 0 deg, for comparison with the terrain result. */
    val FLAT = SkylineAltitude { 0.0 }

    data class Event(
        val riseMillis: Long?,
        val setMillis: Long?,
        /** The body never sets during the window (polar day, or circumpolar). */
        val alwaysUp: Boolean,
        /** The body never clears the skyline during the window. */
        val alwaysDown: Boolean,
    )

    data class DayEvents(val sun: Event, val moon: Event)

    private const val COARSE_STEP_MILLIS = 4 * 60_000L
    private const val REFINEMENTS = 24

    /**
     * Scans [durationMillis] from [startMillis] for the first rise and the first set
     * of each body.
     */
    fun forWindow(
        observer: Observer,
        startMillis: Long,
        durationMillis: Long = 24 * 3_600_000L,
        skyline: SkylineAltitude = FLAT,
    ): DayEvents {
        val times = ArrayList<Long>()
        val sunHeights = ArrayList<Double>()
        val moonHeights = ArrayList<Double>()

        var time = startMillis
        val end = startMillis + durationMillis
        while (time <= end) {
            val snapshot = SkyEngine.snapshot(observer, time)
            times.add(time)
            sunHeights.add(clearance(snapshot.sun, skyline))
            moonHeights.add(clearance(snapshot.moon, skyline))
            time += COARSE_STEP_MILLIS
        }

        return DayEvents(
            sun = eventsFrom(times, sunHeights) { millis ->
                clearance(SkyEngine.snapshot(observer, millis).sun, skyline)
            },
            moon = eventsFrom(times, moonHeights) { millis ->
                clearance(SkyEngine.snapshot(observer, millis).moon, skyline)
            },
        )
    }

    /**
     * How far the body's upper limb stands above the skyline, in degrees. Positive
     * means visible.
     */
    private fun clearance(body: BodyPosition, skyline: SkylineAltitude): Double =
        body.apparentAltitudeDeg + body.angularDiameterDeg / 2.0 -
            skyline.degreesAt(body.azimuthDeg)

    private fun eventsFrom(
        times: List<Long>,
        heights: List<Double>,
        evaluate: (Long) -> Double,
    ): Event {
        var rise: Long? = null
        var set: Long? = null
        var anyUp = false
        var anyDown = false

        for (index in heights.indices) {
            if (heights[index] > 0.0) anyUp = true else anyDown = true
            if (index == 0) continue
            val before = heights[index - 1]
            val now = heights[index]
            if (before <= 0.0 && now > 0.0 && rise == null) {
                rise = refine(times[index - 1], times[index], evaluate)
            }
            if (before > 0.0 && now <= 0.0 && set == null) {
                set = refine(times[index - 1], times[index], evaluate)
            }
        }

        return Event(
            riseMillis = rise,
            setMillis = set,
            alwaysUp = !anyDown,
            alwaysDown = !anyUp,
        )
    }

    /** Bisects the bracketed crossing down to well under a second. */
    private fun refine(
        lowMillis: Long,
        highMillis: Long,
        evaluate: (Long) -> Double,
    ): Long {
        var low = lowMillis
        var high = highMillis
        val lowSign = evaluate(low) > 0.0
        repeat(REFINEMENTS) {
            if (high - low <= 1L) return low
            val middle = low + (high - low) / 2
            if ((evaluate(middle) > 0.0) == lowSign) low = middle else high = middle
        }
        return (low + high) / 2
    }
}
