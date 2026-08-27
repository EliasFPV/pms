package com.skyhorizon.app.terrain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

sealed interface HorizonResult {
    data class Ready(val profile: HorizonProfile) : HorizonResult
    data class Unavailable(val reason: String) : HorizonResult
}

/**
 * Builds the real skyline for a location out of public elevation tiles.
 *
 * Tiles come from the AWS "Terrain Tiles" open dataset in Terrarium encoding, which
 * needs no API key. Each pixel packs its elevation into the colour channels as
 * `(red * 256 + green + blue / 256) - 32768` metres.
 */
class TerrainRepository(context: Context) {

    private val cacheDir = File(context.cacheDir, "terrain").apply { mkdirs() }
    private val downloadLimit = Semaphore(FETCH_CONCURRENCY)

    private var cachedKey: String? = null
    private var cachedProfile: HorizonProfile? = null

    /**
     * Returns the horizon for a position, reusing the previous result while the
     * observer has not moved far enough for the skyline to change.
     */
    suspend fun horizonFor(latitudeDeg: Double, longitudeDeg: Double): HorizonResult {
        val key = cacheKey(latitudeDeg, longitudeDeg)
        cachedProfile?.let { if (key == cachedKey) return HorizonResult.Ready(it) }

        return try {
            withTimeout(OVERALL_TIMEOUT_MS) {
                val tiers = TIERS.map { (zoom, radius) ->
                    TieredElevationSource.Tier(
                        mosaic = buildMosaic(zoom, latitudeDeg, longitudeDeg, radius),
                        radiusMeters = radius,
                    )
                }
                val source = TieredElevationSource(
                    tiers = tiers,
                    observerLatitude = latitudeDeg,
                    observerLongitude = longitudeDeg,
                )
                val profile = withContext(Dispatchers.Default) {
                    HorizonCalculator.compute(latitudeDeg, longitudeDeg, source)
                }
                cachedKey = key
                cachedProfile = profile
                HorizonResult.Ready(profile)
            }
        } catch (timeout: TimeoutCancellationException) {
            HorizonResult.Unavailable("Timed out downloading elevation data")
        } catch (cancellation: CancellationException) {
            // The observer moved on: let the cancellation reach the caller rather than
            // reporting it as a failed download.
            throw cancellation
        } catch (error: Exception) {
            HorizonResult.Unavailable(
                error.message?.takeIf { it.isNotBlank() } ?: "Elevation data unavailable",
            )
        }
    }

    /** Rounded to about a hundred metres: closer than that the skyline is unchanged. */
    private fun cacheKey(latitudeDeg: Double, longitudeDeg: Double): String {
        val lat = (latitudeDeg * 1000).roundToInt()
        val lon = (longitudeDeg * 1000).roundToInt()
        return "$lat/$lon"
    }

    private suspend fun buildMosaic(
        preferredZoom: Int,
        latitudeDeg: Double,
        longitudeDeg: Double,
        radiusMeters: Double,
    ): ElevationMosaic {
        var zoom = preferredZoom
        var plan = planMosaic(zoom, latitudeDeg, longitudeDeg, radiusMeters)
        // Near the poles a fixed ground radius spans many more tiles; step the zoom
        // down rather than pulling hundreds of them.
        while (plan.tileCount > MAX_TILES_PER_MOSAIC && zoom > MIN_ZOOM) {
            zoom--
            plan = planMosaic(zoom, latitudeDeg, longitudeDeg, radiusMeters)
        }

        val widthPx = plan.tilesX * TileMath.TILE_SIZE
        val heightPx = plan.tilesY * TileMath.TILE_SIZE
        val samples = ShortArray(widthPx * heightPx)

        coroutineScope {
            val jobs = ArrayList<kotlinx.coroutines.Deferred<Unit>>(plan.tileCount)
            for (row in 0 until plan.tilesY) {
                for (column in 0 until plan.tilesX) {
                    jobs += async(Dispatchers.IO) {
                        val tileX = TileMath.wrapTileX(plan.tileX0 + column, zoom)
                        val tileY = TileMath.clampTileY(plan.tileY0 + row, zoom)
                        val pixels = decodeTile(zoom, tileX, tileY)
                        blit(pixels, samples, widthPx, column, row)
                    }
                }
            }
            jobs.awaitAll()
        }

        return ElevationMosaic(
            zoom = zoom,
            originPixelX = plan.tileX0 * TileMath.TILE_SIZE,
            originPixelY = plan.tileY0 * TileMath.TILE_SIZE,
            widthPx = widthPx,
            heightPx = heightPx,
            samples = samples,
        )
    }

    private class MosaicPlan(val tileX0: Int, val tileY0: Int, val tilesX: Int, val tilesY: Int) {
        val tileCount: Int get() = tilesX * tilesY
    }

    private fun planMosaic(
        zoom: Int,
        latitudeDeg: Double,
        longitudeDeg: Double,
        radiusMeters: Double,
    ): MosaicPlan {
        val latitudeSpan = radiusMeters / HorizonCalculator.METERS_PER_DEGREE
        val longitudeSpan = latitudeSpan / cos(latitudeDeg * PI / 180.0).coerceAtLeast(0.05)

        val left = TileMath.pixelX(zoom, longitudeDeg - longitudeSpan)
        val right = TileMath.pixelX(zoom, longitudeDeg + longitudeSpan)
        val top = TileMath.pixelY(zoom, latitudeDeg + latitudeSpan)
        val bottom = TileMath.pixelY(zoom, latitudeDeg - latitudeSpan)

        val tileX0 = TileMath.tileIndex(left)
        val tileY0 = TileMath.tileIndex(top)
        return MosaicPlan(
            tileX0 = tileX0,
            tileY0 = tileY0,
            tilesX = (TileMath.tileIndex(right) - tileX0 + 1).coerceAtLeast(1),
            tilesY = (TileMath.tileIndex(bottom) - tileY0 + 1).coerceAtLeast(1),
        )
    }

    private fun blit(
        tile: ShortArray,
        target: ShortArray,
        targetWidth: Int,
        column: Int,
        row: Int,
    ) {
        val size = TileMath.TILE_SIZE
        for (y in 0 until size) {
            val destination = (row * size + y) * targetWidth + column * size
            System.arraycopy(tile, y * size, target, destination, size)
        }
    }

    /** Downloads (or reads from the disk cache) one tile and unpacks it to metres. */
    private suspend fun decodeTile(zoom: Int, x: Int, y: Int): ShortArray {
        val bytes = tileBytes(zoom, x, y)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalStateException("Could not read elevation tile $zoom/$x/$y")

        val size = TileMath.TILE_SIZE
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()

        val elevations = ShortArray(size * size)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            // Whole metres are ample for a skyline; round the 1/256 m fraction.
            val meters = red * 256 + green - 32768 + if (blue >= 128) 1 else 0
            elevations[index] = meters.coerceIn(-12_000, 12_000).toShort()
        }
        return elevations
    }

    private suspend fun tileBytes(zoom: Int, x: Int, y: Int): ByteArray {
        val cached = File(cacheDir, "${zoom}_${x}_$y.png")
        if (cached.isFile && cached.length() > 0) {
            runCatching { return cached.readBytes() }
        }
        val bytes = downloadLimit.withPermit { download(zoom, x, y) }
        runCatching { cached.writeBytes(bytes) }
        return bytes
    }

    private suspend fun download(zoom: Int, x: Int, y: Int): ByteArray =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            repeat(2) {
                try {
                    val url = URL("$TILE_BASE_URL/$zoom/$x/$y.png")
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                    try {
                        if (connection.responseCode !in 200..299) {
                            throw IllegalStateException(
                                "Elevation server returned ${connection.responseCode}",
                            )
                        }
                        return@withContext connection.inputStream.use { it.readBytes() }
                    } finally {
                        connection.disconnect()
                    }
                } catch (error: Exception) {
                    lastError = error
                }
            }
            throw lastError ?: IllegalStateException("Could not fetch elevation tile")
        }

    private companion object {
        /** AWS Open Data "Terrain Tiles", Terrarium encoding. No API key required. */
        const val TILE_BASE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"
        const val USER_AGENT = "SkyHorizon/1.0 (Android)"

        /**
         * Zoom level paired with the radius it covers, finest first. Roughly 53 m per
         * sample within 15 km, 210 m to 70 km, and 850 m out to 200 km, where the
         * distant ranges that shape a wide panorama still sit above the horizon.
         * Together this is about 37 tiles, fetched once and then cached.
         */
        val TIERS = listOf(
            11 to 15_000.0,
            9 to 70_000.0,
            7 to HorizonCalculator.DEFAULT_MAX_RANGE_M,
        )
        const val MIN_ZOOM = 5

        const val MAX_TILES_PER_MOSAIC = 36
        const val FETCH_CONCURRENCY = 4
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val OVERALL_TIMEOUT_MS = 120_000L
    }
}
