package com.skyhorizon.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skyhorizon.app.astro.Observer
import com.skyhorizon.app.astro.SkyEngine
import com.skyhorizon.app.astro.SkySnapshot
import com.skyhorizon.app.astro.TrackSample
import com.skyhorizon.app.location.LocationRepository
import com.skyhorizon.app.location.LocationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Where the active coordinates came from. */
enum class LocationSource(val label: String) {
    DEFAULT("Default"),
    GPS("Device GPS"),
    MAP("Picked on map"),
}

/** Which clock the date/time controls and readouts use. */
enum class ZoneMode(val label: String) {
    DEVICE("Device time"),
    LOCATION("Location time"),
}

data class SkyUiState(
    val epochMillis: Long,
    val followRealTime: Boolean,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double,
    val locationLabel: String?,
    val accuracyMeters: Float?,
    val locationSource: LocationSource,
    val zoneMode: ZoneMode,
    val snapshot: SkySnapshot,
    val track: List<TrackSample>,
    val isLocating: Boolean,
    val message: String?,
) {
    val observer: Observer get() = Observer(latitude, longitude, elevationMeters)

    /** The time zone used for every human-readable date and time in the UI. */
    val zoneId: ZoneId
        get() = when (zoneMode) {
            ZoneMode.DEVICE -> ZoneId.systemDefault()
            // No time-zone database lookup is bundled, so fall back to the mean solar
            // offset of the chosen meridian. It is exact for the Sun's hour angle and
            // within an hour of civil time nearly everywhere.
            ZoneMode.LOCATION -> ZoneOffset.ofTotalSeconds(
                ((longitude / 15.0).roundToInt() * 3600).coerceIn(-12 * 3600, 14 * 3600),
            )
        }

    val zonedDateTime: ZonedDateTime
        get() = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
}

class SkyViewModel(application: Application) : AndroidViewModel(application) {

    private val locationRepository = LocationRepository(application)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SkyUiState> = _state.asStateFlow()

    private var tickerJob: Job? = null
    private var trackJob: Job? = null
    private var geocodeJob: Job? = null

    init {
        startTicker()
        recomputeTrack()
    }

    private fun initialState(): SkyUiState {
        // Greenwich: a neutral starting point until the user grants GPS or picks a spot.
        val latitude = 51.4779
        val longitude = -0.0015
        val now = System.currentTimeMillis()
        return SkyUiState(
            epochMillis = now,
            followRealTime = true,
            latitude = latitude,
            longitude = longitude,
            elevationMeters = 0.0,
            locationLabel = "Royal Observatory, Greenwich",
            accuracyMeters = null,
            locationSource = LocationSource.DEFAULT,
            zoneMode = ZoneMode.DEVICE,
            snapshot = SkyEngine.snapshot(Observer(latitude, longitude), now),
            track = emptyList(),
            isLocating = false,
            message = null,
        )
    }

    // --- Time -----------------------------------------------------------------

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                if (_state.value.followRealTime) {
                    setEpochMillis(System.currentTimeMillis(), keepFollowing = true)
                }
            }
        }
    }

    fun resetToNow() = setEpochMillis(System.currentTimeMillis(), keepFollowing = true)

    fun stepMinutes(minutes: Long) = shiftBy(minutes * 60_000L)

    fun stepHours(hours: Long) = shiftBy(hours * 3_600_000L)

    fun stepDays(days: Long) {
        val current = _state.value
        val shifted = current.zonedDateTime.plusDays(days)
        setEpochMillis(shifted.toInstant().toEpochMilli(), keepFollowing = false)
    }

    private fun shiftBy(deltaMillis: Long) =
        setEpochMillis(_state.value.epochMillis + deltaMillis, keepFollowing = false)

    /** Moves within the currently displayed day; [hourOfDay] may carry a fraction. */
    fun setHourOfDay(hourOfDay: Float) {
        val current = _state.value
        val startOfDay = current.zonedDateTime.toLocalDate().atStartOfDay(current.zoneId)
        val millis = startOfDay.toInstant().toEpochMilli() +
            (hourOfDay.toDouble() * 3_600_000.0).toLong()
        setEpochMillis(millis, keepFollowing = false)
    }

    fun setDate(date: LocalDate) {
        val current = _state.value
        val time = current.zonedDateTime.toLocalTime()
        val moved = ZonedDateTime.of(LocalDateTime.of(date, time), current.zoneId)
        setEpochMillis(moved.toInstant().toEpochMilli(), keepFollowing = false)
    }

    fun setTime(hour: Int, minute: Int) {
        val current = _state.value
        val date = current.zonedDateTime.toLocalDate()
        val moved = ZonedDateTime.of(
            LocalDateTime.of(date, LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))),
            current.zoneId,
        )
        setEpochMillis(moved.toInstant().toEpochMilli(), keepFollowing = false)
    }

    fun toggleZoneMode() {
        _state.update { it.copy(zoneMode = if (it.zoneMode == ZoneMode.DEVICE) ZoneMode.LOCATION else ZoneMode.DEVICE) }
        recomputeTrack()
    }

    private fun setEpochMillis(epochMillis: Long, keepFollowing: Boolean) {
        val previousDay = _state.value.zonedDateTime.toLocalDate()
        _state.update { current ->
            current.copy(
                epochMillis = epochMillis,
                followRealTime = keepFollowing,
                snapshot = SkyEngine.snapshot(current.observer, epochMillis),
                message = null,
            )
        }
        // The daily arc only has to be rebuilt when the displayed calendar day changes.
        if (_state.value.zonedDateTime.toLocalDate() != previousDay) {
            recomputeTrack()
        }
    }

    // --- Location -------------------------------------------------------------

    fun setManualLocation(latitude: Double, longitude: Double, label: String? = null) {
        applyLocation(
            latitude = latitude,
            longitude = longitude,
            elevationMeters = 0.0,
            accuracyMeters = null,
            label = label,
            source = LocationSource.MAP,
        )
        geocodeIfPossible(latitude, longitude, overwriteLabel = label == null)
    }

    fun requestDeviceLocation() {
        if (_state.value.isLocating) return
        _state.update { it.copy(isLocating = true, message = null) }
        viewModelScope.launch {
            when (val result = locationRepository.currentLocation()) {
                is LocationResult.Success -> {
                    applyLocation(
                        latitude = result.fix.latitude,
                        longitude = result.fix.longitude,
                        elevationMeters = result.fix.elevationMeters ?: 0.0,
                        accuracyMeters = result.fix.accuracyMeters,
                        label = null,
                        source = LocationSource.GPS,
                    )
                    geocodeIfPossible(result.fix.latitude, result.fix.longitude, overwriteLabel = true)
                }

                LocationResult.PermissionDenied -> _state.update {
                    it.copy(
                        isLocating = false,
                        message = "Location permission is required for GPS mode.",
                    )
                }

                LocationResult.LocationDisabled -> _state.update {
                    it.copy(
                        isLocating = false,
                        message = "Turn on location services to use GPS mode.",
                    )
                }

                is LocationResult.Unavailable -> _state.update {
                    it.copy(isLocating = false, message = result.reason)
                }
            }
        }
    }

    fun setElevation(meters: Double) {
        _state.update { current ->
            current.copy(
                elevationMeters = meters.coerceIn(-500.0, 9000.0),
                snapshot = SkyEngine.snapshot(
                    current.observer.copy(elevationMeters = meters),
                    current.epochMillis,
                ),
            )
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun applyLocation(
        latitude: Double,
        longitude: Double,
        elevationMeters: Double,
        accuracyMeters: Float?,
        label: String?,
        source: LocationSource,
    ) {
        _state.update { current ->
            val observer = Observer(latitude, longitude, elevationMeters)
            current.copy(
                latitude = latitude,
                longitude = longitude,
                elevationMeters = elevationMeters,
                locationLabel = label,
                accuracyMeters = accuracyMeters,
                locationSource = source,
                isLocating = false,
                message = null,
                snapshot = SkyEngine.snapshot(observer, current.epochMillis),
            )
        }
        recomputeTrack()
    }

    private fun geocodeIfPossible(latitude: Double, longitude: Double, overwriteLabel: Boolean) {
        if (!overwriteLabel) return
        geocodeJob?.cancel()
        geocodeJob = viewModelScope.launch {
            val name = locationRepository.describe(latitude, longitude)
            if (name != null) {
                _state.update { current ->
                    if (current.latitude == latitude && current.longitude == longitude) {
                        current.copy(locationLabel = name)
                    } else {
                        current
                    }
                }
            }
        }
    }

    // --- Daily track ----------------------------------------------------------

    /** Recomputes the arc both bodies trace across the displayed day. */
    private fun recomputeTrack() {
        trackJob?.cancel()
        val snapshotState = _state.value
        trackJob = viewModelScope.launch {
            val startOfDay = snapshotState.zonedDateTime
                .toLocalDate()
                .atStartOfDay(snapshotState.zoneId)
                .toInstant()
                .toEpochMilli()
            val samples = withContext(Dispatchers.Default) {
                SkyEngine.track(
                    observer = snapshotState.observer,
                    startMillis = startOfDay,
                    durationMillis = 24 * 3_600_000L,
                    samples = 145,
                )
            }
            _state.update { it.copy(track = samples) }
        }
    }
}
