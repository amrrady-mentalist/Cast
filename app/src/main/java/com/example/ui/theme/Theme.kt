package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val BeamDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = DeepCyanContainer,
    onPrimaryContainer = OnDeepCyanContainer,
    secondary = IndigoSignal,
    onSecondary = Color.White,
    secondaryContainer = DeepIndigoContainer,
    onSecondaryContainer = OnDeepIndigoContainer,
    tertiary = BroadcastCoral,
    onTertiary = Color.White,
    tertiaryContainer = DeepCoralContainer,
    onTertiaryContainer = OnDeepCoralContainer,
    background = ObsidianBg,
    onBackground = IceWhite,
    surface = SlateSurface,
    onSurface = IceWhite,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = MutedSlate,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = ElevatedCard,
    surfaceContainerHigh = SlateSurfaceVariant,
    outline = SubtleOutline,
    outlineVariant = Color(0xFF1E293B),
    error = BroadcastCoral,
    onError = Color.White
)

private val BeamLightColorScheme = lightColorScheme(
    primary = DeepTeal,
    onPrimary = Color.White,
    primaryContainer = SoftCyanContainer,
    onPrimaryContainer = OnSoftCyanContainer,
    secondary = Color(0xFF4F46E5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF1E1B4B),
    tertiary = Color(0xFFE11D48),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE4E6),
    onTertiaryContainer = Color(0xFF4C0519),
    background = CloudBg,
    onBackground = DeepInk,
    surface = CrispWhite,
    onSurface = DeepInk,
    surfaceVariant = SoftSlateVariant,
    onSurfaceVariant = SlateInk,
    surfaceContainerLow = CrispWhite,
    surfaceContainer = Color(0xFFF8FAFC),
    surfaceContainerHigh = SoftSlateVariant,
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White
)

val BeamShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun BeamScreenTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BeamDarkColorScheme
        else -> BeamLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BeamTypography,
        shapes = BeamShapes,
        content = content
    )
}
