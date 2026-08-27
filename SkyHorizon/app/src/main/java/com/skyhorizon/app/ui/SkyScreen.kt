@file:OptIn(ExperimentalMaterial3Api::class)

package com.skyhorizon.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skyhorizon.app.astro.SkySnapshot
import com.skyhorizon.app.ui.components.BodySummaryRow
import com.skyhorizon.app.ui.components.DateTimeControls
import com.skyhorizon.app.ui.components.MapPickerScreen
import com.skyhorizon.app.ui.components.SkyCanvas
import com.skyhorizon.app.ui.components.rememberSkyViewState
import java.util.Locale

@Composable
fun SkyHorizonApp(viewModel: SkyViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showMapPicker by remember { mutableStateOf(false) }

    if (showMapPicker) {
        MapPickerScreen(
            initialLatitude = state.latitude,
            initialLongitude = state.longitude,
            onConfirm = { latitude, longitude ->
                viewModel.setManualLocation(latitude, longitude)
                showMapPicker = false
            },
            onDismiss = { showMapPicker = false },
        )
    } else {
        SkyScreen(
            state = state,
            viewModel = viewModel,
            onOpenMap = { showMapPicker = true },
        )
    }
}

@Composable
private fun SkyScreen(
    state: SkyUiState,
    viewModel: SkyViewModel,
    onOpenMap: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val skyViewState = rememberSkyViewState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            viewModel.requestDeviceLocation()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SkyHorizon") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    Text(
                        text = state.snapshot.twilight.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SkyCanvas(
                snapshot = state.snapshot,
                track = state.track,
                viewState = skyViewState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BodySummaryRow(
                    sun = state.snapshot.sun,
                    moon = state.snapshot.moon,
                    moonPhase = state.snapshot.moonPhase,
                    twilightLabel = state.snapshot.twilight.label,
                    onCenterSun = {
                        skyViewState.centerOn(
                            state.snapshot.sun.azimuthDeg,
                            state.snapshot.sun.apparentAltitudeDeg,
                        )
                    },
                    onCenterMoon = {
                        skyViewState.centerOn(
                            state.snapshot.moon.azimuthDeg,
                            state.snapshot.moon.apparentAltitudeDeg,
                        )
                    },
                )

                DateTimeControls(
                    dateTime = state.zonedDateTime,
                    zoneId = state.zoneId,
                    followRealTime = state.followRealTime,
                    onDateSelected = viewModel::setDate,
                    onTimeSelected = viewModel::setTime,
                    onStepMinutes = viewModel::stepMinutes,
                    onStepHours = viewModel::stepHours,
                    onStepDays = viewModel::stepDays,
                    onHourOfDayChanged = viewModel::setHourOfDay,
                    onResetToNow = viewModel::resetToNow,
                )

                LocationCard(
                    state = state,
                    onRequestGps = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                    onOpenMap = onOpenMap,
                    onToggleZone = viewModel::toggleZoneMode,
                    onResetView = skyViewState::reset,
                )

                DetailCard(state.snapshot)
            }
        }
    }
}

@Composable
private fun LocationCard(
    state: SkyUiState,
    onRequestGps: () -> Unit,
    onOpenMap: () -> Unit,
    onToggleZone: () -> Unit,
    onResetView: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                    Text(
                        text = state.locationLabel ?: "Custom position",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = coordinateText(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isLocating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRequestGps, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("  GPS", style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalButton(onClick = onOpenMap, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Filled.Place,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("  Map", style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalButton(onClick = onResetView, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("  View", style = MaterialTheme.typography.labelLarge)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZoneMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.zoneMode == mode,
                        onClick = { if (state.zoneMode != mode) onToggleZone() },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailCard(snapshot: SkySnapshot) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DetailRow("Sun right ascension", hoursMinutes(snapshot.sun.equatorial.rightAscensionDeg))
            DetailRow("Sun declination", degrees(snapshot.sun.equatorial.declinationDeg))
            DetailRow("Moon right ascension", hoursMinutes(snapshot.moon.equatorial.rightAscensionDeg))
            DetailRow("Moon declination", degrees(snapshot.moon.equatorial.declinationDeg))
            DetailRow(
                "Moon distance",
                String.format(Locale.US, "%,.0f km", snapshot.moon.distanceKm),
            )
            DetailRow(
                "Moon phase angle",
                degrees(snapshot.moonPhase.phaseAngleDeg),
            )
            DetailRow("Local sidereal time", hoursMinutes(snapshot.localSiderealTimeDeg))
            DetailRow(
                "Equation of time",
                String.format(Locale.US, "%+.1f min", snapshot.equationOfTimeMinutes),
            )
            DetailRow("Julian day", String.format(Locale.US, "%.5f", snapshot.julianDay))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun coordinateText(state: SkyUiState): String {
    val latitude = String.format(
        Locale.US,
        "%.4f°%s",
        kotlin.math.abs(state.latitude),
        if (state.latitude >= 0) "N" else "S",
    )
    val longitude = String.format(
        Locale.US,
        "%.4f°%s",
        kotlin.math.abs(state.longitude),
        if (state.longitude >= 0) "E" else "W",
    )
    val elevation = if (state.elevationMeters != 0.0) {
        String.format(Locale.US, " · %.0f m", state.elevationMeters)
    } else {
        ""
    }
    val accuracy = state.accuracyMeters?.let { String.format(Locale.US, " · ±%.0f m", it) } ?: ""
    return "$latitude  $longitude$elevation$accuracy · ${state.locationSource.label}"
}

private fun degrees(value: Double): String = String.format(Locale.US, "%+.3f°", value)

/** Formats a right ascension (or sidereal time) given in degrees as h m s. */
private fun hoursMinutes(degreesValue: Double): String {
    val hours = ((degreesValue % 360.0) + 360.0) % 360.0 / 15.0
    val h = hours.toInt()
    val minutes = (hours - h) * 60.0
    val m = minutes.toInt()
    val s = (minutes - m) * 60.0
    return String.format(Locale.US, "%02dh %02dm %04.1fs", h, m, s)
}
