package com.skyhorizon.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Named colours shared by the Material theme and the custom sky renderer. */
object SkyPalette {
    val NightZenith = Color(0xFF04060F)
    val NightHorizon = Color(0xFF141C3A)
    val AstronomicalZenith = Color(0xFF060B1E)
    val AstronomicalHorizon = Color(0xFF1B2350)
    val NauticalZenith = Color(0xFF0B1533)
    val NauticalHorizon = Color(0xFF3D3B6E)
    val CivilZenith = Color(0xFF16264F)
    val CivilHorizon = Color(0xFFC4623C)
    val GoldenZenith = Color(0xFF2A4E86)
    val GoldenHorizon = Color(0xFFF0A24A)
    val DayZenith = Color(0xFF1663C7)
    val DayHorizon = Color(0xFFA9D6F5)

    val GroundNear = Color(0xFF232028)
    val GroundFar = Color(0xFF07080C)

    // Mountain silhouettes, near (darkest) to far (hazed towards the sky).
    val RidgeNear = Color(0xFF0B0D14)
    val RidgeFar = Color(0xFF3A4562)

    val Sun = Color(0xFFFFD24A)
    val SunCore = Color(0xFFFFF6D8)
    val SunBelow = Color(0xFF8A6A22)

    val MoonLit = Color(0xFFF2F4FA)
    val MoonDark = Color(0xFF2C3245)
    val MoonBelow = Color(0xFF5A6076)

    val Grid = Color(0x33C7D4F0)
    val GridStrong = Color(0x66D6E2FA)
    val Horizon = Color(0xFFE3ECFB)
    val Label = Color(0xFFD5E0F5)
    val Star = Color(0xFFFFFFFF)
    val SunTrack = Color(0x99FFC24D)
    val MoonTrack = Color(0x99A8C4F0)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC5FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF1B4A7D),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFFFD24A),
    onSecondary = Color(0xFF3D2E00),
    secondaryContainer = Color(0xFF574400),
    onSecondaryContainer = Color(0xFFFFE08A),
    tertiary = Color(0xFFC9C3EA),
    background = Color(0xFF07091A),
    onBackground = Color(0xFFE3E6F2),
    surface = Color(0xFF0D1128),
    onSurface = Color(0xFFE3E6F2),
    surfaceVariant = Color(0xFF1A2040),
    onSurfaceVariant = Color(0xFFBFC7DE),
    outline = Color(0xFF5C6584),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5FA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF775A00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE08A),
    onSecondaryContainer = Color(0xFF251A00),
    background = Color(0xFFF7F9FF),
    onBackground = Color(0xFF191C22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C22),
    surfaceVariant = Color(0xFFDFE3EF),
    onSurfaceVariant = Color(0xFF43485A),
)

private val SkyTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun SkyHorizonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SkyTypography,
        content = content,
    )
}
