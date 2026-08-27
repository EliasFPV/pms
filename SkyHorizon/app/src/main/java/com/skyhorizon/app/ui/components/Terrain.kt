package com.skyhorizon.app.ui.components

import com.skyhorizon.app.astro.normalizeDegrees
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * A skyline sampled once around the whole compass. Only integer wave frequencies are
 * used, so the profile joins seamlessly where 360 degrees wraps back to 0.
 */
internal class TerrainProfile(private val heightsDeg: FloatArray) {

    /** Elevation of the ridge, in degrees above the true horizon, at this azimuth. */
    fun heightAt(azimuthDeg: Double): Float {
        val samples = heightsDeg.size
        val position = normalizeDegrees(azimuthDeg) / 360.0 * samples
        val lower = floor(position).toInt() % samples
        val upper = (lower + 1) % samples
        val fraction = (position - floor(position)).toFloat()
        return heightsDeg[lower] * (1f - fraction) + heightsDeg[upper] * fraction
    }
}

/** One mountain range. [haze] is 0 for the nearest ridge and 1 for the most distant. */
internal class TerrainLayer(val profile: TerrainProfile, val haze: Float)

/**
 * Builds a deterministic mountain silhouette. Ridged noise over a handful of integer
 * harmonics gives sharp peaks and broad valleys while staying periodic over the
 * compass, and squaring the result deepens the valleys.
 */
internal fun buildTerrain(
    seed: Int,
    maxHeightDeg: Float,
    samples: Int = 1440,
): TerrainProfile {
    val random = Random(seed)
    val octaves = listOf(2 to 1.0f, 5 to 0.5f, 11 to 0.26f, 23 to 0.13f, 47 to 0.06f)
    val phases = FloatArray(octaves.size) { random.nextFloat() * 2f * PI.toFloat() }

    val raw = FloatArray(samples)
    for (index in 0 until samples) {
        val angle = index.toFloat() / samples * 2f * PI.toFloat()
        var value = 0f
        octaves.forEachIndexed { octave, (frequency, amplitude) ->
            // Ridged noise: folding the sine about zero turns smooth waves into peaks.
            value += amplitude * (1f - abs(sin(frequency * angle + phases[octave])))
        }
        raw[index] = value
    }

    val minimum = raw.minOrNull() ?: 0f
    val maximum = raw.maxOrNull() ?: 1f
    val span = (maximum - minimum).coerceAtLeast(1e-4f)
    for (index in raw.indices) {
        val normalized = (raw[index] - minimum) / span
        raw[index] = normalized * normalized * maxHeightDeg
    }
    return TerrainProfile(raw)
}
