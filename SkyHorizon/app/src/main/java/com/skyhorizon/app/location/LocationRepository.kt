package com.skyhorizon.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** A fix obtained from the device, plus everything the UI wants to show about it. */
data class DeviceFix(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val accuracyMeters: Float?,
)

/** Failure modes the UI has to explain to the user. */
sealed interface LocationResult {
    data class Success(val fix: DeviceFix) : LocationResult
    data object PermissionDenied : LocationResult
    data object LocationDisabled : LocationResult
    data class Unavailable(val reason: String) : LocationResult
}

/**
 * Thin wrapper over [com.google.android.gms.location.FusedLocationProviderClient] plus
 * reverse geocoding. Everything suspends and nothing throws: the caller gets a
 * [LocationResult] describing what happened.
 */
class LocationRepository(private val context: Context) {

    private val fusedClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /**
     * Asks for a fresh fix and falls back to the last known position when the device
     * cannot produce one in time (indoors, airplane mode, emulator without a fix).
     */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): LocationResult {
        if (!hasLocationPermission()) return LocationResult.PermissionDenied
        if (!isLocationEnabled()) return LocationResult.LocationDisabled

        val priority = if (hasPreciseLocationPermission()) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val request = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setDurationMillis(15_000L)
            .setMaxUpdateAgeMillis(60_000L)
            .build()

        val fresh = runCatching { awaitLocation { fusedClient.getCurrentLocation(request, null) } }
            .getOrNull()
        val location = fresh
            ?: runCatching { awaitLocation { fusedClient.lastLocation } }.getOrNull()
            ?: return LocationResult.Unavailable("No position available yet - try again outdoors.")

        return LocationResult.Success(
            DeviceFix(
                latitude = location.latitude,
                longitude = location.longitude,
                elevationMeters = if (location.hasAltitude()) location.altitude else null,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            ),
        )
    }

    private suspend fun awaitLocation(
        request: () -> com.google.android.gms.tasks.Task<Location>,
    ): Location? = suspendCancellableCoroutine { continuation ->
        request()
            .addOnSuccessListener { location -> continuation.resume(location) }
            .addOnFailureListener { continuation.resume(null) }
            .addOnCanceledListener { continuation.resume(null) }
    }

    /**
     * Reverse geocodes a coordinate into a short place name. Returns null when the
     * platform geocoder is unavailable or offline - the UI then just shows coordinates.
     */
    @Suppress("DEPRECATION")
    suspend fun describe(latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            runCatching {
                val geocoder = Geocoder(context, Locale.getDefault())
                val address = geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                    ?: return@runCatching null
                listOfNotNull(
                    address.locality ?: address.subAdminArea ?: address.adminArea,
                    address.countryName,
                ).joinToString(", ").ifBlank { null }
            }.getOrNull()
        }
}
