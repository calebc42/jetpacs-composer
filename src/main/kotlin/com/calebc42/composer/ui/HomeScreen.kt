// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.project.RecentFiles
import java.io.File

@Composable
fun HomeScreen(
    onOpen: (File) -> Unit,
    onNew: (id: String, label: String) -> Unit,
    error: String?,
) {
    val recent = remember { RecentFiles.load().recent.map(::File).filter { it.exists() } }
    var newId by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("jetpacs-composer", style = MaterialTheme.typography.headlineMedium)
        Text("Declarative CRUD apps over org files, for the Jetpacs launcher.",
             style = MaterialTheme.typography.bodyMedium)
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newId, { newId = it.lowercase() },
                              label = { Text("app id (slug)") },
                              singleLine = true, modifier = Modifier.width(200.dp))
            OutlinedTextField(newLabel, { newLabel = it },
                              label = { Text("label") },
                              singleLine = true, modifier = Modifier.width(200.dp))
            Button(
                onClick = { onNew(newId, newLabel.ifBlank { newId }) },
                enabled = AppSpec.ID_RE.matches(newId),
            ) { Text("New app") }
            OutlinedButton(onClick = {
                pickOrgFile("Open app.org")?.let(onOpen)
            }) { Text("Open…") }
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

/** A native save dialog seeded with NAME; null when cancelled. */
fun pickSaveFile(title: String, name: String): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title,
                                     java.awt.FileDialog.SAVE)
    dialog.file = name
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}
