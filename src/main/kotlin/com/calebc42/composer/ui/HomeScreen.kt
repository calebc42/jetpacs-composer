// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.project.RecentFiles
import com.calebc42.composer.project.Templates
import java.io.File

@Composable
fun HomeScreen(
    onOpen: (File) -> Unit,
    onSpec: (AppSpec) -> Unit,
    onSettings: () -> Unit,
    error: String?,
) {
    val recent = remember { RecentFiles.load().recent.map(::File).filter { it.exists() } }
    var wizard by remember { mutableStateOf(false) }
    var templateError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(48.dp).padding(end = 12.dp)) {
                Image(
                    painter = painterResource("icons/jetpacs-compose-icon-background.svg"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
                Image(
                    painter = painterResource("icons/jetpacs-composer-icon-forground.svg"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text("jetpacs-composer", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = onSettings) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings"
                )
            }
        }
        Text("Declarative CRUD apps over org files, for the Jetpacs launcher.",
             style = MaterialTheme.typography.bodyMedium)
        (error ?: templateError)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { wizard = true }) { Text("New app…") }
            OutlinedButton(onClick = {
                pickOrgFile("Open app.org")?.let(onOpen)
            }) { Text("Open…") }
            Text("Templates:", style = MaterialTheme.typography.bodyMedium)
            Templates.names.forEach { name ->
                OutlinedButton(onClick = {
                    runCatching { Templates.load(name) }
                        .onSuccess(onSpec)
                        .onFailure { templateError = "$name: ${it.message}" }
                }) { Text(name) }
            }
        }
        if (wizard) {
            NewAppWizard(onCreate = { wizard = false; onSpec(it) },
                         onDismiss = { wizard = false })
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Text("Recent", style = MaterialTheme.typography.titleMedium,
             modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(recent) { file ->
                TextButton(onClick = { onOpen(file) }) { Text(file.absolutePath) }
            }
        }
    }
}

/** A native file-open dialog; null when cancelled. */
fun pickOrgFile(title: String): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title,
                                     java.awt.FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".org") }
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}

/**
 * A native save dialog seeded with NAME, opening in DIRECTORY when given
 * (the Settings "Default App Path"); null when cancelled.
 */
fun pickSaveFile(title: String, name: String, directory: String? = null): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title,
                                     java.awt.FileDialog.SAVE)
    directory?.takeIf { it.isNotBlank() }?.let { dialog.directory = it }
    dialog.file = name
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}
