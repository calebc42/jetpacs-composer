// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui.theme

import androidx.compose.ui.graphics.Color

// Composer's palette: Emacs-purple accents (same family as the jetpacs
// Android app's EmacsPurple) over a warm parchment/ink neutral set instead
// of Material's cool grays — this tool authors org-mode text, so its chrome
// reads like paper and ink rather than a generic app shell.

val EmacsPurpleVivid = Color(0xFF622486)   // primary (light theme)
val EmacsPurpleLight = Color(0xFF7F5AB6)   // primary (dark theme) / tertiary (light theme)
val EmacsPurpleMuted = Color(0xFF624195)   // secondary (both themes)
val EmacsPurpleDeep = Color(0xFF4F3774)    // deepest — containers, onPrimaryContainer

val ParchmentLightest = Color(0xFFF2EFE4)  // background (light theme)
val ParchmentLight = Color(0xFFF0EBDE)     // surface (light theme)
val ParchmentMuted = Color(0xFFEEE7D7)     // surfaceVariant (light theme)
val ParchmentTan = Color(0xFFD4CBB6)       // secondaryContainer (light) / tertiary (dark)

val InkWarm = Color(0xFF3F3B3B)            // background (dark theme) / body text (light theme)
val InkNeutral = Color(0xFF434342)         // surface (dark theme) / secondary text (light theme)
