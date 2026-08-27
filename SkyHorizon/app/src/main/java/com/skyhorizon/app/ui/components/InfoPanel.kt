@file:OptIn(ExperimentalMaterial3Api::class)

package com.skyhorizon.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skyhorizon.app.astro.BodyPosition
import com.skyhorizon.app.astro.MoonPhase
import com.skyhorizon.app.ui.theme.SkyPalette
import java.util.Locale
import kotlin.math.roundToInt

/** Side-by-side readout of where the Sun and the Moon currently are. */
@Composable
fun BodySummaryRow(
    sun: BodyPosition,
    moon: BodyPosition,
    moonPhase: MoonPhase,
    twilightLabel: String,
    onCenterSun: () -> Unit,
    onCenterMoon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BodyCard(
            title = "Sun",
            accent = SkyPalette.Sun,
            body = sun,
            secondLine = twilightLabel,
            onClick = onCenterSun,
            modifier = Modifier.weight(1f),
        )
        BodyCard(
            title = "Moon",
            accent = SkyPalette.MoonLit,
            body = moon,
            secondLine = "${moonPhase.name.label} · ${moonPhase.illuminatedPercent.roundToInt()}%",
            badge = {
                MoonPhaseBadge(
                    illuminatedFraction = moonPhase.illuminatedFraction.toFloat(),
                    waxing = moonPhase.isWaxing,
                    modifier = Modifier.size(26.dp),
                )
            },
            onClick = onCenterMoon,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BodyCard(
    title: String,
    accent: Color,
    body: BodyPosition,
    secondLine: String,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                badge?.invoke()
            }
            Text(
                text = "Az ${degrees(body.azimuthDeg)}  ${compassPoint(body.azimuthDeg)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Alt ${degrees(body.apparentAltitudeDeg)}" +
                    if (body.isAboveHorizon) "" else "  (below horizon)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = secondLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Miniature rendering of the current phase, using the same terminator geometry as the sky view. */
@Composable
fun MoonPhaseBadge(
    illuminatedFraction: Float,
    waxing: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 1f
            if (radius <= 0f) return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = SkyPalette.MoonDark, radius = radius, center = center)
            drawPath(
                path = lunarLimbPath(
                    centerX = center.x,
                    centerY = center.y,
                    radius = radius,
                    illuminatedFraction = illuminatedFraction,
                    litOnRight = waxing,
                ),
                color = SkyPalette.MoonLit,
            )
            drawCircle(
                color = SkyPalette.MoonLit.copy(alpha = 0.5f),
                radius = radius,
                center = center,
                style = Stroke(width = 1f),
            )
        }
    }
}

private fun degrees(value: Double): String = String.format(Locale.US, "%.1f°", value)

/** Nearest 16-point compass name for an azimuth. */
fun compassPoint(azimuthDeg: Double): String {
    val points = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )
    val index = ((azimuthDeg % 360.0 + 360.0) % 360.0 / 22.5).roundToInt() % 16
    return points[index]
}
