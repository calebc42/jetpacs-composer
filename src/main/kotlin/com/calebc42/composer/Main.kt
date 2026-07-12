// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.calebc42.composer.ui.EditorScreen
import com.calebc42.composer.ui.EditorSession
import com.calebc42.composer.ui.HomeScreen

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "jetpacs-composer",
        state = WindowState(width = 1100.dp, height = 760.dp),
    ) {
        MaterialTheme { Surface { App() } }
    }
}

@Composable
private fun App() {
    var session by remember { mutableStateOf<EditorSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val current = session
    if (current == null) {
        HomeScreen(
            onOpen = { file ->
                EditorSession.open(file)
                    .onSuccess { session = it; error = null }
                    .onFailure { error = "${file.name}: ${it.message}" }
            },
            onSpec = { spec -> session = EditorSession.fromSpec(spec) },
            error = error,
        )
    } else {
        EditorScreen(current, onClose = { session = null })
    }
}
