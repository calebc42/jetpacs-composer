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
    // A floating panel (e.g. the device preview) needs a fill that reads as
    // distinct from the flat app canvas, not just a 1dp border away from it —
    // so the ramp ends on a tan+purple tint rather than repeating a neutral.
    surfaceContainerLowest = ParchmentLightest,
    surfaceContainerLow = ParchmentLight,
    surfaceContainer = ParchmentMuted,
    surfaceContainerHigh = lerp(ParchmentMuted, ParchmentTan, 0.60f),
    surfaceContainerHighest = lerp(ParchmentTan, EmacsPurpleVivid, 0.14f),
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
    // InkWarm and InkNeutral sit only ~0.01 apart in luminance, so a divider
    // color equal to either of them is invisible against a surface painted
    // in the other. Lean on the tan instead.
    outlineVariant = lerp(ParchmentTan, InkNeutral, 0.78f),
    inverseSurface = ParchmentLightest,
    inverseOnSurface = InkWarm,
    inversePrimary = EmacsPurpleVivid,
    surfaceTint = EmacsPurpleLight,
    // The two dark neutrals (InkWarm, InkNeutral) are nearly identical in
    // luminance, so an elevation ramp built only from them would be
    // invisible too — climb toward EmacsPurpleLight instead, which is the
    // only palette color with real headroom above them.
    surfaceContainerLowest = InkWarm,
    surfaceContainerLow = InkNeutral,
    surfaceContainer = lerp(InkNeutral, EmacsPurpleLight, 0.18f),
    surfaceContainerHigh = lerp(InkNeutral, EmacsPurpleLight, 0.32f),
    surfaceContainerHighest = lerp(InkNeutral, EmacsPurpleLight, 0.46f),
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
