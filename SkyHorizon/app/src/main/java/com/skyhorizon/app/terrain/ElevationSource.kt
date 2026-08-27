package com.skyhorizon.app.terrain

/**
 * Ground elevation in metres above sea level at a geographic position.
 *
 * Kept as an interface so the horizon maths can be exercised against synthetic
 * landscapes with a known answer, without touching the network.
 */
fun interface ElevationSource {
    fun elevationAt(latitudeDeg: Double, longitudeDeg: Double): Float
}

/**
 * A square of decoded elevation samples covering one zoom level, sampled bilinearly.
 * Elevations are held as metres in a [ShortArray]: the tiles carry a 1/256 m
 * fraction that no horizon silhouette can resolve, and whole metres keep a large
 * mosaic down to a couple of megabytes.
 */
internal class ElevationMosaic(
    private val zoom: Int,
    private val originPixelX: Int,
    private val originPixelY: Int,
    private val widthPx: Int,
    private val heightPx: Int,
    private val samples: ShortArray,
) : ElevationSource {

    override fun elevationAt(latitudeDeg: Double, longitudeDeg: Double): Float {
        val globalX = TileMath.pixelX(zoom, longitudeDeg) - originPixelX
        val globalY = TileMath.pixelY(zoom, latitudeDeg) - originPixelY
        val x = globalX.coerceIn(0.0, widthPx - 1.001)
        val y = globalY.coerceIn(0.0, heightPx - 1.001)

        val x0 = x.toInt()
        val y0 = y.toInt()
        val tx = (x - x0).toFloat()
        val ty = (y - y0).toFloat()
        val row0 = y0 * widthPx
        val row1 = row0 + widthPx

        val topLeft = samples[row0 + x0].toFloat()
        val topRight = samples[row0 + x0 + 1].toFloat()
        val bottomLeft = samples[row1 + x0].toFloat()
        val bottomRight = samples[row1 + x0 + 1].toFloat()

        return topLeft * (1 - tx) * (1 - ty) +
            topRight * tx * (1 - ty) +
            bottomLeft * (1 - tx) * ty +
            bottomRight * tx * ty
    }
}

/**
 * Uses the fine mosaic for everything inside [nearRadiusMeters] of the observer and
 * the coarse one beyond it, which is what keeps the download to a couple of dozen
 * tiles without flattening nearby ridges.
 */
internal class TieredElevationSource(
    private val near: ElevationMosaic,
    private val far: ElevationMosaic,
    private val observerLatitude: Double,
    private val observerLongitude: Double,
    private val nearRadiusMeters: Double,
) : ElevationSource {

    private val nearRadiusDegreesSquared: Double = run {
        val degrees = nearRadiusMeters / HorizonCalculator.METERS_PER_DEGREE
        degrees * degrees
    }
    private val longitudeScale = kotlin.math.cos(observerLatitude * PI_OVER_180)

    override fun elevationAt(latitudeDeg: Double, longitudeDeg: Double): Float {
        val dLat = latitudeDeg - observerLatitude
        val dLon = (longitudeDeg - observerLongitude) * longitudeScale
        return if (dLat * dLat + dLon * dLon <= nearRadiusDegreesSquared) {
            near.elevationAt(latitudeDeg, longitudeDeg)
        } else {
            far.elevationAt(latitudeDeg, longitudeDeg)
        }
    }

    private companion object {
        const val PI_OVER_180 = Math.PI / 180.0
    }
}
