@file:OptIn(ExperimentalMaterial3Api::class)

package com.skyhorizon.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())

/**
 * Date, time and scrubbing controls: pickers to jump anywhere in time, step buttons
 * for quick moves, and a slider that sweeps through the displayed day.
 */
@Composable
fun DateTimeControls(
    dateTime: ZonedDateTime,
    zoneId: ZoneId,
    followRealTime: Boolean,
    onDateSelected: (LocalDate) -> Unit,
    onTimeSelected: (hour: Int, minute: Int) -> Unit,
    onStepMinutes: (Long) -> Unit,
    onStepHours: (Long) -> Unit,
    onStepDays: (Long) -> Unit,
    onHourOfDayChanged: (Float) -> Unit,
    onResetToNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val hourOfDay = dateTime.hour + dateTime.minute / 60f + dateTime.second / 3600f

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = { showDatePicker = true },
                label = { Text(dateTime.format(DATE_FORMAT)) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            AssistChip(
                onClick = { showTimePicker = true },
                label = { Text(dateTime.format(TIME_FORMAT)) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Text(
                text = zoneLabel(zoneId, dateTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onResetToNow,
                enabled = !followRealTime,
            ) {
                Text("Now")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StepButton("-1d", Modifier.weight(1f)) { onStepDays(-1) }
            StepButton("-1h", Modifier.weight(1f)) { onStepHours(-1) }
            StepButton("-10m", Modifier.weight(1f)) { onStepMinutes(-10) }
            StepButton("+10m", Modifier.weight(1f)) { onStepMinutes(10) }
            StepButton("+1h", Modifier.weight(1f)) { onStepHours(1) }
            StepButton("+1d", Modifier.weight(1f)) { onStepDays(1) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "00",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.Center,
            )
            Slider(
                value = hourOfDay.coerceIn(0f, 24f),
                onValueChange = onHourOfDayChanged,
                valueRange = 0f..24f,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "24",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showDatePicker) {
        // The Material date picker works in UTC, so shift the displayed local date
        // onto the UTC timeline before handing it over and back again afterwards.
        val initialMillis = dateTime.toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        onDateSelected(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    }
                    showDatePicker = false
                }) {
                    Text("Set date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = dateTime.hour,
            initialMinute = dateTime.minute,
            is24Hour = true,
        )
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Select time", style = MaterialTheme.typography.titleMedium)
                    TimePicker(state = timePickerState)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            onTimeSelected(timePickerState.hour, timePickerState.minute)
                            showTimePicker = false
                        }) {
                            Text("Set time")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun zoneLabel(zoneId: ZoneId, dateTime: ZonedDateTime): String {
    val offset = dateTime.offset.totalSeconds / 3600.0
    val sign = if (offset < 0) "-" else "+"
    val hours = kotlin.math.abs(offset)
    val formatted = if (hours % 1.0 == 0.0) {
        String.format(Locale.US, "%d", hours.toInt())
    } else {
        String.format(Locale.US, "%.1f", hours)
    }
    val id = zoneId.id
    return if (id.startsWith("+") || id.startsWith("-") || id == "Z") {
        "UTC$sign$formatted"
    } else {
        "$id (UTC$sign$formatted)"
    }
}
