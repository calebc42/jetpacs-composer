// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.calebc42.composer.ui.EditorScreen
import com.calebc42.composer.ui.EditorSession
import com.calebc42.composer.ui.HomeScreen
import com.calebc42.composer.ui.SettingsDialog
import com.calebc42.composer.ui.UnsavedChangesDialog
import com.calebc42.composer.ui.pickSaveFile
import com.calebc42.composer.project.RecentFiles
import com.calebc42.composer.project.ComposerConfig
import com.calebc42.composer.project.ThemePreference

fun main() {
    System.setProperty("skiko.renderApi", "OPENGL")
    application {
        var appConfig by remember { mutableStateOf(RecentFiles.load()) }
        var session by remember { mutableStateOf<EditorSession?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var showExitWarning by remember { mutableStateOf(false) }

        val windowState = rememberWindowState(
            placement = if (appConfig.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = run {
                val x = appConfig.windowX
                val y = appConfig.windowY
                if (x != null && y != null) WindowPosition(x.dp, y.dp)
                else WindowPosition.Aligned(Alignment.Center)
            },
            width = appConfig.windowWidth.dp,
            height = appConfig.windowHeight.dp
        )

        fun exitWithSave() {
            val pos = windowState.position
            val finalConfig = appConfig.copy(
                windowWidth = windowState.size.width.value.toInt(),
                windowHeight = windowState.size.height.value.toInt(),
                windowX = if (pos is WindowPosition.Absolute) pos.x.value.toInt() else null,
                windowY = if (pos is WindowPosition.Absolute) pos.y.value.toInt() else null,
                windowMaximized = windowState.placement == WindowPlacement.Maximized
            )
            RecentFiles.save(finalConfig)
            exitApplication()
        }

        Window(
            onCloseRequest = {
                if (session?.dirty == true) {
                    showExitWarning = true
                } else {
                    exitWithSave()
                }
            },
            title = "jetpacs-composer",
            icon = painterResource("icons/jetpacs-composer-icon-forground.svg"),
            state = windowState,
        ) {
            val isDark = when (appConfig.theme) {
                ThemePreference.DARK -> true
                ThemePreference.LIGHT -> false
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
            }
            val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) { 
                Surface { 
                    App(
                        config = appConfig,
                        session = session,
                        error = error,
                        onSessionChange = { session = it },
                        onErrorChange = { error = it },
                        onConfigChange = { newConfig -> 
                            appConfig = newConfig
                            RecentFiles.save(newConfig)
                        }
                    ) 
                    
                    if (showExitWarning) {
                        UnsavedChangesDialog(
                            onSaveAndClose = {
                                val s = session
                                val saved = when {
                                    s == null -> true
                                    s.file != null -> { s.save(); true }
                                    else -> {
                                        val target = pickSaveFile(
                                            "Save app document", "${s.spec.id}.org",
                                            appConfig.defaultAppPath)
                                        if (target != null) { s.save(target); true } else false
                                    }
                                }
                                // A cancelled Save-As must not close and lose the doc.
                                if (saved) exitWithSave() else showExitWarning = false
                            },
                            onDiscardAndClose = { exitWithSave() },
                            onCancel = { showExitWarning = false }
                        )
                    }
                } 
            }
        }
    }
}

@Composable
private fun App(
    config: ComposerConfig,
    session: EditorSession?,
    error: String?,
    onSessionChange: (EditorSession?) -> Unit,
    onErrorChange: (String?) -> Unit,
    onConfigChange: (ComposerConfig) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    val current = session
    if (current == null) {
        HomeScreen(
            onOpen = { file ->
                EditorSession.open(file)
                    .onSuccess { onSessionChange(it); onErrorChange(null) }
                    .onFailure { onErrorChange("${file.name}: ${it.message}") }
            },
            onSpec = { spec -> onSessionChange(EditorSession.fromSpec(spec)) },
            onSettings = { showSettings = true },
            error = error,
        )
    } else {
        var showCloseWarning by remember { mutableStateOf(false) }
        
        EditorScreen(
            current,
            config = config,
            onSettings = { showSettings = true },
            onClose = {
                if (current.dirty) showCloseWarning = true
                else onSessionChange(null)
            }
        )
        
        if (showCloseWarning) {
            UnsavedChangesDialog(
                onSaveAndClose = {
                    val saved = if (current.file != null) {
                        current.save(); true
                    } else {
                        val target = pickSaveFile(
                            "Save app document", "${current.spec.id}.org",
                            config.defaultAppPath)
                        if (target != null) { current.save(target); true } else false
                    }
                    // A cancelled Save-As returns to the editor, doc intact.
                    if (saved) onSessionChange(null)
                    showCloseWarning = false
                },
                onDiscardAndClose = {
                    onSessionChange(null)
                    showCloseWarning = false
                },
                onCancel = { showCloseWarning = false }
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            config = config,
            onDismiss = { showSettings = false },
            onSave = { newConfig -> 
                onConfigChange(newConfig)
                showSettings = false
            }
        )
    }
}
