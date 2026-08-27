package com.skyhorizon.app.astro

/**
 * Apparent geocentric position of the Sun, following Meeus "Astronomical Algorithms"
 * chapter 25 (the "lower accuracy" solution, good to roughly 0.01 deg between 1900
 * and 2100 - far below what the eye or this app's rendering can resolve).
 */
object SunCalculator {

    /** One astronomical unit in kilometres. */
    const val AU_KM = 149_597_870.7

    data class Result(
        val ecliptic: Ecliptic,
        val equatorial: Equatorial,
        val distanceAu: Double,
        /** Apparent angular diameter of the solar disc, in degrees. */
        val angularDiameterDeg: Double,
        /** Difference between apparent and mean solar time, in minutes. */
        val equationOfTimeMinutes: Double,
    )

    fun compute(julianDay: Double): Result {
        val t = AstroTime.julianCenturies(julianDay)
        val nutation = Nutation.forCenturies(t)

        // Geometric mean longitude and mean anomaly.
        val meanLongitude = normalizeDegrees(280.46646 + 36000.76983 * t + 0.0003032 * t * t)
        val meanAnomaly = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
        val eccentricity = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t

        // Equation of the centre.
        val centre = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sinDeg(meanAnomaly) +
            (0.019993 - 0.000101 * t) * sinDeg(2 * meanAnomaly) +
            0.000289 * sinDeg(3 * meanAnomaly)

        val trueLongitude = meanLongitude + centre
        val trueAnomaly = meanAnomaly + centre

        // Radius vector in astronomical units.
        val radiusAu = 1.000001018 * (1 - eccentricity * eccentricity) /
            (1 + eccentricity * cosDeg(trueAnomaly))

        // Apparent longitude: aberration plus the nutation in longitude.
        val omega = 125.04 - 1934.136 * t
        val apparentLongitude = trueLongitude - 0.00569 - 0.00478 * sinDeg(omega)

        // Meeus recommends this correction to the obliquity for the apparent position.
        val obliquity = nutation.meanObliquityDeg + 0.00256 * cosDeg(omega)

        val ecliptic = Ecliptic(
            longitudeDeg = normalizeDegrees(apparentLongitude),
            latitudeDeg = 0.0,
            distanceKm = radiusAu * AU_KM,
        )
        val equatorial = CoordinateTransform.eclipticToEquatorial(ecliptic, obliquity)

        return Result(
            ecliptic = ecliptic,
            equatorial = equatorial,
            distanceAu = radiusAu,
            angularDiameterDeg = 0.533128 / radiusAu,
            equationOfTimeMinutes = equationOfTime(
                meanLongitude = meanLongitude,
                meanAnomaly = meanAnomaly,
                eccentricity = eccentricity,
                obliquityDeg = obliquity,
            ),
        )
    }

    /** Meeus, eq. 28.3 - the equation of time expressed in minutes of time. */
    private fun equationOfTime(
        meanLongitude: Double,
        meanAnomaly: Double,
        eccentricity: Double,
        obliquityDeg: Double,
    ): Double {
        val y = tanDeg(obliquityDeg / 2.0).let { it * it }
        val radians = y * sinDeg(2 * meanLongitude) -
            2 * eccentricity * sinDeg(meanAnomaly) +
            4 * eccentricity * y * sinDeg(meanAnomaly) * cosDeg(2 * meanLongitude) -
            0.5 * y * y * sinDeg(4 * meanLongitude) -
            1.25 * eccentricity * eccentricity * sinDeg(2 * meanAnomaly)
        return radians * RAD_TO_DEG * 4.0
    }
}
