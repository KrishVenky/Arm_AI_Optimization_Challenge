package com.audimus.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extended design tokens beyond Material's [androidx.compose.material3.ColorScheme], mirroring the
 * Audimus prototype palette (warm neutrals, teal shield primary, red/amber/green risk scale with
 * matching soft "container" fills). Read via [AudimusTheme.colors].
 */
data class AudimusColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryC: Color,
    val onPrimaryC: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val outline: Color,
    val outline2: Color,
    val high: Color,
    val highC: Color,
    val onHighC: Color,
    val med: Color,
    val medC: Color,
    val onMedC: Color,
    val safe: Color,
    val safeC: Color,
    val onSafeC: Color,
)

val AudimusDarkColors = AudimusColors(
    bg = Color(0xFF1A1A18),
    surface = Color(0xFF2A2A26),
    surface2 = Color(0xFF35342F),
    primary = Color(0xFF1E8A7C),
    onPrimary = Color(0xFF04231F),
    primaryC = Color(0xFF0E3B37),
    onPrimaryC = Color(0xFF93E6D8),
    text = Color(0xFFEAE7DF),
    text2 = Color(0xFFA8A498),
    text3 = Color(0xFF6E6B60),
    outline = Color(0x17FFFFFF),
    outline2 = Color(0x29FFFFFF),
    high = Color(0xFFC4432B),
    highC = Color(0xFF3A1A13),
    onHighC = Color(0xFFF6CFC5),
    med = Color(0xFFD98E2B),
    medC = Color(0xFF3A2C11),
    onMedC = Color(0xFFF3DDB4),
    safe = Color(0xFF2E7D5B),
    safeC = Color(0xFF123528),
    onSafeC = Color(0xFFBFE7D2),
)

val AudimusLightColors = AudimusColors(
    bg = Color(0xFFF5F3EE),
    surface = Color(0xFFE6E2D8),
    surface2 = Color(0xFFDAD5C8),
    primary = Color(0xFF0B4F4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryC = Color(0xFFCDE7E1),
    onPrimaryC = Color(0xFF08302C),
    text = Color(0xFF1C1B17),
    text2 = Color(0xFF5F5B50),
    text3 = Color(0xFF8C887B),
    outline = Color(0x1A1C1B17),
    outline2 = Color(0x2E1C1B17),
    high = Color(0xFFC4432B),
    highC = Color(0xFFF6DED7),
    onHighC = Color(0xFF5E1B0E),
    med = Color(0xFFB26A12),
    medC = Color(0xFFF3E4C8),
    onMedC = Color(0xFF5A3808),
    safe = Color(0xFF2E7D5B),
    safeC = Color(0xFFD9EBE0),
    onSafeC = Color(0xFF123D2A),
)

val LocalAudimusColors = staticCompositionLocalOf { AudimusDarkColors }

/** Convenience accessor mirroring `MaterialTheme`. */
object AudimusTheme {
    val colors: AudimusColors
        @Composable @ReadOnlyComposable get() = LocalAudimusColors.current
}
