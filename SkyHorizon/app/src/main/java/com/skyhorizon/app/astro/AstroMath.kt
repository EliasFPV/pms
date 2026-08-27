package com.skyhorizon.app.astro

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

internal const val DEG_TO_RAD = PI / 180.0
internal const val RAD_TO_DEG = 180.0 / PI

/** Equatorial radius of the Earth in kilometres (IAU 1976). */
internal const val EARTH_RADIUS_KM = 6378.14

internal fun sinDeg(degrees: Double): Double = sin(degrees * DEG_TO_RAD)
internal fun cosDeg(degrees: Double): Double = cos(degrees * DEG_TO_RAD)
internal fun tanDeg(degrees: Double): Double = tan(degrees * DEG_TO_RAD)
internal fun asinDeg(value: Double): Double = asin(value.coerceIn(-1.0, 1.0)) * RAD_TO_DEG
internal fun atan2Deg(y: Double, x: Double): Double = atan2(y, x) * RAD_TO_DEG

/** Wraps an angle into `[0, 360)`. */
fun normalizeDegrees(degrees: Double): Double {
    val wrapped = degrees % 360.0
    return if (wrapped < 0.0) wrapped + 360.0 else wrapped
}

/** Wraps an angle into `(-180, 180]`, i.e. the shortest signed distance from zero. */
fun normalizeSignedDegrees(degrees: Double): Double {
    var wrapped = normalizeDegrees(degrees)
    if (wrapped > 180.0) wrapped -= 360.0
    return wrapped
}

/** Time scales and sidereal time. */
object AstroTime {

    /** Julian Day number of the J2000.0 epoch. */
    const val J2000 = 2451545.0

    /** Julian Day (UT) for a Unix epoch timestamp in milliseconds. */
    fun julianDay(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2_440_587.5

    /** Julian centuries since J2000.0. */
    fun julianCenturies(julianDay: Double): Double = (julianDay - J2000) / 36525.0

    /** Greenwich mean sidereal time in degrees (Meeus, eq. 12.4). */
    fun greenwichMeanSiderealTime(julianDay: Double): Double {
        val t = julianCenturies(julianDay)
        val theta = 280.46061837 +
            360.98564736629 * (julianDay - J2000) +
            0.000387933 * t * t -
            t * t * t / 38_710_000.0
        return normalizeDegrees(theta)
    }
}

/** Nutation in longitude/obliquity and the obliquity of the ecliptic (Meeus, ch. 22). */
data class Nutation(
    val longitudeDeg: Double,
    val obliquityDeg: Double,
    val meanObliquityDeg: Double,
) {
    val trueObliquityDeg: Double get() = meanObliquityDeg + obliquityDeg

    companion object {
        fun forCenturies(t: Double): Nutation {
            val omega = 125.04452 - 1934.136261 * t
            val sunLongitude = 280.4665 + 36000.7698 * t
            val moonLongitude = 218.3165 + 481267.8813 * t

            // Arc seconds -> degrees.
            val dPsi = (-17.20 * sinDeg(omega) -
                1.32 * sinDeg(2 * sunLongitude) -
                0.23 * sinDeg(2 * moonLongitude) +
                0.21 * sinDeg(2 * omega)) / 3600.0
            val dEps = (9.20 * cosDeg(omega) +
                0.57 * cosDeg(2 * sunLongitude) +
                0.10 * cosDeg(2 * moonLongitude) -
                0.09 * cosDeg(2 * omega)) / 3600.0

            // Mean obliquity, Meeus eq. 22.2 (accurate form).
            val u = t / 100.0
            val eps0 = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 -
                (4680.93 * u +
                    1.55 * u * u -
                    1999.25 * u * u * u -
                    51.38 * u * u * u * u +
                    249.67 * u * u * u * u * u +
                    39.05 * u * u * u * u * u * u -
                    7.12 * u * u * u * u * u * u * u -
                    27.87 * u * u * u * u * u * u * u * u +
                    5.79 * u * u * u * u * u * u * u * u * u +
                    2.45 * u * u * u * u * u * u * u * u * u * u) / 3600.0

            return Nutation(longitudeDeg = dPsi, obliquityDeg = dEps, meanObliquityDeg = eps0)
        }
    }
}

/**
 * Atmospheric refraction, Bennett's formula (Meeus, eq. 16.3) with the standard
 * 1.02/60 correction so that a geometric altitude of 0 deg maps to roughly +0.57 deg
 * of apparent altitude. Below the cut-off the correction is faded out so the curve
 * stays continuous and monotonic well under the horizon.
 */
object Refraction {

    private const val LOWER_LIMIT_DEG = -2.0

    fun apparent(trueAltitudeDeg: Double): Double {
        if (trueAltitudeDeg <= LOWER_LIMIT_DEG) return trueAltitudeDeg
        val r = 1.02 / tanDeg(trueAltitudeDeg + 10.3 / (trueAltitudeDeg + 5.11)) / 60.0
        return trueAltitudeDeg + r.coerceIn(0.0, 0.6)
    }
}

/** Geocentric or topocentric equatorial coordinates. */
data class Equatorial(
    val rightAscensionDeg: Double,
    val declinationDeg: Double,
    /** Distance to the body in kilometres, or [Double.NaN] when parallax is irrelevant. */
    val distanceKm: Double = Double.NaN,
)

/** Ecliptic coordinates of date. */
data class Ecliptic(
    val longitudeDeg: Double,
    val latitudeDeg: Double,
    val distanceKm: Double,
)

/** Position of a body with respect to the observer's horizon. */
data class Horizontal(
    /** Azimuth measured from true north, increasing eastward: `[0, 360)`. */
    val azimuthDeg: Double,
    /** Geometric (airless) altitude: `[-90, 90]`. */
    val altitudeDeg: Double,
    /** Altitude including atmospheric refraction. */
    val apparentAltitudeDeg: Double,
    /** Angle between the celestial north pole and the zenith as seen from the body. */
    val parallacticAngleDeg: Double,
    /** Local hour angle in degrees, topocentric when parallax was applied. */
    val hourAngleDeg: Double,
    /** Topocentric equatorial coordinates that produced this position. */
    val topocentric: Equatorial,
)

/** An observing site on the Earth's surface. */
data class Observer(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val elevationMeters: Double = 0.0,
) {
    fun sanitized(): Observer = copy(
        latitudeDeg = latitudeDeg.coerceIn(-90.0, 90.0),
        longitudeDeg = normalizeSignedDegrees(longitudeDeg),
        elevationMeters = elevationMeters.coerceIn(-500.0, 9000.0),
    )
}

/** Equatorial -> horizontal conversion including diurnal parallax. */
object CoordinateTransform {

    /**
     * Converts geocentric equatorial coordinates to the observer's horizon frame.
     *
     * @param applyParallax when true (and a finite distance is present) the geocentric
     *   position is reduced to the observer's location on the Earth's surface. This
     *   matters for the Moon, where the shift reaches about one degree.
     */
    fun toHorizontal(
        equatorial: Equatorial,
        observer: Observer,
        julianDay: Double,
        applyParallax: Boolean,
    ): Horizontal {
        val t = AstroTime.julianCenturies(julianDay)
        val nutation = Nutation.forCenturies(t)
        val gmst = AstroTime.greenwichMeanSiderealTime(julianDay)
        // Apparent sidereal time: add the equation of the equinoxes.
        val gast = gmst + nutation.longitudeDeg * cosDeg(nutation.trueObliquityDeg)
        val localSiderealTime = gast + observer.longitudeDeg

        var hourAngle = normalizeDegrees(localSiderealTime - equatorial.rightAscensionDeg)
        var declination = equatorial.declinationDeg

        if (applyParallax && equatorial.distanceKm.isFinite() && equatorial.distanceKm > 0.0) {
            // Meeus, ch. 40: the observer's geocentric coordinates on a flattened Earth.
            val flattening = 0.99664719
            val u = atan(flattening * tanDeg(observer.latitudeDeg))
            val heightRatio = observer.elevationMeters / (EARTH_RADIUS_KM * 1000.0)
            val rhoSinPhi = flattening * sin(u) + heightRatio * sinDeg(observer.latitudeDeg)
            val rhoCosPhi = cos(u) + heightRatio * cosDeg(observer.latitudeDeg)

            val sinParallax = EARTH_RADIUS_KM / equatorial.distanceKm
            val denominator = cosDeg(declination) - rhoCosPhi * sinParallax * cosDeg(hourAngle)
            val deltaRa = atan2Deg(
                -rhoCosPhi * sinParallax * sinDeg(hourAngle),
                denominator,
            )
            declination = atan2Deg(
                (sinDeg(declination) - rhoSinPhi * sinParallax) * cosDeg(deltaRa),
                denominator,
            )
            hourAngle = normalizeDegrees(hourAngle - deltaRa)
        }

        val latitude = observer.latitudeDeg
        val altitude = asinDeg(
            sinDeg(latitude) * sinDeg(declination) +
                cosDeg(latitude) * cosDeg(declination) * cosDeg(hourAngle),
        )
        // Meeus' azimuth is measured westward from south; shift it to north-based.
        val azimuthFromSouth = atan2Deg(
            sinDeg(hourAngle),
            cosDeg(hourAngle) * sinDeg(latitude) - tanDeg(declination) * cosDeg(latitude),
        )
        val azimuth = normalizeDegrees(azimuthFromSouth + 180.0)

        val parallactic = atan2Deg(
            sinDeg(hourAngle),
            tanDeg(latitude) * cosDeg(declination) - sinDeg(declination) * cosDeg(hourAngle),
        )

        return Horizontal(
            azimuthDeg = azimuth,
            altitudeDeg = altitude,
            apparentAltitudeDeg = Refraction.apparent(altitude),
            parallacticAngleDeg = parallactic,
            hourAngleDeg = hourAngle,
            topocentric = Equatorial(
                rightAscensionDeg = normalizeDegrees(localSiderealTime - hourAngle),
                declinationDeg = declination,
                distanceKm = equatorial.distanceKm,
            ),
        )
    }

    /** Ecliptic coordinates of date -> equatorial coordinates of date (Meeus, eq. 13.3/13.4). */
    fun eclipticToEquatorial(ecliptic: Ecliptic, obliquityDeg: Double): Equatorial {
        val lambda = ecliptic.longitudeDeg
        val beta = ecliptic.latitudeDeg
        val rightAscension = atan2Deg(
            sinDeg(lambda) * cosDeg(obliquityDeg) - tanDeg(beta) * sinDeg(obliquityDeg),
            cosDeg(lambda),
        )
        val declination = asinDeg(
            sinDeg(beta) * cosDeg(obliquityDeg) +
                cosDeg(beta) * sinDeg(obliquityDeg) * sinDeg(lambda),
        )
        return Equatorial(
            rightAscensionDeg = normalizeDegrees(rightAscension),
            declinationDeg = declination,
            distanceKm = ecliptic.distanceKm,
        )
    }

    /** Angular separation between two equatorial positions, in degrees. */
    fun angularSeparation(a: Equatorial, b: Equatorial): Double {
        val cosDistance = sinDeg(a.declinationDeg) * sinDeg(b.declinationDeg) +
            cosDeg(a.declinationDeg) * cosDeg(b.declinationDeg) *
            cosDeg(a.rightAscensionDeg - b.rightAscensionDeg)
        return kotlin.math.acos(cosDistance.coerceIn(-1.0, 1.0)) * RAD_TO_DEG
    }
}
