package com.skyhorizon.app.astro

import kotlin.math.acos
import kotlin.math.atan2

/** The eight conventional names of the lunar phase. */
enum class MoonPhaseName(val label: String) {
    NEW_MOON("New Moon"),
    WAXING_CRESCENT("Waxing Crescent"),
    FIRST_QUARTER("First Quarter"),
    WAXING_GIBBOUS("Waxing Gibbous"),
    FULL_MOON("Full Moon"),
    WANING_GIBBOUS("Waning Gibbous"),
    LAST_QUARTER("Last Quarter"),
    WANING_CRESCENT("Waning Crescent"),
}

data class MoonPhase(
    /** Fraction of the disc that is lit, `[0, 1]`. */
    val illuminatedFraction: Double,
    /** Phase angle Sun-Moon-Earth in degrees, 0 = full, 180 = new. */
    val phaseAngleDeg: Double,
    /** Age of the Moon expressed as elongation from the Sun, `[0, 360)`. 0 = new. */
    val elongationDeg: Double,
    /**
     * Position angle of the midpoint of the bright limb, measured from the celestial
     * north pole toward the east (Meeus, eq. 48.5).
     */
    val brightLimbAngleDeg: Double,
    val name: MoonPhaseName,
) {
    val isWaxing: Boolean get() = elongationDeg < 180.0
    val illuminatedPercent: Double get() = illuminatedFraction * 100.0
}

/** Everything the UI needs about one body at one instant. */
data class BodyPosition(
    val horizontal: Horizontal,
    val equatorial: Equatorial,
    val angularDiameterDeg: Double,
    val distanceKm: Double,
) {
    val azimuthDeg: Double get() = horizontal.azimuthDeg
    val altitudeDeg: Double get() = horizontal.altitudeDeg
    val apparentAltitudeDeg: Double get() = horizontal.apparentAltitudeDeg
    val isAboveHorizon: Boolean get() = horizontal.apparentAltitudeDeg > 0.0
}

/** A full snapshot of the sky for one observer at one instant. */
data class SkySnapshot(
    val epochMillis: Long,
    val julianDay: Double,
    val observer: Observer,
    val sun: BodyPosition,
    val moon: BodyPosition,
    val moonPhase: MoonPhase,
    /** Local apparent sidereal time in degrees. */
    val localSiderealTimeDeg: Double,
    val equationOfTimeMinutes: Double,
) {
    /** Coarse description of the daylight situation, driven by the Sun's altitude. */
    val twilight: Twilight get() = Twilight.of(sun.apparentAltitudeDeg)
}

enum class Twilight(val label: String) {
    DAY("Day"),
    GOLDEN_HOUR("Golden Hour"),
    CIVIL("Civil Twilight"),
    NAUTICAL("Nautical Twilight"),
    ASTRONOMICAL("Astronomical Twilight"),
    NIGHT("Night");

    companion object {
        fun of(sunAltitudeDeg: Double): Twilight = when {
            sunAltitudeDeg >= 6.0 -> DAY
            sunAltitudeDeg >= -0.833 -> GOLDEN_HOUR
            sunAltitudeDeg >= -6.0 -> CIVIL
            sunAltitudeDeg >= -12.0 -> NAUTICAL
            sunAltitudeDeg >= -18.0 -> ASTRONOMICAL
            else -> NIGHT
        }
    }
}

/**
 * Combines the solar and lunar solutions into a single observation, including the
 * topocentric reduction, refraction and the illuminated fraction of the Moon.
 */
object SkyEngine {

    fun snapshot(observer: Observer, epochMillis: Long): SkySnapshot {
        val site = observer.sanitized()
        val julianDay = AstroTime.julianDay(epochMillis)

        val sunResult = SunCalculator.compute(julianDay)
        val moonResult = MoonCalculator.compute(julianDay)

        val sunHorizontal = CoordinateTransform.toHorizontal(
            equatorial = sunResult.equatorial,
            observer = site,
            julianDay = julianDay,
            applyParallax = true,
        )
        val moonHorizontal = CoordinateTransform.toHorizontal(
            equatorial = moonResult.equatorial,
            observer = site,
            julianDay = julianDay,
            applyParallax = true,
        )

        val phase = moonPhase(
            sunEquatorial = sunResult.equatorial,
            moonEquatorial = moonResult.equatorial,
            sunDistanceKm = sunResult.distanceAu * SunCalculator.AU_KM,
            moonDistanceKm = moonResult.ecliptic.distanceKm,
            sunLongitudeDeg = sunResult.ecliptic.longitudeDeg,
            moonLongitudeDeg = moonResult.ecliptic.longitudeDeg,
        )

        val nutation = Nutation.forCenturies(AstroTime.julianCenturies(julianDay))
        val localSiderealTime = normalizeDegrees(
            AstroTime.greenwichMeanSiderealTime(julianDay) +
                nutation.longitudeDeg * cosDeg(nutation.trueObliquityDeg) +
                site.longitudeDeg,
        )

        return SkySnapshot(
            epochMillis = epochMillis,
            julianDay = julianDay,
            observer = site,
            sun = BodyPosition(
                horizontal = sunHorizontal,
                equatorial = sunResult.equatorial,
                angularDiameterDeg = sunResult.angularDiameterDeg,
                distanceKm = sunResult.distanceAu * SunCalculator.AU_KM,
            ),
            moon = BodyPosition(
                horizontal = moonHorizontal,
                equatorial = moonResult.equatorial,
                angularDiameterDeg = moonResult.angularDiameterDeg,
                distanceKm = moonResult.ecliptic.distanceKm,
            ),
            moonPhase = phase,
            localSiderealTimeDeg = localSiderealTime,
            equationOfTimeMinutes = sunResult.equationOfTimeMinutes,
        )
    }

    /**
     * Samples the altitude/azimuth track of both bodies over [durationMillis], which the
     * renderer draws as the arc each body follows across the sky.
     */
    fun track(
        observer: Observer,
        startMillis: Long,
        durationMillis: Long,
        samples: Int,
    ): List<TrackSample> {
        if (samples < 2) return emptyList()
        val step = durationMillis.toDouble() / (samples - 1)
        return (0 until samples).map { index ->
            val millis = startMillis + (index * step).toLong()
            val julianDay = AstroTime.julianDay(millis)
            val sun = CoordinateTransform.toHorizontal(
                SunCalculator.compute(julianDay).equatorial, observer, julianDay, false,
            )
            val moon = CoordinateTransform.toHorizontal(
                MoonCalculator.compute(julianDay).equatorial, observer, julianDay, true,
            )
            TrackSample(
                epochMillis = millis,
                sunAzimuthDeg = sun.azimuthDeg,
                sunAltitudeDeg = sun.apparentAltitudeDeg,
                moonAzimuthDeg = moon.azimuthDeg,
                moonAltitudeDeg = moon.apparentAltitudeDeg,
            )
        }
    }

    /** Meeus, ch. 48 - the illuminated fraction and the bright limb orientation. */
    private fun moonPhase(
        sunEquatorial: Equatorial,
        moonEquatorial: Equatorial,
        sunDistanceKm: Double,
        moonDistanceKm: Double,
        sunLongitudeDeg: Double,
        moonLongitudeDeg: Double,
    ): MoonPhase {
        // Geocentric elongation of the Moon from the Sun.
        val cosElongation = (
            sinDeg(sunEquatorial.declinationDeg) * sinDeg(moonEquatorial.declinationDeg) +
                cosDeg(sunEquatorial.declinationDeg) * cosDeg(moonEquatorial.declinationDeg) *
                cosDeg(sunEquatorial.rightAscensionDeg - moonEquatorial.rightAscensionDeg)
            ).coerceIn(-1.0, 1.0)
        val elongation = acos(cosElongation) * RAD_TO_DEG

        // Phase angle of the Moon (eq. 48.3).
        val phaseAngle = atan2(
            sunDistanceKm * sinDeg(elongation),
            moonDistanceKm - sunDistanceKm * cosDeg(elongation),
        ) * RAD_TO_DEG
        val illuminated = (1.0 + cosDeg(phaseAngle)) / 2.0

        // Position angle of the bright limb (eq. 48.5).
        val deltaRa = sunEquatorial.rightAscensionDeg - moonEquatorial.rightAscensionDeg
        val brightLimb = normalizeDegrees(
            atan2Deg(
                cosDeg(sunEquatorial.declinationDeg) * sinDeg(deltaRa),
                sinDeg(sunEquatorial.declinationDeg) * cosDeg(moonEquatorial.declinationDeg) -
                    cosDeg(sunEquatorial.declinationDeg) * sinDeg(moonEquatorial.declinationDeg) *
                    cosDeg(deltaRa),
            ),
        )

        // Signed elongation in ecliptic longitude tells waxing from waning.
        val signedElongation = normalizeDegrees(moonLongitudeDeg - sunLongitudeDeg)

        return MoonPhase(
            illuminatedFraction = illuminated.coerceIn(0.0, 1.0),
            phaseAngleDeg = phaseAngle,
            elongationDeg = signedElongation,
            brightLimbAngleDeg = brightLimb,
            name = phaseName(signedElongation),
        )
    }

    private fun phaseName(elongationDeg: Double): MoonPhaseName {
        // Each named phase covers 45 deg of elongation, centred on the exact instant.
        val shifted = normalizeDegrees(elongationDeg + 22.5)
        return when ((shifted / 45.0).toInt()) {
            0 -> MoonPhaseName.NEW_MOON
            1 -> MoonPhaseName.WAXING_CRESCENT
            2 -> MoonPhaseName.FIRST_QUARTER
            3 -> MoonPhaseName.WAXING_GIBBOUS
            4 -> MoonPhaseName.FULL_MOON
            5 -> MoonPhaseName.WANING_GIBBOUS
            6 -> MoonPhaseName.LAST_QUARTER
            else -> MoonPhaseName.WANING_CRESCENT
        }
    }
}

/** One sampled point of the daily path of the Sun and the Moon. */
data class TrackSample(
    val epochMillis: Long,
    val sunAzimuthDeg: Double,
    val sunAltitudeDeg: Double,
    val moonAzimuthDeg: Double,
    val moonAltitudeDeg: Double,
)
