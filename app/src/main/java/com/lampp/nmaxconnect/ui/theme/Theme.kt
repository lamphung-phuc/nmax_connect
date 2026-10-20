package com.lampp.nmaxconnect.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    // Every role set: unset roles fall back to the baseline purple scheme (Snackbar, chips, fixed accents).
    surfaceVariant = SurfaceVariantLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    inversePrimary = PrimaryDark,
    primaryFixed = PrimaryContainerLight,
    primaryFixedDim = PrimaryDark,
    onPrimaryFixed = OnPrimaryContainerLight,
    onPrimaryFixedVariant = PrimaryContainerDark,
    secondaryFixed = SecondaryContainerLight,
    secondaryFixedDim = SecondaryDark,
    onSecondaryFixed = OnSecondaryContainerLight,
    onSecondaryFixedVariant = SecondaryContainerDark,
    tertiaryFixed = TertiaryContainerLight,
    tertiaryFixedDim = TertiaryDark,
    onTertiaryFixed = OnTertiaryContainerLight,
    onTertiaryFixedVariant = TertiaryContainerDark
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    // Every role set: unset roles fall back to the baseline purple scheme (Snackbar, chips, fixed accents).
    surfaceVariant = SurfaceVariantDark,
    surfaceDim = SurfaceDark,
    surfaceBright = SurfaceBrightDark,
    inverseSurface = OnSurfaceDark,
    inverseOnSurface = InverseSurfaceLight,
    inversePrimary = PrimaryLight,
    primaryFixed = PrimaryContainerLight,
    primaryFixedDim = PrimaryDark,
    onPrimaryFixed = OnPrimaryContainerLight,
    onPrimaryFixedVariant = PrimaryContainerDark,
    secondaryFixed = SecondaryContainerLight,
    secondaryFixedDim = SecondaryDark,
    onSecondaryFixed = OnSecondaryContainerLight,
    onSecondaryFixedVariant = SecondaryContainerDark,
    tertiaryFixed = TertiaryContainerLight,
    tertiaryFixedDim = TertiaryDark,
    onTertiaryFixed = OnTertiaryContainerLight,
    onTertiaryFixedVariant = TertiaryContainerDark
)

@Composable
fun NmaxConnectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    // Google Settings look in light mode: toned page, near-white cards. Cards use surfaceContainerLow everywhere.
    val colorScheme = if (darkTheme) {
        base
    } else {
        base.copy(background = base.surfaceContainer, surface = base.surfaceContainer, surfaceContainerLow = base.surfaceBright)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
