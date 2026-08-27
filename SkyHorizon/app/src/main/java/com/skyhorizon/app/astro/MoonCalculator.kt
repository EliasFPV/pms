package com.skyhorizon.app.astro

import kotlin.math.pow

/**
 * Apparent geocentric position of the Moon following Meeus "Astronomical Algorithms"
 * chapter 47. The full 60-term periodic series for longitude/distance (table 47.A)
 * and latitude (table 47.B) are used, which yields about 10" in longitude and 4" in
 * latitude - roughly a fiftieth of the lunar disc.
 */
object MoonCalculator {

    /** Mean radius of the Moon in kilometres, used for the apparent diameter. */
    private const val MOON_RADIUS_KM = 1737.4

    data class Result(
        val ecliptic: Ecliptic,
        val equatorial: Equatorial,
        /** Equatorial horizontal parallax in degrees. */
        val parallaxDeg: Double,
        /** Apparent angular diameter of the lunar disc, in degrees. */
        val angularDiameterDeg: Double,
    )

    // Table 47.A - arguments are multiples of D, M, M', F.
    private val ARGUMENTS_LR = intArrayOf(
        0, 0, 1, 0, /**/ 2, 0, -1, 0, /**/ 2, 0, 0, 0, /**/ 0, 0, 2, 0,
        0, 1, 0, 0, /**/ 0, 0, 0, 2, /**/ 2, 0, -2, 0, /**/ 2, -1, -1, 0,
        2, 0, 1, 0, /**/ 2, -1, 0, 0, /**/ 0, 1, -1, 0, /**/ 1, 0, 0, 0,
        0, 1, 1, 0, /**/ 2, 0, 0, -2, /**/ 0, 0, 1, 2, /**/ 0, 0, 1, -2,
        4, 0, -1, 0, /**/ 0, 0, 3, 0, /**/ 4, 0, -2, 0, /**/ 2, 1, -1, 0,
        2, 1, 0, 0, /**/ 1, 0, -1, 0, /**/ 1, 1, 0, 0, /**/ 2, -1, 1, 0,
        2, 0, 2, 0, /**/ 4, 0, 0, 0, /**/ 2, 0, -3, 0, /**/ 0, 1, -2, 0,
        2, 0, -1, 2, /**/ 2, -1, -2, 0, /**/ 1, 0, 1, 0, /**/ 2, -2, 0, 0,
        0, 1, 2, 0, /**/ 0, 2, 0, 0, /**/ 2, -2, -1, 0, /**/ 2, 0, 1, -2,
        2, 0, 0, 2, /**/ 4, -1, -1, 0, /**/ 0, 0, 2, 2, /**/ 3, 0, -1, 0,
        2, 1, 1, 0, /**/ 4, -1, -2, 0, /**/ 0, 2, -1, 0, /**/ 2, 2, -1, 0,
        2, 1, -2, 0, /**/ 2, -1, 0, -2, /**/ 4, 0, 1, 0, /**/ 0, 0, 4, 0,
        4, -1, 0, 0, /**/ 1, 0, -2, 0, /**/ 2, 1, 0, -2, /**/ 0, 0, 2, -2,
        1, 1, 1, 0, /**/ 3, 0, -2, 0, /**/ 4, 0, -3, 0, /**/ 2, -1, 2, 0,
        0, 2, 1, 0, /**/ 1, 1, -1, 0, /**/ 2, 0, 3, 0, /**/ 2, 0, -1, -2,
    )

    // Table 47.A - sine coefficients for the longitude, in units of 1e-6 degrees.
    private val COEFFICIENTS_L = intArrayOf(
        6288774, 1274027, 658314, 213618, -185116, -114332, 58793, 57066,
        53322, 45758, -40923, -34720, -30383, 15327, -12528, 10980,
        10675, 10034, 8548, -7888, -6766, -5163, 4987, 4036,
        3994, 3861, 3665, -2689, -2602, 2390, -2348, 2236,
        -2120, -2069, 2048, -1773, -1595, 1215, -1110, -892,
        -810, 759, -713, -700, 691, 596, 549, 537,
        520, -487, -399, -381, 351, -340, 330, 327,
        -323, 299, 294, 0,
    )

    // Table 47.A - cosine coefficients for the distance, in units of 1e-3 kilometres.
    private val COEFFICIENTS_R = intArrayOf(
        -20905355, -3699111, -2955968, -569925, 48888, -3149, 246158, -152138,
        -170733, -204586, -129620, 108743, 104755, 10321, 0, 79661,
        -34782, -23210, -21636, 24208, 30824, -8379, -16675, -12831,
        -10445, -11650, 14403, -7003, 0, 10056, 6322, -9884,
        5751, 0, -4950, 4130, 0, -3958, 0, 3258,
        2616, -1897, -2117, 2354, 0, 0, -1423, -1117,
        -1571, -1739, 0, -4421, 0, 0, 0, 0,
        1165, 0, 0, 8752,
    )

    // Table 47.B - arguments are multiples of D, M, M', F.
    private val ARGUMENTS_B = intArrayOf(
        0, 0, 0, 1, /**/ 0, 0, 1, 1, /**/ 0, 0, 1, -1, /**/ 2, 0, 0, -1,
        2, 0, -1, 1, /**/ 2, 0, -1, -1, /**/ 2, 0, 0, 1, /**/ 0, 0, 2, 1,
        2, 0, 1, -1, /**/ 0, 0, 2, -1, /**/ 2, -1, 0, -1, /**/ 2, 0, -2, -1,
        2, 0, 1, 1, /**/ 2, 1, 0, -1, /**/ 2, -1, -1, 1, /**/ 2, -1, 0, 1,
        2, -1, -1, -1, /**/ 0, 1, -1, -1, /**/ 4, 0, -1, -1, /**/ 0, 1, 0, 1,
        0, 0, 0, 3, /**/ 0, 1, -1, 1, /**/ 1, 0, 0, 1, /**/ 0, 1, 1, 1,
        0, 1, 1, -1, /**/ 0, 1, 0, -1, /**/ 1, 0, 0, -1, /**/ 0, 0, 3, 1,
        4, 0, 0, -1, /**/ 4, 0, -1, 1, /**/ 0, 0, 1, -3, /**/ 4, 0, -2, 1,
        2, 0, 0, -3, /**/ 2, 0, 2, -1, /**/ 2, -1, 1, -1, /**/ 2, 0, -2, 1,
        0, 0, 3, -1, /**/ 2, 0, 2, 1, /**/ 2, 0, -3, -1, /**/ 2, 1, -1, 1,
        2, 1, 0, 1, /**/ 4, 0, 0, 1, /**/ 2, -1, 1, 1, /**/ 2, -2, 0, -1,
        0, 0, 1, 3, /**/ 2, 1, 1, -1, /**/ 1, 1, 0, -1, /**/ 1, 1, 0, 1,
        0, 1, -2, -1, /**/ 2, 1, -1, -1, /**/ 1, 0, 1, 1, /**/ 2, -1, -2, -1,
        0, 1, 2, 1, /**/ 4, 0, -2, -1, /**/ 4, -1, -1, -1, /**/ 1, 0, 1, -1,
        4, 0, 1, -1, /**/ 1, 0, -1, -1, /**/ 4, -1, 0, -1, /**/ 2, -2, 0, 1,
    )

    // Table 47.B - sine coefficients for the latitude, in units of 1e-6 degrees.
    private val COEFFICIENTS_B = intArrayOf(
        5128122, 280602, 277693, 173237, 55413, 46271, 32573, 17198,
        9266, 8822, 8216, 4324, 4200, -3359, 2463, 2211,
        2065, -1870, 1828, -1794, -1749, -1565, -1491, -1475,
        -1410, -1344, -1335, 1107, 1021, 833, 777, 671,
        607, 596, 491, -451, 439, 422, 421, -366,
        -351, 331, 315, 302, -283, -229, 223, 223,
        -220, -220, -185, 181, -177, 176, 166, -164,
        132, -119, 115, 107,
    )

    fun compute(julianDay: Double): Result {
        val t = AstroTime.julianCenturies(julianDay)
        val nutation = Nutation.forCenturies(t)

        // Mean elements of the lunar orbit (Meeus, eq. 47.1 - 47.5).
        val meanLongitude = normalizeDegrees(
            218.3164477 + 481267.88123421 * t - 0.0015786 * t.pow(2) +
                t.pow(3) / 538841.0 - t.pow(4) / 65_194_000.0,
        )
        val meanElongation = normalizeDegrees(
            297.8501921 + 445267.1114034 * t - 0.0018819 * t.pow(2) +
                t.pow(3) / 545868.0 - t.pow(4) / 113_065_000.0,
        )
        val sunMeanAnomaly = normalizeDegrees(
            357.5291092 + 35999.0502909 * t - 0.0001536 * t.pow(2) + t.pow(3) / 24_490_000.0,
        )
        val moonMeanAnomaly = normalizeDegrees(
            134.9633964 + 477198.8675055 * t + 0.0087414 * t.pow(2) +
                t.pow(3) / 69699.0 - t.pow(4) / 14_712_000.0,
        )
        val argumentOfLatitude = normalizeDegrees(
            93.2720950 + 483202.0175233 * t - 0.0036539 * t.pow(2) -
                t.pow(3) / 3_526_000.0 + t.pow(4) / 863_310_000.0,
        )

        // Further arguments for the additive corrections (Venus, Jupiter, flattening).
        val a1 = normalizeDegrees(119.75 + 131.849 * t)
        val a2 = normalizeDegrees(53.09 + 479264.290 * t)
        val a3 = normalizeDegrees(313.45 + 481266.484 * t)

        // Eccentricity correction of the Earth's orbit.
        val e = 1 - 0.002516 * t - 0.0000074 * t * t

        var sumL = 0.0
        var sumR = 0.0
        for (i in COEFFICIENTS_L.indices) {
            val d = ARGUMENTS_LR[i * 4]
            val m = ARGUMENTS_LR[i * 4 + 1]
            val mPrime = ARGUMENTS_LR[i * 4 + 2]
            val f = ARGUMENTS_LR[i * 4 + 3]
            val argument = d * meanElongation + m * sunMeanAnomaly +
                mPrime * moonMeanAnomaly + f * argumentOfLatitude
            val eccentricityFactor = eccentricityFactor(m, e)
            sumL += COEFFICIENTS_L[i] * eccentricityFactor * sinDeg(argument)
            sumR += COEFFICIENTS_R[i] * eccentricityFactor * cosDeg(argument)
        }

        var sumB = 0.0
        for (i in COEFFICIENTS_B.indices) {
            val d = ARGUMENTS_B[i * 4]
            val m = ARGUMENTS_B[i * 4 + 1]
            val mPrime = ARGUMENTS_B[i * 4 + 2]
            val f = ARGUMENTS_B[i * 4 + 3]
            val argument = d * meanElongation + m * sunMeanAnomaly +
                mPrime * moonMeanAnomaly + f * argumentOfLatitude
            sumB += COEFFICIENTS_B[i] * eccentricityFactor(m, e) * sinDeg(argument)
        }

        // Additive terms (Meeus, p. 342).
        sumL += 3958 * sinDeg(a1) +
            1962 * sinDeg(meanLongitude - argumentOfLatitude) +
            318 * sinDeg(a2)
        sumB += -2235 * sinDeg(meanLongitude) +
            382 * sinDeg(a3) +
            175 * sinDeg(a1 - argumentOfLatitude) +
            175 * sinDeg(a1 + argumentOfLatitude) +
            127 * sinDeg(meanLongitude - moonMeanAnomaly) -
            115 * sinDeg(meanLongitude + moonMeanAnomaly)

        val longitude = normalizeDegrees(meanLongitude + sumL / 1_000_000.0 + nutation.longitudeDeg)
        val latitude = sumB / 1_000_000.0
        val distanceKm = 385_000.56 + sumR / 1000.0

        val ecliptic = Ecliptic(
            longitudeDeg = longitude,
            latitudeDeg = latitude,
            distanceKm = distanceKm,
        )
        val equatorial = CoordinateTransform.eclipticToEquatorial(
            ecliptic = ecliptic,
            obliquityDeg = nutation.trueObliquityDeg,
        )

        val parallax = asinDeg(EARTH_RADIUS_KM / distanceKm)
        val angularDiameter = 2.0 * asinDeg(MOON_RADIUS_KM / distanceKm)

        return Result(
            ecliptic = ecliptic,
            equatorial = equatorial,
            parallaxDeg = parallax,
            angularDiameterDeg = angularDiameter,
        )
    }

    /** Terms involving the Sun's anomaly are scaled by the Earth's orbital eccentricity. */
    private fun eccentricityFactor(sunAnomalyMultiple: Int, e: Double): Double =
        when (sunAnomalyMultiple) {
            1, -1 -> e
            2, -2 -> e * e
            else -> 1.0
        }
}
