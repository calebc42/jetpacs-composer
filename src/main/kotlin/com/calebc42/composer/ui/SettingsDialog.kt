// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.calebc42.composer.project.ComposerConfig
import com.calebc42.composer.project.ThemePreference

@Composable
fun SettingsDialog(
    config: ComposerConfig,
    onDismiss: () -> Unit,
    onSave: (ComposerConfig) -> Unit,
) {
    var theme by remember { mutableStateOf(config.theme) }
    var defaultAppPath by remember { mutableStateOf(config.defaultAppPath.orEmpty()) }
    var defaultExportPath by remember { mutableStateOf(config.defaultExportPath.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.width(400.dp).padding(vertical = 8.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RadioButton(selected = theme == ThemePreference.SYSTEM, onClick = { theme = ThemePreference.SYSTEM })
                    Text("System")
                    RadioButton(selected = theme == ThemePreference.LIGHT, onClick = { theme = ThemePreference.LIGHT })
                    Text("Light")
                    RadioButton(selected = theme == ThemePreference.DARK, onClick = { theme = ThemePreference.DARK })
                    Text("Dark")
                }
                
                Spacer(Modifier.height(16.dp))
                Text("Paths", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = defaultAppPath,
                    onValueChange = { defaultAppPath = it },
                    label = { Text("Default App Path (.org)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = defaultExportPath,
                    onValueChange = { defaultExportPath = it },
                    label = { Text("Default Export Path (.el)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        config.copy(
                            theme = theme,
                            defaultAppPath = defaultAppPath.ifBlank { null },
                            defaultExportPath = defaultExportPath.ifBlank { null }
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
