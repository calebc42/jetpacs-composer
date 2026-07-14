// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.lerp

// Container/outline tones are blended from the palette in Color.kt rather
// than hand-picked hexes, so every derived color still traces back to the
// same ten swatches.

private val LightColorScheme = lightColorScheme(
    primary = EmacsPurpleVivid,
    onPrimary = ParchmentLightest,
    primaryContainer = lerp(EmacsPurpleVivid, ParchmentLightest, 0.82f),
    onPrimaryContainer = EmacsPurpleDeep,
    secondary = EmacsPurpleMuted,
    onSecondary = ParchmentLightest,
    secondaryContainer = ParchmentTan,
    onSecondaryContainer = InkWarm,
    tertiary = EmacsPurpleLight,
    onTertiary = ParchmentLightest,
    tertiaryContainer = lerp(EmacsPurpleLight, ParchmentLightest, 0.78f),
    onTertiaryContainer = EmacsPurpleDeep,
    background = ParchmentLightest,
    onBackground = InkWarm,
    surface = ParchmentLight,
    onSurface = InkWarm,
    surfaceVariant = ParchmentMuted,
    onSurfaceVariant = InkNeutral,
    outline = lerp(ParchmentTan, InkWarm, 0.55f),
    outlineVariant = ParchmentTan,
    inverseSurface = InkWarm,
    inverseOnSurface = ParchmentLightest,
    inversePrimary = EmacsPurpleLight,
    surfaceTint = EmacsPurpleVivid,
)

private val DarkColorScheme = darkColorScheme(
    primary = EmacsPurpleLight,
    onPrimary = ParchmentLightest,
    primaryContainer = EmacsPurpleDeep,
    onPrimaryContainer = ParchmentMuted,
    secondary = EmacsPurpleMuted,
    onSecondary = ParchmentLightest,
    secondaryContainer = lerp(ParchmentTan, InkWarm, 0.70f),
    onSecondaryContainer = ParchmentMuted,
    tertiary = ParchmentTan,
    onTertiary = InkWarm,
    tertiaryContainer = lerp(ParchmentTan, InkWarm, 0.55f),
    onTertiaryContainer = ParchmentLightest,
    background = InkWarm,
    onBackground = ParchmentLightest,
    surface = InkNeutral,
    onSurface = ParchmentLightest,
    surfaceVariant = lerp(InkNeutral, EmacsPurpleDeep, 0.30f),
    onSurfaceVariant = ParchmentTan,
    outline = lerp(ParchmentTan, InkNeutral, 0.55f),
    outlineVariant = InkNeutral,
    inverseSurface = ParchmentLightest,
    inverseOnSurface = InkWarm,
    inversePrimary = EmacsPurpleVivid,
    surfaceTint = EmacsPurpleLight,
)

@Composable
fun ComposerTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content
    )
}
