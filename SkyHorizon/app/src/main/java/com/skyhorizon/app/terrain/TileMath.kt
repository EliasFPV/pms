package com.skyhorizon.app.terrain

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/** Web Mercator tile arithmetic, in the 256-pixel-per-tile convention. */
internal object TileMath {

    const val TILE_SIZE = 256

    /** Continuous global pixel x for a longitude at [zoom]. */
    fun pixelX(zoom: Int, longitudeDeg: Double): Double =
        (longitudeDeg + 180.0) / 360.0 * (1 shl zoom) * TILE_SIZE

    /** Continuous global pixel y for a latitude at [zoom]. */
    fun pixelY(zoom: Int, latitudeDeg: Double): Double {
        val clamped = latitudeDeg.coerceIn(-85.05112878, 85.05112878)
        val s = sin(clamped * PI / 180.0)
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * (1 shl zoom) * TILE_SIZE
    }

    fun tileIndex(pixel: Double): Int = floor(pixel / TILE_SIZE).toInt()

    /** Wraps a tile x index so the mosaic keeps working across the date line. */
    fun wrapTileX(x: Int, zoom: Int): Int {
        val n = 1 shl zoom
        return ((x % n) + n) % n
    }

    fun clampTileY(y: Int, zoom: Int): Int = y.coerceIn(0, (1 shl zoom) - 1)
}
