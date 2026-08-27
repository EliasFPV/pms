package com.skyhorizon.app.terrain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The skyline an observer actually sees, sampled once around the compass.
 *
 * [anglesDeg] holds the elevation angle of the highest terrain in each direction and
 * [distancesMeters] how far away that terrain is, which is what lets the renderer
 * fade distant ridges into the haze.
 */
class HorizonProfile(
    private val anglesDeg: FloatArray,
    private val distancesMeters: FloatArray,
    /** Ground elevation under the observer, from the elevation model. */
    val observerElevationMeters: Double,
) {
    val sampleCount: Int get() = anglesDeg.size

    val minAngleDeg: Float get() = anglesDeg.min()
    val maxAngleDeg: Float get() = anglesDeg.max()

    /** Elevation angle of the skyline at this azimuth, interpolated across samples. */
    fun angleAt(azimuthDeg: Double): Float = interpolate(anglesDeg, azimuthDeg)

    /** Distance to the terrain forming the skyline at this azimuth, in metres. */
    fun distanceAt(azimuthDeg: Double): Float = interpolate(distancesMeters, azimuthDeg)

    private fun interpolate(values: FloatArray, azimuthDeg: Double): Float {
        val count = values.size
        val normalized = ((azimuthDeg % 360.0) + 360.0) % 360.0
        val position = normalized / 360.0 * count
        val lower = floor(position).toInt() % count
        val upper = (lower + 1) % count
        val fraction = (position - floor(position)).toFloat()
        return values[lower] * (1f - fraction) + values[upper] * fraction
    }
}

/**
 * Ray-casts a horizon out of an elevation model: for every azimuth it walks outwards
 * along the ground and keeps the highest apparent elevation angle, which is exactly
 * the ridge a person standing there would see against the sky.
 */
object HorizonCalculator {

    /** Mean Earth radius, metres. */
    const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Effective radius for standard atmospheric refraction (k = 0.13). Light bends
     * slightly downwards with the Earth's curve, so distant terrain sits a little
     * higher than pure geometry predicts.
     */
    const val REFRACTED_RADIUS_M = EARTH_RADIUS_M * 7.0 / 6.0

    const val METERS_PER_DEGREE = EARTH_RADIUS_M * PI / 180.0

    /** Height of the observer's eye above the ground, metres. */
    const val EYE_HEIGHT_M = 1.7

    const val DEFAULT_SAMPLE_COUNT = 1440
    const val DEFAULT_MAX_RANGE_M = 70_000.0

    /**
     * @param sampleCount number of azimuths; the default gives one sample per quarter
     *   degree, a few pixels on a phone at the app's normal field of view.
     * @param maxRangeMeters how far to look. Beyond about 70 km the Earth's curve
     *   hides everything but the very largest peaks.
     */
    fun compute(
        latitudeDeg: Double,
        longitudeDeg: Double,
        source: ElevationSource,
        sampleCount: Int = DEFAULT_SAMPLE_COUNT,
        maxRangeMeters: Double = DEFAULT_MAX_RANGE_M,
        eyeHeightMeters: Double = EYE_HEIGHT_M,
    ): HorizonProfile {
        val groundElevation = source.elevationAt(latitudeDeg, longitudeDeg).toDouble()
        val eyeElevation = groundElevation + eyeHeightMeters

        val ranges = buildRanges(maxRangeMeters)
        // Terrain this far out is pulled below the horizon by the Earth's curvature.
        val curvatureDrop = DoubleArray(ranges.size) { index ->
            val d = ranges[index]
            d * d / (2.0 * REFRACTED_RADIUS_M)
        }

        val angles = FloatArray(sampleCount)
        val distances = FloatArray(sampleCount)
        val longitudeScale = max(cos(latitudeDeg * PI / 180.0), 1e-6)

        for (index in 0 until sampleCount) {
            val bearing = index * 2.0 * PI / sampleCount
            // Local flat-Earth step: over tens of kilometres the error is far smaller
            // than the elevation model's own resolution.
            val latStep = cos(bearing) / METERS_PER_DEGREE
            val lonStep = sin(bearing) / (METERS_PER_DEGREE * longitudeScale)

            var bestAngle = -Float.MAX_VALUE
            var bestDistance = 0f

            for (step in ranges.indices) {
                val distance = ranges[step]
                val elevation = source.elevationAt(
                    latitudeDeg + latStep * distance,
                    longitudeDeg + lonStep * distance,
                ).toDouble()
                val rise = elevation - eyeElevation - curvatureDrop[step]
                val angle = atan2(rise, distance).toFloat()
                if (angle > bestAngle) {
                    bestAngle = angle
                    bestDistance = distance.toFloat()
                }
            }

            angles[index] = (bestAngle * 180.0 / PI).toFloat()
            distances[index] = bestDistance
        }

        return HorizonProfile(angles, distances, groundElevation)
    }

    /**
     * Distances to sample along each ray: close together nearby, where a metre of
     * terrain covers a lot of sky, and progressively wider out towards the limit.
     */
    private fun buildRanges(maxRangeMeters: Double): DoubleArray {
        val ranges = ArrayList<Double>(1024)
        var distance = 20.0
        while (distance < maxRangeMeters) {
            ranges.add(distance)
            distance += max(20.0, distance / 110.0)
        }
        return ranges.toDoubleArray()
    }
}
