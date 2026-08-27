@file:OptIn(ExperimentalMaterial3Api::class)

package com.skyhorizon.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale

/** One-time osmdroid setup: cache location and a user agent for the tile server. */
fun initialiseOsmdroid(context: Context) {
    val configuration = Configuration.getInstance()
    configuration.load(
        context,
        context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE),
    )
    configuration.userAgentValue = context.packageName
    configuration.osmdroidBasePath = context.cacheDir
    configuration.osmdroidTileCache = context.cacheDir.resolve("osmdroid-tiles")
}

/**
 * Full-screen OpenStreetMap picker. Tap anywhere or drag the pin to choose a spot;
 * the coordinate fields accept typed values for exact positions.
 */
@Composable
fun MapPickerScreen(
    initialLatitude: Double,
    initialLongitude: Double,
    onConfirm: (latitude: Double, longitude: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var latitude by remember { mutableStateOf(initialLatitude) }
    var longitude by remember { mutableStateOf(initialLongitude) }
    var latitudeText by remember { mutableStateOf(format(initialLatitude)) }
    var longitudeText by remember { mutableStateOf(format(initialLongitude)) }

    val mapView = remember {
        initialiseOsmdroid(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            setHorizontalMapRepetitionEnabled(true)
            setMinZoomLevel(2.0)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(initialLatitude, initialLongitude))
        }
    }

    val marker = remember {
        Marker(mapView).apply {
            position = GeoPoint(initialLatitude, initialLongitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isDraggable = true
            title = "Observer"
        }
    }

    // Keep the pin, the map and the text fields in step, wherever the change came from.
    fun updatePosition(newLatitude: Double, newLongitude: Double, moveMap: Boolean) {
        val clampedLatitude = newLatitude.coerceIn(-85.0, 85.0)
        val wrappedLongitude = ((newLongitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        latitude = clampedLatitude
        longitude = wrappedLongitude
        latitudeText = format(clampedLatitude)
        longitudeText = format(wrappedLongitude)
        val point = GeoPoint(clampedLatitude, wrappedLongitude)
        marker.position = point
        if (moveMap) mapView.controller.animateTo(point)
        mapView.invalidate()
    }

    DisposableEffect(mapView, marker) {
        val eventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p ?: return false
                updatePosition(p.latitude, p.longitude, moveMap = false)
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                updatePosition(p.latitude, p.longitude, moveMap = true)
                return true
            }
        }
        marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDrag(m: Marker?) {
                m?.position?.let { updatePosition(it.latitude, it.longitude, moveMap = false) }
            }

            override fun onMarkerDragEnd(m: Marker?) {
                m?.position?.let { updatePosition(it.latitude, it.longitude, moveMap = false) }
            }

            override fun onMarkerDragStart(m: Marker?) = Unit
        })
        mapView.overlays.add(MapEventsOverlay(eventsReceiver))
        mapView.overlays.add(marker)
        mapView.invalidate()

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.overlays.clear()
            mapView.onDetach()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick a location") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Tap the map or drag the pin. Map data © OpenStreetMap contributors.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = latitudeText,
                        onValueChange = { text ->
                            latitudeText = text
                            text.toDoubleOrNull()
                                ?.takeIf { it in -85.0..85.0 }
                                ?.let { updatePosition(it, longitude, moveMap = true) }
                        },
                        label = { Text("Latitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = longitudeText,
                        onValueChange = { text ->
                            longitudeText = text
                            text.toDoubleOrNull()
                                ?.takeIf { it in -180.0..180.0 }
                                ?.let { updatePosition(latitude, it, moveMap = true) }
                        },
                        label = { Text("Longitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = { onConfirm(latitude, longitude) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Use this location")
                }
            }
        }
    }
}

private fun format(value: Double): String = String.format(Locale.US, "%.5f", value)
