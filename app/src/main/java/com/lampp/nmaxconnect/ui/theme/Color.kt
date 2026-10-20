package com.lampp.nmaxconnect.ui.theme

import androidx.compose.ui.graphics.Color

/** Congestion on route lines (Google Maps convention). Map layers only: no M3 role means "traffic". */
object TrafficColors {
    val Moderate = Color(0xFFF29900)
    val Heavy = Color(0xFFD93025)
    val Severe = Color(0xFF8E1F14)
}

// Google Pixel / Material 3 Static Baseline Palette (Fallback for API < 31)
// Never reference these directly in screens; always use MaterialTheme.colorScheme.*

// Light Tonal Palette
val PrimaryLight = Color(0xFF0B57D0)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFD3E3FD)
val OnPrimaryContainerLight = Color(0xFF041E49)

val SecondaryLight = Color(0xFF00639B)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFC2E7FF)
val OnSecondaryContainerLight = Color(0xFF001D35)

val TertiaryLight = Color(0xFF146C2E)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFC4EED0)
val OnTertiaryContainerLight = Color(0xFF072711)

val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFF9DEDC)
val OnErrorContainerLight = Color(0xFF410E0B)

val SurfaceLight = Color(0xFFF8F9FA)
val OnSurfaceLight = Color(0xFF1F1F1F)
val OnSurfaceVariantLight = Color(0xFF444746)
val OutlineLight = Color(0xFF747775)
val OutlineVariantLight = Color(0xFFC4C7C5)

// Tonal Surface Containers (Light) - Used instead of shadow elevation
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF3F5F7)
val SurfaceContainerLight = Color(0xFFEEF0F2)
val SurfaceContainerHighLight = Color(0xFFE8EAED)
val SurfaceContainerHighestLight = Color(0xFFE1E3E6)

val SurfaceVariantLight = Color(0xFFE1E3E1)
val SurfaceDimLight = Color(0xFFD9DADC)
val InverseSurfaceLight = Color(0xFF303030)
val InverseOnSurfaceLight = Color(0xFFF2F2F2)

// Dark Tonal Palette (Stock Pixel Charcoal - NOT pure #000000)
val PrimaryDark = Color(0xFFA8C7FA)
val OnPrimaryDark = Color(0xFF062E6F)
val PrimaryContainerDark = Color(0xFF0842A0)
val OnPrimaryContainerDark = Color(0xFFD3E3FD)

val SecondaryDark = Color(0xFF7FCFFF)
val OnSecondaryDark = Color(0xFF003355)
val SecondaryContainerDark = Color(0xFF004A77)
val OnSecondaryContainerDark = Color(0xFFC2E7FF)

val TertiaryDark = Color(0xFF6DD58C)
val OnTertiaryDark = Color(0xFF0A3818)
val TertiaryContainerDark = Color(0xFF0F5223)
val OnTertiaryContainerDark = Color(0xFFC4EED0)

val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)
val ErrorContainerDark = Color(0xFF8C1D18)
val OnErrorContainerDark = Color(0xFFF9DEDC)

val SurfaceDark = Color(0xFF131314)
val OnSurfaceDark = Color(0xFFE3E3E3)
val OnSurfaceVariantDark = Color(0xFFC4C7C5)
val OutlineDark = Color(0xFF8E918F)
val OutlineVariantDark = Color(0xFF444746)

// Tonal Surface Containers (Dark) - Used instead of shadow elevation
val SurfaceContainerLowestDark = Color(0xFF0E0E0F)
val SurfaceContainerLowDark = Color(0xFF1B1B1C)
val SurfaceContainerDark = Color(0xFF1F1F20)
val SurfaceContainerHighDark = Color(0xFF282829)
val SurfaceContainerHighestDark = Color(0xFF333334)

val SurfaceVariantDark = Color(0xFF444746)
val SurfaceBrightDark = Color(0xFF39393A)
