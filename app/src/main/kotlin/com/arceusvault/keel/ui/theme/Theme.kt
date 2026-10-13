package com.arceusvault.keel.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arceusvault.keel.R

// IBM Plex throughout: Sans for headings and text, Mono for keys, paths and logs.
val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)
val PlexMono = FontFamily(Font(R.font.ibm_plex_mono_regular, FontWeight.Normal))

private val keelTypography: Typography = run {
    val base = Typography()
    fun TextStyle.head(weight: FontWeight = FontWeight.SemiBold) = copy(fontFamily = PlexSans, fontWeight = weight)
    fun TextStyle.body() = copy(fontFamily = PlexSans)
    Typography(
        displayLarge = base.displayLarge.head(), displayMedium = base.displayMedium.head(), displaySmall = base.displaySmall.head(),
        headlineLarge = base.headlineLarge.head(), headlineMedium = base.headlineMedium.head(), headlineSmall = base.headlineSmall.head(),
        titleLarge = base.titleLarge.head(), titleMedium = base.titleMedium.head().copy(fontSize = 18.sp),
        titleSmall = base.titleSmall.head().copy(fontSize = 15.sp),
        bodyLarge = base.bodyLarge.body(), bodyMedium = base.bodyMedium.body(), bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.body().copy(fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.body().copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.body().copy(fontWeight = FontWeight.Medium),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Readable text on top of a filled color. */
fun contentOn(c: Color): Color = if (c.compositeOver(Color.Black).luminance() > 0.3f) Color(0xFF0E161C) else Color.White

private fun scheme(p: Palette): ColorScheme {
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    val variant = p.altBg1.compositeOver(p.bg2)
    return base.copy(
        primary = p.primary, onPrimary = contentOn(p.primary),
        primaryContainer = p.primary.copy(alpha = 0.22f).compositeOver(p.bg2), onPrimaryContainer = p.fg1,
        secondary = p.accent.compositeOver(p.bg2), onSecondary = contentOn(p.accent.compositeOver(p.bg2)),
        secondaryContainer = p.primary.copy(alpha = 0.18f).compositeOver(p.bg2), onSecondaryContainer = p.fg1,
        tertiary = p.cyan, onTertiary = contentOn(p.cyan),
        background = p.bg1, onBackground = p.fg1,
        surface = p.bg1, onSurface = p.fg1,
        surfaceVariant = variant, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.bg1, surfaceContainerLow = p.bg2, surfaceContainer = p.bg2,
        surfaceContainerHigh = p.bg2, surfaceContainerHighest = variant,
        surfaceBright = p.bg2, surfaceDim = p.bg1,
        inverseSurface = p.fg1, inverseOnSurface = p.bg1, inversePrimary = p.primary,
        error = p.red, onError = contentOn(p.red),
        errorContainer = p.red.copy(alpha = 0.2f).compositeOver(p.bg2), onErrorContainer = p.fg1,
        outline = p.altBg2.compositeOver(p.bg2), outlineVariant = p.lines.compositeOver(p.bg2),
        scrim = Color.Black,
    )
}

val LocalPalette = staticCompositionLocalOf { Palette.Dark }

@Composable
fun KeelTheme(palette: Palette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = remember(palette) { scheme(palette) }, typography = keelTypography, shapes = shapes, content = content)
    }
}

/** Status colors from the active palette. */
object StatusColors {
    val ok: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.green
    val warn: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.orange
    val bad: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.red
    val info: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.blue
    val muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
}
